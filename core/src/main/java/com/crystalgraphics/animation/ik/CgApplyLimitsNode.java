package com.crystalgraphics.animation.ik;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.crystalgraphics.animation.graph.CgAnimGraphInstance;
import com.crystalgraphics.animation.graph.CgAnimNode;
import com.crystalgraphics.animation.graph.CgAnimNodeKind;
import com.crystalgraphics.animation.graph.CgAnimNodeWriter;
import com.crystalgraphics.api.animation.CgAnimPose;
import com.crystalgraphics.api.animation.CgSkeleton;
import com.crystalgraphics.asset.tree.CgAssetException;

/**
 * Bones kept within angles of their reference pose: Unreal's Apply Limits ({@code FAnimNode_ApplyLimits},
 * AnimNode_ApplyLimits.cpp, over {@code AnimationCore::ConstrainAngularRangeUsingEuler}, AngularLimit.cpp). Each
 * bone's turn away from its rest, in its parent's space, is taken as Euler angles and each clamped.
 *
 * <pre>{@code
 * // a neck that turns at most 70 degrees either way about z and 30 about x
 * CgApplyLimitsNode.of(body).limit("neck_01", -30f, 0f, -70f, 30f, 0f, 70f)
 * }</pre>
 *
 * <ul>
 *   <li>Degrees, each angle about the axis it is named for: x the first, z the last, composed {@code Rz·Ry·Rx}.
 *       Unreal clamps yaw by its limit's x and roll by its z; an importer swaps them into these.</li>
 *   <li>A bone within its limits is left exactly as it was.</li>
 * </ul>
 */
public final class CgApplyLimitsNode extends CgSkeletalControlNode {

    /** One bone's limits: Unreal's {@code FAngularRangeLimit}. */
    private record Limit(String bone, Vector3f min, Vector3f max) {
    }

    /** Reads {@code kind: apply_limits}. */
    public static final CgAnimNodeKind KIND = (c, at, m) -> {
        CgApplyLimitsNode n = of(source(c, at, m)).alpha(alpha(c, at, m));
        if (!(m.get("limits") instanceof List<?> list) || list.isEmpty()) throw new CgAssetException(at + ".limits: none");
        for (int i = 0; i < list.size(); i++) {
            String a = at + ".limits[" + i + "]";
            if (!(list.get(i) instanceof Map<?, ?> e)) throw new CgAssetException(a + ": { bone, min, max }");
            float[] min = vector(c, a, e, "min", -180f, -180f, -180f), max = vector(c, a, e, "max", 180f, 180f, 180f);
            n = n.limit(c.string(a, e, "bone"), min[0], min[1], min[2], max[0], max[1], max[2]);
        }
        return n;
    };

    private final List<Limit> limits;

    private CgApplyLimitsNode(CgAnimNode source, CgControlAlpha alpha, List<Limit> limits) {
        super(source, alpha);
        this.limits = List.copyOf(limits);
    }

    public static CgApplyLimitsNode of(CgAnimNode source) {
        return new CgApplyLimitsNode(source, CgControlAlpha.FULL, List.of());
    }

    public CgApplyLimitsNode alpha(CgControlAlpha alpha) {
        return new CgApplyLimitsNode(source, alpha, limits);
    }

    /** Bone {@code bone}'s turn from its rest kept between {@code min} and {@code max} degrees about x, y and z. */
    public CgApplyLimitsNode limit(String bone, float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        List<Limit> next = new ArrayList<>(limits);
        next.add(new Limit(Objects.requireNonNull(bone, "bone"), new Vector3f(minX, minY, minZ), new Vector3f(maxX, maxY, maxZ)));
        return new CgApplyLimitsNode(source, alpha, next);
    }

    @Override
    protected Control control(CgAnimGraphInstance g) {
        if (limits.isEmpty()) throw new IllegalArgumentException(this + ": no limits");
        CgSkeleton skeleton = g.skeleton();
        int count = limits.size();
        // the bones ascending, so a limited parent is solved before its child
        Integer[] order = new Integer[count];
        int[] boneOf = new int[count];
        for (int i = 0; i < count; i++) {
            order[i] = i;
            boneOf[i] = bone(skeleton, limits.get(i).bone, this);
        }
        Arrays.sort(order, Comparator.comparingInt(i -> boneOf[i]));
        int[] bonesSorted = new int[count];
        float[] min = new float[count * 3], max = new float[count * 3];
        Quaternionf[] rest = new Quaternionf[count];
        for (int k = 0; k < count; k++) {
            int i = order[k];
            bonesSorted[k] = boneOf[i];
            Limit l = limits.get(i);
            for (int c = 0; c < 3; c++) {
                min[k * 3 + c] = (float) Math.toRadians(l.min.get(c));
                max[k * 3 + c] = (float) Math.toRadians(l.max.get(c));
            }
            rest[k] = skeleton.restRotation(boneOf[i], new Quaternionf());
        }
        return new Control(count) {
            private final Quaternionf q = new Quaternionf(), delta = new Quaternionf();
            private final Vector3f e = new Vector3f(), t = new Vector3f(), s = new Vector3f();
            private final Matrix4f local = new Matrix4f();

            @Override
            protected int evaluate(CgAnimGraphInstance g, CgAnimPose pose, int[] bones, Matrix4f[] out) {
                int n = 0;
                for (int k = 0; k < count; k++) {
                    int b = bonesSorted[k];
                    pose.rotation(b, q);
                    // ConstrainAngularRangeUsingEuler: the delta from the rest, clamped angle by angle
                    CgIkMath.eulerZYX(rest[k].mul(delta.set(q).conjugate(), delta), e);
                    float x = clamp(e.x, min[k * 3], max[k * 3]), y = clamp(e.y, min[k * 3 + 1], max[k * 3 + 1]),
                            z = clamp(e.z, min[k * 3 + 2], max[k * 3 + 2]);
                    if (x == e.x && y == e.y && z == e.z) continue;
                    delta.rotationZYX(z, y, x).conjugate().mul(rest[k], q);
                    local.translationRotateScale(pose.translation(b, t), q.normalize(), pose.scale(b, s));
                    int parent = skeleton.parent(b);
                    Matrix4f m = out[n], solvedParent = parent < 0 ? null : solved(parent, bones, out, n);
                    if (parent < 0) m.set(local);
                    else if (solvedParent != null) solvedParent.mulAffine(local, m);
                    else pose.component(parent).mulAffine(local, m);
                    bones[n++] = b;
                }
                return n;
            }

            /** The transform already solved for {@code bone} this frame, or null. */
            private Matrix4f solved(int bone, int[] bones, Matrix4f[] out, int n) {
                for (int i = 0; i < n; i++) if (bones[i] == bone) return out[i];
                return null;
            }
        };
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    @Override
    protected void write(CgAnimNodeWriter out) {
        out.kind("apply_limits");
        writeBase(out);
        List<Object> list = new ArrayList<>(limits.size());
        for (Limit l : limits) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("bone", l.bone);
            e.put("min", List.of(l.min.x, l.min.y, l.min.z));
            e.put("max", List.of(l.max.x, l.max.y, l.max.z));
            list.add(e);
        }
        out.put("limits", list);
    }

    @Override
    public String toString() {
        return "apply_limits(" + limits.size() + " bones)";
    }
}
