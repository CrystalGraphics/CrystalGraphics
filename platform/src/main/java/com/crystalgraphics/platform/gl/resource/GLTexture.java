package com.crystalgraphics.platform.gl.resource;

import com.crystalgraphics.platform.gl.driver.CgGLDriver;
import com.crystalgraphics.platform.gl.format.GLFormats;
import com.crystalgraphics.platform.rhi.resource.CgGpuTexture;
import com.crystalgraphics.platform.rhi.resource.CgTextureView;

import java.util.ArrayList;
import java.util.List;

import static com.crystalgraphics.platform.gl.driver.GLC.*;

/**
 * A texture with immutable storage. A view of part of its mips or layers is a {@code glTextureView}, made on the first
 * ask and kept until the texture goes. The whole of one with several levels is a view too: its own name is shared with
 * the GL front, which pins the levels it samples (base and max level are texture state, under any sampler).
 */
public final class GLTexture implements CgGpuTexture {

    public final int name;
    public final int target;
    private final Desc desc;
    private final List<CgTextureView> viewKeys = new ArrayList<>();
    private final List<Integer> viewNames = new ArrayList<>();
    private final boolean owned;

    public GLTexture(CgGLDriver gl, Desc desc) {
        this.desc = desc;
        this.target = target(desc);
        this.name = gl.glCreateTextures(target);
        int internal = GLFormats.internal(desc.format());
        switch (desc.kind()) {
            case D2 -> {
                if (desc.samples() > 1) gl.glTextureStorage2DMultisample(name, desc.samples(), internal, desc.width(), desc.height());
                else gl.glTextureStorage2D(name, desc.mips(), internal, desc.width(), desc.height());
            }
            case CUBE -> gl.glTextureStorage2D(name, desc.mips(), internal, desc.width(), desc.height());
            case D2_ARRAY, D3 -> gl.glTextureStorage3D(name, desc.mips(), internal, desc.width(), desc.height(),
                    desc.depthOrLayers());
        }
        gl.glObjectLabel(GL_TEXTURE, name, desc.label());
        owned = true;
    }

    private GLTexture(int name, int target, Desc desc) {
        this.desc = desc;
        this.target = target;
        this.name = name;
        this.owned = false;
    }

    /** {@code name}, made elsewhere with {@code desc}, as a texture that never deletes it. */
    public static GLTexture adopt(int name, Desc desc) {
        return new GLTexture(name, target(desc), desc);
    }

    /** Renderbuffer {@code name}, made elsewhere with {@code desc}: an attachment only, never deleted here. */
    public static GLTexture adoptRenderbuffer(int name, Desc desc) {
        return new GLTexture(name, GL_RENDERBUFFER, desc);
    }

    /**
     * The default framebuffer's colour or depth, {@code desc}: name 0, which {@link
     * com.crystalgraphics.platform.gl.command.GLFramebuffers} answers as framebuffer 0 (Unreal's back buffer).
     */
    public static GLTexture backBuffer(Desc desc) {
        return new GLTexture(0, GL_NONE, desc);
    }

    /** The GL texture target a description makes. */
    public static int target(Desc d) {
        return switch (d.kind()) {
            case D2 -> d.samples() > 1 ? GL_TEXTURE_2D_MULTISAMPLE : GL_TEXTURE_2D;
            case D2_ARRAY -> GL_TEXTURE_2D_ARRAY;
            case D3 -> GL_TEXTURE_3D;
            case CUBE -> GL_TEXTURE_CUBE_MAP;
        };
    }

    @Override public Desc desc() { return desc; }

    /** Whether its name is the device's own, freed by {@link #delete}; false for a name adopted from elsewhere. */
    public boolean owned() { return owned; }

    /**
     * The texture name {@code view} samples: a texture view of the range, or this texture's own for the whole of one
     * that is adopted (mutable storage takes no view) or has a single level.
     */
    public int sampled(CgGLDriver gl, CgTextureView view) {
        if (view.baseMip() == 0 && view.mips() == desc.mips() && view.baseLayer() == 0
                && (desc.kind() == Kind.D3 || view.layers() == desc.depthOrLayers()) && (!owned || desc.mips() == 1)) {
            return name;
        }
        int at = viewKeys.indexOf(view);
        if (at >= 0) return viewNames.get(at);
        int v = gl.glGenTextures();
        gl.glTextureView(v, target, name, GLFormats.internal(desc.format()), view.baseMip(), view.mips(), view.baseLayer(),
                view.layers());
        viewKeys.add(view);
        viewNames.add(v);
        return v;
    }

    /** Deletes the texture and its views. */
    public void delete(CgGLDriver gl) {
        for (int v : viewNames) gl.glDeleteTextures(v);
        if (owned) gl.glDeleteTextures(name);
    }
}
