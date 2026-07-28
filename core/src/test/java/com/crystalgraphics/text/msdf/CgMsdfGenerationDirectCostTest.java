package com.crystalgraphics.text.msdf;

import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.font.CgFontStyle;
import com.crystalgraphics.msdfgen.FreeTypeMSDFIntegration;
import com.crystalgraphics.msdfgen.MSDFBitmap;
import com.crystalgraphics.msdfgen.MSDFConstants;
import com.crystalgraphics.msdfgen.MSDFGenerator;
import com.crystalgraphics.msdfgen.MSDFShape;
import com.crystalgraphics.msdfgen.MSDFTransform;
import org.junit.Test;

import java.io.File;
import java.util.Locale;

import static org.junit.Assert.assertTrue;

/**
 * Times {@code generateMtsdf} alone, with nothing else in the measurement.
 *
 * <p>Separate from {@link CgMsdfGenerationCostTest} because that one measures through
 * {@link CgMsdfQualityProbe}, which generates its own high-resolution reference SDF per glyph. That
 * reference is a large constant — roughly 95 ms of the ~108 ms it reports — and it compresses every
 * difference between variants into noise: a real 9x speedup reads as 1.16x once buried under it.
 *
 * <p>This isolates the call the profiler says is 99.3% of generation, so the numbers here are the
 * ones to act on. Quality still comes from the other test; speed comes from this one.
 */
public class CgMsdfGenerationDirectCostTest {

    private static final String CJK_FONT = "src/main/resources/assets/crystalgraphics/MPLUS1p-Regular.ttf";
    private static final int SAMPLE_GLYPHS = 150;

    /** {@code overlap == null} means "let {@link CgMsdfGenerator#needsOverlapSupport} decide". */
    private record Variant(String name, Boolean overlap, int correction, int distanceCheck) {
    }

    @Test
    public void reportsDirectGenerationCostPerFlag() {
        File fontFile = new File(CJK_FONT);
        if (!fontFile.isFile()) {
            System.out.println("[msdf-direct] CJK font missing, skipping");
            return;
        }

        CgFont font = CgFont.load(fontFile.getPath(), CgFontStyle.REGULAR, 80);
        try {
            CgMsdfAtlasConfig base = CgMsdfAtlasConfig.defaultConfig();
            FreeTypeMSDFIntegration.Font msdfFont = font.getMsdfFont();
            // Full density range rather than the dense tail: the gate's benefit depends on the mix
            // of glyphs an atlas actually builds, and sampling only the heaviest glyphs would both
            // overstate absolute cost and understate how often the gate fires.
            int[] sample = CgMsdfQualityProbe.selectDenseGlyphs(font, SAMPLE_GLYPHS, 0.0f, 1.0f, 6000);
            assertTrue(sample.length > 0);

            Variant[] variants = {
                    new Variant("CURRENT (overlap + edgePriority + checkAtEdge)",
                            true, MSDFConstants.ERROR_CORRECTION_EDGE_PRIORITY, MSDFConstants.DISTANCE_CHECK_AT_EDGE),
                    new Variant("overlap GATED (shipping)",
                            null, MSDFConstants.ERROR_CORRECTION_EDGE_PRIORITY, MSDFConstants.DISTANCE_CHECK_AT_EDGE),
                    new Variant("overlap OFF (unsafe, lower bound)",
                            false, MSDFConstants.ERROR_CORRECTION_EDGE_PRIORITY, MSDFConstants.DISTANCE_CHECK_AT_EDGE),
                    new Variant("overlap OFF + distanceCheck NONE",
                            false, MSDFConstants.ERROR_CORRECTION_EDGE_PRIORITY, MSDFConstants.DISTANCE_CHECK_NONE),
                    new Variant("overlap OFF + correction OFF",
                            false, MSDFConstants.ERROR_CORRECTION_DISABLED, MSDFConstants.DISTANCE_CHECK_NONE),
                    new Variant("overlap ON + correction OFF",
                            true, MSDFConstants.ERROR_CORRECTION_DISABLED, MSDFConstants.DISTANCE_CHECK_NONE),
            };

            System.out.println();
            System.out.printf(Locale.ROOT,
                    "=== generateMtsdf direct cost: %d dense CJK glyphs at scale %d ===%n",
                    sample.length, base.atlasScalePx());
            System.out.printf(Locale.ROOT, "%-48s %11s %10s%n", "variant", "ms/glyph", "speedup");

            double baseline = -1;
            for (Variant variant : variants) {
                CgMsdfAtlasConfig config = base
                        .withOverlapSupport(variant.overlap() == null || variant.overlap())
                        .withErrorCorrection(variant.correction(), variant.distanceCheck(),
                                base.minDeviationRatio(), base.minImproveRatio());

                generateAll(msdfFont, config, sample, variant.overlap()); // warm
                long start = System.nanoTime();
                int generated = generateAll(msdfFont, config, sample, variant.overlap());
                double msPerGlyph = (System.nanoTime() - start) / 1_000_000.0 / Math.max(1, generated);

                if (baseline < 0) baseline = msPerGlyph;
                System.out.printf(Locale.ROOT, "%-48s %11.3f %9.2fx%n",
                        variant.name(), msPerGlyph, baseline / msPerGlyph);
            }
        } finally {
            font.dispose();
        }
    }

    /** Mirrors CgMsdfGenerator.prepareGlyph's generation stage exactly, minus bookkeeping. */
    private static int generateAll(FreeTypeMSDFIntegration.Font msdfFont, CgMsdfAtlasConfig config,
                                   int[] glyphIds, Boolean overlapOverride) {
        int generated = 0;
        for (int glyphId : glyphIds) {
            FreeTypeMSDFIntegration.GlyphData data;
            try {
                data = msdfFont.loadGlyphByIndex(glyphId, FreeTypeMSDFIntegration.FONT_SCALING_EM_NORMALIZED);
            } catch (RuntimeException e) {
                continue;
            }
            MSDFShape shape = data.getShape();
            try {
                if (shape.getEdgeCount() == 0) continue;
                shape.normalize();
                CgMsdfGenerator.applyEdgeColoring(shape, config);

                double[] bounds = shape.getBounds();
                CgMsdfGlyphLayout layout = CgMsdfGlyphLayout.compute(
                        bounds[0], bounds[1], bounds[2], bounds[3],
                        config.atlasScalePx(), config.pxRange(), config.miterLimit(),
                        config.alignOriginX(), config.alignOriginY());
                if (layout.isEmpty()) continue;

                MSDFBitmap bitmap = MSDFBitmap.allocMtsdf(layout.getBoxWidth(), layout.getBoxHeight());
                try {
                    MSDFTransform transform = new MSDFTransform()
                            .scale(layout.getScale())
                            .translate(layout.getTranslateX(), layout.getTranslateY())
                            .range(-layout.getRangeInShapeUnits(), layout.getRangeInShapeUnits());
                    boolean overlap = overlapOverride != null
                            ? overlapOverride
                            : CgMsdfGenerator.needsOverlapSupport(shape);
                    MSDFGenerator.generateMtsdf(bitmap, shape, transform,
                            overlap,
                            config.errorCorrectionMode(),
                            config.distanceCheckMode(),
                            config.minDeviationRatio(),
                            config.minImproveRatio());
                    generated++;
                } finally {
                    bitmap.free();
                }
            } finally {
                shape.free();
            }
        }
        return generated;
    }
}
