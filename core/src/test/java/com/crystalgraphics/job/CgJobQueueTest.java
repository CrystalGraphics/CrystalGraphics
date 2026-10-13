package com.crystalgraphics.job;

import org.junit.Test;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

/** Which queued job a free worker takes. */
public class CgJobQueueTest {

    private final long[] nanos = {0};

    private static final class Job extends CgJobQueue.Entry {
        Job(CgJobLane lane) {
            this.lane = lane;
        }

        @Override
        void runQueued() {
        }
    }

    @Test
    public void urgentLanesFirstThenFirstCome() {
        CgJobQueue queue = new CgJobQueue(4, () -> nanos[0]);
        Job background = new Job(CgJobLane.BACKGROUND), latency1 = new Job(CgJobLane.LATENCY),
                latency2 = new Job(CgJobLane.LATENCY), interactive = new Job(CgJobLane.INTERACTIVE);
        queue.add(background);
        queue.add(latency1);
        queue.add(latency2);
        queue.add(interactive);
        assertSame(interactive, queue.take());
        assertSame(latency1, queue.take());
        assertSame(latency2, queue.take());
        assertSame(background, queue.take());
        assertNull(queue.take());
    }

    @Test
    public void aStarvedJobGoesAheadOfAnUrgentOne() {
        CgJobQueue queue = new CgJobQueue(4, () -> nanos[0]);
        Job background = new Job(CgJobLane.BACKGROUND);
        queue.add(background);
        nanos[0] += CgJobQueue.STARVATION_GUARD_NANOS;
        Job interactive = new Job(CgJobLane.INTERACTIVE);
        queue.add(interactive);
        assertSame(background, queue.take());
        assertSame(interactive, queue.take());
    }

    @Test
    public void backgroundNeverTakesTheLastThread() {
        CgJobQueue queue = new CgJobQueue(3, () -> nanos[0]);
        Job b1 = new Job(CgJobLane.BACKGROUND), b2 = new Job(CgJobLane.BACKGROUND), b3 = new Job(CgJobLane.BACKGROUND);
        queue.add(b1);
        queue.add(b2);
        queue.add(b3);
        nanos[0] += CgJobQueue.STARVATION_GUARD_NANOS;   // starved or not
        assertSame(b1, queue.take());
        assertSame(b2, queue.take());
        assertNull("the third thread is kept for urgent work", queue.take());
        Job interactive = new Job(CgJobLane.INTERACTIVE);
        queue.add(interactive);
        assertSame(interactive, queue.take());
        queue.finished(b1);
        assertSame(b3, queue.take());
    }

    @Test
    public void oneThreadStillRunsBackgroundWork() {
        CgJobQueue queue = new CgJobQueue(1, () -> nanos[0]);
        Job background = new Job(CgJobLane.BACKGROUND);
        queue.add(background);
        assertSame(background, queue.take());
    }
}
