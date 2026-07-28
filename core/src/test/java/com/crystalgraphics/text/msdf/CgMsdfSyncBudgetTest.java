package com.crystalgraphics.text.msdf;

import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.font.CgFontStyle;
import com.crystalgraphics.api.font.CgGlyphKey;
import com.crystalgraphics.msdfgen.FreeTypeMSDFIntegration;
import com.crystalgraphics.text.cache.CgGlyphGenerationResult;
import com.crystalgraphics.text.cache.CgMsdfAtlasKey;
import org.junit.Test;

import java.io.File;
import java.util.Locale;

import static org.junit.Assert.assertTrue;

/**
 * Guards the per-frame ceiling on <em>synchronous</em> MSDF generation.
 *
 * <p>This limit exists to stop glyph generation from spiking a frame, and for a long time it was a
 * count of four glyphs — which only prevents spikes if glyphs cost about the same. They do not.
 * Measured, one glyph runs from well under a millisecond for simple Latin to 32 ms for dense CJK,
 * so the old limit permitted roughly 128 ms of work in a single frame: an eight-frame stall at
 * 60 Hz, caused by the very thing meant to prevent it.
 *
 * <p>The two properties below are what make the replacement correct, and they pull against each
 * other — which is why both are asserted. A budget that only capped time could refuse every glyph
 * forever on a font whose cheapest glyph exceeds the budget, leaving text stuck on the bitmap
 * fallback permanently. A budget that only guaranteed progress would not bound anything.
 */
public class CgMsdfSyncBudgetTest {

    private static final String CJK_FONT = "src/main/resources/assets/crystalgraphics/MPLUS1p-Regular.ttf";

    /**
     * The budget is 2 ms, but one glyph is always allowed to start, so a frame can overshoot by at
     * most a single glyph. This bounds that: budget plus the worst measured glyph plus slack for a
     * cold JIT and a loaded CI machine. The point is to catch a return to "four expensive glyphs
     * per frame", not to police a few milliseconds.
     */
    private static final long MAX_ACCEPTABLE_FRAME_MS = 60;

    @Test
    public void oneFrameCannotSpendMoreThanBudgetPlusASingleGlyph() {
        File fontFile = new File(CJK_FONT);
        if (!fontFile.isFile()) {
            System.out.println("[msdf-budget] CJK font missing, skipping");
            return;
        }

        CgFont font = CgFont.load(fontFile.getPath(), CgFontStyle.REGULAR, 80);
        try {
            FreeTypeMSDFIntegration.Font msdfFont = font.getMsdfFont();
            CgMsdfAtlasKey atlasKey = new CgMsdfAtlasKey(font.getKey(), CgMsdfAtlasConfig.defaultConfig());
            CgMsdfGenerator generator = new CgMsdfGenerator();

            // Densest glyphs available, so each generation is as expensive as this font gets. If the
            // budget holds here it holds everywhere.
            int[] dense = CgMsdfQualityProbe.selectDenseGlyphs(font, 64, 0.9f, 1.0f, 4000);
            assertTrue("no glyphs selected", dense.length > 0);

            long worstFrameNanos = 0;
            int totalGenerated = 0;
            int frames = 4;
            int cursor = 0;

            for (int frame = 0; frame < frames; frame++) {
                generator.tickFrame();
                long frameStart = System.nanoTime();
                int generatedThisFrame = 0;

                // Ask for far more than the budget can allow; the generator must start refusing.
                for (int attempt = 0; attempt < 16 && cursor < dense.length; attempt++, cursor++) {
                    CgGlyphGenerationResult result = generator.prepareGlyphWithinBudget(
                            new CgGlyphKey(font.getKey(), dense[cursor], true),
                            font.getKey(), msdfFont, atlasKey);
                    if (result == null) break;
                    generatedThisFrame++;
                }

                long frameNanos = System.nanoTime() - frameStart;
                worstFrameNanos = Math.max(worstFrameNanos, frameNanos);
                totalGenerated += generatedThisFrame;

                System.out.printf(Locale.ROOT, "[msdf-budget] frame %d: %d glyphs in %.2f ms%n",
                        frame, generatedThisFrame, frameNanos / 1_000_000.0);

                // Progress guarantee: a fresh frame has spent nothing, so the first request must be
                // served no matter how expensive it is. Without this the fallback would be permanent.
                assertTrue("frame " + frame + " generated nothing; sync generation can never progress",
                        generatedThisFrame >= 1);
            }

            double worstMs = worstFrameNanos / 1_000_000.0;
            System.out.printf(Locale.ROOT,
                    "[msdf-budget] worst frame %.2f ms, %d glyphs over %d frames%n",
                    worstMs, totalGenerated, frames);

            assertTrue("a single frame spent " + String.format(Locale.ROOT, "%.1f", worstMs)
                            + " ms on sync MSDF generation, over the " + MAX_ACCEPTABLE_FRAME_MS
                            + " ms ceiling — the per-frame budget is not bounding frame cost",
                    worstMs <= MAX_ACCEPTABLE_FRAME_MS);
        } finally {
            font.dispose();
        }
    }
}
