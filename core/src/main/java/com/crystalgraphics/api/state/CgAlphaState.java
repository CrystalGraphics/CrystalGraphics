package com.crystalgraphics.api.state;

import com.github.bsideup.jabel.Desugar;
import com.crystalgraphics.platform.gl.CgGL;

/**
 * Immutable alpha-test state slot for {@link CgRenderState}.
 *
 * <p>{@link #clear()} unconditionally disables {@code GL_ALPHA_TEST} to prevent
 * state leaks between passes.</p>
 */
@Desugar
public record CgAlphaState(boolean enabled, int func, float cutoff) {

    /** Alpha test disabled. {@code func} is {@code GL_ALWAYS} (unused), {@code cutoff} is 0. */
    public static final CgAlphaState DISABLED = new CgAlphaState(false, CgGL.GL_ALWAYS, 0f);

    public void apply() {
        if (enabled) {
            CgGL.glEnable(CgGL.GL_ALPHA_TEST);
            CgGL.glAlphaFunc(func, cutoff);
        } else {
            CgGL.glDisable(CgGL.GL_ALPHA_TEST);
        }
    }

    public void clear() {
        CgGL.glDisable(CgGL.GL_ALPHA_TEST);
    }
}
