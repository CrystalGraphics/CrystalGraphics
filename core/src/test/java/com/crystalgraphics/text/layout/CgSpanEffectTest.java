package com.crystalgraphics.text.layout;

import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.font.CgFontFamily;
import com.crystalgraphics.api.font.CgFontFamilyGroup;
import com.crystalgraphics.api.font.CgFontStyle;
import com.crystalgraphics.api.text.CgFontFeature;
import com.crystalgraphics.api.text.CgStyleSpan;
import com.crystalgraphics.api.text.CgStyledText;
import com.crystalgraphics.api.text.CgTextLayout;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * Proves that {@code baselineShift} and {@code fontFeatures} actually reach the output.
 *
 * <p>Both were carried faithfully from {@link CgStyleSpan} onto {@code CgShapedRun} and then
 * dropped at the last step — {@code baselineShift} never added to {@code penY}, {@code fontFeatures}
 * never passed to HarfBuzz. Neither failed loudly; setting one simply did nothing. These tests
 * exist because that failure mode is invisible to every other test in the suite: the layout was
 * still well-formed, just not the layout that was asked for.</p>
 */
public class CgSpanEffectTest {

    /** A real, shipped font with genuine kerning data — the kern test needs both to mean anything. */
    private static final String FONT_RESOURCE = "/assets/crystalgraphics/IBMPlexSans-Regular.ttf";
    private static final int SIZE_PX = 32;

    private static CgFontFamilyGroup group;

    @BeforeClass
    public static void loadFont() throws Exception {
        byte[] bytes;
        try (java.io.InputStream in = CgSpanEffectTest.class.getResourceAsStream(FONT_RESOURCE)) {
            if (in == null) throw new IllegalStateException("missing test font: " + FONT_RESOURCE);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            for (int n; (n = in.read(chunk)) > 0; ) out.write(chunk, 0, n);
            bytes = out.toByteArray();
        }
        font = CgFont.load(bytes, "span-effect-test", CgFontStyle.REGULAR, SIZE_PX);
        group = CgFontFamilyGroup.ofRegular(CgFontFamily.of(font));
    }

    private static CgFont font;

    /**
     * Disposed explicitly. {@code CgFont.load} registers into process-wide caches, so a test class
     * that loads a font and walks away leaves it there for every later test in the same JVM — which
     * showed up as unrelated font tests failing only when the whole suite ran, and passing in
     * isolation.
     */
    @org.junit.AfterClass
    public static void disposeFont() {
        if (font != null) {
            font.dispose();
            font = null;
        }
        group = null;
    }

    private static CgStyleSpan span(int start, int end, float baselineShift, List<CgFontFeature> features) {
        return CgStyleSpan.builder()
                .start(start).end(end)
                .baselineShift(baselineShift)
                .fontFeatures(features)
                .build();
    }

    private static CgTextLayout layout(CgStyledText text) {
        return CgTextLayout.of(text, group).shape().layout(0f, 0f);
    }

    // ── baselineShift ───────────────────────────────────────────────────

    @Test
    public void baselineShiftMovesTheSpansGlyphs() {
        String text = "AAAA";
        CgTextLayout unshifted = layout(new CgStyledText(text, Collections.emptyList()));
        CgTextLayout shifted = layout(new CgStyledText(text,
                Collections.singletonList(span(2, 4, -10f, null))));

        float[] before = unshifted.baked().penY();
        float[] after = shifted.baked().penY();
        assertEquals("same glyph count", before.length, after.length);

        // Glyphs outside the span are untouched...
        assertEquals(before[0], after[0], 0.001f);
        assertEquals(before[1], after[1], 0.001f);
        // ...and glyphs inside it moved by exactly the shift, in the documented direction
        // (negative = up the screen, since penY grows downward).
        assertEquals(before[2] - 10f, after[2], 0.001f);
        assertEquals(before[3] - 10f, after[3], 0.001f);
    }

    @Test
    public void zeroBaselineShiftChangesNothing() {
        String text = "AAAA";
        float[] plain = layout(new CgStyledText(text, Collections.emptyList())).baked().penY();
        float[] zero = layout(new CgStyledText(text,
                Collections.singletonList(span(0, 4, 0f, null)))).baked().penY();
        for (int i = 0; i < plain.length; i++) {
            assertEquals(plain[i], zero[i], 0.001f);
        }
    }

    @Test
    public void baselineShiftDoesNotChangeHorizontalAdvance() {
        // A vertical shift must not move the pen sideways -- an easy mistake if the shift were
        // applied to the wrong array.
        //
        // The control is a span with the SAME boundaries and a zero shift, not unspanned text.
        // Any span splits the shaping run at its edges, and separately-shaped runs lose the kerning
        // that would have applied across the boundary, so spanned text is legitimately a different
        // width from unspanned text. Comparing against unspanned would fail for a reason that has
        // nothing to do with baselineShift.
        String text = "AAAA";
        CgTextLayout unshifted = layout(new CgStyledText(text,
                Collections.singletonList(span(1, 3, 0f, null))));
        CgTextLayout shifted = layout(new CgStyledText(text,
                Collections.singletonList(span(1, 3, 8f, null))));

        assertEquals(unshifted.totalWidth(), shifted.totalWidth(), 0.001f);
        float[] a = unshifted.baked().penX();
        float[] b = shifted.baked().penX();
        assertEquals(a.length, b.length);
        for (int i = 0; i < a.length; i++) {
            assertEquals("penX[" + i + "]", a[i], b[i], 0.001f);
        }
    }

    // ── fontFeatures ────────────────────────────────────────────────────

    @Test
    public void disablingKerningChangesAdvances() {
        // "AV" is the canonical kerning pair. Shaping it with kern off must produce a different
        // total advance than with kern on -- if features never reached HarfBuzz these would be
        // identical, which is exactly the bug this guards.
        String text = "AVAVAVAV";
        CgTextLayout kerned = layout(new CgStyledText(text, Collections.emptyList()));
        CgTextLayout unkerned = layout(new CgStyledText(text, Collections.singletonList(
                span(0, text.length(), 0f, Collections.singletonList(new CgFontFeature("kern", 0))))));

        assertTrue("sanity: the fixture must actually produce glyphs", kerned.baked().glyphCount() > 0);
        assertNotEquals("kern=0 must change the shaped advances",
                kerned.totalWidth(), unkerned.totalWidth(), 0.001f);
    }

    @Test
    public void emptyAndNullFeaturesShapeIdenticallyToNoSpan() {
        String text = "AVAV";
        float plain = layout(new CgStyledText(text, Collections.emptyList())).totalWidth();
        float nullFeatures = layout(new CgStyledText(text,
                Collections.singletonList(span(0, 4, 0f, null)))).totalWidth();
        float emptyFeatures = layout(new CgStyledText(text,
                Collections.singletonList(span(0, 4, 0f, Collections.emptyList())))).totalWidth();
        assertEquals(plain, nullFeatures, 0.001f);
        assertEquals(plain, emptyFeatures, 0.001f);
    }
}
