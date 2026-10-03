package com.crystalgraphics.compute.ops;

import com.crystalgraphics.render.graph.CgGraphBuffer;

import javax.annotation.Nullable;

/**
 * How many elements a {@link CgGpuOps} op works on: a number the CPU knows, or a word on the GPU a kernel wrote, read
 * when the op runs.
 *
 * <pre>{@code
 * CgGpuCount.of(4096);                       // fixed
 * CgGpuCount.at(alive, 0, capacity);         // the uint at word 0 of alive, at most capacity
 * }</pre>
 *
 * <ul>
 *   <li>A count on the GPU dispatches its capacity, and every kernel stops at the count it reads: work is the
 *       capacity's, and nothing past the count is written.</li>
 *   <li>The capacity is what the op sizes its scratch by; the buffers it is handed must hold that many elements.</li>
 * </ul>
 *
 * @param capacity the count itself when fixed, else the most it can be
 * @param buffer   where a count on the GPU is, else null
 * @param word     its word in {@code buffer}
 */
public record CgGpuCount(int capacity, @Nullable CgGraphBuffer buffer, int word) {

    public CgGpuCount {
        if (capacity < 0) throw new IllegalArgumentException("a count of " + capacity);
        if (word < 0) throw new IllegalArgumentException("word " + word);
    }

    public static CgGpuCount of(int count) {
        return new CgGpuCount(count, null, 0);
    }

    public static CgGpuCount at(CgGraphBuffer buffer, int word, int capacity) {
        return new CgGpuCount(capacity, buffer, word);
    }

    public boolean onGpu() {
        return buffer != null;
    }
}
