package com.crystalgraphics.api.texture;

import org.lwjgl.opengl.GL11;

/**
 * Immutable specification of mipmap configuration for textures.
 *
 * <p>Controls whether mipmaps are generated for a texture and, if so, what
 * filter policy to use. {@code glGenerateMipmap} always builds a full mip chain
 * automatically — the old {@code levels} parameter is gone.</p>
 *
 * <p>Prefer the named constants over constructing instances directly:</p>
 * <ul>
 *   <li>{@link #NONE} — mipmapping disabled (the default for most textures)</li>
 *   <li>{@link #TRILINEAR} — full mip chain, trilinear filtering. Standard choice
 *       for diffuse/color textures.</li>
 *   <li>{@link #NEAREST} — full mip chain, nearest-mip-nearest. Sharp pixelated
 *       look, lower cost.</li>
 * </ul>
 *
 * <h3>Examples</h3>
 * <pre>
 * // No mipmaps (default)
 * CgMipmapConfig noMips = CgMipmapConfig.NONE;
 *
 * // Trilinear filtering
 * CgMipmapConfig trilinear = CgMipmapConfig.TRILINEAR;
 *
 * // Use in CgTextureSpec builder
 * CgTextureSpec spec = CgTextureSpec.builder()
 *     .type(CgTextureType.RGBA8)
 *     .mipmaps(CgMipmapConfig.TRILINEAR)
 *     .build();
 * </pre>
 *
 * <p>Instances are immutable and thread-safe.</p>
 */
public final class CgMipmapConfig {

    // ── Named constants ────────────────────────────────────────────────────────

    /** Mipmapping disabled (default). */
    public static final CgMipmapConfig NONE =
            new CgMipmapConfig(false, GL11.GL_LINEAR, GL11.GL_LINEAR);

    /** Trilinear filtering — best quality, standard choice for diffuse textures. */
    public static final CgMipmapConfig TRILINEAR =
            new CgMipmapConfig(true, GL11.GL_LINEAR_MIPMAP_LINEAR, GL11.GL_LINEAR);

    /** Nearest-mip-nearest — sharp pixelated look, low cost. */
    public static final CgMipmapConfig NEAREST =
            new CgMipmapConfig(true, GL11.GL_NEAREST_MIPMAP_NEAREST, GL11.GL_NEAREST);

    // ── Instance fields ────────────────────────────────────────────────────────

    /** Whether mipmaps should be generated. */
    private final boolean enabled;

    /** The minification filter constant (only meaningful if {@code enabled}). */
    private final int minFilter;

    /** The magnification filter constant (only meaningful if {@code enabled}). */
    private final int magFilter;

    /**
     * Private constructor; use the named constants {@link #NONE}, {@link #TRILINEAR},
     * {@link #NEAREST}, or the {@link #enabled(int, int)} factory for custom filters.
     *
     * @param enabled   whether mipmapping is enabled
     * @param minFilter minification filter (ignored if disabled)
     * @param magFilter magnification filter (ignored if disabled)
     */
    private CgMipmapConfig(boolean enabled, int minFilter, int magFilter) {
        this.enabled   = enabled;
        this.minFilter = minFilter;
        this.magFilter = magFilter;
    }

    // ── Factories ──────────────────────────────────────────────────────────────

    /**
     * Returns a mipmap configuration with mipmapping enabled and custom filter constants.
     * Prefer {@link #TRILINEAR} or {@link #NEAREST} for the common cases.
     *
     * @param minFilter the minification filter constant
     *                  (e.g., {@code GL11.GL_LINEAR_MIPMAP_LINEAR})
     * @param magFilter the magnification filter constant
     *                  (e.g., {@code GL11.GL_LINEAR})
     * @return an enabled {@code CgMipmapConfig}
     */
    public static CgMipmapConfig enabled(int minFilter, int magFilter) {
        return new CgMipmapConfig(true, minFilter, magFilter);
    }

    /**
     * Returns a mipmap configuration with mipmapping disabled.
     *
     * @return {@link #NONE}
     * @deprecated Prefer {@link #NONE} directly.
     */
    @Deprecated
    public static CgMipmapConfig disabled() {
        return NONE;
    }

    // ── Accessors ──────────────────────────────────────────────────────────────

    /**
     * Returns whether mipmapping is enabled for this configuration.
     *
     * @return {@code true} if mipmaps should be generated; {@code false} otherwise
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Returns the minification filter constant.
     *
     * <p>Only meaningful if {@link #isEnabled()} returns {@code true}.</p>
     *
     * @return the minification filter (e.g., {@code GL11.GL_LINEAR_MIPMAP_LINEAR})
     */
    public int getMinFilter() {
        return minFilter;
    }

    /**
     * Returns the magnification filter constant.
     *
     * <p>Only meaningful if {@link #isEnabled()} returns {@code true}.</p>
     *
     * @return the magnification filter (e.g., {@code GL11.GL_LINEAR})
     */
    public int getMagFilter() {
        return magFilter;
    }

    // ── Object overrides ───────────────────────────────────────────────────────

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        CgMipmapConfig that = (CgMipmapConfig) o;

        if (enabled != that.enabled) return false;
        if (enabled) {
            if (minFilter != that.minFilter) return false;
            if (magFilter != that.magFilter) return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        int result = enabled ? 1 : 0;
        if (enabled) {
            result = 31 * result + minFilter;
            result = 31 * result + magFilter;
        }
        return result;
    }

    @Override
    public String toString() {
        if (!enabled) {
            return "CgMipmapConfig{disabled}";
        }
        return "CgMipmapConfig{enabled, minFilter=" + minFilter + ", magFilter=" + magFilter + '}';
    }
}
