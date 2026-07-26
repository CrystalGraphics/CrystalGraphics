package com.crystalgraphics.api.text;

import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.font.CgFontFamily;
import com.crystalgraphics.api.font.CgFontStyle;
import org.junit.After;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Phase 12 tests — one per {@link CgTextLayoutRequest} paragraph-level knob: alignment,
 * max-lines truncation (with and without ellipsis), tab-stop expansion, line-height
 * override, and direction override. Uses a real loaded font (native FreeType/HarfBuzz),
 * same pattern as {@link com.crystalgraphics.api.font.CgFontFamilyGroupTest}.
 */
public class CgTextLayoutRequestTest {

    private final List<CgFont> createdFonts = new ArrayList<>();

    @After
    public void disposeCreatedFonts() {
        for (CgFont font : createdFonts) {
            font.dispose();
        }
        createdFonts.clear();
    }

    // ---------------------------------------------------------------
    //  align
    // ---------------------------------------------------------------

    @Test
    public void testAlign_left_isDefault_noOffset() {
        CgTextLayout layout = CgTextLayoutRequest.of("aaaaaaaaaa\nbb", family()).build();
        int[] lineStart = layout.baked().lineStart();
        assertEquals(0f, layout.baked().penX()[lineStart[1]], 0.01f);
    }

    @Test
    public void testAlign_center_offsetsShorterLineHalfway() {
        CgTextLayout layout = CgTextLayoutRequest.of("aaaaaaaaaa\nbb", family())
                .align(CgTextAlign.CENTER)
                .build();

        assertEquals(2, layout.lines().size());
        float line1Width = lineWidth(layout.lines().get(1));
        float expectedOffset = (layout.totalWidth() - line1Width) / 2f;

        int[] lineStart = layout.baked().lineStart();
        assertEquals(expectedOffset, layout.baked().penX()[lineStart[1]], 0.05f);
    }

    @Test
    public void testAlign_right_offsetsShorterLineToTheRightEdge() {
        CgTextLayout layout = CgTextLayoutRequest.of("aaaaaaaaaa\nbb", family())
                .align(CgTextAlign.RIGHT)
                .build();

        float line1Width = lineWidth(layout.lines().get(1));
        float expectedOffset = layout.totalWidth() - line1Width;

        int[] lineStart = layout.baked().lineStart();
        assertEquals(expectedOffset, layout.baked().penX()[lineStart[1]], 0.05f);
    }

    // ---------------------------------------------------------------
    //  maxLines / ellipsis
    // ---------------------------------------------------------------

    @Test
    public void testMaxLines_truncatesWithoutEllipsis() {
        CgTextLayout layout = CgTextLayoutRequest.of("a\nb\nc\nd", family())
                .maxLines(2)
                .build();
        assertEquals(2, layout.lines().size());
    }

    @Test
    public void testMaxLines_notExceeded_noTruncation() {
        CgTextLayout layout = CgTextLayoutRequest.of("a\nb", family())
                .maxLines(5)
                .build();
        assertEquals(2, layout.lines().size());
    }

    @Test
    public void testMaxLines_withEllipsis_appendsMarkerGlyphsToLastLine() {
        CgTextLayout withEllipsis = CgTextLayoutRequest.of("a\nb\nc\nd", family())
                .maxLines(2).ellipsis("...")
                .build();
        CgTextLayout withoutEllipsis = CgTextLayoutRequest.of("a\nb\nc\nd", family())
                .maxLines(2)
                .build();

        assertEquals(2, withEllipsis.lines().size());
        int glyphsWithEllipsis = countGlyphs(withEllipsis.lines().get(1));
        int glyphsWithoutEllipsis = countGlyphs(withoutEllipsis.lines().get(1));
        assertTrue("Ellipsis marker should add glyphs to the truncated last line",
                glyphsWithEllipsis > glyphsWithoutEllipsis);
    }

    // ---------------------------------------------------------------
    //  tabStopWidth
    // ---------------------------------------------------------------

    @Test
    public void testTabStopWidth_pushesFollowingGlyphToNextStop() {
        CgTextLayout withTab = CgTextLayoutRequest.of("a\tb", family())
                .tabStopWidth(50f)
                .build();
        CgTextLayout withoutTab = CgTextLayoutRequest.of("ab", family()).build();

        assertEquals("'a' + tab + 'b' should bake to exactly 2 glyphs (tab has none)",
                2, withTab.baked().glyphCount());
        float[] withTabPenX = withTab.baked().penX();
        float[] withoutTabPenX = withoutTab.baked().penX();
        assertTrue("The tab should push 'b' further right than sitting immediately after 'a'",
                withTabPenX[1] > withoutTabPenX[1] + 10f);
    }

    @Test
    public void testTabStopWidth_disabledByDefault_tabsShapeNormally() {
        CgTextLayout layout = CgTextLayoutRequest.of("a\tb", family()).build();
        // Without tabStopWidth set, the tab char is shaped like any other character —
        // not stripped, not treated specially — so it contributes at least one glyph slot.
        assertTrue(layout.baked().glyphCount() >= 2);
    }

    // ---------------------------------------------------------------
    //  lineHeightOverride
    // ---------------------------------------------------------------

    @Test
    public void testLineHeightOverride_replacesComputedHeightUniformly() {
        CgTextLayout layout = CgTextLayoutRequest.of("a\nb", family())
                .lineHeightOverride(100f)
                .build();

        assertArrayEquals(new float[]{100f, 100f}, layout.baked().lineHeight(), 0.01f);
    }

    @Test
    public void testLineHeightOverride_disabledByDefault_usesRealMetrics() {
        CgFontFamily family = family();
        CgTextLayout layout = CgTextLayoutRequest.of("a\nb", family).build();

        float expected = family.getLayoutMetrics().getLineHeight();
        assertArrayEquals(new float[]{expected, expected}, layout.baked().lineHeight(), 0.01f);
    }

    // ---------------------------------------------------------------
    //  direction
    // ---------------------------------------------------------------

    @Test
    public void testDirection_ltrVsRtlForced_produceDifferentRunStructure() {
        // A mixed Latin+Arabic fixture — forcing the paragraph's overall base direction
        // changes how the neutral space between the two words is leveled, which changes
        // the resulting run/visual-order structure.
        CgFontFamily family = family();
        String mixedText = "Hello مرحبا";

        CgTextLayout ltrForced = CgTextLayoutRequest.of(mixedText, family)
                .direction(CgTextDirection.LTR)
                .build();
        CgTextLayout rtlForced = CgTextLayoutRequest.of(mixedText, family)
                .direction(CgTextDirection.RTL)
                .build();

        assertNotEquals("Forcing LTR vs RTL paragraph direction on mixed-script text "
                        + "should produce different run/visual-order structure",
                ltrForced.lines(), rtlForced.lines());
    }

    @Test
    public void testDirection_auto_matchesDefaultBidiBehavior() {
        // AUTO should be indistinguishable from not setting direction at all.
        CgFontFamily family = family();
        String text = "Hello world";

        CgTextLayout explicit = CgTextLayoutRequest.of(text, family)
                .direction(CgTextDirection.AUTO)
                .build();
        CgTextLayout implicit = CgTextLayoutRequest.of(text, family).build();

        assertEquals(explicit.lines(), implicit.lines());
    }

    // ---------------------------------------------------------------
    //  Helpers
    // ---------------------------------------------------------------

    private static float lineWidth(List<CgShapedRun> line) {
        float width = 0;
        for (CgShapedRun run : line) {
            width += run.totalAdvance();
        }
        return width;
    }

    private static int countGlyphs(List<CgShapedRun> line) {
        int count = 0;
        for (CgShapedRun run : line) {
            count += run.glyphIds().length;
        }
        return count;
    }

    private CgFontFamily family() {
        byte[] bytes = loadTestFontBytes();
        CgFont font = CgFont.load(bytes, "test-font", CgFontStyle.REGULAR, 16);
        createdFonts.add(font);
        return CgFontFamily.of(font);
    }

    private static byte[] loadTestFontBytes() {
        InputStream in = CgTextLayoutRequestTest.class.getResourceAsStream("/assets/crystalgraphics/test-font.ttf");
        assertNotNull("test font resource must exist", in);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                bos.write(buffer, 0, read);
            }
            return bos.toByteArray();
        } catch (IOException e) {
            throw new AssertionError("Failed to read test font resource", e);
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }
    }
}
