package com.crystalgraphics.platform.rhi;

import com.crystalgraphics.platform.rhi.command.CgTransition;
import com.crystalgraphics.platform.rhi.resource.CgGpuTexture;
import com.crystalgraphics.platform.rhi.resource.CgTextureView;

/**
 * What a host owns, as the device's resources (RDG's {@code RegisterExternalTexture}): a texture the host hands over,
 * and the attachments of a framebuffer the host draws into, known by its name. Installed by the device that runs.
 *
 * <pre>{@code
 * CgHostImports host = CgHostImports.get();
 * CgGpuTexture lightmap = host.texture(hostHandle);                 // Minecraft's: a GL name, or a 26.2 image
 * CgTextureView colour = host.attachment(hostFbo, false, w, h);     // the host's target
 * CgTextureView depth = host.attachment(hostFbo, true, w, h);       // null for a target without one
 * }</pre>
 *
 * <ul>
 *   <li>Borrowed: nothing here owns or deletes what it hands back.</li>
 *   <li>Answers are kept; {@link #forgetAttachments} when the host may have remade its targets (a resize).</li>
 *   <li>Render thread only.</li>
 * </ul>
 */
public interface CgHostImports {

    /**
     * The host's texture behind {@code handle}: an {@code Integer} GL name on a GL host, a device texture the host's own
     * images were wrapped in on a device host. The same texture for the same handle.
     */
    CgGpuTexture texture(Object handle);

    /**
     * The default framebuffer's colour, {@code width} by {@code height} (Unreal's back buffer): a pass drawing to it draws
     * to framebuffer 0 on GL, to the surface on a device presenting one. An attachment only.
     */
    CgGpuTexture defaultColor(int width, int height);

    /** The default framebuffer's depth beside {@link #defaultColor}; null where it has none. */
    CgGpuTexture defaultDepth(int width, int height);

    /**
     * The host framebuffer {@code fbo}'s colour attachment 0, or with {@code depth} its depth, as the device texture's
     * level and layer attached ({@link #defaultColor} and {@link #defaultDepth} for 0). Null where nothing is attached.
     */
    CgTextureView attachment(int fbo, boolean depth, int width, int height);

    /** What {@link #attachment} learned of framebuffers is stale: a host's targets may be remade, as at a resize. */
    default void forgetAttachments() {}

    /**
     * Framebuffer {@code fbo}'s attachments (0 the window's) moved from {@code from} to {@code to}, added to
     * {@code into}. False where the device names no image for it (GL, whose framebuffer barrier is a memory barrier).
     */
    default boolean framebufferBarrier(int fbo, int from, int to, CgTransition into) {
        return false;
    }

    /**
     * The imports of a device with nothing of a host's but its surface (a test's null device, an owned window's):
     * framebuffer 0 is {@code rhi}'s surface, a texture's handle a device texture.
     */
    static CgHostImports surface(CgRHI rhi) {
        return new CgHostImports() {
            /** The surface's two views, kept while it is the same texture: asked at every {@code into}. */
            private CgTextureView color, depth;

            @Override
            public CgGpuTexture texture(Object handle) {
                if (handle instanceof CgGpuTexture t) return t;
                throw new IllegalArgumentException("not a device texture: " + handle);
            }

            @Override public CgGpuTexture defaultColor(int width, int height) { return rhi.surfaceColor(); }

            @Override public CgGpuTexture defaultDepth(int width, int height) { return rhi.surfaceDepth(); }

            @Override
            public CgTextureView attachment(int fbo, boolean depth, int width, int height) {
                if (fbo != 0) throw new IllegalArgumentException("framebuffer " + fbo + ": this device names none but 0");
                CgGpuTexture t = depth ? rhi.surfaceDepth() : rhi.surfaceColor();
                if (t == null) return null;
                CgTextureView kept = depth ? this.depth : color;
                if (kept != null && kept.texture() == t) return kept;
                kept = CgTextureView.attachment(t, 0, 0);
                if (depth) this.depth = kept;
                else color = kept;
                return kept;
            }
        };
    }

    /** The imports installed now; throws until a device installs them. */
    static CgHostImports get() {
        CgHostImports i = Slot.installed;
        if (i == null) throw new IllegalStateException("no host imports installed: a device installs them as it starts");
        return i;
    }

    /** Whether imports are installed. */
    static boolean installed() { return Slot.installed != null; }

    /** Installs {@code imports}; null when the device goes. */
    static void install(CgHostImports imports) { Slot.installed = imports; }

    /** Where {@link #get} reads from. */
    final class Slot {
        static volatile CgHostImports installed;

        private Slot() {}
    }
}
