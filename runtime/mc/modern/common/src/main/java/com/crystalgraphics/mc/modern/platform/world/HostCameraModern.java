package com.crystalgraphics.mc.modern.platform.world;

import com.crystalgraphics.platform.service.CgHostCamera;

/**
 * {@link CgHostCamera} for the modern hosts: holds the last offset core gave and adds it where each loader sets its
 * camera up — Forge's and NeoForge's camera-angle and field-of-view events, and node mixins on the camera and its field
 * of view where those events fall short or Fabric has none. Client only: constructed by
 * {@code PlatformServiceModern.gl()}.
 *
 * <pre>{@code
 * // a loader, at its camera hook
 * event.setYaw(HostCameraModern.yaw(event.getYaw()));
 * event.setFOV(HostCameraModern.fov(event.getFOV()));
 * }</pre>
 *
 * <ul>
 *   <li>Rotation, roll and field of view only: no modern hook offers the camera's position, so the translation is
 *       dropped. Fabric rolls from 1.21.11, where Minecraft renders from the camera's quaternion; Forge 26.1.1 to 26.2
 *       turn the camera in a node mixin, their angle event coming after the view is taken.</li>
 *   <li>Render thread only, like the hooks that read it.</li>
 * </ul>
 */
public final class HostCameraModern implements CgHostCamera {

    private static float yaw, pitch, roll, fovScale = 1f, eventRoll;
    private static final Angle YAW = new Angle(), PITCH = new Angle(), ROLL_ANGLE = new Angle();
    private static volatile int capabilities;
    /** The parts whose hook has run: render thread only, like the hooks. */
    private static int applied;

    /** A loader, where it wires a hook: what it applies ({@code CgHostCamera.ROTATION} and the rest). */
    public static synchronized void declare(int parts) {
        capabilities |= parts;
    }

    @Override
    public int capabilities() {
        return capabilities;
    }

    @Override
    public int applied() {
        return applied;
    }

    @Override
    public void offset(float x, float y, float z, float yaw, float pitch, float roll, float fovScale) {
        HostCameraModern.yaw = yaw;
        HostCameraModern.pitch = pitch;
        HostCameraModern.roll = roll;
        HostCameraModern.fovScale = fovScale;
    }

    public static float yaw(float base) {
        applied |= ROTATION;
        return YAW.add(base, yaw);
    }

    public static float pitch(float base) {
        return PITCH.add(base, pitch);
    }

    public static float roll(float base) {
        applied |= ROLL;
        return eventRoll = ROLL_ANGLE.add(base, roll);
    }

    /**
     * One angle's offset, added once: an angle arriving as the turned value we last returned passes unchanged. Forge 26.3
     * posts its camera event twice a frame, the second from the camera the first already turned.
     */
    private static final class Angle {
        private float in = Float.NaN, out = Float.NaN;

        float add(float base, float offset) {
            if (base == out && out != in) return base;
            in = base;
            return out = base + offset;
        }
    }

    /**
     * The roll the loader's camera event ended with, degrees, as our offset left it: 0 where no event rolls (Fabric).
     * Below 1.21.6 Minecraft keeps it only in the pose stack, so the host's view composes it from here.
     */
    public static float eventRoll() {
        return eventRoll;
    }

    public static double fov(double base) {
        applied |= FOV;
        return base * fovScale;
    }

    public static float fov(float base) {
        applied |= FOV;
        return base * fovScale;
    }
}
