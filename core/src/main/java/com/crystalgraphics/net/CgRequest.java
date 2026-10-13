package com.crystalgraphics.net;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.crystalgraphics.job.reply.CgPendingReply;
import com.crystalgraphics.job.reply.CgReply;
import com.crystalgraphics.job.reply.CgReplyError;
import com.crystalgraphics.net.protocol.CgMessageRouter;
import com.crystalgraphics.net.protocol.CgProtocolConnection;
import com.crystalgraphics.net.protocol.CgProtocolErrors;
import com.crystalgraphics.serialization.CgCodec;
import com.crystalgraphics.serialization.CgCodecException;

/**
 * A question one side asks and the other answers: a name, a direction, and a codec each way. Declared once at init
 * on both sides, like a {@link CgMessage}.
 *
 * <pre>{@code
 * public static final CgRequest<BlockPos, Stats> STATS =
 *         CgRequest.toServer("mymod:machine/stats", BlockPos.CODEC, Stats.CODEC);
 *
 * STATS.onServer((peer, pos, reply) -> reply.ok(statsAt(peer, pos)));        // server
 * STATS.ask(pos).then(stats -> show(stats)).onError(e -> showError(e.code()));   // client
 *
 * // the other way: the server asks one client
 * public static final CgRequest<Unit, Settings> SETTINGS =
 *         CgRequest.toClient("mymod:client/settings", Unit.CODEC, Settings.CODEC);
 * SETTINGS.onClient((unit, reply) -> reply.ok(localSettings()));             // client entry point
 * SETTINGS.ask(playerId, Unit.INSTANCE).then(settings -> apply(settings));   // server
 * }</pre>
 *
 * An ask is a {@link CgReply}, so it composes with jobs and other asks:
 *
 * <pre>{@code
 * CgReply.both(STATS.ask(pos), CgJob.run(() -> loadIcon(pos))).then(both -> show(both.first(), both.second()));
 * }</pre>
 *
 * <ul>
 *   <li>Every answer, error included, arrives on the asking side's game thread at its tick, once.</li>
 *   <li>A reply may be given later than the handler returns; {@link Reply} is answered once and later calls are
 *       ignored.</li>
 *   <li>An error's code is the handler's own text, or {@link #UNSUPPORTED} when the peer lacks the namespace or
 *       speaks another version, {@link CgReplyError#TIMEOUT} past {@link #timeout}, {@link CgReplyError#CANCELLED}
 *       after {@code cancel()}, and {@link CgProtocolErrors#HANDLER_FAILED} (its detail what was thrown) when the
 *       handler threw or an answer would not decode.</li>
 *   <li>A failure nobody listens for is logged.</li>
 *   <li>Mods mostly push: reach for a {@link CgMessage} unless the asker needs the answer.</li>
 * </ul>
 */
public final class CgRequest<A, R> {

    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphics");

    /** The peer cannot answer: no such namespace there, another version of it, or no hello at all. */
    public static final String UNSUPPORTED = "cg/unsupported";

    /** How long an ask waits by default. */
    public static final long DEFAULT_TIMEOUT_MILLIS = 10_000;

    private static final Pattern NAME = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    private static final List<CgRequest<?, ?>> DECLARED = new CopyOnWriteArrayList<>();

    /** Which side answers. */
    public enum Direction {
        /** A client asks the server. */
        TO_SERVER,
        /** The server asks one client. */
        TO_CLIENT
    }

    private final String name;
    private final String namespace;
    private final Direction direction;
    private final CgCodec<A> args;
    private final CgCodec<R> result;
    private long timeoutMillis = DEFAULT_TIMEOUT_MILLIS;

    @Nullable
    private volatile ServerHandler<A, R> onServer;
    @Nullable
    private volatile ClientHandler<A, R> onClient;

    private CgRequest(String name, Direction direction, CgCodec<A> args, CgCodec<R> result) {
        if (!NAME.matcher(name).matches()) throw new IllegalArgumentException("not a namespaced name: " + name);
        this.name = name;
        this.namespace = name.substring(0, name.indexOf(':'));
        this.direction = direction;
        this.args = Objects.requireNonNull(args, "args");
        this.result = Objects.requireNonNull(result, "result");
    }

    /** A client asks, the server answers. */
    public static <A, R> CgRequest<A, R> toServer(String name, CgCodec<A> args, CgCodec<R> result) {
        return declare(new CgRequest<>(name, Direction.TO_SERVER, args, result));
    }

    /** The server asks a client, the client answers. */
    public static <A, R> CgRequest<A, R> toClient(String name, CgCodec<A> args, CgCodec<R> result) {
        return declare(new CgRequest<>(name, Direction.TO_CLIENT, args, result));
    }

    private static <A, R> CgRequest<A, R> declare(CgRequest<A, R> request) {
        synchronized (DECLARED) {
            for (CgRequest<?, ?> other : DECLARED) {
                if (other.name.equals(request.name)) {
                    throw new IllegalStateException("Request '" + request.name + "' is already declared");
                }
            }
            DECLARED.add(request);
        }
        return request;
    }

    /** How long an ask waits for its answer before failing with {@code protocol/timeout}. */
    public CgRequest<A, R> timeout(long millis) {
        if (millis <= 0) throw new IllegalArgumentException("timeout must be positive: " + millis);
        timeoutMillis = millis;
        return this;
    }

    public String name() {
        return name;
    }

    public Direction direction() {
        return direction;
    }

    // ── Answering ───────────────────────────────────────────────────────────────────────────────

    /** Answers one request. Exactly once; a second call is ignored. */
    public interface Reply<R> {
        void ok(R value);

        void fail(String error);

        /**
         * Answers with what {@code answer} settles to: work too heavy for the tick, run as a job.
         *
         * <pre>{@code
         * STATS.onServer((peer, pos, reply) -> reply.from(CgJob.run(() -> scan(snapshotAt(pos)))));
         * }</pre>
         *
         * A failure answers with its code, or {@link CgProtocolErrors#HANDLER_FAILED} and what was thrown.
         */
        default void from(CgReply<? extends R> answer) {
            answer.then(this::ok).onError(error -> fail(error.cause() != null
                    ? CgProtocolErrors.HANDLER_FAILED + ": " + error.cause() : error.code()));
        }
    }

    /** The server's handler, told which player asked. */
    @FunctionalInterface
    public interface ServerHandler<A, R> {
        void handle(CgPeer from, A args, Reply<R> reply);
    }

    /** A client's handler. */
    @FunctionalInterface
    public interface ClientHandler<A, R> {
        void handle(A args, Reply<R> reply);
    }

    /** On the server: how to answer. Replaces any earlier handler. */
    public CgRequest<A, R> onServer(ServerHandler<A, R> handler) {
        if (direction != Direction.TO_SERVER) throw new IllegalStateException(name + " is answered by a client");
        onServer = Objects.requireNonNull(handler, "handler");
        return this;
    }

    /** On a client: how to answer. Install from client code. Replaces any earlier handler. */
    public CgRequest<A, R> onClient(ClientHandler<A, R> handler) {
        if (direction != Direction.TO_CLIENT) throw new IllegalStateException(name + " is answered by the server");
        onClient = Objects.requireNonNull(handler, "handler");
        return this;
    }

    // ── Asking ──────────────────────────────────────────────────────────────────────────────────

    /** On a client: asks the server. Fails with {@link #UNSUPPORTED} when not connected. */
    public CgReply<R> ask(A value) {
        if (direction != Direction.TO_SERVER) throw new IllegalStateException(name + " is asked by the server");
        return ask(CgNetwork.client(), value);
    }

    /** On the server: asks one player. Fails with {@link #UNSUPPORTED} when they have no connection. */
    public CgReply<R> ask(UUID player, A value) {
        if (direction != Direction.TO_CLIENT) throw new IllegalStateException(name + " is asked by a client");
        return ask(CgNetwork.forPlayer(player), value);
    }

    /** One ask: its reply, and the router's id once it is sent, for cancel. */
    private final class Asked {
        final CgPendingReply<R> reply = new CgPendingReply<>(this::cancel);
        final Consumer<String> failed = this::fail;
        @Nullable
        CgProtocolConnection<Object> connection;
        int id = -1;

        void cancel() {
            if (connection != null && id >= 0) connection.router().cancel(id);   // fails it through the router
        }

        void fail(String wire) {
            if (reply.isDone()) return;
            CgReplyError error = errorOf(wire);
            boolean heard = reply.fail(error);
            if (!heard && !error.is(CgReplyError.CANCELLED)) LOGGER.warn("[cg-net] request {} failed: {}", name, error);
        }
    }

    private CgReply<R> ask(@Nullable CgProtocolConnection<Object> connection, A value) {
        Asked asked = new Asked();
        if (connection == null || connection.isClosed()) {
            asked.fail(UNSUPPORTED);
            return asked.reply;
        }
        Object encoded = args.encode(connection.ops(), value);
        CgHello.State peer = CgHello.state(connection);
        peer.whenKnown(() -> {
            if (asked.reply.isDone()) return;
            int theirs = peer.version(namespace);
            int ours = CgMessage.versionOf(namespace);
            if (theirs < 0 || theirs != ours) {
                if (theirs >= 0) peer.reportMismatch(namespace, ours, connection.peer());
                asked.fail(UNSUPPORTED);
                return;
            }
            asked.connection = connection;
            asked.id = connection.router().request(name, encoded, payload -> {
                if (asked.reply.isDone()) return;
                R answer;
                try {
                    answer = result.decode(connection.ops(), payload);
                } catch (CgCodecException malformed) {
                    asked.fail(CgProtocolErrors.HANDLER_FAILED + ": " + malformed.getMessage());
                    return;
                }
                asked.reply.resolve(answer);
            }, asked.failed, System.currentTimeMillis() + timeoutMillis);
        }, () -> asked.fail(UNSUPPORTED));
        return asked.reply;
    }

    /** A wire error as a reply's: the router's cancel and timeout become the codes every reply uses. */
    static CgReplyError errorOf(String wire) {
        if (wire.equals(CgProtocolErrors.CANCELLED)) return new CgReplyError(CgReplyError.CANCELLED, wire);
        if (wire.equals(CgProtocolErrors.TIMEOUT)) return new CgReplyError(CgReplyError.TIMEOUT, wire);
        if (wire.startsWith(CgProtocolErrors.HANDLER_FAILED + ": ")) {
            return new CgReplyError(CgProtocolErrors.HANDLER_FAILED, wire.substring(CgProtocolErrors.HANDLER_FAILED.length() + 2));
        }
        return new CgReplyError(wire, "");
    }

    // ── Wiring, from CgNetwork ──────────────────────────────────────────────────────────────────

    /** This side's request namespaces, for the hello beside {@link CgMessage}'s. */
    static Map<String, Integer> namespaces() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (CgRequest<?, ?> request : DECLARED) out.put(request.namespace, CgMessage.versionOf(request.namespace));
        return out;
    }

    /** How many of {@link #DECLARED} a connection has bound. */
    private static final class Bound {
        int count;
    }

    /** Binds every declared request onto {@code connection}, and any declared later at its next tick. */
    static void bindAll(CgProtocolConnection<Object> connection) {
        Bound bound = connection.attachment(Bound.class, c -> new Bound());
        Runnable catchUp = () -> {
            while (bound.count < DECLARED.size()) {
                CgRequest<?, ?> request = DECLARED.get(bound.count++);
                connection.router().onRequest(request.name, (payload, respond) -> request.serve(connection, payload, respond));
            }
        };
        catchUp.run();
        connection.onTick(catchUp);
    }

    private void serve(CgProtocolConnection<Object> connection, @Nullable Object payload,
                       CgMessageRouter.Responder<Object> respond) {
        boolean fromTheServer = connection.peer() == null;
        ServerHandler<A, R> server = onServer;
        ClientHandler<A, R> client = onClient;
        if (fromTheServer ? client == null : server == null || !(connection.peer() instanceof CgPeer)) {
            respond.fail(CgProtocolErrors.METHOD_NOT_FOUND);
            return;
        }
        A value;
        try {
            value = args.decode(connection.ops(), payload);
        } catch (CgCodecException malformed) {
            respond.fail(CgProtocolErrors.HANDLER_FAILED + ": " + malformed.getMessage());
            return;
        }
        Reply<R> reply = new Reply<R>() {
            @Override
            public void ok(R answer) {
                respond.ok(result.encode(connection.ops(), answer));
            }

            @Override
            public void fail(String error) {
                respond.fail(error);
            }
        };
        try {
            if (fromTheServer) client.handle(value, reply);
            else server.handle((CgPeer) connection.peer(), value, reply);
        } catch (RuntimeException failed) {
            reply.fail(CgProtocolErrors.HANDLER_FAILED + ": " + failed);
        }
    }

    /** Forgets every declaration. Tests only. */
    static void resetForTesting() {
        DECLARED.clear();
    }

    @Override
    public String toString() {
        return "CgRequest[" + name + " " + direction + "]";
    }
}
