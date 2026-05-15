package com.crystalgraphics.gl.framebuffer;

import com.crystalgraphics.api.framebuffer.CgFrameBufferFormat;
import com.crystalgraphics.gl.state.CallFamily;

import org.lwjgl.opengl.GL30;

/**
 * Framebuffer backend that routes all GL dispatch through Core OpenGL 3.0
 * entry points ({@link GL30}).
 *
 * <p>This is the preferred backend on hardware that supports GL 3.0 or later.
 * All shared logic (attachment allocation, completeness check, reattach,
 * drawBuffers, etc.) lives in {@link CgFrameBuffer}.  This class supplies
 * only the nine one-line GL dispatch overrides and {@link #callFamily()}.</p>
 *
 * @see CgFrameBuffer
 * @see CgArbFrameBuffer
 * @see CgExtFrameBuffer
 */
final class CgCoreFrameBuffer extends CgFrameBuffer {

    CgCoreFrameBuffer(String name, CgFrameBufferFormat format, int width, int height) {
        super(name, format, width, height);
    }

    @Override
    protected CallFamily callFamily() {
        return CallFamily.CORE_GL30;
    }

    @Override
    protected int doGenFramebuffer() {
        return GL30.glGenFramebuffers();
    }

    @Override
    protected void deleteFramebuffer(int id) {
        GL30.glDeleteFramebuffers(id);
    }

    @Override
    protected void deleteRenderbuffer(int id) {
        GL30.glDeleteRenderbuffers(id);
    }

    @Override
    protected void doBindFbo(int target, int fboId) {
        GL30.glBindFramebuffer(target, fboId);
    }

    @Override
    protected void doFramebufferTexture2D(int target, int attachmentPoint, int glTextureTarget, int texId) {
        GL30.glFramebufferTexture2D(target, attachmentPoint, glTextureTarget, texId, 0);
    }

    @Override
    protected void doFramebufferRenderbuffer(int target, int attachmentPoint, int rboId) {
        GL30.glFramebufferRenderbuffer(target, attachmentPoint, GL30.GL_RENDERBUFFER, rboId);
    }

    @Override
    protected int doGenRenderbuffer() {
        return GL30.glGenRenderbuffers();
    }

    @Override
    protected void doRenderbufferStorage(int internalFormat, int w, int h) {
        GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, internalFormat, w, h);
    }

    @Override
    protected int doCheckFramebufferStatus() {
        return GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
    }
}
