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

import static org.junit.Assert.assertTrue;

/**
 * Establishes what MSDF generation cost is actually a function of.
 *
 * <p>Generation is 13 ms per glyph and 99.3% of it is one native call, so the only way to make it
 * cheaper is to reduce whatever that call scales with. msdfgen evaluates every edge of the shape at
 * every pixel of the box with no spatial acceleration, which predicts cost proportional to
 * {@code boxArea * edgeCount}. That prediction is worth checking rather than assuming, because the
 * two inputs suggest completely different fixes: if area dominates, shrinking the box or the padding
 * is the lever; if the product holds, only a spatial index over the edges changes anything.
 *
 * <p>Reports per-glyph geometry alongside measured time, plus how well cost correlates with area,
 * edges, and their product. Also reports how much of each box is padding added by the distance
 * range, which is the one part of the box that is a tuning choice rather than the glyph itself.
 */
public class CgMsdfCostModelTest {

    private static final String[] FONTS = {
            "src/main/resources/assets/crystalgraphics/MPLUS1p-Regular.ttf",
            "src/main/resources/assets/crystalgraphics/IBMPlexSans-Regular.ttf",
    };

    private static final int SAMPLE_GLYPHS = 120;

    private record Measurement(int boxW, int boxH, int edges, double ms, double inkArea) {
        int area() {
            return boxW * boxH;
        }
    }

    @Test
    public void reportsWhatGenerationCostScalesWith() {
        for (String path : FONTS) {
            File file = new File(path);
            if (!file.isFile()) {
                System.out.println("[cost-model] " + file.getName() + " missing, skipping");
                continue;
            }
            analyse(file);
        }
    }

    private static void analyse(File file) {
        CgFont font = CgFont.load(file.getPath(), CgFontStyle.REGULAR, 80);
        try {
            FreeTypeMSDFIntegration.Font msdfFont = font.getMsdfFont();
            CgMsdfAtlasConfig config = CgMsdfAtlasConfig.defaultConfig();
            int[] sample = CgMsdfQualityProbe.selectDenseGlyphs(font, SAMPLE_GLYPHS, 0.0f, 1.0f, 6000);
            assertTrue(sample.length > 0);

            // Warm so JIT and first-call native setup are not charged to the first glyphs.
            for (int i = 0; i < Math.min(10, sample.length); i++) {
                measure(msdfFont, config, sample[i]);
            }

            Measurement[] results = new Measurement[sample.length];
            int n = 0;
            for (int glyphId : sample) {
                Measurement m = measure(msdfFont, config, glyphId);
                if (m != null) results[n++] = m;
            }
            if (n == 0) return;

            double[] ms = new double[n], area = new double[n], edges = new double[n], product = new double[n];
            double totalMs = 0, totalArea = 0, totalInk = 0, totalEdges = 0;
            int minW = Integer.MAX_VALUE, maxW = 0;
            for (int i = 0; i < n; i++) {
                Measurement m = results[i];
                ms[i] = m.ms();
                area[i] = m.area();
                edges[i] = m.edges();
                product[i] = (double) m.area() * m.edges();
                totalMs += m.ms();
                totalArea += m.area();
                totalInk += m.inkArea();
                totalEdges += m.edges();
                minW = Math.min(minW, m.boxW());
                maxW = Math.max(maxW, m.boxW());
            }

            System.out.println();
            System.out.printf(Locale.ROOT, "=== cost model: %s, %d glyphs, scale %d, pxRange %.1f ===%n",
                    file.getName(), n, config.atlasScalePx(), config.pxRange());
            System.out.printf(Locale.ROOT, "  mean %.2f ms/glyph | mean box %.0f px^2 (w %d..%d) | mean %.0f edges%n",
                    totalMs / n, totalArea / n, minW, maxW, totalEdges / n);
            System.out.printf(Locale.ROOT, "  padding from pxRange: %.1f%% of box area is range expansion%n",
                    100.0 * (totalArea - totalInk) / totalArea);
            System.out.printf(Locale.ROOT, "  correlation with time:  area %.3f | edges %.3f | area*edges %.3f%n",
                    correlation(area, ms), correlation(edges, ms), correlation(product, ms));
            System.out.printf(Locale.ROOT, "  cost per area*edge unit: %.4f ns%n",
                    1e6 * totalMs / sum(product));
        } finally {
            font.dispose();
        }
    }

    private static Measurement measure(FreeTypeMSDFIntegration.Font msdfFont,
                                       CgMsdfAtlasConfig config, int glyphId) {
        FreeTypeMSDFIntegration.GlyphData data;
        try {
            data = msdfFont.loadGlyphByIndex(glyphId, FreeTypeMSDFIntegration.FONT_SCALING_EM_NORMALIZED);
        } catch (RuntimeException e) {
            return null;
        }
        MSDFShape shape = data.getShape();
        try {
            int edges = shape.getEdgeCount();
            if (edges == 0) return null;
            shape.normalize();
            CgMsdfGenerator.applyEdgeColoring(shape, config);

            double[] bounds = shape.getBounds();
            CgMsdfGlyphLayout layout = CgMsdfGlyphLayout.compute(
                    bounds[0], bounds[1], bounds[2], bounds[3],
                    config.atlasScalePx(), config.pxRange(), config.miterLimit(),
                    config.alignOriginX(), config.alignOriginY());
            if (layout.isEmpty()) return null;

            // The glyph's own extent at this scale, before the distance range expanded the box.
            double inkArea = (bounds[2] - bounds[0]) * config.atlasScalePx()
                    * (bounds[3] - bounds[1]) * config.atlasScalePx();

            MSDFBitmap bitmap = MSDFBitmap.allocMtsdf(layout.getBoxWidth(), layout.getBoxHeight());
            try {
                MSDFTransform transform = new MSDFTransform()
                        .scale(layout.getScale())
                        .translate(layout.getTranslateX(), layout.getTranslateY())
                        .range(-layout.getRangeInShapeUnits(), layout.getRangeInShapeUnits());
                long start = System.nanoTime();
                MSDFGenerator.generateMtsdf(bitmap, shape, transform,
                        config.overlapSupport() && CgMsdfGenerator.needsOverlapSupport(shape),
                        config.errorCorrectionMode(), config.distanceCheckMode(),
                        config.minDeviationRatio(), config.minImproveRatio());
                double ms = (System.nanoTime() - start) / 1_000_000.0;
                return new Measurement(layout.getBoxWidth(), layout.getBoxHeight(), edges, ms, inkArea);
            } finally {
                bitmap.free();
            }
        } finally {
            shape.free();
        }
    }

    private static double sum(double[] v) {
        double s = 0;
        for (double x : v) s += x;
        return s;
    }

    /** Pearson correlation — how well a candidate cost driver tracks measured time. */
    private static double correlation(double[] x, double[] y) {
        int n = x.length;
        double mx = sum(x) / n, my = sum(y) / n;
        double num = 0, dx = 0, dy = 0;
        for (int i = 0; i < n; i++) {
            double a = x[i] - mx, b = y[i] - my;
            num += a * b;
            dx += a * a;
            dy += b * b;
        }
        return (dx == 0 || dy == 0) ? 0 : num / Math.sqrt(dx * dy);
    }
}
