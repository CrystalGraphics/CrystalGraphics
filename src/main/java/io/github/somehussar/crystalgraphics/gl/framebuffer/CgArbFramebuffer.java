package io.github.somehussar.crystalgraphics.gl.framebuffer;

import io.github.somehussar.crystalgraphics.api.framebuffer.CgColorAttachmentSpec;
import io.github.somehussar.crystalgraphics.api.framebuffer.CgDepthStencilSpec;
import io.github.somehussar.crystalgraphics.api.framebuffer.CgFramebufferSpec;
import io.github.somehussar.crystalgraphics.api.texture.CgTextureSpec;
import io.github.somehussar.crystalgraphics.gl.state.CallFamily;
import io.github.somehussar.crystalgraphics.gl.texture.CgTexture2D;

import org.lwjgl.opengl.ARBFramebufferObject;

import java.util.List;
import java.util.logging.Logger;

/**
 * Framebuffer implementation using the {@code ARB_framebuffer_object} extension.
 *
 * <p>The ARB FBO extension is semantically identical to Core GL30 and uses the
 * same constant values (e.g. {@code GL_FRAMEBUFFER = 0x8D40}), but routes
 * through different LWJGL entry points ({@link ARBFramebufferObject#glGenFramebuffers()}
 * vs. {@link org.lwjgl.opengl.GL30#glGenFramebuffers()}).  This matters because
 * some drivers expose ARB FBO support without fully supporting GL 3.0, and
 * cross-API state tracking must know which entry point was used.</p>
 *
 * <p>All shared logic lives in {@link CgAbstractFramebuffer}.  This class provides
 * only the static factory methods and the 11 one-line GL dispatch overrides that
 * route calls through {@link ARBFramebufferObject}.</p>
 *
 * @see CgAbstractFramebuffer
 * @see CgCoreFramebuffer
 * @see CgExtFramebuffer
 */
public final class CgArbFramebuffer extends CgAbstractFramebuffer {

    private static final Logger LOGGER = Logger.getLogger(CgArbFramebuffer.class.getName());

    // ── GL constants ───────────────────────────────────────────────────

    /** {@code GL_FRAMEBUFFER} target. */
    private static final int GL_FRAMEBUFFER = 0x8D40;

    /** {@code GL_RENDERBUFFER} target. */
    private static final int GL_RENDERBUFFER = 0x8D41;

    /** {@code GL_TEXTURE_2D} target. */
    private static final int GL_TEXTURE_2D = 0x0DE1;

    // ── Constructors ───────────────────────────────────────────────────

    /** Legacy path constructor (single color attachment). */
    private CgArbFramebuffer(int fboId, CgTexture2D colorTexture, int depthRenderbufferId,
                              int width, int height, boolean hasDepth, boolean supportsMrt) {
        super(fboId, colorTexture, depthRenderbufferId, width, height, hasDepth, supportsMrt, true);
    }

    /** Spec-based constructor (multi-attachment). */
    private CgArbFramebuffer(int fboId, CgTexture2D[] colorTextures,
                              int depthRenderbufferId, int stencilRenderbufferId,
                              CgTexture2D depthTexture,
                              int width, int height, boolean supportsMrt,
                              CgFramebufferSpec spec) {
        super(fboId, colorTextures, depthRenderbufferId, stencilRenderbufferId,
                depthTexture, width, height, supportsMrt, spec, true);
    }

    /** Package-private constructor for wrapping an externally-created FBO. */
    CgArbFramebuffer(int fboId, int width, int height, boolean supportsMrt) {
        super(fboId, width, height, supportsMrt);
    }

    // ── Factory method (legacy) ───────────────────────────────────────

    /**
     * Creates a new ARB framebuffer with a color texture attachment and
     * optional depth renderbuffer.
     *
     * @param width  width in pixels (must be &gt; 0)
     * @param height height in pixels (must be &gt; 0)
     * @param depth  whether to attach a depth renderbuffer
     * @param mrt    whether to allow multiple render targets
     * @return a new owned {@code CgArbFramebuffer}
     * @throws IllegalArgumentException if width or height is not positive
     * @throws IllegalStateException    if the framebuffer is not complete after setup
     */
    public static CgArbFramebuffer create(int width, int height, boolean depth, boolean mrt) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                    "Framebuffer dimensions must be positive: " + width + "x" + height);
        }

        int fbo = ARBFramebufferObject.glGenFramebuffers();
        ARBFramebufferObject.glBindFramebuffer(GL_FRAMEBUFFER, fbo);

        // Color texture (GL_COLOR_ATTACHMENT0) — allocate via texture infrastructure
        CgTexture2D colorTex = CgTexture2D.createEmpty(width, height, CgTextureSpec.RGBA8_LINEAR);
        ARBFramebufferObject.glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                GL_TEXTURE_2D, colorTex.getId(), 0);

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
            colorTex.delete();
            if (depthRbo != 0) {
                ARBFramebufferObject.glDeleteRenderbuffers(depthRbo);
            }
            throw new IllegalStateException(
                    "ARB framebuffer is not complete. Status: 0x"
                    + Integer.toHexString(status));
        }

        ARBFramebufferObject.glBindFramebuffer(GL_FRAMEBUFFER, 0);

        return new CgArbFramebuffer(fbo, colorTex, depthRbo, width, height, depth, mrt);
    }

    // ── Factory method (spec-based) ───────────────────────────────────

    /**
     * Creates a new ARB framebuffer from a detailed specification.
     *
     * @param spec the framebuffer specification
     * @return a new owned {@code CgArbFramebuffer}
     * @throws IllegalArgumentException if spec is null
     * @throws IllegalStateException    if the framebuffer is not complete after setup
     */
    public static CgArbFramebuffer createFromSpec(CgFramebufferSpec spec) {
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

        CgTexture2D[] colorTextures = new CgTexture2D[colorCount];
        int depthRbo = 0;
        int stencilRbo = 0;
        CgTexture2D depthTex = null;

        try {
            // ── Allocate color textures ─────────────────────────────
            for (int i = 0; i < colorCount; i++) {
                CgColorAttachmentSpec att = attachments.get(i);
                int attWidth = Math.max(1, (int) Math.ceil(baseWidth * att.getScaleX()));
                int attHeight = Math.max(1, (int) Math.ceil(baseHeight * att.getScaleY()));
                CgTexture2D tex = CgTexture2D.createEmpty(attWidth, attHeight,
                        specFrom(att.getFormat(), att.getMipmaps()));
                colorTextures[i] = tex;
                ARBFramebufferObject.glFramebufferTexture2D(GL_FRAMEBUFFER,
                        GL_COLOR_ATTACHMENT0 + i, GL_TEXTURE_2D, tex.getId(), 0);
            }

            // ── Allocate depth/stencil ─────────────────────────────
            if (dsSpec.isPacked()) {
                depthRbo = ARBFramebufferObject.glGenRenderbuffers();
                ARBFramebufferObject.glBindRenderbuffer(GL_RENDERBUFFER, depthRbo);
                ARBFramebufferObject.glRenderbufferStorage(GL_RENDERBUFFER,
                        dsSpec.getPackedFormat(), baseWidth, baseHeight);
                ARBFramebufferObject.glFramebufferRenderbuffer(GL_FRAMEBUFFER,
                        GL_DEPTH_STENCIL_ATTACHMENT, GL_RENDERBUFFER, depthRbo);

                int packedStatus = ARBFramebufferObject.glCheckFramebufferStatus(GL_FRAMEBUFFER);
                if (packedStatus != GL_FRAMEBUFFER_COMPLETE) {
                    LOGGER.warning("Packed depth-stencil (format 0x"
                            + Integer.toHexString(dsSpec.getPackedFormat())
                            + ") failed completeness (status 0x"
                            + Integer.toHexString(packedStatus)
                            + "), falling back to separate depth + stencil");

                    ARBFramebufferObject.glFramebufferRenderbuffer(GL_FRAMEBUFFER,
                            GL_DEPTH_STENCIL_ATTACHMENT, GL_RENDERBUFFER, 0);
                    ARBFramebufferObject.glDeleteRenderbuffers(depthRbo);
                    depthRbo = 0;

                    depthRbo = ARBFramebufferObject.glGenRenderbuffers();
                    ARBFramebufferObject.glBindRenderbuffer(GL_RENDERBUFFER, depthRbo);
                    ARBFramebufferObject.glRenderbufferStorage(GL_RENDERBUFFER,
                            GL_DEPTH_COMPONENT24, baseWidth, baseHeight);
                    ARBFramebufferObject.glFramebufferRenderbuffer(GL_FRAMEBUFFER,
                            GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, depthRbo);

                    stencilRbo = ARBFramebufferObject.glGenRenderbuffers();
                    ARBFramebufferObject.glBindRenderbuffer(GL_RENDERBUFFER, stencilRbo);
                    ARBFramebufferObject.glRenderbufferStorage(GL_RENDERBUFFER,
                            GL_STENCIL_INDEX8, baseWidth, baseHeight);
                    ARBFramebufferObject.glFramebufferRenderbuffer(GL_FRAMEBUFFER,
                            GL_STENCIL_ATTACHMENT, GL_RENDERBUFFER, stencilRbo);
                }
            } else if (dsSpec.isDepthTexture() && dsSpec.hasDepth()) {
                depthTex = CgTexture2D.createEmpty(baseWidth, baseHeight,
                        depthSpecFrom(dsSpec.getDepthFormat()));
                ARBFramebufferObject.glFramebufferTexture2D(GL_FRAMEBUFFER,
                        GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, depthTex.getId(), 0);
            } else {
                if (dsSpec.hasDepth()) {
                    depthRbo = ARBFramebufferObject.glGenRenderbuffers();
                    ARBFramebufferObject.glBindRenderbuffer(GL_RENDERBUFFER, depthRbo);
                    ARBFramebufferObject.glRenderbufferStorage(GL_RENDERBUFFER,
                            dsSpec.getDepthFormat(), baseWidth, baseHeight);
                    ARBFramebufferObject.glFramebufferRenderbuffer(GL_FRAMEBUFFER,
                            GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, depthRbo);
                }
                if (dsSpec.hasStencil()) {
                    stencilRbo = ARBFramebufferObject.glGenRenderbuffers();
                    ARBFramebufferObject.glBindRenderbuffer(GL_RENDERBUFFER, stencilRbo);
                    ARBFramebufferObject.glRenderbufferStorage(GL_RENDERBUFFER,
                            dsSpec.getStencilFormat(), baseWidth, baseHeight);
                    ARBFramebufferObject.glFramebufferRenderbuffer(GL_FRAMEBUFFER,
                            GL_STENCIL_ATTACHMENT, GL_RENDERBUFFER, stencilRbo);
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
            return new CgArbFramebuffer(fbo, colorTextures, depthRbo, stencilRbo,
                    depthTex, baseWidth, baseHeight, mrt, spec);

        } catch (RuntimeException e) {
            cleanupOnFailure(fbo, colorTextures, depthRbo, stencilRbo, depthTex);
            ARBFramebufferObject.glBindFramebuffer(GL_FRAMEBUFFER, 0);
            throw e;
        }
    }

    /** Releases partially-allocated GL resources after a failed creation. */
    private static void cleanupOnFailure(int fbo, CgTexture2D[] colorTextures,
                                          int depthRbo, int stencilRbo, CgTexture2D depthTex) {
        ARBFramebufferObject.glDeleteFramebuffers(fbo);
        for (CgTexture2D tex : colorTextures) {
            if (tex != null) tex.delete();
        }
        if (depthRbo != 0) ARBFramebufferObject.glDeleteRenderbuffers(depthRbo);
        if (stencilRbo != 0) ARBFramebufferObject.glDeleteRenderbuffers(stencilRbo);
        if (depthTex != null) depthTex.delete();
    }

    // ── CallFamily ─────────────────────────────────────────────────────

    @Override
    protected CallFamily callFamily() {
        return CallFamily.ARB_FBO;
    }

    // ── newFromSpec ────────────────────────────────────────────────────

    @Override
    protected CgAbstractFramebuffer newFromSpec(CgFramebufferSpec spec) {
        return createFromSpec(spec);
    }

    // ── GL dispatch ────────────────────────────────────────────────────

    @Override
    protected int glDoGenFramebuffer() {
        return ARBFramebufferObject.glGenFramebuffers();
    }

    @Override
    protected void glDoDeleteFramebuffer(int id) {
        ARBFramebufferObject.glDeleteFramebuffers(id);
    }

    @Override
    protected void glDoBindFbo(int fboId) {
        ARBFramebufferObject.glBindFramebuffer(GL_FRAMEBUFFER, fboId);
    }

    @Override
    protected void glDoFramebufferTexture2D(int attachment, int texId) {
        ARBFramebufferObject.glFramebufferTexture2D(GL_FRAMEBUFFER, attachment, GL_TEXTURE_2D, texId, 0);
    }

    @Override
    protected int glDoCheckFramebufferStatus() {
        return ARBFramebufferObject.glCheckFramebufferStatus(GL_FRAMEBUFFER);
    }

    @Override
    protected int glDoGenRenderbuffer() {
        return ARBFramebufferObject.glGenRenderbuffers();
    }

    @Override
    protected void glDoBindRenderbuffer(int id) {
        ARBFramebufferObject.glBindRenderbuffer(GL_RENDERBUFFER, id);
    }

    @Override
    protected void glDoRenderbufferStorage(int format, int w, int h) {
        ARBFramebufferObject.glRenderbufferStorage(GL_RENDERBUFFER, format, w, h);
    }

    @Override
    protected void glDoDeleteRenderbuffer(int id) {
        ARBFramebufferObject.glDeleteRenderbuffers(id);
    }

    @Override
    protected void glDoFramebufferRenderbuffer(int attachment, int rboId) {
        ARBFramebufferObject.glFramebufferRenderbuffer(GL_FRAMEBUFFER, attachment, GL_RENDERBUFFER, rboId);
    }
}
