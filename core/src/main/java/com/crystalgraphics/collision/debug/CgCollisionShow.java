package com.crystalgraphics.collision.debug;

import com.crystalgraphics.collision.CgHitPart;
import com.crystalgraphics.collision.hitbox.CgHitbox;
import com.crystalgraphics.collision.query.CgCollisionWorld;
import com.crystalgraphics.collision.query.CgHit;
import com.crystalgraphics.collision.query.CgQuery;
import com.crystalgraphics.net.CgAudience;
import com.crystalgraphics.net.CgMessage;
import com.crystalgraphics.net.CgNetwork;
import com.crystalgraphics.net.CgPeer;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.service.CgHostDebug;
import com.crystalgraphics.platform.service.CgLevelQueries;
import com.crystalgraphics.platform.service.CgServerPlayers;
import com.crystalgraphics.platform.service.CgWorldQuery;
import com.crystalgraphics.serialization.CgCodecs;
import com.crystalgraphics.shape.CgPose;
import com.crystalgraphics.shape.CgShape;
import com.crystalgraphics.shape.bounds.CgAABBc;
import com.crystalgraphics.variable.CgVar;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code crystalgraphics.collision.show}: every live hitbox and every world query drawn as lines, as Minecraft's F3+B
 * draws entity boxes and PhysX's visual debugger draws scene queries. A client draws the hitboxes it ticks and the
 * queries it runs itself, and those of a server whose own variable is on (an integrated server shares its client's).
 *
 * <pre>{@code
 * -Dcrystalgraphics.collision.show=true                 // or from the console, or CgCollisionShow.SHOW.set(true)
 * }</pre>
 *
 * <ul>
 *   <li>The host's own hitbox view shows them too ({@link CgHostDebug}: F3+B in Minecraft), and an integrated server
 *       takes its owner's as its own switch. A dedicated server sends only while its variable is on.</li>
 *   <li>A client's own hitboxes are green and a server's orange: where the two part, a prediction is off. A
 *       client's queries are blue, and red where they stop and what they hit; a server's are all orange.</li>
 *   <li>A cast is drawn where it starts and where it stops, joined; a ray as its path; an overlap as its shape. A
 *       query a hitbox runs, or one marked {@link CgQuery#unwatched}, is left out, as are a {@code CgShapeWorld}'s.</li>
 *   <li>A server sends a player what is within {@link #RANGE} of them, at most {@link #MAX_SEGMENTS} segments a
 *       tick, while their client keeps asking.</li>
 *   <li>Hitboxes are drawn as their last tick tested them, not between ticks, and for half a second once stopped: a
 *       blast's front lives a few ticks.</li>
 * </ul>
 */
public final class CgCollisionShow {

    public static final CgVar.Bool SHOW = CgVar.bool("crystalgraphics.collision.show", false, CgVar.DEV,
            "Draws every live hitbox and world query as lines, as Minecraft's F3+B also does: the client's, and a"
                    + " server's whose own is on.");

    /** How far from a player a server's hitboxes are sent. */
    public static final double RANGE = 64;
    /** The most segments a server sends a player a tick. */
    public static final int MAX_SEGMENTS = 4096;
    /** Client ticks between asks while showing: a server forgets a client after {@link #FORGET_AFTER}. */
    static final int ASK_EVERY = 100;
    static final long FORGET_AFTER = 3 * ASK_EVERY;

    static final CgMessage<Boolean> ASK = CgMessage.toServer("crystalgraphics:collision/show", CgCodecs.BOOL);
    static final CgMessage<byte[]> LINES = CgMessage.toClients("crystalgraphics:collision/lines", CgCodecs.BYTES);

    /** The hitboxes each thread ticked while showing: a client's drawn each frame, a server's sent each tick. */
    private static final ThreadLocal<Set<CgHitbox>> SEEN =
            ThreadLocal.withInitial(() -> Collections.newSetFromMap(new IdentityHashMap<>()));
    private static final int PURGE_ABOVE = 256;

    private static final Map<UUID, Watcher> WATCHERS = new ConcurrentHashMap<>();
    private static boolean installed;
    /** The client's host hitbox view, from its tick; false on a dedicated server. */
    private static volatile boolean host;

    // Server thread.
    private static final CgShapeLines LINES_OUT = new CgShapeLines();
    private static final CgLinePack PACK = new CgLinePack();
    private static final double[] AT = new double[3];
    private static final CgQueryLines SERVER_QUERIES = new CgQueryLines(MAX_SEGMENTS);
    private static final CgHitboxLinger SERVER_LINGER = new CgHitboxLinger();
    /** Whose queries are a server's: an integrated server runs beside its client. */
    private static volatile Thread serverThread;
    private static volatile @Nullable CgQueryLines clientQueries;

    private CgCollisionShow() {
    }

    private static final class Watcher {
        final CgPeer peer;
        long asked;

        Watcher(CgPeer peer) {
            this.peer = peer;
        }
    }

    /**
     * Declares the messages and serves them on a server. From {@code CgNetwork.install} and
     * {@link CgCollisionDebug#install}; once.
     */
    public static synchronized void install() {
        if (installed) return;
        installed = true;
        SHOW.onChange(on -> follow());
        follow();
        ASK.onReceiveFrom(CgCollisionShow::asked);
        CgNetwork.onServerTick(CgCollisionShow::serve);
        CgNetwork.onPeerClosed(peer -> WATCHERS.remove(peer.id()));
    }

    /** Where a client keeps the queries it runs: from {@link CgCollisionDebug#install}. */
    static void clientQueries(CgQueryLines into) {
        clientQueries = into;
    }

    /** This thread's hitboxes seen since showing began, the stopped among them included. */
    static Set<CgHitbox> seen() {
        return SEEN.get();
    }

    /** Whether hitboxes are shown in this process: the variable, or the client's host view. */
    static boolean showing() {
        return SHOW.get() || host;
    }

    /** Takes the host's hitbox view. Client thread, each tick. */
    static void followHost() {
        boolean now = CgPlatform.get(CgHostDebug.SERVICE).hitboxes();
        if (now == host) return;
        host = now;
        follow();
    }

    private static void follow() {
        boolean on = showing();
        CgHitbox.watch(on ? CgCollisionShow::saw : null);
        CgQuery.watch(on ? CgCollisionShow::ran : null);
    }

    private static void ran(@Nullable CgShape shape, CgPose pose, @Nullable CgAABBc box, double dx, double dy,
                            double dz, double length, CgWorldQuery level, @Nullable CgHit nearest) {
        CgQueryLines into = Thread.currentThread() != serverThread ? clientQueries
                : WATCHERS.isEmpty() ? null : SERVER_QUERIES;
        if (into != null) into.add(shape, pose, box, dx, dy, dz, length, level, nearest, System.nanoTime());
    }

    private static void saw(CgHitbox hitbox) {
        Set<CgHitbox> seen = SEEN.get();
        if (seen.add(hitbox) && seen.size() > PURGE_ABOVE) seen.removeIf(CgHitbox::stopped);
    }

    private static void asked(CgPeer peer, Boolean on) {
        if (!on) {
            WATCHERS.remove(peer.id());
            return;
        }
        WATCHERS.computeIfAbsent(peer.id(), id -> new Watcher(peer)).asked = CgNetwork.serverTicks();
    }

    private static void serve() {
        serverThread = Thread.currentThread();
        Set<CgHitbox> seen = SEEN.get();
        if (WATCHERS.isEmpty() || !showing()) {
            seen.clear();
            SERVER_LINGER.clear();
            SERVER_QUERIES.clear();
            return;
        }
        long nanos = System.nanoTime();
        SERVER_LINGER.take(seen, nanos);
        int lingering = SERVER_LINGER.expire(nanos);
        long now = CgNetwork.serverTicks();
        CgServerPlayers players = CgPlatform.get(CgServerPlayers.SERVICE);
        CgLevelQueries levels = CgPlatform.get(CgLevelQueries.SERVICE);
        for (Iterator<Watcher> it = WATCHERS.values().iterator(); it.hasNext(); ) {
            Watcher w = it.next();
            if (now - w.asked > FORGET_AFTER) {
                it.remove();
                continue;
            }
            Object player = w.peer.player();
            if (player == null || !players.position(player, AT)) continue;
            CgWorldQuery level = levels.world(player);
            PACK.begin(Math.floor(AT[0]), Math.floor(AT[1]), Math.floor(AT[2]), MAX_SEGMENTS);
            for (CgHitbox hitbox : seen) packHitbox(hitbox, level);
            for (int i = 0; i < lingering; i++) packHitbox(SERVER_LINGER.get(i), level);
            SERVER_QUERIES.packTo(level, AT[0], AT[1], AT[2], RANGE, PACK);
            LINES.send(CgAudience.player(w.peer.id()), PACK.finish());
        }
        SERVER_QUERIES.clear();
    }

    private static void packHitbox(CgHitbox hitbox, CgWorldQuery level) {
        CgCollisionWorld world = hitbox.world();
        if (world != null && world.blocks() == level && !PACK.full()) hitbox.parts(CgCollisionShow::packPart);
    }

    private static void packPart(CgHitPart name, CgShape shape, CgPose pose) {
        double dx = pose.x - AT[0], dy = pose.y - AT[1], dz = pose.z - AT[2];
        if (dx * dx + dy * dy + dz * dz > RANGE * RANGE) return;
        LINES_OUT.write(shape, pose, PACK);
    }
}
