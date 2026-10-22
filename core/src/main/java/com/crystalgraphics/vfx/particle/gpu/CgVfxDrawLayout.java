package com.crystalgraphics.vfx.particle.gpu;

/**
 * How a pool lays out what its particles draw, derived from its emitter's renderers ({@code CgVfxEmitter.drawLayout()}):
 * part of a pool's identity, since each lays out Range's output its own way. Never authored: an emitter names renderers,
 * and this follows.
 *
 * <pre>{@code
 * CgVfxEmitter.builder("rocks").renderer(CgVfxRenderers.mesh("r1").material(ROCK)...build())...   // MESHES
 * CgVfxEmitter.builder("shuriken").sequence(SPIN)...                                              // SEQUENCE
 * }</pre>
 */
public enum CgVfxDrawLayout {
    /** A camera-facing quad per particle, and the strips through them: sprites, ribbons, trails. */
    QUADS,
    /** A mesh per particle, drawn as instances: a mesh renderer. */
    MESHES,
    /** A stroke per particle, an arc round the source through the particle: an arc renderer. */
    ARCS,
    /** A clip per particle: its prefab's parts where its nodes are at the particle's age. */
    SEQUENCE
}
