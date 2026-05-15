package com.crystalgraphics.gl.vertex;

import com.crystalgraphics.api.vertex.CgInstanceFormat;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.gl.mesh.CgMeshRegistry;

import java.util.HashMap;
import java.util.Map;

/**
 * Singleton registry managing ALL streaming VBOs — base (per vertex format) and
 * instance (per instance layout).
 *
 * <p><strong>Base streams</strong>: keyed by {@link CgVertexFormat} value equality.
 * Both the non-instanced VAO path ({@link CgVertexArrayRegistry#getOrCreate}) and
 * the instanced VAO path ({@link CgVertexArrayRegistry#getOrCreateInstanced}) fetch
 * their base VBO from here.</p>
 *
 * <p><strong>Instance streams</strong>: keyed by {@link CgInstanceFormat} value equality.
 * One shared instance VBO per layout, shared across all base sources that use the same
 * instance layout.</p>
 *
 * <h3>Lifecycle</h3>
 * <p>Call {@link #deleteAll()} during GL context teardown, <strong>after</strong> all VAOs
 * that reference these VBOs have been deleted (i.e., after
 * {@link CgVertexArrayRegistry#deleteAll()} and
 * {@link CgMeshRegistry#deleteAll()}).</p>
 *
 * <h3>Registry consolidation</h3>
 * <p>Future VAO/VBO lifecycle additions belong in {@link CgVertexArrayRegistry} (for VAOs)
 * and this class (for VBOs). Do not create new singleton registries.</p>
 */
public final class CgVertexBufferRegistry {

    private static final CgVertexBufferRegistry INSTANCE = new CgVertexBufferRegistry();

    /** Keyed by value-equal CgVertexFormat. */
    private final Map<CgVertexFormat, CgVertexBuffer> baseCache = new HashMap<>();

    /** Keyed by value-equal CgInstanceFormat. */
    private final Map<CgInstanceFormat, CgInstanceVertexBuffer> instanceCache = new HashMap<>();

    private CgVertexBufferRegistry() {}

    /**
     * Returns the singleton registry.
     *
     * @return the global VBO registry
     */
    public static CgVertexBufferRegistry get() {
        return INSTANCE;
    }

    // ── Base streams ──────────────────────────────────────────────────────────

    /**
     * Returns the existing base stream for {@code format}, or creates and caches a new one.
     *
     * @param format the vertex format (value-equal formats share the same stream)
     * @return the shared base stream VBO for this format
     */
    public CgVertexBuffer getOrCreate(CgVertexFormat format) {
        CgVertexBuffer existing = baseCache.get(format);
        if (existing != null) {
            return existing;
        }
        CgVertexBuffer stream = CgVertexBuffer.create(format);
        baseCache.put(format, stream);
        return stream;
    }

    // ── Instance streams ──────────────────────────────────────────────────────

    /**
     * Returns the existing instance stream for {@code layout}, or creates and caches a new one.
     *
     * <p>Two layouts that are value-equal (same attributes, same stride, same divisor)
     * share a single {@link CgInstanceVertexBuffer}.</p>
     *
     * @param layout the instance layout (value-equal layouts share the same stream)
     * @return the shared instance stream VBO for this layout
     */
    public CgInstanceVertexBuffer getOrCreateInstanced(CgInstanceFormat layout) {
        CgInstanceVertexBuffer existing = instanceCache.get(layout);
        if (existing != null) {
            return existing;
        }
        CgInstanceVertexBuffer stream = CgInstanceVertexBuffer.create(layout);
        instanceCache.put(layout, stream);
        return stream;
    }

    // ── Teardown ──────────────────────────────────────────────────────────────

    /**
     * Deletes all cached VBOs (base + instance streams) and clears the registry.
     *
     * <p><strong>Must be called on the GL thread.</strong> Call during context teardown,
     * after all VAOs referencing these VBOs have been deleted.</p>
     */
    public void deleteAll() {
        for (CgVertexBuffer stream : baseCache.values()) {
            stream.delete();
        }
        baseCache.clear();

        for (CgInstanceVertexBuffer stream : instanceCache.values()) {
            stream.delete();
        }
        instanceCache.clear();
    }
}
