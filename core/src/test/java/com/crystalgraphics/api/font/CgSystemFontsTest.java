package com.crystalgraphics.api.font;

import com.crystalgraphics.harfbuzz.HBBuffer;
import com.crystalgraphics.harfbuzz.HBGlyphInfo;
import com.crystalgraphics.harfbuzz.HBShape;
import org.junit.BeforeClass;
import org.junit.Test;

import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * {@link CgSystemFonts} over the test fonts standing in for the installed ones, so nothing here
 * depends on the machine: the index, name matching, per-character fallback and what a family does
 * with it.
 */
public class CgSystemFontsTest {

    private static final int HIRAGANA_A = 0x3042;
    private static final int ARABIC_AIN = 0x0639;

    private static CgSystemFonts fonts;

    @BeforeClass
    public static void index() {
        fonts = CgSystemFonts.of(List.of(Paths.get("src/test/resources/fonts")));
    }

    @Test
    public void familiesAreFoundByNameWhateverTheCase() {
        assertTrue(fonts.families().toString(), fonts.families().contains("IBM Plex Sans"));
        CgSystemFontFace plex = fonts.find("ibm plex sans", CgFontStyle.REGULAR);
        assertNotNull(plex);
        assertEquals("IBM Plex Sans", plex.getFamily());
        assertNull(fonts.find("No Such Family", CgFontStyle.REGULAR));
    }

    @Test
    public void aFallbackIsAnInstalledFaceThatDrawsTheCharacter() {
        CgSystemFontFace japanese = fonts.fallbackFor(HIRAGANA_A, CgFontStyle.REGULAR, Locale.JAPAN);
        assertNotNull(japanese);
        assertTrue(japanese.covers(HIRAGANA_A));
        CgSystemFontFace arabic = fonts.fallbackFor(ARABIC_AIN, CgFontStyle.REGULAR, Locale.US);
        assertNotNull(arabic);
        assertTrue(arabic.covers(ARABIC_AIN));
        assertNull("private use never falls back", fonts.fallbackFor(0xE000, CgFontStyle.REGULAR, Locale.US));
    }

    @Test
    public void loadingAFaceTwiceGivesTheSameFont() {
        CgSystemFontFace face = fonts.find("IBM Plex Sans", CgFontStyle.REGULAR);
        CgFont first = fonts.load(face, CgFontStyle.REGULAR, 16);
        assertSame(first, fonts.load(face, CgFontStyle.REGULAR, 16));
        assertTrue(first.isSizeBound());
        assertEquals(16, first.getTargetPx());
    }

    /** What an application does: a Latin-only family draws Japanese and Arabic from the installed fonts. */
    @Test
    public void aFamilyDrawsWhatItsOwnFontsLackFromTheInstalledOnes() {
        CgFont plex = fonts.load(fonts.find("IBM Plex Sans", CgFontStyle.REGULAR), CgFontStyle.REGULAR, 16);
        CgFontFamily family = CgFontFamily.of(plex).withFallback(fonts.fallback(Locale.JAPAN));
        String text = "Hello あ مرحبا";

        for (CgFontFamily.ResolvedFontRun run : family.resolveRuns(text, 0, text.length())) {
            CgFont font = family.resolveLoadedFont(run.getFontKey());
            for (int i = run.getStart(); i < run.getEnd(); i = text.offsetByCodePoints(i, 1)) {
                int cp = text.codePointAt(i);
                assertTrue("U+" + Integer.toHexString(cp) + " drawn by " + font, font.canDisplayCodePoint(cp));
            }
        }
        assertEquals("one face for the kana, one for the Arabic", 2, family.getDiscoveredSources().size());
        assertEquals("line metrics stay the declared fonts'", plex.getMetrics().getLineHeight(),
                family.getLayoutMetrics().getLineHeight(), 0f);
    }

    /** An installed face is opened from its file, and every size of it shares that one data object. */
    @Test
    public void anInstalledFaceIsOpenedFromItsFile() {
        CgSystemFontFace face = fonts.find("Noto Sans Arabic", CgFontStyle.REGULAR);
        CgFont font = fonts.load(face, CgFontStyle.REGULAR, 18);
        assertEquals(face.getPath(), font.getData().file());
        assertTrue(font.canDisplayCodePoint(ARABIC_AIN));
        assertSame(font.getData(), font.atSize(22).getData());
    }

    /** Shaping reads the font through HarfBuzz on the FreeType face — which, for an installed font, is a file. */
    @Test
    public void anInstalledFaceShapes() {
        CgFont arabic = fonts.load(fonts.find("Noto Sans Arabic", CgFontStyle.REGULAR), CgFontStyle.REGULAR, 16);
        HBBuffer buffer = HBBuffer.create();
        try {
            buffer.addUTF8("مرحبا");
            buffer.guessSegmentProperties();
            HBShape.shape(arabic.getHbFontInternal(), buffer);
            HBGlyphInfo[] glyphs = buffer.getGlyphInfos();
            assertTrue(glyphs.length > 0);
            for (HBGlyphInfo glyph : glyphs) {
                assertTrue("shaped to a real glyph, not .notdef", glyph.getCodepoint() != 0);
            }
        } finally {
            buffer.destroy();
        }
    }

    /** CSS Fonts 4's order: 400 looks up to 500 first, then lighter, then heavier; 700 looks heavier first. */
    @Test
    public void weightMatchingFollowsCss() {
        List<Float> fromRegular = Arrays.asList(
                CgSystemFonts.weightDistance(400, 500, 500),
                CgSystemFonts.weightDistance(400, 300, 300),
                CgSystemFonts.weightDistance(400, 700, 700));
        assertTrue(fromRegular.toString(), fromRegular.get(0) < fromRegular.get(1) && fromRegular.get(1) < fromRegular.get(2));
        assertTrue(CgSystemFonts.weightDistance(700, 900, 900) < CgSystemFonts.weightDistance(700, 600, 600));
        assertEquals(0f, CgSystemFonts.weightDistance(400, 100, 900), 0f);
    }
}
