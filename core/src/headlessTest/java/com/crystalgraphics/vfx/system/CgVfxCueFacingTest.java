package com.crystalgraphics.vfx.system;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.Test;

import com.crystalgraphics.cue.CgCueParameters;

/** A cue's play faces its normal as Unreal's {@code Normal.Rotation()} does: x along it, no roll. */
public class CgVfxCueFacingTest {

    @Test
    public void xTurnsAlongTheNormalAndItsSideStaysLevel() {
        Quaternionf q = new Quaternionf();
        float[][] normals = {{0, 0, 1}, {-1, 0, 0}, {0.6f, 0, -0.8f}, {1, 1, 0}, {0, 1, 0}};
        for (float[] n : normals) {
            assertTrue(CgVfxCueEffects.facing(new CgCueParameters().normal(n[0], n[1], n[2]), q));
            Vector3f x = q.transform(new Vector3f(1, 0, 0)), want = new Vector3f(n[0], n[1], n[2]).normalize();
            assertEquals(want.x, x.x, 1e-5f);
            assertEquals(want.y, x.y, 1e-5f);
            assertEquals(want.z, x.z, 1e-5f);
            // No roll: its side (Unreal's y, our z) stays level
            assertEquals(0f, q.transform(new Vector3f(0, 0, 1)).y, 1e-5f);
        }
    }

    @Test
    public void aZeroNormalTurnsNothing() {
        Quaternionf q = new Quaternionf().rotationY(1f);
        assertFalse(CgVfxCueEffects.facing(new CgCueParameters(), q));
        assertEquals(1f, q.w, 0f);
    }
}
