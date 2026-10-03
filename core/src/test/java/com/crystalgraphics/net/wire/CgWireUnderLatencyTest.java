package com.crystalgraphics.net.wire;


import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Phase 5 <b>5.9</b> — the wire when credit does not come back instantly.
 *
 * <p>Every other measurement of this layer pumps both ends in a tight loop, so a
 * {@code WINDOW_UPDATE} is available the instant it is sent and no transfer ever waits. That is the one
 * condition production never has: {@code pump()} runs once per <b>game tick</b> on each end, so the floor
 * on a round trip is two ticks — 100 ms at 1.7.10's 20 tps — before a single byte of network latency.</p>
 *
 * <h3>What this deliberately does not simulate</h3>
 *
 * <p><b>No loss and no reordering.</b> {@code plan/fs-remote-workspace.md} listed loss among the untested conditions;
 * it is not a hazard here. Every supported platform's channel rides the loader's own networking —
 * {@code SimpleNetworkWrapper}, {@code SimpleChannel}, Fabric — which is Minecraft's Netty pipeline,
 * which is TCP. A frame that is sent is delivered, in order. Simulating loss would test something that
 * cannot happen and would invite retransmission logic whose only effect would be to duplicate TCP.</p>
 *
 * <h3>What it found</h3>
 *
 * <p>{@code flush} made a <b>single</b> round-robin pass per call, so the connection was capped at one
 * frame per message per tick regardless of credit. A lone 1 MB message took <b>34 ticks — 1.7 seconds at
 * zero latency</b> — with seven-eighths of the granted window unspent, which also made
 * {@link CgFrameMultiplexer#DEFAULT_WINDOW_BYTES}' own justification ("enough to keep the pipe busy")
 * false. Repeating the pass while credit lasts took the same transfer to <b>8 ticks</b>, and moved the
 * limit to the credit window, which is where it belongs and is tunable.</p>
 */
public class CgWireUnderLatencyTest {

    private static final int MC_CLIENT_FRAME = 32_766;

    /** 1.7.10 runs at 20 tps, so one tick is 50 ms. Used only to make the assertions readable. */
    private static final int TICKS_PER_SECOND = 20;

    /**
     * A link with a one-way delay measured in <b>ticks</b>, which is the real granularity.
     *
     * <p>A frame sent during tick {@code n} is visible to the peer's pump at tick {@code n + oneWay}, so
     * {@code oneWay = 0} still costs a full round trip of two ticks — the peer must pump to see it and
     * pump again to answer.</p>
     */
    private static final class DelayedLink {
        final CgFrameMultiplexer a;
        final CgFrameMultiplexer b;
        final List<byte[]> received = new ArrayList<>();

        private final Deque<Long> dueAtA = new ArrayDeque<>();
        private final Deque<byte[]> framesToA = new ArrayDeque<>();
        private final Deque<Long> dueAtB = new ArrayDeque<>();
        private final Deque<byte[]> framesToB = new ArrayDeque<>();

        long tick;

        DelayedLink(int oneWayTicks) {
            CgFrameMultiplexer[] slot = new CgFrameMultiplexer[2];
            slot[0] = new CgFrameMultiplexer(MC_CLIENT_FRAME, true, frame -> {
                framesToB.addLast(frame);
                dueAtB.addLast(tick + oneWayTicks);
            });
            slot[1] = new CgFrameMultiplexer(MC_CLIENT_FRAME, false, frame -> {
                framesToA.addLast(frame);
                dueAtA.addLast(tick + oneWayTicks);
            });
            a = slot[0];
            b = slot[1];
            b.setMessageHandler(received::add);
        }

        void step() {
            while (!dueAtB.isEmpty() && dueAtB.peekFirst() <= tick) {
                dueAtB.pollFirst();
                b.onFrameReceived(framesToB.pollFirst());
            }
            while (!dueAtA.isEmpty() && dueAtA.peekFirst() <= tick) {
                dueAtA.pollFirst();
                a.onFrameReceived(framesToA.pollFirst());
            }
            a.pump();
            b.pump();
            tick++;
        }

        /** Ticks taken to deliver {@code expected} messages. Fails rather than returning a sentinel. */
        long ticksToDeliver(int expected, int budget) {
            for (int i = 0; i < budget; i++) {
                step();
                if (received.size() >= expected) return tick;
            }
            fail("only " + received.size() + " of " + expected + " arrived in " + budget + " ticks");
            return -1;
        }
    }

    private static byte[] bytes(int length, int seed) {
        byte[] value = new byte[length];
        for (int i = 0; i < length; i++) value[i] = (byte) (i * 31 + seed);
        return value;
    }

    // ── Throughput ──────────────────────────────────────────────────────────────────────────────

    /**
     * A 1 MB transfer finishes in well under a second on a link with no latency.
     *
     * <p>Took <b>34 ticks</b> before {@code flush} repeated its pass — one frame per tick, 32 frames,
     * with 224 KB of granted credit unspent throughout. The bound is deliberately generous (a whole
     * second) so this stays a test of "the wire is not accidentally serialised" rather than a benchmark
     * that fails on a slow CI box; the defect it guards was <b>4× over</b> this line, not near it.</p>
     */
    @Test
    public void aMegabyteDoesNotTakeASecondOnAFreeLink() {
        DelayedLink link = new DelayedLink(0);
        link.a.send(bytes(1024 * 1024, 1));

        long ticks = link.ticksToDeliver(1, 4000);

        assertTrue("1 MB took " + ticks + " ticks (" + (ticks / (double) TICKS_PER_SECOND) + "s)",
                ticks < TICKS_PER_SECOND);
    }

    /**
     * More than one frame goes out per pump when credit allows.
     *
     * <p>The mechanism, asserted directly rather than inferred from a stopwatch: with a full window and a
     * message far larger than one frame, a single {@code flush} must spend the credit it was granted.
     * A timing assertion alone would pass again the day somebody re-serialises the loop on a faster
     * machine.</p>
     */
    @Test
    public void oneFlushSpendsTheCreditItWasGranted() {
        int[] frames = {0};
        CgFrameMultiplexer solo = new CgFrameMultiplexer(MC_CLIENT_FRAME, true, frame -> frames[0]++);
        solo.send(bytes(1024 * 1024, 1));

        solo.flush();

        assertTrue("one flush emitted " + frames[0] + " frame(s); the window is "
                        + CgFrameMultiplexer.DEFAULT_WINDOW_BYTES + " bytes",
                frames[0] > 1);
        assertEquals("and it must stop at the window, not run past it",
                0L, solo.sendCredit());
    }

    // ── Latency ─────────────────────────────────────────────────────────────────────────────────

    /**
     * Latency slows a transfer down; it must not stop one.
     *
     * <p>Eight ticks each way is a 800 ms round trip — far worse than any playable server — and the
     * transfer still completes. The assertion is completion, not a duration: what is being guarded
     * against is a <b>stall</b>, where credit and admission wait on each other and neither moves.</p>
     */
    @Test
    public void aTransferCompletesUnderHeavyLatency() {
        DelayedLink link = new DelayedLink(8);
        link.a.send(bytes(1024 * 1024, 1));

        link.ticksToDeliver(1, 4000);

        assertEquals(1024 * 1024, link.received.get(0).length);
    }

    /**
     * <b>Cold credit plus a burst</b> — the shape a reconnect makes.
     *
     * <p>A connection that has been quiet, then everything at once: a capability refresh, listings
     * re-requested, a document re-read. The risk is that admission holds every message back waiting for
     * one in flight to finish, while that one waits on credit a round trip away — each reasonable alone
     * and jointly a deadlock. It does not deadlock, and this is what says so.</p>
     */
    @Test
    public void aBurstAfterIdleDoesNotStall() {
        DelayedLink link = new DelayedLink(4);

        // Quiet first, so credit and the acknowledgement counter are wherever an idle link leaves them.
        link.a.send(bytes(512, 1));
        link.ticksToDeliver(1, 200);
        for (int i = 0; i < 100; i++) link.step();

        for (int i = 0; i < 20; i++) link.a.send(bytes(64 * 1024, i));
        link.ticksToDeliver(21, 4000);

        assertEquals("every message in the burst must arrive", 21, link.received.size());
    }

    /**
     * An idle connection keeps its window.
     *
     * <p>{@code replenish} only fires past half the window, so the tail of a quiet exchange goes
     * unacknowledged — which is correct batching and would be a slow leak if it accumulated. It does not:
     * the counter is per connection rather than per message, so the next traffic carries it over the
     * threshold. Pinned because "the wire goes quiet after N small messages" is the failure this would
     * produce, and it would take a very long session to notice.</p>
     */
    @Test
    public void anIdleConnectionDoesNotLoseItsWindow() {
        DelayedLink link = new DelayedLink(2);
        for (int i = 0; i < 40; i++) {
            link.a.send(bytes(4096, i));
            link.ticksToDeliver(i + 1, 200);
        }
        for (int i = 0; i < 100; i++) link.step();

        long credit = link.a.sendCredit();
        assertTrue("after 40 small messages the sender holds " + credit + " of "
                        + CgFrameMultiplexer.DEFAULT_WINDOW_BYTES,
                credit > CgFrameMultiplexer.DEFAULT_WINDOW_BYTES / 2);
    }
}
