package com.crystalgraphics.text.shadow;

import javax.annotation.Nullable;

/**
 * 8-bit coverage of a glyph, placed against its pen: the mask a text-shadow cell is made of, and the
 * operations that make one.
 *
 * <pre>{@code
 * CgShadowCoverage grown = CgShadowCoverage.fromDistance(distancePx, w, h, left, top, 2f);  // spread 2px
 * CgShadowCoverage cell = grown.downsample(2).blur(3.0);
 * // cell.data() is width x height, row 0 at the top; left/top place it in device pixels, top above the
 * // baseline; one texel is texel() device pixels wide.
 * }</pre>
 *
 * <p>Immutable in use: every operation answers a new coverage and leaves its input alone.</p>
 */
public record CgShadowCoverage(byte[] data, int width, int height, float left, float top, int texel) {

    /**
     * A signed distance thresholded at {@code grow} device pixels past the outline (inside it when
     * negative), antialiased over one pixel.
     *
     * @param distancePx device pixels from the outline, positive inside, row 0 at the top
     */
    public static CgShadowCoverage fromDistance(double[] distancePx, int width, int height, int left, int top,
                                                float grow) {
        byte[] coverage = new byte[width * height];
        for (int i = 0; i < coverage.length; i++) {
            double c = Math.max(0.0, Math.min(1.0, distancePx[i] + grow + 0.5));
            coverage[i] = (byte) Math.round(c * 255.0);
        }
        return new CgShadowCoverage(coverage, width, height, left, top, 1);
    }

    /**
     * Box-averages {@code factor x factor} blocks aligned to multiples of {@code factor} device pixels, so
     * coverage is conserved and one glyph downsamples identically wherever it is drawn.
     */
    public CgShadowCoverage downsample(int factor) {
        if (factor <= 1) return this;
        int newLeft = Math.floorDiv((int) Math.floor(left), factor) * factor;
        int newTop = -Math.floorDiv(-(int) Math.ceil(top), factor) * factor;
        int padX = (int) (left - newLeft);
        int padY = (int) (newTop - top);
        int outWidth = (width + padX + factor - 1) / factor;
        int outHeight = (height + padY + factor - 1) / factor;
        int[] sums = new int[outWidth * outHeight];
        for (int y = 0; y < height; y++) {
            int row = ((y + padY) / factor) * outWidth;
            for (int x = 0; x < width; x++) {
                sums[row + (x + padX) / factor] += data[y * width + x] & 0xFF;
            }
        }
        int area = factor * factor;
        byte[] out = new byte[outWidth * outHeight];
        for (int i = 0; i < out.length; i++) out[i] = (byte) ((sums[i] + area / 2) / area);
        return new CgShadowCoverage(out, outWidth, outHeight, newLeft, newTop, factor * texel);
    }

    /** {@link CgMaskBlurFilter} at {@code sigma} texels, the coverage grown by the blur's border on every side. */
    public CgShadowCoverage blur(double sigma) {
        CgMaskBlurFilter filter = new CgMaskBlurFilter(sigma);
        if (filter.hasNoBlur()) return this;
        int border = filter.border();
        byte[] out = filter.blur(data, width, height);
        return new CgShadowCoverage(out, width + 2 * border, height + 2 * border,
                left - border * texel, top + border * texel, texel);
    }

    /**
     * {@code this * (1 - hole shifted by the offset)} over this coverage's extent: the canvas outside the
     * hole shadows into the glyph, and nothing lands outside it. A null hole is a glyph shrunk to nothing,
     * which shadows all of this.
     *
     * @param offsetX CSS offset, device pixels, positive right
     * @param offsetY CSS offset, device pixels, positive down
     */
    public CgShadowCoverage inset(@Nullable CgShadowCoverage hole, float offsetX, float offsetY) {
        byte[] out = new byte[width * height];
        for (int y = 0; y < height; y++) {
            // Device position of this texel's centre, Y up from the baseline, then back through the offset.
            float sampleY = top - (y + 0.5f) * texel + offsetY;
            for (int x = 0; x < width; x++) {
                int c = data[y * width + x] & 0xFF;
                if (c == 0) continue;
                float sampleX = left + (x + 0.5f) * texel - offsetX;
                double h = hole == null ? 0.0 : hole.sample(sampleX, sampleY) / 255.0;
                out[y * width + x] = (byte) Math.round(c * (1.0 - h));
            }
        }
        return new CgShadowCoverage(out, width, height, left, top, texel);
    }

    /** Coverage at a device position, bilinear, zero outside. */
    public double sample(float deviceX, float deviceY) {
        float u = (deviceX - left) / texel - 0.5f;
        float v = (top - deviceY) / texel - 0.5f;
        int x0 = (int) Math.floor(u);
        int y0 = (int) Math.floor(v);
        float fx = u - x0;
        float fy = v - y0;
        return lerp(lerp(texelAt(x0, y0), texelAt(x0 + 1, y0), fx),
                lerp(texelAt(x0, y0 + 1), texelAt(x0 + 1, y0 + 1), fx), fy);
    }

    private int texelAt(int x, int y) {
        if (x < 0 || y < 0 || x >= width || y >= height) return 0;
        return data[y * width + x] & 0xFF;
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }
}
