package com.crystalgraphics.msdfgen;

/**
 * Represents an MSDFgen bitmap that holds SDF/MSDF pixel data.
 * Manages native memory - must be {@link #free()}'d when no longer needed.
 */
public final class MSDFBitmap {

    private long nativeHandle;
    private final int type;
    private final int width;
    private final int height;
    private boolean freed;

    private MSDFBitmap(long nativeHandle, int type, int width, int height) {
        this.nativeHandle = nativeHandle;
        this.type = type;
        this.width = width;
        this.height = height;
        this.freed = false;
    }

    public static MSDFBitmap allocSdf(int width, int height) {
        return alloc(MSDFConstants.BITMAP_TYPE_SDF, width, height);
    }

    public static MSDFBitmap allocPsdf(int width, int height) {
        return alloc(MSDFConstants.BITMAP_TYPE_PSDF, width, height);
    }

    public static MSDFBitmap allocMsdf(int width, int height) {
        return alloc(MSDFConstants.BITMAP_TYPE_MSDF, width, height);
    }

    public static MSDFBitmap allocMtsdf(int width, int height) {
        return alloc(MSDFConstants.BITMAP_TYPE_MTSDF, width, height);
    }

    public static MSDFBitmap alloc(int type, int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Bitmap dimensions must be positive: " + width + "x" + height);
        }
        long[] handleOut = new long[1];
        int result = MSDFNative.nBitmapAlloc(type, width, height, handleOut);
        MSDFResult.check(result);
        return new MSDFBitmap(handleOut[0], type, width, height);
    }

    public int getType() {
        return type;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public int getChannelCount() {
        return MSDFConstants.channelCountForType(type);
    }

    /**
     * Copies pixel data from native memory into a float array.
     * Array is laid out as [y][x][channel] with row-major order.
     *
     * @return float array of size width * height * channelCount
     */
    public float[] getPixelData() {
        checkNotFreed();
        int channels = getChannelCount();
        float[] pixels = new float[width * height * channels];
        int result = MSDFNative.nBitmapGetPixels(nativeHandle, type, width, height, pixels);
        MSDFResult.check(result);
        return pixels;
    }

    public long getByteSize() {
        checkNotFreed();
        return MSDFNative.nBitmapGetByteSize(nativeHandle, type, width, height);
    }

    public long getPixelPointer() {
        checkNotFreed();
        long ptr = MSDFNative.nBitmapGetPixelPointer(nativeHandle, type);
        if (ptr == 0) {
            throw new MSDFException("Failed to get pixel pointer");
        }
        return ptr;
    }

    public void free() {
        if (!freed) {
            MSDFNative.nBitmapFree(nativeHandle, type);
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
            throw new IllegalStateException("Bitmap has been freed");
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
