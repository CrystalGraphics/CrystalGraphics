package com.crystalgraphics.freetype;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * A loaded font face from FreeType. Wraps a native FT_Face pointer.
 * Provides glyph loading, rendering, and metric querying.
 * Must be explicitly destroyed via {@link #destroy()}.
 *
 * <p><strong>Lifecycle constraint:</strong> If any HBFont has been created from this face
 * via {@link com.crystalgraphics.text.FreeTypeHarfBuzzIntegration#createHBFontFromFTFace},
 * those HBFont objects must be destroyed BEFORE this face is destroyed.</p>
 *
 * @see com.crystalgraphics.text.FreeTypeHarfBuzzIntegration
 */
public final class FTFace {

    private long nativePtr;
    private final FreeTypeLibrary library;
    private final List<WeakReference<Object>> dependentResources = new ArrayList<WeakReference<Object>>();

    FTFace(long nativePtr, FreeTypeLibrary library) {
        this.nativePtr = nativePtr;
        this.library = library;
    }

    public void setPixelSizes(int width, int height) {
        checkNotDestroyed();
        int err = nSetPixelSizes(nativePtr, width, height);
        FTErrors.checkError(err, "FT_Set_Pixel_Sizes");
    }

    /**
     * Sets character size in 26.6 fixed-point.
     * @param charWidth width in 1/64 points (0 = same as height)
     * @param charHeight height in 1/64 points (0 = same as width)
     * @param horzResolution horizontal DPI (0 = default 72)
     * @param vertResolution vertical DPI (0 = default 72)
     */
    public void setCharSize(int charWidth, int charHeight, int horzResolution, int vertResolution) {
        checkNotDestroyed();
        int err = nSetCharSize(nativePtr, charWidth, charHeight, horzResolution, vertResolution);
        FTErrors.checkError(err, "FT_Set_Char_Size");
    }

    public int getCharIndex(int charCode) {
        checkNotDestroyed();
        return nGetCharIndex(nativePtr, charCode);
    }

    public void loadGlyph(int glyphIndex, int loadFlags) {
        checkNotDestroyed();
        int err = nLoadGlyph(nativePtr, glyphIndex, loadFlags);
        FTErrors.checkError(err, "FT_Load_Glyph(" + glyphIndex + ")");
    }

    public void loadChar(int charCode, int loadFlags) {
        checkNotDestroyed();
        int err = nLoadChar(nativePtr, charCode, loadFlags);
        FTErrors.checkError(err, "FT_Load_Char(" + charCode + ")");
    }

    public void renderGlyph(int renderMode) {
        checkNotDestroyed();
        int err = nRenderGlyph(nativePtr, renderMode);
        FTErrors.checkError(err, "FT_Render_Glyph");
    }

    public FTGlyphMetrics getGlyphMetrics() {
        checkNotDestroyed();
        int[] raw = nGetGlyphMetrics(nativePtr);
        return new FTGlyphMetrics(
                raw[0], raw[1], raw[2], raw[3], raw[4], raw[5], raw[6], raw[7]);
    }

    public FTBitmap getGlyphBitmap() {
        checkNotDestroyed();
        return nGetGlyphBitmap(nativePtr);
    }

    public String getFamilyName() {
        checkNotDestroyed();
        return nGetFamilyName(nativePtr);
    }

    public String getStyleName() {
        checkNotDestroyed();
        return nGetStyleName(nativePtr);
    }

    public int getNumGlyphs() {
        checkNotDestroyed();
        return nGetNumGlyphs(nativePtr);
    }

    /**
     * Returns the number of faces in the font file this face was loaded from.
     * For single-face fonts (.ttf, .otf), this returns 1.
     * For TrueType Collections (.ttc), this returns the number of fonts in the collection.
     *
     * @return number of faces (>= 1)
     */
    public int getNumFaces() {
        checkNotDestroyed();
        return nGetNumFaces(nativePtr);
    }

    public int getUnitsPerEM() {
        checkNotDestroyed();
        return nGetUnitsPerEM(nativePtr);
    }

    public int getAscender() {
        checkNotDestroyed();
        return nGetAscender(nativePtr);
    }

    public int getDescender() {
        checkNotDestroyed();
        return nGetDescender(nativePtr);
    }

    public int getHeight() {
        checkNotDestroyed();
        return nGetHeight(nativePtr);
    }

    /**
     * Returns kerning vector between two glyphs in 26.6 fixed-point.
     * @param leftGlyph left glyph index
     * @param rightGlyph right glyph index
     * @param kernMode 0=FT_KERNING_DEFAULT, 1=FT_KERNING_UNFITTED, 2=FT_KERNING_UNSCALED
     * @return int[2] = {x, y} kerning vector
     */
    public int[] getKerning(int leftGlyph, int rightGlyph, int kernMode) {
        checkNotDestroyed();
        return nGetKerning(nativePtr, leftGlyph, rightGlyph, kernMode);
    }

    public boolean hasKerning() {
        checkNotDestroyed();
        return nHasKerning(nativePtr);
    }

    /**
     * Returns the horizontal advance for the given glyph without loading the full glyph.
     * This is faster than loadGlyph + getGlyphMetrics for text measurement.
     *
     * @param glyphIndex the glyph index
     * @param loadFlags load flags (use FTLoadFlags constants)
     * @return the horizontal advance in 16.16 fixed-point, or 0 on error
     */
    public int getAdvance(int glyphIndex, int loadFlags) {
        checkNotDestroyed();
        return nGetAdvance(nativePtr, glyphIndex, loadFlags);
    }

    public long getNativePtr() {
        return nativePtr;
    }

    public void registerDependent(Object dependent) {
        synchronized (dependentResources) {
            dependentResources.add(new WeakReference<Object>(dependent));
        }
    }

    public boolean hasActiveDependents() {
        synchronized (dependentResources) {
            Iterator<WeakReference<Object>> it = dependentResources.iterator();
            while (it.hasNext()) {
                Object dep = it.next().get();
                if (dep == null) {
                    it.remove();
                    continue;
                }
                if (dep instanceof com.crystalgraphics.harfbuzz.HBFont) {
                    if (!((com.crystalgraphics.harfbuzz.HBFont) dep).isDestroyed()) {
                        return true;
                    } else {
                        it.remove();
                    }
                } else {
                    return true;
                }
            }
            return false;
        }
    }

    public void destroy() {
        if (nativePtr != 0) {
            if (hasActiveDependents()) {
                throw new IllegalStateException(
                    "Cannot destroy FTFace while HBFont objects still reference it. "
                    + "Destroy all HBFont instances created from this face first.");
            }
            nDoneFace(nativePtr);
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
                System.err.println("[CrystalGraphics] WARNING: FTFace was not destroyed! "
                    + "Call destroy() explicitly to avoid native memory leaks.");
                try {
                    nDoneFace(nativePtr);
                } catch (Throwable t) {
                    // Best-effort cleanup during finalization
                }
                nativePtr = 0;
            }
        } finally {
            super.finalize();
        }
    }

    private void checkNotDestroyed() {
        if (nativePtr == 0) {
            throw new IllegalStateException("FTFace has been destroyed");
        }
        if (library != null && library.isDestroyed()) {
            throw new IllegalStateException(
                "Parent FreeTypeLibrary has been destroyed. Destroy faces before the library.");
        }
    }

    private static native int nSetPixelSizes(long facePtr, int width, int height);
    private static native int nSetCharSize(long facePtr, int charWidth, int charHeight, int horzRes, int vertRes);
    private static native int nGetCharIndex(long facePtr, int charCode);
    private static native int nLoadGlyph(long facePtr, int glyphIndex, int loadFlags);
    private static native int nLoadChar(long facePtr, int charCode, int loadFlags);
    private static native int nRenderGlyph(long facePtr, int renderMode);
    private static native int[] nGetGlyphMetrics(long facePtr);
    private static native FTBitmap nGetGlyphBitmap(long facePtr);
    private static native String nGetFamilyName(long facePtr);
    private static native String nGetStyleName(long facePtr);
    private static native int nGetNumGlyphs(long facePtr);
    private static native int nGetNumFaces(long facePtr);
    private static native int nGetUnitsPerEM(long facePtr);
    private static native int nGetAscender(long facePtr);
    private static native int nGetDescender(long facePtr);
    private static native int nGetHeight(long facePtr);
    private static native int[] nGetKerning(long facePtr, int leftGlyph, int rightGlyph, int kernMode);
    private static native boolean nHasKerning(long facePtr);
    private static native int nGetAdvance(long facePtr, int glyphIndex, int loadFlags);
    private static native void nDoneFace(long facePtr);
}
