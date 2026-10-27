package com.crystalgraphics.sequence.time;

/**
 * Frames a second as a ratio, Unreal's {@code FFrameRate}: a sequence's tick resolution (24,000 ticks a second) and its
 * display rate (30 frames a second, or NTSC's 30000/1001). Converts seconds and frame times between rates exactly where
 * Unreal does, so a key keeps its tick at any resolution.
 *
 * <pre>{@code
 * CgFrameRate ticks = new CgFrameRate(24000, 1), display = CgFrameRate.of(29.97);   // 30000/1001
 * long t = display.asFrameTime(0.2);                     // frame 5 and a sub-frame of 0.994
 * long inTicks = CgFrameRate.convert(t, display, ticks); // the same instant at 24,000 a second
 * double seconds = ticks.asSeconds(inTicks);             // 0.2 again
 * }</pre>
 *
 * <ul>
 *   <li>Both parts are positive; {@link #of} takes the format's numbers, a whole rate or an NTSC one, and refuses the
 *       rest by name.</li>
 *   <li>A frame time is a {@link CgFrameTime} {@code long}, never a {@code double} of frames.</li>
 * </ul>
 *
 * @param numerator   frames per {@code denominator} seconds
 * @param denominator seconds per {@code numerator} frames
 */
public record CgFrameRate(int numerator, int denominator) {

    public CgFrameRate {
        if (numerator <= 0 || denominator <= 0) {
            throw new IllegalArgumentException("a frame rate of " + numerator + "/" + denominator + ": both parts positive");
        }
    }

    /**
     * {@code rate} frames a second as the format writes it: a whole number, or an NTSC rate ({@code 23.976},
     * {@code 29.97}, {@code 59.94}: a whole number times 1000/1001).
     *
     * <pre>{@code
     * CgFrameRate.of(24000);   // 24000/1
     * CgFrameRate.of(29.97);   // 30000/1001
     * }</pre>
     *
     * @throws IllegalArgumentException for any other rate, naming it
     */
    public static CgFrameRate of(double rate) {
        if (!(rate > 0) || rate > Integer.MAX_VALUE) throw new IllegalArgumentException("a frame rate of " + rate + ": a positive number");
        if (rate == Math.rint(rate)) return new CgFrameRate((int) rate, 1);
        // NTSC: a whole number of frames every 1.001 seconds, written to two or three decimals.
        double whole = Math.rint(rate * 1.001);
        if (Math.abs(whole / 1.001 - rate) < 5e-3) return new CgFrameRate((int) whole * 1000, 1001);
        throw new IllegalArgumentException("a frame rate of " + rate + ": a whole number, or an NTSC rate (n × 1000/1001)");
    }

    /** Frames a second as one number. */
    public double asDecimal() {
        return (double) numerator / denominator;
    }

    /** {@code frameTime} ({@link CgFrameTime}) as seconds: {@code FFrameRate::AsSeconds}. */
    public double asSeconds(long frameTime) {
        long whole = (long) CgFrameTime.frame(frameTime) * denominator;
        double part = (double) CgFrameTime.subFrame(frameTime) * denominator;
        return (whole + part) / numerator;
    }

    /**
     * {@code seconds} as a frame time ({@link CgFrameTime}) at this rate: {@code FFrameRate::AsFrameTime}, the frame
     * floored and the sub-frame kept below {@link CgFrameTime#MAX_SUBFRAME}.
     */
    public long asFrameTime(double seconds) {
        double frames = seconds * numerator / denominator;
        double floor = Math.floor(frames);
        int frame = (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, floor));
        float sub = (float) (frames - floor);
        // The float can round up to 1: that is the next frame.
        int carry = (int) sub;
        sub -= carry;
        return CgFrameTime.of(frame + carry, Math.min(sub, CgFrameTime.MAX_SUBFRAME));
    }

    /**
     * {@code frameTime} at {@code from} as the same instant at {@code to}: {@code ConvertFrameTime}, whole frames through
     * 64-bit integers first, so a tick-resolution key lands on its tick.
     */
    public static long convert(long frameTime, CgFrameRate from, CgFrameRate to) {
        if (from.equals(to)) return frameTime;
        long num = (long) to.numerator * from.denominator, den = (long) to.denominator * from.numerator;
        int frame = CgFrameTime.frame(frameTime);
        long whole = (long) frame * num / den;
        double wholeRest = (double) frame * num / den - whole;
        double part = (double) CgFrameTime.subFrame(frameTime) * num / den + wholeRest;
        double partFloor = Math.floor(part);
        whole += (long) partFloor;
        float sub = (float) (part - partFloor);
        whole = Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, whole));
        return CgFrameTime.of((int) whole, Math.min(sub, CgFrameTime.MAX_SUBFRAME));
    }

    @Override
    public String toString() {
        return denominator == 1 ? numerator + " fps" : numerator + "/" + denominator + " fps";
    }
}
