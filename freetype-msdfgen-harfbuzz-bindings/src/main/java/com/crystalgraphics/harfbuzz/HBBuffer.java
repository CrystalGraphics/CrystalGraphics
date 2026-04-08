package com.crystalgraphics.harfbuzz;

import com.crystalgraphics.NativeLoader;
/**
 * HarfBuzz text buffer. Holds a sequence of Unicode codepoints to be shaped,
 * and after shaping, the resulting glyph info and positions.
 * Must be explicitly destroyed via {@link #destroy()}.
 */
public final class HBBuffer {

    private long nativePtr;

    private HBBuffer(long nativePtr) {
        this.nativePtr = nativePtr;
    }

    public static HBBuffer create() {
        NativeLoader.ensureLoaded();
        long ptr = nCreate();
        if (ptr == 0) {
            throw new RuntimeException("Failed to create HarfBuzz buffer");
        }
        return new HBBuffer(ptr);
    }

    public void addUTF8(String text) {
        checkNotDestroyed();
        if (text == null) {
            throw new IllegalArgumentException("text must not be null");
        }
        byte[] utf8 = toUTF8(text);
        nAddUTF8(nativePtr, utf8, utf8.length, 0, utf8.length);
    }

    public void addUTF8(String text, int itemOffset, int itemLength) {
        checkNotDestroyed();
        byte[] utf8 = toUTF8(text);
        nAddUTF8(nativePtr, utf8, utf8.length, itemOffset, itemLength);
    }

    public void addCodepoints(int[] codepoints) {
        checkNotDestroyed();
        nAddCodepoints(nativePtr, codepoints, codepoints.length, 0, codepoints.length);
    }

    public void setDirection(int direction) {
        checkNotDestroyed();
        nSetDirection(nativePtr, direction);
    }

    public int getDirection() {
        checkNotDestroyed();
        return nGetDirection(nativePtr);
    }

    public void setScript(int script) {
        checkNotDestroyed();
        nSetScript(nativePtr, script);
    }

    public int getScript() {
        checkNotDestroyed();
        return nGetScript(nativePtr);
    }

    public void setLanguage(String language) {
        checkNotDestroyed();
        nSetLanguage(nativePtr, language);
    }

    public void guessSegmentProperties() {
        checkNotDestroyed();
        nGuessSegmentProperties(nativePtr);
    }

    public int getLength() {
        checkNotDestroyed();
        return nGetLength(nativePtr);
    }

    /**
     * Returns glyph info after shaping. Each element is:
     * [codepoint (glyph ID after shaping), cluster].
     */
    public HBGlyphInfo[] getGlyphInfos() {
        checkNotDestroyed();
        return nGetGlyphInfos(nativePtr);
    }

    /**
     * Returns glyph positions after shaping. Each element is:
     * [xAdvance, yAdvance, xOffset, yOffset] in font units.
     */
    public HBGlyphPosition[] getGlyphPositions() {
        checkNotDestroyed();
        return nGetGlyphPositions(nativePtr);
    }

    public void reset() {
        checkNotDestroyed();
        nReset(nativePtr);
    }

    public void clearContents() {
        checkNotDestroyed();
        nClearContents(nativePtr);
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
                System.err.println("[CrystalGraphics] WARNING: HBBuffer was not destroyed! "
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
            throw new IllegalStateException("HBBuffer has been destroyed");
        }
    }

    private static byte[] toUTF8(String text) {
        try {
            return text.getBytes("UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new RuntimeException("UTF-8 not supported", e);
        }
    }

    private static native long nCreate();
    private static native void nDestroy(long bufferPtr);
    private static native void nAddUTF8(long bufferPtr, byte[] utf8, int textLen, int itemOffset, int itemLength);
    private static native void nAddCodepoints(long bufferPtr, int[] codepoints, int len, int itemOffset, int itemLength);
    private static native void nSetDirection(long bufferPtr, int direction);
    private static native int nGetDirection(long bufferPtr);
    private static native void nSetScript(long bufferPtr, int script);
    private static native int nGetScript(long bufferPtr);
    private static native void nSetLanguage(long bufferPtr, String language);
    private static native void nGuessSegmentProperties(long bufferPtr);
    private static native int nGetLength(long bufferPtr);
    private static native HBGlyphInfo[] nGetGlyphInfos(long bufferPtr);
    private static native HBGlyphPosition[] nGetGlyphPositions(long bufferPtr);
    private static native void nReset(long bufferPtr);
    private static native void nClearContents(long bufferPtr);
}
