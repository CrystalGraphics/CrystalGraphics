package com.crystalgraphics.easing;

/**
 * CSS's {@code linear()} with stops: straight segments between (x, y) points, found by binary search. What
 * {@link CgEasings#linear} answers for stops, and what {@link CgEasings#bake} turns a costly curve into.
 *
 * <pre>{@code
 * CgEasing stops = CgEasings.linear(0, 0, 0.25, 0.8, 0.75, 0.2, 1, 1);
 * }</pre>
 *
 * <ul>
 *   <li>x must lie in 0..1 and strictly increase.</li>
 *   <li>Missing an x of 0 or 1, the nearest stop's value holds out to it.</li>
 * </ul>
 */
public final class CgPiecewiseLinear implements CgEasing {

    private final double[] xs;
    private final double[] ys;

    /** @param stops x0, y0, x1, y1, ... */
    CgPiecewiseLinear(double... stops) {
        if (stops.length % 2 != 0) throw new IllegalArgumentException("Stops must be paired: x, y");
        int n = stops.length / 2;
        boolean addStart = stops[0] > 0, addEnd = stops[stops.length - 2] < 1;
        int count = n + (addStart ? 1 : 0) + (addEnd ? 1 : 0);
        xs = new double[count];
        ys = new double[count];
        int o = addStart ? 1 : 0;
        double previous = -1;
        for (int i = 0; i < n; i++) {
            double x = stops[i * 2], y = stops[i * 2 + 1];
            if (x < 0 || x > 1) throw new IllegalArgumentException("x values must be in [0,1]: " + x);
            if (i > 0 && x <= previous) throw new IllegalArgumentException("x values must be strictly increasing: " + x + " <= " + previous);
            xs[o + i] = x;
            ys[o + i] = y;
            previous = x;
        }
        if (addStart) {
            xs[0] = 0;
            ys[0] = ys[1];
        }
        if (addEnd) {
            xs[count - 1] = 1;
            ys[count - 1] = ys[count - 2];
        }
    }

    @Override
    public double ease(double time) {
        if (time <= 0) return ys[0];
        if (time >= 1) return ys[ys.length - 1];
        int low = 0, high = xs.length - 1;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (xs[mid] < time) low = mid + 1;
            else high = mid;
        }
        int i = Math.max(low - 1, 0);
        double span = xs[i + 1] - xs[i];
        if (span == 0) return ys[i];
        return ys[i] + (time - xs[i]) / span * (ys[i + 1] - ys[i]);
    }

    public double[] getXStops() {
        return xs.clone();
    }

    public double[] getYValues() {
        return ys.clone();
    }

    public int getSegmentCount() {
        return xs.length - 1;
    }
}
