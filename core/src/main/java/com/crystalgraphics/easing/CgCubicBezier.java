package com.crystalgraphics.easing;

import lombok.Getter;

import java.util.Objects;

/**
 * CSS's {@code cubic-bezier(x1, y1, x2, y2)}: progress is the curve's x, the eased value its y. Made through
 * {@link CgEasings#cubicBezier}.
 *
 * <pre>{@code
 * CgCubicBezier ease = CgEasings.cubicBezier(0.25, 0.1, 0.25, 1.0);
 * double y = ease.ease(0.5);
 * }</pre>
 *
 * <ul>
 *   <li>Solves x for t by Newton-Raphson from a sampled guess, falling back to bisection where the curve is flat.</li>
 *   <li>Equal by its numbers, so one read back from CSS equals the one written.</li>
 *   <li>Costlier than a lambda: {@link #bake} it for anything evaluated many times a frame.</li>
 * </ul>
 */
public final class CgCubicBezier implements CgEasing {

    private static final int NEWTON_ITERATIONS = 8, BISECTION_LIMIT = 30, SAMPLES = 11;
    private static final double CONVERGED = 1e-10, EPSILON = 1e-12;

    @Getter
    private final double x1, y1, x2, y2;
    private final double startY, endY;
    // X(t) = ((ax t + bx) t + cx) t; Y(t) = ((ay t + by) t + cy) t + startY
    private final double ax, bx, cx, ay, by, cy;
    private final double[] samples = new double[SAMPLES];

    CgCubicBezier(double x1, double y1, double x2, double y2) {
        this(0, x1, y1, x2, y2, 1);
    }

    CgCubicBezier(double startY, double x1, double y1, double x2, double y2, double endY) {
        this.startY = startY;
        this.endY = endY;
        this.x1 = x1;
        this.y1 = y1;
        this.x2 = x2;
        this.y2 = y2;
        cx = 3 * x1;
        bx = 3 * (x2 - x1) - cx;
        ax = 1 - cx - bx;
        cy = 3 * (y1 - startY);
        by = 3 * (y2 - y1) - cy;
        ay = endY - startY - cy - by;
        for (int i = 0; i < SAMPLES; i++) samples[i] = x(i / (SAMPLES - 1.0));
    }

    @Override
    public double ease(double time) {
        if (time <= 0.0) return startY;
        if (time >= 1.0) return endY;
        if (Math.abs(ax) < EPSILON && Math.abs(bx) < EPSILON) return startY + time * (endY - startY);
        double t = guess(time);
        for (int i = 0; i < NEWTON_ITERATIONS; i++) {
            double slope = (3 * ax * t + 2 * bx) * t + cx;
            if (Math.abs(slope) < EPSILON) break;
            double delta = (x(t) - time) / slope;
            t -= delta;
            if (Math.abs(delta) < CONVERGED) return y(t);
            if (t < 0.0 || t > 1.0) break;
        }
        double low = 0.0, high = 1.0;
        for (int i = 0; i < BISECTION_LIMIT && high - low >= EPSILON; i++) {
            double mid = (low + high) * 0.5;
            if (x(mid) < time) low = mid;
            else high = mid;
        }
        return y((low + high) * 0.5);
    }

    private double x(double t) {
        return ((ax * t + bx) * t + cx) * t;
    }

    private double y(double t) {
        return ((ay * t + by) * t + cy) * t + startY;
    }

    /** The sample just below {@code x}, as a starting t. */
    private double guess(double x) {
        int low = 0, high = SAMPLES - 1;
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (samples[mid] < x) low = mid;
            else high = mid - 1;
        }
        return low / (SAMPLES - 1.0);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CgCubicBezier other)) return false;
        return Double.compare(x1, other.x1) == 0 && Double.compare(y1, other.y1) == 0
                && Double.compare(x2, other.x2) == 0 && Double.compare(y2, other.y2) == 0
                && Double.compare(startY, other.startY) == 0 && Double.compare(endY, other.endY) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(x1, y1, x2, y2, startY, endY);
    }
}
