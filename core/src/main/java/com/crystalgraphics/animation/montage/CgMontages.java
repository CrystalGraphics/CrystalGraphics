package com.crystalgraphics.animation.montage;

import java.util.ArrayList;
import com.crystalgraphics.animation.graph.CgRootMotionParams;
import com.crystalgraphics.animation.graph.CgRootMotionMode;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import javax.annotation.Nullable;

import org.joml.Matrix4f;

import com.crystalgraphics.animation.graph.CgAnimGraphInstance;
import com.crystalgraphics.api.animation.CgSkeleton;
import com.crystalgraphics.easing.CgEasing;

/**
 * A character's montages: those playing and blending out, one per slot group, its root motion, and what it tells of
 * them. Unreal's {@code UAnimInstance} montage half: {@code Montage_PlayInternal} (AnimInstance.cpp:2758-2832),
 * {@code StopAllMontagesByGroupName} (:3671), {@code UpdateMontage} (:561) and {@code ConsumeExtractedRootMotion}
 * (:4488). Each anim instance has one, {@code anim.montages()}.
 *
 * <pre>{@code
 * CgMontageInstance m = anim.montages().play(CgMontage.DEFAULT.blendIn(0.1f, CgEasings.LINEAR), this);
 * anim.montages().listen(new CgMontages.Listener() {
 *     @Override public void ended(CgMontageInstance montage, boolean interrupted) { ... }
 * });
 * Matrix4f step = new Matrix4f();
 * if (anim.montages().consumeRootMotion(1f, step)) mover.move(step);   // after the frame's pose
 * }</pre>
 *
 * <ul>
 *   <li>A montage played stops every other of its group, blending them out over its own blend in; a root-motion
 *       montage also stops the one moving the character, so one moves it at a time.</li>
 *   <li>Root motion gathers until consumed: a character nothing moves keeps it.</li>
 *   <li>Listeners hear after the frame's montages have updated, on the game thread.</li>
 * </ul>
 */
public final class CgMontages {

    static final int BLENDED_IN = 0, BLENDING_OUT = 1, ENDED = 2;

    /** What a character's montages tell: Unreal's {@code OnMontageBlendedIn}, {@code OnMontageBlendingOut}, {@code OnMontageEnded}. */
    public interface Listener {

        /** {@code montage} reached its full weight. */
        default void blendedIn(CgMontageInstance montage) {
        }

        /** {@code montage} began blending out: stopped ({@code interrupted}) or at its end. */
        default void blendingOut(CgMontageInstance montage, boolean interrupted) {
        }

        /** {@code montage} blended out and left. */
        default void ended(CgMontageInstance montage, boolean interrupted) {
        }
    }

    private final CgSkeleton skeleton;
    private final List<CgMontageInstance> active = new ArrayList<>();
    private final List<CgMontageInstance> view = Collections.unmodifiableList(active);
    private final List<Listener> listeners = new ArrayList<>(1);
    @Nullable
    private CgMontageInstance rootMotionMontage;
    private CgRootMotionMode mode = CgRootMotionMode.FROM_MONTAGES_ONLY;
    private final CgRootMotionParams rootMotion = new CgRootMotionParams();
    private long frame;
    private int[] eventKinds = new int[4];
    private CgMontageInstance[] eventMontages = new CgMontageInstance[4];
    private boolean[] eventInterrupted = new boolean[4];
    private int events;

    /** A character of {@code skeleton}'s; its anim instance makes it. */
    public CgMontages(CgSkeleton skeleton) {
        this.skeleton = Objects.requireNonNull(skeleton, "skeleton");
    }

    CgSkeleton skeleton() {
        return skeleton;
    }

    /**
     * Plays {@code montage} for {@code source} (what drives it): every montage of its group is interrupted, blending out
     * over its blend in; with root motion, the one moving the character too. It blends in from 0.
     */
    public CgMontageInstance play(CgMontage montage, Object source) {
        stopGroup(montage.group(), montage.blendIn(), montage.blendInCurve());
        if (montage.rootMotion() && rootMotionMontage != null) rootMotionMontage.interrupt(montage.blendIn(), montage.blendInCurve());
        CgMontageInstance m = new CgMontageInstance(this, montage, source);
        m.play();
        active.add(m);
        if (montage.rootMotion()) rootMotionMontage = m;
        return m;
    }

    /** {@code StopAllMontagesByGroupName}: each of {@code group}'s montages interrupted, blending out over {@code seconds}. */
    public void stopGroup(String group, float seconds, CgEasing curve) {
        for (int i = active.size() - 1; i >= 0; i--) {
            CgMontageInstance m = active.get(i);
            if (m.montage().group().equals(group)) m.interrupt(seconds, curve);
        }
    }

    /** Those playing and blending out, oldest first. */
    public List<CgMontageInstance> active() {
        return view;
    }

    /** The montage whose root motion moves the character, or null. */
    @Nullable
    public CgMontageInstance rootMotionMontage() {
        return rootMotionMontage;
    }

    public CgRootMotionMode rootMotionMode() {
        return mode;
    }

    /** {@code SetRootMotionMode}. */
    public CgMontages rootMotionMode(CgRootMotionMode mode) {
        this.mode = Objects.requireNonNull(mode, "mode");
        return this;
    }

    /**
     * {@code ConsumeExtractedRootMotion}: {@code alpha} of the root motion gathered into {@code out}, in the mesh's
     * space; all of it at 1. False, {@code out} identity, with none.
     */
    public boolean consumeRootMotion(float alpha, Matrix4f out) {
        if (!rootMotion.hasRootMotion() || alpha < ZERO_WEIGHT) {
            out.identity();
            return false;
        }
        if (alpha > 1f - ZERO_WEIGHT) {
            rootMotion.get(out);
            rootMotion.clear();
            return true;
        }
        return rootMotion.consume(alpha, out);
    }

    /** {@code ZERO_ANIMWEIGHT_THRESH}. */
    private static final float ZERO_WEIGHT = 0.00001f;

    /**
     * {@code step} added to the root motion the mover consumes, where the mode extracts any: a montage's step, or the
     * graph's players' under {@link CgRootMotionMode#FROM_EVERYTHING}. Its instance calls it.
     */
    public void accumulateRootMotion(Matrix4f step) {
        if (mode == CgRootMotionMode.FROM_MONTAGES_ONLY || mode == CgRootMotionMode.FROM_EVERYTHING) rootMotion.accumulate(step);
    }

    /** Hears its montages blend in, blend out and end. */
    public CgMontages listen(Listener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
        return this;
    }

    public CgMontages unlisten(Listener listener) {
        listeners.remove(listener);
        return this;
    }

    /** Its frame count: a montage's weight moves once in each. */
    long frame() {
        return frame;
    }

    /**
     * The game thread's frame, {@code seconds} long, before {@code graph}'s update: Unreal's {@code UpdateMontage}.
     * Each weight its driver did not move is moved; a released montage plays on alone; one blended out leaves; the
     * rest put their clips on their slots and lead their sync groups; then listeners hear. Its anim instance calls it.
     */
    public void preUpdate(float seconds, CgAnimGraphInstance graph) {
        for (int i = 0; i < active.size(); i++) {
            CgMontageInstance m = active.get(i);
            m.updateWeight(seconds);
            if (m.released()) m.advanceAlone(seconds);
        }
        for (int i = active.size() - 1; i >= 0; i--) {
            CgMontageInstance m = active.get(i);
            if (!m.finished()) continue;
            queue(ENDED, m);
            m.terminate();
            active.remove(i);
            if (rootMotionMontage == m) rootMotionMontage = null;
        }
        for (int i = 0; i < active.size(); i++) {
            CgMontageInstance m = active.get(i);
            m.push();
            if (m.canUseMarkerSync()) graph.syncMontage(m, m.weight(), m.montage().syncGroup());
            m.endFrame();
        }
        frame++;
        int n = events;
        events = 0;
        for (int e = 0; e < n; e++) {
            CgMontageInstance m = eventMontages[e];
            eventMontages[e] = null;
            for (int l = 0; l < listeners.size(); l++) {
                Listener listener = listeners.get(l);
                switch (eventKinds[e]) {
                    case BLENDED_IN -> listener.blendedIn(m);
                    case BLENDING_OUT -> listener.blendingOut(m, eventInterrupted[e]);
                    default -> listener.ended(m, eventInterrupted[e]);
                }
            }
        }
    }

    void queue(int kind, CgMontageInstance m) {
        if (events == eventKinds.length) {
            int n = events * 2;
            eventKinds = Arrays.copyOf(eventKinds, n);
            eventMontages = Arrays.copyOf(eventMontages, n);
            eventInterrupted = Arrays.copyOf(eventInterrupted, n);
        }
        eventKinds[events] = kind;
        eventMontages[events] = m;
        eventInterrupted[events++] = m.interrupted();
    }
}
