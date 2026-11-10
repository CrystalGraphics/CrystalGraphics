package com.crystalgraphics.vulkan;

import com.crystalgraphics.platform.rhi.CgHostImports;
import com.crystalgraphics.platform.rhi.command.CgAccess;
import com.crystalgraphics.platform.rhi.command.CgTransition;
import com.crystalgraphics.platform.rhi.resource.CgGpuTexture;
import com.crystalgraphics.platform.rhi.resource.CgTextureView;

import java.util.HashMap;
import java.util.Map;

/**
 * {@link CgHostImports} on the Vulkan device: framebuffer 0 is the surface, the image drawn now; a host's own target
 * is a framebuffer named over its images ({@link #framebuffer}, Minecraft 26.2's main target); a host texture's handle
 * is already a device texture.
 *
 * <pre>{@code
 * int main = device.hostImports().framebuffer(colour, depth);   // the host's images, wrapped as device textures
 * CgExecutor.into(main, width, height);                         // drawn into as any host framebuffer
 * device.hostImports().forgetFramebuffer(main);                  // when the host remakes them
 * }</pre>
 */
public final class VulkanHostImports implements CgHostImports {

    private final CgVulkanDevice device;
    /** A host framebuffer's colour and depth (null for none), by its name. */
    private final Map<Integer, CgGpuTexture[]> framebuffers = new HashMap<>();
    private int nextFramebuffer = 1;

    VulkanHostImports(CgVulkanDevice device) {
        this.device = device;
    }

    /** A name for the host's target over {@code color} and {@code depth}: what a hook draws into. */
    public int framebuffer(CgGpuTexture color, CgGpuTexture depth) {
        int name = nextFramebuffer++;
        framebuffers.put(name, new CgGpuTexture[]{color, depth});
        return name;
    }

    /** The host remade or closed {@code fbo}'s images: its name answers nothing from now on. */
    public void forgetFramebuffer(int fbo) {
        framebuffers.remove(fbo);
    }

    @Override
    public CgGpuTexture texture(Object handle) {
        if (handle instanceof CgGpuTexture t) return t;
        throw new IllegalArgumentException("a Vulkan host's texture is a device texture, not " + handle);
    }

    @Override public CgGpuTexture defaultColor(int width, int height) { return device.surfaceColor(); }

    @Override public CgGpuTexture defaultDepth(int width, int height) { return device.surfaceDepth(); }

    @Override
    public CgTextureView attachment(int fbo, boolean depth, int width, int height) {
        CgGpuTexture t = images(fbo)[depth ? 1 : 0];
        return t == null ? null : CgTextureView.attachment(t, 0, 0);
    }

    /** Its colour and depth moved; a depth read in place keeps the colour writable. */
    @Override
    public boolean framebufferBarrier(int fbo, int from, int to, CgTransition into) {
        CgGpuTexture[] images = images(fbo);
        CgGpuTexture color = images[0], depth = images[1];
        if (color != null) {
            into.texture(color, 0, 1, 0, 1, unsampled(color, from & ~CgAccess.DEPTH_READ, CgAccess.COLOR_WRITE),
                    unsampled(color, to & ~CgAccess.DEPTH_READ, CgAccess.COLOR_WRITE));
        }
        if (depth != null) {
            into.texture(depth, 0, depth.desc().mips(), 0, 1, unsampled(depth, depthAccess(from), CgAccess.DEPTH_WRITE),
                    unsampled(depth, depthAccess(to), CgAccess.DEPTH_WRITE));
        }
        return true;
    }

    private CgGpuTexture[] images(int fbo) {
        if (fbo == 0) return new CgGpuTexture[]{device.surfaceColor(), device.surfaceDepth()};
        CgGpuTexture[] images = framebuffers.get(fbo);
        if (images == null) throw new IllegalArgumentException("framebuffer " + fbo + " is no host target of this device");
        return images;
    }

    /** What a depth attachment takes of a framebuffer's access: a read-only depth sampled in place, else a write. */
    private static int depthAccess(int access) {
        if ((access & CgAccess.DEPTH_READ) != 0) return CgAccess.DEPTH_READ | CgAccess.SAMPLED_READ;
        return (access & CgAccess.COLOR_WRITE) == 0 ? access : access & ~CgAccess.COLOR_WRITE | CgAccess.DEPTH_WRITE;
    }

    /** An attachment no shader samples stays in its attachment layout for any read but a copy. */
    private static int unsampled(CgGpuTexture t, int access, int attachment) {
        if (t.desc().usage().contains(CgGpuTexture.Usage.SAMPLED)) return access;
        if ((access & (CgAccess.COPY_READ | CgAccess.COPY_WRITE | CgAccess.RESTING)) != 0 || access == 0) return access;
        return attachment;
    }
}
