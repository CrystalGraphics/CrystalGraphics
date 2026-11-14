package com.crystalgraphics.animation.blend;

import java.util.Locale;

import com.crystalgraphics.easing.CgEasing;
import com.crystalgraphics.easing.CgEasings;

/**
 * The curve a blend follows from 0 to 1 over its time: Unreal's {@code EAlphaBlendOption}, as
 * {@code FAlphaBlend::AlphaToBlendOption} maps a linear alpha (AlphaBlend.cpp:239-270). A state machine's transition
 * crossfades by one.
 *
 * <pre>{@code
 * float alpha = CgAlphaBlend.HERMITE_CUBIC.apply(elapsed / crossfade);   // 0 to 1, eased
 * CgAlphaBlend blend = CgAlphaBlend.of("cubic_in_out");                   // a file's lowercase name
 * }</pre>
 *
 * <ul>
 *   <li>Every option clamps its input to 0..1, as Unreal's does.</li>
 *   <li>{@code HERMITE_CUBIC} and {@code CUBIC} are the same curve, Unreal's {@code SmoothStep} and
 *       {@code CubicInterp(0, 0, 1, 0, t)}.</li>
 *   <li>Unreal's {@code Custom} (a curve asset) is not here yet: a file naming it fails at load.</li>
 * </ul>
 */
public enum CgAlphaBlend {

    LINEAR(CgEasings.LINEAR),
    CUBIC(CgEasings.SMOOTH_STEP),
    HERMITE_CUBIC(CgEasings.SMOOTH_STEP),
    SINUSOIDAL(CgEasings.IN_OUT_SINE),
    QUADRATIC_IN_OUT(CgEasings.IN_OUT_QUAD),
    CUBIC_IN_OUT(CgEasings.IN_OUT_CUBIC),
    QUARTIC_IN_OUT(CgEasings.IN_OUT_QUART),
    QUINTIC_IN_OUT(CgEasings.IN_OUT_QUINT),
    CIRCULAR_IN(CgEasings.IN_CIRC),
    CIRCULAR_OUT(CgEasings.OUT_CIRC),
    CIRCULAR_IN_OUT(CgEasings.IN_OUT_CIRC),
    EXP_IN(CgEasings.IN_EXPO),
    EXP_OUT(CgEasings.OUT_EXPO),
    EXP_IN_OUT(CgEasings.IN_OUT_EXPO);

    private final CgEasing easing;

    CgAlphaBlend(CgEasing easing) {
        this.easing = easing;
    }

    /** The eased alpha at linear {@code alpha}, clamped to 0..1. */
    public float apply(float alpha) {
        return (float) easing.ease(alpha);
    }

    /**
     * The fraction of a full blend's time that reaches {@code fraction} of the way: how much shorter a crossfade into a
     * state already partly weighted is ({@code CalculateInverseAlpha}, AnimNode_StateMachine.cpp:78-96). Unreal works
     * it out for HermiteCubic alone ({@code 4/3·t³ − 2t² + 5/3·t}) and clamps for every other option.
     */
    public float inverse(float fraction) {
        if (this == HERMITE_CUBIC) return fraction * fraction * fraction * (4f / 3f) - 2f * fraction * fraction + (5f / 3f) * fraction;
        return Math.max(0f, Math.min(1f, fraction));
    }

    /** Its name in a file: lowercase, as Unreal's ({@code hermite_cubic}). */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * The option named {@code id}, lowercase.
     *
     * @throws IllegalArgumentException for a name it has not, {@code custom} included
     */
    public static CgAlphaBlend of(String id) {
        for (CgAlphaBlend b : values()) if (b.id().equals(id)) return b;
        throw new IllegalArgumentException(id + " is no alpha blend" + ("custom".equals(id) ? " played yet: custom curves are not" : ""));
    }
}
