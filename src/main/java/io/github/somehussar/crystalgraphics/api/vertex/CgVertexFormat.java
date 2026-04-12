package io.github.somehussar.crystalgraphics.api.vertex;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Immutable, hashable vertex format descriptor.
 *
 * <p>No GL calls. No shader coupling. Pure data. Two independently constructed
 * formats with the same attributes are {@code equals()} and share the same
 * hash code — enabling VAO caching by format content, not identity.</p>
 *
 * <p>Attribute locations are assigned sequentially (0, 1, 2, …) in the order
 * they are added via the builder. CrystalGraphics should bind these indices
 * explicitly via {@code glBindAttribLocation} before shader link, making
 * VAO setup format-driven instead of shader-name-driven.</p>
 */
public final class CgVertexFormat {

    private final CgVertexAttribute[] attributes;
    /**
     * -- GETTER --
     * Returns the total byte stride per vertex. 
     */
    @Getter
    private final int stride;
    private final int hash;
    /**
     * -- GETTER --
     * Returns the debug/diagnostic name. 
     */
    @Getter
    private final String debugName;

    // ── Predefined formats ──────────────────────────────────────────────

    /**
     * Canonical 2D textured quad with color: pos2f + uv2f + color4ub = 20 bytes.
     * Matches the legacy CgGlyphVbo layout (same stride=20, same offsets).
     */
    public static final CgVertexFormat POS2_UV2_COL4UB = builder("pos2_uv2_col4ub")
            .add(CgVertexSemantic.POSITION, "a_pos"  , 2, CgAttribType.FLOAT)
            .add(CgVertexSemantic.UV      , "a_uv"   , 2, CgAttribType.FLOAT)
            .add(CgVertexSemantic.COLOR   , "a_color", 4, CgAttribType.UNSIGNED_BYTE, true)
            .build();

    /**
     * 3D textured quad with color: pos3f + uv2f + color4ub = 24 bytes.
     * Used for world-space overlays, 3D UI panels, and any geometry that
     * requires a Z coordinate.
     */
    public static final CgVertexFormat POS3_UV2_COL4UB = builder("pos3_uv2_col4ub")
            .add(CgVertexSemantic.POSITION, "a_pos"  , 3, CgAttribType.FLOAT)
            .add(CgVertexSemantic.UV      , "a_uv"   , 2, CgAttribType.FLOAT)
            .add(CgVertexSemantic.COLOR   , "a_color", 4, CgAttribType.UNSIGNED_BYTE, true)
            .build();

    private CgVertexFormat(CgVertexAttribute[] attributes, int stride, String debugName) {
        this.attributes = attributes;
        this.stride = stride;
        this.debugName = debugName;
        this.hash = computeHash(attributes);
    }

    /** Creates a new format builder. */
    public static Builder builder(String debugName) {
        return new Builder(debugName);
    }

    /** Returns the number of attributes in this format. */
    public int getAttributeCount() {
        return attributes.length;
    }

    /** Returns the attribute at the given index. */
    public CgVertexAttribute getAttribute(int index) {
        return attributes[index];
    }

    /**
     * Returns the number of floats per vertex, assuming all components are
     * 4-byte aligned. Used by the batch layer for float[] staging sizing.
     */
    public int getFloatsPerVertex() {
        return stride / 4;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CgVertexFormat that = (CgVertexFormat) o;
        return stride == that.stride && Arrays.equals(attributes, that.attributes);
    }

    @Override
    public int hashCode() {
        return hash;
    }

    @Override
    public String toString() {
        return "CgVertexFormat{" + debugName + ", stride=" + stride
                + ", attrs=" + Arrays.toString(attributes) + "}";
    }

    private static int computeHash(CgVertexAttribute[] attrs) {
        int h = 17;
        for (CgVertexAttribute a : attrs) {
            h = 31 * h + a.hashCode();
        }
        return h;
    }

    // ── Builder ─────────────────────────────────────────────────────────

    public static final class Builder {
        private final String debugName;
        private final List<CgVertexAttribute> attrs = new ArrayList<CgVertexAttribute>();
        private int currentOffset = 0;

        private Builder(String debugName) {
            this.debugName = debugName != null ? debugName : "unnamed";
        }

        /**
         * Adds a vertex attribute with the given name, component count, type,
         * and normalization flag. The offset is computed automatically.
         */
        public Builder add(String name, int components, CgAttribType type, boolean normalized) {
            attrs.add(new CgVertexAttribute(name, components, type, normalized, currentOffset));
            currentOffset += components * type.getByteSize();
            return this;
        }

        /**
         * Adds a vertex attribute with the given name, component count, type,
         * and normalization flag. The offset is computed automatically.
         */
        public Builder add(String name, int components, CgAttribType type) {
            attrs.add(new CgVertexAttribute(name, components, type, false, currentOffset));
            currentOffset += components * type.getByteSize();
            return this;
        }

        /**
         * Adds a semantic-aware vertex attribute with semantic index 0.
         */
        public Builder add(CgVertexSemantic semantic, String name, int components, CgAttribType type, boolean normalized) {
            return add(semantic, 0, name, components, type, normalized);
        }

        /**
         * Adds a semantic-aware vertex attribute with default normalization (false)
         * and semantic index 0.
         */
        public Builder add(CgVertexSemantic semantic, String name, int components, CgAttribType type) {
            return add(semantic, 0, name, components, type, false);
        }

        /**
         * Adds a semantic-aware vertex attribute with explicit semantic index.
         *
         * <p>Use this overload for multi-texture formats (e.g. UV0 diffuse + UV1 lightmap)
         * or secondary color channels (COLOR1 tint).</p>
         *
         * @param semantic       the attribute's semantic role
         * @param semanticIndex  0-based index distinguishing same-semantic attributes
         * @param name           shader attribute name (e.g. "a_uv1")
         * @param components     number of components (1-4)
         * @param type           primitive data type
         * @param normalized     whether values are normalized
         */
        public Builder add(CgVertexSemantic semantic, int semanticIndex, String name, int components,
                           CgAttribType type, boolean normalized) {
            attrs.add(new CgVertexAttribute(name, components, type, normalized, currentOffset,
                    semantic, semanticIndex));
            currentOffset += components * type.getByteSize();
            return this;
        }

        /** Builds the immutable format. */
        public CgVertexFormat build() {
            if (attrs.isEmpty()) throw new IllegalStateException("Format must have at least one attribute");
            CgVertexAttribute[] arr = attrs.toArray(new CgVertexAttribute[0]);
            return new CgVertexFormat(arr, currentOffset, debugName);
        }
    }
}
