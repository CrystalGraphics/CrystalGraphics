package com.crystalgraphics.vfx.camera;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/**
 * The Perlin pattern samples what Unreal's does for the same offsets and settings. The expected values are worked
 * by hand from {@code FMath::PerlinNoise1D}: its table's first entries 63, 9, 212 and the gradient scales k/8.
 */
public class CgPerlinShakePatternTest {

    @Test
    public void noiseMatchesUnrealsAtWorkedPoints() {
        assertEquals("0 at an integer", 0f, CgPerlinNoise.noise1D(0f), 0f);
        assertEquals(0f, CgPerlinNoise.noise1D(37f), 0f);
        // x = 0.5: grads 63 & 15 -> 1, 9 & 15 -> 2/8; 2 * lerp(0.5, -0.125, 0.5)
        assertEquals(0.375f, CgPerlinNoise.noise1D(0.5f), 1e-7f);
        // x = 1.25: grads 9 -> 2/8, 212 & 15 -> -4/8; u = smooth(0.25) = 0.103515625
        assertEquals(2f * (0.0625f + (0.375f - 0.0625f) * 0.103515625f), CgPerlinNoise.noise1D(1.25f), 1e-7f);
        assertEquals("the table wraps at 256", CgPerlinNoise.noise1D(1.25f), CgPerlinNoise.noise1D(257.25f), 1e-5f);
    }

    @Test
    public void aChannelAdvancesByDtTimesFrequencyAndScalesByAmplitude() {
        CgPerlinShakePattern shake = new CgPerlinShakePattern().channel(CgPerlinShakePattern.X, 2f, 0.5f)
                .start(new float[]{0f, 1f, 0f, 0f, 0f, 0f, 0f});
        float[] out = new float[CgPerlinShakePattern.CHANNELS];
        shake.update(1f, out);   // X's offset 0 + 1 * 0.5
        assertEquals(2f * 0.375f, out[CgPerlinShakePattern.X], 1e-6f);
        assertEquals("Y at 1 + 1", 0f, out[CgPerlinShakePattern.Y], 0f);
        assertEquals("rotation is off by Unreal's default", 0f, out[CgPerlinShakePattern.PITCH], 0f);
        assertEquals("so is FOV", 0f, out[CgPerlinShakePattern.FOV], 0f);
    }

    @Test
    public void rotationAndFovFollowTheirMultipliers() {
        CgPerlinShakePattern shake = new CgPerlinShakePattern().rotationMultipliers(3f, 0.25f)
                .channel(CgPerlinShakePattern.FOV, 4f, 0.5f).start(new float[7]);
        float[] out = new float[CgPerlinShakePattern.CHANNELS];
        shake.update(2f, out);   // rotation's offsets 2 * 1 * 0.25, FOV's 2 * 0.5 * 1
        assertEquals(3f * 0.375f, out[CgPerlinShakePattern.YAW], 1e-6f);
        assertEquals(0f, out[CgPerlinShakePattern.FOV], 0f);
    }

    @Test
    public void aSeedRepeatsAndScrubReturnsToTheStart() {
        float[] a = new float[7], b = new float[7], c = new float[7];
        CgPerlinShakePattern one = new CgPerlinShakePattern().start(42L), two = new CgPerlinShakePattern().start(42L);
        for (int i = 0; i < 10; i++) {
            one.update(0.016f, a);
            two.update(0.016f, b);
        }
        assertArrayEquals(a, b, 0f);
        one.scrub(0.5f, c);
        two.start(42L).update(0.5f, b);
        assertArrayEquals(c, b, 0f);
    }
}
