package com.crystalgraphics.text.render;

/**
 * One screen draw's raster size across frames: exact while its scale holds, on a coarse ladder while it moves, so an
 * animated scale makes a few glyph sizes and shadow cells and not one per pixel size. A browser does the same for a
 * transform animation: it keeps a raster scale and stretches the layer.
 *
 * <pre>{@code
 * CgTextRasterMotion motion = new CgTextRasterMotion();
 * int exact = CgTextRaster.screenPx(basePx, scale, motion.exactPx());   // hysteresis against its own last size
 * int px = motion.resolve(exact, frame);                                // the size to raster at
 * }</pre>
 *
 * <ul>
 *   <li>Moving means the exact size changed on two frames within {@link #WINDOW} of each other. It stays on the
 *       ladder until the size has held for {@link #SETTLE} frames, then returns to exact: a settled label is crisp.</li>
 *   <li>One state per draw, not per font: two draws of one face at different sizes are not an animation.</li>
 * </ul>
 */
final class CgTextRasterMotion {

    /** Frames within which two size changes make a draw moving. */
    static final int WINDOW = 8;
    /** Frames the size must hold before a moving draw is exact again. */
    static final int SETTLE = 15;

    /** Under this size every pixel is its own rung: too few sizes to matter. */
    private static final int LADDER_FROM = 16;
    private static final double LADDER_STEP = 1.2;
    private static final double SHADOW_STEP = 1.5;

    private int exactPx = -1;
    private int lastPx = -1;
    private long lastChange = Long.MIN_VALUE / 2;
    private int streak;
    /** Whether the draw was on the distance field: its tier's hysteresis. */
    boolean wasMsdf;
    /** The frame it was last resolved in: what evicts it. */
    long lastUsed;

    /** The exact size it last held, -1 before the first: what {@link CgTextRaster#screenPx} holds against. */
    int exactPx() {
        return exactPx;
    }

    /** The size it last resolved to, -1 before the first. */
    int lastPx() {
        return lastPx;
    }

    /** Whether the draw is on the ladder at {@code frame}. */
    boolean moving(long frame) {
        return streak >= 2 && frame - lastChange < SETTLE;
    }

    /** The size to raster at for {@code exact} at {@code frame}: {@code exact}, or its rung while the size moves. */
    int resolve(int exact, long frame) {
        lastUsed = frame;
        if (exact != exactPx) {
            if (exactPx > 0) {
                streak = frame - lastChange <= WINDOW ? streak + 1 : 1;
                lastChange = frame;
            }
            exactPx = exact;
        }
        lastPx = moving(frame) ? ladder(exact) : exact;
        return lastPx;
    }

    /** The rung nearest {@code px}: sizes of {@code 16 * 1.2^k}, and every size under 16. */
    static int ladder(int px) {
        return rung(px, LADDER_STEP);
    }

    /**
     * The size a moving draw's shadow cells are built at: a coarser grid than its glyphs', since a blur hides what a
     * stretch of up to half again would show on a glyph.
     */
    static int shadowRung(int px) {
        return rung(px, SHADOW_STEP);
    }

    private static int rung(int px, double step) {
        if (px <= LADDER_FROM) return px;
        long k = Math.round(Math.log(px / (double) LADDER_FROM) / Math.log(step));
        int rung = (int) Math.round(LADDER_FROM * Math.pow(step, k));
        return Math.max(CgTextRaster.MIN_PX, Math.min(CgTextRaster.MAX_PX, rung));
    }
}
