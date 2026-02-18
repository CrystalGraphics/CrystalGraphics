package io.github.somehussar.crystalgraphics.gl.framebuffer;

import io.github.somehussar.crystalgraphics.gl.CrossApiTransition;
import io.github.somehussar.crystalgraphics.gl.state.CallFamily;

import org.lwjgl.opengl.EXTFramebufferObject;
import org.lwjgl.opengl.GL11;

import java.nio.ByteBuffer;

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
 * </ul>
 *
 * <h3>Thread Safety</h3>
 * <p>Not thread-safe.  Must only be used from the GL context thread.</p>
 *
 * @see AbstractCgFramebuffer
 * @see CoreFramebuffer
 * @see ArbFramebuffer
 */
public final class ExtFramebuffer extends AbstractCgFramebuffer {

    // ── GL constants (EXT uses same numeric values but different names) ─

    /** {@code GL_FRAMEBUFFER_EXT} target (same value as GL_FRAMEBUFFER: 0x8D40). */
    private static final int GL_FRAMEBUFFER_EXT = 0x8D40;

    /** {@code GL_RENDERBUFFER_EXT} target. */
    private static final int GL_RENDERBUFFER_EXT = 0x8D41;

    /** {@code GL_DEPTH_COMPONENT24} internal format (shared across all paths). */
    private static final int GL_DEPTH_COMPONENT24 = 0x81A6;

    /** {@code GL_DEPTH_ATTACHMENT_EXT} attachment point. */
    private static final int GL_DEPTH_ATTACHMENT_EXT = 0x8D00;

    /** {@code GL_COLOR_ATTACHMENT0_EXT} attachment point. */
    private static final int GL_COLOR_ATTACHMENT0_EXT = 0x8CE0;

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

    /** {@code GL_FRAMEBUFFER_COMPLETE_EXT} status. */
    private static final int GL_FRAMEBUFFER_COMPLETE_EXT = 0x8CD5;

    // ── Instance state ─────────────────────────────────────────────────

    /** Whether this framebuffer has a depth renderbuffer attached. */
    private final boolean hasDepth;

    // ── Constructors ───────────────────────────────────────────────────

    /**
     * Creates an ExtFramebuffer with pre-existing GL resource IDs.
     *
     * @param fboId               the framebuffer object ID
     * @param colorTextureId      the color texture ID
     * @param depthRenderbufferId the depth renderbuffer ID (0 if none)
     * @param width               width in pixels
     * @param height              height in pixels
     * @param hasDepth            whether a depth buffer is attached
     */
    private ExtFramebuffer(int fboId, int colorTextureId, int depthRenderbufferId,
                           int width, int height, boolean hasDepth) {
        super(fboId, width, height, true, false); // EXT never supports MRT
        this.colorTextureId = colorTextureId;
        this.depthRenderbufferId = depthRenderbufferId;
        this.hasDepth = hasDepth;
    }

    /**
     * Package-private constructor for wrapping an externally-created FBO.
     *
     * <p>The wrapped framebuffer is non-owned and cannot be deleted.</p>
     *
     * @param fboId  the external framebuffer object ID
     * @param width  known width (informational)
     * @param height known height (informational)
     */
    ExtFramebuffer(int fboId, int width, int height) {
        super(fboId, width, height, false, false); // EXT never supports MRT
        this.hasDepth = false;
    }

    // ── Factory method ─────────────────────────────────────────────────

    /**
     * Creates a new EXT framebuffer with a color texture attachment and
     * optional depth renderbuffer.
     *
     * <p>Uses {@link EXTFramebufferObject} entry points (all methods have
     * the {@code EXT} suffix).  MRT is never supported via the EXT path;
     * if {@code mrt} is {@code true}, this method throws
     * {@link UnsupportedOperationException}.</p>
     *
     * @param width  width in pixels (must be &gt; 0)
     * @param height height in pixels (must be &gt; 0)
     * @param depth  whether to attach a depth renderbuffer
     * @param mrt    must be {@code false} — EXT does not support MRT
     * @return a new owned {@code ExtFramebuffer}
     * @throws UnsupportedOperationException if {@code mrt} is {@code true}
     * @throws IllegalStateException         if the framebuffer is not complete
     * @throws IllegalArgumentException      if width or height is not positive
     */
    public static ExtFramebuffer create(int width, int height, boolean depth, boolean mrt) {
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

        // Color texture (GL_COLOR_ATTACHMENT0_EXT)
        int colorTex = GL11.glGenTextures();
        GL11.glBindTexture(GL_TEXTURE_2D, colorTex);
        GL11.glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0,
                GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GL11.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        GL11.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        EXTFramebufferObject.glFramebufferTexture2DEXT(GL_FRAMEBUFFER_EXT,
                GL_COLOR_ATTACHMENT0_EXT, GL_TEXTURE_2D, colorTex, 0);

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
            GL11.glDeleteTextures(colorTex);
            if (depthRbo != 0) {
                EXTFramebufferObject.glDeleteRenderbuffersEXT(depthRbo);
            }
            throw new IllegalStateException(
                    "EXT framebuffer is not complete. Status: 0x"
                    + Integer.toHexString(status));
        }

        // Unbind FBO to restore default state
        EXTFramebufferObject.glBindFramebufferEXT(GL_FRAMEBUFFER_EXT, 0);

        return new ExtFramebuffer(fbo, colorTex, depthRbo, width, height, depth);
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
     * the color texture via GL11, and the depth renderbuffer (if present)
     * via {@link EXTFramebufferObject#glDeleteRenderbuffersEXT(int)}.</p>
     */
    @Override
    protected void freeGlResources() {
        EXTFramebufferObject.glDeleteFramebuffersEXT(fboId);
        if (colorTextureId != 0) {
            GL11.glDeleteTextures(colorTextureId);
        }
        if (depthRenderbufferId != 0) {
            EXTFramebufferObject.glDeleteRenderbuffersEXT(depthRenderbufferId);
        }
    }

    // ── Resize ─────────────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p>Recreates all GL resources at the new dimensions using EXT entry
     * points.  The FBO ID will change after this call.</p>
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

        freeGlResources();

        int newFbo = EXTFramebufferObject.glGenFramebuffersEXT();
        EXTFramebufferObject.glBindFramebufferEXT(GL_FRAMEBUFFER_EXT, newFbo);

        int newColorTex = GL11.glGenTextures();
        GL11.glBindTexture(GL_TEXTURE_2D, newColorTex);
        GL11.glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, newWidth, newHeight, 0,
                GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GL11.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        GL11.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        EXTFramebufferObject.glFramebufferTexture2DEXT(GL_FRAMEBUFFER_EXT,
                GL_COLOR_ATTACHMENT0_EXT, GL_TEXTURE_2D, newColorTex, 0);

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
            GL11.glDeleteTextures(newColorTex);
            if (newDepthRbo != 0) {
                EXTFramebufferObject.glDeleteRenderbuffersEXT(newDepthRbo);
            }
            throw new IllegalStateException(
                    "EXT framebuffer resize failed. Status: 0x"
                    + Integer.toHexString(status));
        }

        EXTFramebufferObject.glBindFramebufferEXT(GL_FRAMEBUFFER_EXT, 0);

        this.fboId = newFbo;
        this.colorTextureId = newColorTex;
        this.depthRenderbufferId = newDepthRbo;
        this.width = newWidth;
        this.height = newHeight;
    }
}
