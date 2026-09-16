package com.crystalgraphics.text.shadow;

import javax.annotation.Nullable;

/**
 * What makes a glyph's shadow cell different from the glyph: the blur, the growth, and for an inset
 * shadow the clip and offset. The part of a {@code CgGlyphKey} that a plain glyph leaves null, and the
 * recipe a worker builds the cell's pixels from.
 *
 * <pre>{@code
 * CgShadowCell cell = CgShadowCell.forOuterShadow(deviceSigma, spreadDevicePx, rasterPx, maxCellPx);
 * CgGlyphKey atlasKey = bitmapKey.withShadowCell(cell);
 * // on a worker:
 * CgShadowCoverage pixels = cell.build(glyphSource);
 * }</pre>
 *
 * <p><b>Every field is a quantised key, and it is quantised where the output is.</b> Sigma from 2 up is
 * {@code PlanGauss}'s window, the only input the triple box reads, so two sigmas that share a window
 * share a cell with nothing lost; below 2 it is sixty-fourths of a pixel, where a half step moves an
 * edge pixel by under 0.6%. Growth is sixteenths of a pixel and an inset offset quarters. A worker
 * regenerates from the DECODED values, so every request mapping to one key gets identical pixels.</p>
 *
 * <p>All lengths are device pixels of the glyph's raster before {@link #downsample}; sigma is in pixels
 * of the downsampled cell, which is what the blur runs on.</p>
 */
public record CgShadowCell(int downsample, int sigmaKey, int growKey, int clipKey,
                           int offsetXKey, int offsetYKey, boolean inset) {

    /** Sigma above which the cell is downsampled first: Skia's {@code kMaxLinearBlurSigma}. */
    public static final double MAX_WORKING_SIGMA = 4.0;

    /** Skia caps a transformed blur sigma here before blurring ({@code computeXformedSigma}). */
    public static final double MAX_DEVICE_SIGMA = 128.0;

    private static final int MAX_DOWNSAMPLE = 64;
    private static final int BOX_KEY_BASE = 1000;
    private static final double SMALL_STEPS = 64.0;
    private static final float GROW_STEPS = 16f;
    private static final float OFFSET_STEPS = 4f;

    /**
     * Where a cell's glyph comes from. The glyph workers answer it with FreeType and msdfgen; this package
     * names neither.
     */
    public interface GlyphSource {
        /** The glyph's own coverage, unhinted, synthetic style applied; null when it draws nothing. */
        @Nullable
        CgShadowCoverage coverage();

        /**
         * The glyph grown by {@code growPx} device pixels along its true distance, shrunk when negative,
         * so corners round; null when it draws nothing.
         */
        @Nullable
        CgShadowCoverage grown(float growPx);
    }

    public CgShadowCell {
        if (downsample < 1) throw new IllegalArgumentException("downsample must be >= 1: " + downsample);
    }

    /**
     * The cell for an outer shadow of a glyph rasterised at {@code rasterPx}: the glyph grown by
     * {@code growDevicePx}, then blurred with {@code deviceSigma}.
     *
     * <p>Downsampled by Skia's GPU rule above {@link #MAX_WORKING_SIGMA}, and further only while the cell
     * would be wider than {@code maxCellPx}, past which it crowds an atlas page out for one glyph.</p>
     */
    public static CgShadowCell forOuterShadow(double deviceSigma, float growDevicePx, int rasterPx, int maxCellPx) {
        double sigma = blurSigma(deviceSigma);
        int downsample = downsampleFor(sigma);
        while (downsample < MAX_DOWNSAMPLE && estimatedCellPx(rasterPx, growDevicePx, sigma, downsample) > maxCellPx) {
            downsample++;
        }
        return outer(downsample, sigma / downsample, growDevicePx);
    }

    /**
     * The cell for an inset shadow. Always full resolution: it is no bigger than the glyph, and a
     * downsampled clip would soften the glyph's own edge.
     */
    public static CgShadowCell forInsetShadow(double deviceSigma, float holeShrinkDevicePx, float clipShrinkDevicePx,
                                              float offsetXDevicePx, float offsetYDevicePx) {
        return inset(1, blurSigma(deviceSigma), holeShrinkDevicePx, clipShrinkDevicePx,
                offsetXDevicePx, offsetYDevicePx);
    }

    /** An outer shadow cell at an exact downsample: the glyph grown by {@code growDevicePx}, then blurred. */
    public static CgShadowCell outer(int downsample, double workingSigma, float growDevicePx) {
        return new CgShadowCell(downsample, sigmaKey(workingSigma), Math.round(growDevicePx * GROW_STEPS),
                0, 0, 0, false);
    }

    /**
     * An inset shadow cell at an exact downsample: {@code clip} is the glyph shrunk by
     * {@code clipShrinkDevicePx}, the hole the glyph shrunk by {@code holeShrinkDevicePx} and blurred, and
     * the cell is {@code clip * (1 - hole at the offset)}.
     */
    public static CgShadowCell inset(int downsample, double workingSigma, float holeShrinkDevicePx,
                                     float clipShrinkDevicePx, float offsetXDevicePx, float offsetYDevicePx) {
        return new CgShadowCell(downsample, sigmaKey(workingSigma),
                Math.round(holeShrinkDevicePx * GROW_STEPS), Math.round(clipShrinkDevicePx * GROW_STEPS),
                Math.round(offsetXDevicePx * OFFSET_STEPS), Math.round(offsetYDevicePx * OFFSET_STEPS), true);
    }

    /** The integer downsample that brings a device sigma under {@link #MAX_WORKING_SIGMA}. */
    public static int downsampleFor(double deviceSigma) {
        return deviceSigma <= MAX_WORKING_SIGMA ? 1 : (int) Math.ceil(deviceSigma / MAX_WORKING_SIGMA);
    }

    /**
     * This cell's pixels: the glyph from {@code source}, grown, downsampled and blurred for an outer
     * shadow, or clipped against its own blurred, offset hole for an inset one. Null when the glyph draws
     * nothing.
     */
    @Nullable
    public CgShadowCoverage build(GlyphSource source) {
        double sigma = sigma();
        if (inset) {
            CgShadowCoverage clip = source.grown(-clip());
            if (clip == null) return null;
            CgShadowCoverage hole = source.grown(-grow());
            if (hole != null && sigma > 0.0) hole = hole.blur(sigma);
            return clip.inset(hole, offsetX(), offsetY());
        }
        CgShadowCoverage shape = needsDistance() ? source.grown(grow()) : source.coverage();
        if (shape == null) return null;
        shape = shape.downsample(downsample);
        return sigma > 0.0 ? shape.blur(sigma) : shape;
    }

    /** The sigma the worker blurs with, in cell pixels; 0 for no blur. */
    public double sigma() {
        if (sigmaKey == 0) return 0.0;
        if (sigmaKey < BOX_KEY_BASE) return sigmaKey / SMALL_STEPS;
        // Any sigma with this window blurs identically; this one maps back to it exactly.
        return (sigmaKey - BOX_KEY_BASE) / (3 * Math.sqrt(2 * Math.PI) / 4);
    }

    /** Outer: growth. Inset: how far the hole is shrunk. Device pixels, may be negative. */
    public float grow() {
        return growKey / GROW_STEPS;
    }

    /** Inset only: how far the clip is shrunk inside the glyph. */
    public float clip() {
        return clipKey / GROW_STEPS;
    }

    public float offsetX() {
        return offsetXKey / OFFSET_STEPS;
    }

    public float offsetY() {
        return offsetYKey / OFFSET_STEPS;
    }

    /** Whether the cell needs the outline's true distance rather than FreeType's coverage raster. */
    public boolean needsDistance() {
        return growKey != 0 || clipKey != 0 || inset;
    }

    static int sigmaKey(double sigma) {
        if (sigma < CgMaskBlurFilter.NO_BLUR_SIGMA) return 0;
        if (sigma < CgMaskBlurFilter.BOX_BLUR_MIN_SIGMA) {
            // Kept strictly inside [1/3, 2) once decoded, so a key never changes which blur runs.
            return (int) Math.max(Math.ceil(CgMaskBlurFilter.NO_BLUR_SIGMA * SMALL_STEPS),
                    Math.min(2 * SMALL_STEPS - 1, Math.round(sigma * SMALL_STEPS)));
        }
        return BOX_KEY_BASE + CgMaskBlurFilter.boxWindow(Math.min(CgMaskBlurFilter.MAX_SIGMA, sigma));
    }

    /** A device sigma as Skia blurs it: capped, and no blur at all below the cutoff. */
    private static double blurSigma(double deviceSigma) {
        double sigma = Math.min(MAX_DEVICE_SIGMA, deviceSigma);
        return sigma >= CgMaskBlurFilter.NO_BLUR_SIGMA ? sigma : 0.0;
    }

    private static int estimatedCellPx(int rasterPx, float growDevicePx, double sigma, int downsample) {
        int border = new CgMaskBlurFilter(sigma / downsample).border();
        // A glyph's ink box is within 1.5 em of its size in every face shipped or seen here.
        return (int) Math.ceil((rasterPx * 1.5 + 2 * growDevicePx) / downsample) + 2 * border;
    }
}
