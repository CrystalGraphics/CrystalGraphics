package io.github.somehussar.crystalgraphics.api.framebuffer;

/**
 * Immutable specification of a texture format for framebuffer attachments.
 *
 * <p>This value object encapsulates the three OpenGL format parameters required
 * to configure a texture image for use as a framebuffer attachment:</p>
 * <ul>
 *   <li><strong>Internal Format</strong>: The OpenGL internal format constant
 *       (e.g., {@code GL_RGBA8}, {@code GL_RGBA16F}, {@code GL_DEPTH_COMPONENT24})</li>
 *   <li><strong>Pixel Format</strong>: The format of pixel data being transferred
 *       (e.g., {@code GL_RGBA}, {@code GL_DEPTH_COMPONENT})</li>
 *   <li><strong>Pixel Type</strong>: The data type of pixel values
 *       (e.g., {@code GL_UNSIGNED_BYTE}, {@code GL_FLOAT})</li>
 * </ul>
 *
 * <p>Instances are immutable and thread-safe. No validation of OpenGL constant
 * values is performed; invalid combinations will be detected at texture creation
 * time by the OpenGL driver.</p>
 *
 * <h3>Examples</h3>
 * <pre>
 * // 8-bit RGBA color attachment
 * CgTextureFormatSpec rgba8 = new CgTextureFormatSpec(
 *     0x8058,                  // GL_RGBA8 (internalFormat)
 *     0x1908,                  // GL_RGBA (pixelFormat)
 *     0x1401                   // GL_UNSIGNED_BYTE (pixelType)
 * );
 *
 * // 16-bit floating-point RGBA
 * CgTextureFormatSpec rgba16f = new CgTextureFormatSpec(
 *     0x881A,                  // GL_RGBA16F (internalFormat)
 *     0x1908,                  // GL_RGBA (pixelFormat)
 *     0x140B                   // GL_HALF_FLOAT (pixelType)
 * );
 * </pre>
 */
public final class CgTextureFormatSpec {

    /** The OpenGL internal format constant. */
    private final int internalFormat;

    /** The OpenGL pixel format constant. */
    private final int pixelFormat;

    /** The OpenGL pixel data type constant. */
    private final int pixelType;

    /**
     * Constructs a texture format specification.
     *
     * @param internalFormat the OpenGL internal format constant
     *                       (e.g., {@code GL_RGBA8}, {@code GL_RGBA16F})
     * @param pixelFormat    the OpenGL pixel format constant
     *                       (e.g., {@code GL_RGBA}, {@code GL_DEPTH_COMPONENT})
     * @param pixelType      the OpenGL pixel data type constant
     *                       (e.g., {@code GL_UNSIGNED_BYTE}, {@code GL_FLOAT})
     */
    public CgTextureFormatSpec(int internalFormat, int pixelFormat, int pixelType) {
        this.internalFormat = internalFormat;
        this.pixelFormat = pixelFormat;
        this.pixelType = pixelType;
    }

    /**
     * Returns the OpenGL internal format constant.
     *
     * @return the internal format (e.g., {@code GL_RGBA8})
     */
    public int getInternalFormat() {
        return internalFormat;
    }

    /**
     * Returns the OpenGL pixel format constant.
     *
     * @return the pixel format (e.g., {@code GL_RGBA})
     */
    public int getPixelFormat() {
        return pixelFormat;
    }

    /**
     * Returns the OpenGL pixel data type constant.
     *
     * @return the pixel type (e.g., {@code GL_UNSIGNED_BYTE})
     */
    public int getPixelType() {
        return pixelType;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        CgTextureFormatSpec that = (CgTextureFormatSpec) o;

        if (internalFormat != that.internalFormat) return false;
        if (pixelFormat != that.pixelFormat) return false;
        if (pixelType != that.pixelType) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = internalFormat;
        result = 31 * result + pixelFormat;
        result = 31 * result + pixelType;
        return result;
    }

    @Override
    public String toString() {
        return "CgTextureFormatSpec{" +
                "internalFormat=" + internalFormat +
                ", pixelFormat=" + pixelFormat +
                ", pixelType=" + pixelType +
                '}';
    }
}
