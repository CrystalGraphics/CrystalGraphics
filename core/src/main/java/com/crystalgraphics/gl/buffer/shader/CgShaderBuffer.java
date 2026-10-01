package com.crystalgraphics.gl.buffer.shader;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.gl.buffer.MapAndOrphanStreamBuffer;
import com.crystalgraphics.api.CgBindingPoints;
import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.api.buffer.CgBufferFormat;
import com.crystalgraphics.api.buffer.CgBufferLifetime;
import com.crystalgraphics.api.buffer.CgObjectBuffer;
import com.crystalgraphics.api.shader.CgShader;
import com.crystalgraphics.gl.buffer.CgFrameRing;
import com.crystalgraphics.gl.buffer.CgStreamBuffer;
import com.crystalgraphics.gl.buffer.staging.CgBufferWriter;
import com.crystalgraphics.gl.buffer.staging.CgStagingBuffer;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;
import lombok.Getter;

import java.util.Objects;

/**
 * Abstract base class for all GPU shader buffer types (SSBO, TBO, UBO).
 *
 * <p>Owns the shared infrastructure that every concrete backend needs:</p>
 * <ul>
 *   <li>A {@link CgStreamBuffer} ({@code dataBuffer}) created via
 *       {@link CgStreamBuffer#createForShaderBuffer}, which orphans on every upload and writes at
 *       offset 0 -- what {@code glBindBufferBase} and {@code glTexBuffer} read, and what keeps a
 *       buffer written once readable for as many frames as it is bound. A {@link CgBufferLifetime#FRAME}
 *       buffer is uploaded in every frame that reads it instead, and sits on the frame ring at a new offset
 *       per upload; its binding follows each upload ({@link #uploadData}).</li>
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
     * How long an upload stays readable, as asked at creation. {@link #isOnFrameRing()} is what the storage
     * became: a TBO or a forced stream tier falls back from {@link CgBufferLifetime#FRAME}.
     */
    @Getter
    private final CgBufferLifetime lifetime;

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
        this(name, format, glTarget, bindingLocation, CgBufferLifetime.RETAINED);
    }

    /** @param lifetime {@link CgBufferLifetime#FRAME} takes the frame ring ({@link CgStreamBuffer#createFrameLocal}) */
    protected CgShaderBuffer(String name, CgBufferFormat format, int glTarget, int bindingLocation,
                             CgBufferLifetime lifetime) {
        Objects.requireNonNull(name,   "name is required");
        Objects.requireNonNull(format, "CgBufferFormat is required");
        Objects.requireNonNull(lifetime, "CgBufferLifetime is required");
        this.name             = name;
        this.bindingLocation  = bindingLocation;
        this.format           = format;
        this.lifetime         = lifetime;
        int floatPerRecord    = format.getFloatCount();
        int capacityBytes     = floatPerRecord * Float.BYTES;
        this.writer           = new CgBufferWriter(new CgStagingBuffer(floatPerRecord), format);
        this.dataBuffer       = lifetime == CgBufferLifetime.FRAME
                ? CgStreamBuffer.createFrameLocal(glTarget, capacityBytes)
                : CgStreamBuffer.createForShaderBuffer(glTarget, capacityBytes);
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
        return create(name, format, userIndex, CgBufferLifetime.RETAINED);
    }

    /**
     * As {@link #create(String, CgBufferFormat, int)}, with the contents' {@link CgBufferLifetime}.
     *
     * <pre>{@code
     * CgShaderBuffer particles = CgShaderBuffer.create("Particles", PARTICLE_FORMAT, 0, CgBufferLifetime.FRAME);
     * particles.beginWrite(n);
     * // ... n records ...
     * particles.endWrite();          // this frame's region, re-bound there
     * mesh.drawInstanced(n);
     * }</pre>
     */
    public static CgShaderBuffer create(String name, CgBufferFormat format, int userIndex, CgBufferLifetime lifetime) {
        CgCapabilities.ShaderBufferPath path = CgCapabilities.detect().shaderBufferPath();

        int binding = path == CgCapabilities.ShaderBufferPath.TBO
                ? CgBindingPoints.USER_START_TBO + userIndex
                : CgBindingPoints.USER_START_SSBO + userIndex;

        return createInternal(name, format, binding, lifetime);
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
        return createInternal(name, format, bindingPoint, CgBufferLifetime.RETAINED);
    }

    /**
     * As {@link #createInternal(String, CgBufferFormat, int)}, with the contents' {@link CgBufferLifetime}. A TBO
     * takes {@link CgBufferLifetime#RETAINED}'s storage whatever is asked: it cannot bind an offset.
     */
    public static CgShaderBuffer createInternal(String name, CgBufferFormat format, int bindingPoint,
                                                CgBufferLifetime lifetime) {
        CgCapabilities.ShaderBufferPath path = CgCapabilities.detect().shaderBufferPath();
        if (path == CgCapabilities.ShaderBufferPath.NONE)
            throw new UnsupportedOperationException("GL 3.3+ required for CrystalShader object buffers");

        if (path == CgCapabilities.ShaderBufferPath.TBO)
            return new CgTextureBuffer(name, format, bindingPoint);

        return new CgShaderStorageBuffer(name, format, path, bindingPoint, lifetime);
    }

    /**
     * Wires {@code shader}'s SSBO or TBO named {@code name} to {@code binding}, with no buffer object of its own: for a
     * block whose buffer is bound per draw by someone else, as the executor binds the object records. The shader is
     * bound.
     */
    public static void wireBlock(CgShader shader, String name, CgBindingPoints.Binding binding) {
        int program = shader.getProgram().getId();
        if (CgCapabilities.detect().shaderBufferPath() == CgCapabilities.ShaderBufferPath.TBO) {
            int location = shader.getUniformLocation(name);
            if (location >= 0) shader.getProgram().setUniform1i(location, binding.tbo());
        } else {
            int index = CgGL.glGetProgramResourceIndex(program, CgGL.GL_SHADER_STORAGE_BLOCK, name);
            if (index != CgGL.GL_INVALID_INDEX) CgGL.glShaderStorageBlockBinding(program, index, binding.ssbo());
        }
    }

    /**
     * Creates the best available SSBO/TBO shader buffer for engine-internal use, resolving
     * {@code binding} against the currently-active capability path (see
     * {@link CgBindingPoints.Binding#resolve()}) instead of the caller doing that resolution
     * itself and passing a raw {@code int}.
     *
     * <p><strong>Engine-internal. Do not use from user code.</strong></p>
     *
     * @param name    debug/sampler name (must be non-null)
     * @param format  typed buffer format
     * @param binding the reserved {@link CgBindingPoints.Binding} to bind at
     * @return {@link CgShaderStorageBuffer} or {@link CgTextureBuffer} depending on hardware
     * @throws UnsupportedOperationException if the hardware does not support GL 3.3+
     */
    public static CgShaderBuffer createInternal(String name, CgBufferFormat format, CgBindingPoints.Binding binding) {
        return createInternal(name, format, binding.resolve());
    }

    /** As {@link #createInternal(String, CgBufferFormat, CgBindingPoints.Binding)}, with a {@link CgBufferLifetime}. */
    public static CgShaderBuffer createInternal(String name, CgBufferFormat format, CgBindingPoints.Binding binding,
                                                CgBufferLifetime lifetime) {
        return createInternal(name, format, binding.resolve(), lifetime);
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
        uploadData(writer.rawData(), writer.rawCursor());
        lastWrittenCount = writeHead;
        inWrite = false;
    }

    /**
     * Uploads pre-accumulated raw floats directly, bypassing the count-declared
     * {@link #beginWrite}/{@link #endRecord}/{@link #endWrite} session entirely.
     *
     * <p>For callers that accumulate records into their own standalone
     * {@link CgBufferWriter}/{@link CgStagingBuffer} pair (built with this buffer's
     * {@link #getFormat()}) across a window whose final record count isn't known
     * until upload time — a real {@link #beginWrite(int)} session can't declare a
     * count it doesn't have yet. Mirrors {@link CgUniformBuffer#upload()}'s existing
     * no-session upload pattern for the SSBO/TBO case.</p>
     *
     * @param data       backing float array of the caller's own accumulation buffer
     * @param floatCount number of valid floats in {@code data} (must be a whole
     *                   multiple of {@link CgBufferFormat#getFloatCount()})
     * @throws IllegalStateException if a {@link #beginWrite(int)} session is currently open
     */
    public void uploadRaw(float[] data, int floatCount) {
        if (inWrite) throw new IllegalStateException("Cannot uploadRaw() during an open beginWrite() session");
        uploadData(data, floatCount);
        lastWrittenCount = floatCount / format.getFloatCount();
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
     * Whether this buffer is frame-local on a ring tier: each upload lands at a new offset in this frame's
     * region, so a frame that reads it must upload it first. False on orphaning storage, which keeps its
     * bytes until the next upload -- and on a frame-local buffer whose tier was forced below the rings.
     *
     * <pre>{@code
     * if (block.isOnFrameRing() && uploadedFrame != CgFrameRing.frame()) block.upload();   // carry it forward
     * block.bind();
     * }</pre>
     */
    public boolean isOnFrameRing() {
        return dataBuffer.offsetMovesPerUpload();
    }

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
     * Uploads {@code floatCount} floats from {@code data} to the GPU via the stream buffer -- the one door every
     * upload of every shader buffer passes: {@link #endWrite()}, {@link #uploadRaw} and
     * {@link CgUniformBuffer#upload()}.
     *
     * <ul>
     *   <li><b>Unchanged bytes are not sent again.</b> An upload of at most {@link #COMPARE_LIMIT_FLOATS} equal to
     *       the last one is skipped -- the GPU already has it. A {@link CgBufferLifetime#FRAME} buffer is still
     *       sent on its first upload of a frame, since its last copy sits in a region the ring reuses three frames
     *       on. Larger uploads are streams, which change on nearly every upload and would pay a compare as long as
     *       the copy it saves, so they are always sent.</li>
     *   <li><b>A frame-local buffer is re-bound here</b>, skipped or not: a binding made before it -- as
     *       {@code CgRenderPipeline.prepareFrame()} binds the object buffer before a preview writes it -- names
     *       the previous upload's bytes. Re-binding keeps the write-then-draw idiom correct with no bind at the
     *       caller.</li>
     * </ul>
     */
    protected final void uploadData(float[] data, int floatCount) {
        boolean ring = dataBuffer.offsetMovesPerUpload();
        long frame = CgFrameRing.frame();
        boolean small = floatCount <= COMPARE_LIMIT_FLOATS;
        if (small && sameAsUploaded(data, floatCount) && (!ring || uploadedFrame == frame)) {
            CgTrace.add(CgChannels.GL, UPLOAD_SKIPPED, 1);
            if (ring) bindInternal();
            return;
        }
        CgTrace.add(CgChannels.GL, UPLOAD_SENT, 1);
        dataBuffer.uploadFloats(data, floatCount);
        uploadedFrame = frame;
        if (small) {
            if (uploaded.length < floatCount) uploaded = new float[floatCount];
            System.arraycopy(data, 0, uploaded, 0, floatCount);
            uploadedCount = floatCount;
        } else {
            uploadedCount = -1;   // a later small upload compares against nothing stale
        }
        if (ring) bindInternal();
    }

    /**
     * Uploads up to this many floats (4 KB) are compared with the last one: every uniform block, and a small SSBO.
     * A compare costs about what the copy it saves does, so it only pays where uploads repeat -- blocks do, instance
     * streams almost never.
     */
    public static final int COMPARE_LIMIT_FLOATS = 1024;

    private static final int UPLOAD_SENT = CgTrace.name("shaderBuffer.upload");
    private static final int UPLOAD_SKIPPED = CgTrace.name("shaderBuffer.uploadSkipped");

    /** The last small upload and the frame of the last upload: what {@link #uploadData} compares against. */
    private float[] uploaded = new float[0];
    private int uploadedCount = -1;
    private long uploadedFrame = -1;

    // Bit for bit: -0 against 0, or a NaN payload, is a change a shader could see.
    private boolean sameAsUploaded(float[] data, int count) {
        if (count != uploadedCount) return false;
        for (int i = 0; i < count; i++) {
            if (Float.floatToRawIntBits(data[i]) != Float.floatToRawIntBits(uploaded[i])) return false;
        }
        return true;
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
     * {@link #bind()}, and for a {@link CgBufferLifetime#FRAME} buffer by every upload.</p>
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
