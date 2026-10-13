package com.crystalgraphics.job.progress;

import javax.annotation.Nullable;
import java.util.Locale;

/**
 * One whole reading of some work's progress. Immutable, so a reader never takes {@code done} from one report and
 * {@code total} from another.
 *
 * <pre>{@code
 * CgProgressState state = job.progress();          // null until the work calls begin
 * if (state != null) {
 *     bar.set(state.fraction());                   // negative: indeterminate, draw a stripe
 *     label.set(state.what() + " " + state.detail());
 *     sizeLine.set(state.summary());               // "12.4 MB of 48.9 MB · 30s left", or null for items
 * }
 * }</pre>
 *
 * @param begunAtMillis    when {@link CgProgress#begin} was called, on the reporter's clock
 * @param updatedAtMillis  when this reading was taken, on the same clock
 * @param secondsRemaining the estimate this reading carries, refreshed about once a second; negative when there is
 *                         none yet
 */
public record CgProgressState(String what, String detail, long done, long total, long begunAtMillis,
                              long updatedAtMillis, CgProgress.Unit unit, long secondsRemaining) {

    /** No total, so no bar: a stripe. */
    public boolean isIndeterminate() {
        return total < 0;
    }

    /** How far along, 0..1 and clamped, or negative when indeterminate. A zero total is done. */
    public float fraction() {
        if (isIndeterminate()) return -1f;
        if (total == 0 || done >= total) return 1f;
        if (done <= 0) return 0f;
        return (float) ((double) done / (double) total);
    }

    /**
     * {@code "12.4 MB of 48.9 MB · 30s left"} for bytes, or null for items, whose count the bar already says. A pure
     * function of the reading: a readout recomputed from the frame's clock sawtooths between reports.
     */
    @Nullable
    public String summary() {
        if (unit != CgProgress.Unit.BYTES || done <= 0) return null;
        String transferred = bytes(done);
        if (isIndeterminate()) return transferred;
        StringBuilder out = new StringBuilder(transferred).append(" of ").append(bytes(total));
        if (secondsRemaining > 0 && done < total) out.append(" · ").append(duration(secondsRemaining));
        return out.toString();
    }

    /** {@code 45s left}, {@code 4m left}, {@code 1h 12m left}: a zero smaller unit is dropped. */
    private static String duration(long seconds) {
        if (seconds < 60) return seconds + "s left";
        if (seconds < 3600) return withUnits(seconds / 60, seconds % 60, "m", "s");
        return withUnits(seconds / 3600, (seconds % 3600) / 60, "h", "m");
    }

    private static String withUnits(long large, long small, String largeUnit, String smallUnit) {
        return small == 0 ? large + largeUnit + " left" : large + largeUnit + " " + small + smallUnit + " left";
    }

    /** {@code 12.4 MB}: binary multiples, as file managers report them; one decimal below a hundred. */
    public static String bytes(long count) {
        if (count < 1024L) return count + " B";
        double value = count;
        String[] units = {"KB", "MB", "GB", "TB"};
        int at = -1;
        while (value >= 1024d && at < units.length - 1) {
            value /= 1024d;
            at++;
        }
        return (value < 100d ? String.format(Locale.ROOT, "%.1f", value) : String.valueOf(Math.round(value)))
                + " " + units[at];
    }
}
