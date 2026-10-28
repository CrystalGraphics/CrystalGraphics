package com.crystalgraphics.vfx.camera;

/**
 * A shake pattern playing: Unreal's {@code UCameraShakePattern}. Each frame it writes its seven channels, a location
 * offset, a rotation offset and a FOV change, before its play's weight and play space apply.
 *
 * <pre>{@code
 * float[] out = new float[CgShakePattern.CHANNELS];
 * pattern.update(dt, out);   // out[X..Z] lengths along Unreal's forward, right, up; out[PITCH..ROLL] and out[FOV] degrees
 * }</pre>
 *
 * <ul>
 *   <li>Lengths are in blocks; angles and the FOV change in degrees. The axes are the shake's own: forward, right, up.</li>
 *   <li>Allocates nothing in {@link #update}.</li>
 * </ul>
 */
public interface CgShakePattern {

    /** The channels, as Unreal's shake result: location, rotation (pitch, yaw, roll), FOV. */
    int X = 0, Y = 1, Z = 2, PITCH = 3, YAW = 4, ROLL = 5, FOV = 6, CHANNELS = 7;

    /** Advances {@code dt} seconds and writes this frame's offsets into {@code out}. */
    void update(float dt, float[] out);
}
