package com.crystalgraphics.render.graph;

import java.util.EnumSet;
import java.util.Set;

/**
 * What a graph buffer the graph allocates is: its size and its uses. Transients share pooled storage by size class,
 * so two that never live at once may be one buffer.
 *
 * <pre>{@code
 * CgBufferDesc state = CgBufferDesc.elements(capacity, decl.stride(), CgBufferUsage.STORAGE, CgBufferUsage.VERTEX);
 * CgBufferDesc args  = CgBufferDesc.of(12, CgBufferUsage.STORAGE, CgBufferUsage.INDIRECT);
 * }</pre>
 */
public record CgBufferDesc(long bytes, Set<CgBufferUsage> usages) {

    public CgBufferDesc {
        if (bytes <= 0) throw new IllegalArgumentException("a buffer of " + bytes + " bytes");
        if (usages.isEmpty()) throw new IllegalArgumentException("a buffer with no use");
        usages = Set.copyOf(usages);
    }

    public static CgBufferDesc of(long bytes, CgBufferUsage first, CgBufferUsage... more) {
        return new CgBufferDesc(bytes, EnumSet.of(first, more));
    }

    /** {@code count} elements of {@code stride} bytes. */
    public static CgBufferDesc elements(int count, int stride, CgBufferUsage first, CgBufferUsage... more) {
        return of((long) count * stride, first, more);
    }

    public boolean has(CgBufferUsage usage) {
        return usages.contains(usage);
    }

    /** The pooled size it takes: the next power of two from 256 bytes. */
    long sizeClass() {
        return Math.max(256L, Long.highestOneBit(bytes - 1) << 1);
    }
}
