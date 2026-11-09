package com.crystalgraphics.vfx.particle.spawn;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class CgVfxRandomStreamTest {

    /**
     * A seeded sphere's first five directions at seed 14, Unreal's axes: three draws each (2r - 1 an axis, both signs
     * allowed), then its radius's one, normalized (LocationPrimitiveSphere::SpawnEx over FRandomStream, 4.21).
     */
    @Test
    public void seedFourteenGivesCascadesSphereDirections() {
        float[][] expected = {{0.60f, 0.69f, 0.39f}, {-0.75f, -0.56f, -0.36f}, {0.52f, -0.84f, -0.14f},
                {0.55f, -0.80f, -0.23f}, {0.51f, 0.64f, 0.58f}};
        int state = 14;
        for (float[] want : expected) {
            float[] d = new float[3];
            for (int a = 0; a < 3; a++) d[a] = CgVfxRandomStream.fraction(state = CgVfxRandomStream.next(state)) * 2f - 1f;
            state = CgVfxRandomStream.next(state);   // its radius
            float n = (float) Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
            for (int a = 0; a < 3; a++) assertEquals(want[a], d[a] / n, 0.006f);
        }
    }

    @Test
    public void skipReachesWhatSteppingDoes() {
        for (int seed : new int[]{0, 14, 38, -7, 22941}) {
            int state = seed;
            for (int n = 0; n <= 300; n++) {
                assertEquals("seed " + seed + " after " + n, state, CgVfxRandomStream.skip(seed, n));
                state = CgVfxRandomStream.next(state);
            }
        }
    }
}
