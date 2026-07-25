package com.crystalgraphics.api.buffer;

import com.crystalgraphics.gl.buffer.staging.CgBufferWriter;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable descriptor for a GPU buffer's field layout (UBO or SSBO record).
 *
 * <p>No GL calls. No shader coupling. Pure data. Two independently constructed formats
 * with the same fields and memory layout are {@link #equals(Object)} and share the same
 * hash code — enabling buffer caching by format content, not identity.</p>
 *
 * <p>The builder auto-computes field byte offsets by applying the alignment rules of the
 * chosen {@link MemoryLayout}. For the field types in v1, std140 and std430 produce
 * identical offsets and strides (the only meaningful difference — scalar arrays — is
 * deferred to v2).</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * CgBufferFormat objectFmt = CgBufferFormat
 *     .builder("cg_object", CgBufferFormat.MemoryLayout.STD430)
 *     .mat4("modelMatrix")
 *     .mat4("normalMatrix")
 *     .vec4("custom0")
 *     .vec4("custom1")
 *     .build();
 *
 * // stride = 48 floats × 4 bytes = 192 bytes
 * int floatCount = objectFmt.getFloatCount(); // 48
 * CgBufferField f = objectFmt.getField("modelMatrix");
 * int offset = f.getFloatOffset(); // 0
 * }</pre>
 *
 * <p>TODO v2: array fields — {@code arrayOf(String name, CgGpuType type, int length)}.
 * Arrays require different stride calculations between std140 (each element padded to 16 bytes)
 * and std430 (element stride = type.alignedBytes). Deferred to keep v1 alignment code simple.</p>
 */
public final class CgBufferFormat {

    /**
     * Memory layout rules for buffer fields.
     *
     * <p><b>Rule of thumb</b>:
     * <ul>
     *   <li>Use {@link #STD140} for Uniform Buffer Objects (UBO, {@code GL_UNIFORM_BUFFER}).
     *   <li>Use {@link #STD430} for Shader Storage Buffer Objects (SSBO, {@code GL_SHADER_STORAGE_BUFFER}).
     * </ul>
     *
     * <p>For the field types supported in v1 (mat4, vec4, mat3, vec3, vec2, float, int, bool)
     * both layouts produce identical field sizes and alignment. The only meaningful difference
     * is for arrays-of-scalars (std140 pads each element to 16 bytes; std430 does not) —
     * array fields are deferred to v2.
     */
    public enum MemoryLayout {
        /** std140 layout — use for UBOs ({@code GL_UNIFORM_BUFFER}). */
        STD140,
        /** std430 layout — use for SSBOs ({@code GL_SHADER_STORAGE_BUFFER}). */
        STD430
    }

    private final CgBufferField[] fields;
    private final Map<String, CgBufferField> fieldMap;
    /**
     * -- GETTER --
     * Returns the total byte stride of one record (sum of aligned field sizes with padding). 
     */
    @Getter
    private final int stride;
    /**
     * -- GETTER --
     * Returns the memory layout this format was built for. 
     */
    @Getter
    private final MemoryLayout memoryLayout;
    private final String glslName;
    private final int hash;

    private CgBufferFormat(CgBufferField[] fields, Map<String, CgBufferField> fieldMap,
                            int stride, MemoryLayout memoryLayout, String glslName) {
        this.fields      = fields;
        this.fieldMap    = fieldMap;
        this.stride      = stride;
        this.memoryLayout = memoryLayout;
        this.glslName    = glslName;
        this.hash        = computeHash(fields, stride, memoryLayout);
    }

    /** Creates a new format builder with the given GLSL struct type name and memory layout. */
    public static Builder builder(String glslName, MemoryLayout memoryLayout) {
        return new Builder(glslName, memoryLayout);
    }

    /** Returns the number of fields in this format. */
    public int getFieldCount() {
        return fields.length;
    }

    /** Returns the field at the given index (in declaration order). */
    public CgBufferField getField(int index) {
        return fields[index];
    }

    /**
     * Returns the field with the given name.
     *
     * @param name the field name as declared in the builder
     * @return the matching field
     * @throws IllegalArgumentException if no field with this name exists
     */
    public CgBufferField getField(String name) {
        CgBufferField f = fieldMap.get(name);
        if (f == null) {
            throw new IllegalArgumentException(
                "No field '" + name + "' in CgBufferFormat '" + glslName + "'. "
                + "Declared fields: " + Arrays.toString(fields));
        }
        return f;
    }

    /**
     * Returns the total number of float slots in one record ({@code stride / 4}).
     * This is the number of floats a {@link CgBufferWriter}
     * must write per record when using this format.
     */
    public int getFloatCount() {
        return stride / 4;
    }

    /** Returns the GLSL identifier used as this format's struct type name in code generation. */
    public String getGlslName() {
        return glslName;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CgBufferFormat that = (CgBufferFormat) o;
        return stride == that.stride
                && memoryLayout == that.memoryLayout
                && Arrays.equals(fields, that.fields);
    }

    @Override
    public int hashCode() {
        return hash;
    }

    @Override
    public String toString() {
        return "CgBufferFormat{" + glslName + ", " + memoryLayout
                + ", stride=" + stride + " bytes (" + getFloatCount() + " floats)"
                + ", fields=" + Arrays.toString(fields) + "}";
    }

    private static int computeHash(CgBufferField[] fields, int stride, MemoryLayout layout) {
        int h = 17;
        h = 31 * h + stride;
        h = 31 * h + layout.hashCode();
        for (CgBufferField f : fields) {
            h = 31 * h + f.hashCode();
        }
        return h;
    }

    // ── Builder ──────────────────────────────────────────────────────────────

    public static final class Builder {
        private final String glslName;
        private final MemoryLayout memoryLayout;
        private final List<CgBufferField> fields = new ArrayList<CgBufferField>();
        private int cursor = 0;

        private Builder(String glslName, MemoryLayout memoryLayout) {
            this.glslName     = glslName != null ? glslName : "unnamed";
            this.memoryLayout = memoryLayout;
        }

        /**
         * Adds a field of the given type with the given name.
         * The byte offset is computed from the current cursor with correct alignment padding.
         */
        public Builder add(String name, CgGpuType type) {
            // 1. Align cursor to field's requirement
            int align = type.getAlignment();
            int padding = (align - (cursor % align)) % align;
            cursor += padding;
            // 2. Record field at aligned offset
            fields.add(new CgBufferField(name, type, cursor));
            // 3. Advance cursor by field's aligned size
            cursor += type.getAlignedBytes();
            return this;
        }

        /** Adds a {@link CgGpuType#FLOAT} field. */
        public Builder float_(String name) { return add(name, CgGpuType.FLOAT); }

        /** Adds a {@link CgGpuType#VEC2} field. */
        public Builder vec2(String name)   { return add(name, CgGpuType.VEC2); }

        /** Adds a {@link CgGpuType#VEC3} field. */
        public Builder vec3(String name)   { return add(name, CgGpuType.VEC3); }

        /** Adds a {@link CgGpuType#VEC4} field. */
        public Builder vec4(String name)   { return add(name, CgGpuType.VEC4); }

        /** Adds a {@link CgGpuType#MAT3} field (48 bytes, 3 × vec4-aligned columns). */
        public Builder mat3(String name)   { return add(name, CgGpuType.MAT3); }

        /** Adds a {@link CgGpuType#MAT4} field (64 bytes). */
        public Builder mat4(String name)   { return add(name, CgGpuType.MAT4); }

        /** Adds an {@link CgGpuType#INT} field. */
        public Builder int_(String name)   { return add(name, CgGpuType.INT); }

        /** Adds a {@link CgGpuType#UINT} field. */
        public Builder uint(String name)   { return add(name, CgGpuType.UINT); }

        /** Adds a {@link CgGpuType#BOOL} field. */
        public Builder bool_(String name)  { return add(name, CgGpuType.BOOL); }

        /** Adds an {@link CgGpuType#IVEC2} field. TBO path: not supported in v1. */
        public Builder ivec2(String name)  { return add(name, CgGpuType.IVEC2); }

        /** Adds an {@link CgGpuType#IVEC3} field. TBO path: not supported in v1. */
        public Builder ivec3(String name)  { return add(name, CgGpuType.IVEC3); }

        /** Adds an {@link CgGpuType#IVEC4} field. TBO path: not supported in v1. */
        public Builder ivec4(String name)  { return add(name, CgGpuType.IVEC4); }

        /** Adds a {@link CgGpuType#UVEC2} field (bindless texture handles, etc.).
         * TBO path: not supported in v1. */
        public Builder uvec2(String name)  { return add(name, CgGpuType.UVEC2); }

        /** Adds a {@link CgGpuType#UVEC3} field. TBO path: not supported in v1. */
        public Builder uvec3(String name)  { return add(name, CgGpuType.UVEC3); }

        /** Adds a {@link CgGpuType#UVEC4} field. TBO path: not supported in v1. */
        public Builder uvec4(String name)  { return add(name, CgGpuType.UVEC4); }

        /** Adds a {@link CgGpuType#UINT64} field (64-bit; requires ARB_gpu_shader_int64).
         * TBO path: not supported in v1. */
        public Builder uint64(String name) { return add(name, CgGpuType.UINT64); }

        /** Adds a {@link CgGpuType#INT64} field (64-bit; requires ARB_gpu_shader_int64).
         * TBO path: not supported in v1. */
        public Builder int64(String name)  { return add(name, CgGpuType.INT64); }

        /**
         * Builds the immutable format descriptor.
         *
         * @throws IllegalStateException if no fields were added
         */
        public CgBufferFormat build() {
            if (fields.isEmpty()) {
                throw new IllegalStateException("CgBufferFormat must have at least one field");
            }
            CgBufferField[] arr = fields.toArray(new CgBufferField[0]);
            Map<String, CgBufferField> map = new HashMap<>(arr.length * 2);
            for (CgBufferField f : arr) {
                if (map.put(f.getName(), f) != null) {
                    throw new IllegalStateException(
                        "Duplicate field name '" + f.getName() + "' in CgBufferFormat '" + glslName + "'");
                }
            }
            // std140/std430 both require a struct's own overall size (its per-element
            // stride when used in an array, e.g. every CgBufferFormat here — SSBO/TBO
            // records are always arrays of this struct) to be rounded up to a multiple
            // of 16 (the base alignment of vec4), on top of each individual field's own
            // alignment already applied by add() above. Every supported field type here
            // (float/vec2/vec3/vec4/mat3/mat4/int/uint/bool) tops out at 16-byte
            // alignment, so rounding the final cursor to 16 is always correct — not just
            // "the common case." Skipping this rounding is silently correct only when a
            // format's fields happen to sum to an exact multiple of 16 already (true for
            // every format that existed before this fix, which is exactly why this was
            // never caught) and silently WRONG otherwise: the GPU still applies this
            // rounding when indexing an array of this struct (QUAD_DATA(n) et al.)
            // regardless of what stride the CPU side assumes, so an unrounded stride
            // here desyncs every record after the first — each subsequent instance's
            // fields get read from the wrong byte offset, worse with each index.
            int stride = roundUpTo16(cursor);
            return new CgBufferFormat(arr, map, stride, memoryLayout, glslName);
        }

        private static int roundUpTo16(int bytes) {
            return (bytes + 15) & ~15;
        }
    }
}
