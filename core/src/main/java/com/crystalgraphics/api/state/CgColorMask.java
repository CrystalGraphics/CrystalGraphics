package com.crystalgraphics.api.state;


import com.crystalgraphics.platform.gl.CgGL;
import com.github.bsideup.jabel.Desugar;

/**
 * Immutable color-write mask state slot for {@link CgRenderState}.
 *
 * <p>Controls which color channels (R, G, B, A) are written to the framebuffer.
 * When {@code targetIndex} is {@code -1}, the mask applies to all render targets
 * via {@code glColorMask}. When {@code targetIndex} is {@code 0..7}, the mask
 * applies only to that specific MRT attachment via {@code glColorMaski} (GL 3.0+).</p>
 *
 * <p>{@link #clearToDefault()} restores all channels enabled for all targets.</p>
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

    public void apply() {
        if (targetIndex < 0) {
            CgGL.glColorMask(r, g, b, a);
        } else {
            CgGL.glColorMaski(targetIndex, r, g, b, a);
        }
    }

    /**
     * Restores GL default: all channels enabled for all render targets.
     * For use in {@code CgRenderState.clear()}.
     */
    public static void clearToDefault() {
        CgGL.glColorMask(true, true, true, true);
    }
}
