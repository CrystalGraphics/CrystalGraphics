package com.crystalgraphics.animation.ik;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * The solvers' shared maths, matching Unreal's: the rotation between two directions ({@code FQuat::FindBetweenNormals}),
 * a rotation split into swing and twist ({@code FQuat::ToSwingTwist}) and the two-bone solve
 * ({@code AnimationCore::SolveTwoBoneIK}, TwoBoneIK.cpp). Written from their behaviour, not copied.
 *
 * <pre>{@code
 * CgIkMath.findBetweenNormals(oldDir, newDir, delta);          // the shortest turn from one unit vector to another
 * CgIkMath.twoBone(root, joint, end, jointTarget, effector, upper, lower, false, 1f, 1.2f, outJoint, outEnd);
 * }</pre>
 */
public final class CgIkMath {

    private static final float KINDA_SMALL = 1e-4f;

    private CgIkMath() {
    }

    /**
     * The rotation taking unit {@code a} onto unit {@code b}, into {@code out}: {@code FQuat::FindBetween_Helper}. Opposite
     * vectors turn half round an axis perpendicular to {@code a}, the one Unreal picks.
     */
    public static Quaternionf findBetweenNormals(Vector3fc a, Vector3fc b, Quaternionf out) {
        float w = 1f + a.dot(b);
        if (w >= 1e-6f) {
            out.set(a.y() * b.z() - a.z() * b.y(), a.z() * b.x() - a.x() * b.z(), a.x() * b.y() - a.y() * b.x(), w);
        } else if (Math.abs(a.x()) > Math.abs(a.z())) {
            out.set(-a.y(), a.x(), 0f, 0f);
        } else {
            out.set(0f, -a.z(), a.y(), 0f);
        }
        return out.normalize();
    }

    /**
     * {@code q} split about unit {@code axis} into a swing and a twist, {@code q = swing * twist}: {@code FQuat::ToSwingTwist}.
     */
    public static void swingTwist(Quaternionf q, Vector3fc axis, Quaternionf swing, Quaternionf twist) {
        float d = q.x * axis.x() + q.y * axis.y() + q.z * axis.z();
        twist.set(axis.x() * d, axis.y() * d, axis.z() * d, q.w);
        float len = (float) Math.sqrt(twist.lengthSquared());
        if (len == 0f) twist.identity(); else twist.normalize();
        swing.set(twist).conjugate().premul(q);
    }

    /**
     * Where the joint and the end go to reach {@code effector} from {@code root} with limbs {@code upper} and {@code lower}
     * long, the joint bending toward {@code jointTarget}: {@code SolveTwoBoneIK}'s positions. Out of reach, the limb
     * straightens toward it; with {@code stretch}, the limbs lengthen from {@code startStretch} of their reach up to
     * {@code maxStretch} times.
     */
    public static void twoBone(Vector3fc root, Vector3fc joint, Vector3fc jointTarget, Vector3fc effector, float upper,
                               float lower, boolean stretch, float startStretch, float maxStretch, Vector3f outJoint,
                               Vector3f outEnd, Vector3f scratchDir, Vector3f scratchNormal, Vector3f scratchBend) {
        Vector3f dir = scratchDir.set(effector).sub(root);
        float length = dir.length();
        float max = upper + lower;
        if (length < KINDA_SMALL) {
            length = KINDA_SMALL;
            dir.set(1f, 0f, 0f);
        } else {
            dir.div(length);
        }
        Vector3f bend = scratchBend.set(jointTarget).sub(root);
        Vector3f normal = scratchNormal;
        if (bend.lengthSquared() < KINDA_SMALL * KINDA_SMALL) {
            bend.set(0f, 1f, 0f);
        } else {
            dir.cross(bend, normal);
            if (normal.lengthSquared() < KINDA_SMALL * KINDA_SMALL) {
                bestAxis(dir, normal);   // FindBestAxisVectors(PlaneNormal, BendDir): the bend its second axis
                normal.cross(dir, bend);
            } else {
                bend.fma(-bend.dot(dir), dir).normalize();
            }
        }
        if (stretch) {
            float range = maxStretch - startStretch;
            if (range > KINDA_SMALL && max > KINDA_SMALL) {
                float reach = length / max;
                float factor = (maxStretch - 1f) * Math.max(0f, Math.min(1f, (reach - startStretch) / range));
                if (factor > KINDA_SMALL) {
                    lower *= 1f + factor;
                    upper *= 1f + factor;
                    max *= 1f + factor;
                }
            }
        }
        outEnd.set(effector);
        outJoint.set(joint);
        if (length >= max) {
            outEnd.set(dir).mul(max).add(root);
            outJoint.set(dir).mul(upper).add(root);
        } else {
            float twoAB = 2f * upper * length;
            float cos = twoAB != 0f ? (upper * upper + length * length - lower * lower) / twoAB : 0f;
            float angle = (float) Math.acos(Math.max(-1f, Math.min(1f, cos)));
            float jointLine = upper * (float) Math.sin(angle);
            float projSq = upper * upper - jointLine * jointLine;
            float proj = projSq > 0f ? (float) Math.sqrt(projSq) : 0f;
            if (cos < 0f) proj = -proj;
            outJoint.set(root).fma(proj, dir).fma(jointLine, bend);
        }
    }

    /** {@code FVector::FindBestAxisVectors}' first axis, perpendicular to {@code n}, into {@code out}; its second is {@code out × n}. */
    private static void bestAxis(Vector3f n, Vector3f out) {
        float nx = Math.abs(n.x), ny = Math.abs(n.y), nz = Math.abs(n.z);
        if (nz > nx && nz > ny) out.set(1f, 0f, 0f); else out.set(0f, 0f, 1f);
        out.fma(-out.dot(n), n).normalize();
    }
}
