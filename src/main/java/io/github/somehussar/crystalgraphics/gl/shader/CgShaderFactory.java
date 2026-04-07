package io.github.somehussar.crystalgraphics.gl.shader;

import io.github.somehussar.crystalgraphics.api.CgCapabilities;
import io.github.somehussar.crystalgraphics.api.shader.CgShader;
import io.github.somehussar.crystalgraphics.api.shader.CgShaderManager;
import io.github.somehussar.crystalgraphics.api.shader.CgShaderProgram;
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
     *       uses {@link CoreShaderProgram#compile(String, String)}.</li>
     *   <li>Otherwise, if {@link CgCapabilities#isArbShaders()} is
     *       {@code true}, uses
     *       {@link ArbShaderProgram#compile(String, String)}.</li>
     *   <li>Otherwise, throws {@link UnsupportedOperationException}.</li>
     * </ol>
     *
     * @param caps           detected GL capabilities (from
     *                       {@link CgCapabilities#detect()})
     * @param vertexSource   GLSL vertex shader source code
     * @param fragmentSource GLSL fragment shader source code
     * @return a new owned shader program
     * @throws UnsupportedOperationException if neither GL20 nor ARB shaders
     *         are available
     * @throws IllegalStateException if shader compilation or linking fails
     */
    public static CgShaderProgram compile(CgCapabilities caps,
                                          String vertexSource,
                                          String fragmentSource) {
        if (caps.isCoreShaders()) return CoreShaderProgram.compile(vertexSource, fragmentSource);
        if (caps.isArbShaders()) return ArbShaderProgram.compile(vertexSource, fragmentSource);
        
        throw new UnsupportedOperationException("No shader support available (GL20 and ARB_shader_objects both absent)");
    }

    public static CgShader load(String vertexLocation, String fragmentLocation) {
        return SHADER_MANAGER.load(vertexLocation, fragmentLocation);
    } 
    
    public static CgShader load(ResourceLocation vertexLocation, ResourceLocation fragmentLocation) {
        return SHADER_MANAGER.load(vertexLocation, fragmentLocation);
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
