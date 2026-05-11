package io.github.somehussar.crystalgraphics.api.material;

import io.github.somehussar.crystalgraphics.gl.material.CgMaterialShaderRegistry;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Singleton load/reload/delete lifecycle manager for {@link CgMaterial} instances.
 *
 * <p>Materials are keyed by resource path and cached — calling {@link #getOrCreate}
 * twice for the same path returns the same instance. The singleton is accessible
 * via {@link #get()}.</p>
 *
 * <p>Hot-reload: {@link #reloadAll()} delegates to {@link CgMaterialShaderRegistry#reloadAll()},
 * marking all shader assets dirty. Materials detect the revision change on the next
 * {@code bind()} call. Wire this to your reload hook (F3+T / resource pack reload).</p>
 *
 * <p>Teardown: {@link #deleteAll()} frees per-instance property UBOs first, then cascades to
 * {@link CgMaterialShaderRegistry#deleteAll()} to release GL shader programs.</p>
 */
public final class CgMaterialRegistry {

    // ── Singleton ─────────────────────────────────────────────────────────────

    private static final CgMaterialRegistry INSTANCE = new CgMaterialRegistry();

    /** Returns the singleton registry. */
    public static CgMaterialRegistry get() {
        return INSTANCE;
    }

    // ── Instance ──────────────────────────────────────────────────────────────

    private final Map<String, CgMaterial> materials = new LinkedHashMap<>();
    private boolean deleted;

    private CgMaterialRegistry() {}

    /**
     * Returns the material for {@code resourcePath}, loading it if not already cached.
     *
     * @param resourcePath e.g. {@code "mymod:shaders/terrain.shader"}
     * @return the material, never {@code null}
     */
    public CgMaterial getOrCreate(String resourcePath) {
        checkNotDeleted();
        CgMaterial existing = materials.get(resourcePath);
        if (existing != null) return existing;
        CgMaterial mat = CgMaterial.create(resourcePath);
        materials.put(resourcePath, mat);
        return mat;
    }

    /**
     * Returns the material for {@code key.name()}, loading it if not already cached.
     *
     * @param key typed material key
     * @return the material, never {@code null}
     */
    public CgMaterial getOrCreate(CgMaterialKey key) {
        return getOrCreate(key.name());
    }

    /**
     * Marks all shader assets dirty so they recompile on the next {@code bind()}.
     * Delegates to {@link CgMaterialShaderRegistry#reloadAll()} — materials detect the
     * revision change on next {@code bind()} without a per-material loop here.
     * Wire this to your reload hook (F3+T / resource pack reload).
     */
    public void reloadAll() {
        for (CgMaterial mat : materials.values())
            mat.markDirty();
    }

    /**
     * Deletes all tracked material instances (freeing per-instance property UBOs), then
     * cascades to {@link CgMaterialShaderRegistry#deleteAll()} to release GL shader programs.
     * Idempotent. Must be called before {@code CgGraphicsLifecycle.destroyContext()}.
     *
     * <p>Material instances must be deleted before shader assets so that property UBOs
     * are freed while the GL context is still valid.</p>
     */
    public void deleteAll() {
        if (!deleted) {
            deleted = true;
            for (CgMaterial mat : materials.values())
                mat.delete();          // frees per-instance matPropsUbo
            materials.clear();
        }
    }

    private void checkNotDeleted() {
        if (deleted) throw new IllegalStateException("CgMaterialRegistry has been deleted");
    }
}
