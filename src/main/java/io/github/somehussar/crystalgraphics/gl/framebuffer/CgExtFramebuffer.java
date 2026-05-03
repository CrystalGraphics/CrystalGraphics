package io.github.somehussar.crystalgraphics.gl.framebuffer;

import io.github.somehussar.crystalgraphics.api.framebuffer.CgColorAttachmentSpec;
import io.github.somehussar.crystalgraphics.api.framebuffer.CgDepthStencilSpec;
import io.github.somehussar.crystalgraphics.api.framebuffer.CgFramebufferSpec;
import io.github.somehussar.crystalgraphics.api.texture.CgTextureSpec;
import io.github.somehussar.crystalgraphics.gl.CrossApiTransition;
import io.github.somehussar.crystalgraphics.gl.state.CallFamily;
import io.github.somehussar.crystalgraphics.gl.texture.CgTexture2D;

import org.lwjgl.opengl.EXTFramebufferObject;

import java.util.List;
import java.util.logging.Logger;

/**
 * Framebuffer implementation using the legacy {@code EXT_framebuffer_object} extension.
 *
 * <p>This is the fallback backend for hardware that supports neither Core GL30 nor
 * {@code ARB_framebuffer_object}.  It uses the {@code *EXT}-suffixed LWJGL methods.</p>
 *
 * <h3>EXT-Specific Limitations</h3>
 * <ul>
 *   <li><strong>No separate draw/read targets</strong>: All binding methods use
 *       {@code GL_FRAMEBUFFER_EXT}.  {@link #bindDraw()} and {@link #bindRead()} are
 *       overridden to always bind using the combined {@code GL_FRAMEBUFFER_EXT} target.</li>
 *   <li><strong>No MRT</strong>: {@link #drawBuffers(int...)} always throws
 *       {@link UnsupportedOperationException}.</li>
 *   <li><strong>No GL_DEPTH_STENCIL_ATTACHMENT</strong>: For packed depth-stencil,
 *       the same renderbuffer is attached to both {@code GL_DEPTH_ATTACHMENT_EXT}
 *       and {@code GL_STENCIL_ATTACHMENT_EXT} separately.</li>
 * </ul>
 *
 * <p>All other shared logic lives in {@link CgAbstractFramebuffer}.  This class
 * provides only the static factory methods, EXT-specific binding overrides,
 * {@link #drawBuffers} override, and the 11 one-line GL dispatch overrides.</p>
 *
 * @see CgAbstractFramebuffer
 * @see CgCoreFramebuffer
 * @see CgArbFramebuffer
 */
public final class CgExtFramebuffer extends CgAbstractFramebuffer {

    private static final Logger LOGGER = Logger.getLogger(CgExtFramebuffer.class.getName());

    // ── GL constants ───────────────────────────────────────────────────

    /**
     * {@code GL_FRAMEBUFFER_EXT} target (same value as GL_FRAMEBUFFER: 0x8D40).
     * The EXT extension only exposes the combined draw+read target.
     */
    private static final int GL_FRAMEBUFFER_EXT = 0x8D40;

    /** {@code GL_RENDERBUFFER_EXT} target. */
    private static final int GL_RENDERBUFFER_EXT = 0x8D41;

    /** {@code GL_COLOR_ATTACHMENT0_EXT} attachment point. */
    private static final int GL_COLOR_ATTACHMENT0_EXT = 0x8CE0;

    /** {@code GL_DEPTH_ATTACHMENT_EXT} attachment point. */
    private static final int GL_DEPTH_ATTACHMENT_EXT = 0x8D00;

    /** {@code GL_STENCIL_ATTACHMENT_EXT} attachment point. */
    private static final int GL_STENCIL_ATTACHMENT_EXT = 0x8D20;

    /** {@code GL_TEXTURE_2D} target. */
    private static final int GL_TEXTURE_2D = 0x0DE1;

    /** {@code GL_FRAMEBUFFER_COMPLETE_EXT} status. */
    private static final int GL_FRAMEBUFFER_COMPLETE_EXT = 0x8CD5;

    // ── Constructors ───────────────────────────────────────────────────

    /** Legacy path constructor (single color attachment). EXT never supports MRT. */
    private CgExtFramebuffer(int fboId, CgTexture2D colorTexture, int depthRenderbufferId,
                              int width, int height, boolean hasDepth) {
        super(fboId, colorTexture, depthRenderbufferId, width, height, hasDepth, false, true);
    }

    /** Spec-based constructor (single attachment — EXT has no MRT). */
    private CgExtFramebuffer(int fboId, CgTexture2D[] colorTextures,
                              int depthRenderbufferId, int stencilRenderbufferId,
                              CgTexture2D depthTexture,
                              int width, int height, CgFramebufferSpec spec) {
        super(fboId, colorTextures, depthRenderbufferId, stencilRenderbufferId,
                depthTexture, width, height, false, spec, true);
    }

    /** Package-private constructor for wrapping an externally-created FBO. */
    CgExtFramebuffer(int fboId, int width, int height) {
        super(fboId, width, height, false); // EXT never supports MRT
    }

    // ── Factory method (legacy) ───────────────────────────────────────

    /**
     * Creates a new EXT framebuffer with a color texture attachment and
     * optional depth renderbuffer.
     *
     * @param width  width in pixels (must be &gt; 0)
     * @param height height in pixels (must be &gt; 0)
     * @param depth  whether to attach a depth renderbuffer
     * @param mrt    must be {@code false} — EXT does not support MRT
     * @return a new owned {@code CgExtFramebuffer}
     * @throws UnsupportedOperationException if {@code mrt} is {@code true}
     * @throws IllegalArgumentException      if width or height is not positive
     * @throws IllegalStateException         if the framebuffer is not complete
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
                int attWidth = Math.max(1, (int) Math.ceil(baseWidth * att.getScaleX()));
                int attHeight = Math.max(1, (int) Math.ceil(baseHeight * att.getScaleY()));
                CgTexture2D tex = CgTexture2D.createEmpty(attWidth, attHeight,
                        specFrom(att.getFormat(), att.getMipmaps()));
                colorTextures[i] = tex;
                EXTFramebufferObject.glFramebufferTexture2DEXT(GL_FRAMEBUFFER_EXT,
                        GL_COLOR_ATTACHMENT0_EXT + i, GL_TEXTURE_2D, tex.getId(), 0);
            }

            // ── Allocate depth/stencil ─────────────────────────────
            if (dsSpec.isPacked()) {
                // EXT has no GL_DEPTH_STENCIL_ATTACHMENT; attach to both depth and stencil separately
                depthRbo = EXTFramebufferObject.glGenRenderbuffersEXT();
                EXTFramebufferObject.glBindRenderbufferEXT(GL_RENDERBUFFER_EXT, depthRbo);
                EXTFramebufferObject.glRenderbufferStorageEXT(GL_RENDERBUFFER_EXT,
                        dsSpec.getPackedFormat(), baseWidth, baseHeight);
                EXTFramebufferObject.glFramebufferRenderbufferEXT(GL_FRAMEBUFFER_EXT,
                        GL_DEPTH_ATTACHMENT_EXT, GL_RENDERBUFFER_EXT, depthRbo);
                EXTFramebufferObject.glFramebufferRenderbufferEXT(GL_FRAMEBUFFER_EXT,
                        GL_STENCIL_ATTACHMENT_EXT, GL_RENDERBUFFER_EXT, depthRbo);
                stencilRbo = depthRbo; // same RBO; freeGlResources guards against double-delete
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
     * Releases partially-allocated GL resources after a failed creation.
     *
     * <p>For packed depth-stencil, {@code stencilRbo == depthRbo}; the
     * {@code stencilRbo != depthRbo} check prevents a double-delete.</p>
     */
    private static void cleanupOnFailure(int fbo, CgTexture2D[] colorTextures,
                                          int depthRbo, int stencilRbo, CgTexture2D depthTex) {
        EXTFramebufferObject.glDeleteFramebuffersEXT(fbo);
        for (CgTexture2D tex : colorTextures) {
            if (tex != null) tex.delete();
        }
        if (depthRbo != 0) EXTFramebufferObject.glDeleteRenderbuffersEXT(depthRbo);
        if (stencilRbo != 0 && stencilRbo != depthRbo) {
            EXTFramebufferObject.glDeleteRenderbuffersEXT(stencilRbo);
        }
        if (depthTex != null) depthTex.delete();
    }

    // ── CallFamily ─────────────────────────────────────────────────────

    @Override
    protected CallFamily callFamily() {
        return CallFamily.EXT_FBO;
    }

    // ── newFromSpec ────────────────────────────────────────────────────

    @Override
    protected CgAbstractFramebuffer newFromSpec(CgFramebufferSpec spec) {
        return createFromSpec(spec);
    }

    // ── Binding overrides (EXT has no separate draw/read targets) ───────

    /**
     * {@inheritDoc}
     *
     * <p>Binds using {@code GL_FRAMEBUFFER_EXT}.  EXT does not support
     * separate draw and read framebuffer targets.</p>
     */
    @Override
    public void bind() {
        CrossApiTransition.bindFramebuffer(GL_FRAMEBUFFER_EXT, fboId, callFamily());
    }

    /**
     * {@inheritDoc}
     *
     * <p><strong>EXT limitation</strong>: Separate draw/read targets are not
     * supported.  Binds using {@code GL_FRAMEBUFFER_EXT} (same as {@link #bind()}).</p>
     */
    @Override
    public void bindDraw() {
        CrossApiTransition.bindFramebuffer(GL_FRAMEBUFFER_EXT, fboId, callFamily());
    }

    /**
     * {@inheritDoc}
     *
     * <p><strong>EXT limitation</strong>: Separate draw/read targets are not
     * supported.  Binds using {@code GL_FRAMEBUFFER_EXT} (same as {@link #bind()}).</p>
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
     * <p>{@code EXT_framebuffer_object} does not include draw-buffer selection.
     * MRT requires Core GL30 or {@code ARB_framebuffer_object}.</p>
     *
     * @throws UnsupportedOperationException always
     */
    @Override
    public void drawBuffers(int... attachments) {
        throw new UnsupportedOperationException(
                "EXT_framebuffer_object does not support MRT. "
                + "Use Core GL30 or ARB_framebuffer_object.");
    }

    // ── GL dispatch ────────────────────────────────────────────────────

    @Override
    protected int glDoGenFramebuffer() {
        return EXTFramebufferObject.glGenFramebuffersEXT();
    }

    @Override
    protected void glDoDeleteFramebuffer(int id) {
        EXTFramebufferObject.glDeleteFramebuffersEXT(id);
    }

    @Override
    protected void glDoBindFbo(int fboId) {
        EXTFramebufferObject.glBindFramebufferEXT(GL_FRAMEBUFFER_EXT, fboId);
    }

    @Override
    protected void glDoFramebufferTexture2D(int attachment, int texId) {
        EXTFramebufferObject.glFramebufferTexture2DEXT(GL_FRAMEBUFFER_EXT, attachment, GL_TEXTURE_2D, texId, 0);
    }

    @Override
    protected int glDoCheckFramebufferStatus() {
        return EXTFramebufferObject.glCheckFramebufferStatusEXT(GL_FRAMEBUFFER_EXT);
    }

    @Override
    protected int glDoGenRenderbuffer() {
        return EXTFramebufferObject.glGenRenderbuffersEXT();
    }

    @Override
    protected void glDoBindRenderbuffer(int id) {
        EXTFramebufferObject.glBindRenderbufferEXT(GL_RENDERBUFFER_EXT, id);
    }

    @Override
    protected void glDoRenderbufferStorage(int format, int w, int h) {
        EXTFramebufferObject.glRenderbufferStorageEXT(GL_RENDERBUFFER_EXT, format, w, h);
    }

    @Override
    protected void glDoDeleteRenderbuffer(int id) {
        EXTFramebufferObject.glDeleteRenderbuffersEXT(id);
    }

    @Override
    protected void glDoFramebufferRenderbuffer(int attachment, int rboId) {
        EXTFramebufferObject.glFramebufferRenderbufferEXT(GL_FRAMEBUFFER_EXT,
                attachment, GL_RENDERBUFFER_EXT, rboId);
    }
}
