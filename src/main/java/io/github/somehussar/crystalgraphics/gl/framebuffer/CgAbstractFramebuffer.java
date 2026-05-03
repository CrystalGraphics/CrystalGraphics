package io.github.somehussar.crystalgraphics.gl.framebuffer;

import io.github.somehussar.crystalgraphics.api.CgMipmapConfig;
import io.github.somehussar.crystalgraphics.api.framebuffer.CgColorAttachmentProvider;
import io.github.somehussar.crystalgraphics.api.framebuffer.CgColorAttachmentSpec;
import io.github.somehussar.crystalgraphics.api.framebuffer.CgFramebuffer;
import io.github.somehussar.crystalgraphics.api.framebuffer.CgFramebufferSpec;
import io.github.somehussar.crystalgraphics.api.framebuffer.CgRuntimeAttachments;
import io.github.somehussar.crystalgraphics.api.framebuffer.CgTextureFormatSpec;
import io.github.somehussar.crystalgraphics.api.texture.CgTextureSpec;
import io.github.somehussar.crystalgraphics.gl.CrossApiTransition;
import io.github.somehussar.crystalgraphics.gl.state.CallFamily;
import io.github.somehussar.crystalgraphics.gl.texture.CgTexture2D;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Abstract base class for all CrystalGraphics framebuffer implementations.
 *
 * <p>Owns all shared instance state, the {@link RuntimeSlot} inner class, all shared
 * logic (resize, freeGlResources, runtime attachments, drawBuffers, etc.) and dispatches
 * backend-specific GL calls through a set of protected abstract methods.  Concrete
 * subclasses supply only static factory methods, {@link #callFamily()}, and the 11
 * one-line abstract GL dispatch overrides.</p>
 *
 * <h3>Abstract GL Dispatch</h3>
 * <p>Every GL call that differs between Core GL30, ARB, and EXT backends is wrapped in
 * a {@code glDo*()} method.  Subclasses implement these methods with a single call to
 * their backend class (e.g. {@code GL30.*}, {@code ARBFramebufferObject.*},
 * {@code EXTFramebufferObject.*EXT()}).</p>
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
 * only be used from the GL context thread.</p>
 *
 * @see CgFramebuffer
 * @see CgCoreFramebuffer
 * @see CgArbFramebuffer
 * @see CgExtFramebuffer
 */
public abstract class CgAbstractFramebuffer implements CgFramebuffer {

    // ── GL constants ────────────────────────────────────────────────────

    /** {@code GL_FRAMEBUFFER} — binds both draw and read targets. */
    private static final int GL_FRAMEBUFFER = 0x8D40;

    /** {@code GL_DRAW_FRAMEBUFFER} — binds draw target only. */
    private static final int GL_DRAW_FRAMEBUFFER = 0x8CA9;

    /** {@code GL_READ_FRAMEBUFFER} — binds read target only. */
    private static final int GL_READ_FRAMEBUFFER = 0x8CA8;

    /** {@code GL_RENDERBUFFER} target. */
    static final int GL_RENDERBUFFER = 0x8D41;

    /** {@code GL_DEPTH_COMPONENT24} internal format. */
    static final int GL_DEPTH_COMPONENT24 = 0x81A6;

    /** {@code GL_DEPTH_ATTACHMENT} attachment point. */
    static final int GL_DEPTH_ATTACHMENT = 0x8D00;

    /** {@code GL_COLOR_ATTACHMENT0} attachment point. */
    static final int GL_COLOR_ATTACHMENT0 = 0x8CE0;

    /** {@code GL_TEXTURE_2D} target. */
    static final int GL_TEXTURE_2D = 0x0DE1;

    /** {@code GL_FRAMEBUFFER_COMPLETE} status. */
    static final int GL_FRAMEBUFFER_COMPLETE = 0x8CD5;

    /** {@code GL_STENCIL_ATTACHMENT} attachment point. */
    static final int GL_STENCIL_ATTACHMENT = 0x8D20;

    /** {@code GL_DEPTH_STENCIL_ATTACHMENT} attachment point (Core/ARB only). */
    static final int GL_DEPTH_STENCIL_ATTACHMENT = 0x821A;

    /** {@code GL_STENCIL_INDEX8} internal format. */
    static final int GL_STENCIL_INDEX8 = 0x8D48;

    /** {@code GL_DEPTH_COMPONENT} pixel format for depth texture allocation. */
    static final int GL_DEPTH_COMPONENT = 0x1902;

    /** {@code GL_UNSIGNED_BYTE} pixel type (used in depth texture spec). */
    static final int GL_UNSIGNED_BYTE = 0x1401;

    /** {@code GL_NEAREST} filter value (appropriate for depth textures). */
    static final int GL_NEAREST = 0x2600;

    // ── Static tracking ─────────────────────────────────────────────────

    /**
     * Set of all owned framebuffers created by CrystalGraphics.
     *
     * <p>Used by {@link #freeAll()} to delete every owned FBO during shutdown.
     * Uses {@link CopyOnWriteArraySet} so that iteration in {@code freeAll()}
     * is safe even though {@code delete()} removes elements.</p>
     */
    protected static final Set<CgAbstractFramebuffer> ALL_OWNED =
            new CopyOnWriteArraySet<CgAbstractFramebuffer>();

    // ── Instance fields ─────────────────────────────────────────────────

    /** OpenGL framebuffer object ID. */
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
     * <p>When {@code false}, the FBO was created externally and wrapped for convenience.
     * Calling {@link #delete()} on a non-owned FBO throws {@link IllegalStateException}.</p>
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

    /** Whether this framebuffer has a depth renderbuffer attached (legacy path). */
    protected final boolean hasDepth;

    /**
     * The spec used to create this framebuffer, or {@code null} for legacy path.
     * Retained for resize operations.
     */
    protected CgFramebufferSpec spec;

    /**
     * Owned color textures (spec-based path). {@code null} for legacy path.
     * The inherited {@code colorTextureId} int is kept in sync via {@code .getId()}.
     */
    protected CgTexture2D[] colorTextures;

    /**
     * Single owned color texture (legacy path). {@code null} for spec-based path.
     * The inherited {@code colorTextureId} int is kept in sync via {@code .getId()}.
     */
    protected CgTexture2D singleColorTexture;

    /**
     * Owned depth texture, or {@code null} if depth uses a renderbuffer or is absent.
     */
    protected CgTexture2D depthTexture;

    /**
     * Renderbuffer ID for the stencil attachment (separate mode), or 0 if none.
     * For EXT packed depth-stencil, set to the same value as {@code depthRenderbufferId};
     * {@link #freeGlResources()} only deletes it once by checking inequality.
     */
    protected int stencilRenderbufferId;

    /** Runtime color attachments keyed by slot index. */
    protected final Map<Integer, RuntimeSlot> runtimeSlots = new HashMap<Integer, RuntimeSlot>();

    /** Lazily-created runtime attachments manager. */
    protected CgRuntimeAttachments runtimeAttachments;

    // ── RuntimeSlot inner class ──────────────────────────────────────────

    /**
     * Tracks a single runtime color attachment slot.
     */
    protected static final class RuntimeSlot {
        int textureId;
        final boolean managed;
        final CgColorAttachmentProvider provider;
        final CgTextureFormatSpec format;

        /** External attachment (not owned). */
        RuntimeSlot(int textureId) {
            this.textureId = textureId;
            this.managed = false;
            this.provider = null;
            this.format = null;
        }

        /** Managed attachment (owned, auto-resized). */
        RuntimeSlot(int textureId, CgColorAttachmentProvider provider, CgTextureFormatSpec format) {
            this.textureId = textureId;
            this.managed = true;
            this.provider = provider;
            this.format = format;
        }
    }

    // ── Constructors ─────────────────────────────────────────────────────

    /**
     * Legacy path constructor (single color attachment, optional depth renderbuffer).
     *
     * <p>If {@code owned} is {@code true}, the new instance is added to
     * {@link #ALL_OWNED} for lifecycle tracking.</p>
     */
    protected CgAbstractFramebuffer(int fboId, CgTexture2D colorTexture,
                                     int depthRenderbufferId,
                                     int width, int height,
                                     boolean hasDepth, boolean supportsMrt,
                                     boolean owned) {
        this.fboId = fboId;
        this.width = width;
        this.height = height;
        this.owned = owned;
        this.supportsMrt = supportsMrt;
        this.hasDepth = hasDepth;
        this.deleted = false;
        this.singleColorTexture = colorTexture;
        this.colorTextureId = (colorTexture != null) ? colorTexture.getId() : 0;
        this.depthRenderbufferId = depthRenderbufferId;
        this.colorTextures = null;
        this.spec = null;
        this.stencilRenderbufferId = 0;
        if (owned) {
            ALL_OWNED.add(this);
        }
    }

    /**
     * Spec-based path constructor (multi-attachment).
     *
     * <p>If {@code owned} is {@code true}, the new instance is added to
     * {@link #ALL_OWNED} for lifecycle tracking.</p>
     */
    protected CgAbstractFramebuffer(int fboId, CgTexture2D[] colorTextures,
                                     int depthRenderbufferId, int stencilRenderbufferId,
                                     CgTexture2D depthTexture,
                                     int width, int height,
                                     boolean supportsMrt,
                                     CgFramebufferSpec spec,
                                     boolean owned) {
        this.fboId = fboId;
        this.width = width;
        this.height = height;
        this.owned = owned;
        this.supportsMrt = supportsMrt;
        this.hasDepth = false;
        this.deleted = false;
        this.colorTextures = colorTextures;
        this.colorTextureId = (colorTextures != null && colorTextures.length > 0)
                ? colorTextures[0].getId() : 0;
        this.depthRenderbufferId = depthRenderbufferId;
        this.stencilRenderbufferId = stencilRenderbufferId;
        this.depthTexture = depthTexture;
        this.singleColorTexture = null;
        this.spec = spec;
        if (owned) {
            ALL_OWNED.add(this);
        }
    }

    /**
     * Wrap constructor for externally-created FBOs (non-owned).
     */
    protected CgAbstractFramebuffer(int fboId, int width, int height, boolean supportsMrt) {
        this.fboId = fboId;
        this.width = width;
        this.height = height;
        this.owned = false;
        this.supportsMrt = supportsMrt;
        this.hasDepth = false;
        this.deleted = false;
        this.colorTextureId = 0;
        this.depthRenderbufferId = 0;
        this.stencilRenderbufferId = 0;
    }

    // ── CgFramebuffer simple getters ─────────────────────────────────────

    @Override
    public boolean isOwned() { return owned; }

    @Override
    public boolean isDeleted() { return deleted; }

    @Override
    public int getId() { return fboId; }

    @Override
    public int getWidth() { return width; }

    @Override
    public int getHeight() { return height; }

    @Override
    public boolean supportsMrt() { return supportsMrt; }

    // ── Binding ──────────────────────────────────────────────────────────

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

    // ── drawBuffers ──────────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p>Sets the draw buffers using {@link GL20#glDrawBuffers(IntBuffer)}.
     * Core and ARB backends use this shared implementation.
     * {@code EXT_framebuffer_object} does not support MRT — {@link CgExtFramebuffer}
     * overrides this method to always throw {@link UnsupportedOperationException}.</p>
     *
     * @param attachments the color attachment constants
     * @throws UnsupportedOperationException if MRT is not supported
     * @throws IllegalArgumentException      if attachments is empty
     */
    @Override
    public void drawBuffers(int... attachments) {
        if (!supportsMrt) {
            throw new UnsupportedOperationException(
                    "This framebuffer does not support multiple render targets (MRT). "
                    + "Use a Core GL30 or ARB_framebuffer_object backend.");
        }
        if (attachments.length == 0) {
            throw new IllegalArgumentException("At least one attachment must be specified");
        }
        ByteBuffer raw = ByteBuffer.allocateDirect(attachments.length * 4)
                .order(ByteOrder.nativeOrder());
        IntBuffer buf = raw.asIntBuffer();
        for (int attachment : attachments) {
            buf.put(attachment);
        }
        buf.flip();
        GL20.glDrawBuffers(buf);
    }

    // ── Deletion ─────────────────────────────────────────────────────────

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

    // ── getColorTextureId / getDepthTextureId ────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p>For spec-based framebuffers, returns the texture ID at the given
     * attachment index. For legacy framebuffers, only index 0 is valid and
     * returns the single color texture ID.</p>
     *
     * @param attachmentIndex the color attachment index (zero-based)
     * @return the OpenGL texture ID
     * @throws IndexOutOfBoundsException if index is out of range
     */
    @Override
    public int getColorTextureId(int attachmentIndex) {
        if (colorTextures != null) {
            if (attachmentIndex < 0 || attachmentIndex >= colorTextures.length) {
                throw new IndexOutOfBoundsException(
                        "Attachment index " + attachmentIndex + " out of range [0, "
                        + colorTextures.length + ")");
            }
            return colorTextures[attachmentIndex].getId();
        } else {
            if (attachmentIndex != 0) {
                throw new IndexOutOfBoundsException(
                        "Legacy framebuffer only has 1 color attachment, index: "
                        + attachmentIndex);
            }
            return colorTextureId;
        }
    }

    /**
     * {@inheritDoc}
     *
     * @throws UnsupportedOperationException if no depth texture was allocated
     */
    @Override
    public int getDepthTextureId() {
        if (depthTexture == null) {
            throw new UnsupportedOperationException(
                    "This framebuffer does not have a depth texture attachment. "
                    + "Depth texture is only available when created with CgDepthStencilSpec.depthOnlyTexture().");
        }
        return depthTexture.getId();
    }

    // ── Runtime attachments ──────────────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p>Returns (lazily creating) the {@link CgRuntimeAttachments} manager for
     * this framebuffer.  Uses backend-specific GL dispatch through the abstract
     * {@link #glDoBindFbo}/{@link #glDoFramebufferTexture2D} hooks.</p>
     */
    @Override
    public CgRuntimeAttachments getRuntimeAttachments() {
        if (runtimeAttachments == null) {
            runtimeAttachments = new RuntimeAttachmentsImpl();
        }
        return runtimeAttachments;
    }

    /**
     * Returns the number of spec-allocated color attachments (1 for legacy path).
     */
    protected int getSpecColorCount() {
        if (colorTextures != null) {
            return colorTextures.length;
        }
        return 1;
    }

    /**
     * Validates that a runtime slot index is not reserved for spec attachments.
     *
     * @param slot the slot index to validate
     * @throws IllegalArgumentException if slot is negative or reserved
     */
    protected void validateSlot(int slot) {
        if (slot < 0) {
            throw new IllegalArgumentException("Slot index must not be negative: " + slot);
        }
        int specCount = getSpecColorCount();
        if (slot < specCount) {
            throw new IllegalArgumentException(
                    "Slot " + slot + " is reserved for managed spec attachments"
                    + " (colorAttachmentCount=" + specCount + ")");
        }
    }

    /**
     * Inner class implementing {@link CgRuntimeAttachments} using the enclosing
     * framebuffer's abstract GL dispatch methods.  This single implementation is
     * shared by Core, ARB, and EXT backends.
     */
    private final class RuntimeAttachmentsImpl implements CgRuntimeAttachments {

        @Override
        public void attachExternal(int slot, int textureId) {
            if (deleted) {
                throw new IllegalStateException("Framebuffer is deleted");
            }
            if (textureId <= 0) {
                throw new IllegalArgumentException("textureId must be positive: " + textureId);
            }
            validateSlot(slot);

            RuntimeSlot existing = runtimeSlots.get(slot);
            if (existing != null && existing.managed) {
                GL11.glDeleteTextures(existing.textureId);
            }

            runtimeSlots.put(slot, new RuntimeSlot(textureId));

            glDoBindFbo(fboId);
            glDoFramebufferTexture2D(GL_COLOR_ATTACHMENT0 + slot, textureId);
            glDoBindFbo(0);
        }

        @Override
        public void attachManaged(int slot, CgColorAttachmentProvider provider, CgTextureFormatSpec format) {
            if (deleted) {
                throw new IllegalStateException("Framebuffer is deleted");
            }
            if (provider == null) {
                throw new IllegalArgumentException("provider must not be null");
            }
            if (format == null) {
                throw new IllegalArgumentException("format must not be null");
            }
            validateSlot(slot);

            RuntimeSlot existing = runtimeSlots.get(slot);
            if (existing != null && existing.managed) {
                GL11.glDeleteTextures(existing.textureId);
            }

            int texId = provider.allocate(width, height, format);
            runtimeSlots.put(slot, new RuntimeSlot(texId, provider, format));

            glDoBindFbo(fboId);
            glDoFramebufferTexture2D(GL_COLOR_ATTACHMENT0 + slot, texId);
            glDoBindFbo(0);
        }

        @Override
        public void detach(int slot) {
            if (deleted) {
                throw new IllegalStateException("Framebuffer is deleted");
            }
            RuntimeSlot existing = runtimeSlots.remove(slot);
            if (existing == null) {
                return;
            }
            if (existing.managed) {
                GL11.glDeleteTextures(existing.textureId);
            }
            glDoBindFbo(fboId);
            glDoFramebufferTexture2D(GL_COLOR_ATTACHMENT0 + slot, 0);
            glDoBindFbo(0);
        }

        @Override
        public int getTextureId(int slot) {
            if (deleted) {
                throw new IllegalStateException("Framebuffer is deleted");
            }
            RuntimeSlot rs = runtimeSlots.get(slot);
            return rs != null ? rs.textureId : 0;
        }

        @Override
        public boolean hasAttachment(int slot) {
            if (deleted) {
                throw new IllegalStateException("Framebuffer is deleted");
            }
            return runtimeSlots.containsKey(slot);
        }
    }

    // ── freeGlResources ──────────────────────────────────────────────────

    /**
     * Releases all OpenGL resources held by this framebuffer.
     *
     * <p>Called exactly once by {@link #delete()} and {@link #freeAll()}.
     * Deletes runtime slots, the FBO, all color textures, depth/stencil
     * renderbuffers, and the depth texture if present.  Uses abstract
     * dispatch for backend-specific deletes.</p>
     *
     * <p>The {@code stencilRenderbufferId != depthRenderbufferId} guard
     * prevents double-delete in the EXT packed depth-stencil case where
     * a single RBO is attached to both depth and stencil points.</p>
     */
    protected void freeGlResources() {
        for (RuntimeSlot rs : runtimeSlots.values()) {
            if (rs.managed) {
                GL11.glDeleteTextures(rs.textureId);
            }
        }
        runtimeSlots.clear();

        glDoDeleteFramebuffer(fboId);

        if (colorTextures != null) {
            for (CgTexture2D tex : colorTextures) {
                if (tex != null) {
                    tex.delete();
                }
            }
        } else if (singleColorTexture != null) {
            singleColorTexture.delete();
        }

        if (depthRenderbufferId != 0) {
            glDoDeleteRenderbuffer(depthRenderbufferId);
        }
        if (stencilRenderbufferId != 0 && stencilRenderbufferId != depthRenderbufferId) {
            glDoDeleteRenderbuffer(stencilRenderbufferId);
        }
        if (depthTexture != null) {
            depthTexture.delete();
        }
    }

    // ── Resize ───────────────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p>Recreates all GL resources at the new dimensions.  The FBO ID will
     * change after this call.  Dispatches to {@link #resizeSpec} or
     * {@link #resizeLegacy} depending on how this framebuffer was created.</p>
     *
     * @param newWidth  new width in pixels (must be &gt; 0)
     * @param newHeight new height in pixels (must be &gt; 0)
     * @throws IllegalArgumentException if dimensions are not positive
     * @throws IllegalStateException    if the framebuffer has been deleted
     */
    @Override
    public void resize(int newWidth, int newHeight) {
        if (deleted) {
            throw new IllegalStateException("Cannot resize a deleted framebuffer");
        }
        if (newWidth <= 0 || newHeight <= 0) {
            throw new IllegalArgumentException(
                    "Framebuffer dimensions must be positive: " + newWidth + "x" + newHeight);
        }
        if (spec != null) {
            resizeSpec(newWidth, newHeight);
        } else {
            resizeLegacy(newWidth, newHeight);
        }
    }

    /**
     * Resizes the framebuffer using the legacy single-color-attachment path.
     *
     * <p>Frees existing GL resources, allocates new ones at the requested
     * dimensions, and re-attaches any runtime slots.</p>
     */
    private void resizeLegacy(int newWidth, int newHeight) {
        Map<Integer, RuntimeSlot> savedSlots = new HashMap<Integer, RuntimeSlot>(runtimeSlots);
        freeGlResources();

        int newFbo = glDoGenFramebuffer();
        glDoBindFbo(newFbo);

        CgTexture2D newColorTex = CgTexture2D.createEmpty(newWidth, newHeight, CgTextureSpec.RGBA8_LINEAR);
        glDoFramebufferTexture2D(GL_COLOR_ATTACHMENT0, newColorTex.getId());

        int newDepthRbo = 0;
        if (hasDepth) {
            newDepthRbo = glDoGenRenderbuffer();
            glDoBindRenderbuffer(newDepthRbo);
            glDoRenderbufferStorage(GL_DEPTH_COMPONENT24, newWidth, newHeight);
            glDoFramebufferRenderbuffer(GL_DEPTH_ATTACHMENT, newDepthRbo);
        }

        int status = glDoCheckFramebufferStatus();
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            glDoDeleteFramebuffer(newFbo);
            newColorTex.delete();
            if (newDepthRbo != 0) {
                glDoDeleteRenderbuffer(newDepthRbo);
            }
            throw new IllegalStateException(
                    "Framebuffer resize failed. Status: 0x" + Integer.toHexString(status));
        }

        glDoBindFbo(0);

        this.fboId = newFbo;
        this.singleColorTexture = newColorTex;
        this.colorTextureId = newColorTex.getId();
        this.depthRenderbufferId = newDepthRbo;
        this.width = newWidth;
        this.height = newHeight;

        runtimeSlots.putAll(savedSlots);
        resizeRuntimeSlots();
    }

    /**
     * Resizes the framebuffer using the spec-based multi-attachment path.
     *
     * <p>Delegates to the subclass's {@link #newFromSpec} factory to create
     * a fresh instance, then steals its GL resources and destroys the shell.</p>
     */
    private void resizeSpec(int newWidth, int newHeight) {
        CgFramebufferSpec.Builder builder = CgFramebufferSpec.builder()
                .baseDimensions(newWidth, newHeight)
                .depthStencil(spec.getDepthStencil());
        for (CgColorAttachmentSpec att : spec.getColorAttachments()) {
            builder.addColorAttachment(att);
        }
        CgFramebufferSpec newSpec = builder.build();

        CgAbstractFramebuffer replacement = newFromSpec(newSpec);

        Map<Integer, RuntimeSlot> savedSlots = new HashMap<Integer, RuntimeSlot>(runtimeSlots);
        freeGlResources();

        this.fboId = replacement.fboId;
        this.colorTextures = replacement.colorTextures;
        this.colorTextureId = replacement.colorTextureId;
        this.depthRenderbufferId = replacement.depthRenderbufferId;
        this.stencilRenderbufferId = replacement.stencilRenderbufferId;
        this.depthTexture = replacement.depthTexture;
        this.width = newWidth;
        this.height = newHeight;
        this.spec = newSpec;

        // Prevent double-free: the replacement's GL resources are now owned by us
        ALL_OWNED.remove(replacement);
        replacement.deleted = true;

        runtimeSlots.putAll(savedSlots);
        resizeRuntimeSlots();
    }

    /**
     * Reallocates managed runtime slots and re-attaches all runtime slots to the current FBO.
     */
    private void resizeRuntimeSlots() {
        if (runtimeSlots.isEmpty()) {
            return;
        }
        glDoBindFbo(fboId);
        for (Map.Entry<Integer, RuntimeSlot> entry : runtimeSlots.entrySet()) {
            int slot = entry.getKey();
            RuntimeSlot rs = entry.getValue();
            if (rs.managed) {
                int newTexId = rs.provider.allocate(width, height, rs.format);
                GL11.glDeleteTextures(rs.textureId);
                rs.textureId = newTexId;
            }
            glDoFramebufferTexture2D(GL_COLOR_ATTACHMENT0 + slot, rs.textureId);
        }
        glDoBindFbo(0);
    }

    // ── Spec helpers (shared by all subclass factories) ──────────────────

    /**
     * Builds a {@link CgTextureSpec} from an attachment's format + mipmap config.
     */
    protected static CgTextureSpec specFrom(CgTextureFormatSpec fmt, CgMipmapConfig mips) {
        CgTextureSpec.CgTextureSpecBuilder b = CgTextureSpec.builder().format(fmt);
        if (mips != null && mips.isEnabled()) {
            b.minFilter(mips.getMinFilter()).magFilter(mips.getMagFilter());
        }
        return b.build();
    }

    /**
     * Builds a {@link CgTextureSpec} for a depth-only texture.
     *
     * @param depthInternalFormat e.g. {@code GL_DEPTH_COMPONENT24}
     */
    protected static CgTextureSpec depthSpecFrom(int depthInternalFormat) {
        return CgTextureSpec.builder()
                .format(new CgTextureFormatSpec(depthInternalFormat, GL_DEPTH_COMPONENT, GL_UNSIGNED_BYTE))
                .minFilter(GL_NEAREST)
                .magFilter(GL_NEAREST)
                .build();
    }

    // ── Abstract hooks for subclasses ────────────────────────────────────

    /**
     * Returns the GL call family used by this framebuffer implementation.
     * Used by binding methods to route through {@link CrossApiTransition}.
     */
    protected abstract CallFamily callFamily();

    /**
     * Creates a new spec-based framebuffer of this backend type.
     * Used by {@link #resizeSpec} to rebuild GL resources at a new size.
     *
     * @param spec the spec describing the new framebuffer
     * @return a new owned framebuffer of the same backend type
     */
    protected abstract CgAbstractFramebuffer newFromSpec(CgFramebufferSpec spec);

    // ── Abstract GL dispatch methods ─────────────────────────────────────

    /** Generates a new framebuffer object and returns its ID. */
    protected abstract int glDoGenFramebuffer();

    /** Deletes the framebuffer with the given ID. */
    protected abstract void glDoDeleteFramebuffer(int id);

    /**
     * Binds the framebuffer with the given ID to the {@code GL_FRAMEBUFFER} target.
     * Pass {@code 0} to unbind.
     */
    protected abstract void glDoBindFbo(int fboId);

    /**
     * Attaches a 2D texture to the currently bound framebuffer at {@code attachment}.
     * Pass {@code texId = 0} to detach.
     */
    protected abstract void glDoFramebufferTexture2D(int attachment, int texId);

    /** Checks and returns the completeness status of the currently bound framebuffer. */
    protected abstract int glDoCheckFramebufferStatus();

    /** Generates a new renderbuffer object and returns its ID. */
    protected abstract int glDoGenRenderbuffer();

    /** Binds the renderbuffer with the given ID to the {@code GL_RENDERBUFFER} target. */
    protected abstract void glDoBindRenderbuffer(int id);

    /** Allocates storage for the currently bound renderbuffer. */
    protected abstract void glDoRenderbufferStorage(int format, int w, int h);

    /** Deletes the renderbuffer with the given ID. */
    protected abstract void glDoDeleteRenderbuffer(int id);

    /**
     * Attaches a renderbuffer to the currently bound framebuffer at {@code attachment}.
     * Pass {@code rboId = 0} to detach.
     */
    protected abstract void glDoFramebufferRenderbuffer(int attachment, int rboId);

    // ── Static lifecycle ─────────────────────────────────────────────────

    /**
     * Deletes all owned framebuffers tracked by CrystalGraphics.
     *
     * <p>Intended to be called during shutdown or context destruction.
     * After this call, {@link #ALL_OWNED} is empty.  Each framebuffer's
     * {@link #freeGlResources()} is called exactly once.</p>
     */
    public static void freeAll() {
        for (CgAbstractFramebuffer fbo : ALL_OWNED) {
            if (!fbo.deleted) {
                fbo.freeGlResources();
                fbo.deleted = true;
            }
        }
        ALL_OWNED.clear();
    }
}
