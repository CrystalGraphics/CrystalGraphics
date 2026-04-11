package io.github.somehussar.crystalgraphics.gl.shader;

import io.github.somehussar.crystalgraphics.api.CgCapabilities;
import io.github.somehussar.crystalgraphics.api.shader.CgShader;
import io.github.somehussar.crystalgraphics.api.shader.CgShaderManager;
import io.github.somehussar.crystalgraphics.api.shader.CgShaderProgram;
import io.github.somehussar.crystalgraphics.api.vertex.CgVertexFormat;
import io.github.somehussar.crystalgraphics.mc.shader.CgShaderManagerImpl;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.BufferUtils;

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
 * @see CoreShaderProgram
 * @see ArbShaderProgram
 */
public final class CgShaderFactory {

       /**
     * Thread-local direct FloatBuffer (16 elements) for serializing JOML matrices
     * without per-call allocation.  Sized for 4×4 (16 floats); 3×3 (9 floats)
     * reuses the same buffer with a smaller limit.
     */
    public static final ThreadLocal<FloatBuffer> JOML_BUFFER = 
               ThreadLocal.withInitial(() -> BufferUtils.createFloatBuffer(16));
    
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
     *       uses {@link CoreShaderProgram#compile(String, String, CgVertexFormat)}.</li>
     *   <li>Otherwise, if {@link CgCapabilities#isArbShaders()} is
     *       {@code true}, uses
     *       {@link ArbShaderProgram#compile(String, String, CgVertexFormat)}.</li>
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
        if (caps.isCoreShaders()) return CoreShaderProgram.compile(vertexSource, fragmentSource, format);
        if (caps.isArbShaders()) return ArbShaderProgram.compile(vertexSource, fragmentSource, format);
        
        throw new UnsupportedOperationException("No shader support available (GL20 and ARB_shader_objects both absent)");
    }

    /**
     * Compiles and links a GLSL program using the best available backend.
     *
     * <p>The selection waterfall is:</p>
     * <ol>
     *   <li>If {@link CgCapabilities#isCoreShaders()} is {@code true},
     *       uses {@link CoreShaderProgram#compile(String, String, CgVertexFormat)}.</li>
     *   <li>Otherwise, if {@link CgCapabilities#isArbShaders()} is
     *       {@code true}, uses
     *       {@link ArbShaderProgram#compile(String, String, CgVertexFormat)}.</li>
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
    
    public static CgShader load(ResourceLocation vertexLocation, ResourceLocation fragmentLocation, CgVertexFormat format) {
        return SHADER_MANAGER.load(vertexLocation, fragmentLocation, format);
    }
    
    public static CgShader load(String vertexLocation, String fragmentLocation) {
        return load(vertexLocation, fragmentLocation, null);
    } 
    
    public static CgShader load(ResourceLocation vertexLocation, ResourceLocation fragmentLocation) {
        return load(vertexLocation, fragmentLocation, null);
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
