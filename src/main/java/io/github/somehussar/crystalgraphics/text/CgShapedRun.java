package io.github.somehussar.crystalgraphics.text;

import io.github.somehussar.crystalgraphics.api.font.CgFontKey;
import lombok.Value;

/**
 * Immutable result of shaping a single directional run of text.
 *
 * <p>A shaped run contains the glyph IDs, cluster IDs, advances, and offsets
 * produced by HarfBuzz for a contiguous run of text with a single direction
 * (LTR or RTL). All arrays have the same length — one entry per shaped glyph.</p>
 *
 * <h3>Glyph ID semantics</h3>
 * <p>{@code glyphIds} contains <strong>glyph indices</strong> (from
 * {@code HBGlyphInfo.getCodepoint()}) — NOT Unicode codepoints. After shaping,
 * HarfBuzz repurposes the "codepoint" field to hold the font-specific glyph
 * slot index that maps 1:1 to FreeType's glyph index.</p>
 *
 * <h3>Cluster IDs</h3>
 * <p>{@code clusterIds} contains byte offsets into the original UTF-8 input text,
 * as reported by {@code HBGlyphInfo.getCluster()}. These are used for cursor/
 * selection mapping (back-mapping shaped glyphs to source characters) and are
 * NOT used for atlas lookup.</p>
 *
 * <h3>Coordinate units</h3>
 * <p>All advance and offset values are in <strong>pixels</strong>, converted from
 * HarfBuzz's 26.6 fixed-point format by dividing by 64.</p>
 *
 * @see CgTextShaper
 * @see CgLineBreaker
 */
@Value
public class CgShapedRun {

    /** Font this run was shaped with. */
    CgFontKey fontKey;

    /** {@code true} if this run is right-to-left. */
    boolean rtl;

    /**
     * HarfBuzz glyph IDs (= FreeType glyph indices).
     * NOT Unicode codepoints.
     */
    int[] glyphIds;

    /**
     * HarfBuzz cluster IDs (byte offsets into UTF-8 source text).
     * Used for cluster/selection mapping only.
     */
    int[] clusterIds;

    /** Per-glyph horizontal advance in pixels. */
    float[] advancesX;

    /** Per-glyph horizontal offset in pixels. */
    float[] offsetsX;

    /** Per-glyph vertical offset in pixels. */
    float[] offsetsY;

    /** Sum of all {@code advancesX} values (total run width in pixels). */
    float totalAdvance;
}
