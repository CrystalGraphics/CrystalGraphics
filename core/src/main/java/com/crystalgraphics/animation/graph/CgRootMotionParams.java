package com.crystalgraphics.animation.graph;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Root motion gathered over a frame, in the mesh's space, until something moves the character by it: Unreal's
 * {@code FRootMotionMovementParams} (AnimationAsset.h). An anim instance keeps one; its montages accumulate their steps
 * into it and the character's mover consumes it.
 *
 * <pre>{@code
 * Matrix4f step = new Matrix4f();
 * if (anim.consumeRootMotion(1f, step)) mover.move(step);    // all of this frame's motion
 * }</pre>
 *
 * <ul>
 *   <li>Steps compose as Unreal's {@code In * RootMotionTransform}: each later step is relative to where the earlier
 *       ones left the root.</li>
 *   <li>Scale is never carried: Unreal's {@code RootMotionScale} is one.</li>
 *   <li>Allocates nothing.</li>
 * </ul>
 */
public final class CgRootMotionParams {

    private final Matrix4f transform = new Matrix4f();
    private final Quaternionf rotation = new Quaternionf(), partial = new Quaternionf();
    private final Vector3f translation = new Vector3f();
    private final Matrix4f scratch = new Matrix4f();
    /** A blended sum's translation and rotation so far, and a step's taken apart. */
    private final Vector3f sumT = new Vector3f(), blendT = new Vector3f();
    private final Quaternionf sumQ = new Quaternionf(), blendQ = new Quaternionf();
    private float weight;
    private boolean has;

    /** Whether anything was accumulated since the last clear ({@code bHasRootMotion}). */
    public boolean hasRootMotion() {
        return has;
    }

    /** What it holds, into {@code out}; identity with nothing. */
    public Matrix4f get(Matrix4f out) {
        return has ? out.set(transform) : out.identity();
    }

    /** {@code Accumulate}: {@code step} after what it holds. */
    public void accumulate(Matrix4f step) {
        if (!has) {
            transform.set(step);
            has = true;
        } else {
            transform.mul(step);
        }
    }

    /** Replaces what it holds with {@code motion}: a warping node's bent root motion. */
    public void set(Matrix4f motion) {
        transform.set(motion);
        has = true;
        weight = 1f;
    }

    /**
     * {@code AccumulateWithBlend}: {@code step} at {@code weight}, summed with what it holds, the rotations along the
     * shortest way. A frame's players each add theirs; {@link #makeUpToFullWeight} closes the sum.
     *
     * <pre>{@code
     * motion.clear();
     * motion.accumulateWithBlend(runStep, 0.7f);
     * motion.accumulateWithBlend(walkStep, 0.3f);
     * motion.makeUpToFullWeight();
     * }</pre>
     */
    public void accumulateWithBlend(Matrix4f step, float weight) {
        step.getTranslation(blendT);
        step.getNormalizedRotation(blendQ);
        if (!has) {
            sumT.set(blendT).mul(weight);
            sumQ.set(blendQ.x * weight, blendQ.y * weight, blendQ.z * weight, blendQ.w * weight);
            this.weight = weight;
            has = true;
        } else {
            sumT.fma(weight, blendT);
            float sign = sumQ.x * blendQ.x + sumQ.y * blendQ.y + sumQ.z * blendQ.z + sumQ.w * blendQ.w < 0f ? -weight : weight;
            sumQ.set(sumQ.x + blendQ.x * sign, sumQ.y + blendQ.y * sign, sumQ.z + blendQ.z * sign, sumQ.w + blendQ.w * sign);
            this.weight += weight;
        }
        transform.translationRotate(sumT.x, sumT.y, sumT.z, blendQ.set(sumQ).normalize());
    }

    /** {@code MakeUpToFullWeight}: the weight short of 1 added as no motion, the rotation normalised. */
    public void makeUpToFullWeight() {
        if (!has) return;
        float left = Math.max(1f - weight, 0f);
        if (left > 1e-4f) accumulateWithBlend(scratch.identity(), left);
    }

    /** {@code Clear}. */
    public void clear() {
        has = false;
        weight = 0f;
        transform.identity();
    }

    /**
     * {@code ConsumeRootMotion}: {@code alpha} of it into {@code out}, its rotation slerped from identity and its
     * translation scaled, the rest kept ({@code GetRelativeTransform}). False, and {@code out} identity, with nothing.
     */
    public boolean consume(float alpha, Matrix4f out) {
        if (!has) {
            out.identity();
            return false;
        }
        transform.getNormalizedRotation(rotation);
        transform.getTranslation(translation);
        partial.identity().slerp(rotation, alpha);
        translation.mul(alpha);
        out.translationRotate(translation.x, translation.y, translation.z, partial);
        transform.set(scratch.set(out).invert().mul(transform));
        return true;
    }
}
