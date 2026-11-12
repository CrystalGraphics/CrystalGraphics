package com.crystalgraphics.platform.world;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.CgService;

/**
 * Moves the player's camera (Unreal's {@code APlayerCameraManager}): a per-frame offset (shakes) or a held pose (rigs).
 * The host applies them where it sets its camera up.
 *
 * <pre>{@code
 * CgCameraManager.offset(dx, dy, dz, yaw, pitch, roll, fovScale);   // once a frame; zeros and 1 to stop
 * CgCameraManager.hold(x, y, z, yaw, pitch, roll, fov, 0);          // every frame while a rig has the camera
 * CgCameraManager.release();
 *
 * CgPlatform.provide(CgCameraManager.HOST, new HostCameraModern());  // a host, on a client
 * }</pre>
 *
 * <ul>
 *   <li>Angles are Minecraft's, in degrees: yaw 0 facing +z growing west, pitch positive looking down.</li>
 *   <li>A held pose is the engine's alone until released; an offset still applies on top.</li>
 *   <li>A host applies only what {@link #capabilities()} says. Render thread; ignored where there is no camera.</li>
 * </ul>
 */
public final class CgCameraManager {

    /** Parts a host applies ({@link #capabilities()}). */
    public static final int ROTATION = 1, ROLL = 2, FOV = 4, POSITION = 8, LOCK = 16, HIDES_PLAYER = 32, TRANSLATION = 64;

    /** {@link #hold} flags: hide the host's HUD, hide the player. */
    public static final int HIDE_HUD = 1, HIDE_PLAYER = 2;

    /** A host's camera. */
    @FunctionalInterface
    public interface Host {

        Host NONE = (x, y, z, yaw, pitch, roll, fovScale) -> { };

        /** This frame's offset: blocks along the world's axes, degrees added, an FOV multiplier. */
        void offset(float x, float y, float z, float yaw, float pitch, float roll, float fovScale);

        /** Holds the camera at this pose, input locked; unrefreshed for a frame, it releases itself ({@link CgHeldCamera}). */
        default void hold(double x, double y, double z, float yaw, float pitch, float roll, float fov, int flags) {
        }

        default void release() {
        }

        default int capabilities() {
            return 0;
        }

        /** Parts whose hook has run since start: one declared but missing is wired to a hook that never fires. */
        default int applied() {
            return 0;
        }
    }

    public static final CgService<Host> HOST = CgService.of("crystalgraphics:host_camera", Host.NONE);

    private static int writes;
    private static boolean held;

    private CgCameraManager() {
    }

    public static void offset(float x, float y, float z, float yaw, float pitch, float roll, float fovScale) {
        writes++;
        host().offset(x, y, z, yaw, pitch, roll, fovScale);
    }

    /** A rig's turn becomes these angles through {@code CgCameraPose.angles}. */
    public static void hold(double x, double y, double z, float yaw, float pitch, float roll, float fov, int flags) {
        held = true;
        host().hold(x, y, z, yaw, pitch, roll, fov, flags);
    }

    /** Gives the camera back; nothing where it was not held. */
    public static void release() {
        if (!held) return;
        held = false;
        host().release();
    }

    public static boolean held() {
        return held;
    }

    /** Offsets set so far: a writer can tell when another replaced its own. */
    public static int writes() {
        return writes;
    }

    public static int capabilities() {
        return host().capabilities();
    }

    public static int applied() {
        return host().applied();
    }

    private static Host host() {
        return CgPlatform.get(HOST);
    }
}
