package com.crystalgraphics.camera;

import javax.annotation.Nullable;

/**
 * An additive change on the persistent stack, applied after the rigs every frame: a camera animation on the viewer's
 * camera, a FOV kick. Mirrors Gameplay Cameras' persistent blend stack and Unreal's camera modifiers
 * ({@code UCameraModifier::ModifyCamera}).
 *
 * <pre>{@code
 * cameras.add(new CgCameraModifier() {
 *     float age;
 *     public void apply(CgCameraPose pose, float dt) { age += dt; pose.fov(pose.fov() * 1.1f); }
 *     public boolean done() { return age > 0.3f; }
 * });
 * }</pre>
 *
 * <ul>
 *   <li>Applied in the order added, on the viewer's clock; one {@link #done()} is dropped before its next frame.</li>
 *   <li>Runs on the frame thread: allocate nothing in {@link #apply}.</li>
 * </ul>
 */
public interface CgCameraModifier {

    /** Changes {@code pose}, the rigs' result and the modifiers' before this one, for a frame {@code dt} seconds long. */
    void apply(CgCameraPose pose, float dt);

    /** Whether it has finished and leaves the stack. */
    default boolean done() {
        return false;
    }

    /** Whose camera it is, a sequence's play: what {@link CgCameraSystem#weightOf} answers for. Null for nobody's. */
    @Nullable
    default Object owner() {
        return null;
    }

    /** How much of it is in the view this frame, 0 to 1. */
    default float weight() {
        return 1f;
    }
}
