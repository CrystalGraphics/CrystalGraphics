package com.crystalgraphics.gl.shader;

import com.crystalgraphics.api.vertex.CgVertexFormat;

/**
 * Makes the engine's linked programs ({@link CgShader}) from the sources its compilers generate. Internal: a consumer's
 * shader is a material or a kernel.
 *
 * <pre>{@code
 * CgShader now = CgShaderFactory.fromSource(vertex, fragment, format);     // compiled and linked before it returns
 * CgShader later = CgShaderFactory.submit(vertex, fragment, format);      // linked on the driver's threads
 * }</pre>
 */
public final class CgShaderFactory {

    private CgShaderFactory() {}

    /** A program compiled and linked now; {@code format} names its vertex inputs, or null for none. */
    public static CgShader fromSource(String vertexSource, String fragmentSource, CgVertexFormat format) {
        CgShader shader = new CgShader(vertexSource, fragmentSource, format);
        shader.compile();
        return shader;
    }

    /**
     * A program whose link the driver runs while frames go on; the sources are used as given. Anything asked of it
     * before {@link CgShader#isReady} waits for the driver.
     */
    public static CgShader submit(String vertexSource, String fragmentSource, CgVertexFormat format) {
        CgShader shader = new CgShader(vertexSource, fragmentSource, format);
        shader.submit();
        return shader;
    }
}
