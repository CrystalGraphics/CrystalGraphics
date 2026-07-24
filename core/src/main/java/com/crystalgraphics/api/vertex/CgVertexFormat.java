package com.crystalgraphics.api.vertex;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
public final class CgVertexFormat implements CgAttributeFormat {

    /**
     * Global registry mapping {@code key} → {@code CgVertexFormat}.
     * Auto-populated when any format is constructed via {@link Builder#build()}.
     * Used by the CrystalShader material pipeline to resolve {@code #type <name>} declarations
     * at compile time via {@link #forShaderType(String)}.
     *
     * <p>Thread-safe: {@code ConcurrentHashMap} allows concurrent format registration
     * from multiple threads during mod initialization without external synchronization.</p>
     */
    private static final Map<String, CgVertexFormat> REGISTRY = new ConcurrentHashMap<>();

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
     * Returns the key name. 
     */
    @Getter
    private final String key;

    // ── Predefined formats ──────────────────────────────────────────────

    /**
     * Canonical 2D textured quad with color: pos2f + uv2f + color4ub = 20 bytes.
     * Matches the legacy CgGlyphVbo layout (same stride=20, same offsets).
     */
    public static final CgVertexFormat POS2_UV2_COL4UB = builder("pos2_uv2_col4ub")
            .add(CgVertexSemantic.POSITION, "cg_Position"  , 2, CgAttribType.FLOAT)
            .add(CgVertexSemantic.UV      , "cg_TexCoord0"   , 2, CgAttribType.FLOAT)
            .add(CgVertexSemantic.COLOR   , "cg_Color", 4, CgAttribType.UNSIGNED_BYTE, true)
            .build();

    /**
     * 3D textured quad with color: pos3f + uv2f + color4ub = 24 bytes.
     * Used for world-space overlays, 3D UI panels, and any geometry that
     * requires a Z coordinate.
     */
    public static final CgVertexFormat POS3_UV2_COL4UB = builder("pos3_uv2_col4ub")
            .add(CgVertexSemantic.POSITION, "cg_Position"  , 3, CgAttribType.FLOAT)
            .add(CgVertexSemantic.UV      , "cg_TexCoord0"   , 2, CgAttribType.FLOAT)
            .add(CgVertexSemantic.COLOR   , "cg_Color", 4, CgAttribType.UNSIGNED_BYTE, true)
            .build();

    /**
     * Standard spatial vertex format for the CrystalShader material pipeline:
     * POSITION(vec3) + UV(vec2) + NORMAL(vec3) = 32 bytes stride.
     *
     * <p>Registered under the {@code #type} key {@code "spatial"} so that any
     * {@code .shader} file declaring {@code #type spatial} uses this format.</p>
     *
     * <p>Attribute locations are assigned sequentially in declaration order:</p>
     * <ul>
     *   <li>Location 0 — {@code cg_Position} (vec3)</li>
     *   <li>Location 1 — {@code cg_TexCoord0} (vec2)</li>
     *   <li>Location 2 — {@code cg_Normal} (vec3)</li>
     * </ul>
     *
     * <p>Pass to {@code CgShaderFactory.fromSource(vert, frag, CgVertexFormat.SPATIAL)}
     * so that {@code glBindAttribLocation} wires each attribute to the correct index
     * before shader link.</p>
     */
    public static final CgVertexFormat SPATIAL = builder("spatial")
            .add(CgVertexSemantic.POSITION, "cg_Position",  3, CgAttribType.FLOAT)
            .add(CgVertexSemantic.UV,       "cg_TexCoord0", 2, CgAttribType.FLOAT)
            .add(CgVertexSemantic.NORMAL,   "cg_Normal",    3, CgAttribType.FLOAT)
            .build();

    /**
     * Standard UI 2D vertex format for the CrystalShader material pipeline:
     * POSITION(vec2) + UV(vec2) + COLOR(ubyte4) = 20 bytes stride.
     *
     <p>Registered under the {@code #type} key {@code "UI"} so that any
     * {@code .shader} file declaring {@code #type spatial} uses this format.</p>
     */
    public static final CgVertexFormat UI = builder("UI")
            .add(CgVertexSemantic.POSITION, "cg_Position",  2, CgAttribType.FLOAT)
            .add(CgVertexSemantic.UV      , "cg_TexCoord0", 2, CgAttribType.FLOAT)
            .add(CgVertexSemantic.COLOR   , "cg_Color",     4, CgAttribType.UNSIGNED_BYTE, true)
            .build();


    private CgVertexFormat(CgVertexAttribute[] attributes, int stride, String key) {
        this.attributes = attributes;
        this.stride = stride;
        this.key = key;
        this.hash = computeHash(attributes);
        // Auto-register under key as the #type key for the CrystalShader material pipeline.
        // ConcurrentHashMap.putIfAbsent is atomic — safe for concurrent format construction.
        CgVertexFormat existing = REGISTRY.putIfAbsent(key, this);
        if (existing != null && !this.equals(existing)) {
            throw new IllegalStateException(
                    "CgVertexFormat registry collision: a different format is already registered under '"
                    + key + "'. Each key must map to exactly one format layout.");
        }
    }

    /** Creates a new format builder. */
    public static Builder builder(String key) {
        return new Builder(key);
    }

    /**
     * Looks up a registered format by its {@code #type} name (= its {@code key}).
     *
     * @param name the type name from a {@code #type <name>} directive, e.g. {@code "spatial"}
     * @return the registered format, or {@code null} if no format was registered under that name
     */
    public static CgVertexFormat forShaderType(String name) {
        return REGISTRY.get(name);
    }

    /**
     * Returns an unmodifiable view of all currently registered type names.
     * Used by {@code CgStructureParser} to produce informative error messages
     * when an unknown {@code #type} name is encountered.
     */
    public static Set<String> registeredShaderTypes() {
        return Collections.unmodifiableSet(REGISTRY.keySet());
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
    public int getFloatsPerElement() {
        return stride / Float.BYTES;
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
        return "CgVertexFormat{" + key + ", stride=" + stride
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
        private final String key;
        private final List<CgVertexAttribute> attrs = new ArrayList<CgVertexAttribute>();
        private int currentOffset = 0;

        private Builder(String key) {
            this.key = key != null ? key : "unnamed";
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
            return new CgVertexFormat(arr, currentOffset, key);
        }
    }
}
