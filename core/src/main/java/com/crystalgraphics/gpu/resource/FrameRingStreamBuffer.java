package com.crystalgraphics.gpu.resource;

import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * A vertex stream, or a {@code CgBufferLifetime.FRAME} SSBO or UBO, on the frame ring: one persistently mapped device
 * buffer with a region per frame in flight, filled by bump allocation, each upload at a fresh offset that nothing in
 * flight reads.
 *
 * <p>Built by {@link CgStreamBuffer#create} and {@link CgStreamBuffer#createFrameLocal}; a caller sees only the
 * {@code CgStreamBuffer} contract, and must honour the offset {@link #commit} returns, which differs on every upload.</p>
 *
 * <pre>{@code
 * ByteBuffer out = stream.map(bytes);
 * // ... write ...
 * int offset = stream.commit(bytes);     // where this upload's first byte sits in gpuBuffer()
 * }</pre>
 *
 * <p>A frame that outgrows its region gets a new buffer with every region doubled, once, which bumps
 * {@link #getGeneration()} so bindings re-point; the old one is destroyed once the frames reading it retire. Nothing here
 * waits inside a frame: the only wait is {@link CgFrameRing#awaitRetired} at a frame's first upload, for the frame
 * {@link CgFrameRing#FRAMES} back.</p>
 */
final class FrameRingStreamBuffer extends CgStreamBuffer {

    /** Covers every target's offset alignment, uniform buffers' 256 included. */
    private static final int ALIGNMENT = 256;
    /** Growth stops doubling here; a single upload larger still gets a region of its own size. */
    private static final int MAX_REGION_BYTES = 64 << 20;
    private static final int OVERFLOW = CgTrace.name("frameRing.overflow");

    /** The whole buffer's mapping, and views of it as floats, ints and bytes; rebuilt with every {@link #allocate}. */
    private ByteBuffer mapping;
    private FloatBuffer floats;
    private IntBuffer ints;
    private ByteBuffer bytes;

    private int regionBytes;
    private long frame = -1;
    private int regionBase;
    private int cursor;
    private int mappedAt;
    /** The latest upload's {@link #window}. */
    private int bindingBytes;

    FrameRingStreamBuffer(Use use, int capacityBytes) {
        super(use, capacityBytes);
        this.regionBytes = alignUp(Math.max(capacityBytes, ALIGNMENT));
        allocate();
    }

    @Override
    public ByteBuffer map(int sizeBytes) {
        reserve(sizeBytes);   // may allocate(), which drops the slices
        // A slice per place and size, kept: a caller mapping the same upload frame after frame (a mesh ring's page)
        // reuses its region's, where a new view a call was an allocation a frame
        for (int i = 0; i < sliceCount; i++) {
            if (sliceAt[i] == mappedAt && slices[i].capacity() == sizeBytes) return (ByteBuffer) slices[i].clear();
        }
        ByteBuffer view = mapping.duplicate();
        view.limit(mappedAt + sizeBytes).position(mappedAt);
        ByteBuffer slice = view.slice().order(ByteOrder.nativeOrder());   // slice() resets the order
        int k = sliceCount < slices.length ? sliceCount++ : nextSlice++ % slices.length;
        slices[k] = slice;
        sliceAt[k] = mappedAt;
        return slice;
    }

    /** {@link #map}'s slices, by where each starts; dropped when the buffer is made again. */
    private final ByteBuffer[] slices = new ByteBuffer[2 * CgFrameRing.FRAMES];
    private final int[] sliceAt = new int[2 * CgFrameRing.FRAMES];
    private int sliceCount, nextSlice;

    /** Writes through one view of the whole mapping, positioned per upload. */
    @Override
    public FloatBuffer mapFloats(int sizeBytes) {
        reserve(sizeBytes);   // may allocate(), which rebuilds the view
        floats.clear();
        floats.position(mappedAt >> 2);
        return floats;
    }

    @Override
    public IntBuffer mapInts(int sizeBytes) {
        reserve(sizeBytes);
        ints.clear();
        ints.position(mappedAt >> 2);
        return ints;
    }

    @Override
    public void write(byte[] src, int from, int count) {
        reserve(count);
        bytes.clear();
        bytes.position(mappedAt);
        bytes.put(src, from, count);
    }

    @Override
    public boolean offsetMovesPerUpload() {
        return true;
    }

    /** Sets {@link #mappedAt} to room for {@code sizeBytes} in this frame's region, growing first where it must. */
    private void reserve(int sizeBytes) {
        long now = CgFrameRing.frame();
        if (now != frame) beginFrame(now);

        int need = alignUp(sizeBytes);
        if (need > regionBytes) {
            regionBytes = alignUp(Math.max(need, Math.min(regionBytes * 2, MAX_REGION_BYTES)));
            overflowed();
            allocate();
        } else if (cursor + window(sizeBytes) > regionBytes) {
            // Doubled now, not only at the next frame: a block uploaded per draw from a small start would otherwise take
            // a new buffer at every upload that overflows, all frame long.
            regionBytes = alignUp(Math.min(regionBytes * 2, Math.max(regionBytes, MAX_REGION_BYTES)));
            overflowed();
            allocate();
        }
        mappedAt = regionBase + cursor;
    }

    /** Counted and marked: a new buffer mid-frame is a cost to name. */
    private void overflowed() {
        CgTrace.add(CgChannels.GL, OVERFLOW, 1);
        if (CgTrace.isEnabled(CgChannels.GL)) {
            CgTrace.marker(CgChannels.GL, "frameRing.overflow", use + " to " + regionBytes + " B a region");
        }
    }

    /**
     * What a binding of an upload of {@code bytes} may read: a storage upload's rounded up to a power of two, which
     * {@link #reserve} keeps inside the region, as a dynamic offset's fixed range must fit the buffer.
     */
    private int window(int bytes) {
        int need = alignUp(Math.max(bytes, 1));
        return use == Use.STORAGE ? Math.min(Integer.highestOneBit(need - 1) << 1, regionBytes) : need;
    }

    @Override
    public int bindingBytes() {
        return bindingBytes;
    }

    @Override
    public int mappedOffset() {
        return mappedAt;
    }

    /** A coherent mapping: the write is visible to every command recorded after it. */
    @Override
    public int commit(int usedBytes) {
        cursor += alignUp(usedBytes);
        writeOffset = mappedAt;
        bindingBytes = window(usedBytes);
        return mappedAt;
    }

    private void beginFrame(long now) {
        frame = now;
        CgFrameRing.awaitRetired(now - CgFrameRing.FRAMES);
        cursor = 0;
        regionBase = CgFrameRing.region(now) * regionBytes;
    }

    /** A new buffer sized for every region; the old one lives on until the frames reading it retire. */
    private void allocate() {
        capacityBytes = regionBytes * CgFrameRing.FRAMES;
        if (gpu != null) {
            release(gpu);
            generation++;
        }
        gpu = make(capacityBytes, true);
        mapping = gpu.mapped().duplicate().order(ByteOrder.nativeOrder());
        floats = mapping.asFloatBuffer();
        ints = mapping.asIntBuffer();
        bytes = mapping.duplicate();
        java.util.Arrays.fill(slices, null);
        sliceCount = 0;
        cursor = 0;
        regionBase = CgFrameRing.region(Math.max(frame, 0)) * regionBytes;
    }

    private static int alignUp(int value) {
        return (value + ALIGNMENT - 1) & ~(ALIGNMENT - 1);
    }
}
