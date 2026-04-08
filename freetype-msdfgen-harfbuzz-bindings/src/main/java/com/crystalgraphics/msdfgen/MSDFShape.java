package com.crystalgraphics.msdfgen;

/**
 * Represents an MSDFgen Shape - a 2D vector shape composed of contours.
 * Manages native memory - must be {@link #free()}'d when no longer needed.
 */
public final class MSDFShape {

    private long nativeHandle;
    private boolean freed;

    MSDFShape(long nativeHandle) {
        this.nativeHandle = nativeHandle;
        this.freed = false;
    }

    public static MSDFShape create() {
        long handle = MSDFNative.nShapeAlloc();
        if (handle == 0) {
            throw new MSDFException("Failed to allocate shape");
        }
        return new MSDFShape(handle);
    }

    public MSDFContour addContour() {
        checkNotFreed();
        long contourHandle = MSDFNative.nShapeAddContour(nativeHandle);
        if (contourHandle == 0) {
            throw new MSDFException("Failed to add contour to shape");
        }
        return new MSDFContour(contourHandle, false);
    }

    public int getContourCount() {
        checkNotFreed();
        return MSDFNative.nShapeGetContourCount(nativeHandle);
    }

    public MSDFContour getContour(int index) {
        checkNotFreed();
        long contourHandle = MSDFNative.nShapeGetContour(nativeHandle, index);
        if (contourHandle == 0) {
            throw new MSDFException("Failed to get contour at index " + index);
        }
        return new MSDFContour(contourHandle, false);
    }

    public void removeContour(MSDFContour contour) {
        checkNotFreed();
        MSDFResult.check(MSDFNative.nShapeRemoveContour(nativeHandle, contour.getNativeHandle()));
    }

    public int getEdgeCount() {
        checkNotFreed();
        return MSDFNative.nShapeGetEdgeCount(nativeHandle);
    }

    public void normalize() {
        checkNotFreed();
        MSDFResult.check(MSDFNative.nShapeNormalize(nativeHandle));
    }

    public boolean validate() {
        checkNotFreed();
        return MSDFNative.nShapeValidate(nativeHandle) != 0;
    }

    public void orientContours() {
        checkNotFreed();
        MSDFResult.check(MSDFNative.nShapeOrientContours(nativeHandle));
    }

    public void edgeColoringSimple(double angleThreshold) {
        checkNotFreed();
        MSDFResult.check(MSDFNative.nShapeEdgeColorsSimple(nativeHandle, angleThreshold));
    }

    public void edgeColoringInkTrap(double angleThreshold) {
        checkNotFreed();
        MSDFResult.check(MSDFNative.nShapeEdgeColorsInkTrap(nativeHandle, angleThreshold));
    }

    public void edgeColoringByDistance(double angleThreshold) {
        checkNotFreed();
        MSDFResult.check(MSDFNative.nShapeEdgeColorsByDistance(nativeHandle, angleThreshold));
    }

    public int getYAxisOrientation() {
        checkNotFreed();
        return MSDFNative.nShapeGetYAxisOrientation(nativeHandle);
    }

    public void setYAxisOrientation(int orientation) {
        checkNotFreed();
        MSDFResult.check(MSDFNative.nShapeSetYAxisOrientation(nativeHandle, orientation));
    }

    /**
     * Returns shape bounds as {left, bottom, right, top}.
     */
    public double[] getBounds() {
        checkNotFreed();
        double[] bounds = new double[4];
        MSDFResult.check(MSDFNative.nShapeBound(nativeHandle, bounds));
        return bounds;
    }

    public double[] getBoundsMiters(double[] boundsInOut, double border, double miterLimit, int polarity) {
        checkNotFreed();
        double[] bounds = new double[4];
        System.arraycopy(boundsInOut, 0, bounds, 0, 4);
        MSDFResult.check(MSDFNative.nShapeBoundMiters(nativeHandle, bounds, border, miterLimit, polarity));
        return bounds;
    }

    public double getOneShotDistance(double originX, double originY) {
        checkNotFreed();
        return MSDFNative.nShapeOneShotDistance(nativeHandle, originX, originY);
    }

    public void free() {
        if (!freed) {
            MSDFNative.nShapeFree(nativeHandle);
            freed = true;
            nativeHandle = 0;
        }
    }

    public boolean isFreed() {
        return freed;
    }

    long getNativeHandle() {
        checkNotFreed();
        return nativeHandle;
    }

    private void checkNotFreed() {
        if (freed) {
            throw new IllegalStateException("Shape has been freed");
        }
    }

    @Override
    protected void finalize() throws Throwable {
        if (!freed) {
            free();
        }
        super.finalize();
    }
}
