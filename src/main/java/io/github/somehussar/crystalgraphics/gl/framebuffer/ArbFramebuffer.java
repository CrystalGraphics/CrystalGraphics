package io.github.somehussar.crystalgraphics.gl.framebuffer;

import io.github.somehussar.crystalgraphics.api.CgColorAttachmentProvider;
import io.github.somehussar.crystalgraphics.api.CgColorAttachmentSpec;
import io.github.somehussar.crystalgraphics.api.CgDepthStencilSpec;
import io.github.somehussar.crystalgraphics.api.CgFramebufferSpec;
import io.github.somehussar.crystalgraphics.api.CgMipmapConfig;
import io.github.somehussar.crystalgraphics.api.CgRuntimeAttachments;
import io.github.somehussar.crystalgraphics.api.CgTextureFormatSpec;
import io.github.somehussar.crystalgraphics.gl.state.CallFamily;

import org.lwjgl.opengl.ARBFramebufferObject;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Framebuffer implementation using the {@code ARB_framebuffer_object} extension.
 *
 * <p>The ARB FBO extension is semantically identical to Core GL30 and uses the
 * same constant values (e.g. {@code GL_FRAMEBUFFER = 0x8D40}), but routes
 * through different LWJGL entry points
 * ({@link ARBFramebufferObject#glGenFramebuffers()} vs.
 * {@link org.lwjgl.opengl.GL30#glGenFramebuffers()}).  This matters because
 * some drivers expose ARB FBO support without fully supporting GL 3.0, and
 * cross-API state tracking must know which entry point was used.</p>
 *
 * <p>MRT is supported via {@link GL20#glDrawBuffers(IntBuffer)}, which is
 * independent of the FBO extension and only requires GL 2.0.</p>
 *
 * <p>Both legacy (single color attachment) and spec-based (multi-attachment)
 * framebuffers are supported.</p>
 *
 * <h3>Thread Safety</h3>
 * <p>Not thread-safe.  Must only be used from the GL context thread.</p>
 *
 * @see AbstractCgFramebuffer
 * @see CoreFramebuffer
 * @see ExtFramebuffer
 */
public final class ArbFramebuffer extends AbstractCgFramebuffer {

    private static final Logger LOGGER = Logger.getLogger(ArbFramebuffer.class.getName());

    // ── GL constants (same values as Core GL30) ────────────────────────

    /** {@code GL_FRAMEBUFFER} target. */
    private static final int GL_FRAMEBUFFER = 0x8D40;

    /** {@code GL_RENDERBUFFER} target. */
    private static final int GL_RENDERBUFFER = 0x8D41;

    /** {@code GL_DEPTH_COMPONENT24} internal format. */
    private static final int GL_DEPTH_COMPONENT24 = 0x81A6;

    /** {@code GL_DEPTH_ATTACHMENT} attachment point. */
    private static final int GL_DEPTH_ATTACHMENT = 0x8D00;

    /** {@code GL_COLOR_ATTACHMENT0} attachment point. */
    private static final int GL_COLOR_ATTACHMENT0 = 0x8CE0;

    /** {@code GL_TEXTURE_2D} target. */
    private static final int GL_TEXTURE_2D = 0x0DE1;

    /** {@code GL_RGBA8} internal format. */
    private static final int GL_RGBA8 = 0x8058;

    /** {@code GL_RGBA} pixel format. */
    private static final int GL_RGBA = 0x1908;

    /** {@code GL_UNSIGNED_BYTE} pixel type. */
    private static final int GL_UNSIGNED_BYTE = 0x1401;

    /** {@code GL_TEXTURE_MIN_FILTER} parameter. */
    private static final int GL_TEXTURE_MIN_FILTER = 0x2801;

    /** {@code GL_TEXTURE_MAG_FILTER} parameter. */
    private static final int GL_TEXTURE_MAG_FILTER = 0x2800;

    /** {@code GL_LINEAR} filter value. */
    private static final int GL_LINEAR = 0x2601;

    /** {@code GL_FRAMEBUFFER_COMPLETE} status. */
    private static final int GL_FRAMEBUFFER_COMPLETE = 0x8CD5;

    /** {@code GL_STENCIL_ATTACHMENT} attachment point. */
    private static final int GL_STENCIL_ATTACHMENT = 0x8D20;

    /** {@code GL_DEPTH_STENCIL_ATTACHMENT} attachment point (Core/ARB only). */
    private static final int GL_DEPTH_STENCIL_ATTACHMENT = 0x821A;

    /** {@code GL_STENCIL_INDEX8} internal format. */
    private static final int GL_STENCIL_INDEX8 = 0x8D48;

    /** {@code GL_DEPTH_COMPONENT} pixel format for depth texture allocation. */
    private static final int GL_DEPTH_COMPONENT = 0x1902;

    /** {@code GL_NEAREST} filter value. */
    private static final int GL_NEAREST = 0x2600;

    // ── Instance state ─────────────────────────────────────────────────

    /** Whether this framebuffer has a depth renderbuffer attached (legacy path). */
    private final boolean hasDepth;

    /**
     * The spec used to create this framebuffer, or {@code null} for legacy path.
     * Retained for resize operations.
     */
    private CgFramebufferSpec spec;

    /**
     * Array of all color texture IDs (spec-based path).
     * {@code null} for legacy path (uses inherited {@code colorTextureId}).
     */
    private int[] colorTextureIds;

    /**
     * Renderbuffer ID for the stencil attachment (separate mode), or 0 if none.
     * For packed depth-stencil, the single renderbuffer is stored in {@code depthRenderbufferId}.
     */
    private int stencilRenderbufferId;

    /** Texture ID for depth attachment when allocated as texture, or 0 if renderbuffer/absent. */
    private int depthTextureId;

    /** Runtime color attachments keyed by slot index. */
    private final Map<Integer, RuntimeSlot> runtimeSlots = new HashMap<Integer, RuntimeSlot>();

    /** Lazily-created runtime attachments manager. */
    private CgRuntimeAttachments runtimeAttachments;

    // ── RuntimeSlot inner class ────────────────────────────────────────

    private static final class RuntimeSlot {
        int textureId;
        final boolean managed;
        final CgColorAttachmentProvider provider;
        final CgTextureFormatSpec format;

        RuntimeSlot(int textureId) {
            this.textureId = textureId;
            this.managed = false;
            this.provider = null;
            this.format = null;
        }

        RuntimeSlot(int textureId, CgColorAttachmentProvider provider, CgTextureFormatSpec format) {
            this.textureId = textureId;
            this.managed = true;
            this.provider = provider;
            this.format = format;
        }
    }

    // ── Constructors ───────────────────────────────────────────────────

    /**
     * Creates an ArbFramebuffer with pre-existing GL resource IDs (legacy path).
     *
     * @param fboId               the framebuffer object ID
     * @param colorTextureId      the color texture ID
     * @param depthRenderbufferId the depth renderbuffer ID (0 if none)
     * @param width               width in pixels
     * @param height              height in pixels
     * @param hasDepth            whether a depth buffer is attached
     * @param supportsMrt         whether MRT is available
     */
    private ArbFramebuffer(int fboId, int colorTextureId, int depthRenderbufferId,
                           int width, int height, boolean hasDepth, boolean supportsMrt) {
        super(fboId, width, height, true, supportsMrt);
        this.colorTextureId = colorTextureId;
        this.depthRenderbufferId = depthRenderbufferId;
        this.hasDepth = hasDepth;
        this.spec = null;
        this.colorTextureIds = null;
        this.stencilRenderbufferId = 0;
    }

    /**
     * Creates an ArbFramebuffer with spec-based multi-attachment state.
     *
     * @param fboId                 the framebuffer object ID
     * @param colorTextureIds       array of all color texture IDs
     * @param depthRenderbufferId   the depth renderbuffer ID (0 if none)
     * @param stencilRenderbufferId the stencil renderbuffer ID (0 if none)
     * @param width                 base width in pixels
     * @param height                base height in pixels
     * @param supportsMrt           whether MRT is available
     * @param spec                  the framebuffer spec (retained for resize)
     */
    private ArbFramebuffer(int fboId, int[] colorTextureIds,
                           int depthRenderbufferId, int stencilRenderbufferId,
                           int depthTextureId,
                           int width, int height, boolean supportsMrt,
                           CgFramebufferSpec spec) {
        super(fboId, width, height, true, supportsMrt);
        this.colorTextureIds = colorTextureIds;
        this.colorTextureId = colorTextureIds.length > 0 ? colorTextureIds[0] : 0;
        this.depthRenderbufferId = depthRenderbufferId;
        this.stencilRenderbufferId = stencilRenderbufferId;
        this.depthTextureId = depthTextureId;
        this.hasDepth = false;
        this.spec = spec;
    }

    /**
     * Package-private constructor for wrapping an externally-created FBO.
     *
     * <p>The wrapped framebuffer is non-owned and cannot be deleted.</p>
     *
     * @param fboId       the external framebuffer object ID
     * @param width       known width (informational)
     * @param height      known height (informational)
     * @param supportsMrt whether MRT is available
     */
    ArbFramebuffer(int fboId, int width, int height, boolean supportsMrt) {
        super(fboId, width, height, false, supportsMrt);
        this.hasDepth = false;
    }

    // ── Factory method ─────────────────────────────────────────────────

    /**
     * Creates a new ARB framebuffer with a color texture attachment and
     * optional depth renderbuffer.
     *
     * <p>Uses {@link ARBFramebufferObject} entry points for all FBO
     * operations.  The constant values are identical to Core GL30.</p>
     *
     * @param width  width in pixels (must be &gt; 0)
     * @param height height in pixels (must be &gt; 0)
     * @param depth  whether to attach a depth renderbuffer
     * @param mrt    whether to allow multiple render targets
     * @return a new owned {@code ArbFramebuffer}
     * @throws IllegalStateException    if the framebuffer is not complete after setup
     * @throws IllegalArgumentException if width or height is not positive
     */
    public static ArbFramebuffer create(int width, int height, boolean depth, boolean mrt) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                    "Framebuffer dimensions must be positive: " + width + "x" + height);
        }

        int fbo = ARBFramebufferObject.glGenFramebuffers();
        ARBFramebufferObject.glBindFramebuffer(GL_FRAMEBUFFER, fbo);

        // Color texture (GL_COLOR_ATTACHMENT0)
        int colorTex = GL11.glGenTextures();
        GL11.glBindTexture(GL_TEXTURE_2D, colorTex);
        GL11.glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0,
                GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GL11.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        GL11.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        ARBFramebufferObject.glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                GL_TEXTURE_2D, colorTex, 0);

        // Optional depth renderbuffer
        int depthRbo = 0;
        if (depth) {
            depthRbo = ARBFramebufferObject.glGenRenderbuffers();
            ARBFramebufferObject.glBindRenderbuffer(GL_RENDERBUFFER, depthRbo);
            ARBFramebufferObject.glRenderbufferStorage(GL_RENDERBUFFER,
                    GL_DEPTH_COMPONENT24, width, height);
            ARBFramebufferObject.glFramebufferRenderbuffer(GL_FRAMEBUFFER,
                    GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, depthRbo);
        }

        // Completeness check
        int status = ARBFramebufferObject.glCheckFramebufferStatus(GL_FRAMEBUFFER);
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            ARBFramebufferObject.glDeleteFramebuffers(fbo);
            GL11.glDeleteTextures(colorTex);
            if (depthRbo != 0) {
                ARBFramebufferObject.glDeleteRenderbuffers(depthRbo);
            }
            throw new IllegalStateException(
                    "ARB framebuffer is not complete. Status: 0x"
                    + Integer.toHexString(status));
        }

        // Unbind FBO to restore default state
        ARBFramebufferObject.glBindFramebuffer(GL_FRAMEBUFFER, 0);

        return new ArbFramebuffer(fbo, colorTex, depthRbo, width, height, depth, mrt);
    }

    // ── Factory method (spec-based) ───────────────────────────────────

    /**
     * Creates a new ARB framebuffer from a detailed specification.
     *
     * <p>Allocates N color textures based on the spec's color attachments,
     * each with independent sizing and format. Allocates depth/stencil
     * renderbuffers based on the depth-stencil spec. All resources are
     * failure-atomic: if any allocation or completeness check fails, all
     * previously allocated GL objects are cleaned up before throwing.</p>
     *
     * <p>For packed depth-stencil, if the initial packed attachment fails the
     * completeness check, a fallback to separate depth + stencil is attempted.</p>
     *
     * @param spec the framebuffer specification
     * @return a new owned {@code ArbFramebuffer}
     * @throws IllegalArgumentException if spec is null
     * @throws IllegalStateException    if the framebuffer is not complete after setup
     */
    public static ArbFramebuffer createFromSpec(CgFramebufferSpec spec) {
        if (spec == null) {
            throw new IllegalArgumentException("Spec must not be null");
        }

        int baseWidth = spec.getBaseWidth();
        int baseHeight = spec.getBaseHeight();
        List<CgColorAttachmentSpec> attachments = spec.getColorAttachments();
        CgDepthStencilSpec dsSpec = spec.getDepthStencil();
        int colorCount = attachments.size();
        boolean mrt = colorCount > 1;

        int fbo = ARBFramebufferObject.glGenFramebuffers();
        ARBFramebufferObject.glBindFramebuffer(GL_FRAMEBUFFER, fbo);

        // Track allocated resources for failure-atomic cleanup
        int[] colorTexIds = new int[colorCount];
        int depthRbo = 0;
        int stencilRbo = 0;
        int depthTex = 0;

        try {
            // ── Allocate color textures ─────────────────────────────
            for (int i = 0; i < colorCount; i++) {
                CgColorAttachmentSpec att = attachments.get(i);
                CgTextureFormatSpec fmt = att.getFormat();
                CgMipmapConfig mips = att.getMipmaps();

                int attWidth = Math.max(1, (int) Math.ceil(baseWidth * att.getScaleX()));
                int attHeight = Math.max(1, (int) Math.ceil(baseHeight * att.getScaleY()));

                int tex = GL11.glGenTextures();
                colorTexIds[i] = tex;
                GL11.glBindTexture(GL_TEXTURE_2D, tex);
                GL11.glTexImage2D(GL_TEXTURE_2D, 0,
                        fmt.getInternalFormat(), attWidth, attHeight, 0,
                        fmt.getPixelFormat(), fmt.getPixelType(), (ByteBuffer) null);

                if (mips.isEnabled()) {
                    GL11.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, mips.getMinFilter());
                    GL11.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, mips.getMagFilter());
                } else {
                    GL11.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
                    GL11.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
                }

                ARBFramebufferObject.glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0 + i,
                        GL_TEXTURE_2D, tex, 0);
            }

            // ── Allocate depth/stencil ─────────────────────────────
            if (dsSpec.isPacked()) {
                depthRbo = ARBFramebufferObject.glGenRenderbuffers();
                ARBFramebufferObject.glBindRenderbuffer(GL_RENDERBUFFER, depthRbo);
                ARBFramebufferObject.glRenderbufferStorage(GL_RENDERBUFFER, dsSpec.getPackedFormat(),
                        baseWidth, baseHeight);
                ARBFramebufferObject.glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT,
                        GL_RENDERBUFFER, depthRbo);

                int packedStatus = ARBFramebufferObject.glCheckFramebufferStatus(GL_FRAMEBUFFER);
                if (packedStatus != GL_FRAMEBUFFER_COMPLETE) {
                    LOGGER.warning("Packed depth-stencil (format 0x"
                            + Integer.toHexString(dsSpec.getPackedFormat())
                            + ") failed completeness (status 0x"
                            + Integer.toHexString(packedStatus)
                            + "), falling back to separate depth + stencil");

                    ARBFramebufferObject.glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT,
                            GL_RENDERBUFFER, 0);
                    ARBFramebufferObject.glDeleteRenderbuffers(depthRbo);
                    depthRbo = 0;

                    depthRbo = ARBFramebufferObject.glGenRenderbuffers();
                    ARBFramebufferObject.glBindRenderbuffer(GL_RENDERBUFFER, depthRbo);
                    ARBFramebufferObject.glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24,
                            baseWidth, baseHeight);
                    ARBFramebufferObject.glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT,
                            GL_RENDERBUFFER, depthRbo);

                    stencilRbo = ARBFramebufferObject.glGenRenderbuffers();
                    ARBFramebufferObject.glBindRenderbuffer(GL_RENDERBUFFER, stencilRbo);
                    ARBFramebufferObject.glRenderbufferStorage(GL_RENDERBUFFER, GL_STENCIL_INDEX8,
                            baseWidth, baseHeight);
                    ARBFramebufferObject.glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_STENCIL_ATTACHMENT,
                            GL_RENDERBUFFER, stencilRbo);
                }
            } else if (dsSpec.isDepthTexture() && dsSpec.hasDepth()) {
                depthTex = GL11.glGenTextures();
                GL11.glBindTexture(GL_TEXTURE_2D, depthTex);
                GL11.glTexImage2D(GL_TEXTURE_2D, 0,
                        dsSpec.getDepthFormat(), baseWidth, baseHeight, 0,
                        GL_DEPTH_COMPONENT, GL_UNSIGNED_BYTE, (ByteBuffer) null);
                GL11.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
                GL11.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
                ARBFramebufferObject.glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT,
                        GL_TEXTURE_2D, depthTex, 0);
            } else {
                if (dsSpec.hasDepth()) {
                    depthRbo = ARBFramebufferObject.glGenRenderbuffers();
                    ARBFramebufferObject.glBindRenderbuffer(GL_RENDERBUFFER, depthRbo);
                    ARBFramebufferObject.glRenderbufferStorage(GL_RENDERBUFFER, dsSpec.getDepthFormat(),
                            baseWidth, baseHeight);
                    ARBFramebufferObject.glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT,
                            GL_RENDERBUFFER, depthRbo);
                }
                if (dsSpec.hasStencil()) {
                    stencilRbo = ARBFramebufferObject.glGenRenderbuffers();
                    ARBFramebufferObject.glBindRenderbuffer(GL_RENDERBUFFER, stencilRbo);
                    ARBFramebufferObject.glRenderbufferStorage(GL_RENDERBUFFER, dsSpec.getStencilFormat(),
                            baseWidth, baseHeight);
                    ARBFramebufferObject.glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_STENCIL_ATTACHMENT,
                            GL_RENDERBUFFER, stencilRbo);
                }
            }

            // ── Completeness check ──────────────────────────────────
            int status = ARBFramebufferObject.glCheckFramebufferStatus(GL_FRAMEBUFFER);
            if (status != GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException(
                        "ARB framebuffer (spec-based) is not complete. Status: 0x"
                        + Integer.toHexString(status));
            }

            ARBFramebufferObject.glBindFramebuffer(GL_FRAMEBUFFER, 0);

            return new ArbFramebuffer(fbo, colorTexIds, depthRbo, stencilRbo,
                    depthTex, baseWidth, baseHeight, mrt, spec);

        } catch (RuntimeException e) {
            cleanupOnFailure(fbo, colorTexIds, depthRbo, stencilRbo, depthTex);
            ARBFramebufferObject.glBindFramebuffer(GL_FRAMEBUFFER, 0);
            throw e;
        }
    }

    /**
     * Cleans up all GL resources allocated during a failed framebuffer creation.
     *
     * @param fbo         the FBO ID to delete
     * @param colorTexIds array of color texture IDs to delete (0 entries are skipped)
     * @param depthRbo    depth renderbuffer ID (0 if none)
     * @param stencilRbo  stencil renderbuffer ID (0 if none)
     */
    private static void cleanupOnFailure(int fbo, int[] colorTexIds,
                                         int depthRbo, int stencilRbo,
                                         int depthTex) {
        ARBFramebufferObject.glDeleteFramebuffers(fbo);
        for (int texId : colorTexIds) {
            if (texId != 0) {
                GL11.glDeleteTextures(texId);
            }
        }
        if (depthRbo != 0) {
            ARBFramebufferObject.glDeleteRenderbuffers(depthRbo);
        }
        if (stencilRbo != 0) {
            ARBFramebufferObject.glDeleteRenderbuffers(stencilRbo);
        }
        if (depthTex != 0) {
            GL11.glDeleteTextures(depthTex);
        }
    }

    // ── CallFamily ─────────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * @return {@link CallFamily#ARB_FBO}
     */
    @Override
    protected CallFamily callFamily() {
        return CallFamily.ARB_FBO;
    }

    // ── getColorTextureId ─────────────────────────────────────────────

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
        if (colorTextureIds != null) {
            // Spec-based path: use the array
            if (attachmentIndex < 0 || attachmentIndex >= colorTextureIds.length) {
                throw new IndexOutOfBoundsException(
                        "Attachment index " + attachmentIndex + " out of range [0, "
                        + colorTextureIds.length + ")");
            }
            return colorTextureIds[attachmentIndex];
        } else {
            // Legacy path: only index 0 is valid
            if (attachmentIndex != 0) {
                throw new IndexOutOfBoundsException(
                        "Legacy framebuffer only has 1 color attachment, index: "
                        + attachmentIndex);
            }
            return colorTextureId;
        }
    }

    @Override
    public int getDepthTextureId() {
        if (depthTextureId == 0) {
            throw new UnsupportedOperationException(
                    "This framebuffer does not have a depth texture attachment. "
                    + "Depth texture is only available when created with CgDepthStencilSpec.depthOnlyTexture().");
        }
        return depthTextureId;
    }

    // ── Runtime attachments ───────────────────────────────────────────

    @Override
    public CgRuntimeAttachments getRuntimeAttachments() {
        if (runtimeAttachments == null) {
            runtimeAttachments = new RuntimeAttachmentsImpl();
        }
        return runtimeAttachments;
    }

    private int getSpecColorCount() {
        if (colorTextureIds != null) {
            return colorTextureIds.length;
        }
        return 1;
    }

    private void validateSlot(int slot) {
        if (slot < 0) {
            throw new IllegalArgumentException("Slot index must not be negative: " + slot);
        }
        int specCount = getSpecColorCount();
        if (slot < specCount) {
            throw new IllegalArgumentException(
                    "Slot " + slot + " is reserved for managed spec attachments (colorAttachmentCount=" + specCount + ")");
        }
    }

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

            ARBFramebufferObject.glBindFramebuffer(GL_FRAMEBUFFER, fboId);
            ARBFramebufferObject.glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0 + slot,
                    GL_TEXTURE_2D, textureId, 0);
            ARBFramebufferObject.glBindFramebuffer(GL_FRAMEBUFFER, 0);
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

            ARBFramebufferObject.glBindFramebuffer(GL_FRAMEBUFFER, fboId);
            ARBFramebufferObject.glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0 + slot,
                    GL_TEXTURE_2D, texId, 0);
            ARBFramebufferObject.glBindFramebuffer(GL_FRAMEBUFFER, 0);
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

            ARBFramebufferObject.glBindFramebuffer(GL_FRAMEBUFFER, fboId);
            ARBFramebufferObject.glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0 + slot,
                    GL_TEXTURE_2D, 0, 0);
            ARBFramebufferObject.glBindFramebuffer(GL_FRAMEBUFFER, 0);
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

    // ── drawBuffers (MRT via GL20) ─────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p>Sets the draw buffers using {@link GL20#glDrawBuffers(IntBuffer)}.
     * The {@code drawBuffers} function is part of GL 2.0 and is independent
     * of the FBO extension, so both Core and ARB paths use the same call.</p>
     *
     * @param attachments the color attachment constants
     * @throws UnsupportedOperationException if MRT is not supported
     * @throws IllegalArgumentException      if attachments is empty
     */
    @Override
    public void drawBuffers(int... attachments) {
        if (!supportsMrt) {
            throw new UnsupportedOperationException(
                    "This ArbFramebuffer does not support MRT. "
                    + "Ensure mrt=true was passed at creation and the hardware supports it.");
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

    // ── Resource cleanup ───────────────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p>Deletes the FBO, all color textures, and all depth/stencil
     * renderbuffers using ARB entry points.</p>
     */
    @Override
    protected void freeGlResources() {
        for (RuntimeSlot rs : runtimeSlots.values()) {
            if (rs.managed) {
                GL11.glDeleteTextures(rs.textureId);
            }
        }
        runtimeSlots.clear();

        ARBFramebufferObject.glDeleteFramebuffers(fboId);

        if (colorTextureIds != null) {
            // Spec-based path: delete all color textures
            for (int texId : colorTextureIds) {
                if (texId != 0) {
                    GL11.glDeleteTextures(texId);
                }
            }
        } else {
            // Legacy path: single color texture
            if (colorTextureId != 0) {
                GL11.glDeleteTextures(colorTextureId);
            }
        }

        if (depthRenderbufferId != 0) {
            ARBFramebufferObject.glDeleteRenderbuffers(depthRenderbufferId);
        }
        if (stencilRenderbufferId != 0) {
            ARBFramebufferObject.glDeleteRenderbuffers(stencilRenderbufferId);
        }
        if (depthTextureId != 0) {
            GL11.glDeleteTextures(depthTextureId);
        }
    }

    // ── Resize ─────────────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p>Recreates all GL resources at the new dimensions.  The FBO ID will
     * change after this call.  The old resources are freed first.</p>
     *
     * <p>For spec-based framebuffers, the new base dimensions are used with
     * the original spec's scale factors, formats, and depth/stencil config.</p>
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
     */
    private void resizeLegacy(int newWidth, int newHeight) {
        Map<Integer, RuntimeSlot> savedSlots = new HashMap<Integer, RuntimeSlot>(runtimeSlots);
        freeGlResources();

        // Recreate
        int newFbo = ARBFramebufferObject.glGenFramebuffers();
        ARBFramebufferObject.glBindFramebuffer(GL_FRAMEBUFFER, newFbo);

        int newColorTex = GL11.glGenTextures();
        GL11.glBindTexture(GL_TEXTURE_2D, newColorTex);
        GL11.glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, newWidth, newHeight, 0,
                GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GL11.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        GL11.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        ARBFramebufferObject.glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                GL_TEXTURE_2D, newColorTex, 0);

        int newDepthRbo = 0;
        if (hasDepth) {
            newDepthRbo = ARBFramebufferObject.glGenRenderbuffers();
            ARBFramebufferObject.glBindRenderbuffer(GL_RENDERBUFFER, newDepthRbo);
            ARBFramebufferObject.glRenderbufferStorage(GL_RENDERBUFFER,
                    GL_DEPTH_COMPONENT24, newWidth, newHeight);
            ARBFramebufferObject.glFramebufferRenderbuffer(GL_FRAMEBUFFER,
                    GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, newDepthRbo);
        }

        int status = ARBFramebufferObject.glCheckFramebufferStatus(GL_FRAMEBUFFER);
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            ARBFramebufferObject.glDeleteFramebuffers(newFbo);
            GL11.glDeleteTextures(newColorTex);
            if (newDepthRbo != 0) {
                ARBFramebufferObject.glDeleteRenderbuffers(newDepthRbo);
            }
            throw new IllegalStateException(
                    "ARB framebuffer resize failed. Status: 0x"
                    + Integer.toHexString(status));
        }

        ARBFramebufferObject.glBindFramebuffer(GL_FRAMEBUFFER, 0);

        this.fboId = newFbo;
        this.colorTextureId = newColorTex;
        this.depthRenderbufferId = newDepthRbo;
        this.width = newWidth;
        this.height = newHeight;

        runtimeSlots.putAll(savedSlots);
        resizeRuntimeSlots();
    }

    /**
     * Resizes the framebuffer using the spec-based multi-attachment path.
     *
     * <p>Builds a new spec with the updated base dimensions and delegates
     * to the same allocation logic as {@link #createFromSpec(CgFramebufferSpec)}.
     * On success, the old resources are freed and instance state is updated.
     * On failure, the old resources remain intact.</p>
     */
    private void resizeSpec(int newWidth, int newHeight) {
        CgFramebufferSpec.Builder builder = CgFramebufferSpec.builder()
                .baseDimensions(newWidth, newHeight)
                .depthStencil(spec.getDepthStencil());
        for (CgColorAttachmentSpec att : spec.getColorAttachments()) {
            builder.addColorAttachment(att);
        }
        CgFramebufferSpec newSpec = builder.build();

        ArbFramebuffer replacement = createFromSpec(newSpec);

        Map<Integer, RuntimeSlot> savedSlots = new HashMap<Integer, RuntimeSlot>(runtimeSlots);
        freeGlResources();

        this.fboId = replacement.fboId;
        this.colorTextureIds = replacement.colorTextureIds;
        this.colorTextureId = replacement.colorTextureId;
        this.depthRenderbufferId = replacement.depthRenderbufferId;
        this.stencilRenderbufferId = replacement.stencilRenderbufferId;
        this.depthTextureId = replacement.depthTextureId;
        this.width = newWidth;
        this.height = newHeight;
        this.spec = newSpec;

        ALL_OWNED.remove(replacement);
        replacement.deleted = true;

        runtimeSlots.putAll(savedSlots);
        resizeRuntimeSlots();
    }

    private void resizeRuntimeSlots() {
        if (runtimeSlots.isEmpty()) {
            return;
        }

        ARBFramebufferObject.glBindFramebuffer(GL_FRAMEBUFFER, fboId);
        for (Map.Entry<Integer, RuntimeSlot> entry : runtimeSlots.entrySet()) {
            int slot = entry.getKey();
            RuntimeSlot rs = entry.getValue();

            if (rs.managed) {
                int newTexId = rs.provider.allocate(width, height, rs.format);
                GL11.glDeleteTextures(rs.textureId);
                rs.textureId = newTexId;
            }

            ARBFramebufferObject.glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0 + slot,
                    GL_TEXTURE_2D, rs.textureId, 0);
        }
        ARBFramebufferObject.glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }
}
