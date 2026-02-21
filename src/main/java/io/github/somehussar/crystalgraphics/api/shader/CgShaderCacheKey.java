package io.github.somehussar.crystalgraphics.api.shader;

import net.minecraft.util.ResourceLocation;

import java.util.Arrays;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Deterministic cache key for managed shaders.
 *
 * <p>This key uniquely identifies a shader variant based on:</p>
 * <ul>
 *   <li>Vertex shader source ({@code ResourceLocation})</li>
 *   <li>Fragment shader source ({@code ResourceLocation})</li>
 *   <li>Preprocessor defines (variant-aware, deterministic ordering)</li>
 * </ul>
 *
 * <p>The key is designed to be used in maps/sets for caching compiled
 * program instances. Two keys with identical vertex, fragment, and
 * (ordered) defines are equal and have the same hash code.</p>
 *
 * <p>Define ordering is deterministic: defines are sorted alphabetically
 * by name, then by value. This ensures that shader variant behavior is
 * predictable and cache hits are reliable.</p>
 *
 * <h3>Thread Safety</h3>
 * <p>Instances are immutable and thread-safe after construction.</p>
 */
public final class CgShaderCacheKey {

    /**
     * Vertex shader resource location.
     */
    private final ResourceLocation vertexLocation;

    /**
     * Fragment shader resource location.
     */
    private final ResourceLocation fragmentLocation;

    /**
     * Preprocessor defines, stored as (name, value) pairs in a sorted tree
     * to ensure deterministic ordering.
     */
    private final TreeMap<String, String> definesMap;

    /**
     * Cached hash code.
     */
    private final int cachedHashCode;

    /**
     * Constructs a cache key from vertex/fragment locations and defines.
     *
     * <p>Defines are stored internally in a {@code TreeMap} to ensure
     * deterministic ordering. The caller may pass defines in any order;
     * the key will normalize them.</p>
     *
     * @param vertexLocation   the {@code ResourceLocation} of the vertex shader
     * @param fragmentLocation the {@code ResourceLocation} of the fragment shader
     * @param defines          a map of preprocessor defines (name -> value);
     *                         may be empty or null (treated as empty)
     * @throws NullPointerException if either location is null
     */
    public CgShaderCacheKey(ResourceLocation vertexLocation,
                            ResourceLocation fragmentLocation,
                            java.util.Map<String, String> defines) {
        this.vertexLocation = Objects.requireNonNull(vertexLocation,
                "vertexLocation cannot be null");
        this.fragmentLocation = Objects.requireNonNull(fragmentLocation,
                "fragmentLocation cannot be null");

        // Copy defines into a TreeMap for deterministic ordering
        this.definesMap = new TreeMap<>();
        if (defines != null) {
            this.definesMap.putAll(defines);
        }

        // Precompute hash code
        this.cachedHashCode = computeHashCode();
    }

    /**
     * Returns the vertex shader resource location.
     */
    public ResourceLocation getVertexLocation() {
        return vertexLocation;
    }

    /**
     * Returns the fragment shader resource location.
     */
    public ResourceLocation getFragmentLocation() {
        return fragmentLocation;
    }

    /**
     * Returns an unmodifiable view of the preprocessor defines.
     *
     * <p>The defines are stored in deterministic (sorted) order.</p>
     */
    public java.util.Map<String, String> getDefines() {
        return java.util.Collections.unmodifiableMap(definesMap);
    }

    /**
     * Computes the hash code for this key.
     *
     * <p>Combines hashes of vertex location, fragment location, and
     * the ordered defines map.</p>
     */
    private int computeHashCode() {
        return Objects.hash(vertexLocation, fragmentLocation, definesMap);
    }

    @Override
    public int hashCode() {
        return cachedHashCode;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof CgShaderCacheKey)) {
            return false;
        }
        CgShaderCacheKey other = (CgShaderCacheKey) obj;
        return vertexLocation.equals(other.vertexLocation)
                && fragmentLocation.equals(other.fragmentLocation)
                && definesMap.equals(other.definesMap);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("CgShaderCacheKey{");
        sb.append("vert=").append(vertexLocation);
        sb.append(", frag=").append(fragmentLocation);
        if (!definesMap.isEmpty()) {
            sb.append(", defines=").append(definesMap);
        }
        sb.append("}");
        return sb.toString();
    }
}
