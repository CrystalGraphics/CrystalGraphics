package com.crystalgraphics.api.buffer;

import com.crystalgraphics.api.shader.CgShaderProgram;

/**
 * Base contract for GPU-resident data blocks accessible from shader programs.
 *
 * <p>All VBO, EBO, SSBO, TBO, and UBO-backed buffer types in CrystalGraphics implement this
 * interface. It mirrors the ownership and lifecycle model of {@link CgShaderProgram}:
 * bind, unbind, get the underlying GL object ID, and idempotent delete.</p>
 *
 * <p>Implementations must document their designated GL binding point and the
 * specific GL call family (GL43 / ARB / UBO) used for binding.</p>
 */
public interface CgObjectBuffer {

    /** Binds this buffer to its designated GL binding point for use in draw calls. */
    void bind();

    /** Unbinds this buffer from its GL binding point. */
    void unbind();

    /** Returns the raw GL buffer object ID. */
    int getGlBufferId();

    /** Frees all GPU resources owned by this buffer. Idempotent. */
    void delete();

    /** Returns true if {@link #delete()} has been called. */
    boolean isDeleted();
}
