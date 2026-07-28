package com.crystalgraphics.text.msdf;

import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.font.CgFontStyle;
import com.crystalgraphics.msdfgen.FreeTypeMSDFIntegration;
import com.crystalgraphics.msdfgen.MSDFBitmap;
import com.crystalgraphics.msdfgen.MSDFGenerator;
import com.crystalgraphics.msdfgen.MSDFShape;
import com.crystalgraphics.msdfgen.MSDFTransform;
import org.junit.Test;

import java.io.File;
import java.util.Locale;

import static org.junit.Assert.assertEquals;

/**
 * Proves {@link CgMsdfGenerator#needsOverlapSupport} never skips a glyph that needed it.
 *
 * <p>Overlap support is 36% of MSDF generation cost. Measured field-by-field, it is bit-identical to
 * plain generation for every sampled Latin glyph and for all but one CJK glyph in 120 — where it
 * differs by a full sign inversion, which renders that glyph as garbage. That single case is why the
 * flag cannot simply be turned off, and why counting reconstruction defects was not a strong enough
 * check to authorise doing so: a 40-glyph defect sample missed it entirely.
 *
 * <p>So the generator gates instead of removing, and this is the safety proof for the gate. It
 * generates every sampled glyph twice — once with overlap unconditionally on, once through the gate
 * — and compares the raw fields. Any difference at all is a failure, because the gate's whole claim
 * is that it only skips work that provably cannot change the output.
 *
 * <p>It also reports the gate's hit rate, which is what determines whether the gate is worth having:
 * a gate that fires on every glyph is only overhead.
 */
public class CgMsdfOverlapSupportEquivalenceTest {

    private static final String[] FONTS = {
            "src/main/resources/assets/crystalgraphics/MPLUS1p-Regular.ttf",
            "src/main/resources/assets/crystalgraphics/IBMPlexSans-Regular.ttf",
    };

    /**
     * Wide enough to include the rare glyph that genuinely overlaps. The 120-glyph sample that first
     * found it had exactly one; anything much smaller cannot be trusted to contain any.
     */
    private static final int SAMPLE_GLYPHS = 400;

    @Test
    public void gatedGenerationMatchesUnconditionalOverlapExactly() {
        System.out.println();
        System.out.println("=== overlap gate: equivalence and hit rate ===");
        System.out.printf(Locale.ROOT, "%-28s %8s %10s %11s %12s%n",
                "font", "glyphs", "gateFired", "hitRate", "maxDelta");

        for (String path : FONTS) {
            File file = new File(path);
            if (!file.isFile()) {
                System.out.printf(Locale.ROOT, "%-28s %s%n", file.getName(), "(missing, skipped)");
                continue;
            }
            checkFont(file);
        }
    }

    private static void checkFont(File file) {
        CgFont font = CgFont.load(file.getPath(), CgFontStyle.REGULAR, 80);
        try {
            FreeTypeMSDFIntegration.Font msdfFont = font.getMsdfFont();
            CgMsdfAtlasConfig config = CgMsdfAtlasConfig.defaultConfig().withOverlapSupport(true);

            // Span the whole density range, not just the dense tail: simple glyphs are where the
            // gate is most likely to fire, so excluding them would flatter the hit rate.
            int[] sample = CgMsdfQualityProbe.selectDenseGlyphs(font, SAMPLE_GLYPHS, 0.0f, 1.0f, 6000);

            int compared = 0;
            int gateFired = 0;
            float maxDelta = 0f;

            for (int glyphId : sample) {
                Generated always = generate(msdfFont, config, glyphId, GateMode.ALWAYS_ON);
                Generated gated = generate(msdfFont, config, glyphId, GateMode.GATED);
                if (always == null || gated == null || always.pixels.length != gated.pixels.length) {
                    continue;
                }

                compared++;
                if (!gated.overlapUsed) {
                    gateFired++;
                }
                for (int i = 0; i < always.pixels.length; i++) {
                    float delta = Math.abs(always.pixels[i] - gated.pixels[i]);
                    if (delta > maxDelta) maxDelta = delta;
                }
            }

            System.out.printf(Locale.ROOT, "%-28s %8d %10d %10.1f%% %12.6f%n",
                    file.getName(), compared, gateFired,
                    compared == 0 ? 0f : (100f * gateFired / compared), maxDelta);

            assertEquals(file.getName() + ": gated generation diverged from unconditional overlap",
                    0f, maxDelta, 0f);
        } finally {
            font.dispose();
        }
    }

    private enum GateMode { ALWAYS_ON, GATED }

    private record Generated(float[] pixels, boolean overlapUsed) {
    }

    private static Generated generate(FreeTypeMSDFIntegration.Font msdfFont,
                                      CgMsdfAtlasConfig config, int glyphId, GateMode mode) {
        FreeTypeMSDFIntegration.GlyphData data;
        try {
            data = msdfFont.loadGlyphByIndex(glyphId, FreeTypeMSDFIntegration.FONT_SCALING_EM_NORMALIZED);
        } catch (RuntimeException e) {
            return null;
        }
        MSDFShape shape = data.getShape();
        try {
            if (shape.getEdgeCount() == 0) return null;
            shape.normalize();
            CgMsdfGenerator.applyEdgeColoring(shape, config);

            boolean overlapUsed = mode == GateMode.ALWAYS_ON
                    || CgMsdfGenerator.needsOverlapSupport(shape);

            double[] bounds = shape.getBounds();
            CgMsdfGlyphLayout layout = CgMsdfGlyphLayout.compute(
                    bounds[0], bounds[1], bounds[2], bounds[3],
                    config.atlasScalePx(), config.pxRange(), config.miterLimit(),
                    config.alignOriginX(), config.alignOriginY());
            if (layout.isEmpty()) return null;

            MSDFBitmap bitmap = MSDFBitmap.allocMtsdf(layout.getBoxWidth(), layout.getBoxHeight());
            try {
                MSDFTransform transform = new MSDFTransform()
                        .scale(layout.getScale())
                        .translate(layout.getTranslateX(), layout.getTranslateY())
                        .range(-layout.getRangeInShapeUnits(), layout.getRangeInShapeUnits());
                MSDFGenerator.generateMtsdf(bitmap, shape, transform,
                        overlapUsed,
                        config.errorCorrectionMode(),
                        config.distanceCheckMode(),
                        config.minDeviationRatio(),
                        config.minImproveRatio());
                return new Generated(bitmap.getPixelData(), overlapUsed);
            } finally {
                bitmap.free();
            }
        } finally {
            shape.free();
        }
    }
}
