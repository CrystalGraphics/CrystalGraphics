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
public final class CgVertexFormat {

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
     * The vertex of every mesh a material draws: position, normal, colour and four UV sets in 40 bytes, Unreal's local
     * vertex factory's streams at its default precision. {@code #type spatial}.
     *
     * <pre>{@code
     * CgMesh tri = CgMesh.build(CgVertexFormat.SPATIAL, m -> {
     *     int a = m.vertex().position(0, 0, 0).normal(0, 1, 0).uv(0, 0).end();               // white, every set = uv
     *     int b = m.vertex().position(1, 0, 0).normal(0, 1, 0).uv(1, 0).color(0xFFFF8000).end();
     *     int c = m.vertex().position(0, 0, 1).normal(0, 1, 0).uv(0, 1).uv1(0.5f, 0.5f).end();
     *     m.triangle(a, b, c);
     * });
     * }</pre>
     *
     * <table>
     *   <tr><th>Location</th><th>Shader reads</th><th>Stored</th><th>Unreal</th></tr>
     *   <tr><td>0</td><td>{@code vec3 cg_Position}</td><td>3 floats</td><td>{@code Position}</td></tr>
     *   <tr><td>1</td><td>{@code vec3 cg_Normal}</td><td>4 snorm16, w padding</td><td>{@code TangentZ}</td></tr>
     *   <tr><td>2</td><td>{@code vec4 cg_Color}</td><td>4 unorm8</td><td>{@code Color}</td></tr>
     *   <tr><td>3</td><td>{@code vec2 cg_TexCoord0}</td><td>2 halves</td><td>{@code TexCoords0}</td></tr>
     *   <tr><td>4</td><td>{@code vec2 cg_TexCoord1}</td><td>2 halves</td><td>{@code TexCoords1}</td></tr>
     *   <tr><td>5</td><td>{@code vec2 cg_TexCoord2}</td><td>2 halves</td><td>{@code TexCoords2}</td></tr>
     *   <tr><td>6</td><td>{@code vec2 cg_TexCoord3}</td><td>2 halves</td><td>{@code TexCoords3}</td></tr>
     * </table>
     *
     * <ul>
     *   <li>A vertex given no colour is white, and a UV set it is not given repeats the set before it: Unreal's null
     *       colour stream and its repeated last UV channel. Every attribute is always there, so one pool and one VAO
     *       serve every mesh; four sets, not Unreal's per-mesh count, is 8 bytes a vertex for that.</li>
     *   <li>UVs are halves: steps of 1/2048 below 1, 1/1024 to 2, 1/16 at 64. A UV tiled far past that needs a format of its own.</li>
     *   <li>Read stored bytes back with {@link CgVertexAttribute#read}, never {@code getFloat}.</li>
     * </ul>
     */
    public static final CgVertexFormat SPATIAL = builder("spatial")
            .add(CgVertexSemantic.POSITION, "cg_Position",  3, CgAttribType.FLOAT)
            .add(CgVertexSemantic.NORMAL, 0, "cg_Normal",   4, 3, CgAttribType.SHORT, true)
            .add(CgVertexSemantic.COLOR,    "cg_Color",     4, CgAttribType.UNSIGNED_BYTE, true)
            .add(CgVertexSemantic.UV,       "cg_TexCoord0", 2, CgAttribType.HALF_FLOAT)
            .add(CgVertexSemantic.UV, 1,    "cg_TexCoord1", 2, CgAttribType.HALF_FLOAT, false)
            .add(CgVertexSemantic.UV, 2,    "cg_TexCoord2", 2, CgAttribType.HALF_FLOAT, false)
            .add(CgVertexSemantic.UV, 3,    "cg_TexCoord3", 2, CgAttribType.HALF_FLOAT, false)
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


    /**
     * No attributes and a stride of 0: a mesh whose shader places every vertex itself from {@code CG_VERTEX_ID}
     * ({@code CgMesh.quads}, {@code CgMesh.vertices}). Registered as {@code #type none}.
     */
    public static final CgVertexFormat NONE = new CgVertexFormat(new CgVertexAttribute[0], 0, "none");

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

    /** The index of the attribute named {@code name}, or -1. */
    public int indexOf(String name) {
        for (int i = 0; i < attributes.length; i++) {
            if (attributes[i].getName().equals(name)) return i;
        }
        return -1;
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

        /**
         * A semantic attribute stored as {@code components} but declared to a shader as its first
         * {@code shaderComponents}: a normal padded to four snorm16s (a three-component 16-bit vertex format is
         * optional on Vulkan) and read as {@code vec3}.
         */
        public Builder add(CgVertexSemantic semantic, int semanticIndex, String name, int components,
                           int shaderComponents, CgAttribType type, boolean normalized) {
            attrs.add(new CgVertexAttribute(name, components, shaderComponents, type, normalized, currentOffset,
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
