package com.msdfgen;

/**
 * JNI bridge to the MSDFgen C API. All methods are package-private
 * and called by the public wrapper classes.
 *
 * This class mirrors the msdfgen-c.h C API 1:1.
 * Opaque handles (shape, contour, segment) are passed as {@code long} (native pointer).
 * Structs (bitmap, vector2, transform, bounds, config) use primitive arrays or dedicated classes.
 */
final class MsdfNative {

    static {
        NativeLoader.load();
    }

    private MsdfNative() {}

    // --- Bitmap ---
    // bitmap is represented as int[4]: {type, width, height, handleLow} + long handle separately
    static native int nBitmapAlloc(int type, int width, int height, long[] bitmapHandleOut);
    static native int nBitmapGetChannelCount(long bitmapHandle, int type);
    static native int nBitmapGetPixels(long bitmapHandle, int type, int width, int height, float[] pixelsOut);
    static native long nBitmapGetByteSize(long bitmapHandle, int type, int width, int height);
    static native void nBitmapFree(long bitmapHandle, int type);

    // --- Shape ---
    static native long nShapeAlloc();
    static native int nShapeGetBounds(long shape, double[] boundsOut);
    static native long nShapeAddContour(long shape);
    static native int nShapeRemoveContour(long shape, long contour);
    static native int nShapeGetContourCount(long shape);
    static native long nShapeGetContour(long shape, int index);
    static native int nShapeGetEdgeCount(long shape);
    static native int nShapeGetYAxisOrientation(long shape);
    static native int nShapeSetYAxisOrientation(long shape, int orientation);
    static native int nShapeNormalize(long shape);
    static native int nShapeValidate(long shape);
    static native int nShapeBound(long shape, double[] boundsOut);
    static native int nShapeBoundMiters(long shape, double[] boundsInOut, double border, double miterLimit, int polarity);
    static native int nShapeOrientContours(long shape);
    static native int nShapeEdgeColorsSimple(long shape, double angleThreshold);
    static native int nShapeEdgeColorsInkTrap(long shape, double angleThreshold);
    static native int nShapeEdgeColorsByDistance(long shape, double angleThreshold);
    static native double nShapeOneShotDistance(long shape, double originX, double originY);
    static native void nShapeFree(long shape);

    // --- Contour ---
    static native long nContourAlloc();
    static native int nContourAddEdge(long contour, long segment);
    static native int nContourRemoveEdge(long contour, long segment);
    static native int nContourGetEdgeCount(long contour);
    static native long nContourGetEdge(long contour, int index);
    static native int nContourGetWinding(long contour);
    static native int nContourReverse(long contour);
    static native int nContourBound(long contour, double[] boundsOut);
    static native void nContourFree(long contour);

    // --- Segment ---
    static native long nSegmentAlloc(int type);
    static native int nSegmentGetType(long segment);
    static native int nSegmentGetPointCount(long segment);
    static native int nSegmentGetPoint(long segment, int index, double[] pointOut);
    static native int nSegmentSetPoint(long segment, int index, double x, double y);
    static native int nSegmentGetColor(long segment);
    static native int nSegmentSetColor(long segment, int color);
    static native int nSegmentGetDirection(long segment, double param, double[] dirOut);
    static native int nSegmentPoint(long segment, double param, double[] pointOut);
    static native int nSegmentBound(long segment, double[] boundsOut);
    static native int nSegmentMoveStartPoint(long segment, double x, double y);
    static native int nSegmentMoveEndPoint(long segment, double x, double y);
    static native void nSegmentFree(long segment);

    // --- Generation ---
    static native int nGenerateSdf(long bitmapHandle, int bitmapType, int width, int height,
                                   long shape,
                                   double scaleX, double scaleY,
                                   double translateX, double translateY,
                                   double rangeLower, double rangeUpper);

    static native int nGeneratePsdf(long bitmapHandle, int bitmapType, int width, int height,
                                    long shape,
                                    double scaleX, double scaleY,
                                    double translateX, double translateY,
                                    double rangeLower, double rangeUpper);

    static native int nGenerateMsdf(long bitmapHandle, int bitmapType, int width, int height,
                                    long shape,
                                    double scaleX, double scaleY,
                                    double translateX, double translateY,
                                    double rangeLower, double rangeUpper);

    static native int nGenerateMtsdf(long bitmapHandle, int bitmapType, int width, int height,
                                     long shape,
                                     double scaleX, double scaleY,
                                     double translateX, double translateY,
                                     double rangeLower, double rangeUpper);

    static native int nGenerateSdfWithConfig(long bitmapHandle, int bitmapType, int width, int height,
                                             long shape,
                                             double scaleX, double scaleY,
                                             double translateX, double translateY,
                                             double rangeLower, double rangeUpper,
                                             boolean overlapSupport);

    static native int nGenerateMsdfWithConfig(long bitmapHandle, int bitmapType, int width, int height,
                                              long shape,
                                              double scaleX, double scaleY,
                                              double translateX, double translateY,
                                              double rangeLower, double rangeUpper,
                                              boolean overlapSupport,
                                              int errorCorrectionMode,
                                              int distanceCheckMode,
                                              double minDeviationRatio,
                                              double minImproveRatio);

    static native int nGenerateMtsdfWithConfig(long bitmapHandle, int bitmapType, int width, int height,
                                               long shape,
                                               double scaleX, double scaleY,
                                               double translateX, double translateY,
                                               double rangeLower, double rangeUpper,
                                               boolean overlapSupport,
                                               int errorCorrectionMode,
                                               int distanceCheckMode,
                                               double minDeviationRatio,
                                               double minImproveRatio);

    // --- Error Correction ---
    /**
     * Applies error correction to an MSDF/MTSDF bitmap using the shape for edge analysis.
     * This is the full error correction that analyzes shape edges for optimal correction.
     *
     * @param bitmapHandle native bitmap handle
     * @param bitmapType bitmap type (MSDF=2 or MTSDF=3)
     * @param width bitmap width
     * @param height bitmap height
     * @param shape native shape handle
     * @param scaleX projection scale X
     * @param scaleY projection scale Y
     * @param translateX projection translate X
     * @param translateY projection translate Y
     * @param rangeLower distance range lower bound
     * @param rangeUpper distance range upper bound
     * @param errorCorrectionMode mode from MsdfConstants.ERROR_CORRECTION_*
     * @param distanceCheckMode mode from MsdfConstants.DISTANCE_CHECK_*
     * @param minDeviationRatio minimum deviation ratio
     * @param minImproveRatio minimum improvement ratio
     */
    static native int nErrorCorrection(long bitmapHandle, int bitmapType, int width, int height,
                                       long shape,
                                       double scaleX, double scaleY,
                                       double translateX, double translateY,
                                       double rangeLower, double rangeUpper,
                                       int errorCorrectionMode, int distanceCheckMode,
                                       double minDeviationRatio, double minImproveRatio);

    /**
     * Applies fast distance-based error correction to an MSDF/MTSDF bitmap.
     * Does not require a shape - uses distance discontinuities only.
     */
    static native int nErrorCorrectionFastDistance(long bitmapHandle, int bitmapType, int width, int height,
                                                   double scaleX, double scaleY,
                                                   double translateX, double translateY,
                                                   double rangeLower, double rangeUpper,
                                                   double minDeviationRatio);

    /**
     * Applies fast edge-based error correction to an MSDF/MTSDF bitmap.
     * Does not require a shape - uses edge discontinuities only.
     */
    static native int nErrorCorrectionFastEdge(long bitmapHandle, int bitmapType, int width, int height,
                                                 double rangeLower, double rangeUpper,
                                                 double minDeviationRatio);

    static native int nDistanceSignCorrection(long bitmapHandle, int bitmapType, int width, int height,
                                               long shape,
                                               double scaleX, double scaleY,
                                               double translateX, double translateY,
                                               double rangeLower, double rangeUpper,
                                               int fillRule);

    // --- Generation with Config (PSDF) ---
    static native int nGeneratePsdfWithConfig(long bitmapHandle, int bitmapType, int width, int height,
                                              long shape,
                                              double scaleX, double scaleY,
                                              double translateX, double translateY,
                                              double rangeLower, double rangeUpper,
                                              boolean overlapSupport);

    // --- Render SDF ---
    /**
     * Renders an SDF bitmap to a regular bitmap for visualization/debugging.
     *
     * @param outputHandle output bitmap handle (1-channel or 3-channel)
     * @param outputType output bitmap type
     * @param outputWidth output bitmap width
     * @param outputHeight output bitmap height
     * @param sdfHandle SDF bitmap handle
     * @param sdfType SDF bitmap type
     * @param sdfWidth SDF bitmap width
     * @param sdfHeight SDF bitmap height
     * @param rangeLower SDF pixel range lower
     * @param rangeUpper SDF pixel range upper
     * @param sdThreshold signed distance threshold (typically 0.5)
     */
    static native int nRenderSdf(long outputHandle, int outputType, int outputWidth, int outputHeight,
                                  long sdfHandle, int sdfType, int sdfWidth, int sdfHeight,
                                  double rangeLower, double rangeUpper, float sdThreshold);

    // --- Contour bound miters ---
    static native int nContourBoundMiters(long contour, double[] boundsInOut,
                                           double border, double miterLimit, int polarity);

    // --- Segment direction change ---
    static native int nSegmentGetDirectionChange(long segment, double param, double[] dirChangeOut);

    // --- Direct pixel buffer access ---
    /**
     * Returns a direct pointer to native bitmap pixel data.
     * WARNING: The returned pointer is only valid while the bitmap is alive.
     * Do not use after calling nBitmapFree.
     *
     * @return native pointer to float pixel data, or 0 if invalid
     */
    static native long nBitmapGetPixelPointer(long bitmapHandle, int type);

    // --- FreeType extension (optional, only if built with MSDFGEN_USE_FREETYPE) ---

    /**
     * Returns whether the native library was compiled with FreeType support.
     */
    static native boolean nHasFreetypeSupport();

    /**
     * Initializes a FreeType library instance.
     *
     * @param handleOut single-element array to receive the FreeType handle
     * @return MSDF_SUCCESS on success, error code otherwise
     */
    static native int nFreetypeInit(long[] handleOut);

    /**
     * Deinitializes and frees a FreeType library instance.
     *
     * @param ftHandle the FreeType handle from {@link #nFreetypeInit}
     */
    static native void nFreetypeDeinit(long ftHandle);

    /**
     * Loads a font from a file path.
     *
     * @param ftHandle the FreeType handle
     * @param filename path to the font file (TTF, OTF, etc.)
     * @param fontHandleOut single-element array to receive the font handle
     * @return MSDF_SUCCESS on success, error code otherwise
     */
    static native int nLoadFont(long ftHandle, String filename, long[] fontHandleOut);

    /**
     * Loads a font from raw byte data in memory.
     *
     * @param ftHandle the FreeType handle
     * @param data raw font file data
     * @param dataLen length of the data array
     * @param fontHandleOut single-element array to receive the font handle
     * @return MSDF_SUCCESS on success, error code otherwise
     */
    static native int nLoadFontData(long ftHandle, byte[] data, int dataLen, long[] fontHandleOut);

    /**
     * Destroys a loaded font and frees its resources.
     *
     * @param fontHandle the font handle from {@link #nLoadFont} or {@link #nLoadFontData}
     */
    static native void nDestroyFont(long fontHandle);

    /**
     * Loads a glyph shape from a font by Unicode codepoint.
     *
     * @param fontHandle the font handle
     * @param unicode the Unicode codepoint (e.g., 'A' = 65)
     * @param coordinateScaling coordinate scaling mode (0=FONT_SCALING_NONE, 1=FONT_SCALING_EM_NORMALIZED, 2=FONT_SCALING_LEGACY)
     * @param advanceOut single-element array to receive the glyph advance width (may be null)
     * @param shapeHandleOut single-element array to receive the new Shape handle (caller must free)
     * @return MSDF_SUCCESS on success, error code otherwise
     */
    static native int nLoadGlyph(long fontHandle, int unicode, int coordinateScaling,
                                 double[] advanceOut, long[] shapeHandleOut);

    /**
     * Loads a glyph shape from a font by glyph index.
     *
     * @param fontHandle the font handle
     * @param glyphIndex the glyph index (not Unicode codepoint)
     * @param coordinateScaling coordinate scaling mode
     * @param advanceOut single-element array to receive the glyph advance width (may be null)
     * @param shapeHandleOut single-element array to receive the new Shape handle (caller must free)
     * @return MSDF_SUCCESS on success, error code otherwise
     */
    static native int nLoadGlyphByIndex(long fontHandle, int glyphIndex, int coordinateScaling,
                                        double[] advanceOut, long[] shapeHandleOut);

    /**
     * Gets the glyph index for a Unicode codepoint.
     *
     * @param fontHandle the font handle
     * @param unicode the Unicode codepoint
     * @param indexOut single-element array to receive the glyph index
     * @return MSDF_SUCCESS on success, error code otherwise
     */
    static native int nGetGlyphIndex(long fontHandle, int unicode, int[] indexOut);

    /**
     * Gets kerning between two Unicode codepoints.
     *
     * @param fontHandle the font handle
     * @param cp1 left codepoint
     * @param cp2 right codepoint
     * @param kerningOut single-element array to receive the kerning value
     * @return MSDF_SUCCESS on success, error code otherwise
     */
    static native int nGetKerning(long fontHandle, int cp1, int cp2, double[] kerningOut);

    /**
     * Gets kerning between two glyph indices.
     *
     * @param fontHandle the font handle
     * @param index1 left glyph index
     * @param index2 right glyph index
     * @param kerningOut single-element array to receive the kerning value
     * @return MSDF_SUCCESS on success, error code otherwise
     */
    static native int nGetKerningByIndex(long fontHandle, int index1, int index2, double[] kerningOut);

    static native int nSetFontVariations(long ftHandle, long fontHandle, String[] axisTags, float[] values, int valueCount);
}
