package com.crystalgraphics.api.animation;

import com.crystalgraphics.api.mesh.CgSkeletalMesh;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * One skeleton's pose: every bone's translation, rotation and scale local to its parent, and once {@link #resolve()}d
 * each bone's transform in the skeleton's space. What a clip is sampled into, poses are blended into, and skinning
 * matrices and sockets are read from. Unreal's {@code FCompactPose} and its component-space transforms.
 *
 * <pre>{@code
 * CgPose pose = new CgPose(kurama.skeleton());          // made once, reused every frame
 * pose.rest();
 * attack.sample(pose, 0.4f);                             // the clip's bones at 0.4 s; the rest keep their rest pose
 * pose.resolve();                                        // component space, parents first
 * pose.skinning(kurama, skinning);                       // what CgBonePalettes.write takes
 * pose.component(kurama.skeleton().indexOf("hand_r"), hand);   // a bone in the mesh's space
 * }</pre>
 *
 * <p>Blending, as Unreal's {@code FAnimationRuntime::BlendPosesTogether}:</p>
 *
 * <pre>{@code
 * out.blend(new CgPose[]{run, wave}, new float[]{0.7f, 0.3f}, 2);   // weights summing to 1
 * }</pre>
 *
 * <ul>
 *   <li>One thread at a time; allocates nothing after construction.</li>
 *   <li>{@link #component} and {@link #skinning} read the last {@link #resolve()}: resolve after the local pose
 *       changes.</li>
 * </ul>
 */
public final class CgPose {

    private static final int REST = CgSkeleton.REST;
    /** Unreal's {@code ZERO_ANIMWEIGHT_THRESH}: a weight this close to 0 or 1 is that weight. */
    static final float ZERO_WEIGHT = 0.00001f;

    private final CgSkeleton skeleton;
    /** Per bone: translation 3, rotation 4 (x, y, z, w), scale 3, as the skeleton's rest. */
    final float[] local;
    private final Matrix4f[] component;
    private final Matrix4f scratch = new Matrix4f();

    public CgPose(CgSkeleton skeleton) {
        this.skeleton = skeleton;
        this.local = skeleton.rest().clone();
        this.component = new Matrix4f[skeleton.boneCount()];
        for (int b = 0; b < component.length; b++) component[b] = new Matrix4f();
    }

    public CgSkeleton skeleton() {
        return skeleton;
    }

    /** Every bone back to its rest pose. */
    public CgPose rest() {
        float[] rest = skeleton.rest();
        System.arraycopy(rest, 0, local, 0, rest.length);
        return this;
    }

    /** {@code from}'s local pose, of the same skeleton. */
    public CgPose set(CgPose from) {
        requireSame(from);
        System.arraycopy(from.local, 0, local, 0, local.length);
        return this;
    }

    public Vector3f translation(int bone, Vector3f out) {
        int at = bone * REST;
        return out.set(local[at], local[at + 1], local[at + 2]);
    }

    public Quaternionf rotation(int bone, Quaternionf out) {
        int at = bone * REST;
        return out.set(local[at + 3], local[at + 4], local[at + 5], local[at + 6]);
    }

    public Vector3f scale(int bone, Vector3f out) {
        int at = bone * REST;
        return out.set(local[at + 7], local[at + 8], local[at + 9]);
    }

    /** Sets a bone's local transform; {@code rotation} unit length. */
    public CgPose set(int bone, float tx, float ty, float tz, float qx, float qy, float qz, float qw, float sx, float sy, float sz) {
        int at = bone * REST;
        local[at] = tx;
        local[at + 1] = ty;
        local[at + 2] = tz;
        local[at + 3] = qx;
        local[at + 4] = qy;
        local[at + 5] = qz;
        local[at + 6] = qw;
        local[at + 7] = sx;
        local[at + 8] = sy;
        local[at + 9] = sz;
        return this;
    }

    /**
     * The weighted blend of {@code count} poses of this skeleton (Unreal's {@code BlendPosesTogether},
     * AnimationRuntime.cpp:364-391): the first written times its weight, each other added along the shortest rotation
     * ({@code AccumulateWithShortestRotation}, TransformVectorized.h:1060-1071), the rotations then normalised.
     *
     * @param weights summing to 1, as the caller normalised them
     */
    public CgPose blend(CgPose[] poses, float[] weights, int count) {
        if (count < 1) throw new IllegalArgumentException("a blend of no poses");
        if (count == 1) return set(poses[0]);
        for (int p = 0; p < count; p++) requireSame(poses[p]);
        float[] first = poses[0].local;
        float w0 = weights[0];
        for (int i = 0; i < local.length; i++) local[i] = first[i] * w0;
        for (int p = 1; p < count; p++) {
            float[] other = poses[p].local;
            float w = weights[p];
            for (int at = 0; at < local.length; at += REST) {
                for (int c = 0; c < 3; c++) local[at + c] += other[at + c] * w;
                float dot = local[at + 3] * other[at + 3] + local[at + 4] * other[at + 4]
                        + local[at + 5] * other[at + 5] + local[at + 6] * other[at + 6];
                float sw = dot >= 0f ? w : -w;
                for (int c = 3; c < 7; c++) local[at + c] += other[at + c] * sw;
                for (int c = 7; c < 10; c++) local[at + c] += other[at + c] * w;
            }
        }
        for (int at = 0; at < local.length; at += REST) normalizeRotation(local, at + 3);
        return this;
    }

    /** Each bone's transform in the skeleton's space from the local pose, parents first (FillUpComponentSpaceTransforms). */
    public CgPose resolve() {
        for (int b = 0; b < component.length; b++) {
            int at = b * REST;
            Matrix4f m = component[b].translationRotateScale(local[at], local[at + 1], local[at + 2], local[at + 3],
                    local[at + 4], local[at + 5], local[at + 6], local[at + 7], local[at + 8], local[at + 9]);
            int parent = skeleton.parent(b);
            if (parent >= 0) component[parent].mul(m, m);
        }
        return this;
    }

    /** Bone {@code bone}'s transform in the skeleton's space, as of the last {@link #resolve()}. */
    public Matrix4f component(int bone, Matrix4f out) {
        return out.set(component[bone]);
    }

    /** Bone {@code bone}'s transform in the skeleton's space, read in place: valid until the next {@link #resolve()}. */
    public Matrix4fc component(int bone) {
        return component[bone];
    }

    /**
     * Every bone's skinning matrix, component × the mesh's inverse bind, into {@code out}: what
     * {@code CgBonePalettes.write} and {@code CgSkinning.skin} take. Reads the last {@link #resolve()}.
     */
    public Matrix4f[] skinning(CgSkeletalMesh mesh, Matrix4f[] out) {
        if (mesh.skeleton() != skeleton && !mesh.skeleton().equals(skeleton)) {
            throw new IllegalArgumentException(mesh + " is not of this pose's skeleton");
        }
        if (out.length < component.length) throw new IllegalArgumentException(out.length + " matrices for " + component.length + " bones");
        for (int b = 0; b < component.length; b++) component[b].mul(mesh.inverseBind(b, scratch), out[b]);
        return out;
    }

    private void requireSame(CgPose other) {
        if (other.skeleton != skeleton) throw new IllegalArgumentException("a pose of another skeleton");
    }

    /** Divides the quaternion at {@code at} by its length, leaving a unit one's bits alone. */
    static void normalizeRotation(float[] q, int at) {
        float length = CgAnimSequence.unitLength(q[at], q[at + 1], q[at + 2], q[at + 3]);
        if (length == 1f) return;
        if (length == 0f) {
            q[at] = q[at + 1] = q[at + 2] = 0f;
            q[at + 3] = 1f;
            return;
        }
        for (int c = 0; c < 4; c++) q[at + c] /= length;
    }
}
