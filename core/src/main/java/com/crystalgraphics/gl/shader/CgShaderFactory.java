package com.crystalgraphics.gl.shader;

import com.crystalgraphics.api.CgCapabilities;
import com.crystalgraphics.api.shader.CgShader;
import com.crystalgraphics.api.shader.CgShaderManager;
import com.crystalgraphics.api.shader.CgShaderProgram;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.mc.shader.CgShaderImpl;
import com.crystalgraphics.mc.shader.CgShaderManagerImpl;
import com.crystalgraphics.util.CgBufferUtils;

import java.nio.FloatBuffer;

/**
 * Factory for creating shader programs using the best available backend.
 *
 * <p>Selection order: Core GL20 &gt; ARB shader objects.
 * If neither is available, throws {@link UnsupportedOperationException}.</p>
 *
 * <p>This class mirrors the waterfall pattern used by the framebuffer
 * factory: the caller supplies detected {@link CgCapabilities} and shader
 * source code, and the factory selects the highest-priority backend that
 * is available on the current hardware.</p>
 *
 * <p>This class is not instantiable.</p>
 *
 * @see CgCapabilities
 * @see CgCoreShaderProgram
 * @see CgArbShaderProgram
 */
public final class CgShaderFactory {

       /**
     * Thread-local direct FloatBuffer (16 elements) for serializing JOML matrices
     * without per-call allocation.  Sized for 4×4 (16 floats); 3×3 (9 floats)
     * reuses the same buffer with a smaller limit.
     */
    public static final ThreadLocal<FloatBuffer> JOML_BUFFER = 
               ThreadLocal.withInitial(() -> CgBufferUtils.createFloatBuffer(16));
    
    /**
     * The lazily-initialized global shader manager singleton.
     * Initialized on the first render tick after the GL context is available.
     */
    public static final CgShaderManager SHADER_MANAGER = new CgShaderManagerImpl();;

    /**
     * Compiles and links a GLSL program using the best available backend.
     *
     * <p>The selection waterfall is:</p>
     * <ol>
     *   <li>If {@link CgCapabilities#isCoreShaders()} is {@code true},
     *       uses {@link CgCoreShaderProgram#compile(String, String, CgVertexFormat)}.</li>
     *   <li>Otherwise, if {@link CgCapabilities#isArbShaders()} is
     *       {@code true}, uses
     *       {@link CgArbShaderProgram#compile(String, String, CgVertexFormat)}.</li>
     *   <li>Otherwise, throws {@link UnsupportedOperationException}.</li>
     * </ol>
     *
     * @param vertexSource   GLSL vertex shader source code
     * @param fragmentSource GLSL fragment shader source code
     * @param format attribute format of the VAO that feeds this shader
     * @return a new owned shader program
     * @throws UnsupportedOperationException if neither GL20 nor ARB shaders are available
     * @throws IllegalStateException if shader compilation or linking fails
     */
    public static CgShaderProgram compile(String vertexSource, String fragmentSource, CgVertexFormat format) {
        CgCapabilities caps = CgCapabilities.detect();
        if (caps.isCoreShaders()) return CgCoreShaderProgram.compile(vertexSource, fragmentSource, format);
        if (caps.isArbShaders()) return CgArbShaderProgram.compile(vertexSource, fragmentSource, format);
        
        throw new UnsupportedOperationException("No shader support available (GL20 and ARB_shader_objects both absent)");
    }

    /**
     * Compiles and links a GLSL program using the best available backend.
     *
     * <p>The selection waterfall is:</p>
     * <ol>
     *   <li>If {@link CgCapabilities#isCoreShaders()} is {@code true},
     *       uses {@link CgCoreShaderProgram#compile(String, String, CgVertexFormat)}.</li>
     *   <li>Otherwise, if {@link CgCapabilities#isArbShaders()} is
     *       {@code true}, uses
     *       {@link CgArbShaderProgram#compile(String, String, CgVertexFormat)}.</li>
     *   <li>Otherwise, throws {@link UnsupportedOperationException}.</li>
     * </ol>
     *
     * @param vertexSource   GLSL vertex shader source code
     * @param fragmentSource GLSL fragment shader source code
     * @return a new owned shader program
     * @throws UnsupportedOperationException if neither GL20 nor ARB shaders are available
     * @throws IllegalStateException if shader compilation or linking fails
     */
    public static CgShaderProgram compile(String vertexSource, String fragmentSource) {
        return compile(vertexSource, fragmentSource, null);
    }

    public static CgShader load(String vertexLocation, String fragmentLocation, CgVertexFormat format) {
        return SHADER_MANAGER.load(vertexLocation, fragmentLocation, format);
    }
    
    public static CgShader load(String vertexLocation, String fragmentLocation) {
        return load(vertexLocation, fragmentLocation, null);
    }
    
    /**
     * Creates a {@link CgShader} compiled directly from inline GLSL source strings,
     * without a vertex format.
     *
     * <p>Equivalent to {@link #fromSource(String, String, CgVertexFormat)} with
     * {@code format = null}.</p>
     *
     * @param vertSrc GLSL vertex shader source
     * @param fragSrc GLSL fragment shader source
     * @return a compiled (or failed-to-compile) {@link CgShader} handle
     */
    public static CgShader fromSource(String vertSrc, String fragSrc) {
        return fromSource(vertSrc, fragSrc, null);
    }

    /**
     * Creates a {@link CgShader} compiled directly from inline GLSL source strings.
     *
     * <p>The returned shader is <em>not</em> registered in the shader manager cache.
     * It compiles eagerly on construction. The GLSL source can be replaced at any
     * time via {@link CgShader#setSource(String, String)}, which marks the shader
     * dirty and triggers a recompile on the next {@link CgShader#bind()} call —
     * making this the preferred entry point for node-graph codegen workflows.</p>
     *
     * @param vertSrc GLSL vertex shader source
     * @param fragSrc GLSL fragment shader source
     * @param format  vertex attribute format for {@code glBindAttribLocation}, or {@code null}
     * @return a compiled (or failed-to-compile) {@link CgShader} handle
     */
    public static CgShader fromSource(String vertSrc, String fragSrc, CgVertexFormat format) {
        CgShaderImpl shader = CgShaderImpl.fromSource(vertSrc, fragSrc, format);

        shader.recompile();
        return shader;
    }
    
    /**
     * Private constructor to prevent instantiation.
     *
     * @throws AssertionError always
     */
    private CgShaderFactory() {
        throw new AssertionError();
    }
}
