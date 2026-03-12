package com.msdfgen;

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
     */
    public static Transform autoFrame(Shape shape, int bitmapWidth, int bitmapHeight, double pxRange) {
        double[] bounds = shape.getBounds();
        double l = bounds[0], b = bounds[1], r = bounds[2], t = bounds[3];

        double shapeWidth = r - l;
        double shapeHeight = t - b;

        if (shapeWidth <= 0 || shapeHeight <= 0) {
            return new Transform().scale(1.0).translate(0, 0).range(pxRange);
        }

        double scaleX = (bitmapWidth - 2.0 * pxRange) / shapeWidth;
        double scaleY = (bitmapHeight - 2.0 * pxRange) / shapeHeight;
        double scale = Math.min(scaleX, scaleY);

        double tx = (bitmapWidth / scale - shapeWidth) / 2.0 - l;
        double ty = (bitmapHeight / scale - shapeHeight) / 2.0 - b;

        double range = pxRange / scale;

        return new Transform()
            .scale(scale)
            .translate(tx, ty)
            .range(-range, range);
    }
}
