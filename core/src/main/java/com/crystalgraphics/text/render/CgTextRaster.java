package com.crystalgraphics.text.render;

import org.joml.Matrix4fc;

/**
 * The size a text draw's glyphs are rastered at, and whether they come from the bitmap atlas or the distance-field
 * one. Layout never changes with it: shaping, advances and line metrics stay at the font's own size.
 *
 * <pre>{@code
 * int px = CgTextRaster.screenPx(font.getKey().getTargetPx(), CgTextRaster.maxScale(pixelPose), lastPx);
 * boolean msdf = CgTextRaster.msdf(px, lastMsdf) || !CgTextRaster.isAxisAligned(pixelPose);
 *
 * int worldPx = CgTextRaster.worldPx(basePx);   // world text: always the distance field
 * }</pre>
 *
 * <ul>
 *   <li>Screen sizes need the last size and tier the same font took, kept by the caller: both damp a scale animating
 *       across a boundary, which would otherwise re-raster every frame.</li>
 *   <li>The pose given is in the target's device pixels, not a GUI's logical ones.</li>
 * </ul>
 */
public final class CgTextRaster {

    public static final int MIN_PX = 1, MAX_PX = 256;

    /**
     * Screen text takes the distance field at {@code MSDF_ENTER} px and leaves it at {@code MSDF_EXIT}. Not the
     * field's antialiasing floor ({@code CgMsdfAtlasConfig.minAntialiasablePx()}): where the field starts looking
     * better than a bitmap rastered at the size. {@code MSDF_EXIT} must stay at or above every band's floor
     * ({@code CgFontBandingTest}).
     */
    public static final int MSDF_ENTER = 33, MSDF_EXIT = 31;

    /** A size moves to N+1 at N + this, back to N-1 at N - this. */
    private static final float HYSTERESIS = 0.75f;

    /**
     * World text's raster size, as a multiple of the font's. Fixed: distance-field atlases are keyed at one scale
     * whatever the size, so a camera-driven size only minted bitmap fallback families (a dolly made seven, 333 MB).
     */
    private static final int WORLD_SCALE = 2;

    /** Off-axis components within this fraction of a basis vector's length are float noise, not a tilt (~0.006°). */
    private static final float AXIS_EPSILON = 1.0e-4f;

    private CgTextRaster() {
    }

    /**
     * Screen text's raster size: {@code basePx} times the pose's larger axis scale, rounded and clamped, held at
     * {@code previousPx} (-1 for none) inside the hysteresis band.
     */
    public static int screenPx(int basePx, float scale, int previousPx) {
        float raw = basePx * scale;
        int px = Math.max(MIN_PX, Math.min(MAX_PX, Math.round(raw)));
        if (previousPx > 0) {
            if (px > previousPx && raw < previousPx + HYSTERESIS) return previousPx;
            if (px < previousPx && raw > previousPx - HYSTERESIS) return previousPx;
        }
        return px;
    }

    /** World text's raster size: what its bitmap placeholder draws at until the distance field lands. */
    public static int worldPx(int basePx) {
        return Math.max(MIN_PX, Math.min(MAX_PX, basePx * WORLD_SCALE));
    }

    /** Whether screen text of {@code px} takes the distance field, held at {@code wasMsdf} between the thresholds. */
    public static boolean msdf(int px, boolean wasMsdf) {
        if (px >= MSDF_ENTER) return true;
        if (px <= MSDF_EXIT) return false;
        return wasMsdf;
    }

    /**
     * Whether {@code pose} keeps the text's XY plane on the pixel axes: a bitmap glyph resamples crisp only then, so
     * anything else takes the distance field. Quarter turns, flips and non-uniform scale pass; a rotation, a shear or
     * an out-of-plane tilt does not. Each axis is tested against its own length, so a huge scale on one cannot hide a
     * tilt on the other.
     */
    public static boolean isAxisAligned(Matrix4fc pose) {
        float m00 = pose.m00(), m01 = pose.m01(), m02 = pose.m02();
        float m10 = pose.m10(), m11 = pose.m11(), m12 = pose.m12();
        float sx = (float) Math.sqrt(m00 * m00 + m01 * m01 + m02 * m02);
        float sy = (float) Math.sqrt(m10 * m10 + m11 * m11 + m12 * m12);
        if (sx <= 0f || sy <= 0f) return true;   // degenerate: draws nothing, so changes no tier
        float tolX = AXIS_EPSILON * sx, tolY = AXIS_EPSILON * sy;
        if (Math.abs(m02) > tolX || Math.abs(m12) > tolY) return false;
        boolean diagonal = Math.abs(m01) <= tolX && Math.abs(m10) <= tolY;
        boolean antiDiagonal = Math.abs(m00) <= tolX && Math.abs(m11) <= tolY;
        return diagonal || antiDiagonal;
    }

    /** The longer of {@code pose}'s X and Y basis vectors: the scale a raster size follows, as browsers do. */
    public static float maxScale(Matrix4fc pose) {
        float sx = (float) Math.sqrt(pose.m00() * pose.m00() + pose.m01() * pose.m01() + pose.m02() * pose.m02());
        float sy = (float) Math.sqrt(pose.m10() * pose.m10() + pose.m11() * pose.m11() + pose.m12() * pose.m12());
        return Math.max(sx, sy);
    }
}
