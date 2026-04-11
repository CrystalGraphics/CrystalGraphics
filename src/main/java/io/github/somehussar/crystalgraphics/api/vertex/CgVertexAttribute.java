package io.github.somehussar.crystalgraphics.api.vertex;

/**
 * Describes a single vertex attribute within a {@link CgVertexFormat}.
 *
 * <p>Instances are immutable value objects. The {@code offset} is computed at
 * format build time and represents the byte offset of this attribute within
 * one vertex's worth of interleaved data.</p>
 */
public final class CgVertexAttribute {

    private final String name;
    private final int components;
    private final CgAttribType type;
    private final boolean normalized;
    private final int byteSize;
    private final int offset;

    CgVertexAttribute(String name, int components, CgAttribType type, boolean normalized, int offset) {
        if (name == null || name.isEmpty()) throw new IllegalArgumentException("name must not be null or empty");
        if (components < 1 || components > 4) throw new IllegalArgumentException("components must be 1..4, got " + components);
        if (type == null) throw new IllegalArgumentException("type must not be null");
        this.name = name;
        this.components = components;
        this.type = type;
        this.normalized = normalized;
        this.byteSize = components * type.getByteSize();
        this.offset = offset;
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CgVertexAttribute that = (CgVertexAttribute) o;
        return components == that.components
                && normalized == that.normalized
                && offset == that.offset
                && type == that.type
                && name.equals(that.name);
    }

    @Override
    public int hashCode() {
        int result = name.hashCode();
        result = 31 * result + components;
        result = 31 * result + type.hashCode();
        result = 31 * result + (normalized ? 1 : 0);
        result = 31 * result + offset;
        return result;
    }

    @Override
    public String toString() {
        return "CgVertexAttribute{" + name + ", " + components + "x" + type
                + (normalized ? " norm" : "") + ", offset=" + offset + "}";
    }
}
