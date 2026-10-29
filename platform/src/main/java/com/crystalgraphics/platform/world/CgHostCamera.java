package com.crystalgraphics.platform.world;

import com.crystalgraphics.platform.CgService;

/**
 * The host's camera as core moves it: a small offset a frame (a shake, an FOV kick), or the whole camera held (a rig, a
 * cinematic) until it is released. A host applies the last of each it was given where it sets its camera up, once a
 * frame; core sums every playing {@code com.crystalgraphics.vfx.camera.CgCameraShake} into the one offset, and composes
 * every rig into the one held pose, so a host only applies numbers.
 *
 * <pre>{@code
 * // core, once a frame
 * CgPlatform.get(CgHostCamera.SERVICE).offset(dx, dy, dz, yaw, pitch, roll, fovScale);
 * CgPlatform.get(CgHostCamera.SERVICE).hold(x, y, z, yaw, pitch, roll, fov, 0);   // a rig has the camera
 * CgPlatform.get(CgHostCamera.SERVICE).release();                             // and gives it back
 *
 * // a host, once, on a client only
 * CgPlatform.provide(CgHostCamera.SERVICE, new HostCameraModern());
 * }</pre>
 *
 * <ul>
 *   <li>An offset: blocks along the world's axes, degrees added to the camera's, {@code fovScale} multiplying its field of
 *       view (1 for none). It holds until the next call: a caller that stops shaking gives zeros and 1.</li>
 *   <li>A held pose is absolute: the eye in the world's blocks, Minecraft's yaw, pitch and roll in degrees, the vertical
 *       field of view in degrees. While it is held the camera is ours alone: the player's look and movement input reach
 *       neither the camera nor the player, and the host writes the pose after every other mod that sets the camera up.
 *       An offset still applies on top.</li>
 *   <li>Not every host applies every part: {@link #capabilities()} says which this one does. The rest is dropped.</li>
 *   <li>{@link #NONE} ignores it all, which is right where nothing has a camera to move. Render thread only.</li>
 * </ul>
 */
@FunctionalInterface
public interface CgHostCamera {

    /**
     * What a host applies, as bits of {@link #capabilities()}: yaw and pitch, roll, the field of view, the position of a
     * held pose, the lock of the player's input while one is held, and the player hidden where a held play asks.
     */
    int ROTATION = 1, ROLL = 2, FOV = 4, POSITION = 8, LOCK = 16, HIDES_PLAYER = 32;

    /** What a held pose asks of the viewer, as bits of {@link #hold}'s {@code flags}: the host's HUD and player hidden. */
    int HIDE_HUD = 1, HIDE_PLAYER = 2;

    /** No camera to move: the harness, a dedicated server. */
    CgHostCamera NONE = (x, y, z, yaw, pitch, roll, fovScale) -> { };

    CgService<CgHostCamera> SERVICE = CgService.of("crystalgraphics:host_camera", NONE);

    void offset(float x, float y, float z, float yaw, float pitch, float roll, float fovScale);

    /**
     * Holds the camera at this pose, the player's input locked out, its HUD and player as {@code flags} say. Called
     * every frame while held: a host meeting a frame with no call since the last releases by itself.
     */
    default void hold(double x, double y, double z, float yaw, float pitch, float roll, float fov, int flags) {
    }

    /** Gives the camera back: the host's own pose, input and every other mod's changes, from its next frame. */
    default void release() {
    }

    /** The parts this host applies ({@link #ROTATION}, {@link #ROLL}, {@link #FOV}, {@link #POSITION}, {@link #LOCK}); 0 for none. */
    default int capabilities() {
        return 0;
    }

    /**
     * The parts whose hook has run at least once since start: a part declared in {@link #capabilities()} and missing
     * here is wired to a hook the game never calls.
     */
    default int applied() {
        return 0;
    }
}
