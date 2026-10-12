package com.crystalgraphics.shape.query;

import org.joml.Vector3d;

/**
 * Where a ray or a moving shape first touches a shape: what {@link CgShapes#castRay} and {@link CgShapes#cast} fill.
 * Reused; its fields are overwritten.
 *
 * <pre>{@code
 * private final CgCastHit hit = new CgCastHit();
 *
 * if (CgShapes.castRay(WARD, pose, eyeX, eyeY, eyeZ, lookX, lookY, lookZ, 32, hit)) {
 *     spark(hit.point, hit.normal);                          // hit.distance blocks along the look
 * }
 * }</pre>
 *
 * <ul>
 *   <li>Starting inside, the hit is at distance 0 and its normal points back against the motion.</li>
 *   <li>For a ray, {@link #fraction} is of the maximum distance; for a cast, of the motion.</li>
 * </ul>
 */
public final class CgCastHit {

    /** How far along, from 0 to 1. */
    public double fraction;
    /** How far along, in blocks. */
    public double distance;
    /** The point touched, in the world. */
    public final Vector3d point = new Vector3d();
    /** The touched surface's unit normal, facing the ray or the mover. */
    public final Vector3d normal = new Vector3d();

    @Override
    public String toString() {
        return "CgCastHit[" + distance + " (" + fraction + "), point " + point + ", normal " + normal + "]";
    }
}
