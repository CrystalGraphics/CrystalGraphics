package io.github.somehussar.crystalgraphics.gl.framebuffer;

import io.github.somehussar.crystalgraphics.gl.state.CallFamily;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

/**
 * Framebuffer implementation using Core OpenGL 3.0 entry points.
 *
 * <p>This is the preferred backend on hardware that supports GL 3.0 or later.
 * It uses {@link GL30#glGenFramebuffers()}, {@link GL30#glBindFramebuffer(int, int)},
 * and related Core methods.  MRT is supported via {@link GL20#glDrawBuffers(IntBuffer)}
 * when the hardware allows it.</p>
 *
 * <h3>Resource Lifecycle</h3>
 * <p>A {@code CoreFramebuffer} owns exactly one FBO, one color texture
 * (GL_COLOR_ATTACHMENT0), and optionally one depth renderbuffer.  All resources
 * are released together when {@link #delete()} is called.</p>
 *
 * <h3>Thread Safety</h3>
 * <p>Not thread-safe.  Must only be used from the GL context thread.</p>
 *
 * @see AbstractCgFramebuffer
 * @see ArbFramebuffer
 * @see ExtFramebuffer
 */
public final class CoreFramebuffer extends AbstractCgFramebuffer {

    // ── GL constants ───────────────────────────────────────────────────

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

    // ── Constructor (private — use factory method) ─────────────────────

    /**
     * Creates a CoreFramebuffer with pre-existing GL resource IDs.
     *
     * @param fboId               the framebuffer object ID
     * @param colorTextureId      the color texture ID
     * @param depthRenderbufferId the depth renderbuffer ID (0 if none)
     * @param width               width in pixels
     * @param height              height in pixels
     * @param hasDepth            whether a depth buffer is attached
     * @param supportsMrt         whether MRT is available
     */
    private CoreFramebuffer(int fboId, int colorTextureId, int depthRenderbufferId,
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
    CoreFramebuffer(int fboId, int width, int height, boolean supportsMrt) {
        super(fboId, width, height, false, supportsMrt);
        this.hasDepth = false;
    }

    // ── Factory method ─────────────────────────────────────────────────

    /**
     * Creates a new Core GL30 framebuffer with a color texture attachment
     * and optional depth renderbuffer.
     *
     * <p>Generates the FBO, creates and attaches a GL_RGBA8 color texture
     * (GL_COLOR_ATTACHMENT0), optionally creates and attaches a
     * GL_DEPTH_COMPONENT24 renderbuffer, and verifies framebuffer
     * completeness.</p>
     *
     * @param width  width in pixels (must be &gt; 0)
     * @param height height in pixels (must be &gt; 0)
     * @param depth  whether to attach a depth renderbuffer
     * @param mrt    whether to allow multiple render targets
     * @return a new owned {@code CoreFramebuffer}
     * @throws IllegalStateException    if the framebuffer is not complete after setup
     * @throws IllegalArgumentException if width or height is not positive
     */
    public static CoreFramebuffer create(int width, int height, boolean depth, boolean mrt) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                    "Framebuffer dimensions must be positive: " + width + "x" + height);
        }

        int fbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL_FRAMEBUFFER, fbo);

        // Color texture (GL_COLOR_ATTACHMENT0)
        int colorTex = GL11.glGenTextures();
        GL11.glBindTexture(GL_TEXTURE_2D, colorTex);
        GL11.glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0,
                GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GL11.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        GL11.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        GL30.glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                GL_TEXTURE_2D, colorTex, 0);

        // Optional depth renderbuffer
        int depthRbo = 0;
        if (depth) {
            depthRbo = GL30.glGenRenderbuffers();
            GL30.glBindRenderbuffer(GL_RENDERBUFFER, depthRbo);
            GL30.glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, width, height);
            GL30.glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT,
                    GL_RENDERBUFFER, depthRbo);
        }

        // Completeness check
        int status = GL30.glCheckFramebufferStatus(GL_FRAMEBUFFER);
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            // Clean up on failure
            GL30.glDeleteFramebuffers(fbo);
            GL11.glDeleteTextures(colorTex);
            if (depthRbo != 0) {
                GL30.glDeleteRenderbuffers(depthRbo);
            }
            throw new IllegalStateException(
                    "Core GL30 framebuffer is not complete. Status: 0x"
                    + Integer.toHexString(status));
        }

        // Unbind FBO to restore default state
        GL30.glBindFramebuffer(GL_FRAMEBUFFER, 0);

        return new CoreFramebuffer(fbo, colorTex, depthRbo, width, height, depth, mrt);
    }

    // ── CallFamily ─────────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * @return {@link CallFamily#CORE_GL30}
     */
    @Override
    protected CallFamily callFamily() {
        return CallFamily.CORE_GL30;
    }

    // ── drawBuffers (MRT via GL20) ─────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p>Sets the draw buffers for this framebuffer using
     * {@link GL20#glDrawBuffers(IntBuffer)}.  Requires MRT support.</p>
     *
     * @param attachments the color attachment constants
     *                    (e.g. {@code GL_COLOR_ATTACHMENT0}, {@code GL_COLOR_ATTACHMENT1}, ...)
     * @throws UnsupportedOperationException if MRT is not supported
     * @throws IllegalArgumentException      if attachments is empty
     */
    @Override
    public void drawBuffers(int... attachments) {
        if (!supportsMrt) {
            throw new UnsupportedOperationException(
                    "This CoreFramebuffer does not support MRT. "
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
     * <p>Deletes the FBO, the color texture, and the depth renderbuffer
     * (if present) using Core GL30 / GL11 calls.</p>
     */
    @Override
    protected void freeGlResources() {
        GL30.glDeleteFramebuffers(fboId);
        if (colorTextureId != 0) {
            GL11.glDeleteTextures(colorTextureId);
        }
        if (depthRenderbufferId != 0) {
            GL30.glDeleteRenderbuffers(depthRenderbufferId);
        }
    }

    // ── Resize ─────────────────────────────────────────────────────────

    /**
     * {@inheritDoc}
     *
     * <p>Recreates all GL resources at the new dimensions.  The FBO ID will
     * change after this call.  The old resources are freed first.</p>
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

        // Free old resources (but don't mark as deleted — we're recreating)
        freeGlResources();

        // Recreate
        int newFbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL_FRAMEBUFFER, newFbo);

        int newColorTex = GL11.glGenTextures();
        GL11.glBindTexture(GL_TEXTURE_2D, newColorTex);
        GL11.glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, newWidth, newHeight, 0,
                GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GL11.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        GL11.glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        GL30.glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                GL_TEXTURE_2D, newColorTex, 0);

        int newDepthRbo = 0;
        if (hasDepth) {
            newDepthRbo = GL30.glGenRenderbuffers();
            GL30.glBindRenderbuffer(GL_RENDERBUFFER, newDepthRbo);
            GL30.glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24,
                    newWidth, newHeight);
            GL30.glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT,
                    GL_RENDERBUFFER, newDepthRbo);
        }

        int status = GL30.glCheckFramebufferStatus(GL_FRAMEBUFFER);
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            GL30.glDeleteFramebuffers(newFbo);
            GL11.glDeleteTextures(newColorTex);
            if (newDepthRbo != 0) {
                GL30.glDeleteRenderbuffers(newDepthRbo);
            }
            throw new IllegalStateException(
                    "Core GL30 framebuffer resize failed. Status: 0x"
                    + Integer.toHexString(status));
        }

        GL30.glBindFramebuffer(GL_FRAMEBUFFER, 0);

        // Update instance state
        this.fboId = newFbo;
        this.colorTextureId = newColorTex;
        this.depthRenderbufferId = newDepthRbo;
        this.width = newWidth;
        this.height = newHeight;
    }
}
