package com.crystalgraphics.net.protocol;

import com.crystalgraphics.net.wire.CgFrameMultiplexer;
import com.crystalgraphics.net.wire.CgWireTransport;
import com.crystalgraphics.serialization.CgPlainOps;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import javax.annotation.Nullable;


/**
 * <b>One {@link CgProtocolConnection} per peer</b> - opened when they arrive, closed when they leave,
 * ticked together.
 *
 * <p>The table every networked host needs and none should write twice. A loader turns its own platform
 * events into a few calls and gets the rest for free:</p>
 *
 * <pre>{@code
 * connections.open(playerUuid, peer, frame -> sendToThatPlayer(frame));    // they joined
 * connections.route(playerUuid, incomingFrame);                            // a frame arrived
 * connections.tick();                                                      // every server tick
 * connections.close(playerUuid, "logged out");                             // they left
 * }</pre>
 *
 * <p>Between those, this owns the multiplexer per peer, the transport, the pump, dropping a frame for a
 * peer that has gone, failing outstanding calls on close, and making sure one peer's exception does not
 * stop the rest.</p>
 *
 * <h3>Key it on what is stable, never on what is convenient</h3>
 *
 * <p>The key is yours to choose, and choosing badly fails in a way that is hard to see. Minecraft 1.7.10
 * builds a <em>new</em> player entity on every respawn and every dimension change, so an entity-keyed
 * table is orphaned by the first death: inbound frames name the new body, the lookup misses, and every
 * frame from that client is dropped for the rest of the session - while outbound keeps working, so it
 * reads as an input bug. Use the profile UUID, or whatever your platform's stable identity is.</p>
 *
 * <h3>One table per side</h3>
 *
 * <p>A process that is both client and server - single-player is - holds two, because the two sides are
 * ticked and closed by different events. Frames arrive on the network thread while connections open,
 * close and tick on the thread that owns whatever the handlers touch, so the map is concurrent and
 * {@link CgProtocolConnection#tick()} is the only thing that dispatches.</p>
 */
public final class CgConnections {
    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphics");

    /** Where a frame goes. Supplied per peer, because only the host knows how to reach one. */
    @FunctionalInterface
    public interface Outbound {
        void send(byte[] frame);
    }

    /** Everything a peer needs, kept together so closing one closes all of it. */
    private static final class Held {
        final CgFrameMultiplexer frames;
        final CgProtocolConnection<Object> connection;
        @Nullable
        final Object peer;

        Held(CgFrameMultiplexer frames, CgProtocolConnection<Object> connection, @Nullable Object peer) {
            this.frames = frames;
            this.connection = connection;
            this.peer = peer;
        }
    }

    private final Map<Object, Held> peers = new ConcurrentHashMap<>();
    private final String which;
    private final int maxFrameBytes;
    private final boolean initiator;

    @Nullable
    private Consumer<Object> onClosed;

    /**
     * @param which         "server" or "client" — for log lines, and the only thing that differs in them
     * @param maxFrameBytes the channel's frame cap
     * @param initiator     whether this side allocates odd stream ids. The two ends must disagree, as
     *                      HTTP/2 splits them, so both can allocate concurrently without agreeing on
     *                      anything: a client initiates, a server does not
     */
    public CgConnections(String which, int maxFrameBytes, boolean initiator) {
        this.which = which;
        this.maxFrameBytes = maxFrameBytes;
        this.initiator = initiator;
    }

    /**
     * Called with a peer whose connection has just closed, so per-peer state elsewhere can be dropped.
     *
     * <p>Anything that bound per-peer state must be told, or its maps grow for the life of the server —
     * a leak that only shows on a box that has been up for a week.</p>
     */
    public CgConnections onPeerClosed(Consumer<Object> listener) {
        this.onClosed = listener;
        return this;
    }

    /**
     * Opens a connection for {@code key}.
     *
     * @param peer     what handlers see as {@link CgProtocolConnection#peer()}; null where there is one
     *                 peer and it needs no naming
     * @param outbound how to reach it. <b>Resolved at send time by the caller</b>, never captured: on a
     *                 host whose peer objects are replaced (1.7.10 replaces a player entity on every
     *                 respawn) a captured target sends to a body nobody is in
     */
    public CgProtocolConnection<Object> open(Object key, @Nullable Object peer, Outbound outbound) {
        CgFrameMultiplexer frames = new CgFrameMultiplexer(maxFrameBytes, initiator, outbound::send);
        CgWireTransport transport = new CgWireTransport(frames);
        // The pump goes in here rather than being left to a caller: tick() is then the one call, and a
        // subsystem that forgot to pump would receive nothing, silently.
        CgProtocolConnection<Object> connection =
                CgProtocols.open(transport, CgPlainOps.INSTANCE, transport::pump, peer);
        peers.put(key, new Held(frames, connection, peer));
        return connection;
    }

    /** The connection held for {@code key}, or null. */
    @Nullable
    public CgProtocolConnection<Object> get(Object key) {
        Held held = peers.get(key);
        return held == null ? null : held.connection;
    }

    /**
     * <b>Network thread.</b> Hands a frame to its peer's multiplexer, which only enqueues.
     *
     * <p>A frame for a peer that has already gone is dropped rather than opening one: a connection is
     * created by a lifecycle event, never by traffic. Creating one here would resurrect a peer that has
     * left, and would make a disconnect racy against whatever was still in flight.</p>
     */
    public void route(Object key, byte[] frame) {
        Held held = peers.get(key);
        if (held != null) held.frames.onFrameReceived(frame);
    }

    /**
     * Closes one peer's connection and forgets it.
     *
     * <p>Fails everything outstanding rather than letting each caller wait out its own timeout. A peer
     * that is gone is knowable now; ten seconds of silence per pending call is not information.</p>
     *
     * @return whether there was one
     */
    public boolean close(Object key, String reason) {
        Held held = peers.remove(key);
        if (held == null) return false;
        if (held.peer != null && onClosed != null) onClosed.accept(held.peer);
        held.connection.close(reason);
        return true;
    }

    /**
     * Ticks every connection.
     *
     * <p><b>One peer's exception must not stop every other peer being ticked.</b> On a server that is
     * the difference between one player's broken handler and every player's session freezing — and the
     * frozen ones would show no error of their own, which is the shape that gets diagnosed as a network
     * fault.</p>
     */
    public void tick() {
        for (Held held : peers.values()) {
            try {
                held.connection.tick();
            } catch (RuntimeException failed) {
                LOGGER.error("[cg-net] {} connection tick failed: {}",
                        which, failed.getMessage(), failed);
            }
        }
    }

    /** Closes everything. On a stop, so no caller is left waiting out a timeout for a dead process. */
    public void closeAll(String reason) {
        for (Object key : peers.keySet().toArray()) close(key, reason);
    }

    /** How many peers hold one. Diagnostics, and what a leak would show up in. */
    public int size() {
        return peers.size();
    }
}
