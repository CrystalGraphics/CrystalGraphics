package io.github.somehussar.crystalgraphics.gl.mesh;

import com.github.bsideup.jabel.Desugar;

/**
 * Typed mesh key wrapping a string name.
 *
 * <p>Equality and hash are based on the wrapped string value.
 * Use {@link #of(String)} to create instances.</p>
 */
@Desugar
public record CgMeshKey(String name) {
    /**
     * Creates a typed mesh key from a string identifier.
     *
     * @param name string key identifying the mesh
     * @return a new key wrapping {@code name}
     */
    public static CgMeshKey of(String name) {
        return new CgMeshKey(name);
    }
}
