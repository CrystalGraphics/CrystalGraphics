package com.crystalgraphics.net.wire;

import com.crystalgraphics.serialization.CgCodecException;


import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Phase 5.9 — <b>a stream error is not a connection error.</b>
 *
 * <p>{@code handleData} has said since it was written that it <i>"refuses the stream rather than the
 * connection: one oversized transfer should not take down an editor session that is otherwise fine"</i>.
 * It did not. The refusal threw straight out of {@code pump}, abandoning every frame queued behind it
 * and skipping that tick's {@code replenish} and {@code flush} — no credit granted and nothing sent, on
 * a connection whose other streams were healthy. It survived only because
 * {@code CgUiConnections.tickSafely} catches two layers up, which made the comment true by accident and
 * not true at all for the harness, a test, or anything that pumps directly.</p>
 *
 * <p>The split is HTTP/2's: a <b>stream</b> error (RFC 9113 §5.4.2) resets one stream and the connection
 * carries on; a <b>connection</b> error (§5.4.1) means the peer is not speaking this protocol.</p>
 */
public class CgStreamErrorIsolationTest {

    private static final int MC_CLIENT_FRAME = 32_766;

    private static final class Pair {
        final CgFrameMultiplexer a;
        final CgFrameMultiplexer b;
        final List<byte[]> received = new ArrayList<>();

        Pair() {
            CgFrameMultiplexer[] slot = new CgFrameMultiplexer[2];
            slot[0] = new CgFrameMultiplexer(MC_CLIENT_FRAME, true, frame -> slot[1].onFrameReceived(frame));
            slot[1] = new CgFrameMultiplexer(MC_CLIENT_FRAME, false, frame -> slot[0].onFrameReceived(frame));
            a = slot[0];
            b = slot[1];
            b.setMessageHandler(received::add);
        }

        void settle() {
            for (int i = 0; i < 20_000; i++) {
                int moved = a.pump() + b.pump();
                if (moved == 0 && a.pendingOutboundMessages() == 0) {
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

    // ── Stream errors ───────────────────────────────────────────────────────────────────────────

    /**
     * An oversized transfer is refused and everything else keeps working.
     *
     * <p>This is the claim the comment made and the code did not keep. Asserted on the <b>small message
     * arriving</b> rather than on the absence of a throw: a version that swallowed the refusal and left
     * the pump broken would pass a no-throw assertion and fail this one.</p>
     */
    @Test
    public void anOversizedTransferDoesNotTakeTheConnectionWithIt() {
        Pair pair = new Pair();
        pair.a.send(bytes(9 * 1024 * 1024, 1));
        pair.a.send(bytes(64, 2));
        pair.settle();

        assertEquals("the ordinary message must still arrive", 1, pair.received.size());
        assertEquals(64, pair.received.get(0).length);
        assertEquals("and exactly one stream refused", 1, pair.b.refusedStreams());
        assertNotNull(pair.b.lastRefusal());
        assertTrue("the reason must name the bound: " + pair.b.lastRefusal(),
                pair.b.lastRefusal().contains(String.valueOf(CgFrameMultiplexer.MAX_REASSEMBLY_BYTES)));
    }

    /** And the connection is still usable afterwards, which is the whole point of refusing one stream. */
    @Test
    public void theConnectionKeepsWorkingAfterARefusal() {
        Pair pair = new Pair();
        pair.a.send(bytes(9 * 1024 * 1024, 1));
        pair.settle();
        assertEquals(1, pair.b.refusedStreams());

        pair.a.send(bytes(2 * 1024 * 1024, 2));
        pair.settle();

        assertEquals("a perfectly ordinary transfer after a refusal must go through",
                1, pair.received.size());
        assertEquals(2 * 1024 * 1024, pair.received.get(0).length);
        assertEquals("and nothing further refused", 1, pair.b.refusedStreams());
    }

    /**
     * A RESET cancels the <b>outbound</b> message, not only the inbound buffer.
     *
     * <p>Only the inbound half used to run, and what that costs is <b>corruption, not waste</b>. The peer
     * refuses a stream at its cap, drops its buffer and RESETs; the sender ignores that and sends the
     * remainder; the peer opens a <em>fresh</em> buffer for the same stream id, and the sender's last
     * frame carries FIN — so it reassembles the <b>tail</b> and delivers it as a whole message. A refused
     * 9 MB transfer arrives as a ~1 MB one that looks complete, and nothing reports a problem.</p>
     *
     * <p>Which is why the three tests above assert on <b>how many messages arrived</b>. Removing the
     * cancel makes each of them fail with {@code expected:<1> but was:<2>}, the second being that tail —
     * whereas this test, asserting only that the sender let go, passes against the broken version,
     * because a message that runs to completion releases its budget either way.</p>
     */
    @Test
    public void aResetCancelsTheOutboundMessageAndReleasesItsBudget() {
        Pair pair = new Pair();
        pair.a.send(bytes(9 * 1024 * 1024, 1));
        pair.settle();

        assertEquals("the refusal must have happened once, not once per remaining frame",
                1, pair.b.refusedStreams());
        assertEquals("the sender must have abandoned the message",
                0, pair.a.pendingOutboundMessages());
        assertEquals("and given its admission budget back",
                0L, pair.a.fragmentingBytes());
    }

    /**
     * The budget being released is what lets the next large transfer start at all.
     *
     * <p>A leak here is invisible once and permanent afterwards: the sender admits less and less until
     * it admits nothing, and the connection goes quiet with no error anywhere.</p>
     */
    @Test
    public void aLargeTransferAfterARefusalIsStillAdmitted() {
        Pair pair = new Pair();
        pair.a.send(bytes(9 * 1024 * 1024, 1));
        pair.settle();

        pair.a.send(bytes(7 * 1024 * 1024, 2));
        pair.settle();

        assertEquals("a 7 MB message must still be admitted after a 9 MB one was refused",
                1, pair.received.size());
        assertEquals(7 * 1024 * 1024, pair.received.get(0).length);
    }

    // ── Connection errors ───────────────────────────────────────────────────────────────────────

    /**
     * An unknown opcode still propagates. The peer is not speaking this protocol.
     *
     * <p>Frame layout is {@code [opcode][flags][varint streamId][payload]}, so this is three bytes.
     * Crafted rather than produced by {@code CgFrameCodec}, which by construction cannot emit one.</p>
     */
    @Test
    public void anUnknownOpcodeIsStillAConnectionError() {
        Pair pair = new Pair();
        pair.b.onFrameReceived(new byte[] {0x7F, 0x00, 0x01});
        try {
            pair.b.pump();
            fail("a frame this connection cannot parse must not be swallowed as a stream error");
        } catch (CgCodecException expected) {
            assertTrue("and must not be a CgStreamRefused", !(expected instanceof CgStreamRefused));
            assertTrue(expected.getMessage(), expected.getMessage().contains("opcode"));
        }
        assertEquals("nothing was refused; the connection failed", 0, pair.b.refusedStreams());
    }

    /** DATA on stream 0 is the same class of fault — stream 0 is the connection itself. */
    @Test
    public void dataOnTheConnectionStreamIsStillAConnectionError() {
        Pair pair = new Pair();
        pair.b.onFrameReceived(new byte[] {0x01, 0x00, 0x00});
        try {
            pair.b.pump();
            fail("DATA on stream 0 must not be swallowed as a stream error");
        } catch (CgCodecException expected) {
            assertTrue("and must not be a CgStreamRefused", !(expected instanceof CgStreamRefused));
        }
        assertEquals(0, pair.b.refusedStreams());
    }
}
