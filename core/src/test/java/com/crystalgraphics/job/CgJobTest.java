package com.crystalgraphics.job;

import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Jobs: where and in what order their results land, and how they end. */
public class CgJobTest {

    @After
    public void drainLeftovers() {
        CgJobLoop.endSession();
        CgJob.drain();
    }

    /** Drains this thread's jobs until {@code done}, for at most ten seconds. */
    private static void until(BooleanSupplier done) throws InterruptedException {
        long end = System.nanoTime() + 10_000_000_000L;
        while (!done.getAsBoolean()) {
            if (System.nanoTime() > end) throw new AssertionError("timed out");
            CgJob.drain();
            Thread.sleep(1);
        }
    }

    @Test
    public void thenRunsOnTheStartingThreadInStartOrder() throws InterruptedException {
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger worked = new AtomicInteger();
        List<Integer> order = new ArrayList<>();
        List<Thread> threads = new ArrayList<>();
        List<CgJob<Integer>> jobs = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            int n = i;
            jobs.add(CgJob.run(() -> {
                await(go);
                worked.incrementAndGet();
                return n;
            }).then(r -> {
                order.add(r);
                threads.add(Thread.currentThread());
            }));
        }
        CgJob.drain();
        assertTrue("nothing lands before its work is done", order.isEmpty());
        go.countDown();
        until(() -> worked.get() == 4);
        Thread.sleep(50);
        CgJob.drain();
        assertEquals(List.of(0, 1, 2, 3), order);
        for (Thread t : threads) assertTrue(t == Thread.currentThread());
        for (CgJob<Integer> j : jobs) assertTrue(j.isDone());
    }

    @Test
    public void aCancelledJobOrAnEndedSessionNeverLands() throws InterruptedException {
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger landed = new AtomicInteger(), worked = new AtomicInteger();
        CgJob<Void> cancelled = CgJob.run(() -> {
            await(go);
            worked.incrementAndGet();
        }).then(landed::incrementAndGet);
        CgJob<Void> ended = CgJob.run(() -> {
            await(go);
            worked.incrementAndGet();
        }).then(landed::incrementAndGet);
        cancelled.cancel();
        CgJobLoop.endSession();
        go.countDown();
        until(() -> worked.get() >= 1 && cancelled.isDone() && ended.isDone());
        Thread.sleep(50);
        CgJob.drain();
        assertEquals(0, landed.get());
    }

    @Test
    public void aRepeatingJobHasOneRunInFlightAndHandsItsStateRound() throws InterruptedException {
        AtomicInteger inFlight = new AtomicInteger(), most = new AtomicInteger();
        int[] state = {0};
        int[] reads = {0};
        CgJob.Repeating<Integer> job = CgJob.repeat(() -> {
            reads[0]++;
            return state[0];
        }).work(s -> {
            most.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            sleep(1);
            inFlight.decrementAndGet();
            return s + 1;
        }).then(next -> state[0] = next);
        until(() -> state[0] >= 20);
        job.cancel();
        CgJob.drain();
        assertEquals(1, most.get());
        assertTrue(reads[0] - state[0] <= 1);
        assertTrue(job.isDone());
    }

    @Test
    public void aFailureIsHeardAndEndsARepeatingJob() throws InterruptedException {
        AtomicReference<Throwable> heard = new AtomicReference<>();
        CgJob<Object> once = CgJob.run(() -> {
            throw new IllegalArgumentException("boom");
        }).failed(heard::set);
        until(once::isDone);
        assertEquals("boom", heard.get().getMessage());

        AtomicInteger runs = new AtomicInteger();
        CgJob.Repeating<Integer> repeating = CgJob.repeat(runs::incrementAndGet).work(n -> {
            if (n == 3) throw new IllegalStateException("third");
            return n;
        }).failed(heard::set);
        until(repeating::isDone);
        assertEquals("third", heard.get().getMessage());
        assertEquals(3, runs.get());
    }

    @Test
    public void aJobNeverStartsOnAWorker() throws InterruptedException {
        AtomicReference<Throwable> heard = new AtomicReference<>();
        CgJob<CgJob<Integer>> job = CgJob.run(() -> CgJob.run(() -> 1)).failed(heard::set);
        until(job::isDone);
        assertTrue(String.valueOf(heard.get()), heard.get() instanceof IllegalStateException);
        assertFalse(CgJob.isWorkerThread());
    }

    @Test
    public void anExecutorTaskRunsOnAWorker() throws InterruptedException {
        CountDownLatch ran = new CountDownLatch(1);
        AtomicReference<Boolean> onWorker = new AtomicReference<>();
        CgJob.executor(CgJobLane.BACKGROUND).execute(() -> {
            onWorker.set(CgJob.isWorkerThread());
            ran.countDown();
        });
        assertTrue(ran.await(10, TimeUnit.SECONDS));
        assertTrue(onWorker.get());
    }

    @Test
    public void forEachRunsEveryItemOnceAndNestsInline() {
        int[] once = new int[10_000];
        CgJob.forEach(once.length, i -> once[i]++);
        int[] expected = new int[once.length];
        Arrays.fill(expected, 1);
        assertArrayEquals(expected, once);

        long[] sums = new long[64];
        CgJob.forEach(sums.length, i -> {
            long[] inner = new long[100];
            CgJob.forEach(inner.length, j -> inner[j] = (long) i * j);
            for (long v : inner) sums[i] += v;
        });
        for (int i = 0; i < sums.length; i++) assertEquals(i * 4950L, sums[i]);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }
}
