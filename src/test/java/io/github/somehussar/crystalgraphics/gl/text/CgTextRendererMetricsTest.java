package io.github.somehussar.crystalgraphics.gl.text;

import io.github.somehussar.crystalgraphics.api.font.CgAtlasRegion;
import io.github.somehussar.crystalgraphics.api.font.CgFontKey;
import io.github.somehussar.crystalgraphics.api.font.CgFontStyle;
import io.github.somehussar.crystalgraphics.api.font.CgGlyphKey;
import org.lwjgl.BufferUtils;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Tests for the three-space normalization contract in {@link CgTextRenderer}.
 *
 * <p>Verifies that:</p>
 * <ul>
 *   <li>{@link CgTextRenderer#logicalMetricScale} correctly converts between
 *       physical raster space and logical layout space</li>
 *   <li>Normalized physical bearings/extents match logical-space values regardless
 *       of UI scale (the core invariant)</li>
 *   <li>Raw (unnormalized) physical metrics would corrupt placement at non-1x scales
 *       (proving the bug this fix addresses)</li>
 * </ul>
 */
public class CgTextRendererMetricsTest {

    @Test
    public void logicalMetricScaleIsIdentityAtBaseSize() {
        assertEquals(1.0f, CgTextRenderer.logicalMetricScale(32, 32), 0.0001f);
    }

    @Test
    public void logicalMetricScaleShrinksPhysicalMetricsBackToLogicalSpace() {
        assertEquals(0.5f, CgTextRenderer.logicalMetricScale(32, 64), 0.0001f);
        assertEquals(32.0f, 64.0f * CgTextRenderer.logicalMetricScale(32, 64), 0.0001f);
        assertEquals(6.0f, 12.0f * CgTextRenderer.logicalMetricScale(32, 64), 0.0001f);
    }

    @Test
    public void logicalMetricScaleExpandsWhenEffectiveSizeIsSmaller() {
        assertEquals(2.0f, CgTextRenderer.logicalMetricScale(32, 16), 0.0001f);
    }

    @Test
    public void logicalMetricScaleSupportsFractionalNormalization() {
        float scale = CgTextRenderer.logicalMetricScale(24, 36);
        assertEquals(24.0f / 36.0f, scale, 0.0001f);
        assertEquals(8.0f, 12.0f * scale, 0.0001f);
    }

    @Test(expected = IllegalArgumentException.class)
    public void logicalMetricScaleRejectsZeroBaseTargetPx() {
        CgTextRenderer.logicalMetricScale(0, 32);
    }

    @Test(expected = IllegalArgumentException.class)
    public void logicalMetricScaleRejectsZeroEffectiveTargetPx() {
        CgTextRenderer.logicalMetricScale(32, 0);
    }

    // ── Three-space invariant: normalized placement is scale-independent ──

    /**
     * Proves that physical bearings at 2x effective size, when normalized,
     * produce the same logical placement as bearings at 1x.
     *
     * <p>This is the GREEN test: it passes because the normalization formula
     * {@code scaleFactor = baseTargetPx / effectiveTargetPx} is applied.</p>
     */
    @Test
    public void normalizedBearingsAreInvariantAcrossUIScales() {
        int baseTargetPx = 24;
        // At 1x: physical bearings match logical bearings
        float physBearingX_1x = 3.0f;
        float physBearingY_1x = 20.0f;
        float physWidth_1x = 14.0f;
        float physHeight_1x = 22.0f;

        float scale1x = CgTextRenderer.logicalMetricScale(baseTargetPx, 24);
        float logBx1 = physBearingX_1x * scale1x;
        float logBy1 = physBearingY_1x * scale1x;
        float logW1 = physWidth_1x * scale1x;
        float logH1 = physHeight_1x * scale1x;

        // At 2x: physical bearings are doubled (FreeType scales with pixel size)
        float physBearingX_2x = 6.0f;
        float physBearingY_2x = 40.0f;
        float physWidth_2x = 28.0f;
        float physHeight_2x = 44.0f;

        float scale2x = CgTextRenderer.logicalMetricScale(baseTargetPx, 48);
        float logBx2 = physBearingX_2x * scale2x;
        float logBy2 = physBearingY_2x * scale2x;
        float logW2 = physWidth_2x * scale2x;
        float logH2 = physHeight_2x * scale2x;

        assertEquals("bearingX must be invariant across UI scales", logBx1, logBx2, 0.0001f);
        assertEquals("bearingY must be invariant across UI scales", logBy1, logBy2, 0.0001f);
        assertEquals("width must be invariant across UI scales", logW1, logW2, 0.0001f);
        assertEquals("height must be invariant across UI scales", logH1, logH2, 0.0001f);
    }

    /**
     * Proves that WITHOUT normalization, physical bearings at 2x would
     * produce incorrect (doubled) logical placement — demonstrating the
     * bug that the normalization formula fixes.
     *
     * <p>This is the RED test for the old (broken) code path: raw physical
     * bearings combined with logical pen positions would distort placement
     * proportional to UI scale.</p>
     */
    @Test
    public void rawPhysicalBearingsWouldCorruptPlacementAtNon1xScale() {
        int baseTargetPx = 24;
        // Logical pen position (from HarfBuzz advances — always in logical space)
        float penX = 100.0f;

        // At 1x: physical bearingX = logical bearingX = 3.0
        float physBearingX_1x = 3.0f;
        float correctQuadX = penX + physBearingX_1x;  // 103.0 — correct

        // At 2x: physical bearingX = 6.0 (FreeType at 48px)
        float physBearingX_2x = 6.0f;
        float brokenQuadX = penX + physBearingX_2x;    // 106.0 — WRONG (3px drift)
        float fixedQuadX = penX + physBearingX_2x * CgTextRenderer.logicalMetricScale(baseTargetPx, 48);  // 103.0

        // The broken path would place the glyph 3 logical pixels too far right
        assertEquals("Without normalization: quad X drifts by (bearingX * (scale-1))",
                106.0f, brokenQuadX, 0.0001f);

        // The fixed path normalizes physical bearings back to logical space
        assertEquals("With normalization: quad X matches 1x placement",
                correctQuadX, fixedQuadX, 0.0001f);
    }

    /**
     * Proves the invariant holds for non-integer scale factors (e.g., 1.5x).
     */
    @Test
    public void normalizedPlacementInvariantAtFractionalScale() {
        int baseTargetPx = 32;
        // At 1x: bearingX=4, bearingY=28
        float bx1 = 4.0f;
        float by1 = 28.0f;
        float w1 = 20.0f;
        float h1 = 30.0f;

        // At 1.5x (effective=48): bearings scale proportionally
        float bx15 = 6.0f;
        float by15 = 42.0f;
        float w15 = 30.0f;
        float h15 = 45.0f;

        float scale = CgTextRenderer.logicalMetricScale(baseTargetPx, 48);
        assertEquals(bx1, bx15 * scale, 0.0001f);
        assertEquals(by1, by15 * scale, 0.0001f);
        assertEquals(w1, w15 * scale, 0.0001f);
        assertEquals(h1, h15 * scale, 0.0001f);
    }

    // ── Physical raster demand changes with UI scale ──

    /**
     * Verifies that the effective target pixel size changes with UI scale
     * while the base target pixel size (logical identity) stays the same.
     */
    @Test
    public void effectiveSizeChangesWithUIScaleWhileBaseStaysConstant() {
        int baseTargetPx = 24;
        // 1x → effective = 24
        assertEquals(1.0f, CgTextRenderer.logicalMetricScale(baseTargetPx, 24), 0.0001f);
        // 2x → effective = 48, scale factor = 0.5
        assertEquals(0.5f, CgTextRenderer.logicalMetricScale(baseTargetPx, 48), 0.0001f);
        // 3x → effective = 72, scale factor = 0.333...
        assertEquals(24.0f / 72.0f, CgTextRenderer.logicalMetricScale(baseTargetPx, 72), 0.0001f);
    }

    /**
     * Verifies that the normalization is symmetric: scaling up then normalizing
     * and scaling down then normalizing both yield the original logical values.
     */
    @Test
    public void normalizationIsSymmetricForUpAndDownScaling() {
        int baseTargetPx = 24;
        float logicalBearing = 5.0f;

        // 2x up: physical=10, normalized=10*0.5=5
        float scaleUp = CgTextRenderer.logicalMetricScale(baseTargetPx, 48);
        assertEquals(logicalBearing, (logicalBearing * 2.0f) * scaleUp, 0.0001f);

        // 0.5x down: physical=2.5, normalized=2.5*2.0=5
        float scaleDown = CgTextRenderer.logicalMetricScale(baseTargetPx, 12);
        assertEquals(logicalBearing, (logicalBearing * 0.5f) * scaleDown, 0.0001f);
    }

    @Test
    public void scaledUiRasterDisablesBitmapSubPixelBuckets() {
        CgFontKey key = new CgFontKey("font.ttf", CgFontStyle.REGULAR, 24);
        CgTextRenderContext ctx = new CgTextRenderContext(BufferUtils.createFloatBuffer(16));

        assertEquals(0, CgTextRenderer.resolveSubPixelBucket(ctx, key, 48, 0.75f));
        assertEquals(3, CgTextRenderer.resolveSubPixelBucket(ctx, key, 24, 0.75f));
    }
}
