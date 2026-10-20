package com.crystalgraphics.world;

import javax.annotation.Nullable;

import java.util.concurrent.ConcurrentHashMap;

/**
 * A character's own time: it runs with the level's ticks, holds through hitstop and runs slow under a rate. Gameplay
 * advances it once a game tick; presentation (effects, animation) reads it. Mirrors Unreal's per-actor
 * {@code CustomTimeDilation}, with hitstop as whole frozen ticks.
 *
 * <pre>{@code
 * CgCharacterClock clock = CgCharacterClock.of(entityId);   // one per entity; effects following it find it
 * if (clock.advance()) runStateTick();                       // once a game tick; false while frozen
 * clock.freeze(6);                                           // hitstop: six ticks, extended never summed
 * clock.rate(0.5f);                                          // slow motion: presentation only
 * double t = clock.now(partialTick);                         // seconds, monotonic; hold on a late-heard freeze
 *
 * effect.clock(clock);                                       // or follow(entityId, point): found by itself
 * }</pre>
 *
 * <ul>
 *   <li>Whole frozen ticks are gameplay's; {@link #rate} never changes which ticks run.</li>
 *   <li>{@link #now} is for presenting and never steps back; {@link #exact} is the function itself, for judging.</li>
 *   <li>Time integrates {@code rate} as it goes, so changing the rate late never jumps the time.</li>
 *   <li>Render thread for the registry; a clock is single-threaded, its owner's.</li>
 * </ul>
 */
public final class CgCharacterClock {

    /** Seconds in a game tick. */
    public static final double TICK_SECONDS = 0.05;

    private static final ConcurrentHashMap<Integer, CgCharacterClock> CLOCKS = new ConcurrentHashMap<>();
    private static volatile int version;

    private long ticks;
    private int frozenLeft;
    private float rate = 1f;
    /** Seconds up to the whole tick {@link #ticks} counts. */
    private double seconds;
    private double presented;
    /** Bumped by every freeze and rate change: what a publisher compares to know the state moved. */
    private int revision;
    private int publishedRevision = -1;
    /** A replica's state at a level tick, which {@link #sync} plays forward. */
    private long segmentTick = -1, segmentTicks;
    private double segmentSeconds;
    private int segmentFrozen;
    private float segmentRate = 1f;

    /** A clock of its own, not in the registry: a test's, or a preview's. */
    public CgCharacterClock() {
    }

    /** The clock of {@code entity}, made on first ask. */
    public static CgCharacterClock of(int entity) {
        CgCharacterClock clock = CLOCKS.get(entity);
        if (clock != null) return clock;
        clock = CLOCKS.computeIfAbsent(entity, e -> new CgCharacterClock());
        version++;
        return clock;
    }

    /** The clock of {@code entity} if one was made, else null. Boxes: not for a per-frame path (see {@link #version}). */
    @Nullable
    public static CgCharacterClock find(int entity) {
        return CLOCKS.get(entity);
    }

    /** Forgets the clock of {@code entity}: it left the level. */
    public static void forget(int entity) {
        if (CLOCKS.remove(entity) != null) version++;
    }

    /** Forgets every clock: a new level. */
    public static void forgetAll() {
        CLOCKS.clear();
        version++;
    }

    /** Changes whenever the registry gains or loses a clock, so a holder looks again only then. */
    public static int version() {
        return version;
    }

    /**
     * Runs one game tick of this character: true if its state should tick, false if the tick was spent frozen.
     * Server and the owner's client, once per game tick.
     */
    public boolean advance() {
        if (frozenLeft > 0) {
            frozenLeft--;
            return false;
        }
        ticks++;
        seconds += TICK_SECONDS * rate;
        return true;
    }

    /** Holds the character {@code ticks} game ticks from now; a second freeze while frozen lasts to the later end. */
    public CgCharacterClock freeze(int ticks) {
        int later = Math.max(frozenLeft, ticks);
        if (later != frozenLeft) revision++;
        frozenLeft = later;
        return this;
    }

    /** Whether the next {@link #advance} will be spent frozen. */
    public boolean frozen() {
        return frozenLeft > 0;
    }

    /** Ticks of freeze left. */
    public int frozenTicks() {
        return frozenLeft;
    }

    /** Slow motion or haste for what follows the clock: 1 is real time. Takes effect from now on. */
    public CgCharacterClock rate(float rate) {
        if (rate < 0f) throw new IllegalArgumentException("rate " + rate);
        if (rate != this.rate) revision++;
        this.rate = rate;
        return this;
    }

    public float rate() {
        return rate;
    }

    /** Game ticks that ran, frozen ones not counted. */
    public long ticks() {
        return ticks;
    }

    /**
     * The character's time in seconds at {@code partialTick} of the current game tick, as the rate has shaped it. The
     * partial tick is held at 0 while frozen.
     */
    public double exact(float partialTick) {
        return seconds + (frozenLeft > 0 ? 0.0 : partialTick * TICK_SECONDS * rate);
    }

    /**
     * {@link #exact}, never behind what it has shown: a freeze heard late steps the exact time back, and this holds
     * where it was until the exact time passes it again.
     */
    public double now(float partialTick) {
        double exact = exact(partialTick);
        if (exact > presented) presented = exact;
        return presented;
    }

    /**
     * The pace presented time runs at for {@code partialTick}: 0 frozen or holding behind what was shown, else the
     * rate. What an effect following this character scales its ticks by.
     */
    public float pace(float partialTick) {
        if (frozenLeft > 0) return 0f;
        double exact = exact(partialTick);
        if (exact < presented) return 0f;
        presented = exact;
        return rate;
    }

    /** Bumped by every freeze and rate change. */
    public int revision() {
        return revision;
    }

    /** Whether the state moved since {@link #published} last ran: a publisher sends only then. */
    public boolean unpublished() {
        return revision != publishedRevision;
    }

    /** Marks the current state as sent. */
    public void published() {
        publishedRevision = revision;
    }

    /**
     * A replica's state as the server held it at the end of game tick {@code levelTick}: what {@link #sync} plays
     * forward. The server's own copy is read by {@link #ticks}, {@link #seconds}, {@link #frozenTicks} and {@link #rate}.
     */
    public void segment(long levelTick, long ticks, double seconds, int frozenLeft, float rate) {
        segmentTick = levelTick;
        segmentTicks = ticks;
        segmentSeconds = seconds;
        segmentFrozen = frozenLeft;
        segmentRate = rate;
    }

    /**
     * Sets a replica to what the server's clock is at game tick {@code levelTick}: the segment's freeze spent first,
     * the ticks after it run at its rate. A function of the level tick alone, so nothing is sent per tick.
     */
    public void sync(long levelTick) {
        if (segmentTick < 0) return;
        long elapsed = Math.max(0, levelTick - segmentTick);
        long spent = Math.min(elapsed, segmentFrozen);
        long running = elapsed - spent;
        ticks = segmentTicks + running;
        seconds = segmentSeconds + running * TICK_SECONDS * segmentRate;
        frozenLeft = (int) (segmentFrozen - spent);
        rate = segmentRate;
    }

    /** Sets the clock from a replicated state: tick count and seconds at the last whole tick, freeze left, rate. */
    public void restore(long ticks, double seconds, int frozenLeft, float rate) {
        this.ticks = ticks;
        this.seconds = seconds;
        this.frozenLeft = frozenLeft;
        this.rate = rate;
    }

    /** Seconds at the last whole tick: what a replica is sent with {@link #ticks}. */
    public double seconds() {
        return seconds;
    }
}
