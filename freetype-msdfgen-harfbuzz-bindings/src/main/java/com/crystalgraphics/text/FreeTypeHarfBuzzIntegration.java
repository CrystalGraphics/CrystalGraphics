package com.crystalgraphics.text;

import com.crystalgraphics.freetype.FTFace;
import com.crystalgraphics.freetype.NativeLoader;
import com.crystalgraphics.harfbuzz.HBFont;

/**
 * Bridges FreeType FT_Face and HarfBuzz hb_font_t objects.
 * This is the critical integration layer: HarfBuzz needs font access for shaping,
 * and FreeType provides the font data. The native side calls hb_ft_font_create()
 * which creates an hb_font_t backed by an FT_Face.
 *
 * <p>Lifecycle: The HBFont created here references the FT_Face internally.
 * The FT_Face must remain alive for the lifetime of the HBFont.
 * Destroy the HBFont BEFORE destroying the FTFace.</p>
 */
public final class FreeTypeHarfBuzzIntegration {

    private FreeTypeHarfBuzzIntegration() {
    }

    /**
     * Creates an HBFont backed by an FT_Face. Uses hb_ft_font_create() internally.
     * The FT_Face must outlive the returned HBFont.
     *
     * @param face the FreeType face (must not be destroyed)
     * @return a new HBFont that uses this face for shaping
     */
    public static HBFont createHBFontFromFTFace(FTFace face) {
        NativeLoader.ensureLoaded();
        if (face == null || face.isDestroyed()) {
            throw new IllegalArgumentException("FTFace must not be null or destroyed");
        }
        long hbFontPtr = nCreateHBFontFromFTFace(face.getNativePtr());
        if (hbFontPtr == 0) {
            throw new RuntimeException("Failed to create HBFont from FTFace");
        }
        HBFont hbFont = new HBFont(hbFontPtr);
        face.registerDependent(hbFont);
        return hbFont;
    }

    /**
     * Synchronizes HBFont scale/metrics with the current FTFace size settings.
     * Call this after changing pixel sizes on the FT_Face.
     */
    public static void syncFontMetrics(HBFont font, FTFace face) {
        NativeLoader.ensureLoaded();
        if (font == null || font.isDestroyed()) {
            throw new IllegalArgumentException("HBFont must not be null or destroyed");
        }
        if (face == null || face.isDestroyed()) {
            throw new IllegalArgumentException("FTFace must not be null or destroyed");
        }
        nSyncFontMetrics(font.getNativePtr(), face.getNativePtr());
    }

    private static native long nCreateHBFontFromFTFace(long ftFacePtr);
    private static native void nSyncFontMetrics(long hbFontPtr, long ftFacePtr);
}
