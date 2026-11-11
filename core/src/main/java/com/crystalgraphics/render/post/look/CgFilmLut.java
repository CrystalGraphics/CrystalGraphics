package com.crystalgraphics.render.post.look;

import java.util.Arrays;

import com.crystalgraphics.compute.CgCompute;
import com.crystalgraphics.compute.CgKernel;
import com.crystalgraphics.render.graph.CgBufferDesc;
import com.crystalgraphics.render.graph.CgBufferUsage;
import com.crystalgraphics.render.graph.CgComputePass;
import com.crystalgraphics.render.graph.CgGraphBuffer;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.graph.CgTextureDesc;
import com.crystalgraphics.render.post.volume.CgPostSettings;
import com.crystalgraphics.trace.CgGpuTrace;
import com.crystalgraphics.variable.CgVar;
import com.crystalgraphics.platform.rhi.format.CgFormat;

/**
 * The tonemapper's colour table: Unreal's combined LUT (its {@code PostProcessCombineLUTs}), 4.21's with the legacy
 * film curve or with {@code filmic()} 5.1's with its ACES-fitted film curve, 32 cells a side, built on the GPU by one
 * dispatch whenever the settings change (Unreal's, with {@code r.LUT.UpdateEveryFrame} off) and kept as a 3D graph
 * texture. The CPU computes only the parameters. The composite reads it through Unreal's log shaper ({@link #shape})
 * and scales what it reads by {@link #SCALE}.
 *
 * <pre>{@code
 * CgFilmLut lut = new CgFilmLut();
 * CgGraphTexture table = lut.texture(recording, settings);   // render thread; built only when the settings moved
 *
 * float[] cells = new float[CgFilmLut.CELLS * 3];
 * CgFilmLut.bake(new CgPostSettings().tonemap().filmHeal(0.18f), cells);   // the same table on the CPU, any thread
 * }</pre>
 *
 * <ul>
 *   <li>{@link #bake} is the reference the GPU's table is checked against ({@code post-looks}), cell for cell; the
 *       renderer never calls it.</li>
 *   <li>Each cell is the log-encoded colour at its corner, decoded, white-balanced, graded in ACEScg
 *       ({@link CgColorGrade}) and taken back, through the legacy film curve, tinted, sRGB-encoded and divided by
 *       1.05, as 4.21's combine writes it.</li>
 *   <li>5.1's table ({@link CgPostSettings#filmic()}): white balance, expand gamut, the grade, blue correction around
 *       {@code FilmToneMap} taken {@code ToneCurveAmount} of the way, the tint, {@code 2.2 / DisplayGamma}, sRGB / 1.05.
 *       At a tone curve of 0 HDR clips at 1 per channel. Its {@code MappingPolynomial} is the identity, as by default.</li>
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
    /** Linear sRGB to ACEScg, row-major: Unreal's {@code WorkingColorSpace.ToAP1} for an sRGB working space. A copy. */
    public static double[] srgbToAp1() {
        return SRGB_TO_AP1.clone();
    }

    /** ACEScg to linear sRGB, row-major: {@code WorkingColorSpace.FromAP1}. A copy. */
    public static double[] ap1ToSrgb() {
        return AP1_TO_SRGB.clone();
    }

    /** The log encoding's range in stops, its grey and where grey sits (Unreal's LogToLin). */
    private static final double RANGE = 14, GREY = 0.18, GREY_AT = 444.0 / 1023.0;

    /** Rows of the kernel's parameters ({@code film_lut.compute}'s {@code ROW_*}), each a vec4. */
    static final int ROW_MODE = 23, ROW_LEGACY = 24, ROW_FILMIC = 27, ROW_AP1_TO_AP0 = 30, ROW_AP0_TO_AP1 = 33,
            ROW_BLUE = 36, ROW_BLUE_INV = 39, ROW_EXPAND = 42, ROWS = 45;
    private static final String KERNELS = "crystalgraphics:shaders/env/compute/post/film_lut.compute";
    private static final CgFormat TABLE_FORMAT = CgFormat.RGBA32_FLOAT;
    static final int GPU_FILM_LUT = CgGpuTrace.name("post.filmLut");
    /** Unreal's {@code r.LUT.UpdateEveryFrame}: the table built every frame, settings moved or not, to time it. */
    private static final boolean EVERY_FRAME = CgVar.bool("crystalgraphics.post.lutEveryFrame", false, CgVar.DEV,
            "The tonemapper's table is built every frame its look tonemaps, not only when its settings move.").get();

    private final float[] rows = new float[ROWS * 4];
    private final float[] built = new float[15], now = new float[15];
    private final CgColorGrade grading = new CgColorGrade();
    private final double[] builtGrade = new double[CgColorGrade.PACKED];
    private final Curve legacy = new Curve();
    private final FilmCurve filmCurve = new FilmCurve();
    private CgGraphTexture table;
    private CgGraphBuffer params;
    private CgKernel kernel;

    /**
     * The table for {@code s}'s film settings and grade, built in {@code recording} when they changed since the last
     * build: one dispatch of a cell a thread. Render thread.
     */
    public CgGraphTexture texture(CgRecording recording, CgPostSettings s) {
        double[] grade = grading.update(s);
        film(s, now);
        if (table != null && !EVERY_FRAME && Arrays.equals(now, built) && Arrays.equals(grade, builtGrade)) return table;
        System.arraycopy(now, 0, built, 0, now.length);
        System.arraycopy(grade, 0, builtGrade, 0, grade.length);
        if (table == null) {
            table = CgGraphTexture.requested("cg_film_lut", CgTextureDesc.volume(SIZE, SIZE, SIZE, TABLE_FORMAT));
            params = CgGraphBuffer.persistent("cg_film_lut.params", CgBufferDesc.of(ROWS * 16L, CgBufferUsage.STORAGE,
                    CgBufferUsage.COPY));
        }
        pack(s, grade);
        recording.update(params, 0, rows, 0, rows.length);
        CgComputePass pass = recording.compute("cg.filmLut").timed(GPU_FILM_LUT);
        pass.dispatch(kernel(), SIZE, SIZE, SIZE).image("TABLE", table).bind("PARAMS", params);
        pass.end();
        return table;
    }

    /** Starts the kernel's program, as a loading screen does. Render thread. */
    public static void precache() {
        CgCompute.load(KERNELS).kernel("Combine").precache();
    }

    private CgKernel kernel() {
        if (kernel == null) kernel = CgCompute.load(KERNELS).kernel("Combine");
        return kernel;
    }

    /**
     * {@code s}'s parameters into {@link #rows}: its {@code grade} ({@link CgColorGrade#pack}'s 23 rows), the mode, both
     * curves' constants and the colour matrices, which the kernel takes as they are, computed here in doubles.
     */
    private void pack(CgPostSettings s, double[] grade) {
        Arrays.fill(rows, 0f);
        for (int i = 0; i < CgColorGrade.PACKED; i++) rows[i] = (float) grade[i];
        put(ROW_MODE, s.tonemapsFilmic() ? 1 : 0, s.toneCurve(), s.blueCorrection(), s.expandGamut());
        Curve c = legacy.set(s.filmContrast(), s.filmDynamicRange(), s.filmToe(), s.filmHeal(), s.filmSaturation());
        put(ROW_LEGACY, c.cd1, c.cd3cm3, c.cm2, c.cm0cd0);
        put(ROW_LEGACY + 1, c.cd2, c.ch0cm1, c.ch3, c.ch1);
        put(ROW_LEGACY + 2, c.ch2, c.saturation, 0, 0);
        FilmCurve f = filmCurve.set(s.filmSlope(), s.filmCurveToe(), s.filmShoulder(), s.filmBlackClip(), s.filmWhiteClip());
        put(ROW_FILMIC, f.slope, f.toe, f.shoulder, f.black);
        put(ROW_FILMIC + 1, f.white, f.toeScale, f.shoulderScale, f.toeMatch);
        put(ROW_FILMIC + 2, f.shoulderMatch, 2.2 / s.displayGamma(), 0, 0);
        matrix(ROW_AP1_TO_AP0, AP1_TO_AP0);
        matrix(ROW_AP0_TO_AP1, AP0_TO_AP1);
        matrix(ROW_BLUE, BLUE_CORRECT_AP1);
        matrix(ROW_BLUE_INV, BLUE_CORRECT_INV_AP1);
        matrix(ROW_EXPAND, EXPAND);
    }

    private void put(int row, double x, double y, double z, double w) {
        rows[row * 4] = (float) x;
        rows[row * 4 + 1] = (float) y;
        rows[row * 4 + 2] = (float) z;
        rows[row * 4 + 3] = (float) w;
    }

    private void matrix(int row, double[] m) {
        for (int r = 0; r < 3; r++) put(row + r, m[r * 3], m[r * 3 + 1], m[r * 3 + 2], 0);
    }

    /** What of {@code s} the table is baked from, besides the grade, into {@code out}. */
    private static void film(CgPostSettings s, float[] out) {
        out[0] = s.filmContrast();
        out[1] = s.filmDynamicRange();
        out[2] = s.filmToe();
        out[3] = s.filmHeal();
        out[4] = s.filmSaturation();
        out[5] = s.tonemapsFilmic() ? 1f : 0f;
        out[6] = s.toneCurve();
        out[7] = s.filmSlope();
        out[8] = s.filmCurveToe();
        out[9] = s.filmShoulder();
        out[10] = s.filmBlackClip();
        out[11] = s.filmWhiteClip();
        out[12] = s.blueCorrection();
        out[13] = s.expandGamut();
        out[14] = s.displayGamma();
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
        if (s.tonemapsFilmic()) {
            bakeFilmic(s, grade, out);
            return;
        }
        Curve curve = new Curve().set(s.filmContrast(), s.filmDynamicRange(), s.filmToe(), s.filmHeal(), s.filmSaturation());
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

    // Unreal 5.1's ACES.ush and PostProcessCombineLUTs.usf constants, row-major.
    private static final double[] AP0_TO_XYZ = {0.9525523959, 0, 0.0000936786, 0.3439664498, 0.7281660966, -0.0721325464,
            0, 0, 1.0088251844};
    private static final double[] XYZ_TO_AP0 = {1.0498110175, 0, -0.0000974845, -0.4959030231, 1.3733130458, 0.0982400361,
            0, 0, 0.9912520182};
    private static final double[] AP0_TO_AP1 = {1.4514393161, -0.2365107469, -0.2149285693, -0.0765537734, 1.1762296998,
            -0.0996759264, 0.0083161484, -0.0060324498, 0.9977163014};
    private static final double[] AP1_TO_AP0 = mul(XYZ_TO_AP0, AP1_TO_XYZ);
    private static final double[] BLUE_CORRECT = {0.9404372683, -0.0183068787, 0.0778696104, 0.0083786969, 0.8286599939,
            0.1629613092, 0.0005471261, -0.0008833746, 1.0003362486};
    private static final double[] BLUE_CORRECT_INV = {1.06318, 0.0233956, -0.0865726, -0.0106337, 1.20632, -0.19569,
            -0.000590887, 0.00105248, 0.999538};
    private static final double[] AP0_TO_AP1_CAT = mul(XYZ_TO_AP1, AP0_TO_XYZ);
    private static final double[] BLUE_CORRECT_AP1 = mul(AP0_TO_AP1_CAT, mul(BLUE_CORRECT, AP1_TO_AP0));
    private static final double[] BLUE_CORRECT_INV_AP1 = mul(AP0_TO_AP1_CAT, mul(BLUE_CORRECT_INV, AP1_TO_AP0));
    /** sRGB expanded toward P3 (between P3 and AP1), for ExpandGamut. */
    private static final double[] WIDE_TO_XYZ = {0.5441691, 0.2395926, 0.1666943, 0.2394656, 0.7021530, 0.0583814,
            -0.0023439, 0.0361834, 1.0552183};
    private static final double[] EXPAND = mul(mul(XYZ_TO_AP1, WIDE_TO_XYZ), AP1_TO_SRGB);
    private static final double AP1_Y_R = 0.2722287168, AP1_Y_G = 0.6740817658, AP1_Y_B = 0.0536895174;

    /**
     * 5.1's table ({@code CombineLUTsCommon}, sRGB output device): white-balanced into ACEScg, its gamut expanded, graded,
     * blue-corrected around {@code FilmToneMap} taken {@code ToneCurveAmount} of the way, back to sRGB, tinted, raised to
     * {@code 2.2 / DisplayGamma}, encoded and divided by 1.05, clipped as its 10-bit table stores it.
     */
    private static void bakeFilmic(CgPostSettings s, double[] grade, float[] out) {
        FilmCurve curve = new FilmCurve().set(s.filmSlope(), s.filmCurveToe(), s.filmShoulder(), s.filmBlackClip(), s.filmWhiteClip());
        double amount = s.toneCurve(), blue = s.blueCorrection(), expand = s.expandGamut();
        double gamma = 2.2 / s.displayGamma();
        double[] c = new double[3], ap1 = new double[3], t = new double[3], m = new double[3];
        double zero = logToLin(0);
        for (int b = 0; b < SIZE; b++) {
            for (int g = 0; g < SIZE; g++) {
                for (int r = 0; r < SIZE; r++) {
                    c[0] = logToLin(r / (SIZE - 1.0)) - zero;
                    c[1] = logToLin(g / (SIZE - 1.0)) - zero;
                    c[2] = logToLin(b / (SIZE - 1.0)) - zero;
                    // The grade's IN rows are the white balance into ACEScg (WorkingColorSpace.ToAP1)
                    for (int k = 0; k < 3; k++) {
                        ap1[k] = grade[CgColorGrade.IN + k * 4] * c[0] + grade[CgColorGrade.IN + k * 4 + 1] * c[1]
                                + grade[CgColorGrade.IN + k * 4 + 2] * c[2];
                    }
                    double luma = ap1[0] * AP1_Y_R + ap1[1] * AP1_Y_G + ap1[2] * AP1_Y_B;
                    if (expand > 0 && luma > 0) {
                        double d2 = 0;
                        for (int k = 0; k < 3; k++) d2 += (ap1[k] / luma - 1) * (ap1[k] / luma - 1);
                        double share = (1 - Math.pow(2, -4 * d2)) * (1 - Math.pow(2, -4 * expand * luma * luma));
                        apply(EXPAND, ap1, m);
                        for (int k = 0; k < 3; k++) ap1[k] += (m[k] - ap1[k]) * share;
                    }
                    CgColorGrade.correctAll(grade, ap1[0], ap1[1], ap1[2], t);
                    System.arraycopy(t, 0, ap1, 0, 3);
                    apply(BLUE_CORRECT_AP1, ap1, m);
                    for (int k = 0; k < 3; k++) ap1[k] += (m[k] - ap1[k]) * blue;
                    if (amount > 0) {
                        curve.apply(ap1, m);
                        for (int k = 0; k < 3; k++) ap1[k] += (m[k] - ap1[k]) * amount;
                    }
                    apply(BLUE_CORRECT_INV_AP1, ap1, m);
                    for (int k = 0; k < 3; k++) ap1[k] += (m[k] - ap1[k]) * blue;
                    apply(AP1_TO_SRGB, ap1, t);
                    int at = 3 * (r + SIZE * (g + SIZE * b));
                    for (int k = 0; k < 3; k++) {
                        double film = Math.max(0, t[k]) * grade[CgColorGrade.TINT + k];
                        out[at + k] = (float) Math.max(0, Math.min(1, srgb(Math.pow(film, gamma)) / SCALE));
                    }
                }
            }
        }
    }

    /**
     * 5.1's {@code FilmToneMap} (TonemapCommon.ush): ACES' glow and red modifier, then the fitted toe, line and shoulder.
     * Its constants {@link #set} again for each table, so a fading look makes none.
     */
    static final class FilmCurve {
        double slope, toe, shoulder, black, white, toeScale, shoulderScale, toeMatch, shoulderMatch;

        FilmCurve set(double slope, double toe, double shoulder, double black, double white) {
            this.slope = slope;
            this.toe = toe;
            this.shoulder = shoulder;
            this.black = black;
            this.white = white;
            toeScale = 1 + black - toe;
            shoulderScale = 1 + white - shoulder;
            double inMatch = 0.18, outMatch = 0.18, tm;
            if (toe > 0.8) {
                tm = (1 - toe - outMatch) / slope + Math.log10(inMatch);
            } else {
                double bt = (outMatch + black) / toeScale - 1;
                tm = Math.log10(inMatch) - 0.5 * Math.log((1 + bt) / (1 - bt)) * (toeScale / slope);
            }
            toeMatch = tm;
            double straightMatch = (1 - toe) / slope - toeMatch;
            shoulderMatch = shoulder / slope - straightMatch;
            return this;
        }

        /** ACEScg {@code ap1} through the curve into {@code out}, positive ACEScg. */
        void apply(double[] ap1, double[] out) {
            double[] ap0 = new double[3];
            CgFilmLut.apply(AP1_TO_AP0, ap1, ap0);
            double sat = saturation(ap0);
            double s = sigmoid((sat - 0.4) / 0.2);
            double glow = 1 + glow(yc(ap0), 0.05 * s, 0.08);
            for (int k = 0; k < 3; k++) ap0[k] *= glow;
            double centred = centreHue(hue(ap0));
            double w = smoothstep(1 - Math.abs(2 * centred / 135));
            w *= w;
            ap0[0] += w * sat * (0.03 - ap0[0]) * (1 - 0.82);
            double[] work = new double[3];
            CgFilmLut.apply(AP0_TO_AP1, ap0, work);
            for (int k = 0; k < 3; k++) work[k] = Math.max(0, work[k]);
            double y = work[0] * AP1_Y_R + work[1] * AP1_Y_G + work[2] * AP1_Y_B;
            for (int k = 0; k < 3; k++) work[k] = y + (work[k] - y) * 0.96;
            double straightMatch = (1 - toe) / slope - toeMatch;
            for (int k = 0; k < 3; k++) {
                double log = Math.log10(work[k]);
                double straight = slope * (log + straightMatch);
                double toeColour = -black + 2 * toeScale / (1 + Math.exp(-2 * slope / toeScale * (log - toeMatch)));
                double shoulderColour = 1 + white - 2 * shoulderScale / (1 + Math.exp(2 * slope / shoulderScale * (log - shoulderMatch)));
                if (!(log < toeMatch)) toeColour = straight;
                if (!(log > shoulderMatch)) shoulderColour = straight;
                double t = Math.max(0, Math.min(1, (log - toeMatch) / (shoulderMatch - toeMatch)));
                if (Double.isNaN(t)) t = 0;
                if (shoulderMatch < toeMatch) t = 1 - t;
                t = (3 - 2 * t) * t * t;
                // t at an end takes that end alone: a black channel's straight line is minus infinity
                out[k] = t <= 0 ? toeColour : t >= 1 ? shoulderColour : toeColour + (shoulderColour - toeColour) * t;
            }
            double ty = out[0] * AP1_Y_R + out[1] * AP1_Y_G + out[2] * AP1_Y_B;
            for (int k = 0; k < 3; k++) out[k] = Math.max(0, ty + (out[k] - ty) * 0.93);
        }

        private static double saturation(double[] c) {
            double min = Math.min(c[0], Math.min(c[1], c[2])), max = Math.max(c[0], Math.max(c[1], c[2]));
            return (Math.max(max, 1e-10) - Math.max(min, 1e-10)) / Math.max(max, 1e-2);
        }

        private static double yc(double[] c) {
            double chroma = Math.sqrt(Math.max(0, c[2] * (c[2] - c[1]) + c[1] * (c[1] - c[0]) + c[0] * (c[0] - c[2])));
            return (c[2] + c[1] + c[0] + 1.75 * chroma) / 3;
        }

        private static double sigmoid(double x) {
            double t = Math.max(1 - Math.abs(0.5 * x), 0);
            return 0.5 * (1 + Math.signum(x) * (1 - t * t));
        }

        private static double glow(double yc, double gain, double mid) {
            if (yc <= 2.0 / 3.0 * mid) return gain;
            if (yc >= 2 * mid) return 0;
            return gain * (mid / yc - 0.5);
        }

        private static double hue(double[] c) {
            if (c[0] == c[1] && c[1] == c[2]) return 0;
            double h = Math.toDegrees(Math.atan2(Math.sqrt(3) * (c[1] - c[2]), 2 * c[0] - c[1] - c[2]));
            if (h < 0) h += 360;
            return clamp(h, 0, 360);
        }

        private static double centreHue(double hue) {
            return hue < -180 ? hue + 360 : hue > 180 ? hue - 360 : hue;
        }

        private static double smoothstep(double x) {
            double t = clamp(x, 0, 1);
            return t * t * (3 - 2 * t);
        }
    }

    /**
     * The legacy film curve (4.21's {@code FilmPostProcess}), its constants built from the settings as the renderer
     * builds them: a toe, a straight mid section and a shoulder healing toward white.
     */
    static final class Curve {
        double cd1, cd3cm3, cm2, cm0cd0, cd2, ch0cm1, ch3, ch1, ch2;
        double saturation;

        Curve set(double contrast, double dynamicRange, double toeAmount, double healAmount, double saturation) {
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
            return this;
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
