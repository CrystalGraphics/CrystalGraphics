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
import com.crystalgraphics.platform.rhi.resource.CgTextureView;

import java.util.Arrays;
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
            GL_FRAMEBUFFER_ATTACHMENT_DEPTH_SIZE = 0x8216, GL_FRAMEBUFFER_ATTACHMENT_STENCIL_SIZE = 0x8217,
            GL_ATTACHMENT_TYPE = 0x8CD0, GL_ATTACHMENT_NAME = 0x8CD1, GL_ATTACHMENT_LEVEL = 0x8CD2,
            GL_ATTACHMENT_LAYER = 0x8CD4, GL_ATTACHED_TEXTURE = 0x1702,
            GL_ATTACHED_RENDERBUFFER = 0x8D41, GL_COLOR_POINT_0 = 0x8CE0, GL_DEPTH_POINT = 0x8D00;

    private final CgGLDriver gl;
    private final GLFramebuffers framebuffers;
    private final Map<Integer, GLTexture> textures = new HashMap<>();
    private final Map<Integer, GLBuffer> buffers = new HashMap<>();
    private final Map<Integer, GLTexture> renderbuffers = new HashMap<>();
    private GLTexture backColor, backDepth;
    /**
     * {@link #attachment}'s answers, each a few driver queries that wait for its queue: per entry the framebuffer, the
     * point (1 for depth), the size asked at, and the level and layer attached. A host has a handful of targets.
     */
    private int[] attachedAt = new int[16];
    private CgTextureView[] attached = new CgTextureView[4];
    private int attachedCount;

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
    public CgTextureView attachment(int fbo, boolean depth, int width, int height) {
        if (fbo == 0) {
            CgGpuTexture back = depth ? defaultDepth(width, height) : defaultColor(width, height);
            return back == null ? null : CgTextureView.attachment(back, 0, 0);
        }
        int point = depth ? 1 : 0;
        for (int i = 0; i < attachedCount; i++) {
            int at = i * 4;
            if (attachedAt[at] == fbo && attachedAt[at + 1] == point && attachedAt[at + 2] == width
                    && attachedAt[at + 3] == height) {
                return attached[i];
            }
        }
        int glPoint = depth ? GL_DEPTH_POINT : GL_COLOR_POINT_0;
        int type = gl.glGetNamedFramebufferAttachmentParameteri(fbo, glPoint, GL_ATTACHMENT_TYPE);
        CgTextureView t = null;
        if (type == GL_ATTACHED_RENDERBUFFER) {
            int name = gl.glGetNamedFramebufferAttachmentParameteri(fbo, glPoint, GL_ATTACHMENT_NAME);
            t = CgTextureView.attachment(renderbuffer(name, describeRenderbuffer(name)), 0, 0);
        } else if (type == GL_ATTACHED_TEXTURE) {
            int name = gl.glGetNamedFramebufferAttachmentParameteri(fbo, glPoint, GL_ATTACHMENT_NAME);
            // A host remaking its target (a resize) is often handed the same names back: described again, and no
            // framebuffer of ours kept over the deleted storage, which GL leaves attached to it
            GLTexture known = textures.get(name);
            if (known != null) framebuffers.forget(known);
            t = CgTextureView.attachment(texture(name, describe(name)),
                    gl.glGetNamedFramebufferAttachmentParameteri(fbo, glPoint, GL_ATTACHMENT_LEVEL),
                    Math.max(0, gl.glGetNamedFramebufferAttachmentParameteri(fbo, glPoint, GL_ATTACHMENT_LAYER)));
        }
        if (attachedCount == attached.length) {
            attached = Arrays.copyOf(attached, attachedCount * 2);
            attachedAt = Arrays.copyOf(attachedAt, attachedCount * 8);
        }
        int at = attachedCount * 4;
        attachedAt[at] = fbo;
        attachedAt[at + 1] = point;
        attachedAt[at + 2] = width;
        attachedAt[at + 3] = height;
        attached[attachedCount++] = t;
        return t;
    }

    @Override
    public void forgetAttachments() {
        Arrays.fill(attached, null);
        attachedCount = 0;
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
        if (t != null) {
            framebuffers.forget(t);
            forgetAttachments();
        }
    }

    @Override
    public void renderbufferDeleted(int renderbuffer) {
        GLTexture t = renderbuffers.remove(renderbuffer);
        if (t != null) {
            framebuffers.forget(t);
            forgetAttachments();
        }
    }

    @Override public void bufferDeleted(int buffer) { buffers.remove(buffer); }

    /** Adopted again at its next use where its size moved: the same size keeps it, as an orphaning stream does. */
    @Override
    public void bufferRespecified(int buffer, long bytes) {
        GLBuffer b = buffers.get(buffer);
        if (b != null && b.size() != bytes) buffers.remove(buffer);
    }
}
