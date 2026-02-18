package io.github.somehussar.crystalgraphics.api;

/**
 * Runtime color attachment management interface for {@code CgFramebuffer}.
 *
 * <p>This interface enables dynamic attachment and detachment of color textures
 * at runtime, without recreating the entire framebuffer. It supports two modes:</p>
 * <ul>
 *   <li><strong>External fixed</strong>: Caller supplies a texture ID; the framebuffer
 *       does not own it and does not resize it. Use for textures from other sources.</li>
 *   <li><strong>Provider-based managed</strong>: Framebuffer allocates textures via
 *       a {@link CgColorAttachmentProvider}; the framebuffer owns and auto-resizes them.</li>
 * </ul>
 *
 * <h3>Usage Constraints</h3>
 * <p>Runtime attachments are separate from framebuffer spec-defined color attachments.
 * If the framebuffer was created with {@code colorAttachmentCount} of 4, you can attach
 * runtime attachments starting at slot 4 or higher. Attempting to override managed
 * (spec-defined) slots will throw {@link IllegalArgumentException}.</p>
 *
 * <h3>Attachment Lifecycle</h3>
 * <ul>
 *   <li><strong>External</strong>: Caller is responsible for lifetime management. When
 *       detached, the framebuffer does not delete the texture.</li>
 *   <li><strong>Managed</strong>: Framebuffer owns the texture. On {@link #detach(int)},
 *       the framebuffer deletes the texture. On {@link CgFramebuffer#resize(int, int)},
 *       the framebuffer reallocates via the provider. On
 *       {@link CgFramebuffer#delete()}, all managed textures are deleted.</li>
 * </ul>
 *
 * <h3>Example: Attaching Two Color Targets Dynamically</h3>
 * <pre>
 * CgFramebuffer fbo = ...; // Created with colorAttachmentCount=2
 * CgRuntimeAttachments rt = fbo.getRuntimeAttachments();
 *
 * // Attach an external texture (managed by caller)
 * int externalTexId = createTextureElsewhere();
 * rt.attachExternal(4, externalTexId);
 *
 * // Attach a managed texture (allocated by framebuffer)
 * CgColorAttachmentProvider allocator = (w, h, fmt) -> createRgba8Texture(w, h);
 * CgTextureFormatSpec format = new CgTextureFormatSpec(0x8058, 0x1908, 0x1401);
 * rt.attachManaged(5, allocator, format);
 *
 * // Now drawing to slots [0, 1] (from spec) and [4, 5] (runtime)
 * fbo.drawBuffers(
 *     0x8CE0, 0x8CE1,           // GL_COLOR_ATTACHMENT0, GL_COLOR_ATTACHMENT1
 *     0x8CE4, 0x8CE5            // GL_COLOR_ATTACHMENT4, GL_COLOR_ATTACHMENT5
 * );
 * </pre>
 *
 * @see CgFramebuffer#getRuntimeAttachments()
 * @see CgColorAttachmentProvider
 */
public interface CgRuntimeAttachments {

    /**
     * Attaches an externally-managed texture at the given slot.
     *
     * <p>The texture is not owned by the framebuffer; the caller retains responsibility
     * for its lifetime. The framebuffer will not delete it on detachment or resize.</p>
     *
     * <h3>Slot Validation</h3>
     * <p>The slot index must be greater than or equal to the framebuffer's
     * {@code colorAttachmentCount} (i.e., not overwriting managed spec slots).
     * Using an invalid slot throws {@link IllegalArgumentException}.</p>
     *
     * <h3>Texture Format</h3>
     * <p>The texture must be a valid color texture suitable for use as a framebuffer
     * color attachment. No format validation is performed; invalid formats are
     * detected at OpenGL attachment time.</p>
     *
     * @param slot      the attachment slot index (e.g., 4 for GL_COLOR_ATTACHMENT4).
     *                  Must be >= framebuffer's colorAttachmentCount
     * @param textureId the OpenGL texture ID (must be positive)
     * @throws IllegalArgumentException if slot is reserved for managed attachments
     * @throws IllegalArgumentException if textureId is not positive
     * @throws IllegalStateException if the framebuffer is deleted
     */
    void attachExternal(int slot, int textureId);

    /**
     * Attaches a provider-based managed color texture at the given slot.
     *
     * <p>The framebuffer becomes the owner of this attachment. It will:</p>
     * <ul>
     *   <li>Call the provider to allocate an initial texture</li>
     *   <li>Delete and reallocate textures on framebuffer resize</li>
     *   <li>Delete the texture on detachment or framebuffer deletion</li>
     * </ul>
     *
     * <h3>Reallocation on Resize</h3>
     * <p>When {@link CgFramebuffer#resize(int, int)} is called, the framebuffer will
     * call {@code provider.allocate(newWidth, newHeight, format)} to get a new texture.
     * The old texture is deleted before the new one is attached.</p>
     *
     * <h3>Slot Validation</h3>
     * <p>The slot index must not be in the range of managed spec slots (i.e., >=
     * framebuffer's colorAttachmentCount). Using a reserved slot throws
     * {@link IllegalArgumentException}.</p>
     *
     * @param slot      the attachment slot index (e.g., 5 for GL_COLOR_ATTACHMENT5).
     *                  Must be >= framebuffer's colorAttachmentCount
     * @param provider  the texture allocator (must not be null)
     * @param format    the texture format specification (must not be null)
     * @throws IllegalArgumentException if slot is reserved for managed attachments
     * @throws IllegalArgumentException if provider or format is null
     * @throws IllegalStateException if the framebuffer is deleted
     */
    void attachManaged(int slot, CgColorAttachmentProvider provider, CgTextureFormatSpec format);

    /**
     * Detaches any runtime attachment at the given slot.
     *
     * <p>If the attachment is external, no cleanup occurs (caller is responsible).
     * If the attachment is managed, the framebuffer deletes the texture.</p>
     *
     * <p>If no attachment exists at the slot, this method is a no-op.</p>
     *
     * @param slot the attachment slot index
     * @throws IllegalStateException if the framebuffer is deleted
     */
    void detach(int slot);

    /**
     * Returns the texture ID currently attached at the given slot.
     *
     * @param slot the attachment slot index
     * @return the OpenGL texture ID (a positive integer), or 0 if no attachment exists at this slot
     * @throws IllegalStateException if the framebuffer is deleted
     */
    int getTextureId(int slot);

    /**
     * Returns whether a runtime attachment exists at the given slot.
     *
     * @param slot the attachment slot index
     * @return {@code true} if an attachment (external or managed) exists at this slot;
     *         {@code false} otherwise
     * @throws IllegalStateException if the framebuffer is deleted
     */
    boolean hasAttachment(int slot);
}
