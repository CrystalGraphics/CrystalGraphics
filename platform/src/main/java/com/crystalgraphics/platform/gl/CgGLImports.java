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

    /** The RHI buffer holding {@code name}'s storage now. */
    CgGpuBuffer buffer(int name);

    /** Where in {@link #buffer} {@code name}'s storage starts. */
    long bufferOffset(int name);

    /** A {@code CgGL} name for {@code texture}, the same each ask. */
    int name(CgGpuTexture texture);

    /** {@code CgGL} deleted {@code texture}: forget anything kept for it. */
    default void textureDeleted(int texture) {}

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
