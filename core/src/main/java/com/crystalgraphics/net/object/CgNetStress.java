package com.crystalgraphics.net.object;

import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.crystalgraphics.net.CgAudience;
import com.crystalgraphics.net.CgMessage;
import com.crystalgraphics.net.CgNetwork;
import com.crystalgraphics.net.CgPeer;
import com.crystalgraphics.net.protocol.CgProtocolConnection;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.service.CgServerPlayers;
import com.crystalgraphics.serialization.CgCodecs;

/**
 * A live stress run of the whole network stack: a real server and its clients, through phases that load objects,
 * messages and commands, then a report of what each tick cost, what reached each player and how late.
 *
 * <p>Off unless {@value #PROPERTY} is true on every side, server and clients; {@code CgNetwork.install} then installs
 * it. The server starts once {@code .players} players (2 unless said) have joined, runs about two minutes, logs the
 * report with a PASS or FAIL per check, and writes it to {@code .report} ({@code net-stress.txt} in the server's run
 * directory unless said). The processes keep running afterwards.
 *
 * <pre>{@code
 * ./gradlew :runtime:mc:modern:fabric:26.2:runServer -PcgLiveServer -PcgAcceptEula -PcgNoLanguage -Dcrystalgraphics.net.stress=true
 * ./gradlew :runtime:mc:modern:fabric:26.2:runClient -PcgJoin=localhost:25599 -PcgUser=Alice -PcgNoLanguage -Dcrystalgraphics.net.stress=true
 * ./gradlew :runtime:mc:modern:fabric:26.2:runClient -PcgJoin=localhost:25599 -PcgUser=Bob -PcgClientDir=runs/client-bob -PcgNoLanguage \
 *         -Dcrystalgraphics.net.stress=true
 * }</pre>
 *
 * <p>Phases, in order: <b>crowd</b> (5000 objects around the first player, a tenth changing each tick, moves, events,
 * churn), <b>verify</b> (each client's table against what the server thinks it sent), <b>burst</b> (5000 objects
 * spawned in one tick), <b>contention</b> (4 MB of messages and 300 pings to each player while an object changes every
 * tick), <b>commands</b> (each client commanding its objects 50 times a tick and predicting), <b>quiet</b> (nothing
 * changing: nothing sent, nothing allocated) and <b>teardown</b> (everything despawned: nothing left).
 */
public final class CgNetStress {

    public static final String PROPERTY = "crystalgraphics.net.stress";

    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphics");

    static final CgNetField<Float> HEAT = CgNetField.ofFloat("heat");
    static final CgNetField<Integer> AMMO = CgNetField.ofInt("ammo").ownerOnly();
    static final CgNetField<String> LABEL = CgNetField.of("label", CgCodecs.STRING);
    static final CgNetField<Long> SENT = CgNetField.ofLong("sent");
    static final CgNetEvent<Long> SHOT = CgNetEvent.of("shot", CgCodecs.LONG);
    static final CgNetCommand<Integer> POKE = CgNetCommand.of("poke", CgCodecs.INT).perSecond(2000).burst(200);

    static final CgNetType SMALL = CgNetType.define("cgstress:small").fields(HEAT).range(64).build();
    static final CgNetType RICH = CgNetType.define("cgstress:rich").fields(HEAT, AMMO, LABEL).events(SHOT).commands(POKE)
            .range(96).build();
    static final CgNetType FAR = CgNetType.define("cgstress:far").fields(HEAT).build();
    // Kept through death: an unattended player in a live run is killed by whatever finds them.
    static final CgNetType MANA = CgNetType.define("cgstress:mana").fields(HEAT).perHolder().copyOnDeath().build();
    static final CgNetType TRACKER = CgNetType.define("cgstress:tracker").fields(SENT).build();

    static final CgMessage<String> PHASE = CgMessage.toClients("cgstress:phase", CgCodecs.STRING);
    static final CgMessage<String> BULK = CgMessage.toClients("cgstress:bulk", CgCodecs.STRING);
    static final CgMessage<Long> PING = CgMessage.toClients("cgstress:ping", CgCodecs.LONG);
    static final CgMessage<String> REPORT = CgMessage.toServer("cgstress:report", CgCodecs.STRING);

    /** Only this run's objects are counted: a world may hold others, persisted by other mods. */
    private static final String NS = "cgstress";

    private static boolean installed;

    private CgNetStress() {
    }

    /** Both halves; each runs only where its side does. Once, from {@code CgNetwork.install}. */
    public static synchronized void install() {
        if (installed) return;
        installed = true;
        Server server = new Server(Integer.getInteger(PROPERTY + ".players", 2),
                Paths.get(System.getProperty(PROPERTY + ".report", "net-stress.txt")));
        CgNetwork.measureAllocation(true);
        CgNetwork.onServerTick(server::tick);
        REPORT.onReceiveFrom(server::received);
        RICH.onCommand(POKE, server::poked);
        SMALL.acceptPredictionsFrom((peer, spark) -> ++server.predictionsAccepted > 0);
        new Client().install();
        LOGGER.info("[net-stress] installed: waiting for {} players", server.players);
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    private static int stamp() {
        return (int) (now() & 0x7fffffff);
    }

    /** A bounded series of readings and its spread. */
    private static final class Samples {
        private final long[] values = new long[8192];
        private int size;

        void add(long value) {
            if (size < values.length) values[size++] = value;
        }

        long percentile(double p) {
            if (size == 0) return 0;
            long[] sorted = Arrays.copyOf(values, size);
            Arrays.sort(sorted);
            return sorted[Math.min(size - 1, (int) (size * p))];
        }

        long max() {
            long max = 0;
            for (int i = 0; i < size; i++) max = Math.max(max, values[i]);
            return max;
        }

        String spread(String unit, double scale) {
            if (size == 0) return "none";
            long[] sorted = Arrays.copyOf(values, size);
            Arrays.sort(sorted);
            return String.format("median %.1f, p99 %.1f, max %.1f %s (%d)", sorted[size / 2] * scale,
                    sorted[Math.min(size - 1, (int) (size * 0.99))] * scale, sorted[size - 1] * scale, unit, size);
        }
    }

    private enum Phase { WAIT, CROWD, VERIFY, BURST, CONTENTION, COMMANDS, QUIET, TEARDOWN, REPORT, DONE }

    // ── The server ──────────────────────────────────────────────────────────────────────────────

    private static final class Server {
        static final int SETTLE = 200, CROWD = 600, WARM = 100, BURST = 5000, QUIET = 200;

        final int players;
        final Path out;
        final Random random = new Random(7);
        final List<UUID> ids = new ArrayList<>();
        final Map<UUID, String> names = new LinkedHashMap<>();
        final Map<String, String> reports = new LinkedHashMap<>();
        final List<String> lines = new ArrayList<>();
        final List<String> verdicts = new ArrayList<>();
        final List<CgNetObject> live = new ArrayList<>();
        final double[] at = new double[3];

        Phase phase = Phase.WAIT;
        int ticks;
        String dimension;
        CgNetObject tracker;
        long[] sentBefore;
        int predictionsAccepted, pokes;
        final Samples tickNanos = new Samples(), tickAllocated = new Samples(), perPlayer = new Samples(),
                pokeLag = new Samples();
        final Map<UUID, Integer> burstExpected = new LinkedHashMap<>();
        int burstTicks = -1;
        long burstLargest, burstSlowest, quietAllocated;
        final List<GarbageCollectorMXBean> collectors = ManagementFactory.getGarbageCollectorMXBeans();
        long collections;
        int outliers;

        Server(int players, Path out) {
            this.players = players;
            this.out = out;
        }

        void enter(Phase next) {
            phase = next;
            ticks = 0;
            LOGGER.info("[net-stress] phase {}", next.name().toLowerCase());
        }

        void tick() {
            if (phase == Phase.DONE) return;
            if (phase != Phase.WAIT && ids.size() > 0 && CgNetwork.forPlayer(ids.get(0)) == null && phase != Phase.REPORT) {
                LOGGER.warn("[net-stress] a player left mid-run; stopping");
                enter(Phase.DONE);
                return;
            }
            if (tracker != null && phase != Phase.QUIET && phase != Phase.TEARDOWN) tracker.setLong(SENT, now());
            if (phase != Phase.WAIT) watchOutliers();
            switch (phase) {
                case WAIT: waitForPlayers(); break;
                case CROWD: crowd(); break;
                case VERIFY: verify(); break;
                case BURST: burst(); break;
                case CONTENTION: contention(); break;
                case COMMANDS: commands(); break;
                case QUIET: quiet(); break;
                case TEARDOWN: teardown(); break;
                case REPORT: report(); break;
                default: break;
            }
            ticks++;
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
            if (ready < players) {
                ticks = -1;
                return;
            }
            if (ticks < SETTLE) return;
            CgServerPlayers hosts = CgPlatform.get(CgServerPlayers.SERVICE);
            for (UUID id : ids) names.put(id, ((CgPeer) CgNetwork.forPlayer(id).peer()).name());
            Object first = ((CgPeer) CgNetwork.forPlayer(ids.get(0)).peer()).player();
            dimension = hosts.dimension(first);
            hosts.position(first, at);
            for (UUID id : ids) MANA.of(id).setFloat(HEAT, 1);
            tracker = TRACKER.spawn(CgAnchor.global()).start();
            for (int i = 0; i < 4000; i++) live.add(SMALL.spawn(near(128)).start());
            for (int i = 0; i < 800; i++) {
                live.add(RICH.spawn(near(128)).owner(ids.get(i % ids.size())).setInt(AMMO, i).set(LABEL, "turret " + i).start());
            }
            for (int i = 0; i < 200; i++) live.add(FAR.spawn(near(128)).start());
            sentBefore = sent();
            PHASE.send(CgAudience.all(), "crowd");
            enter(Phase.CROWD);
        }

        /**
         * Logs the last tick's network work when it is far off a typical tick, as it happens. The uptime lines it up
         * with {@code -XX:+PrintCompilation} (a deoptimized tick runs interpreted, allocating what escape analysis
         * removes); a collection during it marks a pause rather than work.
         */
        void watchOutliers() {
            long total = 0;
            for (GarbageCollectorMXBean collector : collectors) total += Math.max(0, collector.getCollectionCount());
            long nanos = CgNetwork.serverTickNanos(), allocated = CgNetwork.serverTickAllocated();
            if (nanos > 10_000_000L || allocated > 1 << 20) {
                outliers++;
                LOGGER.info("[net-stress] outlier in {} tick {}: {} ms, {} KB, {} collections since the last tick, uptime {} ms",
                        phase.name().toLowerCase(), ticks, nanos / 1_000_000, allocated >> 10, total - collections,
                        ManagementFactory.getRuntimeMXBean().getUptime());
            }
            collections = total;
        }

        CgAnchor near(double half) {
            return CgAnchor.at(dimension, at[0] + (random.nextDouble() * 2 - 1) * half, at[1],
                    at[2] + (random.nextDouble() * 2 - 1) * half);
        }

        long[] sent() {
            long[] out = new long[ids.size()];
            for (int i = 0; i < ids.size(); i++) out[i] = CgNetObjects.sentTo(ids.get(i))[0];
            return out;
        }

        long sentSince(long[] before) {
            long total = 0;
            for (int i = 0; i < ids.size(); i++) total += CgNetObjects.sentTo(ids.get(i))[0] - before[i];
            return total;
        }

        void crowd() {
            if (ticks > WARM) {
                tickNanos.add(CgNetwork.serverTickNanos());
                tickAllocated.add(CgNetwork.serverTickAllocated());
                perPlayer.add(sentSince(sentBefore) / ids.size());
            }
            sentBefore = sent();
            if (ticks >= CROWD) {
                lines.add("crowd: " + live.size() + " objects in 256 x 256 blocks, " + ids.size()
                        + " players, a tenth changing a tick, " + (CROWD - WARM) + " ticks measured");
                lines.add("  server net tick " + tickNanos.spread("ms", 1e-6));
                lines.add("  allocated       " + tickAllocated.spread("KB a tick", 1e-3));
                lines.add("  per player      " + perPlayer.spread("B a tick", 1));
                enter(Phase.VERIFY);
                return;
            }
            int n = live.size();
            for (int c = 0; c < n / 10; c++) live.get(random.nextInt(n)).setFloat(HEAT, ticks);
            for (int c = 0; c < n / 100; c++) {
                CgNetObject obj = live.get(random.nextInt(n));
                obj.moveTo(obj.x() + random.nextDouble() - 0.5, obj.y(), obj.z() + random.nextDouble() - 0.5);
            }
            for (int c = 0; c < 8; c++) live.get(4000 + random.nextInt(800)).emit(SHOT, now());
            for (int c = 0; c < n / 500; c++) {
                int i = random.nextInt(4000);
                live.get(i).despawn();
                live.set(i, SMALL.spawn(near(128)).start());
            }
            if (ticks % 20 == 0) for (UUID id : ids) MANA.of(id).setFloat(HEAT, ticks);
        }

        void verify() {
            if (ticks == 40) {
                for (UUID id : ids) PHASE.send(CgAudience.player(id), "verify " + CgNetObjects.seenBy(id, NS));
            }
            if (ticks >= 140 || ticks > 40 && countVerdicts("verify") == ids.size()) {
                for (CgNetObject obj : live) obj.despawn();
                live.clear();
                enter(Phase.BURST);
            }
        }

        int countVerdicts(String prefix) {
            int n = 0;
            for (String verdict : verdicts) if (verdict.contains(prefix)) n++;
            return n;
        }

        void burst() {
            if (ticks == 40) {
                for (UUID id : ids) burstExpected.put(id, CgNetObjects.seenBy(id, NS) + BURST);
                for (int i = 0; i < BURST; i++) live.add(FAR.spawn(near(512)).start());
                for (UUID id : ids) PHASE.send(CgAudience.player(id), "burst " + burstExpected.get(id));
                sentBefore = sent();
            } else if (ticks > 40) {
                for (int i = 0; i < ids.size(); i++) {
                    long now = CgNetObjects.sentTo(ids.get(i))[0];
                    burstLargest = Math.max(burstLargest, now - sentBefore[i]);
                }
                sentBefore = sent();
                burstSlowest = Math.max(burstSlowest, CgNetwork.serverTickNanos());
                if (burstTicks < 0 && allSeen()) burstTicks = ticks - 40;
                if (ticks == 240) {
                    lines.add("burst: " + BURST + " objects spawned in one tick, budget " + CgNetObjects.budget()
                            + " B a tick; every player sent them all in " + burstTicks + " ticks; the largest tick to one player "
                            + burstLargest + " B; the slowest server net tick " + burstSlowest / 1000 + " us");
                    verdicts.add((burstTicks > 0 ? "PASS" : "FAIL") + " burst reaches every player (" + burstTicks + " ticks)");
                    verdicts.add((burstLargest <= CgNetObjects.budget() * 1.25 ? "PASS" : "FAIL")
                            + " burst keeps to the budget (largest tick " + burstLargest + " B)");
                    for (CgNetObject obj : live) obj.despawn();
                    live.clear();
                }
                if (ticks == 300) {
                    PHASE.send(CgAudience.all(), "contention");
                    char[] megabyte = new char[1 << 20];
                    Arrays.fill(megabyte, 'x');
                    String payload = new String(megabyte);
                    for (UUID id : ids) {
                        for (int b = 0; b < 4; b++) BULK.send(CgAudience.player(id), payload);
                        for (int p = 0; p < 300; p++) PING.send(CgAudience.player(id), now());
                    }
                    enter(Phase.CONTENTION);
                }
            }
        }

        boolean allSeen() {
            for (UUID id : ids) if (CgNetObjects.seenBy(id, NS) < burstExpected.get(id)) return false;
            return true;
        }

        void contention() {
            if (ticks == 200) {
                for (int i = 0; i < 400; i++) {
                    live.add(RICH.spawn(near(48)).owner(ids.get(i % ids.size())).setInt(AMMO, i).set(LABEL, "turret " + i).start());
                }
                enter(Phase.COMMANDS);
            }
        }

        void commands() {
            if (ticks == 40) PHASE.send(CgAudience.all(), "commands");
            if (ticks == 340) {
                PHASE.send(CgAudience.all(), "commands end");
                lines.add("commands: " + pokes + " handled, " + POKE.refused() + " refused, " + POKE.malformed()
                        + " malformed; lag " + pokeLag.spread("ms", 1));
                verdicts.add((pokeLag.percentile(0.99) <= 75 ? "PASS" : "FAIL") + " commands handled within 75 ms (p99 "
                        + pokeLag.percentile(0.99) + " ms)");
                lines.add("predictions: " + predictionsAccepted + " accepted, " + SMALL.refusedPredictions() + " refused");
                enter(Phase.QUIET);
            }
        }

        void poked(CgNetObject obj, CgPeer sender, Integer stamp) {
            pokes++;
            if (stamp != null) pokeLag.add((stamp() - stamp) & 0x7fffffff);
            obj.setFloat(HEAT, pokes);
        }

        void quiet() {
            if (ticks == 40) {
                sentBefore = sent();
                quietAllocated = 0;
                tickNanos.size = 0;
            } else if (ticks > 40) {
                quietAllocated += CgNetwork.serverTickAllocated();
                tickNanos.add(CgNetwork.serverTickNanos());
            }
            if (ticks == 40 + QUIET) {
                long bytes = sentSince(sentBefore);
                lines.add("quiet: " + (live.size() + ids.size() + 1) + " objects, nothing changing, " + QUIET + " ticks: "
                        + bytes + " B sent, " + quietAllocated + " B allocated; server net tick " + tickNanos.spread("ms", 1e-6));
                verdicts.add((bytes == 0 ? "PASS" : "FAIL") + " quiet sends nothing (" + bytes + " B)");
                verdicts.add((quietAllocated == 0 ? "PASS" : "FAIL") + " quiet allocates nothing (" + quietAllocated + " B)");
                for (CgNetObject obj : live) obj.despawn();
                live.clear();
                tracker.despawn();
                tracker = null;
                enter(Phase.TEARDOWN);
            }
        }

        void teardown() {
            if (ticks < 60) return;
            int left = CgNetObjects.live(NS);
            String footprint = CgNetObjects.footprint();
            verdicts.add((left == ids.size() && footprint.endsWith("fired 0") ? "PASS" : "FAIL")
                    + " teardown leaves only the mana bars (" + left + " live; " + footprint + ")");
            PHASE.send(CgAudience.all(), "report");
            enter(Phase.REPORT);
        }

        void report() {
            if (reports.size() < ids.size() && ticks < 200) return;
            StringBuilder text = new StringBuilder("net-stress report\n");
            for (String line : lines) text.append(line).append('\n');
            for (Map.Entry<String, String> client : reports.entrySet()) {
                text.append("client ").append(client.getKey()).append('\n').append(client.getValue());
            }
            text.append("outliers: ").append(outliers).append(" ticks over 10 ms or 1 MB, each logged as it happened").append('\n');
            if (reports.size() < ids.size()) verdicts.add("FAIL " + (ids.size() - reports.size()) + " clients never reported");
            for (String verdict : verdicts) text.append(verdict).append('\n');
            for (String line : text.toString().split("\n")) LOGGER.info("[net-stress] {}", line);
            try {
                Files.write(out, text.toString().getBytes(StandardCharsets.UTF_8));
                LOGGER.info("[net-stress] written to {}", out.toAbsolutePath());
            } catch (IOException failed) {
                LOGGER.error("[net-stress] could not write {}", out, failed);
            }
            enter(Phase.DONE);
        }

        void received(CgPeer peer, String text) {
            if (text.startsWith("verify ")) {
                String[] parts = text.split(" ");
                verdicts.add((parts[1].equals(parts[2]) ? "PASS" : "FAIL") + " verify " + peer.name() + " holds "
                        + parts[2] + " objects, the server sent " + parts[1]);
            } else {
                reports.put(peer.name(), text);
                for (String line : text.split("\n")) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("PASS ") || trimmed.startsWith("FAIL ")) {
                        verdicts.add(trimmed.substring(0, 5) + peer.name() + ": " + trimmed.substring(5));
                    }
                }
                String left = text.substring(text.lastIndexOf("  left ") + 7).trim();
                verdicts.add(("1".equals(left) ? "PASS" : "FAIL") + " teardown leaves " + peer.name() + " " + left
                        + " objects (its mana bar)");
            }
        }
    }

    // ── A client ────────────────────────────────────────────────────────────────────────────────

    private static final class Client {
        String phase = "";
        long phaseStarted;
        int clientTicks, phaseTicks;
        final Samples trackerLag = new Samples(), shotLag = new Samples(), contentionLag = new Samples(),
                pingLag = new Samples(), tickNanos = new Samples();
        int bulks, burstExpected, burstTicks = -1, pokes, predictions;
        long bulkMillis = -1, burstMillis = -1;
        /** When this client last ticked, and the arrivals measured just after it had not ticked for over 100 ms. */
        long lastTick;
        int stalled;
        long longestStall;

        /**
         * A lag, unless this client's own thread was held up before it could read it (a frame hitch delays every tick):
         * that is the client's lateness, not the network's, and it is counted apart.
         */
        void lag(Samples into, long sent) {
            long now = now(), gap = lastTick == 0 ? 0 : now - lastTick;
            if (gap > 100) {
                stalled++;
                longestStall = Math.max(longestStall, gap);
                return;
            }
            into.add(now - sent);
        }

        void install() {
            PHASE.onReceive(this::phase);
            TRACKER.onChange(SENT, (obj, sent) -> {
                if ("crowd".equals(phase)) lag(trackerLag, sent);
                else if ("contention".equals(phase)) lag(contentionLag, sent);
            });
            RICH.onEvent(SHOT, (obj, sent) -> lag(shotLag, sent));
            PING.onReceive(sent -> lag(pingLag, sent));
            BULK.onReceive(text -> {
                if (++bulks == 4) bulkMillis = now() - phaseStarted;
            });
            TRACKER.onClient(obj -> new CgNetBehaviour() {
                @Override
                public void tick(CgNetObject obj) {
                    Client.this.tick();
                }
            });
        }

        void phase(String next) {
            phase = next;
            phaseStarted = now();
            phaseTicks = 0;
            if (next.startsWith("verify ")) {
                REPORT.sendToServer("verify " + next.substring(7) + " " + CgNetObjects.client().count(NS));
            } else if (next.startsWith("burst ")) {
                burstExpected = Integer.parseInt(next.substring(6));
                phase = "burst";
            } else if ("report".equals(next)) {
                REPORT.sendToServer(report());
            }
        }

        void tick() {
            lastTick = now();
            clientTicks++;
            phaseTicks++;
            if ("crowd".equals(phase)) tickNanos.add(CgNetwork.clientTickNanos());
            if ("burst".equals(phase) && burstMillis < 0 && CgNetObjects.client().count(NS) >= burstExpected) {
                burstMillis = now() - phaseStarted;
                burstTicks = phaseTicks;
            }
            if ("commands".equals(phase)) {
                int sent = 0;
                CgNetObject mine = null;
                for (CgNetObject obj : CgNetObjects.client().visible(RICH)) {
                    if (!obj.isMine()) continue;
                    mine = obj;
                    if (sent++ < 50) obj.send(POKE, stamp());
                }
                pokes += Math.min(sent, 50);
                if (mine != null && phaseTicks % 5 == 0) {
                    SMALL.predict(CgAnchor.at(mine.x(), mine.y(), mine.z())).setFloat(HEAT, 1).fire();
                    predictions++;
                }
            }
        }

        /** Within a tick and a half: the next client tick, give or take scheduling. */
        static String punctual(String what, Samples lag) {
            long p99 = lag.percentile(0.99);
            return "  " + (p99 <= 75 ? "PASS " : "FAIL ") + what + " arrive within 75 ms (p99 " + p99 + " ms)\n";
        }

        String report() {
            ClientObjects objects = CgNetObjects.client();
            return "  object change lag   " + trackerLag.spread("ms", 1) + "\n"
                    + "  event lag           " + shotLag.spread("ms", 1) + "\n"
                    + "  client net tick     " + tickNanos.spread("ms", 1e-6) + "\n"
                    + "  client stalls       " + stalled + " arrivals read after this client had not ticked for over 100 ms"
                    + " (longest " + longestStall + " ms), left out of the lags\n"
                    + "  burst               " + (burstMillis < 0 ? "never complete" : burstMillis + " ms, " + burstTicks + " ticks") + "\n"
                    + "  contention          4 MB arrived after " + bulkMillis + " ms; object change lag "
                    + contentionLag.spread("ms", 1) + "; ping lag " + pingLag.spread("ms", 1) + "\n"
                    + "  commands            " + pokes + " sent, " + predictions + " predictions\n"
                    + "  received            " + objects.bytesReceived + " B in " + objects.framesReceived
                    + " object frames, the largest " + objects.largestFrame + " B\n"
                    + punctual("object changes", trackerLag) + punctual("object changes under contention", contentionLag)
                    + punctual("pings under contention", pingLag)
                    + "  left " + objects.count(NS) + "\n";
        }
    }
}
