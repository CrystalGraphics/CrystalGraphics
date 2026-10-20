package com.crystalgraphics.vfx.camera;

import java.util.SplittableRandom;

/**
 * A camera shake pattern of a sine wave on each channel, Unreal's {@code UWaveOscillatorCameraShakePattern}
 * (Engine/Plugins/Cameras/EngineCameras/Source/EngineCameras/Private/Shakes/WaveOscillatorCameraShakePattern.cpp): each
 * channel's phase advances by {@code dt * frequency * multiplier * 2pi} and samples {@code amplitude * sin(phase)}.
 *
 * <pre>{@code
 * CgWaveShakePattern shake = new CgWaveShakePattern().channel(CgPerlinShakePattern.Z, 0.1f, 6f, false);
 * shake.start(seed);
 * shake.update(dt, out);   // x y z, pitch yaw roll, fov; times CgCameraShakeState's weight
 * }</pre>
 *
 * <ul>
 *   <li>Unreal's defaults: amplitude 1, frequency 1 and a random start phase on every channel, rotation's amplitude
 *       multiplier 0 and FOV's amplitude 0.</li>
 *   <li>A random phase is drawn from the seed in [0, 2pi), as Unreal's {@code FRand() * 2pi}; a zero one starts at 0.</li>
 * </ul>
 */
public final class CgWaveShakePattern {

    private static final float TWO_PI = (float) (2.0 * Math.PI);
    private final float[] amplitude = {1f, 1f, 1f, 1f, 1f, 1f, 0f};
    private final float[] frequency = {1f, 1f, 1f, 1f, 1f, 1f, 1f};
    private final boolean[] randomPhase = {true, true, true, true, true, true, true};
    private final float[] initial = new float[CgPerlinShakePattern.CHANNELS];
    private final float[] current = new float[CgPerlinShakePattern.CHANNELS];
    private float locationAmplitude = 1f, locationFrequency = 1f, rotationAmplitude = 0f, rotationFrequency = 1f;

    /** One channel ({@link CgPerlinShakePattern#X} ...): amplitude, frequency in cycles a second, random start phase. */
    public CgWaveShakePattern channel(int channel, float amplitude, float frequency, boolean randomPhase) {
        this.amplitude[channel] = amplitude;
        this.frequency[channel] = frequency;
        this.randomPhase[channel] = randomPhase;
        return this;
    }

    public CgWaveShakePattern locationMultipliers(float amplitude, float frequency) {
        locationAmplitude = amplitude;
        locationFrequency = frequency;
        return this;
    }

    public CgWaveShakePattern rotationMultipliers(float amplitude, float frequency) {
        rotationAmplitude = amplitude;
        rotationFrequency = frequency;
        return this;
    }

    /** Starts with each random channel's phase drawn from {@code seed}. */
    public CgWaveShakePattern start(long seed) {
        SplittableRandom random = new SplittableRandom(seed);
        for (int c = 0; c < initial.length; c++) initial[c] = randomPhase[c] ? (float) random.nextDouble() * TWO_PI : 0f;
        System.arraycopy(initial, 0, current, 0, initial.length);
        return this;
    }

    /** Advances {@code dt} seconds and writes every channel's sample into {@code out}. */
    public void update(float dt, float[] out) {
        for (int c = 0; c < initial.length; c++) {
            boolean location = c <= CgPerlinShakePattern.Z, fov = c == CgPerlinShakePattern.FOV;
            float amp = amplitude[c] * (fov ? 1f : location ? locationAmplitude : rotationAmplitude);
            if (amp == 0f) {
                out[c] = 0f;
                continue;
            }
            current[c] += dt * frequency[c] * (fov ? 1f : location ? locationFrequency : rotationFrequency) * TWO_PI;
            out[c] = amp * (float) Math.sin(current[c]);
        }
    }

    /** Back to the start phases, then straight to {@code time}. */
    public void scrub(float time, float[] out) {
        System.arraycopy(initial, 0, current, 0, initial.length);
        update(time, out);
    }
}
