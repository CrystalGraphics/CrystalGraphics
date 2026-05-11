package io.github.somehussar.crystalgraphics.gl.material;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Singleton load/reload/delete lifecycle manager for {@link CgMaterialShader} assets.
 *
 * <p>Shader assets are keyed by resource path and cached — the same path always returns
 * the same {@link CgMaterialShader} instance. Multiple
 * {@link io.github.somehussar.crystalgraphics.api.material.CgMaterial} instances may
 * reference the same asset and share compiled GL programs.</p>
 */
public final class CgMaterialShaderRegistry {

    // ── Instance ──────────────────────────────────────────────────────────────

    /** Path → asset cache. LinkedHashMap preserves insertion order for deterministic iteration. */
    private final Map<String, CgMaterialShader> cache = new LinkedHashMap<>();

    private boolean deleted;

    private CgMaterialShaderRegistry() {}

    // ── Singleton ─────────────────────────────────────────────────────────────

    private static final CgMaterialShaderRegistry INSTANCE = new CgMaterialShaderRegistry();

    /** Returns the singleton registry. */
    public static CgMaterialShaderRegistry get() {
        return INSTANCE;
    }

    /**
     * Returns the shader asset for {@code resourcePath}, creating it if not already cached.
     *
     * @param resourcePath e.g. {@code "mymod:shaders/terrain.shader"}
     * @return the shader asset, never {@code null}
     * @throws IllegalStateException if {@link #deleteAll()} has already been called
     */
    public CgMaterialShader getOrCreate(String resourcePath) {
        checkNotDeleted();
        CgMaterialShader existing = cache.get(resourcePath);
        if (existing != null) return existing;
        CgMaterialShader asset = CgMaterialShader.create(resourcePath);
        cache.put(resourcePath, asset);
        return asset;
    }

    /**
     * Marks all cached shader assets dirty so they recompile on the next {@code bind()}.
     * Called by {@code CgMaterialRegistry.reloadAll()} during hot-reload (F3+T).
     */
    public void reloadAll() {
        for (CgMaterialShader asset : cache.values())
            asset.markDirty();
    }

    /**
     * Deletes all cached shader assets and clears the cache. Idempotent.
     *
     * <p>Must be called after all {@code CgMaterial} instances have been deleted, so that
     * per-material property UBOs are freed before the GL programs are released.</p>
     *
     * <p>Called automatically via {@code CgMaterialRegistry.deleteAll()}. Do not call directly.</p>
     */
    public void deleteAll() {
        if (!deleted) {
            deleted = true;
            for (CgMaterialShader asset : cache.values())
                asset.delete();
            cache.clear();
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void checkNotDeleted() {
        if (deleted) throw new IllegalStateException("CgMaterialShaderRegistry has been deleted");
    }

    // ── Test support (package-private) ────────────────────────────────────────

    /**
     * Resets this registry's state for unit tests.
     * <strong>Do NOT use outside of test code.</strong>
     */
    static void resetForTest() {
        INSTANCE.cache.clear();
        INSTANCE.deleted = false;
    }
}
