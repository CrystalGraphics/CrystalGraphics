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
 *
 * // the voxel window itself, for collision against blocks from any side, fluids and light
 * private static final CgVfxWorldInput[] WINDOW = {CgVfxWorldInput.WORLD};
 * // #include "crystalgraphics:shaders/lib/vfx/sim/fx_world_at.glsl"
 * // void fx_blocks(inout FxParticle p, FxStep s, vec4 m, FxWorld world) { if (fx_world_solid(world, p.position)) ... }
 * }</pre>
 */
public enum CgVfxWorldInput {
    /**
     * The ground's height under the particle, relative to its instance's origin, in blocks; NaN where there is none.
     * The instance's fixed height until the voxel window (vfx-gpu X4).
     */
    FLOOR_Y("float", "floorY"),
    /**
     * The voxel window, as an {@code FxWorld} that {@code fx_world_at.glsl}'s readers take: solid octants, fluid and
     * light at a point relative to the instance's origin. With no level they answer "nothing there".
     */
    WORLD("FxWorld", "world"),
    /**
     * {@link #WORLD}, with the window's distance field kept current while the shape plays: {@code fx_world_sdf}, the
     * distance to the nearest solid octant and its gradient, Niagara's and Unity's distance field collision.
     */
    WORLD_DISTANCE("FxWorld", "world"),
    /**
     * The scene's depth as the camera saw it this frame, as an {@code FxDepth} that {@code fx_depth_at.glsl}'s readers
     * take: Niagara's and Unity's depth buffer collision. Blind off screen and behind the first surface; the voxel
     * window is the primary collider, this one for what it does not hold (entities, a mod's meshes).
     */
    DEPTH("FxDepth", "depth"),
    /**
     * The entities round the camera this frame, as boxes in an {@code FxEntities} that {@code fx_entities_at.glsl}'s
     * readers take ({@code fx_entities_enter}): mobs and players a particle can strike from any side, on screen or not.
     * At most {@code CgVfxEntityBoxes.MAX} of them, the living nearest the camera.
     */
    ENTITIES("FxEntities", "entities"),
    /**
     * The particles near this one from the last step, as an {@code FxNeighbours} that {@code fx_neighbours_at.glsl}
     * walks: every particle of its slot in the 27 cells round it, each cell as wide as the widest
     * {@link CgVfxGpuModule#neighbourRadius} of the pool's kinds. The one input a kind before the solver may take: a
     * force from its neighbours (boids, a swarm). Built after every step on every tier; a newborn is not in it yet.
     */
    NEIGHBOURS("FxNeighbours", "neighbours");

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
