package io.github.somehussar.crystalgraphics.harness.util;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.util.logging.Logger;

/**
 * Shared FBO creation, validation, and teardown for managed harness scenes.
 */
public final class HarnessFboHelper {

    private static final Logger LOGGER = Logger.getLogger(HarnessFboHelper.class.getName());

    private int fboId;
    private int colorTexId;
    private int depthRbId;
    private final int width;
    private final int height;
    private final boolean hasDepth;

    private HarnessFboHelper(int fboId, int colorTexId, int depthRbId,
                             int width, int height, boolean hasDepth) {
        this.fboId = fboId;
        this.colorTexId = colorTexId;
        this.depthRbId = depthRbId;
        this.width = width;
        this.height = height;
        this.hasDepth = hasDepth;
    }

    /**
     * Create an FBO with a color texture and optional depth renderbuffer.
     */
    public static HarnessFboHelper create(int width, int height, boolean withDepth) {
        int fbo = GL30.glGenFramebuffers();
        int colorTex = GL11.glGenTextures();
        int depthRb = 0;

        GL11.glBindTexture(GL11.GL_TEXTURE_2D, colorTex);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8,
                width, height, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);

        if (withDepth) {
            depthRb = GL30.glGenRenderbuffers();
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, depthRb);
            GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL11.GL_DEPTH_COMPONENT,
                    width, height);
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, 0);
        }

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER,
                GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, colorTex, 0);
        if (withDepth) {
            GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER,
                    GL30.GL_DEPTH_ATTACHMENT, GL30.GL_RENDERBUFFER, depthRb);
        }

        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            throw new RuntimeException("FBO incomplete: status=0x" + Integer.toHexString(status));
        }

        LOGGER.info("[Harness] FBO created: " + width + "x" + height
                + " depth=" + withDepth + " id=" + fbo);

        return new HarnessFboHelper(fbo, colorTex, depthRb, width, height, withDepth);
    }

    /** Bind this FBO and set viewport. */
    public void bind() {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fboId);
        GL11.glViewport(0, 0, width, height);
    }

    /** Unbind FBO (bind default framebuffer 0). */
    public void unbind() {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
    }

    /** Clear color (and optionally depth) buffers. */
    public void clear(float r, float g, float b, float a) {
        GL11.glClearColor(r, g, b, a);
        int bits = GL11.GL_COLOR_BUFFER_BIT;
        if (hasDepth) {
            bits |= GL11.GL_DEPTH_BUFFER_BIT;
        }
        GL11.glClear(bits);
    }

    /** Capture the FBO's color attachment to a PNG file. */
    public void captureToFile(String outputDir, String filename) {
        ScreenshotUtil.captureFboColorTexture(fboId, colorTexId,
                width, height, outputDir, filename);
    }

    /** Delete all GL resources. */
    public void delete() {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        if (fboId != 0) {
            GL30.glDeleteFramebuffers(fboId);
            fboId = 0;
        }
        if (colorTexId != 0) {
            GL11.glDeleteTextures(colorTexId);
            colorTexId = 0;
        }
        if (depthRbId != 0) {
            GL30.glDeleteRenderbuffers(depthRbId);
            depthRbId = 0;
        }
    }

    public int getFboId() { return fboId; }
    public int getColorTexId() { return colorTexId; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
}
