package com.crystalgraphics.net.object;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.crystalgraphics.net.CgAudience;
import com.crystalgraphics.net.CgMessage;
import com.crystalgraphics.net.CgNetwork;
import com.crystalgraphics.net.CgPeer;
import com.crystalgraphics.net.protocol.CgProtocolConnection;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.service.CgProcessExit;
import com.crystalgraphics.platform.world.CgEntityQuery;
import com.crystalgraphics.platform.world.CgWorldStimulus;
import com.crystalgraphics.serialization.CgCodecs;
import com.crystalgraphics.variable.CgVar;
import com.crystalgraphics.world.CgEntityQueries;
import com.crystalgraphics.world.CgPlayers;

/**
 * Proves, in a running game, what networked objects do across a player's death: a player's mana kept, a buff without
 * {@code copyOnDeath} ended, a turret on the player's body ended with it, a chunk's object untouched, and every client
 * seeing the same. It also times how late a change reaches each client, which shows where the host pumps the frame.
 *
 * <pre>{@code
 * // single player (a dev client's autotest, prodSmoke): the probe kills and respawns the player itself
 * -Dcrystalgraphics.netprobe=true
 * // a dedicated server and two clients, every process with the flag; the server waits for both, then names its victim
 * -Dcrystalgraphics.netprobe=true -Dcrystalgraphics.netprobe.players=2 -Dcrystalgraphics.netprobe.victim=Alice
 * // and something must kill them there (the console, RCON): the server logs when
 * net probe: kill Alice now
 * // what it logs, the server's side, and what prodSmoke reads:
 * net probe pool.kept: true (Alice's mana 1000 on the same object)
 * net probe seen.pool Bob: true (sees 1000)
 * net probe: true (14 passed, 0 failed)
 * }</pre>
 *
 * <ul>
 *   <li>The clients respawn themselves ({@link CgWorldStimulus#respawn}); a dedicated server cannot kill, so the victim
 *       waits up to a minute for {@code kill <name>}.</li>
 *   <li>{@link #running()} is a client's: an autotest waits for it before quitting.</li>
 *   <li>{@code .exit=true} ends every process once the verdict is out.</li>
 * </ul>
 */
public final class CgNetProbe {

    public static final boolean ENABLED = CgVar.bool("crystalgraphics.netprobe", false, CgVar.DEV | CgVar.RESTART,
            "Prove in a running game what networked objects do across a player's death, and how late a change arrives,"
                    + " logging a verdict per check for prodSmoke and a live server.").get();
    private static final int PLAYERS = CgVar.integer("crystalgraphics.netprobe.players", 1, 1, 16, CgVar.DEV | CgVar.RESTART,
            "How many players the net probe waits for before it starts.").get();
    private static final String VICTIM = CgVar.text("crystalgraphics.netprobe.victim", "", CgVar.DEV | CgVar.RESTART,
            "The player the net probe kills, by name; the first to join unless said.").get();
    private static final boolean EXIT = CgVar.bool("crystalgraphics.netprobe.exit", false, CgVar.DEV | CgVar.RESTART,
            "End every process once the net probe's verdict is out.").get();

    private static final Logger LOGGER = LogManager.getLogger("CgNetProbe");

    /** Declared at install only: a declaration is in every connection's hello. */
    private static final class Net {
        static final CgNetField<Integer> VALUE = CgNetField.ofInt("value");
        static final CgNetField<Long> SENT = CgNetField.ofLong("sent");

        static final CgNetType POOL = CgNetType.define("cgprobe:pool").fields(VALUE).perHolder().copyOnDeath()
                .visibleToTrackers().build();
        static final CgNetType BUFF = CgNetType.define("cgprobe:buff").fields(VALUE).perHolder().build();
        static final CgNetType TURRET = CgNetType.define("cgprobe:turret").fields(VALUE).build();
        static final CgNetType SOIL = CgNetType.define("cgprobe:soil").fields(VALUE).perHolder().build();
        static final CgNetType CLOCK = CgNetType.define("cgprobe:clock").fields(SENT).build();

        static final CgMessage<String> STEP = CgMessage.toClients("cgprobe:step", CgCodecs.STRING);
        static final CgMessage<String> SEEN = CgMessage.toServer("cgprobe:seen", CgCodecs.STRING);
    }

    /** Each player's objects carry these plus the player's index, so a client's report names whose it saw. */
    private static final int POOL_BASE = 1000, TURRET_BASE = 2000, SOIL_BASE = 3000, BUFF_BASE = 4000;

    private static boolean installed;
    private static final Client client = new Client();

    private CgNetProbe() {
    }

    /** Both halves; each runs only where its side does. Once, from {@code CgNetwork.install}. */
    public static synchronized void install() {
        if (installed) return;
        installed = true;
        Server server = new Server();
        CgNetwork.onServerTick(server::tick);
        Net.SEEN.onReceiveFrom(server::seen);
        client.install();
        LOGGER.info("net probe: armed, waiting for {} player(s)", PLAYERS);
    }

    /** True on a client from its connection to the verdict, two minutes at most: an autotest that quits now loses it. */
    public static boolean running() {
        return ENABLED && client.running();
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    private static void exitLater(String why) {
        if (!EXIT) return;
        LOGGER.info("net probe: exiting, {}", why);
        Thread exit = new Thread(() -> {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            CgPlatform.get(CgProcessExit.SERVICE).halt(0);
        }, "net probe exit");
        exit.setDaemon(true);
        exit.start();
    }

    private enum Phase { WAIT, BEFORE, DYING, AFTER, DONE }

    // ── The server ──────────────────────────────────────────────────────────────────────────────

    private static final class Server {
        static final int SETTLE = 100, STEADY = 100, LOOK = 60, BACK_LIMIT = 200, DEATH_LIMIT = 60 * 20;

        final List<UUID> ids = new ArrayList<>();
        final Map<UUID, String> names = new LinkedHashMap<>();
        final Map<String, String> reports = new LinkedHashMap<>();
        final Map<String, Long> reportedAt = new LinkedHashMap<>();
        final List<CgNetObject> turrets = new ArrayList<>();
        Phase phase = Phase.WAIT;
        int ticks, passed, failed, victim;
        UUID victimId;
        Object victimBody;
        CgNetObject pool, buff, soil, clock;
        long respawnedAt;

        void tick() {
            if (phase == Phase.DONE) return;
            if (clock != null) clock.setLong(Net.SENT, now());
            switch (phase) {
                case WAIT: waitForPlayers(); break;
                case BEFORE: before(); break;
                case DYING: dying(); break;
                case AFTER: after(); break;
                default: break;
            }
            ticks++;
        }

        void enter(Phase next) {
            phase = next;
            ticks = 0;
        }

        void waitForPlayers() {
            ids.clear();
            CgNetwork.forEachPlayer(ids::add);
            int ready = 0;
            for (UUID id : ids) {
                CgProtocolConnection<Object> connection = CgNetwork.forPlayer(id);
                if (connection != null && CgNetwork.helloKnown(connection) && connection.peer() instanceof CgPeer
                        && ((CgPeer) connection.peer()).player() != null) {
                    ready++;
                }
            }
            if (ready < PLAYERS) {
                ticks = -1;
                return;
            }
            if (ticks < SETTLE) return;
            for (int i = 0; i < ids.size(); i++) {
                UUID id = ids.get(i);
                String name = ((CgPeer) CgNetwork.forPlayer(id).peer()).name();
                names.put(id, name);
                if (name.equals(VICTIM) || (VICTIM.isEmpty() && i == 0)) victim = i;
                Object body = CgPlayers.player(id);
                double[] at = new double[3];
                CgEntityQueries.position(body, at);
                CgNetObject p = Net.POOL.of(id).setInt(Net.VALUE, POOL_BASE + i);
                CgNetObject b = Net.BUFF.of(id).setInt(Net.VALUE, BUFF_BASE + i);
                CgNetObject s = Net.SOIL.ofChunk(body, (int) Math.floor(at[0]) >> 4, (int) Math.floor(at[2]) >> 4)
                        .setInt(Net.VALUE, SOIL_BASE + i);
                turrets.add(Net.TURRET.spawn(CgAnchor.on(body)).setInt(Net.VALUE, TURRET_BASE + i).start());
                if (i == victim) {
                    pool = p;
                    buff = b;
                    soil = s;
                }
            }
            victimId = ids.get(victim);
            victimBody = CgPlayers.player(victimId);
            clock = Net.CLOCK.spawn(CgAnchor.global()).start();
            LOGGER.info("net probe: started with {} player(s); {} dies", ids.size(), names.get(victimId));
            Net.STEP.send(CgAudience.all(), "armed");
            enter(Phase.BEFORE);
        }

        void before() {
            if (ticks < STEADY) return;
            for (int i = 0; i < ids.size(); i++) {
                String name = names.get(ids.get(i));
                String seen = reports.get(name);
                check("before.pool " + name, has(seen, "pool", POOL_BASE + victim), "sees " + field(seen, "pool"));
                check("before.turret " + name, has(seen, "turret", TURRET_BASE + victim), "sees " + field(seen, "turret"));
                check("before.soil " + name, has(seen, "soil", SOIL_BASE + i), "sees " + field(seen, "soil") + "; " + soilState());
            }
            LOGGER.info("net probe: kill {} now", names.get(victimId));
            // The lag is the steady window's: a respawn resends a world of chunks, and frames queue behind them.
            Net.STEP.send(CgAudience.all(), "dying");
            Net.STEP.send(CgAudience.player(victimId), "kill");
            enter(Phase.DYING);
        }

        void dying() {
            Object body = CgPlayers.player(victimId);
            if (body != null && body != victimBody && CgEntityQueries.alive(body)) {
                LOGGER.info("net probe: {} respawned after {} ticks", names.get(victimId), ticks);
                respawnedAt = now();
                enter(Phase.AFTER);
            } else if (ticks > DEATH_LIMIT) {
                check("death.happened", false, names.get(victimId) + " was not killed and back within a minute");
                finish();
            }
        }

        void after() {
            // A respawn resends the player's chunks, and their chunks' objects leave and come back with them.
            if (ticks < LOOK || ticks < BACK_LIMIT && !allBack()) return;
            String victimName = names.get(victimId);
            CgNetObject now = Net.POOL.find(victimId);
            check("pool.kept", now == pool && !pool.isRemoved() && pool.getInt(Net.VALUE) == POOL_BASE + victim,
                    victimName + "'s mana " + (now == null ? "gone" : now.getInt(Net.VALUE))
                            + (now == pool ? " on the same object" : " on another object"));
            check("buff.ended", buff.isRemoved() && Net.BUFF.find(victimId) != buff, "without copyOnDeath");
            check("turret.ended", turrets.get(victim).isRemoved(), "anchored on the body that died");
            int others = 0;
            for (int i = 0; i < turrets.size(); i++) if (i != victim && !turrets.get(i).isRemoved()) others++;
            check("turret.others-live", others == turrets.size() - 1, others + " of " + (turrets.size() - 1));
            check("soil.kept", !soil.isRemoved() && soil.getInt(Net.VALUE) == SOIL_BASE + victim, soilState());
            for (int i = 0; i < ids.size(); i++) {
                String name = names.get(ids.get(i));
                Long at = reportedAt.get(name);
                String seen = at != null && at > respawnedAt ? reports.get(name) : null;
                if (seen == null) {
                    check("seen " + name, false, "no report since the respawn");
                    continue;
                }
                check("seen.pool " + name, has(seen, "pool", POOL_BASE + victim), "sees " + field(seen, "pool"));
                check("seen.turret-gone " + name, !has(seen, "turret", TURRET_BASE + victim), "sees " + field(seen, "turret"));
                check("seen.buff-gone " + name, !has(seen, "buff", BUFF_BASE + victim), "sees " + field(seen, "buff"));
                check("seen.soil " + name, has(seen, "soil", SOIL_BASE + i), "sees " + field(seen, "soil"));
                String[] lag = field(seen, "lag").split(",");
                // A client reads frames at its tick, so a frame sent at the end of the server's tick arrives within
                // one: a median within 50 ms. Sent a tick later, it is 50 ms more. A hundred samples' p99 is one hitch.
                long median = lag.length == 5 ? Long.parseLong(lag[0]) : Long.MAX_VALUE;
                check("pump.lag " + name, median <= 50, "a change arrives in " + (lag.length == 5
                        ? "median " + lag[0] + ", p99 " + lag[1] + ", max " + lag[2] + " ms over " + lag[3] + ", "
                        + lag[4] + " read after a client stall" : "no samples"));
            }
            finish();
        }

        void finish() {
            boolean ok = failed == 0;
            LOGGER.info("net probe: {} ({} passed, {} failed)", ok, passed, failed);
            Net.STEP.send(CgAudience.all(), "done");
            for (CgNetObject t : turrets) if (!t.isRemoved()) t.kill();
            if (clock != null) clock.kill();
            clock = null;
            enter(Phase.DONE);
            exitLater("the verdict is out");
        }

        boolean allBack() {
            for (int i = 0; i < ids.size(); i++) {
                String name = names.get(ids.get(i));
                Long at = reportedAt.get(name);
                String seen = reports.get(name);
                if (at == null || at <= respawnedAt || !has(seen, "pool", POOL_BASE + victim)
                        || !has(seen, "soil", SOIL_BASE + i)) {
                    return false;
                }
            }
            return true;
        }

        /** Tells a chunk the server thinks unloaded apart from one the host says nobody tracks. */
        String soilState() {
            double[] at = new double[3];
            CgEntityQueries.position(CgPlayers.player(victimId), at);
            int[] trackers = new int[1];
            CgPlayers.trackingChunk(soil.level(), soil.chunkX, soil.chunkZ, id -> trackers[0]++);
            return "chunk " + soil.chunkX + " " + soil.chunkZ + (soil.isDormant() ? " dormant" : " loaded") + ", "
                    + trackers[0] + " tracking; the victim in chunk " + ((int) Math.floor(at[0]) >> 4) + " "
                    + ((int) Math.floor(at[2]) >> 4);
        }

        void seen(CgPeer peer, String report) {
            reports.put(peer.name(), report);
            reportedAt.put(peer.name(), now());
        }

        void check(String name, boolean ok, String detail) {
            if (ok) passed++;
            else failed++;
            LOGGER.info("net probe {}: {} ({})", name, ok, detail);
        }

        static String field(String report, String key) {
            if (report == null) return "nothing";
            for (String part : report.split(" ")) if (part.startsWith(key + "=")) return part.substring(key.length() + 1);
            return "";
        }

        static boolean has(String report, String key, int value) {
            return Arrays.asList(field(report, key).split(",")).contains(Integer.toString(value));
        }
    }

    // ── A client ────────────────────────────────────────────────────────────────────────────────

    private static final class Client {
        static final int REPORT_EVERY = 10, KILL_AFTER = 20, RESPAWN_AFTER = 20, LIMIT_MILLIS = 120_000;

        final long[] lag = new long[4096];
        volatile boolean armed, timing, done;
        volatile long connectedAt;
        int lagCount, stalled, ticks, killAt = -1, deadTicks;
        long lastTick;

        void install() {
            Net.STEP.onReceive(this::step);
            Net.CLOCK.onChange(Net.SENT, (obj, sent) -> {
                if (!timing || lagCount == lag.length) return;
                // As CgNetStress counts it: a change read after this client went 100 ms without a tick waited on the
                // client's own frame, not on the network.
                if (lastTick != 0 && now() - lastTick > 100) stalled++;
                else lag[lagCount++] = now() - sent;
            });
            Net.SOIL.onClient(obj -> new Arrivals("soil"));
            Net.POOL.onClient(obj -> new Arrivals("pool"));
            CgNetwork.onClientTick(this::tick);
        }

        /** When a holder's object comes into this client's view and leaves it: what a report alone cannot say. */
        static final class Arrivals implements CgNetBehaviour {
            final String kind;

            Arrivals(String kind) {
                this.kind = kind;
            }

            @Override
            public void spawned(CgNetObject obj, float age) {
                LOGGER.info("net probe: {} {} arrived at age {}", kind, obj.getInt(Net.VALUE), age);
            }

            @Override
            public void despawned(CgNetObject obj, boolean killed) {
                LOGGER.info("net probe: {} {} left, {}", kind, obj.getInt(Net.VALUE), killed ? "at once" : "gracefully");
            }
        }

        boolean running() {
            if (done) return false;
            if (connectedAt == 0) return false;
            return now() - connectedAt < LIMIT_MILLIS;
        }

        void step(String step) {
            switch (step) {
                case "armed":
                    armed = true;
                    timing = true;
                    lagCount = 0;
                    CgPlatform.get(CgWorldStimulus.SERVICE).keepRunning();
                    break;
                case "dying":
                    timing = false;
                    break;
                case "kill":
                    killAt = ticks + KILL_AFTER;
                    break;
                case "done":
                    done = true;
                    armed = false;
                    timing = false;
                    LOGGER.info("net probe: done on this client");
                    exitLater("the verdict is out");
                    break;
                default:
                    break;
            }
        }

        void tick() {
            if (connectedAt == 0 && CgNetwork.client() != null) connectedAt = now();
            lastTick = now();
            ticks++;
            CgWorldStimulus stimulus = CgPlatform.get(CgWorldStimulus.SERVICE);
            if (ticks == killAt) {
                LOGGER.info(stimulus.kill() ? "net probe: killed through the single-player server"
                        : "net probe: waiting to be killed from outside");
            }
            CgEntityQuery entities = CgEntityQueries.client();
            int me = entities.localPlayer();
            if (armed && me >= 0 && (entities.flags(me) & CgEntityQuery.ALIVE) == 0) {
                if (++deadTicks == RESPAWN_AFTER) LOGGER.info("net probe: respawn {}", stimulus.respawn() ? "pressed" : "refused");
            } else {
                deadTicks = 0;
            }
            if (armed && ticks % REPORT_EVERY == 0) Net.SEEN.sendToServer(report());
        }

        String report() {
            StringBuilder out = new StringBuilder();
            values(out, "pool", Net.POOL);
            values(out, "turret", Net.TURRET);
            values(out, "buff", Net.BUFF);
            values(out, "soil", Net.SOIL);
            long[] sorted = Arrays.copyOf(lag, lagCount);
            Arrays.sort(sorted);
            out.append("lag=");
            if (lagCount > 0) {
                out.append(sorted[lagCount / 2]).append(',').append(sorted[Math.min(lagCount - 1, (int) (lagCount * 0.99))])
                        .append(',').append(sorted[lagCount - 1]).append(',').append(lagCount).append(',').append(stalled);
            }
            return out.toString();
        }

        static void values(StringBuilder out, String key, CgNetType type) {
            out.append(key).append('=');
            List<CgNetObject> visible = type.visible();
            for (int i = 0; i < visible.size(); i++) {
                if (i > 0) out.append(',');
                out.append(visible.get(i).getInt(Net.VALUE));
            }
            out.append(' ');
        }
    }
}
