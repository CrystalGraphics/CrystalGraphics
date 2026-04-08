package com.crystalgraphics.harfbuzz;

import com.crystalgraphics.NativeLoader;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * HarfBuzz font object. Wraps a native hb_font_t pointer.
 * Created from an FT_Face via {@link com.crystalgraphics.text.FreeTypeHarfBuzzIntegration}
 * or directly from a font file.
 * Must be explicitly destroyed via {@link #destroy()}.
 */
public final class HBFont {

    private long nativePtr;

    public HBFont(long nativePtr) {
        this.nativePtr = nativePtr;
    }

    public static HBFont createFromFile(String filePath, int faceIndex) {
        NativeLoader.ensureLoaded();
        long ptr = nCreateFromFile(filePath, faceIndex);
        if (ptr == 0) {
            throw new RuntimeException("Failed to create HarfBuzz font from: " + filePath);
        }
        return new HBFont(ptr);
    }

    public static HBFont createFromFile(String filePath) {
        return createFromFile(filePath, 0);
    }

    public static HBFont createFromMemory(byte[] data, int faceIndex) {
        NativeLoader.ensureLoaded();
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("data must not be null or empty");
        }
        long ptr = nCreateFromMemory(data, data.length, faceIndex);
        if (ptr == 0) {
            throw new RuntimeException("Failed to create HarfBuzz font from memory ("
                    + data.length + " bytes)");
        }
        return new HBFont(ptr);
    }

    public static HBFont createFromMemory(byte[] data) {
        return createFromMemory(data, 0);
    }

    public static HBFont createFromStream(InputStream stream, int faceIndex) throws IOException {
        if (stream == null) {
            throw new IllegalArgumentException("stream must not be null");
        }
        byte[] data = readAllBytes(stream);
        return createFromMemory(data, faceIndex);
    }

    public static HBFont createFromStream(InputStream stream) throws IOException {
        return createFromStream(stream, 0);
    }

    public void setScale(int xScale, int yScale) {
        checkNotDestroyed();
        nSetScale(nativePtr, xScale, yScale);
    }

    public int[] getScale() {
        checkNotDestroyed();
        return nGetScale(nativePtr);
    }

    public void setPpem(int xPpem, int yPpem) {
        checkNotDestroyed();
        nSetPpem(nativePtr, xPpem, yPpem);
    }

    public int[] getPpem() {
        checkNotDestroyed();
        return nGetPpem(nativePtr);
    }

    public void setVariations(String[] axisTags, float[] values) {
        checkNotDestroyed();
        if (axisTags == null || values == null) {
            throw new IllegalArgumentException("axisTags and values must not be null");
        }
        if (axisTags.length != values.length) {
            throw new IllegalArgumentException("axisTags and values length must match");
        }
        if (axisTags.length == 0) {
            return;
        }
        try {
            nSetVariations(nativePtr, axisTags, values);
        } catch (UnsatisfiedLinkError e) {
            throw new UnsupportedOperationException("Variable-font shaping requires updated native bindings", e);
        }
    }

    public long getNativePtr() {
        return nativePtr;
    }

    public void destroy() {
        if (nativePtr != 0) {
            nDestroy(nativePtr);
            nativePtr = 0;
        }
    }

    public boolean isDestroyed() {
        return nativePtr == 0;
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void finalize() throws Throwable {
        try {
            if (nativePtr != 0) {
                System.err.println("[CrystalGraphics] WARNING: HBFont was not destroyed! "
                    + "Call destroy() explicitly to avoid native memory leaks.");
                nDestroy(nativePtr);
                nativePtr = 0;
            }
        } finally {
            super.finalize();
        }
    }

    private void checkNotDestroyed() {
        if (nativePtr == 0) {
            throw new IllegalStateException("HBFont has been destroyed");
        }
    }

    private static native long nCreateFromFile(String filePath, int faceIndex);
    private static native long nCreateFromMemory(byte[] data, int dataLen, int faceIndex);
    private static native void nDestroy(long fontPtr);
    private static native void nSetScale(long fontPtr, int xScale, int yScale);
    private static native int[] nGetScale(long fontPtr);
    private static native void nSetPpem(long fontPtr, int xPpem, int yPpem);
    private static native int[] nGetPpem(long fontPtr);
    private static native void nSetVariations(long fontPtr, String[] axisTags, float[] values);

    private static byte[] readAllBytes(InputStream stream) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = stream.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }
}
