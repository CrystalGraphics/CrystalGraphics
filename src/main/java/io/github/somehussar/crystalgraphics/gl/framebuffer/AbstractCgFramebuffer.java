package io.github.somehussar.crystalgraphics.gl.framebuffer;

import io.github.somehussar.crystalgraphics.api.framebuffer.CgFramebuffer;
import io.github.somehussar.crystalgraphics.api.framebuffer.CgRuntimeAttachments;
import io.github.somehussar.crystalgraphics.gl.CrossApiTransition;
import io.github.somehussar.crystalgraphics.gl.state.CallFamily;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Abstract base class for all CrystalGraphics framebuffer implementations.
 *
 * <p>Provides the common lifecycle (create, bind, delete) and resource tracking
 * shared by the Core GL30, ARB, and EXT backends.  Concrete subclasses supply
 * the actual OpenGL calls through {@link #freeGlResources()} and
 * {@link #callFamily()}.</p>
 *
 * <h3>Ownership Model</h3>
 * <p>Framebuffers created by CrystalGraphics are <em>owned</em>
 * ({@code owned == true}) and tracked in {@link #ALL_OWNED} for bulk cleanup.
 * Externally-created FBOs can be <em>wrapped</em> ({@code owned == false})
 * for binding purposes, but will throw {@link IllegalStateException} if
 * {@link #delete()} is called on them.</p>
 *
 * <h3>Thread Safety</h3>
 * <p>The static tracker set uses {@link CopyOnWriteArraySet} for safe iteration
 * during {@link #freeAll()}.  Individual instances are NOT thread-safe and must
 * only be used from the GL context thread (the Minecraft render thread).</p>
 *
 * @see CgFramebuffer
 * @see CoreFramebuffer
 * @see ArbFramebuffer
 * @see ExtFramebuffer
 */
public abstract class AbstractCgFramebuffer implements CgFramebuffer {

    // ── GL constants (hex literals to avoid importing GL30) ─────────────

    /** {@code GL_FRAMEBUFFER} — binds both draw and read targets. */
    private static final int GL_FRAMEBUFFER = 0x8D40;

    /** {@code GL_DRAW_FRAMEBUFFER} — binds draw target only. */
    private static final int GL_DRAW_FRAMEBUFFER = 0x8CA9;

    /** {@code GL_READ_FRAMEBUFFER} — binds read target only. */
    private static final int GL_READ_FRAMEBUFFER = 0x8CA8;

    // ── Static tracking ────────────────────────────────────────────────

    /**
     * Set of all owned framebuffers created by CrystalGraphics.
     *
     * <p>Used by {@link #freeAll()} to delete every owned FBO during shutdown.
     * Uses {@link CopyOnWriteArraySet} so that iteration in {@code freeAll()}
     * is safe even though {@code delete()} removes elements.</p>
     */
    protected static final Set<AbstractCgFramebuffer> ALL_OWNED =
            new CopyOnWriteArraySet<AbstractCgFramebuffer>();

    // ── Instance fields ────────────────────────────────────────────────

    /** OpenGL framebuffer object ID (the name returned by {@code glGenFramebuffers}). */
    protected int fboId;

    /** Texture ID of the default color attachment (GL_COLOR_ATTACHMENT0), or 0 if none. */
    protected int colorTextureId;

    /** Renderbuffer ID for the depth attachment, or 0 if no depth buffer is attached. */
    protected int depthRenderbufferId;

    /** Width of this framebuffer in pixels. */
    protected int width;

    /** Height of this framebuffer in pixels. */
    protected int height;

    /**
     * Whether CrystalGraphics owns this FBO and is responsible for deleting it.
     *
     * <p>When {@code false}, the FBO was created externally (e.g. by another mod
     * or by Minecraft) and wrapped for convenience.  Calling {@link #delete()}
     * on a non-owned FBO throws {@link IllegalStateException}.</p>
     */
    protected final boolean owned;

    /** Whether {@link #delete()} has been called on this framebuffer. */
    protected boolean deleted;

    /**
     * Whether this framebuffer supports multiple render targets (MRT).
     *
     * <p>MRT support depends on both the GL backend (EXT does not support it)
     * and a runtime capability check for {@code GL_MAX_DRAW_BUFFERS}.</p>
     */
    protected final boolean supportsMrt;

    // ── Constructor ────────────────────────────────────────────────────

    /**
     * Initialises the abstract framebuffer fields.
     *
     * <p>If {@code owned} is {@code true}, the new instance is added to
     * {@link #ALL_OWNED} for lifecycle tracking.</p>
     *
     * @param fboId       the OpenGL framebuffer object ID
     * @param width       width in pixels
     * @param height      height in pixels
     * @param owned       {@code true} if CrystalGraphics created this FBO
     * @param supportsMrt {@code true} if MRT is available for this FBO
     */
    protected AbstractCgFramebuffer(int fboId, int width, int height,
                                    boolean owned, boolean supportsMrt) {
        this.fboId = fboId;
        this.width = width;
        this.height = height;
        this.owned = owned;
        this.supportsMrt = supportsMrt;
        this.deleted = false;
        this.colorTextureId = 0;
        this.depthRenderbufferId = 0;

        if (owned) {
            ALL_OWNED.add(this);
        }
    }

    // ── CgFramebuffer simple getters ───────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * @return {@code true} if CrystalGraphics created and owns this FBO
     */
    @Override
    public boolean isOwned() {
        return owned;
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code true} if {@link #delete()} has been called
     */
    @Override
    public boolean isDeleted() {
        return deleted;
    }

    /**
     * {@inheritDoc}
     *
     * @return the OpenGL framebuffer object ID
     */
    @Override
    public int getId() {
        return fboId;
    }

    /**
     * {@inheritDoc}
     *
     * @return the framebuffer width in pixels
     */
    @Override
    public int getWidth() {
        return width;
    }

    /**
     * {@inheritDoc}
     *
     * @return the framebuffer height in pixels
     */
    @Override
    public int getHeight() {
        return height;
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code true} if MRT is available for this FBO
     */
    @Override
    public boolean supportsMrt() {
        return supportsMrt;
    }

    // ── Binding ────────────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p>Binds this FBO as both the draw and read framebuffer
     * ({@code GL_FRAMEBUFFER} target) via {@link CrossApiTransition}.</p>
     */
    @Override
    public void bind() {
        CrossApiTransition.bindFramebuffer(GL_FRAMEBUFFER, fboId, callFamily());
    }

    /**
     * {@inheritDoc}
     *
     * <p>Binds this FBO as the draw framebuffer only
     * ({@code GL_DRAW_FRAMEBUFFER} target) via {@link CrossApiTransition}.</p>
     */
    @Override
    public void bindDraw() {
        CrossApiTransition.bindFramebuffer(GL_DRAW_FRAMEBUFFER, fboId, callFamily());
    }

    /**
     * {@inheritDoc}
     *
     * <p>Binds this FBO as the read framebuffer only
     * ({@code GL_READ_FRAMEBUFFER} target) via {@link CrossApiTransition}.</p>
     */
    @Override
    public void bindRead() {
        CrossApiTransition.bindFramebuffer(GL_READ_FRAMEBUFFER, fboId, callFamily());
    }

    /**
     * {@inheritDoc}
     *
     * <p>Unbinds this FBO by binding the default framebuffer (ID 0) for
     * the {@code GL_FRAMEBUFFER} target via {@link CrossApiTransition}.</p>
     */
    @Override
    public void unbind() {
        CrossApiTransition.bindFramebuffer(GL_FRAMEBUFFER, 0, callFamily());
    }

    // ── drawBuffers (default: fail if MRT unsupported) ─────────────────

    /**
     * {@inheritDoc}
     *
     * <p>Default implementation throws {@link UnsupportedOperationException}
     * if this framebuffer does not support MRT.  Subclasses that support MRT
     * must override this method with the appropriate GL call.</p>
     *
     * @param attachments the draw buffer attachment points
     * @throws UnsupportedOperationException if {@link #supportsMrt()} is {@code false}
     */
    @Override
    public void drawBuffers(int... attachments) {
        if (!supportsMrt) {
            throw new UnsupportedOperationException(
                    "This framebuffer does not support multiple render targets (MRT). "
                    + "Use a Core GL30 or ARB_framebuffer_object backend for MRT support.");
        }
        // Subclasses override with actual GL calls
    }

    // ── Deletion ───────────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p>Deletes this framebuffer and all associated GL resources.  Throws
     * {@link IllegalStateException} if this is a wrapped (non-owned) FBO.
     * Subsequent calls after the first successful deletion are no-ops.</p>
     *
     * @throws IllegalStateException if this framebuffer is not owned
     */
    @Override
    public void delete() {
        if (!owned) {
            throw new IllegalStateException(
                    "Cannot delete a wrapped (non-owned) framebuffer");
        }
        if (deleted) {
            return;
        }
        freeGlResources();
        deleted = true;
        ALL_OWNED.remove(this);
    }

    // ── Runtime attachments ────────────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p>Default implementation throws {@link UnsupportedOperationException}.
     * Subclasses that support runtime attachments must override this method.</p>
     *
     * @return the runtime attachment manager
     * @throws UnsupportedOperationException if not implemented by the subclass
     */
    @Override
    public CgRuntimeAttachments getRuntimeAttachments() {
        throw new UnsupportedOperationException(
                "This framebuffer implementation does not support runtime attachments");
    }

    /**
     * {@inheritDoc}
     *
     * <p>Default implementation throws {@link UnsupportedOperationException}.
     * Subclasses that support color attachment queries must override this method.</p>
     *
     * @param attachmentIndex the color attachment index
     * @return the texture ID of the color attachment
     * @throws UnsupportedOperationException if not implemented by the subclass
     */
    @Override
    public int getColorTextureId(int attachmentIndex) {
        throw new UnsupportedOperationException(
                "This framebuffer implementation does not support color texture ID queries");
    }

    /**
     * {@inheritDoc}
     *
     * <p>Default implementation throws {@link UnsupportedOperationException}.
     * Subclasses that allocate depth as a texture must override this method.</p>
     */
    @Override
    public int getDepthTextureId() {
        throw new UnsupportedOperationException(
                "This framebuffer does not have a depth texture attachment. "
                + "Depth texture is only available when created with CgDepthStencilSpec.depthOnlyTexture().");
    }

    // ── Abstract hooks for subclasses ──────────────────────────────────

    /**
     * Releases all OpenGL resources held by this framebuffer.
     *
     * <p>Called exactly once by {@link #delete()}.  Implementations must
     * delete the FBO, any attached textures, and any renderbuffers.</p>
     */
    protected abstract void freeGlResources();

    /**
     * Returns the GL call family used by this framebuffer implementation.
     *
     * <p>Used by binding methods to route through
     * {@link CrossApiTransition#bindFramebuffer(int, int, CallFamily)}.</p>
     *
     * @return the {@link CallFamily} for this FBO backend
     */
    protected abstract CallFamily callFamily();

    // ── Static lifecycle ───────────────────────────────────────────────

    /**
     * Deletes all owned framebuffers tracked by CrystalGraphics.
     *
     * <p>Intended to be called during shutdown or context destruction.
     * After this call, {@link #ALL_OWNED} is empty.  Each framebuffer's
     * {@link #freeGlResources()} is called exactly once.</p>
     */
    public static void freeAll() {
        for (AbstractCgFramebuffer fbo : ALL_OWNED) {
            if (!fbo.deleted) {
                fbo.freeGlResources();
                fbo.deleted = true;
            }
        }
        ALL_OWNED.clear();
    }
}
