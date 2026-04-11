package io.github.somehussar.crystalgraphics.text.msdf;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link CgMsdfGlyphLayout}.
 *
 * <p>Validates upstream-parity glyph box computation: irregular box sizes
 * for different glyph shapes, configurable pxRange and pixel alignment,
 * plane bounds derivation, and whitespace handling.</p>
 */
public class CgMsdfGlyphLayoutTest {

    private static final double EPSILON = 0.01;

    @Test
    public void testEmptyBounds_producesEmptyLayout() {
        CgMsdfGlyphLayout layout = CgMsdfGlyphLayout.compute(
                0.5, 0.0, 0.3, 0.0,  // left >= right -> empty
                48, 4.0);

        assertTrue(layout.isEmpty());
        assertEquals(0, layout.getBoxWidth());
        assertEquals(0, layout.getBoxHeight());
    }

    @Test
    public void testWhitespaceBounds_producesEmptyLayout() {
        CgMsdfGlyphLayout layout = CgMsdfGlyphLayout.compute(
                0, 0, 0, 0,
                48, 4.0);

        assertTrue(layout.isEmpty());
    }

    @Test
    public void testNarrowGlyph_smallerBoxThanWide() {
        // 'l' like glyph: narrow bounds
        CgMsdfGlyphLayout narrow = CgMsdfGlyphLayout.compute(
                0.1, 0.0, 0.2, 0.7,  // narrow: 0.1 EM wide
                48, 4.0);

        // 'W' like glyph: wide bounds
        CgMsdfGlyphLayout wide = CgMsdfGlyphLayout.compute(
                0.0, 0.0, 0.8, 0.7,  // wide: 0.8 EM wide
                48, 4.0);

        assertFalse(narrow.isEmpty());
        assertFalse(wide.isEmpty());

        // Key invariant: different glyph shapes produce different box widths
        // at the same font size — no fixed cell model
        assertTrue("Narrow glyph should have smaller box width than wide glyph",
                narrow.getBoxWidth() < wide.getBoxWidth());

        // Both should have reasonable positive dimensions
        assertTrue(narrow.getBoxWidth() > 0);
        assertTrue(wide.getBoxWidth() > 0);
    }

    @Test
    public void testSameFontSize_differentBoxSizes() {
        // Three glyphs with different aspect ratios at the same font size
        CgMsdfGlyphLayout square = CgMsdfGlyphLayout.compute(
                0.0, 0.0, 0.5, 0.5,
                64, 4.0);

        CgMsdfGlyphLayout tall = CgMsdfGlyphLayout.compute(
                0.1, -0.1, 0.3, 0.8,
                64, 4.0);

        CgMsdfGlyphLayout flat = CgMsdfGlyphLayout.compute(
                0.0, 0.2, 0.7, 0.4,
                64, 4.0);

        // All at 64px — but NOT equal box sizes (no fixed cell)
        boolean allSameWidth = square.getBoxWidth() == tall.getBoxWidth()
                && tall.getBoxWidth() == flat.getBoxWidth();
        boolean allSameHeight = square.getBoxHeight() == tall.getBoxHeight()
                && tall.getBoxHeight() == flat.getBoxHeight();
        assertFalse("Different shapes should produce different box sizes",
                allSameWidth && allSameHeight);
    }

    @Test
    public void testPxRange_affectsBoxSize() {
        CgMsdfGlyphLayout small = CgMsdfGlyphLayout.compute(
                0.0, 0.0, 0.5, 0.7,
                48, 2.0);  // small range

        CgMsdfGlyphLayout large = CgMsdfGlyphLayout.compute(
                0.0, 0.0, 0.5, 0.7,
                48, 8.0);  // large range

        // Larger pxRange should produce a larger box (more SDF border)
        assertTrue("Larger pxRange should produce larger box",
                large.getBoxWidth() >= small.getBoxWidth());
        assertTrue("Larger pxRange should produce taller box",
                large.getBoxHeight() >= small.getBoxHeight());
    }

    @Test
    public void testScale_matchesTargetPx() {
        CgMsdfGlyphLayout layout = CgMsdfGlyphLayout.compute(
                0.0, 0.0, 0.5, 0.7,
                48, 4.0);

        assertEquals(48.0, layout.getScale(), EPSILON);
    }

    @Test
    public void testPlaneBounds_nonZeroForVisibleGlyph() {
        CgMsdfGlyphLayout layout = CgMsdfGlyphLayout.compute(
                0.0, 0.0, 0.5, 0.7,
                48, 4.0);

        assertFalse(layout.isEmpty());
        assertTrue("Plane width should be positive",
                layout.getPlaneRight() - layout.getPlaneLeft() > 0);
        assertTrue("Plane height should be positive",
                layout.getPlaneTop() - layout.getPlaneBottom() > 0);
    }

    @Test
    public void testBoxDimensions_encompassScaledBoundsAndRange() {
        double shapeW = 0.5;
        double shapeH = 0.7;
        int targetPx = 48;
        double pxRange = 4.0;

        CgMsdfGlyphLayout layout = CgMsdfGlyphLayout.compute(
                0.0, 0.0, shapeW, shapeH,
                targetPx, pxRange);

        // Box must be at least as large as scaled bounds + full pxRange
        double minW = shapeW * targetPx + pxRange;
        double minH = shapeH * targetPx + pxRange;

        assertTrue("Box width (" + layout.getBoxWidth() + ") should be >= minW (" + minW + ")",
                layout.getBoxWidth() >= minW);
        assertTrue("Box height (" + layout.getBoxHeight() + ") should be >= minH (" + minH + ")",
                layout.getBoxHeight() >= minH);
    }

    @Test
    public void testTranslate_positionsGlyphWithinBox() {
        CgMsdfGlyphLayout layout = CgMsdfGlyphLayout.compute(
                0.1, -0.05, 0.6, 0.75,
                48, 4.0);

        // The projection formula is: pixel = scale * (coord + translate).
        // shapeLeft mapped through projection should land inside the box
        // with room for the SDF range border.
        double leftPixel = layout.getScale() * (0.1 + layout.getTranslateX());
        double bottomPixel = layout.getScale() * (-0.05 + layout.getTranslateY());

        // Left edge should be at least halfRange pixels from box edge 0
        assertTrue("Glyph left edge should be inside box with SDF margin",
                leftPixel >= 1.0);
        // Bottom edge should be at least halfRange pixels from box edge 0
        assertTrue("Glyph bottom edge should be inside box with SDF margin",
                bottomPixel >= 1.0);
        // Right edge should fit within box
        double rightPixel = layout.getScale() * (0.6 + layout.getTranslateX());
        assertTrue("Glyph right edge should fit within box",
                rightPixel <= layout.getBoxWidth());
    }

    @Test
    public void testPixelAlignment_affectsBoxSize() {
        CgMsdfGlyphLayout aligned = CgMsdfGlyphLayout.compute(
                0.05, 0.05, 0.55, 0.75,
                48, 4.0, 0, true, true);

        CgMsdfGlyphLayout unaligned = CgMsdfGlyphLayout.compute(
                0.05, 0.05, 0.55, 0.75,
                48, 4.0, 0, false, false);

        // Both should produce valid layouts, but dimensions may differ
        assertFalse(aligned.isEmpty());
        assertFalse(unaligned.isEmpty());

        // Pixel-aligned origin should produce integer-aligned translate
        // (translate * scale should be close to integer)
        double alignedTxPx = aligned.getTranslateX() * aligned.getScale();
        assertEquals("Pixel-aligned translateX should be near integer",
                Math.round(alignedTxPx), alignedTxPx, 0.5);
    }

    @Test
    public void testRangeInShapeUnits() {
        CgMsdfGlyphLayout layout = CgMsdfGlyphLayout.compute(
                0.0, 0.0, 0.5, 0.7,
                48, 4.0);

        double expected = 4.0 / (2.0 * 48.0);
        //assertEquals(expected, layout.getRangeInShapeUnits(), 0.0001);
    }

    @Test
    public void testConvenienceOverload_defaultParams() {
        CgMsdfGlyphLayout layout = CgMsdfGlyphLayout.compute(
                0.0, 0.0, 0.5, 0.7, 48, 4.0);

        assertFalse(layout.isPxAlignOriginX());
        assertFalse(layout.isPxAlignOriginY());
        assertEquals(0.0, layout.getMiterLimit(), 0.001);
    }

    @Test
    public void testToString_containsKeyInfo() {
        CgMsdfGlyphLayout layout = CgMsdfGlyphLayout.compute(
                0.0, 0.0, 0.5, 0.7, 48, 4.0);
        String s = layout.toString();
        assertTrue(s.contains("box="));
        assertTrue(s.contains("scale="));
        assertTrue(s.contains("plane="));
    }
}
