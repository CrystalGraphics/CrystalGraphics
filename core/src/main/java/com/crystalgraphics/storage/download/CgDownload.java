package com.crystalgraphics.storage.download;

import com.crystalgraphics.job.progress.CgProgress;

import java.io.Closeable;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.function.BooleanSupplier;

/**
 * One transfer that reports its own progress. Open it, read the stream, close it.
 *
 * <pre>{@code
 * try (CgDownload download = CgDownloads.from(url).named("Mappings").reporting(progress).open()) {
 *     Files.copy(download.stream(), target);
 * }
 * }</pre>
 *
 * <ul>
 *   <li>Progress is announced before the connect, indeterminate, then again with the total: the connect is what
 *       hangs, and a bar that waits for the length shows nothing while it does.</li>
 *   <li>The length comes off the response being read, not a {@code HEAD}, which would cost a second round trip and
 *       a second timeout against a host that is not answering.</li>
 *   <li>Reports are rate-limited at {@link #REPORT_EVERY_BYTES}, as each {@code CgProgressState} allocates.</li>
 *   <li>Reached through {@code CgDownloads.from(url)} or {@code located}; not constructed directly.</li>
 * </ul>
 */
public final class CgDownload implements Closeable {

    /** Bytes that must pass before a report: a report every second or two on a slow link, under one a frame on a fast one. */
    static final int REPORT_EVERY_BYTES = 64 * 1024;

    private final String url;
    private final CgDownloads.Body body;
    private final InputStream stream;

    private CgDownload(String url, CgDownloads.Body body, InputStream stream) {
        this.url = url;
        this.body = body;
        this.stream = stream;
    }

    /** Opens {@code url}, announcing it as {@code what} on {@code progress}. */
    static CgDownload start(String url, String what, CgProgress progress,
                            BooleanSupplier cancelled) throws IOException {
        return start(url, what, progress, cancelled, 0L);
    }

    /** The same, continuing from {@code from} bytes if the server allows it; the bar counts from {@code from}. */
    static CgDownload start(String url, String what, CgProgress progress,
                            BooleanSupplier cancelled, long from) throws IOException {
        // Before the connect, indeterminate: nothing knows the size yet
        progress.begin(what, -1, CgProgress.Unit.BYTES);
        CgDownloads.Body body = CgDownloads.fetch(url, from);
        long already = body.resumed() ? from : 0L;
        if (body.length() > 0) {
            progress.begin(what, body.length(), CgProgress.Unit.BYTES);
            if (already > 0) progress.advance(already);
        }
        return new CgDownload(url, body, new Counting(body.stream(), progress, cancelled, already));
    }

    /** The URL that answered, which of a located transfer's locations this is. */
    public String url() {
        return url;
    }

    /** The bytes, counted and reported as they are read. */
    public InputStream stream() {
        return stream;
    }

    /** What the response said it was, or negative: a chunked reply has no length. */
    public long length() {
        return body.length();
    }

    /** Whether this continues a previous attempt, which decides append versus overwrite. */
    public boolean resumed() {
        return body.resumed();
    }

    @Override
    public void close() throws IOException {
        body.close();
    }

    /** Counts bytes past, absolute as {@link CgProgress#advance} is, and reports on a threshold. */
    private static final class Counting extends FilterInputStream {

        private final CgProgress progress;
        private final BooleanSupplier cancelled;
        private long read;
        private long reportedAt;

        Counting(InputStream in, CgProgress progress, BooleanSupplier cancelled,
                 long already) {
            super(in);
            this.progress = progress;
            this.cancelled = cancelled;
            // Counted from what a resumed transfer already holds, or the bar would run backwards
            this.read = already;
            this.reportedAt = already;
        }

        @Override
        public int read() throws IOException {
            int one = super.read();
            if (one >= 0) advance(1);
            return one;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = super.read(buffer, offset, length);
            if (count > 0) advance(count);
            return count;
        }

        private void advance(int count) throws IOException {
            read += count;
            if (read - reportedAt < REPORT_EVERY_BYTES) return;
            reportedAt = read;
            progress.advance(read);
            // Checked on the report threshold; an IOException, as a caller already reads that as "did not arrive"
            if (cancelled.getAsBoolean()) {
                throw new InterruptedIOException("download cancelled");
            }
        }
    }
}
