package com.crystalgraphics.animation.montage;

import java.util.Arrays;
import com.crystalgraphics.animation.graph.CgRootMotionMode;
import java.util.Objects;

import javax.annotation.Nullable;

import org.joml.Matrix4f;

import com.crystalgraphics.animation.blend.CgAlphaBlender;
import com.crystalgraphics.animation.graph.CgAnimSlot;
import com.crystalgraphics.animation.sync.CgAnimAssetPlayer;
import com.crystalgraphics.animation.sync.CgAnimTickContext;
import com.crystalgraphics.animation.sync.CgMarkerSync;
import com.crystalgraphics.animation.sync.CgMarkerTickRecord;
import com.crystalgraphics.api.animation.CgAnimSequence;
import com.crystalgraphics.api.animation.CgMirrorTable;
import com.crystalgraphics.easing.CgEasing;

/**
 * One montage playing on a character: its weight blending in, then out when it is stopped or reaches its end, the
 * clips it plays on its slots this frame, and its root motion. Unreal's {@code FAnimMontageInstance}: the weight and
 * lifetime are its {@code Play}, {@code Stop}, {@code UpdateWeight} and {@code Terminate} (AnimMontage.cpp:1513-1661,
 * 1743-1814, 2038-2070); what plays and where the time goes is its driver's, a sequence's {@code animation} track.
 *
 * <pre>{@code
 * CgMontageInstance m = anim.montages().play(PUNCH_MONTAGE, this);   // blends in, stopping its group's others
 * // each frame, before the anim instance's update (its driver does this):
 * m.updateWeight(dt);
 * m.beginPass(pass);
 * m.entry(slot, punch, t, 1f, null, 1f, false, before, moved);
 * m.stop();                                                          // later: interrupted, blending out
 * }</pre>
 *
 * <ul>
 *   <li>Its weight moves once a frame, the first time {@link #updateWeight} is called in it; its anim instance calls it
 *       for any montage its driver did not.</li>
 *   <li>Stopped by its driver going ({@link #release}), it plays on alone while it blends out: its clips advanced at
 *       their rates, its root motion still extracted, as an interrupted montage advances in Unreal.</li>
 *   <li>Its slot entries are its last pass's, held until written again: a montage at its end holds its last pose
 *       while it blends out.</li>
 *   <li>The game thread's. Allocates nothing once its arrays have grown.</li>
 * </ul>
 */
public final class CgMontageInstance implements CgAnimAssetPlayer {

    private final CgMontages montages;
    private final CgMontage montage;
    private final Object source;
    /** Unreal's {@code FAlphaBlend()}: 0.2 s, at 0. */
    private final CgAlphaBlender blend = new CgAlphaBlender(0.2f);
    private boolean playing, interrupted, terminated, released;
    private long weightFrame = -1;
    @Nullable
    private Runnable onInterrupted;

    private CgAnimSlot[] slots = new CgAnimSlot[1];
    private CgAnimSequence[] clips = new CgAnimSequence[1];
    private CgMirrorTable[] mirrors = new CgMirrorTable[1];
    private float[] positions = new float[1], rates = new float[1], weights = new float[1];
    private boolean[] loops = new boolean[1];
    private int count, pass = Integer.MIN_VALUE;

    /** Its first entry's clip and time over this frame: what it leads a sync group by. */
    @Nullable
    private CgAnimSequence syncClip;
    private float syncTime, syncPrevious, syncDelta;
    private boolean syncMoved, markerSync;
    private final CgMarkerTickRecord record = new CgMarkerTickRecord(), scratch = new CgMarkerTickRecord();
    private String[] passedNames = new String[4];
    private float[] passedDeltas = new float[4];
    private int passed;
    private final CgAnimSequence.MarkerSink sink = this::passed;
    private final Matrix4f step = new Matrix4f();

    CgMontageInstance(CgMontages montages, CgMontage montage, Object source) {
        this.montages = montages;
        this.montage = Objects.requireNonNull(montage, "montage");
        this.source = Objects.requireNonNull(source, "source");
    }

    /** How it plays. */
    public CgMontage montage() {
        return montage;
    }

    /** What drives it: a sequence's player, or whatever code played it. */
    public Object source() {
        return source;
    }

    /** {@code GetWeight}: its blended weight this frame. */
    public float weight() {
        return blend.value();
    }

    /** {@code IsStopped}: blending out, or out. */
    public boolean stopped() {
        return blend.desired() == 0f;
    }

    /** Whether it was stopped by something other than its own end: a stop, or another montage of its group. */
    public boolean interrupted() {
        return interrupted;
    }

    /** {@code bPlaying}: its time moves; false once it reached its end or stopped with no blend. */
    public boolean playing() {
        return playing;
    }

    /** Whether it has blended out and left its character. */
    public boolean terminated() {
        return terminated;
    }

    /** Whether its root motion moves the character: it is the root-motion montage and the mode extracts. */
    public boolean extractsRootMotion() {
        return montage.rootMotion() && montages.rootMotionMontage() == this && montages.rootMotionMode() != CgRootMotionMode.NO_EXTRACTION;
    }

    /** What its driver does when another montage interrupts it: stop what it plays. */
    public CgMontageInstance onInterrupted(@Nullable Runnable onInterrupted) {
        this.onInterrupted = onInterrupted;
        return this;
    }

    // ── its weight ───────────────────────────────────────────────────────────────────────

    /** {@code Play}: blends in from its weight now over its montage's blend in (AnimMontage.cpp:1513-1549). */
    void play() {
        playing = true;
        float current = blend.value();
        blend.curve(montage.blendInCurve()).blendTime(montage.blendIn());
        blend.valueRange(current, 1f);
    }

    /** {@code Montage_Stop}: interrupted, blending out over its montage's blend out. */
    public void stop() {
        stop(montage.blendOut(), montage.blendOutCurve(), true);
    }

    /**
     * {@code Stop} (AnimMontage.cpp:1558-1661): blends out over {@code seconds}; one already blending out only shortens
     * its blend. A blend of 0 stops its time at once. Once interrupted it stays so.
     */
    public void stop(float seconds, CgEasing curve, boolean interrupt) {
        if (terminated) return;
        if (!interrupted && interrupt) interrupted = true;
        if (!stopped()) {
            blend.curve(curve).blendTime(seconds);
            blend.desired(0f);
            blend.update(0f);
            montages.queue(CgMontages.BLENDING_OUT, this);
        } else if (seconds < blend.blendTime()) {
            blend.blendTime(seconds);
            blend.desired(0f);
        }
        if (blend.blendTime() <= 0f) playing = false;
    }

    /** By the anim instance, when its group's newcomer stops it: its driver told. */
    void interrupt(float seconds, CgEasing curve) {
        boolean was = stopped();
        stop(seconds, curve, true);
        if (!was && onInterrupted != null) onInterrupted.run();
    }

    /**
     * {@code UpdateWeight} (AnimMontage.cpp:2038-2070), once a frame: the first call in a frame moves it; a call after
     * that does nothing. Its driver calls it before reading its end, as Unreal updates the weight before advancing.
     */
    public void updateWeight(float seconds) {
        if (terminated || weightFrame == montages.frame()) return;
        weightFrame = montages.frame();
        boolean wasComplete = blend.complete();
        blend.update(seconds);
        if (!stopped() && !wasComplete && blend.complete()) montages.queue(CgMontages.BLENDED_IN, this);
    }

    /** Whether it has blended out: Unreal terminates it then (AnimMontage.cpp:2785-2790). */
    boolean finished() {
        return stopped() && blend.complete();
    }

    void terminate() {
        terminated = true;
        playing = false;
        Arrays.fill(slots, 0, count, null);
        Arrays.fill(clips, 0, count, null);
        count = 0;
    }

    /** {@code CanUseMarkerSync}: a sync group, while it blends out (AnimMontage.cpp:3282-3286). */
    boolean canUseMarkerSync() {
        return montage.syncGroup() != null && stopped() && !blend.complete();
    }

    // ── what it plays ────────────────────────────────────────────────────────────────────

    /** Its driver is gone: it plays on alone, advancing its clips, until it blends out. */
    public void release() {
        released = true;
    }

    boolean released() {
        return released;
    }

    /**
     * Its driver's pass {@code pass} begins writing: the first in a new pass replaces the entries. A frame with several
     * passes (a section ending into its next) keeps its last.
     */
    public void beginPass(int pass) {
        if (pass == this.pass) return;
        this.pass = pass;
        Arrays.fill(slots, 0, count, null);
        Arrays.fill(clips, 0, count, null);
        count = 0;
    }

    /**
     * Plays {@code clip} at {@code position} on {@code slot} at {@code weight} (its section's; its own weight is applied
     * when the anim instance hands it on), moving at {@code rate} and wrapping where {@code loop}. {@code moved}
     * seconds of the clip passed since {@code from}, 0 after a jump: the first entry of a pass is the one its root
     * motion and sync time are taken from, as Unreal's montage track.
     */
    public void entry(CgAnimSlot slot, CgAnimSequence clip, float position, float weight, @Nullable CgMirrorTable mirror,
                      float rate, boolean loop, float from, float moved) {
        if (terminated) return;
        if (count == slots.length) {
            int n = count * 2;
            slots = Arrays.copyOf(slots, n);
            clips = Arrays.copyOf(clips, n);
            mirrors = Arrays.copyOf(mirrors, n);
            positions = Arrays.copyOf(positions, n);
            rates = Arrays.copyOf(rates, n);
            weights = Arrays.copyOf(weights, n);
            loops = Arrays.copyOf(loops, n);
        }
        slots[count] = Objects.requireNonNull(slot, "slot");
        clips[count] = Objects.requireNonNull(clip, "clip");
        mirrors[count] = mirror;
        positions[count] = position;
        rates[count] = rate;
        weights[count] = weight;
        loops[count] = loop;
        if (count++ == 0) moved(clip, from, moved, position, loop);
    }

    /** Its first entry moved {@code moved} from {@code from} to {@code to}: root motion extracted, markers collected. */
    private void moved(CgAnimSequence clip, float from, float moved, float to, boolean loop) {
        if (clip != syncClip) {
            syncClip = clip;
            syncPrevious = to;
            record.reset();
        } else if (!syncMoved) {
            syncPrevious = from;
        }
        syncTime = to;
        syncDelta += moved;
        syncMoved = true;
        if (moved == 0f || !playing) return;
        if (extractsRootMotion()) {
            clip.extractRootMotion(montages.skeleton(), from, moved, loop, step);
            montages.accumulateRootMotion(step);
        }
        markerSync = canUseMarkerSync();
        if (markerSync) {
            if (moved > 0f && to < from) {
                // Wrapped: each side of the clip's end
                clip.markersPassed(from, clip.duration(), moved, sink);
                clip.markersPassed(0f, to, moved, sink);
            } else if (moved > 0f) {
                clip.markersPassed(from, to, moved, sink);
            }
        }
    }

    private void passed(String name, float delta) {
        if (passed == passedNames.length) {
            passedNames = Arrays.copyOf(passedNames, passed * 2);
            passedDeltas = Arrays.copyOf(passedDeltas, passed * 2);
        }
        passedNames[passed] = name;
        passedDeltas[passed++] = delta;
    }

    /** Released: its clips moved {@code seconds} on at their rates, wrapping or held at their ends. */
    void advanceAlone(float seconds) {
        if (!playing || count == 0) return;
        for (int i = 0; i < count; i++) {
            float length = clips[i].duration(), from = positions[i], to = from + seconds * rates[i];
            if (length > 0f) {
                if (loops[i]) {
                    to %= length;
                    if (to < 0f) to += length;
                } else {
                    to = Math.max(0f, Math.min(length, to));
                }
            }
            positions[i] = to;
            if (i == 0) moved(clips[0], from, loops[0] ? seconds * rates[0] : to - from, to, loops[0]);
        }
    }

    /** Its entries into their slots at their weights times its own. */
    void push() {
        float w = blend.value();
        if (w <= 0f) return;
        boolean lock = extractsRootMotion() || montage.rootMotion() && montages.rootMotionMontage() == this
                && montages.rootMotionMode() == CgRootMotionMode.IGNORE;
        for (int i = 0; i < count; i++) slots[i].play(clips[i], positions[i], weights[i] * w, mirrors[i], lock);
    }

    /** The frame's sync time gone: its next frame's moves start from where this one ended. */
    void endFrame() {
        syncDelta = 0f;
        syncMoved = false;
        passed = 0;
        if (!markerSync) record.reset();
        markerSync = false;
    }

    // ── leading a sync group ─────────────────────────────────────────────────────────────

    /**
     * {@code UAnimMontage::TickAssetPlayer} (AnimMontage.cpp:1264-1340): as the leader, its own move handed to the
     * group, and the marker positions either side of it; as a follower, nothing.
     */
    @Override
    public void tick(CgAnimTickContext ctx, boolean canUseMarkerSync) {
        boolean resetRecord = true;
        if (ctx.leader() && syncClip != null) {
            float length = playLength();
            ctx.leaderDelta(syncDelta);
            ctx.previousRatio(syncPrevious / length);
            if (syncDelta != 0f && canUseMarkerSync && ctx.canUseMarkerPosition()) {
                if (record.valid(false)) {
                    CgMarkerSync.position(syncClip, record.previous, record.next, syncPrevious, ctx.start());
                } else {
                    CgMarkerSync.indicesForTime(syncClip, syncPrevious, false, ctx, scratch);
                    CgMarkerSync.position(syncClip, scratch.previous, scratch.next, syncPrevious, ctx.start());
                }
                CgMarkerSync.indicesForTime(syncClip, syncTime, false, ctx, record);
                resetRecord = false;
                CgMarkerSync.position(syncClip, record.previous, record.next, syncTime, ctx.end());
                for (int i = 0; i < passed; i++) ctx.pass(passedNames[i], passedDeltas[i]);
            }
            ctx.ratio(syncTime / length);
        }
        if (resetRecord) record.reset();
    }

    @Nullable
    @Override
    public String[] uniqueMarkers() {
        return syncClip == null ? null : syncClip.uniqueMarkerNames();
    }

    @Nullable
    @Override
    public Object asset() {
        return syncClip;
    }

    @Override
    public CgMarkerTickRecord markerRecord() {
        return record;
    }

    @Override
    public float playLength() {
        float length = syncClip == null ? 0f : syncClip.duration();
        return length > 0f ? length : 1f;
    }

    @Override
    public float accumulatedTime() {
        return syncTime;
    }

    @Override
    public float previousTime() {
        return syncPrevious;
    }

    @Override
    public float lastDelta() {
        return syncDelta;
    }

    @Override
    public boolean looping() {
        return count > 0 && loops[0];
    }

    @Override
    public float playRate() {
        return count > 0 ? rates[0] : 1f;
    }

    @Override
    public float currentAssetLength() {
        return playLength();
    }

    @Override
    public float currentAssetTimePlayRateAdjusted() {
        return syncTime;
    }

    /** Its clips reach the curves through its slots, so the sync gathers none from it. */
    @Override
    public void forEachSample(float weight, SampleSink sink) {
    }

    @Override
    public String toString() {
        return "CgMontageInstance(" + source + ", " + weight() + (stopped() ? ", stopped" : "") + ")";
    }
}
