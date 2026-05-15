package com.crystalgraphics.api.shader;

import lombok.Getter;

import java.util.Objects;

/**
 * Deterministic cache key for managed shaders.
 *
 * <p>This key uniquely identifies a shader variant based on:</p>
 * <ul>
 *   <li>Vertex shader source ({@code ResourceLocation})</li>
 *   <li>Fragment shader source ({@code ResourceLocation})</li>
 * </ul>
 *
 * <p>The key is designed to be used in maps/sets for caching compiled
 * program instances. Two keys with identical vertex and fragment
 * are equal and have the same hash code.</p>
 *
 *
 * <h3>Thread Safety</h3>
 * <p>Instances are immutable and thread-safe after construction.</p>
 */
public final class CgShaderCacheKey {

    /**
     * Vertex shader resource location.
     * -- GETTER --
     *  Returns the vertex shader resource location.

     */
    @Getter
    private final String vertexLocation;

    /**
     * Fragment shader resource location.
     * -- GETTER --
     *  Returns the fragment shader resource location.

     */
    @Getter
    private final String fragmentLocation;

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
     * @throws NullPointerException if either location is null
     */
    public CgShaderCacheKey(String vertexLocation, String fragmentLocation) {
        this.vertexLocation = Objects.requireNonNull(vertexLocation, "vertexLocation cannot be null");
        this.fragmentLocation = Objects.requireNonNull(fragmentLocation, "fragmentLocation cannot be null");
        this.cachedHashCode = Objects.hash(vertexLocation, fragmentLocation);
    }

    @Override
    public int hashCode() {
        return cachedHashCode;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof CgShaderCacheKey)) return false;
        CgShaderCacheKey other = (CgShaderCacheKey) obj;
        return vertexLocation.equals(other.vertexLocation)
                && fragmentLocation.equals(other.fragmentLocation);
    }

    @Override
    public String toString() {
        return "CgShaderCacheKey{vert=" + vertexLocation + ", frag=" + fragmentLocation + "}";
    }
}
