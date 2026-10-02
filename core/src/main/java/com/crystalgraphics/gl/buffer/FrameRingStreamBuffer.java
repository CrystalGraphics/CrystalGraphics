package com.crystalgraphics.gl.buffer;

import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * A vertex stream, or a {@code CgBufferLifetime.FRAME} SSBO or UBO ({@link CgStreamBuffer#createFrameLocal}), on the frame ring: one region per frame in flight, filled by bump allocation, each upload
 * at a fresh offset that nothing in flight reads. The {@link CgCapabilities.StreamBufferTier#PERSISTENT} and
 * {@link CgCapabilities.StreamBufferTier#RING} tiers; they differ only in how the bytes are reached.
 *
 * <p>Built by {@link CgStreamBuffer#create} and {@link CgStreamBuffer#createFrameLocal}; a caller sees only the {@code CgStreamBuffer} contract, and
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
    /** {@link #persistentMapping} as floats; rebuilt with it on every {@link #allocate}. */
    private FloatBuffer persistentFloats;
    /** The mapped tier's last mapping, offered back to the driver so its wrapper can be reused. */
    private ByteBuffer lastMapping;

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
        reserve(sizeBytes);
        if (persistent) {
            ByteBuffer view = persistentMapping.duplicate();
            view.limit(mappedAt + sizeBytes).position(mappedAt);
            return view.slice().order(ByteOrder.nativeOrder());   // slice() resets the order
        }
        bind();
        ByteBuffer mapped = CgGL.glMapBufferRange(target, mappedAt, sizeBytes,
                CgGL.GL_MAP_WRITE_BIT | CgGL.GL_MAP_UNSYNCHRONIZED_BIT
                        | CgGL.GL_MAP_INVALIDATE_RANGE_BIT | CgGL.GL_MAP_FLUSH_EXPLICIT_BIT, lastMapping);
        if (mapped == null) {
            throw new IllegalStateException("glMapBufferRange returned null (offset=" + mappedAt + ", size=" + sizeBytes + ")");
        }
        // LWJGL returns the old wrapper for the same address and size, position and all.
        mapped.clear();
        lastMapping = mapped;
        return mapped;
    }

    /** The persistent tier writes through one view of the whole mapping, positioned per upload. */
    @Override
    protected FloatBuffer mapFloats(int sizeBytes) {
        if (!persistent) return super.mapFloats(sizeBytes);
        reserve(sizeBytes);   // may allocate(), which rebuilds the view
        persistentFloats.clear();
        persistentFloats.position(mappedAt >> 2);
        return persistentFloats;
    }

    @Override
    public boolean offsetMovesPerUpload() {
        return true;
    }

    /**
     * A small block on the mapped tier (a frame-local UBO) as one {@code glBufferSubData} at its reserved offset,
     * not a map and unmap: that pair costs about 0.19 ms a call whatever the size, as measured on the text UBO
     * ({@code MapAndOrphanStreamBuffer.uploadSmall}). The bytes land where nothing in flight reads, as a mapped
     * write would. The persistent tier declines: its write is already a plain copy into the mapping.
     */
    @Override
    protected boolean uploadSmall(float[] data, int floatCount, int byteCount) {
        if (persistent) return false;
        reserve(byteCount);
        if (scratch == null || scratch.capacity() < byteCount) {
            scratch = ByteBuffer.allocateDirect(Math.max(byteCount, SMALL_UPLOAD_THRESHOLD_BYTES)).order(ByteOrder.nativeOrder());
            scratchFloats = scratch.asFloatBuffer();
        }
        scratchFloats.clear();
        scratchFloats.put(data, 0, floatCount);
        scratch.position(0).limit(byteCount);
        bind();
        CgGL.glBufferSubData(target, mappedAt, scratch);
        cursor += alignUp(byteCount);
        writeOffset = mappedAt;
        return true;
    }

    /** {@link #uploadSmall}'s staging, grow-only. */
    private ByteBuffer scratch;
    private FloatBuffer scratchFloats;

    /** Sets {@link #mappedAt} to room for {@code sizeBytes} in this frame's region, growing or re-storing first. */
    private void reserve(int sizeBytes) {
        long now = CgFrameRing.frame();
        if (now != frame) beginFrame(now);

        int need = alignUp(sizeBytes);
        if (need > regionBytes) {
            regionBytes = alignUp(Math.max(need, Math.min(regionBytes * 2, MAX_REGION_BYTES)));
            allocate();
        } else if (cursor + need > regionBytes) {
            // Doubled now, not only at the next frame: a block uploaded per draw from a small start would
            // otherwise take fresh storage at every upload that overflows, all frame long.
            overflowed = true;
            CgTrace.add(CgChannels.GL, "frameRing.overflow", 1);
            regionBytes = alignUp(Math.min(regionBytes * 2, Math.max(regionBytes, MAX_REGION_BYTES)));
            allocate();
        }

        mappedAt = regionBase + cursor;
    }

    @Override
    public int mappedOffset() {
        return mappedAt;
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
            persistentFloats = persistentMapping.order(ByteOrder.nativeOrder()).asFloatBuffer();
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
