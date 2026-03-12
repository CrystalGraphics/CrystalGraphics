package com.crystalgraphics.harfbuzz;

import com.crystalgraphics.freetype.NativeLoader;

public final class HBShape {

    private HBShape() {
    }

    public static void shape(HBFont font, HBBuffer buffer) {
        shape(font, buffer, null);
    }

    public static void shape(HBFont font, HBBuffer buffer, String[] features) {
        NativeLoader.ensureLoaded();
        if (font == null || font.isDestroyed()) {
            throw new IllegalArgumentException("font must not be null or destroyed");
        }
        if (buffer == null || buffer.isDestroyed()) {
            throw new IllegalArgumentException("buffer must not be null or destroyed");
        }
        nShape(font.getNativePtr(), buffer.getNativePtr(), features);
    }

    private static native void nShape(long fontPtr, long bufferPtr, String[] features);
}
