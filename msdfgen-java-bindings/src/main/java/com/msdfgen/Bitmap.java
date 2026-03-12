package com.msdfgen;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Represents an MSDFgen bitmap that holds SDF/MSDF pixel data.
 * Manages native memory - must be {@link #free()}'d when no longer needed.
 */
public final class Bitmap {

    private long nativeHandle;
    private final int type;
    private final int width;
    private final int height;
    private boolean freed;

    private Bitmap(long nativeHandle, int type, int width, int height) {
        this.nativeHandle = nativeHandle;
        this.type = type;
        this.width = width;
        this.height = height;
        this.freed = false;
    }

    public static Bitmap allocSdf(int width, int height) {
        return alloc(MsdfConstants.BITMAP_TYPE_SDF, width, height);
    }

    public static Bitmap allocPsdf(int width, int height) {
        return alloc(MsdfConstants.BITMAP_TYPE_PSDF, width, height);
    }

    public static Bitmap allocMsdf(int width, int height) {
        return alloc(MsdfConstants.BITMAP_TYPE_MSDF, width, height);
    }

    public static Bitmap allocMtsdf(int width, int height) {
        return alloc(MsdfConstants.BITMAP_TYPE_MTSDF, width, height);
    }

    public static Bitmap alloc(int type, int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Bitmap dimensions must be positive: " + width + "x" + height);
        }
        long[] handleOut = new long[1];
        int result = MsdfNative.nBitmapAlloc(type, width, height, handleOut);
        MsdfResult.check(result);
        return new Bitmap(handleOut[0], type, width, height);
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
        return MsdfConstants.channelCountForType(type);
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
        int result = MsdfNative.nBitmapGetPixels(nativeHandle, type, width, height, pixels);
        MsdfResult.check(result);
        return pixels;
    }

    public long getByteSize() {
        checkNotFreed();
        return MsdfNative.nBitmapGetByteSize(nativeHandle, type, width, height);
    }

    public long getPixelPointer() {
        checkNotFreed();
        long ptr = MsdfNative.nBitmapGetPixelPointer(nativeHandle, type);
        if (ptr == 0) {
            throw new MsdfException("Failed to get pixel pointer");
        }
        return ptr;
    }

    public void free() {
        if (!freed) {
            MsdfNative.nBitmapFree(nativeHandle, type);
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
