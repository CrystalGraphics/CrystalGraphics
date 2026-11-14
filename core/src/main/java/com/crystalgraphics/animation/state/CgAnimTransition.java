package com.crystalgraphics.animation.state;

import java.util.Objects;

import javax.annotation.Nullable;

import com.crystalgraphics.animation.blend.CgAlphaBlend;

/**
 * A way out of one state into another, and how the poses cross: Unreal's {@code FAnimationTransitionBetweenStates} with
 * its exit rule ({@code FBakedStateExitTransition}). Unreal's editor defaults: a 0.2 s HermiteCubic crossfade, priority
 * 1 (AnimStateTransitionNode.cpp:84-98).
 *
 * <pre>{@code
 * CgAnimTransition.of("idle", "run", CgAnimRule.variable(SPEED, CgAnimRule.Op.GREATER, 0.1f));
 * CgAnimTransition.of("run", "idle", stopped).crossfade(0.3f).blend(CgAlphaBlend.CUBIC_IN_OUT);
 * CgAnimTransition.automatic("land", "idle").crossfade(0.1f);           // when the landing clip runs out
 * CgAnimTransition.of("run", "hit", hurt).inertialize().priority(0);    // tried before run's others, inertialized
 * }</pre>
 *
 * <ul>
 *   <li>A state's exits are tried in priority order, lower first, then in the order made; the first that holds is
 *       taken.</li>
 *   <li>{@link #automatic} is Unreal's remaining-time rule: taken when the state's clip has {@link #triggerTime} left,
 *       the crossfade by default. With {@link #overshootCorrection} (Unreal 5's) the crossfade is shortened by how far
 *       past that the frame went; without, it runs whole, as Unreal 4.21's did.</li>
 *   <li>{@link #inertialize()} hands the crossfade's time to the nearest inertialization node above the machine; a graph
 *       without one fails when an instance runs it.</li>
 *   <li>{@link #minReentry} keeps the target from being entered again within that many seconds; negative, never.</li>
 * </ul>
 *
 * @param from                 the state it leaves
 * @param to                   the state it enters
 * @param rule                 when it may be taken; null when automatic
 * @param automatic            Unreal's automatic rule based on the state's clip
 * @param crossfade            the crossfade, seconds
 * @param blend                the crossfade's curve
 * @param inertialized         inertialization instead of a crossfade
 * @param priority             lower is tried first
 * @param triggerTime          the automatic rule's seconds left; negative for the crossfade
 * @param overshootCorrection  the automatic rule shortens the crossfade by the frame's overshoot
 * @param minReentry           seconds before the target may be entered again; negative for no limit
 * @param inertializesSelf     Unreal's {@code bAllowInertializationForSelfTransitions}
 */
public record CgAnimTransition(String from, String to, @Nullable CgAnimRule rule, boolean automatic, float crossfade,
                               CgAlphaBlend blend, boolean inertialized, int priority, float triggerTime,
                               boolean overshootCorrection, float minReentry, boolean inertializesSelf) {

    public CgAnimTransition {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(blend, "blend");
        if ((rule == null) != automatic) throw new IllegalArgumentException(from + " -> " + to + ": a transition has a rule or is automatic");
        if (crossfade < 0f) throw new IllegalArgumentException(from + " -> " + to + ": crossfade " + crossfade);
    }

    /** {@code from} to {@code to} while {@code rule} holds. */
    public static CgAnimTransition of(String from, String to, CgAnimRule rule) {
        return new CgAnimTransition(from, to, Objects.requireNonNull(rule, "rule"), false, 0.2f, CgAlphaBlend.HERMITE_CUBIC,
                false, 1, -1f, true, -1f, false);
    }

    /** {@code from} to {@code to} when the state's clip runs out: Unreal's automatic rule. */
    public static CgAnimTransition automatic(String from, String to) {
        return new CgAnimTransition(from, to, null, true, 0.2f, CgAlphaBlend.HERMITE_CUBIC, false, 1, -1f, true, -1f, false);
    }

    public CgAnimTransition crossfade(float seconds) {
        return new CgAnimTransition(from, to, rule, automatic, seconds, blend, inertialized, priority, triggerTime, overshootCorrection, minReentry, inertializesSelf);
    }

    public CgAnimTransition blend(CgAlphaBlend blend) {
        return new CgAnimTransition(from, to, rule, automatic, crossfade, blend, inertialized, priority, triggerTime, overshootCorrection, minReentry, inertializesSelf);
    }

    /** Inertialized over its crossfade instead of crossfading. */
    public CgAnimTransition inertialize() {
        return new CgAnimTransition(from, to, rule, automatic, crossfade, blend, true, priority, triggerTime, overshootCorrection, minReentry, inertializesSelf);
    }

    public CgAnimTransition priority(int priority) {
        return new CgAnimTransition(from, to, rule, automatic, crossfade, blend, inertialized, priority, triggerTime, overshootCorrection, minReentry, inertializesSelf);
    }

    /** The automatic rule's seconds left at which it is taken. */
    public CgAnimTransition triggerTime(float seconds) {
        return new CgAnimTransition(from, to, rule, automatic, crossfade, blend, inertialized, priority, seconds, overshootCorrection, minReentry, inertializesSelf);
    }

    /** Whether the automatic rule shortens the crossfade by the frame's overshoot: true by default; false is Unreal 4.21's. */
    public CgAnimTransition overshootCorrection(boolean correct) {
        return new CgAnimTransition(from, to, rule, automatic, crossfade, blend, inertialized, priority, triggerTime, correct, minReentry, inertializesSelf);
    }

    public CgAnimTransition minReentry(float seconds) {
        return new CgAnimTransition(from, to, rule, automatic, crossfade, blend, inertialized, priority, triggerTime, overshootCorrection, seconds, inertializesSelf);
    }

    /** A self-transition into a weighted, always-resetting state inertializes rather than pops. */
    public CgAnimTransition inertializeSelf() {
        return new CgAnimTransition(from, to, rule, automatic, crossfade, blend, inertialized, priority, triggerTime, overshootCorrection, minReentry, true);
    }
}
