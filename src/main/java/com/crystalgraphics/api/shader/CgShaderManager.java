package com.crystalgraphics.api.shader;

import com.crystalgraphics.api.vertex.CgVertexFormat;
import net.minecraft.util.ResourceLocation;

/**
 * Factory and cache for managed shaders.
 *
 * <p>Defines are no longer passed at load time — they are the caller's concern
 * and applied post-load via {@link CgShader#preprocess(CgShaderPreprocessor)}.</p>
 */
public interface CgShaderManager {

    /**
     * Loads or retrieves a cached managed shader by asset path and vertex format.
     *
     * @param vertexPath   asset path of the vertex shader
     * @param fragmentPath asset path of the fragment shader
     * @param format       vertex attribute format for {@code glBindAttribLocation}, or {@code null}
     * @return a managed shader handle, valid even if compilation failed
     */
    CgShader load(String vertexPath, String fragmentPath, CgVertexFormat format);

    /** Convenience overload — no vertex format. */
    default CgShader load(String vertexPath, String fragmentPath) {
        return load(vertexPath, fragmentPath, null);
    }

    /** {@link ResourceLocation} overload with explicit vertex format. */
    default CgShader load(ResourceLocation vertexLocation, ResourceLocation fragmentLocation, CgVertexFormat format) {
        return load(vertexLocation.toString(), fragmentLocation.toString(), format);
    }

    /** {@link ResourceLocation} overload — no vertex format. */
    default CgShader load(ResourceLocation vertexLocation, ResourceLocation fragmentLocation) {
        return load(vertexLocation.toString(), fragmentLocation.toString(), null);
    }

    /**
     * Retrieves a previously loaded managed shader by its cache key, or {@code null} if not found.
     */
    CgShader getIfLoaded(CgShaderCacheKey cacheKey);

    /**
     * Marks all cached shaders dirty, scheduling a recompile on their next {@code bind()}.
     */
    void reloadAll();

    /**
     * Deletes all managed shaders and clears the cache.
     */
    void deleteAll();
}
