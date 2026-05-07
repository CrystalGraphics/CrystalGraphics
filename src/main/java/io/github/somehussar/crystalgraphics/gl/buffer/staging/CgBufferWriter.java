package io.github.somehussar.crystalgraphics.gl.buffer.staging;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * General-purpose staged float writer backed by a {@link CgStagingBuffer}.
 *
 * <p>Companion to {@link CgInstanceWriter} for non-vertex-format buffer payloads
 * (UBOs, SSBOs, TBOs). The write API mirrors {@link CgInstanceWriter} exactly —
 * scalar, vector, and matrix helpers write floats into a shared staging array.
 * {@link #beginRecord()}/{@link #endRecord(int)} bracket each logical record and
 * enforce an exact float count per record in DEBUG mode.</p>
 *
 * <p>Two usage modes depending on {@code floatPerRecord} passed at construction:</p>
 * <dl>
 *   <dt>Record mode ({@code floatPerRecord > 0}) — SSBO/TBO object data</dt>
 *   <dd>
 *   <pre>{@code
 *   writer.beginRecord();
 *   writer.mat4(model).mat4(normal).vec4Zero().vec4Zero().vec4Zero().vec4Zero();
 *   writer.endRecord();
 *   }</pre>
 *   {@link #endRecord()} validates the exact float count per record — catches ABI drift between
 *   the Java write sequence and the GLSL block layout — then calls
 *   {@link CgStagingBuffer#ensureRoomForStride(int)} to pre-allocate the next slot.</dd>
 *
 *   <dt>Flat mode ({@code floatPerRecord == 0}) — UBO frame data</dt>
 *   <dd>
 *   <pre>{@code
 *   writer.reset();
 *   writer.mat4(view).mat4(proj);
 *   uniformBuffer.upload();
 *   }</pre>
 *   {@link #beginRecord()}/{@link #endRecord()} are never called; {@link CgStagingBuffer#putFloat}
 *   auto-grows the backing array on overflow.</dd>
 * </dl>
 *
 * <p><strong>Thread safety:</strong> none. All calls must be on the render thread.</p>
 *
 * @see CgInstanceWriter
 * @see CgStagingBuffer
 */
public final class CgBufferWriter {

    private final CgStagingBuffer staging;

    /**
     * The record stride passed at construction. Used by {@link #endRecord(int)} to call
     * {@link CgStagingBuffer#ensureRoomForStride(int)}.
     * Zero in flat/UBO mode — {@link #endRecord(int)} skips the ensure call.
     */
    private final int floatPerRecord;

    /** Cursor snapshot taken by {@link #beginRecord()} to verify the exact float count in {@link #endRecord(int)}. */
    private int recordStartCursor;

    /**
     * @param staging         backing staging buffer
     * @param floatPerRecord record stride; pass {@code 0} for flat/UBO writers that never
     *                        call {@link #beginRecord()}/{@link #endRecord(int)}
     */
    public CgBufferWriter(CgStagingBuffer staging, int floatPerRecord) {
        this.staging         = staging;
        this.floatPerRecord = floatPerRecord;
    }

    // ── Scalar ────────────────────────────────────────────────────────────────

    /** Writes a single {@code float} into the staging buffer. Returns {@code this} for chaining. */
    public CgBufferWriter putFloat(float v) {
        staging.putFloat(v);
        return this;
    }

    /**
     * Writes a raw {@code int} value as its IEEE-754 bit-equivalent float slot via
     * {@link Float#intBitsToFloat(int)}.
     * Use for {@code int} or {@code uint} fields in std140/std430 blocks where the bits
     * must be preserved exactly and the caller is responsible for correct GLSL type alignment.
     */
    public CgBufferWriter putInt(int v) {
        staging.putColorPacked(v);
        return this;
    }

    // ── Vector ────────────────────────────────────────────────────────────────

    /** Writes 2 floats as a {@code vec2} (x, y). */
    public CgBufferWriter vec2(float x, float y) {
        staging.putFloat(x);
        staging.putFloat(y);
        return this;
    }

    /** Writes 3 floats as a {@code vec3} (x, y, z). */
    public CgBufferWriter vec3(float x, float y, float z) {
        staging.putFloat(x);
        staging.putFloat(y);
        staging.putFloat(z);
        return this;
    }

    /** Writes a {@link Vector3f} as a {@code vec3}. */
    public CgBufferWriter vec3(Vector3f v) {
        return vec3(v.x, v.y, v.z);
    }

    /** Writes 4 floats as a {@code vec4} (x, y, z, w). */
    public CgBufferWriter vec4(float x, float y, float z, float w) {
        staging.putFloat(x);
        staging.putFloat(y);
        staging.putFloat(z);
        staging.putFloat(w);
        return this;
    }

    /** Writes a {@link Vector4f} as a {@code vec4}. */
    public CgBufferWriter vec4(Vector4f v) {
        return vec4(v.x, v.y, v.z, v.w);
    }

    /** Writes {@code vec4(0, 0, 0, 0)} — 4 zero floats. Useful as a padding/placeholder slot. */
    public CgBufferWriter vec4Zero() {
        staging.putFloat(0f);
        staging.putFloat(0f);
        staging.putFloat(0f);
        staging.putFloat(0f);
        return this;
    }

    // ── Matrix ────────────────────────────────────────────────────────────────

    /**
     * Writes a {@code mat3} as three tightly-packed column-major vec3s (9 floats / 36 bytes).
     *
     * <p>Layout: {@code col0:[m00,m01,m02]  col1:[m10,m11,m12]  col2:[m20,m21,m22]}</p>
     *
     * <p>Use this for {@code mat3} fields in std430 blocks. For std140 or TBO
     * per-object ABI use {@link #mat3Padded(Matrix3f)} instead.</p>
     */
    public CgBufferWriter mat3(Matrix3f m) {
        staging.putFloat(m.m00()); staging.putFloat(m.m01()); staging.putFloat(m.m02());
        staging.putFloat(m.m10()); staging.putFloat(m.m11()); staging.putFloat(m.m12());
        staging.putFloat(m.m20()); staging.putFloat(m.m21()); staging.putFloat(m.m22());
        return this;
    }

    /**
     * Writes a {@code mat3} with each column padded to a {@code vec4} (12 floats / 48 bytes).
     *
     * <p>Layout: {@code col0:[m00,m01,m02,0]  col1:[m10,m11,m12,0]  col2:[m20,m21,m22,0]}</p>
     *
     * <p>Use for custom packed data structures where vec4-column alignment is required,
     * or for std140 blocks that declare mat3 fields (std140 pads each column to vec4).
     * The {@code w=0} padding matches std140 column alignment rules.</p>
     */
    public CgBufferWriter mat3Padded(Matrix3f m) {
        staging.putFloat(m.m00()); staging.putFloat(m.m01()); staging.putFloat(m.m02()); staging.putFloat(0f);
        staging.putFloat(m.m10()); staging.putFloat(m.m11()); staging.putFloat(m.m12()); staging.putFloat(0f);
        staging.putFloat(m.m20()); staging.putFloat(m.m21()); staging.putFloat(m.m22()); staging.putFloat(0f);
        return this;
    }

    /**
     * Writes a {@code mat4} as 16 column-major floats (64 bytes).
     *
     * <p>Layout: 4 columns × 4 rows, column-major order as expected by GLSL {@code mat4}.</p>
     */
    public CgBufferWriter mat4(Matrix4f m) {
        staging.putFloat(m.m00()); staging.putFloat(m.m01()); staging.putFloat(m.m02()); staging.putFloat(m.m03());
        staging.putFloat(m.m10()); staging.putFloat(m.m11()); staging.putFloat(m.m12()); staging.putFloat(m.m13());
        staging.putFloat(m.m20()); staging.putFloat(m.m21()); staging.putFloat(m.m22()); staging.putFloat(m.m23());
        staging.putFloat(m.m30()); staging.putFloat(m.m31()); staging.putFloat(m.m32()); staging.putFloat(m.m33());
        return this;
    }

    // ── Record bracketing ─────────────────────────────────────────────────────

    public CgBufferWriter beginRecord() {
        recordStartCursor = staging.rawCursor();
        return this;
    }

    /**
     * Closes the current record, validating that the exact expected float count was written.
     *
     * <p>The expected count is the {@code floatPerRecord} value passed at construction.
     * If it does not match the floats written since {@link #beginRecord()}, an
     * {@link IllegalStateException} is thrown — this catches ABI drift between the Java
     * write sequence and the GLSL block layout.</p>
     *
     * <p>If {@code floatPerRecord > 0} (record mode), also calls
     * {@link CgStagingBuffer#ensureRoomForStride(int)} to pre-allocate the next slot.</p>
     *
     * @throws IllegalStateException if the actual float count differs from the record stride
     */
    public void endRecord() {
        if (floatPerRecord > 0) {
            int written = staging.rawCursor() - recordStartCursor;
            if (written != floatPerRecord) {
                throw new IllegalStateException(
                    "CgBufferWriter.endRecord(): wrote " + written
                        + " floats but expected " + floatPerRecord + " (record stride)");
            }
            staging.ensureRoomForStride(floatPerRecord);
        }
    }

    // ── Staging access ────────────────────────────────────────────────────────

    /**
     * Resets the write cursor to 0.
     * Call before writing a new frame's data to reuse the same staging allocation.
     */
    public void reset() {
        staging.reset();
    }

    /**
     * Returns the backing {@code float[]} of the staging buffer.
     * Valid until the next write that triggers a growth reallocation.
     */
    public float[] rawData() {
        return staging.rawData();
    }

    /** Returns the current write cursor — number of floats written since the last {@link #reset()}. */
    public int rawCursor() {
        return staging.rawCursor();
    }
}
