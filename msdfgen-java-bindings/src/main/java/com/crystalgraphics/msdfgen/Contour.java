package com.crystalgraphics.msdfgen;

public final class Contour {

    private long nativeHandle;
    private final boolean owned;
    private boolean freed;

    Contour(long nativeHandle, boolean owned) {
        this.nativeHandle = nativeHandle;
        this.owned = owned;
        this.freed = false;
    }

    public static Contour create() {
        long handle = MsdfNative.nContourAlloc();
        if (handle == 0) {
            throw new MsdfException("Failed to allocate contour");
        }
        return new Contour(handle, true);
    }

    public void addEdge(Segment segment) {
        checkNotFreed();
        MsdfResult.check(MsdfNative.nContourAddEdge(nativeHandle, segment.getNativeHandle()));
    }

    public void removeEdge(Segment segment) {
        checkNotFreed();
        MsdfResult.check(MsdfNative.nContourRemoveEdge(nativeHandle, segment.getNativeHandle()));
    }

    public int getEdgeCount() {
        checkNotFreed();
        return MsdfNative.nContourGetEdgeCount(nativeHandle);
    }

    public Segment getEdge(int index) {
        checkNotFreed();
        long segHandle = MsdfNative.nContourGetEdge(nativeHandle, index);
        if (segHandle == 0) {
            throw new MsdfException("Failed to get edge at index " + index);
        }
        return new Segment(segHandle, false);
    }

    public int getWinding() {
        checkNotFreed();
        return MsdfNative.nContourGetWinding(nativeHandle);
    }

    public void reverse() {
        checkNotFreed();
        MsdfResult.check(MsdfNative.nContourReverse(nativeHandle));
    }

    public double[] getBounds() {
        checkNotFreed();
        double[] bounds = new double[4];
        MsdfResult.check(MsdfNative.nContourBound(nativeHandle, bounds));
        return bounds;
    }

    public double[] getBoundsMiters(double[] boundsInOut, double border, double miterLimit, int polarity) {
        checkNotFreed();
        double[] bounds = new double[4];
        System.arraycopy(boundsInOut, 0, bounds, 0, 4);
        MsdfResult.check(MsdfNative.nContourBoundMiters(nativeHandle, bounds, border, miterLimit, polarity));
        return bounds;
    }

    public void free() {
        if (!freed && owned) {
            MsdfNative.nContourFree(nativeHandle);
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
            throw new IllegalStateException("Contour has been freed");
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
