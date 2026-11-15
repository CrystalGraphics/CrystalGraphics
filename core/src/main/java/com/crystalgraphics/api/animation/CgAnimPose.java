package com.crystalgraphics.api.animation;

import com.crystalgraphics.api.mesh.CgSkeletalMesh;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Arrays;

/**
 * One skeleton's pose: every bone's translation, rotation and scale local to its parent, and once {@link #resolve()}d
 * each bone's transform in the skeleton's space. What a clip is sampled into, poses are blended into, and skinning
 * matrices and sockets are read from. Unreal's {@code FCompactPose} and its component-space transforms.
 *
 * <pre>{@code
 * CgAnimPose pose = new CgAnimPose(kurama.skeleton());          // made once, reused every frame
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
 * out.blend(new CgAnimPose[]{run, wave}, new float[]{0.7f, 0.3f}, 2);   // weights summing to 1
 * }</pre>
 *
 * <ul>
 *   <li>One thread at a time; allocates nothing after construction.</li>
 *   <li>{@link #component} and {@link #skinning} read the last {@link #resolve()}: resolve after the local pose
 *       changes.</li>
 * </ul>
 */
public final class CgAnimPose {

    private static final int REST = CgSkeleton.REST;
    /** Unreal's {@code ZERO_ANIMWEIGHT_THRESH}: a weight this close to 0 or 1 is that weight. */
    public static final float ZERO_WEIGHT = 0.00001f;

    private final CgSkeleton skeleton;
    /** Per bone: translation 3, rotation 4 (x, y, z, w), scale 3, as the skeleton's rest. */
    final float[] local;
    private final Matrix4f[] component;
    private final Matrix4f scratch = new Matrix4f();
    /** blendComponent's: the set bones' locals before, which bones moved, a decomposed transform. */
    private final float[] saved;
    private final boolean[] moved;
    private final Vector3f blendT = new Vector3f(), blendS = new Vector3f();
    private final Quaternionf blendQ = new Quaternionf();

    public CgAnimPose(CgSkeleton skeleton) {
        this.skeleton = skeleton;
        this.local = skeleton.rest().clone();
        this.component = new Matrix4f[skeleton.boneCount()];
        for (int b = 0; b < component.length; b++) component[b] = new Matrix4f();
        this.saved = new float[local.length];
        this.moved = new boolean[component.length];
    }

    public CgSkeleton skeleton() {
        return skeleton;
    }

    /** Every bone back to its rest pose. */
    public CgAnimPose rest() {
        float[] rest = skeleton.rest();
        System.arraycopy(rest, 0, local, 0, rest.length);
        return this;
    }

    /** {@code from}'s local pose, of the same skeleton. */
    public CgAnimPose set(CgAnimPose from) {
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
    public CgAnimPose set(int bone, float tx, float ty, float tz, float qx, float qy, float qz, float qw, float sx, float sy, float sz) {
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
    public CgAnimPose blend(CgAnimPose[] poses, float[] weights, int count) {
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

    /**
     * This pose as differences from {@code base}, bone by bone: Unreal's {@code ConvertPoseToAdditive}
     * (AnimationRuntime.cpp:1109-1148). Rotation {@code q · base⁻¹}, translation {@code t − base}, scale
     * {@code s / base − 1}, so differences blend and add. What {@link CgAnimSequence#sampleAdditive} makes.
     */
    public CgAnimPose convertToAdditive(CgAnimPose base) {
        requireSame(base);
        float[] b = base.local;
        for (int at = 0; at < local.length; at += REST) {
            float qx = local[at + 3], qy = local[at + 4], qz = local[at + 5], qw = local[at + 6];
            // q · conj(b): b is unit, so its conjugate is its inverse
            float bx = -b[at + 3], by = -b[at + 4], bz = -b[at + 5], bw = b[at + 6];
            local[at + 3] = qw * bx + qx * bw + qy * bz - qz * by;
            local[at + 4] = qw * by - qx * bz + qy * bw + qz * bx;
            local[at + 5] = qw * bz + qx * by - qy * bx + qz * bw;
            local[at + 6] = qw * bw - qx * bx - qy * by - qz * bz;
            normalizeRotation(local, at + 3);
            for (int c = 0; c < 3; c++) {
                local[at + c] -= b[at + c];
                float s = b[at + 7 + c];
                // GetSafeScaleReciprocal: 0 where the base's scale is within SMALL_NUMBER of 0
                local[at + 7 + c] = local[at + 7 + c] * (Math.abs(s) <= 1e-8f ? 0f : 1f / s) - 1f;
            }
        }
        return this;
    }

    /**
     * {@code additive}'s differences added on top at {@code weight}, bone by bone: Unreal's
     * {@code AccumulateLocalSpaceAdditivePoseInternal} (AnimationRuntime.cpp). At full weight
     * {@code AccumulateWithAdditiveScale}, else {@code BlendFromIdentityAndAccumulate} (TransformNonVectorized.h:
     * 1020-1095): rotation {@code a · q}, translation {@code t + a}, scale {@code s · (1 + a)}.
     *
     * <pre>{@code
     * flinch.sampleAdditive(delta, scratch, t);
     * body.accumulateAdditive(delta, 0.6f);      // a flinch at 60% over whatever the body plays
     * }</pre>
     */
    public CgAnimPose accumulateAdditive(CgAnimPose additive, float weight) {
        requireSame(additive);
        if (weight <= ZERO_WEIGHT) return this;
        boolean full = weight >= 1f - ZERO_WEIGHT;
        float[] a = additive.local;
        for (int at = 0; at < local.length; at += REST) {
            float ax = a[at + 3], ay = a[at + 4], az = a[at + 5], aw = a[at + 6];
            float tx = a[at], ty = a[at + 1], tz = a[at + 2], sx = a[at + 7], sy = a[at + 8], sz = a[at + 9];
            if (!full) {
                // FTransform::Blend from the additive identity: FastLerp from (0,0,0,1), then normalised
                float bias = aw >= 0f ? 1f - weight : weight - 1f;
                ax *= weight;
                ay *= weight;
                az *= weight;
                aw = aw * weight + bias;
                float n = (float) Math.sqrt(ax * ax + ay * ay + az * az + aw * aw);
                if (n > 0f) {
                    ax /= n;
                    ay /= n;
                    az /= n;
                    aw /= n;
                }
                tx *= weight;
                ty *= weight;
                tz *= weight;
                sx *= weight;
                sy *= weight;
                sz *= weight;
            }
            float qx = local[at + 3], qy = local[at + 4], qz = local[at + 5], qw = local[at + 6];
            local[at + 3] = aw * qx + ax * qw + ay * qz - az * qy;
            local[at + 4] = aw * qy - ax * qz + ay * qw + az * qx;
            local[at + 5] = aw * qz + ax * qy - ay * qx + az * qw;
            local[at + 6] = aw * qw - ax * qx - ay * qy - az * qz;
            normalizeRotation(local, at + 3);
            local[at] += tx;
            local[at + 1] += ty;
            local[at + 2] += tz;
            local[at + 7] *= 1f + sx;
            local[at + 8] *= 1f + sy;
            local[at + 9] *= 1f + sz;
        }
        return this;
    }

    /**
     * {@code additive}'s mesh-space rotations added at {@code weight}: Unreal's
     * {@code AccumulateMeshSpaceRotationAdditiveToLocalPoseInternal} (AnimationRuntime.cpp:1328-1343). This pose's
     * rotations are taken to the mesh's space, the differences added there, and brought back, so an aim offset turns the
     * spine and arms together whatever the legs do.
     *
     * <pre>{@code
     * aimClip.sampleAdditive(delta, scratch, t);     // a mesh-space additive clip
     * body.accumulateMeshRotationAdditive(delta, 1f);
     * }</pre>
     */
    public CgAnimPose accumulateMeshRotationAdditive(CgAnimPose additive, float weight) {
        if (weight <= ZERO_WEIGHT) return this;
        toMeshRotation();
        accumulateAdditive(additive, weight);
        return fromMeshRotation();
    }

    /** {@code ConvertPoseToMeshRotation} (AnimationRuntime.cpp:1150-1179): each rotation its parent's times its own, root first. */
    public CgAnimPose toMeshRotation() {
        for (int b = 1; b < skeleton.boneCount(); b++) {
            int at = b * REST, p = skeleton.parent(b) * REST;
            mul(local, p + 3, local, at + 3, local, at + 3);
        }
        return this;
    }

    /** {@code ConvertMeshRotationPoseToLocalSpace} (:1181-1210): each rotation its parent's inverse times its own, leaves first. */
    public CgAnimPose fromMeshRotation() {
        for (int b = skeleton.boneCount() - 1; b > 0; b--) {
            int at = b * REST, p = skeleton.parent(b) * REST;
            float px = -local[p + 3], py = -local[p + 4], pz = -local[p + 5], pw = local[p + 6];
            float qx = local[at + 3], qy = local[at + 4], qz = local[at + 5], qw = local[at + 6];
            local[at + 3] = pw * qx + px * qw + py * qz - pz * qy;
            local[at + 4] = pw * qy - px * qz + py * qw + pz * qx;
            local[at + 5] = pw * qz + px * qy - py * qx + pz * qw;
            local[at + 6] = pw * qw - px * qx - py * qy - pz * qz;
        }
        return this;
    }

    /** {@code out[o..] = a[i..] · b[j..]}, Hamilton; {@code out} may be {@code b}. */
    private static void mul(float[] a, int i, float[] b, int j, float[] out, int o) {
        float ax = a[i], ay = a[i + 1], az = a[i + 2], aw = a[i + 3];
        float bx = b[j], by = b[j + 1], bz = b[j + 2], bw = b[j + 3];
        out[o] = aw * bx + ax * bw + ay * bz - az * by;
        out[o + 1] = aw * by - ax * bz + ay * bw + az * bx;
        out[o + 2] = aw * bz + ax * by - ay * bx + az * bw;
        out[o + 3] = aw * bw - ax * bx - ay * by - az * bz;
    }

    /**
     * Each bone moved toward {@code layer}'s by its weight in {@code boneWeights}, local to its parent: Unreal's layered
     * blend per bone in local space ({@code FAnimationRuntime::BlendPosesPerBoneFilter}). A weight of 0 keeps this
     * pose's bone, 1 takes the layer's.
     *
     * <pre>{@code
     * body.blendBones(face, faceWeights);   // the face's bones from the face pose, the rest from the body
     * }</pre>
     */
    public CgAnimPose blendBones(CgAnimPose layer, float[] boneWeights) {
        requireSame(layer);
        float[] other = layer.local;
        for (int b = 0, at = 0; b < boneWeights.length; b++, at += REST) {
            float w = boneWeights[b];
            if (w <= ZERO_WEIGHT) continue;
            if (w >= 1f - ZERO_WEIGHT) {
                System.arraycopy(other, at, local, at, REST);
                continue;
            }
            float keep = 1f - w;
            for (int c = 0; c < 3; c++) local[at + c] = local[at + c] * keep + other[at + c] * w;
            float dot = local[at + 3] * other[at + 3] + local[at + 4] * other[at + 4] + local[at + 5] * other[at + 5]
                    + local[at + 6] * other[at + 6];
            float sw = dot >= 0f ? w : -w;
            for (int c = 3; c < 7; c++) local[at + c] = local[at + c] * keep + other[at + c] * sw;
            normalizeRotation(local, at + 3);
            for (int c = 7; c < 10; c++) local[at + c] = local[at + c] * keep + other[at + c] * w;
        }
        return this;
    }

    /**
     * Mirrors the pose across {@code table}'s axis, each pair swapping sides: Unreal's {@code FAnimationRuntime::MirrorPose}
     * (AnimationRuntime.cpp:1432-1530). A bone's translation and rotation are taken into the skeleton's space through
     * the rest pose's rotations, mirrored, corrected for the two bones' rest rotations, and brought into the target's
     * parent; scale is kept. A bone the table leaves out stays as it is.
     *
     * <pre>{@code
     * swing.sample(pose.rest(), t);
     * pose.mirror(mirror);   // the right arm's swing, on the left
     * }</pre>
     */
    public CgAnimPose mirror(CgMirrorTable table) {
        if (table.skeleton() != skeleton && !table.skeleton().equals(skeleton)) {
            throw new IllegalArgumentException(table + " is of another skeleton");
        }
        int[] source = table.source;
        float[] ref = table.restRotation;
        CgMirrorTable.Axis axis = table.axis();
        if (source.length > 0 && source[0] >= 0) {
            // The root, in place (AnimationRuntime.cpp:1490-1508)
            Vector3f t = translation(0, mirrorT);
            mirror(t, axis);
            Quaternionf q = mirror(rotation(0, mirrorQ), axis);
            Quaternionf rest = mirrorRest.set(ref[0], ref[1], ref[2], ref[3]);
            q.mul(mirror(mirrorTmp.set(rest), axis).invert()).mul(rest);
            write(0, t, q, local, 0);
        }
        for (int target = 1; target < source.length; target++) {
            int from = source[target];
            if (from == target) {
                mirrorBone(table, target, target, mirrorA);
                System.arraycopy(mirrorA, 0, local, target * REST, REST);
            } else if (from > target) {
                mirrorBone(table, from, target, mirrorA);
                mirrorBone(table, target, from, mirrorB);
                System.arraycopy(mirrorA, 0, local, target * REST, REST);
                System.arraycopy(mirrorB, 0, local, from * REST, REST);
            }
        }
        return this;
    }

    private final Vector3f mirrorT = new Vector3f();
    private final Quaternionf mirrorQ = new Quaternionf(), mirrorRest = new Quaternionf(), mirrorTmp = new Quaternionf(),
            mirrorParent = new Quaternionf();
    private final float[] mirrorA = new float[REST], mirrorB = new float[REST];

    /** Unreal's {@code MirrorTransform}: bone {@code from}'s local transform as bone {@code to}'s, into {@code out}. */
    private void mirrorBone(CgMirrorTable table, int from, int to, float[] out) {
        float[] ref = table.restRotation;
        CgMirrorTable.Axis axis = table.axis();
        int fromParent = skeleton.parent(from), toParent = skeleton.parent(to);
        Quaternionf sourceParent = mirrorParent.set(ref[fromParent * 4], ref[fromParent * 4 + 1], ref[fromParent * 4 + 2], ref[fromParent * 4 + 3]);
        Vector3f t = sourceParent.transform(translation(from, mirrorT));
        Quaternionf q = sourceParent.mul(rotation(from, mirrorQ), mirrorQ);
        mirror(t, axis);
        mirror(q, axis);
        Quaternionf sourceBone = mirror(mirrorRest.set(ref[from * 4], ref[from * 4 + 1], ref[from * 4 + 2], ref[from * 4 + 3]), axis).invert();
        q.mul(sourceBone).mul(mirrorTmp.set(ref[to * 4], ref[to * 4 + 1], ref[to * 4 + 2], ref[to * 4 + 3]));
        Quaternionf targetParentInverse = mirrorParent.set(ref[toParent * 4], ref[toParent * 4 + 1], ref[toParent * 4 + 2], ref[toParent * 4 + 3]).invert();
        targetParentInverse.transform(t);
        targetParentInverse.mul(q, q);
        write(from, t, q, out, -1);
    }

    /** Bone {@code scaleOf}'s scale with {@code t} and {@code q} into {@code out} at bone {@code at} (-1: at 0 of a bone's own array). */
    private void write(int scaleOf, Vector3f t, Quaternionf q, float[] out, int at) {
        int o = at < 0 ? 0 : at * REST;
        int s = scaleOf * REST + 7;
        float s0 = local[s], s1 = local[s + 1], s2 = local[s + 2];
        out[o] = t.x;
        out[o + 1] = t.y;
        out[o + 2] = t.z;
        q.normalize();
        out[o + 3] = q.x;
        out[o + 4] = q.y;
        out[o + 5] = q.z;
        out[o + 6] = q.w;
        out[o + 7] = s0;
        out[o + 8] = s1;
        out[o + 9] = s2;
    }

    /** Unreal's {@code MirrorVector}: the axis's coordinate negated. */
    private static void mirror(Vector3f v, CgMirrorTable.Axis axis) {
        switch (axis) {
            case X -> v.x = -v.x;
            case Y -> v.y = -v.y;
            case Z -> v.z = -v.z;
        }
    }

    /** Unreal's {@code MirrorQuat} (AnimationRuntime.cpp:1396-1430): the two other axes' parts negated. */
    private static Quaternionf mirror(Quaternionf q, CgMirrorTable.Axis axis) {
        switch (axis) {
            case X -> q.set(q.x, -q.y, -q.z, q.w);
            case Y -> q.set(-q.x, q.y, -q.z, q.w);
            case Z -> q.set(-q.x, -q.y, q.z, q.w);
        }
        return q;
    }

    /** Each bone's transform in the skeleton's space from the local pose, parents first (FillUpComponentSpaceTransforms). */
    public CgAnimPose resolve() {
        for (int b = 0; b < component.length; b++) {
            int at = b * REST;
            Matrix4f m = component[b].translationRotateScale(local[at], local[at + 1], local[at + 2], local[at + 3],
                    local[at + 4], local[at + 5], local[at + 6], local[at + 7], local[at + 8], local[at + 9]);
            int parent = skeleton.parent(b);
            if (parent >= 0) component[parent].mulAffine(m, m);   // TRS both: the bottom row is (0, 0, 0, 1)
        }
        return this;
    }

    /**
     * Sets {@code count} bones' transforms in the skeleton's space, each bone's children following it, then at an
     * {@code alpha} below 1 blends the set bones' local transforms back toward the ones they had: Unreal's
     * {@code FCSPose::LocalBlendCSBoneTransforms} (BonePose.h:769-830), how a skeletal control lands. Resolves after.
     *
     * <pre>{@code
     * pose.resolve();
     * // a solver writes thigh, calf and foot in the skeleton's space
     * pose.blendComponent(3, bones, transforms, 0.5f);   // half its effect, the toes riding the foot
     * }</pre>
     *
     * <ul>
     *   <li>Reads the last {@link #resolve()}.</li>
     *   <li>{@code bones} ascending, as Unreal's bone transforms are: a parent before its children.</li>
     * </ul>
     */
    public CgAnimPose blendComponent(int count, int[] bones, Matrix4fc[] transforms, float alpha) {
        if (count == 0 || alpha < ZERO_WEIGHT) return this;
        if (alpha < 1f - ZERO_WEIGHT) {
            for (int i = 0; i < count; i++) System.arraycopy(local, bones[i] * REST, saved, i * REST, REST);
        }
        // SafeSetCSBoneTransforms: each set against its parent as already moved, the moved bones' children following
        for (int i = 0; i < count; i++) {
            int bone = bones[i];
            if (i > 0 && bone < bones[i - 1]) throw new IllegalArgumentException("bones out of order at " + i);
            int parent = skeleton.parent(bone);
            Matrix4f m = parent < 0 ? scratch.set(transforms[i]) : component[parent].invertAffine(scratch).mulAffine(transforms[i]);
            m.getTranslation(blendT);
            m.getNormalizedRotation(blendQ);
            m.getScale(blendS);
            set(bone, blendT.x, blendT.y, blendT.z, blendQ.x, blendQ.y, blendQ.z, blendQ.w, blendS.x, blendS.y, blendS.z);
            component[bone].set(transforms[i]);
            Arrays.fill(moved, false);
            moved[bone] = true;
            for (int b = bone + 1; b < component.length; b++) {
                int p = skeleton.parent(b);
                if (p < 0 || !moved[p]) continue;
                int at = b * REST;
                component[p].mulAffine(component[b].translationRotateScale(local[at], local[at + 1], local[at + 2],
                        local[at + 3], local[at + 4], local[at + 5], local[at + 6], local[at + 7], local[at + 8], local[at + 9]),
                        component[b]);
                moved[b] = true;
            }
        }
        if (alpha < 1f - ZERO_WEIGHT) {
            // Pose[Bone].BlendWith(LocalBefore, 1 - Alpha): FTransform::Blend, the rotation the shortest lerp, normalised
            float back = 1f - alpha;
            for (int i = 0; i < count; i++) {
                int at = bones[i] * REST, from = i * REST;
                for (int c = 0; c < REST; c++) {
                    if (c == 3) {
                        float dot = local[at + 3] * saved[from + 3] + local[at + 4] * saved[from + 4]
                                + local[at + 5] * saved[from + 5] + local[at + 6] * saved[from + 6];
                        float sign = dot >= 0f ? 1f : -1f;
                        for (int q = 3; q < 7; q++) local[at + q] = local[at + q] * alpha + saved[from + q] * back * sign;
                        normalizeRotation(local, at + 3);
                        c = 6;
                        continue;
                    }
                    local[at + c] = local[at + c] * alpha + saved[from + c] * back;
                }
            }
        }
        return resolve();
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
        for (int b = 0; b < component.length; b++) component[b].mulAffine(mesh.inverseBind(b, scratch), out[b]);   // both affine
        return out;
    }

    private void requireSame(CgAnimPose other) {
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
