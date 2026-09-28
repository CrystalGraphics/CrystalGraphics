package com.crystalgraphics.text.render;

/**
 * How a text renderer corrects glyph coverage for blending in the target's encoded colour space: Skia's text gamma
 * and contrast ({@code SkMaskGamma}), which give light text on a dark background back the weight blending takes
 * from it.
 *
 * <pre>{@code
 * renderer.gamma(CgTextGamma.DEFAULT);         // gamma 1.8, contrast 0.5
 * renderer.gamma(CgTextGamma.NONE);            // coverage exactly as rasterised
 * renderer.gamma(new CgTextGamma(1.2f, 0.2f)); // Chromium's values: barely visible on hinted glyphs
 * }</pre>
 *
 * <p>It corrects light text only: text darker than mid-grey is drawn as rasterised, because the correction thins
 * it. Shadows and strokes are always drawn as rasterised. Start the JVM with
 * {@code -Dcrystalgraphics.text.gamma=false} to make {@link #initial()} answer {@link #NONE}, for comparing.</p>
 *
 * @param exponent the gamma the correction blends in; 1 blends in the encoded space, as without correction
 * @param contrast extra coverage, strongest on mid-grey text and fading to none toward white; 0 for none
 */
public record CgTextGamma(float exponent, float contrast) {

    /**
     * Stronger than Chromium's 1.2 and 0.2, which assume unhinted glyphs: ours are hinted, so few pixels are partly
     * covered and Chromium's values change almost nothing. Holes in letters stay open down to 5px.
     */
    public static final CgTextGamma DEFAULT = new CgTextGamma(1.8f, 0.5f);

    /** No correction. */
    public static final CgTextGamma NONE = new CgTextGamma(1f, 0f);

    public CgTextGamma {
        if (!(exponent > 0f)) throw new IllegalArgumentException("exponent must be positive: " + exponent);
        if (!(contrast >= 0f && contrast <= 1f)) throw new IllegalArgumentException("contrast must be in [0, 1]: " + contrast);
    }

    /** What a new renderer starts with: {@link #DEFAULT}, or {@link #NONE} under {@code crystalgraphics.text.gamma=false}. */
    public static CgTextGamma initial() {
        return "false".equals(System.getProperty("crystalgraphics.text.gamma")) ? NONE : DEFAULT;
    }

    public boolean isIdentity() {
        return exponent == 1f && contrast == 0f;
    }
}
