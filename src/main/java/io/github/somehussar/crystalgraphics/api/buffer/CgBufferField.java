package io.github.somehussar.crystalgraphics.api.buffer;

/**
 * Describes a single named field within a {@link CgBufferFormat}.
 *
 * <p>Instances are immutable value objects. The {@code byteOffset} is computed
 * at format build time by the {@link CgBufferFormat.Builder}, applying the
 * std140/std430 alignment rules for the field's {@link CgGpuType}.</p>
 *
 * <p>The {@code floatOffset} is derived from {@code byteOffset} and is only valid
 * when {@code byteOffset} is divisible by 4 — always true for the field types in
 * {@link CgGpuType}.</p>
 *
 * <p>The constructor is package-private. Only {@link CgBufferFormat.Builder} creates
 * instances; external code receives them via {@link CgBufferFormat#getField(String)}.</p>
 */
public final class CgBufferField {

    private final String name;
    private final CgGpuType type;
    private final int byteOffset;
    private final int floatOffset;

    /** Package-private — only {@link CgBufferFormat.Builder} may instantiate. */
    /* package */ CgBufferField(String name, CgGpuType type, int byteOffset) {
        this.name        = name;
        this.type        = type;
        this.byteOffset  = byteOffset;
        this.floatOffset = byteOffset / 4;
    }

    /** Returns the field name as declared in the builder (e.g. {@code "modelMatrix"}). */
    public String getName() {
        return name;
    }

    /** Returns the GPU type of this field (e.g. {@link CgGpuType#MAT4}). */
    public CgGpuType getType() {
        return type;
    }

    /** Returns the byte offset of this field within one record of the owning format. */
    public int getByteOffset() {
        return byteOffset;
    }

    /**
     * Returns the float offset of this field within one record ({@code byteOffset / 4}).
     * Valid for all types in {@link CgGpuType} because every type has a byte offset that
     * is a multiple of 4.
     */
    public int getFloatOffset() {
        return floatOffset;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CgBufferField that = (CgBufferField) o;
        return byteOffset == that.byteOffset && type == that.type && name.equals(that.name);
    }

    @Override
    public int hashCode() {
        int result = name.hashCode();
        result = 31 * result + type.hashCode();
        result = 31 * result + byteOffset;
        return result;
    }

    @Override
    public String toString() {
        return "CgBufferField{" + name + ": " + type.getGlslName()
                + " @offset=" + byteOffset + " (" + type.getFloatCount() + " floats)}";
    }
}
