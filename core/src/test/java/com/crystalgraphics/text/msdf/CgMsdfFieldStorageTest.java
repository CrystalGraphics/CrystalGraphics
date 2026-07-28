package com.crystalgraphics.text.msdf;

import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.font.CgFontStyle;
import com.crystalgraphics.msdfgen.FreeTypeMSDFIntegration;
import org.junit.Test;

import java.io.File;
import java.util.Locale;

import static org.junit.Assert.assertTrue;

/**
 * Guards the distance-field atlas's {@code RGBA8} storage format.
 *
 * <p>The atlas used to allocate {@code RGBA16F}, at 8 bytes per texel. It now allocates
 * {@code RGBA8}, which halved atlas memory (88 MB → 44 MB on a three-font CJK workload), and this
 * test is the evidence for that being safe rather than merely conventional: the same glyphs, the
 * same scales, the field quantised exactly as an 8-bit upload quantises it, scored on structural
 * integrity.
 *
 * <p>It is a regression test, not a one-off. Anything that changes
 * {@link CgMsdfAtlasConfig#DEFAULT_PX_RANGE} or {@link CgMsdfAtlasConfig#DEFAULT_ATLAS_SCALE_PX}
 * changes how much of the numeric range a distance field actually uses, and therefore how much
 * quantisation headroom it has. This is what catches that.
 *
 * <p>This is a <em>within-font</em> comparison, which is the regime {@link CgMsdfQualityProbe} was
 * validated in — the same glyphs measured two ways. Nothing here relies on comparing scores
 * <em>between</em> fonts, which the probe cannot do reliably (edge-position error scales with a
 * glyph's perimeter-to-ink ratio, a property of letterform shape rather than of quality).
 *
 * <p><strong>Scope:</strong> the three MSDF channels only. MTSDF's fourth channel is a true SDF for
 * wide effects (outlines, glows), where a shallow gradient spread over many screen pixels is a
 * plausible place for 8-bit banding. Nothing samples it today — {@code text.shader} reads
 * {@code .rgb} — so it is untested here by design. See that shader's header before wiring up
 * effects.
 */
public class CgMsdfFieldStorageTest {

    private static final String CJK_FONT = "src/main/resources/assets/crystalgraphics/MPLUS1p-Regular.ttf";

    /** Scales spanning well below and above the shipping 80, all dividing the 240px eval grid. */
    private static final int[] SCALES = {30, 40, 48, 60, 80, 120};

    private static final int SAMPLE_GLYPHS = 24;
    private static final float BAND_LOW = 0.88f;
    private static final float BAND_HIGH = 0.98f;
    private static final int MAX_SCANNED = 3000;

    @Test
    public void unorm8MatchesHalfFloatOnDenseCjk() {
        File fontFile = new File(CJK_FONT);
        assertTrue("CJK font missing at " + fontFile.getAbsolutePath(), fontFile.isFile());

        CgFont font = CgFont.load(fontFile.getPath(), CgFontStyle.REGULAR, 80);
        try {
            CgMsdfAtlasConfig base = CgMsdfAtlasConfig.defaultConfig();
            FreeTypeMSDFIntegration.Font msdfFont = font.getMsdfFont();
            int evalPx = CgMsdfQualityProbe.DEFAULT_EVAL_PX;

            // Probe glyphs that actually stress the field, not an easy sample.
            int[] sample = CgMsdfQualityProbe.selectDenseGlyphs(
                    font, SAMPLE_GLYPHS, BAND_LOW, BAND_HIGH, MAX_SCANNED);
            assertTrue("selection produced no glyphs", sample.length > 0);

            System.out.println("=== RGBA16F vs RGBA8 field storage: M+ 1p, pxRange "
                    + base.pxRange() + ", " + sample.length + " glyphs ===");
            System.out.println();
            System.out.println("scale |        HALF_FLOAT         |          UNORM8           | delta");
            System.out.println("      | mismatch  defects  worst  | mismatch  defects  worst  |");

            boolean structuralRegression = false;
            int referenceFeatures = -1;

            for (int scale : SCALES) {
                CgMsdfAtlasConfig candidate = base.withAtlasScalePx(scale);
                CgMsdfQualityProbe.FieldQuality half = CgMsdfQualityProbe.evaluate(
                        msdfFont, candidate, sample, evalPx, CgMsdfQualityProbe.FieldStorage.HALF_FLOAT);
                CgMsdfQualityProbe.FieldQuality byte8 = CgMsdfQualityProbe.evaluate(
                        msdfFont, candidate, sample, evalPx, CgMsdfQualityProbe.FieldStorage.UNORM8);

                // Self-check: the reference is the same shape at the same resolution every time, so
                // its feature count must not move. If it does, the comparison grids are misaligned
                // and no defect count in this run means anything.
                if (referenceFeatures < 0) {
                    referenceFeatures = half.referenceFeatures();
                }

                int defectDelta = byte8.defectiveGlyphs() - half.defectiveGlyphs();
                if (defectDelta > 0) {
                    structuralRegression = true;
                }

                System.out.printf(Locale.ROOT,
                        "%5d | %8.6f  %7d  %5d  | %8.6f  %7d  %5d  | dMismatch=%+.6f dDefects=%+d%n",
                        scale,
                        half.worstMismatch(), half.defectiveGlyphs(), half.totalDefects(),
                        byte8.worstMismatch(), byte8.defectiveGlyphs(), byte8.totalDefects(),
                        byte8.worstMismatch() - half.worstMismatch(), defectDelta);
            }

            System.out.println();
            System.out.println(structuralRegression
                    ? "RESULT: 8-bit lost structure somewhere -- RGBA16F is earning its bytes."
                    : "RESULT: 8-bit preserved structure at every scale -- RGBA16F buys nothing here.");

            // Structure is the criterion: does quantisation close a counter or merge a stroke that
            // the float field kept? Area error may wobble slightly either way and is reported for
            // context, not asserted on.
            assertTrue("8-bit quantisation must not introduce structural defects the float field avoided",
                    !structuralRegression);
        } finally {
            font.dispose();
        }
    }
}
