package com.crystalgraphics.gl.material;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.util.CgContentHash;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Singleton load/reload/delete lifecycle manager for {@link CgMaterialShader} assets.
 *
 * <p>Shader assets are keyed by resource path and cached — the same path always returns
 * the same {@link CgMaterialShader} instance. Multiple
 * {@link CgMaterial} instances may
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

    /** Marks the synthetic key of a generated shader — see {@link #getOrCreateGenerated}. */
    private static final String GENERATED_PREFIX = "generated:";
    
    /**
     * Returns the shader asset for a {@code .shader} source held in memory, compiling it at most once
     * however many callers ask — what a node graph produces.
     *
     * <p><b>Keyed on the content hash of the source, not on a name.</b> Two graphs that compile to
     * identical GLSL are the same shader and must share one GL program: that is not a nicety, it is the
     * case a grid of node previews produces constantly, where a dozen nodes computing the same thing
     * would otherwise each get their own compile. It also makes an edit that changes the graph without
     * changing its output — moving a node — recompile nothing, for free.</p>
     *
     * @param source complete {@code .shader} text
     * @return the shader asset, never {@code null}
     */
    public CgMaterialShader getOrCreateGenerated(String source) {
        checkNotDeleted();
        if (source == null || source.isEmpty())
            throw new IllegalArgumentException("Generated shader source must not be empty");

        String key = GENERATED_PREFIX + CgContentHash.of(source);
        CgMaterialShader existing = cache.get(key);
        if (existing != null) return existing;
        CgMaterialShader asset = CgMaterialShader.createGenerated(key, source);
        cache.put(key, asset);
        return asset;
    }

    /**
     * Marks all <b>resource-backed</b> shader assets dirty so they recompile on the next {@code bind()}.
     * Called by {@code CgMaterialRegistry.reloadAll()} during hot-reload (F3+T).
     *
     * <p><b>Generated shaders are skipped, and must be.</b> Hot reload means "re-read the file", and a
     * generated shader has no file — marking it dirty would make it recompile from the source it
     * already holds, which is pure waste at best. Its source cannot have changed without its owner
     * compiling a new one, which produces a different content hash and therefore a different asset.
     * Invalidation for these flows from the graph, never from the resource manager.</p>
     */
    public void reloadAll() {
        for (CgMaterialShader asset : cache.values())
            if (!asset.isGenerated()) asset.markDirty();
    }

    /**
     * Deletes all cached shader assets and clears the cache. Idempotent.
     *
     * <p>Must be called after all {@code CgMaterial} instances have been deleted, so that
     * per-material property UBOs are freed before the GL programs are released.</p>
     *
     * <p>Called automatically by {@code CgGraphicsLifecycle.destroyContext()}. Do not call directly.</p>
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
