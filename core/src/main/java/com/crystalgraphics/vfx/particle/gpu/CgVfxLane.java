package com.crystalgraphics.vfx.particle.gpu;

/**
 * The GLSL type of one per-instance value a module kind takes: four 32-bit words, read as floats or integers. A kind
 * declares its lanes in the order its {@code fx_<kind>} function takes them ({@link CgVfxGpuModule#instanceLanes}).
 *
 * <pre>{@code
 * private static final CgVfxLane[] LANES = {CgVfxLane.IVEC4, CgVfxLane.VEC4};   // a lattice cell and its fraction
 * public CgVfxLane[] instanceLanes() { return LANES; }
 * }</pre>
 */
public enum CgVfxLane {
    VEC4("vec4"), IVEC4("ivec4"), UVEC4("uvec4");

    /** No lanes: what a kind with no per-instance values declares. Never write into it. */
    public static final CgVfxLane[] NONE = {};

    private final String glsl;

    CgVfxLane(String glsl) {
        this.glsl = glsl;
    }

    /** The GLSL type the kernel passes: {@code vec4}, {@code ivec4} or {@code uvec4}. */
    public String glsl() {
        return glsl;
    }
}
