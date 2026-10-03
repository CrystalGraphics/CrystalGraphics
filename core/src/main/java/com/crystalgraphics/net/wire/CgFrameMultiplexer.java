package com.crystalgraphics.net.wire;

import com.crystalgraphics.serialization.CgCodecException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import javax.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/**
 * Many logical messages over one small-framed, ordered pipe.
 *
 * <p>The pipe is a Minecraft custom-payload channel, and it imposes two things this exists to absorb:
 * a frame ceiling that is <b>~32 KB client→server on every version</b> (a signed short in the vanilla
 * packet format, unchanged from 1.7.10 to 1.20.x), and an outbound queue —
 * {@code NetworkManager.outboundPacketsQueue} — that is an <b>unbounded</b>
 * {@code ConcurrentLinkedQueue} shared with the entire game.</p>
 *
 * <h3>Three mechanisms, none of them invented</h3>
 *
 * <ul>
 *   <li><b>Stream multiplexing</b>, from HTTP/2. Every message gets a stream id and frames from
 *       different streams interleave, so a five-megabyte file read cannot sit in front of a
 *       two-hundred-byte RPC. Without it, opening a large file visibly freezes the editor's own
 *       protocol behind it.</li>
 *   <li><b>FIN fragmentation</b>, from WebSocket. A message is N frames on one stream, the last flagged.
 *       See {@link CgFrameCodec} for why there is no sequence number.</li>
 *   <li><b>Credit flow control</b>, from HTTP/2's {@code WINDOW_UPDATE}. The receiver advertises a byte
 *       budget; the sender spends it and stops when it is gone.</li>
 * </ul>
 *
 * <p><b>Flow control is the one that is not optional here.</b> RFC 9113 gives the general reason —
 * <i>"a flow-control scheme ensures that streams on the same connection do not destructively interfere
 * with each other"</i> — and our case is worse than the one it describes: these streams contend not only
 * with each other but with chat, movement and chunk loading, on the same TCP connection. A sender that
 * pushes a large file as fast as it can encode does not degrade this feature, it degrades the game.</p>
 *
 * <h3>Threading — one concurrent queue and nothing else</h3>
 *
 * <p>{@link #onFrameReceived} is called from the network thread and does exactly one thing: enqueue.
 * Everything else — reassembly, delivery, credit accounting, scheduling — happens in {@link #pump} on the
 * thread that owns the tree. So credit is single-threaded state despite being replenished by the peer,
 * because a {@code WINDOW_UPDATE} is only ever <em>processed</em> during a pump.</p>
 *
 * <p>That is the contract {@code CgTransport} already states: <i>"It is a mailbox, not a dispatcher."</i>
 * {@code Property} and {@code SignalBase} are single-threaded by documented contract, so delivering a
 * message from the network thread would not be a race to tune but a correctness bug.</p>
 */
public final class CgFrameMultiplexer {
    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphics");


    /** Where an encoded frame goes. Implemented by the platform adapter; ~one method of loader code. */
    public interface FrameSink {
        void send(byte[] frame);
    }

    /**
     * Bytes a peer may have in flight before it must wait for the receiver to catch up.
     *
     * <p>256 KB is eight client→server frames, which is enough to keep the pipe busy without letting one
     * transfer own the connection. HTTP/2 starts streams at 65,535 and treats the number as tunable
     * rather than derived; so is this.</p>
     *
     * <p><b>That sentence was false for as long as it existed.</b> {@link #flush} made a single
     * round-robin pass per call and {@code pump()} runs once per game tick, so exactly ONE of those eight
     * frames was ever in flight: a lone 1 MB message took 32 ticks — 1.7 seconds at zero latency — with
     * seven-eighths of the granted window unspent. Tuning this number would have changed nothing, which
     * is the tell that the constant was never the limit. Measured and fixed in {@code flush}; the figure
     * itself still wants measuring against a real server under load.</p>
     */
    public static final int DEFAULT_WINDOW_BYTES = 256 * 1024;

    /**
     * A ceiling on reassembly, across every open stream — and the budget the sender admits against.
     *
     * <p>Two jobs, and the second was added after measuring. As a <b>receiver</b> bound it stops a broken
     * or hostile peer pinning memory by opening streams and never finishing them. As a <b>sender</b>
     * budget it is what {@link #admits} rations, so a well-behaved peer paces itself instead of being
     * refused: without that, forty ordinary half-megabyte messages fragment simultaneously and none of
     * them arrives. @see #flush</p>
     *
     * <p>Both sides share the constant rather than negotiating it. A negotiated limit is the right shape
     * eventually — HTTP/2 sends one in SETTINGS — and would need a handshake this protocol does not
     * have; until then, a peer that ignores the budget is precisely what the receiver half still guards.</p>
     */
    public static final int MAX_REASSEMBLY_BYTES = 8 * 1024 * 1024;

    private final int maxPayloadBytes;
    private final FrameSink sink;

    /** The only field two threads touch. Network thread appends; {@link #pump} drains. */
    private final Queue<byte[]> arrived = new ConcurrentLinkedQueue<>();

    private final Map<Integer, ByteArrayOutputStream> reassembling = new HashMap<>();
    private int reassemblyBytes;

    /** Messages waiting to go out, each already assigned a stream. Round-robined by {@link #flush}. */
    private final Deque<Outbound> outbound = new ArrayDeque<>();

    private Consumer<byte[]> messageHandler = message -> { };

    /** Odd for the side that connects, even for the side that accepts — HTTP/2's trick for never colliding. */
    private int nextStreamId;

    /** What the peer has granted us. Spent by {@link #flush}, replenished by their WINDOW_UPDATE. */
    private long sendCredit = DEFAULT_WINDOW_BYTES;

    /** What we have granted the peer and not yet re-granted. */
    private int unacknowledgedBytes;

    /**
     * Total length of messages that have begun fragmenting and not finished. @see #admits
     *
     * <p>Counts the <em>whole</em> message rather than what is left of it: the receiver's buffer grows to
     * the full length before it delivers, so what matters to it is what is coming, not what has arrived.</p>
     */
    private long fragmentingBytes;

    private boolean closed;

    /** Streams refused since this connection opened. @see #pump */
    private int refusedStreams;

    /** Why the last one was refused, for a test and for a log. */
    @Nullable
    private String lastRefusal;

    /**
     * @param maxFrameBytes the platform's own per-frame ceiling — <b>asked for, never assumed</b>. It
     *                      differs by direction and version (~32 KB client→server on both, 2 MB
     *                      server→client on 1.7.10, 1 MB on 1.20.1), and a constant here would be wrong
     *                      on three of those four.
     * @param initiator     true on the side that opened the connection; decides odd or even stream ids
     */
    public CgFrameMultiplexer(int maxFrameBytes, boolean initiator, FrameSink sink) {
        if (maxFrameBytes <= 64) throw new IllegalArgumentException("maxFrameBytes too small");
        // Sized against the widest header a stream id can produce, so a payload can never push a frame
        // one byte over the platform's limit -- which would throw from inside the loader, mid-send.
        this.maxPayloadBytes = maxFrameBytes - CgFrameCodec.headerSize(Integer.MAX_VALUE);
        this.sink = sink;
        this.nextStreamId = initiator ? 1 : 2;
    }

    /** Installs the sink for whole, reassembled messages. Called during {@link #pump}, on its thread. */
    public void setMessageHandler(Consumer<byte[]> handler) {
        this.messageHandler = handler == null ? message -> { } : handler;
    }

    // ── Sending ─────────────────────────────────────────────────────────────

    /**
     * Queues one whole message. Fragmented and paced by {@link #flush}; nothing goes out here.
     *
     * <p>Deferring the write is what makes flow control possible at all — a {@code send} that emitted
     * immediately would have nowhere to put the bytes it is not yet allowed to send.</p>
     */
    public void send(byte[] message) {
        if (closed) throw new IllegalStateException("connection is closed");
        outbound.addLast(new Outbound(allocateStreamId(), message));
    }

    private int allocateStreamId() {
        int id = nextStreamId;
        // Skips 0, which is the connection itself. Wraps rather than overflowing into negatives: ids are
        // only ever live for the length of one message, so reuse after two billion is not a collision.
        nextStreamId += 2;
        if (nextStreamId < 0) nextStreamId = (id & 1) == 1 ? 1 : 2;
        return id;
    }

    /**
     * Emits as much as credit allows, round-robin across streams that have been <b>admitted</b>.
     *
     * <p>Round-robin rather than first-in-first-out is the entire anti-head-of-line-blocking measure: a
     * queued 5 MB message and a queued 200-byte one make progress together, and the small one finishes
     * almost immediately instead of after the large one.</p>
     *
     * <h3>Why admission exists — measured, and worse than it was suspected to be</h3>
     *
     * <p>Round-robin with no limit means <b>every queued message fragments at once</b>, so the receiver
     * must buffer all of them simultaneously: reassembly demand is the <em>sum</em> of the messages in
     * flight, not the largest. Against {@link #MAX_REASSEMBLY_BYTES} that is not a large-file problem at
     * all. Measured on an in-memory pair at the 1.7.10 client frame size:</p>
     *
     * <pre>
     *   one 7 MB                  -> delivered, peak reassembly 7.3 MB
     *   three 4 MB  (12 MB total) -> delivered 0, CgCodecException at the cap
     *   eight 2 MB  (16 MB total) -> delivered 0
     *   forty 512 KB (20 MB total)-> delivered 0
     * </pre>
     *
     * <p>Forty half-megabyte messages is a workspace listing, not an attack, and <b>none of them
     * arrives</b>. The plan expected "several large transfers together" to be the risk; the real one is
     * <em>many ordinary ones</em>, which is far likelier and reads as the connection dying under load.</p>
     *
     * <h3>The rule</h3>
     *
     * <p>A message begins fragmenting only while what is already in flight leaves room for it — HTTP/2's
     * {@code SETTINGS_MAX_CONCURRENT_STREAMS}, expressed in <b>bytes</b> rather than in a count, because
     * bytes is what the receiver's bound is denominated in and a count cannot tell forty 512 KB messages
     * from forty 5 MB ones.</p>
     *
     * <p>Three properties fall out, and each is deliberate:</p>
     * <ul>
     *   <li><b>A message that fits in one frame is never gated.</b> The receiver delivers it without
     *       touching a reassembly buffer, so it costs nothing to bound — which means every UI packet,
     *       every event and every RPC on this wire is completely unaffected.</li>
     *   <li><b>The first message is always admitted</b>, however large. Otherwise a message bigger than
     *       the budget would never be sent at all; instead it goes, and is refused by the receiver at the
     *       cap, which is the documented bound rather than a silent stall.</li>
     *   <li><b>Round-robin is untouched among admitted messages</b>, so the small-behind-large property
     *       it exists for still holds. Admission bounds the interleaving; it does not remove it.</li>
     * </ul>
     */
    public void flush() {
        // ROUND-ROBIN PASSES, REPEATED WHILE CREDIT LASTS -- and the repetition is the fix, not a detail.
        //
        // This was a SINGLE pass: `spins = outbound.size()`, one turn each, done. Fair, and it silently
        // capped the connection at ONE FRAME PER MESSAGE PER TICK, because pump() runs once per game
        // tick. A lone 1 MB message is 32 frames, so it took 32 ticks -- 1.7 SECONDS AT ZERO LATENCY --
        // with 224 KB of granted credit sitting unspent the entire time.
        //
        // Which made DEFAULT_WINDOW_BYTES' own justification false: it says "256 KB is eight
        // client->server frames, which is enough to keep the pipe busy", and exactly one of those eight
        // was ever in flight. The window was advisory; the flush loop was the real limit, and no amount
        // of tuning the constant would have moved it.
        //
        // The inner pass still gives every message one turn before any message gets a second, so the
        // anti-head-of-line-blocking property below is untouched. The outer loop only decides HOW MANY
        // rounds happen, and credit -- which is what the peer actually promised to buffer -- decides
        // that. Termination: every round either sends (strictly decreasing finite credit) or breaks.
        while (sendCredit > 0 && !outbound.isEmpty()) {
            int spins = outbound.size();
            boolean progressed = false;

            while (spins-- > 0 && sendCredit > 0 && !outbound.isEmpty()) {
                Outbound message = outbound.pollFirst();

                if (message.sent == 0 && message.fragments(maxPayloadBytes)) {
                    if (!admits(message.bytes.length)) {
                        // Back of the queue, untouched. The spin counter bounds one pass; `progressed`
                        // is what stops the OUTER loop spinning over a queue where nothing can start.
                        outbound.addLast(message);
                        continue;
                    }
                    fragmentingBytes += message.bytes.length;
                }

                int remaining = message.remaining();
                int chunk = (int) Math.min(Math.min(remaining, maxPayloadBytes), sendCredit);
                boolean fin = chunk == remaining;

                sink.send(CgFrameCodec.data(message.streamId, message.bytes, message.sent, chunk, fin));
                message.sent += chunk;
                sendCredit -= chunk;
                progressed = true;

                if (fin) {
                    if (message.fragments(maxPayloadBytes)) fragmentingBytes -= message.bytes.length;
                } else {
                    // Back of the queue if there is more, so the next message gets a turn before this.
                    outbound.addLast(message);
                }
            }

            // Nothing in the whole queue could move: everything left is waiting on admission, which
            // waits on a message that is already in flight finishing. More rounds cannot help.
            if (!progressed) break;
        }
    }

    /**
     * Whether a message of {@code length} may begin fragmenting now.
     *
     * <p>Budgeted against {@link #MAX_REASSEMBLY_BYTES} because that is the peer's bound and both sides
     * share the constant. It is a self-imposed limit, not a negotiated one: a peer that ignores it is
     * exactly what the receiver's cap is still there for.</p>
     */
    private boolean admits(int length) {
        return fragmentingBytes == 0 || fragmentingBytes + length <= MAX_REASSEMBLY_BYTES;
    }

    // ── Receiving ───────────────────────────────────────────────────────────

    /** <b>Network thread.</b> Enqueues and returns; never parses, never delivers. */
    public void onFrameReceived(byte[] frame) {
        arrived.add(frame);
    }

    /**
     * <b>Game thread.</b> Processes what arrived, delivers whole messages, then sends what is queued.
     *
     * <h3>A stream error is not a connection error, and this is where that becomes true</h3>
     *
     * <p>{@code handleData} has always said <i>"refuse the stream rather than the connection: one
     * oversized transfer should not take down an editor session that is otherwise fine"</i> — and it was
     * not true. The refusal was a {@link CgStreamRefused} thrown out of {@code accept}, straight through
     * this loop, so it abandoned every frame still queued behind it <b>and skipped {@link #replenish}
     * and {@link #flush} for the tick</b>: no credit granted, nothing sent, on a connection whose other
     * streams were perfectly healthy. It survived only because {@code CgUiConnections.tickSafely}
     * catches two layers up, which made the comment true by accident rather than by construction — and
     * not true at all for the harness, a test, or any caller that pumps directly.</p>
     *
     * <p>The split is HTTP/2's, which is where the rest of this class comes from. A <b>stream</b> error
     * (RFC 9113 §5.4.2) resets one stream and the connection carries on; a <b>connection</b> error
     * (§5.4.1) means the peer is not speaking this protocol and there is nothing to salvage. So
     * {@link CgStreamRefused} is caught per frame and counted, while an unknown opcode or DATA on the
     * connection stream still propagates.</p>
     *
     * @return how many whole messages were delivered
     */
    public int pump() {
        int delivered = 0;
        byte[] frame;
        while ((frame = arrived.poll()) != null) {
            try {
                delivered += accept(frame);
            } catch (CgStreamRefused refused) {
                // Already dropped and RESET by the thrower. Counted rather than rethrown, and said once
                // per refusal rather than per frame -- a peer that keeps pushing a stream we have
                // abandoned would otherwise cost a log line for every frame of it.
                refusedStreams++;
                lastRefusal = refused.getMessage();
                LOGGER.warn("[wire] refused a stream: {}", refused.getMessage());
            }
        }
        replenish();
        flush();
        return delivered;
    }

    private int accept(byte[] frame) {
        int opcode = CgFrameCodec.opcode(frame);
        int streamId = CgFrameCodec.streamId(frame);

        if (opcode == CgFrameCodec.OP_WINDOW_UPDATE) {
            // Saturating: a peer that grants more than we can hold is a peer we simply believe less.
            sendCredit = Math.min(sendCredit + CgFrameCodec.credit(frame), Integer.MAX_VALUE);
            return 0;
        }
        if (opcode == CgFrameCodec.OP_RESET) {
            // BOTH DIRECTIONS. drop() forgets what we were reassembling; cancel() stops us sending a
            // message the peer has already abandoned.
            //
            // Only drop() used to run, and the consequence is CORRUPTION rather than waste. The peer
            // refuses a stream at its reassembly cap, drops its buffer and RESETs; we ignore that and
            // send the remainder; the peer -- having dropped -- opens a FRESH buffer for the same stream
            // id, and our last frame carries FIN. So it reassembles the TAIL and delivers it as a whole
            // message. A refused 9 MB transfer arrives as a ~1 MB one that looks perfectly complete, and
            // nothing anywhere reports a problem.
            //
            // Measured, not reasoned: removing this line makes three tests fail with `expected:<1> but
            // was:<2>` -- the second message being that tail. The first draft of this comment claimed the
            // fault was wasted bytes and a repeated warning, which is what it looks like from the code
            // and is not what it does.
            drop(streamId);
            cancel(streamId);
            return 0;
        }
        if (opcode != CgFrameCodec.OP_DATA) {
            throw new CgCodecException("unknown wire opcode 0x" + Integer.toHexString(opcode));
        }
        if (streamId == CgFrameCodec.CONNECTION_STREAM) {
            throw new CgCodecException("DATA on the connection stream");
        }

        int length = CgFrameCodec.payloadLength(frame);
        int offset = CgFrameCodec.payloadOffset(frame);
        unacknowledgedBytes += length;

        // The single-frame case, which is the overwhelming majority: no buffer, no map entry.
        if (CgFrameCodec.isFin(frame) && !reassembling.containsKey(streamId)) {
            byte[] message = new byte[length];
            System.arraycopy(frame, offset, message, 0, length);
            messageHandler.accept(message);
            return 1;
        }

        if (reassemblyBytes + length > MAX_REASSEMBLY_BYTES) {
            // Refuse the stream rather than the connection: one oversized transfer should not take down
            // an editor session that is otherwise fine. CgStreamRefused rather than a bare CgCodecException
            // is what makes that sentence true -- see #pump for what it used to do instead.
            drop(streamId);
            sink.send(CgFrameCodec.reset(streamId));
            throw new CgStreamRefused("stream " + streamId + ": reassembly would exceed "
                    + MAX_REASSEMBLY_BYTES + " bytes");
        }

        ByteArrayOutputStream buffer =
                reassembling.computeIfAbsent(streamId, ignored -> new ByteArrayOutputStream());
        buffer.write(frame, offset, length);
        reassemblyBytes += length;

        if (!CgFrameCodec.isFin(frame)) return 0;

        byte[] message = buffer.toByteArray();
        drop(streamId);
        messageHandler.accept(message);
        return 1;
    }

    private void drop(int streamId) {
        ByteArrayOutputStream buffer = reassembling.remove(streamId);
        if (buffer != null) reassemblyBytes -= buffer.size();
    }

    /**
     * Abandons an outbound message the peer has RESET, giving its admission budget back.
     *
     * <p>{@link #drop} is the inbound half of the same idea and the two are deliberately separate: a
     * stream id is one direction's, so at most one of these ever has anything to do.</p>
     */
    private void cancel(int streamId) {
        for (Iterator<Outbound> it = outbound.iterator(); it.hasNext(); ) {
            Outbound message = it.next();
            if (message.streamId != streamId) continue;
            // Only a message that STARTED fragmenting was ever counted, so only that one is released.
            if (message.sent > 0 && message.fragments(maxPayloadBytes)) {
                fragmentingBytes -= message.bytes.length;
            }
            it.remove();
            return;
        }
    }

    /**
     * Gives the peer back what we have consumed.
     *
     * <p>Batched rather than per-frame, and only past a threshold: a {@code WINDOW_UPDATE} per DATA frame
     * would double the packet count of every transfer to say something the next one would have said
     * anyway. Half the window is HTTP/2 implementations' usual point, and the spec explicitly leaves the
     * choice open — <i>"the document does not stipulate how a receiver decides when to send this
     * frame"</i>.</p>
     */
    private void replenish() {
        if (unacknowledgedBytes < DEFAULT_WINDOW_BYTES / 2) return;
        sink.send(CgFrameCodec.windowUpdate(CgFrameCodec.CONNECTION_STREAM, unacknowledgedBytes));
        unacknowledgedBytes = 0;
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────

    /** Abandons every partial transfer in both directions. Idempotent. */
    public void close() {
        closed = true;
        for (Iterator<Map.Entry<Integer, ByteArrayOutputStream>> it = reassembling.entrySet().iterator();
             it.hasNext(); ) {
            it.next();
            it.remove();
        }
        reassemblyBytes = 0;
        outbound.clear();
        fragmentingBytes = 0;
        arrived.clear();
    }

    public boolean isClosed() {
        return closed;
    }

    /** Whole messages queued or partly sent. Zero means everything handed to the platform. */
    public int pendingOutboundMessages() {
        return outbound.size();
    }

    /** Bytes held in partial inbound messages — the number {@link #MAX_REASSEMBLY_BYTES} bounds. */
    public int reassemblyBytes() {
        return reassemblyBytes;
    }

    /** Bytes of outbound messages that have begun fragmenting and not finished. @see #admits */
    public long fragmentingBytes() {
        return fragmentingBytes;
    }

    /** How many streams this connection has refused. @see #pump */
    public int refusedStreams() {
        return refusedStreams;
    }

    /** Why the last refusal happened, or {@code null} if there has not been one. */
    @Nullable
    public String lastRefusal() {
        return lastRefusal;
    }

    /** What the peer currently allows us to send. */
    public long sendCredit() {
        return sendCredit;
    }

    private static final class Outbound {
        final int streamId;
        final byte[] bytes;
        int sent;

        Outbound(int streamId, byte[] bytes) {
            this.streamId = streamId;
            this.bytes = bytes;
        }

        int remaining() {
            return bytes.length - sent;
        }

        /**
         * Whether this needs more than one frame, and so a reassembly buffer at the far side.
         *
         * <p>The single-frame case is delivered straight out of the frame with no buffer and no map
         * entry — see {@code handleData} — which is why it is exempt from admission.</p>
         */
        boolean fragments(int maxPayloadBytes) {
            return bytes.length > maxPayloadBytes;
        }
    }
}
