package com.crystalgraphics.render.post.look;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Arrays;

import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.gl.texture.CgTexture3D;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.render.post.volume.CgPostSettings;

/**
 * The tonemapper's colour table: Unreal 4.21's combined LUT (its {@code PostProcessCombineLUTs}) with the legacy film
 * curve, 32 cells a side, baked on the CPU whenever the settings change and held as a 3D texture. The composite reads
 * it through Unreal's log shaper ({@link #shape}) and scales what it reads by {@link #SCALE}.
 *
 * <pre>{@code
 * CgFilmLut lut = new CgFilmLut();
 * CgTexture3D table = lut.texture(settings);   // render thread; rebaked only when the film settings moved
 *
 * float[] cells = new float[CgFilmLut.CELLS * 3];
 * CgFilmLut.bake(new CgPostSettings().tonemap().filmHeal(0.18f), cells);   // any thread: r fastest, then g, then b
 * }</pre>
 *
 * <ul>
 *   <li>Each cell is the log-encoded colour at its corner, decoded, white-balanced, graded in ACEScg
 *       ({@link CgColorGrade}) and taken back, through the legacy film curve, tinted, sRGB-encoded and divided by
 *       1.05, as 4.21's combine writes it.</li>
 *   <li>The ACES film tonemapper ({@code r.TonemapperFilm 1}) is not baked yet: only the legacy curve is.</li>
 *   <li>Colour science constants are the ACES and sRGB standards' (Academy aces-dev; IEC 61966-2-1), Bradford's and the
 *       legacy curve's construction as 4.21's renderer computes them.</li>
 * </ul>
 */
public final class CgFilmLut {

    /** Cells a side, and in all. */
    public static final int SIZE = 32, CELLS = SIZE * SIZE * SIZE;
    /** What a cell is multiplied by when read: the combine divides by it so the table can hold a little past white. */
    public static final float SCALE = 1.05f;

    static final double[] SRGB_TO_XYZ = {0.4124564, 0.3575761, 0.1804375, 0.2126729, 0.7151522, 0.0721750,
            0.0193339, 0.1191920, 0.9503041};
    static final double[] XYZ_TO_SRGB = {3.2409699419, -1.5373831776, -0.4986107603, -0.9692436363,
            1.8759675015, 0.0415550574, 0.0556300797, -0.2039769589, 1.0569715142};
    private static final double[] XYZ_TO_AP1 = {1.6410233797, -0.3248032942, -0.2364246952, -0.6636628587,
            1.6153315917, 0.0167563477, 0.0117218943, -0.0082844420, 0.9883948585};
    private static final double[] AP1_TO_XYZ = {0.6624541811, 0.1340042065, 0.1561876870, 0.2722287168, 0.6740817658,
            0.0536895174, -0.0055746495, 0.0040607335, 1.0103391003};
    /** Bradford adaptation between D65 (sRGB's white) and D60 (ACES'). */
    private static final double[] D65_TO_D60 = {1.01303, 0.00610531, -0.014971, 0.00769823, 0.998165, -0.00503203,
            -0.00284131, 0.00468516, 0.924507};
    private static final double[] D60_TO_D65 = {0.987224, -0.00611327, 0.0159533, -0.00759836, 1.00186, 0.00533002,
            0.00307257, -0.00509595, 1.08168};
    private static final double[] BRADFORD = {0.8951, 0.2664, -0.1614, -0.7502, 1.7135, 0.0367, 0.0389, -0.0685, 1.0296};
    private static final double[] BRADFORD_INV = {0.9869929, -0.1470543, 0.1599627, 0.4323053, 0.5183603, 0.0492912,
            -0.0085287, 0.0400428, 0.9684867};
    static final double[] SRGB_TO_AP1 = mul(XYZ_TO_AP1, mul(D65_TO_D60, SRGB_TO_XYZ));
    static final double[] AP1_TO_SRGB = mul(XYZ_TO_SRGB, mul(D60_TO_D65, AP1_TO_XYZ));
    /** The log encoding's range in stops, its grey and where grey sits (Unreal's LogToLin). */
    private static final double RANGE = 14, GREY = 0.18, GREY_AT = 444.0 / 1023.0;

    private final float[] cells = new float[CELLS * 3];
    private final ByteBuffer upload = ByteBuffer.allocateDirect(CELLS * 16).order(ByteOrder.nativeOrder());
    private final float[] baked = new float[5];
    private final CgColorGrade grading = new CgColorGrade();
    private final double[] bakedGrade = new double[CgColorGrade.PACKED];
    private CgTexture3D texture;

    /** The table for {@code s}'s film settings and grade: rebaked and uploaded when they changed. Render thread. */
    public CgTexture3D texture(CgPostSettings s) {
        double[] grade = grading.update(s);
        if (texture != null && same(s, grade)) return texture;
        baked[0] = s.filmContrast();
        baked[1] = s.filmDynamicRange();
        baked[2] = s.filmToe();
        baked[3] = s.filmHeal();
        baked[4] = s.filmSaturation();
        System.arraycopy(grade, 0, bakedGrade, 0, grade.length);
        bake(s, cells);
        FloatBuffer f = upload.clear().asFloatBuffer();
        for (int i = 0; i < CELLS; i++) f.put(cells[i * 3]).put(cells[i * 3 + 1]).put(cells[i * 3 + 2]).put(1f);
        if (texture == null) texture = CgTexture3D.createEmpty(SIZE, SIZE, SIZE, CgTextureType.RGBA32F.toTextureSpec());
        texture.uploadRegion(0, 0, 0, 0, SIZE, SIZE, SIZE, upload, CgGL.GL_RGBA, CgGL.GL_FLOAT);
        return texture;
    }

    private boolean same(CgPostSettings s, double[] grade) {
        return baked[0] == s.filmContrast() && baked[1] == s.filmDynamicRange() && baked[2] == s.filmToe()
                && baked[3] == s.filmHeal() && baked[4] == s.filmSaturation() && Arrays.equals(grade, bakedGrade);
    }

    /** Frees the table; the next {@link #texture} makes it again. */
    public void release() {
        if (texture != null) texture.delete();
        texture = null;
    }

    /** Where linear {@code c} reads the table, 0 to 1 a side: Unreal's {@code LinToLog(c + LogToLin(0))}, to cell centres. */
    public static double shape(double c) {
        double log = Math.log(c + logToLin(0)) / Math.log(2) / RANGE - Math.log(GREY) / Math.log(2) / RANGE + GREY_AT;
        return Math.max(0, Math.min(1, log)) * ((SIZE - 1.0) / SIZE) + 0.5 / SIZE;
    }

    /**
     * Linear {@code (r, g, b)} tonemapped through {@code table} as the composite reads it: the shaper, a trilinear read
     * between cell centres, times {@link #SCALE}. {@code out} gets the encoded colour, 0 to about 1. For a check on the CPU.
     */
    public static void tonemap(float[] table, double r, double g, double b, double[] out) {
        double[] at = {shape(r), shape(g), shape(b)};
        int[] i0 = new int[3];
        double[] f = new double[3];
        for (int c = 0; c < 3; c++) {
            double t = Math.max(0, Math.min(SIZE - 1, at[c] * SIZE - 0.5));
            i0[c] = (int) Math.floor(t);
            f[c] = t - i0[c];
        }
        out[0] = out[1] = out[2] = 0;
        for (int corner = 0; corner < 8; corner++) {
            int cr = Math.min(SIZE - 1, i0[0] + (corner & 1)), cg = Math.min(SIZE - 1, i0[1] + (corner >> 1 & 1));
            int cb = Math.min(SIZE - 1, i0[2] + (corner >> 2));
            double w = ((corner & 1) != 0 ? f[0] : 1 - f[0]) * ((corner >> 1 & 1) != 0 ? f[1] : 1 - f[1])
                    * ((corner >> 2) != 0 ? f[2] : 1 - f[2]);
            for (int c = 0; c < 3; c++) out[c] += w * table[3 * (cr + SIZE * (cg + SIZE * cb)) + c] * SCALE;
        }
    }

    private static double logToLin(double log) {
        return Math.pow(2, (log - GREY_AT) * RANGE) * GREY;
    }

    /** Bakes {@code s}'s table into {@code out}: {@link #CELLS} cells of r, g, b; r fastest, then g, then b. */
    public static void bake(CgPostSettings s, float[] out) {
        double[] grade = new double[CgColorGrade.PACKED];
        CgColorGrade.pack(s, grade);
        Curve curve = new Curve(s.filmContrast(), s.filmDynamicRange(), s.filmToe(), s.filmHeal(), s.filmSaturation());
        double[] c = new double[3], t = new double[3];
        double zero = logToLin(0);
        for (int b = 0; b < SIZE; b++) {
            for (int g = 0; g < SIZE; g++) {
                for (int r = 0; r < SIZE; r++) {
                    c[0] = logToLin(r / (SIZE - 1.0)) - zero;
                    c[1] = logToLin(g / (SIZE - 1.0)) - zero;
                    c[2] = logToLin(b / (SIZE - 1.0)) - zero;
                    CgColorGrade.apply(grade, c, t);
                    curve.apply(t);
                    int at = 3 * (r + SIZE * (g + SIZE * b));
                    for (int k = 0; k < 3; k++) {
                        double tinted = t[k] * grade[CgColorGrade.TINT + k];
                        out[at + k] = (float) Math.min(1, srgb(Math.max(tinted, 0)) / SCALE);
                    }
                }
            }
        }
    }

    /**
     * The legacy film curve (4.21's {@code FilmPostProcess}), its constants built from the settings as the renderer
     * builds them: a toe, a straight mid section and a shoulder healing toward white.
     */
    static final class Curve {
        final double cd1, cd3cm3, cm2, cm0cd0, cd2, ch0cm1, ch3, ch1, ch2;
        final double saturation;

        Curve(double contrast, double dynamicRange, double toeAmount, double healAmount, double saturation) {
            this.saturation = clamp(saturation, 0, 2);
            double c = clamp(contrast, 0, 1) + 1;
            double range = Math.pow(2, clamp(dynamicRange, 1, 4));
            double toe = clamp((1 - clamp(toeAmount, 0, 1)) * 0.18, 0.18 / 8, 0.18 * 15 / 16);
            double heal = 1 - Math.max(1.0 / 32, 1 - clamp(healAmount, 0, 1)) * (1 - 0.18);
            double offset = 0.18 - 0.18 * c;
            double x0 = -offset / c, xs = (1 - offset) / c - x0;
            double hiX = x0 + heal * xs, hiY = hiX * c + offset;
            double loX = x0 + toe * xs, loY = loX * c + offset;
            double slope = (hiY - loY) / (hiX - loX), hiYs = 1 - hiY, healRange = range - hiX;
            double hiG = (-hiYs + slope * healRange) / (slope * healRange);
            double loG = (-loY + slope * loX) / (slope * loX);
            ch1 = hiYs / hiG;
            ch2 = -hiX * (hiYs / hiG);
            ch3 = hiYs / (slope * hiG) - hiX;
            ch0cm1 = hiX;
            cm2 = slope;
            if (loG != 0) {
                cm0cd0 = loX;
                cd3cm3 = loY - loX * slope;
                cd1 = -loY / loG;
                cd2 = loY / (slope * loG);
            } else {
                // A toe of zero continues the straight section into black.
                cm0cd0 = 0;
                cd3cm3 = 0;
                cd1 = 0;
                cd2 = 1;
            }
        }

        /** {@code c}, linear sRGB, through the curve in place. */
        void apply(double[] c) {
            // The channel mixer at its identity, then saturation about the mean (the matrix's luma), never below zero.
            double mean = (c[0] + c[1] + c[2]) / 3;
            for (int k = 0; k < 3; k++) {
                double m = Math.max(0, mean + (c[k] - mean) * saturation);
                double d = Math.max(0, cm0cd0 - m), h = Math.max(m, ch0cm1), mid = clamp(m, cm0cd0, ch0cm1);
                c[k] = (h * ch1 + ch2) / (h + ch3) + (mid * cm2 + (d * cd1) / (d + cd2) + cd3cm3) - 0.002;
            }
        }
    }

    /** Unreal's white balance: Bradford from the white at {@code temp} kelvin and {@code tint}, to D65. */
    static double[] adaptation(double temp, double tint) {
        double[] uv = planckUv(temp);
        double[] planck = uvToXy(uv[0], uv[1]);
        double tc = temp * 1.4388 / 1.438;
        double x = tc <= 7000 ? 0.244063 + (0.09911e3 + (2.9678e6 - 4.6070e9 / tc) / tc) / tc
                : 0.237040 + (0.24748e3 + (1.9018e6 - 2.0064e9 / tc) / tc) / tc;
        double[] src = temp < 4000 ? planck : new double[] {x, -3 * x * x + 2.87 * x - 0.275};
        double n = Math.hypot(uv[0], uv[1]);
        double[] iso = uvToXy(uv[0] - uv[1] / n * tint * 0.05, uv[1] + uv[0] / n * tint * 0.05);
        src = new double[] {src[0] + iso[0] - planck[0], src[1] + iso[1] - planck[1]};
        double[] s = new double[3], d = new double[3];
        apply(BRADFORD, xyz(src[0], src[1]), s);
        apply(BRADFORD, xyz(0.31270, 0.32900), d);
        double[] scale = {d[0] / s[0], 0, 0, 0, d[1] / s[1], 0, 0, 0, d[2] / s[2]};
        return mul(BRADFORD_INV, mul(scale, BRADFORD));
    }

    private static double[] planckUv(double t) {
        double u = (0.860117757 + 1.54118254e-4 * t + 1.28641212e-7 * t * t) / (1 + 8.42420235e-4 * t + 7.08145163e-7 * t * t);
        double v = (0.317398726 + 4.22806245e-5 * t + 4.20481691e-8 * t * t) / (1 - 2.89741816e-5 * t + 1.61456053e-7 * t * t);
        return new double[] {u, v};
    }

    private static double[] uvToXy(double u, double v) {
        double d = 2 * u - 8 * v + 4;
        return new double[] {3 * u / d, 2 * v / d};
    }

    private static double[] xyz(double x, double y) {
        return new double[] {x / y, 1, (1 - x - y) / y};
    }

    /** sRGB's encoding, IEC 61966-2-1. */
    static double srgb(double x) {
        return x < 0.00313067 ? x * 12.92 : Math.pow(x, 1 / 2.4) * 1.055 - 0.055;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** Row-major 3x3 {@code a · b}. */
    static double[] mul(double[] a, double[] b) {
        double[] o = new double[9];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) o[i * 3 + j] = a[i * 3] * b[j] + a[i * 3 + 1] * b[3 + j] + a[i * 3 + 2] * b[6 + j];
        }
        return o;
    }

    private static void apply(double[] m, double[] v, double[] out) {
        double x = v[0], y = v[1], z = v[2];
        out[0] = m[0] * x + m[1] * y + m[2] * z;
        out[1] = m[3] * x + m[4] * y + m[5] * z;
        out[2] = m[6] * x + m[7] * y + m[8] * z;
    }
}
