package com.crystalgraphics.api.material;

import com.github.bsideup.jabel.Desugar;

/**
 * Typed material key wrapping a resource-path string.
 *
 * <p>Equality and hash are based on the wrapped string value.
 * Use {@link #of(String)} to create instances.</p>
 *
 * <p>Accepts any valid {@code .shader} resource path, e.g.
 * {@code "mymod:shaders/terrain.shader"}.</p>
 */
@Desugar
public record CgMaterialKey(String name) {
    /**
     * Creates a typed material key from a resource path string.
     *
     * @param name resource path identifying the material
     * @return a new key wrapping {@code name}
     */
    public static CgMaterialKey of(String name) {
        return new CgMaterialKey(name);
    }
}
