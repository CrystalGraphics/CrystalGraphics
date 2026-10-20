package com.crystalgraphics.vulkan.resource;

import com.crystalgraphics.vulkan.CgVulkanDevice;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Host-visible memory an upload or a readback passes through: chunks bump-allocated for the frame, handed back when
 * it retires. An upload is copied in with {@link #put}, which allocates nothing once the chunks it needs exist; one
 * larger than {@link #CHUNK} is cut first ({@link VulkanUploadSplit}).
 *
 * <pre>{@code
 * long at = staging.put(data, 16);                     // data.remaining() <= CHUNK; its position is left alone
 * vkCmdCopyBuffer(cmd, staging.chunk().buffer, dst.buffer, ...srcOffset(at)...);
 * }</pre>
 *
 * <p>A readback ({@link #take}) larger than a chunk gets one of its own, destroyed rather than kept.</p>
 */
public final class VulkanStaging {

    /** A span of a staging chunk. */
    public record Region(VulkanBuffer buffer, long offset, long size) {
        /** The span's bytes, positioned at 0. */
        public ByteBuffer bytes() {
            return buffer.mapped().duplicate().limit((int) (offset + size)).position((int) offset).slice()
                    .order(buffer.mapped().order());
        }
    }

    /** Bytes of one chunk: the most one {@link #put} takes. */
    public static final long CHUNK = 8L << 20;

    /** A chunk and a view of its mapping kept for copying into, so a put makes no view of its own. */
    private static final class Chunk {
        final VulkanBuffer buffer;
        final ByteBuffer view;

        Chunk(VulkanBuffer buffer) {
            this.buffer = buffer;
            this.view = buffer.mapped().duplicate();
        }
    }

    /** One frame's chunks, handed back when it retires; pooled, so ending a frame allocates nothing. */
    private final class Retire implements Runnable {
        final List<Chunk> chunks = new ArrayList<>();

        @Override
        public void run() {
            for (int i = 0; i < chunks.size(); i++) {
                Chunk c = chunks.get(i);
                if (c.buffer.size() == CHUNK) free.push(c);
                else device.destroy(c.buffer);
            }
            chunks.clear();
            spare.push(this);
        }
    }

    private final CgVulkanDevice device;
    private final ArrayDeque<Chunk> free = new ArrayDeque<>();
    private final ArrayDeque<Retire> spare = new ArrayDeque<>();
    private Retire used = new Retire();
    private Chunk current;
    private long offset;

    public VulkanStaging(CgVulkanDevice device) {
        this.device = device;
    }

    /** A span for a readback of {@code size} bytes. */
    public Region take(long size, long align) {
        long at = reserve(size, align);
        return new Region(current.buffer, at, size);
    }

    /**
     * Copies {@code data}'s remaining bytes into this frame's chunk, its position left where it was, and returns their
     * offset in {@link #chunk()}. Throws past {@link #CHUNK}: cut it first.
     */
    public long put(ByteBuffer data, long align) {
        int n = data.remaining();
        if (n > CHUNK) throw new IllegalArgumentException(n + " B passes a staging chunk: cut it with VulkanUploadSplit");
        long at = reserve(n, align);
        ByteBuffer view = current.view;
        view.limit((int) (at + n)).position((int) at);
        int from = data.position();
        view.put(data);
        data.position(from);
        return at;
    }

    /** The chunk the last {@link #put} or {@link #take} landed in. */
    public VulkanBuffer chunk() {
        return current.buffer;
    }

    private long reserve(long size, long align) {
        long at = (offset + align - 1) / align * align;
        if (current == null || at + size > current.buffer.size()) {
            if (current != null) used.chunks.add(current);
            current = size > CHUNK ? new Chunk(device.stagingBuffer(size))
                    : free.isEmpty() ? new Chunk(device.stagingBuffer(CHUNK)) : free.pop();
            current.view.clear();
            at = 0;
        }
        offset = at + size;
        return at;
    }

    /** This frame's chunks come back once {@code frame} has retired. */
    public void endFrame(long frame) {
        if (current != null) used.chunks.add(current);
        current = null;
        offset = 0;
        if (used.chunks.isEmpty()) return;
        Retire retiring = used;
        used = spare.isEmpty() ? new Retire() : spare.pop();
        device.whenRetired(frame, retiring);
    }

    public void destroy() {
        while (!free.isEmpty()) device.destroy(free.pop().buffer);
    }
}
