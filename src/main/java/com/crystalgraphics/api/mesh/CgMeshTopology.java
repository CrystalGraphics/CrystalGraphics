package com.crystalgraphics.api.mesh;

import com.crystalgraphics.gl.mesh.CgMesh;
import org.lwjgl.opengl.GL11;

/**
 * Primitive topology for mesh rendering.
 *
 * <p>Maps logical topology names to GL draw mode constants. Used by
 * {@link CgMeshData} (CPU) and {@link CgMesh} (GPU)
 * to avoid scattering raw GL constants through mesh code.</p>
 */
public enum CgMeshTopology {
    TRIANGLES(GL11.GL_TRIANGLES),
    TRIANGLE_STRIP(GL11.GL_TRIANGLE_STRIP),
    LINES(GL11.GL_LINES),
    LINE_STRIP(GL11.GL_LINE_STRIP),
    POINTS(GL11.GL_POINTS);

    private final int glMode;

    CgMeshTopology(int glMode) {
        this.glMode = glMode;
    }

    /** Returns the OpenGL draw mode constant (e.g. {@code GL11.GL_TRIANGLES}). */
    public int getGlMode() {
        return glMode;
    }
}
