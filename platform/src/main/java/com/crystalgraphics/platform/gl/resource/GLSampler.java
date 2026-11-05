package com.crystalgraphics.platform.gl.resource;

import com.crystalgraphics.platform.gl.driver.CgGLDriver;
import com.crystalgraphics.platform.gl.format.GLFormats;
import com.crystalgraphics.platform.rhi.resource.CgGpuSampler;

import static com.crystalgraphics.platform.gl.driver.GLC.*;

/** A sampler object, its parameters set once. */
public final class GLSampler implements CgGpuSampler {

    public final int name;
    private final Desc desc;

    public GLSampler(CgGLDriver gl, Desc desc, float maxAnisotropy) {
        this.desc = desc;
        this.name = gl.glCreateSamplers();
        gl.glSamplerParameteri(name, GL_TEXTURE_MAG_FILTER, desc.mag() == Filter.LINEAR ? GL_LINEAR : GL_NEAREST);
        gl.glSamplerParameteri(name, GL_TEXTURE_MIN_FILTER, minFilter(desc));
        gl.glSamplerParameteri(name, GL_TEXTURE_WRAP_S, wrap(desc.u()));
        gl.glSamplerParameteri(name, GL_TEXTURE_WRAP_T, wrap(desc.v()));
        gl.glSamplerParameteri(name, GL_TEXTURE_WRAP_R, wrap(desc.w()));
        gl.glSamplerParameterf(name, GL_TEXTURE_MIN_LOD, desc.minLod());
        gl.glSamplerParameterf(name, GL_TEXTURE_MAX_LOD, desc.maxLod());
        if (desc.maxAnisotropy() > 1f && maxAnisotropy > 1f)
            gl.glSamplerParameterf(name, GL_TEXTURE_MAX_ANISOTROPY, Math.min(desc.maxAnisotropy(), maxAnisotropy));
        if (desc.compare() != null) {
            gl.glSamplerParameteri(name, GL_TEXTURE_COMPARE_MODE, GL_COMPARE_REF_TO_TEXTURE);
            gl.glSamplerParameteri(name, GL_TEXTURE_COMPARE_FUNC, GLFormats.compare(desc.compare()));
        }
    }

    private static int minFilter(Desc d) {
        boolean linear = d.min() == Filter.LINEAR;
        return switch (d.mip()) {
            case NONE -> linear ? GL_LINEAR : GL_NEAREST;
            case NEAREST -> linear ? GL_LINEAR_MIPMAP_NEAREST : GL_NEAREST_MIPMAP_NEAREST;
            case LINEAR -> linear ? GL_LINEAR_MIPMAP_LINEAR : GL_NEAREST_MIPMAP_LINEAR;
        };
    }

    private static int wrap(Wrap w) {
        return switch (w) {
            case REPEAT -> GL_REPEAT;
            case MIRRORED_REPEAT -> GL_MIRRORED_REPEAT;
            case CLAMP_TO_EDGE -> GL_CLAMP_TO_EDGE;
            case CLAMP_TO_BORDER -> GL_CLAMP_TO_BORDER;
        };
    }

    @Override public Desc desc() { return desc; }

    @Override public String label() { return "sampler"; }
}
