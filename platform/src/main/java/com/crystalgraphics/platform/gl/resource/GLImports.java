package com.crystalgraphics.platform.gl.resource;

import com.crystalgraphics.platform.gl.CgGLImports;
import com.crystalgraphics.platform.gl.driver.CgGLDriver;
import com.crystalgraphics.platform.rhi.resource.CgGpuBuffer;
import com.crystalgraphics.platform.rhi.resource.CgGpuTexture;

import java.util.HashMap;
import java.util.Map;

/**
 * {@link CgGLImports} on the GL device, sharing its context with {@code CgGL}: a name is wrapped in a texture or buffer
 * owning nothing, kept until {@code CgGL} deletes the name; an RHI resource's name is its own.
 */
public final class GLImports implements CgGLImports {

    private final CgGLDriver gl;
    private final Map<Integer, GLTexture> textures = new HashMap<>();
    private final Map<Integer, GLBuffer> buffers = new HashMap<>();

    public GLImports(CgGLDriver gl) {
        this.gl = gl;
    }

    @Override
    public CgGpuTexture texture(int name, CgGpuTexture.Desc desc) {
        GLTexture t = textures.get(name);
        if (t == null || !t.desc().equals(desc)) textures.put(name, t = GLTexture.adopt(name, desc));
        return t;
    }

    @Override
    public CgGpuBuffer buffer(int name) {
        GLBuffer b = buffers.get(name);
        if (b == null) buffers.put(name, b = GLBuffer.adopt(gl, name));
        return b;
    }

    @Override public long bufferOffset(int name) { return 0; }

    @Override public int name(CgGpuTexture texture) { return ((GLTexture) texture).name; }

    @Override public void textureDeleted(int texture) { textures.remove(texture); }

    @Override public void bufferDeleted(int buffer) { buffers.remove(buffer); }
}
