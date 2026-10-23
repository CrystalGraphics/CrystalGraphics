package com.crystalgraphics.vfx.particle.ribbon;

import com.crystalgraphics.vfx.particle.renderer.CgVfxRibbonRendererProperties;
import com.crystalgraphics.vfx.particle.renderer.CgVfxRibbonRendererProperties.RenderAxis;
import com.crystalgraphics.vfx.particle.spawn.CgVfxSpawnPerUnit;

import java.util.Arrays;

/**
 * The spawning of a Cascade trail whose trails each follow a particle of another emitter: Cascade's TrailSource with
 * method particle ({@code FParticleRibbonEmitterInstance::ResolveSourcePoint}, {@code ParticleTrail2EmitterInstance.cpp}
 * :2186-2340). Those particles live on the GPU, so the GPU works each trail out: its emitter's books tick this, which
 * hands its pool a head row a trail each step, and the pool's heads kernel binds each trail to a source particle and
 * spawns along its move as {@link CgVfxTrailSource} does along an emitter's.
 *
 * <pre>{@code
 * # in an emitter of a system file: four trails, each behind one of handle h1's particles
 * particle_spawn:
 *   - { id: s1, module: crystalgraphics:ribbon_source, inputs: { method: particle, name: h1, selection: random } }
 * renderers:
 *   - { id: r1, kind: ribbon, material: ..., trail: { max_trails: 4 } }
 * }</pre>
 *
 * <pre>{@code
 * // a trail behind each of up to 64 sparks: sequential gives each trail a particle no other trail follows
 * CgVfxEmitter.builder("streaks").ratePerBlock(CgVfxSpawnPerUnit.every(1f, 0.1f))
 *         .spawnFrom(CgVfxSpawnFrom.trailing("sparks", CgVfxSpawnFrom.Selection.SEQUENTIAL)).renderer(trail64).build();
 * }</pre>
 *
 * <ul>
 *   <li>Each trail reserves {@code max_particles} spawn indices (the most it can spawn in a step) once a pool step,
 *       however many ticks the step holds, and the heads kernel spawns as many of them as the source's move asks
 *       for.</li>
 *   <li>It spawns by its source particles' moves alone: a rate or a burst on such an emitter fails at load.</li>
 *   <li>Each trail keeps a ring of its spawns, a block a pool step, sized for steps of {@link #RING_STEP}; faster frames
 *       merge its oldest two blocks, their births laid evenly between, which a trail's one life keeps close.</li>
 *   <li>{@code random} may set two trails on one particle, as Cascade's does; {@code sequential} never does, and its
 *       source may hold at most {@link #MAX_SEQUENTIAL_SOURCES} particles.</li>
 * </ul>
 */
public final class CgVfxTrailHeads implements CgVfxTrailRows {

    /** A row's kind: a trail's head, worked out on the GPU. */
    public static final int HEAD = 4;
    /** A head row's flags. */
    public static final int SPAWN_INITIAL = 1, RECALCULATE_PREVIOUS = 2, EVERY_FRAME = 4, DEAD_ON_SOURCE_LOSS = 8,
            SEQUENTIAL = 16;
    /** The most particles a sequential trail's source may hold: the heads kernel marks which are followed, a bit each. */
    public static final int MAX_SEQUENTIAL_SOURCES = 4096;
    /** The pool step a head's ring of spawns is sized for: a frame at 60 a second. */
    public static final float RING_STEP = 1f / 60f;

    private final CgVfxRibbonRendererProperties.Trail trail;
    private final CgVfxSpawnPerUnit perUnit;
    private final float maxStep;
    private final boolean sequential;
    private final int trails, budget;
    private final int[] rows, rowFirst, minIds = new int[0], refits = new int[0];
    private int rowsUsed;
    /** Whether {@link #rowFirst} holds this pool step's spawn indices, reserved by an earlier tick of it. */
    private boolean reserved;

    /**
     * The heads of an emitter drawing {@code trail}: {@code perUnit} its spawn per unit, {@code maxStep} its farthest
     * source move a step (0 none), {@code capacity} the most it holds; {@code sequential} its source's selection.
     */
    public CgVfxTrailHeads(CgVfxRibbonRendererProperties.Trail trail, CgVfxSpawnPerUnit perUnit, float maxStep, int capacity,
                           boolean sequential) {
        this.trail = trail;
        this.perUnit = perUnit;
        this.maxStep = maxStep;
        this.sequential = sequential;
        trails = Math.max(1, trail.maxTrails());
        budget = Math.max(1, trail.maxParticles() > 0 ? Math.min(trail.maxParticles(), capacity) : capacity);
        rows = new int[trails * CgVfxTrailSource.ROW_WORDS];
        rowFirst = new int[trails];
    }

    /** The most a trail spawns in a step: the spawn indices its row reserves. */
    public int budget() {
        return budget;
    }

    /** How many trails it keeps: its rows a step. */
    public int heads() {
        return trails;
    }

    /**
     * One step of {@code dt} seconds from {@code time}, Cascade's {@code RunningTime} as it begins: its emitter turned by
     * {@code (qx, qy, qz, qw)}, {@code perUnitNow} its spawn per unit's count a unit now, {@code spawning} whether its
     * loop spawns, its loop's seconds from {@code loopFrom} to {@code loopTo}.
     */
    public void tick(CgVfxTrailSource.Spawner books, float dt, float time, float qx, float qy, float qz, float qw,
                     float perUnitNow, boolean spawning, float loopFrom, float loopTo) {
        boolean sourceUp = trail.renderAxis() == RenderAxis.SOURCE_UP;
        // The source's up: its effect's Z, Minecraft's Y (Cascade's GetScaledAxis(EAxis::Z)).
        float upX = sourceUp ? 2f * (qx * qy - qw * qz) : 0f, upY = sourceUp ? 1f - 2f * (qx * qx + qz * qz) : 1f;
        float upZ = sourceUp ? 2f * (qy * qz + qw * qx) : 0f;
        int flags = (trail.spawnInitialParticle() ? SPAWN_INITIAL : 0) | (trail.recalculatePrevious() ? RECALCULATE_PREVIOUS : 0)
                | (trail.everyFrame() ? EVERY_FRAME : 0) | (trail.deadOnSourceLoss() ? DEAD_ON_SOURCE_LOSS : 0)
                | (sequential ? SEQUENTIAL : 0);
        float perBlock = spawning && perUnit.perBlock() > 0f ? perUnitNow / perUnit.unit() : 0f;
        Arrays.fill(rows, 0);
        rowsUsed = trails;
        // A pool step spawns on its first tick's indices: later ticks of it keep the emitter alive and take none.
        if (reserved) books.reserve(0);
        for (int t = 0; t < trails; t++) {
            if (!reserved) rowFirst[t] = books.reserve(budget);
            int at = t * CgVfxTrailSource.ROW_WORDS;
            rows[at] = Float.floatToRawIntBits(time);
            rows[at + 1] = Float.floatToRawIntBits(perBlock);
            rows[at + 2] = Float.floatToRawIntBits(perUnit.unit());
            rows[at + 3] = Float.floatToRawIntBits(dt);
            rows[at + 4] = Float.floatToRawIntBits(perUnit.tolerance() * perUnit.unit());
            rows[at + 5] = Float.floatToRawIntBits(maxStep);
            rows[at + 6] = Float.floatToRawIntBits(trail.tangentSpawnScale());
            rows[at + 7] = flags;
            rows[at + 8] = Float.floatToRawIntBits(upX);
            rows[at + 9] = Float.floatToRawIntBits(upY);
            rows[at + 10] = Float.floatToRawIntBits(upZ);
            rows[at + 11] = Float.floatToRawIntBits(trail.maxParticles());
            rows[at + 12] = Float.floatToRawIntBits(loopFrom);
            rows[at + 13] = Float.floatToRawIntBits(loopTo);
            // Word 14 the pool fills: the trail's head among its slot's.
            rows[at + 15] = Float.floatToRawIntBits(t);
            rows[at + 16] = Float.floatToRawIntBits(budget);
            rows[at + 17] = Float.floatToRawIntBits(trails);
            rows[at + 19] = Float.floatToRawIntBits(HEAD);
        }
        reserved = true;
    }

    @Override
    public int trails() {
        return 0;
    }

    @Override
    public int rows() {
        return rowsUsed;
    }

    @Override
    public int rowFirst(int r) {
        return rowFirst[r];
    }

    @Override
    public int rowCount(int r) {
        return budget;
    }

    @Override
    public int[] rowWords() {
        return rows;
    }

    @Override
    public int refits() {
        return 0;
    }

    @Override
    public int[] refitWords() {
        return refits;
    }

    @Override
    public int[] minIds() {
        return minIds;
    }

    @Override
    public void reset() {
        rowsUsed = 0;
        reserved = false;
    }

    @Override
    public void stepped() {
        reserved = false;
    }
}
