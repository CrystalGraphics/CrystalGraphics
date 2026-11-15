package com.crystalgraphics.animation.ik;

import java.util.Objects;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import com.crystalgraphics.animation.blend.CgAlphaBlender;
import com.crystalgraphics.animation.graph.CgAnimGraphInstance;
import com.crystalgraphics.animation.graph.CgAnimVariable;

/**
 * How much of a bone control shows over its input, 0 to 1: Unreal's skeletal control alpha
 * (AnimNode_SkeletalControlBase.cpp:49-87). A float (a constant or a variable) through a scale, bias and clamp, or a
 * bool blended in and out over its times.
 *
 * <pre>{@code
 * CgControlAlpha.of(1f);                                     // always on
 * CgControlAlpha.of(LOOK_WEIGHT).interp(4f, 2f);            // a variable, eased toward it at 4/s up, 2/s down
 * CgControlAlpha.bool(FEET_ON, 0.2f, 0.3f);                 // a bool, blended in over 0.2 s and out over 0.3 s
 * }</pre>
 *
 * <ul>
 *   <li>A float goes through {@code FInputScaleBiasClamp} (map range, scale and bias, clamp, interpolation), then
 *       {@code FInputScaleBias} (scale, bias, clamped to 0 to 1); the result is clamped to 0 to 1.</li>
 *   <li>A bool starts where its value is, with no blend, as {@code FInputAlphaBoolBlend} initialises.</li>
 * </ul>
 */
public final class CgControlAlpha {

    private final float constant;
    @Nullable
    private final CgAnimVariable<Float> variable;
    @Nullable
    private final CgAnimVariable<Boolean> enabled;
    private final float blendIn, blendOut;
    // FInputScaleBias
    private final float scale, bias;
    // FInputScaleBiasClamp
    private final boolean mapRange, clampResult, interpResult;
    private final float inMin, inMax, outMin, outMax, clampMin, clampMax, interpUp, interpDown;

    private CgControlAlpha(float constant, @Nullable CgAnimVariable<Float> variable, @Nullable CgAnimVariable<Boolean> enabled,
                           float blendIn, float blendOut, float scale, float bias, boolean mapRange, float inMin, float inMax,
                           float outMin, float outMax, boolean clampResult, float clampMin, float clampMax,
                           boolean interpResult, float interpUp, float interpDown) {
        this.constant = constant;
        this.variable = variable;
        this.enabled = enabled;
        this.blendIn = blendIn;
        this.blendOut = blendOut;
        this.scale = scale;
        this.bias = bias;
        this.mapRange = mapRange;
        this.inMin = inMin;
        this.inMax = inMax;
        this.outMin = outMin;
        this.outMax = outMax;
        this.clampResult = clampResult;
        this.clampMin = clampMin;
        this.clampMax = clampMax;
        this.interpResult = interpResult;
        this.interpUp = interpUp;
        this.interpDown = interpDown;
    }

    /** Full: the control's whole effect. */
    public static final CgControlAlpha FULL = of(1f);

    public static CgControlAlpha of(float alpha) {
        return new CgControlAlpha(alpha, null, null, 0f, 0f, 1f, 0f, false, 0f, 1f, 0f, 1f, false, 0f, 1f, false, 10f, 10f);
    }

    public static CgControlAlpha of(CgAnimVariable<Float> alpha) {
        return new CgControlAlpha(0f, Objects.requireNonNull(alpha, "alpha"), null, 0f, 0f, 1f, 0f, false, 0f, 1f, 0f, 1f,
                false, 0f, 1f, false, 10f, 10f);
    }

    /** A bool, blended toward 1 over {@code blendIn} seconds when it turns true, toward 0 over {@code blendOut}. */
    public static CgControlAlpha bool(CgAnimVariable<Boolean> enabled, float blendIn, float blendOut) {
        return new CgControlAlpha(0f, null, Objects.requireNonNull(enabled, "enabled"), Math.max(0f, blendIn), Math.max(0f, blendOut),
                1f, 0f, false, 0f, 1f, 0f, 1f, false, 0f, 1f, false, 10f, 10f);
    }

    /** {@code FInputScaleBias}: the float times {@code scale} plus {@code bias}, clamped to 0 to 1. */
    public CgControlAlpha scaleBias(float scale, float bias) {
        return new CgControlAlpha(constant, variable, enabled, blendIn, blendOut, scale, bias, mapRange, inMin, inMax,
                outMin, outMax, clampResult, clampMin, clampMax, interpResult, interpUp, interpDown);
    }

    /** {@code FInputScaleBiasClamp}'s range map: the float from {@code in} mapped onto {@code out}, unclamped. */
    public CgControlAlpha mapRange(float inMin, float inMax, float outMin, float outMax) {
        return new CgControlAlpha(constant, variable, enabled, blendIn, blendOut, scale, bias, true, inMin, inMax,
                outMin, outMax, clampResult, clampMin, clampMax, interpResult, interpUp, interpDown);
    }

    /** {@code FInputScaleBiasClamp}'s clamp. */
    public CgControlAlpha clamp(float min, float max) {
        return new CgControlAlpha(constant, variable, enabled, blendIn, blendOut, scale, bias, mapRange, inMin, inMax,
                outMin, outMax, true, min, max, interpResult, interpUp, interpDown);
    }

    /** {@code FInputScaleBiasClamp}'s interpolation: eased toward the value at these speeds ({@code FInterpTo}). */
    public CgControlAlpha interp(float increasing, float decreasing) {
        return new CgControlAlpha(constant, variable, enabled, blendIn, blendOut, scale, bias, mapRange, inMin, inMax,
                outMin, outMax, clampResult, clampMin, clampMax, true, increasing, decreasing);
    }

    void reads(Consumer<CgAnimVariable<?>> each) {
        if (variable != null) each.accept(variable);
        if (enabled != null) each.accept(enabled);
    }

    /** Its runtime state in one instance. */
    State state() {
        return new State();
    }

    /** One instance's alpha: the bool's blend and the interpolation it remembers. */
    final class State {
        private final CgAlphaBlender blend = new CgAlphaBlender(0f);
        private boolean initialized;
        private float interpolated;

        void reset() {
            initialized = false;
        }

        /** This frame's alpha, {@code dt} seconds on. */
        float apply(CgAnimGraphInstance g, float dt) {
            float a;
            if (enabled != null) {
                // FInputAlphaBoolBlend::ApplyTo (InputScaleBias.cpp)
                float target = g.is(enabled) ? 1f : 0f;
                if (!initialized) {
                    blend.valueRange(target, target).blendTime(0f);
                    initialized = true;
                } else if (blend.desired() != target) {
                    blend.desired(target).blendTime(target == 1f ? blendIn : blendOut);
                }
                blend.update(dt);
                a = blend.value();
            } else {
                a = variable != null ? g.get(variable) : constant;
                // FInputScaleBiasClamp::ApplyTo
                if (mapRange) {
                    float range = inMax - inMin;
                    float pct = range != 0f ? (a - inMin) / range : (a >= inMax ? 1f : 0f);
                    a = outMin + (outMax - outMin) * pct;
                }
                if (clampResult) a = Math.max(clampMin, Math.min(clampMax, a));
                if (interpResult) {
                    if (initialized) a = interpTo(interpolated, a, dt, a >= interpolated ? interpUp : interpDown);
                    interpolated = a;
                }
                initialized = true;
                // FInputScaleBias::ApplyTo
                a = Math.max(0f, Math.min(1f, a * scale + bias));
            }
            return Math.max(0f, Math.min(1f, a));
        }
    }

    /** Unreal's {@code FMath::FInterpTo}: toward {@code target} by speed times time, at once where the speed is 0. */
    static float interpTo(float current, float target, float dt, float speed) {
        if (speed <= 0f) return target;
        float dist = target - current;
        if (dist * dist < 1e-8f) return target;
        return current + dist * Math.max(0f, Math.min(1f, dt * speed));
    }

    // ── its data form ──

    float constant() {
        return constant;
    }

    @Nullable
    CgAnimVariable<Float> variable() {
        return variable;
    }

    @Nullable
    CgAnimVariable<Boolean> enabled() {
        return enabled;
    }

    float blendIn() {
        return blendIn;
    }

    float blendOut() {
        return blendOut;
    }
}
