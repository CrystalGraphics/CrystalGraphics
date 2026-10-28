package com.crystalgraphics.sequence.time;

import com.crystalgraphics.sequence.definition.CgSequenceDefinition;

/**
 * Where a playing sequence is, and the span each update evaluates: Unreal's {@code FMovieScenePlaybackPosition}. Time is
 * given at the display rate and evaluated at the tick resolution; each {@link #playTo} returns the range from the last
 * position to the new one, so an event inside a 200 ms frame fires once, and a {@link #jumpTo} lands on one point.
 *
 * <pre>{@code
 * CgSequencePlaybackPosition position = CgSequencePlaybackPosition.of(definition);   // at the playback range's start
 * CgSequenceEvaluationRange r = position.playToSeconds(age);   // each update: seconds since it started
 * r.sweep(hits, listener);
 *
 * position.jumpToSeconds(1.5);                                 // a seek or a late joiner: lands, crosses nothing
 * }</pre>
 *
 * <ul>
 *   <li>The range returned is one object, filled again by the next call.</li>
 *   <li>A frame-locked sequence ({@link CgSequenceDefinition.Evaluation#FRAME_LOCKED}) floors every position to a
 *       whole display frame; the rest keep sub-frames.</li>
 *   <li>The first {@code playTo} after {@link #reset} or {@link #jumpTo} includes the position it starts from.</li>
 *   <li>Allocates nothing per update.</li>
 * </ul>
 */
public final class CgSequencePlaybackPosition {

    private final CgFrameRate input, output;
    private final boolean frameLocked;
    /** The playback range's first tick, at the input rate. */
    private final long start;
    private final CgSequenceEvaluationRange range = new CgSequenceEvaluationRange();
    private long current, previous;
    private boolean hasPrevious;

    /**
     * A position taking times at {@code input} (a display rate) and evaluating at {@code output} (a tick resolution),
     * starting from tick {@code startTick}: Unreal's {@code SetTimeBase} then {@code Reset}.
     */
    public CgSequencePlaybackPosition(CgFrameRate input, CgFrameRate output, CgSequenceDefinition.Evaluation evaluation, int startTick) {
        this.input = input;
        this.output = output;
        this.frameLocked = evaluation == CgSequenceDefinition.Evaluation.FRAME_LOCKED;
        long at = CgFrameRate.convert(CgFrameTime.of(startTick), output, input);
        this.start = frameLocked ? CgFrameTime.floored(at) : at;
        reset(start);
    }

    /** {@code definition}'s: its display rate in, its tick resolution out, from its playback range's start. */
    public static CgSequencePlaybackPosition of(CgSequenceDefinition definition) {
        return new CgSequencePlaybackPosition(definition.displayRate(), definition.tickResolution(), definition.evaluation(), definition.start());
    }

    /** The playback range's start at the input rate: where {@link #playToSeconds} counts from. */
    public long startInput() {
        return start;
    }

    /** The last position set, at the input rate ({@link CgFrameTime}). */
    public long current() {
        return current;
    }

    /** The last position set, at the tick resolution. */
    public long currentTicks() {
        return CgFrameRate.convert(current, input, output);
    }

    /** Puts it at {@code inputTime} without evaluating: the next {@link #playTo} includes that time. Unreal's {@code Reset}. */
    public void reset(long inputTime) {
        current = inputTime;
        hasPrevious = false;
        range.clear();
    }

    /**
     * Jumps to {@code inputTime} ({@link CgFrameTime}, at the input rate) and returns the one point it lands on, at the
     * tick resolution. The next {@link #playTo} starts from there, inclusive.
     */
    public CgSequenceEvaluationRange jumpTo(long inputTime, CgSequenceEvaluationRange.Direction preferred) {
        if (frameLocked) inputTime = CgFrameTime.floored(inputTime);
        hasPrevious = false;
        current = inputTime;
        range.point(CgFrameRate.convert(inputTime, input, output), preferred);
        return range;
    }

    /**
     * Plays to {@code inputTime} ({@link CgFrameTime}, at the input rate) and returns the range evaluated, at the tick
     * resolution: from the last position played, exclusive, to this one, inclusive.
     */
    public CgSequenceEvaluationRange playTo(long inputTime, CgSequenceEvaluationRange.Direction preferred) {
        if (frameLocked) inputTime = CgFrameTime.floored(inputTime);
        long from = CgFrameRate.convert(hasPrevious ? previous : current, input, output);
        long to = CgFrameRate.convert(inputTime, input, output);
        range.play(to, from, !hasPrevious, preferred);
        current = inputTime;
        previous = inputTime;
        hasPrevious = true;
        return range;
    }

    /** {@link #playTo} {@code seconds} after the playback range's start, forwards. */
    public CgSequenceEvaluationRange playToSeconds(double seconds) {
        return playTo(CgFrameTime.add(start, input.asFrameTime(seconds)), CgSequenceEvaluationRange.Direction.FORWARDS);
    }

    /** {@link #jumpTo} {@code seconds} after the playback range's start. */
    public CgSequenceEvaluationRange jumpToSeconds(double seconds) {
        return jumpTo(CgFrameTime.add(start, input.asFrameTime(seconds)), CgSequenceEvaluationRange.Direction.FORWARDS);
    }

    /** The last range evaluated, or one not {@linkplain CgSequenceEvaluationRange#isSet() set} since {@link #reset}. */
    public CgSequenceEvaluationRange lastRange() {
        return range;
    }
}
