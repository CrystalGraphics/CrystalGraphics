package com.crystalgraphics.animation;

import java.util.Objects;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;

import com.crystalgraphics.api.animation.CgPose;

/**
 * One bone moved by code after the clips and layers are blended: Unreal's Transform (Modify) Bone node
 * ({@code FAnimNode_ModifyBone}). Its scale, rotation and translation are each ignored, replaced or added to, in a space
 * of its own, then blended over the blended pose by its alpha. Kept on its {@link CgAnimInstance} and applied every
 * frame in the order written; change its values each frame as the code that drives it decides.
 *
 * <pre>{@code
 * CgBoneWrite look = role.write("head");                                       // once
 * look.rotation(CgBoneWrite.Mode.ADD, CgBoneWrite.Space.COMPONENT, yawPitch);  // each frame, before POSE
 * }</pre>
 *
 * <p>A pose composed by another rule, written whole, bone by bone, in the parent's space (what a GeckoLib bone holds):</p>
 *
 * <pre>{@code
 * CgBoneWrite arm = role.write("right_arm");
 * arm.translation(CgBoneWrite.Mode.REPLACE, CgBoneWrite.Space.PARENT, x, y, z)
 *    .rotation(CgBoneWrite.Mode.REPLACE, CgBoneWrite.Space.PARENT, turn);
 * }</pre>
 *
 * <ul>
 *   <li>Applied scale, then rotation, then translation, as Unreal's node; a rotation added is {@code q * current}.</li>
 *   <li>A write wholly in {@link Space#PARENT} touches the bone alone; the other spaces read the bones above it as
 *       posed so far, earlier writes included.</li>
 *   <li>Changed on the client world's thread outside {@code POSE}; allocates nothing once made.</li>
 * </ul>
 */
public final class CgBoneWrite {

    /** Unreal's {@code EBoneModificationMode}. */
    public enum Mode { IGNORE, REPLACE, ADD }

    /** Unreal's {@code EBoneControlSpace} less world space, which is the caller's: it holds the placement. */
    public enum Space { COMPONENT, PARENT, BONE }

    private final int bone;
    private Mode translationMode = Mode.IGNORE, rotationMode = Mode.IGNORE, scaleMode = Mode.IGNORE;
    private Space translationSpace = Space.COMPONENT, rotationSpace = Space.COMPONENT, scaleSpace = Space.COMPONENT;
    private final Vector3f translation = new Vector3f(), scale = new Vector3f(1f);
    private final Quaternionf rotation = new Quaternionf();
    private float alpha = 1f;

    private final Matrix4f boneTm = new Matrix4f(), spaceTm = new Matrix4f(), base = new Matrix4f();
    private final Vector3f t = new Vector3f(), s = new Vector3f(), t0 = new Vector3f(), s0 = new Vector3f();
    private final Quaternionf q = new Quaternionf(), q0 = new Quaternionf();

    CgBoneWrite(int bone) {
        this.bone = bone;
    }

    public int bone() {
        return bone;
    }

    public CgBoneWrite translation(Mode mode, Space space, float x, float y, float z) {
        translationMode = Objects.requireNonNull(mode, "mode");
        translationSpace = Objects.requireNonNull(space, "space");
        translation.set(x, y, z);
        return this;
    }

    public CgBoneWrite rotation(Mode mode, Space space, Quaternionfc rotation) {
        rotationMode = Objects.requireNonNull(mode, "mode");
        rotationSpace = Objects.requireNonNull(space, "space");
        this.rotation.set(rotation).normalize();
        return this;
    }

    public CgBoneWrite scale(Mode mode, Space space, float x, float y, float z) {
        scaleMode = Objects.requireNonNull(mode, "mode");
        scaleSpace = Objects.requireNonNull(space, "space");
        scale.set(x, y, z);
        return this;
    }

    /** How much of the write shows over the blended pose, 0 to 1: Unreal's skeletal control alpha. 1 by default. */
    public CgBoneWrite alpha(float alpha) {
        this.alpha = Math.max(0f, Math.min(1f, alpha));
        return this;
    }

    public float alpha() {
        return alpha;
    }

    /** Ignores all three again: the write does nothing until set. */
    public CgBoneWrite clear() {
        translationMode = rotationMode = scaleMode = Mode.IGNORE;
        return this;
    }

    private boolean local() {
        return (scaleMode == Mode.IGNORE || scaleSpace == Space.PARENT)
                && (rotationMode == Mode.IGNORE || rotationSpace == Space.PARENT)
                && (translationMode == Mode.IGNORE || translationSpace == Space.PARENT);
    }

    /** Whether applying it reads component space, so the pose must be resolved first. */
    boolean needsComponent() {
        return !local() && alpha > 0f;
    }

    /** Writes it into {@code pose}'s local transform; resolved first where {@link #needsComponent()}. */
    void apply(CgPose pose) {
        if (alpha <= 0f || (scaleMode == Mode.IGNORE && rotationMode == Mode.IGNORE && translationMode == Mode.IGNORE)) return;
        pose.translation(bone, t0);
        pose.rotation(bone, q0);
        pose.scale(bone, s0);
        if (local()) {
            t.set(t0);
            q.set(q0);
            s.set(s0);
            if (scaleMode == Mode.ADD) s.mul(scale); else if (scaleMode == Mode.REPLACE) s.set(scale);
            if (rotationMode == Mode.ADD) rotation.mul(q, q); else if (rotationMode == Mode.REPLACE) q.set(rotation);
            if (translationMode == Mode.ADD) t.add(translation); else if (translationMode == Mode.REPLACE) t.set(translation);
        } else {
            // AnimNode_ModifyBone.cpp:58-104: each part converted into its space, changed, converted back
            int parent = pose.skeleton().parent(bone);
            base.set(pose.component(bone));
            boneTm.set(base);
            if (scaleMode != Mode.IGNORE) {
                toSpace(pose, parent, scaleSpace);
                spaceTm.getScale(s);
                if (scaleMode == Mode.ADD) s.mul(scale); else s.set(scale);
                spaceTm.getTranslation(t);
                spaceTm.getNormalizedRotation(q);
                spaceTm.translationRotateScale(t, q, s);
                fromSpace(pose, parent, scaleSpace);
            }
            if (rotationMode != Mode.IGNORE) {
                toSpace(pose, parent, rotationSpace);
                spaceTm.getScale(s);
                spaceTm.getTranslation(t);
                spaceTm.getNormalizedRotation(q);
                if (rotationMode == Mode.ADD) rotation.mul(q, q); else q.set(rotation);
                spaceTm.translationRotateScale(t, q, s);
                fromSpace(pose, parent, rotationSpace);
            }
            if (translationMode != Mode.IGNORE) {
                toSpace(pose, parent, translationSpace);
                spaceTm.getTranslation(t);
                if (translationMode == Mode.ADD) t.add(translation); else t.set(translation);
                spaceTm.setTranslation(t);
                fromSpace(pose, parent, translationSpace);
            }
            // Back to the parent's space: the bone's new local transform
            if (parent >= 0) spaceTm.set(pose.component(parent)).invertAffine().mul(boneTm, boneTm);
            boneTm.getTranslation(t);
            boneTm.getScale(s);
            boneTm.getNormalizedRotation(q);
        }
        if (alpha < 1f) {
            // The control's alpha, blended local to the parent (LocalBlendCSBoneTransforms)
            t0.lerp(t, alpha, t);
            q0.slerp(q, alpha, q);
            s0.lerp(s, alpha, s);
        }
        q.normalize();
        pose.set(bone, t.x, t.y, t.z, q.x, q.y, q.z, q.w, s.x, s.y, s.z);
    }

    /** {@code ConvertCSTransformToBoneSpace}: {@link #boneTm} into {@link #spaceTm}. */
    private void toSpace(CgPose pose, int parent, Space space) {
        switch (space) {
            case COMPONENT -> spaceTm.set(boneTm);
            case PARENT -> {
                if (parent < 0) spaceTm.set(boneTm);
                else spaceTm.set(pose.component(parent)).invertAffine().mul(boneTm);
            }
            case BONE -> spaceTm.set(base).invertAffine().mul(boneTm);
        }
    }

    /** {@code ConvertBoneSpaceTransformToCS}: {@link #spaceTm} back into {@link #boneTm}. */
    private void fromSpace(CgPose pose, int parent, Space space) {
        switch (space) {
            case COMPONENT -> boneTm.set(spaceTm);
            case PARENT -> {
                if (parent < 0) boneTm.set(spaceTm);
                else pose.component(parent, boneTm).mul(spaceTm);
            }
            case BONE -> boneTm.set(base).mul(spaceTm);
        }
    }
}
