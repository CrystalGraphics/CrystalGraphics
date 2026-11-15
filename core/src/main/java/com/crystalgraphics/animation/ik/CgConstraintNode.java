package com.crystalgraphics.animation.ik;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.crystalgraphics.animation.graph.CgAnimGraphInstance;
import com.crystalgraphics.animation.graph.CgAnimNode;
import com.crystalgraphics.animation.graph.CgAnimNodeKind;
import com.crystalgraphics.animation.graph.CgAnimNodeWriter;
import com.crystalgraphics.animation.graph.CgAnimVariable;
import com.crystalgraphics.api.animation.CgAnimPose;
import com.crystalgraphics.asset.tree.CgAssetException;

/**
 * One bone follows others' translation, rotation, scale or whole transform, each by a weight: Unreal's Constraint
 * ({@code FAnimNode_Constraint}, AnimNode_Constraint.cpp, over {@code AnimationCore::SolveConstraints}). With an offset,
 * the bone keeps where it stood from each target in the reference pose.
 *
 * <pre>{@code
 * // a holster bone half between the hips and the thigh, keeping its rest offset
 * CgConstraintNode.of(body, "holster")
 *         .constraint("pelvis", CgConstraintNode.Type.PARENT, true, 0.5f)
 *         .constraint("thigh_r", CgConstraintNode.Type.PARENT, true, 0.5f)
 *
 * // a lid following the eye's yaw alone, by a variable
 * CgConstraintNode.of(body, "lid_l").constraint("eye_l", CgConstraintNode.Type.ROTATION, true, false, false, true, LID_FOLLOW)
 * }</pre>
 *
 * <ul>
 *   <li>Weights summing past 1 are normalised; below 1, a translation, rotation or scale blends toward zero, as
 *       Unreal's blend helper does, not toward the input.</li>
 *   <li>{@code PARENT} with any other type on one node uses the parents alone, as Unreal's.</li>
 *   <li>An axis filter zeroes the Euler angles (rotation) or components (translation, scale) it turns off.</li>
 * </ul>
 */
public final class CgConstraintNode extends CgSkeletalControlNode {

    /** Unreal's {@code ETransformConstraintType}, less its look-at. */
    public enum Type { TRANSLATION, ROTATION, SCALE, PARENT }

    /** One target: Unreal's {@code FConstraint} with its weight. */
    private record Target(String bone, Type type, boolean maintainOffset, boolean x, boolean y, boolean z, float weight,
                          @Nullable CgAnimVariable<Float> variable) {
    }

    /** Reads {@code kind: constraint}. */
    public static final CgAnimNodeKind KIND = (c, at, m) -> {
        CgConstraintNode n = of(source(c, at, m), c.string(at, m, "bone")).alpha(alpha(c, at, m));
        if (!(m.get("constraints") instanceof List<?> list) || list.isEmpty()) throw new CgAssetException(at + ".constraints: none");
        for (int i = 0; i < list.size(); i++) {
            String a = at + ".constraints[" + i + "]";
            if (!(list.get(i) instanceof Map<?, ?> e)) throw new CgAssetException(a + ": a map");
            Type type = choice(c, a, e, "type", Type.class, Type.PARENT);
            boolean offset = !Boolean.FALSE.equals(e.get("maintain_offset"));
            boolean[] axes = flags(a + ".axes", e.get("axes"), true);
            boolean x = axes[0], y = axes[1], z = axes[2];
            Object w = e.get("weight");
            n = w instanceof String v
                    ? n.constraint(c.string(a, e, "target_bone"), type, offset, x, y, z, c.<Float>variable(a + ".weight", v, CgAnimVariable.Type.FLOAT))
                    : n.constraint(c.string(a, e, "target_bone"), type, offset, x, y, z, w == null ? 1f : c.number(a + ".weight", w));
        }
        return n;
    };


    private final String bone;
    private final List<Target> targets;

    private CgConstraintNode(CgAnimNode source, CgControlAlpha alpha, String bone, List<Target> targets) {
        super(source, alpha);
        this.bone = Objects.requireNonNull(bone, "bone");
        this.targets = List.copyOf(targets);
    }

    public static CgConstraintNode of(CgAnimNode source, String bone) {
        return new CgConstraintNode(source, CgControlAlpha.FULL, bone, List.of());
    }

    public CgConstraintNode alpha(CgControlAlpha alpha) {
        return new CgConstraintNode(source, alpha, bone, targets);
    }

    /** Follows {@code targetBone} by {@code type} at a constant weight, every axis. */
    public CgConstraintNode constraint(String targetBone, Type type, boolean maintainOffset, float weight) {
        return constraint(targetBone, type, maintainOffset, true, true, true, weight);
    }

    /** Follows {@code targetBone} by {@code type} at a constant weight, on the axes named true. */
    public CgConstraintNode constraint(String targetBone, Type type, boolean maintainOffset, boolean x, boolean y, boolean z, float weight) {
        return add(new Target(Objects.requireNonNull(targetBone, "targetBone"), Objects.requireNonNull(type, "type"),
                maintainOffset, x, y, z, weight, null));
    }

    /** Follows {@code targetBone} by {@code type} at a variable's weight, on the axes named true. */
    public CgConstraintNode constraint(String targetBone, Type type, boolean maintainOffset, boolean x, boolean y, boolean z,
                                       CgAnimVariable<Float> weight) {
        return add(new Target(Objects.requireNonNull(targetBone, "targetBone"), Objects.requireNonNull(type, "type"),
                maintainOffset, x, y, z, 0f, Objects.requireNonNull(weight, "weight")));
    }

    private CgConstraintNode add(Target t) {
        List<Target> next = new ArrayList<>(targets);
        next.add(t);
        return new CgConstraintNode(source, alpha, bone, next);
    }

    @Override
    protected Control control(CgAnimGraphInstance g) {
        if (targets.isEmpty()) throw new IllegalArgumentException(this + ": no constraints");
        int b = bone(g.skeleton(), bone, this);
        int count = targets.size();
        int[] targetBones = new int[count];
        for (int i = 0; i < count; i++) targetBones[i] = bone(g.skeleton(), targets.get(i).bone, this);
        // InitializeBoneReferences: each offset from the reference pose, against an identity base
        Matrix4f[] rest = new Matrix4f[g.skeleton().boneCount()];
        for (int i = 0; i < rest.length; i++) rest[i] = new Matrix4f();
        g.skeleton().restGlobal(rest);
        Matrix4f[] parentOffset = new Matrix4f[count];
        Vector3f[] tOffset = new Vector3f[count], sOffset = new Vector3f[count];
        Quaternionf[] rOffset = new Quaternionf[count];
        Vector3f st = new Vector3f(), tt = new Vector3f(), ss = new Vector3f(), ts = new Vector3f();
        Quaternionf sq = new Quaternionf(), tq = new Quaternionf();
        rest[b].getTranslation(st);
        rest[b].getNormalizedRotation(sq);
        rest[b].getScale(ss);
        for (int i = 0; i < count; i++) {
            Matrix4f target = rest[targetBones[i]];
            target.getTranslation(tt);
            target.getNormalizedRotation(tq);
            target.getScale(ts);
            parentOffset[i] = new Matrix4f(target).invertAffine().mulAffine(rest[b]);
            tOffset[i] = new Vector3f(st).sub(tt);
            rOffset[i] = new Quaternionf(tq).conjugate().mul(sq);
            sOffset[i] = new Vector3f(safeDiv(ss.x, ts.x), safeDiv(ss.y, ts.y), safeDiv(ss.z, ts.z));
        }
        return new Control(1) {
            private final Matrix4f offsetTarget = new Matrix4f();
            private final Vector3f t = new Vector3f(), s = new Vector3f(), e = new Vector3f(), tSum = new Vector3f(),
                    sSum = new Vector3f(), pt = new Vector3f(), ps = new Vector3f(), ct = new Vector3f(), cs = new Vector3f();
            private final Quaternionf q = new Quaternionf(), rSum = new Quaternionf(), pq = new Quaternionf(), cq = new Quaternionf();
            private final float[] weight = new float[count];

            @Override
            protected int evaluate(CgAnimGraphInstance g, CgAnimPose pose, int[] bones, Matrix4f[] out) {
                float parentTotal = 0f, tTotal = 0f, rTotal = 0f, sTotal = 0f;
                for (int i = 0; i < count; i++) {
                    Target c = targets.get(i);
                    float w = c.variable != null ? g.get(c.variable) : c.weight;
                    weight[i] = w > CgAnimPose.ZERO_WEIGHT ? w : 0f;
                    if (weight[i] == 0f) continue;
                    switch (c.type) {
                        case PARENT -> parentTotal += weight[i];
                        case TRANSLATION -> tTotal += weight[i];
                        case ROTATION -> rTotal += weight[i];
                        case SCALE -> sTotal += weight[i];
                    }
                }
                Matrix4f m = out[0].set(pose.component(b));
                m.getTranslation(ct);
                m.getNormalizedRotation(cq);
                m.getScale(cs);
                boolean parent = parentTotal > CgAnimPose.ZERO_WEIGHT;
                boolean first = true;
                tSum.zero();
                sSum.zero();
                rSum.set(0f, 0f, 0f, 0f);
                pt.zero();
                ps.zero();
                pq.set(0f, 0f, 0f, 0f);
                boolean rFirst = true;
                for (int i = 0; i < count; i++) {
                    if (weight[i] == 0f) continue;
                    Target c = targets.get(i);
                    Parts target = view(pose, targetBones[i]);
                    switch (c.type) {
                        case PARENT -> {
                            if (!parent) continue;
                            if (c.maintainOffset) pose.component(targetBones[i]).mulAffine(parentOffset[i], offsetTarget);
                            else offsetTarget.set(pose.component(targetBones[i]));
                            float w = weight[i] * (parentTotal > 1f ? 1f / parentTotal : 1f);
                            offsetTarget.getTranslation(t);
                            offsetTarget.getNormalizedRotation(q);
                            offsetTarget.getScale(s);
                            // AccumulateWithShortestRotation
                            pt.fma(w, t);
                            ps.fma(w, s);
                            accumulate(pq, q, w, first);
                            first = false;
                        }
                        case TRANSLATION -> {
                            t.set(target.t);
                            if (c.maintainOffset) t.add(tOffset[i]);
                            filter(t, c);
                            tSum.fma(weight[i] * (tTotal > 1f ? 1f / tTotal : 1f), t);
                        }
                        case ROTATION -> {
                            q.set(target.q);
                            if (c.maintainOffset) q.mul(rOffset[i]).normalize();
                            CgIkMath.eulerZYX(q, e);
                            if (!c.x) e.x = 0f;
                            if (!c.y) e.y = 0f;
                            if (!c.z) e.z = 0f;
                            q.rotationZYX(e.z, e.y, e.x);
                            accumulate(rSum, q, weight[i] * (rTotal > 1f ? 1f / rTotal : 1f), rFirst);
                            rFirst = false;
                        }
                        case SCALE -> {
                            s.set(target.s);
                            if (c.maintainOffset) s.mul(sOffset[i]);
                            filter(s, c);
                            sSum.fma(weight[i] * (sTotal > 1f ? 1f / sTotal : 1f), s);
                        }
                    }
                }
                if (parent) {
                    m.translationRotateScale(pt, pq.normalize(), ps);
                } else {
                    if (tTotal > CgAnimPose.ZERO_WEIGHT) ct.set(tSum);
                    if (rTotal > CgAnimPose.ZERO_WEIGHT) cq.set(rSum).normalize();
                    if (sTotal > CgAnimPose.ZERO_WEIGHT) cs.set(sSum);
                    m.translationRotateScale(ct, cq, cs);
                }
                bones[0] = b;
                return 1;
            }

            private final Parts parts = new Parts();

            private Parts view(CgAnimPose pose, int bone) {
                pose.component(bone).getTranslation(parts.t);
                pose.component(bone).getNormalizedRotation(parts.q);
                pose.component(bone).getScale(parts.s);
                return parts;
            }
        };
    }

    /** A bone's transform taken apart. */
    private static final class Parts {
        final Vector3f t = new Vector3f(), s = new Vector3f();
        final Quaternionf q = new Quaternionf();
    }

    /** Adds {@code w * q} to {@code sum}, flipped to the shortest side: {@code GetBlendedRotation}'s sum. */
    private static void accumulate(Quaternionf sum, Quaternionf q, float w, boolean first) {
        float sign = first || sum.x * q.x + sum.y * q.y + sum.z * q.z + sum.w * q.w >= 0f ? w : -w;
        sum.set(sum.x + q.x * sign, sum.y + q.y * sign, sum.z + q.z * sign, sum.w + q.w * sign);
    }

    private static void filter(Vector3f v, Target c) {
        if (!c.x) v.x = 0f;
        if (!c.y) v.y = 0f;
        if (!c.z) v.z = 0f;
    }

    /** {@code FTransform::GetSafeScaleReciprocal}'s quotient. */
    private static float safeDiv(float a, float b) {
        return Math.abs(b) <= 1e-8f ? 0f : a / b;
    }

    @Override
    protected void reads(Consumer<CgAnimVariable<?>> each) {
        super.reads(each);
        for (Target t : targets) if (t.variable != null) each.accept(t.variable);
    }

    @Override
    protected void write(CgAnimNodeWriter out) {
        out.kind("constraint");
        writeBase(out);
        out.put("bone", bone);
        List<Object> list = new ArrayList<>(targets.size());
        for (Target t : targets) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("target_bone", t.bone);
            if (t.type != Type.PARENT) e.put("type", id(t.type));
            if (!t.maintainOffset) e.put("maintain_offset", false);
            if (!t.x || !t.y || !t.z) e.put("axes", List.of(t.x, t.y, t.z));
            if (t.variable != null) e.put("weight", t.variable.name());
            else if (t.weight != 1f) e.put("weight", t.weight);
            list.add(e);
        }
        out.put("constraints", list);
    }

    @Override
    public String toString() {
        return "constraint(" + bone + ")";
    }
}
