package com.crystalgraphics.platform.gl;

import com.crystalgraphics.platform.rhi.resource.CgGpuBuffer;
import com.crystalgraphics.platform.rhi.resource.CgGpuTexture;

/**
 * Engine objects still made through {@link CgGL} as RHI resources, and RHI resources as {@code CgGL} names, while the
 * two draw side by side (RDG's {@code RegisterExternalTexture}): the executor imports a texture or buffer by its name,
 * and code still on {@code CgGL} reaches what the executor made by the name this gives it. Deleted with {@code CgGL}.
 *
 * <pre>{@code
 * CgGLImports imports = CgGLImports.get();
 * CgGpuTexture albedo = imports.texture(texture.getId(), desc);   // a CgGL texture, for a binding set
 * CgGpuBuffer vertices = imports.buffer(meshBuffer);              // a CgGL buffer, at imports.bufferOffset(meshBuffer)
 * int name = imports.name(graphTexture);                          // an RHI texture, for CgGL code
 * imports.forget(graphTexture);                                  // before releasing it
 * rhi.release(graphTexture);
 * }</pre>
 *
 * <ul>
 *   <li>Installed by the device that runs: the tracked backend's maps on Vulkan, wrappers owning nothing on GL.</li>
 *   <li>A buffer's storage may move when it is respecified: read its buffer and offset when binding, never keep them.</li>
 *   <li>Render thread only.</li>
 * </ul>
 */
public interface CgGLImports {

    /** The RHI texture behind {@code name}, which has {@code desc}. */
    CgGpuTexture texture(int name, CgGpuTexture.Desc desc);

    /**
     * {@code name}'s own description, a 2D texture made elsewhere (a host's target): its format and size as the device
     * sees them, sampled and drawn into, one level. What {@link #texture} is asked with for a name nothing described.
     */
    CgGpuTexture.Desc describe(int name);

    /** Renderbuffer {@code name} as an RHI texture, which has {@code desc}: an attachment only, never sampled. */
    CgGpuTexture renderbuffer(int name, CgGpuTexture.Desc desc);

    /** Renderbuffer {@code name}'s own description: its format and size, an attachment only. */
    CgGpuTexture.Desc describeRenderbuffer(int name);

    /**
     * The default framebuffer's colour, {@code width} by {@code height}, as an RHI texture (Unreal's back buffer): a pass
     * drawing to it draws to framebuffer 0 on GL, to the surface on a device presenting one. An attachment only.
     */
    CgGpuTexture defaultColor(int width, int height);

    /** The default framebuffer's depth beside {@link #defaultColor}; null where it has none. */
    CgGpuTexture defaultDepth(int width, int height);

    /** The RHI buffer holding {@code name}'s storage now. */
    CgGpuBuffer buffer(int name);

    /** Where in {@link #buffer} {@code name}'s storage starts. */
    long bufferOffset(int name);

    /** A {@code CgGL} name for {@code texture}, the same each ask. */
    int name(CgGpuTexture texture);

    /** A {@code CgGL} name for {@code buffer}, the same each ask; its storage starts at offset 0. */
    int name(CgGpuBuffer buffer);

    /** {@code texture} is being released: drop the name given it, which {@code CgGL} must not use again. */
    default void forget(CgGpuTexture texture) {}

    /** {@code buffer} is being released: drop the name given it. */
    default void forget(CgGpuBuffer buffer) {}

    /** {@code CgGL} deleted {@code texture}: forget anything kept for it. */
    default void textureDeleted(int texture) {}

    /** {@code CgGL} deleted {@code renderbuffer}: forget anything kept for it. */
    default void renderbufferDeleted(int renderbuffer) {}

    /** {@code CgGL} deleted {@code buffer}: forget anything kept for it. */
    default void bufferDeleted(int buffer) {}

    /** The imports installed now; throws until a device installs them. */
    static CgGLImports get() {
        CgGLImports i = Slot.installed;
        if (i == null) throw new IllegalStateException("no CgGLImports installed: a device installs them as it starts");
        return i;
    }

    /** Whether imports are installed. */
    static boolean installed() { return Slot.installed != null; }

    /** Installs {@code imports}; null when the device goes. */
    static void install(CgGLImports imports) { Slot.installed = imports; }

    /** Where {@link #get} reads from. */
    final class Slot {
        static volatile CgGLImports installed;

        private Slot() {}
    }
}
