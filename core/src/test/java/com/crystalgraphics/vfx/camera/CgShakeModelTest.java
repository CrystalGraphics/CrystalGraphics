package com.crystalgraphics.vfx.camera;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CgShakeModelTest {

    private double clock;
    private final CgShakeModel model = new CgShakeModel(() -> clock);

    private float step(float dt) {
        clock += dt;
        return model.step(dt, 0.0, 0.0, 0.0);
    }

    @Test
    public void anImpactIsFeltByDistanceBetweenItsRadii() {
        model.add(5.0, 0.0, 0.0, 1f, 10f, 30f, 1f);
        assertEquals("inside the inner radius: full", 1f, step(0f), 1e-6f);
        model.step(10f, 0.0, 0.0, 0.0);
        model.add(20.0, 0.0, 0.0, 1f, 10f, 30f, 1f);
        assertEquals("halfway between the radii: half the trauma, a quarter the shake", 0.25f, step(0f), 1e-6f);
        model.step(10f, 0.0, 0.0, 0.0);
        model.add(40.0, 0.0, 0.0, 1f, 10f, 30f, 1f);
        assertEquals("past the outer radius: nothing", 0f, step(0f), 0f);
    }

    @Test
    public void hitsStackToAtMostOneAndSettleAtTheDecayRate() {
        model.add(0.0, 0.0, 0.0, 0.6f, 5f, 20f, 1f);
        model.add(0.0, 0.0, 0.0, 0.6f, 5f, 20f, 1f);
        step(0f);
        assertEquals(1f, model.trauma(), 0f);
        step(0.5f);
        assertEquals(1f - CgShakeModel.DECAY * 0.5f, model.trauma(), 1e-5f);
        step(2f);
        assertEquals(0f, model.trauma(), 0f);
        assertTrue(model.still());
    }

    @Test
    public void aRumbleAddsWhileHeldAndLapsesWhenLeftAlone() {
        CgCameraShake.Held rumble = new CgCameraShake.Held(
                CgCameraShake.builder().trauma(1f).radii(4f, 24f).build(), model).at(0.0, 0.0, 0.0);
        rumble.level(0.5f);
        assertEquals(0.25f, step(0.05f), 1e-6f);
        clock += CgShakeModel.RUMBLE_EXPIRES + 0.01;
        assertEquals("unset for longer than it lasts", 0f, step(0f), 0f);
        assertTrue(model.still());

        rumble.level(0.5f);
        rumble.close();
        assertEquals(0f, step(0.01f), 0f);
    }

    @Test
    public void noiseIsSmoothAndBounded() {
        float last = CgShakeModel.noise(0f, 1), max = 0f;
        for (int i = 1; i < 10000; i++) {
            float n = CgShakeModel.noise(i * 0.001f, 1);
            assertTrue("smooth", Math.abs(n - last) < 0.01f);
            max = Math.max(max, Math.abs(n));
            last = n;
        }
        assertTrue("bounded", max <= 1f);
        assertTrue("moves", max > 0.2f);
    }
}
