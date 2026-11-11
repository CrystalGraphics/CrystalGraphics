package com.crystalgraphics.gpu.resource;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.CgBindingPoints;
import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.api.buffer.CgBufferFormat;
import com.crystalgraphics.api.buffer.CgBufferLifetime;
import com.crystalgraphics.api.buffer.CgObjectBuffer;
import com.crystalgraphics.gpu.staging.CgBufferWriter;
import com.crystalgraphics.gpu.staging.CgStagingBuffer;
import com.crystalgraphics.gpu.CgDeferral;
import com.crystalgraphics.gpu.CgUploadLease;
import com.crystalgraphics.gpu.CgUploads;
import com.crystalgraphics.platform.rhi.resource.CgGpuBuffer;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;
import lombok.Getter;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Abstract base class for all GPU shader buffer types (SSBO, UBO).
 *
 * <p>Owns the shared infrastructure that every concrete backend needs:</p>
 * <ul>
 *   <li>A {@link CgStreamBuffer} ({@code dataBuffer}) created via
 *       {@link CgStreamBuffer#createForShaderBuffer}, which copies each upload in at offset 0 -- what
 *       {@code glBindBufferBase} reads, and what keeps a buffer written once readable for as many frames as it
 *       is bound. A {@link CgBufferLifetime#FRAME}
 *       buffer is uploaded in every frame that reads it instead, and sits on the frame ring at a new offset
 *       per upload; its binding follows each upload ({@link #uploadData}).</li>
 *   <li>A {@link CgBufferWriter} backed by a {@link CgStagingBuffer} — either record-mode
 *       (SSBO, fixed stride per record) or flat-mode (UBO, arbitrary float sequence).</li>
 *   <li>A write-session API ({@link #beginWrite}/{@link #endRecord}/{@link #endWrite})
 *       that validates object count and drives GPU upload via {@link #endWrite()}.</li>
 *   <li>A {@link #delete()} that releases the storage, destroyed once the frames reading it retire.</li>
 * </ul>
 *
 * <h3>Concrete subclasses</h3>
 * <ul>
 *   <li>{@link CgShaderStorageBuffer} — SSBO (GL 4.3 core or {@code ARB_shader_storage_buffer_object})</li>
 *   <li>{@link CgUniformBuffer} — UBO (flat-mode child)</li>
 * </ul>
 *
 * <h3>Threads</h3>
 * <p>A {@link CgBufferLifetime#RETAINED} buffer is made, written, uploaded and deleted from any thread, as a texture is:
 * off the render thread its GL work waits for the render thread, before the next frame executes, and an upload's floats
 * are copied into a lease by the thread that wrote them. A {@link CgBufferLifetime#FRAME} buffer is the render
 * thread's: it is uploaded in the frame that reads it.</p>
 *
 * <pre>{@code
 * // a lookup table built on a worker
 * CgShaderBuffer table = CgShaderBuffer.create("Heights", HEIGHT_FORMAT, 0);   // RETAINED
 * table.beginWrite(cells);
 * for (int i = 0; i < cells; i++) {
 *     table.writer().beginRecord().vec4("height", h[i], 0f, 0f, 0f);
 *     table.endRecord();
 * }
 * table.endWrite();   // lands before the next frame; draws bind it as they would any buffer
 * }</pre>
 *
 * <h3>Factory</h3>
 * <p>{@link #create(String, CgBufferFormat, int)} makes an SSBO, through core GL 4.3 or the ARB extension
 * ({@link CgCapabilities#shaderBufferPath()}); the TBO path is unhooked ({@code docs/TBO_PATH.md}). The {@code userIndex}
 * is 0-based; {@link CgBindingPoints} is added internally. Use
 * {@link #createInternal(String, CgBufferFormat, int)} for engine-reserved binding points.</p>
 *
 * <h3>Shader binding</h3>
 * <p>The material compiler declares its block, named {@link #getName()}, at {@link #bindingLocation}'s binding: a
 * device pass's sets read it there.</p>
 *
 * <h3>SSBO write lifecycle</h3>
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
 * buffer.bind();         // what a device pass's sets read: noted, never a GL bind
 * }</pre>
 */
public abstract class CgShaderBuffer implements CgObjectBuffer {

    /** Binding point used for glBindBufferBase. Immutable after construction. */
    @Getter
    protected final int bindingLocation;

    /**
     * Debug/sampler/block name for this buffer.
     * <ul>
     *   <li>SSBO — debug label, appears in error messages and registry keys.</li>
     *   <li>UBO — the GLSL block name it is declared as.</li>
     * </ul>
     */
    @Getter
    private final String name;

    protected final CgBufferWriter writer;
    /** The storage: made at once for a {@link CgBufferLifetime#FRAME} buffer, through {@link #gpu} otherwise. */
    protected CgStreamBuffer dataBuffer;
    /** This buffer's device work, in order: what lets a {@link CgBufferLifetime#RETAINED} one be used from any thread. */
    protected final CgDeferral gpu = new CgDeferral();
    private final Consumer<CgUploadLease> landing = this::land;

    /** Format descriptor. Required — all shader buffers must have a typed format. */
    @Getter
    private CgBufferFormat format;

    /**
     * How long an upload stays readable, as asked at creation. {@link #isOnFrameRing()} is what the storage
     * became: a target that binds no range falls back from {@link CgBufferLifetime#FRAME}.
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
     * Unified constructor for all SSBO/UBO backends.
     * Initial capacity is one record (auto-grows on {@link #beginWrite(int)}).
     * {@link #lastWrittenCount} starts at {@code 0} — valid for both the single-block
     * UBO write cycle and for SSBO batches (reset to {@code -1} by {@link #beginWrite}).
     *
     * @param name            debug/sampler/block name (must be non-null)
     * @param format          typed format descriptor (mandatory)
     * @param use             what the storage is read as
     * @param bindingLocation binding point; immutable after construction
     */
    protected CgShaderBuffer(String name, CgBufferFormat format, CgStreamBuffer.Use use, int bindingLocation) {
        this(name, format, use, bindingLocation, CgBufferLifetime.RETAINED);
    }

    /** @param lifetime {@link CgBufferLifetime#FRAME} takes the frame ring ({@link CgStreamBuffer#createFrameLocal}) */
    protected CgShaderBuffer(String name, CgBufferFormat format, CgStreamBuffer.Use use, int bindingLocation,
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
        this.lastWrittenCount = 0;
        if (lifetime == CgBufferLifetime.FRAME) {
            this.dataBuffer = CgStreamBuffer.createFrameLocal(use, capacityBytes);
        } else {
            gpu.run(() -> dataBuffer = CgStreamBuffer.createForShaderBuffer(use, capacityBytes));
        }
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    /**
     * Creates the best available SSBO shader buffer driven by the given format descriptor.
     * The buffer starts at capacity 1 and auto-grows on {@link #beginWrite(int)}.
     *
     * <p>The {@code userIndex} is 0-based: binding {@link CgBindingPoints#USER_START_SSBO} plus it, so user code is
     * free of magic offset arithmetic.</p>
     *
     * @param name      debug/sampler name (must be non-null)
     * @param format    typed buffer format descriptor
     * @param userIndex 0-based user slot index (0 = first user slot after engine range)
     * @return a {@link CgShaderStorageBuffer}
     * @throws UnsupportedOperationException where the context reads no storage blocks
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
        return createInternal(name, format, CgBindingPoints.USER_START_SSBO + userIndex, lifetime);
    }

    /**
     * Creates an SSBO for engine-internal use. Accepts raw binding points (may be engine-reserved 0–4). No USER_START
     * offset is added.
     *
     * <p><strong>Engine-internal. Do not use from user code.</strong></p>
     *
     * @param name            debug/sampler name (must be non-null)
     * @param format          typed buffer format
     * @param bindingPoint    binding slot (may be engine-reserved)
     * @return a {@link CgShaderStorageBuffer}
     * @throws UnsupportedOperationException where the context reads no storage blocks
     */
    public static CgShaderBuffer createInternal(String name, CgBufferFormat format, int bindingPoint) {
        return createInternal(name, format, bindingPoint, CgBufferLifetime.RETAINED);
    }

    /**
     * As {@link #createInternal(String, CgBufferFormat, int)}, with the contents' {@link CgBufferLifetime}.
     */
    public static CgShaderBuffer createInternal(String name, CgBufferFormat format, int bindingPoint,
                                                CgBufferLifetime lifetime) {
        CgCapabilities.ShaderBufferPath path = CgCapabilities.detect().shaderBufferPath();
        if (path != CgCapabilities.ShaderBufferPath.SSBO_GL43 && path != CgCapabilities.ShaderBufferPath.SSBO_ARB) {
            // The TBO path is unhooked: docs/TBO_PATH.md
            throw new UnsupportedOperationException("shader buffers need storage blocks (GL 4.3 or ARB_shader_storage_buffer_object); "
                    + "this context's path is " + path);
        }
        return new CgShaderStorageBuffer(name, format, path, bindingPoint, lifetime);
    }

    /**
     * Creates an SSBO for engine-internal use at {@code binding}'s storage point ({@link CgBindingPoints.Binding#resolve()}).
     *
     * <p><strong>Engine-internal. Do not use from user code.</strong></p>
     *
     * @param name    debug/sampler name (must be non-null)
     * @param format  typed buffer format
     * @param binding the reserved {@link CgBindingPoints.Binding} to bind at
     * @return a {@link CgShaderStorageBuffer}
     * @throws UnsupportedOperationException where the context reads no storage blocks
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
     *   <li><strong>SSBO (in write session)</strong>: validates the record count against
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
     * no-session upload pattern for the SSBO case.</p>
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
     * Notes this buffer at its binding point ({@code CgBoundResources}), what a device pass's sets read: its latest
     * upload's range on the frame ring, else the whole storage. Lands what other threads uploaded first.
     *
     * @throws IllegalStateException if this buffer has been deleted, or is not made yet
     */
    public void bind() {
        if (deleted) throw new IllegalStateException("CgShaderBuffer has been deleted");
        gpu.flush();
        if (dataBuffer == null) throw new IllegalStateException(name + " is not made yet: bind it on the render thread, outside a recording");
        noteInternal();
    }

    /** {@link #bind()} inside a pass, with nothing to land: a no-op before the storage is made. */
    public void noteBound() {
        if (dataBuffer != null) noteInternal();
    }

    /** Notes the range {@link #bind()} names. */
    protected abstract void noteInternal();

    @Override public boolean isDeleted() { return deleted; }

    /**
     * Whether this buffer is frame-local on a ring tier: each upload lands at a new offset in this frame's
     * region, so a frame that reads it must upload it first. False on retained storage, which keeps its
     * bytes until the next upload -- and on a frame-local buffer whose tier was forced below the rings.
     *
     * <pre>{@code
     * if (block.isOnFrameRing() && uploadedFrame != CgFrameRing.frame()) block.upload();   // carry it forward
     * block.bind();
     * }</pre>
     */
    public boolean isOnFrameRing() {
        return dataBuffer != null && dataBuffer.offsetMovesPerUpload();
    }

    @Override
    public CgGpuBuffer gpuBuffer() {
        gpu.flush();
        return dataBuffer == null ? null : dataBuffer.gpuBuffer();
    }

    @Override
    public int getGlBufferId() {
        gpu.flush();
        return dataBuffer == null ? 0 : dataBuffer.getGlBuffer();
    }

    /** Releases the storage, destroyed once the frames reading it retire. Idempotent. */
    @Override
    public void delete() {
        if (deleted) return;
        deleted = true;
        gpu.clear();
        gpu.run(() -> {
            if (dataBuffer != null) dataBuffer.delete();
            dataBuffer = null;
        });
    }

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
     *       one made by a caller before writing the buffer -- names
     *       the previous upload's bytes. Re-binding keeps the write-then-draw idiom correct with no bind at the
     *       caller.</li>
     * </ul>
     */
    protected final void uploadData(float[] data, int floatCount) {
        if (lifetime != CgBufferLifetime.FRAME && !gpu.immediate()) {
            gpu.flush();
            if (!gpu.immediate()) {
                uploadLater(data, floatCount);
                return;
            }
        }
        boolean ring = dataBuffer.offsetMovesPerUpload();
        long frame = CgFrameRing.frame();
        boolean small = floatCount <= COMPARE_LIMIT_FLOATS;
        if (small && sameAsUploaded(data, floatCount) && (!ring || uploadedFrame == frame)) {
            CgTrace.add(CgChannels.GL, UPLOAD_SKIPPED, 1);
            if (ring) noteInternal();
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
        if (ring) noteInternal();
    }

    /** Off the render thread: the floats copied into a lease here, landed before the next frame executes. */
    private void uploadLater(float[] data, int floatCount) {
        if (floatCount == 0) return;
        CgUploadLease lease = CgUploads.lease(floatCount * Float.BYTES).put(data, 0, floatCount);
        gpu.run(lease.into(landing, 0, 0, 0, 0, floatCount, 1, 1, 0, 0));
    }

    private void land(CgUploadLease lease) {
        dataBuffer.uploadFrom(lease);
        uploadedCount = -1;
        uploadedFrame = CgFrameRing.frame();
        CgTrace.add(CgChannels.GL, UPLOAD_SENT, 1);
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
}
