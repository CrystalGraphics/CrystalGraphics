package com.crystalgraphics.api.state;

import com.github.bsideup.jabel.Desugar;
import com.crystalgraphics.platform.gl.CgCapabilities;
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

    /**
     * Makes this state current.
     *
     * <p>Calls {@code CgGL} directly; {@code CgGL} decides whether each call reaches the driver, so a
     * repeated apply of the same value costs nothing.</p>
     */
    public void apply() {
        if (isCore()) return;
        if (enabled) {
            CgGL.glEnable(CgGL.GL_ALPHA_TEST);
            CgGL.glAlphaFunc(func, cutoff);
        } else {
            CgGL.glDisable(CgGL.GL_ALPHA_TEST);
        }
    }

    public void clear() {
        if (isCore()) return;
        CgGL.glDisable(CgGL.GL_ALPHA_TEST);
    }

    private static Boolean CORE = Boolean.FALSE;

    private static boolean isCore(){
        if (!CORE) CORE = CgCapabilities.detect().isCoreProfile();
        return CORE;
    }
}
