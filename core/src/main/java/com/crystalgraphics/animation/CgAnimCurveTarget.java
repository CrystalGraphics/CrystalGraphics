package com.crystalgraphics.animation;

/**
 * Where an {@link CgAnimInstance}'s material curves land each frame: Unreal's component materials, set by
 * {@code UpdateCurvesPostEvaluation}. A {@code CgSkeletalMeshComponent} is one and sets itself on its instance; implement
 * it to drive something else from a clip's curves.
 *
 * <pre>{@code
 * anim.curveTarget(new CgAnimCurveTarget() {
 *     @Override public void scalar(String name, float value) { glow.set1f(name, value); }
 *     @Override public void resetScalar(String name) { glow.set1f(name, 0f); }
 * });
 * }</pre>
 *
 * <ul>
 *   <li>Called on the world's thread after {@code POSE}'s evaluation, once a frame per curve a clip keys.</li>
 *   <li>{@link #resetScalar} comes the first frame no clip keys a curve that one did.</li>
 * </ul>
 */
public interface CgAnimCurveTarget {

    /** A curve's value this frame. */
    void scalar(String name, float value);

    /** No clip keys the curve any more: back to its default. */
    void resetScalar(String name);
}
