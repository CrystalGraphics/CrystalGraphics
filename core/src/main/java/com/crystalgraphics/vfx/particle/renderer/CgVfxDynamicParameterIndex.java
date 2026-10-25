package com.crystalgraphics.vfx.particle.renderer;

/**
 * Which of a particle's dynamic parameters a material's {@code cg_dynamic_parameter(p, i, d)} reads: Unreal's
 * {@code GetDynamicParameter}, which differs by vertex factory.
 *
 * <pre>{@code
 * renderers:
 *   - { id: r1, kind: sprite, material: ..., dynamic_parameter_index: first }   # an emitter imported from Cascade
 * }</pre>
 *
 * <pre>{@code
 * CgVfxRenderers.sprite("puffs").material(PUFF).dynamicParameterIndex(CgVfxDynamicParameterIndex.FIRST).build();
 * }</pre>
 *
 * <ul>
 *   <li>Only sprite and mesh renderers read it; the others have no dynamic parameters.</li>
 *   <li>Under {@link #FIRST}, an emitter carrying no dynamic parameters reads (1, 1, 1, 1) at every index, not the
 *       material's default.</li>
 * </ul>
 */
public enum CgVfxDynamicParameterIndex {

    /** Index i reads parameter i, or the material's default where the emitter sets none: Niagara's factories. */
    BY_INDEX,
    /**
     * Every index reads parameter 0, or (1, 1, 1, 1) where the emitter sets none: Cascade's sprite and mesh factories
     * ({@code MaterialTemplate.ush}'s {@code PARTICLE_FACTORY} branch in Unreal 4).
     */
    FIRST
}
