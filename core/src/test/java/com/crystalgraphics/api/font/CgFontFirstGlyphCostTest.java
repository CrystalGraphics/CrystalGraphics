package com.crystalgraphics.api.font;

import com.crystalgraphics.freetype.FTFace;
import com.crystalgraphics.freetype.FTLoadFlags;
import com.crystalgraphics.freetype.FTRenderMode;
import org.junit.Test;

import java.io.File;
import java.util.Locale;

/**
 * Measures whether the first glyph loaded from a freshly-opened face costs more than the rest.
 *
 * <p>Bitmap rasterisation averages 41 us per glyph but was measured peaking at 51 ms on a single
 * call — twelve hundred times the average, and all of it inside frame 1. A spike that large against
 * that average is the signature of a one-time cost rather than an unlucky glyph, and the obvious
 * candidate is FreeType parsing font tables (cmap, glyf, loca, hinting program) lazily on first use
 * rather than at {@code FT_New_Face}.
 *
 * <p>That distinction decides whether it is fixable. A genuinely expensive glyph cannot be helped;
 * a deferred one-time parse can simply be paid during font loading, which is already a startup cost,
 * instead of landing on the first rendered frame.
 *
 * <p>Reports rather than asserts — the number is hardware- and font-dependent, and the point is to
 * establish which of the two explanations holds.
 */
public class CgFontFirstGlyphCostTest {

    private static final String[] FONTS = {
            "src/main/resources/assets/crystalgraphics/IBMPlexSans-Regular.ttf",
            "src/main/resources/assets/crystalgraphics/MPLUS1p-Regular.ttf",
            "src/main/resources/assets/crystalgraphics/NotoSansArabic-Regular.ttf",
    };

    @Test
    public void reportsFirstGlyphVersusSteadyStateCost() {
        System.out.println();
        System.out.println("=== first glyph load vs steady state, per font ===");
        System.out.printf(Locale.ROOT, "%-32s %12s %12s %10s%n",
                "font", "first (ms)", "median (ms)", "ratio");

        for (String path : FONTS) {
            File file = new File(path);
            if (!file.isFile()) {
                System.out.printf(Locale.ROOT, "%-32s (missing)%n", file.getName());
                continue;
            }
            measure(file);
        }
    }

    private static void measure(File file) {
        // A fresh CgFont per measurement: the whole question is what the *first* load costs, so a
        // reused face would have already paid it and report nothing.
        CgFont font = CgFont.load(file.getPath(), CgFontStyle.REGULAR, 16);
        try {
            FTFace face = font.getFtFace();
            face.setPixelSizes(0, 16);

            int[] glyphs = new int[64];
            for (int i = 0; i < glyphs.length; i++) {
                glyphs[i] = font.getGlyphIndex('A' + (i % 26));
            }

            long start = System.nanoTime();
            face.loadGlyph(glyphs[0], FTLoadFlags.FT_LOAD_DEFAULT);
            face.renderGlyph(FTRenderMode.FT_RENDER_MODE_NORMAL);
            double firstMs = (System.nanoTime() - start) / 1_000_000.0;

            double[] rest = new double[glyphs.length - 1];
            for (int i = 1; i < glyphs.length; i++) {
                long t = System.nanoTime();
                face.loadGlyph(glyphs[i], FTLoadFlags.FT_LOAD_DEFAULT);
                face.renderGlyph(FTRenderMode.FT_RENDER_MODE_NORMAL);
                rest[i - 1] = (System.nanoTime() - t) / 1_000_000.0;
            }
            java.util.Arrays.sort(rest);
            double median = rest[rest.length / 2];

            System.out.printf(Locale.ROOT, "%-32s %12.3f %12.3f %9.1fx%n",
                    file.getName(), firstMs, median, median == 0 ? 0 : firstMs / median);
        } finally {
            font.dispose();
        }
    }
}
