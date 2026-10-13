package com.crystalgraphics.job;

import com.crystalgraphics.job.progress.CgProgressState;
import com.crystalgraphics.job.reply.CgReply;
import com.crystalgraphics.job.reply.CgReplyError;
import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** A loop stepped by hand: work runs when the test says, debounces on a clock the test turns. */
public class CgJobLoopTest {

    private final ArrayDeque<Runnable> queued = new ArrayDeque<>();
    private final long[] now = {1_000};
    private final CgJobLoop loop = CgJobLoop.create("test", queued::add, () -> now[0]);
    private final Object doc = new Object();

    private void runQueued() {
        while (!queued.isEmpty()) queued.poll().run();
    }

    @Test
    public void aResultLandsAtTheDrainAndNotBefore() {
        List<String> landed = new ArrayList<>();
        CgJob<String> job = CgJob.on(loop).run(() -> "tree").then(landed::add);
        runQueued();
        assertTrue("nothing lands outside a drain", landed.isEmpty());
        loop.drain();
        assertEquals(List.of("tree"), landed);
        assertTrue(job.isDone());
        assertEquals(0, loop.pendingCount());
    }

    @Test
    public void startingAWaitingKeyAgainReplacesIt() {
        AtomicInteger ran = new AtomicInteger();
        List<Integer> landed = new ArrayList<>();
        CgJob<Integer> first = CgJob.on(loop).keyed(doc, "parse").debounce(100).run(ran::incrementAndGet).then(landed::add);
        CgJob.on(loop).keyed(doc, "parse").debounce(100).run(ran::incrementAndGet).then(landed::add);
        now[0] += 100;
        loop.drain();
        runQueued();
        loop.drain();
        assertEquals(1, ran.get());
        assertEquals(List.of(1), landed);
        assertFalse("a superseded job never settles", first.isDone());
    }

    @Test
    public void keysAreOwnerAndKindTogether() {
        Object other = new Object();
        List<String> landed = new ArrayList<>();
        CgJob.on(loop).keyed(doc, "parse").run(() -> "doc parse").then(landed::add);
        CgJob.on(loop).keyed(other, "parse").run(() -> "other parse").then(landed::add);
        CgJob.on(loop).keyed(doc, "lint").run(() -> "doc lint").then(landed::add);
        runQueued();
        loop.drain();
        assertEquals(List.of("doc parse", "other parse", "doc lint"), landed);
    }

    @Test
    public void aSupersededRunningJobIsAskedToStopAndItsSuccessorWaitsForIt() {
        AtomicBoolean firstSawCancel = new AtomicBoolean();
        List<String> landed = new ArrayList<>();
        Runnable[] firstWork = new Runnable[1];
        CgJob.on(loop).keyed(doc, "parse").run(ctx -> {
            firstWork[0].run();
            firstSawCancel.set(ctx.isCancelled());
            return "old";                                      // never polled: dropped all the same
        }).then(landed::add);
        Runnable first = queued.poll();
        // the first job's work is running when the second is started
        firstWork[0] = () -> {
            CgJob.on(loop).keyed(doc, "parse").run(() -> "new").then(landed::add);
            loop.drain();
            assertTrue("never two in flight for one key", queued.isEmpty());
        };
        first.run();
        assertTrue(firstSawCancel.get());
        loop.drain();                                         // the first is out of flight: the second is submitted
        runQueued();
        loop.drain();
        assertEquals(List.of("new"), landed);
    }

    @Test
    public void aDebounceWaitsAndAnotherStartPushesItOut() {
        AtomicInteger ran = new AtomicInteger();
        CgJob.on(loop).keyed(doc, "lint").debounce(300).run(ran::incrementAndGet);
        now[0] += 200;
        CgJob.on(loop).keyed(doc, "lint").debounce(300).run(ran::incrementAndGet);
        now[0] += 200;
        loop.drain();
        runQueued();
        assertEquals("300 ms after the last start, not the first", 0, ran.get());
        now[0] += 100;
        loop.drain();
        runQueued();
        assertEquals(1, ran.get());
    }

    @Test
    public void cancellingSettlesCancelledAndClosingAnOwnerCancelsItsJobs() {
        List<String> errors = new ArrayList<>();
        AtomicInteger ran = new AtomicInteger();
        CgJob.on(loop).keyed(doc, "parse").debounce(50).run(ran::incrementAndGet).onError(e -> errors.add(e.code()));
        loop.cancel(doc, "parse");
        CgJob.on(loop).keyed(doc, "lint").run(ran::incrementAndGet).onError(e -> errors.add(e.code()));
        CgJob.on(loop).keyed(doc, "index").run(ran::incrementAndGet).onError(e -> errors.add(e.code()));
        loop.cancelAll(doc);
        now[0] += 50;
        runQueued();
        loop.drain();
        assertEquals(0, ran.get());
        assertEquals(Arrays.asList(CgReplyError.CANCELLED, CgReplyError.CANCELLED, CgReplyError.CANCELLED), errors);
        assertEquals(0, loop.pendingCount());
    }

    @Test
    public void stoppingOnCancelIsNotAFailure() {
        List<CgReplyError> errors = new ArrayList<>();
        CgJob<?>[] job = new CgJob<?>[1];
        job[0] = CgJob.on(loop).run(ctx -> {
            job[0].cancel();                                  // while it runs
            ctx.throwIfCancelled();
            return null;
        }).onError(errors::add);
        runQueued();
        loop.drain();
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).is(CgReplyError.CANCELLED));
    }

    @Test
    public void aFailureReachesOnErrorAndAThrowingListenerSparesTheRest() {
        AtomicReference<Throwable> heard = new AtomicReference<>();
        List<String> landed = new ArrayList<>();
        CgJob.on(loop).run(() -> {
            throw new IllegalArgumentException("boom");
        }).failed(heard::set);
        CgJob.on(loop).run(() -> "a").then(r -> {
            throw new IllegalStateException("listener");
        });
        CgJob.on(loop).run(() -> "b").then(landed::add);
        runQueued();
        loop.drain();
        assertEquals("boom", heard.get().getMessage());
        assertEquals(List.of("b"), landed);
    }

    @Test
    public void closingCancelsEverythingAndRefusesMore() {
        List<String> errors = new ArrayList<>();
        CgJob.on(loop).run(() -> 1).onError(e -> errors.add(e.code()));
        loop.close();
        CgJob<Integer> late = CgJob.on(loop).run(() -> 2).onError(e -> errors.add(e.code()));
        runQueued();
        loop.drain();
        assertEquals(Arrays.asList(CgReplyError.CANCELLED, CgReplyError.CANCELLED), errors);
        assertTrue(late.isDone());
    }

    @Test
    public void jobsStartedInsideAScopeReportToTheEnteredLoop() {
        CgJobLoop own = CgJobLoop.current();
        CgJob<Integer> job;
        try (CgJobLoop.Scope ignored = loop.enter()) {
            assertSame(loop, CgJobLoop.current());
            job = CgJob.run(() -> 7);
        }
        assertSame(own, CgJobLoop.current());
        runQueued();
        own.drain();
        assertFalse("the thread's own loop is not where it reports", job.isDone());
        loop.drain();
        assertEquals(Integer.valueOf(7), job.result());
    }

    @Test
    public void progressIsReadableWhileTheWorkRuns() {
        AtomicReference<CgProgressState> seen = new AtomicReference<>();
        CgJob<?>[] job = new CgJob<?>[1];
        job[0] = CgJob.on(loop).run(ctx -> {
            ctx.progress().begin("Indexing", 4);
            ctx.progress().advance(3);
            seen.set(job[0].progress());
            return null;
        });
        assertNull(job[0].progress());
        runQueued();
        assertEquals(0.75f, seen.get().fraction(), 0f);
        assertEquals("Indexing", seen.get().what());
    }

    @Test
    public void jobsComposeAsReplies() {
        AtomicReference<CgReply.Both<Integer, String>> both = new AtomicReference<>();
        CgJob<Integer> a = CgJob.on(loop).run(() -> 2);
        CgJob<String> b = CgJob.on(loop).run(() -> "x");
        CgReply.both(a, b).then(both::set);
        CgReply<Integer> doubled = a.map(n -> n * 2);
        runQueued();
        loop.drain();
        assertEquals(Integer.valueOf(2), both.get().first());
        assertEquals("x", both.get().second());
        assertEquals(Integer.valueOf(4), doubled.result());

        AtomicBoolean stopped = new AtomicBoolean();
        CgJob<Object> slow = CgJob.on(loop).run(ctx -> {
            stopped.set(ctx.isCancelled());
            return null;
        });
        slow.map(o -> o).cancel();                            // cancelling the view cancels the job
        runQueued();
        loop.drain();
        assertTrue(slow.error().is(CgReplyError.CANCELLED));
        assertFalse("cancelled before it ran: its work never starts", stopped.get());
    }
}
