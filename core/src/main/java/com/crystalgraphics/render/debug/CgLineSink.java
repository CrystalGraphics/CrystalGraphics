package com.crystalgraphics.render.debug;

/**
 * Where world-space segments and triangles go: a debug batch, or a shape's outline being written. Implement
 * {@link #line} alone for lines; {@link #triangle} and {@link #piece} are for see-through faces.
 *
 * <pre>{@code
 * CgShapeLines.box(bounds, (ax, ay, az, bx, by, bz) -> segments.add(ax, ay, az, bx, by, bz));
 * }</pre>
 */
@FunctionalInterface
public interface CgLineSink {
    void line(double ax, double ay, double az, double bx, double by, double bz);

    /** A triangle of a shape's surface; a sink of lines alone leaves it. */
    default void triangle(double ax, double ay, double az, double bx, double by, double bz, double cx, double cy,
            double cz) {
    }

    /** The triangles after this, up to the next, are one piece: a shape, or one child of a compound. */
    default void piece() {
    }
}
