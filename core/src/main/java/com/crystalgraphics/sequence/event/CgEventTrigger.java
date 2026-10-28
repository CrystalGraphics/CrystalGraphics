package com.crystalgraphics.sequence.event;

import java.util.Objects;

/**
 * A triggered section's clock: the section begins when its event fires, its range runs from there, and a second event
 * starts it over. A late joiner never starts one: only {@link #fire} does.
 *
 * <pre>{@code
 * CgEventTrigger swing = new CgEventTrigger("swing", 2400);       // lasts 2400 ticks
 * swing.fire("swing", 5000, seed);                                 // true: started at 5000
 * swing.local(5600);                                               // 600: ticks into the section
 * swing.fire("swing", 6000, seed);                                 // restarts; count 2, a new seed
 * swing.active(8000);                                              // true until 6000 + 2400
 * }</pre>
 *
 * <ul>
 *   <li>{@link #seed} is a function of the sequence's seed, the event's name and its count, so a replay of the same
 *       events plays the same randomness.</li>
 *   <li>Other events are ignored: {@code fire} returns false.</li>
 * </ul>
 */
public final class CgEventTrigger {

    private final String event;
    private final int length;
    private int origin = Integer.MIN_VALUE;
    private int count;
    private long seed;

    /** A section that starts on {@code event} and lasts {@code length} ticks. */
    public CgEventTrigger(String event, int length) {
        this.event = Objects.requireNonNull(event);
        this.length = length;
    }

    /** Offers {@code name} firing at {@code tick}: starts or restarts the section if it is this one's event. */
    public boolean fire(String name, int tick, long sequenceSeed) {
        if (!event.equals(name)) return false;
        origin = tick;
        count++;
        seed = mix(mix(sequenceSeed, event.hashCode()), count);
        return true;
    }

    /** Whether the section is playing at {@code tick}. */
    public boolean active(int tick) {
        return origin != Integer.MIN_VALUE && tick >= origin && tick - origin < length;
    }

    /** Ticks since the section started, for its keys to read; meaningful while {@link #active}. */
    public int local(int tick) {
        return tick - origin;
    }

    /** Times the event has fired. */
    public int count() {
        return count;
    }

    /** The seed of the current run. */
    public long seed() {
        return seed;
    }

    private static long mix(long a, long b) {
        long h = (a ^ b) * 0x9E3779B97F4A7C15L;
        return h ^ (h >>> 32);
    }
}
