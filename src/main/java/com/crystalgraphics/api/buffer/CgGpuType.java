package com.crystalgraphics.api.buffer;

import com.crystalgraphics.api.vertex.CgAttribType;
import com.crystalgraphics.gl.buffer.staging.CgBufferWriter;

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
 *
 * <p><strong>Sampler types absent</strong>: {@code sampler2D}, {@code sampler2DArray}, etc.
 * are opaque GLSL types and are illegal inside buffer blocks per GLSL §4.1.7.
 * They are intentionally absent from this enum.
 * For bindless texture handles, use {@link #UVEC2} (2 × uint32 = one 64-bit handle) or
 * {@link #UINT64} (single uint64_t, requires {@code ARB_gpu_shader_int64}).</p>
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
     * <p>Named write: {@code CgBufferWriter.int_()}.</p>
     */
    INT(0, 4, 4, 4, "int"),

    /**
     * {@code uint} — 32-bit unsigned integer. 4 bytes, 4-byte aligned.
     *
     * <p>Named write: {@code CgBufferWriter.uint()}.</p>
     */
    UINT(0, 4, 4, 4, "uint"),

    /**
     * {@code bool} — GPU boolean, always 4 bytes, 4-byte aligned.
     *
     * <p>Named write: {@code CgBufferWriter.bool_()}.</p>
     */
    BOOL(0, 4, 4, 4, "bool"),

    /** {@code ivec2} — 2-component signed integer vector. 8 bytes, 8-byte aligned.
     * <p>TBO path: not supported in v1 (requires {@code isamplerBuffer} + {@code GL_RGBA32I}).</p> */
    IVEC2(0, 8, 8, 8, "ivec2"),

    /** {@code ivec3} — 3-component signed integer vector. 12 bytes data, 16-byte aligned slot.
     * <p>TBO path: not supported in v1.</p> */
    IVEC3(0, 12, 16, 16, "ivec3"),

    /** {@code ivec4} — 4-component signed integer vector. 16 bytes, 16-byte aligned.
     * <p>TBO path: not supported in v1.</p> */
    IVEC4(0, 16, 16, 16, "ivec4"),

    /** {@code uvec2} — 2-component unsigned integer vector. 8 bytes, 8-byte aligned.
     * <p>Primary type for bindless texture handles ({@code ARB_bindless_texture}):
     * a {@code uvec2} stores one 64-bit GL texture handle as two uint32 components.</p>
     * <p>TBO path: not supported in v1 (requires {@code usamplerBuffer} + {@code GL_RGBA32UI}).</p> */
    UVEC2(0, 8, 8, 8, "uvec2"),

    /** {@code uvec3} — 3-component unsigned integer vector. 12 bytes data, 16-byte aligned slot.
     * <p>TBO path: not supported in v1.</p> */
    UVEC3(0, 12, 16, 16, "uvec3"),

    /** {@code uvec4} — 4-component unsigned integer vector. 16 bytes, 16-byte aligned.
     * <p>TBO path: not supported in v1.</p> */
    UVEC4(0, 16, 16, 16, "uvec4"),

    /** {@code uint64_t} — 64-bit unsigned integer. 8 bytes, 8-byte aligned.
     * <p>Requires {@code ARB_gpu_shader_int64} or {@code ARB_bindless_texture}.
     * Stores a full 64-bit GL texture handle in a single field (vs two uint32 with UVEC2).</p>
     * <p>TBO path: not supported in v1 (no standard 64-bit TBO internal format).</p> */
    UINT64(0, 8, 8, 8, "uint64_t"),

    /** {@code int64_t} — 64-bit signed integer. 8 bytes, 8-byte aligned.
     * <p>Requires {@code ARB_gpu_shader_int64}.</p>
     * <p>TBO path: not supported in v1.</p> */
    INT64(0, 8, 8, 8, "int64_t");

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
     * Returns the GLSL extension string required to use this type, or {@code null} if none.
     * INT64 and UINT64 generate {@code int64_t}/{@code uint64_t} declarations that require
     * {@code GL_ARB_gpu_shader_int64}.
     */
    public String requiredExtension() {
        switch (this) {
            case INT64:
            case UINT64:
                return "GL_ARB_gpu_shader_int64";
            default:
                return null;
        }
    }

    /**
     * Convenience: returns the number of float slots this type occupies in a buffer
     * ({@code alignedBytes / 4}). Useful for computing float-based offsets.
     */
    public int getFloatCount() {
        return alignedBytes / 4;
    }

    /**
     * Returns {@code true} if this type can be used in a TBO-path attached buffer.
     *
     * <p>The TBO float path uses {@code samplerBuffer} + {@code GL_RGBA32F}. Only float-family
     * types map naturally to this format. Integer and 64-bit types require
     * {@code isamplerBuffer}/{@code usamplerBuffer} with {@code GL_RGBA32I}/{@code GL_RGBA32UI},
     * which the current {@code CgTextureBuffer} does not support.</p>
     *
     * <p>TBO-compatible: FLOAT, VEC2, VEC3, VEC4, MAT3, MAT4.<br>
     * Not TBO-compatible: INT, UINT, BOOL, IVEC2-4, UVEC2-4, INT64, UINT64.</p>
     */
    public boolean isTboCompatible() {
        switch (this) {
            case FLOAT: case VEC2: case VEC3: case VEC4: case MAT3: case MAT4:
                return true;
            default:
                return false;
        }
    }
}
