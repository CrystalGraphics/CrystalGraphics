package com.crystalgraphics.api.state;


import com.crystalgraphics.platform.gl.driver.GLC;
import com.github.bsideup.jabel.Desugar;

/**
 * Immutable description of the blend state for a render pass.
 *
 * <p>Encapsulates the GL blend function and blend equation parameters applied at pass begin.
 * When blending is disabled ({@link #DISABLED}), no blend calls are issued
 * and {@code glDisable(GL_BLEND)} is set. When blending is enabled, the
 * pass calls {@code glEnable(GL_BLEND)}, configures the blend function
 * using the specified source/destination factors, and sets the blend equations
 * via {@code glBlendEquationSeparate}.</p>
 *
 * <p>Pre-defined constants cover the most common UI and post-processing
 * blend modes. Custom blend states can be created via the constructor or
 * the {@link #of(boolean, int, int, int, int)} factory (defaulting equations to
 * {@code GL_FUNC_ADD}).</p>
 */
@Desugar
public record CgBlendState(boolean enabled, int srcRgb, int dstRgb, int srcAlpha, int dstAlpha,
                            int blendEquationRgb, int blendEquationAlpha) {




    /** No blending. {@code glDisable(GL_BLEND)} is issued. */
    public static final CgBlendState DISABLED = new CgBlendState(false,
            GLC.GL_ONE, GLC.GL_ZERO, GLC.GL_ONE, GLC.GL_ZERO,
            GLC.GL_FUNC_ADD, GLC.GL_FUNC_ADD);

    /**
     * Standard alpha blending: {@code SRC_ALPHA / ONE_MINUS_SRC_ALPHA}.
     * Used by UI text, panels, and most 2D content.
     */
    public static final CgBlendState ALPHA = new CgBlendState(true,
            GLC.GL_SRC_ALPHA, GLC.GL_ONE_MINUS_SRC_ALPHA,
            GLC.GL_ONE, GLC.GL_ONE_MINUS_SRC_ALPHA,
            GLC.GL_FUNC_ADD, GLC.GL_FUNC_ADD);

    /**
     * Pre-multiplied alpha blending: {@code ONE / ONE_MINUS_SRC_ALPHA}.
     * Used when textures are stored with pre-multiplied alpha.
     */
    public static final CgBlendState PREMULTIPLIED_ALPHA = new CgBlendState(true,
            GLC.GL_ONE, GLC.GL_ONE_MINUS_SRC_ALPHA,
            GLC.GL_ONE, GLC.GL_ONE_MINUS_SRC_ALPHA,
            GLC.GL_FUNC_ADD, GLC.GL_FUNC_ADD);

    /**
     * Additive blending: {@code SRC_ALPHA / ONE}.
     * Used for glow effects, particle effects, etc.
     */
    public static final CgBlendState ADDITIVE = new CgBlendState(true,
            GLC.GL_SRC_ALPHA, GLC.GL_ONE,
            GLC.GL_ONE, GLC.GL_ONE,
            GLC.GL_FUNC_ADD, GLC.GL_FUNC_ADD);

    /**
     * Multiply blending: {@code DST_COLOR / ZERO}.
     * Darkens the destination by multiplying it with the source colour.
     */
    public static final CgBlendState MULTIPLY = new CgBlendState(true,
            GLC.GL_DST_COLOR, GLC.GL_ZERO,
            GLC.GL_DST_ALPHA, GLC.GL_ZERO,
            GLC.GL_FUNC_ADD, GLC.GL_FUNC_ADD);

    /**
     * Mask-alpha-multiply: {@code (ZERO, SRC_ALPHA)} for both RGB and alpha.
     * Multiplies the destination's existing color and alpha by this pass's source alpha,
     * ignoring the source's own color entirely — the standard technique for compositing a
     * separately-rendered alpha mask onto an already-drawn offscreen target (draw the mask
     * shape into its own texture, then blend it over the target with this state to "punch out"
     * everywhere the mask's alpha is 0).
     */
    public static final CgBlendState MASK_ALPHA_MULTIPLY = new CgBlendState(true,
            GLC.GL_ZERO, GLC.GL_SRC_ALPHA,
            GLC.GL_ZERO, GLC.GL_SRC_ALPHA,
            GLC.GL_FUNC_ADD, GLC.GL_FUNC_ADD);

    /** Source and destination summed, alpha too: a count, as the overdraw view keeps. */
    public static final CgBlendState SUM = new CgBlendState(true,
            GLC.GL_ONE, GLC.GL_ONE,
            GLC.GL_ONE, GLC.GL_ONE,
            GLC.GL_FUNC_ADD, GLC.GL_FUNC_ADD);

    /** Whether it adds to what is there unscaled ({@code dst} factor ONE): it can only brighten it, never cover it. */
    public boolean adds() {
        return enabled && dstRgb == GLC.GL_ONE;
    }

    /**
     * This blend drawn into a layer of its own whose alpha is the coverage a composite lays it over with: an alpha
     * blend's colour as it is, its alpha over; an additive one's colour as it is, its alpha untouched. Null for any other,
     * which a layer cannot carry; {@link #DISABLED} for none.
     *
     * <pre>{@code
     * CgBlendState inLayer = CgBlendState.ALPHA.layered();   // colour as ALPHA, alpha ONE, ONE_MINUS_SRC_ALPHA
     * }</pre>
     */
    public CgBlendState layered() {
        if (!enabled) return DISABLED;
        if (blendEquationRgb != GLC.GL_FUNC_ADD) return null;
        if (dstRgb == GLC.GL_ONE_MINUS_SRC_ALPHA) return of(true, srcRgb, dstRgb, GLC.GL_ONE, GLC.GL_ONE_MINUS_SRC_ALPHA);
        if (dstRgb == GLC.GL_ONE) return of(true, srcRgb, GLC.GL_ONE, GLC.GL_ZERO, GLC.GL_ONE);
        return null;
    }

    public static CgBlendState of(boolean enabled, int srcRgb, int dstRgb, int srcAlpha, int dstAlpha) {
        return new CgBlendState(enabled, srcRgb, dstRgb, srcAlpha, dstAlpha,
                GLC.GL_FUNC_ADD, GLC.GL_FUNC_ADD);
    }

    @Override
    public String toString() {
        if (!enabled) return "CgBlendState{DISABLED}";
        return "CgBlendState{srcRgb=" + srcRgb + ", dstRgb=" + dstRgb
                + ", srcAlpha=" + srcAlpha + ", dstAlpha=" + dstAlpha + "}";
    }
}
