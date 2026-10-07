package com.crystalgraphics.render.graph;

import com.crystalgraphics.platform.gl.CgGL;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.Arrays;

/**
 * A framebuffer of ours holding a target we do not own, the host's colour and depth, beside a second colour attachment
 * of ours: what a pass with {@link CgRasterPass#attachment} draws through. The host's framebuffer is only read, never
 * changed. Render thread.
 */
final class CgComposedTargets {

    private static final int GL_OBJECT_NAME = 0x8CD1, GL_TEXTURE_LEVEL = 0x8CD2, GL_TEXTURE = 0x1702;

    private int fbo;
    /** What was last checked complete: colour name, its level and renderbuffer bit, depth, stencil, ours, width, height. */
    private final int[] checked = new int[7];
    private final int[] key = new int[7];
    private final IntBuffer drawBuffers = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder()).asIntBuffer();

    CgComposedTargets() {
        drawBuffers.put(0, CgGL.GL_COLOR_ATTACHMENT0).put(1, CgGL.GL_COLOR_ATTACHMENT1);
    }

    /**
     * Binds our framebuffer with {@code host}'s colour and depth and {@code texture} as colour attachment 1.
     * {@code host} must be bound for drawing when called.
     */
    void bind(int host, int width, int height, int texture, CgRasterPass pass) {
        if (host == 0) throw new IllegalStateException(pass + " draws a second attachment beside the default framebuffer, which takes none");
        int colorType = query(CgGL.GL_COLOR_ATTACHMENT0, CgGL.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
        if (colorType != GL_TEXTURE && colorType != CgGL.GL_RENDERBUFFER) {
            throw new IllegalStateException(pass + ": framebuffer " + host + " has no colour attachment 0 to draw beside");
        }
        int colorName = query(CgGL.GL_COLOR_ATTACHMENT0, GL_OBJECT_NAME);
        int colorLevel = colorType == GL_TEXTURE ? query(CgGL.GL_COLOR_ATTACHMENT0, GL_TEXTURE_LEVEL) : 0;
        int depthType = query(CgGL.GL_DEPTH_ATTACHMENT, CgGL.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
        int depthName = depthType == CgGL.GL_NONE ? 0 : query(CgGL.GL_DEPTH_ATTACHMENT, GL_OBJECT_NAME);
        int stencilType = query(CgGL.GL_STENCIL_ATTACHMENT, CgGL.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
        int stencilName = stencilType == CgGL.GL_NONE ? 0 : query(CgGL.GL_STENCIL_ATTACHMENT, GL_OBJECT_NAME);
        boolean made = fbo == 0;
        if (made) fbo = CgGL.glGenFramebuffers();
        CgGL.glBindFramebuffer(CgGL.GL_FRAMEBUFFER, fbo);
        // Attached at every bind and detached after (detach): a host that deletes its target and makes another gets
        // the same names back, and a framebuffer still holding the deleted objects would draw into them.
        if (colorType == GL_TEXTURE) {
            CgGL.glFramebufferTexture2D(CgGL.GL_FRAMEBUFFER, CgGL.GL_COLOR_ATTACHMENT0, CgGL.GL_TEXTURE_2D, colorName, colorLevel);
        } else {
            CgGL.glFramebufferRenderbuffer(CgGL.GL_FRAMEBUFFER, CgGL.GL_COLOR_ATTACHMENT0, CgGL.GL_RENDERBUFFER, colorName);
        }
        boolean depthStencil = depthName != 0 && stencilName == depthName;
        attach(depthStencil ? CgGL.GL_DEPTH_STENCIL_ATTACHMENT : CgGL.GL_DEPTH_ATTACHMENT, depthType, depthName);
        if (!depthStencil) attach(CgGL.GL_STENCIL_ATTACHMENT, stencilType, stencilName);
        CgGL.glFramebufferTexture2D(CgGL.GL_FRAMEBUFFER, CgGL.GL_COLOR_ATTACHMENT1, CgGL.GL_TEXTURE_2D, texture, 0);
        if (made) CgGL.glDrawBuffers(drawBuffers);
        key[0] = colorName;
        key[1] = colorLevel << 1 | (colorType == CgGL.GL_RENDERBUFFER ? 1 : 0);
        key[2] = depthName;
        key[3] = stencilName;
        key[4] = texture;
        key[5] = width;
        key[6] = height;
        if (!made && Arrays.equals(key, checked)) return;
        int status = CgGL.glCheckFramebufferStatus(CgGL.GL_FRAMEBUFFER);
        if (status != CgGL.GL_FRAMEBUFFER_COMPLETE) {
            CgGL.glBindFramebuffer(CgGL.GL_FRAMEBUFFER, host);
            throw new IllegalStateException(pass + ": framebuffer " + host + "'s colour and depth with a second attachment "
                    + "are incomplete (0x" + Integer.toHexString(status) + "): a multisampled host target takes none");
        }
        System.arraycopy(key, 0, checked, 0, key.length);
    }

    /**
     * Detaches everything from the framebuffer, which is left bound: at the end of each pass, so it never holds the
     * host's images past it.
     */
    void detach() {
        if (fbo == 0) return;
        CgGL.glBindFramebuffer(CgGL.GL_FRAMEBUFFER, fbo);
        // Slot 1 first: a draw buffer left after an empty one is refused on a device.
        CgGL.glFramebufferTexture2D(CgGL.GL_FRAMEBUFFER, CgGL.GL_COLOR_ATTACHMENT1, CgGL.GL_TEXTURE_2D, 0, 0);
        CgGL.glFramebufferTexture2D(CgGL.GL_FRAMEBUFFER, CgGL.GL_COLOR_ATTACHMENT0, CgGL.GL_TEXTURE_2D, 0, 0);
        CgGL.glFramebufferTexture2D(CgGL.GL_FRAMEBUFFER, CgGL.GL_DEPTH_ATTACHMENT, CgGL.GL_TEXTURE_2D, 0, 0);
        CgGL.glFramebufferTexture2D(CgGL.GL_FRAMEBUFFER, CgGL.GL_STENCIL_ATTACHMENT, CgGL.GL_TEXTURE_2D, 0, 0);
    }

    /** {@code name} of {@code type} at {@code point}, or nothing there. */
    private static void attach(int point, int type, int name) {
        if (type == CgGL.GL_RENDERBUFFER) CgGL.glFramebufferRenderbuffer(CgGL.GL_FRAMEBUFFER, point, CgGL.GL_RENDERBUFFER, name);
        else CgGL.glFramebufferTexture2D(CgGL.GL_FRAMEBUFFER, point, CgGL.GL_TEXTURE_2D, type == GL_TEXTURE ? name : 0, 0);
    }

    private static int query(int attachment, int pname) {
        return CgGL.glGetFramebufferAttachmentParameteriv(CgGL.GL_FRAMEBUFFER, attachment, pname);
    }

    /** Deletes the framebuffer. At context teardown. */
    void delete() {
        if (fbo != 0) CgGL.glDeleteFramebuffers(fbo);
        fbo = 0;
        Arrays.fill(checked, 0);
    }
}
