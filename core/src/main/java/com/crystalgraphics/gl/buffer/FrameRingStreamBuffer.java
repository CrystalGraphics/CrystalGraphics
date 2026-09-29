package com.crystalgraphics.gl.buffer;

import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.util.profiling.CgProfiler;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * A vertex stream on the frame ring: one region per frame in flight, filled by bump allocation, each upload
 * at a fresh offset that nothing in flight reads. The {@link CgCapabilities.StreamBufferTier#PERSISTENT} and
 * {@link CgCapabilities.StreamBufferTier#RING} tiers; they differ only in how the bytes are reached.
 *
 * <p>Built by {@link CgStreamBuffer#create}; a caller sees only the {@code CgStreamBuffer} contract, and
 * must honour the offset {@link #commit} returns, which differs on every upload.</p>
 *
 * <pre>{@code
 * ByteBuffer out = stream.map(bytes);
 * // ... write ...
 * int offset = stream.commit(bytes);     // where this upload's first byte sits in the GL buffer
 * binding.rebindPointersIfNeeded(offset);
 * }</pre>
 *
 * <p>A frame that outgrows its region gets fresh storage and the next frame's region doubles: an orphan on
 * the mapped ring, keeping the buffer's name; a new buffer on the persistent ring, whose storage is immutable,
 * which bumps {@link #getGeneration()} so bindings re-point. Nothing here waits inside a frame: the only wait
 * is {@link CgFrameRing#awaitRetired} at a frame's first upload, for the frame {@link CgFrameRing#FRAMES} back.</p>
 */
final class FrameRingStreamBuffer extends CgStreamBuffer {

    /** Covers every target's offset alignment, uniform buffers' 256 included. */
    private static final int ALIGNMENT = 256;
    /** Growth stops doubling here; a single upload larger still gets a region of its own size. */
    private static final int MAX_REGION_BYTES = 64 << 20;
    private static final int PERSISTENT_FLAGS =
            CgGL.GL_MAP_WRITE_BIT | CgGL.GL_MAP_PERSISTENT_BIT | CgGL.GL_MAP_COHERENT_BIT;

    private final boolean persistent;
    /** The whole buffer, mapped once for the storage's life. Persistent tier only. */
    private ByteBuffer persistentMapping;

    private int regionBytes;
    private long frame = -1;
    private int regionBase;
    private int cursor;
    private int mappedAt;
    private boolean overflowed;

    FrameRingStreamBuffer(int target, int capacityBytes, boolean persistent) {
        super(target, capacityBytes);
        this.persistent = persistent;
        this.regionBytes = alignUp(Math.max(capacityBytes, ALIGNMENT));
        allocate();
    }

    @Override
    public ByteBuffer map(int sizeBytes) {
        long now = CgFrameRing.frame();
        if (now != frame) beginFrame(now);

        int need = alignUp(sizeBytes);
        if (need > regionBytes) {
            regionBytes = alignUp(Math.max(need, Math.min(regionBytes * 2, MAX_REGION_BYTES)));
            allocate();
        } else if (cursor + need > regionBytes) {
            overflowed = true;
            CgProfiler.count("frameRing.overflow");
            allocate();
        }

        mappedAt = regionBase + cursor;
        if (persistent) {
            ByteBuffer view = persistentMapping.duplicate();
            view.limit(mappedAt + sizeBytes).position(mappedAt);
            return view.slice().order(ByteOrder.nativeOrder());   // slice() resets the order
        }
        bind();
        ByteBuffer mapped = CgGL.glMapBufferRange(target, mappedAt, sizeBytes,
                CgGL.GL_MAP_WRITE_BIT | CgGL.GL_MAP_UNSYNCHRONIZED_BIT
                        | CgGL.GL_MAP_INVALIDATE_RANGE_BIT | CgGL.GL_MAP_FLUSH_EXPLICIT_BIT, null);
        if (mapped == null) {
            throw new IllegalStateException("glMapBufferRange returned null (offset=" + mappedAt + ", size=" + sizeBytes + ")");
        }
        return mapped;
    }

    @Override
    public int commit(int usedBytes) {
        if (!persistent) {
            CgGL.glFlushMappedBufferRange(target, 0, usedBytes);   // relative to the mapped range
            CgGL.glUnmapBuffer(target);
        }
        // A coherent persistent mapping needs neither: the write is visible to every command issued after it.
        cursor += alignUp(usedBytes);
        writeOffset = mappedAt;
        return mappedAt;
    }

    private void beginFrame(long now) {
        frame = now;
        if (overflowed) {
            // Last frame did not fit. Fresh storage at twice the size needs no wait: nothing reads it.
            overflowed = false;
            regionBytes = alignUp(Math.min(regionBytes * 2, Math.max(regionBytes, MAX_REGION_BYTES)));
            allocate();
        } else {
            CgFrameRing.awaitRetired(now - CgFrameRing.FRAMES);
            cursor = 0;
            regionBase = CgFrameRing.region(now) * regionBytes;
        }
    }

    /** New storage, sized for every region; the old storage lives on until the draws reading it finish. */
    private void allocate() {
        capacityBytes = regionBytes * CgFrameRing.FRAMES;
        if (persistent) {
            if (persistentMapping != null) {
                // Immutable storage cannot be respecified. GL keeps the old buffer alive for the draws
                // still reading it; the name goes back to the pool, which is why bindings watch generation.
                CgGL.glDeleteBuffers(glBuffer);
                glBuffer = CgGL.glGenBuffers();
                generation++;
            }
            bind();
            CgGL.glBufferStorage(target, capacityBytes, PERSISTENT_FLAGS);
            persistentMapping = CgGL.glMapBufferRange(target, 0, capacityBytes, PERSISTENT_FLAGS, null);
            if (persistentMapping == null) {
                throw new IllegalStateException("persistent glMapBufferRange returned null (size=" + capacityBytes + ")");
            }
        } else {
            bind();
            CgGL.glBufferData(target, capacityBytes, CgGL.GL_STREAM_DRAW);
        }
        cursor = 0;
        regionBase = CgFrameRing.region(Math.max(frame, 0)) * regionBytes;
    }

    private static int alignUp(int value) {
        return (value + ALIGNMENT - 1) & ~(ALIGNMENT - 1);
    }

    @Override
    protected void deleteGlResources() {
        CgGL.glDeleteBuffers(glBuffer);   // also ends a persistent mapping
    }
}
