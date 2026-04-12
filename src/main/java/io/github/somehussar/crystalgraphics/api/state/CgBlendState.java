package io.github.somehussar.crystalgraphics.api.state;

import com.github.bsideup.jabel.Desugar;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;

/**
 * Immutable description of the blend state for a render pass.
 *
 * <p>Encapsulates the GL blend function parameters applied at pass begin.
 * When blending is disabled ({@link #DISABLED}), no blend calls are issued
 * and {@code glDisable(GL_BLEND)} is set. When blending is enabled, the
 * pass calls {@code glEnable(GL_BLEND)} and configures the blend function
 * using the specified source/destination factors.</p>
 *
 * <p>Pre-defined constants cover the most common UI and post-processing
 * blend modes. Custom blend states can be created via the constructor.</p>
 */
@Desugar
public record CgBlendState(boolean enabled, int srcRgb, int dstRgb, int srcAlpha, int dstAlpha) {

    /** No blending. {@code glDisable(GL_BLEND)} is issued. */
    public static final CgBlendState DISABLED = new CgBlendState(false,
            GL11.GL_ONE, GL11.GL_ZERO, GL11.GL_ONE, GL11.GL_ZERO);

    /**
     * Standard alpha blending: {@code SRC_ALPHA / ONE_MINUS_SRC_ALPHA}.
     * Used by UI text, panels, and most 2D content.
     */
    public static final CgBlendState ALPHA = new CgBlendState(true,
            GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
            GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);

    /**
     * Pre-multiplied alpha blending: {@code ONE / ONE_MINUS_SRC_ALPHA}.
     * Used when textures are stored with pre-multiplied alpha.
     */
    public static final CgBlendState PREMULTIPLIED_ALPHA = new CgBlendState(true,
            GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA,
            GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);

    /**
     * Additive blending: {@code SRC_ALPHA / ONE}.
     * Used for glow effects, particle effects, etc.
     */
    public static final CgBlendState ADDITIVE = new CgBlendState(true,
            GL11.GL_SRC_ALPHA, GL11.GL_ONE,
            GL11.GL_ONE, GL11.GL_ONE);

    /**
     * Creates a blend state with separate RGB and alpha factors.
     *
     * @param enabled  whether blending is enabled
     * @param srcRgb   source RGB factor (e.g. {@code GL_SRC_ALPHA})
     * @param dstRgb   destination RGB factor (e.g. {@code GL_ONE_MINUS_SRC_ALPHA})
     * @param srcAlpha source alpha factor
     * @param dstAlpha destination alpha factor
     */
    public CgBlendState {
    }

    /**
     * Applies this blend state to the current GL context.
     *
     * <p>If blending is disabled, calls {@code glDisable(GL_BLEND)}.
     * Otherwise calls {@code glEnable(GL_BLEND)} and sets the blend
     * function via {@code glBlendFuncSeparate}.</p>
     */
    public void apply() {
        if (!enabled) {
            GL11.glDisable(GL11.GL_BLEND);
            return;
        }
        GL11.glEnable(GL11.GL_BLEND);
        GL14.glBlendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
    }

    @Override
    public String toString() {
        if (!enabled) return "CgBlendState{DISABLED}";
        return "CgBlendState{srcRgb=" + srcRgb + ", dstRgb=" + dstRgb
                + ", srcAlpha=" + srcAlpha + ", dstAlpha=" + dstAlpha + "}";
    }
}
