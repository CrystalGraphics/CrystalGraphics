package com.crystalgraphics.net;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import com.crystalgraphics.net.protocol.CgProtocolConnection;
import com.crystalgraphics.serialization.CgCodec;
import com.crystalgraphics.serialization.CgCodecException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A typed message between client and server: a name, a direction and a codec, declared once and read by both
 * sides. What a mod reaches for instead of writing packets.
 *
 * <pre>{@code
 * public record VfxPlay(String effect, double x, double y, double z, long seed) {
 *     public static final CgCodec<VfxPlay> CODEC = ...;   // a field per component
 * }
 *
 * public static final CgMessage<VfxPlay> PLAY = CgMessage.toClients("crystalgraphics:vfx/play", VfxPlay.CODEC);
 *
 * PLAY.send(CgAudience.all(), new VfxPlay("blast", x, y, z, seed));   // server
 * PLAY.onReceive(play -> CgVfx.play(play));                            // client, from client code
 * }</pre>
 *
 * <p>The other way, the handler is told who sent it:</p>
 *
 * <pre>{@code
 * public static final CgMessage<Purge> PURGE = CgMessage.toServer("mymod:machine/purge", Purge.CODEC);
 *
 * PURGE.sendToServer(new Purge(machineId));                    // client
 * PURGE.onReceiveFrom((peer, purge) -> purge(peer.id(), purge));   // server
 * }</pre>
 *
 * <ul>
 *   <li><b>Declare at mod init</b>, on both sides, as a {@code static final}. The hello tells each peer which
 *       namespaces this side declared when its connection opened; one declared later is sent to no peer that
 *       connected before it.</li>
 *   <li>A peer that lacks the namespace, or never says hello (no mod), is skipped and counted
 *       ({@link #skipped()}), never logged per send. A version mismatch ({@link #version}) is logged once per peer.</li>
 *   <li>Handlers run on the receiving side's game thread, at its tick. The client thread is the render thread.</li>
 *   <li>Install a client handler from client code, so a dedicated server never loads its classes.</li>
 *   <li>The sender of a {@code toServer} message is the connection's, never a field of the payload: a client that
 *       could name its sender could name another player.</li>
 * </ul>
 */
public final class CgMessage<T> {

    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphics");

    private static final Pattern NAME = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    private static final List<CgMessage<?>> DECLARED = new CopyOnWriteArrayList<>();
    private static final Map<String, Integer> VERSIONS = new ConcurrentHashMap<>();

    /** Which way a message may travel. A message arriving the other way is dropped as forged. */
    public enum Direction {
        TO_CLIENTS, TO_SERVER, BOTH;

        boolean toClients() {
            return this != TO_SERVER;
        }

        boolean toServer() {
            return this != TO_CLIENTS;
        }
    }

    private final String name;
    private final String namespace;
    private final Direction direction;
    private final CgCodec<T> codec;
    private final AtomicLong skipped = new AtomicLong();
    private final AtomicBoolean reportedMalformed = new AtomicBoolean();

    @Nullable
    private volatile Consumer<T> fromServer;
    @Nullable
    private volatile BiConsumer<CgPeer, T> fromClient;

    private CgMessage(String name, Direction direction, CgCodec<T> codec) {
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("A message name is namespace:path in lowercase, not '" + name + "'");
        }
        this.name = name;
        this.namespace = name.substring(0, name.indexOf(':'));
        this.direction = direction;
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    /** Sent by the server to clients. */
    public static <T> CgMessage<T> toClients(String name, CgCodec<T> codec) {
        return declare(new CgMessage<>(name, Direction.TO_CLIENTS, codec));
    }

    /** Sent by a client to the server. */
    public static <T> CgMessage<T> toServer(String name, CgCodec<T> codec) {
        return declare(new CgMessage<>(name, Direction.TO_SERVER, codec));
    }

    /** Sent either way. */
    public static <T> CgMessage<T> both(String name, CgCodec<T> codec) {
        return declare(new CgMessage<>(name, Direction.BOTH, codec));
    }

    /**
     * The version of {@code namespace}, 1 unless said here. Raise it when a message in the namespace changes shape
     * incompatibly; peers on another version skip each other's messages in it. Before any of them is declared.
     */
    public static void version(String namespace, int version) {
        VERSIONS.put(namespace, version);
    }

    private static <T> CgMessage<T> declare(CgMessage<T> message) {
        synchronized (DECLARED) {
            for (CgMessage<?> other : DECLARED) {
                if (other.name.equals(message.name)) {
                    throw new IllegalStateException("Message '" + message.name + "' is already declared");
                }
            }
            DECLARED.add(message);
        }
        return message;
    }

    // ── Receiving ───────────────────────────────────────────────────────────────────────────────

    /** On a client: what to do with one from the server. Replaces any earlier handler. */
    public CgMessage<T> onReceive(Consumer<T> handler) {
        if (!direction.toClients()) throw new IllegalStateException(name + " is never sent to clients");
        fromServer = handler;
        return this;
    }

    /** On the server: what to do with one from a client, told which. Replaces any earlier handler. */
    public CgMessage<T> onReceiveFrom(BiConsumer<CgPeer, T> handler) {
        if (!direction.toServer()) throw new IllegalStateException(name + " is never sent to the server");
        fromClient = handler;
        return this;
    }

    // ── Sending ─────────────────────────────────────────────────────────────────────────────────

    /** On the server: to each player in {@code audience} that has a connection. */
    public void send(CgAudience audience, T value) {
        if (!direction.toClients()) throw new IllegalStateException(name + " is never sent to clients");
        Encoded encoded = new Encoded(value);
        audience.forEach(id -> {
            CgProtocolConnection<Object> connection = CgNetwork.forPlayer(id);
            if (connection != null) sendTo(connection, encoded.on(connection));
        });
    }

    /** On a client: to the server, when connected; otherwise nothing. */
    public void sendToServer(T value) {
        if (!direction.toServer()) throw new IllegalStateException(name + " is never sent to the server");
        CgProtocolConnection<Object> connection = CgNetwork.client();
        if (connection != null) sendTo(connection, codec.encode(connection.ops(), value));
    }

    /** Sends skipped because the peer lacked the namespace, spoke another version or never said hello. */
    public long skipped() {
        return skipped.get();
    }

    public String name() {
        return name;
    }

    public Direction direction() {
        return direction;
    }

    /** Encoded once per send, at the call, so a codec failure is the sender's and a later change is not sent. */
    private final class Encoded {
        private final T value;
        @Nullable
        private Object tree;

        Encoded(T value) {
            this.value = value;
        }

        Object on(CgProtocolConnection<Object> connection) {
            if (tree == null) tree = codec.encode(connection.ops(), value);
            return tree;
        }
    }

    void sendTo(CgProtocolConnection<Object> connection, Object encoded) {
        if (connection.isClosed()) return;
        CgHello.State peer = CgHello.state(connection);
        peer.whenKnown(() -> deliver(connection, peer, encoded), skipped::incrementAndGet);
    }

    private void deliver(CgProtocolConnection<Object> connection, CgHello.State peer, Object encoded) {
        int theirs = peer.version(namespace);
        if (theirs < 0) {
            skipped.incrementAndGet();
            return;
        }
        int ours = versionOf(namespace);
        if (theirs != ours) {
            peer.reportMismatch(namespace, ours, connection.peer());
            skipped.incrementAndGet();
            return;
        }
        connection.router().notify(name, encoded);
    }

    private void receive(CgProtocolConnection<Object> connection, @Nullable Object payload) {
        boolean fromTheServer = connection.peer() == null;
        if (fromTheServer ? !direction.toClients() : !direction.toServer()) return;
        T value;
        try {
            value = codec.decode(connection.ops(), payload);
        } catch (CgCodecException malformed) {
            if (reportedMalformed.compareAndSet(false, true)) {
                LOGGER.warn("[cg-net] a malformed {} was dropped: {}", name, malformed.getMessage());
            }
            return;
        }
        if (fromTheServer) {
            Consumer<T> handler = fromServer;
            if (handler != null) handler.accept(value);
        } else {
            BiConsumer<CgPeer, T> handler = fromClient;
            if (handler != null && connection.peer() instanceof CgPeer) handler.accept((CgPeer) connection.peer(), value);
        }
    }

    // ── Wiring, from CgNetwork ──────────────────────────────────────────────────────────────────

    static int versionOf(String namespace) {
        return VERSIONS.getOrDefault(namespace, 1);
    }

    /** This side's namespaces and versions: what the hello says. */
    static Map<String, Integer> namespaces() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (CgMessage<?> message : DECLARED) out.put(message.namespace, versionOf(message.namespace));
        return out;
    }

    /** How many of {@link #DECLARED} a connection has bound. */
    private static final class Bound {
        int count;
    }

    /** Binds every declared message onto {@code connection}, and any declared later at its next tick. */
    static void bindAll(CgProtocolConnection<Object> connection) {
        Bound bound = connection.attachment(Bound.class, c -> new Bound());
        Runnable catchUp = () -> {
            while (bound.count < DECLARED.size()) {
                CgMessage<?> message = DECLARED.get(bound.count++);
                connection.router().onNotify(message.name, payload -> message.receive(connection, payload));
            }
        };
        catchUp.run();
        connection.onTick(catchUp);
    }

    /** Forgets every declaration. Tests only. */
    static void resetForTesting() {
        DECLARED.clear();
        VERSIONS.clear();
    }

    @Override
    public String toString() {
        return "CgMessage[" + name + " " + direction + "]";
    }
}
