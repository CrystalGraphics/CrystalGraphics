package com.crystalgraphics.shape;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.world.CgHostShapes;
import com.crystalgraphics.shape.bounds.CgAABB;
import com.crystalgraphics.shape.primitive.CgBox;
import com.crystalgraphics.shape.primitive.CgCompound;
import com.crystalgraphics.shape.primitive.CgConvex;
import org.joml.Vector3d;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

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
 * A Minecraft block shape on any version, placed at the block's corner:
 *
 * <pre>{@code
 * CgShape stair = CgShape.of(state.getCollisionShape(level, pos));      // null for air, flowers, open gates
 * if (stair != null && CgShapes.overlaps(BLAST, blastPose, stair, pose.at(pos.getX(), pos.getY(), pos.getZ()))) ...
 * }</pre>
 *
 * <ul>
 *   <li>A shape that is not {@link #isConvex() convex} (a shell, a ring, a cone wider than 90 degrees, a compound)
 *       answers the support of its convex hull; tests against it are its own, not the hull's.</li>
 *   <li>Local +Z is forward and +Y up, which {@link CgPose#facing} turns to a look.</li>
 * </ul>
 */
public abstract class CgShape {

    /** Conversions of host shapes that never change, so Minecraft's shared block shapes convert once each. */
    private static final Map<Object, Object> HOST_SHAPES = Collections.synchronizedMap(new WeakHashMap<>());
    /** An empty host shape's entry. */
    private static final Object NOTHING = new Object();
    private static CgHostShapes host = CgHostShapes.NONE;

    /**
     * A host block shape (modern's {@code VoxelShape}), host box, or {@code List} of host boxes, as a compound of boxes
     * in the block's own space, 0 to 1; null when it has no boxes. A block's shared shape converts once.
     *
     * @throws IllegalArgumentException for an object the host does not know, and anywhere with no host
     */
    public static CgShape of(Object hostShape) {
        CgHostShapes h = host;
        if (h == CgHostShapes.NONE) host = h = CgPlatform.get(CgHostShapes.SERVICE);
        boolean keep = h.immutable(hostShape);
        Object known = keep ? HOST_SHAPES.get(hostShape) : null;
        if (known != null) return known == NOTHING ? null : (CgShape) known;
        double[] boxes = new double[6 * 8];
        int n = h.boxes(hostShape, boxes);
        if (n * 6 > boxes.length) {
            boxes = new double[n * 6];
            h.boxes(hostShape, boxes);
        }
        CgShape shape = null;
        if (n > 0) {
            CgCompound.Builder b = CgCompound.builder();
            for (int i = 0; i < n * 6; i += 6) {
                b.add(CgBox.of((boxes[i + 3] - boxes[i]) / 2, (boxes[i + 4] - boxes[i + 1]) / 2,
                        (boxes[i + 5] - boxes[i + 2]) / 2), (boxes[i] + boxes[i + 3]) / 2,
                        (boxes[i + 1] + boxes[i + 4]) / 2, (boxes[i + 2] + boxes[i + 5]) / 2);
            }
            shape = b.build();
        }
        if (keep) HOST_SHAPES.put(hostShape, shape == null ? NOTHING : shape);
        return shape;
    }

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
