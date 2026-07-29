package com.crystalgraphics.api.font;

import com.crystalgraphics.util.profiling.CgProfiler;
import com.crystalgraphics.util.profiling.CgProfilerReport;
import org.junit.Test;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Measures what changing a variable-font axis costs.
 *
 * <p>Never benchmarked before. It matters because axis values are part of {@link CgFontKey}: moving
 * an axis produces a different key, which means a different {@code CgFont}, a different native face,
 * and a glyph cache that shares nothing with the previous value. Animating a weight axis is
 * therefore not like animating a colour — every step re-loads the face and re-rasterises every
 * glyph on screen.
 *
 * <p>The question this answers is whether an animated axis is viable at all, or whether it has to be
 * quantised to a few discrete steps. Two costs decide it: loading a face at a new axis value, and
 * whether the shipped fonts even expose axes to animate.
 *
 * <p>Reports rather than asserts. Whether a given axis animation is affordable is a design call, and
 * the point here is to put a number under it — and to record, if the shipped fonts turn out to be
 * static, that this engine path is currently untested by any real asset.
 */
public class CgVariableFontCostTest {

    private static final String[] FONTS = {
            "src/main/resources/assets/crystalgraphics/IBMPlexSans-Regular.ttf",
            "src/main/resources/assets/crystalgraphics/MPLUS1p-Regular.ttf",
            "src/main/resources/assets/crystalgraphics/NotoSansArabic-Regular.ttf",
            "src/main/resources/assets/crystalgraphics/MPLUSRounded1c-Regular.ttf",
            "src/main/resources/assets/crystalgraphics/IBMPlexSansArabic-Regular.ttf",
    };

    @Test
    public void reportsVariationAxisCost() {
        CgProfiler.setEnabled(true);
        CgProfiler.reset();
        try {
            run();
        } finally {
            // reset() BEFORE setEnabled(false): reset() is itself gated on `enabled`, so disabling
            // first makes it a silent no-op and leaks this test's scopes into whatever test runs
            // next on the same thread. That is exactly how CgProfilerTest started failing.
            CgProfiler.reset();
            CgProfiler.setEnabled(false);
        }
    }

    private void run() {
        System.out.println();
        System.out.println("=== variable font axes: which shipped fonts have any ===");

        CgFont variableFont = null;
        String variableAxisTag = null;

        for (String path : FONTS) {
            File f = new File(path);
            if (!f.isFile()) continue;
            CgFont font = CgFont.load(f.getPath(), CgFontStyle.REGULAR, 16);
            List<CgFontAxisInfo> axes = font.getVariationAxes();
            System.out.printf(Locale.ROOT, "  %-32s %d axis/axes%s%n",
                    f.getName(), axes == null ? 0 : axes.size(),
                    axes == null || axes.isEmpty() ? "" : " -> " + axes);
            if (variableFont == null && axes != null && !axes.isEmpty()) {
                variableFont = font;
                variableAxisTag = axes.get(0).getTag();
            } else {
                font.dispose();
            }
        }

        if (variableFont == null) {
            System.out.println();
            System.out.println("  RESULT: no shipped font exposes a variation axis, so the variable-font");
            System.out.println("  path cannot be exercised with current assets. The engine supports it");
            System.out.println("  (CgFontVariation participates in CgFontKey), but nothing tests it.");
            System.out.println("  To close this properly, add a variable font (e.g. IBMPlexSans-Variable)");
            System.out.println("  to the test assets and re-run.");
            measureFaceReloadCost();
            return;
        }

        measureAxisSteps(variableFont, variableAxisTag);
        variableFont.dispose();
    }

    /**
     * Even without a variable font, the dominant cost of an axis change is measurable: a new axis
     * value means a new {@link CgFontKey}, which means loading the face again. That load is the
     * floor on what any axis step can cost, so measuring it bounds the answer regardless.
     */
    private void measureFaceReloadCost() {
        File f = null;
        for (String path : FONTS) {
            File c = new File(path);
            if (c.isFile()) { f = c; break; }
        }
        if (f == null) return;

        CgFont warm = CgFont.load(f.getPath(), CgFontStyle.REGULAR, 16);
        warm.dispose();

        int n = 20;
        long start = System.nanoTime();
        for (int i = 0; i < n; i++) {
            CgFont font = CgFont.load(f.getPath(), CgFontStyle.REGULAR, 16);
            font.dispose();
        }
        double msPerLoad = (System.nanoTime() - start) / 1_000_000.0 / n;

        System.out.println();
        System.out.printf(Locale.ROOT,
                "  Face reload cost (the floor on any axis step): %.3f ms per load, font %s%n",
                msPerLoad, f.getName());
        System.out.println("  An animated axis pays at least this per distinct value, plus re-rasterising");
        System.out.println("  every on-screen glyph — so continuous animation is not viable; quantise to");
        System.out.println("  a few steps and let the glyph caches settle on each.");

        CgProfilerReport report = CgProfiler.report();
        for (CgProfilerReport.ScopeEntry e : report.scopes()) {
            if (e.name().startsWith("font.")) {
                System.out.printf(Locale.ROOT, "    %-24s %8.3f ms over %d calls%n",
                        e.name(), e.totalNanos() / 1_000_000.0, e.callCount());
            }
        }
    }

    private void measureAxisSteps(CgFont base, String axisTag) {
        System.out.println();
        System.out.printf(Locale.ROOT, "  Animating axis '%s' — one load per distinct value%n", axisTag);

        int steps = 10;
        long start = System.nanoTime();
        for (int i = 0; i < steps; i++) {
            float value = 300f + i * 40f;
            CgFont stepped = CgFont.load(
                    base.getLogicalName(), CgFontStyle.REGULAR, 16,
                    Collections.singletonList(new CgFontVariation(axisTag, value)));
            stepped.dispose();
        }
        double msPerStep = (System.nanoTime() - start) / 1_000_000.0 / steps;
        System.out.printf(Locale.ROOT, "  %.3f ms per axis step%n", msPerStep);
    }
}
