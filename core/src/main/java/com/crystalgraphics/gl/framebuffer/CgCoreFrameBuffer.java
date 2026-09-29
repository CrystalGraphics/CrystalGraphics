package com.crystalgraphics.gl.framebuffer;

import com.crystalgraphics.api.framebuffer.CgFrameBufferFormat;

import com.crystalgraphics.platform.gl.CgGL;

/**
 * The owned framebuffer: GL dispatch through {@link CgGL}'s core GL 3.0 entry points. All shared logic
 * (attachment allocation, completeness check, reattach, drawBuffers) lives in {@link CgFrameBuffer}; this
 * class supplies only the one-line GL dispatch overrides.
 *
 * @see CgFrameBuffer
 */
final class CgCoreFrameBuffer extends CgFrameBuffer {

    CgCoreFrameBuffer(String name, CgFrameBufferFormat format, int width, int height) {
        super(name, format, width, height);
    }


    @Override
    protected int doGenFramebuffer() {
        return CgGL.glGenFramebuffers();
    }

    @Override
    protected void deleteFramebuffer(int id) {
        CgGL.glDeleteFramebuffers(id);
    }

    @Override
    protected void deleteRenderbuffer(int id) {
        CgGL.glDeleteRenderbuffers(id);
    }

    @Override
    protected void doBindFbo(int target, int fboId) {
        CgGL.glBindFramebuffer(target, fboId);
    }

    @Override
    protected void doFramebufferTexture2D(int target, int attachmentPoint, int glTextureTarget, int texId) {
        CgGL.glFramebufferTexture2D(target, attachmentPoint, glTextureTarget, texId, 0);
    }

    @Override
    protected void doFramebufferRenderbuffer(int target, int attachmentPoint, int rboId) {
        CgGL.glFramebufferRenderbuffer(target, attachmentPoint, CgGL.GL_RENDERBUFFER, rboId);
    }

    @Override
    protected int doGenRenderbuffer() {
        return CgGL.glGenRenderbuffers();
    }

    @Override
    protected void doRenderbufferStorage(int internalFormat, int w, int h) {
        CgGL.glRenderbufferStorage(CgGL.GL_RENDERBUFFER, internalFormat, w, h);
    }

    @Override
    protected int doCheckFramebufferStatus() {
        return CgGL.glCheckFramebufferStatus(CgGL.GL_FRAMEBUFFER);
    }
}
