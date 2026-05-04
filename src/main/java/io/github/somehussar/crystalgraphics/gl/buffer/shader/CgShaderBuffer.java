package io.github.somehussar.crystalgraphics.gl.buffer.shader;

import io.github.somehussar.crystalgraphics.api.CgCapabilities;
import io.github.somehussar.crystalgraphics.api.shader.CgObjectBuffer;
import io.github.somehussar.crystalgraphics.gl.buffer.CgStreamBuffer;
import io.github.somehussar.crystalgraphics.gl.buffer.staging.CgBufferWriter;
import io.github.somehussar.crystalgraphics.gl.buffer.staging.CgStagingBuffer;
import lombok.Getter;

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
 *   <li>A write-session API ({@link #beginWrite}/{@link #advanceRecord}/{@link #endWrite})
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
 * <p>{@link #create(int)} and {@link #create(int, int)} select the best available SSBO/TBO
 * backend via {@link CgCapabilities#preferredShaderBufferPath()}. Use the concrete constructors
 * directly when you need a specific type.</p>
 *
 * <h3>SSBO/TBO write lifecycle</h3>
 * <pre>{@code
 * buffer.beginWrite(N);
 * for (int i = 0; i < N; i++) {
 *     writer().beginRecord();
 *     writer().mat4(model).mat3Padded(normal).vec4Zero()...;
 *     writer().endRecord(FLOATS_PER_OBJECT);
 *     buffer.advanceRecord();
 * }
 * buffer.endWrite();
 * buffer.bind(N);
 * // draw N instances
 * buffer.unbind();
 * }</pre>
 */
public abstract class CgShaderBuffer implements CgObjectBuffer {

    /**
     * GL binding point for per-object SSBO/TBO data (binding = 0).
     * Matches the {@code layout(binding = 0)} declaration in {@code cg_env.glsl}.
     */
    public static final int BINDING_POINT = 0;

    /**
     * Default GL texture unit reserved for the TBO object-data sampler ({@code cg_ObjectTBO}).
     * Set this sampler uniform once after program link on the TBO path:
     * {@code shader.set1i("cg_ObjectTBO", CgShaderBuffer.DEFAULT_TBO_TEXTURE_UNIT)}.
     */
    public static final int DEFAULT_TBO_TEXTURE_UNIT = 7;

    /**
     * Default CrystalShader per-object record size: 44 floats / 176 bytes.
     * Matches the std430/TBO ABI declared in {@code cg_env.glsl}:
     * {@code mat4} modelMatrix (16) + {@code mat3} normalMatrix padded to 3×vec4 (12) + 4×{@code vec4} custom (16).
     * Pass to {@link #create(int, int)} or use the {@link #create(int)} shorthand.
     */
    public static final int FLOATS_PER_OBJECT = 44;
    
    private final int floatPerRecord;
    protected final CgBufferWriter writer;
    protected final CgStreamBuffer dataBuffer;

    /**
     * Set to {@code true} by {@link #delete()}. Checked by {@link #bind(int)} to guard
     * against use-after-free. Declared {@code volatile} so deletion on one thread is
     * immediately visible to bind calls on the render thread.
     */
    protected volatile boolean deleted;

    private int writeHead;
    private int declaredWriteCount;

    /**
     * Number of records successfully written by the most recent {@link #endWrite()} call.
     * {@code -1} until the first successful {@link #endWrite()}.
     * Used by {@link #bind(int)} to validate the draw count.
     */
    @Getter private int lastWrittenCount = -1;

    private boolean inWrite;

    // ── Constructors ──────────────────────────────────────────────────────────

    /**
     * Record-mode constructor for SSBO/TBO backends.
     * Allocates staging for {@code initialCapacity} records of {@code floatPerRecord} floats each.
     * The GL stream buffer is sized accordingly via {@link CgStreamBuffer#createForShaderBuffer}.
     *
     * @param floatPerRecord floats per record (= record stride for {@link CgBufferWriter})
     * @param initialCapacity number of records to pre-allocate for
     * @param glTarget        GL buffer target (e.g. {@code GL_SHADER_STORAGE_BUFFER}, {@code GL_ARRAY_BUFFER})
     */
    protected CgShaderBuffer(int floatPerRecord, int initialCapacity, int glTarget) {
        this.floatPerRecord = floatPerRecord;
        this.writer          = new CgBufferWriter(new CgStagingBuffer(floatPerRecord, initialCapacity), floatPerRecord);
        this.dataBuffer      = CgStreamBuffer.createForShaderBuffer(glTarget, initialCapacity * floatPerRecord * Float.BYTES);
    }

    /**
     * Flat-mode constructor for UBO backends.
     * Allocates a flat staging buffer of {@code initialFloats} capacity with no fixed record stride.
     * The GL stream buffer is sized to {@code initialFloats × Float.BYTES}.
     * Sets {@link #lastWrittenCount} to {@code 0} so the parent {@link #bind()} path is valid
     * immediately after construction — UBO has no write session and therefore never calls
     * {@link #endWrite()} to set this value.
     *
     * @param glTarget      GL buffer target (e.g. {@code GL_UNIFORM_BUFFER})
     * @param initialFloats initial staging and GPU buffer capacity in floats
     */
    protected CgShaderBuffer(int glTarget, int initialFloats) {
        this.floatPerRecord  = 0;
        this.writer           = new CgBufferWriter(new CgStagingBuffer(initialFloats), 0);
        this.dataBuffer       = CgStreamBuffer.createForShaderBuffer(glTarget, initialFloats * Float.BYTES);
        this.lastWrittenCount = 0;
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    /**
     * Creates the best available SSBO/TBO shader buffer for {@code initialCapacity} objects
     * using the default {@link #FLOATS_PER_OBJECT} record stride.
     *
     * @param initialCapacity number of per-object records to pre-allocate
     * @return {@link CgShaderStorageBuffer} or {@link CgTextureBuffer} depending on hardware
     * @throws UnsupportedOperationException if the hardware does not support GL 3.3+
     */
    public static CgShaderBuffer create(int initialCapacity) {
        return create(FLOATS_PER_OBJECT, initialCapacity);
    }

    /**
     * Creates the best available SSBO/TBO shader buffer with a custom record stride.
     *
     * @param floatPerRecord floats per object record; use {@link #FLOATS_PER_OBJECT} for the
     *                        default CrystalShader ABI or any other positive stride for custom layouts
     * @param initialCapacity number of records to pre-allocate
     * @return {@link CgShaderStorageBuffer} or {@link CgTextureBuffer} depending on hardware
     * @throws UnsupportedOperationException if the hardware does not support GL 3.3+
     */
    public static CgShaderBuffer create(int floatPerRecord, int initialCapacity) {
        CgCapabilities.ShaderBufferPath path = CgCapabilities.detect().preferredShaderBufferPath();
        if (path == CgCapabilities.ShaderBufferPath.NONE) {
            throw new UnsupportedOperationException("GL 3.3+ required for CrystalShader object buffers");
        }
        initialCapacity = Math.max(1, initialCapacity);
        if (path == CgCapabilities.ShaderBufferPath.TBO) {
            return new CgTextureBuffer(floatPerRecord, initialCapacity);
        }
        return new CgShaderStorageBuffer(path, floatPerRecord, initialCapacity);
    }

    // ── Write API ─────────────────────────────────────────────────────────────

    /**
     * Returns the record stride this buffer was constructed with (floats per record).
     * Zero for flat-mode (UBO) instances.
     */
    public int floatPerRecord() {
        return floatPerRecord;
    }

    /**
     * Returns the {@link CgBufferWriter} for filling per-object or per-frame data.
     * In record mode, bracket each object with {@link CgBufferWriter#beginRecord()} /
     * {@link CgBufferWriter#endRecord(int)} and call {@link #advanceRecord()} after each.
     */
    public CgBufferWriter writer() {
        return writer;
    }

    /**
     * Opens a write session for {@code instanceCount} object records.
     * Resets the writer cursor and validates that the session is not already open.
     *
     * @param instanceCount number of records that will be written in this session;
     *                      {@link #advanceRecord()} will throw if this count is exceeded
     * @throws IllegalStateException if a write session is already open
     */
    public void beginWrite(int instanceCount) {
        if (inWrite) throw new IllegalStateException("Already in a write session; call endWrite() first");
        writeHead = 0;
        declaredWriteCount = instanceCount;
        writer.reset();
        inWrite = true;
        lastWrittenCount = -1;
    }

    /**
     * Advances the internal record counter by one.
     * Must be called once per object after the writer has finished that object's record.
     *
     * @throws IllegalStateException if not in a write session or if the declared count is exceeded
     */
    public void advanceRecord() {
        if (!inWrite) throw new IllegalStateException("Not in a write session; call beginWrite() first");
        if (writeHead >= declaredWriteCount) {
            throw new IllegalStateException(
                "Write overflow: record " + writeHead + " but beginWrite() declared " + declaredWriteCount);
        }
        writeHead++;
    }

    /**
     * Closes the write session and uploads all staged data to the GPU.
     * Sets {@link #lastWrittenCount} to the number of records actually advanced via
     * {@link #advanceRecord()}, which may be less than the count declared in
     * {@link #beginWrite(int)}.
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
     * Binds this buffer to its GL binding point and validates the draw count.
     *
     * @param expectedDrawCount number of objects the draw call will render; must not exceed
     *                          the {@link #lastWrittenCount} from the most recent {@link #endWrite()}
     * @throws IllegalStateException if deleted, if no data has been uploaded yet, or if
     *                               {@code expectedDrawCount > lastWrittenCount}
     */
    public void bind(int expectedDrawCount) {
        if (deleted) throw new IllegalStateException("CgShaderBuffer has been deleted");
        if (lastWrittenCount < 0) {
            throw new IllegalStateException("No object data uploaded; call endWrite() first");
        }
        if (expectedDrawCount > lastWrittenCount) {
            throw new IllegalStateException(
                "expectedDrawCount " + expectedDrawCount + " > lastWrittenCount " + lastWrittenCount);
        }
        bindInternal();
    }

    /**
     * Binds this buffer using the full {@link #lastWrittenCount} as the expected draw count.
     * Equivalent to {@code bind(lastWrittenCount)}.
     */
    @Override public void bind()   { bind(Math.max(0, lastWrittenCount)); }

    /** Unbinds this buffer from its GL binding point. */
    @Override public void unbind() { unbindInternal(); }

    /** {@inheritDoc} */
    @Override public boolean isDeleted() { return deleted; }

    /**
     * Returns the GL buffer object ID of the underlying stream buffer.
     * Useful for passing to {@code glBindBufferBase} / {@code glTexBuffer} manually,
     * or for interop with external GL code.
     */
    @Override
    public int getGlBufferId() {
        return dataBuffer.getGlBuffer();
    }

    /**
     * Deletes the underlying GL stream buffer and calls {@link #deleteGlResources()} for
     * any additional GL objects owned by the subclass (e.g. the TBO texture).
     * Idempotent — subsequent calls are no-ops.
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
     * Called by {@link #endWrite()} (SSBO/TBO session upload) and by
     * {@link CgUniformBuffer#upload()} (UBO flat upload).
     *
     * @param data       source float array
     * @param floatCount number of floats to upload from {@code data[0..floatCount-1]}
     */
    protected final void uploadData(float[] data, int floatCount) {
        dataBuffer.uploadFloats(data, floatCount);
    }

    // ── Backend queries ───────────────────────────────────────────────────────

    /**
     * Returns the {@link CgCapabilities.ShaderBufferPath} that backs this buffer,
     * or {@code null} for types where the concept does not apply (e.g. UBO).
     */
    public CgCapabilities.ShaderBufferPath getPath() { return null; }

    /**
     * Returns the GL texture unit used by the TBO sampler ({@code cg_ObjectTBO}).
     * Always {@code 0} for non-TBO types; override in {@link CgTextureBuffer}.
     * Set this value as the {@code cg_ObjectTBO} sampler uniform after program link.
     */
    public int getTboTextureUnit() { return 0; }

    // ── Abstract backend contract ─────────────────────────────────────────────

    /**
     * Performs the concrete GL bind operation (e.g. {@code glBindBufferBase},
     * {@code glBindTexture}). Called by {@link #bind(int)} after all validations pass.
     */
    protected abstract void bindInternal();

    /**
     * Performs the concrete GL unbind operation. Called by {@link #unbind()}.
     */
    protected abstract void unbindInternal();
}
