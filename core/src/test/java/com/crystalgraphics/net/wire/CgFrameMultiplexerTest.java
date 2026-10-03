package com.crystalgraphics.net.wire;

import com.crystalgraphics.net.CgTransport;
import com.crystalgraphics.serialization.CgCodecException;
import com.crystalgraphics.serialization.CgPlainOps;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The wire engine, headless — no Minecraft, no GL, which is the whole point of putting it in {@code core}.
 *
 * <p>Two connections wired to each other's {@code onFrameReceived} stand in for a network. That is not a
 * simplification of the real thing: the platform's contribution is exactly "hand this {@code byte[]} to
 * the other side", so a direct hand-off exercises everything above that seam.</p>
 */
public class CgFrameMultiplexerTest {

    /** ~32 KB is the real client→server ceiling on every Minecraft version we target. */
    private static final int MC_CLIENT_FRAME = 32_766;

    /**
     * Every per-frame ceiling any supported platform actually imposes, plus two that no platform does.
     *
     * <p><b>This list is the whole cross-version surface.</b> Nothing else about a Minecraft version
     * reaches {@code core}: a channel name is a string the adapter owns, a player is an opaque handle,
     * and the payload is a {@code byte[]} either way. The single number a platform contributes is its
     * frame ceiling — so "does the engine work on 1.7.10, on 1.20.x, and on a version nobody has written
     * an adapter for" is exactly the question of whether it is correct for an arbitrary value here.</p>
     *
     * <p>The two synthetic entries are the ones that catch an assumption. 128 is far below anything real
     * and makes the header a visible fraction of every frame — a fragmenter that reserved a
     * <em>typical</em> header rather than the worst-case one passes at 32 KB and overflows here. 4 MB is
     * above every real ceiling and makes most messages single-frame, which is where a reassembler that
     * only works via the fragment path fails.</p>
     */
    private static final int[] EVERY_PLATFORM_CEILING = {
            128,            // synthetic: smaller than any platform, header-dominated
            32_766,         // 1.7.10 client -> server (vanilla signed short, minus Forge's discriminator)
            32_767,         // 1.20.x client -> server (vanilla signed short)
            1_048_576,      // 1.20.x server -> client
            2_097_050,      // 1.7.10 server -> client (Forge widens it with readVarShort)
            4_194_304,      // synthetic: above every platform, most messages single-frame
    };

    /** A pair of connections, each sending into the other's inbox. */
    private static final class Pair {
        final CgFrameMultiplexer a;
        final CgFrameMultiplexer b;
        final List<byte[]> aReceived = new ArrayList<>();
        final List<byte[]> bReceived = new ArrayList<>();
        int aFrames;
        int bFrames;

        Pair(int frameBytes) {
            CgFrameMultiplexer[] slot = new CgFrameMultiplexer[2];
            slot[0] = new CgFrameMultiplexer(frameBytes, true, frame -> {
                aFrames++;
                slot[1].onFrameReceived(frame);
            });
            slot[1] = new CgFrameMultiplexer(frameBytes, false, frame -> {
                bFrames++;
                slot[0].onFrameReceived(frame);
            });
            a = slot[0];
            b = slot[1];
            a.setMessageHandler(aReceived::add);
            b.setMessageHandler(bReceived::add);
        }

        /** Pumps both until neither has anything left — a settled exchange. */
        void settle() {
            for (int i = 0; i < 2_000; i++) {
                int moved = a.pump() + b.pump();
                if (moved == 0 && a.pendingOutboundMessages() == 0 && b.pendingOutboundMessages() == 0) {
                    // One more each, so a trailing WINDOW_UPDATE is delivered.
                    a.pump();
                    b.pump();
                    return;
                }
            }
            fail("the exchange never settled");
        }
    }

    private static byte[] bytes(int length, int seed) {
        byte[] value = new byte[length];
        for (int i = 0; i < length; i++) value[i] = (byte) (i * 31 + seed);
        return value;
    }

    @Test
    public void aSmallMessageCrossesInOneFrame() {
        Pair pair = new Pair(MC_CLIENT_FRAME);
        pair.a.send(bytes(100, 1));
        pair.settle();

        assertEquals(1, pair.bReceived.size());
        assertArrayEquals(bytes(100, 1), pair.bReceived.get(0));
        assertEquals("one DATA frame and nothing else", 1, pair.aFrames);
    }

    @Test
    public void aMessageLargerThanTheFrameCeilingIsFragmentedAndReassembled() {
        Pair pair = new Pair(MC_CLIENT_FRAME);
        byte[] big = bytes(200_000, 7);
        pair.a.send(big);
        pair.settle();

        assertEquals(1, pair.bReceived.size());
        assertArrayEquals(big, pair.bReceived.get(0));
        assertTrue("must have taken several frames, took " + pair.aFrames, pair.aFrames >= 7);
    }

    @Test
    public void noFrameExceedsThePlatformCeiling() {
        // The failure this prevents is not ours: an oversized payload throws from inside the loader,
        // mid-send, with the connection already committed.
        List<Integer> sizes = new ArrayList<>();
        CgFrameMultiplexer[] slot = new CgFrameMultiplexer[1];
        slot[0] = new CgFrameMultiplexer(MC_CLIENT_FRAME, true, frame -> sizes.add(frame.length));
        slot[0].send(bytes(500_000, 3));
        slot[0].flush();

        assertTrue("emitted nothing", !sizes.isEmpty());
        for (int size : sizes) {
            assertTrue("frame of " + size + " exceeds " + MC_CLIENT_FRAME, size <= MC_CLIENT_FRAME);
        }
    }

    /**
     * The reason stream multiplexing is in here at all.
     *
     * <p>A large transfer and a small one queued behind it must both make progress. If the small one only
     * completes after the large one, opening a big file freezes every RPC and UI event behind it — which
     * reads as the editor hanging, not as a transport decision.</p>
     */
    /**
     * The engine is correct at every ceiling a platform imposes, and at two that none does.
     *
     * <p>Runs the same exchange at each: a message far below the ceiling, one straddling it, and one
     * several fragments long, all in flight together. Asserts the bytes arrive intact <em>and</em> that
     * nothing was emitted larger than the platform said it would carry — the second is the half that
     * cannot be checked in game without a crash, because an oversized payload throws from inside the
     * loader with the connection already committed.</p>
     */
    @Test
    public void everyPlatformCeilingCarriesTheSameMessagesIntact() {
        for (int ceiling : EVERY_PLATFORM_CEILING) {
            List<Integer> emitted = new ArrayList<>();
            CgFrameMultiplexer[] slot = new CgFrameMultiplexer[2];
            List<byte[]> arrived = new ArrayList<>();
            slot[0] = new CgFrameMultiplexer(ceiling, true, frame -> {
                emitted.add(frame.length);
                slot[1].onFrameReceived(frame);
            });
            slot[1] = new CgFrameMultiplexer(ceiling, false, frame -> {
                emitted.add(frame.length);
                slot[0].onFrameReceived(frame);
            });
            slot[1].setMessageHandler(arrived::add);
            slot[0].setMessageHandler(frame -> { });

            // Below the ceiling, straddling it, and several fragments past it -- all three in flight at
            // once, so their SUM must stay inside MAX_REASSEMBLY_BYTES. That bound is connection-wide
            // rather than per-stream (it exists to cap total memory held in partial inbound messages, and
            // a per-stream cap would be trivially defeated by opening more streams), and it does not rise
            // with the platform ceiling -- so at a 4 MB ceiling the straddling message alone is half the
            // budget. Sized off the constant rather than a literal, so it stays true if the bound moves.
            byte[] small = bytes(Math.max(1, ceiling / 8), 1);
            byte[] straddling = bytes(ceiling + 1, 2);
            byte[] large = bytes(Math.min(ceiling * 3, CgFrameMultiplexer.MAX_REASSEMBLY_BYTES / 4), 3);
            slot[0].send(small);
            slot[0].send(straddling);
            slot[0].send(large);

            for (int i = 0; i < 20_000 && arrived.size() < 3; i++) {
                slot[0].pump();
                slot[1].pump();
            }

            String at = " at ceiling " + ceiling;
            assertEquals("all three messages must arrive" + at, 3, arrived.size());
            // Matched by content, NOT by arrival index: the multiplexer round-robins across streams on
            // purpose, so a small message overtakes a large one already in flight -- which is the whole
            // point of aSmallMessageIsNotBlockedBehindALargeOne. Ordering is guaranteed WITHIN a stream
            // and deliberately not ACROSS them, and an index-based assertion here passes at four of the
            // six ceilings and fails at the two where the fragment counts happen to interleave.
            assertArrived("small" + at, small, arrived);
            assertArrived("straddling" + at, straddling, arrived);
            assertArrived("large" + at, large, arrived);
            for (int size : emitted) {
                assertTrue("a frame of " + size + " exceeds what the platform reported" + at,
                        size <= ceiling);
            }
        }
    }

    /** Asserts one message arrived intact, wherever in the batch it landed. */
    private static void assertArrived(String what, byte[] expected, List<byte[]> arrived) {
        for (byte[] candidate : arrived) {
            if (candidate.length == expected.length) {
                assertArrayEquals(what + " intact", expected, candidate);
                return;
            }
        }
        fail(what + " never arrived (" + expected.length + " bytes)");
    }

    /**
     * A ceiling too small to hold a header is refused at construction, not at the first send.
     *
     * <p>The failure it prevents is the one that costs a connection: a fragmenter told it may use a
     * negative number of payload bytes emits nothing, forever, with no error. Refusing the ceiling names
     * the adapter that reported it while there is still a stack trace pointing at the registration.</p>
     */
    @Test
    public void aCeilingTooSmallForAHeaderIsRefused() {
        try {
            new CgFrameMultiplexer(8, true, frame -> { });
            fail("a ceiling of 8 bytes cannot carry a header and must be refused");
        } catch (IllegalArgumentException expected) {
            // the point
        }
    }

    /**
     * The small message overtakes the large one it was queued behind.
     *
     * <p><b>Rewritten when flush stopped emitting one frame per message per pump.</b> The original
     * pumped four times and asserted the receiver held exactly ONE message — using "the large one has
     * not finished yet" as a proxy for "we are still early". That proxy stopped holding the moment the
     * connection got four times faster, and the test failed against a version where the property it
     * names was perfectly intact. The property is <em>ordering</em>, not timing.</p>
     *
     * <p>So it now asserts what it is called: the small message arrives, and arrives <b>first</b>. The
     * large one is sized from {@link CgFrameMultiplexer#DEFAULT_WINDOW_BYTES} rather than a literal, so
     * raising the window cannot quietly turn this back into a test of how fast the wire happens to be.</p>
     */
    @Test
    public void aSmallMessageIsNotBlockedBehindALargeOne() {
        Pair pair = new Pair(4096);
        // Four windows' worth, so no single credit grant can carry it whatever the window is set to.
        byte[] large = bytes(CgFrameMultiplexer.DEFAULT_WINDOW_BYTES * 4, 1);
        byte[] small = bytes(10, 2);

        pair.a.send(large);
        pair.a.send(small);        // one frame, queued behind many

        pair.a.pump();
        pair.b.pump();

        assertEquals("the small message must not wait for the large one", 1, pair.bReceived.size());
        assertArrayEquals("and it must be the small one", small, pair.bReceived.get(0));

        pair.settle();
        assertEquals("both arrive in the end", 2, pair.bReceived.size());
        assertArrayEquals("the large one last, intact", large, pair.bReceived.get(1));
    }

    @Test
    public void manyMessagesAllArriveIntactAndInterleaved() {
        Pair pair = new Pair(1024);
        for (int i = 0; i < 20; i++) pair.a.send(bytes(3000 + i, i));
        pair.settle();

        assertEquals(20, pair.bReceived.size());
        // Every message intact, regardless of the order they completed in.
        List<byte[]> got = pair.bReceived;
        for (int i = 0; i < 20; i++) {
            byte[] expected = bytes(3000 + i, i);
            boolean found = false;
            for (byte[] candidate : got) {
                if (Arrays.equals(expected, candidate)) { found = true; break; }
            }
            assertTrue("message " + i + " did not arrive intact", found);
        }
    }

    @Test
    public void bothDirectionsWorkAtOnce() {
        Pair pair = new Pair(2048);
        pair.a.send(bytes(50_000, 1));
        pair.b.send(bytes(50_000, 2));
        pair.settle();

        assertEquals(1, pair.bReceived.size());
        assertEquals(1, pair.aReceived.size());
        assertArrayEquals(bytes(50_000, 1), pair.bReceived.get(0));
        assertArrayEquals(bytes(50_000, 2), pair.aReceived.get(0));
    }

    /**
     * Stream ids must never collide, which is why one side is odd and the other even.
     *
     * <p>A collision would splice two unrelated messages into one buffer — and the result would still
     * decode as *something*, which is the worst available failure.</p>
     */
    @Test
    public void theTwoSidesNeverAllocateTheSameStreamId() {
        Pair pair = new Pair(64_000);
        for (int i = 0; i < 50; i++) {
            pair.a.send(bytes(8, i));
            pair.b.send(bytes(8, i));
        }
        pair.settle();
        assertEquals(50, pair.aReceived.size());
        assertEquals(50, pair.bReceived.size());
    }

    // ── Flow control ────────────────────────────────────────────────────────

    /**
     * The measure that protects Minecraft's unbounded {@code outboundPacketsQueue}.
     *
     * <p>A sender with no credit must stop, not keep encoding into a queue nobody is draining. Without
     * this a large transfer balloons heap and head-of-line blocks the whole game connection.</p>
     */
    @Test
    public void aSenderStopsWhenItRunsOutOfCredit() {
        AtomicInteger emitted = new AtomicInteger();
        // A sink that goes nowhere: the peer never pumps, so it never grants more credit.
        CgFrameMultiplexer sender = new CgFrameMultiplexer(4096, true, frame -> emitted.incrementAndGet());

        sender.send(bytes(4 * 1024 * 1024, 9));   // 4 MB, far past the initial window
        for (int i = 0; i < 100; i++) sender.flush();

        long sentBytes = (long) emitted.get() * 4096;
        assertTrue("sent " + sentBytes + " bytes with no acknowledgement — the window did not hold",
                sentBytes <= CgFrameMultiplexer.DEFAULT_WINDOW_BYTES + 4096);
        assertEquals("credit should be spent", 0, Math.max(0, sender.sendCredit()));
        assertTrue("the rest must still be queued", sender.pendingOutboundMessages() > 0);
    }

    @Test
    public void creditIsReplenishedSoALargeTransferStillCompletes() {
        Pair pair = new Pair(MC_CLIENT_FRAME);
        byte[] huge = bytes(3 * 1024 * 1024, 4);   // well past several windows
        pair.a.send(huge);
        pair.settle();

        assertEquals(1, pair.bReceived.size());
        assertArrayEquals(huge, pair.bReceived.get(0));
    }

    // ── Refusals ────────────────────────────────────────────────────────────

    @Test(expected = CgCodecException.class)
    public void anUnknownOpcodeIsRefused() {
        CgFrameMultiplexer connection = new CgFrameMultiplexer(4096, true, frame -> { });
        connection.onFrameReceived(new byte[] {0x7F, 0x00, 0x01});
        connection.pump();
    }

    @Test(expected = CgCodecException.class)
    public void aTruncatedFrameIsRefused() {
        CgFrameMultiplexer connection = new CgFrameMultiplexer(4096, true, frame -> { });
        connection.onFrameReceived(new byte[] {0x01});
        connection.pump();
    }

    @Test
    public void reassemblyIsBoundedRatherThanUnlimited() {
        // A peer that opens a stream and never finishes it costs the sender nothing; the receiver must
        // not pay for that without limit.
        CgFrameMultiplexer victim = new CgFrameMultiplexer(64_000, false, frame -> { });
        CgFrameMultiplexer attacker = new CgFrameMultiplexer(64_000, true, victim::onFrameReceived);

        try {
            for (int i = 0; i < 400; i++) {
                attacker.send(bytes(60_000, i));
                attacker.flush();
                // Grant unlimited credit so the attacker is never the thing that stops.
                victim.onFrameReceived(windowUpdateOf(1_000_000));
                victim.pump();
            }
        } catch (CgCodecException refused) {
            assertTrue(refused.getMessage().contains("reassembly"));
            return;
        }
        // Not reaching the cap is fine too, so long as we never held more than it allows.
        assertTrue("held " + victim.reassemblyBytes(),
                victim.reassemblyBytes() <= CgFrameMultiplexer.MAX_REASSEMBLY_BYTES);
    }

    private static byte[] windowUpdateOf(int credit) {
        // OP_WINDOW_UPDATE, no flags, stream 0, varint credit.
        List<Byte> out = new ArrayList<>(Arrays.asList((byte) 0x02, (byte) 0x00, (byte) 0x00));
        int remaining = credit;
        while ((remaining & ~0x7F) != 0) {
            out.add((byte) ((remaining & 0x7F) | 0x80));
            remaining >>>= 7;
        }
        out.add((byte) remaining);
        byte[] frame = new byte[out.size()];
        for (int i = 0; i < frame.length; i++) frame[i] = out.get(i);
        return frame;
    }

    // ── Threading ───────────────────────────────────────────────────────────

    /**
     * {@code onFrameReceived} is called from the network thread and must only enqueue.
     *
     * <p>Delivering from there would touch the tree off its own thread — which {@code CgTransport}
     * describes as <i>"not a race to be tuned but a correctness bug"</i>.</p>
     */
    @Test
    public void deliveryHappensOnThePumpingThreadNotTheReceivingOne() throws Exception {
        CgFrameMultiplexer connection = new CgFrameMultiplexer(4096, false, frame -> { });
        List<String> deliveredOn = new ArrayList<>();
        connection.setMessageHandler(message -> deliveredOn.add(Thread.currentThread().getName()));

        CgFrameMultiplexer sender = new CgFrameMultiplexer(4096, true, connection::onFrameReceived);
        CountDownLatch handed = new CountDownLatch(1);
        Thread network = new Thread(() -> {
            sender.send(bytes(10, 1));
            sender.flush();
            handed.countDown();
        }, "fake-network-thread");
        network.start();

        assertTrue(handed.await(5, TimeUnit.SECONDS));
        network.join();
        assertTrue("nothing may be delivered before a pump", deliveredOn.isEmpty());

        connection.pump();
        assertEquals(1, deliveredOn.size());
        assertNotEquals("fake-network-thread", deliveredOn.get(0));
    }

    // ── The session-facing seam ─────────────────────────────────────────────

    /**
     * The whole point of the exercise: the same {@code CgTransport<Object>} a session already takes,
     * carrying the same {@code CgPlainOps} trees, now over frames.
     */
    @Test
    @SuppressWarnings("unchecked")
    public void aPlainOpsTreeRoundTripsThroughTheRealTransport() {
        CgFrameMultiplexer[] slot = new CgFrameMultiplexer[2];
        slot[0] = new CgFrameMultiplexer(MC_CLIENT_FRAME, true, frame -> slot[1].onFrameReceived(frame));
        slot[1] = new CgFrameMultiplexer(MC_CLIENT_FRAME, false, frame -> slot[0].onFrameReceived(frame));

        CgWireTransport client = new CgWireTransport(slot[0]);
        CgWireTransport server = new CgWireTransport(slot[1]);

        List<Object> got = new ArrayList<>();
        server.setReceiver(got::add);

        Map<Object, Object> packet = new LinkedHashMap<>();
        packet.put("type", "OpenWindow");
        packet.put("windowId", 3);
        packet.put("elements", Arrays.asList("button", "text"));
        packet.put("blob", bytes(80_000, 5));   // forces fragmentation

        client.send(packet);
        for (int i = 0; i < 500 && got.isEmpty(); i++) {
            client.pump();
            server.pump();
        }

        assertEquals(1, got.size());
        Map<Object, Object> received = (Map<Object, Object>) got.get(0);
        assertEquals("OpenWindow", received.get("type"));
        assertEquals(3, received.get("windowId"));
        assertEquals(Arrays.asList("button", "text"), received.get("elements"));
        assertArrayEquals(bytes(80_000, 5), (byte[]) received.get("blob"));
    }
}
