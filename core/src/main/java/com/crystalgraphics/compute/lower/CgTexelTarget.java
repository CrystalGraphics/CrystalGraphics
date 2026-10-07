package com.crystalgraphics.compute.lower;

import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.platform.gl.CgGL;

import java.nio.ByteBuffer;

/**
 * A 2D texture a lowered pass renders into, with the framebuffer that writes it: a scatter target, the count texel,
 * a copy of an image a kernel reads while it writes it. Nearest-filtered with no mipmaps, since {@code texelFetch} reads
 * an incomplete texture (an integer one filtered linearly) as zero. Render thread.
 *
 * <pre>{@code
 * CgTexelTarget t = CgTexelTarget.create(CgTextureType.R32F, 1, 1);
 * CgGL.glBindFramebuffer(CgGL.GL_FRAMEBUFFER, t.framebuffer());
 * ... draw ...
 * t.delete();
 * }</pre>
 */
public final class CgTexelTarget {

    private final CgTextureType type;
    private final int width, height, texture, framebuffer;

    private CgTexelTarget(CgTextureType type, int width, int height, int texture, int framebuffer) {
        this.type = type;
        this.width = width;
        this.height = height;
        this.texture = texture;
        this.framebuffer = framebuffer;
    }

    public static CgTexelTarget create(CgTextureType type, int width, int height) {
        // Made mid-dispatch, after the sampler properties are bound: the active unit's texture is put back.
        int bound = CgGL.glGetInteger(CgGL.GL_TEXTURE_BINDING_2D);
        int texture = CgGL.glGenTextures();
        CgGL.glBindTexture(CgGL.GL_TEXTURE_2D, texture);
        CgGL.glTexImage2D(CgGL.GL_TEXTURE_2D, 0, type.glInternalFormat, width, height, 0, type.glBaseFormat, type.glType, (ByteBuffer) null);
        CgGL.glTexParameteri(CgGL.GL_TEXTURE_2D, CgGL.GL_TEXTURE_MIN_FILTER, CgGL.GL_NEAREST);
        CgGL.glTexParameteri(CgGL.GL_TEXTURE_2D, CgGL.GL_TEXTURE_MAG_FILTER, CgGL.GL_NEAREST);
        CgGL.glBindTexture(CgGL.GL_TEXTURE_2D, bound);
        int framebuffer = CgGL.glGenFramebuffers();
        CgGL.glBindFramebuffer(CgGL.GL_FRAMEBUFFER, framebuffer);
        CgGL.glFramebufferTexture2D(CgGL.GL_FRAMEBUFFER, CgGL.GL_COLOR_ATTACHMENT0, CgGL.GL_TEXTURE_2D, texture, 0);
        int status = CgGL.glCheckFramebufferStatus(CgGL.GL_FRAMEBUFFER);
        if (status != CgGL.GL_FRAMEBUFFER_COMPLETE) {
            CgGL.glDeleteFramebuffers(framebuffer);
            CgGL.glDeleteTextures(texture);
            throw new IllegalStateException(type + " " + width + "x" + height + " is no render target here: 0x"
                    + Integer.toHexString(status));
        }
        return new CgTexelTarget(type, width, height, texture, framebuffer);
    }

    public CgTextureType type() { return type; }

    public int width() { return width; }

    public int height() { return height; }

    public int texture() { return texture; }

    public int framebuffer() { return framebuffer; }

    public void delete() {
        CgGL.glDeleteFramebuffers(framebuffer);
        CgGL.glDeleteTextures(texture);
    }
}
