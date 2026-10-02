package com.crystalgraphics.gl.buffer;

import com.crystalgraphics.api.buffer.CgObjectBuffer;
import lombok.Getter;
import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.platform.gl.CgCapabilities.StreamBufferTier;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

/**
 * A GL buffer rewritten while the GPU may still be reading earlier contents -- vertex streams, and the
 * storage under every shader buffer.
 *
 * <p>Two kinds, from two factories, each a waterfall of {@link StreamBufferTier}s that {@link CgCapabilities}
 * picks once per context:</p>
 * <ul>
 *   <li>{@link #create(int, int)} -- a <b>stream</b>: {@link StreamBufferTier#PERSISTENT} &gt;
 *       {@link StreamBufferTier#RING} &gt; {@link StreamBufferTier#ORPHAN} &gt; {@link StreamBufferTier#SUBDATA}.
 *       On the two ring tiers each upload lands at a new offset, valid for the frame that wrote it.</li>
 *   <li>{@link #createForShaderBuffer(int, int)} -- <b>shader-buffer storage</b>: {@link StreamBufferTier#ORPHAN}
 *       &gt; {@link StreamBufferTier#SUBDATA}, always at offset 0 and readable until the next upload -- for a
 *       buffer whose readers pass no point that could re-upload it (a TBO, a mod's own registry buffer), and
 *       {@code glBindBufferBase}/{@code glTexBuffer} read from 0.</li>
 *   <li>{@link #createFrameLocal(int, int)} -- a <b>frame-local SSBO or UBO</b>, what a shader buffer created with
 *       {@code CgBufferLifetime.FRAME} stands on: uploaded in every frame that
 *       reads it: the vertex stream's ring tiers, bound by range at each upload's offset. Every engine-owned
 *       shader buffer but the TBO path.</li>
 * </ul>
 *
 * <p>{@code -Dcrystalgraphics.stream.tier=persistent|ring|orphan|subdata} forces a tier -- for a driver that
 * misbehaves on the one chosen, and for comparing them. A shader buffer takes {@code subdata} if forced and
 * {@code orphan} otherwise.</p>
 *
 * <pre>{@code
 * CgStreamBuffer stream = CgStreamBuffer.create(capacityBytes);
 *
 * ByteBuffer out = stream.map(bytes);
 * // ... write `bytes` ...
 * int offset = stream.commit(bytes);     // where the data starts: point the draw here
 * // ... draw ...
 * stream.afterSubmit();                  // a no-op today; kept for callers that already call it
 * }</pre>
 *
 * <p>Easy to get wrong: a stream upload read in a later frame reads another frame's bytes -- a vertex
 * stream is not somewhere to keep data. And {@link #commit} returns the offset; binding at 0 draws the
 * wrong bytes.</p>
 */
public abstract class CgStreamBuffer implements CgObjectBuffer {

    /**
     * Uploads at or below this many bytes take {@link #uploadSmall} instead of map/commit.
     *
     * <p>{@code glMapBufferRange}/{@code glUnmapBuffer} carry a fixed per-call driver cost —
     * buffer bookkeeping, orphan allocation, mapping setup — that is irrelevant when amortised
     * over a large batch and dominant for a tiny one. Measured on this codebase's own text UBO:
     * 2,286 uploads of 64 bytes each cost <strong>438 ms of map time for 146 KB total</strong>
     * (~0.19 ms per call), while the SSBO instance path pushed <strong>106 MB through the same
     * code in 57 ms</strong>. Per byte the small writes were ~5,500x more expensive — the cost
     * tracks call count, not size.
     *
     * <p>256 bytes is comfortably above the small-constant-block case this targets (a mat4 is
     * 64) and far below any real streaming batch, so no bulk path changes behaviour.</p>
     */
    protected static final int SMALL_UPLOAD_THRESHOLD_BYTES = 256;

    /**
     * The GL buffer object. Stable, except that {@link StreamBufferTier#PERSISTENT} storage is immutable, so growing it
     * takes a new buffer -- and bumps {@link #generation}, which is what a VAO binding watches.
     */
    @Getter
    protected int glBuffer;

    /** Bumped whenever {@link #glBuffer} is replaced. A binding re-points its attributes when this changes, as
     * it does when the offset changes; comparing names instead would miss a name the driver hands back. */
    @Getter
    protected int generation;

    /** The GL buffer target this buffer was created for (e.g. {@code GL_ARRAY_BUFFER}, {@code GL_UNIFORM_BUFFER}). */
    @Getter
    protected final int target;

    /** Current allocated GL buffer size in bytes. Updated whenever the buffer grows. */
    @Getter
    protected int capacityBytes;

    /** Byte offset of the most recently committed upload within the GL buffer; always 0 when orphaning. */
    @Getter
    protected int writeOffset;

    /**
     * Set to {@code true} by {@link #delete()}. Checked by {@link #bind()} to guard
     * against use-after-free. Declared {@code volatile} so deletion on one thread is
     * immediately visible to bind calls on the render thread.
     */
    protected volatile boolean deleted;

    /**
     * Allocates a new GL buffer object via {@code glGenBuffers} and records the target and capacity.
     * Subclass constructors are responsible for calling {@code glBufferData} to initialise the storage.
     *
     * @param target        GL buffer target (e.g. {@code GL_ARRAY_BUFFER})
     * @param capacityBytes initial allocated size in bytes
     */
    protected CgStreamBuffer(int target, int capacityBytes) {
        this.glBuffer = CgGL.glGenBuffers();
        this.target = target;
        this.capacityBytes = capacityBytes;
        this.writeOffset = 0;

        // Interned once here, never per upload: uploadFloats is the single hottest path in the
        // engine (every vertex, instance, UBO, SSBO and TBO write goes through it), and a name built
        // inline is concatenated before the channel can say it is off.
        String prefix = "streamBuffer." + targetLabel() + ".";
        this.profileMapName = CgTrace.name(prefix + "map");
        this.profileWriteName = CgTrace.name(prefix + "write");
        this.profileCommitName = CgTrace.name(prefix + "commit");
        this.profileBytesName = CgTrace.name(prefix + "bytes");
        this.profileSmallUploadName = CgTrace.name(prefix + "smallUpload");
    }

    // Pre-built profiling names — see the constructor for why these are not built inline.
    private final int profileMapName;
    private final int profileWriteName;
    private final int profileCommitName;
    private final int profileBytesName;
    private final int profileSmallUploadName;

    /**
     * Reserves {@code sizeBytes} bytes for writing and returns a {@code ByteBuffer} pointing
     * to that region. The returned buffer is valid until {@link #commit(int)} is called.
     * Implementations auto-grow the GL buffer if {@code sizeBytes > capacityBytes}.
     *
     * @param sizeBytes number of bytes to reserve
     * @return writable {@code ByteBuffer} of at least {@code sizeBytes} capacity
     */
    public abstract ByteBuffer map(int sizeBytes);

    /**
     * Where the region the last {@link #map} returned starts in the buffer: what {@link #commit} will return. Valid
     * between the two, for a caller that aligns what it writes to the buffer rather than to the region.
     */
    public int mappedOffset() {
        return 0;
    }

    /**
     * Finalises the CPU-side upload and returns the byte offset where the data starts in the GL buffer.
     *
     * <p>This call only finalises the CPU write; do not assume the GPU has consumed the data when it
     * returns.</p>
     *
     * @param usedBytes number of bytes actually written since {@link #map(int)}
     * @return byte offset in the GL buffer where this upload's data begins -- a new one per upload on a
     *         vertex stream, always {@code 0} on shader-buffer storage
     */
    public abstract int commit(int usedBytes);

    /**
     * Called after the draw that consumes the most recent upload. A no-op: the frame ring fences once per
     * frame ({@link CgFrameRing}), not per upload. Kept because callers already call it.
     */
    public void afterSubmit() {
    }

    /**
     * Convenience method: maps, copies {@code floatCount} floats from {@code data[0..floatCount-1]},
     * commits, and returns the byte offset where the data starts.

     *
     * @param data       source float array
     * @param floatCount number of floats to copy
     * @return byte offset in the GL buffer where the uploaded data begins
     */
    public int uploadFloats(float[] data, int floatCount) {
        int byteCount = floatCount * Float.BYTES;

        // Small writes bypass map/unmap entirely — see SMALL_UPLOAD_THRESHOLD_BYTES.
        if (byteCount <= SMALL_UPLOAD_THRESHOLD_BYTES && uploadSmall(data, floatCount, byteCount)) {
            CgTrace.add(CgChannels.GL, profileSmallUploadName, 1);
            committedBytes = byteCount;
            return writeOffset;
        }

        // Split into three scopes because this is the single upload path shared by EVERY
        // stream buffer — vertex, instance, UBO, SSBO, TBO alike. If a GL stall lives in here
        // it is a pipeline-wide problem, not a per-feature one, so the scopes are named by GL
        // target: a cost that shows up only under one target is a usage-pattern issue, while
        // one spread across all of them is the shared machinery.
        FloatBuffer out;
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL_DETAIL, profileMapName)) {
            out = mapFloats(byteCount);
        }
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL_DETAIL, profileWriteName)) {
            out.put(data, 0, floatCount);
        }
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL_DETAIL, profileCommitName)) {
            CgTrace.add(CgChannels.GL, profileBytesName, byteCount);
            committedBytes = byteCount;
            return commit(byteCount);
        }
    }

    /**
     * {@link #map} as floats, positioned at the first float to write. The view is kept while {@link #map}
     * hands back the same object -- LWJGL does, for a mapping at the same address and size -- since a view
     * per upload was the largest allocation on the frame thread.
     */
    protected FloatBuffer mapFloats(int sizeBytes) {
        ByteBuffer mapped = map(sizeBytes);
        if (mapped != floatSource) {
            floatSource = mapped;
            floatView = mapped.asFloatBuffer();
        }
        floatView.clear();
        return floatView;
    }

    /** The mapping {@link #floatView} was made over. Compared by identity, never written through. */
    private ByteBuffer floatSource;
    private FloatBuffer floatView;

    /** Size of the latest {@link #uploadFloats}: with {@link #getWriteOffset()}, the range a draw reads. */
    @Getter
    protected int committedBytes;

    /**
     * Whether each upload lands at a new offset, so a binding must say where: {@code glBindBufferRange},
     * not {@code glBindBufferBase}. True on the frame ring.
     */
    public boolean offsetMovesPerUpload() {
        return false;
    }

    /**
     * Fast path for tiny uploads, bypassing map/unmap. Default returns {@code false}, meaning
     * "not supported — use the normal path".
     *
     * <p>An override sets {@link #writeOffset} to where the bytes went: 0 on orphaning shader-buffer storage,
     * the reserved offset on the mapped frame ring.</p>
     *
     * @return {@code true} if the upload was performed; {@code false} to fall back to map/commit
     */
    protected boolean uploadSmall(float[] data, int floatCount, int byteCount) {
        return false;
    }

    /**
     * Short GL-target name for profiling scope names — see {@link #uploadFloats}. Only called
     * when the profiler is enabled would be ideal, but the cost is a switch on an int, which is
     * negligible next to the map/unmap it labels.
     */
    private String targetLabel() {
        switch (target) {
            case CgGL.GL_ARRAY_BUFFER:
                return "vertex";
            case CgGL.GL_UNIFORM_BUFFER:
                return "ubo";
            case CgGL.GL_SHADER_STORAGE_BUFFER:
                return "ssbo";
            case CgGL.GL_TEXTURE_BUFFER:
                return "tbo";
            case CgGL.GL_ELEMENT_ARRAY_BUFFER:
                return "index";
            default:
                return "other";
        }
    }

    /** Binds this buffer's GL object to its {@link #target}. */
    public void bind() {
        CgGL.glBindBuffer(target, glBuffer);
    }

    /** Unbinds by binding {@code 0} to {@link #target}. */
    public void unbind() {
        CgGL.glBindBuffer(target, 0);
    }

    /**
     * Returns the GL buffer object ID of the underlying stream buffer.
     * Useful for passing to {@code glBindBufferBase} / {@code glTexBuffer} manually,
     * or for interop with external GL code.
     */
    @Override
    public int getGlBufferId() {
        return glBuffer;
    }

    /** {@inheritDoc} */
    @Override
    public boolean isDeleted() {return deleted;}

    @Override
    public void delete() {
        if (!deleted) {
            deleteGlResources();
            deleted = true;
        }
    }

    /** Deletes the underlying GL buffer object. Must not be called more than once. */
    protected void deleteGlResources() {}

    
    /**
     * A stream on {@code target}, on {@link CgCapabilities#vertexStreamTier()}. On a ring tier every
     * {@link #commit} returns a new offset, valid for the frame that wrote it.
     *
     * @param target GL target (e.g. {@code GL_ARRAY_BUFFER}, {@code GL_ELEMENT_ARRAY_BUFFER}, {@code GL_SHADER_STORAGE_BUFFER})
     * @param capacityBytes initial GL buffer capacity in bytes
     */
    public static CgStreamBuffer create(int target, int capacityBytes) {
        switch (CgCapabilities.detect().vertexStreamTier()) {
            case PERSISTENT: return new FrameRingStreamBuffer(target, capacityBytes, true);
            case RING:       return new FrameRingStreamBuffer(target, capacityBytes, false);
            case ORPHAN:     return new MapAndOrphanStreamBuffer(target, capacityBytes);
            default:         return new SubDataStreamBuffer(target, capacityBytes);
        }
    }

    /**
     * Storage for a shader buffer (UBO, SSBO, TBO): every upload orphans and lands at offset 0, and stays
     * readable until the next one: {@link CgCapabilities#shaderStreamTier()}. For a buffer read in frames that
     * never upload it and whose readers pass no point that could -- a mod's own registry buffer -- and for a TBO,
     * since {@code glTexBuffer} reads from 0 and the TBO path runs exactly where {@code glTexBufferRange} is
     * missing. Everything else takes {@link #createFrameLocal}.
     *
     * @param target        GL buffer target (e.g. {@code GL_UNIFORM_BUFFER}, {@code GL_SHADER_STORAGE_BUFFER})
     * @param capacityBytes initial GL buffer capacity in bytes
     */
    public static CgStreamBuffer createForShaderBuffer(int target, int capacityBytes) {
        return CgCapabilities.detect().shaderStreamTier() == StreamBufferTier.ORPHAN
                ? new MapAndOrphanStreamBuffer(target, capacityBytes)
                : new SubDataStreamBuffer(target, capacityBytes);
    }

    /**
     * Storage for a <b>frame-local</b> SSBO or UBO: one whose every reading frame uploads it first, so no
     * draw reads bytes an earlier frame wrote -- the ring reuses a region three frames on, while a draw of an
     * earlier frame may still read it. That is the whole test, and it is about the data's lifetime, not the
     * buffer type. Per-instance data meets it by being written right before the draw (the quad, curve and object
     * buffers). A block written once meets it when every reader passes a point that copies it into the frame:
     * {@code CgMaterial.bind} does for its properties and for the frame block, the text renderer for its own.
     *
     * <pre>{@code
     * CgStreamBuffer storage = CgStreamBuffer.createFrameLocal(CgGL.GL_SHADER_STORAGE_BUFFER, bytes);
     * int offset = storage.uploadFloats(data, count);
     * CgGL.glBindBufferRange(CgGL.GL_SHADER_STORAGE_BUFFER, binding, storage.getGlBuffer(),
     *         offset, storage.getCommittedBytes());   // the name too: the persistent tier replaces it on growth
     * }</pre>
     *
     * <p>What it buys: every upload lands at a fresh offset with no orphan, no driver rename and, on the
     * persistent tier, no GL call at all -- where a tracked device renamed every orphaning upload into a new
     * allocation. Offsets are 256-aligned, the largest {@code GL_UNIFORM_BUFFER_OFFSET_ALIGNMENT} and
     * storage-buffer alignment GL or Vulkan allows.</p>
     *
     * <p>The frame ring on {@link StreamBufferTier#PERSISTENT} and {@link StreamBufferTier#RING}; otherwise
     * {@link #createForShaderBuffer} -- so forcing {@code orphan} or {@code subdata} turns it off too. Any
     * other target takes {@link #createForShaderBuffer}: a TBO cannot bind an offset, since {@code glTexBuffer}
     * reads from 0 and {@code glTexBufferRange} is missing exactly where the TBO path runs.</p>
     */
    public static CgStreamBuffer createFrameLocal(int target, int capacityBytes) {
        StreamBufferTier tier = CgCapabilities.detect().vertexStreamTier();
        boolean rangeBindable = target == CgGL.GL_SHADER_STORAGE_BUFFER || target == CgGL.GL_UNIFORM_BUFFER;
        if (!rangeBindable || (tier != StreamBufferTier.PERSISTENT && tier != StreamBufferTier.RING)) {
            return createForShaderBuffer(target, capacityBytes);
        }
        // Instance data starts sized for a frame's flushes. A UBO starts at its own block: there is one per
        // material, and hundreds at 256 KB a region would be hundreds of megabytes; a busy one grows.
        int start = target == CgGL.GL_UNIFORM_BUFFER ? capacityBytes : Math.max(capacityBytes, FRAME_LOCAL_START_BYTES);
        return new FrameRingStreamBuffer(target, start, tier == StreamBufferTier.PERSISTENT);
    }

    private static final int FRAME_LOCAL_START_BYTES = 256 << 10;
}
