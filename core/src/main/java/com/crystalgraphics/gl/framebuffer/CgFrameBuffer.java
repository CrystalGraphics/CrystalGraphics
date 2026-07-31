package com.crystalgraphics.gl.framebuffer;


import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.api.framebuffer.CgFrameBufferFormat;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.gl.lifecycle.CgGraphicsLifecycle;
import com.crystalgraphics.platform.gl.state.CgGlScope;
import com.crystalgraphics.platform.gl.state.CgGlState;
import com.crystalgraphics.gl.texture.CgTexture2D;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.util.CgBufferUtils;
import java.nio.IntBuffer;
import java.util.TreeMap;
import javax.annotation.Nullable;
import lombok.Getter;

import static com.crystalgraphics.platform.gl.state.CgGlSlot.FBO;

/**
 * Abstract base for all CrystalGraphics framebuffer implementations.
 *
 * <p>Every {@code CgFrameBuffer} has a {@link #name} (first argument to all
 * factory methods), a {@link CgFrameBufferFormat} descriptor, and a
 * {@link TreeMap} of {@link Attachment} objects sparse-keyed by color slot
 * index.  Backends ({@link CgCoreFrameBuffer}, {@link CgArbFrameBuffer},
 * {@link CgExtFrameBuffer}) extend this class and supply backend-specific GL
 * dispatch via a set of protected abstract methods.</p>
 *
 * <h3>Factories</h3>
 * <ul>
 *   <li>{@link #create(String, int, int, CgFrameBufferFormat)} — public entry point;
 *       delegates to {@link CgFrameBufferRegistry#getOrCreate} which caches the result.</li>
 *   <li>{@link #createInternal(String, int, int, CgFrameBufferFormat)} — package-private;
 *       does the actual GL allocation; called only by the registry.</li>
 *   <li>{@link #createScreenSized(String, CgFrameBufferFormat)} — delegates to
 *       {@link CgFrameBufferRegistry}, which auto-resizes on
 *       {@link CgGraphicsLifecycle#onResize}.
 *       <strong>Do not cache the returned instance across frames</strong> — re-query
 *       each frame; the registry replaces the internal FBO on resize.</li>
 *   <li>{@link #wrap(String, int, int, int)} — wraps an
 *       externally-created FBO ID.  {@link #delete()} is a no-op on wrapped
 *       instances.</li>
 * </ul>
 *
 * <h3>Attachments</h3>
 * <p>Each occupied slot holds an {@link Attachment}.  Color slots are stored in
 * a {@code TreeMap<Integer, Attachment>} (sparse — only populated slots
 * appear).  The depth slot is stored in {@link #depthAttachment} (or
 * {@code null}).  Convenience accessors {@link #getColorTexture(int)} and
 * {@link #getDepthTexture()} return {@code null} for renderbuffer slots.</p>
 *
 * <h3>Depth-only FBOs</h3>
 * <p>When the format has no color attachments ({@link CgFrameBufferFormat#isDepthOnly()}
 * is true), {@link GL11#glDrawBuffer} and {@link GL11#glReadBuffer} are set to
 * {@link GL11#GL_NONE} automatically during creation.</p>
 *
 * <h3>Thread Safety</h3>
 * <p>Instances are not thread-safe.  All methods must be called on the GL
 * context thread.</p>
 *
 * @see CgFrameBufferFormat
 * @see CgFrameBufferRegistry
 */
public abstract class CgFrameBuffer {
   
    // ── Instance fields ────────────────────────────────────────────────────────

    /** Human-readable instance name (e.g. "gbuffer_main", "shadow:directional"). */
    protected final String name;

    /** The format descriptor used to create this FBO. */
    protected final CgFrameBufferFormat format;

    /** GL FBO id. */
    protected int fboId;

    /** FBO width in pixels. 
     * -- GETTER --
     * Returns the FBO width in pixels. 
     */
    @Getter
    protected int width;

    /** FBO height in pixels. 
     * -- GETTER --
     * Returns the FBO height in pixels. 
     */
    @Getter
    protected int height;

    /** {@code true} if CrystalGraphics created this FBO and may delete it. 
     * -- GETTER --
     * Returns 
     *  if CrystalGraphics owns and may delete this FBO. 
     */
    @Getter
    protected final boolean owned;

    /** {@code true} after {@link #delete()} has been called successfully. 
     * -- GETTER --
     * Returns 
     *  if 
     *  has been called. 
     */
    @Getter
    protected boolean deleted;

    /**
     * {@code true} if this FBO was created via
     * {@link CgFrameBufferRegistry#acquireScreenSized} and is managed by the registry.
     * Set by the registry immediately after creation; defaults to {@code false}.
     * -- GETTER --
     * Returns 
     *  if this FBO is managed as a screen-sized FBO by the registry. 

     */
    @Getter
    boolean screenSized = false;

    /**
     * Sparse map of color attachments, sorted by slot index.
     * Only populated slots are present.
     */
    protected final TreeMap<Integer, Attachment> colorAttachments = new TreeMap<>();

    /** Depth/stencil attachment, or {@code null} if the format has no depth. */
    protected Attachment depthAttachment;
    
     // ── Lazily cached GPU limit ────────────────────────────────────────────────

    private static Integer maxColorAttachments = null;

    /**
     * Returns the maximum number of color attachments supported by the current
     * GL context.  Lazily probed from {@link CgCapabilities} on first call.
     */
    static int getMaxColorAttachments() {
        if (maxColorAttachments == null) {
            maxColorAttachments = CgCapabilities.detect().getMaxDrawBuffers();
        }
        return maxColorAttachments;
    }

    // ── Constructors ───────────────────────────────────────────────────────────

    /**
     * Constructor for owned FBOs created by {@link #createInternal}.
     * GL resources are allocated separately via {@link #initGl}.
     */
    CgFrameBuffer(String name, CgFrameBufferFormat format, int width, int height) {
        this.name    = name;
        this.format  = format;
        this.width   = width;
        this.height  = height;
        this.owned   = true;
        this.deleted = false;
    }

    /**
     * Constructor for wrapped (non-owned) FBOs created by {@link #wrap}.
     */
    CgFrameBuffer(String name, int fboId, int width, int height) {
        this.name    = name;
        this.format  = null; // wrapped FBOs have no format descriptor
        this.fboId   = fboId;
        this.width   = width;
        this.height  = height;
        this.owned   = false;
        this.deleted = false;
    }

    // ── Static factories ───────────────────────────────────────────────────────

    /**
     * Returns (or lazily creates) a framebuffer for the given name, dimensions, and format.
     * Delegates to {@link CgFrameBufferRegistry#getOrCreate(String, int, int, CgFrameBufferFormat)}.
     *
     * @param name   human-readable instance name (registry cache key)
     * @param width  width in pixels (must be &gt; 0)
     * @param height height in pixels (must be &gt; 0)
     * @param format attachment layout descriptor
     * @return the owned {@code CgFrameBuffer} for this name/format pair
     */
    public static CgFrameBuffer create(String name, int width, int height,
                                        CgFrameBufferFormat format) {
        return CgFrameBufferRegistry.get().getOrCreate(name, width, height, format);
    }

    /**
     * Allocates a new owned framebuffer using the best available GL backend
     * (Core GL30 → ARB_framebuffer_object → EXT_framebuffer_object).
     * Called only by {@link CgFrameBufferRegistry}; external callers use {@link #create}.
     *
     * <p>Texture vs renderbuffer per slot is determined by the format's per-slot
     * flags.  Hardware attachment count is validated against
     * {@link #getMaxColorAttachments()}.  The framebuffer is checked for
     * completeness via {@code glCheckFramebufferStatus}; an
     * {@link IllegalStateException} is thrown if the status is not
     * {@code GL_FRAMEBUFFER_COMPLETE}.</p>
     *
     * <p>Depth-only formats ({@link CgFrameBufferFormat#isDepthOnly()}) automatically
     * invoke {@code GL_NONE} draw/read buffer configuration.</p>
     *
     * @param name   human-readable instance name
     * @param width  width in pixels (must be &gt; 0)
     * @param height height in pixels (must be &gt; 0)
     * @param format attachment layout descriptor
     * @return a new owned {@code CgFrameBuffer}
     * @throws IllegalArgumentException      if dimensions are not positive
     * @throws IllegalStateException         if the FBO is not complete
     * @throws UnsupportedOperationException if the hardware cannot satisfy the format
     *                                       requirements (e.g. MRT on EXT-only)
     */
    static CgFrameBuffer createInternal(String name, int width, int height, CgFrameBufferFormat format) {
        if (width <= 0 || height <= 0) 
            throw new IllegalArgumentException("Framebuffer dimensions must be positive: " + width + "x" + height);
        if (format == null) 
            throw new IllegalArgumentException("Format must not be null");
        

        // Validate color slot count against GPU limit
        int maxSlots = getMaxColorAttachments();
        for (int slot : format.getActiveColorSlotIds()) 
            if (slot >= maxSlots) 
                throw new UnsupportedOperationException("CgFrameBuffer '" + name + "': color slot " + slot + " exceeds GPU max draw buffers (" + maxSlots + ")");
            

        // Select backend
        CgCapabilities caps = CgCapabilities.detect();
        CgFrameBuffer fbo;
        if (caps.isCoreFbo())     fbo = new CgCoreFrameBuffer(name, format, width, height);
        else if (caps.isArbFbo()) fbo = new CgArbFrameBuffer(name, format, width, height);
        else if (caps.isExtFbo()) {
            if (format.colorSlotCount() > 1) 
                throw new UnsupportedOperationException("EXT_framebuffer_object does not support MRT. " + "Use Core GL30 or ARB_framebuffer_object.");
            fbo = new CgExtFrameBuffer(name, format, width, height);
        } else 
            throw new UnsupportedOperationException("No framebuffer object extension available. " + "CrystalGraphics requires at least EXT_framebuffer_object.");

        fbo.initGl(width, height, format);
        return fbo;
    }

    /**
     * Allocates a new, independently-owned framebuffer that bypasses
     * {@link CgFrameBufferRegistry}'s name-keyed cache entirely.
     *
     * <p>Use this for framebuffers whose count and size are caller-managed and don't fit the
     * registry's "small set of well-known named buffers" or "exactly screen-sized" models — e.g.
     * one small offscreen target per UI element that needs isolated compositing. The registry's
     * {@link #create} silently returns the existing FBO on a name hit regardless of size
     * mismatch; callers of this method own their instance outright and must call {@link #resize}
     * themselves when their target size changes, and {@link #delete()} when done.</p>
     *
     * @param name   human-readable instance name (for debugging only — not a cache key)
     * @param width  width in pixels (must be &gt; 0)
     * @param height height in pixels (must be &gt; 0)
     * @param format attachment layout descriptor
     * @return a new, independently-owned {@code CgFrameBuffer}
     */
    public static CgFrameBuffer createOwned(String name, int width, int height, CgFrameBufferFormat format) {
        return createInternal(name, width, height, format);
    }

    /**
     * Returns (or lazily creates) a screen-sized FBO managed by
     * {@link CgFrameBufferRegistry}.
     *
     * <p>The returned instance is resized in-place on every window resize —
     * the Java reference stays valid and can be cached safely.</p>
     *
     * @param name   human-readable instance name (registry cache key)
     * @param format attachment layout descriptor
     * @return the screen-sized FBO (never null)
     */
    public static CgFrameBuffer createScreenSized(String name, CgFrameBufferFormat format) {
        return CgFrameBufferRegistry.get().acquireScreenSized(name, format);
    }

    /**
     * Wraps an externally-created FBO ID as a non-owned {@code CgFrameBuffer}.
     *
     * <p>The returned instance can be bound and queried but {@link #delete()} is
     * a no-op — the underlying GL object is owned by the caller.</p>
     *
     * @param name   human-readable name for debugging
     * @param fboId  external GL framebuffer object ID
     * @param width  known width in pixels (informational)
     * @param height known height in pixels (informational)
     * @return a non-owned wrapped framebuffer
     */
    public static CgFrameBuffer wrap(String name, int fboId, int width, int height) {
        return new WrappedFrameBuffer(name, fboId, width, height);
    }

    // ── GL initialisation (called once by createInternal()) ───────────────────

    /**
     * Allocates all GL resources for this FBO.  Called once from
     * {@link #create} immediately after the backend constructor.
     *
     * <p>Uses abstract dispatch methods so each backend routes through its
     * own LWJGL entry points.</p>
     */
    private void initGl(int w, int h, CgFrameBufferFormat fmt) {
        if(fboId == 0) fboId = doGenFramebuffer();
        doBindFbo(CgGL.GL_FRAMEBUFFER, fboId);

        try {
            // ── Color attachments ──────────────────────────────────────────────
            for (int slot : fmt.getActiveColorSlotIds()) {
                CgTextureType type     = fmt.getColorSlot(slot);
                boolean       isRbo    = fmt.isColorRenderbuffer(slot);
                int           glAttach = type.glAttachmentPoint(slot);

                Attachment a = new Attachment(slot, type, isRbo, this);
                if (isRbo) {
                    int rboId = doGenRenderbuffer();
                    doBindRenderbuffer(rboId);
                    if (fmt.isMultisampled()) {
                        doRenderbufferStorageMultisample(fmt.getSamples(), type.glInternalFormat, w, h);
                    } else {
                        doRenderbufferStorage(type.glInternalFormat, w, h);
                    }
                    doFramebufferRenderbuffer(CgGL.GL_FRAMEBUFFER, glAttach, rboId);
                    a.setRenderbufferId(rboId);
                } else if (fmt.isMultisampled()) {
                    // A multisampled COLOR slot backed by a texture would need GL_TEXTURE_2D_MULTISAMPLE
                    // and a sampler2DMS to read it — a different type from CgTexture2D. Refused loudly
                    // rather than silently built single-sampled, because "my MSAA does nothing" is the
                    // hardest kind of graphics bug to find.
                    throw new IllegalArgumentException(
                            "CgFrameBufferFormat '" + fmt + "': colour slot " + slot + " is multisampled "
                            + "but declared as a sampleable texture. Use colorRenderbuffer(...) and "
                            + "resolve with blitFrom(...), which is what a sampler2D can read.");
                } else {
                    CgTexture2D tex = CgTexture2D.createEmpty(w, h, type.toTextureSpec());
                    doFramebufferTexture2D(CgGL.GL_FRAMEBUFFER, glAttach, CgGL.GL_TEXTURE_2D, tex.getId());
                    a.setTexture(tex);
                }
                colorAttachments.put(slot, a);
            }

            // ── Depth attachment ───────────────────────────────────────────────
            if (fmt.hasDepth()) {
                CgTextureType depthType = fmt.getDepthType();
                boolean       isRbo     = fmt.isDepthRenderbuffer();
                int           glAttach  = depthType.glAttachmentPoint(0); // slot ignored for depth

                Attachment a = new Attachment(-1, depthType, isRbo, this);
                if (isRbo) {
                    int rboId = doGenRenderbuffer();
                    doBindRenderbuffer(rboId);
                    // Depth MUST match the colour attachments' sample count or the framebuffer is
                    // incomplete — GL has no notion of mixing them.
                    if (fmt.isMultisampled()) {
                        doRenderbufferStorageMultisample(fmt.getSamples(), depthType.glInternalFormat, w, h);
                    } else {
                        doRenderbufferStorage(depthType.glInternalFormat, w, h);
                    }
                    doFramebufferRenderbuffer(CgGL.GL_FRAMEBUFFER, glAttach, rboId);
                    a.setRenderbufferId(rboId);
                } else if (fmt.isMultisampled()) {
                    throw new IllegalArgumentException(
                            "CgFrameBufferFormat '" + fmt + "': depth is multisampled but declared as a "
                            + "sampleable texture. Use depthRenderbuffer(...).");
                } else {
                    CgTexture2D tex = CgTexture2D.createEmpty(w, h, depthType.toTextureSpec());
                    doFramebufferTexture2D(CgGL.GL_FRAMEBUFFER, glAttach, CgGL.GL_TEXTURE_2D, tex.getId());
                    a.setTexture(tex);
                }
                depthAttachment = a;
            }

            // ── Depth-only: suppress draw/read ─────────────────────────────────
            if (fmt.isDepthOnly()) {
                CgGL.glDrawBuffer(CgGL.GL_NONE);
                CgGL.glReadBuffer(CgGL.GL_NONE);
            }

            // ── Completeness check ─────────────────────────────────────────────
            int status = doCheckFramebufferStatus();
            if (status != CgGL.GL_FRAMEBUFFER_COMPLETE) 
                throw new IllegalStateException("FBO '" + name + "' incomplete: 0x" + Integer.toHexString(status));

        } catch (RuntimeException e) {
            // Cleanup on failure
            freeGlResources();
            doBindFbo(CgGL.GL_FRAMEBUFFER, 0);
            throw e;
        }

        doBindFbo(CgGL.GL_FRAMEBUFFER, 0);
    }

    // ── Core API ───────────────────────────────────────────────────────────────

    /**
     * Binds this FBO as both draw and read framebuffer ({@code GL_FRAMEBUFFER}).
     * Routes through {@link CrossApiTransition} to handle cross-API transitions.
     */
    public void bind() {
        CgGL.glBindFramebuffer(CgGL.GL_FRAMEBUFFER, fboId);
    }

    /**
     * Binds this FBO as the draw framebuffer only ({@code GL_DRAW_FRAMEBUFFER}).
     * EXT backends override this to use {@code GL_FRAMEBUFFER_EXT}.
     */
    public void bindDraw() {
        CgGL.glBindFramebuffer(CgGL.GL_DRAW_FRAMEBUFFER, fboId);
    }

    /**
     * Binds this FBO as the read framebuffer only ({@code GL_READ_FRAMEBUFFER}).
     * EXT backends override this to use {@code GL_FRAMEBUFFER_EXT}.
     */
    public void bindRead() {
        CgGL.glBindFramebuffer(CgGL.GL_READ_FRAMEBUFFER, fboId);
    }

    /**
     * Unbinds this FBO by binding the default framebuffer (ID 0).
     */
    public void unbind() {
        CgGL.glBindFramebuffer(CgGL.GL_FRAMEBUFFER, 0);
    }

    /** Returns the GL FBO object ID. */
    public int getId() { return fboId; }

    /** Returns the format descriptor used to create this FBO (null for wrapped FBOs). */
    @Nullable
    public CgFrameBufferFormat getFormat() { return format; }

    /**
     * Returns {@code true} if this FBO supports multiple render targets (MRT).
     * EXT FBOs always return {@code false}.
     */
    public boolean supportsMrt() {
        return format != null && format.colorSlotCount() > 1;
    }

    /**
     * Sets the active draw color buffers by slot index.
     * Each element is a color slot index (0, 1, 2, …); internally converted to
     * {@code GL_COLOR_ATTACHMENT0 + n}.
     *
     * <p>EXT backends override this to always throw
     * {@link UnsupportedOperationException}.</p>
     *
     * @param slotIds color slot indices to activate as draw buffers
     * @throws IllegalArgumentException if {@code slotIds} is empty
     */
    public void drawBuffers(int... slotIds) {
        if (slotIds == null || slotIds.length == 0) 
            throw new IllegalArgumentException("At least one draw buffer slot must be specified");
        
        IntBuffer buf = CgBufferUtils.createIntBuffer(slotIds.length);
        for (int slot : slotIds) 
            buf.put(CgGL.GL_COLOR_ATTACHMENT0 + slot);
        
        buf.flip();
        CgGL.glDrawBuffers(buf);
    }

    // ── Attachment access ──────────────────────────────────────────────────────

    /**
     * Returns the {@link Attachment} for the given color slot, or {@code null}
     * if the slot is unused.
     */
    @Nullable
    public Attachment getColorAttachment(int slot) {
        return colorAttachments.get(slot);
    }

    /**
     * Returns the depth/stencil {@link Attachment}, or {@code null} if the
     * format has no depth attachment.
     */
    @Nullable
    public Attachment getDepthAttachment() {
        return depthAttachment;
    }

    /**
     * Returns the {@link CgTexture} at the given color slot, or {@code null}
     * if the slot is unused or backed by a renderbuffer.
     */
    @Nullable
    public CgTexture getColorTexture(int slot) {
        Attachment a = colorAttachments.get(slot);
        return a != null ? a.getTexture() : null;
    }

    /**
     * Returns the depth {@link CgTexture}, or {@code null} if there is no depth
     * attachment or the depth attachment is backed by a renderbuffer.
     */
    @Nullable
    public CgTexture getDepthTexture() {
        return depthAttachment != null ? depthAttachment.getTexture() : null;
    }

    // ── Blit ───────────────────────────────────────────────────────────────────

    /** Blits from {@code source} into this FBO. Extracts GL id and dimensions from the source. */
    public void blitFrom(CgFrameBuffer source, int mask, int filter) {
        blitFrom(source.getId(), source.getWidth(), source.getHeight(), mask, filter);
    }

    /** Blits from {@code sourceFboId} into this FBO assuming equal dimensions; uses {@code GL_NEAREST}. */
    public void blitFrom(int sourceFboId, int mask) {
        blitFrom(sourceFboId, width, height, mask, CgGL.GL_NEAREST);
    }

    /** Blits from {@code sourceFboId} into this FBO with explicit source dimensions and filter. */
    public void blitFrom(int sourceFboId, int sourceWidth, int sourceHeight, int mask, int filter) {
        blitFrom(sourceFboId, fboId, 0, 0, width, height, 0, 0, sourceWidth, sourceHeight, mask, filter);
    }

    /**
     * Raw blit between two arbitrary FBO ids with full source and destination rect control.
     * Saves and restores the FBO binding.
     */
    public static void blitFrom(int src, int dst, int srcX0, int srcY0, int srcX1, int srcY1,
                                int dstX0, int dstY0, int dstX1, int dstY1,
                                int mask, int filter) {
        try (CgGlScope scope = CgGlState.save(FBO)) {
            CgGL.glBindFramebuffer(CgGL.GL_READ_FRAMEBUFFER, src);
            CgGL.glBindFramebuffer(CgGL.GL_DRAW_FRAMEBUFFER, dst);
            CgGL.glBlitFramebuffer(srcX0, srcY0, srcX1, srcY1, dstX0, dstY0, dstX1, dstY1, mask, filter);
        }
    }

    /**
     * Probes the source FBO to determine the optimal GL blit mask for a depth snapshot copy.
     *
     * <p>Blitting a {@code DEPTH24_STENCIL8} attachment with only {@code GL_DEPTH_BUFFER_BIT}
     * forces the driver to unpack the packed 32-bit word, copy only the depth channel, and
     * repack — a slow path. Passing {@code GL_DEPTH_BUFFER_BIT | GL_STENCIL_BUFFER_BIT}
     * copies the entire packed word as a single memcpy-equivalent operation.</p>
     *
     * <p>However, blitting stencil from a depth-only source is a GL error. This method queries
     * {@code GL_STENCIL_ATTACHMENT} on the source FBO exactly once and returns the appropriate
     * mask. The result should be cached by the caller.</p>
     *
     * @param sourceFboId the GL framebuffer id to probe (bound as read framebuffer temporarily)
     * @return {@code GL_DEPTH_BUFFER_BIT | GL_STENCIL_BUFFER_BIT} when the source has a stencil
     *         attachment, otherwise {@code GL_DEPTH_BUFFER_BIT}
     */
    public static int optimalDepthBlitMask(int sourceFboId) {
        try (CgGlScope scope = CgGlState.save(FBO)) {
            CgGL.glBindFramebuffer(CgGL.GL_READ_FRAMEBUFFER, sourceFboId);
            int stencilToken = (sourceFboId == 0) ? CgGL.GL_STENCIL : CgGL.GL_STENCIL_ATTACHMENT;
            int objType = CgGL.glGetFramebufferAttachmentParameteriv(
                    CgGL.GL_READ_FRAMEBUFFER,
                    stencilToken,
                    CgGL.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
            boolean hasStencil = (objType != CgGL.GL_NONE);
            return hasStencil
                    ? CgGL.GL_DEPTH_BUFFER_BIT | CgGL.GL_STENCIL_BUFFER_BIT
                    : CgGL.GL_DEPTH_BUFFER_BIT;
        }
    }

    // ── Clear ──────────────────────────────────────────────────────────────────

    /**
     * Full-parameter clear. Sets each clear value only when the corresponding bit
     * is present in {@code mask}, then issues {@code glClear}.
     * Restores the previously bound framebuffer on exit.
     */
    public void clear(int mask, float r, float g, float b, float a, double depthValue, int stencilValue) {
        try (CgGlScope scope = CgGlState.save(FBO)) {
            bind();
            if ((mask & CgGL.GL_COLOR_BUFFER_BIT) != 0) CgGL.glClearColor(r, g, b, a);
            if ((mask & CgGL.GL_DEPTH_BUFFER_BIT) != 0) CgGL.glClearDepth(depthValue);
            if ((mask & CgGL.GL_STENCIL_BUFFER_BIT) != 0) CgGL.glClearStencil(stencilValue);
            CgGL.glClear(mask);
        }
    }

    /** Clears only the colour buffer to the given RGBA colour. */
    public void clearColor(float r, float g, float b, float a) {
        clear(CgGL.GL_COLOR_BUFFER_BIT, r, g, b, a, 1.0, 0);
    }

    /** Clears only the depth buffer to 1.0 (far plane). */
    public void clearDepth() {
        clear(CgGL.GL_DEPTH_BUFFER_BIT, 0, 0, 0, 0, 1.0, 0);
    }

    /** Clears only the depth buffer to {@code value}. */
    public void clearDepth(double value) {
        clear(CgGL.GL_DEPTH_BUFFER_BIT, 0, 0, 0, 0, value, 0);
    }

    /** Clears only the stencil buffer to 0. */
    public void clearStencil() {
        clear(CgGL.GL_STENCIL_BUFFER_BIT, 0, 0, 0, 0, 1.0, 0);
    }

    /** Clears only the stencil buffer to {@code value}. */
    public void clearStencil(int value) {
        clear(CgGL.GL_STENCIL_BUFFER_BIT, 0, 0, 0, 0, 1.0, value);
    }

    /** Clears depth and stencil buffers. */
    public void clearDepthStencil() {
        clear(CgGL.GL_DEPTH_BUFFER_BIT | CgGL.GL_STENCIL_BUFFER_BIT, 0, 0, 0, 0, 1.0, 0);
    }

    /** Clears depth and stencil buffers. */
    public void clearDepthStencil(double depthValue, int stencilValue) {
        clear(CgGL.GL_DEPTH_BUFFER_BIT | CgGL.GL_STENCIL_BUFFER_BIT, 0, 0, 0, 0, depthValue, stencilValue);
    }

    /** Clears colour, depth, and stencil in one call. Depth resets to 1.0, stencil to 0. */
    public void clearAll(float r, float g, float b, float a) {
        clear(CgGL.GL_COLOR_BUFFER_BIT | CgGL.GL_DEPTH_BUFFER_BIT | CgGL.GL_STENCIL_BUFFER_BIT,
                r, g, b, a, 1.0, 0);
    }
    
    // ── Dynamic re-attachment ──────────────────────────────────────────────────

    /**
     * Reattaches a {@link CgTexture} to a color slot.  Does NOT transfer
     * ownership — the caller is responsible for the texture's lifecycle.
     *
     * @param slot    the color slot index
     * @param texture the texture to attach
     */
    public void reattachColor(int slot, CgTexture texture) {
        if (texture == null) throw new IllegalArgumentException("texture must not be null");
        int glAttach = CgGL.GL_COLOR_ATTACHMENT0 + slot;
        doBindFbo(CgGL.GL_FRAMEBUFFER, fboId);
        doFramebufferTexture2D(CgGL.GL_FRAMEBUFFER, glAttach, CgGL.GL_TEXTURE_2D, texture.getId());
        doBindFbo(CgGL.GL_FRAMEBUFFER, 0);
        Attachment a = colorAttachments.get(slot);
        if (a != null) a.setTexture(texture instanceof CgTexture2D ? texture : null);
    }

    /**
     * Raw GL re-attachment for cube map face rendering.
     * Pass {@code GL_TEXTURE_CUBE_MAP_POSITIVE_X + face} as {@code glTarget}.
     *
     * @param slot        color slot index
     * @param glTextureId raw GL texture object ID
     * @param glTarget    GL texture target (e.g. {@code GL_TEXTURE_CUBE_MAP_POSITIVE_X + face})
     */
    public void reattachColorRaw(int slot, int glTextureId, int glTarget) {
        int glAttach = CgGL.GL_COLOR_ATTACHMENT0 + slot;
        doBindFbo(CgGL.GL_FRAMEBUFFER, fboId);
        doFramebufferTexture2D(CgGL.GL_FRAMEBUFFER, glAttach, glTarget, glTextureId);
        doBindFbo(CgGL.GL_FRAMEBUFFER, 0);
    }

    /**
     * Reattaches a different texture to the depth slot.  Does NOT transfer
     * ownership — the caller is responsible for the texture's lifecycle.
     *
     * @param texture the depth texture to attach
     */
    public void reattachDepth(CgTexture texture) {
        if (depthAttachment == null) throw new IllegalStateException("FBO '" + name + "' has no depth attachment");
        
        int glAttach = depthAttachment.getType().glAttachmentPoint(0);
        doBindFbo(CgGL.GL_FRAMEBUFFER, fboId);
        doFramebufferTexture2D(CgGL.GL_FRAMEBUFFER, glAttach, CgGL.GL_TEXTURE_2D, texture.getId());
        doBindFbo(CgGL.GL_FRAMEBUFFER, 0);
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    /**
     * Resizes this FBO in-place. All GL resources are freed and reallocated at
     * the new dimensions. The Java object reference is stable — callers do not
     * need to re-query after a window resize.
     *
     * @param newWidth  new width in pixels (must be &gt; 0)
     * @param newHeight new height in pixels (must be &gt; 0)
     */
    public void resize(int newWidth, int newHeight) {
        freeGlResources();
        this.width  = newWidth;
        this.height = newHeight;
        initGl(newWidth, newHeight, this.format);
    }

    /**
     * Deletes this framebuffer and all associated GL resources.
     *
     * <p>For wrapped (non-owned) FBOs this is a no-op.  Subsequent calls after
     * the first successful deletion are also no-ops.</p>
     */
    public void delete() {
        if (!owned || deleted) return;
        freeGlResources();
        deleted = true;
    }

    /**
     * Releases all GL resources held by this FBO (attachments + FBO id).
     */
    private void freeGlResources() {
        for (Attachment a : colorAttachments.values()) 
            a.delete();
        
        colorAttachments.clear();
        if (depthAttachment != null) {
            depthAttachment.delete();
            depthAttachment = null;
        }
        if (fboId != 0) {
            deleteFramebuffer(fboId);
            fboId = 0;
        }
    }

    // ── Abstract GL dispatch (implemented by each backend) ─────────────────────

    /**
     * Generates a new GL framebuffer object and returns its ID.
     * Called once from {@link #initGl}.
     */
    protected abstract int doGenFramebuffer();

    /**
     * Deletes the framebuffer object with the given ID.
     * Called by {@link #freeGlResources}.
     */
    protected abstract void deleteFramebuffer(int id);

    /**
     * Deletes the renderbuffer with the given ID.
     * Called by {@link Attachment#delete()} to ensure the correct GL API is used.
     */
    protected abstract void deleteRenderbuffer(int id);

    /**
     * Binds a framebuffer to the given target ({@code GL_FRAMEBUFFER}, etc.).
     * Used internally for FBO setup and reattach operations — not for public
     * bind (which goes through {@link CrossApiTransition}).
     *
     * @param target GL target (e.g. {@code GL_FRAMEBUFFER = 0x8D40})
     * @param fboId  FBO ID, or 0 to unbind
     */
    protected abstract void doBindFbo(int target, int fboId);

    /**
     * Attaches a 2D texture to the currently bound framebuffer.
     *
     * @param target          framebuffer target (e.g. {@code GL_FRAMEBUFFER})
     * @param attachmentPoint GL attachment point (e.g. {@code GL_COLOR_ATTACHMENT0})
     * @param glTextureTarget texture target (e.g. {@code GL_TEXTURE_2D}, cube face)
     * @param texId           GL texture ID, or 0 to detach
     */
    protected abstract void doFramebufferTexture2D(int target, int attachmentPoint,
                                                    int glTextureTarget, int texId);

    /**
     * Attaches a renderbuffer to the currently bound framebuffer.
     *
     * @param target          framebuffer target
     * @param attachmentPoint GL attachment point
     * @param rboId           GL renderbuffer ID, or 0 to detach
     */
    protected abstract void doFramebufferRenderbuffer(int target, int attachmentPoint,
                                                       int rboId);

    /**
     * Generates a new GL renderbuffer object and returns its ID.
     * The renderbuffer is left bound after this call.
     */
    protected abstract int doGenRenderbuffer();

    /**
     * Allocates storage for the currently bound renderbuffer.
     *
     * @param internalFormat GL internal format (e.g. {@code GL_DEPTH24_STENCIL8})
     * @param w              width in pixels
     * @param h              height in pixels
     */
    protected abstract void doRenderbufferStorage(int internalFormat, int w, int h);

    /**
     * Multisampled renderbuffer storage.
     *
     * <p>Concrete rather than abstract, unlike its single-sampled twin: every backend that has
     * multisampling at all reaches it through the same {@code GL_RENDERBUFFER} target, and the one that
     * does not ({@link CgExtFrameBuffer}) overrides this to fall back. Making it abstract would force
     * three identical overrides to say the same thing.</p>
     */
    protected void doRenderbufferStorageMultisample(int samples, int internalFormat, int w, int h) {
        CgGL.glRenderbufferStorageMultisample(CgGL.GL_RENDERBUFFER, samples, internalFormat, w, h);
    }

    /**
     * Binds a renderbuffer so storage can be allocated into it.
     *
     * <p><b>{@code glRenderbufferStorage} operates on the BOUND renderbuffer, not on an id</b> — and
     * generating one does not bind it. Without this the allocation lands on whatever was bound
     * previously (or on nothing), and the attachment ends up with no storage.</p>
     */
    protected void doBindRenderbuffer(int rboId) {
        CgGL.glBindRenderbuffer(CgGL.GL_RENDERBUFFER, rboId);
    }

    /**
     * Checks the completeness status of the currently bound framebuffer.
     *
     * @return GL framebuffer status constant
     *         (e.g. {@code GL_FRAMEBUFFER_COMPLETE = 0x8CD5})
     */
    protected abstract int doCheckFramebufferStatus();

    // ── Attachment inner class ─────────────────────────────────────────────────

    /**
     * One GL attachment slot — either a sampleable {@link CgTexture2D} or a
     * non-sampleable renderbuffer.
     *
     * <p>Created only by {@link CgFrameBuffer#initGl} and owned entirely by
     * its parent FBO.  {@link #delete()} is called by the parent during
     * {@link CgFrameBuffer#delete()}.</p>
     *
     * <h3>Paths</h3>
     * <ul>
     *   <li>Texture path: {@code texture != null}, {@code renderbufferId == 0}</li>
     *   <li>Renderbuffer path: {@code texture == null}, {@code renderbufferId != 0}</li>
     * </ul>
     */
    public static final class Attachment {

        /** Color index (0..N-1), or -1 for the depth attachment. 
         * -- GETTER --
         * Returns the slot index (color index, or -1 for depth). 
         */
        @Getter
        private final int slot;

        /** Format of this attachment. 
         * -- GETTER --
         * Returns the format of this attachment. 
         */
        @Getter
        private final CgTextureType type;

        /** {@code true} if this attachment is a renderbuffer (non-sampleable). 
         * -- GETTER --
         * Returns 
         *  if this attachment is a renderbuffer (not sampleable). 
         */
        @Getter
        private final boolean renderbuffer;

        /** Sampleable texture, or {@code null} for renderbuffer path. */
        @Nullable
        private CgTexture texture;

        /** GL renderbuffer ID, or 0 for texture path. */
        private int renderbufferId;

        /**
         * Reference to the owning {@code CgFrameBuffer} for backend-correct
         * renderbuffer deletion.
         */
        private final CgFrameBuffer parent;

        Attachment(int slot, CgTextureType type, boolean renderbuffer, CgFrameBuffer parent) {
            this.slot         = slot;
            this.type         = type;
            this.renderbuffer = renderbuffer;
            this.parent       = parent;
            this.texture      = null;
            this.renderbufferId = 0;
        }

        /** Returns {@code true} if this attachment is a sampleable texture. */
        public boolean hasTexture() { return texture != null; }

        /**
         * Returns the texture, or {@code null} if this is a renderbuffer slot.
         * Check {@link #hasTexture()} or {@link #isRenderbuffer()} first.
         */
        @Nullable
        public CgTexture getTexture() { return texture; }

        // ── Package-private setters (called by CgFrameBuffer during creation) ──

        void setTexture(CgTexture t)     { this.texture         = t; }
        void setRenderbufferId(int id)   { this.renderbufferId  = id; }
        int  getRenderbufferId()         { return renderbufferId; }

        /**
         * Releases the GL resource held by this attachment.
         * Routes renderbuffer deletion through the parent's backend hook.
         */
        void delete() {
            if (texture != null) {
                texture.delete();
                texture = null;
            }
            if (renderbufferId != 0) {
                parent.deleteRenderbuffer(renderbufferId);
                renderbufferId = 0;
            }
        }
    }

    // ── WrappedFrameBuffer inner class ─────────────────────────────────────────

    /**
     * Minimal wrapper for externally-created FBO IDs.
     *
     * <p>Supports bind/unbind only.  All GL dispatch stubs are no-ops because
     * this object never creates or modifies GL resources.</p>
     */
    private static final class WrappedFrameBuffer extends CgFrameBuffer {

        WrappedFrameBuffer(String name, int fboId, int width, int height) {
            super(name, fboId, width, height);
        }

        // ── delete is no-op for wrapped FBOs ──────────────────────────────────
        @Override
        public void delete() { /* non-owned — caller is responsible */ }

        // ── GL dispatch stubs (wrapped FBOs own nothing) ─────────────────────
        @Override protected int  doGenFramebuffer()                                    { return 0; }
        @Override protected void deleteFramebuffer(int id)                             { /* no-op */ }
        @Override protected void deleteRenderbuffer(int id)                            { /* no-op */ }
        @Override protected void doBindFbo(int target, int fboId)                     { /* no-op */ }
        @Override protected void doFramebufferTexture2D(int t, int ap, int gt, int id){ /* no-op */ }
        @Override protected void doFramebufferRenderbuffer(int t, int ap, int rbo)    { /* no-op */ }
        @Override protected int  doGenRenderbuffer()                                   { return 0; }
        @Override protected void doRenderbufferStorage(int fmt, int w, int h)         { /* no-op */ }
        @Override protected void doBindRenderbuffer(int rboId)                        { /* no-op */ }
        @Override protected void doRenderbufferStorageMultisample(int s, int f, int w, int h) { /* no-op */ }
        @Override protected int  doCheckFramebufferStatus()                            { return CgGL.GL_FRAMEBUFFER_COMPLETE; }
    }
}
