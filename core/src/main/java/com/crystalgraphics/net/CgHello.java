package com.crystalgraphics.net;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import com.crystalgraphics.net.protocol.CgProtocolConnection;
import com.crystalgraphics.serialization.CgStateMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * What the far end of one connection speaks: its CrystalGraphics protocol version and its message namespaces.
 *
 * <p>The client says {@code cg/hello} as its connection opens, and again every {@link #RESEND_TICKS} until the
 * server answers with its own: a first one can arrive before the server has opened that player's connection, and
 * is dropped there. The server only ever answers, so a client without the mod is sent nothing. Typed sends wait
 * here until the peer is known, then go or are skipped; a peer silent for {@link #SILENT_TICKS} is taken to have
 * none.</p>
 */
final class CgHello {

    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphics");

    static final String METHOD = "cg/hello";

    /** Bumped when the envelope, the hello or the message framing changes. */
    static final int PROTOCOL = 1;

    /** Ten seconds at 20 ticks: a peer with the mod answers within a few. */
    static final int SILENT_TICKS = 200;

    /** How often a client says hello again while unanswered. */
    static final int RESEND_TICKS = 20;

    /** Sends waiting on the hello, per connection. Past it the oldest is dropped. */
    static final int MAX_WAITING = 256;

    private static final String KEY_PROTOCOL = "p";
    private static final String KEY_NAMESPACES = "n";

    /** One connection's view of its peer. Touched only on the connection's own thread. */
    static final class State {
        private Map<String, Integer> remote = Collections.emptyMap();
        private boolean known;
        private boolean absent;
        private int ticksWaited;
        private final ArrayDeque<Runnable[]> waiting = new ArrayDeque<>();
        private final Set<String> reported = new HashSet<>();

        boolean known() {
            return known;
        }

        boolean absent() {
            return absent;
        }

        /** The version of {@code namespace} the peer declared, or -1. */
        int version(String namespace) {
            Integer v = remote.get(namespace);
            return v == null ? -1 : v;
        }

        /**
         * Runs {@code send} once the peer is known, or {@code dropped} if it never says hello or the queue
         * overflows first.
         */
        void whenKnown(Runnable send, Runnable dropped) {
            if (known) {
                send.run();
            } else if (absent) {
                dropped.run();
            } else {
                if (waiting.size() >= MAX_WAITING) waiting.pollFirst()[1].run();
                waiting.addLast(new Runnable[]{send, dropped});
            }
        }

        /** Logs a namespace version mismatch once per peer and namespace. */
        void reportMismatch(String namespace, int ours, Object peer) {
            if (reported.add(namespace)) {
                LOGGER.warn("[cg-net] {} speaks {} version {}, this side {}: its messages are skipped",
                        peer == null ? "the server" : peer, namespace, version(namespace), ours);
            }
        }

        private void learned(Map<String, Integer> namespaces) {
            remote = namespaces;
            known = true;
            absent = false;
            while (!waiting.isEmpty()) waiting.pollFirst()[0].run();
        }

        private void giveUp() {
            absent = true;
            while (!waiting.isEmpty()) waiting.pollFirst()[1].run();
        }
    }

    private CgHello() {
    }

    static State state(CgProtocolConnection<Object> connection) {
        return connection.attachment(State.class, c -> new State());
    }

    /**
     * Installs the hello on {@code connection}: the client says it until answered, the server answers each.
     *
     * @param local this side's namespaces and versions, read when a hello is made
     */
    static void bind(CgProtocolConnection<Object> connection, Supplier<Map<String, Integer>> local) {
        State state = state(connection);
        boolean client = connection.peer() == null;
        connection.onNotify(METHOD, hello -> {
            state.learned(read(hello));
            if (!client) connection.notify(METHOD, describe(connection, local.get()));
        });
        if (client) connection.notify(METHOD, describe(connection, local.get()));
        connection.onTick(() -> {
            if (state.known || state.absent) return;
            state.ticksWaited++;
            if (state.ticksWaited >= SILENT_TICKS) {
                state.giveUp();
            } else if (client && state.ticksWaited % RESEND_TICKS == 0) {
                connection.notify(METHOD, describe(connection, local.get()));
            }
        });
    }

    private static CgStateMap<Object> describe(CgProtocolConnection<Object> connection, Map<String, Integer> local) {
        return new CgStateMap<>(connection.ops())
                .putInt(KEY_PROTOCOL, PROTOCOL)
                .putList(KEY_NAMESPACES, new ArrayList<>(local.entrySet()),
                        (out, entry) -> out.putString("n", entry.getKey()).putInt("v", entry.getValue()));
    }

    private static Map<String, Integer> read(CgStateMap<Object> in) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (CgStateMap<Object> entry : in.getList(KEY_NAMESPACES, entry -> entry)) {
            String name = entry.getString("n", null);
            if (name != null) out.put(name, entry.getInt("v", -1));
        }
        return out;
    }
}
