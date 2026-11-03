package com.crystalgraphics.render.post.bloom;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** The Gaussian bloom's taps against Unreal 5.1's {@code Compute1DGaussianFilterKernel}, worked by hand. */
public class CgGaussianBloomTest {

    @Test
    public void aRadiusOfOnePairsTheCentreWithItsNeighbour() {
        // i = -1: exp(-16.7) and exp(0) paired just short of 0; i = 1, the last: on its texel alone
        double[] out = new double[CgGaussianBloom.MAX_TAPS * 2];
        assertEquals(2, CgGaussianBloom.kernel(1, out));
        double e = Math.exp(-16.7), sum = 1 + 2 * e;
        assertEquals(-1 + 1 / (1 + e), out[0], 1e-12);
        assertEquals((1 + e) / sum, out[1], 1e-12);
        assertEquals(1, out[2], 0);
        assertEquals(e / sum, out[3], 1e-12);
    }

    @Test
    public void aWideKernelIsClampedTo31TexelsAndSumsToOne() {
        double[] out = new double[CgGaussianBloom.MAX_TAPS * 2];
        int n = CgGaussianBloom.kernel(500, out);
        assertEquals("from -31 to 31 by twos", 32, n);
        double sum = 0;
        for (int i = 0; i < n; i++) sum += out[i * 2 + 1];
        assertEquals(1, sum, 1e-12);
        assertEquals(31, out[(n - 1) * 2], 0);
        // GetBlurRadius: 960 wide at 0.3% times 4 is 5.76 texels
        assertEquals(5.76, CgGaussianBloom.radius(960, 1.2f), 1e-5);
    }
}
