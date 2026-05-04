package io.github.somehussar.crystalgraphics.gl.vertex;

import com.github.bsideup.jabel.Desugar;
import io.github.somehussar.crystalgraphics.api.vertex.CgInstanceFormat;
import io.github.somehussar.crystalgraphics.api.vertex.CgVertexFormat;
import io.github.somehussar.crystalgraphics.gl.mesh.CgMesh;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Global registry of ALL VAO bindings — non-instanced (per vertex format) and
 * instanced (per mesh+layout or per format+layout).
 *
 * <h3>Non-instanced path</h3>
 * <p>Each binding owns a VAO only; the base stream VBO is fetched from
 * {@link CgVertexBufferRegistry} and borrowed (never deleted here).
 * Cache key: {@link CgVertexFormat} value equality.</p>
 *
 * <h3>Instanced streaming path</h3>
 * <p>Each binding owns an instanced VAO only; both the base VBO and the instance
 * VBO are borrowed from their respective registries and never deleted here.
 * Cache key: {@link InstancedStreamKey} — a value-equal composite of
 * ({@link CgVertexFormat}, {@link CgInstanceFormat}).</p>
 *
 * <h3>Instanced mesh path</h3>
 * <p>Cache key: {@link InstancedMeshKey} — identity-based {@link CgMesh} reference plus
 * value-equal {@link CgInstanceFormat}. Identity is used for the mesh because the
 * same format can be used to upload multiple independent meshes.</p>
 *
 * <h3>Lifecycle (4-step teardown)</h3>
 * <p>{@link #deleteAll()} deletes instanced VAOs first, then non-instanced VAOs,
 * so that all VAOs are gone before any VBOs are deleted by
 * {@link CgVertexBufferRegistry#deleteAll()}.</p>
 */
public final class CgVertexArrayRegistry {

    private static final CgVertexArrayRegistry INSTANCE = new CgVertexArrayRegistry();

    // ── Non-instanced bindings ─────────────────────────────────────────────────
    /**
     * Cache of non-instanced VAO bindings.
     * Key: {@link CgVertexFormat} (value equality).
     * Value: the shared {@link CgVertexArrayBinding} for that format.
     */
    private final Map<CgVertexFormat, CgVertexArrayBinding> bindings = new HashMap<>();

    // ── Instanced bindings — streaming (format+layout key) ────────────────────
    /**
     * Cache of instanced VAO bindings for the streaming base path.
     * Key: {@link InstancedStreamKey} — a value-equal composite of
     * ({@link CgVertexFormat}, {@link CgInstanceFormat}). This replaces the
     * previous nested {@code IdentityHashMap} design, which was fragile because
     * {@link CgVertexBuffer} and {@link CgInstanceVertexBuffer} instances are
     * singletons per format/layout but were keyed by identity rather than value.
     */
    private final Map<InstancedStreamKey, CgInstanceVertexArrayBinding> instancedStreamBindings = new HashMap<>();

    // ── Instanced bindings — mesh (mesh+layout key) ────────────────────────────
    /**
     * Cache of instanced VAO bindings for the static mesh base path.
     * Key: {@link InstancedMeshKey} — identity-based {@link CgMesh} reference plus
     * value-equal {@link CgInstanceFormat}. The mesh is keyed by identity because
     * the same vertex format can be used to upload distinct mesh objects.
     */
    private final Map<InstancedMeshKey, CgInstanceVertexArrayBinding> instancedMeshBindings = new HashMap<>();

    private CgVertexArrayRegistry() {
    }

    public static CgVertexArrayRegistry get() {
        return INSTANCE;
    }

    // ── Non-instanced ──────────────────────────────────────────────────────────

    /**
     * Returns (or lazily creates) a non-instanced VAO binding for the given vertex format.
     *
     * <p>All callers sharing a value-equal {@link CgVertexFormat} share the same binding
     * and therefore the same VBO and VAO. Must be called on the GL thread.</p>
     *
     * @param format the vertex format (value-equal formats share the same binding)
     * @return the shared non-instanced VAO binding for this format
     */
    public CgVertexArrayBinding getOrCreate(CgVertexFormat format) {
        CgVertexArrayBinding existing = bindings.get(format);
        if (existing != null) {
            return existing;
        }

        // Fetch (or create) the shared base stream from the VBO-only registry.
        CgVertexBuffer baseStream = CgVertexBufferRegistry.get().getOrCreate(format);

        // VBO must be bound *before* VAO configure so that glVertexAttribPointer
        // captures the VBO binding into the VAO state.
        baseStream.getStreamBuffer().bind();
        CgVertexArray vertexArray = CgVertexArray.create();
        vertexArray.configure(format);
        baseStream.getStreamBuffer().unbind();
        vertexArray.unbind();

        CgVertexArrayBinding binding = new CgVertexArrayBinding(baseStream, vertexArray);
        bindings.put(format, binding);
        return binding;
    }

    // ── Instanced — streaming base ─────────────────────────────────────────────

    /**
     * Returns (or lazily creates) an instanced VAO for the given streaming base
     * format and instance layout.
     *
     * <p>Fetches {@link CgVertexBuffer} from {@link CgVertexBufferRegistry} and
     * {@link CgInstanceVertexBuffer} from {@link CgVertexBufferRegistry} —
     * no {@link CgVertexArrayBinding} is consulted, so no non-instanced VAO is
     * wasted. The cache key is a value-equal {@link InstancedStreamKey} so that
     * re-constructed format/layout objects with equal values share the same VAO.</p>
     *
     * <p>Validates combined attribute slot count against {@code GL_MAX_VERTEX_ATTRIBS}
     * before VAO creation. Must be called on the GL thread.</p>
     *
     * @param format the base vertex format
     * @param layout the per-instance attribute layout
     * @return the shared instanced VAO binding
     * @throws IllegalArgumentException if combined attribute count exceeds the GL limit
     */
    public CgInstanceVertexArrayBinding getOrCreateInstanced(CgVertexFormat format, CgInstanceFormat layout) {
        InstancedStreamKey key = new InstancedStreamKey(format, layout);
        CgInstanceVertexArrayBinding existing = instancedStreamBindings.get(key);
        if (existing != null) {
            return existing;
        }

        // Validate slot count before VAO creation (C3).
        CgInstanceVertexArrayBinding.validateAttributeSlots(format, layout);

        CgVertexBuffer base = CgVertexBufferRegistry.get().getOrCreate(format);
        CgInstanceVertexBuffer instance = CgVertexBufferRegistry.get().getOrCreateInstanced(layout);

        CgInstanceVertexArrayBinding binding = CgInstanceVertexArrayBinding.createStreaming(base, instance);
        instancedStreamBindings.put(key, binding);
        return binding;
    }

    // ── Instanced — mesh base ──────────────────────────────────────────────────

    /**
     * Returns (or lazily creates) an instanced VAO for the given static mesh and
     * instance layout.
     *
     * <p>The mesh is keyed by object identity so that multiple independent meshes
     * uploaded with the same vertex format each get their own instanced VAO.
     * The instance layout is keyed by value equality.</p>
     *
     * <p>Validates combined attribute slot count against {@code GL_MAX_VERTEX_ATTRIBS}
     * before VAO creation. Must be called on the GL thread.</p>
     *
     * @param mesh   the static mesh (VBO/IBO are borrowed, not owned here)
     * @param layout the per-instance attribute layout
     * @return the shared instanced VAO binding
     * @throws IllegalArgumentException if combined attribute count exceeds the GL limit
     */
    public CgInstanceVertexArrayBinding getOrCreateMeshInstanced(CgMesh mesh, CgInstanceFormat layout) {
        InstancedMeshKey key = new InstancedMeshKey(mesh, layout);
        CgInstanceVertexArrayBinding existing = instancedMeshBindings.get(key);
        if (existing != null) {
            return existing;
        }

        // Validate slot count before VAO creation (C3).
        CgInstanceVertexArrayBinding.validateAttributeSlots(mesh.getFormat(), layout);

        CgInstanceVertexBuffer instance = CgVertexBufferRegistry.get().getOrCreateInstanced(layout);

        CgInstanceVertexArrayBinding binding = CgInstanceVertexArrayBinding.createMeshInstanced(mesh, instance);
        instancedMeshBindings.put(key, binding);
        return binding;
    }

    // ── Mesh binding invalidation ──────────────────────────────────────────────

    /**
     * Removes and deletes all instanced VAO bindings that reference the given mesh.
     *
     * <p>Must be called when {@link CgMesh#delete()} is invoked to prevent stale VAOs
     * from lingering in the registry pointing at deleted GL buffer objects.</p>
     *
     * <p>Must be called on the GL thread.</p>
     *
     * @param mesh the mesh whose instanced VAO bindings should be invalidated
     */
    public void invalidateMeshBindings(CgMesh mesh) {
        Iterator<Map.Entry<InstancedMeshKey, CgInstanceVertexArrayBinding>> it = instancedMeshBindings.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<InstancedMeshKey, CgInstanceVertexArrayBinding> entry = it.next();
            if (entry.getKey().mesh == mesh) {
                entry.getValue().delete();
                it.remove();
            }
        }
    }

    // ── Teardown ───────────────────────────────────────────────────────────────

    /**
     * Deletes ALL owned VAOs (instanced first, then non-instanced) and clears all caches.
     *
     * <p><strong>Order is critical</strong>: instanced VAOs reference both base VBOs and
     * instance VBOs; they must be destroyed before non-instanced VAOs, and all VAOs must
     * be gone before any VBOs are deleted by {@link CgVertexBufferRegistry#deleteAll()}.</p>
     *
     * <p>Does NOT delete any VBOs — those are owned by {@link CgVertexBufferRegistry}.</p>
     */
    public void deleteAll() {
        // 1. Instanced streaming VAOs — reference both base VBOs and instance VBOs.
        for (CgInstanceVertexArrayBinding b : instancedStreamBindings.values()) {
            b.delete();
        }
        instancedStreamBindings.clear();

        // 2. Instanced mesh VAOs — reference mesh VBOs and instance VBOs.
        for (CgInstanceVertexArrayBinding b : instancedMeshBindings.values()) {
            b.delete();
        }
        instancedMeshBindings.clear();

        // 3. Non-instanced VAOs — reference base VBOs only.
        for (CgVertexArrayBinding binding : bindings.values()) {
            binding.delete();
        }
        bindings.clear();
    }

    // ── Composite key types ────────────────────────────────────────────────────

    /**
     * Value-equal composite key for the streaming instanced VAO cache.
     *
     * <p>Both components use value equality ({@link CgVertexFormat} and
     * {@link CgInstanceFormat} both implement proper {@link Object#equals} /
     * {@link Object#hashCode} based on their attribute lists and strides), so
     * two independently-constructed key objects with the same format/layout pair
     * will map to the same cache entry.</p>
     * @param format  The base vertex format. Value equality used for cache lookup. 
     * @param layout  The instance attribute layout. Value equality used for cache lookup. 
     */
    @Desugar
    record InstancedStreamKey(CgVertexFormat format, CgInstanceFormat layout) {

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof InstancedStreamKey)) return false;
            InstancedStreamKey other = (InstancedStreamKey) o;
            return format.equals(other.format) && layout.equals(other.layout);
        }
    }

    /**
     * Composite key for the mesh-instanced VAO cache.
     *
     * <p>The mesh component uses <em>object identity</em> ({@code ==}) so that
     * multiple distinct {@link CgMesh} objects uploaded with the same vertex format
     * each get their own instanced VAO. The layout component uses value equality.</p>
     * @param mesh  The static mesh. Identity used for cache lookup (not value equality). 
     * @param layout  The instance attribute layout. Value equality used for cache lookup. 
     */
    @Desugar
    record InstancedMeshKey(CgMesh mesh, CgInstanceFormat layout) {

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof InstancedMeshKey)) return false;
            InstancedMeshKey other = (InstancedMeshKey) o;
            // Identity check for mesh: two separate meshes with the same format are distinct keys.
            return this.mesh == other.mesh && layout.equals(other.layout);
        }

        @Override
        public int hashCode() {
            return 31 * System.identityHashCode(mesh) + layout.hashCode();
        }
    }
}
