package com.crystalgraphics.net.rate;

/**
 * How often a report from one source may travel: every time, after a quiet spell, or at most so often — and in the
 * last two, always its final value. Declared by whoever knows the tempo of the input (a slider, an aim, a text box),
 * applied by a {@link CgRateGate}.
 *
 * <pre>{@code
 * CgRatePolicy.IMMEDIATE        // a press, a selection: every one
 * CgRatePolicy.TYPING           // quiet for 150 ms, then the text as it stands
 * CgRatePolicy.DRAGGING         // at most every 50 ms, and where it was released
 * CgRatePolicy.throttle(100)    // an aim, at most ten a second
 * }</pre>
 *
 * <p>Dropping the values in between is fine; dropping the last one is data loss, so a debounce or throttle always
 * sends the final value however its window fell.</p>
 */
public final class CgRatePolicy {

    /** Every occurrence travels. */
    public static final CgRatePolicy IMMEDIATE = new CgRatePolicy(0, 0, false);

    /** Typing: quiet for 150 ms, and always the final text. VS Code and Phoenix LiveView land near this. */
    public static final CgRatePolicy TYPING = new CgRatePolicy(150, 0, true);

    /** Dragging: at most one every 50 ms, and always the value it was released at. */
    public static final CgRatePolicy DRAGGING = new CgRatePolicy(0, 50, true);

    private final int debounceMillis;
    private final int throttleMillis;
    private final boolean commitOnRelease;

    private CgRatePolicy(int debounceMillis, int throttleMillis, boolean commitOnRelease) {
        this.debounceMillis = debounceMillis;
        this.throttleMillis = throttleMillis;
        this.commitOnRelease = commitOnRelease;
    }

    /** Waits for {@code millis} of quiet, then sends the final value. */
    public static CgRatePolicy debounce(int millis) {
        return new CgRatePolicy(millis, 0, true);
    }

    /** Sends at most one every {@code millis}, and always the final value. */
    public static CgRatePolicy throttle(int millis) {
        return new CgRatePolicy(0, millis, true);
    }

    public int debounceMillis() {
        return debounceMillis;
    }

    public int throttleMillis() {
        return throttleMillis;
    }

    /** Whether the last value always travels, however the window fell. */
    public boolean commitOnRelease() {
        return commitOnRelease;
    }

    public boolean isImmediate() {
        return debounceMillis == 0 && throttleMillis == 0;
    }

    @Override
    public String toString() {
        if (isImmediate()) return "immediate";
        return (debounceMillis > 0 ? "debounce " + debounceMillis + "ms" : "throttle " + throttleMillis + "ms")
                + (commitOnRelease ? " + commit" : "");
    }
}
