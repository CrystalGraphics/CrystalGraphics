package io.github.somehussar.crystalgraphics.api.framebuffer;

import io.github.somehussar.crystalgraphics.api.CgMipmapConfig;

/**
 * Immutable specification of a single color attachment for a framebuffer.
 *
 * <p>This value object describes how a color texture should be sized, formatted,
 * and optionally mipmapped when created as a framebuffer attachment. Each color
 * attachment can have independent sizing and format requirements.</p>
 *
 * <h3>Scaling</h3>
 * <p>Attachments are sized independently via per-axis scale factors ({@code scaleX},
 * {@code scaleY}), expressed as floats relative to a base framebuffer size. For
 * example, to create a half-resolution attachment:</p>
 * <pre>
 *   CgColorAttachmentSpec halfRes = CgColorAttachmentSpec.builder()
 *       .scaleX(0.5f)
 *       .scaleY(0.5f)
 *       .format(new CgTextureFormatSpec(0x8058, 0x1908, 0x1401)) // RGBA8
 *       .build();
 * </pre>
 *
 * <h3>Format and Mipmaps</h3>
 * <p>Each attachment specifies its texture format via {@link CgTextureFormatSpec}
 * and optional mipmap configuration via {@link CgMipmapConfig}. If mipmapping
 * is enabled, the mipmap config controls the number of levels and filtering
 * policy.</p>
 *
 * <h3>Validation</h3>
 * <p>Scale factors must be positive ({@code scaleX > 0}, {@code scaleY > 0}).
 * Format must not be {@code null}. Invalid configurations throw
 * {@link IllegalArgumentException}.</p>
 *
 * <h3>Examples</h3>
 * <pre>
 * // Full-resolution RGBA8 color without mipmaps
 * CgColorAttachmentSpec fullRes = CgColorAttachmentSpec.builder()
 *     .format(new CgTextureFormatSpec(0x8058, 0x1908, 0x1401))
 *     .build(); // defaults: scaleX=1.0f, scaleY=1.0f, no mipmaps
 *
 * // Half-resolution with trilinear mipmapping
 * CgColorAttachmentSpec halfResMips = CgColorAttachmentSpec.builder()
 *     .scaleX(0.5f)
 *     .scaleY(0.5f)
 *     .format(new CgTextureFormatSpec(0x8058, 0x1908, 0x1401))
 *     .mipmaps(CgMipmapConfig.enabled(4, 0x2703, 0x2601))
 *     .build();
 * </pre>
 *
 * @see CgTextureFormatSpec
 * @see io.github.somehussar.crystalgraphics.api.CgMipmapConfig
 */
public final class CgColorAttachmentSpec {

    /** Default scale factor (1.0 = full resolution). */
    private static final float DEFAULT_SCALE = 1.0f;

    /** X-axis scale factor (width multiplier relative to framebuffer base size). */
    private final float scaleX;

    /** Y-axis scale factor (height multiplier relative to framebuffer base size). */
    private final float scaleY;

    /** Texture format specification for this attachment. */
    private final CgTextureFormatSpec format;

    /** Mipmap configuration for this attachment (may be disabled). */
    private final CgMipmapConfig mipmaps;

    /**
     * Private constructor; use {@link #builder()} to create instances.
     *
     * @param scaleX   X-axis scale factor (must be positive)
     * @param scaleY   Y-axis scale factor (must be positive)
     * @param format   texture format specification (must not be null)
     * @param mipmaps  mipmap configuration (must not be null)
     */
    private CgColorAttachmentSpec(float scaleX, float scaleY,
                                  CgTextureFormatSpec format, CgMipmapConfig mipmaps) {
        this.scaleX = scaleX;
        this.scaleY = scaleY;
        this.format = format;
        this.mipmaps = mipmaps;
    }

    /**
     * Returns a new builder for constructing {@code CgColorAttachmentSpec} instances.
     *
     * @return a builder with default values
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the X-axis scale factor.
     *
     * <p>The computed width of this attachment is {@code baseWidth * scaleX}.</p>
     *
     * @return the scale factor for width (always positive)
     */
    public float getScaleX() {
        return scaleX;
    }

    /**
     * Returns the Y-axis scale factor.
     *
     * <p>The computed height of this attachment is {@code baseHeight * scaleY}.</p>
     *
     * @return the scale factor for height (always positive)
     */
    public float getScaleY() {
        return scaleY;
    }

    /**
     * Returns the texture format specification for this attachment.
     *
     * @return the format specification (never null)
     */
    public CgTextureFormatSpec getFormat() {
        return format;
    }

    /**
     * Returns the mipmap configuration for this attachment.
     *
     * @return the mipmap configuration (never null, may be disabled)
     */
    public CgMipmapConfig getMipmaps() {
        return mipmaps;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        CgColorAttachmentSpec that = (CgColorAttachmentSpec) o;

        if (Float.compare(that.scaleX, scaleX) != 0) return false;
        if (Float.compare(that.scaleY, scaleY) != 0) return false;
        if (!format.equals(that.format)) return false;
        if (!mipmaps.equals(that.mipmaps)) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = (scaleX != +0.0f ? Float.floatToIntBits(scaleX) : 0);
        result = 31 * result + (scaleY != +0.0f ? Float.floatToIntBits(scaleY) : 0);
        result = 31 * result + format.hashCode();
        result = 31 * result + mipmaps.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "CgColorAttachmentSpec{" +
                "scaleX=" + scaleX +
                ", scaleY=" + scaleY +
                ", format=" + format +
                ", mipmaps=" + mipmaps +
                '}';
    }

    /**
     * Builder for {@code CgColorAttachmentSpec} instances.
     *
     * <p>Provides a fluent interface for constructing specifications with
     * default values for optional properties.</p>
     */
    public static final class Builder {

        private float scaleX = DEFAULT_SCALE;
        private float scaleY = DEFAULT_SCALE;
        private CgTextureFormatSpec format;
        private CgMipmapConfig mipmaps = CgMipmapConfig.disabled();

        /**
         * Private constructor; use {@link CgColorAttachmentSpec#builder()}.
         */
        private Builder() {
        }

        /**
         * Sets the X-axis scale factor.
         *
         * @param scaleX the width multiplier (must be positive)
         * @return this builder for method chaining
         * @throws IllegalArgumentException if {@code scaleX} is not positive
         */
        public Builder scaleX(float scaleX) {
            if (scaleX <= 0.0f) {
                throw new IllegalArgumentException("Scale factor must be positive, got: " + scaleX);
            }
            this.scaleX = scaleX;
            return this;
        }

        /**
         * Sets the Y-axis scale factor.
         *
         * @param scaleY the height multiplier (must be positive)
         * @return this builder for method chaining
         * @throws IllegalArgumentException if {@code scaleY} is not positive
         */
        public Builder scaleY(float scaleY) {
            if (scaleY <= 0.0f) {
                throw new IllegalArgumentException("Scale factor must be positive, got: " + scaleY);
            }
            this.scaleY = scaleY;
            return this;
        }

        /**
         * Sets both X and Y scale factors to the same value.
         *
         * @param scale the uniform scale factor (must be positive)
         * @return this builder for method chaining
         * @throws IllegalArgumentException if {@code scale} is not positive
         */
        public Builder scale(float scale) {
            if (scale <= 0.0f) {
                throw new IllegalArgumentException("Scale factor must be positive, got: " + scale);
            }
            this.scaleX = scale;
            this.scaleY = scale;
            return this;
        }

        /**
         * Sets the texture format specification.
         *
         * @param format the format specification (must not be null)
         * @return this builder for method chaining
         * @throws IllegalArgumentException if {@code format} is null
         */
        public Builder format(CgTextureFormatSpec format) {
            if (format == null) {
                throw new IllegalArgumentException("Format must not be null");
            }
            this.format = format;
            return this;
        }

        /**
         * Sets the mipmap configuration.
         *
         * @param mipmaps the mipmap configuration (must not be null)
         * @return this builder for method chaining
         * @throws IllegalArgumentException if {@code mipmaps} is null
         */
        public Builder mipmaps(CgMipmapConfig mipmaps) {
            if (mipmaps == null) {
                throw new IllegalArgumentException("Mipmap config must not be null");
            }
            this.mipmaps = mipmaps;
            return this;
        }

        /**
         * Constructs a {@code CgColorAttachmentSpec} from the current builder state.
         *
         * @return a new immutable specification
         * @throws IllegalArgumentException if required fields (format) are missing
         */
        public CgColorAttachmentSpec build() {
            if (format == null) {
                throw new IllegalArgumentException("Format is required");
            }
            return new CgColorAttachmentSpec(scaleX, scaleY, format, mipmaps);
        }
    }
}
