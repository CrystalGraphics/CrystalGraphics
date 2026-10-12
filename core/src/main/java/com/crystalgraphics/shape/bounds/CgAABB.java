package com.crystalgraphics.shape.bounds;

/**
 * An axis-aligned box, mutable and reused: the one bounds type for drawing, shapes and world queries. Hand out
 * {@link CgAABBc} where nobody should change it.
 *
 * <pre>{@code
 * private final CgAABB box = new CgAABB();                        // a field: nothing allocated per frame
 *
 * box.set(-r, -r, -r, r, r + height, r).grow(pad);
 * if (box.intersects(area)) ...
 * }</pre>
 *
 * Growing a box over points, from empty:
 *
 * <pre>{@code
 * box.empty();
 * for (int i = 0; i < n; i++) box.union(xs[i], ys[i], zs[i]);
 * }</pre>
 *
 * <ul>
 *   <li>{@code new CgAABB()} is empty, not a point at the origin: a union over nothing stays empty.</li>
 *   <li>Every setter returns this box, and none checks that min is below max: an inverted box is simply empty.</li>
 * </ul>
 */
public final class CgAABB implements CgAABBc {

    public double minX, minY, minZ, maxX, maxY, maxZ;

    /** An empty box. */
    public CgAABB() {
        empty();
    }

    public CgAABB(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        set(minX, minY, minZ, maxX, maxY, maxZ);
    }

    public CgAABB(CgAABBc other) {
        set(other);
    }

    public static CgAABB of(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        return new CgAABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /** The box {@code half} out from the centre on each axis. */
    public static CgAABB ofCenter(double cx, double cy, double cz, double hx, double hy, double hz) {
        return new CgAABB().setCenterHalf(cx, cy, cz, hx, hy, hz);
    }

    public CgAABB set(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
        return this;
    }

    public CgAABB set(CgAABBc other) {
        return set(other.minX(), other.minY(), other.minZ(), other.maxX(), other.maxY(), other.maxZ());
    }

    public CgAABB setCenterHalf(double cx, double cy, double cz, double hx, double hy, double hz) {
        return set(cx - hx, cy - hy, cz - hz, cx + hx, cy + hy, cz + hz);
    }

    /** Empties the box, ready for {@link #union}. */
    public CgAABB empty() {
        return set(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY);
    }

    /** Grows the box to hold the point. */
    public CgAABB union(double x, double y, double z) {
        if (x < minX) minX = x;
        if (y < minY) minY = y;
        if (z < minZ) minZ = z;
        if (x > maxX) maxX = x;
        if (y > maxY) maxY = y;
        if (z > maxZ) maxZ = z;
        return this;
    }

    /** Grows the box to hold {@code other}; an empty {@code other} changes nothing. */
    public CgAABB union(CgAABBc other) {
        if (other.isEmpty()) return this;
        minX = Math.min(minX, other.minX());
        minY = Math.min(minY, other.minY());
        minZ = Math.min(minZ, other.minZ());
        maxX = Math.max(maxX, other.maxX());
        maxY = Math.max(maxY, other.maxY());
        maxZ = Math.max(maxZ, other.maxZ());
        return this;
    }

    /** Moves every face out by {@code d}, or in for a negative one. An empty box stays empty. */
    public CgAABB grow(double d) {
        return grow(d, d, d);
    }

    public CgAABB grow(double dx, double dy, double dz) {
        if (isEmpty()) return this;
        return set(minX - dx, minY - dy, minZ - dz, maxX + dx, maxY + dy, maxZ + dz);
    }

    public CgAABB translate(double dx, double dy, double dz) {
        return set(minX + dx, minY + dy, minZ + dz, maxX + dx, maxY + dy, maxZ + dz);
    }

    @Override
    public double minX() {
        return minX;
    }

    @Override
    public double minY() {
        return minY;
    }

    @Override
    public double minZ() {
        return minZ;
    }

    @Override
    public double maxX() {
        return maxX;
    }

    @Override
    public double maxY() {
        return maxY;
    }

    @Override
    public double maxZ() {
        return maxZ;
    }

    @Override
    public String toString() {
        return isEmpty() ? "CgAABB[empty]"
                : "CgAABB[" + minX + ", " + minY + ", " + minZ + " .. " + maxX + ", " + maxY + ", " + maxZ + "]";
    }

    /** {@code Math.max}, with NaN (a ray in a slab's plane) losing: the slab test's. */
    static double nanLosesMax(double a, double b) {
        return a > b || Double.isNaN(b) ? a : b;
    }

    static double nanLosesMin(double a, double b) {
        return a < b || Double.isNaN(b) ? a : b;
    }

    /** A box nothing can change: the constants. */
    static final class Fixed implements CgAABBc {

        private final double minX, minY, minZ, maxX, maxY, maxZ;

        Fixed(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
        }

        @Override
        public double minX() {
            return minX;
        }

        @Override
        public double minY() {
            return minY;
        }

        @Override
        public double minZ() {
            return minZ;
        }

        @Override
        public double maxX() {
            return maxX;
        }

        @Override
        public double maxY() {
            return maxY;
        }

        @Override
        public double maxZ() {
            return maxZ;
        }

        @Override
        public String toString() {
            return "CgAABBc[" + minX + ", " + minY + ", " + minZ + " .. " + maxX + ", " + maxY + ", " + maxZ + "]";
        }
    }
}
