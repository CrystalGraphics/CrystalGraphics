package com.crystalgraphics.gauntlet;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.crystalgraphics.lifecycle.CgEngineLifecycle;
import com.crystalgraphics.lifecycle.CgLifecycleListener;
import com.crystalgraphics.loading.CgLoadingScreen;
import com.crystalgraphics.net.object.CgNetProbe;
import com.crystalgraphics.platform.io.CgAtomicFile;
import com.crystalgraphics.platform.service.CgGameDirectory;
import com.crystalgraphics.platform.service.CgGameSession;
import com.crystalgraphics.platform.service.CgServers;
import com.crystalgraphics.probe.CgCollisionProbe;
import com.crystalgraphics.probe.CgServerSmoke;
import com.crystalgraphics.probe.CgWorldProbe;
import com.crystalgraphics.variable.CgVar;

/**
 * An unattended run of the game: the tests named on the command line, played one after another, each driving the game
 * itself, then a report and the game's own shutdown. Unreal's Gauntlet module ({@code FGauntletModule}): it makes the
 * controllers {@code -gauntlet=} names, broadcasts the game's state to them, ticks them, and ends the run when they end.
 * Something outside the game (CrystalGUI's {@code prodSmoke}) launches it and reads the report.
 *
 * <pre>{@code
 * // the JVM's flags: the engine's boot test, then the world probe; the report and captures under build/run/
 * -Dcrystalgraphics.gauntlet=crystalgraphics:boot,crystalgraphics:world -Dcrystalgraphics.gauntlet.out=build/run
 *
 * // a mod's own test, from a listener's onPreInit (client tests from a client listener's)
 * CgGauntlet.register("mymod:spawn", SpawnTest::new);
 * }</pre>
 *
 * <p>The report, {@code <out>/report.txt}: its first line is {@code PASS} or {@code FAIL}, then a line per test and
 * one per check. <b>An absent report is a failure</b>: a game that died before the run ended wrote none.</p>
 *
 * <pre>{@code
 * PASS
 * crystalgraphics:boot: PASS (2 passed, 0 failed, 0 skipped)
 *   boot.engine: true (the graphics started)
 *   ...
 * }</pre>
 *
 * <ul>
 *   <li>Tests run in the order named, each on the state the last left. Unreal ticks every controller at once; here the
 *       tests share one game, and one kills the player (the net probe), so they take turns.</li>
 *   <li>A name nothing registered fails the run when its turn comes, by name: a client listener may register its
 *       tests after the run starts.</li>
 *   <li>Each test has {@code crystalgraphics.gauntlet.timeout} seconds (300): one that has not ended by then fails.</li>
 *   <li>On a dedicated server the run ticks on the server's ticks and stops the server at its end.</li>
 * </ul>
 */
public final class CgGauntlet {

    private static final Logger LOGGER = LogManager.getLogger("CgGauntlet");

    private static final CgVar.Text RUN = CgVar.text("crystalgraphics.gauntlet", "", CgVar.DEV | CgVar.RESTART,
            "The tests an unattended run plays, by id and in order, separated by commas or by + where a launcher splits "
                    + "commas (crystalgraphics:boot,crystalgraphics:world); empty for no run.");
    private static final CgVar.Text OUT = CgVar.text("crystalgraphics.gauntlet.out", "", CgVar.DEV | CgVar.RESTART,
            "Where a run writes its report and captures; empty for <game>/crystalgraphics/gauntlet.");
    private static final CgVar.Text WORLD = CgVar.text("crystalgraphics.gauntlet.world", "", CgVar.DEV | CgVar.RESTART,
            "The save a run's tests enter; empty for the first on disk, or one the host makes.");
    private static final CgVar.Int TIMEOUT = CgVar.integer("crystalgraphics.gauntlet.timeout", 300, 1, 86400,
            CgVar.DEV | CgVar.RESTART, "Seconds a run's test has to end before it fails.");
    private static final CgVar.Int HEARTBEAT = CgVar.integer("crystalgraphics.gauntlet.heartbeat", 0, 0, 3600,
            CgVar.DEV | CgVar.RESTART, "Seconds between a run's heartbeat lines; 0 for none.");

    /** Seconds the run waits for its captures before it quits anyway. */
    private static final double CAPTURE_WAIT = 20;

    private static final Map<String, Supplier<? extends CgGauntletController>> TESTS = new LinkedHashMap<>();

    private enum Phase { WAITING, RUNNING, QUITTING, DONE }

    private static volatile Phase phase = Phase.WAITING;
    private static volatile CgGauntletState state = CgGauntletState.INITIALIZED;
    @Nullable private static volatile CgGauntletState broadcast;
    private static long stateNanos = System.nanoTime(), testNanos, quitNanos, lastTickNanos, heartbeatNanos;
    private static final List<String> names = new ArrayList<>();
    private static final List<CgGauntletController> finished = new ArrayList<>();
    private static final List<String> refused = new ArrayList<>();
    @Nullable private static CgGauntletController current;
    private static int next;
    private static boolean heartbeatActive;
    private static String heartbeatStatus = "";
    /** A test asked for a world, and whether the host has taken the request. */
    private static boolean worldWanted, worldAsked;

    static {
        register("crystalgraphics:boot", CgGauntletBootTest::new);
        register("crystalgraphics:play", CgGauntletPlayTest::new);
        register("crystalgraphics:world", CgWorldProbe::test);
        register("crystalgraphics:collision", CgCollisionProbe::test);
        register("crystalgraphics:net", CgNetProbe::test);
        register("crystalgraphics:server", CgServerSmoke::new);
    }

    private CgGauntlet() {
    }

    /**
     * Makes the test {@code id} playable: {@code factory} is asked for one when a run names it. Before the run starts,
     * a listener's {@code onPreInit} at the latest. A second id wins over the first, said in the log.
     */
    public static synchronized void register(String id, Supplier<? extends CgGauntletController> factory) {
        if (TESTS.put(id, factory) != null) LOGGER.warn("gauntlet: '{}' registered twice; the last wins", id);
    }

    /** Whether this process plays a run. */
    public static boolean running() {
        return !RUN.get().isEmpty();
    }

    /**
     * Whether this process's run names {@code id}: for what a test needs before the run starts (a network type is
     * declared at init, before any test is made). Any time.
     */
    public static boolean plays(String id) {
        for (String name : names()) {
            if (name.equals(id)) return true;
        }
        return false;
    }

    /** The game's state now. */
    public static CgGauntletState state() {
        return state;
    }

    /** Seconds since the game entered {@link #state()}. */
    public static double timeInState() {
        return (System.nanoTime() - stateNanos) / 1.0e9;
    }

    /**
     * Moves the run to a state of a mod's own, for every test to hear; the engine's own states are broadcast for it.
     * Unreal's {@code BroadcastStateChange}. Any thread; heard at the next tick.
     */
    public static void broadcastStateChange(CgGauntletState to) {
        broadcast = to;
    }

    /**
     * Asks the game for a world, the run's named save or the first: asked again each tick on the title screen until the
     * host takes it, since a host may not take it yet. True once one is on its way or loaded. Render thread.
     *
     * @throws IllegalStateException when the host has no save to load and cannot make one
     */
    public static boolean enterWorld() {
        if (CgGameSession.inWorld()) return true;
        worldWanted = true;
        if (!worldAsked) {
            String save = WORLD.get();
            worldAsked = CgGameSession.enterWorld(save.isEmpty() ? null : save);
        }
        return worldAsked;
    }

    /** Where the run writes its report and captures. */
    public static Path out() {
        String set = OUT.get();
        return set.isEmpty() ? CgGameDirectory.game().resolve("crystalgraphics").resolve("gauntlet") : Paths.get(set);
    }

    /** Marks the run as working, with what it is doing; logged at once and by the next heartbeat. */
    public static void markHeartbeatActive(String status) {
        heartbeatActive = true;
        heartbeatStatus = status;
        LOGGER.info("gauntlet heartbeat: active {}", status);
    }

    // ── the run ──────────────────────────────────────────────────────────────────

    /** Starts the run the flags ask for, from pre-init; nothing without one. */
    public static synchronized void start() {
        if (!running() || phase != Phase.WAITING) return;
        names.addAll(names());
        phase = Phase.RUNNING;
        CgEngineLifecycle.addListener(LISTENER);
        CgEngineLifecycle.onServerTick(() -> {
            if (CgServers.dedicated()) tick();
        });
        LOGGER.info("gauntlet: running {}", names);
    }

    /** The run's test ids in order. Prism's config reads a comma as a list separator, so prodSmoke joins them with +. */
    private static List<String> names() {
        List<String> names = new ArrayList<>();
        for (String name : RUN.get().split("[,+]")) {
            if (!name.trim().isEmpty()) names.add(name.trim());
        }
        return names;
    }

    private static final CgLifecycleListener LISTENER = new CgLifecycleListener() {
        @Override
        public void onFrame(long frame) {
            CgGauntletCapture.frameEnd();
            tick();
        }

        @Override
        public void onShutdown() {
            if (phase == Phase.RUNNING) {
                refused.add("the game stopped before the run ended" + (current == null ? "" : ", in " + current.id()));
                finishRun();
            }
        }
    };

    private static void tick() {
        long now = System.nanoTime();
        float seconds = lastTickNanos == 0 ? 0f : (now - lastTickNanos) / 1.0e9f;
        lastTickNanos = now;
        if (phase == Phase.QUITTING) {
            quitWhenWritten(now);
            return;
        }
        if (phase != Phase.RUNNING) return;
        heartbeat(now);
        CgGauntletState was = state;
        CgGauntletState is = observe();
        if (!is.equals(was)) {
            state = is;
            stateNanos = now;
            LOGGER.info("gauntlet state: {} -> {}", was, is);
            if (is.equals(CgGauntletState.IN_WORLD)) worldWanted = worldAsked = false;
            if (is.equals(CgGauntletState.TITLE)) worldAsked = false;
            if (current != null) safely(current, c -> c.onStateChange(was, is));
        }
        if (current == null && !beginNext(now)) return;
        if (worldWanted && !worldAsked && state.equals(CgGauntletState.TITLE)) safely(current, c -> enterWorld());
        CgGauntletController test = current;
        safely(test, c -> c.onTick(seconds));
        if (!test.ended() && (now - testNanos) / 1.0e9 > TIMEOUT.get()) {
            test.fail("gauntlet.timeout", "not ended after " + TIMEOUT.get() + " s, in state " + state);
        }
        if (test.ended()) endCurrent();
    }

    /** Where the game is: a state of a mod's own once broadcast, else the engine's reading. */
    private static CgGauntletState observe() {
        CgGauntletState asked = broadcast;
        if (asked != null) {
            broadcast = null;
            return asked;
        }
        if (CgServers.dedicated()) return CgGauntletState.SERVER;
        if (CgLoadingScreen.shown() || !CgGameSession.ready()) return CgGauntletState.LOADING;
        return CgGameSession.inWorld() ? CgGauntletState.IN_WORLD : CgGauntletState.TITLE;
    }

    /** Makes and starts the next named test; false when the run is over. */
    private static boolean beginNext(long now) {
        while (next < names.size()) {
            String id = names.get(next++);
            Supplier<? extends CgGauntletController> factory;
            synchronized (CgGauntlet.class) {
                factory = TESTS.get(id);
            }
            if (factory == null) {
                refused.add("no test is registered as '" + id + "' (registered: " + TESTS.keySet() + ")");
                LOGGER.error("gauntlet: no test is registered as '{}'", id);
                continue;
            }
            CgGauntletController test;
            try {
                test = factory.get();
            } catch (RuntimeException | LinkageError failed) {
                refused.add("'" + id + "' could not be made: " + failed);
                LOGGER.error("gauntlet: '{}' could not be made", id, failed);
                continue;
            }
            test.begin(id);
            current = test;
            testNanos = now;
            LOGGER.info("gauntlet: {} starts in state {}", id, state);
            safely(test, CgGauntletController::onInit);
            safely(test, c -> c.onStateChange(CgGauntletState.INITIALIZED, state));
            return true;
        }
        finishRun();
        return false;
    }

    private static void endCurrent() {
        CgGauntletController test = current;
        current = null;
        safely(test, CgGauntletController::onEnd);
        finished.add(test);
        LOGGER.info("gauntlet: {} ended, {}", test.id(), test.passed() ? "passing" : "failing");
    }

    /** A test that throws fails, and the run goes on. */
    private static void safely(CgGauntletController test, Consumer<CgGauntletController> moment) {
        try {
            moment.accept(test);
        } catch (RuntimeException | LinkageError failed) {
            LOGGER.error("gauntlet: {} threw", test.id(), failed);
            test.fail("gauntlet.threw", failed.toString());
        }
    }

    private static void finishRun() {
        boolean ok = refused.isEmpty() && !finished.isEmpty();
        for (CgGauntletController test : finished) ok &= test.passed();
        List<String> lines = new ArrayList<>();
        lines.add(ok ? "PASS" : "FAIL");
        if (finished.isEmpty() && refused.isEmpty()) lines.add("gauntlet: no test ran");
        for (String why : refused) lines.add("gauntlet: " + why);
        for (CgGauntletController test : finished) lines.addAll(test.report());
        Path report = out().resolve("report.txt");
        try {
            CgAtomicFile.write(report, (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            LOGGER.error("gauntlet: could not write {}", report, e);
        }
        LOGGER.info("**** TEST COMPLETE. EXIT CODE: {} **** ({})", ok ? 0 : 1, report.toAbsolutePath());
        phase = Phase.QUITTING;
        quitNanos = System.nanoTime();
    }

    /** Quits once every capture is on disk, or after {@link #CAPTURE_WAIT}: a read lands frames after it is asked. */
    private static void quitWhenWritten(long now) {
        if (CgGauntletCapture.pending() > 0 && (now - quitNanos) / 1.0e9 < CAPTURE_WAIT) return;
        phase = Phase.DONE;
        LOGGER.info("gauntlet: quitting");
        if (CgServers.dedicated()) CgServers.halt();
        else CgGameSession.quit();
    }

    private static void heartbeat(long now) {
        int every = HEARTBEAT.get();
        if (every <= 0 || (now - heartbeatNanos) / 1.0e9 < every) return;
        heartbeatNanos = now;
        LOGGER.info("gauntlet heartbeat: {} {}", heartbeatActive ? "active" : "idle", heartbeatStatus);
        heartbeatActive = false;
        heartbeatStatus = "";
    }
}
