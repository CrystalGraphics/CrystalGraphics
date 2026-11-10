package com.crystalgraphics.api.state;


import com.github.bsideup.jabel.Desugar;

/**
 * Immutable color-write mask state slot for {@link CgRenderState}.
 *
 * <p>Controls which color channels (R, G, B, A) are written to the framebuffer: every
 * attachment when {@code targetIndex} is {@code -1}, else that attachment alone.</p>
 */
@Desugar
public record CgColorMask(boolean r, boolean g, boolean b, boolean a, int targetIndex) {

    /** All channels enabled, applied to all render targets ({@code targetIndex == -1}). */
    public static final CgColorMask ALL = new CgColorMask(true, true, true, true, -1);

    /** All channels disabled, applied to all render targets ({@code targetIndex == -1}). */
    public static final CgColorMask NONE = new CgColorMask(false, false, false, false, -1);

    public static CgColorMask of(boolean r, boolean g, boolean b, boolean a) {
        return new CgColorMask(r, g, b, a, -1);
    }

    /**
     * Creates a color mask applied to a specific MRT render target.
     *
     * <p>Requires GL 3.0+ when applied ({@code glColorMaski}). Do not use on
     * pre-3.0 hardware.</p>
     *
     * @param targetIndex the zero-based MRT attachment index (0..7)
     */
    public static CgColorMask ofTarget(boolean r, boolean g, boolean b, boolean a, int targetIndex) {
        return new CgColorMask(r, g, b, a, targetIndex);
    }
}
