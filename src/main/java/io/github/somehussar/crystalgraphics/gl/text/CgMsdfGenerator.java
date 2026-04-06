package io.github.somehussar.crystalgraphics.gl.text;

import com.msdfgen.Bitmap;
import com.msdfgen.FreeTypeIntegration;
import com.msdfgen.Generator;
import com.msdfgen.MsdfConstants;
import com.msdfgen.MsdfException;
import com.msdfgen.Shape;
import com.msdfgen.Transform;
import io.github.somehussar.crystalgraphics.api.font.CgAtlasRegion;
import io.github.somehussar.crystalgraphics.api.font.CgFontKey;
import io.github.somehussar.crystalgraphics.api.font.CgGlyphKey;
import io.github.somehussar.crystalgraphics.api.font.CgGlyphPlacement;
import io.github.somehussar.crystalgraphics.gl.text.atlas.CgPagedGlyphAtlas;
import io.github.somehussar.crystalgraphics.gl.text.msdf.CgMsdfAtlasConfig;
import io.github.somehussar.crystalgraphics.gl.text.msdf.CgMsdfEdgeColoringMode;
import io.github.somehussar.crystalgraphics.gl.text.msdf.CgMsdfGlyphLayout;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Render-thread MSDF generator for glyph atlases.
 *
 * <p>This class converts glyph outlines from the local msdfgen bindings into
 * RGB float MSDF images and uploads them into a {@link CgGlyphAtlas}. A strict
 * per-frame budget is enforced to avoid frame spikes. When generation is not
 * allowed for the current glyph or budget, callers are expected to use the
 * bitmap fallback path.</p>
 *
 * <h3>Coordinate Convention</h3>
 * <p>Glyphs are loaded with {@code FONT_SCALING_EM_NORMALIZED}: shape
 * coordinates are in EM units (1.0 = 1 em). A <strong>uniform</strong>
 * scale equal to {@code targetPx} is applied so that one cell pixel maps
 * to one screen pixel, and all glyphs at the same font size share the same
 * scale. This avoids the per-glyph autoFrame scaling that would make every
 * glyph fill the cell and appear the same size.</p>
 *
 * <h3>Error Correction</h3>
 * <p>MSDF generation uses {@code ERROR_CORRECTION_DISABLED} because the
 * default internal error-correction pass in msdfgen's {@code generateMSDF}
 * has been observed to crash on certain glyph shapes over time
 * ({@code EXCEPTION_ACCESS_VIOLATION} in {@code msdfgen-jni.dll}).
 * At the cell sizes used here (32-64px) the artifacts that error correction
 * fixes are imperceptible.</p>
 */
public class CgMsdfGenerator {

    private static final Logger LOGGER = Logger.getLogger(CgMsdfGenerator.class.getName());

    public static final float PX_RANGE = 4.0f;
    public static final int MAX_PER_FRAME = 4;
    static final int COMPLEXITY_EDGE_THRESHOLD = 24;
    static final int SIMPLE_MSDF_MIN_PX = 32;
    static final int COMPLEX_MSDF_MIN_PX = 48;

    private int generatedThisFrame;

    public CgMsdfGenerator() {
        this.generatedThisFrame = 0;
    }

    /**
     * Generates one MSDF glyph and inserts it into the target atlas.
     *
     * <p>Returns {@code null} when the frame budget is exhausted, when the
     * shape is empty, when the complexity heuristic says bitmap rendering is
     * still the better choice at the current font size, or when the glyph
     * does not fit in the atlas cell at the current font size.</p>
     *
     * @param key          glyph key
     * @param font         msdfgen FreeType font handle
     * @param atlas        target MSDF atlas
     * @param ftBearingX   unused (reserved for future FreeType metric override)
     * @param ftBearingY   unused (reserved for future FreeType metric override)
     * @param currentFrame current frame number for LRU tracking
     * @return the atlas region, or {@code null} if generation was skipped
     */
    public CgAtlasRegion queueOrGenerate(CgGlyphKey key,
                                         FreeTypeIntegration.Font font,
                                         CgGlyphAtlas atlas,
                                         float ftBearingX,
                                         float ftBearingY,
                                         long currentFrame) {
        return queueOrGenerate(key, font, atlas, CgMsdfAtlasConfig.defaultConfig(), currentFrame);
    }

    public CgAtlasRegion queueOrGenerate(CgGlyphKey key,
                                         FreeTypeIntegration.Font font,
                                         CgGlyphAtlas atlas,
                                         CgMsdfAtlasConfig config,
                                         long currentFrame) {
        if (generatedThisFrame >= MAX_PER_FRAME) {
            return null;
        }
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }

        FreeTypeIntegration.GlyphData glyphData;
        try {
            glyphData = font.loadGlyphByIndex(key.getGlyphId(), FreeTypeIntegration.FONT_SCALING_EM_NORMALIZED);
        } catch (MsdfException e) {
            LOGGER.log(Level.FINE, "Failed to load glyph index " + key.getGlyphId(), e);
            return null;
        }

        // DO NOT call shape.free() manually. Shape has a finalize() method
        // that calls free(), but its 'freed' flag is not volatile. If we call
        // shape.free() on the render thread, the finalizer thread can still
        // see the stale freed==false and call nShapeFree a second time —
        // corrupting the native heap. The corruption is silent until a later
        // native allocation (e.g. nLoadGlyphByIndex) traverses the damaged
        // free-list and crashes. Letting the finalizer be the sole owner of
        // the free() call eliminates the race entirely.
        Shape shape = glyphData.getShape();

        if (shape.getEdgeCount() == 0) {
            return null;
        }
        if (!shouldUseMsdf(shape, key.getFontKey().getTargetPx())) {
            return null;
        }

        shape.normalize();
        applyEdgeColoring(shape, config);

        int targetPx = key.getFontKey().getTargetPx();
        int cellSize = cellSizeForFontPx(targetPx);
        double[] bounds = shape.getBounds();
        double shapeL = bounds[0];
        double shapeB = bounds[1];
        double shapeR = bounds[2];
        double shapeT = bounds[3];

        // Uniform scale: 1 EM = targetPx cell pixels = targetPx screen pixels.
        // All glyphs at the same font size share this scale, so relative
        // sizes are preserved (unlike autoFrame which scales each glyph
        // individually to fill the cell).
        double scale = targetPx;
        double halfRange = PX_RANGE / 2.0;

        // Check that the glyph + SDF border fits in the cell.
        double neededW = (shapeR - shapeL) * scale + PX_RANGE;
        double neededH = (shapeT - shapeB) * scale + PX_RANGE;
        if (neededW > cellSize || neededH > cellSize) {
            return null;
        }

        // Projection formula: pixel = scale*(coord + tx).
        // Position shapeL at pixel halfRange (left SDF margin).
        double tx = -shapeL + halfRange / scale;
        double ty = -shapeB + halfRange / scale;

        // Centre remaining slack.
        double slackX = cellSize - neededW;
        double slackY = cellSize - neededH;
        tx += (slackX / 2.0) / scale;
        ty += (slackY / 2.0) / scale;

        double rangeInShapeUnits = halfRange / scale;

        Transform transform = new Transform()
                .scale(scale)
                .translate(tx, ty)
                .range(-rangeInShapeUnits, rangeInShapeUnits);

        Bitmap bitmap = config.isMtsdf()
                ? Bitmap.allocMtsdf(cellSize, cellSize)
                : Bitmap.allocMsdf(cellSize, cellSize);
        try {
            if (config.isMtsdf()) {
                Generator.generateMtsdf(bitmap, shape, transform,
                        config.isOverlapSupport(),
                        config.getErrorCorrectionMode(),
                        config.getDistanceCheckMode(),
                        config.getMinDeviationRatio(),
                        config.getMinImproveRatio());
            } else {
                Generator.generateMsdf(bitmap, shape, transform,
                        config.isOverlapSupport(),
                        config.getErrorCorrectionMode(),
                        config.getDistanceCheckMode(),
                        config.getMinDeviationRatio(),
                        config.getMinImproveRatio());
            }

            float[] pixelData = bitmap.getPixelData();
            int channels = config.isMtsdf() ? 4 : 3;
            flipRows(pixelData, cellSize, cellSize, channels);

            // bearingX = -(scale * tx)  : pen is scale*tx pixels from cell left
            // bearingY = cellSize - scale*ty : baseline is scale*ty from cell bottom,
            //            i.e. cellSize - scale*ty from cell top (after flipRows)
            float bearingX = (float) -(scale * tx);
            float bearingY = (float) (cellSize - scale * ty);
            float metricsWidth = (float) ((shapeR - shapeL) * scale);
            float metricsHeight = (float) ((shapeT - shapeB) * scale);

            CgAtlasRegion region = atlas.getOrAllocateMsdf(key, pixelData,
                    cellSize, cellSize, bearingX, bearingY,
                    metricsWidth, metricsHeight, currentFrame);
            generatedThisFrame++;
            return region;
        } finally {
            bitmap.free();
        }
    }

    /**
     * Paged-atlas MSDF generation using upstream-parity glyph layout.
     *
     * <p>Uses {@link CgMsdfGlyphLayout} for per-glyph box sizing instead of the
     * fixed-cell model in {@link #queueOrGenerate}. The generated MSDF bitmap is
     * uploaded to a {@link CgPagedGlyphAtlas} which handles page allocation and
     * returns a {@link CgGlyphPlacement} directly.</p>
     *
     * <p>Returns {@code null} when the frame budget is exhausted, the shape is
     * empty.</p>
     *
     * @param key          glyph key
     * @param font         msdfgen FreeType font handle
     * @param pagedAtlas   target paged MSDF atlas
     * @param currentFrame current frame number for LRU tracking
     * @return the glyph placement, or {@code null} if generation was skipped
     */
    public CgGlyphPlacement queueOrGeneratePaged(CgGlyphKey key,
                                                   FreeTypeIntegration.Font font,
                                                   CgPagedGlyphAtlas pagedAtlas,
                                                   CgMsdfAtlasConfig config,
                                                   long currentFrame) {
        if (generatedThisFrame >= MAX_PER_FRAME) {
            return null;
        }
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }

        FreeTypeIntegration.GlyphData glyphData;
        try {
            glyphData = font.loadGlyphByIndex(key.getGlyphId(), FreeTypeIntegration.FONT_SCALING_EM_NORMALIZED);
        } catch (MsdfException e) {
            LOGGER.log(Level.FINE, "Failed to load glyph index " + key.getGlyphId(), e);
            return null;
        }

        // DO NOT call shape.free() manually. Shape has a finalize() method
        // that calls free(), but its 'freed' flag is not volatile. If we call
        // shape.free() on the render thread, the finalizer thread can still
        // see the stale freed==false and call nShapeFree a second time —
        // corrupting the native heap. The corruption is silent until a later
        // native allocation (e.g. nLoadGlyphByIndex) traverses the damaged
        // free-list and crashes. Letting the finalizer be the sole owner of
        // the free() call eliminates the race entirely.
        Shape shape = glyphData.getShape();

        if (shape.getEdgeCount() == 0) {
            return null;
        }
        shape.normalize();
        applyEdgeColoring(shape, config);

        int targetPx = config.getAtlasScalePx();
        double[] bounds = shape.getBounds();
        double shapeL = bounds[0];
        double shapeB = bounds[1];
        double shapeR = bounds[2];
        double shapeT = bounds[3];

        // Compute per-glyph box dimensions using upstream-parity layout math
        CgMsdfGlyphLayout layout = CgMsdfGlyphLayout.compute(
                shapeL, shapeB, shapeR, shapeT,
                targetPx,
                config.getPxRange(),
                config.getMiterLimit(),
                config.isAlignOriginX(),
                config.isAlignOriginY());

        if (layout.isEmpty()) {
            return null;
        }

        int boxWidth = layout.getBoxWidth();
        int boxHeight = layout.getBoxHeight();
        double scale = layout.getScale();
        double tx = layout.getTranslateX();
        double ty = layout.getTranslateY();
        double rangeInShapeUnits = layout.getRangeInShapeUnits();

        Transform transform = new Transform()
                .scale(scale)
                .translate(tx, ty)
                .range(-rangeInShapeUnits, rangeInShapeUnits);

        int channels = config.isMtsdf() ? 4 : 3;
        Bitmap bitmap = config.isMtsdf()
                ? Bitmap.allocMtsdf(boxWidth, boxHeight)
                : Bitmap.allocMsdf(boxWidth, boxHeight);
        try {
            if (config.isMtsdf()) {
                Generator.generateMtsdf(bitmap, shape, transform,
                        config.isOverlapSupport(),
                        config.getErrorCorrectionMode(),
                        config.getDistanceCheckMode(),
                        config.getMinDeviationRatio(),
                        config.getMinImproveRatio());
            } else {
                Generator.generateMsdf(bitmap, shape, transform,
                        config.isOverlapSupport(),
                        config.getErrorCorrectionMode(),
                        config.getDistanceCheckMode(),
                        config.getMinDeviationRatio(),
                        config.getMinImproveRatio());
            }

            float[] pixelData = bitmap.getPixelData();
            flipRows(pixelData, boxWidth, boxHeight, channels);

            // Derive bearing and metrics from the layout's plane bounds.
            // bearingX = planeLeft * scale (plane bounds are in EM, convert to px)
            // bearingY = planeTop * scale (above baseline, positive)
            // The paged atlas page will use these to build CgGlyphPlacement
            // with correct plane bounds for the renderer.
            float bearingX = (float) (layout.getPlaneLeft() * scale);
            float bearingY = (float) (layout.getPlaneTop() * scale);
            float metricsWidth = (float) ((shapeR - shapeL) * scale);
            float metricsHeight = (float) ((shapeT - shapeB) * scale);

            CgGlyphPlacement placement = pagedAtlas.allocateMsdf(
                    key, pixelData, boxWidth, boxHeight,
                    bearingX, bearingY, metricsWidth, metricsHeight,
                    config.getPxRange(), currentFrame);
            generatedThisFrame++;
            return placement;
        } finally {
            bitmap.free();
        }
    }

    static CgGlyphGenerationResult preparePagedGlyph(CgGlyphKey key,
                                                     CgFontKey sourceFontKey,
                                                     FreeTypeIntegration.Font font,
                                                     CgMsdfAtlasKey atlasKey,
                                                     CgMsdfAtlasConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }

        FreeTypeIntegration.GlyphData glyphData;
        try {
            glyphData = font.loadGlyphByIndex(key.getGlyphId(), FreeTypeIntegration.FONT_SCALING_EM_NORMALIZED);
        } catch (MsdfException e) {
            LOGGER.log(Level.FINE, "Failed to load glyph index " + key.getGlyphId(), e);
            return null;
        }

        Shape shape = glyphData.getShape();
        if (shape.getEdgeCount() == 0) {
            return CgGlyphGenerationResult.emptyMsdf(sourceFontKey, key, atlasKey, config.getPxRange());
        }
        shape.normalize();
        applyEdgeColoring(shape, config);

        int targetPx = config.getAtlasScalePx();
        double[] bounds = shape.getBounds();
        double shapeL = bounds[0];
        double shapeB = bounds[1];
        double shapeR = bounds[2];
        double shapeT = bounds[3];

        CgMsdfGlyphLayout layout = CgMsdfGlyphLayout.compute(
                shapeL, shapeB, shapeR, shapeT,
                targetPx,
                config.getPxRange(),
                config.getMiterLimit(),
                config.isAlignOriginX(),
                config.isAlignOriginY());

        if (layout.isEmpty()) {
            return CgGlyphGenerationResult.emptyMsdf(sourceFontKey, key, atlasKey, config.getPxRange());
        }

        int boxWidth = layout.getBoxWidth();
        int boxHeight = layout.getBoxHeight();
        double scale = layout.getScale();
        double tx = layout.getTranslateX();
        double ty = layout.getTranslateY();
        double rangeInShapeUnits = layout.getRangeInShapeUnits();

        Transform transform = new Transform()
                .scale(scale)
                .translate(tx, ty)
                .range(-rangeInShapeUnits, rangeInShapeUnits);

        int channels = config.isMtsdf() ? 4 : 3;
        Bitmap bitmap = config.isMtsdf()
                ? Bitmap.allocMtsdf(boxWidth, boxHeight)
                : Bitmap.allocMsdf(boxWidth, boxHeight);
        try {
            if (config.isMtsdf()) {
                Generator.generateMtsdf(bitmap, shape, transform,
                        config.isOverlapSupport(),
                        config.getErrorCorrectionMode(),
                        config.getDistanceCheckMode(),
                        config.getMinDeviationRatio(),
                        config.getMinImproveRatio());
            } else {
                Generator.generateMsdf(bitmap, shape, transform,
                        config.isOverlapSupport(),
                        config.getErrorCorrectionMode(),
                        config.getDistanceCheckMode(),
                        config.getMinDeviationRatio(),
                        config.getMinImproveRatio());
            }

            float[] pixelData = bitmap.getPixelData();
            flipRows(pixelData, boxWidth, boxHeight, channels);

            float bearingX = (float) (layout.getPlaneLeft() * scale);
            float bearingY = (float) (layout.getPlaneTop() * scale);
            float metricsWidth = (float) ((shapeR - shapeL) * scale);
            float metricsHeight = (float) ((shapeT - shapeB) * scale);

            return CgGlyphGenerationResult.msdf(
                    sourceFontKey,
                    key,
                    atlasKey,
                    pixelData,
                    boxWidth,
                    boxHeight,
                    bearingX,
                    bearingY,
                    metricsWidth,
                    metricsHeight,
                    config.getPxRange());
        } finally {
            bitmap.free();
        }
    }

    public void tickFrame() {
        generatedThisFrame = 0;
    }

    public static int cellSizeForFontPx(int fontPx) {
        if (fontPx >= 64) {
            // Scale cell to fit the widest glyphs: fontPx + PX_RANGE, rounded up to multiple of 8
            int needed = fontPx + (int) PX_RANGE;
            return ((needed + 7) / 8) * 8;
        }
        if (fontPx >= 36) {
            return 48;
        }
        return 32;
    }

    public static boolean shouldUseMsdf(Shape shape, int fontPx) {
        int totalEdges = shape.getEdgeCount();
        if (totalEdges > COMPLEXITY_EDGE_THRESHOLD) {
            return fontPx >= COMPLEX_MSDF_MIN_PX;
        }
        return fontPx >= SIMPLE_MSDF_MIN_PX;
    }

    public static void applyEdgeColoring(Shape shape, CgMsdfAtlasConfig config) {
        CgMsdfEdgeColoringMode mode = config.getEdgeColoringMode();
        double threshold = config.getEdgeColoringAngleThreshold();
        if (mode == CgMsdfEdgeColoringMode.INK_TRAP) {
            shape.edgeColoringInkTrap(threshold);
            return;
        }
        if (mode == CgMsdfEdgeColoringMode.DISTANCE) {
            shape.edgeColoringByDistance(threshold);
            return;
        }
        shape.edgeColoringSimple(threshold);
    }

    /**
     * Flips pixel data rows vertically in-place.
     *
     * <p>msdfgen produces bitmaps in math convention (row 0 = bottom, Y-up),
     * but OpenGL {@code glTexSubImage2D} expects image convention (row 0 = top).
     * This swaps rows so row 0 becomes the topmost row of the glyph.</p>
     *
     * @param pixels   row-major float array ({@code height * width * channels})
     * @param width    bitmap width in pixels
     * @param height   bitmap height in pixels
     * @param channels number of channels per pixel (3 for MSDF)
     */
    static void flipRows(float[] pixels, int width, int height, int channels) {
        int rowStride = width * channels;
        float[] tmp = new float[rowStride];
        for (int top = 0, bot = height - 1; top < bot; top++, bot--) {
            int topOff = top * rowStride;
            int botOff = bot * rowStride;
            System.arraycopy(pixels, topOff, tmp, 0, rowStride);
            System.arraycopy(pixels, botOff, pixels, topOff, rowStride);
            System.arraycopy(tmp, 0, pixels, botOff, rowStride);
        }
    }

    int getGeneratedThisFrame() {
        return generatedThisFrame;
    }

    void simulateGeneration() {
        generatedThisFrame++;
    }
}
