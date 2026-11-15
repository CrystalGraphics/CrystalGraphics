package com.crystalgraphics.easing;

/**
 * A value chasing a target over time: Unreal's {@code FMath} interpolation and smoothing (UnrealMathUtility.h), for a
 * blend space's weights and inputs, a camera's lag, anything eased toward a moving goal frame by frame.
 *
 * <pre>{@code
 * weight = CgSmoothing.interpConstantTo(weight, target, dt, 5f);        // 5 a second, then exactly there
 *
 * float[] state = { value, 0f };                                       // the value and its rate, kept between frames
 * CgSmoothing.criticallyDamped(state, target, 0f, dt, 0.2f);           // no overshoot, lagging 0.2 s
 * CgSmoothing.springDamper(state, target, 0f, dt, 0.2f, 0.5f);         // under-damped: it overshoots
 * }</pre>
 *
 * <ul>
 *   <li>The rate-carrying forms read and write {@code state[0]} (the value) and {@code state[1]} (its rate a second).</li>
 *   <li>The exponentials use Unreal's {@link #invExpApprox}, accurate while {@code dt} is under half the smoothing time:
 *       the same approximation, so results match Unreal's to the bit's neighbourhood, not {@code Math.exp}'s.</li>
 *   <li>Static, allocation free, any thread.</li>
 * </ul>
 */
public final class CgSmoothing {

    /** {@code UE_SMALL_NUMBER}. */
    public static final float SMALL_NUMBER = 1e-8f;
    /** {@code UE_KINDA_SMALL_NUMBER}. */
    public static final float KINDA_SMALL_NUMBER = 1e-4f;

    private CgSmoothing() {
    }

    /**
     * {@code current} moved toward {@code target} by at most {@code speed · dt}, landing on it when within reach:
     * {@code FInterpConstantTo}.
     */
    public static float interpConstantTo(float current, float target, float dt, float speed) {
        float distance = target - current;
        if (distance * distance < SMALL_NUMBER) return target;
        float step = speed * dt;
        return current + Math.max(-step, Math.min(step, distance));
    }

    /** {@code InvExpApprox}: {@code 1 / e^x} by a cubic fitted for {@code x} in 0..1. */
    public static float invExpApprox(float x) {
        final float a = 1.00746054f, b = 0.45053901f, c = 0.25724632f;
        return 1f / (1f + a * x + b * x * x + c * x * x * x);
    }

    /** {@code ExponentialSmoothingApprox}: {@code value} decayed toward {@code target} with time constant {@code smoothingTime}. */
    public static float exponential(float value, float target, float dt, float smoothingTime) {
        if (smoothingTime > KINDA_SMALL_NUMBER) return target + (value - target) * invExpApprox(dt / smoothingTime);
        return target;
    }

    /**
     * {@code CriticallyDampedSmoothing}: {@code state} ({@code {value, rate}}) toward {@code target} moving at
     * {@code targetRate}, lagging {@code smoothingTime} seconds behind a constant motion.
     */
    public static void criticallyDamped(float[] state, float target, float targetRate, float dt, float smoothingTime) {
        if (smoothingTime > KINDA_SMALL_NUMBER) {
            float adjusted = target + targetRate * smoothingTime;
            float w = 2f / smoothingTime, exp = invExpApprox(w * dt);
            float x0 = state[0] - adjusted, b = (state[1] + x0 * w) * dt;
            state[0] = adjusted + (x0 + b) * exp;
            state[1] = (state[1] - b * w) * exp;
        } else if (dt > 0f) {
            state[1] = (target - state[0]) / dt;
            state[0] = target;
        } else {
            state[0] = target;
            state[1] = 0f;
        }
    }

    /**
     * {@code SpringDamperSmoothing}: {@code state} ({@code {value, rate}}) on a spring toward {@code target}; a damping
     * ratio of 1 is critical, under 1 overshoots, over 1 creeps.
     */
    public static void springDamper(float[] state, float target, float targetRate, float dt, float smoothingTime, float dampingRatio) {
        if (smoothingTime < SMALL_NUMBER) {
            if (dt <= 0f) return;
            state[1] = (target - state[0]) / dt;
            state[0] = target;
            return;
        }
        springDamperFrequency(state, target, targetRate, dt, 1f / ((float) Math.PI * smoothingTime), dampingRatio);
    }

    /** {@code SpringDamper}: by its undamped frequency, cycles a second; what {@link CgSpringInterp}'s springs step. */
    public static void springDamperFrequency(float[] state, float target, float targetRate, float dt, float frequency, float damping) {
        if (dt <= 0f) return;
        float w = frequency * 2f * (float) Math.PI;
        float value = state[0], rate = state[1];
        if (w < SMALL_NUMBER) {
            state[0] = value + rate * dt;
            return;
        }
        if (damping < SMALL_NUMBER) {
            float err = value - target, b = rate / w;
            float s = (float) Math.sin(w * dt), c = (float) Math.cos(w * dt);
            state[0] = target + err * c + b * s;
            state[1] = rate * c - err * (w * s);
            return;
        }
        float smoothingTime = 2f / w;
        float adjusted = target + targetRate * (damping * smoothingTime);
        float err = value - adjusted;
        if (damping > 1f) {
            float wd = w * (float) Math.sqrt(damping * damping - 1f);
            float c2 = -(rate + (w * damping - wd) * err) / (2f * wd), c1 = err - c2;
            float a1 = wd - damping * w, a2 = -(wd + damping * w);
            float e1 = invExpApprox(-a1 * dt), e2 = invExpApprox(-a2 * dt);
            state[0] = adjusted + e1 * c1 + e2 * c2;
            state[1] = e1 * c1 * a1 + e2 * c2 * a2;
        } else if (damping < 1f) {
            float wd = w * (float) Math.sqrt(1f - damping * damping);
            float a = err, b = (rate + err * (damping * w)) / wd;
            float s = (float) Math.sin(wd * dt), c = (float) Math.cos(wd * dt);
            float e = invExpApprox(damping * w * dt);
            float v = e * (a * c + b * s);
            state[1] = -v * damping * w + e * (b * (wd * c) - a * (wd * s));
            state[0] = v + adjusted;
        } else {
            float c1 = err, c2 = rate + err * w;
            float e = invExpApprox(w * dt);
            state[0] = adjusted + (c1 + c2 * dt) * e;
            state[1] = (c2 - c1 * w - c2 * (w * dt)) * e;
        }
    }
}
