package com.crystalgraphics.mc.modern.platform.world;

import com.crystalgraphics.platform.service.CgHostCamera;

/**
 * {@link CgHostCamera} for the modern hosts: holds the last offset core gave and adds it where each loader sets its
 * camera up — Forge's and NeoForge's camera-angle and field-of-view events, a node mixin on Fabric's {@code Camera.setup}
 * and {@code GameRenderer.getFov}. Client only: constructed by {@code PlatformServiceModern.gl()}.
 *
 * <pre>{@code
 * // a loader, at its camera hook
 * event.setYaw(HostCameraModern.yaw(event.getYaw()));
 * event.setFOV(HostCameraModern.fov(event.getFOV()));
 * }</pre>
 *
 * <ul>
 *   <li>Rotation, roll and field of view only: no modern hook offers the camera's position, so the translation is
 *       dropped. Fabric has no roll either (its {@code setRotation} takes two angles).</li>
 *   <li>Render thread only, like the hooks that read it.</li>
 * </ul>
 */
public final class HostCameraModern implements CgHostCamera {

    private static float yaw, pitch, roll, fovScale = 1f;
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
        return base + yaw;
    }

    public static float pitch(float base) {
        return base + pitch;
    }

    public static float roll(float base) {
        applied |= ROLL;
        return base + roll;
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
