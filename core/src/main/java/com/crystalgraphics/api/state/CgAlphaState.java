package com.crystalgraphics.api.state;

import com.github.bsideup.jabel.Desugar;
import com.crystalgraphics.platform.gl.CgGL;

/** Immutable alpha-test state slot for {@link CgRenderState}, as a shader's {@code AlphaTest} declares it. */
@Desugar
public record CgAlphaState(boolean enabled, int func, float cutoff) {

    /** Alpha test disabled. {@code func} is {@code GL_ALWAYS} (unused), {@code cutoff} is 0. */
    public static final CgAlphaState DISABLED = new CgAlphaState(false, CgGL.GL_ALWAYS, 0f);
}
