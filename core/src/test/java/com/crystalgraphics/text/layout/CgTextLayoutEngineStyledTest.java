package com.crystalgraphics.text.layout;

import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.font.CgFontFamily;
import com.crystalgraphics.api.font.CgFontFamilyGroup;
import com.crystalgraphics.api.font.CgFontStyle;
import com.crystalgraphics.api.text.CgShapedRun;
import com.crystalgraphics.api.text.CgStyleSpan;
import com.crystalgraphics.api.text.CgStyledText;
import com.crystalgraphics.api.text.CgTextLayout;
import com.crystalgraphics.api.text.CgTextLayoutRequest;
import org.junit.After;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Phase 9 tests: BiDi ∩ style-span splitting and real per-line metrics —
 * {@link CgTextLayoutEngine#shapeStyled(CgStyledText, CgFontFamilyGroup, com.crystalgraphics.api.text.CgParagraphKnobs)}
 * via {@link CgTextLayoutRequest}.
 *
 * <p>Uses real loaded fonts (native FreeType/HarfBuzz), same pattern as
 * {@link com.crystalgraphics.api.font.CgFontFamilyGroupTest} — every created
 * {@link CgFont} is tracked and disposed in {@link #disposeCreatedFonts}.</p>
 */
public class CgTextLayoutEngineStyledTest {

    private final List<CgFont> createdFonts = new ArrayList<>();

    @After
    public void disposeCreatedFonts() {
        for (CgFont font : createdFonts) {
            font.dispose();
        }
        createdFonts.clear();
    }

    @Test
    public void testMixedBoldRegular_lineHeightIsMaxOfBothFaces() {
        // Same font file loaded at two different pixel sizes so "bold" genuinely has
        // taller real metrics than "regular" — exercises the max-over-metrics reduction
        // without needing two distinct font files.
        CgFontFamily regular = familyAt(CgFontStyle.REGULAR, 16);
        CgFontFamily bold = familyAt(CgFontStyle.BOLD, 32);
        CgFontFamilyGroup group = new CgFontFamilyGroup(Map.of(
                CgFontStyle.REGULAR, regular,
                CgFontStyle.BOLD, bold));

        CgStyledText text = new CgStyledText("plain BOLD", List.of(
                CgStyleSpan.builder().start(6).end(10).bold(true).build()));

        CgTextLayout layout = CgTextLayoutRequest.of(text, group).build();

        assertEquals(1, layout.lines().size());
        float expected = CgFontFamily.combineMetrics(
                List.of(regular.getLayoutMetrics(), bold.getLayoutMetrics())).getLineHeight();
        assertEquals(expected, layout.baked().lineHeight()[0], 0.01f);
        assertTrue("Mixed-line height should exceed the regular-only face's own height",
                layout.baked().lineHeight()[0] > regular.getLayoutMetrics().getLineHeight() + 0.01f);
    }

    @Test
    public void testPlainOnlyLine_lineHeightMatchesRegularFace() {
        CgFontFamily regular = familyAt(CgFontStyle.REGULAR, 16);
        CgFontFamily bold = familyAt(CgFontStyle.BOLD, 32);
        CgFontFamilyGroup group = new CgFontFamilyGroup(Map.of(
                CgFontStyle.REGULAR, regular,
                CgFontStyle.BOLD, bold));

        CgStyledText text = CgStyledText.plain("no styling here");
        CgTextLayout layout = CgTextLayoutRequest.of(text, group).build();

        assertEquals(regular.getLayoutMetrics().getLineHeight(), layout.baked().lineHeight()[0], 0.01f);
    }

    @Test
    public void testBoldSpan_resolvesAgainstBoldFamily() {
        CgFontFamily regular = familyAt(CgFontStyle.REGULAR, 16);
        CgFontFamily bold = familyAt(CgFontStyle.BOLD, 16);
        CgFontFamilyGroup group = new CgFontFamilyGroup(Map.of(
                CgFontStyle.REGULAR, regular,
                CgFontStyle.BOLD, bold));

        CgStyledText text = new CgStyledText("ab", List.of(
                CgStyleSpan.builder().start(1).end(2).bold(true).build()));

        CgTextLayout layout = CgTextLayoutRequest.of(text, group).build();
        List<CgShapedRun> runs = layout.lines().get(0);

        assertEquals(2, runs.size());
        assertEquals("'a' should resolve against the regular family",
                regular.getPrimarySource().getKey(), runs.get(0).getFontKey());
        assertEquals("'b' should resolve against the bold family",
                bold.getPrimarySource().getKey(), runs.get(1).getFontKey());
        assertTrue(runs.get(1).getDecoration() != null); // rich fields present, not null
    }

    @Test
    public void testBoldSpan_carriesRichFieldsOntoShapedRun() {
        CgFontFamily regular = familyAt(CgFontStyle.REGULAR, 16);
        CgFontFamilyGroup group = CgFontFamilyGroup.ofRegular(regular);

        CgStyledText text = new CgStyledText("ab", List.of(
                CgStyleSpan.builder().start(1).end(2).bold(true).argbColor(0xFFFF0000).build()));

        CgTextLayout layout = CgTextLayoutRequest.of(text, group).build();
        List<CgShapedRun> runs = layout.lines().get(0);

        assertEquals(0, runs.get(0).getArgbColor());
        assertEquals(0xFFFF0000, runs.get(1).getArgbColor());
    }

    @Test
    public void testBoldSpan_colorReachesBakedGlyphs() {
        // Phase 11: the color must also survive the bake step, one entry per glyph.
        CgFontFamily regular = familyAt(CgFontStyle.REGULAR, 16);
        CgFontFamilyGroup group = CgFontFamilyGroup.ofRegular(regular);

        CgStyledText text = new CgStyledText("ab", List.of(
                CgStyleSpan.builder().start(1).end(2).bold(true).argbColor(0xFFFF0000).build()));

        CgTextLayout layout = CgTextLayoutRequest.of(text, group).build();
        int[] bakedColors = layout.baked().argbColor();

        assertEquals(2, bakedColors.length);
        assertEquals("'a' has no span override, should inherit (0)", 0, bakedColors[0]);
        assertEquals("'b' should carry its span's override color", 0xFFFF0000, bakedColors[1]);
    }

    @Test
    public void testBoldSpanContainingRtlSubrun_resolvesRtlRunAgainstBoldFamily() {
        CgFontFamily regular = familyAt(CgFontStyle.REGULAR, 16);
        CgFontFamily bold = familyAt(CgFontStyle.BOLD, 16);
        CgFontFamilyGroup group = new CgFontFamilyGroup(Map.of(
                CgFontStyle.REGULAR, regular,
                CgFontStyle.BOLD, bold));

        // "a " (regular, LTR) + bold span covering "b" + an Arabic (RTL) word + "c"
        String rtlWord = "مرحبا";
        String fullText = "a b" + rtlWord + "c";
        CgStyledText text = new CgStyledText(fullText, List.of(
                CgStyleSpan.builder().start(2).end(fullText.length()).bold(true).build()));

        CgTextLayout layout = CgTextLayoutRequest.of(text, group).build();
        List<CgShapedRun> runs = layout.lines().get(0);

        boolean foundRegularLtr = false;
        boolean foundBoldRtl = false;
        for (CgShapedRun run : runs) {
            if (!run.isRtl() && run.getFontKey().equals(regular.getPrimarySource().getKey())) {
                foundRegularLtr = true;
            }
            if (run.isRtl()) {
                assertEquals("RTL sub-run inside the bold span should resolve to the bold family",
                        bold.getPrimarySource().getKey(), run.getFontKey());
                foundBoldRtl = true;
            }
        }
        assertTrue("Should have an unstyled regular-family LTR run before the bold span", foundRegularLtr);
        assertTrue("Should have found the bold-resolved RTL run", foundBoldRtl);
    }

    // ---------------------------------------------------------------
    //  Phase 10: reshaper resolves against the run's own face, not always regular
    // ---------------------------------------------------------------

    @Test
    public void testForcedWrapInsideBoldRun_reshapesAgainstBoldFace_notRegular() {
        // Before phase 10, this threw IllegalArgumentException("Font key is not part of
        // this family") — the reshaper was always built against the regular family, which
        // doesn't contain the bold font's key at all.
        CgFontFamily regular = familyAt(CgFontStyle.REGULAR, 16);
        CgFontFamily bold = familyAt(CgFontStyle.BOLD, 64); // much larger so wrap is forced
        CgFontFamilyGroup group = new CgFontFamilyGroup(Map.of(
                CgFontStyle.REGULAR, regular,
                CgFontStyle.BOLD, bold));

        // One long unbroken "word" (no spaces) — forces grapheme-level intra-run splitting.
        String longWord = "abcdefghijklmnopqrstuvwxyz";
        CgStyledText text = new CgStyledText(longWord, List.of(
                CgStyleSpan.builder().start(0).end(longWord.length()).bold(true).build()));

        CgTextLayout layout = CgTextLayoutRequest.of(text, group).maxWidth(40.0f).build();

        assertTrue("Bold text at 64px should need more than one line at maxWidth=40",
                layout.lines().size() > 1);
        for (List<CgShapedRun> line : layout.lines()) {
            for (CgShapedRun run : line) {
                assertEquals("Every wrapped fragment of the bold run should still resolve "
                                + "to the bold family's font key",
                        bold.getPrimarySource().getKey(), run.getFontKey());
            }
        }
    }

    // ---------------------------------------------------------------
    //  Helpers
    // ---------------------------------------------------------------

    private CgFontFamily familyAt(CgFontStyle style, int targetPx) {
        byte[] bytes = loadTestFontBytes();
        CgFont font = CgFont.load(bytes, "test-font-" + style + "-" + targetPx, style, targetPx);
        createdFonts.add(font);
        return CgFontFamily.of(font);
    }

    private static byte[] loadTestFontBytes() {
        InputStream in = CgTextLayoutEngineStyledTest.class
                .getResourceAsStream("/assets/crystalgraphics/test-font.ttf");
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
