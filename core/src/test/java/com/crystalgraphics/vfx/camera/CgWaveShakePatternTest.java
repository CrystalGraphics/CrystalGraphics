package com.crystalgraphics.vfx.camera;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** CA3: the wave oscillator and the blend weights against Unreal's formulas, values worked by hand. */
public class CgWaveShakePatternTest {

    @Test
    public void aWaveSamplesAmplitudeTimesSineOfItsPhase() {
        CgWaveShakePattern shake = new CgWaveShakePattern().channel(CgPerlinShakePattern.Y, 0.5f, 2f, false)
                .channel(CgPerlinShakePattern.X, 0f, 1f, false).channel(CgPerlinShakePattern.Z, 0f, 1f, false).start(1L);
        float[] out = new float[CgPerlinShakePattern.CHANNELS];
        shake.update(0.125f, out);   // phase 0.125 * 2 * 2pi = pi/2
        assertEquals(0.5f, out[CgPerlinShakePattern.Y], 1e-6f);
        shake.update(0.125f, out);   // pi
        assertEquals(0f, out[CgPerlinShakePattern.Y], 1e-6f);
        assertEquals("rotation off by Unreal's default", 0f, out[CgPerlinShakePattern.PITCH], 0f);
        shake.scrub(0.375f, out);    // 3pi/2 from the start
        assertEquals(-0.5f, out[CgPerlinShakePattern.Y], 1e-6f);
    }

    @Test
    public void blendInRampsAndBlendOutEndsBeforeTheDuration() {
        CgCameraShakeState state = new CgCameraShakeState(0.5f, 1f, 3f).start();
        assertEquals(0.5f, state.update(0.25f), 1e-6f);     // 0.25 / 0.5 in
        assertEquals(1f, state.update(0.5f), 1e-6f);        // 0.75: in done
        assertEquals(1f, state.update(1f), 1e-6f);          // 1.75: 1.25 left
        assertEquals(0.75f, state.update(0.5f), 1e-6f);     // 2.25: 0.75 left, out 0.25 of 1
        assertEquals(0f, state.update(1f), 0f);             // past the duration
        assertFalse(state.playing());
    }

    @Test
    public void anEndlessShakeBlendsOutWhenStopped() {
        CgCameraShakeState state = new CgCameraShakeState(0f, 0.4f, 0f).start();
        assertEquals(1f, state.update(10f), 0f);
        state.stop(false);
        assertEquals(0.5f, state.update(0.2f), 1e-6f);
        assertTrue(state.playing());
        assertEquals(0f, state.update(0.3f), 0f);
        assertFalse(state.playing());
        assertEquals("a scrub starts again", 1f, state.scrub(5f), 0f);
    }
}
