package com.crystalgraphics.gl.texture;

import com.crystalgraphics.platform.gl.CgGL;

/**
 * Takes the host's sampler objects off the texture units for one of our passes, and puts them back after.
 *
 * <pre>{@code
 * CgHostSamplers.park();
 * try {
 *     drawEverything();          // our textures sample with their own filtering and wrapping
 * } finally {
 *     CgHostSamplers.unpark();
 * }
 * }</pre>
 *
 * <p>A sampler bound to a unit overrides the filtering, wrapping and LOD of any texture sampled through
 * it. Minecraft 1.21.5 on binds one per draw and leaves it there — measured on units 0 to 2 at every entry
 * point — so without this our textures on those units sample with whatever Minecraft drew last.</p>
 *
 * <ul>
 *   <li>Pairs nest, up to {@link #MAX_DEPTH}; an unmatched {@code unpark} throws.</li>
 *   <li>Only units holding a sampler are touched, so a host that binds none costs {@link #UNITS} reads.</li>
 *   <li>Minecraft caches no sampler binding and rebinds its own per draw; putting them back is for any
 *       other mod that might.</li>
 * </ul>
 */
public final class CgHostSamplers {

    /** Units parked: every unit our materials bind in practice. */
    public static final int UNITS = 16;
    static final int MAX_DEPTH = 4;

    private static final int GL_SAMPLER_BINDING = 0x8919;

    private static final int[][] SAVED = new int[MAX_DEPTH][UNITS];
    private static int depth;

    private CgHostSamplers() {}

    /** Unbinds the host's samplers from the first {@link #UNITS} units, remembering them. */
    public static void park() {
        if (depth == MAX_DEPTH) throw new IllegalStateException("CgHostSamplers nested deeper than " + MAX_DEPTH);
        int[] saved = SAVED[depth++];
        int active = CgGL.glGetInteger(CgGL.GL_ACTIVE_TEXTURE);
        for (int unit = 0; unit < UNITS; unit++) {
            CgGL.glActiveTexture(CgGL.GL_TEXTURE0 + unit);
            saved[unit] = CgGL.glGetInteger(GL_SAMPLER_BINDING);
            if (saved[unit] != 0) CgGL.glBindSampler(unit, 0);
        }
        CgGL.glActiveTexture(active);
    }

    /** Rebinds what {@link #park()} took off. */
    public static void unpark() {
        if (depth == 0) throw new IllegalStateException("CgHostSamplers.unpark without park");
        int[] saved = SAVED[--depth];
        for (int unit = 0; unit < UNITS; unit++) {
            if (saved[unit] != 0) CgGL.glBindSampler(unit, saved[unit]);
        }
    }
}
