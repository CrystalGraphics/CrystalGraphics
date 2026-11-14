package com.crystalgraphics.render.shader;

import com.crystalgraphics.platform.rhi.CgRHI;

/**
 * Whether shaders read textures by index from the device's bindless heap or bound to units: decided once per installed
 * device, the heap where it has one unless {@link CgRHI#BINDLESS} says otherwise, as Unreal's shader platforms compile
 * with {@code UE_BINDLESS_ENABLED} where the RHI's bindless configuration allows it. Every program the engine writes
 * takes {@link #header()} after its {@code #version}, so a program and the CPU that fills its blocks agree.
 *
 * <pre>{@code
 * StringBuilder sb = new StringBuilder().append("#version 450 core\n").append(CgBindless.header());
 * if (CgBindless.active()) block.putUint(member, CgBindlessTextures.index(rhi, texture, sampler));
 * }</pre>
 *
 * <ul>
 *   <li>False with no device installed: what compiles then binds to units.</li>
 *   <li>{@code crystalgraphics.bindless} is read as a device is installed; a change takes a restart.</li>
 * </ul>
 */
public final class CgBindless {

    /** Decided for the installed device on the first ask; asked again for the next, as one closes. */
    private static boolean decided, active, viewTexturesBound;

    private CgBindless() {}

    /** Whether programs compiled on the installed device read textures from its heap. Any thread. */
    public static synchronized boolean active() {
        if (!CgRHI.installed()) return false;
        if (!decided) {
            CgRHI rhi = CgRHI.get();
            decided = true;
            active = rhi.globals().bindless() && CgRHI.BINDLESS.get();
            viewTexturesBound = active && rhi.bindlessFreezesTextures();
            rhi.onClose(CgBindless::closed);
        }
        return active;
    }

    /**
     * Whether the view's textures stay bound to units on the bindless path: they may be the host's own (its lightmap,
     * its depth sampled in place), which a handle would freeze ({@link CgRHI#bindlessFreezesTextures}). A material's and
     * a kernel's textures, the engine's, are read by index all the same.
     */
    public static synchronized boolean viewTexturesBound() {
        return active() && viewTexturesBound;
    }

    private static synchronized void closed() {
        decided = false;
    }

    /**
     * The defines a program takes right after {@code #version}: {@code CG_BINDLESS} on the bindless path, and
     * {@code CG_VIEW_TEXTURES_BOUND} where the view's textures stay bound. Empty on the bound path.
     */
    public static String header() {
        if (!active()) return "";
        return viewTexturesBound() ? "#define CG_BINDLESS 1\n#define CG_VIEW_TEXTURES_BOUND 1\n" : "#define CG_BINDLESS 1\n";
    }
}
