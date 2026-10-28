package com.crystalgraphics.render.post.look;

import com.crystalgraphics.render.post.volume.CgPostSettings;
import com.crystalgraphics.render.post.volume.CgPostSettings.Control;
import com.crystalgraphics.render.post.volume.CgPostSettings.Region;

/**
 * Unreal's colour grading, as its combined LUT applies it: the white balance, then {@code ColorCorrectAll} in ACEScg,
 * shadows, midtones and highlights each corrected with the global controls folded in and weighted by luma. The
 * composite grades the HDR scene with its {@link #constants}; the tonemapper's table bakes the same grade.
 *
 * <pre>{@code
 * CgColorGrade grade = new CgColorGrade();
 * float[] constants = grade.constants(settings);   // render thread, each firing: what the composite's GRADE reads
 *
 * double[] p = new double[CgColorGrade.PACKED];
 * CgColorGrade.pack(settings, p);                  // any thread
 * CgColorGrade.apply(p, linearRgb, graded);       // the same grade on the CPU, linear sRGB in and out
 * }</pre>
 *
 * <ul>
 *   <li>Shadows weigh 1 at luma 0 and none by 0.09, highlights from 0.5 to 1, midtones the rest (4.21's
 *       {@code ColorCorrectionShadowsMax} and {@code ColorCorrectionHighlightsMin} at their defaults).</li>
 *   <li>The scene tint is packed but not applied here: it multiplies after the curve or the roll.</li>
 *   <li>A neutral grade is not identity (Unreal's white balance at 6500 K is not quite D65): the composite skips it
 *       instead ({@link CgPostSettings#grades()}).</li>
 * </ul>
 */
public final class CgColorGrade {

    /** Doubles {@link #pack} writes, and floats {@link #constants} returns. */
    public static final int PACKED = 92;
    /** Where each part sits: rows of linear sRGB to balanced ACEScg, rows back, the regions' controls, ranges, tint. */
    static final int IN = 0, OUT = 12, CONTROLS = 24, RANGE = 84, TINT = 88;
    private static final double SHADOWS_MAX = 0.09, HIGHLIGHTS_MIN = 0.5;
    /** ACEScg's luma: AP1 to XYZ's middle row. */
    private static final double YR = 0.2722287168, YG = 0.6740817658, YB = 0.0536895174;
    private static final Region[] REGIONS = Region.values();
    private static final Control[] CONTROLS_OF = Control.values();

    private final double[] packed = new double[PACKED];
    private final float[] gpu = new float[PACKED];
    private float temp = Float.NaN, tint = Float.NaN;

    /** {@code s}'s grade for the composite; the white balance's matrix remade only when it moved. Render thread. */
    public float[] constants(CgPostSettings s) {
        update(s);
        for (int i = 0; i < PACKED; i++) gpu[i] = (float) packed[i];
        return gpu;
    }

    /** {@code s}'s grade, packed as {@link #pack} writes it, into an array this holds; allocates only when the white balance moved. */
    public double[] update(CgPostSettings s) {
        if (s.whiteTemp() != temp || s.whiteTint() != tint) {
            temp = s.whiteTemp();
            tint = s.whiteTint();
            matrices(temp, tint, packed);
        }
        controls(s, packed);
        return packed;
    }

    /** {@code s}'s grade into {@code out}, {@link #PACKED} long. */
    public static void pack(CgPostSettings s, double[] out) {
        matrices(s.whiteTemp(), s.whiteTint(), out);
        controls(s, out);
    }

    private static void matrices(double temp, double tint, double[] out) {
        double[] balance = CgFilmLut.mul(CgFilmLut.XYZ_TO_SRGB, CgFilmLut.mul(CgFilmLut.adaptation(temp, tint), CgFilmLut.SRGB_TO_XYZ));
        rows(CgFilmLut.mul(CgFilmLut.SRGB_TO_AP1, balance), out, IN);
        rows(CgFilmLut.AP1_TO_SRGB, out, OUT);
    }

    private static void rows(double[] m, double[] out, int at) {
        for (int r = 0; r < 3; r++) {
            out[at + r * 4] = m[r * 3];
            out[at + r * 4 + 1] = m[r * 3 + 1];
            out[at + r * 4 + 2] = m[r * 3 + 2];
            out[at + r * 4 + 3] = 0;
        }
    }

    /** Each region's controls with the global's folded in, as {@code ColorCorrectAll} passes them, the scalar applied. */
    private static void controls(CgPostSettings s, double[] out) {
        for (int r = 1; r < 4; r++) {
            Region region = REGIONS[r];
            for (Control c : CONTROLS_OF) {
                int at = CONTROLS + ((r - 1) * 5 + c.ordinal()) * 4;
                // A region's offset adds the global's; every other control multiplies it.
                boolean add = c == Control.OFFSET;
                double gw = s.grade(Region.GLOBAL, c, 3), rw = s.grade(region, c, 3);
                double w = add ? rw + gw : rw * gw;
                for (int k = 0; k < 3; k++) {
                    double g = s.grade(Region.GLOBAL, c, k), v = s.grade(region, c, k);
                    v = add ? v + g + w : v * g * w;
                    out[at + k] = c == Control.GAMMA ? 1 / v : v;
                }
                out[at + 3] = 0;
            }
        }
        out[RANGE] = SHADOWS_MAX;
        out[RANGE + 1] = HIGHLIGHTS_MIN;
        out[RANGE + 2] = out[RANGE + 3] = 0;
        out[TINT] = s.tintR();
        out[TINT + 1] = s.tintG();
        out[TINT + 2] = s.tintB();
        out[TINT + 3] = 0;
    }

    /** Linear sRGB {@code rgb} white-balanced, into ACEScg, graded and back into {@code out}; no tint. */
    public static void apply(double[] p, double[] rgb, double[] out) {
        double r = rgb[0], g = rgb[1], b = rgb[2];
        double ar = p[IN] * r + p[IN + 1] * g + p[IN + 2] * b;
        double ag = p[IN + 4] * r + p[IN + 5] * g + p[IN + 6] * b;
        double ab = p[IN + 8] * r + p[IN + 9] * g + p[IN + 10] * b;
        correctAll(p, ar, ag, ab, out);
        r = out[0];
        g = out[1];
        b = out[2];
        out[0] = p[OUT] * r + p[OUT + 1] * g + p[OUT + 2] * b;
        out[1] = p[OUT + 4] * r + p[OUT + 5] * g + p[OUT + 6] * b;
        out[2] = p[OUT + 8] * r + p[OUT + 9] * g + p[OUT + 10] * b;
    }

    /** {@code ColorCorrectAll} on ACEScg {@code (r, g, b)} into {@code out}. */
    public static void correctAll(double[] p, double r, double g, double b, double[] out) {
        double luma = r * YR + g * YG + b * YB;
        double shadows = 1 - smoothstep(0, p[RANGE], luma), highlights = smoothstep(p[RANGE + 1], 1, luma);
        double midtones = 1 - shadows - highlights;
        for (int k = 0; k < 3; k++) {
            double c = k == 0 ? r : k == 1 ? g : b;
            out[k] = correct(p, 0, c, luma, k) * shadows + correct(p, 1, c, luma, k) * midtones
                    + correct(p, 2, c, luma, k) * highlights;
        }
    }

    /** {@code ColorCorrect} of channel {@code k}, value {@code c}, by a region's packed controls (0 shadows, 2 highlights). */
    private static double correct(double[] p, int region, double c, double luma, int k) {
        int at = CONTROLS + region * 20 + k;
        double v = Math.max(0, luma + (c - luma) * p[at]);
        v = Math.pow(v * (1 / 0.18), p[at + 4]) * 0.18;
        v = Math.pow(v, p[at + 8]);
        return v * p[at + 12] + p[at + 16];
    }

    private static double smoothstep(double e0, double e1, double x) {
        double t = Math.max(0, Math.min(1, (x - e0) / (e1 - e0)));
        return t * t * (3 - 2 * t);
    }
}
