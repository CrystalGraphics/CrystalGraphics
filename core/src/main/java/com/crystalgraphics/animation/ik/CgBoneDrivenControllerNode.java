package com.crystalgraphics.animation.ik;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
 * One bone's movement away from its rest drives parts of another bone: Unreal's Bone Driven Controller
 * ({@code FAnimNode_BoneDrivenController}, AnimNode_BoneDrivenController.cpp). The driver's value is one component of
 * its local change (a translation, an Euler angle in degrees, or a scale), mapped through a range and a multiplier,
 * then added to or replacing the driven bone's parts in its parent's space.
 *
 * <pre>{@code
 * // the jaw's opening (its turn about x) lowers the chin strap
 * CgBoneDrivenControllerNode.of(body, "jaw", CgBoneDrivenControllerNode.Source.ROTATION_X, "chin_strap")
 *         .affects(CgBoneDrivenControllerNode.Part.TRANSLATION_Z).multiplier(-0.002f)
 *
 * // a knee's bend from 0 to 120 degrees turns its pad 0 to 40, replacing the pad's own angle
 * CgBoneDrivenControllerNode.of(body, "calf_l", CgBoneDrivenControllerNode.Source.ROTATION_Y, "kneepad_l")
 *         .affects(CgBoneDrivenControllerNode.Part.ROTATION_Y).range(0f, 120f, 0f, 40f)
 *         .mode(CgBoneDrivenControllerNode.Mode.REPLACE_COMPONENT)
 * }</pre>
 *
 * <ul>
 *   <li>Euler angles are degrees about x, y and z, composed {@code Rz·Ry·Rx}, as Unreal's {@code FQuat::Euler}.</li>
 *   <li>{@code SCALE} is the change in the largest of the three scales.</li>
 *   <li>A driving curve, or a morph target or material parameter as the destination, is refused at load.</li>
 * </ul>
 */
public final class CgBoneDrivenControllerNode extends CgSkeletalControlNode {

    /** Unreal's {@code EComponentType}: what of the driver is read. */
    public enum Source { TRANSLATION_X, TRANSLATION_Y, TRANSLATION_Z, ROTATION_X, ROTATION_Y, ROTATION_Z, SCALE, SCALE_X, SCALE_Y, SCALE_Z }

    /** The driven parts: Unreal's {@code bAffectTarget*} flags. */
    public enum Part { TRANSLATION_X, TRANSLATION_Y, TRANSLATION_Z, ROTATION_X, ROTATION_Y, ROTATION_Z, SCALE_X, SCALE_Y, SCALE_Z }

    /** Unreal's {@code EDrivenBoneModificationMode}. */
    public enum Mode { ADD_TO_INPUT, REPLACE_COMPONENT, ADD_TO_REF_POSE }

    /** Reads {@code kind: bone_driven_controller}. */
    public static final CgAnimNodeKind KIND = (c, at, m) -> {
        if (m.get("driving_curve") != null) throw new CgAssetException(at + ".driving_curve: a driving curve is not supported");
        Object dest = m.get("destination");
        if (dest != null && !"bone".equals(dest)) throw new CgAssetException(at + ".destination: " + dest + " is not supported, only bone");
        CgBoneDrivenControllerNode n = of(source(c, at, m), c.string(at, m, "source_bone"),
                choice(c, at, m, "source_component", Source.class, Source.TRANSLATION_X), c.string(at, m, "target_bone"))
                .alpha(alpha(c, at, m))
                .mode(choice(c, at, m, "mode", Mode.class, Mode.ADD_TO_INPUT))
                .multiplier(number(c, at, m, "multiplier", 1f));
        float[] range = CgControlAlpha.numbers(c, at + ".range", m.get("range"), 4);
        if (range != null) n = n.range(range[0], range[1], range[2], range[3]);
        if (!(m.get("affects") instanceof List<?> parts) || parts.isEmpty()) throw new CgAssetException(at + ".affects: none");
        Part[] p = new Part[parts.size()];
        for (int i = 0; i < p.length; i++) {
            Object o = parts.get(i);
            p[i] = null;
            for (Part e : Part.values()) if (id(e).equals(o)) p[i] = e;
            if (p[i] == null) throw new CgAssetException(at + ".affects[" + i + "]: " + o + " is no part");
        }
        return n.affects(p);
    };

    private final String sourceBone, targetBone;
    private final Source component;
    private final boolean[] affects;
    private final Mode mode;
    private final float multiplier;
    private final boolean useRange;
    private final float rangeMin, rangeMax, remappedMin, remappedMax;

    private CgBoneDrivenControllerNode(CgAnimNode source, CgControlAlpha alpha, String sourceBone, Source component, String targetBone,
                                       boolean[] affects, Mode mode, float multiplier, boolean useRange, float rangeMin,
                                       float rangeMax, float remappedMin, float remappedMax) {
        super(source, alpha);
        this.sourceBone = Objects.requireNonNull(sourceBone, "sourceBone");
        this.component = Objects.requireNonNull(component, "component");
        this.targetBone = Objects.requireNonNull(targetBone, "targetBone");
        this.affects = affects.clone();
        this.mode = Objects.requireNonNull(mode, "mode");
        this.multiplier = multiplier;
        this.useRange = useRange;
        this.rangeMin = rangeMin;
        this.rangeMax = rangeMax;
        this.remappedMin = remappedMin;
        this.remappedMax = remappedMax;
    }

    /** {@code targetBone} driven by {@code component} of {@code sourceBone}; name its parts with {@link #affects}. */
    public static CgBoneDrivenControllerNode of(CgAnimNode source, String sourceBone, Source component, String targetBone) {
        return new CgBoneDrivenControllerNode(source, CgControlAlpha.FULL, sourceBone, component, targetBone,
                new boolean[Part.values().length], Mode.ADD_TO_INPUT, 1f, false, -1f, 1f, 0f, 1f);
    }

    public CgBoneDrivenControllerNode alpha(CgControlAlpha alpha) {
        return new CgBoneDrivenControllerNode(source, alpha, sourceBone, component, targetBone, affects, mode, multiplier,
                useRange, rangeMin, rangeMax, remappedMin, remappedMax);
    }

    /** The driven bone's parts the value goes to. */
    public CgBoneDrivenControllerNode affects(Part... parts) {
        boolean[] a = new boolean[Part.values().length];
        for (Part p : parts) a[p.ordinal()] = true;
        return new CgBoneDrivenControllerNode(source, alpha, sourceBone, component, targetBone, a, mode, multiplier,
                useRange, rangeMin, rangeMax, remappedMin, remappedMax);
    }

    public CgBoneDrivenControllerNode mode(Mode mode) {
        return new CgBoneDrivenControllerNode(source, alpha, sourceBone, component, targetBone, affects, mode, multiplier,
                useRange, rangeMin, rangeMax, remappedMin, remappedMax);
    }

    /** The value times {@code multiplier}, after any {@link #range}. */
    public CgBoneDrivenControllerNode multiplier(float multiplier) {
        return new CgBoneDrivenControllerNode(source, alpha, sourceBone, component, targetBone, affects, mode, multiplier,
                useRange, rangeMin, rangeMax, remappedMin, remappedMax);
    }

    /** The driver's value from {@code min}–{@code max} mapped, clamped, onto {@code remappedMin}–{@code remappedMax}. */
    public CgBoneDrivenControllerNode range(float min, float max, float remappedMin, float remappedMax) {
        return new CgBoneDrivenControllerNode(source, alpha, sourceBone, component, targetBone, affects, mode, multiplier,
                true, min, max, remappedMin, remappedMax);
    }

    @Override
    protected Control control(CgAnimGraphInstance g) {
        CgSkeleton skeleton = g.skeleton();
        int from = bone(skeleton, sourceBone, this), to = bone(skeleton, targetBone, this);
        boolean any = false;
        for (boolean a : affects) any |= a;
        if (!any) throw new IllegalArgumentException(this + ": affects no part");
        Vector3f refT = skeleton.restTranslation(from, new Vector3f()), refS = skeleton.restScale(from, new Vector3f());
        Quaternionf refQ = skeleton.restRotation(from, new Quaternionf());
        Vector3f targetRefT = skeleton.restTranslation(to, new Vector3f()), targetRefS = skeleton.restScale(to, new Vector3f());
        Quaternionf targetRefQ = skeleton.restRotation(to, new Quaternionf());
        boolean rotates = affects[Part.ROTATION_X.ordinal()] || affects[Part.ROTATION_Y.ordinal()] || affects[Part.ROTATION_Z.ordinal()];
        return new Control(1) {
            private final Vector3f t = new Vector3f(), s = new Vector3f(), e = new Vector3f(), d = new Vector3f();
            private final Quaternionf q = new Quaternionf(), r = new Quaternionf();
            private final Matrix4f local = new Matrix4f();

            @Override
            protected int evaluate(CgAnimGraphInstance g, CgAnimPose pose, int[] bones, Matrix4f[] out) {
                float v = (float) value(pose);
                pose.translation(to, t);
                pose.rotation(to, q);
                pose.scale(to, s);
                switch (mode) {
                    case ADD_TO_INPUT -> {
                        add(t, Part.TRANSLATION_X, v);
                        add(s, Part.SCALE_X, v);
                        if (rotates) q.mul(euler(d.zero(), v, r));
                    }
                    case REPLACE_COMPONENT -> {
                        set(t, Part.TRANSLATION_X, v);
                        set(s, Part.SCALE_X, v);
                        if (rotates) {
                            degrees(CgIkMath.eulerZYX(q, e));
                            set(e, Part.ROTATION_X, v);
                            radians(e);
                            q.rotationZYX(e.z, e.y, e.x);
                        }
                    }
                    case ADD_TO_REF_POSE -> {
                        for (int c = 0; c < 3; c++) {
                            if (affects[Part.TRANSLATION_X.ordinal() + c]) t.setComponent(c, targetRefT.get(c) + v);
                            if (affects[Part.SCALE_X.ordinal() + c]) s.setComponent(c, targetRefS.get(c) + v);
                        }
                        if (rotates) {
                            // the driven angles from the rest pose, then the value turned on after
                            degrees(CgIkMath.eulerZYX(q, e));
                            degrees(CgIkMath.eulerZYX(targetRefQ, d));
                            for (int c = 0; c < 3; c++) if (affects[Part.ROTATION_X.ordinal() + c]) e.setComponent(c, d.get(c));
                            radians(e);
                            q.rotationZYX(e.z, e.y, e.x).mul(euler(d.zero(), v, r));
                        }
                    }
                }
                local.translationRotateScale(t, q.normalize(), s);
                int parent = pose.skeleton().parent(to);
                if (parent < 0) out[0].set(local);
                else pose.component(parent).mulAffine(local, out[0]);
                bones[0] = to;
                return 1;
            }

            /** {@code ExtractSourceValue}. */
            private double value(CgAnimPose pose) {
                double raw;
                switch (component) {
                    case TRANSLATION_X, TRANSLATION_Y, TRANSLATION_Z -> raw = pose.translation(from, t).sub(refT).get(component.ordinal());
                    case ROTATION_X, ROTATION_Y, ROTATION_Z -> {
                        CgIkMath.eulerZYX(pose.rotation(from, q).mul(r.set(refQ).conjugate()), e);
                        raw = Math.toDegrees(e.get(component.ordinal() - Source.ROTATION_X.ordinal()));
                    }
                    case SCALE -> {
                        pose.scale(from, s);
                        raw = Math.max(s.x, Math.max(s.y, s.z)) - Math.max(refS.x, Math.max(refS.y, refS.z));
                    }
                    default -> raw = pose.scale(from, s).sub(refS).get(component.ordinal() - Source.SCALE_X.ordinal());
                }
                if (useRange) {
                    double range = rangeMax - rangeMin;
                    double pct = range != 0 ? (raw - rangeMin) / range : (raw >= rangeMax ? 1 : 0);   // FMath::GetRangePct
                    raw = remappedMin + (remappedMax - remappedMin) * Math.max(0, Math.min(1, pct));
                }
                return raw * multiplier;
            }

            /** The affected angles of {@code v} degrees as a rotation, into {@code out}: {@code FQuat::MakeFromEuler}. */
            private Quaternionf euler(Vector3f angles, float v, Quaternionf out) {
                for (int c = 0; c < 3; c++) if (affects[Part.ROTATION_X.ordinal() + c]) angles.setComponent(c, v);
                radians(angles);
                return out.rotationZYX(angles.z, angles.y, angles.x);
            }

            private void add(Vector3f v, Part first, float value) {
                for (int c = 0; c < 3; c++) if (affects[first.ordinal() + c]) v.setComponent(c, v.get(c) + value);
            }

            private void set(Vector3f v, Part first, float value) {
                for (int c = 0; c < 3; c++) if (affects[first.ordinal() + c]) v.setComponent(c, value);
            }
        };
    }

    private static Vector3f degrees(Vector3f v) {
        return v.set((float) Math.toDegrees(v.x), (float) Math.toDegrees(v.y), (float) Math.toDegrees(v.z));
    }

    private static Vector3f radians(Vector3f v) {
        return v.set((float) Math.toRadians(v.x), (float) Math.toRadians(v.y), (float) Math.toRadians(v.z));
    }

    @Override
    protected void write(CgAnimNodeWriter out) {
        out.kind("bone_driven_controller");
        writeBase(out);
        out.put("source_bone", sourceBone).put("source_component", id(component)).put("target_bone", targetBone);
        List<Object> parts = new ArrayList<>();
        for (Part p : Part.values()) if (affects[p.ordinal()]) parts.add(p.name().toLowerCase(Locale.ROOT));
        out.put("affects", parts);
        if (mode != Mode.ADD_TO_INPUT) out.put("mode", id(mode));
        if (multiplier != 1f) out.put("multiplier", multiplier);
        if (useRange) out.put("range", List.of(rangeMin, rangeMax, remappedMin, remappedMax));
    }

    @Override
    public String toString() {
        return "bone_driven_controller(" + sourceBone + " -> " + targetBone + ")";
    }
}
