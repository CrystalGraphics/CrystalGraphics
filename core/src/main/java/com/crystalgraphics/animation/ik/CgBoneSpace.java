package com.crystalgraphics.animation.ik;

import java.util.Locale;

import org.joml.Matrix4d;
import org.joml.Matrix4dc;
import org.joml.Matrix4f;

import com.crystalgraphics.api.animation.CgAnimPose;

/**
 * The space a bone control's value is given in: Unreal's {@code EBoneControlSpace}. A control converts its target into
 * the skeleton's space (component space) through {@link #toComponent}, as {@code FAnimationRuntime::ConvertBoneSpaceTransformToCS}
 * does (AnimationRuntime.cpp:2543-2575), and back through {@link #fromComponent}.
 *
 * <pre>{@code
 * Matrix4f target = new Matrix4f().translation(0f, 0.1f, 0f);
 * CgBoneSpace.BONE.toComponent(target, pose, foot, placement);    // 10 cm along the foot's own up
 * CgBoneSpace.WORLD.toComponent(target.translation(x, y, z), pose, -1, placement);   // a point in the world
 * }</pre>
 *
 * <ul>
 *   <li>Reads the pose's last {@code resolve()}.</li>
 *   <li>{@code bone} is the bone the space is relative to; {@code PARENT_BONE} and {@code BONE} with none are the
 *       component's.</li>
 * </ul>
 */
public enum CgBoneSpace {
    /** The world: through the component's placement. */
    WORLD,
    /** The skeleton's space, the component's. */
    COMPONENT,
    /** The bone's parent's space. */
    PARENT_BONE,
    /** The bone's own space. */
    BONE;

    /** {@code m}, given in this space, into the skeleton's space, in place. */
    public Matrix4f toComponent(Matrix4f m, CgAnimPose pose, int bone, Matrix4dc placement) {
        switch (this) {
            case WORLD -> {
                Matrix4d inv = SCRATCH.get().set(placement).invertAffine();
                Matrix4d d = SCRATCH2.get().set(m);
                inv.mulAffine(d, d);
                m.set(d);
            }
            case COMPONENT -> {
            }
            case PARENT_BONE -> {
                int parent = bone < 0 ? -1 : pose.skeleton().parent(bone);
                if (parent >= 0) pose.component(parent).mulAffine(m, m);
            }
            case BONE -> {
                if (bone >= 0) pose.component(bone).mulAffine(m, m);
            }
        }
        return m;
    }

    /** {@code m}, in the skeleton's space, into this space, in place: {@code ConvertCSTransformToBoneSpace}. */
    public Matrix4f fromComponent(Matrix4f m, CgAnimPose pose, int bone, Matrix4dc placement) {
        Matrix4f inv = SCRATCH_F.get();
        switch (this) {
            case WORLD -> {
                Matrix4d d = SCRATCH2.get().set(m);
                SCRATCH.get().set(placement).mulAffine(d, d);
                m.set(d);
            }
            case COMPONENT -> {
            }
            case PARENT_BONE -> {
                int parent = bone < 0 ? -1 : pose.skeleton().parent(bone);
                if (parent >= 0) pose.component(parent).invertAffine(inv).mulAffine(m, m);
            }
            case BONE -> {
                if (bone >= 0) pose.component(bone).invertAffine(inv).mulAffine(m, m);
            }
        }
        return m;
    }

    /** A worker's scratch: controls run on several workers at once, one instance each. */
    private static final ThreadLocal<Matrix4d> SCRATCH = ThreadLocal.withInitial(Matrix4d::new),
            SCRATCH2 = ThreadLocal.withInitial(Matrix4d::new);
    private static final ThreadLocal<Matrix4f> SCRATCH_F = ThreadLocal.withInitial(Matrix4f::new);

    /** The file's name: {@code world}, {@code component}, {@code parent_bone}, {@code bone}. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static CgBoneSpace of(String id) {
        for (CgBoneSpace s : values()) if (s.id().equals(id)) return s;
        throw new IllegalArgumentException("no bone space " + id);
    }
}
