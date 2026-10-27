package com.crystalgraphics.sequence.time;

import com.crystalgraphics.sequence.CgEventChannel;

/**
 * The span of a sequence one update evaluates, Unreal's {@code FMovieSceneEvaluationRange}: from where the last update
 * stopped to where this one does, at the sequence's tick resolution, so whatever lies inside a long frame is still
 * crossed. A {@link CgSequencePlaybackPosition} fills it; tracks read it.
 *
 * <pre>{@code
 * CgSequenceEvaluationRange r = position.playToSeconds(age);
 * r.sweep(hits, (tick, name) -> fire(name));   // each event key inside the range, once
 * int first = r.keyFrom(), end = r.keyEnd();   // the ticks whose keys it covers: [first, end)
 * }</pre>
 *
 * <ul>
 *   <li>The same object is filled again by the next update: read it before then, and copy what you keep.</li>
 *   <li>A jump's range ({@link #jumped()}) is the one point jumped to: place event channels there
 *       ({@link CgEventChannel#place}) rather than sweeping them, so a jump fires nothing it skips.</li>
 * </ul>
 */
public final class CgSequenceEvaluationRange {

    /** Which way time moved: Unreal's {@code EPlayDirection}. */
    public enum Direction { FORWARDS, BACKWARDS }

    private long lower, upper;
    private boolean lowerInclusive, upperInclusive, jumped, set;
    private Direction direction = Direction.FORWARDS;

    /** Its earliest time ({@link CgFrameTime}), at the tick resolution. */
    public long lower() {
        return lower;
    }

    /** Its latest time ({@link CgFrameTime}), at the tick resolution. */
    public long upper() {
        return upper;
    }

    public boolean lowerInclusive() {
        return lowerInclusive;
    }

    public boolean upperInclusive() {
        return upperInclusive;
    }

    /** Which way it was played; a range of one point takes the direction asked for. */
    public Direction direction() {
        return direction;
    }

    /** Whether it came from a jump: one point, nothing between crossed. */
    public boolean jumped() {
        return jumped;
    }

    /** Whether an update has filled it since its position was reset. */
    public boolean isSet() {
        return set;
    }

    /** Where this update ended: the upper bound played forwards, the lower one backwards. */
    public long current() {
        return direction == Direction.FORWARDS ? upper : lower;
    }

    /**
     * The first tick whose keys it covers: {@code TimeRangeToNumberRange}'s lower bound. A bound with a sub-frame, or
     * exclusive, is already past its frame.
     */
    public int keyFrom() {
        int from = CgFrameTime.frame(lower);
        int end = keyEnd();
        if ((CgFrameTime.subFrame(lower) != 0f || !lowerInclusive) && from < end) from++;
        return from;
    }

    /** The tick after the last whose keys it covers, exclusive: {@code TimeRangeToNumberRange}'s upper bound. */
    public int keyEnd() {
        int end = CgFrameTime.frame(upper);
        if (CgFrameTime.subFrame(upper) != 0f || upperInclusive) end++;
        return end;
    }

    /**
     * Fires each of {@code channel}'s keys inside this range once, in the direction it was played. A key on the bound
     * two consecutive ranges share fires in exactly one of them.
     */
    public void sweep(CgEventChannel channel, CgEventChannel.Listener listener) {
        if (!set) return;
        int from = keyFrom(), end = keyEnd();
        if (from >= end) return;
        // The channel sweeps (from, to] forwards and [to, from) backwards.
        if (direction == Direction.FORWARDS) channel.sweep(from - 1, end - 1, listener);
        else channel.sweep(end, from, listener);
    }

    void clear() {
        set = false;
        jumped = false;
    }

    void point(long time, Direction preferred) {
        lower = upper = time;
        lowerInclusive = upperInclusive = true;
        direction = preferred;
        jumped = true;
        set = true;
    }

    /** {@code CalculateEvaluationRange}: from {@code previous} to {@code current}, the earlier end exclusive unless asked. */
    void play(long current, long previous, boolean inclusivePrevious, Direction preferred) {
        jumped = false;
        set = true;
        if (current == previous) {
            lower = upper = current;
            lowerInclusive = upperInclusive = true;
            direction = preferred;
        } else if (current < previous) {
            lower = current;
            lowerInclusive = true;
            upper = previous;
            upperInclusive = inclusivePrevious;
            direction = Direction.BACKWARDS;
        } else {
            lower = previous;
            lowerInclusive = inclusivePrevious;
            upper = current;
            upperInclusive = true;
            direction = Direction.FORWARDS;
        }
    }

    @Override
    public String toString() {
        return (lowerInclusive ? "[" : "(") + CgFrameTime.toString(lower) + ", " + CgFrameTime.toString(upper)
                + (upperInclusive ? "]" : ")") + " " + direction + (jumped ? " jumped" : "");
    }
}
