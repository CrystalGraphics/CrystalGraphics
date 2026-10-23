package com.crystalgraphics.vfx.particle.ribbon;

/**
 * One tick's Cascade trail spawning as its pool's Step takes it: spawn rows, the refits of what it spawned before, and
 * each trail's oldest spawn still kept. {@link CgVfxTrailSource} (a ribbon trail) and {@link CgVfxAnimTrailSource} (an
 * anim trail) make them; the emitter's books tick one, and its system hands what it made to the pool.
 *
 * <pre>{@code
 * CgVfxTrailRows rows = instance.books().trailSource();
 * for (int r = 0; r < rows.rows(); r++) {
 *     pool.spawnTrail(slot, rows.rowFirst(r), rows.rowCount(r), rows.rowWords(), r * CgVfxTrailSource.ROW_WORDS, bornAt);
 * }
 * pool.trailTable(slot, rows.minIds(), rows.trails(), rows.refitWords(), rows.refits());
 * }</pre>
 *
 * <ul>
 *   <li>What it holds is the last tick's alone: read it after each tick, before the next.</li>
 *   <li>{@link #minIds()} is the whole trail's state, not the tick's: hand it every step, spawning or not.</li>
 * </ul>
 */
public interface CgVfxTrailRows {

    /** How many trails it keeps: {@link #minIds()}' length that counts. */
    int trails();

    /** This tick's spawn rows. */
    int rows();

    /** Row {@code r}'s first spawn index. */
    int rowFirst(int r);

    /** Row {@code r}'s spawn candidates. */
    int rowCount(int r);

    /** Every row's words, {@link CgVfxTrailSource#ROW_WORDS} each from {@code r * ROW_WORDS}. */
    int[] rowWords();

    /** This tick's refits, {@link CgVfxTrailSource#REFIT_WORDS} words each in {@link #refitWords()}. */
    int refits();

    int[] refitWords();

    /** Each trail's oldest spawn index still kept: the Step ends older ones of the trail. */
    int[] minIds();

    /** Forgets every trail: a fresh slot. */
    void reset();

    /** Its rows have been handed to a pool step: spawn indices kept for that step are spent. */
    default void stepped() {
    }
}
