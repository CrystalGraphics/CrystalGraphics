package com.crystalgraphics.gl.buffer;

import com.crystalgraphics.api.buffer.CgObjectBuffer;
import lombok.Getter;
import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.platform.gl.CgCapabilities.StreamBufferTier;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;

import java.nio.ByteBuffer;

/**
 * A GL buffer rewritten while the GPU may still be reading earlier contents -- vertex streams, and the
 * storage under every shader buffer.
 *
 * <p>Two kinds, from two factories, each a waterfall of {@link StreamBufferTier}s that {@link CgCapabilities}
 * picks once per context:</p>
 * <ul>
 *   <li>{@link #create(int)} -- a <b>vertex stream</b>: {@link StreamBufferTier#PERSISTENT} &gt;
 *       {@link StreamBufferTier#RING} &gt; {@link StreamBufferTier#ORPHAN} &gt; {@link StreamBufferTier#SUBDATA}.
 *       On the two ring tiers each upload lands at a new offset, valid for the frame that wrote it;
 *       {@code CgBatchRenderer} and {@code CgInstanceRenderer} draw from it the same frame.</li>
 *   <li>{@link #createForShaderBuffer(int, int)} -- <b>shader-buffer storage</b>: {@link StreamBufferTier#ORPHAN}
 *       &gt; {@link StreamBufferTier#SUBDATA}, always at offset 0 and readable until the next upload -- a material
 *       block written once is bound for many frames, and {@code glBindBufferBase}/{@code glTexBuffer} cannot take
 *       an offset.</li>
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
            return 0;
        }

        // Split into three scopes because this is the single upload path shared by EVERY
        // stream buffer — vertex, instance, UBO, SSBO, TBO alike. If a GL stall lives in here
        // it is a pipeline-wide problem, not a per-feature one, so the scopes are named by GL
        // target: a cost that shows up only under one target is a usage-pattern issue, while
        // one spread across all of them is the shared machinery.
        java.nio.ByteBuffer mapped;
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, profileMapName)) {
            mapped = map(byteCount);
        }
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, profileWriteName)) {
            mapped.asFloatBuffer().put(data, 0, floatCount);
        }
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, profileCommitName)) {
            CgTrace.add(CgChannels.GL, profileBytesName, byteCount);
            return commit(byteCount);
        }
    }

    /**
     * Fast path for tiny uploads, bypassing map/unmap. Default returns {@code false}, meaning
     * "not supported — use the normal path".
     *
     * <p>Only for storage that always writes at offset 0 -- orphaning shader-buffer storage. The frame
     * ring does not override it: an offset-0 write there would clobber a region still in flight.</p>
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
     * A vertex stream on {@code GL_ARRAY_BUFFER}, on {@link CgCapabilities#vertexStreamTier()}. On a ring
     * tier every {@link #commit} returns a new offset; {@code CgVertexArrayBinding} re-points its attributes
     * to it.
     *
     * @param capacityBytes initial GL buffer capacity in bytes
     */
    public static CgStreamBuffer create(int capacityBytes) {
        return create(CgGL.GL_ARRAY_BUFFER, capacityBytes);
    }
    
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
     * readable until the next one: {@link CgCapabilities#shaderStreamTier()}. Never a ring tier, on purpose: a
     * material block is written once and bound for many frames, {@code glBindBufferBase} reads at offset 0,
     * and the TBO path runs exactly where {@code glTexBufferRange} is missing.
     *
     * @param target        GL buffer target (e.g. {@code GL_UNIFORM_BUFFER}, {@code GL_SHADER_STORAGE_BUFFER})
     * @param capacityBytes initial GL buffer capacity in bytes
     */
    public static CgStreamBuffer createForShaderBuffer(int target, int capacityBytes) {
        return CgCapabilities.detect().shaderStreamTier() == StreamBufferTier.ORPHAN
                ? new MapAndOrphanStreamBuffer(target, capacityBytes)
                : new SubDataStreamBuffer(target, capacityBytes);
    }
}
