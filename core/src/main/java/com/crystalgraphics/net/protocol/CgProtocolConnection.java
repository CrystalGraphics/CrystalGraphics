package com.crystalgraphics.net.protocol;

import com.crystalgraphics.net.CgTransport;
import com.crystalgraphics.serialization.CgDynamicOps;
import com.crystalgraphics.serialization.CgStateMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * One peer's end of the protocol — a {@link CgMessageRouter} with a transport under it and a name for who
 * is on the other side.
 *
 * <p>This is the thing a subsystem binds to. It exists because a router was previously reachable only by
 * constructing a {@code ServerUiSession}: the protocol layer was general, the <em>wiring</em> was not, so
 * a workspace or a script runtime on a dedicated server had nowhere to register.</p>
 *
 * <h3>Why this is per-connection and CustomNPC+'s {@code PacketHandler} is global</h3>
 *
 * <p>Their registry maps a packet type to a handler and can be a singleton, because a packet carries
 * everything needed to act on it. A router cannot: it holds <b>pending requests correlated to one peer</b>,
 * a stream-id space, credit, and timeouts. Two players sharing a router would collide on ids the first
 * time both had a call in flight. So the <em>registry</em> is global — see {@link CgProtocols} — and what
 * it registers are contributors that are invoked once per connection, against one of these.</p>
 *
 * <h3>{@link #tick()} does all three steps</h3>
 *
 * <p>Pump the wire, drain what arrived, expire what timed out. Deliberately one call: a subsystem that
 * had to remember to pump its transport separately would receive nothing, silently, which is the exact
 * failure shape this codebase keeps paying for elsewhere.</p>
 *
 * @param <T> the encoded representation, matching the transport's {@code CgDynamicOps}
 */
public final class CgProtocolConnection<T> {
    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphics");


    private final CgMessageRouter<T> router;
    private final CgDynamicOps<T> ops;
    private final Runnable pump;
    @Nullable
    private final Object peer;

    /** Arrives on the transport's thread, drained on ours. See {@link CgTransport}. */
    private final Deque<T> mailbox = new ArrayDeque<>();

    private long callTimeoutMillis = 10_000L;

    /**
     * Things scoped to this connection, one per type — the seat a subsystem's per-connection state
     * takes. @see #attachment
     */
    private final Map<Class<?>, Object> attachments = new LinkedHashMap<>();

    /** Marks a type being built, so a factory that re-enters for its own type says so. @see #attachment */
    private static final Object CONSTRUCTING = new Object();

    /** Run at the end of every {@link #tick()}. @see #onTick */
    private final List<Runnable> tickHooks = new ArrayList<>();

    /** Run once, when the peer goes away. @see #onClosed */
    private final List<Consumer<String>> closeHooks = new ArrayList<>();

    private boolean closed;
    private String closeReason = "";

    CgProtocolConnection(CgTransport<T> transport, CgDynamicOps<T> ops, Runnable pump, @Nullable Object peer) {
        this.ops = ops;
        this.pump = pump;
        this.peer = peer;
        this.router = new CgMessageRouter<>(envelope -> transport.send(CgEnvelopeCodec.encode(ops, envelope)));
        transport.setReceiver(raw -> {
            synchronized (mailbox) {
                mailbox.add(raw);
            }
        });
    }

    /**
     * The platform's handle for who is on the other end — an {@code EntityPlayerMP} on 1.7.10, a
     * {@code ServerPlayer} on 1.20.x, and {@code null} on a client, where there is only ever one peer.
     *
     * <p>Opaque here for the same reason it is opaque on {@code CgNetworkChannel}: {@code core} cannot
     * name a Minecraft type, and a subsystem that needs one casts at its own loader.</p>
     */
    @Nullable
    public Object peer() {
        return peer;
    }

    public CgDynamicOps<T> ops() {
        return ops;
    }

    /** The router itself, for anything the conveniences below do not cover. */
    public CgMessageRouter<T> router() {
        return router;
    }

    // ── Per-connection state, hooks ─────────────────────────────────────────

    /**
     * The one instance of {@code type} for this connection, built on first ask.
     *
     * <p><b>What three static {@code WeakHashMap}s were.</b> {@code UiWindowMux.of},
     * {@code ClientUiSessions.forConnection} and the workspace each memoised
     * "the X for this connection" independently, each with the same javadoc explaining the same
     * singleton-per-connection reason, and each relying on <em>garbage collection</em> rather than on
     * the lifecycle to let go. The connection is the natural owner of anything scoped to it, so the
     * memo lives here: it dies with the connection, it needs no weak reference, and there is one
     * statement of the pattern rather than one per subsystem.</p>
     *
     * <p>The reason those memos exist at all is unchanged and worth restating: a second
     * {@code UiWindowMux} over one router would each install their own handler for a method and the
     * second would be refused by {@link CgMessageRouter}'s duplicate check — so "the X for this
     * connection" has to be a single answer, not something a caller remembers to share.</p>
     *
     * <p>A factory may ask for a <em>different</em> attachment (a host asking for a mux is the real
     * case); asking for its own throws rather than recursing or quietly building two.</p>
     */
    @SuppressWarnings("unchecked")
    public synchronized <A> A attachment(Class<A> type, Function<CgProtocolConnection<T>, A> factory) {
        Object existing = attachments.get(type);
        if (existing == CONSTRUCTING) {
            throw new IllegalStateException("the factory for " + type.getName()
                    + " asked for its own attachment; it is not built yet");
        }
        if (existing != null) return (A) existing;
        attachments.put(type, CONSTRUCTING);
        A created;
        try {
            created = factory.apply(this);
        } catch (RuntimeException | Error failed) {
            // Or the marker outlives the failure and every later ask reports a bogus recursion.
            attachments.remove(type);
            throw failed;
        }
        attachments.put(type, created);
        return created;
    }

    /** Whether {@code type} has already been attached, without <em>causing</em> it. */
    public synchronized boolean hasAttachment(Class<?> type) {
        Object existing = attachments.get(type);
        return existing != null && existing != CONSTRUCTING;
    }

    /**
     * Runs at the end of every {@link #tick()} — <b>after</b> the mailbox is drained and timeouts have
     * been swept.
     *
     * <p>Order is the whole point: a hook is where per-connection work belongs, and it must see this
     * tick's input already delivered. Draining after would run every window's tick against the
     * <em>previous</em> tick's messages, which looks like one frame of network latency and is not.</p>
     *
     * <p>One hook's exception does not stop the others — the same rule
     * {@code CgUiConnections.tickSafely} already applies per peer, for the same reason: a frozen
     * subsystem with no error of its own is the shape that gets diagnosed as a network fault.</p>
     */
    public CgProtocolConnection<T> onTick(Runnable hook) {
        if (hook != null) tickHooks.add(hook);
        return this;
    }

    /**
     * Runs once, when the peer goes away — the fact everything above the router needs and could not
     * get.
     *
     * <p>{@link #close} used to do exactly one thing: fail every pending request. Nothing was
     * <em>told</em>. So a {@code ServerUiSession} on a dead connection stayed open, kept observing its
     * tree and kept encoding state deltas into a wire nobody was reading, until something external
     * remembered to close it — which is why every consumer grew its own "is the connection still
     * there" poll, and why forgetting one leaked for the life of the server.</p>
     *
     * <p><b>A hook registered after the connection has already closed runs immediately.</b> Same rule
     * and same reason as {@code CgLifecycleListener.onInit} reaching a late registrant: the guarantee
     * is <em>exactly once</em>, whenever it was asked for, because the alternative is a miss that
     * nothing reports.</p>
     */
    public CgProtocolConnection<T> onClosed(Consumer<String> hook) {
        if (hook == null) return this;
        if (closed) {
            hook.accept(closeReason);
            return this;
        }
        closeHooks.add(hook);
        return this;
    }

    /** Whether the peer has gone. A closed connection dispatches nothing and ticks nothing. */
    public boolean isClosed() {
        return closed;
    }

    /** How long this connection's calls wait before their error handler is told. */
    public CgProtocolConnection<T> setCallTimeoutMillis(long millis) {
        this.callTimeoutMillis = millis;
        return this;
    }

    // ── Registering ─────────────────────────────────────────────────────────

    /**
     * Serves a method. The handler must answer exactly once — the router enforces it.
     *
     * <p>Shaped as {@link CgCall.Handler} rather than {@link CgMessageRouter.RequestHandler} so that
     * {@code WorkspaceBinding.Registrar} and {@code ServerUiSession::onCall} stay interchangeable: a
     * subsystem is written once and installs onto either.</p>
     */
    public CgProtocolConnection<T> onRequest(String method, CgCall.Handler<T> handler) {
        router.onRequest(method, (payload, respond) ->
                handler.invoke(read(payload), new CgCall.Responder<T>() {
                    @Override
                    public void ok(@Nullable CgStateMap<T> value) {
                        respond.ok(value == null ? null : value.encode());
                    }

                    @Override
                    public void fail(String error) {
                        respond.fail(error);
                    }
                }));
        return this;
    }

    /** Listens for a notification. Nothing is sent back — that is what makes it one. */
    public CgProtocolConnection<T> onNotify(String method, Consumer<CgStateMap<T>> handler) {
        router.onNotify(method, payload -> handler.accept(read(payload)));
        return this;
    }

    // ── Sending ─────────────────────────────────────────────────────────────

    /** Asks the peer and expects an answer. */
    public void call(String method, @Nullable CgStateMap<T> args,
                     @Nullable Consumer<CgStateMap<T>> onResult, @Nullable Consumer<String> onError) {
        router.request(method, args == null ? null : args.encode(),
                value -> {
                    if (onResult != null) onResult.accept(read(value));
                },
                onError,
                System.currentTimeMillis() + callTimeoutMillis);
    }

    /** Tells the peer. Nothing comes back, and nothing may be waited on. */
    public void notify(String method, @Nullable CgStateMap<T> payload) {
        router.notify(method, payload == null ? null : payload.encode());
    }

    // ── Running ─────────────────────────────────────────────────────────────

    /**
     * Pump, drain, expire. <b>Call once per tick, on the thread that owns whatever the handlers touch.</b>
     *
     * @return how many messages were dispatched
     */
    public int tick() {
        if (closed) return 0;
        pump.run();

        List<T> batch;
        synchronized (mailbox) {
            if (mailbox.isEmpty()) {
                router.tickTimeouts(System.currentTimeMillis());
                runTickHooks();
                return 0;
            }
            batch = new ArrayList<>(mailbox);
            mailbox.clear();
        }

        int dispatched = 0;
        for (T raw : batch) {
            CgEnvelope envelope;
            try {
                envelope = CgEnvelopeCodec.decode(ops, raw);
            } catch (RuntimeException malformed) {
                // Per message, so one unreadable frame cannot take the rest of the batch with it.
                LOGGER.warn("Dropping an undecodable message: {}", malformed.getMessage());
                continue;
            }
            router.accept(envelope);
            dispatched++;
        }
        router.tickTimeouts(System.currentTimeMillis());
        runTickHooks();
        return dispatched;
    }

    private void runTickHooks() {
        // Indexed rather than iterated: a hook may legitimately add another (a host opening a window
        // from inside a tick), and a ConcurrentModificationException there would be a crash caused by
        // ordinary use. Anything added during a tick runs on the NEXT one.
        int count = tickHooks.size();
        for (int i = 0; i < count; i++) {
            try {
                tickHooks.get(i).run();
            } catch (RuntimeException failed) {
                LOGGER.error("A connection tick hook failed: {}", failed.getMessage(), failed);
            }
        }
    }

    /**
     * The peer has gone: fail everything outstanding, then tell anything that asked.
     *
     * <p>Without the first half every caller waits out its own timeout for an answer that is never
     * coming; without the second, nothing above the router ever learns — see {@link #onClosed}.</p>
     *
     * <p><b>Failing comes first</b> so a close hook does not see requests still pending against a wire
     * that has gone. Idempotent: a connection closes once, and hooks are dropped afterwards so a
     * retained connection cannot keep them alive.</p>
     */
    public void close(String reason) {
        if (closed) return;
        closed = true;
        closeReason = reason == null ? "" : reason;
        router.failAllPending(closeReason);
        List<Consumer<String>> hooks = new ArrayList<>(closeHooks);
        closeHooks.clear();
        for (Consumer<String> hook : hooks) {
            try {
                hook.accept(closeReason);
            } catch (RuntimeException failed) {
                // One subsystem's teardown must not cost every other subsystem its own.
                LOGGER.error("A connection close hook failed: {}", failed.getMessage(), failed);
            }
        }
    }

    private CgStateMap<T> read(@Nullable T payload) {
        return payload == null ? new CgStateMap<>(ops) : new CgStateMap<>(ops, payload);
    }
}
