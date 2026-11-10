package com.crystalgraphics.api.text;

/**
 * Numbers as a game's HUD shows them.
 *
 * <pre>{@code
 * CgNumberFormat.compact(847);            // "847"
 * CgNumberFormat.compact(2_699);          // "2.7k"
 * CgNumberFormat.compact(206_400);        // "206.4k"
 * CgNumberFormat.compact(2_400_000);      // "2.4m"
 * CgNumberFormat.compact(2_200_000_000L); // "2.2b"
 *
 * canvas.text().style(DAMAGE).text(CgNumberFormat.compact(hit.amount())).font(font).at(x, y).submit();
 * }</pre>
 *
 * <ul>
 *   <li>Allocates the string it answers: format once per value, not per frame.</li>
 *   <li>Negative values are formatted as their magnitude, with a leading minus.</li>
 * </ul>
 */
public final class CgNumberFormat {

    private static final char[] UNITS = {'k', 'm', 'b', 't'};

    private CgNumberFormat() {
    }

    /**
     * {@code value} as it is below 1,000, else to a tenth of its unit, rounded half up: {@code 1.0k}, {@code 2.7k},
     * {@code 206.4k}, {@code 2.4m}, {@code 2.2b}, {@code 1.5t}.
     *
     * <ul>
     *   <li>A value rounding up to 1,000 of a unit takes the next: 999,950 is {@code 1.0m}, never {@code 1000.0k}.</li>
     *   <li>Past the trillions it stays in them: 2 * 10^15 is {@code 2000.0t}.</li>
     *   <li>Exact for every {@code long}: integer arithmetic, no float rounding.</li>
     * </ul>
     */
    public static String compact(long value) {
        if (value < 0) return "-" + compact(value == Long.MIN_VALUE ? Long.MAX_VALUE : -value);
        if (value < 1_000) return Long.toString(value);
        long unit = 1_000;
        int u = 0;
        long tenths = tenths(value, unit);
        while (tenths >= 10_000 && u < UNITS.length - 1) {
            unit *= 1_000;
            u++;
            tenths = tenths(value, unit);
        }
        return tenths / 10 + "." + tenths % 10 + UNITS[u];
    }

    /** {@code value / unit} in tenths, rounded half up, without overflowing for any positive {@code long}. */
    private static long tenths(long value, long unit) {
        long tenth = unit / 10;
        return value / tenth + (value % tenth >= tenth / 2 ? 1 : 0);
    }
}
