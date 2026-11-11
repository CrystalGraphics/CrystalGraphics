package com.crystalgraphics.gauntlet;

import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * One test an unattended run plays: it watches the game's state, drives it, states checks, and ends. Unreal's
 * {@code UGauntletTestController}: a thin class the run makes by name ({@code -Dcrystalgraphics.gauntlet=<id>,...}),
 * whose overridable moments are the state changes and the tick, and which calls {@link #endTest} when its criteria
 * are met, or not.
 *
 * <pre>{@code
 * public final class SpawnTest extends CgGauntletController {
 *     @Override protected void onStateChange(CgGauntletState from, CgGauntletState to) {
 *         if (to.equals(CgGauntletState.TITLE)) enterWorld();
 *     }
 *
 *     @Override protected void onTick(float seconds) {
 *         if (!state().equals(CgGauntletState.IN_WORLD) || timeInState() < 3) return;
 *         check("spawn.player", CgEntityQueries.client().localPlayer() >= 0, "the local player's entity");
 *         endTest();
 *     }
 * }
 *
 * // a client listener's onPreInit, before any run starts:
 * CgGauntlet.register("mymod:spawn", SpawnTest::new);
 * }</pre>
 *
 * <p>A test that photographs what is on screen waits for the file before it ends:</p>
 *
 * <pre>{@code
 * private CgGauntletCapture.Shot shot;
 * @Override protected void onTick(float seconds) {
 *     if (shot == null) shot = capture("title");
 *     else if (shot.done()) endTest();
 * }
 * }</pre>
 *
 * <ul>
 *   <li>Its moments run on the client's render thread, or a dedicated server's thread; {@link #check} and
 *       {@link #endTest} may be called from any thread (a server half, an asynchronous read).</li>
 *   <li>The verdict is the checks': a test ends passing when none failed. One that never ends is failed by the run's
 *       timeout ({@code crystalgraphics.gauntlet.timeout}), never passed.</li>
 *   <li>Tests run one after another, in the order named, each on the state the last one left: a test that needs a
 *       world asks for one ({@link #enterWorld}) and finds it already loaded when another test loaded it.</li>
 *   <li>Release what it registered (a stage renderer, a tick function) in {@link #onEnd}.</li>
 * </ul>
 */
public abstract class CgGauntletController {

    private static final Logger LOGGER = LogManager.getLogger("CgGauntlet");

    private String id = "?";
    private int passed, failed, skipped;
    private final List<String> lines = new ArrayList<>();
    private volatile boolean ended;

    /** The run starts this test: made, then this, then {@link #onStateChange} with the state the game is in. */
    protected void onInit() {
    }

    /** The game moved from one state to another; {@link #state()} answers {@code to} already. */
    protected void onStateChange(CgGauntletState from, CgGauntletState to) {
    }

    /** Once a client frame, or a dedicated server's tick: {@code seconds} since the last. */
    protected void onTick(float seconds) {
    }

    /** The test ended, by itself or by the timeout: release what it registered. */
    protected void onEnd() {
    }

    /** Its id, as the run named it: {@code crystalgraphics:world}. */
    public final String id() {
        return id;
    }

    /** The state the game is in now. */
    protected final CgGauntletState state() {
        return CgGauntlet.state();
    }

    /** Seconds since the game entered {@link #state()}. */
    protected final double timeInState() {
        return CgGauntlet.timeInState();
    }

    /** States a check: logged as {@code <id> <name>: true (detail)} and written in the run's report. Any thread. */
    protected final synchronized void check(String name, boolean ok, String detail) {
        if (ok) passed++;
        else failed++;
        line(name + ": " + ok + " (" + detail + ")");
    }

    /** A check this game cannot be asked, and why: counted apart, never a failure. Any thread. */
    protected final synchronized void skip(String name, String reason) {
        skipped++;
        line(name + ": skipped (" + reason + ")");
    }

    /**
     * A measurement or a fact, logged and reported but never counted. "is", never ": ", so no reader takes it for a
     * check.
     */
    protected final synchronized void info(String name, String detail) {
        line(name + " is " + detail);
    }

    /** A check failed, and the test ends with it. */
    protected final void fail(String name, String reason) {
        check(name, false, reason);
        endTest();
    }

    /** Ends this test; the run moves to the next at its next tick. Any thread; a second call does nothing. */
    protected final void endTest() {
        ended = true;
    }

    /** Asks the game for a world, the run's named save or the first; true once one is on its way. */
    protected final boolean enterWorld() {
        return CgGauntlet.enterWorld();
    }

    /** Photographs what is on screen into the run's folder as {@code <id>-<name>.png}, a frame or more from now. */
    protected final CgGauntletCapture.Shot capture(String name) {
        return CgGauntletCapture.request(CgGauntlet.out().resolve(id.replace(':', '-') + "-" + name + ".png"));
    }

    /** Says the test is working, with what it is doing; the run's heartbeat logs it. Unreal's {@code MarkHeartbeatActive}. */
    protected final void markHeartbeatActive(String status) {
        CgGauntlet.markHeartbeatActive(id + ": " + status);
    }

    private void line(String text) {
        lines.add(text);
        LOGGER.info("{} {}", id, text);
    }

    // ── the run's side ───────────────────────────────────────────────────────────

    final void begin(String id) {
        this.id = id;
    }

    final boolean ended() {
        return ended;
    }

    final synchronized boolean passed() {
        return failed == 0;
    }

    /** {@code PASS (3 passed, 0 failed, 1 skipped)}, then each line. */
    final synchronized List<String> report() {
        List<String> out = new ArrayList<>();
        out.add(id + ": " + (failed == 0 ? "PASS" : "FAIL") + " (" + passed + " passed, " + failed + " failed, "
                + skipped + " skipped)");
        for (String line : lines) out.add("  " + line);
        return out;
    }
}
