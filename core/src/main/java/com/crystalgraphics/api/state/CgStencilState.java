package com.crystalgraphics.api.state;

import com.github.bsideup.jabel.Desugar;
import org.lwjgl.opengl.GL11;

/**
 * Immutable stencil-test state slot for {@link CgRenderState}.
 *
 * <p>Encapsulates the three GL calls needed to configure the stencil test:</p>
 * <ul>
 *   <li>{@code glStencilFunc(compFunc, ref, readMask)} — comparison function and reference</li>
 *   <li>{@code glStencilMask(writeMask)} — which stencil bits are written</li>
 *   <li>{@code glStencilOp(failOp, zfailOp, passOp)} — what happens on each stencil/depth outcome</li>
 * </ul>
 *
 * <p>Note the argument order of {@code glStencilOp}: sfail, dpfail, dppass.
 * The field names match GL conventions: {@code failOp} = sfail, {@code zfailOp} = dpfail,
 * {@code passOp} = dppass.</p>
 *
 * <p>{@link #DISABLED} leaves the stencil test off and the write mask fully open (0xFF).</p>
 *
 * <p>{@link #clear()} always disables the stencil test and resets the write mask to 0xFF,
 * ensuring stencil state does not leak between passes.</p>
 */
@Desugar
public record CgStencilState(
        boolean enabled,
        int     ref,
        int     readMask,
        int     writeMask,
        int     compFunc,
        int     passOp,
        int     failOp,
        int     zfailOp
) {

    /**
     * Stencil test disabled. Write mask is fully open (0xFF) so subsequent clears are unobstructed.
     * All operation fields are set to {@code GL_KEEP} / {@code GL_ALWAYS} — harmless if accidentally applied.
     */
    public static final CgStencilState DISABLED = new CgStencilState(
            false, 0, 0xFF, 0xFF,
            GL11.GL_ALWAYS, GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);

    /**
     * Applies this stencil state to the current GL context.
     *
     * <p>If enabled, calls {@code glEnable(GL_STENCIL_TEST)}, sets the comparison function,
     * write mask, and stencil operations. If disabled, calls {@code glDisable(GL_STENCIL_TEST)}.</p>
     */
    public void apply() {
        if (!enabled) {
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            return;
        }
        GL11.glEnable(GL11.GL_STENCIL_TEST);
        GL11.glStencilFunc(compFunc, ref, readMask);
        GL11.glStencilMask(writeMask);
        GL11.glStencilOp(failOp, zfailOp, passOp);
    }

    /**
     * Restores GL stencil defaults: test disabled, write mask 0xFF.
     * Always resets the write mask so subsequent depth/stencil clears are unobstructed.
     */
    public void clear() {
        GL11.glDisable(GL11.GL_STENCIL_TEST);
        GL11.glStencilMask(0xFF);
    }
}
