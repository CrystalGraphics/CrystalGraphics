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
import com.crystalgraphics.text.atlas.CgGlyphAtlas;
import com.crystalgraphics.text.cache.CgFontRegistry;
import com.crystalgraphics.text.cache.CgGlyphGenerationResult;
import com.crystalgraphics.text.cache.CgMsdfAtlasKey;

import java.util.logging.Level;
import java.util.logging.Logger;
import com.crystalgraphics.util.profiling.CgProfiler;

/**
 * Render-thread MSDF generator for glyph atlases.
 *
 * <p>This class converts glyph outlines from the local msdfgen bindings into
 * RGB float MSDF images and uploads them into a {@link CgGlyphAtlas}. A strict
 * per-frame budget is enforced to avoid frame spikes. When generation is not
 * allowed for the current glyph or budget, callers are expected to use the
 * bitmap fallback path.</p>
 *
 * <h3>Pipeline Role</h3>
 * <p>CgMsdfGenerator is the <em>render-thread MSDF rasterizer</em> in the glyph
 * cache pipeline.  {@link CgFontRegistry} delegates paged MSDF generation here.
 * The generator loads glyph outlines via msdfgen's FreeType integration, applies
 * edge-coloring and projection, then hands the resulting pixel data to the
 * {@link CgGlyphAtlas} for GPU upload.</p>
 *
 * <h3>Visibility</h3>
 * <p>The class is {@code public} because the debug harness and
 * {@link CgGlyphKey} reference
 * its constants.  Normal rendering code should not instantiate this class
 * directly &mdash; it is owned and called by {@link CgFontRegistry}.</p>
 *
 * <h3>Reading Order</h3>
 * <ol>
 *   <li><strong>Paged generation</strong> &mdash; {@link #prepareGlyphWithinBudget} /
 *       {@link #prepareGlyph} (the only path)</li>
 *   <li><strong>Shape preparation</strong> &mdash; normalize, orient, edge-color</li>
 *   <li><strong>Heuristics / utilities</strong> &mdash; cell sizing, complexity threshold, row flip</li>
 * </ol>
 *
 * <h3>Coordinate Convention</h3>
 * <p>Glyphs are loaded with EM-normalized coordinates and then mapped into atlas
 * pixels using explicit layout math.</p>
 *
 * <h3>Error Correction</h3>
 * <p>MSDF generation uses {@code ERROR_CORRECTION_EDGE_PRIORITY} (see
 * {@link CgMsdfAtlasConfig#DEFAULT_ERROR_CORRECTION_MODE}).</p>
 *
 * <p>This was previously disabled, on the belief that msdfgen's error-correction pass crashed on
 * certain glyph shapes — {@code EXCEPTION_ACCESS_VIOLATION} in
 * {@code freetype_msdfgen_harfbuzz_jni.dll}. That diagnosis was wrong. The crash was a reachability
 * race in the bindings: generation passes raw handles owned by finalizable wrappers into a native
 * call lasting 13-32 ms, and the wrappers could be collected and finalized — freeing those handles —
 * while the call was still running. Error correction was implicated only because it widened the
 * window. See {@code NativeReachability} and the "finalizers can free a handle mid-call" section of
 * {@code freetype-msdfgen-harfbuzz-bindings/AGENTS.md}.</p>
 */
public class CgMsdfGenerator {

    private static final Logger LOGGER = Logger.getLogger(CgMsdfGenerator.class.getName());

    public static final float PX_RANGE = CgMsdfAtlasConfig.DEFAULT_PX_RANGE;

    /**
     * Hard ceiling on synchronous generations per frame, retained as a backstop under
     * {@link #FRAME_BUDGET_NANOS}. The time budget is the real limit; this only bounds per-glyph
     * fixed overhead in the degenerate case where every glyph is nearly free.
     */
    public static final int MAX_PER_FRAME = 4;

    /**
     * How much wall time synchronous glyph generation may take from a single frame.
     *
     * <p>This used to be a count of four glyphs, which silently assumed glyphs cost the same. They
     * do not: measured on the shipped fonts, one glyph ranges from under a millisecond for simple
     * Latin to 32 ms for dense CJK, because msdfgen's cost scales with box area times edge count.
     * Four of the expensive kind is 128 ms — an eight-frame stall at 60 Hz, produced by a limit
     * whose whole purpose was to prevent frame spikes.
     *
     * <p>Two milliseconds is a slice a frame can absorb, and the check deliberately reads the budget
     * <em>before</em> generating rather than predicting cost. That makes the behaviour fall out
     * without a special case: the first glyph of a frame always proceeds, since nothing has been
     * spent yet, so progress is guaranteed even when a single glyph costs more than the entire
     * budget — but it also exhausts the frame, so an expensive glyph stalls once instead of four
     * times. Cheap glyphs keep fitting several per frame, which is the common case.
     *
     * <p>Glyphs refused here are not lost. They fall back to the bitmap path for the frame and are
     * picked up by the background generation executor, so the visible cost is a few frames at lower
     * fidelity rather than a hitch.
     */
    private static final long FRAME_BUDGET_NANOS = 2_000_000L;
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
    private long generationNanosThisFrame;

    public CgMsdfGenerator() {
        this.generatedThisFrame = 0;
        this.generationNanosThisFrame = 0L;
    }

    public CgGlyphGenerationResult prepareGlyphWithinBudget(CgGlyphKey key,
                                                            CgFontKey sourceFontKey,
                                                            FreeTypeMSDFIntegration.Font font,
                                                            CgMsdfAtlasKey atlasKey) {
        if (generatedThisFrame >= MAX_PER_FRAME || generationNanosThisFrame >= FRAME_BUDGET_NANOS) {
            CgProfiler.count("msdfgen.syncBudgetRefused");
            return null;
        }
        long start = System.nanoTime();
        CgGlyphGenerationResult result = prepareGlyph(
                key,
                sourceFontKey,
                font,
                atlasKey,
                atlasKey.getConfig());
        // Charged even for empty/failed geometry: the frame spent the time either way, and not
        // charging it would let a run of failures blow the budget without ever tripping it.
        generationNanosThisFrame += System.nanoTime() - start;
        if (result != null && !result.isEmptyGeometry()) {
            generatedThisFrame++;
        }
        return result;
    }

    public static CgGlyphGenerationResult prepareGlyph(CgGlyphKey key,
                                                       CgFontKey sourceFontKey,
                                                       FreeTypeMSDFIntegration.Font font,
                                                       CgMsdfAtlasKey atlasKey,
                                                       CgMsdfAtlasConfig config) {
        try (CgProfiler.Scope ignored = CgProfiler.scope("msdfgen.prepareGlyph")) {
            return prepareGlyphInternal(key, sourceFontKey, font, atlasKey, config);
        }
    }

    private static CgGlyphGenerationResult prepareGlyphInternal(CgGlyphKey key,
                                                       CgFontKey sourceFontKey,
                                                       FreeTypeMSDFIntegration.Font font,
                                                       CgMsdfAtlasKey atlasKey,
                                                       CgMsdfAtlasConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }

        FreeTypeMSDFIntegration.GlyphData glyphData;
        try (CgProfiler.Scope ignored = CgProfiler.scope("msdfgen.loadGlyph")) {
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
            try (CgProfiler.Scope ignored = CgProfiler.scope("msdfgen.normalize")) {
                normalizeShape(shape);
            }
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
            try (CgProfiler.Scope ignored = CgProfiler.scope("msdfgen.edgeColor")) {
                orientAndColorShape(shape, key.getGlyphId(), config);
            }

            int targetPx = config.atlasScalePx();

            // A distance-field bias needs headroom in the stored range to still show a smooth
            // (anti-aliased) transition at the new, dilated edge — the normal pxRange only
            // budgets for AA at the shape's own true edge. Widen it for synthetic-bold glyphs
            // specifically, both for the layout/generation below and the placement's stored
            // pxRange (read back by the shader at draw time), so the two stay consistent.
            // Headroom needed is the actual per-edge dilation in pixels -- see applyBoldBias's
            // call site below for why that's SYNTHETIC_BOLD_STRENGTH_EM/2, not the full value.
            float effectivePxRange = config.pxRange();
            if (key.isSyntheticBold()) {
                effectivePxRange += (float) (SYNTHETIC_BOLD_STRENGTH_EM * targetPx);
            }

            double[] bounds;
            try (CgProfiler.Scope ignored = CgProfiler.scope("msdfgen.bounds")) {
                bounds = shape.getBounds();
                if (config.miterLimit() > 0.0f) {
                    double border = (effectivePxRange * 0.5) / targetPx;
                    bounds = shape.getBoundsMiters(bounds, border, config.miterLimit(), 1);
                }
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

            MSDFBitmap bitmap;
            try (CgProfiler.Scope ignored = CgProfiler.scope("msdfgen.allocBitmap")) {
                bitmap = config.mtsdf()
                        ? MSDFBitmap.allocMtsdf(boxWidth, boxHeight)
                        : MSDFBitmap.allocMsdf(boxWidth, boxHeight);
            }
            MSDFTransform transform = new MSDFTransform()
                    .scale(scale)
                    .translate(tx, ty)
                    .range(-rangeInShapeUnits, rangeInShapeUnits);
            try {
                // Only pay for overlap resolution on shapes whose contours can actually meet.
                boolean overlapSupport;
                try (CgProfiler.Scope ignored = CgProfiler.scope("msdfgen.overlapGate")) {
                    overlapSupport = config.overlapSupport() && needsOverlapSupport(shape);
                }
                CgProfiler.sample("msdfgen.overlapUsed", overlapSupport ? 1 : 0);

                try (CgProfiler.Scope ignored = CgProfiler.scope("msdfgen.generate")) {
                    if (config.mtsdf()) {
                        MSDFGenerator.generateMtsdf(bitmap, shape, transform,
                                overlapSupport,
                                config.errorCorrectionMode(),
                                config.distanceCheckMode(),
                                config.minDeviationRatio(),
                                config.minImproveRatio());
                    } else {
                        MSDFGenerator.generateMsdf(bitmap, shape, transform,
                                overlapSupport,
                                config.errorCorrectionMode(),
                                config.distanceCheckMode(),
                                config.minDeviationRatio(),
                                config.minImproveRatio());
                    }
                }

                float[] pixelData;
                try (CgProfiler.Scope ignored = CgProfiler.scope("msdfgen.readPixels")) {
                    pixelData = bitmap.getPixelData();
                }
                CgProfiler.sample("msdfgen.pixelFloats", pixelData.length);
                int channels = config.mtsdf() ? 4 : 3;
                try (CgProfiler.Scope ignored = CgProfiler.scope("msdfgen.flipRows")) {
                    flipRows(pixelData, boxWidth, boxHeight, channels);
                }

                if (key.isSyntheticBold()) {
                    // Dilate: shift every distance-carrying channel (R/G/B — never MTSDF's
                    // alpha) toward "inside" so the reconstructed 0.5-threshold edge moves
                    // outward, uniformly, on the already-correct (unmodified) shape's distance
                    // field — no geometry, no self-intersection risk.
                    //
                    // A uniform SDF bias dilates EVERY edge by the full bias amount, all
                    // around the shape -- unlike FreeType's FT_Outline_Embolden(strength),
                    // which the bitmap path uses and which moves each edge outward by only
                    // strength/2 (so a stroke's total width grows by strength, both edges
                    // combined; see FTFace#outlineEmbolden's javadoc). To match that same
                    // total growth here, each edge must dilate by SYNTHETIC_BOLD_STRENGTH_EM/2,
                    // not the full value -- using the full value here doubles the effective
                    // bold weight (both edges each growing by the full amount instead of half).
                    float bias = (float) ((SYNTHETIC_BOLD_STRENGTH_EM / 2.0) / (2.0 * rangeInShapeUnits));
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
        CgProfiler.sample("msdfgen.syncFrameMicros", generationNanosThisFrame / 1000L);
        generatedThisFrame = 0;
        generationNanosThisFrame = 0L;
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

    /**
     * Whether this shape can actually benefit from msdfgen's overlap-support path.
     *
     * <p>Overlap support is the most expensive generation flag by a wide margin — measured at 36% of
     * total generation time, or roughly 12ms down to 8ms per dense CJK glyph. It exists for outlines
     * built from overlapping contours, where the plain winding rule resolves the sign incorrectly
     * where two strokes cross. It cannot change the result anywhere else: with nothing to overlap,
     * per-contour resolution and whole-shape resolution agree by construction.
     *
     * <p>Measured on the shipped fonts, disabling it wholesale is bit-identical across all sampled
     * Latin glyphs, and identical for all but one CJK glyph in 120 — where it differs by a full sign
     * inversion. So the flag is nearly always dead weight and occasionally load-bearing, which makes
     * it a gating problem rather than something to turn off.
     *
     * <p>The gate is deliberately conservative: it compares axis-aligned contour bounds, which
     * over-approximate the contours themselves. Disjoint bounds prove the contours cannot touch, so
     * skipping is always safe; overlapping bounds do not prove they do touch, so some glyphs still
     * pay for overlap they never needed. That asymmetry is the right way round — a false "needed"
     * costs milliseconds, a false "not needed" corrupts a glyph.
     */
    static boolean needsOverlapSupport(MSDFShape shape) {
        int contourCount = shape.getContourCount();
        // A single contour has nothing to overlap with; the per-contour path degenerates to the
        // plain one. Zero contours cannot generate anything at all.
        if (contourCount < 2) {
            return false;
        }
        // Bounds are [minX, minY, maxX, maxY] in shape units, flattened to avoid n small arrays.
        double[] bounds = new double[contourCount * 4];
        for (int i = 0; i < contourCount; i++) {
            double[] contourBounds = shape.getContour(i).getBounds();
            System.arraycopy(contourBounds, 0, bounds, i * 4, 4);
        }
        for (int a = 0; a < contourCount; a++) {
            int ai = a * 4;
            for (int b = a + 1; b < contourCount; b++) {
                int bi = b * 4;
                boolean disjoint =
                        bounds[ai + 2] < bounds[bi] - BOUNDS_TOUCH_EPSILON      // a entirely left of b
                        || bounds[bi + 2] < bounds[ai] - BOUNDS_TOUCH_EPSILON   // b entirely left of a
                        || bounds[ai + 3] < bounds[bi + 1] - BOUNDS_TOUCH_EPSILON // a entirely below b
                        || bounds[bi + 3] < bounds[ai + 1] - BOUNDS_TOUCH_EPSILON; // b entirely below a
                if (!disjoint) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Slack applied when testing contour bounds for disjointness, in em-normalized shape units.
     *
     * <p>Signed the safe way: it treats bounds that merely come close as overlapping, so
     * floating-point noise in the bounds can only ever push a glyph onto the slower correct path,
     * never off it.
     */
    private static final double BOUNDS_TOUCH_EPSILON = 1e-6;

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
     * {@link #prepareGlyph} for why this replaces a geometry-level embolden.
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
