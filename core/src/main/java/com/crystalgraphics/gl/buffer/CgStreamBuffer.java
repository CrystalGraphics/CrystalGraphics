package com.crystalgraphics.gl.buffer;

import com.crystalgraphics.platform.gl.CgCapabilities;

import com.crystalgraphics.api.buffer.CgObjectBuffer;
import lombok.Getter;
import com.crystalgraphics.platform.gl.CgGL;

import java.nio.ByteBuffer;

/**
 * Abstract streaming buffer that manages per-frame dynamic GPU data upload.
 *
 * <p>One {@code CgStreamBuffer} exists per {@code CgVertexFormat} in active use for vertex data,
 * or per shader buffer type for uniform/shader-storage/texture-buffer data.
 * All consumers sharing a format or buffer target share the same stream buffer instance.</p>
 *
 * <h3>Streaming strategies (waterfall)</h3>
 * <p>Three concrete implementations cover the full hardware range:</p>
 * <ol>
 *   <li>{@link MapAndSyncStreamBuffer} — Tier A (best): 3-slot ring buffer with {@code ARB_sync}
 *       fences. CPU writes slot N while GPU reads N-1 and N-2. Zero stalls in steady state.
 *       Requires {@link CgCapabilities#isArbSync()}.</li>
 *   <li>{@link MapAndOrphanStreamBuffer} — Tier B: full-buffer orphan via
 *       {@code glMapBufferRange(GL_MAP_INVALIDATE_BUFFER_BIT)}. Driver allocates new backing
 *       storage on each map; no CPU/GPU sync. Always writes at offset 0.
 *       Requires {@link CgCapabilities#isMapBufferRangeSupported()}.</li>
 *   <li>{@link SubDataStreamBuffer} — Tier C (baseline): CPU staging {@code ByteBuffer} +
 *       {@code glBufferSubData}. Works on all GL 1.5+ hardware.</li>
 * </ol>
 *
 * <h3>Factory methods</h3>
 * <ul>
 *   <li>{@link #create(int)} — vertex data; full Tier A→B→C waterfall with {@code GL_ARRAY_BUFFER}.</li>
 *   <li>{@link #createForShaderBuffer(int, int)} — UBO/SSBO/TBO; capped at Tier B.
 *       Tier A is excluded because its non-zero slot offsets are incompatible with
 *       {@code glBindBufferBase} (always reads at offset 0) and {@code glTexBuffer}
 *       (whole-buffer attachment, no sub-range until GL 4.3).</li>
 * </ul>
 *
 * <h3>Per-frame upload contract</h3>
 * <ol>
 *   <li>Call {@link #map(int)} to obtain a writable {@code ByteBuffer}.</li>
 *   <li>Write data into it.</li>
 *   <li>Call {@link #commit(int)} with the number of bytes actually written.
 *       Returns the byte offset within the GL buffer where the data starts
 *       (non-zero on the Tier A ring path; always 0 on Tier B and C).</li>
 *   <li>Issue the draw call that consumes this data.</li>
 *   <li>Call {@link #afterSubmit()} so ring-buffer implementations can place their fence.</li>
 * </ol>
 */
public abstract class CgStreamBuffer implements CgObjectBuffer {

    /** The GL buffer object name allocated at construction. Never changes for the lifetime of this instance. */
    @Getter
    protected final int glBuffer;

    /** The GL buffer target this buffer was created for (e.g. {@code GL_ARRAY_BUFFER}, {@code GL_UNIFORM_BUFFER}). */
    @Getter
    protected final int target;

    /** Current allocated GL buffer size in bytes. Updated whenever the buffer grows. */
    @Getter
    protected int capacityBytes;

    /**
     * Byte offset of the most recently committed upload within the GL buffer.
     * Always {@code 0} on Tier B (orphan) and Tier C (subdata) paths.
     * Non-zero on the Tier A ring path — equal to {@code currentSlot * slotSize}.
     */
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
    }

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
     * <p>This call only finalises the CPU write. Backends that track GPU consumption
     * (e.g. the sync ring-buffer) place their fence in {@link #afterSubmit()}, which is called
     * after the draw command has been submitted. Do not assume the GPU has consumed the data
     * when {@code commit} returns.</p>
     *
     * @param usedBytes number of bytes actually written since {@link #map(int)}
     * @return byte offset in the GL buffer where this upload's data begins;
     *         always {@code 0} on Tier B and C, {@code slot * slotSize} on Tier A
     */
    public abstract int commit(int usedBytes);

    /**
     * Called after the draw command that consumes the most recently committed upload has been
     * submitted to GL. The sync ring-buffer ({@link MapAndSyncStreamBuffer}) uses this to place
     * its {@code ARB_sync} fence. Default implementation is a no-op.
     */
    public void afterSubmit() {
    }

    /**
     * Convenience method: maps, copies {@code floatCount} floats from {@code data[0..floatCount-1]},
     * commits, and returns the byte offset where the data starts.
     *
     * <p>The caller is responsible for calling {@link #afterSubmit()} after the draw call
     * that consumes this upload.</p>
     *
     * @param data       source float array
     * @param floatCount number of floats to copy
     * @return byte offset in the GL buffer where the uploaded data begins
     */
    public int uploadFloats(float[] data, int floatCount) {
        int byteCount = floatCount * Float.BYTES;
        java.nio.ByteBuffer mapped = map(byteCount);
        mapped.asFloatBuffer().put(data, 0, floatCount);
        return commit(byteCount);
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
     * Creates the best available stream buffer for vertex data using {@code GL_ARRAY_BUFFER}.
     * Full Tier A→B→C waterfall. The Tier A path may return non-zero byte offsets from
     * {@link #commit} — callers (e.g. {@code CgVertexArrayBinding}) handle this by rebinding
     * VAO attribute pointers with the returned offset.
     *
     * @param capacityBytes initial GL buffer capacity in bytes
     */
    public static CgStreamBuffer create(int capacityBytes) {
        return create(CgGL.GL_ARRAY_BUFFER, capacityBytes);
    }
    
    /**
     * Creates the best available stream buffer for vertex data using the given GL target.
     * Full Tier A→B→C waterfall. The Tier A path may return non-zero byte offsets from
     * {@link #commit} — callers (e.g. {@code CgVertexArrayBinding}) handle this by rebinding
     * VAO attribute pointers with the returned offset.
     *
     * @param target GL target (e.g. {@code GL_ARRAY_BUFFER}, {@code GL_ELEMENT_ARRAY_BUFFER}, {@code GL_SHADER_STORAGE_BUFFER})
     * @param capacityBytes initial GL buffer capacity in bytes
     */
    public static CgStreamBuffer create(int target, int capacityBytes) {
        CgCapabilities caps = CgCapabilities.detect();
        if (caps.isArbSync()) return new MapAndSyncStreamBuffer(target, capacityBytes);
        if (caps.isMapBufferRangeSupported()) return new MapAndOrphanStreamBuffer(target, capacityBytes);
        return new SubDataStreamBuffer(target, capacityBytes);
    }

    /**
     * Creates the best available stream buffer for shader data (UBO, SSBO, TBO) on the given GL target.
     * Capped at Tier B ({@link MapAndOrphanStreamBuffer}) — the Tier A sync ring-buffer is excluded
     * because it returns non-zero slot offsets from {@link #commit}, which are incompatible with:
     * <ul>
     *   <li>{@code glBindBufferBase} — always reads at offset 0</li>
     *   <li>{@code glTexBuffer} — attaches the whole buffer object; {@code glTexBufferRange}
     *       requires GL 4.3 which is beyond our minimum target</li>
     * </ul>
     * {@link MapAndOrphanStreamBuffer} always writes at offset 0, making it safe for all three
     * shader buffer bind paths without requiring {@code glBindBufferRange} or {@code glTexBufferRange}.
     *
     * @param target        GL buffer target (e.g. {@code GL_UNIFORM_BUFFER}, {@code GL_SHADER_STORAGE_BUFFER})
     * @param capacityBytes initial GL buffer capacity in bytes
     */
    public static CgStreamBuffer createForShaderBuffer(int target, int capacityBytes) {
        CgCapabilities caps = CgCapabilities.detect();
        if (caps.isMapBufferRangeSupported()) return new MapAndOrphanStreamBuffer(target, capacityBytes);
        return new SubDataStreamBuffer(target, capacityBytes);
    }
}
