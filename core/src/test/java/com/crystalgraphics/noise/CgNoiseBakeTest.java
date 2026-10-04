package com.crystalgraphics.noise;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class CgNoiseBakeTest {

    private static final float[] SEED = {0f, 0f, 0f};

    @Test
    public void everyBakeTilesAtItsPeriod() {
        int period = 16;
        float[] cell = new float[3], shifted = new float[3];
        for (float p : new float[]{0.3f, 7.75f, 15.9f}) {
            assertEquals(CgNoiseBake.gradientAt(p, 2.5f, 9.1f, period, SEED),
                    CgNoiseBake.gradientAt(p + period, 2.5f + period, 9.1f - period, period, SEED), 1e-4f);
            assertEquals(CgNoiseBake.valueAt(p, 2.5f, 9.1f, period, SEED),
                    CgNoiseBake.valueAt(p + period, 2.5f, 9.1f + period, period, SEED), 1e-4f);
            CgNoiseBake.voronoiAt(p, 2.5f, 9.1f, period, cell);
            CgNoiseBake.voronoiAt(p + period, 2.5f, 9.1f, period, shifted);
            assertEquals(cell[0], shifted[0], 1e-4f);
            assertEquals(cell[2], shifted[2], 1e-6f);
        }
    }

    @Test
    public void nearestDirectionIsTheDistancesGradient() {
        float[] cell = new float[6], plus = new float[3], minus = new float[3];
        float e = 1e-3f;
        for (float[] p : new float[][]{{0.31f, 2.47f, 9.13f}, {5.6f, 11.2f, 3.3f}}) {
            CgNoiseBake.voronoiAt(p[0], p[1], p[2], 16, cell);
            for (int axis = 0; axis < 3; axis++) {
                float[] a = p.clone(), b = p.clone();
                a[axis] += e;
                b[axis] -= e;
                CgNoiseBake.voronoiAt(a[0], a[1], a[2], 16, plus);
                CgNoiseBake.voronoiAt(b[0], b[1], b[2], 16, minus);
                assertEquals("axis " + axis, (plus[0] - minus[0]) / (2 * e), cell[3 + axis], 1e-2f);
            }
        }
    }

    @Test
    public void halvesMatchJava() {
        for (float v : new float[]{0f, -0f, 1f, -1.6f, 0.333333f, 65504f, 70000f, 6.1e-5f, 3e-6f, 1e-9f, Float.NaN}) {
            int mask = Float.isNaN(v) ? 0x7E00 : 0xFFFF;
            assertEquals("half of " + v, Float.floatToFloat16(v) & mask, CgNoiseBake.half(v) & mask);
        }
    }
}
