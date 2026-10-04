package com.crystalgraphics.gl.texture;

import com.crystalgraphics.platform.gl.CgGL;

/**
 * Takes the host's sampler objects off the texture units for one of our passes.
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
 *   <li>Every one of the first {@link #UNITS} units is unbound, read or not: what was bound is never read, since a
 *       {@code glGet} waits for the driver to drain every queued call.</li>
 *   <li>Nothing is put back. Minecraft caches no sampler binding and rebinds its own per draw.</li>
 * </ul>
 */
public final class CgHostSamplers {

    /** Units parked: every unit our materials bind in practice. */
    public static final int UNITS = 16;
    static final int MAX_DEPTH = 4;

    private static int depth;

    private CgHostSamplers() {}

    /** Unbinds any sampler from the first {@link #UNITS} units. */
    public static void park() {
        if (depth == MAX_DEPTH) throw new IllegalStateException("CgHostSamplers nested deeper than " + MAX_DEPTH);
        depth++;
        for (int unit = 0; unit < UNITS; unit++) CgGL.glBindSampler(unit, 0);
    }

    /** Ends what {@link #park()} began. */
    public static void unpark() {
        if (depth == 0) throw new IllegalStateException("CgHostSamplers.unpark without park");
        depth--;
    }
}
