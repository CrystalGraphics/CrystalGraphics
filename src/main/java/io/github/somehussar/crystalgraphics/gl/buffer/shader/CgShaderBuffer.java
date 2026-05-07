package io.github.somehussar.crystalgraphics.gl.buffer.shader;

import io.github.somehussar.crystalgraphics.api.CgBindingPoints;
import io.github.somehussar.crystalgraphics.api.CgCapabilities;
import io.github.somehussar.crystalgraphics.api.buffer.CgBufferFormat;
import io.github.somehussar.crystalgraphics.api.buffer.CgObjectBuffer;
import io.github.somehussar.crystalgraphics.gl.buffer.CgStreamBuffer;
import io.github.somehussar.crystalgraphics.gl.buffer.staging.CgBufferWriter;
import io.github.somehussar.crystalgraphics.gl.buffer.staging.CgStagingBuffer;
import lombok.Getter;

import java.util.Objects;

/**
 * Abstract base class for all GPU shader buffer types (SSBO, TBO, UBO).
 *
 * <p>Owns the shared infrastructure that every concrete backend needs:</p>
 * <ul>
 *   <li>A {@link CgStreamBuffer} ({@code dataBuffer}) created via
 *       {@link CgStreamBuffer#createForShaderBuffer} — capped at {@link
 *       io.github.somehussar.crystalgraphics.gl.buffer.MapAndOrphanStreamBuffer} (Tier B)
 *       to guarantee offset-0 writes, which is required by {@code glBindBufferBase} and
 *       {@code glTexBuffer}.</li>
 *   <li>A {@link CgBufferWriter} backed by a {@link CgStagingBuffer} — either record-mode
 *       (SSBO/TBO, fixed stride per record) or flat-mode (UBO, arbitrary float sequence).</li>
 *   <li>A write-session API ({@link #beginWrite}/{@link #endRecord}/{@link #endWrite})
 *       that validates object count and drives GPU upload via {@link #endWrite()}.</li>
 *   <li>A {@link #delete()} template method that deletes the stream buffer then calls the
 *       {@link #deleteGlResources()} hook for subclass-owned GL objects.</li>
 * </ul>
 *
 * <h3>Concrete subclasses</h3>
 * <ul>
 *   <li>{@link CgShaderStorageBuffer} — SSBO (GL 4.3 core or {@code ARB_shader_storage_buffer_object})</li>
 *   <li>{@link CgTextureBuffer} — TBO (GL 3.1 fallback)</li>
 *   <li>{@link CgUniformBuffer} — UBO (flat-mode child; overrides {@link #bind()}/{@link #unbind()})</li>
 * </ul>
 *
 * <h3>Factory</h3>
 * <p>{@link #create(CgBufferFormat, int)} selects the best available SSBO/TBO
 * backend via {@link CgCapabilities#preferredShaderBufferPath()}. Use the concrete
 * constructors directly when you need a specific type.</p>
 *
 * <h3>SSBO/TBO write lifecycle</h3>
 * <pre>{@code
 * buffer.beginWrite(N);
 * for (int i = 0; i < N; i++) {
 *     writer().beginRecord()
 *             .mat4("modelMatrix", model)
 *             .mat4("normalMatrix", normal);
 *     // custom0-3 auto-zeroed
 *     buffer.endRecord();
 * }
 * buffer.endWrite();
 * buffer.bind();
 * // draw N instances
 * buffer.unbind();
 * }</pre>
 */
public abstract class CgShaderBuffer implements CgObjectBuffer {

    /** Binding point used for glBindBufferBase or as GL texture unit (TBO). Immutable after construction. */
    @Getter
    protected final int bindingLocation;

    protected final CgBufferWriter writer;
    protected final CgStreamBuffer dataBuffer;

    /** Format descriptor. Required — all shader buffers must have a typed format. */
    @Getter
    private final CgBufferFormat format;

    /**
     * Set to {@code true} by {@link #delete()}. Checked by {@link #bind()} to guard
     * against use-after-free.
     */
    protected volatile boolean deleted;

    private int writeHead;
    private int declaredWriteCount;

    /**
     * Number of records successfully written by the most recent {@link #endWrite()} call.
     * {@code -1} until the first successful {@link #endWrite()}.
     */
    @Getter private int lastWrittenCount = -1;

    private boolean inWrite;

    /**
     * Returns the {@link CgCapabilities.ShaderBufferPath} that backs this buffer,
     * or {@code null} for types where the concept does not apply (e.g. UBO).
     */
    @Getter
    protected CgCapabilities.ShaderBufferPath path;

    // ── Constructors ──────────────────────────────────────────────────────────

    /**
     * Unified constructor for all SSBO/TBO/UBO backends.
     * Initial capacity is one record (auto-grows on {@link #beginWrite(int)}).
     * {@link #lastWrittenCount} starts at {@code 0} — valid for both the single-block
     * UBO write cycle and for SSBO batches (reset to {@code -1} by {@link #beginWrite}).
     *
     * @param format          typed format descriptor (mandatory)
     * @param glTarget        GL buffer target
     * @param bindingLocation GL binding point; immutable after construction
     */
    protected CgShaderBuffer(CgBufferFormat format, int glTarget, int bindingLocation) {
        Objects.requireNonNull(format, "CgBufferFormat is required");
        this.bindingLocation  = bindingLocation;
        this.format           = format;
        int floatPerRecord    = format.getFloatCount();
        this.writer           = new CgBufferWriter(new CgStagingBuffer(floatPerRecord), format);
        this.dataBuffer       = CgStreamBuffer.createForShaderBuffer(glTarget, floatPerRecord * Float.BYTES);
        this.lastWrittenCount = 0;
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    /**
     * Creates the best available SSBO/TBO shader buffer driven by the given format descriptor.
     * The buffer starts at capacity 1 and auto-grows on {@link #beginWrite(int)}.
     * The binding location is mandatory and immutable — there is no default-binding overload
     * to avoid accidental shadowing of engine-reserved slots.
     *
     * @param format          typed buffer format descriptor (drives named writes and stride)
     * @param bindingLocation binding slot; must be {@code >= CgBindingPoints.USER_START}
     *                        to avoid stomping engine-reserved data
     * @return {@link CgShaderStorageBuffer} or {@link CgTextureBuffer} depending on hardware
     * @throws IllegalArgumentException  if {@code bindingLocation} is engine-reserved
     * @throws UnsupportedOperationException if the hardware does not support GL 3.3+
     */
    public static CgShaderBuffer create(CgBufferFormat format, int bindingLocation) {
        CgBindingPoints.validateBindingPoint(bindingLocation);
        CgCapabilities.ShaderBufferPath path = CgCapabilities.detect().preferredShaderBufferPath();
        if (path == CgCapabilities.ShaderBufferPath.NONE)
            throw new UnsupportedOperationException("GL 3.3+ required for CrystalShader object buffers");

        if (path == CgCapabilities.ShaderBufferPath.TBO)
            return new CgTextureBuffer(format, bindingLocation);

        return new CgShaderStorageBuffer(format, path, bindingLocation);
    }

    /**
     * Creates the best available SSBO/TBO shader buffer for engine-internal use with a
     * typed format descriptor. Bypasses the {@link CgBindingPoints#USER_START} guard so
     * engine-reserved binding points (0–9) are allowed.
     * The buffer starts at capacity 1 and auto-grows on {@link #beginWrite(int)}.
     *
     * <p><strong>Engine-internal. Do not use from user code.</strong></p>
     *
     * @param format          typed buffer format (drives named writes and stride)
     * @param bindingLocation binding slot (may be engine-reserved)
     * @return {@link CgShaderStorageBuffer} or {@link CgTextureBuffer} depending on hardware
     * @throws UnsupportedOperationException if the hardware does not support GL 3.3+
     */
    public static CgShaderBuffer createInternal(CgBufferFormat format, int bindingLocation) {
        CgCapabilities.ShaderBufferPath path = CgCapabilities.detect().preferredShaderBufferPath();
        if (path == CgCapabilities.ShaderBufferPath.NONE)
            throw new UnsupportedOperationException("GL 3.3+ required for CrystalShader object buffers");

        if (path == CgCapabilities.ShaderBufferPath.TBO)
            return new CgTextureBuffer(format, bindingLocation);

        return new CgShaderStorageBuffer(format, path, bindingLocation);
    }

    // ── Write API ─────────────────────────────────────────────────────────────

    /**
     * Returns the {@link CgBufferWriter} for filling per-object or per-frame data.
     * In record mode, bracket each object with {@link CgBufferWriter#beginRecord()} and
     * call {@link #endRecord()} after each record.
     */
    public CgBufferWriter writer() {
        return writer;
    }

    /**
     * Opens a write session for {@code instanceCount} object records.
     * Resets the writer cursor and validates that the session is not already open.
     *
     * @param instanceCount number of records that will be written in this session
     * @throws IllegalStateException if a write session is already open
     */
    public CgBufferWriter beginWrite(int instanceCount) {
        if (inWrite) throw new IllegalStateException("Already in a write session; call endWrite() first");
        writeHead = 0;
        declaredWriteCount = instanceCount;
        writer.reset();
        inWrite = true;
        lastWrittenCount = -1;
        return writer;
    }

    /**
     * Finalizes the current record and advances the internal record counter.
     *
     * <p>Two modes:</p>
     * <ul>
     *   <li><strong>SSBO/TBO (in write session)</strong>: validates the record count against
     *       {@link #beginWrite(int)}'s declaration, calls {@link CgBufferWriter#endRecord()},
     *       then increments {@link #writeHead}.</li>
     *   <li><strong>UBO (single-block, no write session)</strong>: calls
     *       {@link CgBufferWriter#endRecord()} and sets {@link #lastWrittenCount} to 1.
     *       No {@link #beginWrite(int)} is required for UBO use.</li>
     * </ul>
     *
     * @throws IllegalStateException if a write session is open and the declared count is exceeded
     */
    public void endRecord() {
        if (inWrite) {
            if (writeHead >= declaredWriteCount) {
                throw new IllegalStateException(
                    "Write overflow: record " + writeHead + " but beginWrite() declared " + declaredWriteCount);
            }
            writer.endRecord();
            writeHead++;
        } else {
            // Single-block (UBO) path — no session required.
            writer.endRecord();
            lastWrittenCount = 1;
        }
    }

    /**
     * Closes the write session and uploads all staged data to the GPU.
     * Sets {@link #lastWrittenCount} to the number of records written via {@link #endRecord()}.
     *
     * @throws IllegalStateException if not in a write session
     */
    public void endWrite() {
        if (!inWrite) throw new IllegalStateException("Not in a write session");
        dataBuffer.uploadFloats(writer.rawData(), writer.rawCursor());
        lastWrittenCount = writeHead;
        inWrite = false;
    }

    // ── Bind / unbind ─────────────────────────────────────────────────────────

    /**
     * Binds this buffer to its GL binding point.
     *
     * @throws IllegalStateException if this buffer has been deleted
     */
    @Override public void bind() {
        if (deleted) throw new IllegalStateException("CgShaderBuffer has been deleted");
        bindInternal();
    }

    /** Unbinds this buffer from its GL binding point. */
    @Override public void unbind() { unbindInternal(); }

    @Override public boolean isDeleted() { return deleted; }

    /**
     * Returns the GL buffer object ID of the underlying stream buffer.
     */
    @Override
    public int getGlBufferId() {
        return dataBuffer.getGlBuffer();
    }

    /**
     * Deletes the underlying GL stream buffer and calls {@link #deleteGlResources()} for
     * any additional GL objects owned by the subclass. Idempotent — subsequent calls are no-ops.
     */
    @Override
    public void delete() {
        if (!deleted) {
            dataBuffer.delete();
            deleteGlResources();
            deleted = true;
        }
    }

    /**
     * Extension hook called by {@link #delete()} after the stream buffer has been deleted.
     * Override to release additional GL resources owned by a subclass (e.g. a texture ID).
     * Default implementation is a no-op.
     */
    protected void deleteGlResources() {}

    /**
     * Uploads {@code floatCount} floats from {@code data} to the GPU via the stream buffer.
     * Called by {@link #endWrite()} and by {@link CgUniformBuffer#upload()}.
     */
    protected final void uploadData(float[] data, int floatCount) {
        dataBuffer.uploadFloats(data, floatCount);
    }


    // ── Abstract backend contract ─────────────────────────────────────────────

    /**
     * Performs the concrete GL bind operation. Called by {@link #bind(int)} after validations.
     */
    protected abstract void bindInternal();

    /**
     * Performs the concrete GL unbind operation. Called by {@link #unbind()}.
     */
    protected abstract void unbindInternal();
}
