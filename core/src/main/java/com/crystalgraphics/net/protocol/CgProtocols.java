package com.crystalgraphics.net.protocol;

import com.crystalgraphics.net.CgTransport;
import com.crystalgraphics.serialization.CgDynamicOps;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Where a subsystem says "I speak part of this protocol" — the one obvious place to register, and the
 * thing the protocol layer was missing.
 *
 * <p>The layers below this were already general: {@link CgEnvelope} carries a method string and an opaque
 * payload, {@link CgMessageRouter} dispatches on the string, and neither names a UI type. What did not
 * exist was anywhere to <em>put</em> a handler: a router was reachable only by constructing a
 * {@code ServerUiSession}, so a workspace, a script runtime, or anything else on a dedicated server had a
 * protocol it could speak and no seat at it.</p>
 *
 * <h3>Two halves, after {@code ScriptRuntimes}</h3>
 *
 * <p>Deliberately the same shape as the language layer's registry, because it is the same problem: what
 * the shell can do is not something the shell can know. The <b>static</b> half is a set of contributors,
 * added once from a subsystem's own {@code register()}. The <b>instance</b> half is
 * {@link CgProtocolConnection}, one per peer, built by {@link #open} with every contributor bound onto it.
 * <b>Adds, never replaces</b>, so registration order does not matter and two subsystems cannot evict each
 * other.</p>
 *
 * <h3>Why the registry is global and a router is not</h3>
 *
 * <p>CustomNPC+'s {@code PacketHandler} maps a packet type to a handler and is a singleton, which works
 * because a packet carries everything needed to act on it. A router holds pending requests correlated to
 * <em>one peer</em>, so it cannot be shared: two players with a call in flight would collide on ids. So
 * what is global is the list of contributors, and what is per-connection is everything stateful. A
 * subsystem still registers exactly once, at init, and never thinks about connections again.</p>
 *
 * <pre>{@code
 * // once, at mod init -- the whole registration, sided at the call site:
 * CgProtocols.server("mymod", wire ->
 *         wire.onNotify("mymod/open", payload -> ServerWindows.of(wire).open(MyPanel.TYPE, model)));
 *
 * // per connection, wherever a peer appears
 * CgProtocolConnection<Object> connection = CgProtocols.open(transport, CgPlainOps.INSTANCE, wire::pump, player);
 * // every tick
 * connection.tick();
 * }</pre>
 *
 * <h3>Sided at the call site</h3>
 *
 * <p>{@link #server} binds only where the connection has a peer; {@link #client} only where it does
 * not; {@link #contribute} binds everywhere. The old shape made every server contributor open with
 * {@code if (connection.peer() == null) return;} — the most important fact about the contributor,
 * buried in a guard the reader had to decode. Now it is the method name. One mod may register under
 * one name on both sides: they are two halves of one protocol.</p>
 *
 * <h3>Why {@code bind} takes {@code CgProtocolConnection<Object>}</h3>
 *
 * <p>It used to be a generic method — {@code <T> void bind(CgProtocolConnection<T>)} — on the argument
 * that a contributor should serve a connection of any ops. Every contributor that ever existed
 * immediately cast to {@code CgProtocolConnection<Object>} with a {@code @SuppressWarnings}: the
 * genericity forced an anonymous class on every mod and was discarded by its own first line. The cast
 * is sound for the reason {@code WindowProtocol} has always stated — every {@code CgStateMap} takes its
 * ops from {@code connection.ops()}, never from a hardcoded instance, so erasure cannot mis-encode —
 * and it now lives in {@link #open}, once, instead of in every mod. A contributor is a lambda.</p>
 *
 * <h3>Method names are namespaced, and that is the whole collision story</h3>
 *
 * <p>{@code ui/*}, {@code workspace/*}, {@code mymod/*} — LSP's convention, already documented on
 * {@code UiMethods}. Two subsystems on one connection cannot collide unless they choose the same prefix,
 * and {@link CgMessageRouter} refuses a duplicate registration outright rather than letting the second win
 * silently. Contribute under your modid and prefix your methods with it.</p>
 */
public final class CgProtocols {
    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphics");


    /**
     * Binds one subsystem onto a connection. A lambda — one per subsystem, written once at init.
     */
    @FunctionalInterface
    public interface Contributor {
        void bind(CgProtocolConnection<Object> connection);
    }

    /** Which ends of the wire a contributor binds to. */
    private enum Side {
        EVERY,
        SERVER,
        CLIENT;

        boolean matches(@Nullable Object peer) {
            if (this == EVERY) return true;
            return this == SERVER ? peer != null : peer == null;
        }
    }

    private record Registered(String name, Side side, Contributor contributor) {
    }

    /** Keyed (name, side) so a double-register is visible; insertion-ordered so binding is reproducible. */
    private static final Map<String, Registered> CONTRIBUTORS = new LinkedHashMap<>();

    /** Whether any connection has been opened — after which a new contribution is (loudly) late. */
    private static boolean opened;

    private CgProtocols() {
    }

    /**
     * Says a subsystem speaks part of the protocol, on <b>both</b> ends of the wire. Called by its own
     * {@code register()}, once. Prefer {@link #server}/{@link #client} when only one side serves.
     *
     * @param name        what is contributing, for diagnostics — a modid: {@code "workspace"}, {@code "mymod"}
     * @param contributor invoked for every connection opened <b>after</b> this call — binding happens
     *                    in {@link #open}, so a connection that already exists does not get it. That is
     *                    why contribution belongs at mod init, before any peer can have arrived, and why
     *                    this is not a hot-swap mechanism
     * @throws IllegalStateException if {@code name} is already registered for these ends, which is a
     *                               wiring mistake rather than something to resolve silently
     */
    public static void contribute(String name, Contributor contributor) {
        add(name, Side.EVERY, contributor);
    }

    /** Like {@link #contribute}, binding only where the connection has a peer — the server's ends. */
    public static void server(String name, Contributor contributor) {
        add(name, Side.SERVER, contributor);
    }

    /** Like {@link #contribute}, binding only where the connection has no peer — the client's end. */
    public static void client(String name, Contributor contributor) {
        add(name, Side.CLIENT, contributor);
    }

    private static synchronized void add(String name, Side side, Contributor contributor) {
        if (name == null || name.isEmpty()) throw new IllegalArgumentException("a contributor needs a name");
        if (contributor == null) throw new IllegalArgumentException("contributor is null");
        Registered previous = CONTRIBUTORS.putIfAbsent(name + '@' + side, new Registered(name, side, contributor));
        if (previous != null) {
            throw new IllegalStateException("'" + name + "' already contributes to the protocol");
        }
        if (opened) {
            // The one silent failure this layer had left: a late contributor binds to nothing that
            // already exists, and "my handler never runs" points everywhere except at init order.
            LOGGER.warn("'{}' contributed after connections were already open; "
                    + "those connections do not get it — contribute at mod init", name);
        }
        LOGGER.debug("Protocol contributor registered: {} ({})", name, side);
    }

    /** What has registered. Diagnostics — the answer to "is my subsystem actually wired". */
    public static synchronized Set<String> contributors() {
        Set<String> names = new LinkedHashSet<>();
        for (Registered entry : CONTRIBUTORS.values()) names.add(entry.name);
        return names;
    }

    /**
     * Opens a connection to one peer and binds every matching contributor onto it.
     *
     * @param transport the encoded-message transport for this peer
     * @param ops       its representation
     * @param pump      moves bytes underneath — {@code wireTransport::pump}, or a no-op in memory. Called
     *                  by {@link CgProtocolConnection#tick()}, so nothing has to remember it separately
     * @param peer      the platform's player handle, or {@code null} on a client
     */
    public static synchronized <T> CgProtocolConnection<T> open(
            CgTransport<T> transport, CgDynamicOps<T> ops, Runnable pump, @Nullable Object peer) {
        CgProtocolConnection<T> connection = new CgProtocolConnection<>(transport, ops, pump, peer);
        opened = true;
        // The ONE unchecked cast, here instead of in every mod. Sound by the ops discipline: every
        // CgStateMap a contributor builds takes its ops from connection.ops(), so erasure cannot
        // mis-encode. @see the class javadoc
        @SuppressWarnings("unchecked")
        CgProtocolConnection<Object> wire = (CgProtocolConnection<Object>) connection;
        for (Registered entry : CONTRIBUTORS.values()) {
            if (!entry.side.matches(peer)) continue;
            try {
                entry.contributor.bind(wire);
            } catch (RuntimeException failed) {
                // One broken contributor must not cost the connection every other subsystem on it.
                LOGGER.error("Protocol contributor '{}' failed to bind: {}",
                        entry.name, failed.getMessage(), failed);
            }
        }
        return connection;
    }

    /** Drops every contributor. <b>Tests only</b> — a live process registers once and never unregisters. */
    public static synchronized void resetForTesting() {
        CONTRIBUTORS.clear();
        opened = false;
    }
}
