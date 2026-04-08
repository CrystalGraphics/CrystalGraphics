package com.crystalgraphics.msdfgen;

public final class MSDFSegment {

    private long nativeHandle;
    private final boolean owned;
    private boolean freed;

    MSDFSegment(long nativeHandle, boolean owned) {
        this.nativeHandle = nativeHandle;
        this.owned = owned;
        this.freed = false;
    }

    public static MSDFSegment createLinear() {
        return create(MSDFConstants.SEGMENT_TYPE_LINEAR);
    }

    public static MSDFSegment createQuadratic() {
        return create(MSDFConstants.SEGMENT_TYPE_QUADRATIC);
    }

    public static MSDFSegment createCubic() {
        return create(MSDFConstants.SEGMENT_TYPE_CUBIC);
    }

    public static MSDFSegment create(int type) {
        if (!MSDFSegmentType.isValid(type)) {
            throw new IllegalArgumentException("Invalid segment type: " + type
                + ". Must be LINEAR(0), QUADRATIC(1), or CUBIC(2)");
        }
        long handle = MSDFNative.nSegmentAlloc(type);
        if (handle == 0) {
            throw new MSDFException("Failed to allocate segment of type " + MSDFSegmentType.name(type));
        }
        return new MSDFSegment(handle, true);
    }

    public int getType() {
        checkNotFreed();
        return MSDFNative.nSegmentGetType(nativeHandle);
    }

    public int getPointCount() {
        checkNotFreed();
        return MSDFNative.nSegmentGetPointCount(nativeHandle);
    }

    /**
     * Returns the control point at the given index as {x, y}.
     */
    public double[] getPoint(int index) {
        checkNotFreed();
        double[] point = new double[2];
        MSDFResult.check(MSDFNative.nSegmentGetPoint(nativeHandle, index, point));
        return point;
    }

    public void setPoint(int index, double x, double y) {
        checkNotFreed();
        MSDFResult.check(MSDFNative.nSegmentSetPoint(nativeHandle, index, x, y));
    }

    public int getColor() {
        checkNotFreed();
        return MSDFNative.nSegmentGetColor(nativeHandle);
    }

    public void setColor(int color) {
        checkNotFreed();
        MSDFResult.check(MSDFNative.nSegmentSetColor(nativeHandle, color));
    }

    /**
     * Returns the direction at the parametric position as {x, y}.
     */
    public double[] getDirection(double param) {
        checkNotFreed();
        double[] dir = new double[2];
        MSDFResult.check(MSDFNative.nSegmentGetDirection(nativeHandle, param, dir));
        return dir;
    }

    public double[] getDirectionChange(double param) {
        checkNotFreed();
        double[] dc = new double[2];
        MSDFResult.check(MSDFNative.nSegmentGetDirectionChange(nativeHandle, param, dc));
        return dc;
    }

    /**
     * Returns the point on the edge at the parametric position as {x, y}.
     */
    public double[] point(double param) {
        checkNotFreed();
        double[] pt = new double[2];
        MSDFResult.check(MSDFNative.nSegmentPoint(nativeHandle, param, pt));
        return pt;
    }

    public double[] getBounds() {
        checkNotFreed();
        double[] bounds = new double[4];
        MSDFResult.check(MSDFNative.nSegmentBound(nativeHandle, bounds));
        return bounds;
    }

    public void moveStartPoint(double x, double y) {
        checkNotFreed();
        MSDFResult.check(MSDFNative.nSegmentMoveStartPoint(nativeHandle, x, y));
    }

    public void moveEndPoint(double x, double y) {
        checkNotFreed();
        MSDFResult.check(MSDFNative.nSegmentMoveEndPoint(nativeHandle, x, y));
    }

    public void free() {
        if (!freed && owned) {
            MSDFNative.nSegmentFree(nativeHandle);
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
            throw new IllegalStateException("Segment has been freed");
        }
    }

    @Override
    protected void finalize() throws Throwable {
        if (!freed && owned) {
            free();
        }
        super.finalize();
    }
}
