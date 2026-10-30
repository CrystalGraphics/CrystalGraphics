package com.crystalgraphics.render.graph;

import com.crystalgraphics.gl.framebuffer.CgFrameBuffer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Framebuffers for transient graph textures, kept between frames by description: a layer the same size as last
 * frame's reuses its storage, and one idle for {@link CgBufferPool#IDLE_NANOS} is freed. Render thread only; a steady
 * frame allocates nothing.
 */
final class CgTexturePool {

    private final Map<CgTextureDesc, Bucket> free = new HashMap<>();
    /** Every bucket made, kept when empty, so the idle sweep walks them by index. */
    private final List<Bucket> buckets = new ArrayList<>();
    /** {@link System#nanoTime()} at the last frame's end: a framebuffer remade is names and storage, driver waits. */
    private long now = System.nanoTime();
    private int created;

    CgFrameBuffer acquire(CgTextureDesc desc) {
        Bucket bucket = free.get(desc);
        if (bucket != null && bucket.size > 0) return bucket.takeNewest();
        return create("cg_graph_" + desc.width() + "x" + desc.height() + "_" + created++, desc);
    }

    /** Storage for {@code desc}: a framebuffer, an array's, or a volume's 3D texture. */
    static CgFrameBuffer create(String name, CgTextureDesc desc) {
        if (desc.isArray()) return CgFrameBuffer.createArray(name, desc.width(), desc.height(), desc.layers(), desc.format());
        return desc.isVolume() ? CgFrameBuffer.createVolume(name, desc.width(), desc.height(), desc.depth(), desc.format())
                : CgFrameBuffer.createOwned(name, desc.width(), desc.height(), desc.format(), desc.levels());
    }

    void release(CgTextureDesc desc, CgFrameBuffer framebuffer) {
        Bucket bucket = free.get(desc);
        if (bucket == null) {
            free.put(desc, bucket = new Bucket());
            buckets.add(bucket);
        }
        bucket.add(framebuffer, now);
    }

    /** Frees what has sat unused for {@link CgBufferPool#IDLE_NANOS}. Once a frame. */
    void endFrame() {
        now = System.nanoTime();
        for (int i = 0; i < buckets.size(); i++) {
            Bucket bucket = buckets.get(i);
            while (bucket.size > 0 && now - bucket.oldestAt() > CgBufferPool.IDLE_NANOS) bucket.takeOldest().delete();
        }
    }

    void delete() {
        for (int i = 0; i < buckets.size(); i++) {
            Bucket bucket = buckets.get(i);
            while (bucket.size > 0) bucket.takeOldest().delete();
        }
        free.clear();
        buckets.clear();
    }

    /** One description's free framebuffers, oldest first, as a ring of parallel arrays. */
    private static final class Bucket {
        private CgFrameBuffer[] framebuffers = new CgFrameBuffer[4];
        private long[] releasedAt = new long[4];
        private int head, size;

        void add(CgFrameBuffer framebuffer, long at) {
            if (size == framebuffers.length) grow();
            int i = (head + size) % framebuffers.length;
            framebuffers[i] = framebuffer;
            releasedAt[i] = at;
            size++;
        }

        long oldestAt() {
            return releasedAt[head];
        }

        CgFrameBuffer takeOldest() {
            CgFrameBuffer fb = framebuffers[head];
            framebuffers[head] = null;
            head = (head + 1) % framebuffers.length;
            size--;
            return fb;
        }

        CgFrameBuffer takeNewest() {
            int i = (head + size - 1) % framebuffers.length;
            CgFrameBuffer fb = framebuffers[i];
            framebuffers[i] = null;
            size--;
            return fb;
        }

        private void grow() {
            int n = framebuffers.length;
            CgFrameBuffer[] f = new CgFrameBuffer[n * 2];
            long[] a = new long[n * 2];
            for (int k = 0; k < size; k++) {
                f[k] = framebuffers[(head + k) % n];
                a[k] = releasedAt[(head + k) % n];
            }
            framebuffers = f;
            releasedAt = a;
            head = 0;
        }
    }
}
