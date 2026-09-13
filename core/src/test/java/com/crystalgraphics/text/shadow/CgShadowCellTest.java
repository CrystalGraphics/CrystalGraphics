package com.crystalgraphics.text.shadow;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * The two things about a shadow cell that are silent when wrong: two requests sharing a key must want
 * identical pixels, and a cell's pixels must land where the glyph is.
 */
public class CgShadowCellTest {

    @Test
    public void boxSigmasWithOneWindowShareAKeyAndDecodeToThatWindow() {
        CgShadowCell a = CgShadowCell.outer(1, 2.6, 0f);
        CgShadowCell b = CgShadowCell.outer(1, 2.8, 0f);
        assertEquals(CgMaskBlurFilter.boxWindow(2.6), CgMaskBlurFilter.boxWindow(2.8));
        assertEquals(a, b);
        assertEquals(CgMaskBlurFilter.boxWindow(2.6), CgMaskBlurFilter.boxWindow(a.sigma()));
        assertNotEquals(a, CgShadowCell.outer(1, 3.4, 0f));
    }

    @Test
    public void smallSigmaKeysStayOnTheSmallBlurAndNeverRoundToNoBlur() {
        CgShadowCell justOver = CgShadowCell.outer(1, 0.334, 0f);
        assertTrue(justOver.sigma() >= CgMaskBlurFilter.NO_BLUR_SIGMA);
        CgShadowCell justUnder = CgShadowCell.outer(1, 1.999, 0f);
        assertTrue(justUnder.sigma() < CgMaskBlurFilter.BOX_BLUR_MIN_SIGMA);
        assertEquals(0.0, CgShadowCell.outer(1, 0.2, 0f).sigma(), 0.0);
    }

    @Test
    public void downsampleFactorBringsSigmaUnderSkiasWorkingLimit() {
        assertEquals(1, CgShadowCell.downsampleFor(4.0));
        assertEquals(2, CgShadowCell.downsampleFor(4.1));
        assertEquals(8, CgShadowCell.downsampleFor(30.0));
        assertTrue(30.0 / CgShadowCell.downsampleFor(30.0) <= CgShadowCell.MAX_WORKING_SIGMA);
    }

    @Test
    public void anOuterCellTooWideForThePageDownsamplesUntilItFits() {
        CgShadowCell fits = CgShadowCell.forOuterShadow(30.0, 0f, 64, 512);
        assertEquals("Skia's rule alone", CgShadowCell.downsampleFor(30.0), fits.downsample());
        CgShadowCell capped = CgShadowCell.forOuterShadow(30.0, 0f, 400, 64);
        assertTrue("a small page forces more", capped.downsample() > fits.downsample());
        assertEquals("inset cells never downsample", 1,
                CgShadowCell.forInsetShadow(30.0, 0f, 0f, 2f, 2f).downsample());
        assertEquals("no blur below Skia's cutoff", 0.0, CgShadowCell.forOuterShadow(0.2, 0f, 32, 512).sigma(), 0.0);
    }

    @Test
    public void theRecipeAsksTheSourceForWhatTheCellNeeds() {
        List<String> asked = new ArrayList<>();
        CgShadowCoverage dot = new CgShadowCoverage(new byte[]{(byte) 255}, 1, 1, 0f, 1f, 1);
        CgShadowCell.GlyphSource source = new CgShadowCell.GlyphSource() {
            @Override
            public CgShadowCoverage coverage() {
                asked.add("coverage");
                return dot;
            }

            @Override
            public CgShadowCoverage grown(float growPx) {
                asked.add("grown " + growPx);
                return dot;
            }
        };

        CgShadowCell.outer(1, 0.0, 0f).build(source);
        assertEquals(List.of("coverage"), asked);

        asked.clear();
        CgShadowCell.outer(1, 0.0, 2f).build(source);
        assertEquals("a spread needs the true distance", List.of("grown 2.0"), asked);

        asked.clear();
        CgShadowCell.inset(1, 0.0, 3f, 1f, 0f, 0f).build(source);
        assertEquals("the clip, then the hole", List.of("grown -1.0", "grown -3.0"), asked);
    }

    @Test
    public void downsampleConservesCoverageOnADevicePixelGrid() {
        int w = 7, h = 5;
        byte[] data = new byte[w * h];
        long mass = 0;
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) ((i * 37) & 0xFF);
            mass += (i * 37) & 0xFF;
        }
        CgShadowCoverage source = new CgShadowCoverage(data, w, h, -3f, 11f, 1);
        CgShadowCoverage down = source.downsample(4);
        assertEquals("aligned to multiples of the factor", 0f, down.left() % 4, 0f);
        assertEquals(0f, down.top() % 4, 0f);
        assertTrue("covers the source", down.left() <= source.left()
                && down.left() + down.width() * 4 >= source.left() + w);
        long sum = 0;
        for (byte b : down.data()) sum += b & 0xFF;
        assertEquals("average of whole blocks", mass, sum * 16, down.data().length * 8L);
    }

    @Test
    public void blurGrowsTheCellAroundItsOwnPosition() {
        byte[] data = new byte[9];
        data[4] = (byte) 255;
        CgShadowCoverage dot = new CgShadowCoverage(data, 3, 3, 10f, 20f, 2);
        CgShadowCoverage blurred = dot.blur(1.5);
        int border = new CgMaskBlurFilter(1.5).border();
        assertEquals(3 + 2 * border, blurred.width());
        assertEquals("left moves out by the border in DEVICE px", 10f - border * 2f, blurred.left(), 0f);
        assertEquals(20f + border * 2f, blurred.top(), 0f);
        int centre = (1 + border) * blurred.width() + (1 + border);
        int peak = 0;
        for (byte b : blurred.data()) peak = Math.max(peak, b & 0xFF);
        assertEquals("the brightest texel is still over the dot", peak, blurred.data()[centre] & 0xFF);
    }

    @Test
    public void insetShadowsOnlyInsideTheClipAndOnlyWhereTheHoleHasMoved() {
        int size = 10;
        byte[] solid = new byte[size * size];
        Arrays.fill(solid, (byte) 255);
        CgShadowCoverage glyph = new CgShadowCoverage(solid, size, size, 0f, 10f, 1);

        // Offset 3px right: the hole leaves the first three columns, which are shadowed; the rest is not.
        CgShadowCoverage cell = glyph.inset(glyph, 3f, 0f);
        assertEquals(size, cell.width());
        for (int x = 0; x < size; x++) {
            int value = cell.data()[5 * size + x] & 0xFF;
            if (x < 3) assertEquals("shadowed column " + x, 255, value);
            else assertEquals("covered column " + x, 0, value);
        }

        // No hole at all shadows the whole clip.
        CgShadowCoverage full = glyph.inset(null, 0f, 0f);
        for (byte b : full.data()) assertEquals(255, b & 0xFF);
    }
}
