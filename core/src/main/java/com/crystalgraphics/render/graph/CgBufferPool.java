package com.crystalgraphics.render.graph;

import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.platform.gl.CgGL;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.IntConsumer;

/**
 * GL buffers for transient graph buffers, kept between frames by size class: a buffer released after its last use is
 * the next transient's of that class, in the same frame or a later one, and one idle for {@value #IDLE_FRAMES} frames is
 * freed. Render thread only.
 */
final class CgBufferPool {

    static final int IDLE_FRAMES = 120;

    private final Map<Long, ArrayDeque<long[]>> free = new HashMap<>();
    private long frame;

    /** A buffer of at least {@code desc}'s bytes. */
    int acquire(CgBufferDesc desc) {
        long size = desc.sizeClass();
        ArrayDeque<long[]> bucket = free.get(size);
        long[] entry = bucket == null ? null : bucket.pollLast();
        return entry != null ? (int) entry[0] : create(size);
    }

    void release(CgBufferDesc desc, int buffer) {
        free.computeIfAbsent(desc.sizeClass(), s -> new ArrayDeque<>()).addLast(new long[]{buffer, frame});
    }

    /** Frees what has sat unused for {@link #IDLE_FRAMES} frames, telling {@code freed}. Once a frame. */
    void endFrame(IntConsumer freed) {
        frame++;
        for (Iterator<ArrayDeque<long[]>> buckets = free.values().iterator(); buckets.hasNext(); ) {
            ArrayDeque<long[]> bucket = buckets.next();
            while (!bucket.isEmpty() && frame - bucket.peekFirst()[1] > IDLE_FRAMES) {
                int buffer = (int) bucket.pollFirst()[0];
                CgGL.glDeleteBuffers(buffer);
                freed.accept(buffer);
            }
            if (bucket.isEmpty()) buckets.remove();
        }
    }

    void delete() {
        for (ArrayDeque<long[]> bucket : free.values()) for (long[] entry : bucket) CgGL.glDeleteBuffers((int) entry[0]);
        free.clear();
    }

    /** Device-local storage of {@code bytes}: immutable where the context has buffer storage. */
    static int create(long bytes) {
        int buffer = CgGL.glGenBuffers();
        CgGL.glBindBuffer(CgGL.GL_COPY_WRITE_BUFFER, buffer);
        if (CgCapabilities.detect().isBufferStorageSupported()) CgGL.glBufferStorage(CgGL.GL_COPY_WRITE_BUFFER, bytes, 0);
        else CgGL.glBufferData(CgGL.GL_COPY_WRITE_BUFFER, bytes, CgGL.GL_DYNAMIC_COPY);
        CgGL.glBindBuffer(CgGL.GL_COPY_WRITE_BUFFER, 0);
        return buffer;
    }
}
