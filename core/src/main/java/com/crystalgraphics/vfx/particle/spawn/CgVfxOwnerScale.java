package com.crystalgraphics.vfx.particle.spawn;

import java.util.EnumSet;
import java.util.Set;

/**
 * A value set at a particle's birth that takes its effect's world scale ({@code CgVfxEffect.worldScale()}): Niagara's
 * owner scale, opted into per attribute and off by default ({@code FNiagaraStatelessSystemScaleBuildData},
 * {@code NiagaraStatelessModuleCommon.h}). Forces opt in on their own module ({@code CgVfxModule.Force},
 * {@code ScaleVelocity}).
 *
 * <pre>{@code
 * CgVfxEmitter.builder("sparks").ownerScale(CgVfxOwnerScale.SPRITE_SIZE, CgVfxOwnerScale.VELOCITY)...
 * }</pre>
 *
 * <pre>{@code
 * - { module: crystalgraphics:initialize_particle, inputs: { sprite_size: [20, 20], owner_scale: [sprite_size] } }
 * - { module: crystalgraphics:add_velocity, inputs: { speed: 300, owner_scale: true } }
 * - { module: crystalgraphics:shape_location, inputs: { shape: sphere, sphere_radius: 40, owner_scale: true } }
 * }</pre>
 *
 * <ul>
 *   <li>A sprite's size and a ribbon's width take the scale's x; a mesh's scale each axis.</li>
 *   <li>Read at birth: a particle keeps the size it was born at when its effect's scale changes after.</li>
 * </ul>
 */
public enum CgVfxOwnerScale {

    /** {@code initialize_particle}'s {@code sprite_size}. */
    SPRITE_SIZE,
    /** {@code initialize_particle}'s {@code mesh_scale}. */
    MESH_SCALE,
    /** {@code initialize_particle}'s {@code ribbon_width}. */
    RIBBON_WIDTH,
    /** {@code shape_location}'s shape: where in it a particle is born, from its middle. */
    SHAPE,
    /** {@code add_velocity}'s velocity. */
    VELOCITY;

    /** Its bit in the spawn row's mask. */
    public int bit() {
        return 1 << ordinal();
    }

    /** {@code set}'s bits. */
    public static int mask(Set<CgVfxOwnerScale> set) {
        int m = 0;
        for (CgVfxOwnerScale s : set) m |= s.bit();
        return m;
    }

    /** The values {@code mask} holds. */
    public static Set<CgVfxOwnerScale> of(int mask) {
        EnumSet<CgVfxOwnerScale> out = EnumSet.noneOf(CgVfxOwnerScale.class);
        for (CgVfxOwnerScale s : values()) if ((mask & s.bit()) != 0) out.add(s);
        return out;
    }
}
