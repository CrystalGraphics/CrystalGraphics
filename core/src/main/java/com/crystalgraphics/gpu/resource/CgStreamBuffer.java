package com.crystalgraphics.gpu.resource;

import com.crystalgraphics.platform.rhi.CgDeviceNames;
import com.crystalgraphics.api.buffer.CgObjectBuffer;
import com.crystalgraphics.gpu.CgUploadLease;
import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.platform.rhi.resource.CgGpuBuffer;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;
import lombok.Getter;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.EnumSet;
import java.util.Set;

/**
 * A device buffer rewritten while the GPU may still be reading earlier contents: vertex streams, staging, and the
 * storage under every shader buffer.
 *
 * <p>Two kinds, from three factories:</p>
 * <ul>
 *   <li>{@link #create} -- a <b>stream</b> on the frame ring: each upload lands at a new offset in this frame's region
 *       of a persistently mapped buffer, valid for the frame that wrote it.</li>
 *   <li>{@link #createFrameLocal} -- a <b>frame-local SSBO or UBO</b>, the same ring, bound by range at each upload's
 *       offset: what a shader buffer created with {@code CgBufferLifetime.FRAME} stands on.</li>
 *   <li>{@link #createForShaderBuffer} -- <b>retained storage</b>, at offset 0 and readable until the next upload:
 *       each upload staged in a shared ring and copied in on the GPU (Unreal's {@code RHIUpdateBuffer}).</li>
 * </ul>
 *
 * <pre>{@code
 * CgStreamBuffer stream = CgStreamBuffer.create(CgStreamBuffer.Use.VERTEX, capacityBytes);
 * ByteBuffer out = stream.map(bytes);
 * // ... write `bytes` ...
 * int offset = stream.commit(bytes);       // where the data starts: point the draw here
 * pass.setVertexBuffer(0, stream.gpuBuffer(), offset);
 * }</pre>
 *
 * <ul>
 *   <li>A stream upload read in a later frame reads another frame's bytes: a vertex stream is not somewhere to keep
 *       data. And {@link #commit} returns the offset; binding at 0 draws the wrong bytes.</li>
 *   <li>Read {@link #gpuBuffer()} when binding, never keep it: growth replaces it, and bumps {@link #getGeneration()}.</li>
 *   <li>Render thread only.</li>
 * </ul>
 */
public abstract class CgStreamBuffer implements CgObjectBuffer {

    /** What a buffer is read as: its device usage, and its name in a profile. */
    public enum Use {
        VERTEX("vertex", CgGpuBuffer.Usage.VERTEX, CgGpuBuffer.Usage.STORAGE),
        /** A page of meshes' bytes: vertices pulled as words, and the indices a draw takes. */
        MESH("mesh", CgGpuBuffer.Usage.STORAGE, CgGpuBuffer.Usage.INDEX),
        INDEX("index", CgGpuBuffer.Usage.INDEX),
        UNIFORM("ubo", CgGpuBuffer.Usage.UNIFORM),
        STORAGE("ssbo", CgGpuBuffer.Usage.STORAGE),
        INDIRECT("indirect", CgGpuBuffer.Usage.INDIRECT, CgGpuBuffer.Usage.STORAGE),
        STAGING("staging", CgGpuBuffer.Usage.COPY_SRC);

        final String label;
        final Set<CgGpuBuffer.Usage> usage;

        Use(String label, CgGpuBuffer.Usage first, CgGpuBuffer.Usage... rest) {
            this.label = label;
            EnumSet<CgGpuBuffer.Usage> all = EnumSet.of(first, rest);
            all.add(CgGpuBuffer.Usage.COPY_SRC);
            all.add(CgGpuBuffer.Usage.COPY_DST);
            this.usage = all;
        }
    }

    /** The storage now; growth replaces it. */
    protected CgGpuBuffer gpu;

    /** Bumped whenever {@link #gpu} is replaced: a binding made from it re-points when this changes. */
    @Getter
    protected int generation;

    @Getter
    protected final Use use;

    /** Bytes the storage holds now. */
    @Getter
    protected int capacityBytes;

    /** Byte offset of the most recently committed upload; always 0 on retained storage. */
    @Getter
    protected int writeOffset;

    /** Size of the latest upload: with {@link #getWriteOffset()}, the range a draw reads. */
    @Getter
    protected int committedBytes;

    protected volatile boolean deleted;

    // Interned once, never per upload: uploadFloats is the hottest path in the engine, and a name built inline is
    // concatenated before the channel can say it is off.
    private final int profileWriteName;
    private final int profileBytesName;

    protected CgStreamBuffer(Use use, int capacityBytes) {
        this.use = use;
        this.capacityBytes = capacityBytes;
        String prefix = "streamBuffer." + use.label + ".";
        this.profileWriteName = CgTrace.name(prefix + "write");
        this.profileBytesName = CgTrace.name(prefix + "bytes");
    }

    /** The device buffer holding the storage now. */
    public CgGpuBuffer gpuBuffer() {
        return gpu;
    }

    /**
     * Reserves {@code sizeBytes} for writing and answers a buffer over that region, valid until {@link #commit}.
     * Grows the storage where it must.
     */
    public abstract ByteBuffer map(int sizeBytes);

    /** Where the region the last {@link #map} returned starts: what {@link #commit} will return. */
    public int mappedOffset() {
        return 0;
    }

    /**
     * Ends the write of {@code usedBytes} since {@link #map} and answers where they start in {@link #gpuBuffer()}: a new
     * offset per upload on the ring, always 0 on retained storage.
     */
    public abstract int commit(int usedBytes);

    /** Maps, copies {@code floatCount} floats of {@code data}, commits, and answers where they start. */
    public int uploadFloats(float[] data, int floatCount) {
        int byteCount = floatCount * Float.BYTES;
        FloatBuffer out = mapFloats(byteCount);
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL_DETAIL, profileWriteName)) {
            out.put(data, 0, floatCount);
        }
        CgTrace.add(CgChannels.GL, profileBytesName, byteCount);
        committedBytes = byteCount;
        return commit(byteCount);
    }

    /**
     * Retained storage: a lease's bytes as its contents from offset 0, grown first if need be, copied on the GPU. The
     * frame ring refuses: what it holds belongs to the frame that wrote it.
     */
    public void uploadFrom(CgUploadLease lease) {
        throw new UnsupportedOperationException("a frame-local stream is written by the frame that reads it");
    }

    /**
     * {@link #map} as floats, positioned at the first float to write: write from {@code position()} on, then
     * {@link #commit}.
     *
     * <pre>{@code
     * FloatBuffer out = ring.mapFloats(bytes);
     * int base = out.position();             // not always 0: the ring writes through one view of it all
     * out.position(base + 4).put(block);     // a block 16 bytes in
     * int offset = ring.commit(bytes);
     * }</pre>
     */
    public FloatBuffer mapFloats(int sizeBytes) {
        return map(sizeBytes).order(ByteOrder.nativeOrder()).asFloatBuffer();
    }

    /** {@link #mapFloats} for words a CPU writes as ints. */
    public IntBuffer mapInts(int sizeBytes) {
        return map(sizeBytes).order(ByteOrder.nativeOrder()).asIntBuffer();
    }

    /** Writes {@code count} bytes of {@code src} from {@code from} as one upload: then {@link #commit}, as after a map. */
    public void write(byte[] src, int from, int count) {
        map(count).put(src, from, count);
    }

    /**
     * The range a binding of the latest upload covers from {@link #getWriteOffset()}: at least its bytes. The frame ring
     * rounds a storage upload's up to a power of two inside its region, so a set bound at a dynamic offset keeps its
     * range while the upload's size stays under it.
     */
    public int bindingBytes() {
        return committedBytes;
    }

    /** Whether each upload lands at a new offset, so a binding must say where. True on the frame ring. */
    public boolean offsetMovesPerUpload() {
        return false;
    }

    /** The device name ({@code CgDeviceNames}) of the storage now. */
    public int getGlBuffer() {
        return CgDeviceNames.name(gpu);
    }

    @Override
    public int getGlBufferId() {
        return gpu == null ? 0 : getGlBuffer();
    }

    @Override
    public boolean isDeleted() {
        return deleted;
    }

    @Override
    public void delete() {
        if (deleted) return;
        deleted = true;
        if (gpu != null) release(gpu);
        gpu = null;
    }

    /** A device buffer for this use of {@code bytes}: mapped for its life where {@code streamed}, else device-local. */
    protected final CgGpuBuffer make(long bytes, boolean streamed) {
        String label = "cg_stream_" + use.label;
        CgGpuBuffer.Desc desc = streamed ? new CgGpuBuffer.Desc(label, bytes, use.usage, true, false, true)
                : new CgGpuBuffer.Desc(label, bytes, use.usage, false);
        return CgRHI.get().createBuffer(desc);
    }

    /** Gives {@code buffer} back, destroyed once the frames in flight that may read it have retired. */
    protected static void release(CgGpuBuffer buffer) {
        CgDeviceNames.forget(buffer);
        CgRHI.get().release(buffer);
    }

    /**
     * A stream for {@code use} on the frame ring: every {@link #commit} returns a new offset, valid for the frame that
     * wrote it.
     */
    public static CgStreamBuffer create(Use use, int capacityBytes) {
        return new FrameRingStreamBuffer(use, capacityBytes);
    }

    /**
     * Storage for a shader buffer whose every upload lands at offset 0 and stays readable until the next: staged in the
     * frame ring and copied in on the GPU. For a buffer read in frames that never upload it -- a mod's own registry
     * buffer. Everything else takes {@link #createFrameLocal}.
     *
     * <pre>{@code
     * CgStreamBuffer config = CgStreamBuffer.createForShaderBuffer(CgStreamBuffer.Use.UNIFORM, bytes);
     * config.uploadFloats(values, n);    // once; draws in later frames read it at offset 0
     * }</pre>
     */
    public static CgStreamBuffer createForShaderBuffer(Use use, int capacityBytes) {
        return new RetainedStreamBuffer(use, capacityBytes);
    }

    /** What every retained storage shares, at the device's teardown. Render thread. */
    public static void releaseShared() {
        RetainedStreamBuffer.releaseStaging();
    }

    /** {@link #createFrameLocal} for uniform blocks: a binding table's ring. */
    public static CgStreamBuffer frameLocalUniforms(int capacityBytes) {
        return createFrameLocal(Use.UNIFORM, capacityBytes);
    }

    /** {@link #createFrameLocal} for storage buffers. */
    public static CgStreamBuffer frameLocalStorage(int capacityBytes) {
        return createFrameLocal(Use.STORAGE, capacityBytes);
    }

    /**
     * Storage for a <b>frame-local</b> SSBO or UBO: one whose every reading frame uploads it first, so no draw reads
     * bytes an earlier frame wrote -- the ring reuses a region three frames on, while a draw of an earlier frame may
     * still read it. Per-instance data meets it by being written right before the draw; a block written once meets it
     * when every reader passes a point that copies it into the frame ({@code CgMaterial.bind}).
     *
     * <pre>{@code
     * CgStreamBuffer storage = CgStreamBuffer.createFrameLocal(CgStreamBuffer.Use.STORAGE, bytes);
     * int offset = storage.uploadFloats(data, count);
     * contents.buffer(binding, STORAGE_BUFFER, storage.gpuBuffer(), offset, storage.getCommittedBytes());
     * }</pre>
     *
     * <p>Offsets are 256-aligned, the largest uniform and storage offset alignment GL or Vulkan allows.</p>
     */
    public static CgStreamBuffer createFrameLocal(Use use, int capacityBytes) {
        if (use != Use.STORAGE && use != Use.UNIFORM) return createForShaderBuffer(use, capacityBytes);
        // Instance data starts sized for a frame's flushes. A UBO starts at its own block: there is one per material, and
        // hundreds at 256 KB a region would be hundreds of megabytes; a busy one grows.
        int start = use == Use.UNIFORM ? capacityBytes : Math.max(capacityBytes, FRAME_LOCAL_START_BYTES);
        return new FrameRingStreamBuffer(use, start);
    }

    private static final int FRAME_LOCAL_START_BYTES = 256 << 10;
}
