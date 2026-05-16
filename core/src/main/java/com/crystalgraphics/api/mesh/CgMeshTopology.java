package com.crystalgraphics.api.mesh;

import com.crystalgraphics.gl.mesh.CgMesh;
import com.crystalgraphics.platform.gl.CgGL;

/**
 * Primitive topology for mesh rendering.
 *
 * <p>Maps logical topology names to GL draw mode constants. Used by
 * {@link CgMeshData} (CPU) and {@link CgMesh} (GPU)
 * to avoid scattering raw GL constants through mesh code.</p>
 */
public enum CgMeshTopology {
    TRIANGLES(CgGL.GL_TRIANGLES),
    TRIANGLE_STRIP(CgGL.GL_TRIANGLE_STRIP),
    LINES(CgGL.GL_LINES),
    LINE_STRIP(CgGL.GL_LINE_STRIP),
    POINTS(CgGL.GL_POINTS);

    private final int glMode;

    CgMeshTopology(int glMode) {
        this.glMode = glMode;
    }

    /** Returns the OpenGL draw mode constant (e.g. {@code CgGL.GL_TRIANGLES}). */
    public int getGlMode() {
        return glMode;
    }
}
