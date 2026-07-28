package com.crystalgraphics.text.render;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Guards the sort key's ordering contract.
 *
 * <p>These are cheap tests for a thing that failed silently: the key previously placed {@code kind}
 * in bit 63, which made every decoration key negative under the signed
 * {@link Arrays#sort(long[], int, int)} the renderer uses. Decorations therefore sorted ahead of
 * all glyphs instead of inside their own batch, doubling material transitions whenever both spanned
 * more than one batch — invisible in any scene without decorations, and invisible to the compiler.
 */
public class CgTextSortKeyTest {

    private static long key(boolean df, int texture, float pxRange, boolean decoration, int index) {
        return CgTextSortKey.of(df, texture, pxRange, decoration, index);
    }

    /** The whole layout assumes unsigned ordering; the renderer sorts signed. Bit 63 must be clear. */
    @Test
    public void keysAreNeverNegative() {
        long[] keys = {
                key(false, 0, 0f, false, 0),
                key(true, CgTextSortKey.MAX_TEXTURE_ID, Float.MAX_VALUE, true, CgTextSortKey.MAX_LOCAL_INDEX),
                key(true, 7, 6f, true, 1234),
        };
        for (long k : keys) {
            assertTrue("key must be non-negative so the signed sort matches the intended order: "
                    + CgTextSortKey.describe(k), k >= 0);
        }
    }

    /**
     * The regression that motivated the rewrite: a decoration sharing a glyph's atlas must sort
     * adjacent to it, not ahead of every glyph in the draw.
     */
    @Test
    public void decorationSortsInsideItsOwnBatchNotAheadOfEverything() {
        long glyphA = key(true, 10, 6f, false, 0);
        long decorA = key(true, 10, 6f, true, 0);
        long glyphB = key(true, 20, 6f, false, 0);
        long decorB = key(true, 20, 6f, true, 0);

        long[] sorted = {decorB, glyphB, decorA, glyphA};
        Arrays.sort(sorted);

        // Expect A's pair together, then B's pair — not "all decorations, then all glyphs".
        assertEquals("glyph A first", glyphA, sorted[0]);
        assertEquals("its decoration adjacent", decorA, sorted[1]);
        assertEquals("then glyph B", glyphB, sorted[2]);
        assertEquals("then its decoration", decorB, sorted[3]);

        int transitions = 0;
        long current = -1L;
        for (long k : sorted) {
            if (CgTextSortKey.batchOf(k) != current) {
                transitions++;
                current = CgTextSortKey.batchOf(k);
            }
        }
        assertEquals("two atlases means two transitions, regardless of decorations", 2, transitions);
    }

    /** Batch identity must ignore kind and index, and must not ignore mode/texture/pxRange. */
    @Test
    public void batchIdentityCoversExactlyTheTransitionFields() {
        long base = key(true, 10, 6f, false, 5);
        assertEquals("index must not affect batch", CgTextSortKey.batchOf(base),
                CgTextSortKey.batchOf(key(true, 10, 6f, false, 900)));
        assertEquals("kind must not affect batch", CgTextSortKey.batchOf(base),
                CgTextSortKey.batchOf(key(true, 10, 6f, true, 5)));

        assertTrue("texture must affect batch",
                CgTextSortKey.batchOf(base) != CgTextSortKey.batchOf(key(true, 11, 6f, false, 5)));
        assertTrue("mode must affect batch",
                CgTextSortKey.batchOf(base) != CgTextSortKey.batchOf(key(false, 10, 6f, false, 5)));
        assertTrue("pxRange must affect batch",
                CgTextSortKey.batchOf(base) != CgTextSortKey.batchOf(key(true, 10, 2f, false, 5)));
    }

    /** Sorting must group by mode first, then texture — the coarsest-to-finest contract. */
    @Test
    public void ordersByModeThenTexture() {
        long bitmapHighTexture = key(false, 999, 0f, false, 0);
        long dfLowTexture = key(true, 1, 6f, false, 0);
        long[] sorted = {dfLowTexture, bitmapHighTexture};
        Arrays.sort(sorted);
        assertTrue("bitmap sorts before distance field regardless of texture id",
                sorted[0] == bitmapHighTexture);
    }

    @Test
    public void roundTripsIndexAndKind() {
        long g = key(false, 3, 0f, false, 65535);
        assertEquals(65535, CgTextSortKey.localIndexOf(g));
        assertFalse(CgTextSortKey.isDecoration(g));
        assertFalse(CgTextSortKey.isDistanceField(g));

        long d = key(true, 3, 6f, true, 0);
        assertEquals(0, CgTextSortKey.localIndexOf(d));
        assertTrue(CgTextSortKey.isDecoration(d));
        assertTrue(CgTextSortKey.isDistanceField(d));
    }

    /** Aliasing two entries onto one key corrupts the picture, so overflow must throw. */
    @Test
    public void rejectsOutOfRangeRatherThanAliasing() {
        try {
            key(false, 1, 0f, false, CgTextSortKey.MAX_LOCAL_INDEX + 1);
            fail("expected IllegalArgumentException for an index that would wrap");
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            key(false, CgTextSortKey.MAX_TEXTURE_ID + 1, 0f, false, 0);
            fail("expected IllegalArgumentException for a texture id that would wrap");
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            key(false, 1, Float.NaN, false, 0);
            fail("expected IllegalArgumentException for NaN pxRange, whose bits are not ordered");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    /** pxRange ordering relies on non-negative floats having monotonic bit patterns. */
    @Test
    public void largerPxRangeSortsAfterSmaller() {
        long small = key(true, 5, 2f, false, 0);
        long large = key(true, 5, 12f, false, 0);
        assertTrue("pxRange must order monotonically", small < large);
    }

    /**
     * Field-independence: max out each field in turn and verify nothing bleeds into a neighbour.
     *
     * <p>This replaces a static-block arithmetic check that summed the field widths and compared
     * against 64. That check was dead code whenever the layout was correct — all the operands are
     * compile-time constants — and it could not catch the more likely mistake anyway: a wrong SHIFT
     * with correct widths still sums to 64 while silently overlapping two fields. Packing real
     * values and reading them back catches both.
     */
    @Test
    public void fieldsDoNotOverlap() {
        long maxIndex = key(false, 0, 0f, false, CgTextSortKey.MAX_LOCAL_INDEX);
        assertEquals("index round-trips at max", CgTextSortKey.MAX_LOCAL_INDEX,
                CgTextSortKey.localIndexOf(maxIndex));
        assertFalse("index must not bleed into kind", CgTextSortKey.isDecoration(maxIndex));
        assertFalse("index must not bleed into mode", CgTextSortKey.isDistanceField(maxIndex));
        assertEquals("index must not bleed into the batch fields", 0L, CgTextSortKey.batchOf(maxIndex));

        long maxTexture = key(false, CgTextSortKey.MAX_TEXTURE_ID, 0f, false, 0);
        assertEquals("texture must not bleed into index", 0, CgTextSortKey.localIndexOf(maxTexture));
        assertFalse("texture must not bleed into kind", CgTextSortKey.isDecoration(maxTexture));
        assertFalse("texture must not bleed into mode", CgTextSortKey.isDistanceField(maxTexture));
        assertTrue("texture must not reach the sign bit", maxTexture >= 0);

        long maxPxRange = key(false, 0, Float.MAX_VALUE, false, 0);
        assertEquals("pxRange must not bleed into index", 0, CgTextSortKey.localIndexOf(maxPxRange));
        assertFalse("pxRange must not bleed into kind", CgTextSortKey.isDecoration(maxPxRange));
        assertFalse("pxRange must not bleed into mode", CgTextSortKey.isDistanceField(maxPxRange));
        assertTrue("pxRange must not reach the sign bit", maxPxRange >= 0);

        long decoration = key(false, 0, 0f, true, 0);
        assertTrue(CgTextSortKey.isDecoration(decoration));
        assertEquals("kind must not bleed into index", 0, CgTextSortKey.localIndexOf(decoration));
        assertEquals("kind must not bleed into the batch fields", 0L, CgTextSortKey.batchOf(decoration));

        long everythingAtMax = key(true, CgTextSortKey.MAX_TEXTURE_ID, Float.MAX_VALUE, true,
                CgTextSortKey.MAX_LOCAL_INDEX);
        assertTrue("every field at max must still leave bit 63 clear: "
                + CgTextSortKey.describe(everythingAtMax), everythingAtMax >= 0);
        assertEquals(CgTextSortKey.MAX_LOCAL_INDEX, CgTextSortKey.localIndexOf(everythingAtMax));
        assertTrue(CgTextSortKey.isDecoration(everythingAtMax));
        assertTrue(CgTextSortKey.isDistanceField(everythingAtMax));
    }
}
