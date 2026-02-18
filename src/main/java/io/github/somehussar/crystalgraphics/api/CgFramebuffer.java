package io.github.somehussar.crystalgraphics.api;

/**
 * Primary public API for framebuffer object (FBO) management in CrystalGraphics.
 *
 * <p>This interface abstracts the differences between OpenGL Core (GL30),
 * ARB ({@code ARB_framebuffer_object}), and EXT ({@code EXT_framebuffer_object})
 * framebuffer entry points behind a single, unified contract.  Implementations
 * are created by the factory/backend layer and should never be instantiated
 * directly by consuming code.</p>
 *
 * <h3>Ownership Model</h3>
 * <p>Each {@code CgFramebuffer} is either <em>owned</em> or <em>wrapped</em>:</p>
 * <ul>
 *   <li><strong>Owned</strong> ({@link #isOwned()} returns {@code true}):
 *       CrystalGraphics created this FBO and is responsible for its lifecycle.
 *       Calling {@link #delete()} is valid and will release the underlying
 *       OpenGL resource.</li>
 *   <li><strong>Wrapped</strong> ({@link #isOwned()} returns {@code false}):
 *       This FBO was created externally (e.g., by another mod or by Minecraft
 *       itself) and is merely tracked by CrystalGraphics.  Calling
 *       {@link #delete()} on a wrapped FBO will throw
 *       {@link IllegalStateException}.</li>
 * </ul>
 *
 * <h3>Call Family Semantics</h3>
 * <p>Implementations bind framebuffers through a specific OpenGL call family
 * (Core GL30, ARB, or EXT).  When the currently active call family differs
 * from this framebuffer's family, the implementation (via
 * {@link io.github.somehussar.crystalgraphics.gl.CrossApiTransition}) performs
 * a defensive unbind of the previous family before binding through its own,
 * preventing undefined driver behavior on mixed-family transitions.</p>
 *
 * <h3>Multiple Render Targets (MRT)</h3>
 * <p>MRT support depends on the underlying hardware and chosen backend.
 * Use {@link #supportsMrt()} to check availability before calling
 * {@link #drawBuffers(int...)}.  Attempting to set multiple draw buffers
 * on a backend that does not support MRT will throw
 * {@link UnsupportedOperationException}.</p>
 *
 * <h3>Thread Safety</h3>
 * <p>Instances are <strong>not</strong> thread-safe.  All methods must be
 * called on the thread that owns the OpenGL context (typically the render
 * thread).</p>
 *
 * @see CgCapabilities
 * @see io.github.somehussar.crystalgraphics.gl.CrossApiTransition
 */
public interface CgFramebuffer {

    /**
     * Binds this framebuffer for both drawing and reading
     * ({@code GL_FRAMEBUFFER} target, constant {@code 0x8D40}).
     *
     * <p>After this call, all subsequent draw and read operations target
     * this framebuffer until another framebuffer is bound or
     * {@link #unbind()} is called.</p>
     */
    void bind();

    /**
     * Binds this framebuffer for drawing only
     * ({@code GL_DRAW_FRAMEBUFFER} target, constant {@code 0x8CA9}).
     *
     * <p>The read framebuffer binding is not affected.  Note that the
     * EXT framebuffer extension does not support separate draw/read
     * targets; on EXT backends this method behaves identically to
     * {@link #bind()}.</p>
     */
    void bindDraw();

    /**
     * Binds this framebuffer for reading only
     * ({@code GL_READ_FRAMEBUFFER} target, constant {@code 0x8CA8}).
     *
     * <p>The draw framebuffer binding is not affected.  Note that the
     * EXT framebuffer extension does not support separate draw/read
     * targets; on EXT backends this method behaves identically to
     * {@link #bind()}.</p>
     */
    void bindRead();

    /**
     * Unbinds this framebuffer by binding the default framebuffer (ID 0)
     * for both draw and read targets.
     *
     * <p>After this call, rendering targets the window-system-provided
     * default framebuffer.</p>
     */
    void unbind();

    /**
     * Returns the raw OpenGL framebuffer object ID.
     *
     * @return the OpenGL FBO name (a positive integer), or 0 if this
     *         represents the default framebuffer
     */
    int getId();

    /**
     * Returns the width of this framebuffer in pixels.
     *
     * @return width in pixels (always positive)
     */
    int getWidth();

    /**
     * Returns the height of this framebuffer in pixels.
     *
     * @return height in pixels (always positive)
     */
    int getHeight();

    /**
     * Sets the draw buffers for multiple render target (MRT) rendering.
     *
     * <p>Each element should be a {@code GL_COLOR_ATTACHMENTi} constant
     * (e.g., {@code GL_COLOR_ATTACHMENT0 = 0x8CE0}).  The draw buffers
     * are set via the appropriate GL call for this framebuffer's backend
     * (Core GL20/GL30 or ARB).</p>
     *
     * @param attachments one or more {@code GL_COLOR_ATTACHMENTi} constants
     *                    specifying which color attachments to draw to
     * @throws UnsupportedOperationException if MRT is not available on
     *         this framebuffer's backend (check {@link #supportsMrt()} first)
     * @throws IllegalArgumentException if {@code attachments} is null or empty
     */
    void drawBuffers(int... attachments);

    /**
     * Returns whether multiple render targets (MRT) are supported by this
     * framebuffer's backend.
     *
     * <p>MRT requires either Core GL20+ draw buffer support or the
     * {@code ARB_draw_buffers} extension.  The EXT framebuffer path
     * typically does not support MRT.</p>
     *
     * @return {@code true} if {@link #drawBuffers(int...)} can be called
     *         with multiple attachments; {@code false} otherwise
     */
    boolean supportsMrt();

    /**
     * Returns whether CrystalGraphics owns this framebuffer and may delete it.
     *
     * @return {@code true} if this FBO was created by CrystalGraphics and
     *         may be deleted via {@link #delete()}; {@code false} if it was
     *         created externally (wrapped) and must not be deleted
     */
    boolean isOwned();

    /**
     * Deletes this framebuffer, releasing the underlying OpenGL resource.
     *
     * <p>Only valid if {@link #isOwned()} returns {@code true}.  After
     * deletion, {@link #isDeleted()} returns {@code true} and no further
     * operations on this framebuffer are valid.</p>
     *
     * @throws IllegalStateException if {@link #isOwned()} returns {@code false},
     *         indicating this FBO was created externally and must not be
     *         deleted by CrystalGraphics
     * @throws IllegalStateException if {@link #isDeleted()} already returns
     *         {@code true}
     */
    void delete();

    /**
     * Returns whether this framebuffer has been deleted.
     *
     * @return {@code true} if {@link #delete()} has been called successfully;
     *         {@code false} otherwise
     */
    boolean isDeleted();

    /**
     * Resizes this framebuffer to the specified dimensions.
     *
     * <p>All existing attachments (color, depth, stencil) are recreated at
     * the new size.  The framebuffer does not need to be bound when this
     * method is called.  After resizing, the framebuffer is left unbound.</p>
     *
     * @param width  new width in pixels (must be positive)
     * @param height new height in pixels (must be positive)
     * @throws IllegalArgumentException if width or height is not positive
     * @throws IllegalStateException if this framebuffer has been deleted
     */
    void resize(int width, int height);
}
