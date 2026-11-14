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
    private static boolean decided, active, hostTexturesBound;

    private CgBindless() {}

    /** Whether programs compiled on the installed device read textures from its heap. Any thread. */
    public static synchronized boolean active() {
        if (!CgRHI.installed()) return false;
        if (!decided) {
            CgRHI rhi = CgRHI.get();
            decided = true;
            active = rhi.globals().bindless() && CgRHI.BINDLESS.get();
            hostTexturesBound = active && rhi.bindlessFreezesTextures();
            rhi.onClose(CgBindless::closed);
        }
        return active;
    }

    /**
     * Whether the two view slots that may hold the host's own textures stay bound to units on the bindless path:
     * {@link CgBindingModel#SCENE_DEPTH} (its depth, sampled in place) and {@link CgBindingModel#LIGHTMAP}, which a
     * handle would freeze ({@link CgRHI#bindlessFreezesTextures}: GL). Every other texture, the view's scene colour,
     * media and local lights included, is read by index all the same.
     */
    public static synchronized boolean hostTexturesBound() {
        return active() && hostTexturesBound;
    }

    private static synchronized void closed() {
        decided = false;
    }

    /**
     * The defines a program takes right after {@code #version}: {@code CG_BINDLESS} on the bindless path, and
     * {@code CG_HOST_TEXTURES_BOUND} where the host's textures stay bound. Empty on the bound path.
     */
    public static String header() {
        if (!active()) return "";
        return hostTexturesBound() ? "#define CG_BINDLESS 1\n#define CG_HOST_TEXTURES_BOUND 1\n" : "#define CG_BINDLESS 1\n";
    }
}
