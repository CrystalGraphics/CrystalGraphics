package com.crystalgraphics.net.object;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.crystalgraphics.gauntlet.CgGauntletController;
import com.crystalgraphics.gauntlet.CgGauntletState;
import com.crystalgraphics.lifecycle.CgEngineLifecycle;
import com.crystalgraphics.net.CgNetwork;
import com.crystalgraphics.serialization.CgCodecs;

/**
 * The gauntlet's {@code crystalgraphics:player-saves}: a type on players with a persisted field of every kind, filled on
 * the server, written to the player's file and read back, so {@code <world>/crystalgraphics/players/<uuid>.jsonc} can be
 * opened after. A field not persisted and a local one are set too, and must not be there.
 *
 * <pre>{@code
 * ./gradlew :runtime:mc:modern:forge:1.20.1:runClient -Dcrystalgraphics.gauntlet=crystalgraphics:player-saves
 * // then open <world>/crystalgraphics/players/<uuid>.jsonc; the log's saves.text is its contents
 * }</pre>
 */
public final class CgPlayerSavesProbe {

    private static final Logger LOGGER = LogManager.getLogger("CgPlayerSavesProbe");

    /** Declared at install only: a declaration is in every connection's hello. */
    private static final class Saved {
        static final CgNetField<Integer> LEVEL = CgNetField.ofInt("level").persisted();
        static final CgNetField<Long> XP = CgNetField.ofLong("xp").persisted();
        static final CgNetField<Float> HEALTH = CgNetField.ofFloat("health").persisted();
        static final CgNetField<Double> SPEED = CgNetField.ofDouble("speed").persisted();
        static final CgNetField<Boolean> FLYING = CgNetField.ofBool("flying").persisted();
        static final CgNetField<String> TITLE = CgNetField.of("title", CgCodecs.STRING).persisted();
        static final CgNetField<List<String>> FORMS = CgNetField.of("forms", CgCodecs.listOf(CgCodecs.STRING)).persisted();
        static final CgNetField<Map<String, Integer>> STATS =
                CgNetField.of("stats", CgCodecs.mapOf(CgCodecs.STRING, CgCodecs.INT)).persisted();
        static final CgNetField<UUID> RIVAL = CgNetField.of("rival", CgCodecs.UUID).persisted();
        /** This session's: set, and never saved. */
        static final CgNetField<Integer> TARGET = CgNetField.ofInt("target");
        /** Each side's own: set, and never saved. */
        static final CgNetField<Integer> TICKS = CgNetField.ofInt("ticks").local();

        static final CgNetType TYPE = CgNetType.define("cgprobe:player_saves")
                .fields(LEVEL, XP, HEALTH, SPEED, FLYING, TITLE, FORMS, STATS, RIVAL, TARGET, TICKS)
                .onEntities(type -> type.equals("minecraft:player")).build();
    }

    private static final String[] SAVED = {"level", "xp", "health", "speed", "flying", "title", "forms", "stats", "rival"};

    private static boolean installed;
    /** The test playing it; null outside a run's turn. */
    private static volatile Test active;

    private CgPlayerSavesProbe() {
    }

    /** The gauntlet's {@code crystalgraphics:player-saves}. */
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
        LOGGER.info("player saves probe: armed ({} fields persisted)", SAVED.length);
    }

    private static final class Test extends CgGauntletController {
        @Override
        protected void onInit() {
            active = this;
        }

        @Override
        protected void onStateChange(CgGauntletState from, CgGauntletState to) {
            if (to.equals(CgGauntletState.TITLE)) enterWorld();
        }

        @Override
        protected void onTick(float seconds) {
            if (state().equals(CgGauntletState.IN_WORLD) && timeInState() > 60) {
                check("saves.filled", false, "no player's object was filled on the server within a minute");
                endTest();
            }
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

    /** Fills the first player's object a few seconds after it exists, saves it through the player, and reads it back. */
    private static final class Server {
        int waited;
        boolean filled;

        void tick() {
            Test test = active;
            if (filled || test == null || CgNetwork.side() != CgNetwork.Side.SERVER) return;
            CgNetObject[] first = {null};
            Saved.TYPE.forEach(obj -> {
                if (first[0] == null && obj.entity() != null) first[0] = obj;
            });
            if (first[0] == null || ++waited < 40) return;
            filled = true;
            CgNetObject obj = first[0];
            obj.setInt(Saved.LEVEL, 42).setLong(Saved.XP, 9_000_000_000L).setFloat(Saved.HEALTH, 87.5f)
                    .setDouble(Saved.SPEED, 1.25).setBool(Saved.FLYING, true).set(Saved.TITLE, "Super Saiyan")
                    .set(Saved.FORMS, List.of("base", "kaioken", "ssj"))
                    .set(Saved.STATS, Map.of("strength", 12, "ki", 30))
                    .set(Saved.RIVAL, UUID.fromString("00000000-0000-0000-0000-0000000000a1"))
                    .setInt(Saved.TARGET, 7).setInt(Saved.TICKS, 99);
            // written at the next tick, as the world's save would
            CgEngineLifecycle.onServerTick(new Runnable() {
                boolean done;

                @Override
                public void run() {
                    if (done) return;
                    done = true;
                    verify(test, obj);
                }
            });
        }

        private static void verify(Test test, CgNetObject obj) {
            PlayerFiles.File saved = obj.saveFile;
            PlayerFiles.write(saved);
            Path file = PlayerFiles.path(saved);
            Object all = file == null ? null : SaveFiles.read(file, CgCodecs.PLAIN);
            Object entry = all instanceof Map ? ((Map<?, ?>) all).get(Saved.TYPE.name()) : null;
            Map<?, ?> mine = entry instanceof Map ? (Map<?, ?>) entry : null;
            test.stated("saves.written", mine != null, "the player's file holds " + Saved.TYPE.name());
            if (mine != null) {
                StringBuilder missing = new StringBuilder();
                for (String field : SAVED) if (!mine.containsKey(field)) missing.append(' ').append(field);
                test.stated("saves.persisted", missing.length() == 0,
                        missing.length() == 0 ? "every persisted field: " + String.join(", ", SAVED) : "missing:" + missing);
                test.stated("saves.unpersisted", !mine.containsKey("target"), "the field not persisted is left out");
                test.stated("saves.local", !mine.containsKey("ticks"), "the local field is left out");
            }
            test.said("saves.file", (file == null ? "<world>/crystalgraphics/players/" + obj.player() + ".jsonc" : file.toAbsolutePath().toString()));
            try {
                if (file != null) test.said("saves.text", System.lineSeparator() + Files.readString(file));
            } catch (IOException ignored) {
                // the checks above already say whether it was written
            }
            test.done();
        }
    }
}
