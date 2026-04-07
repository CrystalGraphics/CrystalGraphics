package io.github.somehussar.crystalgraphics.text.atlas.packing;

/**
 * Immutable value representing a rectangle packed into a bin atlas.
 *
 * <p>Stores the position and dimensions of the packed rectangle within the bin,
 * along with a caller-provided identifier (e.g., a {@code CgGlyphKey}) for
 * associating the packed region with its source data.</p>
 *
 * <p>Instances are created by {@link MaxRectsPacker#insert(int, int, Object)}
 * and should not be constructed directly by callers.</p>
 *
 * @see MaxRectsPacker
 */
public final class PackedRect {

    /** X position of the top-left corner within the bin (pixels). */
    private final int x;

    /** Y position of the top-left corner within the bin (pixels). */
    private final int y;

    /** Width of the packed rectangle (pixels). */
    private final int width;

    /** Height of the packed rectangle (pixels). */
    private final int height;

    /** Caller-provided identifier (e.g., CgGlyphKey). */
    private final Object id;

    /**
     * Constructs a packed rectangle.
     *
     * @param x      X position in the bin
     * @param y      Y position in the bin
     * @param width  rectangle width
     * @param height rectangle height
     * @param id     caller-provided identifier
     */
    public PackedRect(int x, int y, int width, int height, Object id) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.id = id;
    }

    /** Returns the X position of the top-left corner within the bin. */
    public int getX() {
        return x;
    }

    /** Returns the Y position of the top-left corner within the bin. */
    public int getY() {
        return y;
    }

    /** Returns the width of the packed rectangle. */
    public int getWidth() {
        return width;
    }

    /** Returns the height of the packed rectangle. */
    public int getHeight() {
        return height;
    }

    /** Returns the caller-provided identifier. */
    public Object getId() {
        return id;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        PackedRect that = (PackedRect) o;

        if (x != that.x) return false;
        if (y != that.y) return false;
        if (width != that.width) return false;
        if (height != that.height) return false;
        return id != null ? id.equals(that.id) : that.id == null;
    }

    @Override
    public int hashCode() {
        int result = x;
        result = 31 * result + y;
        result = 31 * result + width;
        result = 31 * result + height;
        result = 31 * result + (id != null ? id.hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        return "PackedRect{" +
                "x=" + x +
                ", y=" + y +
                ", width=" + width +
                ", height=" + height +
                ", id=" + id +
                '}';
    }
}
