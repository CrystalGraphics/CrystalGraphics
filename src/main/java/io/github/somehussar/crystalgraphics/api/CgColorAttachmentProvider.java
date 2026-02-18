package io.github.somehussar.crystalgraphics.api;

/**
 * Functional interface for allocating and managing provider-based color attachment textures.
 *
 * <p>This interface represents a strategy for creating color textures that will be owned
 * and managed by a {@code CgFramebuffer}. The framebuffer calls this provider to:</p>
 * <ul>
 *   <li>Allocate a color texture on initial {@link CgRuntimeAttachments#attachManaged(int, CgColorAttachmentProvider, CgTextureFormatSpec)}</li>
 *   <li>Reallocate the texture to a new size when {@link CgFramebuffer#resize(int, int)} is called</li>
 * </ul>
 *
 * <h3>Ownership Semantics</h3>
 * <p>Once a provider is registered, the framebuffer becomes the owner of all textures
 * allocated through it. The framebuffer is responsible for:</p>
 * <ul>
 *   <li>Deleting textures when the attachment is detached via {@link CgRuntimeAttachments#detach(int)}</li>
 *   <li>Deleting textures when the framebuffer is resized (as new textures are allocated)</li>
 *   <li>Deleting textures when the framebuffer itself is deleted</li>
 * </ul>
 *
 * <p>The provider should not attempt to manage the lifecycle of its returned textures;
 * the framebuffer assumes full ownership.</p>
 *
 * <h3>Reallocation on Resize</h3>
 * <p>When {@link CgFramebuffer#resize(int, int)} is called, the framebuffer will:</p>
 * <ol>
 *   <li>Call {@code allocate(newWidth, newHeight, format)} for each managed attachment</li>
 *   <li>Delete the old texture ID</li>
 *   <li>Attach the new texture ID to the same slot</li>
 * </ol>
 *
 * <p>The provider must respect the dimensions and format parameters and return a
 * valid OpenGL texture ID suitable for use as a color attachment.</p>
 *
 * <h3>Example: Simple RGBA8 Allocator</h3>
 * <pre>
 * CgColorAttachmentProvider rgbaAllocator = (width, height, format) -> {
 *     int texId = GL11.glGenTextures();
 *     GL11.glBindTexture(GL11.GL_TEXTURE_2D, texId);
 *     GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0,
 *         format.getInternalFormat(),
 *         width, height, 0,
 *         format.getPixelFormat(),
 *         format.getPixelType(),
 *         (ByteBuffer) null);
 *     GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
 *     GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
 *     return texId;
 * };
 *
 * CgRuntimeAttachments runtime = framebuffer.getRuntimeAttachments();
 * runtime.attachManaged(8, rgbaAllocator,
 *     new CgTextureFormatSpec(0x8058, 0x1908, 0x1401)); // GL_RGBA8, GL_RGBA, GL_UNSIGNED_BYTE
 * </pre>
 *
 * @see CgRuntimeAttachments#attachManaged(int, CgColorAttachmentProvider, CgTextureFormatSpec)
 * @see CgFramebuffer#resize(int, int)
 */
@FunctionalInterface
public interface CgColorAttachmentProvider {

    /**
     * Allocates (or reallocates) a color texture for the given dimensions and format.
     *
     * <p>This method is called when:</p>
     * <ul>
     *   <li>A managed attachment is first registered via
     *       {@link CgRuntimeAttachments#attachManaged(int, CgColorAttachmentProvider, CgTextureFormatSpec)}</li>
     *   <li>The framebuffer is resized via {@link CgFramebuffer#resize(int, int)}</li>
     * </ul>
     *
     * <p>The implementation must:</p>
     * <ol>
     *   <li>Create an OpenGL texture using the provided dimensions and format</li>
     *   <li>Allocate texture storage (e.g., via {@code glTexImage2D})</li>
     *   <li>Return the OpenGL texture ID (a positive integer)</li>
     * </ol>
     *
     * <p>The framebuffer will assume ownership of the returned texture and will delete it
     * when appropriate (on reallocation, detachment, or framebuffer deletion).</p>
     *
     * <h3>Format Parameter</h3>
     * <p>The {@code format} parameter specifies the texture format to allocate. Use its
     * accessors to obtain the OpenGL constants:</p>
     * <ul>
     *   <li>{@link CgTextureFormatSpec#getInternalFormat()} — internal format constant</li>
     *   <li>{@link CgTextureFormatSpec#getPixelFormat()} — pixel format constant</li>
     *   <li>{@link CgTextureFormatSpec#getPixelType()} — pixel data type constant</li>
     * </ul>
     *
     * <h3>No GL State Guarantees</h3>
     * <p>This method must not assume any particular OpenGL state. Set all necessary
     * texture parameters and bind states explicitly.</p>
     *
     * <h3>Thread Safety</h3>
     * <p>This method is always called on the thread that owns the OpenGL context
     * (the render thread).</p>
     *
     * @param width  the width in pixels (always positive)
     * @param height the height in pixels (always positive)
     * @param format the texture format specification (never null)
     * @return an OpenGL texture ID (must be positive). The framebuffer assumes ownership
     *         of this texture and will delete it when appropriate.
     * @throws IllegalArgumentException if width or height is not positive
     * @throws RuntimeException or OpenGL-related exception if texture allocation fails
     */
    int allocate(int width, int height, CgTextureFormatSpec format);
}
