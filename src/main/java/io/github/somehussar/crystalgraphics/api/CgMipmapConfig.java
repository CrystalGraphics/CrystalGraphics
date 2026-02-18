package io.github.somehussar.crystalgraphics.api;

/**
 * Immutable specification of mipmap configuration for texture attachments.
 *
 * <p>This value object controls whether mipmaps are generated for a texture
 * attachment and, if so, what filtering policy and level count to use.</p>
 *
 * <h3>Mipmapping Disabled</h3>
 * <p>By default, framebuffer attachments do not generate mipmaps. The
 * {@link #disabled()} factory method creates a configuration with mipmapping
 * explicitly disabled.</p>
 *
 * <h3>Mipmapping Enabled</h3>
 * <p>When mipmapping is enabled, you must specify:</p>
 * <ul>
 *   <li><strong>Levels</strong>: The number of mipmap levels to generate
 *       (typically {@code 1 + floor(log2(max(width, height)))} for full chains,
 *       but can be fewer)</li>
 *   <li><strong>Min Filter</strong>: The minification filter to use when sampling
 *       below 1:1 scale (e.g., {@code GL_LINEAR_MIPMAP_LINEAR})</li>
 *   <li><strong>Mag Filter</strong>: The magnification filter to use when
 *       sampling above 1:1 scale (typically {@code GL_LINEAR})</li>
 * </ul>
 *
 * <p>Instances are immutable and thread-safe.</p>
 *
 * <h3>Examples</h3>
 * <pre>
 * // No mipmaps
 * CgMipmapConfig noMips = CgMipmapConfig.disabled();
 *
 * // Full mipmap chain with trilinear filtering
 * CgMipmapConfig trilinear = CgMipmapConfig.enabled(
 *     8,                  // levels
 *     0x2703,             // GL_LINEAR_MIPMAP_LINEAR (minFilter)
 *     0x2601              // GL_LINEAR (magFilter)
 * );
 * </pre>
 */
public final class CgMipmapConfig {

    /** Singleton instance representing mipmapping disabled. */
    private static final CgMipmapConfig DISABLED = new CgMipmapConfig(false, 0, 0, 0);

    /** Whether mipmaps should be generated. */
    private final boolean enabled;

    /** The number of mipmap levels (only meaningful if {@code enabled}). */
    private final int levels;

    /** The minification filter constant (only meaningful if {@code enabled}). */
    private final int minFilter;

    /** The magnification filter constant (only meaningful if {@code enabled}). */
    private final int magFilter;

    /**
     * Private constructor; use {@link #disabled()} or {@link #enabled(int, int, int)}.
     *
     * @param enabled   whether mipmapping is enabled
     * @param levels    mipmap level count (ignored if disabled)
     * @param minFilter minification filter (ignored if disabled)
     * @param magFilter magnification filter (ignored if disabled)
     */
    private CgMipmapConfig(boolean enabled, int levels, int minFilter, int magFilter) {
        this.enabled = enabled;
        this.levels = levels;
        this.minFilter = minFilter;
        this.magFilter = magFilter;
    }

    /**
     * Returns a mipmap configuration with mipmapping disabled.
     *
     * <p>This is the default configuration for most framebuffer attachments.
     * The returned instance is a singleton and safe for reuse.</p>
     *
     * @return a disabled {@code CgMipmapConfig}
     */
    public static CgMipmapConfig disabled() {
        return DISABLED;
    }

    /**
     * Returns a mipmap configuration with mipmapping enabled.
     *
     * @param levels   the number of mipmap levels to generate (must be positive)
     * @param minFilter the minification filter constant
     *                  (e.g., {@code GL_LINEAR_MIPMAP_LINEAR}, {@code GL_NEAREST_MIPMAP_NEAREST})
     * @param magFilter the magnification filter constant
     *                  (typically {@code GL_LINEAR} or {@code GL_NEAREST})
     * @return an enabled {@code CgMipmapConfig}
     * @throws IllegalArgumentException if {@code levels} is not positive
     */
    public static CgMipmapConfig enabled(int levels, int minFilter, int magFilter) {
        if (levels <= 0) {
            throw new IllegalArgumentException("Mipmap levels must be positive, got: " + levels);
        }
        return new CgMipmapConfig(true, levels, minFilter, magFilter);
    }

    /**
     * Returns whether mipmapping is enabled for this configuration.
     *
     * @return {@code true} if mipmaps should be generated; {@code false} otherwise
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Returns the number of mipmap levels.
     *
     * <p>This value is only meaningful if {@link #isEnabled()} returns {@code true}.</p>
     *
     * @return the mipmap level count
     */
    public int getLevels() {
        return levels;
    }

    /**
     * Returns the minification filter constant.
     *
     * <p>This value is only meaningful if {@link #isEnabled()} returns {@code true}.</p>
     *
     * @return the minification filter (e.g., {@code GL_LINEAR_MIPMAP_LINEAR})
     */
    public int getMinFilter() {
        return minFilter;
    }

    /**
     * Returns the magnification filter constant.
     *
     * <p>This value is only meaningful if {@link #isEnabled()} returns {@code true}.</p>
     *
     * @return the magnification filter (e.g., {@code GL_LINEAR})
     */
    public int getMagFilter() {
        return magFilter;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        CgMipmapConfig that = (CgMipmapConfig) o;

        if (enabled != that.enabled) return false;
        if (enabled) {
            if (levels != that.levels) return false;
            if (minFilter != that.minFilter) return false;
            if (magFilter != that.magFilter) return false;
        }

        return true;
    }

    @Override
    public int hashCode() {
        int result = enabled ? 1 : 0;
        if (enabled) {
            result = 31 * result + levels;
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
        return "CgMipmapConfig{" +
                "enabled=true" +
                ", levels=" + levels +
                ", minFilter=" + minFilter +
                ", magFilter=" + magFilter +
                '}';
    }
}
