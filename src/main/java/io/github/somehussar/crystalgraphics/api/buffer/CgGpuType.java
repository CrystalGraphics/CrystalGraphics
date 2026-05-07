package io.github.somehussar.crystalgraphics.api.buffer;

import io.github.somehussar.crystalgraphics.api.vertex.CgAttribType;
import io.github.somehussar.crystalgraphics.gl.buffer.staging.CgBufferWriter;

/**
 * Enumerates the GLSL compound field types available for UBO and SSBO buffer fields.
 *
 * <p>Each constant carries the std140/std430 size and alignment data derived from the
 * OpenGL 4.5 specification §7.6.2.2. These values are spec-derived constants, not
 * implementation choices — they are the same on every conformant driver.</p>
 *
 * <p>This enum operates at the GLSL-compound-type level, distinct from
 * {@link CgAttribType} which operates
 * at the GL primitive type level (FLOAT, UNSIGNED_BYTE, etc.). Do not conflate them.</p>
 *
 * <p><strong>Note</strong>: this enum covers standalone field types only.
 * Array-of-scalars stride differs between std140 and std430 (std140 pads each element
 * to 16 bytes; std430 does not). Array fields are not supported in v1 — see
 * TODO v2 in {@link CgBufferFormat}.</p>
 */
public enum CgGpuType {

    /** Single {@code float} scalar. 4 bytes, 4-byte aligned. */
    FLOAT(1, 4, 4, 4, "float"),

    /** {@code vec2} — 2-component float vector. 8 bytes, 8-byte aligned. */
    VEC2(2, 8, 8, 8, "vec2"),

    /**
     * {@code vec3} — 3-component float vector. 12 bytes data, 16-byte aligned slot.
     *
     * <p><strong>Warning</strong>: Old Intel drivers have known bugs with vec3 in UBOs.
     * Prefer VEC4 with {@code w=0} for maximum compatibility on legacy hardware.</p>
     */
    VEC3(3, 12, 16, 16, "vec3"),

    /** {@code vec4} — 4-component float vector. 16 bytes, 16-byte aligned. */
    VEC4(4, 16, 16, 16, "vec4"),

    /**
     * {@code mat3} — 3×3 float matrix.
     *
     * <p><strong>mat3 occupies 48 bytes in BOTH std140 and std430</strong> (3 columns ×
     * 16-byte vec4-aligned slots). {@link CgBufferWriter#mat3(String, org.joml.Matrix3f)}
     * writes 48 bytes (12 float slots).</p>
     */
    MAT3(9, 36, 48, 16, "mat3"),

    /** {@code mat4} — 4×4 float matrix. 64 bytes, 16-byte aligned. */
    MAT4(16, 64, 64, 16, "mat4"),

    /**
     * {@code int} — 32-bit signed integer. 4 bytes, 4-byte aligned.
     *
     * <p><strong>v1</strong>: Format declaration is supported (correct stride/offset).
     * Named write method ({@code int_}) deferred to v2. Unwritten INT fields are
     * pre-zeroed by {@code beginRecord()}.
     * TODO v2: add {@code int_} named write with {@code CgStagingBuffer.setIntBitsAt}.</p>
     */
    INT(0, 4, 4, 4, "int"),

    /**
     * {@code uint} — 32-bit unsigned integer. 4 bytes, 4-byte aligned.
     *
     * <p><strong>v1</strong>: Format declaration supported. Named write deferred to v2.
     * TODO v2: add {@code uint} named write method.</p>
     */
    UINT(0, 4, 4, 4, "uint"),

    /**
     * {@code bool} — GPU boolean, always 4 bytes, 4-byte aligned.
     *
     * <p><strong>v1</strong>: Format declaration supported. Named write deferred to v2.
     * TODO v2: add {@code bool_} named write method.</p>
     */
    BOOL(0, 4, 4, 4, "bool");

    private final int floatComponents;
    private final int dataBytes;
    private final int alignedBytes;
    private final int alignment;
    private final String glslName;

    CgGpuType(int floatComponents, int dataBytes, int alignedBytes, int alignment, String glslName) {
        this.floatComponents = floatComponents;
        this.dataBytes       = dataBytes;
        this.alignedBytes    = alignedBytes;
        this.alignment       = alignment;
        this.glslName        = glslName;
    }

    /**
     * Returns the number of logical float components written for this type.
     * Zero for INT, UINT, and BOOL (no float write method in v1).
     */
    public int getFloatComponents() {
        return floatComponents;
    }

    /** Returns the raw data bytes of the type (without alignment padding). */
    public int getDataBytes() {
        return dataBytes;
    }

    /**
     * Returns the bytes this type occupies in a std140 or std430 block, including
     * intra-field alignment padding. This is the value used by {@link CgBufferFormat}
     * when computing field offsets and the total stride.
     */
    public int getAlignedBytes() {
        return alignedBytes;
    }

    /** Returns the byte-alignment requirement of this type (e.g. 16 for vec4/mat4/mat3). */
    public int getAlignment() {
        return alignment;
    }

    /** Returns the GLSL type name (e.g. {@code "mat4"}, {@code "vec3"}). */
    public String getGlslName() {
        return glslName;
    }

    /**
     * Convenience: returns the number of float slots this type occupies in a buffer
     * ({@code alignedBytes / 4}). Useful for computing float-based offsets.
     */
    public int getFloatCount() {
        return alignedBytes / 4;
    }
}
