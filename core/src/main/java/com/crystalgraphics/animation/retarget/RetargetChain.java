package com.crystalgraphics.animation.retarget;

import com.crystalgraphics.animation.rig.CgRig;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** A rig chain resolved on a skeleton: its bones start to end, and each one's place along it, 0 to 1, in the retarget pose. */
final class RetargetChain {

    final CgRig.Chain chain;
    final int[] bones;
    final float[] params;

    private RetargetChain(CgRig.Chain chain, int[] bones, float[] params) {
        this.chain = chain;
        this.bones = bones;
        this.params = params;
    }

    static RetargetChain resolve(String owner, CgRig.Chain chain, RetargetSkeleton side) {
        int start = bone(owner, chain, chain.start(), side), end = bone(owner, chain, chain.end(), side);
        int n = 1;
        for (int b = end; b != start; b = side.skeleton.parent(b), n++) {
            if (b < 0) throw new IllegalArgumentException(owner + ": chain " + chain.name() + "'s end " + chain.end()
                    + " is not below its start " + chain.start());
        }
        int[] bones = new int[n];
        for (int b = end, i = n - 1; i >= 0; b = side.skeleton.parent(b), i--) bones[i] = b;
        float[] params = new float[n];
        if (n == 1) {
            params[0] = 1f;   // a single bone sits at the chain's end
        } else {
            float total = 0f;
            for (int i = 1; i < n; i++) {
                total += side.initPos[bones[i]].distance(side.initPos[bones[i - 1]]);
                params[i] = total;
            }
            for (int i = 1; i < n; i++) params[i] = total > 1e-6f ? params[i] / total : i / (float) (n - 1);
        }
        return new RetargetChain(chain, bones, params);
    }

    private static int bone(String owner, CgRig.Chain chain, String name, RetargetSkeleton side) {
        int b = side.skeleton.indexOf(name);
        if (b < 0) throw new IllegalArgumentException(owner + ": chain " + chain.name() + " names " + name + ", not a bone");
        return b;
    }

    int start() {
        return bones[0];
    }

    int end() {
        return bones[bones.length - 1];
    }

    /** The place and turn at {@code param} along it, between the two bones either side. */
    void sample(Vector3f[] pos, Quaternionf[] rot, float param, Vector3f outPos, Quaternionf outRot) {
        int n = bones.length;
        if (n == 1 || param <= params[0]) {
            outPos.set(pos[bones[0]]);
            outRot.set(rot[bones[0]]);
            return;
        }
        if (param >= params[n - 1]) {
            outPos.set(pos[bones[n - 1]]);
            outRot.set(rot[bones[n - 1]]);
            return;
        }
        int i = 1;
        while (params[i] < param) i++;
        float span = params[i] - params[i - 1];
        float t = span > 1e-6f ? (param - params[i - 1]) / span : 0f;
        pos[bones[i - 1]].lerp(pos[bones[i]], t, outPos);
        rot[bones[i - 1]].nlerp(rot[bones[i]], t, outRot);
    }
}
