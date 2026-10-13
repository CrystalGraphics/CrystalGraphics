package com.crystalgraphics.job.progress;

/**
 * How work says how far along it is. A job gets one from {@code CgJobContext.progress()}; anyone reads the latest
 * {@link CgProgressState}.
 *
 * <pre>{@code
 * CgJob.run(ctx -> {
 *     CgProgress progress = ctx.progress();
 *     progress.begin("Indexing classes", files.size());
 *     for (int i = 0; i < files.size(); i++) {
 *         ctx.throwIfCancelled();
 *         progress.detail(files.get(i).name());
 *         index(files.get(i));
 *         progress.advance(i + 1);                    // absolute: units done so far
 *     }
 *     return index;
 * });
 * }</pre>
 *
 * A download announces itself before the size is known, then retargets:
 *
 * <pre>{@code
 * progress.begin("Downloading engine", -1, CgProgress.Unit.BYTES);   // indeterminate while connecting
 * progress.begin("Downloading engine", response.length(), CgProgress.Unit.BYTES);
 * }</pre>
 *
 * <ul>
 *   <li>{@link #advance} takes the absolute count, never a delta: a dropped delta would desync the bar for good.</li>
 *   <li>Each report allocates a reading: report on a threshold, not per 8 KB chunk.</li>
 *   <li>Work that never calls {@link #begin} has no progress; a reader treats it as not worth showing.</li>
 * </ul>
 */
public interface CgProgress {

    /** What {@code done} and {@code total} count. {@link CgProgressState#summary()} formats bytes only. */
    enum Unit {
        ITEMS,
        BYTES,
    }

    /** Discards everything. */
    CgProgress NONE = new CgProgress() {
        @Override
        public void begin(String what, long total, Unit unit) {
        }

        @Override
        public void advance(long done) {
        }

        @Override
        public void detail(String item) {
        }
    };

    /** Announces the work, counting items. A second call retargets. {@code total} negative for indeterminate. */
    default void begin(String what, long total) {
        begin(what, total, Unit.ITEMS);
    }

    /** Announces the work, saying what it counts. A second call retargets and keeps the rate measured so far. */
    void begin(String what, long total, Unit unit);

    /** Units done so far, against {@link #begin}'s total. Before {@code begin} it is ignored. */
    void advance(long done);

    /** The secondary line: which item. */
    void detail(String item);
}
