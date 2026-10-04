package com.crystalgraphics.render;

import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.trace.CgGpuTrace;

import java.util.Arrays;

/**
 * A share of the GPU's frame for one consumer (an effect system, a simulation) and the scale its work runs at to stay
 * inside it. The passes charged to it are timed on the GPU; the scale drops at once when they run over and rises slowly
 * after a run well under. A consumer multiplies what it spawns or simulates by {@link #scale()}, so an effect written on
 * a fast GPU thins itself on a weak one without naming devices. Unreal's dynamic resolution heuristic is the
 * controller's model, Niagara's scalability its use.
 *
 * <pre>{@code
 * static final CgGpuBudget SPARKS = CgGpuBudget.define("sparks", 1.5f);   // 1.5 ms of GPU a frame
 *
 * CgComputePass step = frame.recording().compute("sparks.step").timed(SPARKS);   // charged to it
 * int spawned = Math.round(wanted * SPARKS.scale());
 * }</pre>
 *
 * <pre>{@code
 * SPARKS.millis(0.75f);          // a setting changed: the scale drops at once and rises in steps
 * SPARKS.floor(0.25f);           // never below a quarter, whatever it costs
 * float spent = SPARKS.spent();  // ms a frame, smoothed: NaN until measured
 * }</pre>
 *
 * <ul>
 *   <li>It starts at the tier's share, 1 where kernels run as compute, 0.5 lowered and 0.25 on the CPU tier, then
 *       follows what it measures. Where the context has no timer queries it stays there.</li>
 *   <li>Charge every pass the consumer records, raster passes too ({@code CgRasterPass.timed}), or the scale holds the
 *       wrong time. An async pass on a device with a compute queue runs beside the timer and is not counted.</li>
 *   <li>What it measures lags the work by a few frames, and it never waits for it.</li>
 *   <li>Defined once per consumer, on any thread; {@link #tick()} is the engine's.</li>
 * </ul>
 */
public final class CgGpuBudget {

    private static final float FLOOR = 0.1f;
    private static volatile CgGpuBudget[] budgets = new CgGpuBudget[0];

    private final String name;
    private final int slot, zone;
    private final Controller controller = new Controller();
    private volatile float millis, floor = FLOOR, scale = 1f, spent = Float.NaN;
    private boolean started;
    private long seenThrough, seenNanos;

    private CgGpuBudget(String name, float millis) {
        this.name = name;
        this.millis = millis;
        this.slot = CgGpuTrace.budgetSlot();
        this.zone = CgGpuTrace.name("budget." + name);
    }

    /** A consumer's budget of {@code millis} of GPU a frame. Any thread. */
    public static synchronized CgGpuBudget define(String name, float millis) {
        for (CgGpuBudget b : budgets) {
            if (b.name.equals(name)) throw new IllegalArgumentException("a GPU budget named " + name + " exists");
        }
        CgGpuBudget budget = new CgGpuBudget(name, positive(millis));
        CgGpuBudget[] next = Arrays.copyOf(budgets, budgets.length + 1);
        next[budgets.length] = budget;
        budgets = next;
        return budget;
    }

    public String name() {
        return name;
    }

    /** The GPU milliseconds a frame it holds its passes under. */
    public float millis() {
        return millis;
    }

    public CgGpuBudget millis(float millis) {
        this.millis = positive(millis);
        return this;
    }

    /** The lowest scale it goes to, 0.1 unless set. */
    public CgGpuBudget floor(float floor) {
        if (!(floor > 0f && floor <= 1f)) throw new IllegalArgumentException("a floor of " + floor);
        this.floor = floor;
        return this;
    }

    /** What to multiply the consumer's work by, in [floor, 1]. Any thread. */
    public float scale() {
        return scale;
    }

    /** Its passes' GPU milliseconds a frame: the median of the last few frames measured, NaN before any. */
    public float spent() {
        return spent;
    }

    /** The slot its passes' GPU time adds up in ({@code CgGpuTrace.begin(zone, slot)}). */
    public int slot() {
        return slot;
    }

    /** The zone a pass charged to it is timed under when it names none. */
    public int zone() {
        return zone;
    }

    /**
     * Once a frame on the render thread, from {@code CgGraphicsLifecycle.tickFrame}: each budget takes the frames that
     * came back whole, then the next budget frame starts.
     */
    public static void tick() {
        CgGpuBudget[] all = budgets;
        if (all.length == 0) return;
        CgGpuTrace.collect();
        boolean timed = CgGpuTrace.support() != CgGpuTrace.Support.UNSUPPORTED;
        long next = CgGpuTrace.budgetFrame() + 1;   // the first frame recorded at a scale set now
        for (CgGpuBudget b : all) b.update(timed, next);
        CgGpuTrace.nextBudgetFrame();
    }

    private void update(boolean timed, long next) {
        long through = CgGpuTrace.budgetThrough(slot), nanos = CgGpuTrace.budgetNanos(slot);
        if (!started) {
            started = true;
            scale = controller.start(share(CgCapabilities.detect().computeTier()), next);
        } else if (timed && through > seenThrough) {
            // The frames that came back together share their total: a frame's own figure is not kept apart.
            float each = (nanos - seenNanos) / 1e6f / (through - seenThrough);
            for (long f = Math.max(seenThrough, through - Controller.WINDOW); f < through; f++) {
                scale = controller.sample(f, each, next, millis, floor);
            }
            if (controller.measured()) spent = controller.median();
        }
        seenThrough = through;
        seenNanos = nanos;
    }

    /** Where a budget starts on a tier: how much of the work a kernel runs there at the cost compute would. */
    static float share(CgCapabilities.ComputeTier tier) {
        switch (tier) {
            case V:
            case G43: return 1f;
            case G40:
            case G33: return 0.5f;
            default: return 0.25f;
        }
    }

    private static float positive(float millis) {
        if (!(millis > 0f)) throw new IllegalArgumentException("a budget of " + millis + " ms");
        return millis;
    }

    /**
     * The scale from each frame's measured time, as Unreal's dynamic resolution heuristic moves its screen percentage:
     * over by more than the threshold, down at once in proportion; well under for a run of frames, up a step; between,
     * held. It judges the median of a full window of frames recorded since its last change, so it acts on what that change
     * caused and a frame the driver stretched moves nothing.
     */
    static final class Controller {
        static final int WINDOW = 9;
        /** Judgements well under, in a row, before a step up. */
        static final int RISE_AFTER = 20;
        static final float OVER = 1.05f, UNDER = 0.85f, MAX_DROP = 0.5f, MAX_RISE = 1.25f;

        private final float[] window = new float[WINDOW], sorted = new float[WINDOW];
        private int filled, at, under;
        private long changedAt;
        private float scale = 1f;

        /** Starts at {@code share}, judging frames from {@code from} on. */
        float start(float share, long from) {
            scale = share;
            changedAt = from;
            return scale;
        }

        /** Frame {@code frame} cost {@code millis}; a scale changed now holds from frame {@code next}. */
        float sample(long frame, float millis, long next, float budget, float floor) {
            if (frame < changedAt) return scale;   // recorded at the scale before
            window[at] = millis;
            at = (at + 1) % WINDOW;
            if (filled < WINDOW) filled++;
            if (filled < WINDOW) return scale;
            float average = median();
            if (average > budget * OVER && scale > floor) {
                scale = Math.max(floor, scale * Math.max(MAX_DROP, budget / average));
                changed(next);
            } else if (average < budget * UNDER && scale < 1f) {
                if (++under >= RISE_AFTER) {
                    float rise = average > 0f ? Math.min(MAX_RISE, budget * 0.95f / average) : MAX_RISE;
                    scale = Math.min(1f, scale * rise);
                    changed(next);
                }
            } else {
                under = 0;
            }
            if (scale < floor) scale = floor;
            return scale;
        }

        boolean measured() {
            return filled > 0;
        }

        /** The median of the frames in the window: what a frame costs, a stretched one aside. */
        float median() {
            if (filled == 0) return Float.NaN;
            for (int i = 0; i < filled; i++) {
                float v = window[i];
                int j = i;
                for (; j > 0 && sorted[j - 1] > v; j--) sorted[j] = sorted[j - 1];
                sorted[j] = v;
            }
            return (filled & 1) == 1 ? sorted[filled / 2] : (sorted[filled / 2 - 1] + sorted[filled / 2]) / 2f;
        }

        private void changed(long next) {
            changedAt = next;
            under = 0;
            filled = 0;
            at = 0;
        }
    }
}
