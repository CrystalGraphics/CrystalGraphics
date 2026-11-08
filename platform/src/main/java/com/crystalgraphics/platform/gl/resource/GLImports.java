package com.crystalgraphics.platform.gl.resource;

import java.util.Set;
import java.util.EnumSet;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.platform.gl.format.GLFormats;
import com.crystalgraphics.platform.gl.CgGLImports;
import com.crystalgraphics.platform.gl.command.GLFramebuffers;
import com.crystalgraphics.platform.gl.driver.CgGLDriver;
import com.crystalgraphics.platform.rhi.resource.CgGpuBuffer;
import com.crystalgraphics.platform.rhi.resource.CgGpuTexture;

import java.util.HashMap;
import java.util.Map;

import static com.crystalgraphics.platform.gl.driver.GLC.*;

/**
 * {@link CgGLImports} on the GL device, sharing its context with {@code CgGL}: a name is wrapped in a texture or buffer
 * owning nothing, kept until the name is deleted ({@code CgGL}'s delete, or the device freeing a resource of its own);
 * an RHI resource's name is its own.
 */
public final class GLImports implements CgGLImports {

    private static final int GL_RENDERBUFFER_WIDTH = 0x8D42, GL_RENDERBUFFER_HEIGHT = 0x8D43,
            GL_RENDERBUFFER_INTERNAL_FORMAT = 0x8D44, GL_RENDERBUFFER_SAMPLES = 0x8CAB,
            GL_FRAMEBUFFER_ATTACHMENT_DEPTH_SIZE = 0x8216, GL_FRAMEBUFFER_ATTACHMENT_STENCIL_SIZE = 0x8217;

    private final CgGLDriver gl;
    private final GLFramebuffers framebuffers;
    private final Map<Integer, GLTexture> textures = new HashMap<>();
    private final Map<Integer, GLBuffer> buffers = new HashMap<>();
    private final Map<Integer, GLTexture> renderbuffers = new HashMap<>();
    private GLTexture backColor, backDepth;

    /** @param framebuffers the device's: an import replaced or deleted drops those it is attached to */
    public GLImports(CgGLDriver gl, GLFramebuffers framebuffers) {
        this.gl = gl;
        this.framebuffers = framebuffers;
    }

    @Override
    public CgGpuTexture texture(int name, CgGpuTexture.Desc desc) {
        GLTexture t = textures.get(name);
        if (t == null || !t.desc().equals(desc)) {
            if (t != null) framebuffers.forget(t);
            textures.put(name, t = GLTexture.adopt(name, desc));
        }
        return t;
    }

    @Override
    public CgGpuTexture.Desc describe(int name) {
        int internal = gl.glGetTextureLevelParameteri(name, 0, GL_TEXTURE_INTERNAL_FORMAT);
        int w = gl.glGetTextureLevelParameteri(name, 0, GL_TEXTURE_WIDTH);
        int h = gl.glGetTextureLevelParameteri(name, 0, GL_TEXTURE_HEIGHT);
        CgFormat format = GLFormats.of(internal);
        Set<CgGpuTexture.Usage> usage = EnumSet.of(CgGpuTexture.Usage.SAMPLED, CgGpuTexture.Usage.ATTACHMENT,
                CgGpuTexture.Usage.COPY_SRC, CgGpuTexture.Usage.COPY_DST);
        return new CgGpuTexture.Desc("texture " + name, CgGpuTexture.Kind.D2, format, w, h, 1, 1, 1, usage);
    }

    @Override
    public CgGpuTexture renderbuffer(int name, CgGpuTexture.Desc desc) {
        GLTexture t = renderbuffers.get(name);
        if (t == null || !t.desc().equals(desc)) {
            if (t != null) framebuffers.forget(t);
            renderbuffers.put(name, t = GLTexture.adoptRenderbuffer(name, desc));
        }
        return t;
    }

    @Override
    public CgGpuTexture.Desc describeRenderbuffer(int name) {
        CgFormat format = GLFormats.of(gl.glGetNamedRenderbufferParameteri(name, GL_RENDERBUFFER_INTERNAL_FORMAT));
        int w = gl.glGetNamedRenderbufferParameteri(name, GL_RENDERBUFFER_WIDTH);
        int h = gl.glGetNamedRenderbufferParameteri(name, GL_RENDERBUFFER_HEIGHT);
        int samples = Math.max(1, gl.glGetNamedRenderbufferParameteri(name, GL_RENDERBUFFER_SAMPLES));
        return new CgGpuTexture.Desc("renderbuffer " + name, CgGpuTexture.Kind.D2, format, w, h, 1, 1, samples,
                EnumSet.of(CgGpuTexture.Usage.ATTACHMENT));
    }

    @Override
    public CgGpuTexture defaultColor(int width, int height) {
        if (backColor == null || backColor.desc().width() != width || backColor.desc().height() != height) {
            backColor = GLTexture.backBuffer(new CgGpuTexture.Desc("default framebuffer", CgGpuTexture.Kind.D2,
                    CgFormat.RGBA8_UNORM, width, height, 1, 1, 1, EnumSet.of(CgGpuTexture.Usage.ATTACHMENT)));
        }
        return backColor;
    }

    @Override
    public CgGpuTexture defaultDepth(int width, int height) {
        if (backDepth == null || backDepth.desc().width() != width || backDepth.desc().height() != height) {
            int depth = gl.glGetNamedFramebufferAttachmentParameteri(0, GL_DEPTH, GL_FRAMEBUFFER_ATTACHMENT_DEPTH_SIZE);
            if (depth == 0) return null;
            int stencil = gl.glGetNamedFramebufferAttachmentParameteri(0, GL_STENCIL, GL_FRAMEBUFFER_ATTACHMENT_STENCIL_SIZE);
            backDepth = GLTexture.backBuffer(new CgGpuTexture.Desc("default framebuffer depth", CgGpuTexture.Kind.D2,
                    stencil > 0 ? CgFormat.DEPTH24_PLUS_STENCIL8 : CgFormat.DEPTH24_PLUS, width, height, 1, 1, 1,
                    EnumSet.of(CgGpuTexture.Usage.ATTACHMENT)));
        }
        return backDepth;
    }

    @Override
    public CgGpuBuffer buffer(int name) {
        GLBuffer b = buffers.get(name);
        if (b == null) buffers.put(name, b = GLBuffer.adopt(gl, name));
        return b;
    }

    @Override public long bufferOffset(int name) { return 0; }

    @Override public int name(CgGpuTexture texture) { return ((GLTexture) texture).name; }

    @Override public int name(CgGpuBuffer buffer) { return ((GLBuffer) buffer).name; }

    @Override
    public void textureDeleted(int texture) {
        GLTexture t = textures.remove(texture);
        if (t != null) framebuffers.forget(t);
    }

    @Override
    public void renderbufferDeleted(int renderbuffer) {
        GLTexture t = renderbuffers.remove(renderbuffer);
        if (t != null) framebuffers.forget(t);
    }

    @Override public void bufferDeleted(int buffer) { buffers.remove(buffer); }

    /** Adopted again at its next use where its size moved: the same size keeps it, as an orphaning stream does. */
    @Override
    public void bufferRespecified(int buffer, long bytes) {
        GLBuffer b = buffers.get(buffer);
        if (b != null && b.size() != bytes) buffers.remove(buffer);
    }
}
