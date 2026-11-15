package com.crystalgraphics.animation.ik;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
 * One bone's translation, rotation or scale replaced or added to, each in a space of its own: Unreal's Transform
 * (Modify) Bone ({@code FAnimNode_ModifyBone}, AnimNode_ModifyBone.cpp). Its values are constants or float variables,
 * set by the game each frame.
 *
 * <pre>{@code
 * // a crouch: the hips lowered in the skeleton's space by a variable
 * CgModifyBoneNode.of(locomotion, "hips")
 *         .translation(CgModifyBoneNode.Mode.ADD, CgBoneSpace.COMPONENT, null, CROUCH_DROP, null)
 *
 * // a head turned by yaw and pitch, added in the head's parent's space, half strength
 * CgModifyBoneNode.of(body, "head")
 *         .rotation(CgModifyBoneNode.Mode.ADD, CgBoneSpace.PARENT_BONE, LOOK_PITCH, LOOK_YAW, null)
 *         .alpha(CgControlAlpha.of(0.5f))
 * }</pre>
 *
 * <ul>
 *   <li>Applied scale, then rotation, then translation, each converted into its space, changed and converted back;
 *       an added rotation is {@code value * current}, as Unreal's.</li>
 *   <li>A rotation is Euler degrees about the skeleton's x, y and z, composed {@code Rz·Ry·Rx}; an importer converts
 *       Unreal's {@code FRotator} into it.</li>
 *   <li>A value with no variable is its constant ({@link #translation(Mode, CgBoneSpace, float, float, float)}).</li>
 * </ul>
 */
public final class CgModifyBoneNode extends CgSkeletalControlNode {

    /** Unreal's {@code EBoneModificationMode}. */
    public enum Mode { IGNORE, REPLACE, ADD }

    /** Reads {@code kind: modify_bone}. */
    public static final CgAnimNodeKind KIND = (c, at, m) -> {
        CgModifyBoneNode n = of(source(c, at, m), c.string(at, m, "bone")).alpha(alpha(c, at, m));
        return new CgModifyBoneNode(n.source, n.alpha, n.bone, read(c, at + ".translation", m.get("translation"), n.translation),
                read(c, at + ".rotation", m.get("rotation"), n.rotation), read(c, at + ".scale", m.get("scale"), n.scale));
    };

    @SuppressWarnings("unchecked")
    private static Part read(CgAnimNodeKind.Context c, String at, @Nullable Object o, Part none) {
        if (o == null) return none;
        if (!(o instanceof Map<?, ?> p)) throw new CgAssetException(at + ": { mode, space, value }");
        Mode mode = choice(c, at, p, "mode", Mode.class, Mode.ADD);
        CgBoneSpace space;
        try {
            space = CgBoneSpace.of(c.string(at, p, "space"));
        } catch (IllegalArgumentException e) {
            throw new CgAssetException(at + ".space: " + e.getMessage());
        }
        if (!(p.get("value") instanceof List<?> l) || l.size() != 3) throw new CgAssetException(at + ".value: 3 values");
        float[] k = none.constants.clone();
        CgAnimVariable<Float>[] v = new CgAnimVariable[3];
        for (int i = 0; i < 3; i++) {
            Object e = l.get(i);
            if (e instanceof String name) v[i] = c.variable(at + ".value[" + i + "]", name, CgAnimVariable.Type.FLOAT);
            else k[i] = c.number(at + ".value[" + i + "]", e);
        }
        return part(mode, space, k[0], k[1], k[2], v[0], v[1], v[2]);
    }

    /** One part: its mode, space and three values, each a constant or a variable. */
    private record Part(Mode mode, CgBoneSpace space, float[] constants, CgAnimVariable<Float>[] variables) {
        float value(CgAnimGraphInstance g, int c) {
            return variables[c] != null ? g.get(variables[c]) : constants[c];
        }
    }

    private final String bone;
    private final Part translation, rotation, scale;

    private CgModifyBoneNode(CgAnimNode source, CgControlAlpha alpha, String bone, Part translation, Part rotation, Part scale) {
        super(source, alpha);
        this.bone = Objects.requireNonNull(bone, "bone");
        this.translation = translation;
        this.rotation = rotation;
        this.scale = scale;
    }

    public static CgModifyBoneNode of(CgAnimNode source, String bone) {
        Part none = part(Mode.IGNORE, CgBoneSpace.COMPONENT, 0f, 0f, 0f, null, null, null);
        return new CgModifyBoneNode(source, CgControlAlpha.FULL, bone, none, none, part(Mode.IGNORE, CgBoneSpace.COMPONENT, 1f, 1f, 1f, null, null, null));
    }

    @SuppressWarnings("unchecked")
    private static Part part(Mode mode, CgBoneSpace space, float x, float y, float z,
                             @Nullable CgAnimVariable<Float> vx, @Nullable CgAnimVariable<Float> vy, @Nullable CgAnimVariable<Float> vz) {
        return new Part(Objects.requireNonNull(mode, "mode"), Objects.requireNonNull(space, "space"), new float[]{x, y, z},
                new CgAnimVariable[]{vx, vy, vz});
    }

    public CgModifyBoneNode translation(Mode mode, CgBoneSpace space, float x, float y, float z) {
        return new CgModifyBoneNode(source, alpha, bone, part(mode, space, x, y, z, null, null, null), rotation, scale);
    }

    /** The translation's values from variables; a null one is 0. */
    public CgModifyBoneNode translation(Mode mode, CgBoneSpace space, @Nullable CgAnimVariable<Float> x,
                                        @Nullable CgAnimVariable<Float> y, @Nullable CgAnimVariable<Float> z) {
        return new CgModifyBoneNode(source, alpha, bone, part(mode, space, 0f, 0f, 0f, x, y, z), rotation, scale);
    }

    /** Euler degrees about x, y and z. */
    public CgModifyBoneNode rotation(Mode mode, CgBoneSpace space, float x, float y, float z) {
        return new CgModifyBoneNode(source, alpha, bone, translation, part(mode, space, x, y, z, null, null, null), scale);
    }

    /** Euler degrees about x, y and z from variables; a null one is 0. */
    public CgModifyBoneNode rotation(Mode mode, CgBoneSpace space, @Nullable CgAnimVariable<Float> x,
                                     @Nullable CgAnimVariable<Float> y, @Nullable CgAnimVariable<Float> z) {
        return new CgModifyBoneNode(source, alpha, bone, translation, part(mode, space, 0f, 0f, 0f, x, y, z), scale);
    }

    public CgModifyBoneNode scale(Mode mode, CgBoneSpace space, float x, float y, float z) {
        return new CgModifyBoneNode(source, alpha, bone, translation, rotation, part(mode, space, x, y, z, null, null, null));
    }

    /** The scale's values from variables; a null one is 1. */
    public CgModifyBoneNode scale(Mode mode, CgBoneSpace space, @Nullable CgAnimVariable<Float> x,
                                  @Nullable CgAnimVariable<Float> y, @Nullable CgAnimVariable<Float> z) {
        return new CgModifyBoneNode(source, alpha, bone, translation, rotation, part(mode, space, 1f, 1f, 1f, x, y, z));
    }

    public CgModifyBoneNode alpha(CgControlAlpha alpha) {
        return new CgModifyBoneNode(source, alpha, bone, translation, rotation, scale);
    }

    @Override
    protected Control control(CgAnimGraphInstance g) {
        int b = bone(g.skeleton(), bone, this);
        return new Control(1) {
            private final Matrix4f tm = new Matrix4f();
            private final Vector3f t = new Vector3f(), s = new Vector3f();
            private final Quaternionf q = new Quaternionf(), value = new Quaternionf();

            @Override
            protected int evaluate(CgAnimGraphInstance g, CgAnimPose pose, int[] bones, Matrix4f[] out) {
                Matrix4f m = out[0].set(pose.component(b));
                // AnimNode_ModifyBone.cpp: scale, rotation, translation, each in its own space
                if (scale.mode != Mode.IGNORE) {
                    scale.space.fromComponent(m, pose, b, g.placement());
                    decompose(m);
                    float x = scale.value(g, 0), y = scale.value(g, 1), z = scale.value(g, 2);
                    if (scale.mode == Mode.ADD) s.mul(x, y, z); else s.set(x, y, z);
                    m.translationRotateScale(t, q, s);
                    scale.space.toComponent(m, pose, b, g.placement());
                }
                if (rotation.mode != Mode.IGNORE) {
                    rotation.space.fromComponent(m, pose, b, g.placement());
                    decompose(m);
                    value.rotationZYX((float) Math.toRadians(rotation.value(g, 2)), (float) Math.toRadians(rotation.value(g, 1)),
                            (float) Math.toRadians(rotation.value(g, 0)));
                    if (rotation.mode == Mode.ADD) value.mul(q, q); else q.set(value);
                    m.translationRotateScale(t, q.normalize(), s);
                    rotation.space.toComponent(m, pose, b, g.placement());
                }
                if (translation.mode != Mode.IGNORE) {
                    translation.space.fromComponent(m, pose, b, g.placement());
                    m.getTranslation(t);
                    float x = translation.value(g, 0), y = translation.value(g, 1), z = translation.value(g, 2);
                    if (translation.mode == Mode.ADD) t.add(x, y, z); else t.set(x, y, z);
                    m.setTranslation(t);
                    translation.space.toComponent(m, pose, b, g.placement());
                }
                bones[0] = b;
                return 1;
            }

            private void decompose(Matrix4f m) {
                m.getTranslation(t);
                m.getNormalizedRotation(q);
                m.getScale(s);
            }
        };
    }

    @Override
    protected void reads(Consumer<CgAnimVariable<?>> each) {
        super.reads(each);
        for (Part p : new Part[]{translation, rotation, scale}) {
            for (CgAnimVariable<Float> v : p.variables) if (v != null) each.accept(v);
        }
    }

    @Override
    protected void write(CgAnimNodeWriter out) {
        out.kind("modify_bone");
        writeBase(out);
        out.put("bone", bone);
        write(out, "translation", translation);
        write(out, "rotation", rotation);
        write(out, "scale", scale);
    }

    /** A part as {@code { mode, space, value: [x, y, z] }}, a value a number or a variable's name; ignored, nothing. */
    private static void write(CgAnimNodeWriter out, String key, Part p) {
        if (p.mode == Mode.IGNORE) return;
        List<Object> value = new ArrayList<>(3);
        for (int c = 0; c < 3; c++) value.add(p.variables[c] != null ? p.variables[c].name() : p.constants[c]);
        out.put(key, Map.of("mode", p.mode.name().toLowerCase(Locale.ROOT), "space", p.space.id(), "value", value));
    }

    @Override
    public String toString() {
        return "modify_bone(" + bone + ")";
    }
}
