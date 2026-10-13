package com.crystalgraphics.job;

import com.crystalgraphics.net.CgNetwork;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A thread's jobs not yet ended, in the order they were started; drained on that thread. Loaded with the first job,
 * which hooks Minecraft's server and client ticks.
 */
final class CgJobLoop {

    private static final ThreadLocal<CgJobLoop> MINE = ThreadLocal.withInitial(CgJobLoop::new);
    /** Bumped when this process leaves its world: a job from before is cancelled. */
    private static final AtomicInteger SESSION = new AtomicInteger();

    static {
        CgNetwork.onServerTick(CgJob::drain);
        CgNetwork.onClientTick(CgJob::drain);
        CgNetwork.onSessionEnd(SESSION::incrementAndGet);
    }

    private final ArrayList<CgJob<?>> pending = new ArrayList<>();
    private boolean draining;

    static CgJobLoop mine() {
        return MINE.get();
    }

    static int session() {
        return SESSION.get();
    }

    /** Ends the session as leaving a world does; for tests. */
    static void endSession() {
        SESSION.incrementAndGet();
    }

    void add(CgJob<?> job) {
        pending.add(job);
    }

    int size() {
        return pending.size();
    }

    /** Steps each job once; one started by a {@code then} waits for the next drain. Allocates nothing. */
    void drain() {
        if (draining || pending.isEmpty()) return;
        draining = true;
        int n = pending.size(), kept = 0;
        try {
            for (int i = 0; i < n; i++) {
                CgJob<?> job = pending.get(i);
                pending.set(i, null);
                if (job.step()) pending.set(kept++, job);
            }
        } finally {
            // jobs started meanwhile follow the kept ones; a throw mid-loop keeps the unstepped ones
            int end = pending.size();
            for (int i = 0; i < end; i++) {
                CgJob<?> job = pending.get(i);
                if (job != null && i >= kept) pending.set(kept++, job);
            }
            for (int i = end - 1; i >= kept; i--) pending.remove(i);
            draining = false;
        }
    }
}
