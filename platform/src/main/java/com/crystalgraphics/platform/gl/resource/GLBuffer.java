package com.crystalgraphics.platform.gl.resource;

import com.crystalgraphics.platform.gl.driver.CgGLDriver;
import com.crystalgraphics.platform.rhi.resource.CgGpuBuffer;

import com.crystalgraphics.platform.gl.format.GLFormats;
import com.crystalgraphics.platform.rhi.format.CgFormat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import static com.crystalgraphics.platform.gl.driver.GLC.*;

/**
 * A buffer with immutable storage. Host-visible ones are mapped once, persistently and coherently, as Unreal maps its
 * readback staging (`OpenGLCommands.cpp`): a write lands without a flush, and a read sees the GPU's writes once their
 * frame's fence has signalled.
 */
public final class GLBuffer implements CgGpuBuffer {

    public final int name;
    private final Desc desc;
    private final ByteBuffer mapped;
    private List<long[]> texels;   // offset, size, format ordinal, texture name

    public GLBuffer(CgGLDriver gl, Desc desc) {
        this.desc = desc;
        this.name = gl.glCreateBuffers();
        int flags = GL_DYNAMIC_STORAGE_BIT;
        int access = 0;
        if (desc.hostVisible()) {
            access = GL_MAP_PERSISTENT_BIT | GL_MAP_COHERENT_BIT | GL_MAP_WRITE_BIT | (desc.hostReads() ? GL_MAP_READ_BIT : 0);
            flags |= access | (desc.hostReads() ? GL_CLIENT_STORAGE_BIT : 0);
        }
        gl.glNamedBufferStorage(name, Math.max(4, desc.size()), flags);
        gl.glObjectLabel(GL_BUFFER, name, desc.label());
        mapped = desc.hostVisible() ? gl.glMapNamedBufferRange(name, 0, desc.size(), access).order(ByteOrder.nativeOrder()) : null;
    }

    public Desc desc() { return desc; }

    @Override public String label() { return desc.label(); }

    @Override public long size() { return desc.size(); }

    @Override public boolean hostVisible() { return desc.hostVisible(); }

    @Override
    public ByteBuffer mapped() {
        if (mapped == null) throw new IllegalStateException(desc.label() + " is not host-visible");
        return mapped;
    }

    /** A buffer texture over {@code size} bytes at {@code offset}, read as {@code format}: made on the first ask, kept. */
    public int texel(CgGLDriver gl, long offset, long size, CgFormat format) {
        if (texels == null) texels = new ArrayList<>(2);
        for (int i = 0; i < texels.size(); i++) {
            long[] t = texels.get(i);
            if (t[0] == offset && t[1] == size && t[2] == format.ordinal()) return (int) t[3];
        }
        int texture = gl.glCreateTextures(GL_TEXTURE_BUFFER);
        gl.glTextureBufferRange(texture, GLFormats.internal(format), name, offset, size);
        texels.add(new long[] {offset, size, format.ordinal(), texture});
        return texture;
    }

    /** Deletes the buffer and its buffer textures. */
    public void delete(CgGLDriver gl) {
        if (texels != null) for (long[] t : texels) gl.glDeleteTextures((int) t[3]);
        gl.glDeleteBuffers(name);
    }
}
