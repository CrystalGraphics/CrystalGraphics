package com.crystalgraphics.platform.gl;

import com.crystalgraphics.variable.CgVar;

import java.util.Arrays;

/**
 * The sampler object bound on each texture unit, as far as our own calls through {@link CgGL} know it, and the host's
 * samplers cleared lazily: a unit whose sampler is unknown (the host may have bound one since we last had control) is
 * unbound the first time we bind a texture there, instead of every unit at each stage's start.
 *
 * <p>Unknown at every host entry, at {@link CgGL#cgForgetSamplers()} (a stage's park, an execution's start), and for a
 * new backend. A {@code glBindSampler} to what a unit is known to hold is skipped.</p>
 */
final class CgGlSamplers {

    static final boolean LAZY = CgVar.bool("crystalgraphics.gl.lazySamplerPark", true, CgVar.DEV | CgVar.RESTART,
            "A stage's park clears a unit's sampler at our first texture bind there, not all 16 at once.").get();

    static final int UNITS = 32;
    private static final int UNKNOWN = -1;
    private static final int[] bound = new int[UNITS];

    static {
        Arrays.fill(bound, UNKNOWN);
    }

    private CgGlSamplers() {}

    static void forget() {
        Arrays.fill(bound, UNKNOWN);
    }

    /** Whether binding {@code sampler} at {@code unit} must reach the driver; records it if so. */
    static boolean changed(int unit, int sampler) {
        if (unit < 0 || unit >= UNITS) return true;
        if (bound[unit] == sampler) return false;
        bound[unit] = sampler;
        return true;
    }

    /** Whether {@code unit}'s sampler may be the host's: a texture about to be bound there clears it first. */
    static boolean unknown(int unit) {
        return unit >= 0 && unit < UNITS && bound[unit] == UNKNOWN;
    }
}
