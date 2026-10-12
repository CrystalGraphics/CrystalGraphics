package com.crystalgraphics.shape;

import com.crystalgraphics.shape.bounds.CgAABB;
import com.crystalgraphics.shape.primitive.CgConvex;
import org.joml.Vector3d;

/**
 * A region of space defined about its own origin, placed by a {@link CgPose} where it is used: what a world query, a
 * hitbox or a draw's bounds take. Immutable, so a {@code static final} shape is shared freely, between a
 * singleplayer's server and client threads too.
 *
 * <pre>{@code
 * static final CgShape BLAST = CgSphere.of(6);
 * static final CgShape CLEAVE = CgCone.of(8, Math.toRadians(35));   // 8 blocks along local +Z, 35 degrees either side
 *
 * BLAST.bounds(pose.at(x, y, z), box);                       // its box in the world
 * }</pre>
 *
 * Every shape answers its {@link #support support point}, which is what an exact test between any two of them is built
 * from. A mod's own convex shape is easiest as a {@link CgConvex} over its support function.
 *
 * <ul>
 *   <li>A shape that is not {@link #isConvex() convex} (a shell, a ring, a cone wider than 90 degrees, a compound)
 *       answers the support of its convex hull; tests against it are its own, not the hull's.</li>
 *   <li>Local +Z is forward and +Y up, which {@link CgPose#facing} turns to a look.</li>
 * </ul>
 */
public abstract class CgShape {

    /** A subclass is tested through its support, so it must be convex, or say it is not: a test treats it as its hull. */
    protected CgShape() {
    }

    /**
     * The point of the shape, or of its convex hull, farthest along {@code (dx, dy, dz)}, in its own space. The
     * direction need not be unit length; a zero one answers some point of the shape.
     */
    public abstract Vector3d support(double dx, double dy, double dz, Vector3d out);

    /** Whether the shape is its own convex hull, so that {@link #support} describes it exactly. */
    public abstract boolean isConvex();

    /** Its box in its own space. */
    public CgAABB bounds(CgAABB out) {
        Vector3d s = SCRATCH.get();
        double maxX = support(1, 0, 0, s).x, maxY = support(0, 1, 0, s).y, maxZ = support(0, 0, 1, s).z;
        double minX = support(-1, 0, 0, s).x, minY = support(0, -1, 0, s).y, minZ = support(0, 0, -1, s).z;
        return out.set(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /**
     * Its box in the world when placed by {@code pose}: exact for the hull (the support along each world axis), not
     * the rotated box of its own-space box. Allocates nothing past the first call on a thread.
     */
    public CgAABB bounds(CgPose pose, CgAABB out) {
        Vector3d s = SCRATCH.get();
        // world +X seen in own space is the matrix's first row; the support's world x is that row dotted with it
        double maxX = dotRow(pose.m00(), pose.m01(), pose.m02(), support(pose.m00(), pose.m01(), pose.m02(), s));
        double minX = dotRow(pose.m00(), pose.m01(), pose.m02(), support(-pose.m00(), -pose.m01(), -pose.m02(), s));
        double maxY = dotRow(pose.m10(), pose.m11(), pose.m12(), support(pose.m10(), pose.m11(), pose.m12(), s));
        double minY = dotRow(pose.m10(), pose.m11(), pose.m12(), support(-pose.m10(), -pose.m11(), -pose.m12(), s));
        double maxZ = dotRow(pose.m20(), pose.m21(), pose.m22(), support(pose.m20(), pose.m21(), pose.m22(), s));
        double minZ = dotRow(pose.m20(), pose.m21(), pose.m22(), support(-pose.m20(), -pose.m21(), -pose.m22(), s));
        return out.set(pose.x + minX, pose.y + minY, pose.z + minZ, pose.x + maxX, pose.y + maxY, pose.z + maxZ);
    }

    private static final ThreadLocal<Vector3d> SCRATCH = ThreadLocal.withInitial(Vector3d::new);

    private static double dotRow(double a, double b, double c, Vector3d v) {
        return a * v.x + b * v.y + c * v.z;
    }
}
