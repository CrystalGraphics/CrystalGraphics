package com.crystalgraphics.compute.source;

/**
 * What a kernel writes, declared by its author in {@code #pragma kernel}, and so which tiers can run it: every shape
 * but {@link #GENERAL} lowers to fixed-function GL where there is no compute (gpu-compute §6.2). The accessor macros
 * a kernel is given follow from its shape, and the parser refuses a kernel whose code reaches past it.
 *
 * <pre>{@code
 * #pragma kernel Simulate 64 map        // STATE_WRITE(p): this element of each output
 * #pragma kernel Spawn 64 append        // ... and SPAWNS_APPEND(p)
 * #pragma kernel Histogram 256 scatter  // BINS_ADD(i, 1u) at any index
 * #pragma kernel Blur 8 8 image         // OUT_WRITE(c): this texel of each output image
 * #pragma kernel Sort 256 general       // shared memory, barrier(), subgroups, any access
 * }</pre>
 */
public enum CgKernelShape {
    /** Reads anything; writes its own element of each output buffer. */
    MAP,
    /** {@link #MAP}, reading other elements too: lowered alike. */
    GATHER,
    /** {@link #MAP}, plus elements appended to an append buffer. */
    APPEND,
    /** Writes, adds, takes the minimum or maximum at computed indices of a buffer. */
    SCATTER,
    /** Writes its own texel of each output image. */
    IMAGE,
    /** Anything compute can do: shared memory, barriers, subgroups, raw atomics, any access. Not lowerable. */
    GENERAL;

    /** Whether a tier without compute can run it, rewritten (gpu-compute C5). */
    public boolean lowerable() { return this != GENERAL; }

    /** {@code NAME_WRITE(v)}: this element of a writable buffer. */
    public boolean writesOwnElement() { return this != IMAGE; }

    /** {@code NAME_APPEND(v)} on an append buffer. */
    public boolean appends() { return this == APPEND || this == GENERAL; }

    /** {@code NAME_STORE(i, v)}, {@code NAME_ADD}, {@code _MIN}, {@code _MAX}; a counter's {@code NAME_INC(i)}. */
    public boolean scatters() { return this == SCATTER || this == GENERAL; }

    /** {@code NAME_WRITE(v)} on an image: this texel. */
    public boolean writesOwnTexel() { return this == IMAGE || this == GENERAL; }

    /** Image writes and atomics at any texel, raw buffer arrays ({@code NAME_DATA}), shared memory, barriers. */
    public boolean unrestricted() { return this == GENERAL; }

    /** The shape a {@code #pragma kernel} token names, or null. */
    public static CgKernelShape of(String token) {
        for (CgKernelShape s : values()) if (s.name().equalsIgnoreCase(token)) return s;
        return null;
    }
}
