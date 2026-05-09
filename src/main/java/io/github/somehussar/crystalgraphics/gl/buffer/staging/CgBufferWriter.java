package io.github.somehussar.crystalgraphics.gl.buffer.staging;

import io.github.somehussar.crystalgraphics.api.buffer.CgBufferField;
import io.github.somehussar.crystalgraphics.api.buffer.CgBufferFormat;
import io.github.somehussar.crystalgraphics.api.buffer.CgGpuType;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.Objects;

/**
 * Format-aware staged float writer backed by a {@link CgStagingBuffer}.
 *
 * <p>Companion to {@link CgInstanceWriter} for non-vertex-format buffer payloads
 * (UBOs, SSBOs, TBOs). Requires a {@link CgBufferFormat} — all shader buffers
 * carry a typed format. {@link #beginRecord()}/{@link #endRecord()} bracket each
 * logical record.</p>
 *
 * <h3>Format-aware mode — named writes</h3>
 * <pre>{@code
 * writer.beginRecord()
 *       .mat4("modelMatrix", model)
 *       .mat4("normalMatrix", normal);
 * // custom0-3 auto-zeroed from reserveAndZero
 * buffer.endRecord();
 * }</pre>
 * {@link #beginRecord()} calls {@link CgStagingBuffer#reserveAndZero(int)} to pre-zero
 * the full record. Named writes scatter values at specific offsets via
 * {@link CgStagingBuffer#setFloatAt(int, float)}.
 *
 * <p><strong>Thread safety:</strong> none. All calls must be on the render thread.</p>
 *
 * @see CgInstanceWriter
 * @see CgStagingBuffer
 */
public final class CgBufferWriter {

    private final CgStagingBuffer staging;

    /** Format descriptor. Always non-null — all shader buffers must carry a typed format. */
    private final CgBufferFormat format;

    /**
     * Start index within the staging array for the current record.
     * Set by {@link #beginRecord()} via {@link CgStagingBuffer#reserveAndZero(int)}.
     */
    private int recordStartIdx;

    /**
     * @param staging backing staging buffer
     * @param format  the buffer format driving named writes; must not be {@code null}
     */
    public CgBufferWriter(CgStagingBuffer staging, CgBufferFormat format) {
        Objects.requireNonNull(format, "CgBufferFormat is required — CgBufferWriter is format-aware only");
        this.staging = staging;
        this.format = format;
    }

    // ── Record bracketing ─────────────────────────────────────────────────────

    /**
     * Opens a new record: calls {@link CgStagingBuffer#reserveAndZero(int)} to pre-zero
     * the full record and records {@link #recordStartIdx} for named writes.
     *
     * @return {@code this} for chaining
     */
    public CgBufferWriter beginRecord() {
        recordStartIdx = staging.reserveAndZero(format.getFloatCount());
        return this;
    }

    /**
     * Closes the current record. In format-aware mode the record was pre-zeroed by
     * {@link #beginRecord()} — no float-count validation is needed.
     *
     * @apiNote Internal — prefer {@link io.github.somehussar.crystalgraphics.gl.buffer.shader.CgShaderBuffer#endRecord()}
     *          which also increments the write head. Call this directly only from {@code CgShaderBuffer}.
     */
    public void endRecord() {
        // Record was pre-zeroed by beginRecord(); all slots are accounted for.
    }

    // ── Named writes (format-aware mode) ──────────────────────────────────────

    /**
     * Named write — writes a {@code mat4} to the field with the given name.
     * Looks up the field offset in the attached format and writes 16 floats via
     * absolute-index writes into the pre-reserved record.
     *
     * @param fieldName name of the MAT4 field in the attached format
     * @param m         the matrix to write (column-major)
     * @return {@code this} for chaining
     * @throws IllegalStateException if the field does not exist or is not of type MAT4
     */
    public CgBufferWriter mat4(String fieldName, Matrix4f m) {
        int base = resolveField(fieldName, CgGpuType.MAT4);
        staging.setFloatAt(base + 0, m.m00());
        staging.setFloatAt(base + 1, m.m01());
        staging.setFloatAt(base + 2, m.m02());
        staging.setFloatAt(base + 3, m.m03());
        staging.setFloatAt(base + 4, m.m10());
        staging.setFloatAt(base + 5, m.m11());
        staging.setFloatAt(base + 6, m.m12());
        staging.setFloatAt(base + 7, m.m13());
        staging.setFloatAt(base + 8, m.m20());
        staging.setFloatAt(base + 9, m.m21());
        staging.setFloatAt(base + 10, m.m22());
        staging.setFloatAt(base + 11, m.m23());
        staging.setFloatAt(base + 12, m.m30());
        staging.setFloatAt(base + 13, m.m31());
        staging.setFloatAt(base + 14, m.m32());
        staging.setFloatAt(base + 15, m.m33());
        return this;
    }

    /**
     * Named write — writes a {@code mat3} (48 bytes, 3 × vec4-aligned columns) to the
     * field with the given name. Each column is padded with a {@code w=0} float.
     *
     * @param fieldName name of the MAT3 field in the attached format
     * @param m         the matrix to write (column-major)
     * @return {@code this} for chaining
     * @throws IllegalStateException if the field does not exist or is not of type MAT3
     */
    public CgBufferWriter mat3(String fieldName, Matrix3f m) {
        int base = resolveField(fieldName, CgGpuType.MAT3);
        staging.setFloatAt(base + 0, m.m00());
        staging.setFloatAt(base + 1, m.m01());
        staging.setFloatAt(base + 2, m.m02());
        staging.setFloatAt(base + 3, 0f);
        staging.setFloatAt(base + 4, m.m10());
        staging.setFloatAt(base + 5, m.m11());
        staging.setFloatAt(base + 6, m.m12());
        staging.setFloatAt(base + 7, 0f);
        staging.setFloatAt(base + 8, m.m20());
        staging.setFloatAt(base + 9, m.m21());
        staging.setFloatAt(base + 10, m.m22());
        staging.setFloatAt(base + 11, 0f);
        return this;
    }

    /**
     * Named write — writes a {@code vec4} to the field with the given name.
     *
     * @param fieldName name of the VEC4 field
     * @return {@code this} for chaining
     */
    public CgBufferWriter vec4(String fieldName, float x, float y, float z, float w) {
        int base = resolveField(fieldName, CgGpuType.VEC4);
        staging.setFloatAt(base + 0, x);
        staging.setFloatAt(base + 1, y);
        staging.setFloatAt(base + 2, z);
        staging.setFloatAt(base + 3, w);
        return this;
    }

    /**
     * Named write — writes a {@code vec4} from a {@link Vector4f} to the field with the given name.
     *
     * @param fieldName name of the VEC4 field
     * @return {@code this} for chaining
     */
    public CgBufferWriter vec4(String fieldName, Vector4f v) {
        return vec4(fieldName, v.x, v.y, v.z, v.w);
    }

    /**
     * Named write — writes a {@code vec3} (3 floats + 1 padding zero for 16-byte alignment)
     * to the field with the given name.
     *
     * @param fieldName name of the VEC3 field
     * @return {@code this} for chaining
     */
    public CgBufferWriter vec3(String fieldName, float x, float y, float z) {
        int base = resolveField(fieldName, CgGpuType.VEC3);
        staging.setFloatAt(base + 0, x);
        staging.setFloatAt(base + 1, y);
        staging.setFloatAt(base + 2, z);
        // padding float at base+3 is already zero from reserveAndZero
        return this;
    }

    /**
     * Named write — writes a {@code vec2} to the field with the given name.
     *
     * @param fieldName name of the VEC2 field
     * @return {@code this} for chaining
     */
    public CgBufferWriter vec2(String fieldName, float x, float y) {
        int base = resolveField(fieldName, CgGpuType.VEC2);
        staging.setFloatAt(base + 0, x);
        staging.setFloatAt(base + 1, y);
        return this;
    }

    /**
     * Named write — writes a single {@code float} to the field with the given name.
     * Method name uses trailing underscore to avoid conflict with the Java keyword {@code float}.
     *
     * @param fieldName name of the FLOAT field
     * @return {@code this} for chaining
     */
    public CgBufferWriter float_(String fieldName, float v) {
        int base = resolveField(fieldName, CgGpuType.FLOAT);
        staging.setFloatAt(base, v);
        return this;
    }

    /**
     * Named write — writes an {@code int} field (32-bit signed integer).
     * Stores the raw int bits via {@link Float#intBitsToFloat(int)}.
     *
     * @param fieldName name of the INT field in the attached format
     * @param v         the integer value
     * @return {@code this} for chaining
     * @throws IllegalStateException if the field does not exist or is not of type INT
     */
    public CgBufferWriter int_(String fieldName, int v) {
        int base = resolveField(fieldName, CgGpuType.INT);
        staging.setIntBitsAt(base, v);
        return this;
    }

    /**
     * Named write — writes a {@code uint} field (32-bit unsigned integer).
     * Pass the unsigned value as a signed Java {@code int} (raw bits are preserved).
     *
     * @param fieldName name of the UINT field
     * @return {@code this} for chaining
     */
    public CgBufferWriter uint(String fieldName, int v) {
        int base = resolveField(fieldName, CgGpuType.UINT);
        staging.setIntBitsAt(base, v);
        return this;
    }

    /**
     * Named write — writes a {@code bool} field (GPU bool = 1 int slot, 0 or 1).
     *
     * @param fieldName name of the BOOL field
     * @return {@code this} for chaining
     */
    public CgBufferWriter bool_(String fieldName, boolean v) {
        int base = resolveField(fieldName, CgGpuType.BOOL);
        staging.setIntBitsAt(base, v ? 1 : 0);
        return this;
    }

    /**
     * Named write — writes an {@code ivec2} field (2-component signed integer vector).
     *
     * @param fieldName name of the IVEC2 field
     * @return {@code this} for chaining
     */
    public CgBufferWriter ivec2(String fieldName, int x, int y) {
        int base = resolveField(fieldName, CgGpuType.IVEC2);
        staging.setIntBitsAt(base,     x);
        staging.setIntBitsAt(base + 1, y);
        return this;
    }

    /**
     * Named write — writes an {@code ivec3} field (3-component signed integer vector).
     * The 4th slot (padding to 16-byte alignment) is pre-zeroed by {@link #beginRecord()}.
     *
     * @param fieldName name of the IVEC3 field
     * @return {@code this} for chaining
     */
    public CgBufferWriter ivec3(String fieldName, int x, int y, int z) {
        int base = resolveField(fieldName, CgGpuType.IVEC3);
        staging.setIntBitsAt(base,     x);
        staging.setIntBitsAt(base + 1, y);
        staging.setIntBitsAt(base + 2, z);
        // slot base+3 is the 16-byte alignment pad — already zero from reserveAndZero
        return this;
    }

    /**
     * Named write — writes an {@code ivec4} field (4-component signed integer vector).
     *
     * @param fieldName name of the IVEC4 field
     * @return {@code this} for chaining
     */
    public CgBufferWriter ivec4(String fieldName, int x, int y, int z, int w) {
        int base = resolveField(fieldName, CgGpuType.IVEC4);
        staging.setIntBitsAt(base,     x);
        staging.setIntBitsAt(base + 1, y);
        staging.setIntBitsAt(base + 2, z);
        staging.setIntBitsAt(base + 3, w);
        return this;
    }

    /**
     * Named write — writes a {@code uvec2} field (2-component unsigned integer vector).
     * Pass unsigned values as signed Java {@code int}s (raw bits are preserved).
     * Also used for bindless texture handle storage ({@code ARB_bindless_texture}).
     *
     * @param fieldName name of the UVEC2 field
     * @return {@code this} for chaining
     */
    public CgBufferWriter uvec2(String fieldName, int x, int y) {
        int base = resolveField(fieldName, CgGpuType.UVEC2);
        staging.setIntBitsAt(base,     x);
        staging.setIntBitsAt(base + 1, y);
        return this;
    }

    /**
     * Named write — writes a {@code uvec3} field (3-component unsigned integer vector).
     * The 4th slot (padding to 16-byte alignment) is pre-zeroed.
     *
     * @param fieldName name of the UVEC3 field
     * @return {@code this} for chaining
     */
    public CgBufferWriter uvec3(String fieldName, int x, int y, int z) {
        int base = resolveField(fieldName, CgGpuType.UVEC3);
        staging.setIntBitsAt(base,     x);
        staging.setIntBitsAt(base + 1, y);
        staging.setIntBitsAt(base + 2, z);
        return this;
    }

    /**
     * Named write — writes a {@code uvec4} field (4-component unsigned integer vector).
     *
     * @param fieldName name of the UVEC4 field
     * @return {@code this} for chaining
     */
    public CgBufferWriter uvec4(String fieldName, int x, int y, int z, int w) {
        int base = resolveField(fieldName, CgGpuType.UVEC4);
        staging.setIntBitsAt(base,     x);
        staging.setIntBitsAt(base + 1, y);
        staging.setIntBitsAt(base + 2, z);
        staging.setIntBitsAt(base + 3, w);
        return this;
    }

    /**
     * Named write — writes a {@code uint64_t} field (64-bit unsigned integer).
     * Stored as two consecutive 32-bit float slots: low word at {@code base}, high word at {@code base+1}.
     * Requires {@code ARB_gpu_shader_int64} or {@code ARB_bindless_texture} in the shader.
     *
     * @param fieldName name of the UINT64 field
     * @param v         the 64-bit value (interpreted as unsigned)
     * @return {@code this} for chaining
     */
    public CgBufferWriter uint64(String fieldName, long v) {
        int base = resolveField(fieldName, CgGpuType.UINT64);
        staging.setIntBitsAt(base,     (int) v);
        staging.setIntBitsAt(base + 1, (int)(v >>> 32));
        return this;
    }

    /**
     * Named write — writes an {@code int64_t} field (64-bit signed integer).
     * Stored as two consecutive 32-bit float slots: low word at {@code base}, high word at {@code base+1}.
     * Requires {@code ARB_gpu_shader_int64} in the shader.
     *
     * @param fieldName name of the INT64 field
     * @param v         the 64-bit signed value
     * @return {@code this} for chaining
     */
    public CgBufferWriter int64(String fieldName, long v) {
        int base = resolveField(fieldName, CgGpuType.INT64);
        staging.setIntBitsAt(base,     (int) v);
        staging.setIntBitsAt(base + 1, (int)(v >> 32));
        return this;
    }

    // ── Staging access ────────────────────────────────────────────────────────

    /**
     * Resets the write cursor to 0.
     * Call before writing a new frame's data to reuse the same staging allocation.
     *
     * @return {@code this} for chaining (required by {@code beginFrame()} chain)
     */
    public CgBufferWriter reset() {
        staging.reset();
        return this;
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

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Resolves a named field, validates its type, and returns the absolute float index
     * ({@code recordStartIdx + field.floatOffset}) for scatter writes via
     * {@link CgStagingBuffer#setFloatAt(int, float)}.
     */
    private int resolveField(String fieldName, CgGpuType expectedType) {
        CgBufferField field = format.getField(fieldName); // throws if not found
        if (field.getType() != expectedType) {
            throw new IllegalStateException(
                    "Type mismatch for field '" + fieldName + "' in format '"
                            + format.getGlslName() + "': expected " + expectedType
                            + " but field is " + field.getType() + ".");
        }
        return recordStartIdx + field.getFloatOffset();
    }
}
