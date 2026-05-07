package io.github.somehussar.crystalgraphics.api.buffer;

import io.github.somehussar.crystalgraphics.gl.buffer.staging.CgBufferWriter;

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
    private final int stride;
    private final MemoryLayout memoryLayout;
    private final String debugName;
    private final int hash;

    private CgBufferFormat(CgBufferField[] fields, Map<String, CgBufferField> fieldMap,
                            int stride, MemoryLayout memoryLayout, String debugName) {
        this.fields      = fields;
        this.fieldMap    = fieldMap;
        this.stride      = stride;
        this.memoryLayout = memoryLayout;
        this.debugName   = debugName;
        this.hash        = computeHash(fields, stride, memoryLayout);
    }

    /** Creates a new format builder with the given debug name and memory layout. */
    public static Builder builder(String debugName, MemoryLayout memoryLayout) {
        return new Builder(debugName, memoryLayout);
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
                "No field '" + name + "' in CgBufferFormat '" + debugName + "'. "
                + "Declared fields: " + Arrays.toString(fields));
        }
        return f;
    }

    /** Returns the total byte stride of one record (sum of aligned field sizes with padding). */
    public int getStride() {
        return stride;
    }

    /**
     * Returns the total number of float slots in one record ({@code stride / 4}).
     * This is the number of floats a {@link CgBufferWriter}
     * must write per record when using this format.
     */
    public int getFloatCount() {
        return stride / 4;
    }

    /** Returns the memory layout this format was built for. */
    public MemoryLayout getMemoryLayout() {
        return memoryLayout;
    }

    /** Returns the debug/diagnostic name (excluded from equality). */
    public String getDebugName() {
        return debugName;
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
        return "CgBufferFormat{" + debugName + ", " + memoryLayout
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
        private final String debugName;
        private final MemoryLayout memoryLayout;
        private final List<CgBufferField> fields = new ArrayList<CgBufferField>();
        private int cursor = 0;

        private Builder(String debugName, MemoryLayout memoryLayout) {
            this.debugName    = debugName != null ? debugName : "unnamed";
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

        /**
         * Adds an {@link CgGpuType#INT} field.
         * <p><strong>v1</strong>: Correct stride and offset computation only.
         * Named write method ({@code int_}) deferred to v2.</p>
         */
        public Builder int_(String name)   { return add(name, CgGpuType.INT); }

        /**
         * Adds a {@link CgGpuType#UINT} field.
         * <p><strong>v1</strong>: Correct stride and offset computation only.
         * Named write method ({@code uint}) deferred to v2.</p>
         */
        public Builder uint(String name)   { return add(name, CgGpuType.UINT); }

        /**
         * Adds a {@link CgGpuType#BOOL} field.
         * <p><strong>v1</strong>: Correct stride and offset computation only.
         * Named write method ({@code bool_}) deferred to v2.</p>
         */
        public Builder bool_(String name)  { return add(name, CgGpuType.BOOL); }

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
                        "Duplicate field name '" + f.getName() + "' in CgBufferFormat '" + debugName + "'");
                }
            }
            return new CgBufferFormat(arr, map, cursor, memoryLayout, debugName);
        }
    }
}
