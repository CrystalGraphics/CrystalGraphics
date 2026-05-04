package io.github.somehussar.crystalgraphics.gl.mesh;

import java.util.HashMap;
import java.util.Map;

/**
 * Singleton registry: {@code String} key → {@link CgMesh} cache.
 *
 * <p>All callers that load or build the same logical mesh should go through
 * this registry so the GPU mesh is created only once.</p>
 *
 * <p>Call {@link #deleteAll()} during GL context teardown (step 2 of 4),
 * after {@link io.github.somehussar.crystalgraphics.gl.vertex.CgVertexArrayRegistry#deleteAll()}
 * so that instanced VAOs referencing mesh VBOs are destroyed first,
 * and before {@link io.github.somehussar.crystalgraphics.gl.vertex.CgVertexBufferRegistry#deleteAll()}.</p>
 */
public final class CgMeshRegistry {

    private static final CgMeshRegistry INSTANCE = new CgMeshRegistry();

    private final Map<String, CgMesh> cache = new HashMap<>();

    private CgMeshRegistry() {}

    /**
     * Returns the singleton registry.
     *
     * @return the global mesh registry
     */
    public static CgMeshRegistry get() {
        return INSTANCE;
    }

    /**
     * Returns the cached mesh for {@code key}, or calls {@code supplier.create()} to build
     * and cache it if not already present.
     *
     * <p>The supplier is only called on first access. Subsequent calls with an equal key
     * return the cached instance without invoking the supplier again.</p>
     *
     * @param key      string key identifying the mesh (e.g. {@code "crystalgraphics:builtin/quad/..."})
     * @param supplier factory invoked once on cache miss; must return a non-null mesh
     * @return the cached or newly created mesh
     */
    public CgMesh getOrCreate(String key, CgMeshSupplier supplier) {
        if (cache.containsKey(key)) {
            return cache.get(key);
        }
        CgMesh mesh = supplier.create();
        cache.put(key, mesh);
        return mesh;
    }

    /**
     * Returns the cached mesh for {@code key}, or {@code null} if not present.
     *
     * @param key string key identifying the mesh
     * @return the cached mesh, or {@code null}
     */
    public CgMesh get(String key) {
        return cache.get(key);
    }

    /**
     * Registers a pre-built mesh under {@code key}.
     *
     * <p><strong>Do NOT call delete() directly</strong> on meshes obtained from this
     * registry; use {@link #deleteAll()} during context teardown instead.</p>
     *
     * @param key  string key identifying the mesh
     * @param mesh the mesh to register
     * @throws IllegalArgumentException if {@code key} is already registered
     */
    public void register(String key, CgMesh mesh) {
        if (cache.containsKey(key)) {
            throw new IllegalArgumentException("CgMeshRegistry: duplicate key '" + key + "'");
        }
        cache.put(key, mesh);
    }

    /**
     * Deletes all cached meshes and clears the registry.
     *
     * <p>This is step 2 of the canonical 4-step GL context teardown. Must be called
     * on the GL thread, after {@link io.github.somehussar.crystalgraphics.gl.vertex.CgVertexArrayRegistry#deleteAll()}
     * and before {@link io.github.somehussar.crystalgraphics.gl.vertex.CgVertexBufferRegistry#deleteAll()}.</p>
     */
    public void deleteAll() {
        for (CgMesh mesh : cache.values()) {
            if (mesh != null) {
                mesh.delete();
            }
        }
        cache.clear();
    }

    /**
     * Supplier interface for lazy mesh creation in {@link #getOrCreate(String, CgMeshSupplier)}.
     *
     * <p>Implementations must create and return a non-null {@link CgMesh}. The registry
     * calls this exactly once per unique key.</p>
     */
    public interface CgMeshSupplier {
        CgMesh create();
    }
}
