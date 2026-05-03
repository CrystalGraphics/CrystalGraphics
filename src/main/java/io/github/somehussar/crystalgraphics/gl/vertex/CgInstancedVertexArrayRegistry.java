package io.github.somehussar.crystalgraphics.gl.vertex;

import io.github.somehussar.crystalgraphics.api.vertex.CgInstanceLayout;
import io.github.somehussar.crystalgraphics.api.vertex.CgVertexFormat;

import java.util.HashMap;
import java.util.Map;

/**
 * Singleton registry of instanced VAO bindings keyed by base format + instance layout pair.
 *
 * <p>The registry lazily creates {@link CgInstancedVertexArrayBinding} instances on demand.
 * Each unique (baseFormat, instanceLayout) pair maps to exactly one instanced binding.</p>
 *
 * <p><strong>Cleanup order is critical</strong>: {@link #deleteAll()} must be called
 * <em>before</em> {@link CgVertexArrayRegistry#deleteAll()} at context teardown.
 * Instanced bindings borrow the base VBO ID; deleting the base registry first produces
 * dangling GL references.</p>
 */
public final class CgInstancedVertexArrayRegistry {

    public static final CgInstancedVertexArrayRegistry INSTANCE = new CgInstancedVertexArrayRegistry();

    private final Map<Key, CgInstancedVertexArrayBinding> bindings = new HashMap<Key, CgInstancedVertexArrayBinding>();

    private CgInstancedVertexArrayRegistry() {
    }

    public static CgInstancedVertexArrayRegistry get() {
        return INSTANCE;
    }

    /**
     * Returns the cached instanced binding for the given format pair, creating it on first access.
     *
     * <p>Attribute slot validation and instancing capability checks happen inside
     * {@link CgInstancedVertexArrayBinding#create} before any VAO is allocated.</p>
     */
    public CgInstancedVertexArrayBinding getOrCreate(CgVertexFormat baseFormat, CgInstanceLayout instanceLayout) {
        Key key = new Key(baseFormat, instanceLayout);
        CgInstancedVertexArrayBinding existing = bindings.get(key);
        if (existing != null) {
            return existing;
        }
        CgInstancedVertexArrayBinding binding = CgInstancedVertexArrayBinding.create(baseFormat, instanceLayout);
        bindings.put(key, binding);
        return binding;
    }

    /**
     * Deletes all instanced bindings and clears the cache.
     *
     * <p>Must be called before {@link CgVertexArrayRegistry#deleteAll()} at context teardown.</p>
     */
    public void deleteAll() {
        for (CgInstancedVertexArrayBinding binding : bindings.values()) {
            binding.delete();
        }
        bindings.clear();
    }

    /**
     * Value key for the registry: equality by (baseFormat, instanceLayout) content.
     */
    private static final class Key {
        private final CgVertexFormat baseFormat;
        private final CgInstanceLayout instanceLayout;

        Key(CgVertexFormat baseFormat, CgInstanceLayout instanceLayout) {
            this.baseFormat = baseFormat;
            this.instanceLayout = instanceLayout;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key)) return false;
            Key that = (Key) o;
            return baseFormat.equals(that.baseFormat) && instanceLayout.equals(that.instanceLayout);
        }

        @Override
        public int hashCode() {
            return 31 * baseFormat.hashCode() + instanceLayout.hashCode();
        }
    }
}
