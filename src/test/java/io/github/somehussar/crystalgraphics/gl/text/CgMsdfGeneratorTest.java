package io.github.somehussar.crystalgraphics.gl.text;

import io.github.somehussar.crystalgraphics.api.font.CgAtlasRegion;
import io.github.somehussar.crystalgraphics.api.font.CgFontKey;
import io.github.somehussar.crystalgraphics.api.font.CgFontStyle;
import io.github.somehussar.crystalgraphics.api.font.CgGlyphKey;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for {@link CgMsdfGenerator} — per-frame budget enforcement,
 * cell-size selection, and complexity heuristic.
 *
 * <p>Budget and cell-size tests are pure Java (no native library needed).
 * The {@code shouldUseMsdf} test constructs shapes only if the native
 * library is available; otherwise it is skipped.</p>
 */
public class CgMsdfGeneratorTest {

    private static final CgFontKey FONT_32 =
            new CgFontKey("test.ttf", CgFontStyle.REGULAR, 32);
    private static final CgFontKey FONT_48 =
            new CgFontKey("test.ttf", CgFontStyle.REGULAR, 48);
    private static final CgFontKey FONT_64 =
            new CgFontKey("test.ttf", CgFontStyle.REGULAR, 64);

    @After
    public void cleanUp() {
        CgGlyphAtlas.freeAll();
    }

    // ── Per-frame budget ───────────────────────────────────────────────

    @Test
    public void testBudget_firstFourSucceed_fifthReturnsNull() {
        CgMsdfGenerator gen = new CgMsdfGenerator();
        assertEquals(0, gen.getGeneratedThisFrame());

        // Simulate 4 successful generations
        for (int i = 0; i < CgMsdfGenerator.MAX_PER_FRAME; i++) {
            gen.simulateGeneration();
        }
        assertEquals(CgMsdfGenerator.MAX_PER_FRAME, gen.getGeneratedThisFrame());

        // 5th call to generateGlyph must return null due to budget
        CgGlyphAtlas atlas = CgGlyphAtlas.createForTest(256, 256, CgGlyphAtlas.Type.MSDF);
        CgGlyphKey key = new CgGlyphKey(FONT_32, 100, true);

        // generateGlyph checks budget before any native call
        CgAtlasRegion result = gen.queueOrGenerate(key, null, atlas, 0);
        assertNull("5th glyph should be rejected by budget", result);
    }

    @Test
    public void testBudget_tickFrameResetsBudget() {
        CgMsdfGenerator gen = new CgMsdfGenerator();

        for (int i = 0; i < CgMsdfGenerator.MAX_PER_FRAME; i++) {
            gen.simulateGeneration();
        }
        assertEquals(CgMsdfGenerator.MAX_PER_FRAME, gen.getGeneratedThisFrame());

        gen.tickFrame();
        assertEquals(0, gen.getGeneratedThisFrame());
    }

    @Test
    public void testBudget_multipleFrameCycles() {
        CgMsdfGenerator gen = new CgMsdfGenerator();

        // Frame 1: exhaust budget
        for (int i = 0; i < CgMsdfGenerator.MAX_PER_FRAME; i++) {
            gen.simulateGeneration();
        }
        assertEquals(CgMsdfGenerator.MAX_PER_FRAME, gen.getGeneratedThisFrame());

        // Frame 2: reset and generate again
        gen.tickFrame();
        assertEquals(0, gen.getGeneratedThisFrame());
        gen.simulateGeneration();
        assertEquals(1, gen.getGeneratedThisFrame());

        // Frame 3: reset again
        gen.tickFrame();
        assertEquals(0, gen.getGeneratedThisFrame());
    }

    @Test
    public void testBudget_maxPerFrameIsFour() {
        assertEquals(4, CgMsdfGenerator.MAX_PER_FRAME);
    }

    // ── Cell size selection ────────────────────────────────────────────

    @Test
    public void testCellSize_32px() {
        assertEquals(32, CgMsdfGenerator.cellSizeForFontPx(32));
    }

    @Test
    public void testCellSize_smallFonts_return32() {
        assertEquals(32, CgMsdfGenerator.cellSizeForFontPx(16));
        assertEquals(32, CgMsdfGenerator.cellSizeForFontPx(24));
        assertEquals(32, CgMsdfGenerator.cellSizeForFontPx(31));
    }

    @Test
    public void testCellSize_48px() {
        assertEquals(48, CgMsdfGenerator.cellSizeForFontPx(48));
        assertEquals(48, CgMsdfGenerator.cellSizeForFontPx(50));
        assertEquals(48, CgMsdfGenerator.cellSizeForFontPx(63));
    }

    @Test
    public void testCellSize_64pxAndAbove() {
        assertEquals(64, CgMsdfGenerator.cellSizeForFontPx(64));
        assertEquals(64, CgMsdfGenerator.cellSizeForFontPx(65));
        assertEquals(64, CgMsdfGenerator.cellSizeForFontPx(128));
    }

    // ── Complexity heuristic ───────────────────────────────────────────

    @Test
    public void testComplexityThreshold_value() {
        assertEquals(24, CgMsdfGenerator.COMPLEXITY_EDGE_THRESHOLD);
    }

    @Test
    public void testSimpleMsdfMinPx() {
        assertEquals(32, CgMsdfGenerator.SIMPLE_MSDF_MIN_PX);
    }

    @Test
    public void testComplexMsdfMinPx() {
        assertEquals(48, CgMsdfGenerator.COMPLEX_MSDF_MIN_PX);
    }

    // ── PX_RANGE constant ──────────────────────────────────────────────

    @Test
    public void testPxRange_isFour() {
        assertEquals(4.0f, CgMsdfGenerator.PX_RANGE, 0.001f);
    }

    // ── queueOrGenerate null-safety when budget exhausted ─────────────

    @Test
    public void testGenerateGlyph_budgetExhausted_returnsNull_withoutCallingNative() {
        CgMsdfGenerator gen = new CgMsdfGenerator();

        // Exhaust budget
        for (int i = 0; i < CgMsdfGenerator.MAX_PER_FRAME; i++) {
            gen.simulateGeneration();
        }

        CgGlyphAtlas atlas = CgGlyphAtlas.createForTest(128, 128, CgGlyphAtlas.Type.MSDF);
        CgGlyphKey key = new CgGlyphKey(FONT_48, 65, true);

        CgAtlasRegion result = gen.queueOrGenerate(key, null, atlas, 0);
        assertNull(result);
    }

    @Test
    public void testGenerateGlyph_afterTickFrame_budgetAvailable() {
        CgMsdfGenerator gen = new CgMsdfGenerator();

        // Exhaust budget
        for (int i = 0; i < CgMsdfGenerator.MAX_PER_FRAME; i++) {
            gen.simulateGeneration();
        }

        // Budget exhausted
        assertEquals(CgMsdfGenerator.MAX_PER_FRAME, gen.getGeneratedThisFrame());

        // Reset
        gen.tickFrame();
        assertEquals(0, gen.getGeneratedThisFrame());

        // Budget is now available (would need native font to actually generate)
    }
}
