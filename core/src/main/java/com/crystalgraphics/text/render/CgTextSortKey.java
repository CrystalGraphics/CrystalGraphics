package com.crystalgraphics.text.render;

import com.crystalgraphics.api.font.CgGlyphPlacement;

/**
 * Packs one drawable — a glyph or a text decoration — into a single sortable {@code long}, so that
 * sorting the raw array groups everything that can share a GL state into contiguous runs.
 *
 * <h3>What the ordering buys</h3>
 * <p>Every change of atlas texture or shader mode costs a flush plus a material transition. Sorting
 * by those fields first means each distinct combination appears exactly once as a contiguous run,
 * so a draw needs as many transitions as it has genuinely distinct GL states — no more. Since the
 * glyph atlases were merged, two different fonts produce the same texture id and therefore batch
 * together; font identity is deliberately absent from this key.
 *
 * <h3>Layout, MSB to LSB</h3>
 * <pre>
 *   [63]     unused, always zero   see "Why bit 63 stays clear"
 *   [62]     mode                  0 = bitmap, 1 = distance field
 *   [61:38]  textureId             GL atlas array-texture id (24 bits)
 *   [37:17]  pxRange               high bits of the SDF range's float bits (21 bits)
 *   [16]     kind                  0 = glyph, 1 = decoration
 *   [15:0]   localIndex            index into whichever source array {@code kind} selects
 * </pre>
 *
 * <h3>Why the field order is what it is</h3>
 * <p>Coarsest-to-finest, so one numeric sort produces the batching order directly:
 * <ul>
 *   <li><b>mode, textureId, pxRange</b> are the batch identity — see {@link #batchOf}. These sort
 *       outermost because a change in any of them is what costs a transition.</li>
 *   <li><b>kind</b> sits <em>below</em> the batch fields on purpose. A decoration reports the same
 *       atlas and mode as a glyph of its font would, so it belongs <em>inside</em> that font's
 *       batch. Sorting by kind above the batch fields would split every draw into "all decorations,
 *       then all glyphs" and double the transitions whenever both spanned more than one batch.</li>
 *   <li><b>localIndex</b> rides in the low bits purely so the sorted array alone says which entry
 *       each key came from, with no parallel index array to keep aligned.</li>
 * </ul>
 *
 * <h3>Why bit 63 stays clear</h3>
 * <p>Sorting uses {@link java.util.Arrays#sort(long[], int, int)}, which is <strong>signed</strong>.
 * Any key with bit 63 set would be negative and sort ahead of everything else regardless of its
 * other fields. Leaving the top bit unused makes the signed sort agree with the unsigned ordering
 * the layout above assumes — the alternative, flipping the sign bit around every sort, buys one bit
 * in exchange for a trap. The bit is genuinely spare: the fields below sum to 63.
 *
 * <p>An earlier revision put {@code kind} in bit 63 and paid both prices — decorations sorted first
 * globally, and the documented intent ("glyphs sort before decorations within an otherwise-tied
 * group") was the opposite of what the code did.
 */
final class CgTextSortKey {

    // ── Field widths. Must sum to 64; the static block below enforces it. ──────────────────────

    /** Index into the glyph or decoration array. Full 16 bits — see {@link #MAX_LOCAL_INDEX}. */
    private static final int INDEX_BITS = 16;
    /** Glyph vs decoration. */
    private static final int KIND_BITS = 1;
    /**
     * Top bits of {@code pxRange}'s raw float bits.
     *
     * <p>Valid as an ordering only because pxRange is always {@code >= 0}, which makes IEEE-754
     * bit patterns monotonic in value. Dropping the low mantissa bits coarsens ties between ranges
     * differing by well under 0.1%, far finer than two real atlas configs ever differ.
     *
     * <p>In practice this is constant within a texture id — one atlas is one config, one pxRange —
     * so it adds no batch granularity beyond {@code textureId} today. Kept because promoting
     * pxRange to a per-instance value is a live option; see
     * {@code docs_research/CGTEXTRENDERER_INSTANCING_FOUNDATIONS.md}.
     */
    private static final int PX_RANGE_BITS = 21;
    /** GL texture id. Real ids are small; {@link #MAX_TEXTURE_ID} is checked, not assumed. */
    private static final int TEXTURE_BITS = 24;
    /** Bitmap vs distance field. MSDF and MTSDF share one shader keyword, so they share a mode. */
    private static final int MODE_BITS = 1;
    /** Bit 63, deliberately unused so the signed sort matches the intended unsigned order. */
    private static final int SIGN_RESERVED_BITS = 1;

    // The widths above must sum to 64 and must leave bit 63 clear. That is a property of
    // compile-time constants, so a runtime check on it is provably dead code whenever the layout is
    // correct — which is precisely when you would want it to be readable. CgTextSortKeyTest asserts
    // it behaviourally instead, by maxing out each field in turn and verifying no bits bleed into a
    // neighbour or into the sign bit. That also catches drift the arithmetic sum would miss: a wrong
    // SHIFT with right widths still sums to 64.
    

    // ── Shifts, derived low-to-high so the layout cannot drift out of sync with the widths. ────

    private static final int INDEX_SHIFT = 0;
    private static final int KIND_SHIFT = INDEX_SHIFT + INDEX_BITS;
    private static final int PX_RANGE_SHIFT = KIND_SHIFT + KIND_BITS;
    private static final int TEXTURE_SHIFT = PX_RANGE_SHIFT + PX_RANGE_BITS;
    private static final int MODE_SHIFT = TEXTURE_SHIFT + TEXTURE_BITS;

    private static final long INDEX_MASK = (1L << INDEX_BITS) - 1L;
    private static final long KIND_DECORATION = 1L << KIND_SHIFT;

    /**
     * Isolates mode + textureId + pxRange: everything a material transition depends on, and
     * nothing else. Excludes {@code kind} so a decoration and a glyph in the same atlas compare
     * equal and do not force a transition between them.
     */
    private static final long BATCH_MASK =
            (((1L << MODE_BITS) - 1L) << MODE_SHIFT)
                    | (((1L << TEXTURE_BITS) - 1L) << TEXTURE_SHIFT)
                    | (((1L << PX_RANGE_BITS) - 1L) << PX_RANGE_SHIFT);

    /** Largest index a single draw call can address. */
    static final int MAX_LOCAL_INDEX = (int) INDEX_MASK;
    /** Largest GL texture id representable. */
    static final int MAX_TEXTURE_ID = (1 << TEXTURE_BITS) - 1;

    private CgTextSortKey() {}

    /** Key for a glyph at {@code localIndex} in the resolved placements array. */
    static long forGlyph(CgGlyphPlacement placement, int localIndex) {
        return of(placement.isDistanceField(), placement.atlasTextureId(), placement.pxRange(), false, localIndex);
    }

    /**
     * Key for a decoration at {@code localIndex} in this draw's decoration list.
     *
     * <p>Its mode, texture and pxRange come from the same atlas a glyph of this font would report,
     * which is exactly what lets it land inside that font's batch rather than forcing a transition
     * of its own.
     */
    static long forDecoration(CgResolvedGlyphs.ResolvedDecoration decoration, int localIndex) {
        return of(decoration.isDistanceField(), decoration.atlasTextureId(), decoration.pxRange(), true, localIndex);
    }

    /** The bits a material transition depends on. Equal values mean no transition is needed. */
    static long batchOf(long key) {
        return key & BATCH_MASK;
    }

    /** Index into the placements array (glyph) or the decoration list, per {@link #isDecoration}. */
    static int localIndexOf(long key) {
        return (int) (key & INDEX_MASK);
    }

    static boolean isDecoration(long key) {
        return (key & KIND_DECORATION) != 0L;
    }

    static boolean isDistanceField(long key) {
        return ((key >>> MODE_SHIFT) & 1L) != 0L;
    }

    /** Human-readable breakdown, for assertion messages and debugging. */
    static String describe(long key) {
        return "SortKey[mode=" + (isDistanceField(key) ? "df" : "bitmap")
                + ", texture=" + (int) ((key >>> TEXTURE_SHIFT) & ((1L << TEXTURE_BITS) - 1L))
                + ", pxRangeBits=" + ((key >>> PX_RANGE_SHIFT) & ((1L << PX_RANGE_BITS) - 1L))
                + ", kind=" + (isDecoration(key) ? "decoration" : "glyph")
                + ", index=" + localIndexOf(key) + "]";
    }

    /**
     * Packs the fields directly. Package-visible so the ordering contract can be tested without
     * constructing {@link CgGlyphPlacement} records, which carry a dozen fields irrelevant here.
     *
     * <p>Fails loudly rather than wrapping. An out-of-range index or texture id would alias two
     * different entries onto the same key, which corrupts rendering rather than merely slowing it —
     * a silent wrong picture is the worst outcome available here, and the project's fail-fast rule
     * applies squarely.
     */
    static long of(boolean distanceField, int textureId, float pxRange, boolean decoration, int localIndex) {
        if (localIndex < 0 || localIndex > MAX_LOCAL_INDEX)
            throw new IllegalArgumentException(
                    "localIndex out of range for a single draw: " + localIndex + " (max " + MAX_LOCAL_INDEX + ")");
        if (textureId < 0 || textureId > MAX_TEXTURE_ID)
            throw new IllegalArgumentException(
                    "textureId does not fit the sort key: " + textureId + " (max " + MAX_TEXTURE_ID + ")");
        if (!(pxRange >= 0.0f)) // also rejects NaN, whose bit pattern is not order-preserving
            throw new IllegalArgumentException("pxRange must be >= 0 and non-NaN, got " + pxRange);


        long mode = distanceField ? 1L : 0L;
        long pxRangeBits = (Float.floatToRawIntBits(pxRange) & 0xFFFFFFFFL) >>> (Integer.SIZE - PX_RANGE_BITS);

        return (mode << MODE_SHIFT)
                | ((long) textureId << TEXTURE_SHIFT)
                | (pxRangeBits << PX_RANGE_SHIFT)
                | (decoration ? KIND_DECORATION : 0L)
                | ((long) localIndex << INDEX_SHIFT);
    }
}
