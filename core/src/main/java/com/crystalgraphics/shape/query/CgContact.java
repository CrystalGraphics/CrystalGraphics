package com.crystalgraphics.shape.query;

import org.joml.Vector3d;

/**
 * Where two shapes overlap and how deeply: what {@link CgShapes#contact} fills. Reused; its fields are overwritten.
 *
 * <pre>{@code
 * private final CgContact contact = new CgContact();
 *
 * if (CgShapes.contact(BLADE, bladePose, HEAD, headPose, contact)) {
 *     push(target, contact.normal, contact.depth);           // move b along the normal by depth to separate them
 * }
 * }</pre>
 */
public final class CgContact {

    /** On a's surface, farthest along the normal: where a reaches into b. In the world. */
    public final Vector3d pointA = new Vector3d();
    /** On b's surface, farthest against the normal; {@code pointA - pointB} is the normal times the depth. */
    public final Vector3d pointB = new Vector3d();
    /** Unit length, from a toward b: the way to move b to separate them. */
    public final Vector3d normal = new Vector3d();
    /** How far b must move along {@link #normal} to separate them; never negative. */
    public double depth;

    @Override
    public String toString() {
        return "CgContact[a " + pointA + ", b " + pointB + ", normal " + normal + ", depth " + depth + "]";
    }
}
