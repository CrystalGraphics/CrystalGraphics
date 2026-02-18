package io.github.somehussar.crystalgraphics.gl.framebuffer;

import io.github.somehussar.crystalgraphics.gl.state.CallFamily;

import org.lwjgl.opengl.ARBFramebufferObject;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

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
 * <h3>Thread Safety</h3>
 * <p>Not thread-safe.  Must only be used from the GL context thread.</p>
 *
 * @see AbstractCgFramebuffer
 * @see CoreFramebuffer
 * @see ExtFramebuffer
 */
public final class ArbFramebuffer extends AbstractCgFramebuffer {

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

    // ── Instance state ─────────────────────────────────────────────────

    /** Whether this framebuffer has a depth renderbuffer attached. */
    private final boolean hasDepth;

    // ── Constructors ───────────────────────────────────────────────────

    /**
     * Creates an ArbFramebuffer with pre-existing GL resource IDs.
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
     * <p>Deletes the FBO using {@link ARBFramebufferObject#glDeleteFramebuffers(int)},
     * the color texture via GL11, and the depth renderbuffer (if present)
     * via {@link ARBFramebufferObject#glDeleteRenderbuffers(int)}.</p>
     */
    @Override
    protected void freeGlResources() {
        ARBFramebufferObject.glDeleteFramebuffers(fboId);
        if (colorTextureId != 0) {
            GL11.glDeleteTextures(colorTextureId);
        }
        if (depthRenderbufferId != 0) {
            ARBFramebufferObject.glDeleteRenderbuffers(depthRenderbufferId);
        }
    }

    // ── Resize ─────────────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p>Recreates all GL resources at the new dimensions using ARB entry
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
    }
}
