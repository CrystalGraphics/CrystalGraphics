package com.crystalgraphics.sequence.time;

/**
 * A time in a sequence, Unreal's {@code FFrameTime}: a whole frame at some {@link CgFrameRate} and a sub-frame in
 * {@code [0, 1)}, held in one {@code long} so a playing sequence's clock allocates nothing (the frame in the high word,
 * the sub-frame's float bits in the low). Longs of it order as the times do, frame first.
 *
 * <pre>{@code
 * long t = CgFrameTime.of(4800, 0.25f);         // a quarter of the way past tick 4800
 * int tick = CgFrameTime.frame(t);              // 4800
 * int next = CgFrameTime.ceil(t);               // 4801
 * boolean later = t > CgFrameTime.of(4800);     // true: compare the longs directly
 * long sum = CgFrameTime.add(t, CgFrameTime.of(0, 0.75f));   // 4801 exactly
 * }</pre>
 *
 * <ul>
 *   <li>A time means nothing without its rate: keep the rate beside it, and convert with {@link CgFrameRate#convert}.</li>
 *   <li>Only {@link #of} makes one: its sub-frame must be in {@code [0, 1)}.</li>
 * </ul>
 */
public final class CgFrameTime {

    /** The largest sub-frame, the float just below 1: Unreal's {@code FFrameTime::MaxSubframe}. */
    public static final float MAX_SUBFRAME = Math.nextDown(1f);

    private CgFrameTime() {
    }

    /** Frame {@code frame} exactly. */
    public static long of(int frame) {
        return (long) frame << 32;
    }

    /** Frame {@code frame} plus {@code subFrame}, which must be in {@code [0, 1)}. */
    public static long of(int frame, float subFrame) {
        if (!(subFrame >= 0f && subFrame < 1f)) throw new IllegalArgumentException("a sub-frame of " + subFrame + ": in [0, 1)");
        // -0 has its sign bit set, which would order it below every other sub-frame.
        return (long) frame << 32 | (Float.floatToRawIntBits(subFrame + 0f) & 0xFFFF_FFFFL);
    }

    /** The whole frame: {@code FloorToFrame}. */
    public static int frame(long time) {
        return (int) (time >> 32);
    }

    /** The fraction past {@link #frame}, in {@code [0, 1)}. */
    public static float subFrame(long time) {
        return Float.intBitsToFloat((int) time);
    }

    /** The frame at or before {@code time}: {@code FloorToFrame}. */
    public static int floor(long time) {
        return frame(time);
    }

    /** The frame at or after {@code time}: {@code CeilToFrame}. */
    public static int ceil(long time) {
        return subFrame(time) == 0f ? frame(time) : frame(time) + 1;
    }

    /** {@code time} with its sub-frame dropped. */
    public static long floored(long time) {
        return of(frame(time));
    }

    /** {@code a + b}: Unreal's {@code operator+}, the sub-frames summed and their whole part carried. */
    public static long add(long a, long b) {
        float sub = subFrame(a) + subFrame(b);
        int carry = (int) Math.floor(sub);
        return of(frame(a) + frame(b) + carry, Math.min(sub - carry, MAX_SUBFRAME));
    }

    /** {@code time} written as frames, for logs and messages. */
    public static String toString(long time) {
        float sub = subFrame(time);
        return sub == 0f ? Integer.toString(frame(time)) : frame(time) + "+" + sub;
    }
}
