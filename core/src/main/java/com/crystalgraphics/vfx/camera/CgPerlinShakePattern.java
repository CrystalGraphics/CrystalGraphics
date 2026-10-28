package com.crystalgraphics.vfx.camera;

import java.util.SplittableRandom;

/**
 * A camera shake pattern of Perlin noise on each channel, Unreal's {@code UPerlinNoiseCameraShakePattern}
 * (Engine/Plugins/Cameras/EngineCameras/Source/EngineCameras/Private/Shakes/PerlinNoiseCameraShakePattern.cpp): each
 * channel's offset advances by {@code dt * frequency * multiplier} and samples {@link CgPerlinNoise#noise1D} times its
 * amplitude. Beside {@link CgCameraShake}'s trauma model, for a shake authored as Unreal authors one.
 *
 * <pre>{@code
 * CgPerlinShakePattern shake = new CgPerlinShakePattern()
 *         .channel(CgPerlinShakePattern.Y, 0.2f, 12f)       // blocks, cycles a second
 *         .channel(CgPerlinShakePattern.PITCH, 1.5f, 8f)    // degrees
 *         .rotationMultipliers(1f, 1f);                     // rotation is off unless given, as in Unreal
 * shake.start(seed);
 * float[] out = new float[CgPerlinShakePattern.CHANNELS];
 * shake.update(dt, out);                                    // x y z, pitch yaw roll, fov
 * }</pre>
 *
 * <ul>
 *   <li>Unreal's defaults: every channel amplitude 1 and frequency 1, the rotation amplitude multiplier 0 and FOV's
 *       amplitude 0, so only location shakes until told otherwise.</li>
 *   <li>Unreal draws the start offsets with {@code FMath::RandHelper(255)}; here a seed draws them, or
 *       {@link #start(float[])} sets them: the same offsets give Unreal's samples.</li>
 *   <li>The blend in and out (Unreal's {@code State}) is the caller's weight on the result.</li>
 * </ul>
 */
public final class CgPerlinShakePattern implements CgShakePattern {

    public static final int X = 0, Y = 1, Z = 2, PITCH = 3, YAW = 4, ROLL = 5, FOV = 6, CHANNELS = 7;

    private final float[] amplitude = {1f, 1f, 1f, 1f, 1f, 1f, 0f};
    private final float[] frequency = {1f, 1f, 1f, 1f, 1f, 1f, 1f};
    private final float[] initial = new float[CHANNELS];
    private final float[] current = new float[CHANNELS];
    private float locationAmplitude = 1f, locationFrequency = 1f, rotationAmplitude = 0f, rotationFrequency = 1f;

    /** Sets one channel's amplitude (blocks, degrees) and frequency (cycles a second). */
    public CgPerlinShakePattern channel(int channel, float amplitude, float frequency) {
        this.amplitude[channel] = amplitude;
        this.frequency[channel] = frequency;
        return this;
    }

    /** Unreal's {@code LocationAmplitudeMultiplier} and {@code LocationFrequencyMultiplier}: 1 and 1. */
    public CgPerlinShakePattern locationMultipliers(float amplitude, float frequency) {
        locationAmplitude = amplitude;
        locationFrequency = frequency;
        return this;
    }

    /** Unreal's {@code RotationAmplitudeMultiplier} and {@code RotationFrequencyMultiplier}: 0 and 1. */
    public CgPerlinShakePattern rotationMultipliers(float amplitude, float frequency) {
        rotationAmplitude = amplitude;
        rotationFrequency = frequency;
        return this;
    }

    /** Starts with offsets drawn from {@code seed}, each a whole number below 255 as Unreal's. */
    public CgPerlinShakePattern start(long seed) {
        SplittableRandom random = new SplittableRandom(seed);
        for (int c = 0; c < CHANNELS; c++) initial[c] = random.nextInt(255);
        return restart();
    }

    /** Starts at these offsets, one a channel. */
    public CgPerlinShakePattern start(float[] offsets) {
        System.arraycopy(offsets, 0, initial, 0, CHANNELS);
        return restart();
    }

    private CgPerlinShakePattern restart() {
        System.arraycopy(initial, 0, current, 0, CHANNELS);
        return this;
    }

    /** Advances {@code dt} seconds and writes every channel's sample into {@code out}. */
    @Override
    public void update(float dt, float[] out) {
        for (int c = 0; c < CHANNELS; c++) {
            boolean location = c <= Z, fov = c == FOV;
            float amp = amplitude[c] * (fov ? 1f : location ? locationAmplitude : rotationAmplitude);
            if (amp == 0f) {
                out[c] = 0f;
                continue;
            }
            current[c] += dt * frequency[c] * (fov ? 1f : location ? locationFrequency : rotationFrequency);
            out[c] = amp * CgPerlinNoise.noise1D(current[c]);
        }
    }

    /** Back to the start offsets, then straight to {@code time}: Unreal's scrub. */
    public void scrub(float time, float[] out) {
        restart();
        update(time, out);
    }
}
