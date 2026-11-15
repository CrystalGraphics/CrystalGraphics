package com.crystalgraphics.animation.ik;

import java.util.Objects;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.crystalgraphics.animation.graph.CgAnimGraphInstance;
import com.crystalgraphics.animation.graph.CgAnimNode;
import com.crystalgraphics.animation.graph.CgAnimNodeKind;
import com.crystalgraphics.animation.graph.CgAnimNodeWriter;
import com.crystalgraphics.api.animation.CgAnimPose;

/**
 * One bone takes another's translation, rotation or scale, compared in a space of the node's choosing: Unreal's Copy
 * Bone ({@code FAnimNode_CopyBone}, AnimNode_CopyBone.cpp).
 *
 * <pre>{@code
 * // a weapon bone follows the right hand
 * CgCopyBoneNode.of(body, "hand_r", "weapon_r").copy(true, true, false)
 *
 * // a twist bone takes its forearm's rotation in their parent's space
 * CgCopyBoneNode.of(body, "lowerarm_l", "lowerarm_twist_l").copy(false, true, false).space(CgBoneSpace.PARENT_BONE)
 * }</pre>
 *
 * <ul>
 *   <li>Copies nothing by default: name the parts.</li>
 *   <li>In a bone space, both bones are converted into it relative to themselves, as Unreal's.</li>
 * </ul>
 */
public final class CgCopyBoneNode extends CgSkeletalControlNode {

    /** Reads {@code kind: copy_bone}. */
    public static final CgAnimNodeKind KIND = (c, at, m) -> of(source(c, at, m), c.string(at, m, "source_bone"), c.string(at, m, "target_bone"))
            .alpha(alpha(c, at, m))
            .copy(Boolean.TRUE.equals(m.get("translation")), Boolean.TRUE.equals(m.get("rotation")), Boolean.TRUE.equals(m.get("scale")))
            .space(choice(c, at, m, "space", CgBoneSpace.class, CgBoneSpace.COMPONENT));

    private final String sourceBone, targetBone;
    private final boolean translation, rotation, scale;
    private final CgBoneSpace space;

    private CgCopyBoneNode(CgAnimNode source, CgControlAlpha alpha, String sourceBone, String targetBone, boolean translation,
                           boolean rotation, boolean scale, CgBoneSpace space) {
        super(source, alpha);
        this.sourceBone = Objects.requireNonNull(sourceBone, "sourceBone");
        this.targetBone = Objects.requireNonNull(targetBone, "targetBone");
        this.translation = translation;
        this.rotation = rotation;
        this.scale = scale;
        this.space = Objects.requireNonNull(space, "space");
    }

    /** {@code targetBone} copying {@code sourceBone}, in the skeleton's space. */
    public static CgCopyBoneNode of(CgAnimNode source, String sourceBone, String targetBone) {
        return new CgCopyBoneNode(source, CgControlAlpha.FULL, sourceBone, targetBone, false, false, false, CgBoneSpace.COMPONENT);
    }

    public CgCopyBoneNode alpha(CgControlAlpha alpha) {
        return new CgCopyBoneNode(source, alpha, sourceBone, targetBone, translation, rotation, scale, space);
    }

    /** Which parts are copied. */
    public CgCopyBoneNode copy(boolean translation, boolean rotation, boolean scale) {
        return new CgCopyBoneNode(source, alpha, sourceBone, targetBone, translation, rotation, scale, space);
    }

    /** The space the parts are compared in. */
    public CgCopyBoneNode space(CgBoneSpace space) {
        return new CgCopyBoneNode(source, alpha, sourceBone, targetBone, translation, rotation, scale, space);
    }

    @Override
    protected Control control(CgAnimGraphInstance g) {
        int from = bone(g.skeleton(), sourceBone, this), to = bone(g.skeleton(), targetBone, this);
        return new Control(1) {
            private final Matrix4f sourceTm = new Matrix4f();
            private final Vector3f t = new Vector3f(), s = new Vector3f(), st = new Vector3f(), ss = new Vector3f();
            private final Quaternionf q = new Quaternionf(), sq = new Quaternionf();

            @Override
            protected int evaluate(CgAnimGraphInstance g, CgAnimPose pose, int[] bones, Matrix4f[] out) {
                if (!translation && !rotation && !scale) return 0;
                Matrix4f m = out[0].set(pose.component(to));
                space.fromComponent(sourceTm.set(pose.component(from)), pose, from, g.placement());
                space.fromComponent(m, pose, to, g.placement());
                m.getTranslation(t);
                m.getNormalizedRotation(q);
                m.getScale(s);
                if (translation) sourceTm.getTranslation(st);
                if (rotation) sourceTm.getNormalizedRotation(sq);
                if (scale) sourceTm.getScale(ss);
                m.translationRotateScale(translation ? st : t, rotation ? sq : q, scale ? ss : s);
                space.toComponent(m, pose, to, g.placement());
                bones[0] = to;
                return 1;
            }
        };
    }

    @Override
    protected void write(CgAnimNodeWriter out) {
        out.kind("copy_bone");
        writeBase(out);
        out.put("source_bone", sourceBone).put("target_bone", targetBone);
        if (translation) out.put("translation", true);
        if (rotation) out.put("rotation", true);
        if (scale) out.put("scale", true);
        if (space != CgBoneSpace.COMPONENT) out.put("space", space.id());
    }

    @Override
    public String toString() {
        return "copy_bone(" + sourceBone + " -> " + targetBone + ")";
    }
}
