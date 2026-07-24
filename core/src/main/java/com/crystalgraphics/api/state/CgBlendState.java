package com.crystalgraphics.api.state;


import com.crystalgraphics.platform.gl.CgGL;
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
            CgGL.GL_ONE, CgGL.GL_ZERO, CgGL.GL_ONE, CgGL.GL_ZERO,
            CgGL.GL_FUNC_ADD, CgGL.GL_FUNC_ADD);

    /**
     * Standard alpha blending: {@code SRC_ALPHA / ONE_MINUS_SRC_ALPHA}.
     * Used by UI text, panels, and most 2D content.
     */
    public static final CgBlendState ALPHA = new CgBlendState(true,
            CgGL.GL_SRC_ALPHA, CgGL.GL_ONE_MINUS_SRC_ALPHA,
            CgGL.GL_ONE, CgGL.GL_ONE_MINUS_SRC_ALPHA,
            CgGL.GL_FUNC_ADD, CgGL.GL_FUNC_ADD);

    /**
     * Pre-multiplied alpha blending: {@code ONE / ONE_MINUS_SRC_ALPHA}.
     * Used when textures are stored with pre-multiplied alpha.
     */
    public static final CgBlendState PREMULTIPLIED_ALPHA = new CgBlendState(true,
            CgGL.GL_ONE, CgGL.GL_ONE_MINUS_SRC_ALPHA,
            CgGL.GL_ONE, CgGL.GL_ONE_MINUS_SRC_ALPHA,
            CgGL.GL_FUNC_ADD, CgGL.GL_FUNC_ADD);

    /**
     * Additive blending: {@code SRC_ALPHA / ONE}.
     * Used for glow effects, particle effects, etc.
     */
    public static final CgBlendState ADDITIVE = new CgBlendState(true,
            CgGL.GL_SRC_ALPHA, CgGL.GL_ONE,
            CgGL.GL_ONE, CgGL.GL_ONE,
            CgGL.GL_FUNC_ADD, CgGL.GL_FUNC_ADD);

    /**
     * Multiply blending: {@code DST_COLOR / ZERO}.
     * Darkens the destination by multiplying it with the source colour.
     */
    public static final CgBlendState MULTIPLY = new CgBlendState(true,
            CgGL.GL_DST_COLOR, CgGL.GL_ZERO,
            CgGL.GL_DST_ALPHA, CgGL.GL_ZERO,
            CgGL.GL_FUNC_ADD, CgGL.GL_FUNC_ADD);

    /**
     * Mask-alpha-multiply: {@code (ZERO, SRC_ALPHA)} for both RGB and alpha.
     * Multiplies the destination's existing color and alpha by this pass's source alpha,
     * ignoring the source's own color entirely — the standard technique for compositing a
     * separately-rendered alpha mask onto an already-drawn offscreen target (draw the mask
     * shape into its own texture, then blend it over the target with this state to "punch out"
     * everywhere the mask's alpha is 0).
     */
    public static final CgBlendState MASK_ALPHA_MULTIPLY = new CgBlendState(true,
            CgGL.GL_ZERO, CgGL.GL_SRC_ALPHA,
            CgGL.GL_ZERO, CgGL.GL_SRC_ALPHA,
            CgGL.GL_FUNC_ADD, CgGL.GL_FUNC_ADD);

    /**
     * Creates a blend state with separate RGB and alpha factors and default
     * {@code GL_FUNC_ADD} equations for both channels.
     *
     * @param enabled  whether blending is enabled
     * @param srcRgb   source RGB factor (e.g. {@code GL_SRC_ALPHA})
     * @param dstRgb   destination RGB factor (e.g. {@code GL_ONE_MINUS_SRC_ALPHA})
     * @param srcAlpha source alpha factor
     * @param dstAlpha destination alpha factor
     * @return a new {@code CgBlendState} with {@code GL_FUNC_ADD} for both blend equations
     */
    public static CgBlendState of(boolean enabled, int srcRgb, int dstRgb, int srcAlpha, int dstAlpha) {
        return new CgBlendState(enabled, srcRgb, dstRgb, srcAlpha, dstAlpha,
                CgGL.GL_FUNC_ADD, CgGL.GL_FUNC_ADD);
    }

    /**
     * Applies this blend state to the current GL context.
     *
     * <p>If blending is disabled, calls {@code glDisable(GL_BLEND)}.
     * Otherwise calls {@code glEnable(GL_BLEND)}, sets the blend function
     * via {@code glBlendFuncSeparate}, and sets the blend equations via
     * {@code glBlendEquationSeparate}.</p>
     */
    public void apply() {
        if (!enabled) {
            CgGL.glDisable(CgGL.GL_BLEND);
            return;
        }
        CgGL.glEnable(CgGL.GL_BLEND);
        CgGL.glBlendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
        CgGL.glBlendEquationSeparate(blendEquationRgb, blendEquationAlpha);
    }

    /**
     * Restores blend defaults: disables blending and resets both blend equations to
     * {@code GL_FUNC_ADD}. For use in {@code CgRenderState.clear()}.
     */
    public static void clearToDefault() {
        CgGL.glDisable(CgGL.GL_BLEND);
        CgGL.glBlendEquationSeparate(CgGL.GL_FUNC_ADD, CgGL.GL_FUNC_ADD);
    }

    @Override
    public String toString() {
        if (!enabled) return "CgBlendState{DISABLED}";
        return "CgBlendState{srcRgb=" + srcRgb + ", dstRgb=" + dstRgb
                + ", srcAlpha=" + srcAlpha + ", dstAlpha=" + dstAlpha + "}";
    }
}
