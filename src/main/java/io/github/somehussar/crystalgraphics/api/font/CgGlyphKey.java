package io.github.somehussar.crystalgraphics.api.font;

import lombok.Value;

/**
 * Immutable key identifying a specific glyph variant within a font.
 *
 * <p>A {@code CgGlyphKey} combines a {@link CgFontKey} with a glyph index and
 * rendering mode (bitmap vs. MSDF) to uniquely identify a glyph slot in the
 * texture atlas.</p>
 *
 * <h3>Glyph ID semantics</h3>
 * <p>{@code glyphId} is the <strong>shaped glyph index</strong> from HarfBuzz
 * ({@code HBGlyphInfo.getCodepoint()}), which maps 1:1 to FreeType's glyph index.
 * It is <strong>NOT</strong> a Unicode codepoint. Unicode codepoints are only used
 * for cluster mapping (back-mapping to the source string) and are not stored in
 * this key.</p>
 *
 * <p>The {@code msdf} flag distinguishes bitmap and MSDF atlas variants of the
 * same glyph, since they reside in separate atlases with different texture formats
 * ({@code GL_R8} for bitmap, {@code GL_RGB16F} for MSDF).</p>
 *
 * <h3>Examples</h3>
 * <pre>
 * CgFontKey fontKey = new CgFontKey("font.ttf", CgFontStyle.REGULAR, 48);
 * CgGlyphKey bitmap = new CgGlyphKey(fontKey, 65, false);
 * CgGlyphKey msdf   = new CgGlyphKey(fontKey, 65, true);
 * assert !bitmap.equals(msdf);  // different rendering mode
 * </pre>
 *
 * @see CgFontKey
 */
@Value
public class CgGlyphKey {

    /**
     * Maximum target pixel size (exclusive) that uses bitmap sub-pixel offset buckets.
     *
     * <p>At sizes below this threshold, sub-pixel offset buckets (0–3) are preserved
     * in the glyph key so that fractional glyph positions produce distinct rasterizations.
     * At this size and above, the bucket is normalized to 0 because MSDF or large-bitmap
     * rendering makes sub-pixel offsets imperceptible.</p>
     *
     * <p>This constant is aligned with the bitmap/MSDF handoff boundary
     * ({@link io.github.somehussar.crystalgraphics.gl.text.CgMsdfGenerator#SIMPLE_MSDF_MIN_PX}).
     * All three consumers — {@code CgGlyphKey}, {@code CgTextRenderer.selectSubPixelBucket()},
     * and {@code CgFontRegistry.ensureBitmapGlyph()} — must use this single constant.</p>
     */
    public static final int SUB_PIXEL_BUCKET_MAX_PX = 32;

    /** Font this glyph belongs to. */
    CgFontKey fontKey;

    /**
     * HarfBuzz glyph ID / FreeType glyph index (NOT a Unicode codepoint).
     *
     * <p>{@code HBGlyphInfo.getCodepoint()} returns the glyph index in HarfBuzz
     * terminology. This maps 1:1 to FreeType's glyph index used to rasterize
     * the glyph bitmap or generate the MSDF.</p>
     */
    int glyphId;

    /** {@code true} for MSDF variant, {@code false} for bitmap. */
    boolean msdf;

    int subPixelBucket;

    public CgGlyphKey(CgFontKey fontKey, int glyphId, boolean msdf) {
        this(fontKey, glyphId, msdf, 0);
    }

    public CgGlyphKey(CgFontKey fontKey, int glyphId, boolean msdf, int subPixelBucket) {
        if (fontKey == null) {
            throw new IllegalArgumentException("fontKey must not be null");
        }
        if (glyphId < 0) {
            throw new IllegalArgumentException("glyphId must be >= 0");
        }
        if (fontKey.getTargetPx() < SUB_PIXEL_BUCKET_MAX_PX) {
            if (subPixelBucket < 0 || subPixelBucket > 3) {
                throw new IllegalArgumentException(
                        "subPixelBucket must be in range [0, 3] for targetPx < " + SUB_PIXEL_BUCKET_MAX_PX);
            }
            this.subPixelBucket = subPixelBucket;
        } else {
            this.subPixelBucket = 0;
        }
        this.fontKey = fontKey;
        this.glyphId = glyphId;
        this.msdf = msdf;
    }
}
