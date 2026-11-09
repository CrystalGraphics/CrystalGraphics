package com.crystalgraphics.platform.gl;

import com.crystalgraphics.platform.rhi.command.CgTransition;
import com.crystalgraphics.platform.rhi.resource.CgGpuBuffer;
import com.crystalgraphics.platform.rhi.resource.CgGpuTexture;
import com.crystalgraphics.platform.rhi.resource.CgTextureView;

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

    /**
     * Framebuffer {@code fbo}'s colour attachment 0, or with {@code depth} its depth, as the RHI texture's level and
     * layer attached: a target known by its GL name ({@link #defaultColor} and {@link #defaultDepth} for 0,
     * {@code width} x {@code height}). Null where nothing is attached there.
     *
     * <pre>{@code
     * CgTextureView color = imports.attachment(hostFbo, false, w, h);
     * CgTextureView depth = imports.attachment(hostFbo, true, w, h);   // null for a target without one
     * encoder.readTexture(color.texture(), new CgTextureRegion(color.baseMip(), 0, 0, color.baseLayer(), w, h, 1), out);
     * }</pre>
     *
     * <ul>
     *   <li>Answers are kept per framebuffer and size until an attached texture is deleted or {@link #forgetAttachments}:
     *       attaching another level to the same framebuffer is not seen. {@code CgFrameBuffer.levelId} gives each level
     *       a framebuffer of its own.</li>
     * </ul>
     */
    CgTextureView attachment(int fbo, boolean depth, int width, int height);

    /** What {@link #attachment} learned of framebuffers is stale: a host's targets may be remade, as at a resize. */
    default void forgetAttachments() {}

    /** The RHI buffer holding {@code name}'s storage now. */
    CgGpuBuffer buffer(int name);

    /** Where in {@link #buffer} {@code name}'s storage starts. */
    long bufferOffset(int name);

    /** A {@code CgGL} name for {@code texture}, the same each ask. */
    int name(CgGpuTexture texture);

    /** A {@code CgGL} name for {@code buffer}, the same each ask; its storage starts at offset 0. */
    int name(CgGpuBuffer buffer);

    /**
     * Framebuffer {@code fbo}'s attachments (0 the window's) moved from {@code from} to {@code to}, added to
     * {@code into}: what a frame graph records for a target it knows only by name. False where the device names no image
     * for it (GL, whose framebuffer barrier is a memory barrier).
     */
    default boolean framebufferBarrier(int fbo, int from, int to, CgTransition into) {
        return false;
    }

    /** {@code texture} is being released: drop the name given it, which {@code CgGL} must not use again. */
    default void forget(CgGpuTexture texture) {}

    /** {@code buffer} is being released: drop the name given it. */
    default void forget(CgGpuBuffer buffer) {}

    /**
     * {@code texture} was deleted, by {@code CgGL} or by the device freeing one of its own: forget anything kept for it,
     * since its name may come back as another texture.
     */
    default void textureDeleted(int texture) {}

    /** {@code CgGL} deleted {@code renderbuffer}: forget anything kept for it. */
    default void renderbufferDeleted(int renderbuffer) {}

    /**
     * {@code buffer} was deleted, by {@code CgGL} or by the device freeing one of its own: forget anything kept for it,
     * since its name may come back at another size.
     */
    default void bufferDeleted(int buffer) {}

    /**
     * {@code CgGL} gave {@code buffer} new storage of {@code bytes} ({@code glBufferData}, {@code glBufferStorage}): what
     * was kept for it at another size is stale.
     */
    default void bufferRespecified(int buffer, long bytes) {}

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
