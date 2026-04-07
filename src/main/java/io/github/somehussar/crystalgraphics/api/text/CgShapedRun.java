package io.github.somehussar.crystalgraphics.api.text;

import io.github.somehussar.crystalgraphics.api.font.CgFontKey;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

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
 * <h3>Coordinate units (Logical Layout Space)</h3>
 * <p>All advance and offset values are in <strong>logical layout pixels</strong>,
 * converted from HarfBuzz's 26.6 fixed-point format by dividing by 64. These
 * values are part of the logical layout space in the three-space model and must
 * never be mutated by draw-time transforms such as UI scale or PoseStack.</p>
 *
 * <h3>Public Text API</h3>
 * <p>This type lives in {@code api/text} because it is publicly exposed through
 * {@link CgTextLayout#getLines()} — any consumer of a layout result necessarily
 * works with shaped runs. The internal shaping machinery produces this type but
 * does not own it.</p>
 *
 * <h3>Known API-boundary leak: source-context fields</h3>
 * <p>The fields {@link #sourceText}, {@link #sourceStart}, and {@link #sourceEnd}
 * carry the original input text and character range that produced this run. This
 * is internal pipeline state — the shaped result (glyphs, advances, offsets) is
 * the public contract, not the source text that went into it.</p>
 *
 * <p><strong>Why they are exposed today:</strong> the intra-run word wrapper needs
 * to re-shape sub-segments of a run when breaking a long word. This requires
 * access to the original source text and offsets so it can call HarfBuzz again
 * on a substring. Without these fields on the run itself, the wrapper would need
 * an out-of-band lookup table mapping runs back to their source, which adds
 * complexity for a single consumer.</p>
 *
 * <p><strong>Future direction:</strong> these fields should migrate to an internal
 * pipeline-only wrapper or be passed through a separate re-shaping context, leaving
 * this class as a pure shaped-result DTO. The {@link #hasSourceContext()} method and
 * the backward-compatible constructor (which sets source fields to {@code null}/0/0)
 * already anticipate this separation — callers that do not need re-shaping should
 * use the shorter constructor and not depend on source fields.</p>
 *
 * @see CgTextLayout
 */
@Getter
@EqualsAndHashCode
@ToString
public final class CgShapedRun {

    /** Font this run was shaped with. */
    private final CgFontKey fontKey;

    /** {@code true} if this run is right-to-left. */
    private final boolean rtl;

    /**
     * HarfBuzz glyph IDs (= FreeType glyph indices).
     * NOT Unicode codepoints.
     */
    private final int[] glyphIds;

    /**
     * HarfBuzz cluster IDs (byte offsets into UTF-8 source text).
     * Used for cluster/selection mapping only.
     */
    private final int[] clusterIds;

    /** Per-glyph horizontal advance in logical layout pixels. */
    private final float[] advancesX;

    /** Per-glyph horizontal offset in logical layout pixels. */
    private final float[] offsetsX;

    /** Per-glyph vertical offset in logical layout pixels. */
    private final float[] offsetsY;

    /** Sum of all {@code advancesX} values (total run width in logical layout pixels). */
    private final float totalAdvance;

    /**
     * Original source text used for shaping this run.
     *
     * <p><strong>API-boundary note:</strong> this is an internal pipeline concern
     * exposed for intra-run word wrapping (re-shaping sub-segments via HarfBuzz).
     * Excluded from equals/hashCode because it does not define the shaped result.
     * External callers should not depend on this value; use
     * {@link #hasSourceContext()} to check availability.</p>
     */
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private final String sourceText;

    /**
     * Start index (inclusive) into {@link #sourceText} for this run's segment.
     *
     * <p><strong>API-boundary note:</strong> internal pipeline context; see
     * {@link #sourceText} and class-level Javadoc for rationale.</p>
     */
    @EqualsAndHashCode.Exclude
    private final int sourceStart;

    /**
     * End index (exclusive) into {@link #sourceText} for this run's segment.
     *
     * <p><strong>API-boundary note:</strong> internal pipeline context; see
     * {@link #sourceText} and class-level Javadoc for rationale.</p>
     */
    @EqualsAndHashCode.Exclude
    private final int sourceEnd;

    /**
     * Full constructor with source-text context for cluster-aware re-shaping.
     */
    public CgShapedRun(CgFontKey fontKey, boolean rtl,
                       int[] glyphIds, int[] clusterIds,
                       float[] advancesX, float[] offsetsX, float[] offsetsY,
                       float totalAdvance,
                       String sourceText, int sourceStart, int sourceEnd) {
        this.fontKey = fontKey;
        this.rtl = rtl;
        this.glyphIds = glyphIds;
        this.clusterIds = clusterIds;
        this.advancesX = advancesX;
        this.offsetsX = offsetsX;
        this.offsetsY = offsetsY;
        this.totalAdvance = totalAdvance;
        this.sourceText = sourceText;
        this.sourceStart = sourceStart;
        this.sourceEnd = sourceEnd;
    }

    /**
     * Backward-compatible constructor without source-text context.
     *
     * <p>Source fields are set to {@code null}/0/0. Runs created this way cannot
     * be split by the intra-run word wrapper, but remain valid for all other uses.</p>
     */
    public CgShapedRun(CgFontKey fontKey, boolean rtl,
                       int[] glyphIds, int[] clusterIds,
                       float[] advancesX, float[] offsetsX, float[] offsetsY,
                       float totalAdvance) {
        this(fontKey, rtl, glyphIds, clusterIds, advancesX, offsetsX, offsetsY,
                totalAdvance, null, 0, 0);
    }

    /**
     * Returns {@code true} if this run carries source-text context sufficient
     * for cluster-aware re-shaping (e.g., intra-run word wrapping).
     */
    public boolean hasSourceContext() {
        return sourceText != null && sourceEnd > sourceStart;
    }
}
