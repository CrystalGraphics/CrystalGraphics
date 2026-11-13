package com.crystalgraphics.demo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import com.crystalgraphics.gauntlet.CgGauntlet;
import com.crystalgraphics.gauntlet.CgGauntletController;
import com.crystalgraphics.gauntlet.CgGauntletState;
import com.crystalgraphics.loading.watchdog.CgLoadWatchdog;
import com.crystalgraphics.platform.rhi.pipeline.CgPipelineRecipes;
import com.crystalgraphics.variable.CgVar;

/**
 * {@code crystalgraphics:pipelines}: the shipped pipeline list recorded. It enters a world and shows every scene of
 * {@link CgRenderDemo} in turn, twice round, each for a few seconds once it is built and standing on the ground; every
 * pipeline a draw built is appended to the cache folder's {@code recorded.txt}, which it copies into the run's folder.
 * Unreal's PSO gathering run (a {@code -logPSO} run under Gauntlet).
 *
 * <pre>{@code
 * // CrystalGUI's root, Minecraft on Vulkan, into an empty cache folder
 * ./gradlew :runtime:mc:modern:neoforge:26.2:runClient -PcgAcceptEula -PcgGraphics=vulkan \
 *     -Dcrystalgraphics.gauntlet=crystalgraphics:pipelines -Dcrystalgraphics.gauntlet.out=build/pipelines \
 *     -Dcrystalgraphics.cache.dir=build/pipelines-cache
 * // build/pipelines/report.txt says PASS; build/pipelines/recorded.txt goes over
 * // CrystalGraphics/core/src/main/resources/assets/crystalgraphics/pipelines/recorded.txt
 * }</pre>
 *
 * <ul>
 *   <li>The cache folder must start empty: the list it copies is the folder's whole file, and a check fails when that
 *       holds lines this run did not write.</li>
 *   <li>A scene's seconds ({@code crystalgraphics.gauntlet.pipelines.seconds}, 5) count from when it draws, so a scene
 *       still building loses none of them; one that does not draw within 90 s fails.</li>
 *   <li>Its timeout is its own: the scenes, the laps and their seconds, with room to build each.</li>
 *   <li>The loading watchdog is armed for the run: a switch that holds a frame is logged with what held it.</li>
 *   <li>A run recording nothing passes and copies nothing: the shipped list already builds every pipeline the
 *       scenes draw.</li>
 *   <li>A new draw path no scene reaches records nothing: give it a scene first.</li>
 * </ul>
 */
public final class CgDemoPipelinesTest extends CgGauntletController {

    private static final CgVar.Int SECONDS = CgVar.integer("crystalgraphics.gauntlet.pipelines.seconds", 5, 1, 600,
            CgVar.DEV | CgVar.RESTART, "Seconds each demo scene draws in crystalgraphics:pipelines, once it is built.");
    private static final int LAPS = 2;
    /** Seconds a scene has to build and stand on the ground. */
    private static final int SHOW_LIMIT = 90;
    /** The first seconds after a scene draws, timed apart from the rest: where a switch's hitches land. */
    private static final double EARLY = 2;

    private final CgRenderDemo demo = CgRenderDemo.INSTANCE;
    private final List<String> scenes = demo.scenes();
    /** The scene showing, counted over every lap; -1 before the first. */
    private int step = -1;
    private boolean drawing;
    /** Seconds since the step's scene was asked for, then since it drew. */
    private double waited, drawn;
    private int earlyFrames, lateFrames;
    private double earlyMs, earlyWorst, lateMs;
    private CompletableFuture<Void> written;

    @Override
    protected void onInit() {
        if (!CgRenderDemo.enabled()) {
            fail("pipelines.demo", "the demo is off (crystalgraphics.demo=false): no scene draws");
            return;
        }
        CgLoadWatchdog.arm(true);
    }

    @Override
    protected int timeoutSeconds() {
        return LAPS * scenes.size() * (SECONDS.get() + SHOW_LIMIT) + 600;
    }

    @Override
    protected void onStateChange(CgGauntletState from, CgGauntletState to) {
        if (to.equals(CgGauntletState.TITLE)) enterWorld();
    }

    @Override
    protected void onTick(float seconds) {
        if (written != null) {
            if (written.isDone()) finish();
            return;
        }
        if (!state().equals(CgGauntletState.IN_WORLD)) return;
        if (step < 0) {
            next();
            return;
        }
        String name = scenes.get(step % scenes.size());
        if (!drawing) {
            waited += seconds;
            if (demo.showing(name)) {
                drawing = true;
                markHeartbeatActive(name + ", lap " + (lap() + 1) + ", drawing after "
                        + String.format(Locale.ROOT, "%.1f", waited) + " s");
            } else if (waited > SHOW_LIMIT) {
                check("pipelines." + name, false, "not drawing " + SHOW_LIMIT + " s after it was asked for");
                next();
            }
            return;
        }
        drawn += seconds;
        double ms = seconds * 1000.0;
        if (drawn <= EARLY) {
            earlyFrames++;
            earlyMs += ms;
            earlyWorst = Math.max(earlyWorst, ms);
        } else {
            lateFrames++;
            lateMs += ms;
        }
        if (drawn < SECONDS.get()) return;
        String times = String.format(Locale.ROOT, "drawing after %.1f s; first %.0f s %d frames, mean %.1f ms, worst %.1f ms;"
                        + " then %.1f ms", waited, EARLY, earlyFrames, mean(earlyMs, earlyFrames), earlyWorst,
                mean(lateMs, lateFrames));
        if (lap() == 0) check("pipelines." + name, true, times);
        else info("pipelines." + name + ".lap" + (lap() + 1), times);
        next();
    }

    @Override
    protected void onEnd() {
        CgLoadWatchdog.arm(false);
    }

    private int lap() {
        return step / scenes.size();
    }

    /** The next step's scene asked for; past the last lap, the list's last lines awaited. */
    private void next() {
        step++;
        drawing = false;
        waited = drawn = 0;
        earlyFrames = lateFrames = 0;
        earlyMs = earlyWorst = lateMs = 0;
        if (step < LAPS * scenes.size()) demo.show(scenes.get(step % scenes.size()));
        else written = CgPipelineRecipes.shared().written();
    }

    /** The list checked and copied into the run's folder. */
    private void finish() {
        CgPipelineRecipes recipes = CgPipelineRecipes.shared();
        Path file = recipes.file();
        int appended = recipes.appended();
        info("pipelines.recorded", appended + " recipes this run");
        if (file == null) {
            check("pipelines.list", false, "no cache folder to record into");
            endTest();
            return;
        }
        if (appended == 0) {
            check("pipelines.list", true, "nothing to add: the shipped list builds every pipeline the scenes draw");
            endTest();
            return;
        }
        try {
            long lines = Files.readAllLines(file, StandardCharsets.UTF_8).stream().filter(l -> !l.isEmpty()).count();
            check("pipelines.cache", lines == appended, lines == appended ? "the folder held only this run's"
                    : lines + " lines, " + appended + " of them this run's: start from an empty cache folder");
            Path out = CgGauntlet.out().resolve("recorded.txt");
            Files.createDirectories(out.getParent());
            Files.copy(file, out, StandardCopyOption.REPLACE_EXISTING);
            check("pipelines.list", true, out.toAbsolutePath().toString());
        } catch (IOException e) {
            check("pipelines.list", false, "copying " + file + ": " + e);
        }
        endTest();
    }

    private static double mean(double sum, int n) {
        return n > 0 ? sum / n : 0;
    }
}
