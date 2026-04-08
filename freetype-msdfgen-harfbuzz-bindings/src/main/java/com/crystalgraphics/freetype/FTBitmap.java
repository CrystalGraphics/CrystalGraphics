package com.crystalgraphics.freetype;

/**
 * Rendered glyph bitmap from FreeType. Contains pixel data and dimensions.
 * Pixel format depends on the render mode used.
 */
public final class FTBitmap {

    /** 8-bit grayscale, 1 byte per pixel. */
    public static final int PIXEL_MODE_GRAY = 2;
    /** 1-bit monochrome, packed 8 pixels per byte. */
    public static final int PIXEL_MODE_MONO = 1;
    /** LCD sub-pixel rendering, 3 bytes per pixel (R, G, B). */
    public static final int PIXEL_MODE_LCD = 5;

    private final int width;
    private final int height;
    private final int pitch;
    private final int pixelMode;
    private final byte[] buffer;
    private final int left;
    private final int top;

    public FTBitmap(int width, int height, int pitch, int pixelMode,
                    byte[] buffer, int left, int top) {
        this.width = width;
        this.height = height;
        this.pitch = pitch;
        this.pixelMode = pixelMode;
        this.buffer = buffer;
        this.left = left;
        this.top = top;
    }

    public int getWidth() { return width; }
    public int getHeight() { return height; }

    /** Number of bytes per row (may include padding). Can be negative for bottom-up bitmaps. */
    public int getPitch() { return pitch; }
    public int getPixelMode() { return pixelMode; }

    /** Raw pixel data. For GRAY mode: one byte per pixel, 0=transparent, 255=opaque. */
    public byte[] getBuffer() { return buffer; }

    /** Horizontal offset from glyph origin to left edge of bitmap, in pixels. */
    public int getLeft() { return left; }

    /** Vertical offset from baseline to top edge of bitmap, in pixels (positive = up). */
    public int getTop() { return top; }

    @Override
    public String toString() {
        return "FTBitmap{" + width + "x" + height +
                ", pitch=" + pitch +
                ", pixelMode=" + pixelMode +
                ", left=" + left + ", top=" + top + '}';
    }
}
