package com.crystalgraphics.text.shadow;

/**
 * The discrete Gaussian kernel {@link CgMaskBlurFilter} uses below sigma 2.
 *
 * <p>Ported from Skia's {@code src/core/SkGaussFilter.cpp} (BSD-3-Clause, see THIRD-PARTY.md). It is
 * Lindeberg's scale-space kernel, {@code gauss(n; var) = besselI_n(var) / e^var}, rather than a sampled
 * {@code exp}: the two differ most at the small sigmas this is used for.</p>
 *
 * <pre>{@code
 * CgGaussFilter filter = new CgGaussFilter(1.2);
 * int radius = filter.radius();          // 0..4
 * double centre = filter.factor(0);      // factors 0..radius, symmetric, summing to 1 across both sides
 * }</pre>
 *
 * <p>Sigma must be in {@code [0, 2)}.</p>
 */
public final class CgGaussFilter {

    /** {@code SkGaussFilter::kGaussArrayMax}: sigma under 2 needs at most five factors plus one probe. */
    static final int GAUSS_ARRAY_MAX = 6;

    /** "The spec implies that 3% is acceptable, but we just use 1%." */
    private static final double GOOD_ENOUGH = 1.0 / 100.0;

    private final double[] basis = new double[GAUSS_ARRAY_MAX];
    private final int n;

    public CgGaussFilter(double sigma) {
        if (!(sigma >= 0 && sigma < 2)) throw new IllegalArgumentException("sigma must be in [0, 2): " + sigma);
        n = calculateBesselFactors(sigma, basis);
    }

    /** How far the kernel reaches either side of the centre. */
    public int radius() {
        return n - 1;
    }

    /** Factor {@code i} from the centre, {@code 0 <= i <= radius()}. */
    public double factor(int i) {
        return basis[i];
    }

    private static void normalize(int n, double[] gauss) {
        // Carefully add from smallest to largest to calculate the normalizing sum.
        double sum = 0;
        for (int i = n - 1; i >= 1; i--) sum += 2 * gauss[i];
        sum += gauss[0];

        for (int i = 0; i < n; i++) gauss[i] /= sum;

        // The factors should sum to 1. Take any remaining slop, and add it to gauss[0].
        sum = 0;
        for (int i = n - 1; i >= 1; i--) sum += 2 * gauss[i];
        gauss[0] = 1 - sum;
    }

    private static int calculateBesselFactors(double sigma, double[] gauss) {
        double var = sigma * sigma;

        // Abramowitz and Stegun 9.6.10, with 9.6.12 for I_0.
        double d = Math.exp(var);
        double[] b = new double[GAUSS_ARRAY_MAX];
        b[0] = besselI0(var);
        b[1] = besselI1(var);
        gauss[0] = b[0] / d;
        gauss[1] = b[1] / d;

        // Numerical Recipes 3rd Edition, equation 6.5.16. The probe that stops the loop is computed
        // one element past the last factor, so n ends as the factor count.
        int n = 1;
        while (gauss[n] > GOOD_ENOUGH) {
            b[n + 1] = -(2 * n / var) * b[n] + b[n - 1];
            gauss[n + 1] = b[n + 1] / d;
            n += 1;
        }

        normalize(n, gauss);
        return n;
    }

    private static double besselI0(double t) {
        double tSquaredOver4 = t * t / 4.0;
        double sum = 1.0;
        double factor = 1.0;
        int k = 1;
        while (factor > 1.0 / 1000000.0) {
            factor *= tSquaredOver4 / (k * k);
            sum += factor;
            k += 1;
        }
        return sum;
    }

    private static double besselI1(double t) {
        double tSquaredOver4 = t * t / 4.0;
        double sum = t / 2.0;
        double factor = sum;
        int k = 1;
        while (factor > 1.0 / 1000000.0) {
            factor *= tSquaredOver4 / (k * (k + 1));
            sum += factor;
            k += 1;
        }
        return sum;
    }
}
