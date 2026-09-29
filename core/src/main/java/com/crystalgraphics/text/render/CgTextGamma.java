package com.crystalgraphics.text.render;

/**
 * How a text renderer corrects glyph coverage for blending in the target's encoded colour space: Skia's text gamma
 * and contrast ({@code SkMaskGamma}), which give light text on a dark background back the weight blending takes
 * from it. Small text gets one {@link Level}, large text another, faded across a range of on-screen em sizes.
 *
 * <pre>{@code
 * renderer.gamma(CgTextGamma.DEFAULT);                     // STRONG up to 7px, HEAVY from 10px
 * renderer.gamma(CgTextGamma.NONE);                        // coverage exactly as rasterised
 * renderer.gamma(CgTextGamma.of(Level.STRONG));            // one level at every size
 * renderer.gamma(CgTextGamma.of(new Level(1.2f, 0.2f)));   // Chromium's values: barely visible on hinted glyphs
 * }</pre>
 *
 * <p>It corrects light text only: text darker than mid-grey is drawn as rasterised, because the correction thins
 * it. Shadows and strokes are always drawn as rasterised. Start the JVM with
 * {@code -Dcrystalgraphics.text.gamma=false} to make {@link #initial()} answer {@link #NONE}, for comparing.</p>
 *
 * @param small   the level at and below {@code smallPx}
 * @param large   the level at and above {@code largePx}
 * @param smallPx on-screen em size, in device pixels, where the fade to {@code large} starts
 * @param largePx on-screen em size where it ends; not below {@code smallPx}
 */
public record CgTextGamma(Level small, Level large, float smallPx, float largePx) {

    /**
     * One strength of the correction.
     *
     * @param exponent the gamma the correction blends in; 1 blends in the encoded space, as without correction
     * @param contrast extra coverage, strongest on mid-grey text and fading to none toward white; 0 for none
     */
    public record Level(float exponent, float contrast) {

        /** No correction. */
        public static final Level NONE = new Level(1f, 0f);

        /** Keeps the holes in letters open down to 5px. */
        public static final Level STRONG = new Level(1.8f, 0.5f);

        /** Heavier again; fills the holes in e, a and 8 below about 7px. */
        public static final Level HEAVY = new Level(2.2f, 1f);

        public Level {
            if (!(exponent > 0f)) throw new IllegalArgumentException("exponent must be positive: " + exponent);
            if (!(contrast >= 0f && contrast <= 1f)) throw new IllegalArgumentException("contrast must be in [0, 1]: " + contrast);
        }

        public boolean isIdentity() {
            return exponent == 1f && contrast == 0f;
        }
    }

    /**
     * {@link Level#STRONG} on small text, fading into {@link Level#HEAVY} by 10px. Chromium's own 1.2 and 0.2 assume
     * unhinted glyphs: ours are hinted, so few pixels are partly covered and those values change almost nothing.
     */
    public static final CgTextGamma DEFAULT = new CgTextGamma(Level.STRONG, Level.HEAVY, 7f, 10f);

    /** No correction. */
    public static final CgTextGamma NONE = of(Level.NONE);

    public CgTextGamma {
        if (small == null || large == null) throw new IllegalArgumentException("levels must not be null");
        if (!(largePx >= smallPx)) throw new IllegalArgumentException("largePx " + largePx + " is below smallPx " + smallPx);
    }

    /** The same level at every size. */
    public static CgTextGamma of(Level level) {
        return new CgTextGamma(level, level, 0f, 0f);
    }

    /** What a new renderer starts with: {@link #DEFAULT}, or {@link #NONE} under {@code crystalgraphics.text.gamma=false}. */
    public static CgTextGamma initial() {
        return "false".equals(System.getProperty("crystalgraphics.text.gamma")) ? NONE : DEFAULT;
    }

    public boolean isIdentity() {
        return small.isIdentity() && large.isIdentity();
    }
}
