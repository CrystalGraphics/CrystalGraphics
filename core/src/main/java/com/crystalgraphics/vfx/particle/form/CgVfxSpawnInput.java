package com.crystalgraphics.vfx.particle.form;

/**
 * A spawn value an emitter may give in a form ({@link CgVfxSpawnForm}): the spec module and input it belongs to, how
 * many components it has, and the word of the spawn row its first end sits in, if any. The Step kernel's launch
 * replaces the value with the form's expression.
 *
 * <pre>{@code
 * CgVfxSpawnForm size = CgVfxSpawnForm.of(CgVfxSpawnInput.SPRITE_SIZE, "init", "sprite_size", CgVfxSpawnForm.skewed(0.1f, 0.5f, 2f));
 * CgVfxSpawnForm push = CgVfxSpawnForm.of(CgVfxSpawnInput.VELOCITY, "velocity", "linear",
 *         Map.of("range", List.of(List.of(-1, 2, -1), List.of(1, 4, 1)), "per_channel", true));
 * }</pre>
 *
 * <ul>
 *   <li>The four with a spawn-row word (speed, lifetime, sprite size, radius) take a form only where a constant or a
 *       plain range would not do: the row's two ends are those. The rest take any value, a constant too.</li>
 *   <li>{@link #RADIUS} moves the birth outward by the form's value less the shape's radius: a sweep is the radius by
 *       the loop's seconds.</li>
 *   <li>{@link #VELOCITY} adds to the launch's velocity in the emitter's frame; {@link #OFFSET} to the birth place;
 *       {@link #CONE_ANGLE} is the cone's full apex angle, drawn within per particle; {@link #CYLINDER_HEIGHT} a
 *       cylinder's whole height, drawn per particle as Cascade's {@code StartHeight} is.</li>
 *   <li>{@link #COLOR}, {@link #MESH_SCALE}, {@link #MESH_ORIENTATION} and {@link #MESH_ROTATION_RATE} are stored
 *       with the particle (Niagara's {@code Particles.Color} and {@code Particles.Scale}, Cascade's mesh rotation
 *       payload) where their form is more than a range, which Look rolls from the seed instead.</li>
 *   <li>{@link #LIGHT_RADIUS} and {@link #LIGHT_EXPONENT} are always stored: Cascade's light payload, read once at
 *       birth by the emitter's time ({@code ParticleModules.cpp:3110-3112}). The radius is a multiple of the
 *       particle's size, unitless.</li>
 *   <li>{@link #ORBIT_OFFSET}, {@link #ORBIT_ROTATION} and {@link #ORBIT_RATE} are always stored: Cascade's orbit
 *       payload, taken at birth ({@code ParticleModules_Orbit.cpp:111-165}), which Look turns into the place drawn.</li>
 *   <li>{@link #scales()} says whether {@code CgVfxEmitter.scaled} scales it: lengths and speeds do, a lifetime or an
 *       angle does not.</li>
 * </ul>
 */
public enum CgVfxSpawnInput {
    SPEED("add_velocity", "speed", 1, 0, true),
    LIFETIME("initialize_particle", "lifetime", 1, 2, false),
    SPRITE_SIZE("initialize_particle", "sprite_size", 1, 4, true),
    RADIUS("shape_location", null, 1, 29, true),
    VELOCITY("add_velocity", "linear", 3, -1, true),
    OFFSET("shape_location", "offset", 3, -1, true),
    SPRITE_ROTATION("initialize_particle", "sprite_rotation", 1, -1, false),
    CONE_ANGLE("add_velocity", "cone_angle", 1, -1, false),
    CYLINDER_HEIGHT("shape_location", "cylinder_height", 1, -1, true),
    MESH_ORIENTATION("initial_mesh_orientation", "rotation", 3, -1, false),
    MESH_ROTATION_RATE("mesh_rotation_rate", "rate", 3, -1, false),
    COLOR("initialize_particle", "color", 4, -1, false),
    MESH_SCALE("initialize_particle", "mesh_scale", 3, -1, false),
    LIGHT_RADIUS("light_attributes", "radius", 1, -1, false),
    LIGHT_EXPONENT("light_attributes", "exponent", 1, -1, false),
    ORBIT_OFFSET("orbit", "offset", 3, -1, true),
    ORBIT_ROTATION("orbit", "rotation", 3, -1, false),
    ORBIT_RATE("orbit", "rotation_rate", 3, -1, false);

    private final String module, input;
    private final int components, word;
    private final boolean scales;

    CgVfxSpawnInput(String module, String input, int components, int word, boolean scales) {
        this.module = module;
        this.input = input;
        this.components = components;
        this.word = word;
        this.scales = scales;
    }

    /** The spec module's id, without its namespace. */
    public String module() {
        return module;
    }

    /** Its input's name; null for {@link #RADIUS}, which is the shape's own ({@code sphere_radius}, {@code cylinder_radius}). */
    public String input() {
        return input;
    }

    /** The components the launch reads: 1 for a scalar, 3 for a vector, 4 for a colour. */
    public int components() {
        return components;
    }

    /** The spawn row's word holding its first end ({@code CgVfxGpuEmitter.writeSpawn}'s layout); -1 for none. */
    public int word() {
        return word;
    }

    public boolean scales() {
        return scales;
    }
}
