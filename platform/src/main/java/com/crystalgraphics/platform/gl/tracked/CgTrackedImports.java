package com.crystalgraphics.platform.gl.tracked;

import com.crystalgraphics.platform.gl.CgGLImports;
import com.crystalgraphics.platform.gl.tracked.gl.TrackedBuffers;
import com.crystalgraphics.platform.gl.tracked.gl.TrackedTextures;
import com.crystalgraphics.platform.rhi.memory.CgAllocation;
import com.crystalgraphics.platform.rhi.resource.CgGpuBuffer;
import com.crystalgraphics.platform.rhi.resource.CgGpuTexture;

import java.util.IdentityHashMap;
import java.util.Map;

/** {@link CgGLImports} over the tracked backend: its image or allocation per name, and names adopted for RHI images. */
public final class CgTrackedImports implements CgGLImports {

    private final TrackedTextures textures;
    private final TrackedBuffers buffers;
    private final Map<CgGpuTexture, Integer> adopted = new IdentityHashMap<>();

    public CgTrackedImports(CgTrackedGLBackend backend) {
        this.textures = backend.textureObjects();
        this.buffers = backend.bufferObjects();
    }

    @Override
    public CgGpuTexture texture(int name, CgGpuTexture.Desc desc) {
        TrackedTextures.GlTexture t = textures.get(name);
        if (t == null || t.image == null) throw new IllegalArgumentException("texture " + name + " has no image");
        return t.image;
    }

    private CgAllocation storage(int name) {
        TrackedBuffers.GlBuffer b = buffers.get(name);
        CgAllocation a = b == null ? null : b.storage.allocation();
        if (a == null) throw new IllegalArgumentException("buffer " + name + " has no storage");
        return a;
    }

    @Override public CgGpuBuffer buffer(int name) { return storage(name).buffer(); }

    @Override public long bufferOffset(int name) { return storage(name).offset(); }

    @Override
    public int name(CgGpuTexture texture) {
        Integer known = adopted.get(texture);
        if (known != null) return known;
        int name = textures.adopt(texture);
        adopted.put(texture, name);
        return name;
    }

    @Override
    public void textureDeleted(int texture) {
        adopted.values().removeIf(n -> n == texture);
    }
}
