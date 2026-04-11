package io.github.somehussar.crystalgraphics.mc.shader;

import io.github.somehussar.crystalgraphics.api.shader.CgShader;
import io.github.somehussar.crystalgraphics.api.shader.CgShaderCacheKey;
import io.github.somehussar.crystalgraphics.api.shader.CgShaderManager;
import io.github.somehussar.crystalgraphics.api.vertex.CgVertexFormat;
import net.minecraft.util.ResourceLocation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Concrete implementation of {@link CgShaderManager} backed by a
 * {@link CgShaderCacheKey}-keyed map.
 *
 * <p>Each call to {@link #load(String, String, CgVertexFormat, Map)} constructs a cache key
 * from the vertex path, fragment path, and preprocessor defines. If the cache
 * already contains an entry for that key, the existing {@link CgShader} is
 * returned. Otherwise, a new {@link CgShaderImpl} is created, stored in the
 * cache, and returned.</p>
 *
 * <p>The {@link ResourceLocation} overload converts to string asset paths and
 * delegates to the primary String-based method, ensuring a single cache
 * regardless of how paths are specified.</p>
 *
 * <p>The newly created managed shader is <em>not</em> compiled eagerly;
 * compilation is deferred until the first {@code bind()} call (lazy
 * compile-on-bind). This avoids blocking the caller during resource reload
 * events and prevents compile failures from throwing during construction.</p>
 *
 * <h3>Reload</h3>
 * <p>{@link #reloadAll()} marks every cached shader as dirty. The actual
 * recompile happens on the next {@code bind()} of each individual shader,
 * which avoids mid-render program swaps when a reload fires at an unsafe time.</p>
 *
 * <h3>Thread Safety</h3>
 * <p>Not thread-safe. All methods must be called on the render thread.</p>
 *
 * @see CgShaderManager
 * @see CgShaderImpl
 */
public final class CgShaderManagerImpl implements CgShaderManager {

    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphics");

    /** Cache of managed shaders keyed by their deterministic cache key. */
    private final Map<CgShaderCacheKey, CgShader> cache = new HashMap<>();

    /**
     * Creates a new shader manager.
     *
     * @throws NullPointerException if caps is null
     */
    public CgShaderManagerImpl() {
        CgShaderReloadHook.trackManager(this);
    }

    @Override
    public CgShader load(String vertexPath, String fragmentPath, CgVertexFormat format, Map<String, String> defines) {
        Objects.requireNonNull(vertexPath, "vertexPath must not be null");
        Objects.requireNonNull(fragmentPath, "fragmentPath must not be null");

        Map<String, String> safeDefines = defines != null ? defines : Collections.emptyMap();
        CgShaderCacheKey key = new CgShaderCacheKey(vertexPath, fragmentPath, safeDefines);

        CgShader existing = cache.get(key);
        if (existing != null) return existing;
        
        CgShaderImpl shader = new CgShaderImpl(vertexPath, fragmentPath, format, safeDefines);
        cache.put(key, shader);
        shader.recompile(); 
        
        LOGGER.debug("Registered managed shader: {}", key);
        return shader;
    }
    
    @Override
    public CgShader getIfLoaded(CgShaderCacheKey cacheKey) {
        return cache.get(cacheKey);
    }

    @Override
    public void reloadAll() {
        LOGGER.info("Reloading all managed shaders ({} entries)", cache.size());
        for (CgShader shader : cache.values()) shader.markDirty();
    }

    @Override
    public void deleteAll() {
        LOGGER.info("Deleting all managed shaders ({} entries)", cache.size());
        for (CgShader shader : cache.values()) shader.delete();
        cache.clear();
    }
}
