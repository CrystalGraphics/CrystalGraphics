package com.crystalgraphics.api.vector;

/**
 * A box's corner radii, each corner elliptical: CSS's {@code border-radius} with its slash form, Skia's
 * {@code SkRRect}. Corners in CSS order: top-left, top-right, bottom-right, bottom-left.
 *
 * <pre>{@code
 * CgCornerRadii.of(8f);                          // every corner a circle of radius 8
 * CgCornerRadii.of(8f, 8f, 0f, 0f);              // top corners round, bottom square
 * CgCornerRadii.elliptical(16f, 8f);             // every corner 16 across, 8 down
 * }</pre>
 *
 * <ul>
 *   <li>Radii too large for the box are scaled down together when drawn, as CSS does, so corners never overlap.</li>
 *   <li>A corner with either radius 0 is square.</li>
 * </ul>
 *
 * @param topLeftX     top-left, across
 * @param topLeftY     top-left, down
 * @param topRightX    top-right, across
 * @param topRightY    top-right, down
 * @param bottomRightX bottom-right, across
 * @param bottomRightY bottom-right, down
 * @param bottomLeftX  bottom-left, across
 * @param bottomLeftY  bottom-left, down
 */
public record CgCornerRadii(float topLeftX, float topLeftY, float topRightX, float topRightY,
                            float bottomRightX, float bottomRightY, float bottomLeftX, float bottomLeftY) {

    /** Every corner square. */
    public static final CgCornerRadii NONE = of(0f);

    public CgCornerRadii {
        for (float r : new float[]{topLeftX, topLeftY, topRightX, topRightY, bottomRightX, bottomRightY, bottomLeftX,
                bottomLeftY}) {
            if (!(r >= 0f) || Float.isInfinite(r)) throw new IllegalArgumentException("radii are finite and >= 0: " + r);
        }
    }

    /** Every corner a circle of {@code radius}. */
    public static CgCornerRadii of(float radius) {
        return new CgCornerRadii(radius, radius, radius, radius, radius, radius, radius, radius);
    }

    /** Each corner a circle: top-left, top-right, bottom-right, bottom-left. */
    public static CgCornerRadii of(float topLeft, float topRight, float bottomRight, float bottomLeft) {
        return new CgCornerRadii(topLeft, topLeft, topRight, topRight, bottomRight, bottomRight, bottomLeft, bottomLeft);
    }

    /** Every corner an ellipse {@code across} by {@code down}. */
    public static CgCornerRadii elliptical(float across, float down) {
        return new CgCornerRadii(across, down, across, down, across, down, across, down);
    }

    /** Whether every corner is square. */
    /**
     * The radii of the curve inside a border of these widths: each corner less the border beside it, never below 0
     * (CSS Backgrounds 3, the inner border radius). What a box's padding edge is rounded by.
     *
     * <pre>{@code
     * CgCornerRadii inner = CgCornerRadii.of(8f).inset(2f, 2f, 4f, 2f);   // top, right, bottom, left
     * }</pre>
     */
    public CgCornerRadii inset(float top, float right, float bottom, float left) {
        return new CgCornerRadii(Math.max(0f, topLeftX - left), Math.max(0f, topLeftY - top),
                Math.max(0f, topRightX - right), Math.max(0f, topRightY - top),
                Math.max(0f, bottomRightX - right), Math.max(0f, bottomRightY - bottom),
                Math.max(0f, bottomLeftX - left), Math.max(0f, bottomLeftY - bottom));
    }

    public boolean isNone() {
        return (topLeftX == 0f || topLeftY == 0f) && (topRightX == 0f || topRightY == 0f)
                && (bottomRightX == 0f || bottomRightY == 0f) && (bottomLeftX == 0f || bottomLeftY == 0f);
    }

    /** Whether every corner is a circle of the same radius. */
    public boolean isUniformCircle() {
        return topLeftX == topLeftY && topLeftX == topRightX && topLeftX == topRightY && topLeftX == bottomRightX
                && topLeftX == bottomRightY && topLeftX == bottomLeftX && topLeftX == bottomLeftY;
    }

    /** Every radius times {@code s}. */
    public CgCornerRadii scaled(float s) {
        return new CgCornerRadii(topLeftX * s, topLeftY * s, topRightX * s, topRightY * s, bottomRightX * s,
                bottomRightY * s, bottomLeftX * s, bottomLeftY * s);
    }

    /**
     * These radii fitted to a {@code width} by {@code height} box: all scaled by one factor when two on a side would
     * overlap (CSS Backgrounds 3, section 5.5).
     */
    public CgCornerRadii fitted(float width, float height) {
        float f = 1f;
        f = Math.min(f, ratio(width, topLeftX + topRightX));
        f = Math.min(f, ratio(width, bottomLeftX + bottomRightX));
        f = Math.min(f, ratio(height, topLeftY + bottomLeftY));
        f = Math.min(f, ratio(height, topRightY + bottomRightY));
        return f < 1f ? scaled(f) : this;
    }

    private static float ratio(float side, float sum) {
        return sum > side && sum > 0f ? Math.max(0f, side) / sum : 1f;
    }
}
