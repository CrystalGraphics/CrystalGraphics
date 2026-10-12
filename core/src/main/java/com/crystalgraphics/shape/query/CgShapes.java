package com.crystalgraphics.shape.query;

import com.crystalgraphics.shape.CgPose;
import com.crystalgraphics.shape.CgShape;
import com.crystalgraphics.shape.bounds.CgAABBc;
import com.crystalgraphics.shape.primitive.CgSphere;

/**
 * Exact tests between placed shapes: whether they overlap, how far apart they are, how deeply they meet, and where a
 * ray or a moving shape first touches one. What a world query and a hitbox run on each candidate their broadphase
 * finds.
 *
 * <pre>{@code
 * static final CgShape BLAST = CgSphere.of(6);
 * static final CgShape CLEAVE = CgCone.of(8, Math.toRadians(35));
 *
 * if (CgShapes.overlaps(BLAST, pose.at(x, y, z), entityBox)) damage(entity);       // a CgAABBc
 * if (CgShapes.overlaps(CLEAVE, pose.at(eyeX, eyeY, eyeZ).facing(yaw, pitch), WARD, wardPose)) parry();
 * double gap = CgShapes.distance(BLAST, pose, WARD, wardPose);              // 0 when they overlap
 * }</pre>
 *
 * Contacts and casts fill a reused result:
 *
 * <pre>{@code
 * private final CgContact contact = new CgContact();
 * private final CgCastHit hit = new CgCastHit();
 *
 * if (CgShapes.contact(BLADE, bladePose, WARD, wardPose, contact)) knock(contact.normal, contact.depth);
 * if (CgShapes.castRay(WARD, wardPose, eyeX, eyeY, eyeZ, lookX, lookY, lookZ, 32, hit)) spark(hit.point);
 * if (CgShapes.cast(BOLT, boltPose, vx, vy, vz, WARD, wardPose, hit)) stopAt(hit.fraction);   // the bolt moving by v
 * }</pre>
 *
 * <ul>
 *   <li>Allocates nothing past each thread's first call, and is safe from any thread.</li>
 *   <li>{@link #overlaps} takes any pair but two non-convex shapes (a shell, a ring, a cone wider than 90 degrees);
 *       {@link #castRay} takes shells and rings too. {@link #distance}, {@link #contact} and {@link #cast} take convex
 *       shapes and compounds of them. Anything else throws {@link IllegalArgumentException}.</li>
 *   <li>A compound answers for its nearest child, its deepest, or its first touched.</li>
 *   <li>A cast with no motion is an overlap test: a hit at 0 with a zero normal.</li>
 * </ul>
 */
public final class CgShapes {

    private CgShapes() {
    }

    public static boolean overlaps(CgShape a, CgPose poseA, CgShape b, CgPose poseB) {
        return CgNarrowPhase.CURRENT.get().overlaps(a, poseA, b, poseB);
    }

    /** Against a box that never turns, such as an entity's; an empty box meets nothing. */
    public static boolean overlaps(CgShape shape, CgPose pose, CgAABBc box) {
        // the commonest query of all, a blast against entities, without the per-thread state
        if (shape instanceof CgSphere) {
            if (box.isEmpty()) return false;
            double r = ((CgSphere) shape).radius();
            double dx = pose.x - Math.max(box.minX(), Math.min(pose.x, box.maxX()));
            double dy = pose.y - Math.max(box.minY(), Math.min(pose.y, box.maxY()));
            double dz = pose.z - Math.max(box.minZ(), Math.min(pose.z, box.maxZ()));
            return dx * dx + dy * dy + dz * dz <= r * r;
        }
        return CgNarrowPhase.CURRENT.get().overlaps(shape, pose, box);
    }

    /** The gap between them; 0 when they overlap. */
    public static double distance(CgShape a, CgPose poseA, CgShape b, CgPose poseB) {
        return CgNarrowPhase.CURRENT.get().distance(a, poseA, b, poseB);
    }

    /**
     * Whether they overlap, with the deepest points and the way to separate them; false when they only touch. Between
     * two curved shapes with no closed form (an ellipsoid and a ball) the depth may fall short by about 1e-4 of itself.
     */
    public static boolean contact(CgShape a, CgPose poseA, CgShape b, CgPose poseB, CgContact out) {
        return CgNarrowPhase.CURRENT.get().contact(a, poseA, b, poseB, out);
    }

    /**
     * The first point of {@code shape} along a ray, within {@code maxDistance} blocks.
     *
     * @throws IllegalArgumentException for a zero direction
     */
    public static boolean castRay(CgShape shape, CgPose pose, double originX, double originY, double originZ,
                                  double dirX, double dirY, double dirZ, double maxDistance, CgCastHit out) {
        return CgNarrowPhase.CURRENT.get().castRay(shape, pose, originX, originY, originZ, dirX, dirY, dirZ,
                maxDistance, out);
    }

    /** Where {@code a}, moving by {@code (moveX, moveY, moveZ)} from {@code poseA}, first touches {@code b}. */
    public static boolean cast(CgShape a, CgPose poseA, double moveX, double moveY, double moveZ, CgShape b,
                               CgPose poseB, CgCastHit out) {
        return CgNarrowPhase.CURRENT.get().cast(a, poseA, moveX, moveY, moveZ, b, poseB, out);
    }

    /** Where {@code shape}, moving by {@code (moveX, moveY, moveZ)} from {@code pose}, first touches a box. */
    public static boolean cast(CgShape shape, CgPose pose, double moveX, double moveY, double moveZ, CgAABBc box,
                               CgCastHit out) {
        return CgNarrowPhase.CURRENT.get().cast(shape, pose, moveX, moveY, moveZ, box, out);
    }
}
