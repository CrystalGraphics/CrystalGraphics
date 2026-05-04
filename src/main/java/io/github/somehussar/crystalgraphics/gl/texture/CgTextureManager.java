package io.github.somehussar.crystalgraphics.gl.texture;

import io.github.somehussar.crystalgraphics.api.texture.CgTexture;
import io.github.somehussar.crystalgraphics.api.texture.CgTextureSpec;
import io.github.somehussar.crystalgraphics.util.io.CgTextureIO;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Singleton manager for texture caching, lifecycle, and resource cleanup.
 *
 * <p>Caches all texture types ({@link CgTexture2D}, {@link CgTexture2DArray},
 * {@link CgTexture3D}, {@link CgTextureCubemap}) keyed by a stable string key.
 * For single-path textures the key is the asset path. For multi-path textures
 * (arrays, 3D, cubemaps) the key is the paths joined by {@code \0}.</p>
 *
 * <h3>Reload</h3>
 * <p>Reload is fully in-place: {@link #reloadAll()} calls {@link CgTexture#reload()}
 * on each cached texture. Each texture knows how to reload itself from its
 * source paths (stored at construction time). Procedural/dynamic textures
 * (no source path) silently no-op on reload. The cache entries are never
 * replaced — existing Java references always stay valid.</p>
 *
 * <h3>Fallback</h3>
 * <p>When a 2D texture path fails to load, {@link #getOrCreate(String)} and
 * {@link #getOrCreate(String, CgTextureSpec)} return a shared 8×8
 * purple/black checkerboard texture instead of {@code null}. The fallback is
 * created lazily on first use and freed with the rest of the cache on
 * {@link #freeAll()}.</p>
 *
 * <h3>Lifecycle hooks</h3>
 * <ul>
 *   <li>{@link #freeAll()} — called by {@code CgGraphicsLifecycle.destroyContext()}</li>
 *   <li>{@link #reloadAll()} — called by {@code CgAssetReloader} on F3+T</li>
 * </ul>
 */
public final class CgTextureManager {

    private static final Logger LOGGER = Logger.getLogger(CgTextureManager.class.getName());

    private static final CgTextureManager INSTANCE = new CgTextureManager();

    /** Joins multi-path keys with a character that cannot appear in asset paths. */
    static final String PATH_SEPARATOR = "\0";

    private final Map<String, CgTexture> cache = new HashMap<>();

    /**
     * Shared fallback texture: 8×8 purple/black checkerboard. Created lazily;
     * freed in {@link #freeAll()}. Never put into {@link #cache}.
     */
    private CgTexture2D fallback;

    private CgTextureManager() {}

    public static CgTextureManager get() {
        return INSTANCE;
    }

    // ── Core API ──────────────────────────────────────────────────────────────

    /**
     * Returns the cached texture for {@code key}, or {@code null} if absent or deleted.
     */
    public CgTexture get(String key) {
        CgTexture t = cache.get(key);
        return (t != null && !t.isDeleted()) ? t : null;
    }

    /**
     * Returns the cached texture for {@code key}. On a cache miss, calls
     * {@code loader} to create it, stores the result, and returns it.
     *
     * <p>The loader is invoked once. Reload is handled in-place by the texture
     * itself via {@link CgTexture#reload()} — the loader is not stored.</p>
     *
     * @return the texture, or {@code null} if the loader returned null or threw
     */
    public CgTexture getOrCreate(String key, Supplier<CgTexture> loader) {
        CgTexture cached = get(key);
        if (cached != null) return cached;

        CgTexture texture;
        try {
            texture = loader.get();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[CgTextureManager] Failed to create texture: " + key, e);
            return fallback;
        }

        if (texture == null) {
            LOGGER.log(Level.WARNING, "[CgTextureManager] Loader returned null for: {0}", key);
            return fallback;
        }

        cache.put(key, texture);
        return texture;
    }

    // ── 2D convenience ────────────────────────────────────────────────────────

    /**
     * Gets or creates a cached 2D texture for {@code path} using {@link CgTextureSpec#RGBA8_LINEAR}.
     * Returns the {@link #getFallback() fallback checkerboard} if loading fails.
     */
    public CgTexture2D getOrCreate(String path) {
        return getOrCreate(path, CgTextureSpec.RGBA8_LINEAR);
    }

    /**
     * Gets or creates a cached 2D texture for {@code path}.
     * On a cache hit the cached texture is returned regardless of spec — first caller wins.
     * Returns the {@link #getFallback() fallback checkerboard} if loading fails.
     */
    public CgTexture2D getOrCreate(String path, CgTextureSpec spec) {
        if (path == null || path.isEmpty()) return getFallback();
        CgTexture result = getOrCreate(path, () -> CgTexture2D.createDirect(path, spec));
        return result instanceof CgTexture2D ? (CgTexture2D) result : getFallback();
    }

    /**
     * Gets or creates a 2D texture for {@code path} and immediately binds it.
     * Returns the {@link #getFallback() fallback checkerboard} if loading fails.
     */
    public CgTexture2D bind(String path) {
        CgTexture2D texture = getOrCreate(path);
        texture.bind();
        return texture;
    }

    // ── Manual registration ───────────────────────────────────────────────────

    /**
     * Registers a texture under {@code key}. The texture will be freed on
     * {@link #freeAll()} and reloaded in-place by {@link #reloadAll()} if it
     * has source paths (set at construction time).
     */
    public void register(String key, CgTexture texture) {
        cache.put(key, texture);
    }

    /** @return {@code true} if a valid (non-deleted) texture is cached under {@code key} */
    public boolean isCached(String key) {
        return get(key) != null;
    }

    /** @return number of entries currently in the cache */
    public int getCachedCount() {
        return cache.size();
    }

    // ── Fallback ──────────────────────────────────────────────────────────────

    /**
     * Returns the shared purple/black checkerboard fallback texture, creating it lazily.
     * This texture is never cached under a path key and is freed separately in {@link #freeAll()}.
     */
    public CgTexture2D getFallback() {
        if (fallback == null || fallback.isDeleted()) {
            fallback = CgTextureIO.createFallback();
        }
        return fallback;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Reloads all cached textures in-place by calling {@link CgTexture#reload()} on each.
     * Textures without source paths (procedural/dynamic) silently no-op.
     * Called from {@code CgAssetReloader} on F3+T.
     */
    public void reloadAll() {
        LOGGER.log(Level.INFO, "[CgTextureManager] Reloading {0} texture(s)", cache.size());
        for (CgTexture texture : cache.values()) {
            if (!texture.isDeleted()) texture.reload();
        }
    }

    /**
     * Deletes all cached textures and the fallback, then clears the cache.
     * Called from {@code CgGraphicsLifecycle.destroyContext()}.
     */
    public void freeAll() {
        LOGGER.log(Level.INFO, "[CgTextureManager] Freeing {0} texture(s)", cache.size());
        for (CgTexture texture : cache.values()) {
            if (!texture.isDeleted()) texture.delete();
        }
        cache.clear();
        
        if (fallback != null && !fallback.isDeleted()) {
            fallback.delete();
        }
        fallback = null;
    }
}
