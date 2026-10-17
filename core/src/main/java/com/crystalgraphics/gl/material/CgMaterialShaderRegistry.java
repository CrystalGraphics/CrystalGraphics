package com.crystalgraphics.gl.material;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialState;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.CgContentHash;
import com.crystalgraphics.util.trace.CgChannels;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Singleton load/reload/delete lifecycle manager for {@link CgMaterialShader} assets.
 *
 * <p>Shader assets are keyed by resource path and the {@link CgMaterialState} they compile under (its blend and clip),
 * and cached — the same path and state always return the same {@link CgMaterialShader} instance. Multiple
 * {@link CgMaterial} instances may reference the same asset and share compiled GL programs.</p>
 *
 * <pre>{@code
 * CgMaterialShader shader = CgMaterialShaderRegistry.get().getOrCreate("mymod:shaders/fx/soft.shader");
 * CgMaterialShader added = CgMaterialShaderRegistry.get().getOrCreate("mymod:shaders/fx/soft.shader",
 *         CgMaterialState.builder().blend(CgBlendMode.ADDITIVE).build());       // its own programs
 * CgMaterialShader twoSided = CgMaterialShaderRegistry.get().getOrCreate("mymod:shaders/fx/soft.shader",
 *         CgMaterialState.builder().twoSided().build());                        // the same asset as the first
 * }</pre>
 */
public final class CgMaterialShaderRegistry {

    // ── Instance ──────────────────────────────────────────────────────────────

    /** Path (and program state) → asset cache. LinkedHashMap preserves insertion order for deterministic iteration. */
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
        return getOrCreate(resourcePath, CgMaterialState.SHADER);
    }

    /**
     * The shader asset for {@code resourcePath} compiled under {@code state}'s blend and clip. A state that changes
     * neither (cull, depth write) answers the shader's own asset: those are the material's pipeline state.
     */
    public CgMaterialShader getOrCreate(String resourcePath, CgMaterialState state) {
        checkNotDeleted();
        CgMaterialState program = programState(state);
        String key = key(resourcePath, program);
        CgMaterialShader existing = cache.get(key);
        if (existing != null) return existing;
        CgMaterialShader asset = CgMaterialShader.create(resourcePath, program);
        cache.put(key, asset);
        return asset;
    }

    /** {@code state}'s blend and clip alone: what a program depends on. */
    private static CgMaterialState programState(CgMaterialState state) {
        if (!state.changesProgram()) return CgMaterialState.SHADER;
        CgMaterialState.Builder b = CgMaterialState.builder();
        if (state.blend() != null) b.blend(state.blend());
        if (state.clip()) b.clip();
        return b.build();
    }

    private static String key(String name, CgMaterialState program) {
        return program.changesProgram() ? name + "#" + program.programKey() : name;
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
        return getOrCreateGenerated(source, CgMaterialState.SHADER);
    }

    /** {@link #getOrCreateGenerated(String)} compiled under {@code state}'s blend and clip. */
    public CgMaterialShader getOrCreateGenerated(String source, CgMaterialState state) {
        checkNotDeleted();
        CgMaterialState program = programState(state);
        if (source == null || source.isEmpty())
            throw new IllegalArgumentException("Generated shader source must not be empty");

        String key;
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, "material.generatedHash")) {
            key = GENERATED_PREFIX + CgContentHash.of(source);
        }
        String cached = key(key, program);
        CgMaterialShader existing = cache.get(cached);
        if (existing != null) {
            CgTrace.add(CgChannels.GL, "material.generated.hit", 1);
            return existing;
        }
        CgTrace.add(CgChannels.GL, "material.generated.miss", 1);
        CgMaterialShader asset = CgMaterialShader.createGenerated(key, source, program);
        cache.put(cached, asset);
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
