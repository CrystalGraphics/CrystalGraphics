package com.msdfgen;

/**
 * Represents an MSDFgen Shape - a 2D vector shape composed of contours.
 * Manages native memory - must be {@link #free()}'d when no longer needed.
 */
public final class Shape {

    private long nativeHandle;
    private boolean freed;

    Shape(long nativeHandle) {
        this.nativeHandle = nativeHandle;
        this.freed = false;
    }

    public static Shape create() {
        long handle = MsdfNative.nShapeAlloc();
        if (handle == 0) {
            throw new MsdfException("Failed to allocate shape");
        }
        return new Shape(handle);
    }

    public Contour addContour() {
        checkNotFreed();
        long contourHandle = MsdfNative.nShapeAddContour(nativeHandle);
        if (contourHandle == 0) {
            throw new MsdfException("Failed to add contour to shape");
        }
        return new Contour(contourHandle, false);
    }

    public int getContourCount() {
        checkNotFreed();
        return MsdfNative.nShapeGetContourCount(nativeHandle);
    }

    public Contour getContour(int index) {
        checkNotFreed();
        long contourHandle = MsdfNative.nShapeGetContour(nativeHandle, index);
        if (contourHandle == 0) {
            throw new MsdfException("Failed to get contour at index " + index);
        }
        return new Contour(contourHandle, false);
    }

    public void removeContour(Contour contour) {
        checkNotFreed();
        MsdfResult.check(MsdfNative.nShapeRemoveContour(nativeHandle, contour.getNativeHandle()));
    }

    public int getEdgeCount() {
        checkNotFreed();
        return MsdfNative.nShapeGetEdgeCount(nativeHandle);
    }

    public void normalize() {
        checkNotFreed();
        MsdfResult.check(MsdfNative.nShapeNormalize(nativeHandle));
    }

    public boolean validate() {
        checkNotFreed();
        return MsdfNative.nShapeValidate(nativeHandle) != 0;
    }

    public void orientContours() {
        checkNotFreed();
        MsdfResult.check(MsdfNative.nShapeOrientContours(nativeHandle));
    }

    public void edgeColoringSimple(double angleThreshold) {
        checkNotFreed();
        MsdfResult.check(MsdfNative.nShapeEdgeColorsSimple(nativeHandle, angleThreshold));
    }

    public void edgeColoringInkTrap(double angleThreshold) {
        checkNotFreed();
        MsdfResult.check(MsdfNative.nShapeEdgeColorsInkTrap(nativeHandle, angleThreshold));
    }

    public void edgeColoringByDistance(double angleThreshold) {
        checkNotFreed();
        MsdfResult.check(MsdfNative.nShapeEdgeColorsByDistance(nativeHandle, angleThreshold));
    }

    public int getYAxisOrientation() {
        checkNotFreed();
        return MsdfNative.nShapeGetYAxisOrientation(nativeHandle);
    }

    public void setYAxisOrientation(int orientation) {
        checkNotFreed();
        MsdfResult.check(MsdfNative.nShapeSetYAxisOrientation(nativeHandle, orientation));
    }

    /**
     * Returns shape bounds as {left, bottom, right, top}.
     */
    public double[] getBounds() {
        checkNotFreed();
        double[] bounds = new double[4];
        MsdfResult.check(MsdfNative.nShapeBound(nativeHandle, bounds));
        return bounds;
    }

    public double[] getBoundsMiters(double[] boundsInOut, double border, double miterLimit, int polarity) {
        checkNotFreed();
        double[] bounds = new double[4];
        System.arraycopy(boundsInOut, 0, bounds, 0, 4);
        MsdfResult.check(MsdfNative.nShapeBoundMiters(nativeHandle, bounds, border, miterLimit, polarity));
        return bounds;
    }

    public double getOneShotDistance(double originX, double originY) {
        checkNotFreed();
        return MsdfNative.nShapeOneShotDistance(nativeHandle, originX, originY);
    }

    public void free() {
        if (!freed) {
            MsdfNative.nShapeFree(nativeHandle);
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
