package com.crystalgraphics.mc.v1710.platform.world;

import com.crystalgraphics.platform.service.CgHostCamera;
import org.lwjgl.opengl.GL11;

/**
 * {@link CgHostCamera} on Minecraft 1.7.10: holds the last offset core gave, and {@code CameraHook} adds it where the
 * entity renderer orients its camera and computes its field of view. 1.7.10's Forge has no camera-angle event, and its
 * FOV event is the player's smoothed modifier, so both are mixins here. Client only: provided by
 * {@code PlatformService1710.onPreInit}.
 *
 * <ul>
 *   <li>The rotation is applied in view space ahead of the camera's own, so yaw turns about the view's vertical rather
 *       than the world's: the same at a level gaze, and indistinguishable in a shake.</li>
 *   <li>No translation: the hook has no position to move. Render thread only.</li>
 * </ul>
 */
public final class HostCamera1710 implements CgHostCamera {

    private static float yaw, pitch, roll, fovScale = 1f;

    @Override
    public void offset(float x, float y, float z, float yaw, float pitch, float roll, float fovScale) {
        HostCamera1710.yaw = yaw;
        HostCamera1710.pitch = pitch;
        HostCamera1710.roll = roll;
        HostCamera1710.fovScale = fovScale;
    }

    /** {@code CameraHook}, at the head of {@code orientCamera}: the offset as GL rotations on the modelview. */
    public static void rotate() {
        if (roll != 0f) GL11.glRotatef(roll, 0f, 0f, 1f);
        if (pitch != 0f) GL11.glRotatef(pitch, 1f, 0f, 0f);
        if (yaw != 0f) GL11.glRotatef(yaw, 0f, 1f, 0f);
    }

    public static float fov(float base) {
        return base * fovScale;
    }
}
