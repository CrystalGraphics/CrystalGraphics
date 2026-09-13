package com.crystalgraphics.text.shadow;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pins the Skia port where a wrong number would still produce a plausible blur.
 *
 * <p>The window and border values are {@code PlanGauss}'s formulas evaluated by hand; a shadow cell's
 * padding and its cache key are both read off them, so a drift here misplaces every blurred glyph.</p>
 */
public class CgMaskBlurFilterTest {

    @Test
    public void planGaussWindowAndBorderMatchSkia() {
        assertEquals(4, CgMaskBlurFilter.boxWindow(2.0));
        assertEquals(5, CgMaskBlurFilter.boxBorder(4));
        assertEquals(5, CgMaskBlurFilter.boxWindow(2.5));
        assertEquals(6, CgMaskBlurFilter.boxBorder(5));
        assertEquals(8, CgMaskBlurFilter.boxWindow(4.0));
        assertEquals(11, CgMaskBlurFilter.boxBorder(8));
        assertEquals(56, CgMaskBlurFilter.boxWindow(30.0));
        assertEquals(83, CgMaskBlurFilter.boxBorder(56));
        assertEquals(254, CgMaskBlurFilter.boxWindow(135.0));
        assertEquals(380, CgMaskBlurFilter.boxBorder(254));
    }

    @Test
    public void noBlurBelowOneThird() {
        assertTrue(new CgMaskBlurFilter(0.3).hasNoBlur());
        assertFalse(new CgMaskBlurFilter(0.34).hasNoBlur());
        assertEquals(0, new CgMaskBlurFilter(0.1).border());
    }

    @Test
    public void sigmaIsClampedToSkiasOverflowBound() {
        assertEquals(135.0, new CgMaskBlurFilter(400.0).sigma(), 0.0);
    }

    @Test
    public void gaussFactorsAreNormalisedAndBounded() {
        for (double sigma : new double[]{0.34, 0.5, 1.0, 1.5, 1.99}) {
            CgGaussFilter filter = new CgGaussFilter(sigma);
            assertTrue("radius for " + sigma, filter.radius() >= 1 && filter.radius() <= 4);
            double sum = filter.factor(0);
            for (int i = 1; i <= filter.radius(); i++) sum += 2 * filter.factor(i);
            assertEquals("sum for " + sigma, 1.0, sum, 1e-12);
            for (int i = 1; i <= filter.radius(); i++) {
                assertTrue("decreasing for " + sigma, filter.factor(i) <= filter.factor(i - 1));
            }
        }
    }

    @Test
    public void blurConservesCoverageAndGrowsByTheBorder() {
        int w = 9, h = 7;
        byte[] src = new byte[w * h];
        for (int y = 2; y < 5; y++) for (int x = 3; x < 6; x++) src[y * w + x] = (byte) 255;
        long mass = 9 * 255L;

        for (double sigma : new double[]{0.6, 1.4, 2.0, 3.3, 6.0}) {
            CgMaskBlurFilter filter = new CgMaskBlurFilter(sigma);
            int border = filter.border();
            byte[] out = filter.blur(src, w, h);
            assertEquals(((long) w + 2 * border) * (h + 2 * border), out.length);
            long sum = 0;
            for (byte b : out) sum += b & 0xFF;
            // Rounding per pixel, never systematic: within half a level per output pixel.
            assertEquals("mass for sigma " + sigma, mass, sum, out.length * 0.5 + 2);
        }
    }

    @Test
    public void blurOfASymmetricMaskIsSymmetric() {
        int w = 11, h = 11;
        byte[] src = new byte[w * h];
        for (int y = 3; y < 8; y++) for (int x = 3; x < 8; x++) src[y * w + x] = (byte) 200;
        for (double sigma : new double[]{1.0, 2.5, 5.0}) {
            CgMaskBlurFilter filter = new CgMaskBlurFilter(sigma);
            int dw = w + 2 * filter.border();
            byte[] out = filter.blur(src, w, h);
            for (int y = 0; y < dw; y++) {
                for (int x = 0; x < dw; x++) {
                    assertEquals("mirror at " + x + "," + y + " sigma " + sigma,
                            out[y * dw + x] & 0xFF, out[y * dw + (dw - 1 - x)] & 0xFF, 1);
                    assertEquals("transpose at " + x + "," + y + " sigma " + sigma,
                            out[y * dw + x] & 0xFF, out[x * dw + y] & 0xFF, 1);
                }
            }
        }
    }

    @Test
    public void aLargeSolidRegionStaysSolidInItsInterior() {
        int w = 60, h = 60;
        byte[] src = new byte[w * h];
        Arrays.fill(src, (byte) 255);
        CgMaskBlurFilter filter = new CgMaskBlurFilter(3.0);
        int border = filter.border();
        int dw = w + 2 * border;
        byte[] out = filter.blur(src, w, h);
        int centre = (border + h / 2) * dw + border + w / 2;
        assertEquals(255, out[centre] & 0xFF, 1);
    }
}
