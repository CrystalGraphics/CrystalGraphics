package com.crystalgraphics.text.shadow;

import java.util.Arrays;

/**
 * Blurs an 8-bit coverage mask the way Chrome blurs a glyph for {@code text-shadow}.
 *
 * <p>Ported from Skia's {@code src/core/SkMaskBlurFilter.cpp} (BSD-3-Clause, see THIRD-PARTY.md), the
 * blur {@code SkScalerContext} applies to every glyph of a mask-filtered strike. Below sigma 2 it is a
 * discrete Gaussian ({@link CgGaussFilter}); from 2 up it is the SVG 1.1 {@code feGaussianBlur} triple
 * box. The SIMD arithmetic is ported as scalar loops over the same fixed-point values, so the output
 * matches byte for byte.</p>
 *
 * <pre>{@code
 * CgMaskBlurFilter filter = new CgMaskBlurFilter(sigmaPx);
 * if (!filter.hasNoBlur()) {
 *     int border = filter.border();                      // the mask grows this much on every side
 *     byte[] out = filter.blur(coverage, width, height); // (width + 2 * border) x (height + 2 * border)
 * }
 * }</pre>
 *
 * <p>Sigma is in pixels of the mask and is clamped to {@code [0, 135]}, Skia's overflow bound. Under
 * {@link #NO_BLUR_SIGMA} there is no blur at all and {@link #blur} must not be called.</p>
 */
public final class CgMaskBlurFilter {

    /** The largest sigma whose triple-box sums cannot overflow 32 bits for a full-coverage mask. */
    public static final double MAX_SIGMA = 135.0;

    /** Skia's historical no-blur cutoff, {@code 1/3}. */
    public static final double NO_BLUR_SIGMA = 1.0 / 3.0;

    /** Where {@code blur} switches from the small Gaussian to the triple box. */
    public static final double BOX_BLUR_MIN_SIGMA = 2.0;

    private static final int HALF_8_8 = 0x80;

    private final double sigma;

    public CgMaskBlurFilter(double sigma) {
        this.sigma = Math.max(0.0, Math.min(MAX_SIGMA, sigma));
    }

    public double sigma() {
        return sigma;
    }

    public boolean hasNoBlur() {
        return sigma < NO_BLUR_SIGMA;
    }

    /** How many pixels the blurred mask grows on each side. {@code 0} when there is no blur. */
    public int border() {
        if (hasNoBlur()) return 0;
        if (sigma < BOX_BLUR_MIN_SIGMA) return new CgGaussFilter(sigma).radius();
        return boxBorder(boxWindow(sigma));
    }

    /**
     * {@code PlanGauss}'s window: the only thing a triple-box blur's output depends on, so two sigmas
     * with one window produce identical masks.
     */
    public static int boxWindow(double sigma) {
        int possibleWindow = (int) Math.floor(sigma * 3 * Math.sqrt(2 * Math.PI) / 4 + 0.5);
        return Math.max(1, possibleWindow);
    }

    /** {@code PlanGauss::fBorder} for a window. */
    public static int boxBorder(int window) {
        return (window & 1) == 1 ? 3 * ((window - 1) / 2) : 3 * (window / 2) - 1;
    }

    /**
     * The mask blurred, {@code (srcW + 2 * border()) x (srcH + 2 * border())}, row-major.
     *
     * @throws IllegalStateException when {@link #hasNoBlur()}
     */
    public byte[] blur(byte[] src, int srcW, int srcH) {
        if (hasNoBlur()) throw new IllegalStateException("sigma " + sigma + " has no blur");
        if (srcW < 0 || srcH < 0 || src.length < srcW * srcH) {
            throw new IllegalArgumentException("mask " + srcW + "x" + srcH + " does not fit " + src.length);
        }
        return sigma < BOX_BLUR_MIN_SIGMA ? smallBlur(src, srcW, srcH) : boxBlur(src, srcW, srcH);
    }

    // ── sigma >= 2: PlanGauss ──────────────────────────────────────────────────────────────────────

    private byte[] boxBlur(byte[] src, int srcW, int srcH) {
        PlanGauss plan = new PlanGauss(sigma);
        int border = plan.border;
        int dstW = srcW + 2 * border;
        int dstH = srcH + 2 * border;
        byte[] dst = new byte[dstW * dstH];
        if (srcW == 0 || srcH == 0) return dst;

        int[] buffer = new int[plan.bufferSize()];

        // Blur horizontally, and transpose: tmp is dstW rows of srcH, a column of the result per row.
        int tmpW = srcH;
        int tmpH = dstW;
        byte[] tmp = new byte[tmpW * tmpH];

        for (int y = 0; y < srcH; ++y) {
            plan.blur(src, y * srcW, 1, srcW, tmp, y, tmpW, y + tmpW * tmpH, buffer, srcW);
        }

        // Blur vertically (scan in memory order because of the transposition), and transpose back.
        for (int y = 0; y < tmpH; y++) {
            plan.blur(tmp, y * tmpW, 1, tmpW, dst, y, dstW, y + dstW * dstH, buffer, tmpW);
        }
        return dst;
    }

    private static final class PlanGauss {
        final long weight;
        final int border;
        final int slidingWindow;
        final int pass0Size;
        final int pass1Size;
        final int pass2Size;

        PlanGauss(double sigma) {
            int window = boxWindow(sigma);

            pass0Size = window - 1;
            pass1Size = window - 1;
            pass2Size = (window & 1) == 1 ? window - 1 : window;

            border = boxBorder(window);
            slidingWindow = 2 * border + 1;

            // Odd windows divide by window^3, even ones by window * window * (window + 1).
            long window2 = (long) window * window;
            long window3 = window2 * window;
            long divisor = (window & 1) == 1 ? window3 : window3 + window2;

            weight = Math.round(1.0 / divisor * (double) (1L << 32));
        }

        int bufferSize() {
            return pass0Size + pass1Size + pass2Size;
        }

        private int finalScale(long sum) {
            // ((weight * sum + 2^31) >> 32), all uint64 in Skia. Neither operand exceeds 2^32, so the
            // product fits an unsigned 64-bit value, which an unsigned shift reads correctly.
            return (int) ((weight * (sum & 0xFFFFFFFFL) + (1L << 31)) >>> 32) & 0xFF;
        }

        /**
         * {@code PlanGauss::Scan::blur} over {@code count} source samples starting at {@code srcAt},
         * writing every {@code dstStride}th element from {@code dstAt} up to {@code dstEnd}. Every pass
         * buffer is non-empty, since sigma 2 and up makes the window at least 4.
         */
        void blur(byte[] srcArr, int srcAt, int srcStep, int count,
                  byte[] dstArr, int dstAt, int dstStride, int dstEnd,
                  int[] buffer, int width) {
            int noChangeCount = slidingWindow > width ? slidingWindow - width : 0;
            int buffer0 = 0;
            int buffer0End = pass0Size;
            int buffer1 = buffer0End;
            int buffer1End = buffer1 + pass1Size;
            int buffer2 = buffer1End;
            int buffer2End = buffer2 + pass2Size;

            int buffer0Cursor = buffer0;
            int buffer1Cursor = buffer1;
            int buffer2Cursor = buffer2;

            Arrays.fill(buffer, 0, buffer2End, 0);

            int sum0 = 0;
            int sum1 = 0;
            int sum2 = 0;

            int dst = dstAt;
            // Consume the source generating pixels.
            for (int i = 0; i < count; i++, dst += dstStride) {
                int leadingEdge = srcArr[srcAt + i * srcStep] & 0xFF;
                sum0 += leadingEdge;
                sum1 += sum0;
                sum2 += sum1;

                dstArr[dst] = (byte) finalScale(sum2);

                sum2 -= buffer[buffer2Cursor];
                buffer[buffer2Cursor] = sum1;
                buffer2Cursor = (buffer2Cursor + 1) < buffer2End ? buffer2Cursor + 1 : buffer2;

                sum1 -= buffer[buffer1Cursor];
                buffer[buffer1Cursor] = sum0;
                buffer1Cursor = (buffer1Cursor + 1) < buffer1End ? buffer1Cursor + 1 : buffer1;

                sum0 -= buffer[buffer0Cursor];
                buffer[buffer0Cursor] = leadingEdge;
                buffer0Cursor = (buffer0Cursor + 1) < buffer0End ? buffer0Cursor + 1 : buffer0;
            }

            // The leading edge is off the right side of the mask.
            for (int i = 0; i < noChangeCount; i++) {
                sum1 += sum0;
                sum2 += sum1;

                dstArr[dst] = (byte) finalScale(sum2);

                sum2 -= buffer[buffer2Cursor];
                buffer[buffer2Cursor] = sum1;
                buffer2Cursor = (buffer2Cursor + 1) < buffer2End ? buffer2Cursor + 1 : buffer2;

                sum1 -= buffer[buffer1Cursor];
                buffer[buffer1Cursor] = sum0;
                buffer1Cursor = (buffer1Cursor + 1) < buffer1End ? buffer1Cursor + 1 : buffer1;

                sum0 -= buffer[buffer0Cursor];
                buffer[buffer0Cursor] = 0;
                buffer0Cursor = (buffer0Cursor + 1) < buffer0End ? buffer0Cursor + 1 : buffer0;

                dst += dstStride;
            }

            // Starting from the right, fill in the rest of the buffer.
            Arrays.fill(buffer, 0, buffer2End, 0);
            sum0 = sum1 = sum2 = 0;

            int dstCursor = dstEnd;
            int src = count;
            while (dstCursor > dst) {
                dstCursor -= dstStride;
                int leadingEdge = srcArr[srcAt + (--src) * srcStep] & 0xFF;
                sum0 += leadingEdge;
                sum1 += sum0;
                sum2 += sum1;

                dstArr[dstCursor] = (byte) finalScale(sum2);

                sum2 -= buffer[buffer2Cursor];
                buffer[buffer2Cursor] = sum1;
                buffer2Cursor = (buffer2Cursor + 1) < buffer2End ? buffer2Cursor + 1 : buffer2;

                sum1 -= buffer[buffer1Cursor];
                buffer[buffer1Cursor] = sum0;
                buffer1Cursor = (buffer1Cursor + 1) < buffer1End ? buffer1Cursor + 1 : buffer1;

                sum0 -= buffer[buffer0Cursor];
                buffer[buffer0Cursor] = leadingEdge;
                buffer0Cursor = (buffer0Cursor + 1) < buffer0End ? buffer0Cursor + 1 : buffer0;
            }
        }
    }

    // ── sigma < 2: small_blur ──────────────────────────────────────────────────────────────────────

    /**
     * {@code small_blur}: vertically into the destination offset by the radius, then horizontally.
     * Mask values are 8.8 fixed point, factors 0.16, products {@code mulhi}, and every register is a
     * {@code uint16} that starts at a half so the final shift rounds.
     */
    private byte[] smallBlur(byte[] src, int srcW, int srcH) {
        CgGaussFilter filter = new CgGaussFilter(sigma);
        int radius = filter.radius();
        int[] gauss = new int[CgGaussFilter.GAUSS_ARRAY_MAX];
        for (int i = 0; i <= radius; i++) gauss[i] = (int) Math.round(filter.factor(i) * (1 << 16)) & 0xFFFF;

        int dstW = srcW + 2 * radius;
        int dstH = srcH + 2 * radius;
        byte[] dst = new byte[dstW * dstH];
        if (radius == 0 || srcW == 0 || srcH == 0) {
            // radius 0 only arises at the no-blur cutoff; the kernel is then the identity.
            for (int y = 0; y < srcH; y++) System.arraycopy(src, y * srcW, dst, (y + radius) * dstW + radius, srcW);
            return dst;
        }

        // Blur vertically and copy to destination, columns offset by the radius.
        int[] d = new int[2 * radius];
        int[] v = new int[radius + 1];
        for (int x = 0; x < srcW; x++) {
            Arrays.fill(d, HALF_8_8);
            for (int y = 0; y < srcH; y++) {
                int s = (src[y * srcW + x] & 0xFF) << 8;
                for (int k = 0; k <= radius; k++) v[k] = mulhi(s, gauss[k]);
                int answer = (d[0] + v[radius]) & 0xFFFF;
                for (int i = 0; i < 2 * radius - 1; i++) {
                    d[i] = (d[i + 1] + v[Math.abs(radius - 1 - i)]) & 0xFFFF;
                }
                d[2 * radius - 1] = (v[radius] + HALF_8_8) & 0xFFFF;
                dst[y * dstW + x + radius] = (byte) (answer >>> 8);
            }
            for (int i = 0; i < 2 * radius; i++) {
                dst[(srcH + i) * dstW + x + radius] = (byte) (d[i] >>> 8);
            }
        }

        // Blur horizontally: each row's source is columns [radius, radius + srcW).
        int[] row = new int[dstW];
        for (int y = 0; y < dstH; y++) {
            int base = y * dstW;
            Arrays.fill(row, HALF_8_8);
            for (int n = 0; n < srcW; n++) {
                int s = (dst[base + radius + n] & 0xFF) << 8;
                for (int j = 0; j <= 2 * radius; j++) {
                    row[n + j] = (row[n + j] + mulhi(s, gauss[Math.abs(j - radius)])) & 0xFFFF;
                }
            }
            for (int x = 0; x < dstW; x++) dst[base + x] = (byte) (row[x] >>> 8);
        }
        return dst;
    }

    private static int mulhi(int a, int b) {
        return (a * b) >>> 16;
    }
}
