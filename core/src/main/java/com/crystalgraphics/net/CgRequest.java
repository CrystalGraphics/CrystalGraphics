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
 * STATS.ask(pos, stats -> show(stats), error -> showError(error));           // client
 *
 * // the other way: the server asks one client
 * public static final CgRequest<Unit, Settings> SETTINGS =
 *         CgRequest.toClient("mymod:client/settings", Unit.CODEC, Settings.CODEC);
 * SETTINGS.onClient((unit, reply) -> reply.ok(localSettings()));             // client entry point
 * SETTINGS.ask(playerId, Unit.INSTANCE, settings -> apply(settings), error -> { });   // server
 * }</pre>
 *
 * <ul>
 *   <li>Every answer, error included, arrives on the asking side's game thread at its tick. Exactly one of the two
 *       callbacks runs, once.</li>
 *   <li>A reply may be given later than the handler returns; {@link Reply} is answered once and later calls are
 *       ignored.</li>
 *   <li>An error is a code from {@link CgProtocolErrors} or the handler's own text: {@link #UNSUPPORTED} when the
 *       peer lacks the namespace or speaks another version, {@code protocol/timeout} past {@link #timeout},
 *       {@code protocol/cancelled} after {@link Pending#cancel}.</li>
 *   <li>Mods mostly push: reach for a {@link CgMessage} unless the asker needs the answer.</li>
 * </ul>
 */
public final class CgRequest<A, R> {

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

    /** An ask in flight. */
    public static final class Pending {
        @Nullable
        private CgProtocolConnection<Object> connection;
        private int id = -1;
        private boolean done;
        @Nullable
        private Consumer<String> onError;

        /** Gives up: the error callback runs with {@code protocol/cancelled}, and a late answer is dropped. */
        public void cancel() {
            if (done) return;
            if (connection != null && id >= 0) {
                connection.router().cancel(id);   // runs onError through the router
                return;
            }
            done = true;
            if (onError != null) onError.accept(CgProtocolErrors.CANCELLED);
        }
    }

    /** On a client: asks the server. Fails with {@link #UNSUPPORTED} when not connected. */
    public Pending ask(A value, Consumer<R> onAnswer, Consumer<String> onError) {
        if (direction != Direction.TO_SERVER) throw new IllegalStateException(name + " is asked by the server");
        return ask(CgNetwork.client(), value, onAnswer, onError);
    }

    /** On the server: asks one player. Fails with {@link #UNSUPPORTED} when they have no connection. */
    public Pending ask(UUID player, A value, Consumer<R> onAnswer, Consumer<String> onError) {
        if (direction != Direction.TO_CLIENT) throw new IllegalStateException(name + " is asked by a client");
        return ask(CgNetwork.forPlayer(player), value, onAnswer, onError);
    }

    private Pending ask(@Nullable CgProtocolConnection<Object> connection, A value,
                        Consumer<R> onAnswer, Consumer<String> onError) {
        Objects.requireNonNull(onAnswer, "onAnswer");
        Objects.requireNonNull(onError, "onError");
        Pending pending = new Pending();
        pending.onError = error -> {
            if (pending.done) return;
            pending.done = true;
            onError.accept(error);
        };
        if (connection == null || connection.isClosed()) {
            pending.onError.accept(UNSUPPORTED);
            return pending;
        }
        Object encoded = args.encode(connection.ops(), value);
        CgHello.State peer = CgHello.state(connection);
        peer.whenKnown(() -> {
            if (pending.done) return;
            int theirs = peer.version(namespace);
            int ours = CgMessage.versionOf(namespace);
            if (theirs < 0 || theirs != ours) {
                if (theirs >= 0) peer.reportMismatch(namespace, ours, connection.peer());
                pending.onError.accept(UNSUPPORTED);
                return;
            }
            pending.connection = connection;
            pending.id = connection.router().request(name, encoded, payload -> {
                if (pending.done) return;
                R answer;
                try {
                    answer = result.decode(connection.ops(), payload);
                } catch (CgCodecException malformed) {
                    pending.onError.accept(CgProtocolErrors.HANDLER_FAILED + ": " + malformed.getMessage());
                    return;
                }
                pending.done = true;
                onAnswer.accept(answer);
            }, pending.onError, System.currentTimeMillis() + timeoutMillis);
        }, () -> pending.onError.accept(UNSUPPORTED));
        return pending;
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
