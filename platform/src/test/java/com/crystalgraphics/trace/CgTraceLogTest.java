package com.crystalgraphics.trace;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * T4 — the run directory, and the promise that writing a line never touches a file on the calling
 * thread.
 *
 * @see CgTraceLog
 */
public class CgTraceLogTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Before
    public void quiet() {
        CgTraceLog.resetForTesting();
    }

    @After
    public void closed() {
        CgTraceLog.resetForTesting();
    }

    private Path root() {
        return folder.getRoot().toPath().resolve("trace");
    }

    private static List<String> linesOf(Path file) throws IOException {
        return Files.readAllLines(file, StandardCharsets.UTF_8);
    }

    @Test
    public void everyLineReachesTheFile() throws Exception {
        CgTraceLog.useRoot(root());
        assertNotNull(CgTraceLog.dir());

        for (int i = 0; i < 500; i++) CgTraceLog.line("[frame] " + i + "ms");
        CgTraceLog.stop();

        List<String> lines = linesOf(CgTraceLog.dir().resolve("trace.log"));
        // EVERY ONE, which is the gate: a run that reports every frame must not quietly lose some.
        assertEquals(500, lines.size());
        assertEquals("[frame] 0ms", lines.get(0));
        assertEquals("[frame] 499ms", lines.get(499));
        assertEquals(0L, CgTraceLog.dropped());
    }

    @Test
    public void writingALineDoesNotBlockTheCallerOnTheFilesystem() {
        CgTraceLog.useRoot(root());
        // A crude ceiling, and that is deliberate: the assertion is about ORDERS OF MAGNITUDE. Ten
        // thousand appends to a bounded queue is microseconds; ten thousand synchronous writes through
        // a log pipeline to a console appender is not, and that is what this replaced.
        long started = System.nanoTime();
        for (int i = 0; i < 10_000; i++) CgTraceLog.line("line " + i);
        long perLine = (System.nanoTime() - started) / 10_000L;
        assertTrue("a queued line cost " + perLine + "ns on the caller", perLine < 20_000L);
    }

    @Test
    public void aFullQueueDropsAndSaysSo() throws Exception {
        CgTraceLog.useRoot(root());
        // Far more than the queue holds, faster than a writer on MIN_PRIORITY can drain it.
        for (int i = 0; i < 200_000; i++) CgTraceLog.line("flood " + i);
        long dropped = CgTraceLog.dropped();
        CgTraceLog.stop();

        if (dropped == 0L) return;   // a fast enough machine kept up; nothing to assert about
        List<String> lines = linesOf(CgTraceLog.dir().resolve("trace.log"));
        String last = lines.get(lines.size() - 1);
        // A SILENTLY TRUNCATED LOG IS WORSE THAN AN HONESTLY SHORT ONE, so the count is in the file.
        assertTrue(last, last.contains("dropped"));
    }

    @Test
    public void theLiveRunIsAlwaysAtLatestAndTheOneBeforeIsRotatedAway() throws Exception {
        CgTraceLog.useRoot(root());
        String first = CgTraceLog.runId();
        CgTraceLog.line("first run");
        CgTraceLog.stop();
        assertTrue(Files.isDirectory(root().resolve(CgTraceLog.LATEST)));

        // A second run in the same process is what a restart looks like to the directory.
        CgTraceLog.resetForTesting();
        Thread.sleep(1100L);        // the run id is to the second
        CgTraceLog.useRoot(root());
        CgTraceLog.line("second run");
        CgTraceLog.stop();

        // THE READER'S PATH NEVER CHANGES: `latest` is the run that just finished, which is exactly
        // when somebody reads it. Renaming at shutdown would empty the one path they were given.
        assertEquals(List.of("second run"),
                linesOf(root().resolve(CgTraceLog.LATEST).resolve("trace.log")));
        assertTrue("the previous run was not rotated to its own id",
                Files.isDirectory(root().resolve(first)));
        assertEquals(List.of("first run"),
                linesOf(root().resolve(first).resolve("trace.log")));
    }

    @Test
    public void withoutARootEveryCallIsANoOp() {
        assertFalse(CgTraceLog.isOpen());
        assertFalse(CgTraceLog.line("nowhere"));
        CgTraceLog.write("meta.json", "{}");
        CgTraceLog.stop();
        // The point is that none of the above threw: a host that never gave a directory is the normal
        // case, and a diagnostic may not make it a failure.
    }
}
