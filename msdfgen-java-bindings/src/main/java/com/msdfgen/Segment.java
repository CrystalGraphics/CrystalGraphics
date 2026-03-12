package com.msdfgen;

public final class Segment {

    private long nativeHandle;
    private final boolean owned;
    private boolean freed;

    Segment(long nativeHandle, boolean owned) {
        this.nativeHandle = nativeHandle;
        this.owned = owned;
        this.freed = false;
    }

    public static Segment createLinear() {
        return create(MsdfConstants.SEGMENT_TYPE_LINEAR);
    }

    public static Segment createQuadratic() {
        return create(MsdfConstants.SEGMENT_TYPE_QUADRATIC);
    }

    public static Segment createCubic() {
        return create(MsdfConstants.SEGMENT_TYPE_CUBIC);
    }

    public static Segment create(int type) {
        if (!SegmentType.isValid(type)) {
            throw new IllegalArgumentException("Invalid segment type: " + type
                + ". Must be LINEAR(0), QUADRATIC(1), or CUBIC(2)");
        }
        long handle = MsdfNative.nSegmentAlloc(type);
        if (handle == 0) {
            throw new MsdfException("Failed to allocate segment of type " + SegmentType.name(type));
        }
        return new Segment(handle, true);
    }

    public int getType() {
        checkNotFreed();
        return MsdfNative.nSegmentGetType(nativeHandle);
    }

    public int getPointCount() {
        checkNotFreed();
        return MsdfNative.nSegmentGetPointCount(nativeHandle);
    }

    /**
     * Returns the control point at the given index as {x, y}.
     */
    public double[] getPoint(int index) {
        checkNotFreed();
        double[] point = new double[2];
        MsdfResult.check(MsdfNative.nSegmentGetPoint(nativeHandle, index, point));
        return point;
    }

    public void setPoint(int index, double x, double y) {
        checkNotFreed();
        MsdfResult.check(MsdfNative.nSegmentSetPoint(nativeHandle, index, x, y));
    }

    public int getColor() {
        checkNotFreed();
        return MsdfNative.nSegmentGetColor(nativeHandle);
    }

    public void setColor(int color) {
        checkNotFreed();
        MsdfResult.check(MsdfNative.nSegmentSetColor(nativeHandle, color));
    }

    /**
     * Returns the direction at the parametric position as {x, y}.
     */
    public double[] getDirection(double param) {
        checkNotFreed();
        double[] dir = new double[2];
        MsdfResult.check(MsdfNative.nSegmentGetDirection(nativeHandle, param, dir));
        return dir;
    }

    public double[] getDirectionChange(double param) {
        checkNotFreed();
        double[] dc = new double[2];
        MsdfResult.check(MsdfNative.nSegmentGetDirectionChange(nativeHandle, param, dc));
        return dc;
    }

    /**
     * Returns the point on the edge at the parametric position as {x, y}.
     */
    public double[] point(double param) {
        checkNotFreed();
        double[] pt = new double[2];
        MsdfResult.check(MsdfNative.nSegmentPoint(nativeHandle, param, pt));
        return pt;
    }

    public double[] getBounds() {
        checkNotFreed();
        double[] bounds = new double[4];
        MsdfResult.check(MsdfNative.nSegmentBound(nativeHandle, bounds));
        return bounds;
    }

    public void moveStartPoint(double x, double y) {
        checkNotFreed();
        MsdfResult.check(MsdfNative.nSegmentMoveStartPoint(nativeHandle, x, y));
    }

    public void moveEndPoint(double x, double y) {
        checkNotFreed();
        MsdfResult.check(MsdfNative.nSegmentMoveEndPoint(nativeHandle, x, y));
    }

    public void free() {
        if (!freed && owned) {
            MsdfNative.nSegmentFree(nativeHandle);
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
