package com.crystalgraphics.easing;

/**
 * A timing function: maps progress through an animation, 0 to 1, to how far its value has gone, usually 0 to 1 as
 * well (BACK and ELASTIC leave that range on the way). What a transition, a tween and an effect's curve all ease by.
 *
 * <pre>{@code
 * CgEasing out = CgEasings.OUT_CUBIC;
 * double k = out.ease(0.5);                         // 0.875
 * CgEasing css = CgEasings.cubicBezier(0.42, 0, 0.58, 1);
 * CgEasing mine = t -> t * t;                       // any lambda
 * }</pre>
 *
 * <ul>
 *   <li>{@link #ease(double)} may be asked outside 0..1; the library's easings clamp.</li>
 *   <li>{@link #bake} trades a costly curve (a cubic Bézier) for a piecewise-linear copy that evaluates in a binary
 *       search, for something evaluated many times a frame.</li>
 * </ul>
 */
@FunctionalInterface
public interface CgEasing {

    static double clamp(double t) {
        if (t < 0) return 0;
        if (t > 1) return 1;
        return t;
    }

    double ease(double progress);

    /** {@code current / max} of the way through, clamped. */
    default double ease(double current, double max) {
        return ease(clamp(current / max));
    }

    default double ease(double startTime, double duration, double currentTime) {
        return ease(currentTime - startTime, duration);
    }

    /** For high-precision clocks: nanoseconds or milliseconds. */
    default double ease(long current, long max) {
        return ease(clamp((double) current / max));
    }

    default double ease(long startTime, long duration, long currentTime) {
        return ease(currentTime - startTime, duration);
    }

    /** A piecewise-linear copy sampled at {@code segments} even steps; itself when that is no cheaper. */
    default CgEasing bake(int segments) {
        if (this instanceof CgLinearEasing) return this;
        if (this instanceof CgPiecewiseLinear piecewise && segments >= piecewise.getSegmentCount()) return this;
        return CgEasings.bake(this, segments);
    }
}
