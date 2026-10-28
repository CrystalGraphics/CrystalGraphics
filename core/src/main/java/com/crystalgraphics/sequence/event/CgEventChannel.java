package com.crystalgraphics.sequence.event;

/**
 * An event track's one-shots: named keys on integer ticks, fired as play sweeps across them. Mirrors the sweep in
 * Unreal's {@code FMovieSceneEventSectionTemplate::EvaluateSwept} ({@code MovieSceneTracks/Private/Evaluation/
 * MovieSceneEventTemplate.cpp:152-206}): forwards fires the keys in the range in order, backwards fires them in
 * reverse, and each direction is switched on its own.
 *
 * <pre>{@code
 * CgEventChannel hits = new CgEventChannel(new int[] {100, 4800}, new String[] {"windup", "impact"}, true, false);
 * hits.sweep(0, 4000, (tick, name) -> log(name));        // windup
 * hits.sweep(4000, 9600, (tick, name) -> log(name));     // impact: a 200 ms frame at 24000 ticks a second
 * hits.sweep(4000, 9600, ...);                           // nothing: the range was already swept
 * }</pre>
 *
 * <ul>
 *   <li>Forward sweeps cover {@code (from, to]} and backward ones {@code [to, from)}, so consecutive frames tile and a
 *       key on a frame boundary fires in exactly one of them.</li>
 *   <li>A cursor follows play, so a sweep costs the keys it crosses and allocates nothing. After a seek or jump call
 *       {@link #place}: it fires nothing, the skipped keys are not played.</li>
 *   <li>A key at tick 0 is crossed by {@code place(-1)} then a sweep, not by a sweep from 0.</li>
 *   <li>Not thread safe: one channel per player.</li>
 * </ul>
 */
public final class CgEventChannel {

    /** What a crossed key calls. */
    @FunctionalInterface
    public interface Listener {
        void fired(int tick, String name);
    }

    private final int[] ticks;
    private final String[] names;
    private final boolean fireForwards, fireBackwards;
    /** Index of the first key after the last tick played: keys below it were crossed going forwards. */
    private int cursor;

    /** {@code ticks} ascending, with {@code names} beside them; copied. */
    public CgEventChannel(int[] ticks, String[] names, boolean fireForwards, boolean fireBackwards) {
        if (ticks.length != names.length) throw new IllegalArgumentException("ticks " + ticks.length + " but names " + names.length);
        for (int i = 1; i < ticks.length; i++) {
            if (ticks[i] < ticks[i - 1]) throw new IllegalArgumentException("event ticks must ascend: " + ticks[i - 1] + " then " + ticks[i]);
        }
        this.ticks = ticks.clone();
        this.names = names.clone();
        this.fireForwards = fireForwards;
        this.fireBackwards = fireBackwards;
    }

    public int size() {
        return ticks.length;
    }

    /** Puts the cursor at {@code tick} without firing: keys at or before it count as played. */
    public void place(int tick) {
        int lo = 0, hi = ticks.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (ticks[mid] <= tick) lo = mid + 1;
            else hi = mid;
        }
        cursor = lo;
    }

    /**
     * Plays from {@code from} to {@code to} and fires every key crossed. A {@code to} below {@code from} is backward
     * play and fires only where the channel was made to fire backwards (the cursor still follows).
     */
    public void sweep(int from, int to, Listener listener) {
        if (to >= from) {
            while (cursor < ticks.length && ticks[cursor] <= to) {
                if (fireForwards && ticks[cursor] > from) listener.fired(ticks[cursor], names[cursor]);
                cursor++;
            }
        } else {
            while (cursor > 0 && ticks[cursor - 1] >= to) {
                cursor--;
                if (fireBackwards && ticks[cursor] < from) listener.fired(ticks[cursor], names[cursor]);
            }
        }
    }
}
