package com.crystalgraphics.collision.debug;

import com.crystalgraphics.collision.CgCollisionQuery;
import com.crystalgraphics.collision.CgCollisionWorld;
import com.crystalgraphics.collision.hit.CgHit;
import com.crystalgraphics.collision.hitbox.CgHitbox;
import com.crystalgraphics.collision.hitbox.CgHitboxes;
import com.crystalgraphics.net.CgAudience;
import com.crystalgraphics.net.CgMessage;
import com.crystalgraphics.net.CgNetwork;
import com.crystalgraphics.net.CgPeer;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.world.CgHostDebug;
import com.crystalgraphics.platform.world.CgWorldQuery;
import com.crystalgraphics.serialization.CgCodecs;
import com.crystalgraphics.shape.CgPose;
import com.crystalgraphics.shape.CgShape;
import com.crystalgraphics.shape.bounds.CgAABBc;
import com.crystalgraphics.variable.CgVar;
import com.crystalgraphics.world.CgEntityQueries;
import com.crystalgraphics.world.CgWorldQueries;

import javax.annotation.Nullable;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code crystalgraphics.collision.show}: every live hitbox drawn see-through with its edges solid, and every world
 * query as lines, as Minecraft's F3+B draws entity boxes and PhysX's visual debugger draws scene queries. A client
 * draws the hitboxes it ticks and the queries it runs itself, and the server's.
 *
 * <pre>{@code
 * -Dcrystalgraphics.collision.show=true                 // or from the console, or CgCollisionShow.SHOW.set(true)
 * -Dcrystalgraphics.collision.serve=false               // a server keeping its own to itself
 * -Dcrystalgraphics.collision.style=wireframe           // hitboxes as their edges alone; transparent by default
 * -Dcrystalgraphics.collision.linger=0                  // a stopped hitbox gone with its last tick; 250 ms by default
 * }</pre>
 *
 * <ul>
 *   <li>The host's own hitbox view shows them too ({@link CgHostDebug}: F3+B in Minecraft).</li>
 *   <li>A server sends its lines to any player whose client is showing, recording only while one is, unless
 *       {@link #SERVE} is off; then only while its own {@link #SHOW} is on (an integrated server's follows its
 *       owner's view).</li>
 *   <li>A client's own hitboxes are green and a server's orange: where the two part, a prediction is off. A
 *       client's queries are blue, and red where they stop and what they hit; a server's are all orange. A hitbox
 *       with a {@link CgHitbox#showColour(int) show colour} is drawn in it on every side.</li>
 *   <li>A cast is drawn where it starts and where it stops, joined; a ray as its path; an overlap as its shape. A
 *       query a hitbox runs is left out, its parts drawn instead, as are an empty world's.</li>
 *   <li>A server sends a player what is within {@link #RANGE} of them, at most {@link #MAX_RECORDS} segments and
 *       triangles a tick, edges before faces, while their client keeps asking; faces only to a client drawing
 *       them.</li>
 *   <li>{@link #STYLE} is each client's own: {@link Style#TRANSPARENT} fills a hitbox's faces in under its edges,
 *       {@link Style#WIREFRAME} draws the edges alone. Queries are lines under both.</li>
 *   <li>Hitboxes are drawn as their last tick tested them, not between ticks. One that stops is held
 *       {@link #LINGER} longer, fading, so a blow that lives a tick is still seen; a server's lingers by its own.</li>
 * </ul>
 */
public final class CgCollisionShow {

    public static final CgVar.Bool SHOW = CgVar.bool("crystalgraphics.collision.show", false, CgVar.DEV,
            "Draws every live hitbox, see-through, and every world query, as lines, as Minecraft's F3+B also does:"
                    + " the client's and the server's.");
    public static final CgVar.Bool SERVE = CgVar.bool("crystalgraphics.collision.serve", true, CgVar.SAVED,
            "Lets a server send its hitboxes and queries to any player showing them (F3+B), recording only while one"
                    + " is. Off, it sends only while its own collision.show is on.");

    /** How a shown hitbox is drawn. */
    public enum Style {
        /** See-through faces under solid edges. */
        TRANSPARENT,
        /** Edges alone. */
        WIREFRAME
    }

    public static final CgVar.Choice<Style> STYLE = CgVar.choice("crystalgraphics.collision.style", Style.TRANSPARENT,
            CgVar.SAVED, "How this client draws the hitboxes it shows. transparent: see-through faces under solid"
                    + " edges. wireframe: the edges alone.");
    public static final CgVar.Int LINGER = CgVar.integer("crystalgraphics.collision.linger", 250, 0, 5000,
            CgVar.SAVED, "Milliseconds a stopped hitbox stays shown, fading out, so one that lived a tick is seen.");

    /** How far from a player a server's hitboxes are sent. */
    static final double RANGE = 64;
    /** The most segments and triangles a server sends a player a tick, and keeps of each. */
    static final int MAX_RECORDS = 4096;
    /** Client ticks between asks while showing: a server forgets a client after {@link #FORGET_AFTER}. */
    static final int ASK_EVERY = 100;
    static final long FORGET_AFTER = 3 * ASK_EVERY;
    /** A server's hitboxes and queries. */
    static final int SERVER = 0xFFFF9030;
    /** How long a server's last lines are drawn without a newer pack. */
    private static final long SERVER_HOLD_NANOS = 500_000_000L;

    /** What a client asks for: {@link #ASK_NONE}, {@link #ASK_EDGES} or {@link #ASK_FACES}, edges and faces. */
    static final CgMessage<Integer> ASK = CgMessage.toServer("crystalgraphics:collision/show", CgCodecs.INT);
    static final int ASK_NONE = 0, ASK_EDGES = 1, ASK_FACES = 2;
    static final CgMessage<byte[]> LINES = CgMessage.toClients("crystalgraphics:collision/lines", CgCodecs.BYTES);

    private static boolean installed;

    // Server thread.
    private static final Map<UUID, Watcher> WATCHERS = new ConcurrentHashMap<>();
    private static final CgLinePack PACK = new CgLinePack();
    private static final double[] AT = new double[3];
    private static final CgRecentLines SERVER_LINES = new CgRecentLines(MAX_RECORDS);
    /** Whose queries and hitboxes are a server's: an integrated server runs beside its client. */
    private static volatile Thread serverThread;

    // Client.
    private static final CgRecentLines CLIENT_LINES = new CgRecentLines(1 << 14);
    private static volatile boolean client;
    /** The client's host hitbox view, from its tick; false on a dedicated server. */
    private static volatile boolean host;
    private static int asked;
    private static int sinceAsked;
    private static volatile byte[] serverLines;
    private static volatile long serverAt;
    /** Render thread: whether the last frame showed, so the frame it stops clears. */
    private static boolean shown;

    private CgCollisionShow() {
    }

    private static final class Watcher {
        final CgPeer peer;
        long asked;
        boolean faces;

        Watcher(CgPeer peer) {
            this.peer = peer;
        }
    }

    /** Declares the messages and serves them on a server. From {@code CgNetwork.install}; once. */
    public static synchronized void install() {
        if (installed) return;
        installed = true;
        SHOW.onChange(on -> follow());
        SERVE.onChange(on -> follow());
        follow();
        ASK.onReceiveFrom(CgCollisionShow::asked);
        CgNetwork.onServerTick(CgCollisionShow::serve);
        CgNetwork.onPeerClosed(peer -> {
            WATCHERS.remove(peer.id());
            follow();
        });
    }

    /** The client half: records this process's lines, asks a server for its own. From {@link CgCollisionDebug#install}. */
    static synchronized void installClient() {
        install();
        if (client) return;
        client = true;
        CgNetwork.onClientTick(CgCollisionShow::ask);
        LINES.onReceive(pack -> {
            serverLines = pack;
            serverAt = System.nanoTime();
        });
    }

    /** Hands {@code out} what is shown now, the eye where the frame's camera is. Render thread, each world frame. */
    static void drawTo(double eyeX, double eyeY, double eyeZ, CgRecentLines.Out out) {
        // for what is written before the next frame: the outline lags the camera by a frame
        CLIENT_LINES.eye(eyeX, eyeY, eyeZ);
        boolean showing = showing();
        if (showing) {
            long now = System.nanoTime();
            CLIENT_LINES.drawTo(now, out);
            byte[] pack = serverLines;
            if (pack != null && now - serverAt < SERVER_HOLD_NANOS) CgLinePack.read(pack, SERVER, out);
        } else if (shown) {
            CLIENT_LINES.clear();
        }
        shown = showing;
    }

    /** Whether hitboxes are shown in this process: the variable, or the client's host view. */
    private static boolean showing() {
        return SHOW.get() || host;
    }

    /** Whether this process's server sends its lines to the players asking. */
    private static boolean serving() {
        return SERVE.get() || showing();
    }

    /** Records while this process shows, or while its server serves someone asking. */
    private static void follow() {
        boolean on = showing() || SERVE.get() && !WATCHERS.isEmpty();
        CgHitboxes.watch(on ? CgCollisionShow::saw : null);
        CgCollisionQuery.watch(on ? CgCollisionShow::ran : null);
    }

    private static void ran(@Nullable CgShape shape, CgPose pose, @Nullable CgAABBc box, double dx, double dy,
                            double dz, double length, CgWorldQuery level, @Nullable CgHit nearest) {
        CgRecentLines into = lines();
        if (into != null) into.query(shape, pose, box, dx, dy, dz, length, level, nearest, System.nanoTime());
    }

    private static void saw(CgHitbox hitbox, CgCollisionWorld world) {
        CgRecentLines into = lines();
        if (into != null) into.hitbox(hitbox, world.blocks(), System.nanoTime(), LINGER.get() * 1_000_000L);
    }

    /** Where this thread's queries and hitboxes are kept: a server's only while a player watches. */
    private static @Nullable CgRecentLines lines() {
        if (Thread.currentThread() != serverThread) return client ? CLIENT_LINES : null;
        return WATCHERS.isEmpty() ? null : SERVER_LINES;
    }

    /** Follows the host's view, and keeps the server told while showing and once when it stops. Client thread. */
    private static void ask() {
        boolean view = CgPlatform.get(CgHostDebug.SERVICE).hitboxes();
        if (view != host) {
            host = view;
            follow();
        }
        int want = !showing() ? ASK_NONE : STYLE.get() == Style.TRANSPARENT ? ASK_FACES : ASK_EDGES;
        if (want == asked && (want == ASK_NONE || ++sinceAsked < ASK_EVERY)) return;
        ASK.sendToServer(want);
        asked = want;
        sinceAsked = 0;
    }

    private static void asked(CgPeer peer, Integer want) {
        if (want == ASK_NONE) {
            WATCHERS.remove(peer.id());
        } else {
            Watcher w = WATCHERS.computeIfAbsent(peer.id(), id -> new Watcher(peer));
            w.asked = CgNetwork.serverTicks();
            w.faces = want == ASK_FACES;
        }
        follow();
    }

    private static void serve() {
        serverThread = Thread.currentThread();
        if (WATCHERS.isEmpty() || !serving()) {
            SERVER_LINES.clear();
            return;
        }
        long nanos = System.nanoTime();
        SERVER_LINES.expire(nanos);
        long now = CgNetwork.serverTicks();
        for (Iterator<Watcher> it = WATCHERS.values().iterator(); it.hasNext(); ) {
            Watcher w = it.next();
            if (now - w.asked > FORGET_AFTER) {
                it.remove();
                if (WATCHERS.isEmpty()) follow();
                continue;
            }
            Object player = w.peer.player();
            if (player == null || !CgEntityQueries.position(player, AT)) continue;
            CgWorldQuery level = CgWorldQueries.of(player);
            PACK.begin(Math.floor(AT[0]), Math.floor(AT[1]), Math.floor(AT[2]), MAX_RECORDS);
            SERVER_LINES.packTo(level, AT[0], AT[1], AT[2], RANGE, w.faces, SERVER, nanos, PACK);
            LINES.send(CgAudience.player(w.peer.id()), PACK.finish());
        }
    }
}
