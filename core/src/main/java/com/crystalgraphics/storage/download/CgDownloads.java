package com.crystalgraphics.storage.download;

import com.crystalgraphics.storage.cache.CgCacheFiles;
import com.crystalgraphics.job.progress.CgProgress;

import com.crystalgraphics.platform.service.CgCacheDirectory;

import java.io.Closeable;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLConnection;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Fetching over HTTP: describe a transfer, then open it or complete it.
 *
 * <pre>{@code
 * // An id a locations file lists: every URL it names, checked against the file's pin
 * CgDownloads.located(MY_LOCATIONS, "fabric/intermediary/1.20.1").named("Mappings").reporting(progress).into(target);
 *
 * // A stream to read through something else
 * try (CgDownload open = CgDownloads.located(MY_LOCATIONS, "jdk-sources/17").reporting(progress).open()) {
 *     read(open.stream());
 * }
 *
 * // An address known only at run time
 * CgDownloads.from(url).verifying("sha1:" + sha1).into(target);
 *
 * // Many files under one bar with one aggregate total
 * CgDownloads.batch(artifacts).named("Downloading engine").reporting(progress).into(dir);
 * }</pre>
 *
 * <ul>
 *   <li>Located, not addressed: a URL compiled into a class cannot be repaired when its host moves, so an id names
 *       the download and {@link CgDownloadLocations} says where it is. A located transfer tries each URL in order,
 *       retries a host only when it is the last, and re-reads master's locations once when all have failed.</li>
 *   <li>A {@code Request} is immutable and every wither returns a new one, so a half-described transfer can be
 *       finished two ways.</li>
 *   <li>Built on {@code URLConnection}: {@code java.net.http} does not exist on the Java 8 of older Minecraft.</li>
 *   <li>CrystalGraphics' own locations are {@link #LOCATIONS}; a consumer makes its own
 *       ({@link CgDownloadLocations#of(CgDownloadLocations.Source)}).</li>
 * </ul>
 */
public final class CgDownloads {

    /** Fifteen seconds each for connect and read. Long enough for a slow mirror, short enough to give up. */
    public static final int TIMEOUT_MILLIS = 15_000;

    /**
     * Attempts before a transfer fails. Worth having because of resume: each attempt continues from the kept
     * {@code .part}, which turns a flaky connection from fatal into slow.
     */
    public static final int ATTEMPTS = 3;

    /** Multiplied by the attempt number, so 1s then 2s. Long enough to outlast a blip, short enough to wait. */
    public static final long RETRY_BACKOFF_MILLIS = 1000L;

    /**
     * Whether a failure is worth retrying. An unknown host is not (offline, DNS answers the same every time);
     * a timeout, reset, truncated body or 5xx is.
     */
    private static boolean worthRetrying(IOException failure) {
        if (failure instanceof UnknownHostException) return false;
        // A 404 is an answer, and it will be the same answer next time.
        return !(failure instanceof FileNotFoundException);
    }

    /**
     * A failure in words a user can act on rather than a class name; the exception stays on stderr.
     */
    public static String describe(Throwable failure) {
        if (failure == null) return "unknown error";
        if (failure instanceof UnknownHostException) {
            return "could not reach " + failure.getMessage() + " — check your connection";
        }
        if (failure instanceof SocketTimeoutException) return "the connection timed out";
        if (failure instanceof FileNotFoundException) {
            return "the server does not have it any more";
        }
        if (failure instanceof InterruptedIOException) return "stopped";
        if (failure instanceof ConnectException) return "the connection was refused";
        String message = failure.getMessage();
        return message == null || message.isEmpty() ? failure.getClass().getSimpleName() : message;
    }

    private static void sleep(long millis) throws InterruptedIOException {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            // Restore the flag and abandon the wait, or a job asked to stop keeps going
            throw new InterruptedIOException("interrupted while waiting to retry");
        }
    }

    private CgDownloads() {
    }

    /** One artifact in a {@link Batch}: its file name and the transfer that fetches it. */
    public record Artifact(String fileName, Request request) {
    }

    // ── Describing one transfer ─────────────────────────────────────────────────────────────────

    /** A transfer of {@code url}, undescribed. Add what it is called and where to report it. */
    public static Request from(String url) {
        return new Request(Collections.singletonList(url), "Downloading", null, CgProgress.NONE,
                NEVER_CANCELLED, null, null);
    }

    /**
     * A transfer of the artifact {@code id} in CrystalGraphics' locations file: every URL it lists, checked against its pin.
     *
     * @throws FileNotFoundException when the file does not list {@code id}
     */
    public static Request located(String id) throws IOException {
        return located(LOCATIONS, id);
    }

    /** CrystalGraphics' own locations file. */
    public static final CgDownloadLocations LOCATIONS = CgDownloadLocations.of(new CgDownloadLocations.Source(
            "crystalgraphics", CgDownloadLocations.class, "/assets/crystalgraphics/download/locations.json",
            "crystalgraphics.download.remote", "crystalgraphics.download.locations",
            () -> CgCacheDirectory.of("downloads")));

    /** The same, against {@code locations} rather than CrystalGraphics' own. */
    public static Request located(CgDownloadLocations locations, String id) throws IOException {
        CgDownloadLocations.Location where = locations.find(id);
        if (where == null) throw new FileNotFoundException("download/locations.json lists no " + id);
        return new Request(where.urls(), "Downloading", where.digest(), CgProgress.NONE, NEVER_CANCELLED,
                locations, id);
    }

    /** The default: nobody can stop it. */
    private static final BooleanSupplier NEVER_CANCELLED = () -> false;

    /**
     * An immutable description of one transfer. Nothing touches the network until {@link #open} or {@link #into}.
     */
    public static final class Request {

        private final List<String> urls;
        private final String what;
        private final String digest;
        private final CgProgress progress;
        private final BooleanSupplier cancelled;
        /** Where {@link #urls} came from, so a transfer that runs out can ask again; null when addressed. */
        private final CgDownloadLocations locations;
        private final String id;

        private Request(List<String> urls, String what, String digest, CgProgress progress,
                        BooleanSupplier cancelled, CgDownloadLocations locations, String id) {
            this.urls = urls;
            this.what = what;
            this.digest = digest;
            this.progress = progress;
            this.cancelled = cancelled;
            this.locations = locations;
            this.id = id;
        }

        /** The line the chrome shows, present tense — {@code "Downloading engine band 17"}. */
        public Request named(String what) {
            return new Request(urls, what, digest, progress, cancelled, locations, id);
        }

        /** Where to report. Defaults to {@link CgProgress#NONE}, which is a real answer, not a stub. */
        public Request reporting(CgProgress progress) {
            return new Request(urls, what, digest, progress == null ? CgProgress.NONE : progress, cancelled,
                    locations, id);
        }

        /**
         * When to give up, polled as bytes pass. Cancellation is cooperative: a transfer that never asks cannot be stopped.
         */
        public Request cancelledWhen(BooleanSupplier cancelled) {
            return new Request(urls, what, digest, progress, cancelled == null ? NEVER_CANCELLED : cancelled,
                    locations, id);
        }

        /**
         * The expected digest, tagged with its algorithm ({@code sha1:…}, {@code gitblob:…}, or a bare MD5). Null means any
         * non-empty file will do, as for something nobody can pin.
         */
        public Request verifying(String digest) {
            return new Request(urls, what, digest, progress, cancelled, locations, id);
        }

        /** The digest this transfer is checked against, or null — and so what a cached copy must match. */
        public String digest() {
            return digest;
        }

        /** The first URL tried, or null — what a {@link Batch} sizes itself by. */
        String firstUrl() {
            return urls.isEmpty() ? null : urls.get(0);
        }

        /** Opens the first location that answers. The caller reads {@link CgDownload#stream()} and closes it. */
        public CgDownload open() throws IOException {
            List<String> tried = new ArrayList<>();
            IOException failure = null;
            for (List<String> round = firstRound(); !round.isEmpty(); round = nextRound(tried)) {
                for (String url : round) {
                    tried.add(url);
                    if (cancelled.getAsBoolean()) throw new InterruptedIOException("cancelled");
                    try {
                        return CgDownload.start(url, what, progress, cancelled);
                    } catch (InterruptedIOException stopped) {
                        throw stopped;
                    } catch (IOException failed) {
                        failure = failed;
                    }
                }
            }
            throw failure != null ? failure : new FileNotFoundException("nowhere to download " + what + " from");
        }

        /**
         * Fetches the whole thing into {@code target}, verified and installed atomically.
         *
         * @return whether the file is now valid there; false means every answering location served other bytes than the pin
         *         and nothing was installed, which is a cue to report, not retry
         */
        public boolean into(Path target) throws IOException {
            List<String> tried = new ArrayList<>();
            IOException failure = null;
            boolean mismatched = false;
            for (List<String> round = firstRound(); !round.isEmpty(); round = nextRound(tried)) {
                for (int at = 0; at < round.size(); at++) {
                    String url = round.get(at);
                    // An unpinned partial is no base for another host: nothing could tell the halves apart
                    if (digest == null && !tried.isEmpty()) Files.deleteIfExists(CgCacheFiles.partOf(target));
                    tried.add(url);
                    try {
                        if (attempt(url, target, at < round.size() - 1)) return true;
                        // Other bytes than the pin: a mirror serving something else; the next may not
                        mismatched = true;
                    } catch (InterruptedIOException stopped) {
                        throw stopped;
                    } catch (IOException failed) {
                        failure = failed;
                    }
                }
            }
            if (mismatched) return false;
            throw failure != null ? failure : new FileNotFoundException("nowhere to download " + what + " from");
        }

        /** One URL, retried only when it is the last one left. */
        private boolean attempt(String url, Path target, boolean anotherRemains) throws IOException {
            IOException last = null;
            for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
                if (cancelled.getAsBoolean()) throw new InterruptedIOException("cancelled");
                // What the last attempt left: the kept .part makes a retry a resume
                long have = CgCacheFiles.partialSize(target);
                try (CgDownload download = CgDownload.start(url, what, progress, cancelled, have)) {
                    return CgCacheFiles.install(target, download.stream(), digest, download.resumed());
                } catch (InterruptedIOException stopped) {
                    // Asked to stop is not a failure to retry; the .part stays so resuming costs nothing
                    throw stopped;
                } catch (IOException failed) {
                    last = failed;
                    // Another location is likelier to answer than this one is to recover.
                    if (attempt == ATTEMPTS || !worthRetrying(failed) || anotherRemains) break;
                    sleep(RETRY_BACKOFF_MILLIS * attempt);
                }
            }
            throw last;
        }

        /** Where to start: a located transfer first lets a day-old copy of the locations refresh. */
        private List<String> firstRound() {
            if (locations == null) return urls;
            locations.refreshIfStale();
            return untried(Collections.<String>emptyList());
        }

        /**
         * Once every URL has failed: whatever master's copy adds, asked even if another download fetched it.
         */
        private List<String> nextRound(List<String> tried) {
            if (locations == null) return Collections.emptyList();
            locations.refresh();
            return untried(tried);
        }

        private List<String> untried(List<String> tried) {
            CgDownloadLocations.Location where = locations.find(id);
            List<String> fresh = new ArrayList<>();
            for (String url : where == null ? urls : where.urls()) {
                if (!tried.contains(url)) fresh.add(url);
            }
            return fresh;
        }
    }

    // ── Many transfers, one bar ─────────────────────────────────────────────────────────────────

    /** Several artifacts into one directory, under a single aggregate bar. */
    public static Batch batch(List<Artifact> artifacts) {
        return new Batch(artifacts, "Downloading", CgProgress.NONE, NEVER_CANCELLED);
    }

    /**
     * A set of artifacts fetched as one reported unit: the sizes are summed first (a {@code HEAD} apiece) and one total
     * reported, with each name on the detail line. All or nothing: a failure abandons the batch and names the artifact;
     * what is installed stays, each atomic, so a retry resumes.
     */
    public static final class Batch {

        private final List<Artifact> artifacts;
        private final String what;
        private final CgProgress progress;
        private final BooleanSupplier cancelled;

        private Batch(List<Artifact> artifacts, String what, CgProgress progress, BooleanSupplier cancelled) {
            this.artifacts = artifacts;
            this.what = what;
            this.progress = progress;
            this.cancelled = cancelled;
        }

        public Batch named(String what) {
            return new Batch(artifacts, what, progress, cancelled);
        }

        public Batch reporting(CgProgress progress) {
            return new Batch(artifacts, what, progress == null ? CgProgress.NONE : progress, cancelled);
        }

        /** @see Request#cancelledWhen */
        public Batch cancelledWhen(BooleanSupplier cancelled) {
            return new Batch(artifacts, what, progress, cancelled == null ? NEVER_CANCELLED : cancelled);
        }

        /**
         * What a batch did.
         *
         * @param installed how many artifacts are now valid on disk
         * @param total     how many were asked for
         * @param failure   the first artifact that could not be fetched, or null; named, so a failure says where it stopped
         */
        public record Result(int installed, int total, String failure, boolean cancelled) {

            Result(int installed, int total, String failure) {
                this(installed, total, failure, false);
            }

            /** Asked to stop, which is not the same as unable to continue. */
            static Result cancelled(int installed, int total) {
                return new Result(installed, total, null, true);
            }

            public boolean complete() {
                return !cancelled && failure == null && installed == total;
            }

            @Override
            public String toString() {
                if (cancelled) return "cancelled after " + installed + " of " + total;
                return complete() ? installed + " of " + total
                        : installed + " of " + total + ", stopped at " + failure;
            }
        }

        /** Fetches every artifact into {@code directory}, stopping at the first that will not come. */
        public Result into(Path directory) {
            // Sizes first, so the bar has a total
            long total = 0;
            for (Artifact artifact : artifacts) {
                long length = lengthOf(artifact.request().firstUrl());
                // One unknown and the total is a guess: sweep instead, and a down host costs one timeout
                if (length <= 0) {
                    total = -1;
                    break;
                }
                total += length;
            }
            progress.begin(what, total > 0 ? total : -1, CgProgress.Unit.BYTES);

            long done = 0;
            int installed = 0;
            for (Artifact artifact : artifacts) {
                // Polled between artifacts too, or the next would start after a stop; cancelled is its own state, not a failure
                if (cancelled.getAsBoolean()) return Result.cancelled(installed, artifacts.size());
                progress.detail(artifact.fileName());
                Path target = directory.resolve(artifact.fileName());
                try {
                    if (!CgCacheFiles.isValid(target, artifact.request().digest())
                            && !artifact.request().named(what).reporting(CgProgress.NONE)
                                    .cancelledWhen(cancelled).into(target)) {
                        return new Result(installed, artifacts.size(), artifact.fileName());
                    }
                } catch (IOException | RuntimeException unavailable) {
                    return new Result(installed, artifacts.size(), artifact.fileName());
                }
                installed++;
                // Aggregate, stepped per file: an inner transfer reporting here would reset the bar each time
                try {
                    done += Files.size(target);
                } catch (IOException unreadable) {
                    // Installed but unmeasurable is not a failure of the batch; the bar just does not move.
                }
                progress.advance(done);
            }
            return new Result(installed, artifacts.size(), null);
        }
    }

    // ── The raw connection, which the shapes above are built on ─────────────────────────────────

    /**
     * Opens {@code url} for reading, following redirects (Maven Central and raw-content hosts use them), with a connect and
     * a read timeout so a stalled body cannot hang for ever.
     */
    public static InputStream open(String url) throws IOException {
        return fetch(url).stream();
    }

    /**
     * The stream and the length the same response declared, so no second request is needed.
     */
    public static Body fetch(String url) throws IOException {
        return fetch(url, 0L);
    }

    /**
     * The same, asking the server to continue from {@code from}. {@link Body#resumed()} says whether it agreed; a server
     * that ignores the range answers 200 with the whole body, and the caller starts the file again.
     */
    public static Body fetch(String url, long from) throws IOException {
        URLConnection connection = connect(url);
        if (from > 0) connection.setRequestProperty("Range", "bytes=" + from + "-");
        InputStream stream = connection.getInputStream();
        boolean resumed = from > 0 && connection instanceof HttpURLConnection
                && ((HttpURLConnection) connection).getResponseCode() == 206;
        long length = connection.getContentLengthLong();
        // The total, not the remainder: a 206 declares only the tail, which would size the bar wrong
        if (resumed && length >= 0) length += from;
        return new Body(stream, length, resumed);
    }

    /** An open response: what to read, and how much of it there is. */
    public static final class Body implements Closeable {

        private final InputStream stream;
        private final long length;
        private final boolean resumed;

        Body(InputStream stream, long length) {
            this(stream, length, false);
        }

        Body(InputStream stream, long length, boolean resumed) {
            this.stream = stream;
            this.length = length;
            this.resumed = resumed;
        }

        /** Whether the server honoured a range request and this is a continuation. */
        public boolean resumed() {
            return resumed;
        }

        public InputStream stream() {
            return stream;
        }

        /** Negative when the response declared none — a chunked reply, which is an ordinary answer. */
        public long length() {
            return length;
        }

        @Override
        public void close() throws IOException {
            stream.close();
        }
    }

    /**
     * The declared length of {@code url}'s body, or negative (a chunked reply has none; {@code -1} is what
     * {@link CgProgress#begin} takes as indeterminate). Prefer {@link #fetch} where the body is wanted too.
     */
    public static long lengthOf(String url) {
        if (url == null) return -1L;
        try {
            URLConnection connection = connect(url);
            if (connection instanceof HttpURLConnection http) {
                http.setRequestMethod("HEAD");
                long length = http.getContentLengthLong();
                http.disconnect();
                return length;
            }
            return connection.getContentLengthLong();
        } catch (IOException | RuntimeException unavailable) {
            // Not knowing the size is not a failure to download -- it is a sweep instead of a bar.
            return -1L;
        }
    }

    private static URLConnection connect(String url) throws IOException {
        URLConnection connection = new URL(url).openConnection();
        connection.setConnectTimeout(TIMEOUT_MILLIS);
        connection.setReadTimeout(TIMEOUT_MILLIS);
        if (connection instanceof HttpURLConnection http) {
            http.setInstanceFollowRedirects(true);
        }
        return connection;
    }
}
