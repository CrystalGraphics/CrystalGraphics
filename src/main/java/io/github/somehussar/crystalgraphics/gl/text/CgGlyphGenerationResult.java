package io.github.somehussar.crystalgraphics.gl.text;

import io.github.somehussar.crystalgraphics.api.font.CgFontKey;
import io.github.somehussar.crystalgraphics.api.font.CgGlyphKey;

final class CgGlyphGenerationResult {

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
        this.metricsWidth = metricsWidth;
        this.metricsHeight = metricsHeight;
        this.pxRange = pxRange;
        this.emptyGeometry = emptyGeometry;
    }

    static CgGlyphGenerationResult bitmap(CgFontKey sourceFontKey,
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
                metricsWidth,
                metricsHeight,
                0.0f,
                false);
    }

    static CgGlyphGenerationResult msdf(CgFontKey sourceFontKey,
                                        CgGlyphKey atlasKey,
                                        CgMsdfAtlasKey msdfAtlasKey,
                                        float[] msdfData,
                                        int width,
                                        int height,
                                        float bearingX,
                                        float bearingY,
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
                metricsWidth,
                metricsHeight,
                pxRange,
                false);
    }

    static CgGlyphGenerationResult emptyBitmap(CgFontKey sourceFontKey,
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
                true);
    }

    static CgGlyphGenerationResult emptyMsdf(CgFontKey sourceFontKey,
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
                pxRange,
                true);
    }

    CgFontKey getSourceFontKey() {
        return sourceFontKey;
    }

    CgGlyphKey getAtlasKey() {
        return atlasKey;
    }

    CgRasterFontKey getBitmapRasterKey() {
        return bitmapRasterKey;
    }

    CgMsdfAtlasKey getMsdfAtlasKey() {
        return msdfAtlasKey;
    }

    byte[] getBitmapData() {
        return bitmapData;
    }

    float[] getMsdfData() {
        return msdfData;
    }

    int getWidth() {
        return width;
    }

    int getHeight() {
        return height;
    }

    float getBearingX() {
        return bearingX;
    }

    float getBearingY() {
        return bearingY;
    }

    float getMetricsWidth() {
        return metricsWidth;
    }

    float getMetricsHeight() {
        return metricsHeight;
    }

    float getPxRange() {
        return pxRange;
    }

    boolean isEmptyGeometry() {
        return emptyGeometry;
    }

    boolean isMsdf() {
        return atlasKey.isMsdf();
    }
}
