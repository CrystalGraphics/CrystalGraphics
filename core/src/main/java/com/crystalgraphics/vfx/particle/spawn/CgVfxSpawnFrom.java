package com.crystalgraphics.vfx.particle.spawn;

import java.util.Objects;

/**
 * Where an emitter's particles are born: on another emitter's live particles of the same system instance. Cascade's
 * {@code LocationEmitter} ({@code ParticleModules_Location.cpp:419-553}, 4.21), Niagara's
 * {@code SpawnParticlesFromOtherEmitter} over a particle reader. Each new particle takes the place of one of the
 * source's, picked at random or in turn, and a share of its velocity and spin.
 *
 * <pre>{@code
 * # in an emitter of a system file: born on handle h1's particles
 * particle_spawn:
 *   - { id: s1, module: crystalgraphics:spawn_from_emitter, inputs: { source: h1, selection: random, inherit_velocity: 0.5 } }
 * }</pre>
 *
 * <pre>{@code
 * // in Java: the source by its emitter's name, built to publish its particles
 * CgVfxEmitter rocks = CgVfxEmitter.builder("rocks").publishes(true)...build();
 * CgVfxEmitter sparks = CgVfxEmitter.builder("sparks")
 *         .spawnFrom(CgVfxSpawnFrom.of("rocks", CgVfxSpawnFrom.Selection.SEQUENTIAL).inherit(0.5f, 0f))...build();
 * }</pre>
 *
 * <p>A follower stays on its source particle every step (Cascade's {@code LocationEmitterDirect},
 * {@code ParticleModules_Location.cpp:583-660}): its n-th particle takes the source's n-th by spawn, its place, velocity,
 * age and life, so its curves over life run on the source's clock and it dies with it.</p>
 *
 * <pre>{@code
 * - { id: s1, module: crystalgraphics:spawn_from_emitter, inputs: { source: h1, follow: true } }
 * CgVfxEmitter.builder("glow").spawnFrom(CgVfxSpawnFrom.following("core"))...build();
 * }</pre>
 *
 * <ul>
 *   <li>The source is a handle of the same system, or in Java an emitter's name; its system resolves it to
 *       {@link #sourceIndex}, and fails at load where it lacks it or it does not publish. A source with no live
 *       particle leaves a new one where its emitter put it, as Cascade's finds none to copy.</li>
 *   <li>The source's place replaces the emitter's, and its shape's offset is added only where the shape comes after
 *       it in the spawn stack ({@link #placeAfter}): Cascade sets the location, and later location modules add to it.</li>
 *   <li>{@code SEQUENTIAL} walks the source's live particles in their order in its pool, which their deaths reshuffle,
 *       as Cascade's kills reshuffle its active list.</li>
 *   <li>An emitter may be born on its own live particles ({@link #ofItself}), as Cascade's LocationEmitter may name its
 *       own emitter: each new one on a particle it had at the step's start, none while it has none.</li>
 *   <li>A follower takes the whole of its source's velocity and ignores the selection; a file giving it a share to
 *       inherit fails at load. Its particle whose source is gone keeps its own state, aging on.</li>
 * </ul>
 *
 * @param source          the source's handle, or in Java its emitter's name
 * @param sourceIndex     its place in its system's emitters, or -1 until the system resolves it
 * @param selection       how a source particle is picked
 * @param inheritVelocity the share of the source particle's velocity a new one takes
 * @param inheritRotation the share of its spin
 * @param placeAfter      whether the emitter's shape offsets the place (a shape later in the stack)
 * @param follow          whether each particle stays on its source particle every step
 * @param itself          whether its source is its own emitter
 */
public record CgVfxSpawnFrom(String source, int sourceIndex, Selection selection, float inheritVelocity,
                             float inheritRotation, boolean placeAfter, boolean follow, boolean itself) {

    /** How a source particle is picked for each new one. */
    public enum Selection {
        /** Any of the live ones, rolled per new particle. */
        RANDOM,
        /** The next one in turn. */
        SEQUENTIAL
    }

    public CgVfxSpawnFrom {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(selection, "selection");
        if (itself && follow) throw new IllegalArgumentException("an emitter cannot follow its own particles");
    }

    /** Born on handle {@code source}'s particles, picked by {@code selection}, inheriting nothing. */
    public static CgVfxSpawnFrom of(String source, Selection selection) {
        return new CgVfxSpawnFrom(source, -1, selection, 0f, 0f, false, false, false);
    }

    /** Born on its own emitter's live particles, picked by {@code selection}: the emitter must publish them. */
    public static CgVfxSpawnFrom ofItself(Selection selection) {
        return new CgVfxSpawnFrom("", -1, selection, 0f, 0f, false, false, true);
    }

    /** Each particle on handle {@code source}'s particle of the same spawn, every step: Cascade's LocationEmitterDirect. */
    public static CgVfxSpawnFrom following(String source) {
        return new CgVfxSpawnFrom(source, -1, Selection.RANDOM, 0f, 0f, false, true, false);
    }

    /** This, inheriting {@code velocity} of its source particle's velocity and {@code rotation} of its spin. */
    public CgVfxSpawnFrom inherit(float velocity, float rotation) {
        return new CgVfxSpawnFrom(source, sourceIndex, selection, velocity, rotation, placeAfter, follow, itself);
    }

    /** This, its emitter's shape offsetting the place. */
    public CgVfxSpawnFrom placedAfter(boolean after) {
        return new CgVfxSpawnFrom(source, sourceIndex, selection, inheritVelocity, inheritRotation, after, follow, itself);
    }

    /** This, its source at {@code index} in its system's emitters. */
    public CgVfxSpawnFrom at(int index) {
        return new CgVfxSpawnFrom(source, index, selection, inheritVelocity, inheritRotation, placeAfter, follow, itself);
    }

    /** This, born on its own emitter's particles. */
    public CgVfxSpawnFrom onItself() {
        return new CgVfxSpawnFrom(source, sourceIndex, selection, inheritVelocity, inheritRotation, placeAfter, follow, true);
    }
}
