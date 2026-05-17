package com.crystalgraphics.gl.buffer.shader;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.gl.buffer.MapAndOrphanStreamBuffer;
import com.crystalgraphics.api.CgBindingPoints;
import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.api.buffer.CgBufferFormat;
import com.crystalgraphics.api.buffer.CgObjectBuffer;
import com.crystalgraphics.api.shader.CgShader;
import com.crystalgraphics.gl.buffer.CgStreamBuffer;
import com.crystalgraphics.gl.buffer.staging.CgBufferWriter;
import com.crystalgraphics.gl.buffer.staging.CgStagingBuffer;
import lombok.Getter;

import java.util.Objects;

/**
 * Abstract base class for all GPU shader buffer types (SSBO, TBO, UBO).
 *
 * <p>Owns the shared infrastructure that every concrete backend needs:</p>
 * <ul>
 *   <li>A {@link CgStreamBuffer} ({@code dataBuffer}) created via
 *       {@link CgStreamBuffer#createForShaderBuffer} — capped at {@link
 *       MapAndOrphanStreamBuffer} (Tier B)
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
 * <p>{@link #create(String, CgBufferFormat, int)} selects the best available SSBO/TBO
 * backend via {@link CgCapabilities#shaderBufferPath()}. The {@code userIndex}
 * is 0-based; {@link CgBindingPoints} is added internally. Use
 * {@link #createInternal(String, CgBufferFormat, int)} for engine-reserved binding points.</p>
 *
 * <h3>Shader wiring</h3>
 * <p>Call {@link #bind(CgShader)} after {@code shader.bind()} to both bind the buffer
 * and wire it to the active program in one call. Each subclass implements
 * {@link #wireShader(CgShader)} for its specific wiring strategy:</p>
 * <ul>
 *   <li>SSBO — calls {@code glShaderStorageBlockBinding} to associate the named block with
 *       {@link #bindingLocation}. Post-link wiring; idempotent.</li>
 *   <li>TBO — sets the {@code samplerBuffer} uniform to {@link #bindingLocation} via
 *       {@code glUniform1i}.</li>
 *   <li>UBO — calls {@code glUniformBlockBinding} to associate block index with slot.</li>
 * </ul>
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
 * shader.bind();
 * buffer.bind(shader);   // SSBO: glShaderStorageBlockBinding; TBO: sets samplerBuffer uniform
 * // draw N instances
 * buffer.unbind();
 * shader.unbind();
 * }</pre>
 */
public abstract class CgShaderBuffer implements CgObjectBuffer {

    /** Binding point used for glBindBufferBase or as GL texture unit (TBO). Immutable after construction. */
    @Getter
    protected final int bindingLocation;

    /**
     * Debug/sampler/block name for this buffer.
     * <ul>
     *   <li>SSBO — debug label, appears in error messages and registry keys.</li>
     *   <li>TBO — sampler name used in {@code glGetUniformLocation} during {@link #bind(CgShader)}.</li>
     *   <li>UBO — block name used in {@code glGetUniformBlockIndex} during {@link #bind(CgShader)}.</li>
     * </ul>
     */
    @Getter
    private final String name;

    protected final CgBufferWriter writer;
    protected final CgStreamBuffer dataBuffer;

    /** Format descriptor. Required — all shader buffers must have a typed format. */
    @Getter
    private CgBufferFormat format;

    /**
     * Updates the format descriptor of this buffer without recreating GL resources.
     * Called by {@link CgUniformBuffer#resetFormat(CgBufferFormat)} when a material
     * is recompiled with a changed properties layout.
     */
    protected void resetFormat(CgBufferFormat newFormat) {
        this.format = newFormat;
    }

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
     * @param name            debug/sampler/block name (must be non-null)
     * @param format          typed format descriptor (mandatory)
     * @param glTarget        GL buffer target
     * @param bindingLocation GL binding point; immutable after construction
     */
    protected CgShaderBuffer(String name, CgBufferFormat format, int glTarget, int bindingLocation) {
        Objects.requireNonNull(name,   "name is required");
        Objects.requireNonNull(format, "CgBufferFormat is required");
        this.name             = name;
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
     *
     * <p>The {@code userIndex} is 0-based. The actual binding point is derived from
     * {@link CgBindingPoints#USER_START_SSBO} or {@link CgBindingPoints#USER_START_TBO}
     * depending on the active path, so user code is free of magic offset arithmetic.</p>
     *
     * <p>Examples: SSBO path — {@code userIndex 0} → binding {@code CgBindingPoints.USER_START_SSBO + 0 (= 0)};
     * TBO path — {@code userIndex 0} → texture unit {@code CgBindingPoints.USER_START_TBO + 0 (= 5)}.</p>
     *
     * @param name      debug/sampler name (must be non-null)
     * @param format    typed buffer format descriptor
     * @param userIndex 0-based user slot index (0 = first user slot after engine range)
     * @return {@link CgShaderStorageBuffer} or {@link CgTextureBuffer} depending on hardware
     * @throws UnsupportedOperationException if the hardware does not support GL 3.3+
     */
    public static CgShaderBuffer create(String name, CgBufferFormat format, int userIndex) {
        CgCapabilities.ShaderBufferPath path = CgCapabilities.detect().shaderBufferPath();

        int binding = path == CgCapabilities.ShaderBufferPath.TBO
                ? CgBindingPoints.USER_START_TBO + userIndex
                : CgBindingPoints.USER_START_SSBO + userIndex;
        
        return createInternal(name, format, binding);
    }

    /**
     * Creates the best available SSBO/TBO shader buffer for engine-internal use.
     * Accepts raw binding points (may be engine-reserved 0–4). No USER_START offset is added.
     *
     * <p><strong>Engine-internal. Do not use from user code.</strong></p>
     *
     * @param name            debug/sampler name (must be non-null)
     * @param format          typed buffer format
     * @param bindingPoint    binding slot (may be engine-reserved)
     * @return {@link CgShaderStorageBuffer} or {@link CgTextureBuffer} depending on hardware
     * @throws UnsupportedOperationException if the hardware does not support GL 3.3+
     */
    public static CgShaderBuffer createInternal(String name, CgBufferFormat format, int bindingPoint) {
        CgCapabilities.ShaderBufferPath path = CgCapabilities.detect().shaderBufferPath();
        if (path == CgCapabilities.ShaderBufferPath.NONE)
            throw new UnsupportedOperationException("GL 3.3+ required for CrystalShader object buffers");

        if (path == CgCapabilities.ShaderBufferPath.TBO)
            return new CgTextureBuffer(name, format, bindingPoint);

        return new CgShaderStorageBuffer(name, format, path, bindingPoint);
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

    /**
     * Binds this buffer AND wires it to {@code shader}.
     *
     * <p><strong>Precondition:</strong> {@code shader.bind()} must have been called before this
     * method. GL uniform/block-index queries require the program to be currently active.</p>
     *
     * <p>Behavior by type:</p>
     * <ul>
     *   <li><strong>SSBO</strong> — calls {@code glShaderStorageBlockBinding} to associate the
     *       named block with {@link #bindingLocation}. Post-link, per-program; idempotent.</li>
     *   <li><strong>TBO</strong> — activates the texture unit, binds the texture, then sets
     *       {@code glUniform1i(getName(), bindingLocation)} to wire the {@code samplerBuffer}.</li>
     *   <li><strong>UBO</strong> — {@code glBindBufferBase(GL_UNIFORM_BUFFER, …)} then
     *       {@code glUniformBlockBinding(programId, blockIndex, bindingLocation)}.
     *       Replaces the deleted {@code bindBlock()} methods.</li>
     * </ul>
     *
     * @param shader the currently-bound shader program to wire; must not be null.
     *               If this buffer is attached to a {@link CgMaterial},
     *               the material calls {@link #wireShader(CgShader)} automatically on each compile —
     *               prefer the no-arg {@link #bind()} in that case; {@code bind(CgShader)} is for
     *               standalone (non-material) usage only, where the caller manages the active program.
     * @throws IllegalStateException if this buffer has been deleted
     */
    public void bind(CgShader shader) {
        bind();
        wireShader(shader);
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
     * Performs the concrete GL bind operation. Called by {@link #bind()} after validations.
     */
    protected abstract void bindInternal();

    /**
     * Performs the concrete GL unbind operation. Called by {@link #unbind()}.
     */
    protected abstract void unbindInternal();

    /**
     * Wires this buffer to the given shader program WITHOUT establishing the
     * per-context GL binding. Calls only {@link #wireShader(CgShader)}.
     *
     * <p>Use this after each program link to set per-program block/sampler
     * associations. The per-context binding ({@code glBindBufferBase} /
     * {@code glActiveTexture+glBindTexture}) is handled separately in
     * {@link #bind()} — typically called once per frame from
     * {@code CgMaterialPipeline.beginFrame()}.</p>
     *
     * <p>{@code shader} must not be null and must be the currently-bound program —
     * the GL program must be active via {@code shader.bind()} before this call,
     * as GL uniform/block-index queries require the program to be active.</p>
     *
     * <p>If this buffer is attached to a {@link CgMaterial},
     * {@code wireShader} is called automatically on each material compile — do not call
     * this manually in that case.</p>
     *
     * @param shader the currently-bound shader program; must not be null
     */
    public abstract void wireShader(CgShader shader);
}
