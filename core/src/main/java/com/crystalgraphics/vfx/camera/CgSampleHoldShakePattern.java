package com.crystalgraphics.vfx.camera;

/**
 * Kakarot's preset shake ({@code AATPlayerCameraManager::ApplyShake}), ours: every {@code interval} seconds each
 * channel takes a new offset, uniform in ±its amplitude, and holds it until the next.
 *
 * <pre>{@code
 * CgSampleHoldShakePattern hold = new CgSampleHoldShakePattern(1 / 30f)
 *         .amplitude(CgShakePattern.X, 0.01f).amplitude(CgShakePattern.PITCH, 0.1f).start(seed);
 * hold.update(dt, out);
 * }</pre>
 *
 * <ul>
 *   <li>It samples on its first frame, then each time a whole interval has passed.</li>
 *   <li>A seed repeats its samples.</li>
 * </ul>
 */
public final class CgSampleHoldShakePattern implements CgShakePattern {

    private final float interval;
    private final float[] amplitude = new float[CHANNELS], held = new float[CHANNELS];
    private long random;
    private float since;
    private boolean sampled;

    public CgSampleHoldShakePattern(float interval) {
        if (!(interval > 0f)) throw new IllegalArgumentException("a sample-hold interval of " + interval + ": above 0");
        this.interval = interval;
    }

    /** Channel {@code channel}'s amplitude: blocks for a location, degrees otherwise. */
    public CgSampleHoldShakePattern amplitude(int channel, float amplitude) {
        this.amplitude[channel] = amplitude;
        return this;
    }

    public CgSampleHoldShakePattern start(long seed) {
        random = seed;
        since = 0f;
        sampled = false;
        return this;
    }

    @Override
    public void update(float dt, float[] out) {
        since += dt;
        if (!sampled || since >= interval) {
            since = sampled ? since % interval : 0f;
            sampled = true;
            for (int c = 0; c < CHANNELS; c++) held[c] = amplitude[c] == 0f ? 0f : (float) ((next() * 2 - 1) * amplitude[c]);
        }
        System.arraycopy(held, 0, out, 0, CHANNELS);
    }

    /** SplitMix64's next double in [0, 1), without a generator object. */
    private double next() {
        long z = random += 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return ((z ^ (z >>> 31)) >>> 11) * 0x1.0p-53;
    }
}
