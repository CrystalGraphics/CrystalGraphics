package io.github.somehussar.crystalgraphics.api.material;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Singleton load/reload/delete lifecycle manager for {@link CgMaterial} instances.
 *
 * <p>Materials are keyed by resource path and cached — calling {@link #getOrCreate}
 * twice for the same path returns the same instance. The singleton is accessible
 * via {@link #get()}.</p>
 *
 * <p>For hot-reload support, call {@link #reloadAll()} from your reload hook
 * (e.g. inside a callback registered with {@code CgAssetReloader}).
 * {@link #deleteAll()} must be called before {@code CgGraphicsLifecycle.destroyContext()}.</p>
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
     * Marks all tracked materials dirty so they recompile on the next {@code bind()}.
     * Wire this to your reload hook (F3+T / resource pack reload).
     */
    public void reloadAll() {
        for (CgMaterial mat : materials.values())
            mat.markDirty();
    }

    /**
     * Deletes all tracked materials and clears the cache. Idempotent.
     * Must be called before {@code CgGraphicsLifecycle.destroyContext()}.
     */
    public void deleteAll() {
        if (!deleted) {
            deleted = true;
            for (CgMaterial mat : materials.values())
                mat.delete();
            materials.clear();
        }
    }

    private void checkNotDeleted() {
        if (deleted) throw new IllegalStateException("CgMaterialRegistry has been deleted");
    }
}
