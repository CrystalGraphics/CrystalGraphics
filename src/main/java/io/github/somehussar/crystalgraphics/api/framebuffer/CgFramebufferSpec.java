package io.github.somehussar.crystalgraphics.api.framebuffer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable specification for creating a framebuffer object with CrystalGraphics.
 *
 * <p>This value object encapsulates all parameters needed to construct a framebuffer:
 * base dimensions, color attachments, and depth/stencil configuration. It is used
 * by the framebuffer factory to create concrete FBO instances on the appropriate
 * backend (Core GL30, ARB, or EXT).</p>
 *
 * <h3>Base Size vs. Attachment Scaling</h3>
 * <p>The framebuffer has a base size (width, height). Each color attachment has
 * independent scale factors ({@code scaleX}, {@code scaleY}) that determine its
 * actual size relative to the base. For example:</p>
 * <pre>
 *   CgFramebufferSpec spec = CgFramebufferSpec.builder()
 *       .baseWidth(1024)
 *       .baseHeight(768)
 *       .addColorAttachment(...)  // full-res (1024x768)
 *       .addColorAttachment(CgColorAttachmentSpec.builder()
 *           .scale(0.5f)           // half-res (512x384)
 *           .format(...)
 *           .build())
 *       .build();
 * </pre>
 *
 * <h3>Validation</h3>
 * <p>Strict validation is enforced:</p>
 * <ul>
 *   <li>Base width and height must be positive</li>
 *   <li>At least one color attachment is required</li>
 *   <li>Color attachments must not be null</li>
 *   <li>Depth/stencil configuration must not be null</li>
 *   <li>Conflicting depth/stencil requests (e.g., both packed and separate)
 *       are invalid (validated by {@link CgDepthStencilSpec})</li>
 * </ul>
 *
 * <h3>Immutability</h3>
 * <p>Instances are immutable and thread-safe. The color attachment list is
 * not exposed directly to prevent external modification.</p>
 *
 * <h3>Examples</h3>
 * <pre>
 * // Basic framebuffer with one RGBA8 color attachment and packed depth-stencil
 * CgFramebufferSpec basic = CgFramebufferSpec.builder()
 *     .baseWidth(1024)
 *     .baseHeight(768)
 *     .addColorAttachment(CgColorAttachmentSpec.builder()
 *         .format(new CgTextureFormatSpec(0x8058, 0x1908, 0x1401)) // RGBA8
 *         .build())
 *     .depthStencil(CgDepthStencilSpec.packedDepthStencil(0x88F0)) // GL_DEPTH24_STENCIL8
 *     .build();
 *
 * // MRT framebuffer with different attachment sizes
 * CgFramebufferSpec mrt = CgFramebufferSpec.builder()
 *     .baseWidth(1024)
 *     .baseHeight(1024)
 *     .addColorAttachment(CgColorAttachmentSpec.builder()
 *         .format(new CgTextureFormatSpec(0x8058, 0x1908, 0x1401))
 *         .build())
 *     .addColorAttachment(CgColorAttachmentSpec.builder()
 *         .scale(0.5f) // half-resolution
 *         .format(new CgTextureFormatSpec(0x881A, 0x1908, 0x140B)) // RGBA16F
 *         .build())
 *     .depthStencil(CgDepthStencilSpec.depthOnly(0x1902)) // GL_DEPTH_COMPONENT
 *     .build();
 * </pre>
 *
 * @see CgColorAttachmentSpec
 * @see CgDepthStencilSpec
 */
public final class CgFramebufferSpec {

    /** Base framebuffer width in pixels. */
    private final int baseWidth;

    /** Base framebuffer height in pixels. */
    private final int baseHeight;

    /** Immutable list of color attachment specifications. */
    private final List<CgColorAttachmentSpec> colorAttachments;

    /** Depth and stencil configuration. */
    private final CgDepthStencilSpec depthStencil;

    /**
     * Private constructor; use {@link #builder()} to create instances.
     *
     * @param baseWidth        base width in pixels (must be positive)
     * @param baseHeight       base height in pixels (must be positive)
     * @param colorAttachments list of color attachments (must contain at least one)
     * @param depthStencil     depth/stencil configuration (must not be null)
     */
    private CgFramebufferSpec(int baseWidth, int baseHeight,
                              List<CgColorAttachmentSpec> colorAttachments,
                              CgDepthStencilSpec depthStencil) {
        this.baseWidth = baseWidth;
        this.baseHeight = baseHeight;
        this.colorAttachments = Collections.unmodifiableList(colorAttachments);
        this.depthStencil = depthStencil;
    }

    /**
     * Returns a new builder for constructing {@code CgFramebufferSpec} instances.
     *
     * @return a builder with default values
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the base width of this framebuffer in pixels.
     *
     * <p>The actual width of each color attachment is computed as
     * {@code baseWidth * attachment.getScaleX()}.</p>
     *
     * @return the base width (always positive)
     */
    public int getBaseWidth() {
        return baseWidth;
    }

    /**
     * Returns the base height of this framebuffer in pixels.
     *
     * <p>The actual height of each color attachment is computed as
     * {@code baseHeight * attachment.getScaleY()}.</p>
     *
     * @return the base height (always positive)
     */
    public int getBaseHeight() {
        return baseHeight;
    }

    /**
     * Returns the number of color attachments in this specification.
     *
     * @return the attachment count (always at least 1)
     */
    public int getColorAttachmentCount() {
        return colorAttachments.size();
    }

    /**
     * Returns the color attachment at the specified index.
     *
     * @param index the attachment index (must be in range [0, getColorAttachmentCount()))
     * @return the attachment specification
     * @throws IndexOutOfBoundsException if index is out of range
     */
    public CgColorAttachmentSpec getColorAttachment(int index) {
        return colorAttachments.get(index);
    }

    /**
     * Returns an immutable list of all color attachments.
     *
     * @return the list of color attachment specifications (never null or empty)
     */
    public List<CgColorAttachmentSpec> getColorAttachments() {
        return colorAttachments;
    }

    /**
     * Returns the depth and stencil configuration for this framebuffer.
     *
     * @return the depth/stencil specification (never null, may be "none")
     */
    public CgDepthStencilSpec getDepthStencil() {
        return depthStencil;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        CgFramebufferSpec that = (CgFramebufferSpec) o;

        if (baseWidth != that.baseWidth) return false;
        if (baseHeight != that.baseHeight) return false;
        if (!colorAttachments.equals(that.colorAttachments)) return false;
        if (!depthStencil.equals(that.depthStencil)) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = baseWidth;
        result = 31 * result + baseHeight;
        result = 31 * result + colorAttachments.hashCode();
        result = 31 * result + depthStencil.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "CgFramebufferSpec{" +
                "baseWidth=" + baseWidth +
                ", baseHeight=" + baseHeight +
                ", colorAttachments=" + colorAttachments.size() +
                ", depthStencil=" + depthStencil +
                '}';
    }

    /**
     * Builder for {@code CgFramebufferSpec} instances.
     *
     * <p>Provides a fluent interface for constructing framebuffer specifications
     * with validation of required and optional properties.</p>
     */
    public static final class Builder {

        private int baseWidth;
        private int baseHeight;
        private final List<CgColorAttachmentSpec> colorAttachments = new ArrayList<>();
        private CgDepthStencilSpec depthStencil = CgDepthStencilSpec.none();

        /**
         * Private constructor; use {@link CgFramebufferSpec#builder()}.
         */
        private Builder() {
        }

        /**
         * Sets the base framebuffer width.
         *
         * @param width the width in pixels (must be positive)
         * @return this builder for method chaining
         * @throws IllegalArgumentException if width is not positive
         */
        public Builder baseWidth(int width) {
            if (width <= 0) {
                throw new IllegalArgumentException("Base width must be positive, got: " + width);
            }
            this.baseWidth = width;
            return this;
        }

        /**
         * Sets the base framebuffer height.
         *
         * @param height the height in pixels (must be positive)
         * @return this builder for method chaining
         * @throws IllegalArgumentException if height is not positive
         */
        public Builder baseHeight(int height) {
            if (height <= 0) {
                throw new IllegalArgumentException("Base height must be positive, got: " + height);
            }
            this.baseHeight = height;
            return this;
        }

        /**
         * Sets the base framebuffer dimensions.
         *
         * @param width  the width in pixels (must be positive)
         * @param height the height in pixels (must be positive)
         * @return this builder for method chaining
         * @throws IllegalArgumentException if either dimension is not positive
         */
        public Builder baseDimensions(int width, int height) {
            baseWidth(width);
            baseHeight(height);
            return this;
        }

        /**
         * Adds a color attachment specification.
         *
         * @param attachment the attachment specification (must not be null)
         * @return this builder for method chaining
         * @throws IllegalArgumentException if attachment is null
         */
        public Builder addColorAttachment(CgColorAttachmentSpec attachment) {
            if (attachment == null) {
                throw new IllegalArgumentException("Color attachment must not be null");
            }
            colorAttachments.add(attachment);
            return this;
        }

        /**
         * Sets the depth and stencil configuration.
         *
         * <p>If not called, defaults to {@link CgDepthStencilSpec#none()}.</p>
         *
         * @param depthStencil the depth/stencil specification (must not be null)
         * @return this builder for method chaining
         * @throws IllegalArgumentException if depthStencil is null
         */
        public Builder depthStencil(CgDepthStencilSpec depthStencil) {
            if (depthStencil == null) {
                throw new IllegalArgumentException("Depth/stencil spec must not be null");
            }
            this.depthStencil = depthStencil;
            return this;
        }

        /**
         * Constructs a {@code CgFramebufferSpec} from the current builder state.
         *
         * @return a new immutable specification
         * @throws IllegalArgumentException if required fields are missing or invalid
         */
        public CgFramebufferSpec build() {
            if (baseWidth <= 0) {
                throw new IllegalArgumentException("Base width must be set and positive");
            }
            if (baseHeight <= 0) {
                throw new IllegalArgumentException("Base height must be set and positive");
            }
            if (colorAttachments.isEmpty()) {
                throw new IllegalArgumentException("At least one color attachment is required");
            }

            return new CgFramebufferSpec(baseWidth, baseHeight, colorAttachments, depthStencil);
        }
    }
}
