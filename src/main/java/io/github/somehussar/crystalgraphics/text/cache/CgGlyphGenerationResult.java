package io.github.somehussar.crystalgraphics.text.cache;

import io.github.somehussar.crystalgraphics.api.font.CgFontKey;
import io.github.somehussar.crystalgraphics.api.font.CgGlyphKey;
import io.github.somehussar.crystalgraphics.text.atlas.CgGlyphAtlas;

/**
 * Immutable result of one async glyph-generation job.
 *
 * <p>Contains raw pixel data (bitmap or MSDF), layout metrics, and the
 * atlas keys needed by {@link CgFontRegistry} to upload the glyph into
 * the correct atlas on the render thread.</p>
 */
public final class CgGlyphGenerationResult {

    private final CgFontKey sourceFontKey;
    private final CgGlyphKey atlasKey;
    private final CgRasterFontKey bitmapRasterKey;
    private final CgMsdfAtlasKey msdfAtlasKey;
    private final byte[] bitmapData;
    private final float[] msdfData;
    private final int width;
    private final int height;
    private final float bearingX;
    private final float bearingY;
    private final float planeLeft;
    private final float planeBottom;
    private final float planeRight;
    private final float planeTop;
    private final float metricsWidth;
    private final float metricsHeight;
    private final float pxRange;
    private final boolean emptyGeometry;

    private CgGlyphGenerationResult(CgFontKey sourceFontKey,
                                    CgGlyphKey atlasKey,
                                    CgRasterFontKey bitmapRasterKey,
                                    CgMsdfAtlasKey msdfAtlasKey,
                                    byte[] bitmapData,
                                    float[] msdfData,
                                    int width,
                                    int height,
                                    float bearingX,
                                    float bearingY,
                                    float planeLeft,
                                    float planeBottom,
                                    float planeRight,
                                    float planeTop,
                                    float metricsWidth,
                                    float metricsHeight,
                                    float pxRange,
                                    boolean emptyGeometry) {
        if (sourceFontKey == null) {
            throw new IllegalArgumentException("sourceFontKey must not be null");
        }
        if (atlasKey == null) {
            throw new IllegalArgumentException("atlasKey must not be null");
        }
        this.sourceFontKey = sourceFontKey;
        this.atlasKey = atlasKey;
        this.bitmapRasterKey = bitmapRasterKey;
        this.msdfAtlasKey = msdfAtlasKey;
        this.bitmapData = bitmapData;
        this.msdfData = msdfData;
        this.width = width;
        this.height = height;
        this.bearingX = bearingX;
        this.bearingY = bearingY;
        this.planeLeft = planeLeft;
        this.planeBottom = planeBottom;
        this.planeRight = planeRight;
        this.planeTop = planeTop;
        this.metricsWidth = metricsWidth;
        this.metricsHeight = metricsHeight;
        this.pxRange = pxRange;
        this.emptyGeometry = emptyGeometry;
    }

    public static CgGlyphGenerationResult bitmap(CgFontKey sourceFontKey,
                                          CgGlyphKey atlasKey,
                                          CgRasterFontKey bitmapRasterKey,
                                          byte[] bitmapData,
                                          int width,
                                          int height,
                                          float bearingX,
                                          float bearingY,
                                          float metricsWidth,
                                          float metricsHeight) {
        return new CgGlyphGenerationResult(
                sourceFontKey,
                atlasKey,
                bitmapRasterKey,
                null,
                bitmapData,
                null,
                width,
                height,
                bearingX,
                bearingY,
                bearingX,
                bearingY - metricsHeight,
                bearingX + metricsWidth,
                bearingY,
                metricsWidth,
                metricsHeight,
                0.0f,
                false);
    }

   public  static CgGlyphGenerationResult msdf(CgFontKey sourceFontKey,
                                        CgGlyphKey atlasKey,
                                        CgMsdfAtlasKey msdfAtlasKey,
                                        float[] msdfData,
                                        int width,
                                        int height,
                                        float bearingX,
                                        float bearingY,
                                        float planeLeft,
                                        float planeBottom,
                                        float planeRight,
                                        float planeTop,
                                        float metricsWidth,
                                        float metricsHeight,
                                        float pxRange) {
        return new CgGlyphGenerationResult(
                sourceFontKey,
                atlasKey,
                null,
                msdfAtlasKey,
                null,
                msdfData,
                width,
                height,
                bearingX,
                bearingY,
                planeLeft,
                planeBottom,
                planeRight,
                planeTop,
                metricsWidth,
                metricsHeight,
                pxRange,
                false);
    }

    public static CgGlyphGenerationResult emptyBitmap(CgFontKey sourceFontKey,
                                               CgGlyphKey atlasKey,
                                               CgRasterFontKey bitmapRasterKey) {
        return new CgGlyphGenerationResult(
                sourceFontKey,
                atlasKey,
                bitmapRasterKey,
                null,
                null,
                null,
                0,
                0,
                0.0f,
                0.0f,
                0.0f,
                0.0f,
                0.0f,
                0.0f,
                0.0f,
                0.0f,
                0.0f,
                true);
    }

    public static CgGlyphGenerationResult emptyMsdf(CgFontKey sourceFontKey,
                                             CgGlyphKey atlasKey,
                                             CgMsdfAtlasKey msdfAtlasKey,
                                             float pxRange) {
        return new CgGlyphGenerationResult(
                sourceFontKey,
                atlasKey,
                null,
                msdfAtlasKey,
                null,
                null,
                0,
                0,
                0.0f,
                0.0f,
                0.0f,
                0.0f,
                0.0f,
                0.0f,
                0.0f,
                0.0f,
                pxRange,
                true);
    }

    public CgFontKey getSourceFontKey() {
        return sourceFontKey;
    }

    public CgGlyphKey getAtlasKey() {
        return atlasKey;
    }

    public CgRasterFontKey getBitmapRasterKey() {
        return bitmapRasterKey;
    }

    public CgMsdfAtlasKey getMsdfAtlasKey() {
        return msdfAtlasKey;
    }

    public byte[] getBitmapData() {
        return bitmapData;
    }

    public  float[] getMsdfData() {
        return msdfData;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public float getBearingX() {
        return bearingX;
    }

    public float getBearingY() {
        return bearingY;
    }

    public float getPlaneLeft() {
        return planeLeft;
    }

    public float getPlaneBottom() {
        return planeBottom;
    }

    public float getPlaneRight() {
        return planeRight;
    }

    public float getPlaneTop() {
        return planeTop;
    }

    public float getMetricsWidth() {
        return metricsWidth;
    }

    public float getMetricsHeight() {
        return metricsHeight;
    }

    public  float getPxRange() {
        return pxRange;
    }

    public boolean isEmptyGeometry() {
        return emptyGeometry;
    }

    public boolean isMsdf() {
        return getAtlasType() == CgGlyphAtlas.Type.MSDF;
    }

    public boolean isDistanceField() {
        return getAtlasType() != CgGlyphAtlas.Type.BITMAP;
    }

    public CgGlyphAtlas.Type getAtlasType() {
        if (msdfAtlasKey != null) {
            return msdfAtlasKey.getConfig().resolveAtlasType();
        }
        return CgGlyphAtlas.Type.BITMAP;
    }
}
