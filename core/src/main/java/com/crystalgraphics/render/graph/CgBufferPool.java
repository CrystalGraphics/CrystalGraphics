package com.crystalgraphics.render.graph;

import com.crystalgraphics.gl.CgGlNames;
import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;

/**
 * GL buffers for transient graph buffers, kept between frames by size class: a buffer released after its last use is
 * the next transient's of that class, in the same frame or a later one, and one idle for {@link #IDLE_NANOS} is
 * freed. Render thread only; a steady frame allocates nothing.
 */
final class CgBufferPool {

    /**
     * Five minutes, whatever the frame rate: a buffer one beat of a move uses is still here when the move plays again.
     * Remade, each is a {@code glGenBuffers} and new storage, both waits for a threaded driver (T10: 32 remade in a warm
     * play at 120 frames). The pools hold at most a peak frame's transients; what a resize leaves behind goes after this.
     */
    static final long IDLE_NANOS = TimeUnit.MINUTES.toNanos(5);

    /** Size classes seen and their free buffers, searched in order: a frame names a handful of classes. */
    private long[] sizes = new long[8];
    private Bucket[] buckets = new Bucket[8];
    private int classes;
    /** {@link System#nanoTime()} at the last frame's end: what a release is stamped with. */
    private long now = System.nanoTime();

    /** A buffer of at least {@code desc}'s bytes. */
    int acquire(CgBufferDesc desc) {
        long size = desc.sizeClass();
        Bucket bucket = bucket(size, false);
        return bucket != null && bucket.size > 0 ? bucket.takeNewest() : create(size);
    }

    void release(CgBufferDesc desc, int buffer) {
        bucket(desc.sizeClass(), true).add(buffer, now);
    }

    /** Frees what has sat unused for {@link #IDLE_NANOS}, telling {@code freed}. Once a frame. */
    void endFrame(IntConsumer freed) {
        now = System.nanoTime();
        for (int c = 0; c < classes; c++) {
            Bucket bucket = buckets[c];
            while (bucket.size > 0 && now - bucket.oldestAt() > IDLE_NANOS) {
                int buffer = bucket.takeOldest();
                CgGL.glDeleteBuffers(buffer);
                freed.accept(buffer);
            }
        }
    }

    void delete() {
        for (int c = 0; c < classes; c++) {
            Bucket bucket = buckets[c];
            while (bucket.size > 0) CgGL.glDeleteBuffers(bucket.takeOldest());
        }
        classes = 0;
    }

    private Bucket bucket(long size, boolean make) {
        for (int c = 0; c < classes; c++) if (sizes[c] == size) return buckets[c];
        if (!make) return null;
        if (classes == sizes.length) {
            sizes = Arrays.copyOf(sizes, classes * 2);
            buckets = Arrays.copyOf(buckets, classes * 2);
        }
        sizes[classes] = size;
        return buckets[classes++] = new Bucket();
    }

    /** Device-local storage of {@code bytes}: immutable where the context has buffer storage. */
    static int create(long bytes) {
        int buffer = CgGlNames.buffer();
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL_DETAIL, STORAGE)) {
            CgGL.glBindBuffer(CgGL.GL_COPY_WRITE_BUFFER, buffer);
            if (CgCapabilities.detect().isBufferStorageSupported()) CgGL.glBufferStorage(CgGL.GL_COPY_WRITE_BUFFER, bytes, 0);
            else CgGL.glBufferData(CgGL.GL_COPY_WRITE_BUFFER, bytes, CgGL.GL_DYNAMIC_COPY);
            CgGL.glBindBuffer(CgGL.GL_COPY_WRITE_BUFFER, 0);
        }
        return buffer;
    }

    private static final int STORAGE = CgTrace.name("graph.bufferStorage");

    /** One size class's free buffers, oldest first, as a ring of parallel arrays. */
    private static final class Bucket {
        private int[] buffers = new int[4];
        private long[] releasedAt = new long[4];
        private int head, size;

        void add(int buffer, long at) {
            if (size == buffers.length) grow();
            int i = (head + size) % buffers.length;
            buffers[i] = buffer;
            releasedAt[i] = at;
            size++;
        }

        long oldestAt() {
            return releasedAt[head];
        }

        int takeOldest() {
            int buffer = buffers[head];
            head = (head + 1) % buffers.length;
            size--;
            return buffer;
        }

        int takeNewest() {
            size--;
            return buffers[(head + size) % buffers.length];
        }

        private void grow() {
            int n = buffers.length;
            int[] b = new int[n * 2];
            long[] a = new long[n * 2];
            for (int k = 0; k < size; k++) {
                b[k] = buffers[(head + k) % n];
                a[k] = releasedAt[(head + k) % n];
            }
            buffers = b;
            releasedAt = a;
            head = 0;
        }
    }
}
