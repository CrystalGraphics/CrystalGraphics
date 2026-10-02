package com.crystalgraphics.easing;

import java.util.Arrays;

/**
 * The library of easings: Penner's families, CSS's keywords, and factories for {@code cubic-bezier()}, {@code linear()}
 * and baking.
 *
 * <pre>{@code
 * CgEasing slide = CgEasings.OUT_QUAD;
 * CgEasing ease = CgEasings.EASE;                                  // CSS's `ease`
 * CgEasing custom = CgEasings.cubicBezier(0.2, 0.8, 0.2, 1.0);     // CSS's cubic-bezier()
 * CgEasing stops = CgEasings.linear(0, 0, 0.25, 0.8, 1, 1);        // CSS's linear(), as x, y pairs
 * CgEasing fast = CgEasings.bake(custom, 16);                      // a piecewise-linear copy
 * }</pre>
 *
 * <p>Robert Penner's easing equations (BSD licence), in the forms easings.net gives them; Effekseer's easing table
 * (MIT) carries the same set. Every one clamps progress to 0..1.</p>
 */
public final class CgEasings {

    private CgEasings() {
    }

    private static final double BACK = 1.70158, BACK_IN_OUT = BACK * 1.525, ELASTIC = 2 * Math.PI / 3,
            ELASTIC_IN_OUT = 2 * Math.PI / 4.5;

    public static final CgEasing LINEAR = t -> CgEasing.clamp(t);

    public static final CgEasing IN_SINE = t -> 1 - Math.cos(CgEasing.clamp(t) * Math.PI * 0.5);
    public static final CgEasing OUT_SINE = t -> Math.sin(CgEasing.clamp(t) * Math.PI * 0.5);
    public static final CgEasing IN_OUT_SINE = t -> -(Math.cos(Math.PI * CgEasing.clamp(t)) - 1) * 0.5;

    public static final CgEasing IN_QUAD = t -> power(t, 2);
    /**
     * The gentlest decelerating curve, still moving visibly at the halfway point: what a window CHANGING SHAPE wants
     * (GNOME Shell's maximise, unmaximise and close).
     */
    public static final CgEasing OUT_QUAD = t -> 1 - power(1 - CgEasing.clamp(t), 2);
    public static final CgEasing IN_OUT_QUAD = t -> inOut(t, 2);

    public static final CgEasing IN_CUBIC = t -> power(t, 3);
    public static final CgEasing OUT_CUBIC = t -> 1 - power(1 - CgEasing.clamp(t), 3);
    public static final CgEasing IN_OUT_CUBIC = t -> inOut(t, 3);

    public static final CgEasing IN_QUART = t -> power(t, 4);
    public static final CgEasing OUT_QUART = t -> 1 - power(1 - CgEasing.clamp(t), 4);
    public static final CgEasing IN_OUT_QUART = t -> inOut(t, 4);

    public static final CgEasing IN_QUINT = t -> power(t, 5);
    public static final CgEasing OUT_QUINT = t -> 1 - power(1 - CgEasing.clamp(t), 5);
    public static final CgEasing IN_OUT_QUINT = t -> inOut(t, 5);

    public static final CgEasing IN_EXPO = t -> t <= 0 ? 0 : Math.pow(2, 10 * CgEasing.clamp(t) - 10);
    /**
     * Front-loaded: leaves at speed and glides to a stop, which reads as flight over a long distance (GNOME Shell's
     * minimise and window open). Lands exactly on 1: {@code 2^-10} is not 0, and a transform meant to end at identity
     * would otherwise stop a fraction short for good.
     */
    public static final CgEasing OUT_EXPO = t -> t >= 1 ? 1 : 1 - Math.pow(2, -10 * CgEasing.clamp(t));
    public static final CgEasing IN_OUT_EXPO = t -> {
        t = CgEasing.clamp(t);
        if (t <= 0 || t >= 1) return t;
        return t < 0.5 ? Math.pow(2, 20 * t - 10) * 0.5 : (2 - Math.pow(2, -20 * t + 10)) * 0.5;
    };

    public static final CgEasing IN_CIRC = t -> 1 - Math.sqrt(1 - power(t, 2));
    public static final CgEasing OUT_CIRC = t -> Math.sqrt(1 - power(CgEasing.clamp(t) - 1, 2));
    public static final CgEasing IN_OUT_CIRC = t -> {
        t = CgEasing.clamp(t);
        return t < 0.5 ? (1 - Math.sqrt(1 - 4 * t * t)) * 0.5 : (Math.sqrt(1 - Math.pow(-2 * t + 2, 2)) + 1) * 0.5;
    };

    /** Draws back before it goes. */
    public static final CgEasing IN_BACK = t -> {
        t = CgEasing.clamp(t);
        return (BACK + 1) * t * t * t - BACK * t * t;
    };
    /** Overshoots, then settles. */
    public static final CgEasing OUT_BACK = t -> {
        t = CgEasing.clamp(t) - 1;
        return 1 + (BACK + 1) * t * t * t + BACK * t * t;
    };
    public static final CgEasing IN_OUT_BACK = t -> {
        t = CgEasing.clamp(t);
        return t < 0.5
                ? Math.pow(2 * t, 2) * ((BACK_IN_OUT + 1) * 2 * t - BACK_IN_OUT) * 0.5
                : (Math.pow(2 * t - 2, 2) * ((BACK_IN_OUT + 1) * (t * 2 - 2) + BACK_IN_OUT) + 2) * 0.5;
    };

    public static final CgEasing IN_ELASTIC = t -> {
        t = CgEasing.clamp(t);
        if (t <= 0 || t >= 1) return t;
        return -Math.pow(2, 10 * t - 10) * Math.sin((t * 10 - 10.75) * ELASTIC);
    };
    public static final CgEasing OUT_ELASTIC = t -> {
        t = CgEasing.clamp(t);
        if (t <= 0 || t >= 1) return t;
        return Math.pow(2, -10 * t) * Math.sin((t * 10 - 0.75) * ELASTIC) + 1;
    };
    public static final CgEasing IN_OUT_ELASTIC = t -> {
        t = CgEasing.clamp(t);
        if (t <= 0 || t >= 1) return t;
        return t < 0.5
                ? -(Math.pow(2, 20 * t - 10) * Math.sin((20 * t - 11.125) * ELASTIC_IN_OUT)) * 0.5
                : Math.pow(2, -20 * t + 10) * Math.sin((20 * t - 11.125) * ELASTIC_IN_OUT) * 0.5 + 1;
    };

    public static final CgEasing OUT_BOUNCE = t -> bounce(CgEasing.clamp(t));
    public static final CgEasing IN_BOUNCE = t -> 1 - bounce(1 - CgEasing.clamp(t));
    public static final CgEasing IN_OUT_BOUNCE = t -> {
        t = CgEasing.clamp(t);
        return t < 0.5 ? (1 - bounce(1 - 2 * t)) * 0.5 : (1 + bounce(2 * t - 1)) * 0.5;
    };

    /** CSS's {@code ease}, {@code ease-in}, {@code ease-out} and {@code ease-in-out}. */
    public static final CgEasing EASE = cubicBezier(0.25, 0.1, 0.25, 1.0), EASE_IN = cubicBezier(0.42, 0.0, 1.0, 1.0),
            EASE_OUT = cubicBezier(0.0, 0.0, 0.58, 1.0), EASE_IN_OUT = cubicBezier(0.42, 0.0, 0.58, 1.0);

    /** CSS's {@code cubic-bezier(x1, y1, x2, y2)}, from (0, 0) to (1, 1). */
    public static CgCubicBezier cubicBezier(double x1, double y1, double x2, double y2) {
        return new CgCubicBezier(x1, y1, x2, y2);
    }

    /** A cubic Bézier from {@code startY} to {@code endY} rather than 0 to 1. */
    public static CgCubicBezier cubicBezier(double startY, double x1, double y1, double x2, double y2, double endY) {
        return new CgCubicBezier(startY, x1, y1, x2, y2, endY);
    }

    /**
     * CSS's {@code linear()}, and its simpler cases.
     *
     * <pre>{@code
     * linear()                              // 0 to 1
     * linear(42)                            // always 42
     * linear(10, 100)                       // 10 to 100
     * linear(0, 0, 0.25, 0.8, 1, 1)         // stops as x, y pairs; x in 0..1, strictly increasing
     * }</pre>
     *
     * Stops missing an x of 0 or 1 hold their nearest value out to it.
     *
     * @throws IllegalArgumentException for stops not in pairs, out of 0..1, or not increasing
     */
    public static CgEasing linear(double... values) {
        if (values == null || values.length == 0) return new CgLinearEasing(0, 1);
        if (values.length == 1) return new CgConstantEasing(values[0]);
        if (values.length == 2) return new CgLinearEasing(values[0], values[1]);
        if (values.length % 2 != 0) throw new IllegalArgumentException("Linear stops must come in pairs: x, y");
        return new CgPiecewiseLinear(values);
    }

    /** {@code easing} sampled at {@code segments} even steps into a piecewise-linear copy; a constant as it is. */
    public static CgEasing bake(CgEasing easing, int segments) {
        if (easing instanceof CgConstantEasing) return easing;
        segments = Math.max(segments, 1);
        double[] x = new double[segments + 1];
        for (int i = 0; i <= segments; i++) x[i] = i / (double) segments;
        return bake(easing, x);
    }

    /** {@code easing} sampled at the given progress values (sorted and clamped to 0..1) into a piecewise-linear copy. */
    public static CgEasing bake(CgEasing easing, double... xPositions) {
        if (easing instanceof CgConstantEasing) return easing;
        if (xPositions == null || xPositions.length == 0) throw new IllegalArgumentException("At least one xPosition must be provided");
        double[] sorted = xPositions.clone();
        Arrays.sort(sorted);
        double[] stops = new double[sorted.length * 2];
        for (int i = 0; i < sorted.length; i++) {
            double x = Math.max(0, Math.min(1, sorted[i]));
            stops[i * 2] = x;
            stops[i * 2 + 1] = easing.ease(x);
        }
        return new CgPiecewiseLinear(stops);
    }

    private static double power(double t, int n) {
        t = CgEasing.clamp(t);
        double r = t;
        for (int i = 1; i < n; i++) r *= t;
        return r;
    }

    private static double inOut(double t, int n) {
        t = CgEasing.clamp(t);
        return t < 0.5 ? power(2 * t, n) * 0.5 : 1 - power(2 - 2 * t, n) * 0.5;
    }

    private static double bounce(double t) {
        final double n = 7.5625, d = 2.75;
        if (t < 1 / d) return n * t * t;
        if (t < 2 / d) return n * (t -= 1.5 / d) * t + 0.75;
        if (t < 2.5 / d) return n * (t -= 2.25 / d) * t + 0.9375;
        return n * (t -= 2.625 / d) * t + 0.984375;
    }
}
