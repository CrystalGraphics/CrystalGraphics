package com.crystalgraphics.platform.gl.tracked;

import com.crystalgraphics.platform.gl.CgGLImports;
import com.crystalgraphics.platform.gl.tracked.gl.TrackedBuffers;
import com.crystalgraphics.platform.gl.tracked.gl.TrackedFramebuffers;
import com.crystalgraphics.platform.gl.tracked.gl.TrackedTextures;
import com.crystalgraphics.platform.gl.tracked.tracker.CgTracker;
import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.platform.rhi.command.CgTransition;
import com.crystalgraphics.platform.rhi.memory.CgAllocation;
import com.crystalgraphics.platform.rhi.resource.CgGpuBuffer;
import com.crystalgraphics.platform.rhi.resource.CgGpuTexture;

import java.util.IdentityHashMap;
import java.util.Map;

/** {@link CgGLImports} over the tracked backend: its image or allocation per name, and names adopted for RHI images. */
public final class CgTrackedImports implements CgGLImports {

    private final TrackedTextures textures;
    private final TrackedBuffers buffers;
    private final TrackedFramebuffers framebuffers;
    private final CgRHI device;
    private final CgTracker tracker;
    private final Map<CgGpuTexture, Integer> adopted = new IdentityHashMap<>();
    private final Map<CgGpuBuffer, Integer> adoptedBuffers = new IdentityHashMap<>();

    public CgTrackedImports(CgTrackedGLBackend backend) {
        this.textures = backend.textureObjects();
        this.buffers = backend.bufferObjects();
        this.framebuffers = backend.framebufferObjects();
        this.device = backend.device();
        this.tracker = backend.tracker();
    }

    @Override
    public boolean framebufferBarrier(int fbo, int from, int to, CgTransition into) {
        framebuffers.barrier(fbo, from, to, into::texture);
        return true;
    }

    @Override
    public CgGpuTexture texture(int name, CgGpuTexture.Desc desc) {
        TrackedTextures.GlTexture t = textures.get(name);
        if (t == null || t.image == null) throw new IllegalArgumentException("texture " + name + " has no image");
        return t.image;
    }

    @Override
    public CgGpuTexture.Desc describe(int name) {
        TrackedTextures.GlTexture t = textures.get(name);
        if (t == null || t.image == null) throw new IllegalArgumentException("texture " + name + " has no image");
        return t.image.desc();
    }

    @Override
    public CgGpuTexture renderbuffer(int name, CgGpuTexture.Desc desc) {
        CgGpuTexture image = framebuffers.renderbufferImage(name);
        if (image == null) throw new IllegalArgumentException("renderbuffer " + name + " has no image");
        return image;
    }

    @Override
    public CgGpuTexture.Desc describeRenderbuffer(int name) {
        return renderbuffer(name, null).desc();
    }

    /** Framebuffer 0 is the surface, the swapchain image drawn now. */
    @Override public CgGpuTexture defaultColor(int width, int height) { return device.surfaceColor(); }

    @Override public CgGpuTexture defaultDepth(int width, int height) { return device.surfaceDepth(); }

    private CgAllocation storage(int name) {
        TrackedBuffers.GlBuffer b = buffers.get(name);
        CgAllocation a = b == null ? null : b.storage.allocation();
        if (a == null) throw new IllegalArgumentException("buffer " + name + " has no storage");
        return a;
    }

    /**
     * Marked used this frame, as a draw through the tracker marks what it binds: a buffer handed to device work is in
     * flight, so an upload to it must not write over it in place.
     */
    @Override
    public CgGpuBuffer buffer(int name) {
        CgAllocation a = storage(name);
        tracker.markUsed(a);
        return a.buffer();
    }

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
    public int name(CgGpuBuffer buffer) {
        Integer known = adoptedBuffers.get(buffer);
        if (known != null) return known;
        int name = buffers.adopt(buffer);
        adoptedBuffers.put(buffer, name);
        return name;
    }

    @Override
    public void forget(CgGpuTexture texture) {
        Integer name = adopted.remove(texture);
        if (name != null) textures.delete(name);
    }

    @Override
    public void forget(CgGpuBuffer buffer) {
        Integer name = adoptedBuffers.remove(buffer);
        if (name != null) buffers.delete(name);
    }

    @Override
    public void bufferDeleted(int buffer) {
        adoptedBuffers.values().removeIf(n -> n == buffer);
    }

    @Override
    public void textureDeleted(int texture) {
        adopted.values().removeIf(n -> n == texture);
    }
}
