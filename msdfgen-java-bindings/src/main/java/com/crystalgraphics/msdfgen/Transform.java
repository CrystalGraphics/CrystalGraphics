package com.crystalgraphics.msdfgen;

public final class Transform {

    private double scaleX = 1.0;
    private double scaleY = 1.0;
    private double translateX = 0.0;
    private double translateY = 0.0;
    private double rangeLower = 0.0;
    private double rangeUpper = 1.0;

    public Transform() {}

    public Transform scale(double sx, double sy) {
        this.scaleX = sx;
        this.scaleY = sy;
        return this;
    }

    public Transform scale(double uniformScale) {
        return scale(uniformScale, uniformScale);
    }

    public Transform translate(double tx, double ty) {
        this.translateX = tx;
        this.translateY = ty;
        return this;
    }

    public Transform range(double lower, double upper) {
        this.rangeLower = lower;
        this.rangeUpper = upper;
        return this;
    }

    public Transform range(double pxRange) {
        return range(-pxRange, pxRange);
    }

    public double getScaleX() { return scaleX; }
    public double getScaleY() { return scaleY; }
    public double getTranslateX() { return translateX; }
    public double getTranslateY() { return translateY; }
    public double getRangeLower() { return rangeLower; }
    public double getRangeUpper() { return rangeUpper; }

    /**
     * Creates a transform that automatically frames a shape in the given bitmap dimensions
     * with the specified pixel range for SDF distance.
     * <p>
     * The pxRange parameter specifies the <b>full width</b> of the distance range in pixels
     * (matching msdfgen's {@code -pxrange} CLI argument and {@code Range(symmetricalWidth)}
     * constructor). Half of this range is allocated as margin on each side of the shape.
     * <p>
     * This method matches the autoFrame logic in msdfgen's main.cpp:
     * <ul>
     *   <li>Frame is reduced by pxRange (not 2*pxRange) because Range(pxRange).lower = -pxRange/2,
     *       and the frame adjustment is {@code frame += 2*pxRange.lower = frame - pxRange}</li>
     *   <li>Scale is computed to fit the shape within the reduced frame</li>
     *   <li>Translation centers the shape and offsets by pxRange/2 in pixel space</li>
     *   <li>Distance range is [-pxRange/2/scale, +pxRange/2/scale] in shape units</li>
     * </ul>
     */
    public static Transform autoFrame(Shape shape, int bitmapWidth, int bitmapHeight, double pxRange) {
        double[] bounds = shape.getBounds();
        double l = bounds[0], b = bounds[1], r = bounds[2], t = bounds[3];

        double shapeWidth = r - l;
        double shapeHeight = t - b;

        if (shapeWidth <= 0 || shapeHeight <= 0) {
            l = 0; b = 0; r = 1; t = 1;
            shapeWidth = 1; shapeHeight = 1;
        }

        // C++ Range(pxRange) constructor: lower = -pxRange/2, upper = +pxRange/2
        // frame += 2 * pxRange.lower  =>  frame = (width - pxRange, height - pxRange)
        double frameX = bitmapWidth - pxRange;
        double frameY = bitmapHeight - pxRange;

        if (frameX <= 0 || frameY <= 0) {
            throw new IllegalArgumentException(
                "Cannot fit the specified pixel range: pxRange " + pxRange
                + " is too large for bitmap dimensions " + bitmapWidth + "x" + bitmapHeight);
        }

        // Compute uniform scale to fit shape within frame, preserving aspect ratio
        double scale;
        double tx, ty;

        if (shapeWidth * frameY < shapeHeight * frameX) {
            // Shape is taller than wide relative to frame — scale is limited by height
            scale = frameY / shapeHeight;
            tx = 0.5 * (frameX / frameY * shapeHeight - shapeWidth) - l;
            ty = -b;
        } else {
            // Shape is wider than tall relative to frame — scale is limited by width
            scale = frameX / shapeWidth;
            tx = -l;
            ty = 0.5 * (frameY / frameX * shapeWidth - shapeHeight) - b;
        }

        // C++ translate -= pxRange.lower / scale  =>  translate += (pxRange/2) / scale
        double pxRangeOffset = (pxRange / 2.0) / scale;
        tx += pxRangeOffset;
        ty += pxRangeOffset;

        // C++ range = pxRange / scale, where pxRange is Range(-pxRange/2, +pxRange/2)
        // So range.lower = -pxRange/2/scale, range.upper = +pxRange/2/scale
        double halfRangeInShapeUnits = (pxRange / 2.0) / scale;

        return new Transform()
            .scale(scale)
            .translate(tx, ty)
            .range(-halfRangeInShapeUnits, halfRangeInShapeUnits);
    }
}
