package com.crystalgraphics.text.atlas.packing;

import java.util.Objects;

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
 * @param x  X position of the top-left corner within the bin (pixels). 
-- GETTER --
Returns the X position of the top-left corner within the bin. 
 * @param y  Y position of the top-left corner within the bin (pixels). 
-- GETTER --
Returns the Y position of the top-left corner within the bin. 
 * @param width  Width of the packed rectangle (pixels). 
-- GETTER --
Returns the width of the packed rectangle. 
 * @param height  Height of the packed rectangle (pixels). 
-- GETTER --
Returns the height of the packed rectangle. 
 * @param id  Caller-provided identifier (e.g., CgGlyphKey). 
-- GETTER --
Returns the caller-provided identifier. 
 */
public record PackedRect(int x, int y, int width, int height, Object id) {

    /**
     * Constructs a packed rectangle.
     *
     * @param x      X position in the bin
     * @param y      Y position in the bin
     * @param width  rectangle width
     * @param height rectangle height
     * @param id     caller-provided identifier
     */
    public PackedRect {
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
        return Objects.equals(id, that.id);
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
