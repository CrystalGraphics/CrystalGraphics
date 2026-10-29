package com.crystalgraphics.vfx.field;

/**
 * Where a field's value can be other than 0, as a box in its placement's space, in blocks: Chaos's
 * {@code ComputeFieldBounds}. {@link #INFINITE} where it acts everywhere, as an unbounded Chaos field does.
 *
 * <pre>{@code
 * CgFieldBounds ball = CgFieldBounds.sphere(0, 1, 0, 3);          // a radial falloff of default 0
 * CgFieldBounds both = ball.intersect(CgFieldBounds.INFINITE);    // a multiply: its smaller side, here the ball
 * CgFieldBounds either = ball.union(CgFieldBounds.INFINITE);      // an add: its larger, here everywhere
 * }</pre>
 *
 * <ul>
 *   <li>A side Chaos leaves unbounded is infinite on that axis: {@link #bounded()} only when all six are finite.</li>
 * </ul>
 */
public record CgFieldBounds(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {

    /** Everywhere. */
    public static final CgFieldBounds INFINITE = new CgFieldBounds(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY,
            Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY);

    /** The box round a sphere of {@code radius} at {@code (x, y, z)}. */
    public static CgFieldBounds sphere(float x, float y, float z, float radius) {
        return new CgFieldBounds(x - radius, y - radius, z - radius, x + radius, y + radius, z + radius);
    }

    /** Whether it is finite on every side. */
    public boolean bounded() {
        return Float.isFinite(minX) && Float.isFinite(minY) && Float.isFinite(minZ)
                && Float.isFinite(maxX) && Float.isFinite(maxY) && Float.isFinite(maxZ);
    }

    /** What both hold: a multiply's or divide's, a culling outside's. */
    public CgFieldBounds intersect(CgFieldBounds o) {
        return new CgFieldBounds(Math.max(minX, o.minX), Math.max(minY, o.minY), Math.max(minZ, o.minZ),
                Math.min(maxX, o.maxX), Math.min(maxY, o.maxY), Math.min(maxZ, o.maxZ));
    }

    /** What either holds: an add's or subtract's, a culling inside's. */
    public CgFieldBounds union(CgFieldBounds o) {
        return new CgFieldBounds(Math.min(minX, o.minX), Math.min(minY, o.minY), Math.min(minZ, o.minZ),
                Math.max(maxX, o.maxX), Math.max(maxY, o.maxY), Math.max(maxZ, o.maxZ));
    }
}
