package com.crystalgraphics.compute.source;

/**
 * How the kernels of a {@code .compute} use a buffer it declares in {@code Buffers { }}.
 *
 * <pre>{@code
 * Buffers {
 *     STATE  ("Particle state", Particle, readwrite)
 *     SPAWNS ("Spawned",        Particle, append)     // elements plus a count, in a second binding
 *     COUNTS ("Bin counts",     uint,     counter)    // atomically incremented
 * }
 * }</pre>
 */
public enum CgBufferAccess {
    READONLY,
    WRITEONLY,
    READWRITE,
    /** Elements and a count: {@code NAME_APPEND(v)} takes the next slot. */
    APPEND,
    /** {@code uint} counters: {@code NAME_INC(i)} answers the value before. */
    COUNTER;

    public boolean readable() { return this != WRITEONLY; }

    public boolean writable() { return this != READONLY; }

    /** The access a {@code Buffers} token names, or null. */
    public static CgBufferAccess of(String token) {
        for (CgBufferAccess a : values()) if (a.name().equalsIgnoreCase(token)) return a;
        return null;
    }
}
