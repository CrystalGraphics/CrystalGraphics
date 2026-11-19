package com.crystalgraphics.api.paint.brush;

/**
 * The borders that stay put when a {@link CgBrush} is drawn larger than its image: its corners keep their size, its
 * edges stretch or tile along one axis, its middle fills the rest. Slate's box brush margins, CSS's
 * {@code border-image-slice}.
 *
 * <pre>{@code
 * CgBrush button = CgBrush.texture(theme).region(29, 1, 13, 13).nineSlice(CgNineSlice.of(1, 1, 11, 11));
 * CgBrush panel  = CgBrush.texture(frame).nineSlice(CgNineSlice.uniform(4)).edges(CgTiling.ROUND);
 * }</pre>
 *
 * <ul>
 *   <li>In the source image's pixels, measured inward from each side of the region: left, top, right, bottom.</li>
 *   <li>{@code fill} false leaves the middle empty: a frame around content drawn separately.</li>
 *   <li>{@code scale} draws the slices that many times their source size (CSS's {@code border-image-width}), so a
 *       pixel-art frame can be shown at 2x; the middle tiles at the same scale.</li>
 *   <li>Drawn as one quad: the nine regions are remapped per pixel, so no seams show between them.</li>
 * </ul>
 *
 * @param left   pixels from the region's left edge
 * @param top    pixels from its top
 * @param right  pixels from its right edge
 * @param bottom pixels from its bottom
 * @param fill   whether the middle draws
 * @param scale  the slices' drawn size over their source size, 1 by default
 */
public record CgNineSlice(int left, int top, int right, int bottom, boolean fill, float scale) {

    public CgNineSlice {
        if (left < 0 || top < 0 || right < 0 || bottom < 0) {
            throw new IllegalArgumentException("slices must be >= 0: " + left + ", " + top + ", " + right + ", " + bottom);
        }
        if (!(scale > 0f) || Float.isInfinite(scale)) throw new IllegalArgumentException("scale must be positive: " + scale);
    }

    /** Slices {@code left}, {@code top}, {@code right}, {@code bottom} pixels in, the middle filled. */
    public static CgNineSlice of(int left, int top, int right, int bottom) {
        return new CgNineSlice(left, top, right, bottom, true, 1f);
    }

    /** The same slice on every side, the middle filled. */
    public static CgNineSlice uniform(int pixels) {
        return new CgNineSlice(pixels, pixels, pixels, pixels, true, 1f);
    }

    /** This slice with its middle left empty. */
    public CgNineSlice hollow() {
        return new CgNineSlice(left, top, right, bottom, false, scale);
    }

    /** This slice drawn at {@code scale} times its source size. */
    public CgNineSlice scaled(float scale) {
        return new CgNineSlice(left, top, right, bottom, fill, scale);
    }
}
