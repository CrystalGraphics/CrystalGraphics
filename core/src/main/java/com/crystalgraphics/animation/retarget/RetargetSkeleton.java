package com.crystalgraphics.animation.retarget;

import com.crystalgraphics.api.animation.CgAnimPose;
import com.crystalgraphics.api.animation.CgSkeleton;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.Map;

/**
 * One side of a retarget: a skeleton's retarget pose and its current pose, both as locals and as globals in the shared
 * frame (the target model's space). Scale is dropped; a source's places are scaled by {@link #scale}.
 */
final class RetargetSkeleton {

    final CgSkeleton skeleton;
    final int count;
    /** The retarget pose's locals: rest translation, rest rotation times the pose's offset. */
    final Vector3f[] poseT;
    final Quaternionf[] poseR;
    /** The retarget pose's globals, in the shared frame. */
    final Vector3f[] initPos;
    final Quaternionf[] initRot;
    /** The current pose. */
    final Vector3f[] lpos, pos;
    final Quaternionf[] lrot, rot;
    private final Quaternionf frame = new Quaternionf(), frameInv = new Quaternionf();
    float scale = 1f;
    private final boolean[] dirty;
    private final Quaternionf q = new Quaternionf();
    private final Vector3f v = new Vector3f();

    RetargetSkeleton(String owner, CgSkeleton skeleton, Map<String, Quaternionf> pose, Quaternionfc frame, float scale) {
        this.skeleton = skeleton;
        this.count = skeleton.boneCount();
        this.frame.set(frame);
        this.frameInv.set(frame).conjugate();
        this.scale = scale;
        poseT = vectors(count);
        poseR = quats(count);
        initPos = vectors(count);
        initRot = quats(count);
        lpos = vectors(count);
        pos = vectors(count);
        lrot = quats(count);
        rot = quats(count);
        dirty = new boolean[count];
        for (int b = 0; b < count; b++) {
            skeleton.restTranslation(b, poseT[b]);
            skeleton.restRotation(b, poseR[b]);
        }
        for (Map.Entry<String, Quaternionf> e : pose.entrySet()) {
            int b = skeleton.indexOf(e.getKey());
            if (b < 0) throw new IllegalArgumentException(owner + ": its retarget pose names " + e.getKey() + ", not a bone");
            poseR[b].mul(e.getValue()).normalize();
        }
        resetToPose();
        resolve();
        for (int b = 0; b < count; b++) {
            initPos[b].set(pos[b]);
            initRot[b].set(rot[b]);
        }
    }

    private static Vector3f[] vectors(int n) {
        Vector3f[] out = new Vector3f[n];
        for (int i = 0; i < n; i++) out[i] = new Vector3f();
        return out;
    }

    private static Quaternionf[] quats(int n) {
        Quaternionf[] out = new Quaternionf[n];
        for (int i = 0; i < n; i++) out[i] = new Quaternionf();
        return out;
    }

    void resetToPose() {
        for (int b = 0; b < count; b++) {
            lpos[b].set(poseT[b]);
            lrot[b].set(poseR[b]);
        }
    }

    void load(CgAnimPose pose) {
        for (int b = 0; b < count; b++) {
            pose.translation(b, lpos[b]);
            pose.rotation(b, lrot[b]);
        }
    }

    /** Every global from the locals, parents first. */
    void resolve() {
        for (int b = 0; b < count; b++) global(b);
    }

    /** Bone {@code b}'s global from its local and its parent's global. */
    void global(int b) {
        int p = skeleton.parent(b);
        Vector3f t = v.set(lpos[b]).mul(scale);
        if (p < 0) {
            frame.transform(t, pos[b]);
            frame.mul(lrot[b], rot[b]);
        } else {
            rot[p].transform(t, pos[b]).add(pos[p]);
            rot[p].mul(lrot[b], rot[b]);
        }
        rot[b].normalize();
    }

    /** Every bone below {@code bone} from its local, after {@code bone}'s global changed. */
    void resolveBelow(int bone) {
        dirty[bone] = true;
        for (int b = bone + 1; b < count; b++) {
            int p = skeleton.parent(b);
            dirty[b] = p >= 0 && dirty[p];
            if (dirty[b]) global(b);
        }
        for (int b = bone; b < count; b++) dirty[b] = false;
    }

    /** Sets bone {@code b}'s global, its local following; its children are left until {@link #resolveBelow}. */
    void setGlobal(int b, Vector3fc p, Quaternionfc r) {
        pos[b].set(p);
        rot[b].set(r).normalize();
        int parent = skeleton.parent(b);
        if (parent < 0) {
            frameInv.mul(rot[b], lrot[b]);
            frameInv.transform(v.set(p), lpos[b]);
        } else {
            q.set(rot[parent]).conjugate();
            q.mul(rot[b], lrot[b]);
            q.transform(v.set(p).sub(pos[parent]), lpos[b]);
        }
        lrot[b].normalize();
        if (scale != 0f) lpos[b].div(scale);
    }
}
