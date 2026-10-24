package com.crystalgraphics.vfx.scalability;

import com.crystalgraphics.render.CgCpuMeter;
import com.crystalgraphics.render.CgFrameClock;
import com.crystalgraphics.render.stage.CgStageRenderer;
import com.crystalgraphics.settings.CgQuality;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.variable.CgVar;
import com.crystalgraphics.vfx.CgVfxTrace;

import java.util.Arrays;

/**
 * What every effect costs the CPU, against a budget a line: the measure each {@link CgVfxScalability} scales its limits
 * by as it is approached. Mirrors Unreal's global FX budget ({@code FFXBudget}): each line's time averaged over
 * {@link #HISTORY} frames, its usage that time over its budget, and an adjusted usage that rises at once and falls
 * slowly, so effects culled for the budget do not flick back on the frame after.
 *
 * <pre>{@code
 * float usage = CgVfxBudget.get().worstAdjustedUsage();         // 0 at rest, 1 at the budget, up to 2
 * float ms = CgVfxBudget.get().time(CgVfxBudget.Line.FRAME);     // the effects' frame-thread ms, averaged
 * }</pre>
 *
 * <p>Work of the engine's own is charged where it runs; a mod adding effects work outside an effect's tick, submit or
 * stage recorders charges it too:
 *
 * <pre>{@code
 * CgRenderStage.WORLD_OPAQUE.register(ORDER, CgVfxBudget.charged(MyField::record));    // a recorder, timed
 * recording.compute("mymod.field").charged(CgVfxBudget.FRAME);                           // its pass as it executes
 * long began = System.nanoTime(); stepOnWorker(); CgVfxBudget.WORKERS.add(System.nanoTime() - began);
 * }</pre>
 *
 * <ul>
 *   <li>Two lines: {@link Line#FRAME}, the frame thread, where Minecraft's client both ticks and draws, so Unreal's
 *       game and render threads are one; and {@link Line#CONCURRENT}, that and the workers together.</li>
 *   <li>The budgets are the quality tier's unless {@code crystalgraphics.vfx.budget.frameMs} or
 *       {@code .concurrentMs} says otherwise.</li>
 *   <li>One for every system, as Unreal's is one for every world: {@link #tick} acts once a host frame however many
 *       systems call it.</li>
 * </ul>
 */
public final class CgVfxBudget {

    /** What a budget is kept on. */
    public enum Line {
        /** The frame thread's effects work: updates, submits, stage recorders and the passes they record. */
        FRAME,
        /** The frame thread's and the workers' together. */
        CONCURRENT
    }

    /** The frame thread's effects work, charged by whoever does it. */
    public static final CgCpuMeter FRAME = new CgCpuMeter();
    /** Effects work on the workers ({@code CgJob.forEach}), charged by whoever splits it. */
    public static final CgCpuMeter WORKERS = new CgCpuMeter();

    /** Frames a line's time is averaged over, as {@code fx.Budget.HistoryFrames}. */
    public static final int HISTORY = 60;
    /** How fast a second the adjusted usage may fall, as {@code fx.Budget.AdjustedUsageDecayRate}. */
    public static final float DECAY = 0.1f;
    /** The highest adjusted usage, as {@code fx.Budget.AdjustedUsageMax}: one long frame never holds it up for long. */
    public static final float MAX_USAGE = 2f;

    private static final CgVar.Num FRAME_MS = CgVar.number("crystalgraphics.vfx.budget.frameMs", 0f, 0f, 1000f, CgVar.DEV,
            "CPU milliseconds a frame every effect's frame-thread work may take before the scalabilities scale down; 0 for"
                    + " the quality tier's.");
    private static final CgVar.Num CONCURRENT_MS = CgVar.number("crystalgraphics.vfx.budget.concurrentMs", 0f, 0f, 1000f,
            CgVar.DEV, "CPU milliseconds a frame every effect's work may take on the frame thread and the workers together;"
                    + " 0 for the quality tier's.");
    static final CgVar.Num FRAME_OVERRIDE = CgVar.number("crystalgraphics.vfx.budget.debug.frameTime", -1f, -1f, 1000f,
            CgVar.DEV, "At 0 or more, the frame line's measured milliseconds, to see what the scalabilities do under it.");
    static final CgVar.Num CONCURRENT_OVERRIDE = CgVar.number("crystalgraphics.vfx.budget.debug.concurrentTime", -1f, -1f,
            1000f, CgVar.DEV, "At 0 or more, the concurrent line's measured milliseconds.");

    /**
     * Each tier's frame and concurrent milliseconds, by {@link CgQuality} ordinal: Low at Unreal's 2 ms, High and Ultra
     * holding two ultimates' worth of pools at once on a desktop GPU's frame thread.
     */
    private static final float[][] TIER_MS = {{2f, 3f}, {3f, 4.5f}, {5f, 7f}, {6f, 8f}};

    private static final int FRAME_US = CgTrace.name("vfx.budget.frame-us"),
            CONCURRENT_US = CgTrace.name("vfx.budget.concurrent-us"), USAGE = CgTrace.name("vfx.budget.usage-percent");

    private static final CgVfxBudget GLOBAL = new CgVfxBudget(FRAME, WORKERS);

    private final CgCpuMeter frame, workers;
    private final long[][] history = new long[2][HISTORY];
    private final long[] sum = new long[2];
    private final float[] millis = new float[2], usage = new float[2], adjusted = new float[2];
    private int at;
    private float worst;
    private long lastFrame = Long.MIN_VALUE, lastNanos;
    private CgQuality tier = CgQuality.HIGH;

    CgVfxBudget(CgCpuMeter frame, CgCpuMeter workers) {
        this.frame = frame;
        this.workers = workers;
    }

    /** Every effect's budget. */
    public static CgVfxBudget get() {
        return GLOBAL;
    }

    /** {@code renderer} timed into {@link #FRAME} each time it records: a stage recorder of effects work. */
    public static CgStageRenderer charged(CgStageRenderer renderer) {
        return stage -> {
            long began = System.nanoTime();
            try {
                renderer.render(stage);
            } finally {
                FRAME.add(System.nanoTime() - began);
            }
        };
    }

    /**
     * Takes what the meters gathered since the last host frame and moves each line on, at {@code tier}'s budgets. Once
     * a host frame: a second call in the same frame does nothing. Render thread.
     */
    public void tick(CgQuality tier) {
        tick(CgFrameClock.frameIndex(), System.nanoTime(), tier);
    }

    void tick(long frameIndex, long nanos, CgQuality tier) {
        if (frameIndex == lastFrame) return;
        float dt = lastFrame == Long.MIN_VALUE ? 0f : (nanos - lastNanos) / 1e9f;
        lastFrame = frameIndex;
        lastNanos = nanos;
        this.tier = tier;
        long onFrame = frame.take();
        record(onFrame, onFrame + workers.take());
        line(Line.FRAME, FRAME_OVERRIDE.get(), dt);
        line(Line.CONCURRENT, CONCURRENT_OVERRIDE.get(), dt);
        worst = Math.max(adjusted[0], adjusted[1]);
        CgTrace.counter(CgVfxTrace.CHANNEL, FRAME_US, Math.round(millis[0] * 1000f));
        CgTrace.counter(CgVfxTrace.CHANNEL, CONCURRENT_US, Math.round(millis[1] * 1000f));
        CgTrace.counter(CgVfxTrace.CHANNEL, USAGE, Math.round(worst * 100f));
    }

    /**
     * Forgets every frame measured and the adjusted usage, as Unreal's {@code FFXBudget::Reset}: a new world, or a test
     * that must not inherit the last one's load. What the meters hold is dropped too. Render thread.
     */
    public void reset() {
        frame.take();
        workers.take();
        for (int l = 0; l < 2; l++) {
            Arrays.fill(history[l], 0L);
            sum[l] = 0L;
            millis[l] = usage[l] = adjusted[l] = 0f;
        }
        at = 0;
        worst = 0f;
        lastFrame = Long.MIN_VALUE;
    }

    private void record(long onFrame, long concurrent) {
        for (int l = 0; l < 2; l++) sum[l] -= history[l][at];
        history[0][at] = onFrame;
        history[1][at] = concurrent;
        sum[0] += onFrame;
        sum[1] += concurrent;
        at = (at + 1) % HISTORY;
    }

    private void line(Line line, float override, float dt) {
        int l = line.ordinal();
        // Over the whole history from the first frame, as Unreal's cycle history: a cold first frame counts a sixtieth.
        millis[l] = override >= 0f ? override : sum[l] / (float) HISTORY / 1e6f;
        usage[l] = millis[l] / budget(line);
        // Falls at the decay rate alone; a higher usage is taken at once.
        float next = Math.max(usage[l], adjusted[l] - DECAY * dt);
        adjusted[l] = Math.min(MAX_USAGE, next);
    }

    /** {@code line}'s milliseconds a frame, averaged over the last {@link #HISTORY} frames, those before the first as 0. */
    public float time(Line line) {
        return millis[line.ordinal()];
    }

    /** {@code line}'s budget in milliseconds at the tier of the last tick. */
    public float budget(Line line) {
        float set = (line == Line.FRAME ? FRAME_MS : CONCURRENT_MS).get();
        return set > 0f ? set : TIER_MS[tier.ordinal()][line.ordinal()];
    }

    /** {@code line}'s time over its budget. */
    public float usage(Line line) {
        return usage[line.ordinal()];
    }

    /** {@code line}'s usage as the scalabilities read it: up at once, down by {@link #DECAY} a second, at most 2. */
    public float adjustedUsage(Line line) {
        return adjusted[line.ordinal()];
    }

    /** The higher line's adjusted usage: what every scalability scales by. */
    public float worstAdjustedUsage() {
        return worst;
    }
}
