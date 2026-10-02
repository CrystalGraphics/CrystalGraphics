package com.crystalgraphics.vfx.look;

/**
 * How a {@link CgVfxCurve} moves from one key to the next: {@code apply} maps a segment's progress, 0..1, to how far
 * its value has gone, 0 at the start and 1 at the end (BACK and BOUNCE leave that range between them).
 *
 * <pre>{@code
 * float k = CgVfxEasing.OUT_CUBIC.apply(0.5f);   // 0.875
 * }</pre>
 *
 * <p>Ported from Effekseer's {@code ParameterEasing} ({@code Dev/Cpp/Effekseer/Effekseer/Parameter/Effekseer.Easing.h},
 * MIT, Copyright (c) 2011 Effekseer Project): the same functions and constants, its start/end-speed curve excepted.</p>
 */
public enum CgVfxEasing {

    LINEAR, IN_QUADRATIC, OUT_QUADRATIC, IN_OUT_QUADRATIC, IN_CUBIC, OUT_CUBIC, IN_OUT_CUBIC,
    IN_QUARTIC, OUT_QUARTIC, IN_OUT_QUARTIC, IN_QUINTIC, OUT_QUINTIC, IN_OUT_QUINTIC,
    IN_BACK, OUT_BACK, IN_OUT_BACK, IN_BOUNCE, OUT_BOUNCE, IN_OUT_BOUNCE;

    /** Effekseer's overshoot for the BACK easings. */
    private static final float BACK = 1.8f;

    public float apply(float t) {
        t = Math.max(0f, Math.min(1f, t));
        switch (this) {
            case LINEAR: return t;
            case IN_QUADRATIC: return power(t, 2);
            case OUT_QUADRATIC: return 1f - power(1f - t, 2);
            case IN_OUT_QUADRATIC: return inOut(t, 2);
            case IN_CUBIC: return power(t, 3);
            case OUT_CUBIC: return 1f - power(1f - t, 3);
            case IN_OUT_CUBIC: return inOut(t, 3);
            case IN_QUARTIC: return power(t, 4);
            case OUT_QUARTIC: return 1f - power(1f - t, 4);
            case IN_OUT_QUARTIC: return inOut(t, 4);
            case IN_QUINTIC: return power(t, 5);
            case OUT_QUINTIC: return 1f - power(1f - t, 5);
            case IN_OUT_QUINTIC: return inOut(t, 5);
            case IN_BACK: return back(t);
            case OUT_BACK: return 1f - back(1f - t);
            case IN_OUT_BACK: return t <= 0.5f ? back(t * 2f) * 0.5f : 1f - back((1f - t) * 2f) * 0.5f;
            case IN_BOUNCE: return 1f - bounce(1f - t);
            case OUT_BOUNCE: return bounce(t);
            case IN_OUT_BOUNCE: return t <= 0.5f ? (1f - bounce(1f - t * 2f)) * 0.5f : bounce((t - 0.5f) * 2f) * 0.5f + 0.5f;
            default: throw new AssertionError(this);
        }
    }

    private static float power(float t, int n) {
        float r = t;
        for (int i = 1; i < n; i++) r *= t;
        return r;
    }

    private static float inOut(float t, int n) {
        return t <= 0.5f ? power(t * 2f, n) * 0.5f : 1f - power((1f - t) * 2f, n) * 0.5f;
    }

    private static float back(float t) {
        return (BACK + 1f) * t * t * t - BACK * t * t;
    }

    private static float bounce(float t) {
        if (t < 4f / 11f) {
            t = t / 4f * 11f;
            return t * t;
        } else if (t < 8f / 11f) {
            t = t - 4f / 11f - 2f / 11f;
            return 1f + (t * t - (2f / 11f) * (2f / 11f)) * 8f;
        } else if (t < 10f / 11f) {
            t = t - 8f / 11f - 1f / 11f;
            return 1f + (t * t - (1f / 11f) * (1f / 11f)) * 8f;
        }
        t = t - 10f / 11f - 0.5f / 11f;
        return 1f + (t * t - (0.5f / 11f) * (0.5f / 11f)) * 8f;
    }
}
