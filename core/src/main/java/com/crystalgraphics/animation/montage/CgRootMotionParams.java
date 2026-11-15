package com.crystalgraphics.animation.montage;

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

    /** {@code Clear}. */
    public void clear() {
        has = false;
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
