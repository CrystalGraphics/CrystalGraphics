package io.github.somehussar.crystalgraphics.api.vertex;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Immutable, value-equal descriptor for per-instance vertex attributes.
 *
 * <p>Mirrors {@link CgVertexFormat} in design but serves a different purpose:
 * where a vertex format describes per-vertex data, an instance layout describes
 * per-instance data. Attributes in an instance layout are bound at the start slot
 * {@code baseFormat.getAttributeCount()} inside an instanced VAO.</p>
 *
 * <p>The layout is immutable after {@link Builder#build()} and value-equal based
 * on stride, divisor, and all physical attributes. The debug name is excluded
 * from equality (diagnostic use only), matching {@link CgVertexFormat} precedent.</p>
 *
 * <p>Only divisor {@code 1} is supported in v1. Multi-rate instancing is deferred.</p>
 *
 * <h3>mat4 expansion</h3>
 * <p>A single logical {@code mat4} expands into four physical {@code vec4} attributes.
 * Each column occupies one attribute slot. Given base name {@code "a_model"}, the
 * four attributes are named {@code "a_model0"}, {@code "a_model1"}, {@code "a_model2"},
 * {@code "a_model3"} — matching the GLSL shader attribute names.</p>
 *
 * <h3>Common layout</h3>
 * <p>{@link #TRANSFORM_COLOR_CUSTOM} is a pre-built engine-primitive layout with
 * mat4 transform (64 bytes), RGBA color (4 bytes), and custom vec4 (16 bytes) =
 * 84 bytes per instance.</p>
 */
public final class CgInstanceFormat implements CgAttributeFormat {

    private final CgVertexAttribute[] attributes;
    /**
     * -- GETTER --
     * Returns the total byte stride per instance. 
     */
    @Getter
    private final int stride;
    /**
     * -- GETTER --
     * Returns the divisor (always 1 in v1). 
     */
    @Getter
    private final int divisor;
    /**
     * -- GETTER --
     * Returns the debug/diagnostic name (excluded from equality). 
     */
    @Getter
    private final String debugName;
    private final int hash;

    /**
     * Pre-built engine-primitive layout: mat4 model transform + RGBA color + vec4 custom.
     *
     * <p>Physical attributes (6 total):</p>
     * <ul>
     *   <li>{@code a_instanceModel0..3} — four float vec4 columns of the model matrix (64 bytes)</li>
     *   <li>{@code a_instanceColor} — normalized unsigned byte RGBA color (4 bytes)</li>
     *   <li>{@code a_instanceCustom} — float vec4 custom data (16 bytes)</li>
     * </ul>
     * Total stride: 84 bytes.
     */
    public static final CgInstanceFormat TRANSFORM_COLOR_CUSTOM = builder("transform_color_custom")
            .mat4("a_instanceModel")
            .color4UB("a_instanceColor")
            .vec4("a_instanceCustom")
            .build();

    private CgInstanceFormat(CgVertexAttribute[] attributes, int stride, int divisor, String debugName) {
        this.attributes = attributes;
        this.stride = stride;
        this.divisor = divisor;
        this.debugName = debugName;
        this.hash = computeHash(attributes, stride, divisor);
    }

    public int getFloatsPerInstance() {
        return stride / Float.BYTES;
    }

    @Override
    public int getFloatsPerElement() {
        return stride / Float.BYTES;
    }

    /** Creates a new instance layout builder. */
    public static Builder builder(String debugName) {
        return new Builder(debugName);
    }

    /** Returns the number of physical attributes (a mat4 counts as 4). */
    public int getAttributeCount() {
        return attributes.length;
    }

    /** Returns the physical attribute at the given index. */
    public CgVertexAttribute getAttribute(int index) {
        return attributes[index];
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CgInstanceFormat that = (CgInstanceFormat) o;
        return stride == that.stride && divisor == that.divisor && Arrays.equals(attributes, that.attributes);
    }

    @Override
    public int hashCode() {
        return hash;
    }

    @Override
    public String toString() {
        return "CgInstanceFormat{" + debugName + ", stride=" + stride + ", divisor=" + divisor
                + ", attrs=" + Arrays.toString(attributes) + "}";
    }

    private static int computeHash(CgVertexAttribute[] attrs, int stride, int divisor) {
        int h = 17;
        h = 31 * h + stride;
        h = 31 * h + divisor;
        for (CgVertexAttribute a : attrs) h = 31 * h + a.hashCode();
        
        return h;
    }

    public static final class Builder {
        private final String debugName;
        private final List<CgVertexAttribute> attrs = new ArrayList<CgVertexAttribute>();
        private int currentOffset = 0;
        private int divisor = 1;

        private Builder(String debugName) {
            this.debugName = debugName != null ? debugName : "unnamed";
        }

        /** Adds a physical attribute with explicit normalization flag. */
        public Builder add(String name, int components, CgAttribType type, boolean normalized) {
            attrs.add(new CgVertexAttribute(name, components, type, normalized, currentOffset));
            currentOffset += components * type.getByteSize();
            return this;
        }

        /** Adds a physical attribute (non-normalized). */
        public Builder add(String name, int components, CgAttribType type) {
            return add(name, components, type, false);
        }
        
         /** Adds a float scalar (1 float / 4 bytes) attribute. */
        public Builder add1f(String name) {
            return add(name, 1, CgAttribType.FLOAT, false);
        }

        /** Adds a float vec2 attribute. */
        public Builder vec2(String name) {
            return add(name, 2, CgAttribType.FLOAT, false);
        }

        /** Adds a float vec3 attribute. */
        public Builder vec3(String name) {
            return add(name, 3, CgAttribType.FLOAT, false);
        }

        /** Adds a float vec4 attribute. */
        public Builder vec4(String name) {
            return add(name, 4, CgAttribType.FLOAT, false);
        }

        /** Adds a normalized unsigned byte ×4 color attribute. */
        public Builder color4UB(String name) {
            return add(name, 4, CgAttribType.UNSIGNED_BYTE, true);
        }

        /**
         * Expands a mat3 into three physical float vec3 attributes.
         *
         * <p>Appends attributes named {@code baseName + "0"}, {@code baseName + "1"},
         * {@code baseName + "2"} — one per matrix column. Each column is a
         * {@code vec3} attribute slot (3 floats / 12 bytes).
         * A mat3 therefore consumes 3 attribute slots and 36 bytes.</p>
         *
         * <p>Writers must write exactly 9 floats for a mat3 (see
         * {@link io.github.somehussar.crystalgraphics.gl.buffer.staging.CgInstanceWriter#mat3}).</p>
         */
        public Builder mat3(String baseName) {
            vec3(baseName + "0");
            vec3(baseName + "1");
            vec3(baseName + "2");
            return this;
        }

        /**
         * Expands a mat4 into four physical float vec4 attributes.
         *
         * <p>Appends attributes named {@code baseName + "0"}, {@code baseName + "1"},
         * {@code baseName + "2"}, {@code baseName + "3"} — one per matrix column.
         * A mat4 consumes 4 attribute slots and 64 bytes.</p>
         */
        public Builder mat4(String baseName) {
            vec4(baseName + "0");
            vec4(baseName + "1");
            vec4(baseName + "2");
            vec4(baseName + "3");
            return this;
        }

        /**
         * Sets the attribute divisor. Only divisor {@code 1} is supported in v1.
         *
         * @throws IllegalArgumentException if divisor is not 1
         */
        public Builder divisor(int divisor) {
            if (divisor != 1) 
                throw new IllegalArgumentException("v1 only supports divisor=1; got " + divisor + ". Multi-rate instancing is deferred.");
            
            this.divisor = divisor;
            return this;
        }

        /**
         * Builds the immutable layout.
         *
         * @throws IllegalStateException if no attributes were added
         */
        public CgInstanceFormat build() {
            if (attrs.isEmpty()) throw new IllegalStateException("CgInstanceFormat must have at least one attribute");
            
            CgVertexAttribute[] arr = attrs.toArray(new CgVertexAttribute[0]);
            return new CgInstanceFormat(arr, currentOffset, divisor, debugName);
        }
    }
}
