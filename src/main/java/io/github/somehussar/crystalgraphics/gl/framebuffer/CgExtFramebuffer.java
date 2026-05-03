package io.github.somehussar.crystalgraphics.gl.framebuffer;

import io.github.somehussar.crystalgraphics.api.framebuffer.CgColorAttachmentProvider;
import io.github.somehussar.crystalgraphics.api.framebuffer.CgColorAttachmentSpec;
import io.github.somehussar.crystalgraphics.api.framebuffer.CgDepthStencilSpec;
import io.github.somehussar.crystalgraphics.api.framebuffer.CgFramebufferSpec;
import io.github.somehussar.crystalgraphics.api.CgMipmapConfig;
import io.github.somehussar.crystalgraphics.api.framebuffer.CgRuntimeAttachments;
import io.github.somehussar.crystalgraphics.api.framebuffer.CgTextureFormatSpec;
import io.github.somehussar.crystalgraphics.api.texture.CgTextureSpec;
import io.github.somehussar.crystalgraphics.gl.CrossApiTransition;
import io.github.somehussar.crystalgraphics.gl.state.CallFamily;
import io.github.somehussar.crystalgraphics.gl.texture.CgTexture2D;

import org.lwjgl.opengl.EXTFramebufferObject;
import org.lwjgl.opengl.GL11;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Framebuffer implementation using the legacy {@code EXT_framebuffer_object}
 * extension.
 *
 * <p>This is the fallback backend for hardware that supports neither Core GL30
 * nor {@code ARB_framebuffer_object}.  It uses the {@code *EXT}-suffixed LWJGL
 * methods ({@link EXTFramebufferObject#glGenFramebuffersEXT()},
 * {@link EXTFramebufferObject#glBindFramebufferEXT(int, int)}, etc.).</p>
 *
 * <h3>EXT-Specific Limitations</h3>
 * <ul>
 *   <li><strong>No separate draw/read targets</strong>: The EXT extension does
 *       not define {@code GL_DRAW_FRAMEBUFFER} or {@code GL_READ_FRAMEBUFFER}.
 *       All three binding methods ({@link #bind()}, {@link #bindDraw()},
 *       {@link #bindRead()}) bind using {@code GL_FRAMEBUFFER_EXT}.</li>
 *   <li><strong>No MRT</strong>: {@code EXT_framebuffer_object} does not
 *       include draw-buffer selection.  {@link #drawBuffers(int...)} always
 *       throws {@link UnsupportedOperationException}.</li>
 *   <li><strong>No GL_DEPTH_STENCIL_ATTACHMENT</strong>: For packed
 *       depth-stencil, the same renderbuffer must be attached to both
 *       {@code GL_DEPTH_ATTACHMENT_EXT} and {@code GL_STENCIL_ATTACHMENT_EXT}
 *       separately.</li>
 * </ul>
 *
 * <h3>Thread Safety</h3>
 * <p>Not thread-safe.  Must only be used from the GL context thread.</p>
 *
 * @see CgAbstractFramebuffer
 * @see CgCoreFramebuffer
 * @see CgArbFramebuffer
 */
public final class CgExtFramebuffer extends CgAbstractFramebuffer {

    private static final Logger LOGGER = Logger.getLogger(CgExtFramebuffer.class.getName());

    // ── GL constants (EXT uses same numeric values but different names) ─

    /** {@code GL_FRAMEBUFFER_EXT} target (same value as GL_FRAMEBUFFER: 0x8D40). */
    private static final int GL_FRAMEBUFFER_EXT = 0x8D40;

    /** {@code GL_RENDERBUFFER_EXT} target. */
    private static final int GL_RENDERBUFFER_EXT = 0x8D41;

    /** {@code GL_DEPTH_COMPONENT24} internal format (shared across all paths). */
    private static final int GL_DEPTH_COMPONENT24 = 0x81A6;

    /** {@code GL_DEPTH_ATTACHMENT_EXT} attachment point. */
    private static final int GL_DEPTH_ATTACHMENT_EXT = 0x8D00;

    /** {@code GL_STENCIL_ATTACHMENT_EXT} attachment point. */
    private static final int GL_STENCIL_ATTACHMENT_EXT = 0x8D20;

    /** {@code GL_COLOR_ATTACHMENT0_EXT} attachment point. */
    private static final int GL_COLOR_ATTACHMENT0_EXT = 0x8CE0;

    /** {@code GL_TEXTURE_2D} target. */
    private static final int GL_TEXTURE_2D = 0x0DE1;

    /** {@code GL_FRAMEBUFFER_COMPLETE_EXT} status. */
    private static final int GL_FRAMEBUFFER_COMPLETE_EXT = 0x8CD5;

    /** {@code GL_STENCIL_INDEX8} internal format. */
    private static final int GL_STENCIL_INDEX8 = 0x8D48;

    /** {@code GL_DEPTH_COMPONENT} pixel format for depth texture allocation. */
    private static final int GL_DEPTH_COMPONENT = 0x1902;

    /** {@code GL_UNSIGNED_BYTE} pixel type (used in depth texture spec). */
    private static final int GL_UNSIGNED_BYTE = 0x1401;

    /** {@code GL_NEAREST} filter value (appropriate for depth textures). */
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
     * Owned color textures (spec-based path). {@code null} for legacy path.
     * The inherited {@code colorTextureId} int is kept in sync via {@code .getId()}.
     */
    private CgTexture2D[] colorTextures;

    /**
     * Single owned color texture (legacy path). {@code null} for spec-based path.
     * The inherited {@code colorTextureId} int is kept in sync via {@code .getId()}.
     */
    private CgTexture2D singleColorTexture;

    /**
     * Owned depth texture, or {@code null} if depth uses a renderbuffer or is absent.
     */
    private CgTexture2D depthTexture;

    /**
     * Renderbuffer ID for the stencil attachment, or 0 if none.
     * For packed depth-stencil, set to the same value as {@code depthRenderbufferId};
     * {@code freeGlResources()} only deletes it once by checking inequality.
     */
    private int stencilRenderbufferId;

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
     * Creates an CgExtFramebuffer with pre-existing GL resource IDs (legacy path).
     */
    private CgExtFramebuffer(int fboId, CgTexture2D colorTexture, int depthRenderbufferId,
                           int width, int height, boolean hasDepth) {
        super(fboId, width, height, true, false); // EXT never supports MRT
        this.singleColorTexture = colorTexture;
        this.colorTextureId = colorTexture.getId();
        this.depthRenderbufferId = depthRenderbufferId;
        this.hasDepth = hasDepth;
        this.spec = null;
        this.colorTextures = null;
        this.stencilRenderbufferId = 0;
    }

    /**
     * Creates an CgExtFramebuffer with spec-based single-attachment state.
     */
    private CgExtFramebuffer(int fboId, CgTexture2D[] colorTextures,
                           int depthRenderbufferId, int stencilRenderbufferId,
                           CgTexture2D depthTexture,
                           int width, int height, CgFramebufferSpec spec) {
        super(fboId, width, height, true, false);
        this.colorTextures = colorTextures;
        this.colorTextureId = colorTextures.length > 0 ? colorTextures[0].getId() : 0;
        this.depthRenderbufferId = depthRenderbufferId;
        this.stencilRenderbufferId = stencilRenderbufferId;
        this.depthTexture = depthTexture;
        this.hasDepth = false;
        this.spec = spec;
    }

    /**
     * Package-private constructor for wrapping an externally-created FBO.
     *
     * <p>The wrapped framebuffer is non-owned and cannot be deleted.</p>
     */
    CgExtFramebuffer(int fboId, int width, int height) {
        super(fboId, width, height, false, false); // EXT never supports MRT
        this.hasDepth = false;
        this.spec = null;
        this.colorTextures = null;
        this.stencilRenderbufferId = 0;
    }

    // ── Private spec helpers ──────────────────────────────────────────

    /**
     * Builds a {@link CgTextureSpec} from an attachment's format + mipmap config.
     */
    private static CgTextureSpec specFrom(CgTextureFormatSpec fmt, CgMipmapConfig mips) {
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
    private static CgTextureSpec depthSpecFrom(int depthInternalFormat) {
        return CgTextureSpec.builder()
                .format(new CgTextureFormatSpec(depthInternalFormat, GL_DEPTH_COMPONENT, GL_UNSIGNED_BYTE))
                .minFilter(GL_NEAREST)
                .magFilter(GL_NEAREST)
                .build();
    }

    // ── Factory method ─────────────────────────────────────────────────

    /**
     * Creates a new EXT framebuffer with a color texture attachment and
     * optional depth renderbuffer.
     *
     * <p>MRT is never supported via the EXT path; if {@code mrt} is
     * {@code true}, this method throws {@link UnsupportedOperationException}.</p>
     *
     * @param width  width in pixels (must be &gt; 0)
     * @param height height in pixels (must be &gt; 0)
     * @param depth  whether to attach a depth renderbuffer
     * @param mrt    must be {@code false} — EXT does not support MRT
     * @return a new owned {@code CgExtFramebuffer}
     * @throws UnsupportedOperationException if {@code mrt} is {@code true}
     * @throws IllegalStateException         if the framebuffer is not complete
     * @throws IllegalArgumentException      if width or height is not positive
     */
    public static CgExtFramebuffer create(int width, int height, boolean depth, boolean mrt) {
        if (mrt) {
            throw new UnsupportedOperationException(
                    "EXT_framebuffer_object does not support MRT. "
                    + "Use Core GL30 or ARB_framebuffer_object.");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                    "Framebuffer dimensions must be positive: " + width + "x" + height);
        }

        int fbo = EXTFramebufferObject.glGenFramebuffersEXT();
        EXTFramebufferObject.glBindFramebufferEXT(GL_FRAMEBUFFER_EXT, fbo);

        // Color texture — allocate via texture infrastructure
        CgTexture2D colorTex = CgTexture2D.createEmpty(width, height, CgTextureSpec.RGBA8_LINEAR);
        EXTFramebufferObject.glFramebufferTexture2DEXT(GL_FRAMEBUFFER_EXT,
                GL_COLOR_ATTACHMENT0_EXT, GL_TEXTURE_2D, colorTex.getId(), 0);

        // Optional depth renderbuffer
        int depthRbo = 0;
        if (depth) {
            depthRbo = EXTFramebufferObject.glGenRenderbuffersEXT();
            EXTFramebufferObject.glBindRenderbufferEXT(GL_RENDERBUFFER_EXT, depthRbo);
            EXTFramebufferObject.glRenderbufferStorageEXT(GL_RENDERBUFFER_EXT,
                    GL_DEPTH_COMPONENT24, width, height);
            EXTFramebufferObject.glFramebufferRenderbufferEXT(GL_FRAMEBUFFER_EXT,
                    GL_DEPTH_ATTACHMENT_EXT, GL_RENDERBUFFER_EXT, depthRbo);
        }

        // Completeness check
        int status = EXTFramebufferObject.glCheckFramebufferStatusEXT(GL_FRAMEBUFFER_EXT);
        if (status != GL_FRAMEBUFFER_COMPLETE_EXT) {
            EXTFramebufferObject.glDeleteFramebuffersEXT(fbo);
            colorTex.delete();
            if (depthRbo != 0) {
                EXTFramebufferObject.glDeleteRenderbuffersEXT(depthRbo);
            }
            throw new IllegalStateException(
                    "EXT framebuffer is not complete. Status: 0x"
                    + Integer.toHexString(status));
        }

        EXTFramebufferObject.glBindFramebufferEXT(GL_FRAMEBUFFER_EXT, 0);

        return new CgExtFramebuffer(fbo, colorTex, depthRbo, width, height, depth);
    }

    // ── Factory method (spec-based) ───────────────────────────────────

    /**
     * Creates a new EXT framebuffer from a detailed specification.
     *
     * <p>EXT does not support MRT; if the specification contains more than one
     * color attachment, this method throws {@link UnsupportedOperationException}.
     * For packed depth-stencil, EXT has no {@code GL_DEPTH_STENCIL_ATTACHMENT} —
     * a single renderbuffer is attached to both {@code GL_DEPTH_ATTACHMENT_EXT}
     * and {@code GL_STENCIL_ATTACHMENT_EXT} separately (vanilla MC pattern).</p>
     *
     * @param spec the framebuffer specification
     * @return a new owned {@code CgExtFramebuffer}
     * @throws IllegalArgumentException      if spec is null
     * @throws UnsupportedOperationException if the spec has more than 1 color attachment
     * @throws IllegalStateException         if the framebuffer is not complete after setup
     */
    public static CgExtFramebuffer createFromSpec(CgFramebufferSpec spec) {
        if (spec == null) {
            throw new IllegalArgumentException("Spec must not be null");
        }

        List<CgColorAttachmentSpec> attachments = spec.getColorAttachments();
        if (attachments.size() > 1) {
            throw new UnsupportedOperationException(
                    "EXT_framebuffer_object does not support MRT (Multiple Render Targets). "
                    + "Use Core GL30 or ARB backend.");
        }

        int baseWidth = spec.getBaseWidth();
        int baseHeight = spec.getBaseHeight();
        CgDepthStencilSpec dsSpec = spec.getDepthStencil();
        int colorCount = attachments.size();

        int fbo = EXTFramebufferObject.glGenFramebuffersEXT();
        EXTFramebufferObject.glBindFramebufferEXT(GL_FRAMEBUFFER_EXT, fbo);

        CgTexture2D[] colorTextures = new CgTexture2D[colorCount];
        int depthRbo = 0;
        int stencilRbo = 0;
        CgTexture2D depthTex = null;

        try {
            // ── Allocate color texture (single attachment for EXT) ───
            for (int i = 0; i < colorCount; i++) {
                CgColorAttachmentSpec att = attachments.get(i);
                CgTextureFormatSpec fmt = att.getFormat();
                CgMipmapConfig mips = att.getMipmaps();

                int attWidth = Math.max(1, (int) Math.ceil(baseWidth * att.getScaleX()));
                int attHeight = Math.max(1, (int) Math.ceil(baseHeight * att.getScaleY()));

                CgTexture2D tex = CgTexture2D.createEmpty(attWidth, attHeight, specFrom(fmt, mips));
                colorTextures[i] = tex;
                EXTFramebufferObject.glFramebufferTexture2DEXT(GL_FRAMEBUFFER_EXT,
                        GL_COLOR_ATTACHMENT0_EXT + i, GL_TEXTURE_2D, tex.getId(), 0);
            }

            // ── Allocate depth/stencil ─────────────────────────────
            if (dsSpec.isPacked()) {
                depthRbo = EXTFramebufferObject.glGenRenderbuffersEXT();
                EXTFramebufferObject.glBindRenderbufferEXT(GL_RENDERBUFFER_EXT, depthRbo);
                EXTFramebufferObject.glRenderbufferStorageEXT(GL_RENDERBUFFER_EXT,
                        dsSpec.getPackedFormat(), baseWidth, baseHeight);
                // EXT has no GL_DEPTH_STENCIL_ATTACHMENT; attach to both separately
                EXTFramebufferObject.glFramebufferRenderbufferEXT(GL_FRAMEBUFFER_EXT,
                        GL_DEPTH_ATTACHMENT_EXT, GL_RENDERBUFFER_EXT, depthRbo);
                EXTFramebufferObject.glFramebufferRenderbufferEXT(GL_FRAMEBUFFER_EXT,
                        GL_STENCIL_ATTACHMENT_EXT, GL_RENDERBUFFER_EXT, depthRbo);
                stencilRbo = depthRbo;
            } else if (dsSpec.isDepthTexture() && dsSpec.hasDepth()) {
                depthTex = CgTexture2D.createEmpty(baseWidth, baseHeight,
                        depthSpecFrom(dsSpec.getDepthFormat()));
                EXTFramebufferObject.glFramebufferTexture2DEXT(GL_FRAMEBUFFER_EXT,
                        GL_DEPTH_ATTACHMENT_EXT, GL_TEXTURE_2D, depthTex.getId(), 0);
            } else {
                if (dsSpec.hasDepth()) {
                    depthRbo = EXTFramebufferObject.glGenRenderbuffersEXT();
                    EXTFramebufferObject.glBindRenderbufferEXT(GL_RENDERBUFFER_EXT, depthRbo);
                    EXTFramebufferObject.glRenderbufferStorageEXT(GL_RENDERBUFFER_EXT,
                            dsSpec.getDepthFormat(), baseWidth, baseHeight);
                    EXTFramebufferObject.glFramebufferRenderbufferEXT(GL_FRAMEBUFFER_EXT,
                            GL_DEPTH_ATTACHMENT_EXT, GL_RENDERBUFFER_EXT, depthRbo);
                }
                if (dsSpec.hasStencil()) {
                    stencilRbo = EXTFramebufferObject.glGenRenderbuffersEXT();
                    EXTFramebufferObject.glBindRenderbufferEXT(GL_RENDERBUFFER_EXT, stencilRbo);
                    EXTFramebufferObject.glRenderbufferStorageEXT(GL_RENDERBUFFER_EXT,
                            dsSpec.getStencilFormat(), baseWidth, baseHeight);
                    EXTFramebufferObject.glFramebufferRenderbufferEXT(GL_FRAMEBUFFER_EXT,
                            GL_STENCIL_ATTACHMENT_EXT, GL_RENDERBUFFER_EXT, stencilRbo);
                }
            }

            int status = EXTFramebufferObject.glCheckFramebufferStatusEXT(GL_FRAMEBUFFER_EXT);
            if (status != GL_FRAMEBUFFER_COMPLETE_EXT) {
                throw new IllegalStateException(
                        "EXT framebuffer (spec-based) is not complete. Status: 0x"
                        + Integer.toHexString(status));
            }

            EXTFramebufferObject.glBindFramebufferEXT(GL_FRAMEBUFFER_EXT, 0);

            return new CgExtFramebuffer(fbo, colorTextures, depthRbo, stencilRbo,
                    depthTex, baseWidth, baseHeight, spec);

        } catch (RuntimeException e) {
            cleanupOnFailure(fbo, colorTextures, depthRbo, stencilRbo, depthTex);
            EXTFramebufferObject.glBindFramebufferEXT(GL_FRAMEBUFFER_EXT, 0);
            throw e;
        }
    }

    /**
     * Cleans up all GL resources allocated during a failed framebuffer creation.
     *
     * <p>For packed depth-stencil, {@code stencilRbo == depthRbo}; the check
     * {@code stencilRbo != depthRbo} prevents a double-delete.</p>
     */
    private static void cleanupOnFailure(int fbo, CgTexture2D[] colorTextures,
                                         int depthRbo, int stencilRbo,
                                         CgTexture2D depthTex) {
        EXTFramebufferObject.glDeleteFramebuffersEXT(fbo);
        for (CgTexture2D tex : colorTextures) {
            if (tex != null) {
                tex.delete();
            }
        }
        if (depthRbo != 0) {
            EXTFramebufferObject.glDeleteRenderbuffersEXT(depthRbo);
        }
        if (stencilRbo != 0 && stencilRbo != depthRbo) {
            EXTFramebufferObject.glDeleteRenderbuffersEXT(stencilRbo);
        }
        if (depthTex != null) {
            depthTex.delete();
        }
    }

    // ── CallFamily ─────────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * @return {@link CallFamily#EXT_FBO}
     */
    @Override
    protected CallFamily callFamily() {
        return CallFamily.EXT_FBO;
    }

    // ── getColorTextureId ─────────────────────────────────────────────

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

    @Override
    public int getDepthTextureId() {
        if (depthTexture == null) {
            throw new UnsupportedOperationException(
                    "This framebuffer does not have a depth texture attachment. "
                    + "Depth texture is only available when created with CgDepthStencilSpec.depthOnlyTexture().");
        }
        return depthTexture.getId();
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
        if (colorTextures != null) {
            return colorTextures.length;
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

            EXTFramebufferObject.glBindFramebufferEXT(GL_FRAMEBUFFER_EXT, fboId);
            EXTFramebufferObject.glFramebufferTexture2DEXT(GL_FRAMEBUFFER_EXT,
                    GL_COLOR_ATTACHMENT0_EXT + slot, GL_TEXTURE_2D, textureId, 0);
            EXTFramebufferObject.glBindFramebufferEXT(GL_FRAMEBUFFER_EXT, 0);
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

            EXTFramebufferObject.glBindFramebufferEXT(GL_FRAMEBUFFER_EXT, fboId);
            EXTFramebufferObject.glFramebufferTexture2DEXT(GL_FRAMEBUFFER_EXT,
                    GL_COLOR_ATTACHMENT0_EXT + slot, GL_TEXTURE_2D, texId, 0);
            EXTFramebufferObject.glBindFramebufferEXT(GL_FRAMEBUFFER_EXT, 0);
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

            EXTFramebufferObject.glBindFramebufferEXT(GL_FRAMEBUFFER_EXT, fboId);
            EXTFramebufferObject.glFramebufferTexture2DEXT(GL_FRAMEBUFFER_EXT,
                    GL_COLOR_ATTACHMENT0_EXT + slot, GL_TEXTURE_2D, 0, 0);
            EXTFramebufferObject.glBindFramebufferEXT(GL_FRAMEBUFFER_EXT, 0);
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

    // ── Binding overrides (EXT has no separate draw/read) ──────────────

    /**
     * {@inheritDoc}
     *
     * <p>Binds this FBO using {@code GL_FRAMEBUFFER_EXT}.  The EXT extension
     * does not support separate draw and read framebuffer targets.</p>
     */
    @Override
    public void bind() {
        CrossApiTransition.bindFramebuffer(GL_FRAMEBUFFER_EXT, fboId, callFamily());
    }

    /**
     * {@inheritDoc}
     *
     * <p><strong>EXT limitation</strong>: Separate draw/read targets are not
     * supported.  This method binds using {@code GL_FRAMEBUFFER_EXT}
     * (same as {@link #bind()}).</p>
     */
    @Override
    public void bindDraw() {
        CrossApiTransition.bindFramebuffer(GL_FRAMEBUFFER_EXT, fboId, callFamily());
    }

    /**
     * {@inheritDoc}
     *
     * <p><strong>EXT limitation</strong>: Separate draw/read targets are not
     * supported.  This method binds using {@code GL_FRAMEBUFFER_EXT}
     * (same as {@link #bind()}).</p>
     */
    @Override
    public void bindRead() {
        CrossApiTransition.bindFramebuffer(GL_FRAMEBUFFER_EXT, fboId, callFamily());
    }

    /**
     * {@inheritDoc}
     *
     * <p>Unbinds by binding framebuffer 0 using {@code GL_FRAMEBUFFER_EXT}.</p>
     */
    @Override
    public void unbind() {
        CrossApiTransition.bindFramebuffer(GL_FRAMEBUFFER_EXT, 0, callFamily());
    }

    // ── drawBuffers (always unsupported for EXT) ───────────────────────

    /**
     * Always throws {@link UnsupportedOperationException}.
     *
     * <p>{@code EXT_framebuffer_object} does not include draw-buffer
     * selection.  MRT requires Core GL30 or {@code ARB_framebuffer_object}.</p>
     *
     * @param attachments ignored
     * @throws UnsupportedOperationException always
     */
    @Override
    public void drawBuffers(int... attachments) {
        throw new UnsupportedOperationException(
                "EXT_framebuffer_object does not support MRT. "
                + "Use Core GL30 or ARB_framebuffer_object.");
    }

    // ── Resource cleanup ───────────────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p>Deletes the FBO using {@link EXTFramebufferObject#glDeleteFramebuffersEXT(int)},
     * all color textures, and the depth renderbuffer (if present) via EXT calls.</p>
     */
    @Override
    protected void freeGlResources() {
        for (RuntimeSlot rs : runtimeSlots.values()) {
            if (rs.managed) {
                GL11.glDeleteTextures(rs.textureId);
            }
        }
        runtimeSlots.clear();

        EXTFramebufferObject.glDeleteFramebuffersEXT(fboId);

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
            EXTFramebufferObject.glDeleteRenderbuffersEXT(depthRenderbufferId);
        }
        if (stencilRenderbufferId != 0 && stencilRenderbufferId != depthRenderbufferId) {
            EXTFramebufferObject.glDeleteRenderbuffersEXT(stencilRenderbufferId);
        }
        if (depthTexture != null) {
            depthTexture.delete();
        }
    }

    // ── Resize ─────────────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p>Recreates all GL resources at the new dimensions using EXT entry points.</p>
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

    private void resizeLegacy(int newWidth, int newHeight) {
        Map<Integer, RuntimeSlot> savedSlots = new HashMap<Integer, RuntimeSlot>(runtimeSlots);
        freeGlResources();

        int newFbo = EXTFramebufferObject.glGenFramebuffersEXT();
        EXTFramebufferObject.glBindFramebufferEXT(GL_FRAMEBUFFER_EXT, newFbo);

        CgTexture2D newColorTex = CgTexture2D.createEmpty(newWidth, newHeight, CgTextureSpec.RGBA8_LINEAR);
        EXTFramebufferObject.glFramebufferTexture2DEXT(GL_FRAMEBUFFER_EXT,
                GL_COLOR_ATTACHMENT0_EXT, GL_TEXTURE_2D, newColorTex.getId(), 0);

        int newDepthRbo = 0;
        if (hasDepth) {
            newDepthRbo = EXTFramebufferObject.glGenRenderbuffersEXT();
            EXTFramebufferObject.glBindRenderbufferEXT(GL_RENDERBUFFER_EXT, newDepthRbo);
            EXTFramebufferObject.glRenderbufferStorageEXT(GL_RENDERBUFFER_EXT,
                    GL_DEPTH_COMPONENT24, newWidth, newHeight);
            EXTFramebufferObject.glFramebufferRenderbufferEXT(GL_FRAMEBUFFER_EXT,
                    GL_DEPTH_ATTACHMENT_EXT, GL_RENDERBUFFER_EXT, newDepthRbo);
        }

        int status = EXTFramebufferObject.glCheckFramebufferStatusEXT(GL_FRAMEBUFFER_EXT);
        if (status != GL_FRAMEBUFFER_COMPLETE_EXT) {
            EXTFramebufferObject.glDeleteFramebuffersEXT(newFbo);
            newColorTex.delete();
            if (newDepthRbo != 0) {
                EXTFramebufferObject.glDeleteRenderbuffersEXT(newDepthRbo);
            }
            throw new IllegalStateException(
                    "EXT framebuffer resize failed. Status: 0x"
                    + Integer.toHexString(status));
        }

        EXTFramebufferObject.glBindFramebufferEXT(GL_FRAMEBUFFER_EXT, 0);

        this.fboId = newFbo;
        this.singleColorTexture = newColorTex;
        this.colorTextureId = newColorTex.getId();
        this.depthRenderbufferId = newDepthRbo;
        this.width = newWidth;
        this.height = newHeight;

        runtimeSlots.putAll(savedSlots);
        resizeRuntimeSlots();
    }

    private void resizeSpec(int newWidth, int newHeight) {
        CgFramebufferSpec.Builder builder = CgFramebufferSpec.builder()
                .baseDimensions(newWidth, newHeight)
                .depthStencil(spec.getDepthStencil());
        for (CgColorAttachmentSpec att : spec.getColorAttachments()) {
            builder.addColorAttachment(att);
        }
        CgFramebufferSpec newSpec = builder.build();

        CgExtFramebuffer replacement = createFromSpec(newSpec);

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

        ALL_OWNED.remove(replacement);
        replacement.deleted = true;

        runtimeSlots.putAll(savedSlots);
        resizeRuntimeSlots();
    }

    private void resizeRuntimeSlots() {
        if (runtimeSlots.isEmpty()) {
            return;
        }

        EXTFramebufferObject.glBindFramebufferEXT(GL_FRAMEBUFFER_EXT, fboId);
        for (Map.Entry<Integer, RuntimeSlot> entry : runtimeSlots.entrySet()) {
            int slot = entry.getKey();
            RuntimeSlot rs = entry.getValue();

            if (rs.managed) {
                int newTexId = rs.provider.allocate(width, height, rs.format);
                GL11.glDeleteTextures(rs.textureId);
                rs.textureId = newTexId;
            }

            EXTFramebufferObject.glFramebufferTexture2DEXT(GL_FRAMEBUFFER_EXT,
                    GL_COLOR_ATTACHMENT0_EXT + slot, GL_TEXTURE_2D, rs.textureId, 0);
        }
        EXTFramebufferObject.glBindFramebufferEXT(GL_FRAMEBUFFER_EXT, 0);
    }
}
