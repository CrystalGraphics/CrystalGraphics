package com.crystalgraphics.text.msdf;

import com.crystalgraphics.msdfgen.MSDFBitmap;
import com.crystalgraphics.msdfgen.FreeTypeMSDFIntegration;
import com.crystalgraphics.msdfgen.MSDFGenerator;
import com.crystalgraphics.msdfgen.MSDFException;
import com.crystalgraphics.msdfgen.MSDFShape;
import com.crystalgraphics.msdfgen.MSDFShapeSynthesis;
import com.crystalgraphics.msdfgen.MSDFTransform;
import com.crystalgraphics.api.font.CgFontKey;
import com.crystalgraphics.api.font.CgGlyphKey;
import com.crystalgraphics.text.atlas.CgPagedGlyphAtlas;
import com.crystalgraphics.text.cache.CgFontRegistry;
import com.crystalgraphics.text.cache.CgGlyphGenerationResult;
import com.crystalgraphics.text.cache.CgMsdfAtlasKey;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Render-thread MSDF generator for glyph atlases.
 *
 * <p>This class converts glyph outlines from the local msdfgen bindings into
 * RGB float MSDF images and uploads them into a {@link CgPagedGlyphAtlas}. A strict
 * per-frame budget is enforced to avoid frame spikes. When generation is not
 * allowed for the current glyph or budget, callers are expected to use the
 * bitmap fallback path.</p>
 *
 * <h3>Pipeline Role</h3>
 * <p>CgMsdfGenerator is the <em>render-thread MSDF rasterizer</em> in the glyph
 * cache pipeline.  {@link CgFontRegistry} delegates paged MSDF generation here.
 * The generator loads glyph outlines via msdfgen's FreeType integration, applies
 * edge-coloring and projection, then hands the resulting pixel data to the
 * {@link CgPagedGlyphAtlas} for GPU upload.</p>
 *
 * <h3>Visibility</h3>
 * <p>The class is {@code public} because the debug harness and
 * {@link CgGlyphKey} reference
 * its constants.  Normal rendering code should not instantiate this class
 * directly &mdash; it is owned and called by {@link CgFontRegistry}.</p>
 *
 * <h3>Reading Order</h3>
 * <ol>
 *   <li><strong>Paged generation</strong> &mdash; {@link #preparePagedGlyphWithinBudget} /
 *       {@link #preparePagedGlyph} (the only path)</li>
 *   <li><strong>Shape preparation</strong> &mdash; normalize, orient, edge-color</li>
 *   <li><strong>Heuristics / utilities</strong> &mdash; cell sizing, complexity threshold, row flip</li>
 * </ol>
 *
 * <h3>Coordinate Convention</h3>
 * <p>Glyphs are loaded with EM-normalized coordinates and then mapped into atlas
 * pixels using explicit layout math.</p>
 *
 * <h3>Error Correction</h3>
 * <p>MSDF generation uses {@code ERROR_CORRECTION_DISABLED} because the
 * default internal error-correction pass in msdfgen's {@code generateMSDF}
 * has been observed to crash on certain glyph shapes over time
 * ({@code EXCEPTION_ACCESS_VIOLATION} in {@code freetype_msdfgen_harfbuzz_jni.dll}).
 * At the cell sizes used here (32-64px) the artifacts that error correction
 * fixes are imperceptible.</p>
 */
public class CgMsdfGenerator {

    private static final Logger LOGGER = Logger.getLogger(CgMsdfGenerator.class.getName());

    public static final float PX_RANGE = CgMsdfAtlasConfig.DEFAULT_PX_RANGE;
    public static final int MAX_PER_FRAME = 4;
    static final int COMPLEXITY_EDGE_THRESHOLD = 24;
    static final int SIMPLE_MSDF_MIN_PX = 32;
    static final int COMPLEX_MSDF_MIN_PX = 48;

    /**
     * Synthetic-bold strength as a fraction of 1 em — Skia's pixel-space formula is
     * {@code strength_px = pixelSize / 24} (see {@code FTFace.outlineEmbolden} javadoc); since
     * glyphs here are loaded EM-normalized ({@link FreeTypeMSDFIntegration#FONT_SCALING_EM_NORMALIZED},
     * 1.0 unit = 1 em = the current pixel size), the same strength expressed as a fraction of
     * em is size-independent: {@code (pixelSize / 24) / pixelSize = 1 / 24}.
     */
    private static final double SYNTHETIC_BOLD_STRENGTH_EM = 1.0 / 24.0;

    /**
     * Synthetic-oblique shear magnitude (0.25, matching Skia's constant) — positive here, not
     * Skia's {@code -0.25}: msdfgen's {@link MSDFShape} coordinates are Y-up (same font-design
     * convention as FreeType's outline space), while Skia's constant is expressed in its own
     * Y-down screen-space convention. See {@code CgFontRegistry.SYNTHETIC_ITALIC_SKEW}, which
     * hit the same sign flip (confirmed empirically — negative leaned backslash-direction).
     */
    private static final double SYNTHETIC_ITALIC_SKEW = 0.25;

    private int generatedThisFrame;

    public CgMsdfGenerator() {
        this.generatedThisFrame = 0;
    }

    public CgGlyphGenerationResult preparePagedGlyphWithinBudget(CgGlyphKey key,
                                                          CgFontKey sourceFontKey,
                                                          FreeTypeMSDFIntegration.Font font,
                                                          CgMsdfAtlasKey atlasKey) {
        if (generatedThisFrame >= MAX_PER_FRAME) {
            return null;
        }
        CgGlyphGenerationResult result = preparePagedGlyph(
                key,
                sourceFontKey,
                font,
                atlasKey,
                atlasKey.getConfig());
        if (result != null && !result.isEmptyGeometry()) {
            generatedThisFrame++;
        }
        return result;
    }

    public static CgGlyphGenerationResult preparePagedGlyph(CgGlyphKey key,
                                                     CgFontKey sourceFontKey,
                                                     FreeTypeMSDFIntegration.Font font,
                                                     CgMsdfAtlasKey atlasKey,
                                                     CgMsdfAtlasConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }

        FreeTypeMSDFIntegration.GlyphData glyphData;
        try {
            glyphData = font.loadGlyphByIndex(key.getGlyphId(), FreeTypeMSDFIntegration.FONT_SCALING_EM_NORMALIZED);
        } catch (MSDFException e) {
            // WARNING, matching the bitmap rasterization path's severity for the
            // same class of failure (CgFontRegistry's "Failed to rasterize glyph..."
            // catch) — this call site has a known history of native crashes in
            // msdfgen (see class javadoc), so silently degrading at FINE is
            // under-alarmed for a failure mode already known to be real.
            LOGGER.log(Level.WARNING, "Failed to load glyph index " + key.getGlyphId(), e);
            return null;
        }

        // Freed explicitly in the finally block below on every exit path (empty
        // shape, empty layout, or successful generation) — safe now that
        // MSDFShape.free() is idempotent/thread-safe against a concurrent
        // finalizer (see its javadoc), so this no longer has to leave the
        // shape's native memory to be reclaimed whenever GC gets to it.
        MSDFShape shape = glyphData.getShape();
        try {
            if (shape.getEdgeCount() == 0) {
                return CgGlyphGenerationResult.emptyMsdf(sourceFontKey, key, atlasKey, config.pxRange());
            }
            normalizeShape(shape);
            // Synthetic italic (shear) is a plain affine transform on the vector geometry —
            // always safe, preserves every edge relationship exactly. Synthetic BOLD is
            // deliberately NOT done as a geometry edit here (see SYNTHETIC_BOLD_STRENGTH_EM's
            // javadoc): naive per-vertex "inflate" schemes on Bezier curves reliably produce
            // self-intersecting slivers on tight curves (o/d/g counters) — exactly the garbled
            // look this replaces. Bold is applied further down as a distance-field threshold
            // bias instead, the standard technique real SDF/MSDF text engines use (e.g. Slug,
            // TextMeshPro's "Face Dilate") — mathematically safe on a continuous field with no
            // topology to break.
            if (key.isSyntheticItalic()) {
                MSDFShapeSynthesis.shear(shape, SYNTHETIC_ITALIC_SKEW);
            }
            orientAndColorShape(shape, key.getGlyphId(), config);

            int targetPx = config.atlasScalePx();

            // A distance-field bias needs headroom in the stored range to still show a smooth
            // (anti-aliased) transition at the new, dilated edge — the normal pxRange only
            // budgets for AA at the shape's own true edge. Widen it for synthetic-bold glyphs
            // specifically, both for the layout/generation below and the placement's stored
            // pxRange (read back by the shader at draw time), so the two stay consistent.
            float effectivePxRange = config.pxRange();
            if (key.isSyntheticBold()) {
                effectivePxRange += 2f * (float) (SYNTHETIC_BOLD_STRENGTH_EM * targetPx);
            }

            double[] bounds = shape.getBounds();
            if (config.miterLimit() > 0.0f) {
                double border = (effectivePxRange * 0.5) / targetPx;
                bounds = shape.getBoundsMiters(bounds, border, config.miterLimit(), 1);
            }
            double shapeL = bounds[0];
            double shapeB = bounds[1];
            double shapeR = bounds[2];
            double shapeT = bounds[3];

            CgMsdfGlyphLayout layout = CgMsdfGlyphLayout.compute(
                    shapeL, shapeB, shapeR, shapeT,
                    targetPx,
                    effectivePxRange,
                    config.miterLimit(),
                    config.alignOriginX(),
                    config.alignOriginY());

            if (layout.isEmpty()) {
                return CgGlyphGenerationResult.emptyMsdf(sourceFontKey, key, atlasKey, config.pxRange());
            }

            int boxWidth = layout.getBoxWidth();
            int boxHeight = layout.getBoxHeight();
            double scale = layout.getScale();
            double tx = layout.getTranslateX();
            double ty = layout.getTranslateY();
            double rangeInShapeUnits = layout.getRangeInShapeUnits();

            MSDFBitmap bitmap = config.mtsdf()
                    ? MSDFBitmap.allocMtsdf(boxWidth, boxHeight)
                    : MSDFBitmap.allocMsdf(boxWidth, boxHeight);
            MSDFTransform transform = new MSDFTransform()
                    .scale(scale)
                    .translate(tx, ty)
                    .range(-rangeInShapeUnits, rangeInShapeUnits);
            try {
                if (config.mtsdf()) {
                    MSDFGenerator.generateMtsdf(bitmap, shape, transform,
                            config.overlapSupport(),
                            config.errorCorrectionMode(),
                            config.distanceCheckMode(),
                            config.minDeviationRatio(),
                            config.minImproveRatio());
                } else {
                    MSDFGenerator.generateMsdf(bitmap, shape, transform,
                            config.overlapSupport(),
                            config.errorCorrectionMode(),
                            config.distanceCheckMode(),
                            config.minDeviationRatio(),
                            config.minImproveRatio());
                }

                float[] pixelData = bitmap.getPixelData();
                int channels = config.mtsdf() ? 4 : 3;
                flipRows(pixelData, boxWidth, boxHeight, channels);

                if (key.isSyntheticBold()) {
                    // Dilate: shift every distance-carrying channel (R/G/B — never MTSDF's
                    // alpha) toward "inside" by the same fraction of the stored range that
                    // SYNTHETIC_BOLD_STRENGTH_EM represents in shape units. This moves the
                    // reconstructed 0.5-threshold edge outward by exactly that amount,
                    // uniformly, on the already-correct (unmodified) shape's distance field —
                    // no geometry, no self-intersection risk.
                    float bias = (float) (SYNTHETIC_BOLD_STRENGTH_EM / (2.0 * rangeInShapeUnits));
                    applyBoldBias(pixelData, channels, bias);
                }

                float bearingX = (float) (layout.getPlaneLeft() * scale);
                float bearingY = (float) (layout.getPlaneTop() * scale);
                float planeLeft = (float) (layout.getPlaneLeft() * scale);
                float planeBottom = (float) (layout.getPlaneBottom() * scale);
                float planeRight = (float) (layout.getPlaneRight() * scale);
                float planeTop = (float) (layout.getPlaneTop() * scale);
                float metricsWidth = (float) ((shapeR - shapeL) * scale);
                float metricsHeight = (float) ((shapeT - shapeB) * scale);

                return CgGlyphGenerationResult.msdf(sourceFontKey, key, atlasKey, pixelData, boxWidth, boxHeight,
                        bearingX, bearingY,
                        planeLeft, planeBottom, planeRight, planeTop,
                        metricsWidth, metricsHeight, effectivePxRange);
            } finally {
                bitmap.free();
            }
        } finally {
            shape.free();
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

    public static boolean shouldUseMsdf(MSDFShape shape, int fontPx) {
        int totalEdges = shape.getEdgeCount();
        if (totalEdges > COMPLEXITY_EDGE_THRESHOLD) {
            return fontPx >= COMPLEX_MSDF_MIN_PX;
        }
        return fontPx >= SIMPLE_MSDF_MIN_PX;
    }

    public static void applyEdgeColoring(MSDFShape shape, CgMsdfAtlasConfig config) {
        CgMsdfEdgeColoringMode mode = config.edgeColoringMode();
        double threshold = config.edgeColoringAngleThreshold();
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
     * Split into two halves so synthetic bold/italic (see
     * {@link MSDFShapeSynthesis}) can run between them — normalize first (topological
     * cleanup: splits mixed segments, on the shape as FreeType/msdfgen actually produced it),
     * then synthesize, then orient+color the FINAL synthesized geometry. Coloring must see the
     * post-synthesis shape: edge colors are assigned from corner angles, and assigning them
     * before a geometric change (especially embolden, which meaningfully alters corner
     * sharpness) leaves them describing angles that no longer exist, which is exactly the
     * kind of mismatch that produces MSDF reconstruction artifacts.
     */
    private static void normalizeShape(MSDFShape shape) {
        shape.normalize();
    }

    private static void orientAndColorShape(MSDFShape shape, int glyphId, CgMsdfAtlasConfig config) {
        double[] bounds = shape.getBounds();
        double outerX = bounds[0] - (bounds[2] - bounds[0]) - 1.0;
        double outerY = bounds[1] - (bounds[3] - bounds[1]) - 1.0;
        if (shape.getOneShotDistance(outerX, outerY) > 0.0) {
            for (int i = 0; i < shape.getContourCount(); i++) {
                shape.getContour(i).reverse();
            }
        }
        if (!shape.validate()) {
            LOGGER.log(Level.WARNING,
                    "MSDF shape validation failed for glyph {0}; continuing with normalized shape",
                    Integer.valueOf(glyphId));
        }
        applyEdgeColoring(shape, config);
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
    public static void flipRows(float[] pixels, int width, int height, int channels) {
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

    /**
     * Adds {@code bias} to every distance-carrying channel (R/G/B; MTSDF's 4th/alpha channel
     * is untouched — see {@code text.shader}'s MSDF path, which only ever reads {@code .rgb}),
     * clamped to {@code [0,1]}. See the synthetic-bold call site in
     * {@link #preparePagedGlyph} for why this replaces a geometry-level embolden.
     */
    private static void applyBoldBias(float[] pixels, int channels, float bias) {
        int distanceChannels = Math.min(channels, 3);
        for (int i = 0; i < pixels.length; i += channels) {
            for (int c = 0; c < distanceChannels; c++) {
                float v = pixels[i + c] + bias;
                pixels[i + c] = v < 0f ? 0f : (v > 1f ? 1f : v);
            }
        }
    }

    public int getGeneratedThisFrame() {
        return generatedThisFrame;
    }

    public void simulateGeneration() {
        generatedThisFrame++;
    }
}
