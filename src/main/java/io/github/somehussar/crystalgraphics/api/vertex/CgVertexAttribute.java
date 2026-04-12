package io.github.somehussar.crystalgraphics.api.vertex;

/**
 * Describes a single vertex attribute within a {@link CgVertexFormat}.
 *
 * <p>Instances are immutable value objects. The {@code offset} is computed at
 * format build time and represents the byte offset of this attribute within
 * one vertex's worth of interleaved data.</p>
 *
 * <h3>Semantic Metadata</h3>
 * <p>Each attribute carries a {@link CgVertexSemantic} and a {@code semanticIndex}
 * that together describe its purpose within the vertex format:</p>
 * <ul>
 *   <li><strong>{@code semantic}</strong> — the role (POSITION, UV, COLOR, NORMAL, GENERIC)</li>
 *   <li><strong>{@code semanticIndex}</strong> — disambiguates multiple attributes of the
 *       same semantic. For example, a format with both diffuse UV (index 0) and lightmap
 *       UV (index 1) would have two UV attributes with different indices.</li>
 * </ul>
 *
 * <p>This pair enables format-aware vertex writers (e.g. {@code CgVertexWriter}) to
 * route fluent API calls to the correct staging offsets by semantic identity, not by
 * attribute name or declaration order.</p>
 *
 * <p>Legacy constructors (without semantic parameters) default to
 * {@link CgVertexSemantic#GENERIC} with index 0 for backward compatibility.</p>
 */
public final class CgVertexAttribute {

    private final String name;
    private final int components;
    private final CgAttribType type;
    private final boolean normalized;
    private final int byteSize;
    private final int offset;
    private final CgVertexSemantic semantic;
    private final int semanticIndex;

    CgVertexAttribute(String name, int components, CgAttribType type, boolean normalized, int offset) {
        this(name, components, type, normalized, offset, CgVertexSemantic.GENERIC, 0);
    }

    CgVertexAttribute(String name, int components, CgAttribType type, boolean normalized, int offset,
                      CgVertexSemantic semantic, int semanticIndex) {
        if (name == null || name.isEmpty()) throw new IllegalArgumentException("name must not be null or empty");
        if (components < 1 || components > 4) throw new IllegalArgumentException("components must be 1..4, got " + components);
        if (type == null) throw new IllegalArgumentException("type must not be null");
        if (semantic == null) throw new IllegalArgumentException("semantic must not be null");
        if (semanticIndex < 0) throw new IllegalArgumentException("semanticIndex must be >= 0, got " + semanticIndex);
        this.name = name;
        this.components = components;
        this.type = type;
        this.normalized = normalized;
        this.byteSize = components * type.getByteSize();
        this.offset = offset;
        this.semantic = semantic;
        this.semanticIndex = semanticIndex;
    }

    /** Returns the shader attribute name (e.g. {@code "a_pos"}). */
    public String getName() { return name; }

    /** Returns the number of components (1..4). */
    public int getComponents() { return components; }

    /** Returns the primitive data type. */
    public CgAttribType getType() { return type; }

    /** Returns whether values should be normalized to [0,1] or [-1,1]. */
    public boolean isNormalized() { return normalized; }

    /** Returns the byte size of this attribute (components * type byte size). */
    public int getByteSize() { return byteSize; }

    /** Returns the byte offset within one vertex of interleaved data. */
    public int getOffset() { return offset; }

    /**
     * Returns the semantic role of this attribute (POSITION, UV, COLOR, NORMAL, or GENERIC).
     *
     * <p>Combined with {@link #getSemanticIndex()}, this uniquely identifies the attribute's
     * purpose within a vertex format — enabling format-aware writers to route fluent API calls
     * to the correct staging offsets without relying on attribute name conventions.</p>
     */
    public CgVertexSemantic getSemantic() { return semantic; }

    /**
     * Returns the semantic index, distinguishing multiple attributes of the same semantic.
     *
     * <p>The primary use case is multi-texturing: UV index 0 is the main texture coordinate,
     * index 1 could be a lightmap coordinate, etc. For POSITION and NORMAL, only index 0
     * is meaningful in practice. COLOR index 1 could serve as a secondary tint channel.</p>
     *
     * <p>The V1 vertex writer ({@code CgVertexWriter}) only supports index 0 for all
     * semantics. Formats with higher indices will fail at writer construction time.</p>
     *
     * @return the 0-based semantic index (always >= 0)
     */
    public int getSemanticIndex() { return semanticIndex; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CgVertexAttribute that = (CgVertexAttribute) o;
        return components == that.components
                && normalized == that.normalized
                && offset == that.offset
                && type == that.type
                && semantic == that.semantic
                && semanticIndex == that.semanticIndex
                && name.equals(that.name);
    }

    @Override
    public int hashCode() {
        int result = name.hashCode();
        result = 31 * result + components;
        result = 31 * result + type.hashCode();
        result = 31 * result + (normalized ? 1 : 0);
        result = 31 * result + offset;
        result = 31 * result + semantic.hashCode();
        result = 31 * result + semanticIndex;
        return result;
    }

    @Override
    public String toString() {
        return "CgVertexAttribute{" + name + ", " + components + "x" + type
                + (normalized ? " norm" : "") + ", offset=" + offset
                + ", semantic=" + semantic + (semanticIndex > 0 ? "[" + semanticIndex + "]" : "") + "}";
    }
}
