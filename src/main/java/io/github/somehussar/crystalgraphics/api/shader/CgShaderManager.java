package io.github.somehussar.crystalgraphics.api.shader;

import io.github.somehussar.crystalgraphics.api.vertex.CgVertexFormat;
import net.minecraft.util.ResourceLocation;

import java.util.Map;

/**
 * Factory and manager for creating and managing compiled shaders.
 *
 * <p>This interface provides the public entry point for the shader manager system.
 * It handles loading shader sources from Minecraft resources, preprocessing,
 * and compilation via the underlying CrystalGraphics backend.</p>
 *
 * <p>Usage pattern:</p>
 * <pre>{@code
 * CgShaderManager manager = CrystalGraphics.getShaderManager();
 * CgShader shader = manager.load(
 *     new ResourceLocation("modid", "shader/custom.vert"),
 *     new ResourceLocation("modid", "shader/custom.frag"),
 *     Collections.emptyMap()  // defines
 * );
 *
 * try (CgShaderScope scope = shader.bindScoped()) {
 *     // Render with the shader...
 * }
 * }</pre>
 *
 * <h3>Failure Semantics</h3>
 * <p>If shader compilation fails, {@link #load(ResourceLocation, ResourceLocation, Map)}
 * returns a managed shader with {@link CgShader#isCompiled()} == false.
 * Bind operations on non-compiled shaders are pure no-ops (no GL mutations).</p>
 *
 * <h3>Cache Key Uniqueness</h3>
 * <p>Each loaded shader is identified by its cache key (vertex location, fragment location,
 * and preprocessor defines). Identical keys produce identical cache entries.</p>
 *
 * <h3>Thread Safety</h3>
 * <p>Not thread-safe. All methods must be called on the render thread.</p>
 *
 * @see CgShader
 * @see CgShaderCacheKey
 */
public interface CgShaderManager {

    /**
     * Loads or retrieves a cached managed shader.
     *
     * <p>If a shader with the same cache key (vertex, fragment, defines) has been
     * previously loaded, returns the cached managed shader instance.
     * Otherwise, creates a new managed shader by loading sources from the
     * resource manager, preprocessing with the given defines, and compiling.</p>
     *
     * <p>If compilation fails, returns a managed shader with
     * {@link CgShader#isCompiled()} == false. The returned handle is
     * always valid and safe to use; bind operations on non-compiled shaders
     * are pure no-ops.</p>
     *
     * @param vertexPath   the asset path of the vertex shader source
     * @param fragmentPath the asset path of the fragment shader source
     * @param defines      a map of preprocessor defines (name -> value);
     *                     may be empty or null (treated as empty)
     * @return a managed shader handle, valid even if compilation failed
     * @throws NullPointerException if either path is null
     */
    CgShader load(String vertexPath, String fragmentPath, CgVertexFormat format, Map<String, String> defines);

    /**
     * Loads or retrieves a cached managed shader using {@link ResourceLocation}s.
     *
     * <p>Convenience overload that converts each {@link ResourceLocation} to an
     * asset path string and delegates to {@link #load(String, String, CgVertexFormat, Map)}.</p>
     *
     * @param vertexLocation   the {@code ResourceLocation} of the vertex shader source
     * @param fragmentLocation the {@code ResourceLocation} of the fragment shader source
     * @param defines          a map of preprocessor defines (name -> value);
     *                         may be empty or null (treated as empty)
     * @return a managed shader handle, valid even if compilation failed
     * @throws NullPointerException if either location is null
     */
    default CgShader load(ResourceLocation vertexLocation, ResourceLocation fragmentLocation, CgVertexFormat format, Map<String, String> defines){
        return load(vertexLocation.toString(), fragmentLocation.toString(), format, defines);
    }
    
    /**
     * Loads or retrieves a cached managed shader without preprocessor defines.
     *
     * <p>Equivalent to calling {@link #load(ResourceLocation, ResourceLocation, CgVertexFormat, Map)}
     * with an empty defines map.</p>
     *
     * @param vertexLocation   the {@code ResourceLocation} of the vertex shader source
     * @param fragmentLocation the {@code ResourceLocation} of the fragment shader source
     * @return a managed shader handle, valid even if compilation failed
     * @throws NullPointerException if either location is null
     */
    default CgShader load(ResourceLocation vertexLocation, ResourceLocation fragmentLocation, CgVertexFormat format) {
        return load(vertexLocation, fragmentLocation, format, null);
    }

    /**
     * Loads or retrieves a cached managed shader without preprocessor defines.
     *
     * <p>Equivalent to calling {@link #load(ResourceLocation, ResourceLocation, CgVertexFormat, Map)}
     * with an empty defines map.</p>
     *
     * @param vertexLocation   the mod asset string location of the vertex shader source
     * @param fragmentLocation the mod asset string location of the fragment shader source
     * @return a managed shader handle, valid even if compilation failed
     * @throws NullPointerException if either location is null
     */
    default CgShader load(String vertexLocation, String fragmentLocation, CgVertexFormat format) {
        return load(vertexLocation, fragmentLocation, format, null);
    }

   /**
     * Loads or retrieves a cached managed shader without preprocessor defines.
     *
     * @param vertexLocation   the {@code ResourceLocation} of the vertex shader source
     * @param fragmentLocation the {@code ResourceLocation} of the fragment shader source
     * @return a managed shader handle, valid even if compilation failed
     * @throws NullPointerException if either location is null
     */
    default CgShader load(ResourceLocation vertexLocation, ResourceLocation fragmentLocation) {
        return load(vertexLocation, fragmentLocation, null, null);
    }

    /**
     * Loads or retrieves a cached managed shader without preprocessor defines.
     *
     * @param vertexLocation   the mod asset string location of the vertex shader source
     * @param fragmentLocation the mod asset string location of the fragment shader source
     * @return a managed shader handle, valid even if compilation failed
     * @throws NullPointerException if either location is null
     */
    default CgShader load(String vertexLocation, String fragmentLocation) {
        return load(vertexLocation, fragmentLocation, null, null);
    }
    /**
     * Retrieves a previously loaded managed shader by its cache key.
     *
     * <p>Returns null if no shader with the given key has been loaded.</p>
     *
     * @param cacheKey the cache key
     * @return the managed shader, or null if not found
     */
    CgShader getIfLoaded(CgShaderCacheKey cacheKey);

    /**
     * Reloads (recompiles) all managed shaders.
     *
     * <p>This method marks all managed shaders as dirty, triggering a recompile
     * on their next bind. This is intended for use with reload listeners
     * (e.g., when Minecraft's resource manager reports resource changes).</p>
     */
    void reloadAll();

    /**
     * Cleans up all managed resources.
     *
     * <p>Deletes all compiled shader programs and clears the cache.
     * After this call, the manager should no longer be used.</p>
     */
    void deleteAll();
}
