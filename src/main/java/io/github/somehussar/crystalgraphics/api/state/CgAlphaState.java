package io.github.somehussar.crystalgraphics.api.state;

import com.github.bsideup.jabel.Desugar;
import org.lwjgl.opengl.GL11;

/**
 * Immutable alpha-test state slot for {@link CgRenderState}.
 *
 * <p>{@link #clear()} unconditionally disables {@code GL_ALPHA_TEST} to prevent
 * state leaks between passes.</p>
 */
@Desugar
public record CgAlphaState(boolean enabled, int func, float cutoff) {

    /** Alpha test disabled. {@code func} is {@code GL_ALWAYS} (unused), {@code cutoff} is 0. */
    public static final CgAlphaState DISABLED = new CgAlphaState(false, GL11.GL_ALWAYS, 0f);

    public void apply() {
        if (enabled) {
            GL11.glEnable(GL11.GL_ALPHA_TEST);
            GL11.glAlphaFunc(func, cutoff);
        } else {
            GL11.glDisable(GL11.GL_ALPHA_TEST);
        }
    }

    public void clear() {
        GL11.glDisable(GL11.GL_ALPHA_TEST);
    }
}
