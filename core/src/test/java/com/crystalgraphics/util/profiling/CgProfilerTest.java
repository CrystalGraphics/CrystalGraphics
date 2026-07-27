package com.crystalgraphics.util.profiling;

import org.junit.After;
import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link CgProfiler}/{@link CgProfilerReport} — disabled-is-free semantics,
 * nested scope path-building and self/total time accounting, counters, samples, the
 * missing-{@code pop()} safety net, and cross-thread {@link CgProfiler#reportAllThreads()}.
 */
public class CgProfilerTest {

    @After
    public void tearDown() {
        // Every test enables the profiler; leaving it on would let this test class's ordering
        // (or a future test file) accidentally observe live instrumentation cost/state.
        CgProfiler.reset();
        CgProfiler.setEnabled(false);
    }

    @Test
    public void disabled_scopeIsNoop_andRecordsNothing() {
        CgProfiler.setEnabled(false);

        try (CgProfiler.Scope ignored = CgProfiler.scope("outer")) {
            CgProfiler.count("hits");
            CgProfiler.sample("value", 42.0);
        }

        assertNull("report() must return null while disabled", CgProfiler.report());
    }

    @Test
    public void enabled_singleScope_recordsTotalAndSelfTime() throws InterruptedException {
        CgProfiler.setEnabled(true);

        try (CgProfiler.Scope ignored = CgProfiler.scope("work")) {
            Thread.sleep(5);
        }

        CgProfilerReport report = CgProfiler.report();
        assertNotNull(report);
        assertEquals(1, report.scopes().size());

        CgProfilerReport.ScopeEntry entry = report.scopes().get(0);
        assertEquals("work", entry.path());
        assertEquals("work", entry.name());
        assertEquals(0, entry.depth());
        assertEquals(1, entry.callCount());
        assertTrue("expected >= 5ms recorded, got " + entry.totalNanos() + "ns",
                entry.totalNanos() >= TimeUnit.MILLISECONDS.toNanos(5));
        // No children -- self time must equal total time exactly.
        assertEquals(entry.totalNanos(), entry.selfNanos());
    }

    @Test
    public void enabled_nestedScopes_buildSlashJoinedPaths_andSplitSelfFromTotal() {
        CgProfiler.setEnabled(true);

        try (CgProfiler.Scope outer = CgProfiler.scope("resolve")) {
            busySpin(TimeUnit.MILLISECONDS.toNanos(2));
            try (CgProfiler.Scope inner = CgProfiler.scope("flatten")) {
                busySpin(TimeUnit.MILLISECONDS.toNanos(2));
            }
        }

        CgProfilerReport report = CgProfiler.report();
        assertEquals(2, report.scopes().size());

        CgProfilerReport.ScopeEntry outerEntry = findScope(report, "resolve");
        CgProfilerReport.ScopeEntry innerEntry = findScope(report, "resolve/flatten");

        assertEquals(0, outerEntry.depth());
        assertEquals(1, innerEntry.depth());
        assertEquals("flatten", innerEntry.name());

        // Outer's total must cover both its own busy-spin AND the nested scope's time.
        assertTrue(outerEntry.totalNanos() > innerEntry.totalNanos());
        // Outer's self time excludes the child -- must be strictly less than its total.
        assertTrue(outerEntry.selfNanos() < outerEntry.totalNanos());
        assertEquals(outerEntry.totalNanos() - innerEntry.totalNanos(), outerEntry.selfNanos());
        // Leaf scope has no children -- self equals total.
        assertEquals(innerEntry.totalNanos(), innerEntry.selfNanos());
    }

    @Test
    public void enabled_sameNameAtDifferentNestingLevels_areDistinctPaths() {
        CgProfiler.setEnabled(true);

        try (CgProfiler.Scope a = CgProfiler.scope("glyph")) {
            try (CgProfiler.Scope b = CgProfiler.scope("glyph")) {
                CgProfiler.count("hit");
            }
        }

        CgProfilerReport report = CgProfiler.report();
        assertEquals(2, report.scopes().size());
        assertNotNull(findScope(report, "glyph"));
        assertNotNull(findScope(report, "glyph/glyph"));
    }

    @Test
    public void enabled_repeatedScopeCalls_accumulateAcrossCalls() {
        CgProfiler.setEnabled(true);

        for (int i = 0; i < 5; i++) {
            try (CgProfiler.Scope ignored = CgProfiler.scope("loop")) {
                busySpin(TimeUnit.MICROSECONDS.toNanos(200));
            }
        }

        CgProfilerReport.ScopeEntry entry = findScope(CgProfiler.report(), "loop");
        assertEquals(5, entry.callCount());
        assertTrue(entry.totalNanos() >= entry.maxNanos());
    }

    @Test
    public void counters_accumulateByDelta_andStartAtZero() {
        CgProfiler.setEnabled(true);

        CgProfiler.count("glyph.atlasHit");
        CgProfiler.count("glyph.atlasHit");
        CgProfiler.count("glyph.syncBitmapFallback", 3);

        Map<String, Long> counters = CgProfiler.report().counters();
        assertEquals(Long.valueOf(2), counters.get("glyph.atlasHit"));
        assertEquals(Long.valueOf(3), counters.get("glyph.syncBitmapFallback"));
        assertNull(counters.get("never.touched"));
    }

    @Test
    public void samples_trackCountSumMinMaxLastAndAverage() {
        CgProfiler.setEnabled(true);

        CgProfiler.sample("async.pendingGlyphs", 10);
        CgProfiler.sample("async.pendingGlyphs", 20);
        CgProfiler.sample("async.pendingGlyphs", 0);

        CgProfilerReport.SampleSummary summary = CgProfiler.report().samples().get("async.pendingGlyphs");
        assertNotNull(summary);
        assertEquals(3, summary.count());
        assertEquals(30.0, summary.sum(), 0.0);
        assertEquals(0.0, summary.min(), 0.0);
        assertEquals(20.0, summary.max(), 0.0);
        assertEquals(0.0, summary.last(), 0.0);
        assertEquals(10.0, summary.avg(), 0.0);
    }

    @Test
    public void endFrame_resetsState_soNextFrameStartsEmpty() {
        CgProfiler.setEnabled(true);

        try (CgProfiler.Scope ignored = CgProfiler.scope("work")) {
            CgProfiler.count("hits");
        }
        CgProfilerReport first = CgProfiler.endFrame();
        assertEquals(1, first.scopes().size());
        assertEquals(Long.valueOf(1), first.counters().get("hits"));

        CgProfilerReport second = CgProfiler.report();
        assertTrue("endFrame() must clear scopes for the next frame", second.scopes().isEmpty());
        assertTrue("endFrame() must clear counters for the next frame", second.counters().isEmpty());
    }

    @Test
    public void report_doesNotReset_repeatedCallsSeeSameData() {
        CgProfiler.setEnabled(true);

        try (CgProfiler.Scope ignored = CgProfiler.scope("work")) {
            CgProfiler.count("hits");
        }

        assertEquals(1, CgProfiler.report().scopes().size());
        assertEquals(1, CgProfiler.report().scopes().size());
    }

    @Test
    public void reset_clearsWithoutProducingReport() {
        CgProfiler.setEnabled(true);

        try (CgProfiler.Scope ignored = CgProfiler.scope("work")) {
            CgProfiler.count("hits");
        }
        CgProfiler.reset();

        CgProfilerReport report = CgProfiler.report();
        assertTrue(report.scopes().isEmpty());
        assertTrue(report.counters().isEmpty());
    }

    @Test
    public void endFrame_withUnclosedScope_throwsIllegalStateException() {
        CgProfiler.setEnabled(true);
        CgProfiler.push("leaked");

        try {
            CgProfiler.endFrame();
            fail("expected IllegalStateException for a scope missing its pop()");
        } catch (IllegalStateException expected) {
            // expected -- clean up manually so tearDown()'s reset()/setEnabled(false) don't
            // leave a genuinely leaked open scope on this JUnit-runner thread for later tests.
            CgProfiler.pop();
        }
    }

    @Test
    public void pop_withoutMatchingPush_throwsIllegalStateException() {
        CgProfiler.setEnabled(true);
        try {
            CgProfiler.pop();
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            // expected
        }
    }

    @Test
    public void report_midFrameWithOpenScope_doesNotThrow_andOmitsTheOpenScope() {
        CgProfiler.setEnabled(true);

        CgProfiler.push("stillOpen");
        try {
            CgProfilerReport report = CgProfiler.report();
            assertNotNull(report);
            assertTrue("an unpopped scope must not appear in a mid-frame report() snapshot",
                    report.scopes().isEmpty());
        } finally {
            CgProfiler.pop();
        }
    }

    @Test
    public void scope_exceptionInBody_stillPopsViaTryWithResources() {
        CgProfiler.setEnabled(true);

        try {
            try (CgProfiler.Scope ignored = CgProfiler.scope("risky")) {
                throw new RuntimeException("boom");
            }
        } catch (RuntimeException expected) {
            // expected -- the point is that close() still ran
        }

        // If close() hadn't run, the stack would be non-empty and endFrame() would throw.
        CgProfilerReport report = CgProfiler.endFrame();
        assertEquals(1, report.scopes().size());
    }

    @Test
    public void reportAllThreads_aggregatesMultipleThreads() throws InterruptedException {
        CgProfiler.setEnabled(true);
        CgProfiler.reset(); // clear this (main test) thread's state so its assertions are isolated

        final CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> workerError = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                try (CgProfiler.Scope ignored = CgProfiler.scope("worker.generate")) {
                    CgProfiler.count("worker.jobsCompleted");
                }
            } catch (Throwable t) {
                workerError.set(t);
            } finally {
                done.countDown();
            }
        }, "cgprofiler-test-worker");
        worker.setDaemon(true);
        worker.start();
        assertTrue("worker thread did not finish in time", done.await(5, TimeUnit.SECONDS));
        assertNull(workerError.get());

        Map<String, CgProfilerReport> all = CgProfiler.reportAllThreads();
        assertTrue("expected an entry for the worker thread", all.containsKey("cgprofiler-test-worker"));

        CgProfilerReport workerReport = all.get("cgprofiler-test-worker");
        assertNotNull(findScope(workerReport, "worker.generate"));
        assertEquals(Long.valueOf(1), workerReport.counters().get("worker.jobsCompleted"));
    }

    @Test
    public void format_producesNonEmptyReadableOutput() {
        CgProfiler.setEnabled(true);

        try (CgProfiler.Scope ignored = CgProfiler.scope("draw")) {
            CgProfiler.count("glyph.atlasHit", 4);
            CgProfiler.sample("async.pendingGlyphs", 12);
        }

        String text = CgProfiler.report().format();
        assertTrue(text.contains("draw"));
        assertTrue(text.contains("glyph.atlasHit"));
        assertTrue(text.contains("async.pendingGlyphs"));
    }

    // ── helpers ──

    private static CgProfilerReport.ScopeEntry findScope(CgProfilerReport report, String path) {
        List<CgProfilerReport.ScopeEntry> scopes = report.scopes();
        for (CgProfilerReport.ScopeEntry entry : scopes) {
            if (entry.path().equals(path)) return entry;
        }
        fail("no scope found for path '" + path + "' in " + scopes);
        return null; // unreachable
    }

    /** Busy-spins for at least {@code minNanos} -- {@code Thread.sleep} has too much scheduling
     * jitter/granularity on some platforms to reliably assert sub-millisecond ordering. */
    private static void busySpin(long minNanos) {
        long start = System.nanoTime();
        while (System.nanoTime() - start < minNanos) {
            // spin
        }
    }
}
