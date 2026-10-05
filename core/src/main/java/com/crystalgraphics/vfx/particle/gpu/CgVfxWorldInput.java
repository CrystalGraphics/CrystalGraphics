package com.crystalgraphics.vfx.particle.gpu;

/**
 * A value of the world the Step kernel fetches per particle for a module kind that runs after the solver, passed to its
 * {@code fx_<kind>} function after its lanes, in the order the kind declares ({@link CgVfxGpuModule#worldInputs}).
 * A closed list: a new world value is a change to the compiler, since only the kernel can read the world.
 *
 * <pre>{@code
 * private static final CgVfxWorldInput[] WORLD = {CgVfxWorldInput.FLOOR_Y};
 * public CgVfxWorldInput[] worldInputs() { return WORLD; }
 * // void fx_ground(inout FxParticle p, FxStep s, vec4 m, float floorY)
 * }</pre>
 */
public enum CgVfxWorldInput {
    /**
     * The ground's height under the particle, relative to its instance's origin, in blocks; NaN where there is none.
     * The instance's fixed height until the voxel window (vfx-gpu X4).
     */
    FLOOR_Y("float", "floorY");

    /** No world inputs: what a kind before the solver declares. Never write into it. */
    public static final CgVfxWorldInput[] NONE = {};

    private final String glsl, name;

    CgVfxWorldInput(String glsl, String name) {
        this.glsl = glsl;
        this.name = name;
    }

    /** The GLSL type the kernel passes. */
    public String glsl() {
        return glsl;
    }

    /** The argument's conventional name, as {@code fx_types.glsl} lists it. */
    public String argument() {
        return name;
    }
}
