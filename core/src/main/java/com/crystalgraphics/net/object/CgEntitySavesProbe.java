package com.crystalgraphics.net.object;

import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.crystalgraphics.gauntlet.CgGauntletController;
import com.crystalgraphics.gauntlet.CgGauntletState;
import com.crystalgraphics.lifecycle.CgEngineLifecycle;
import com.crystalgraphics.net.CgNetwork;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.world.CgEntityQuery;
import com.crystalgraphics.platform.world.CgPlayers;
import com.crystalgraphics.platform.world.CgWorldQuery;
import com.crystalgraphics.platform.world.CgWorldStimulus;
import com.crystalgraphics.serialization.CgCodecs;
import com.crystalgraphics.world.CgEntityQueries;
import com.crystalgraphics.world.CgEntitySaves;
import com.crystalgraphics.world.CgWorldQueries;

/**
 * The gauntlet's {@code crystalgraphics:entity-saves}: an entity's persisted fields through its chunk unloading and
 * loading again, the game's own save and load. A mob is summoned far from spawn, its fields set, the player taken away
 * until its object ends with the chunk, then brought back: its saved fields come back, the one not saved does not.
 *
 * <pre>{@code
 * ./gradlew :runtime:mc:modern:forge:1.20.1:runClient -Dcrystalgraphics.gauntlet=crystalgraphics:entity-saves
 * }</pre>
 *
 * <p>It puts the player in creative and moves them 3000 blocks out and back: run it in a test world.</p>
 */
public final class CgEntitySavesProbe {

    private static final Logger LOGGER = LogManager.getLogger("CgEntitySavesProbe");

    /**
     * Far from spawn, whose chunks never unload, and somewhere no earlier run stood (the gauntlet's world is kept, and
     * an earlier run's mob there still holds its values); under every version's build limit, the player falling from
     * there in creative.
     */
    private static final double FAR_X = 3000 + 64 * ThreadLocalRandom.current().nextInt(1000),
            FAR_Z = 3000 + 64 * ThreadLocalRandom.current().nextInt(1000), HIGH = 240;

    /** Declared at install only: a declaration is in every connection's hello. */
    private static final class Saved {
        static final CgNetField<Integer> LEVEL = CgNetField.ofInt("level").persisted();
        static final CgNetField<String> TITLE = CgNetField.of("title", CgCodecs.STRING).persisted();
        /** This session's: set, and back at its fallback after the reload. */
        static final CgNetField<Integer> TAUNTS = CgNetField.ofInt("taunts");

        // every entity but a player: a stand on most versions, a pig on 1.7.10
        static final CgNetType<CgNetObject> TYPE = CgNetType.define("cgprobe:entity_saves").fields(LEVEL, TITLE, TAUNTS)
                .onEntities(type -> !type.equals("minecraft:player")).build();
    }

    private enum Phase { GO_FAR, LANDING, PLACE, SUMMON, FILL, GO_HOME, UNLOADED, COME_BACK, DONE }

    private static boolean installed;
    private static volatile Test active;
    /** Set on the server thread, read by the test's ticks. */
    private static volatile Phase phase = Phase.GO_FAR;
    /**
     * Where the mob is summoned: beside where the player landed, read on the server (1.7.10's pig would die of a fall,
     * and suffocate in the ground).
     */
    private static volatile double summonX, summonY, summonZ;
    /** Why the server has not yet placed the summon: said if it never does. */
    private static volatile String placing = "it has not looked yet";
    private static volatile String failure;

    private CgEntitySavesProbe() {
    }

    /** The gauntlet's {@code crystalgraphics:entity-saves}. */
    public static CgGauntletController test() {
        return new Test();
    }

    /** Declares its type and arms the server half. Once, from {@code CgNetwork.install} where the run names it. */
    public static synchronized void install() {
        if (installed) return;
        installed = true;
        // declared now, at network init: a type declared later misses every entity already in the world
        Objects.requireNonNull(Saved.TYPE);
        Server server = new Server();
        CgEngineLifecycle.onServerTick(server::tick);
        LOGGER.info("entity saves probe: armed");
    }

    /** Drives the player; the server half reports what it sees. */
    private static final class Test extends CgGauntletController {
        private double waitedSince = -1;

        @Override
        protected void onInit() {
            active = this;
            phase = Phase.GO_FAR;
        }

        @Override
        protected void onStateChange(CgGauntletState from, CgGauntletState to) {
            if (to.equals(CgGauntletState.TITLE)) enterWorld();
        }

        @Override
        protected void onTick(float seconds) {
            if (!state().equals(CgGauntletState.IN_WORLD) || timeInState() < 3) return;
            CgWorldStimulus stimulus = CgPlatform.get(CgWorldStimulus.SERVICE);
            double now = timeInState();
            switch (phase) {
                case GO_FAR:
                    stimulus.keepRunning();
                    if (!stimulus.teleport(FAR_X, HIGH, FAR_Z)) {
                        skip("saves.entity", "no single-player server to move the player in");
                        endTest();
                        return;
                    }
                    phase = Phase.LANDING;
                    waitedSince = now;
                    return;
                case LANDING:
                    // the far chunks generate and load around the player first, and they land
                    if (now - waitedSince < 8) return;
                    phase = Phase.PLACE;
                    waitedSince = now;
                    return;
                case PLACE:
                    if (now - waitedSince > 10) fail("the server never placed the summon: " + placing);
                    return;
                case SUMMON: {
                    stimulus.stand(summonX, summonY, summonZ);
                    phase = Phase.FILL;
                    waitedSince = now;
                    return;
                }
                case FILL:
                    if (now - waitedSince > 30) fail("the summoned mob's object never appeared on the server");
                    return;
                case GO_HOME:
                    stimulus.teleport(0, HIGH, 0);
                    phase = Phase.UNLOADED;
                    waitedSince = now;
                    return;
                case UNLOADED:
                    if (now - waitedSince > 120) fail("the far chunk never unloaded: the mob's object never ended");
                    return;
                case COME_BACK:
                    stimulus.teleport(FAR_X, HIGH, FAR_Z);
                    phase = Phase.DONE;
                    waitedSince = now;
                    return;
                case DONE:
                    if (now - waitedSince > 60) fail("the mob never came back with its chunk");
                    return;
                default:
            }
        }

        private void fail(String why) {
            check("saves.entity", false, why);
            endTest();
        }

        @Override
        protected void onEnd() {
            active = null;
        }

        void stated(String name, boolean ok, String detail) {
            check(name, ok, detail);
        }

        void said(String name, String detail) {
            info(name, detail);
        }

        void done() {
            endTest();
        }
    }

    /** Fills the mob's object, sees it end with its chunk, and checks the one made as it loads again. */
    private static final class Server {
        private CgNetObject filled;
        private int settle, waiting;
        private double lastX, lastZ;
        private Object summonLevel;
        private final double[] at = new double[3];

        void tick() {
            Test test = active;
            if (test == null || CgNetwork.side() != CgNetwork.Side.SERVER) return;
            switch (phase) {
                case PLACE: {
                    // the single-player world's one player, on the server: a client's ids are not the server's on every version
                    Object player = CgNetObjects.onlyPlayer();
                    if (player == null) {
                        placing = "not exactly one player connected";
                        return;
                    }
                    if (!CgEntityQueries.position(player, at)) {
                        placing = "the server player has no position";
                        return;
                    }
                    // on the surface: a player moved over chunks not yet made can fall through them to bedrock
                    Object level = CgWorldQueries.level(CgNetObjects.server(), CgWorldQueries.dimension(player));
                    int top = level == null ? Integer.MIN_VALUE
                            : CgWorldQueries.of(level).surfaceY((int) Math.floor(at[0] + 3), (int) Math.floor(at[2]), CgWorldQuery.HEIGHT_TOP);
                    if (top == Integer.MIN_VALUE) {
                        placing = level == null ? "no level for " + CgWorldQueries.dimension(player)
                                : "no surface at " + (int) (at[0] + 3) + ", " + (int) at[2] + " (column not loaded)";
                        return;
                    }
                    summonLevel = level;
                    summonX = at[0] + 3;
                    summonY = top;
                    summonZ = at[2];
                    phase = Phase.SUMMON;
                    return;
                }
                case FILL: {
                    CgNetObject near = near(summonX, summonZ, 4);
                    if (near == null) {
                        if (++waiting % 100 == 0) test.said("saves.entity.waiting", seen());
                        return;
                    }
                    near.setInt(Saved.LEVEL, 42).set(Saved.TITLE, "Super Saiyan").setInt(Saved.TAUNTS, 7);
                    filled = near;
                    lastX = at[0];
                    lastZ = at[2];
                    settle = 0;
                    phase = Phase.GO_HOME;
                    test.said("saves.entity.filled", "the mob at " + (int) at[0] + ", " + (int) at[2]);
                    return;
                }
                case UNLOADED:
                    // a pig wanders until its chunk goes: where it was saved is where it was last
                    if (filled != null && !filled.isRemoved() && filled.entity() != null
                            && CgEntityQueries.position(filled.entity(), at)) {
                        lastX = at[0];
                        lastZ = at[2];
                    }
                    if (filled != null && filled.isRemoved()) {
                        // a few ticks for the chunk's save to finish before it loads again
                        if (++settle < 40) return;
                        test.said("saves.entity.unloaded", "its object ended with the chunk; entities written "
                                + CgNetEntities.entitiesWritten + ", objects ended unsaved " + CgNetEntities.endedUnsaved);
                        filled = null;
                        phase = Phase.COME_BACK;
                    }
                    return;
                case DONE: {
                    CgNetObject back = near(lastX, lastZ, 1.5);
                    if (back == null || CgPlayers.idOf(back.entity()) != null) return;
                    int level = back.getInt(Saved.LEVEL);
                    String title = back.get(Saved.TITLE);
                    test.stated("saves.entity.persisted", level == 42 && "Super Saiyan".equals(title),
                            "level " + level + ", title " + title + " after the chunk saved and loaded");
                    test.stated("saves.entity.unpersisted", back.getInt(Saved.TAUNTS) == 0,
                            "taunts " + back.getInt(Saved.TAUNTS) + ": not saved, back at its fallback");
                    // tells a save never written into the entity from one written and not read back
                    test.said("saves.entity.compound", "the loaded entity's is " + CgEntitySaves.read(back.entity()) + ", " + seen());
                    phase = Phase.GO_FAR;
                    test.done();
                    return;
                }
                default:
            }
        }

        /** What it sees while it waits: tells a move that did not happen from a mob that has no object. */
        private String seen() {
            int[] count = {0};
            double[] best = {Double.MAX_VALUE, 0, 0};
            String[] player = {"none"};
            Saved.TYPE.forEach(obj -> {
                Object entity = obj.entity();
                count[0]++;
                if (entity == null || !CgEntityQueries.position(entity, at)) return;
                if (CgPlayers.idOf(entity) != null) {
                    player[0] = (int) at[0] + ", " + (int) at[1] + ", " + (int) at[2];
                    return;
                }
                double dx = at[0] - summonX, dz = at[2] - summonZ, d = dx * dx + dz * dz;
                if (d < best[0]) {
                    best[0] = d;
                    best[1] = at[0];
                    best[2] = at[2];
                }
            });
            String nearest = best[0] == Double.MAX_VALUE ? "none" : (int) best[1] + ", " + (int) best[2];
            // the level's own answer, apart from the type's objects: no mob there, or a mob with no object
            StringBuilder there = new StringBuilder();
            if (summonLevel != null) {
                CgEntityQuery q = CgEntityQueries.of(summonLevel);
                q.within(summonX - 6, summonY - 6, summonZ - 6, summonX + 6, summonY + 6, summonZ + 6,
                        id -> {
                            Object e = q.entity(id);
                            CgNetObject o = e == null ? null : Saved.TYPE.holders.get(e);
                            there.append(' ').append(q.type(id)).append('/').append(q.kind(id))
                                    .append(o == null ? " (no object)" : o.isRemoved() ? " (object ended)" : " (object)");
                        });
            }
            return count[0] + " objects of its type; the nearest mob at " + nearest + "; summoned at " + (int) summonX + ", "
                    + (int) summonY + ", " + (int) summonZ + "; the level has there:" + (there.length() == 0 ? " nothing" : there);
        }

        /** The type's object on a mob within {@code radius} blocks across of the point: not a player, item or shot. */
        private CgNetObject near(double x, double z, double radius) {
            CgNetObject[] found = {null};
            Saved.TYPE.forEach(obj -> {
                Object entity = obj.entity();
                if (found[0] != null || entity == null || CgPlayers.idOf(entity) != null) return;
                if (!CgEntityQueries.position(entity, at)) return;
                // what it drops (an item) or fires is not it
                int kind = CgEntityQueries.of(obj.level()).kind(CgEntityQueries.idOf(entity));
                if (kind == CgEntityQuery.KIND_ITEM || kind == CgEntityQuery.KIND_PROJECTILE) return;
                double dx = at[0] - x, dz = at[2] - z;
                if (dx * dx + dz * dz < radius * radius) found[0] = obj;
            });
            return found[0];
        }
    }
}
