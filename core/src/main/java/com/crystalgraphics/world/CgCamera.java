package com.crystalgraphics.world;

import org.joml.Quaternionfc;
import org.joml.Vector3f;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.world.CgHostCamera;

/**
 * The host's camera: moved by a little for one frame (the offset every playing {@code CgCameraShake} sums to), or held
 * whole by a rig until released (the pose {@code CgCameraSystem} composes). The only reader of {@link CgHostCamera}; an
 * effect shakes through {@code CgCameraShake} and a move takes the camera through {@code CgCameraSystem}, never here.
 *
 * <pre>{@code
 * CgCamera.offset(dx, dy, dz, yaw, pitch, roll, fovScale);   // once a frame; zeros and 1 to stop
 * CgCamera.hold(x, y, z, turn, fov, true, 0);                 // every frame while a rig has the camera
 * CgCamera.release();                                          // once, as it gives it back
 * if ((CgCamera.capabilities() & CgHostCamera.ROLL) == 0) foldRollIntoYaw();
 * }</pre>
 *
 * <ul>
 *   <li>A turn is the pose's: its forward is +x and its up +y, in the world's axes ({@code CgCameraPose}). The host is
 *       given Minecraft's yaw (0 facing +z, growing to the west), pitch (positive looking down) and roll.</li>
 *   <li>Render thread only. Where nothing has a camera (a server, the harness) every call is ignored.</li>
 * </ul>
 */
public final class CgCamera {

    private static int writes;
    private static boolean held;
    private static final float[] ANGLES = new float[3];
    private static final Vector3f FORWARD = new Vector3f(), UP = new Vector3f(), LEVEL = new Vector3f(), RIGHT = new Vector3f();

    private CgCamera() {
    }

    /** This frame's offset, holding until the next call: blocks along the world's axes, degrees, an FOV multiplier. */
    public static void offset(float x, float y, float z, float yaw, float pitch, float roll, float fovScale) {
        writes++;
        camera().offset(x, y, z, yaw, pitch, roll, fovScale);
    }

    /**
     * Holds the camera at the eye {@code (x, y, z)} turned by {@code turn}, its vertical field of view {@code fov} degrees,
     * level where {@code roll} is false, the viewer's HUD and player as {@code flags} ({@link CgHostCamera#HIDE_HUD},
     * {@code HIDE_PLAYER}) say. Every frame while held: a host missing a frame's call releases by itself.
     */
    public static void hold(double x, double y, double z, Quaternionfc turn, float fov, boolean roll, int flags) {
        angles(turn, ANGLES);
        if (!roll) ANGLES[2] = 0f;
        held = true;
        camera().hold(x, y, z, ANGLES[0], ANGLES[1], ANGLES[2], fov, flags);
    }

    /** Gives the camera back to the host, once; nothing where it was not held. */
    public static void release() {
        if (!held) return;
        held = false;
        camera().release();
    }

    /** Whether a rig holds the camera now. */
    public static boolean held() {
        return held;
    }

    /** {@code turn} as Minecraft's yaw, pitch and roll in degrees, into {@code out}. */
    public static float[] angles(Quaternionfc turn, float[] out) {
        turn.transform(FORWARD.set(1f, 0f, 0f));
        turn.transform(UP.set(0f, 1f, 0f));
        out[0] = (float) Math.toDegrees(Math.atan2(-FORWARD.x, FORWARD.z));
        out[1] = (float) Math.toDegrees(Math.asin(Math.max(-1f, Math.min(1f, -FORWARD.y))));
        // Roll: how far its up has turned about its forward from the up a level camera facing there would have.
        LEVEL.set(0f, 1f, 0f).fma(-FORWARD.y, FORWARD);
        if (LEVEL.lengthSquared() < 1e-12f) {
            out[2] = 0f;
            return out;
        }
        LEVEL.normalize();
        FORWARD.cross(LEVEL, RIGHT);
        out[2] = (float) Math.toDegrees(Math.atan2(UP.dot(RIGHT), UP.dot(LEVEL)));
        return out;
    }

    /** How many offsets have been set: a writer that reads it after its own call can tell when another replaced it. */
    public static int writes() {
        return writes;
    }

    /** The parts this host applies ({@link CgHostCamera#ROTATION}, {@code ROLL}, {@code FOV}, {@code POSITION}, {@code LOCK}). */
    public static int capabilities() {
        return camera().capabilities();
    }

    /** The parts whose hook has run since start: one in {@link #capabilities()} and missing here never fires. */
    public static int applied() {
        return camera().applied();
    }

    private static CgHostCamera camera() {
        return CgPlatform.get(CgHostCamera.SERVICE);
    }
}
