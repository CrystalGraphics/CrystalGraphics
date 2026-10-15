package com.crystalgraphics.vfx.render;

import com.crystalgraphics.api.mesh.CgMesh;

/**
 * How a {@code QUADS} particle slot draws: on a shared {@link CgMesh#quads quads} mesh of at least {@link #COUNT} quads,
 * one per particle, which a shader places from the slot's particle records ({@code #pragma cg_use particle}): quad
 * {@code i} reads record {@code base + i}. The draw is indirect, its count the particles Range kept for the view.
 *
 * <p>The mesh carries no vertex data ({@code #type none}): {@code fx_common.glsl} gives a vertex its quad and corner.</p>
 *
 * <pre>{@code
 * // in the vertex shader of a particle material (#pragma cg_use particle)
 * int n = fx_particle_index(FX_QUAD_INDEX, CG_OBJECT_CUSTOM0.x, CG_OBJECT_CUSTOM0.y);   // -1 past the count
 * vec3 centre = origin + CG_PARTICLE_POSITION(max(n, 0));
 * vec3 world = fx_particle_corner(centre, FX_QUAD_CORNER, vec2(size), angle, right, up);
 * }</pre>
 *
 * <h3>Vertex pulling, not an instanced unit quad</h3>
 * <p>The GPU does the same work either way: four vertices a particle, each reading its record by index. This
 * shape wins here because:</p>
 * <ul>
 *   <li><b>The world renderer's instancing is already taken.</b> Each {@code CgWorldRenderer} draw is an instance
 *       with its own object record, so a quad instanced per particle would be a record per particle, copying what the
 *       particle buffer already holds.</li>
 *   <li><b>Tiny instances underfill the GPU.</b> Vertices are shaded in batches of 32 to 64; a 4-vertex instance leaves
 *       most of each batch idle on many GPUs, which is why merged geometry or vertex pulling is the usual advice for
 *       quad-heavy work.</li>
 * </ul>
 */
public final class CgVfxQuads {

    /** The fewest quads a slot's mesh holds; larger slots take the next power of two, so slots share meshes. */
    public static final int COUNT = 1024;

    private CgVfxQuads() {
    }
}
