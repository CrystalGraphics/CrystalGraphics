package com.crystalgraphics.api.font;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** How a family uses a {@link CgFontFallback}: only on a miss, once per character, and found again by key. */
public class CgFontFamilyFallbackTest {

    private static final int ARABIC_AIN = 0x0639;

    private static CgFont latin;
    private static CgFont arabic;

    @BeforeClass
    public static void load() throws IOException {
        latin = CgFont.load(Files.readAllBytes(Paths.get("src/test/resources/fonts/IBMPlexSans-Regular.ttf")),
                "IBMPlexSans-Regular.ttf", CgFontStyle.REGULAR, 16);
        arabic = CgFont.load(Files.readAllBytes(Paths.get("src/test/resources/fonts/NotoSansArabic-Regular.ttf")),
                "NotoSansArabic-Regular.ttf", CgFontStyle.REGULAR, 16);
    }

    @AfterClass
    public static void dispose() {
        latin.dispose();
        arabic.dispose();
    }

    @Test
    public void aSuppliedFontIsUsedAndResolvableByItsKey() {
        AtomicInteger asked = new AtomicInteger();
        CgFontFamily family = CgFontFamily.of(latin).withFallback((cp, style, px) -> {
            asked.incrementAndGet();
            return arabic.canDisplayCodePoint(cp) ? arabic : null;
        });

        List<CgFontFamily.ResolvedFontRun> runs = family.resolveRuns("ab عل", 0, 5);
        assertEquals(2, runs.size());
        assertEquals(latin.getKey(), runs.get(0).getFontKey());
        assertEquals(arabic.getKey(), runs.get(1).getFontKey());
        assertSame(arabic, family.resolveLoadedFont(arabic.getKey()));
        assertEquals("asked once, for the first Arabic letter; the second found it among the discovered", 1, asked.get());
    }

    @Test
    public void aMissIsRememberedAndNeverAskedTwice() {
        AtomicInteger asked = new AtomicInteger();
        CgFontFamily family = CgFontFamily.of(latin).withFallback((cp, style, px) -> {
            asked.incrementAndGet();
            return null;
        });

        family.resolveSourceForCodePoint(ARABIC_AIN);
        family.resolveSourceForCodePoint(ARABIC_AIN);
        assertEquals(1, asked.get());
        assertSame("with nothing found, the primary still answers", family.getPrimarySource(),
                family.resolveSourceForCodePoint(ARABIC_AIN));
    }

    @Test
    public void aFontAtTheWrongSizeIsRefused() {
        CgFontFamily family = CgFontFamily.of(latin).withFallback((cp, style, px) -> arabic.atSize(px + 1));
        assertSame(family.getPrimarySource(), family.resolveSourceForCodePoint(ARABIC_AIN));
        assertTrue(family.getDiscoveredSources().isEmpty());
    }

    /** Han asks in the language its own text shows, before the locale: 日 ahead of です is still Japanese. */
    @Test
    public void hanAsksInTheLanguageItsTextShows() {
        Map<Integer, Locale> firstAsked = new HashMap<>();
        CgFontFamily family = CgFontFamily.of(latin).withFallback(new CgFontFallback() {
            @Override
            public CgFont fontFor(int codePoint, CgFontStyle style, int targetPx) {
                return fontFor(codePoint, style, targetPx, null);
            }

            @Override
            public CgFont fontFor(int codePoint, CgFontStyle style, int targetPx, Locale language) {
                firstAsked.putIfAbsent(codePoint, language);
                return null;
            }
        });

        family.resolveRuns("日本語です", 0, 5);
        family.resolveRuns("한국 韓國", 0, 5);
        family.resolveRuns("中文", 0, 2);

        assertEquals(Locale.JAPANESE, firstAsked.get(0x65E5));
        assertEquals(Locale.KOREAN, firstAsked.get(0x97D3));
        assertTrue("asked about 中", firstAsked.containsKey(0x4E2D));
        assertNull("no kana, no Hangul: the fallback's own locale decides", firstAsked.get(0x4E2D));
    }

    /** A renderer re-sizes a family per draw; losing the fallback there loses every system glyph at that size. */
    @Test
    public void resizingKeepsTheFallback() {
        CgFontFallback fallback = (cp, style, px) -> null;
        CgFontFamily family = CgFontFamily.of(latin).withFallback(fallback);
        CgFontFamily sized = family.atSize(24);
        assertEquals(24, sized.getTargetPx());
        assertSame(fallback, sized.getFallback());
        assertSame("already that size", family, family.atSize(16));
    }

    @Test
    public void declaredSourcesAreAskedFirst() {
        AtomicInteger asked = new AtomicInteger();
        CgFontFamily family = CgFontFamily.of(latin, arabic).withFallback((cp, style, px) -> {
            asked.incrementAndGet();
            return null;
        });
        family.resolveRuns("aع", 0, 2);
        assertEquals(0, asked.get());
    }
}
