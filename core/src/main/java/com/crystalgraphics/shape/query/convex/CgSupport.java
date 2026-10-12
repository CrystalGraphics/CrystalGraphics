package com.crystalgraphics.shape.query.convex;

import org.joml.Vector3d;

/**
 * A convex set by its support function, in the frame a query works in: what {@link CgGjk} and {@link CgEpa} test.
 * {@link CgPlacedSupport} puts a shape, a box or a point there.
 *
 * <pre>{@code
 * CgSupport ball = (dx, dy, dz, out) -> {                     // a ball of radius 2 at (5, 0, 0)
 *     double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
 *     return len == 0 ? out.set(5, 0, 2) : out.set(5 + 2 * dx / len, 2 * dy / len, 2 * dz / len);
 * };
 * }</pre>
 *
 * <ul>
 *   <li>The direction need not be unit length, and may be zero: answer any point of the set then.</li>
 *   <li>Answer into {@code out} and return it; allocate nothing.</li>
 * </ul>
 */
public interface CgSupport {

    /** The point of the set farthest along {@code (dx, dy, dz)}. */
    Vector3d support(double dx, double dy, double dz, Vector3d out);
}
