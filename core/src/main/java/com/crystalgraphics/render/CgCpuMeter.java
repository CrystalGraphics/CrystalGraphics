package com.crystalgraphics.render;

import java.util.concurrent.atomic.AtomicLong;

/**
 * One consumer's CPU time, summed from the compute passes charged to it as they execute and from what its own code
 * adds, and taken by whoever budgets it. The CPU's counterpart of {@link CgGpuBudget}'s charge, as Unreal's particle
 * perf stats sum every effect's cycles for its FX budget.
 *
 * <pre>{@code
 * static final CgCpuMeter SPARKS = new CgCpuMeter();
 *
 * recording.compute("sparks.step").charged(SPARKS);    // its execution, timed by the executor
 *
 * long began = System.nanoTime();
 * recordSparks(recording);
 * SPARKS.add(System.nanoTime() - began);                 // the consumer's own work, from any thread
 *
 * long nanos = SPARKS.take();                            // once a frame: what was added since, then zero
 * }</pre>
 *
 * <ul>
 *   <li>A pass's time lands when its frame executes, so a frame recorded in one host frame may be taken in the
 *       next.</li>
 *   <li>A pass is charged the CPU its execution takes, an async one's issue included, never its GPU time.</li>
 * </ul>
 */
public final class CgCpuMeter {

    private final AtomicLong nanos = new AtomicLong();

    /** Adds {@code nanos} of CPU. Any thread. */
    public void add(long nanos) {
        this.nanos.addAndGet(nanos);
    }

    /** The nanoseconds added since the last take, and zero after. Any thread. */
    public long take() {
        return nanos.getAndSet(0L);
    }
}
