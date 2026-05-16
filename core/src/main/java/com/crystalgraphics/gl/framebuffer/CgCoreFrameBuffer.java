package com.crystalgraphics.gl.framebuffer;

import com.crystalgraphics.api.framebuffer.CgFrameBufferFormat;
import com.crystalgraphics.gl.state.CallFamily;

import com.crystalgraphics.platform.gl.CgGL;

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
