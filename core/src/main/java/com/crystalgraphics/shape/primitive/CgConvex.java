package com.crystalgraphics.shape.primitive;

import com.crystalgraphics.shape.CgShape;
import org.joml.Vector3d;

import java.util.Objects;

/**
 * Any other convex shape: the hull of some points, or a mod's own shape given by its support function. Every exact
 * test takes it as it takes the built-in shapes.
 *
 * <pre>{@code
 * static final CgShape WEDGE = CgConvex.hull(new double[]{
 *         -1, 0, 0,   1, 0, 0,   -1, 0, 2,   1, 0, 2,   -1, 1, 0,   1, 1, 0});
 * }</pre>
 *
 * A shape of your own, by the farthest point along a direction:
 *
 * <pre>{@code
 * static final CgShape LENS = CgConvex.of((dx, dy, dz, out) -> ...);   // must answer a convex set, in its own space
 * }</pre>
 *
 * <ul>
 *   <li>{@link #hull} takes the points as they are, with no hull built: a few dozen is cheap, thousands are not.</li>
 *   <li>A support function answering a set that is not convex is tested as its hull.</li>
 * </ul>
 */
public final class CgConvex extends CgShape {

    /** The farthest point of a convex set along {@code (dx, dy, dz)}, written into {@code out}. */
    @FunctionalInterface
    public interface Support {
        /** The direction need not be unit length, and may be zero: answer any point of the set then. Allocates nothing. */
        Vector3d support(double dx, double dy, double dz, Vector3d out);
    }

    private final double[] points;
    private final Support support;

    private CgConvex(double[] points, Support support) {
        this.points = points;
        this.support = support;
    }

    /**
     * The hull of points given as {@code x, y, z} triples, copied.
     *
     * @throws IllegalArgumentException for no points, a count not a multiple of three, or a non-finite coordinate
     */
    public static CgConvex hull(double[] xyz) {
        if (xyz.length == 0 || xyz.length % 3 != 0) {
            throw new IllegalArgumentException("points come as x, y, z triples: " + xyz.length + " numbers");
        }
        for (double v : xyz) CgShapeArgs.finite("a point's coordinate", v);
        return new CgConvex(xyz.clone(), null);
    }

    public static CgConvex of(Support support) {
        return new CgConvex(null, Objects.requireNonNull(support, "support"));
    }

    @Override
    public Vector3d support(double dx, double dy, double dz, Vector3d out) {
        if (support != null) return support.support(dx, dy, dz, out);
        int best = 0;
        double bestDot = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < points.length; i += 3) {
            double dot = points[i] * dx + points[i + 1] * dy + points[i + 2] * dz;
            if (dot > bestDot) {
                bestDot = dot;
                best = i;
            }
        }
        return out.set(points[best], points[best + 1], points[best + 2]);
    }

    @Override
    public boolean isConvex() {
        return true;
    }

    @Override
    public String toString() {
        return support != null ? "CgConvex[" + support + "]" : "CgConvex[" + points.length / 3 + " points]";
    }
}
