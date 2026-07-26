package com.crystalgraphics.api.text;

import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.font.CgFontKey;
import com.crystalgraphics.api.font.CgFontStyle;
import com.crystalgraphics.text.layout.CgLineBreaker;
import com.crystalgraphics.text.layout.CgReshapeContext;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.util.List;
import java.util.Set;

/**
 * Immutable result of shaping a single directional run of text.
 *
 * <p>A shaped run contains the glyph IDs, cluster IDs, advances, and offsets
 * produced by HarfBuzz for a contiguous run of text with a single direction
 * (LTR or RTL). {@code glyphIds}, {@code clusterIds}, {@code advancesX},
 * {@code offsetsX}, and {@code offsetsY} all have the same length — one
 * entry per shaped glyph.</p>
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
 * <h3>Source position, not source text</h3>
 * <p>{@link #sourceStart}/{@link #sourceEnd} record this run's character range
 * within its paragraph — needed by {@link CgLineBreaker}
 * to re-shape sub-ranges when splitting an overflowing run at a word or grapheme
 * boundary. The paragraph text itself is supplied separately, once per paragraph,
 * via {@link CgReshapeContext} — this run does not
 * carry its own copy of the source string.</p>
 *
 * <h3>Resolved font</h3>
 * <p>{@link #resolvedFont} is the concrete {@link CgFont} this run was shaped
 * with, as opposed to {@link #fontKey} which only identifies it. Carrying the
 * resolved handle directly on the run lets later pipeline stages (glyph baking,
 * rendering) skip a second font-table lookup by key. Excluded from equality —
 * it is fully determined by {@link #fontKey}.</p>
 *
 * <h3>Public Text API</h3>
 * <p>This type lives in {@code api/text} because it is publicly exposed through
 * {@link CgTextLayout#lines()} — any consumer of a layout result necessarily
 * works with shaped runs. The internal shaping machinery produces this type but
 * does not own it.</p>
 *
 * <h3>Rich-text span fields</h3>
 * <p>{@link #argbColor}, {@link #decorations}, {@link #fontFeatures}, and
 * {@link #baselineShift} mirror {@link CgStyleSpan}'s fields — copied onto whichever
 * run resulted from shaping that span's sub-range. Landed here ahead of the splitting
 * logic that actually populates them: nothing constructs a run with non-default values
 * for these fields yet (see the BiDi-∩-style-span splitting work that does).</p>
 *
 * @see CgTextLayout
 * @see CgStyleSpan
 */
@Getter
@EqualsAndHashCode
@ToString
public final class CgShapedRun {

    /** Font this run was shaped with. */
    private final CgFontKey fontKey;

    /** Resolved font handle for {@link #fontKey}; see class Javadoc. */
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private final CgFont resolvedFont;

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
     * Start index (inclusive) into the paragraph text for this run's segment.
     * See {@link com.crystalgraphics.text.layout.CgReshapeContext}.
     */
    @EqualsAndHashCode.Exclude
    private final int sourceStart;

    /**
     * End index (exclusive) into the paragraph text for this run's segment.
     * See {@link com.crystalgraphics.text.layout.CgReshapeContext}.
     */
    @EqualsAndHashCode.Exclude
    private final int sourceEnd;

    /** Override color, {@code 0} = inherit the draw's default color. See {@link CgStyleSpan#argbColor()}. */
    private final int argbColor;

    /** See {@link CgStyleSpan#decorations()}. */
    private final Set<CgTextDecoration> decorations;

    /** OpenType features to enable for this run's shaping. See {@link CgStyleSpan#fontFeatures()}. */
    private final List<CgFontFeature> fontFeatures;

    /** Vertical baseline offset in logical pixels; {@code 0} = none. See {@link CgStyleSpan#baselineShift()}. */
    private final float baselineShift;

    /**
     * {@code true} when this run requested {@link CgFontStyle#BOLD}/{@link CgFontStyle#BOLD_ITALIC}
     * but {@link #resolvedFont}'s family had no distinct bold face to shape against (see
     * {@code CgFontFamilyGroup#resolve} falling back to {@link CgFontStyle#REGULAR}) — a signal
     * to the rasterizer to synthesize bold via {@code FTFace.outlineEmbolden}/an equivalent MSDF
     * shape-dilation pass, rather than silently rendering as plain regular weight.
     */
    private final boolean syntheticBold;

    /** Same as {@link #syntheticBold}, for {@link CgFontStyle#ITALIC}/{@link CgFontStyle#BOLD_ITALIC}. */
    private final boolean syntheticItalic;

    /**
     * Per-glyph "is a line break immediately before this glyph provably safe" flag, from
     * HarfBuzz's {@code HB_GLYPH_FLAG_UNSAFE_TO_BREAK} (inverted — {@code true} here means
     * safe). {@code null} means unknown (e.g. a hand-built fixture, or a shaper that didn't
     * populate it) — callers must treat that conservatively as "always re-shape", the same
     * as before this field existed. See {@link CgLineBreaker#findBestFittingBoundary}, the
     * only consumer: a {@code true} entry lets it slice this run's existing glyph/advance
     * data at that boundary instead of calling {@link com.crystalgraphics.text.layout.RunReshaper#reshape}.
     */
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private final boolean[] safeToBreakBefore;

    /** Full constructor including rich-text span fields, the unsafe-to-break fast-path hint,
     * and synthetic bold/italic flags. */
    public CgShapedRun(CgFontKey fontKey, CgFont resolvedFont, boolean rtl,
                        int[] glyphIds, int[] clusterIds,
                        float[] advancesX, float[] offsetsX, float[] offsetsY,
                        float totalAdvance, int sourceStart, int sourceEnd,
                        int argbColor, Set<CgTextDecoration> decorations,
                        List<CgFontFeature> fontFeatures, float baselineShift,
                        boolean[] safeToBreakBefore, boolean syntheticBold, boolean syntheticItalic) {
        if (fontKey == null) {
            throw new IllegalArgumentException("fontKey must not be null");
        }
        this.fontKey = fontKey;
        this.resolvedFont = resolvedFont;
        this.rtl = rtl;
        this.glyphIds = glyphIds;
        this.clusterIds = clusterIds;
        this.advancesX = advancesX;
        this.offsetsX = offsetsX;
        this.offsetsY = offsetsY;
        this.totalAdvance = totalAdvance;
        this.sourceStart = sourceStart;
        this.sourceEnd = sourceEnd;
        this.argbColor = argbColor;
        this.decorations = decorations == null || decorations.isEmpty() ? Set.of() : Set.copyOf(decorations);
        this.fontFeatures = fontFeatures == null ? List.of() : List.copyOf(fontFeatures);
        this.baselineShift = baselineShift;
        this.safeToBreakBefore = safeToBreakBefore;
        this.syntheticBold = syntheticBold;
        this.syntheticItalic = syntheticItalic;
    }

    /** Full constructor including rich-text span fields and the unsafe-to-break fast-path hint;
     * defaults synthetic bold/italic to {@code false}. */
    public CgShapedRun(CgFontKey fontKey, CgFont resolvedFont, boolean rtl,
                        int[] glyphIds, int[] clusterIds,
                        float[] advancesX, float[] offsetsX, float[] offsetsY,
                        float totalAdvance, int sourceStart, int sourceEnd,
                        int argbColor, Set<CgTextDecoration> decorations,
                        List<CgFontFeature> fontFeatures, float baselineShift,
                        boolean[] safeToBreakBefore) {
        this(fontKey, resolvedFont, rtl, glyphIds, clusterIds, advancesX, offsetsX, offsetsY,
                totalAdvance, sourceStart, sourceEnd, argbColor, decorations, fontFeatures, baselineShift,
                safeToBreakBefore, false, false);
    }

    /** Convenience constructor with rich-text span fields but no unsafe-to-break data (defaults to unknown). */
    public CgShapedRun(CgFontKey fontKey, CgFont resolvedFont, boolean rtl,
                        int[] glyphIds, int[] clusterIds,
                        float[] advancesX, float[] offsetsX, float[] offsetsY,
                        float totalAdvance, int sourceStart, int sourceEnd,
                        int argbColor, Set<CgTextDecoration> decorations,
                        List<CgFontFeature> fontFeatures, float baselineShift) {
        this(fontKey, resolvedFont, rtl, glyphIds, clusterIds, advancesX, offsetsX, offsetsY,
                totalAdvance, sourceStart, sourceEnd, argbColor, decorations, fontFeatures, baselineShift, null);
    }

    /**
     * Convenience constructor without rich-text span fields — defaults
     * {@code argbColor} to {@code 0} (inherit), {@code decorations} to empty,
     * {@code fontFeatures} to empty, and {@code baselineShift} to {@code 0}.
     */
    public CgShapedRun(CgFontKey fontKey, CgFont resolvedFont, boolean rtl,
                        int[] glyphIds, int[] clusterIds,
                        float[] advancesX, float[] offsetsX, float[] offsetsY,
                        float totalAdvance, int sourceStart, int sourceEnd) {
        this(fontKey, resolvedFont, rtl, glyphIds, clusterIds, advancesX, offsetsX, offsetsY,
                totalAdvance, sourceStart, sourceEnd,
                0, Set.of(), List.of(), 0f, null);
    }
}
