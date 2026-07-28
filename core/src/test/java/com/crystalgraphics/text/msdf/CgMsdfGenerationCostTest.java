package com.crystalgraphics.text.msdf;

import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.font.CgFontStyle;
import com.crystalgraphics.msdfgen.FreeTypeMSDFIntegration;
import com.crystalgraphics.msdfgen.MSDFConstants;
import org.junit.Test;

import java.io.File;
import java.util.Locale;

import static org.junit.Assert.assertTrue;

/**
 * Measures what each MSDF generation flag costs, and what it buys.
 *
 * <p>Generation is 12.9 ms per glyph, and 99.3% of that is the single native
 * {@code generateMtsdf} call — nothing on the Java side matters. So the only levers are the flags
 * handed to it, and the question for each is not "is it faster" but "is it faster <em>without
 * making glyphs worse</em>".
 *
 * <p>This reports both. Timing comes from generating a fixed sample of the densest glyphs in the
 * font; quality comes from {@link CgMsdfQualityProbe}, which reconstructs the field the way the
 * shader does and counts structural defects — closed counters and merged strokes. A configuration
 * is only interesting if it moves the time down and leaves defects unchanged.
 *
 * <p>Reports rather than asserts a winner: which trade to take is a judgement about acceptable
 * quality, and the point of this test is to put real numbers under that judgement instead of
 * guessing which flag is expensive.
 */
public class CgMsdfGenerationCostTest {

    private static final String CJK_FONT = "src/main/resources/assets/crystalgraphics/MPLUS1p-Regular.ttf";

    /** Enough to be representative; each config regenerates all of them. */
    private static final int SAMPLE_GLYPHS = 40;

    private record Variant(String name, boolean overlapSupport, int errorCorrection, int distanceCheck) {
    }

    @Test
    public void reportsCostAndQualityPerGenerationFlag() {
        File fontFile = new File(CJK_FONT);
        if (!fontFile.isFile()) {
            System.out.println("[msdf-cost] CJK font missing, skipping");
            return;
        }

        CgFont font = CgFont.load(fontFile.getPath(), CgFontStyle.REGULAR, 80);
        try {
            CgMsdfAtlasConfig base = CgMsdfAtlasConfig.defaultConfig();
            FreeTypeMSDFIntegration.Font msdfFont = font.getMsdfFont();

            int[] sample = CgMsdfQualityProbe.selectDenseGlyphs(font, SAMPLE_GLYPHS, 0.88f, 0.98f, 3000);
            assertTrue("selection produced no glyphs", sample.length > 0);

            Variant[] variants = {
                    new Variant("CURRENT (overlap, edgePriority, checkAtEdge)",
                            true, MSDFConstants.ERROR_CORRECTION_EDGE_PRIORITY, MSDFConstants.DISTANCE_CHECK_AT_EDGE),
                    new Variant("no overlap support",
                            false, MSDFConstants.ERROR_CORRECTION_EDGE_PRIORITY, MSDFConstants.DISTANCE_CHECK_AT_EDGE),
                    new Variant("no distance check",
                            true, MSDFConstants.ERROR_CORRECTION_EDGE_PRIORITY, MSDFConstants.DISTANCE_CHECK_NONE),
                    new Variant("no overlap + no distance check",
                            false, MSDFConstants.ERROR_CORRECTION_EDGE_PRIORITY, MSDFConstants.DISTANCE_CHECK_NONE),
                    new Variant("error correction OFF",
                            true, MSDFConstants.ERROR_CORRECTION_DISABLED, MSDFConstants.DISTANCE_CHECK_NONE),
                    new Variant("no overlap + correction OFF",
                            false, MSDFConstants.ERROR_CORRECTION_DISABLED, MSDFConstants.DISTANCE_CHECK_NONE),
            };

            System.out.println();
            System.out.printf(Locale.ROOT, "=== MSDF generation cost vs quality: %d dense glyphs, scale %d, pxRange %.1f ===%n",
                    sample.length, base.atlasScalePx(), base.pxRange());
            System.out.printf(Locale.ROOT, "%-42s %11s %10s %11s %9s%n",
                    "variant", "ms/glyph", "speedup", "defective", "worstArea");

            double baseline = -1;
            for (Variant variant : variants) {
                CgMsdfAtlasConfig config = base
                        .withOverlapSupport(variant.overlapSupport())
                        .withErrorCorrection(variant.errorCorrection(), variant.distanceCheck(),
                                base.minDeviationRatio(), base.minImproveRatio());

                // Warm once so JIT and any first-call native setup are not attributed to the run.
                CgMsdfQualityProbe.evaluate(msdfFont, config, new int[]{sample[0]},
                        CgMsdfQualityProbe.DEFAULT_EVAL_PX, CgMsdfQualityProbe.FieldStorage.UNORM8);

                long start = System.nanoTime();
                CgMsdfQualityProbe.FieldQuality quality = CgMsdfQualityProbe.evaluate(
                        msdfFont, config, sample,
                        CgMsdfQualityProbe.DEFAULT_EVAL_PX, CgMsdfQualityProbe.FieldStorage.UNORM8);
                double msPerGlyph = (System.nanoTime() - start) / 1_000_000.0 / sample.length;

                if (baseline < 0) baseline = msPerGlyph;
                System.out.printf(Locale.ROOT, "%-42s %11.3f %9.2fx %6d/%-4d %9.5f%n",
                        variant.name(), msPerGlyph, baseline / msPerGlyph,
                        quality.defectiveGlyphs(), quality.glyphsProbed(), quality.worstMismatch());
            }
            System.out.println();
            System.out.println("  NOTE: probe timing includes its own reference-SDF generation, so absolute");
            System.out.println("        ms/glyph is inflated versus production. Compare variants to each other.");
        } finally {
            font.dispose();
        }
    }
}
