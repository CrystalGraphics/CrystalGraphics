package com.crystalgraphics.render.graph;

import com.crystalgraphics.gl.framebuffer.CgFrameBuffer;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Framebuffers for transient graph textures, kept between frames by description: a layer the same size as last
 * frame's reuses its storage, and one idle for {@value #IDLE_FRAMES} frames is freed. Render thread only.
 */
final class CgTexturePool {

    static final int IDLE_FRAMES = 120;

    private final Map<CgTextureDesc, ArrayDeque<Entry>> free = new HashMap<>();
    private long frame;
    private int created;

    CgFrameBuffer acquire(CgTextureDesc desc) {
        ArrayDeque<Entry> bucket = free.get(desc);
        Entry entry = bucket == null ? null : bucket.pollLast();
        if (entry != null) return entry.framebuffer;
        return create("cg_graph_" + desc.width() + "x" + desc.height() + "_" + created++, desc);
    }

    /** Storage for {@code desc}: a framebuffer, or a volume's 3D texture. */
    static CgFrameBuffer create(String name, CgTextureDesc desc) {
        return desc.isVolume() ? CgFrameBuffer.createVolume(name, desc.width(), desc.height(), desc.depth(), desc.format())
                : CgFrameBuffer.createOwned(name, desc.width(), desc.height(), desc.format(), desc.levels());
    }

    void release(CgTextureDesc desc, CgFrameBuffer framebuffer) {
        free.computeIfAbsent(desc, d -> new ArrayDeque<>()).addLast(new Entry(framebuffer, frame));
    }

    /** Frees what has sat unused for {@link #IDLE_FRAMES} frames. Once a frame. */
    void endFrame() {
        frame++;
        for (Iterator<ArrayDeque<Entry>> buckets = free.values().iterator(); buckets.hasNext(); ) {
            ArrayDeque<Entry> bucket = buckets.next();
            while (!bucket.isEmpty() && frame - bucket.peekFirst().releasedAt > IDLE_FRAMES) {
                bucket.pollFirst().framebuffer.delete();
            }
            if (bucket.isEmpty()) buckets.remove();
        }
    }

    void delete() {
        for (ArrayDeque<Entry> bucket : free.values()) for (Entry entry : bucket) entry.framebuffer.delete();
        free.clear();
    }

    private record Entry(CgFrameBuffer framebuffer, long releasedAt) {
    }
}
