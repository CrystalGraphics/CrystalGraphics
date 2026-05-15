package com.crystalgraphics.gl.framebuffer;

import com.crystalgraphics.api.framebuffer.CgFrameBufferFormat;
import com.crystalgraphics.gl.CrossApiTransition;
import com.crystalgraphics.gl.state.CallFamily;

import org.lwjgl.opengl.EXTFramebufferObject;

/**
 * Framebuffer backend that routes all GL dispatch through the legacy
 * {@code EXT_framebuffer_object} extension ({@link EXTFramebufferObject}).
 *
 * <p>This is the fallback backend for hardware that supports neither Core GL30
 * nor {@code ARB_framebuffer_object}.  All EXT methods carry the mandatory
 * {@code EXT} suffix (e.g. {@code glBindFramebufferEXT}).</p>
 *
 * <h3>EXT-specific limitations</h3>
 * <ul>
 *   <li><strong>No separate draw/read targets</strong>: {@link #bindDraw()} and
 *       {@link #bindRead()} both bind to {@code GL_FRAMEBUFFER_EXT} (0x8D40).</li>
 *   <li><strong>No MRT</strong>: {@link #drawBuffers(int...)} always throws
 *       {@link UnsupportedOperationException}.</li>
 * </ul>
 *
 * <p>All other shared logic lives in {@link CgFrameBuffer}.  This class supplies
 * only the nine one-line GL dispatch overrides, the binding overrides, the
 * {@link #drawBuffers} override, and {@link #callFamily()}.</p>
 *
 * @see CgFrameBuffer
 * @see CgCoreFrameBuffer
 * @see CgArbFrameBuffer
 */
final class CgExtFrameBuffer extends CgFrameBuffer {

    /** {@code GL_FRAMEBUFFER_EXT = 0x8D40} — the only target EXT exposes. */
    private static final int GL_FRAMEBUFFER_EXT = 0x8D40;

    /** {@code GL_RENDERBUFFER_EXT = 0x8D41}. */
    private static final int GL_RENDERBUFFER_EXT = 0x8D41;

    CgExtFrameBuffer(String name, CgFrameBufferFormat format, int width, int height) {
        super(name, format, width, height);
    }

    @Override
    protected CallFamily callFamily() {
        return CallFamily.EXT_FBO;
    }

    /**
     * Binds using {@code GL_FRAMEBUFFER_EXT}.
     * EXT does not support separate draw and read framebuffer targets.
     */
    @Override
    public void bindDraw() {
        CrossApiTransition.bindFramebuffer(GL_FRAMEBUFFER_EXT, fboId, callFamily());
    }

    /**
     * Binds using {@code GL_FRAMEBUFFER_EXT}.
     * EXT does not support separate draw and read framebuffer targets.
     */
    @Override
    public void bindRead() {
        CrossApiTransition.bindFramebuffer(GL_FRAMEBUFFER_EXT, fboId, callFamily());
    }

    /**
     * Always throws {@link UnsupportedOperationException}.
     * {@code EXT_framebuffer_object} does not support MRT.
     */
    @Override
    public void drawBuffers(int... slotIds) {
        throw new UnsupportedOperationException("EXT_framebuffer_object does not support MRT. " + "Use Core GL30 or ARB_framebuffer_object.");
    }

    @Override
    protected int doGenFramebuffer() {
        return EXTFramebufferObject.glGenFramebuffersEXT();
    }

    @Override
    protected void deleteFramebuffer(int id) {
        EXTFramebufferObject.glDeleteFramebuffersEXT(id);
    }

    @Override
    protected void deleteRenderbuffer(int id) {
        EXTFramebufferObject.glDeleteRenderbuffersEXT(id);
    }

    /** Ignores {@code target} — EXT only exposes {@code GL_FRAMEBUFFER_EXT}. */
    @Override
    protected void doBindFbo(int target, int fboId) {
        EXTFramebufferObject.glBindFramebufferEXT(GL_FRAMEBUFFER_EXT, fboId);
    }

    @Override
    protected void doFramebufferTexture2D(int target, int attachmentPoint, int glTextureTarget, int texId) {
        EXTFramebufferObject.glFramebufferTexture2DEXT(GL_FRAMEBUFFER_EXT, attachmentPoint, glTextureTarget, texId, 0);
    }

    @Override
    protected void doFramebufferRenderbuffer(int target, int attachmentPoint, int rboId) {
        EXTFramebufferObject.glFramebufferRenderbufferEXT(GL_FRAMEBUFFER_EXT, attachmentPoint, GL_RENDERBUFFER_EXT, rboId);
    }

    @Override
    protected int doGenRenderbuffer() {
        return EXTFramebufferObject.glGenRenderbuffersEXT();
    }

    @Override
    protected void doRenderbufferStorage(int internalFormat, int w, int h) {
        EXTFramebufferObject.glRenderbufferStorageEXT(GL_RENDERBUFFER_EXT, internalFormat, w, h);
    }

    @Override
    protected int doCheckFramebufferStatus() {
        return EXTFramebufferObject.glCheckFramebufferStatusEXT(GL_FRAMEBUFFER_EXT);
    }
}
