package com.crystalgraphics.vfx.particle.form;

/**
 * A spawn value an emitter may give in a form ({@link CgVfxSpawnForm}): the spec module it belongs to and the word of
 * the spawn row its first end sits in, which the Step kernel's launch replaces with the form's expression.
 *
 * <pre>{@code
 * CgVfxSpawnForm size = CgVfxSpawnForm.of(CgVfxSpawnInput.SPRITE_SIZE, "sprite_size", CgVfxSpawnForm.skewed(0.1f, 0.5f, 2f));
 * }</pre>
 *
 * <ul>
 *   <li>{@link #RADIUS} moves the birth outward by the form's value less the shape's radius: a sweep is the radius by
 *       the loop's seconds.</li>
 *   <li>{@link #scales()} says whether {@code CgVfxEmitter.scaled} scales it: lengths and speeds do, a lifetime does not.</li>
 * </ul>
 */
public enum CgVfxSpawnInput {
    SPEED("add_velocity", 0, true),
    LIFETIME("initialize_particle", 2, false),
    SPRITE_SIZE("initialize_particle", 4, true),
    RADIUS("shape_location", 29, true);

    private final String module;
    private final int word;
    private final boolean scales;

    CgVfxSpawnInput(String module, int word, boolean scales) {
        this.module = module;
        this.word = word;
        this.scales = scales;
    }

    /** The spec module's id, without its namespace. */
    public String module() {
        return module;
    }

    /** The spawn row's word holding its first end: {@code CgVfxGpuEmitter.writeSpawn}'s layout. */
    public int word() {
        return word;
    }

    public boolean scales() {
        return scales;
    }
}
