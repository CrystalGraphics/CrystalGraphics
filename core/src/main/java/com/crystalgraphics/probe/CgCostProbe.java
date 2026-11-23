package com.crystalgraphics.probe;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nullable;

import com.crystalgraphics.api.canvas.CgCanvas;
import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.gauntlet.CgGauntletController;
import com.crystalgraphics.gauntlet.CgGauntletState;
import com.crystalgraphics.lifecycle.CgEngineLifecycle;
import com.crystalgraphics.mc.block.CgBlockState;
import com.crystalgraphics.mc.entity.CgEntityData;
import com.crystalgraphics.mc.entity.CgEntityRef;
import com.crystalgraphics.mc.level.CgStructure;
import com.crystalgraphics.mc.render.world.CgChildEntityComponent;
import com.crystalgraphics.mc.render.world.CgHostWorld;
import com.crystalgraphics.mc.render.world.CgLevelPicture;
import com.crystalgraphics.mc.render.world.CgPocketLevel;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.world.CgEntityQuery;
import com.crystalgraphics.platform.world.CgWorldStimulus;
import com.crystalgraphics.render.scene.CgScene;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.world.CgEntityQueries;

/**
 * The performance pass's world workloads (NR10): host-drawn components and pocket-level views at counts, each drawn by
 * the engine and by vanilla where vanilla draws the same, whole frames against the empty one. The gauntlet's
 * {@code crystalgraphics:cost}; it enters a world itself. The GUI's pictures are {@code crystalgraphics:pictures}' cost
 * phase.
 *
 * <pre>
 * -Dcrystalgraphics.gauntlet=crystalgraphics:cost
 * -Dcrystalgraphics.cost=components50,levels8       // these runs alone; every run by default
 * </pre>
 *
 * <p>Each run notes {@code cost.<run> is ours X ms a frame, vanilla Y ms, the empty frame Z ms; ...}. Run it uncapped
 * (vsync off, the frame limit at its highest) with {@code crystalgraphics.demo=false}, or the frames measure the cap.</p>
 * <ul>
 *   <li>Components draw the entity {@link CgWorldStimulus#stand} makes on the node (an armor stand, a pig on 1.7.10),
 *       so vanilla's side is the same entity in the level, culled and batched as vanilla does.</li>
 *   <li>Level views have no vanilla counterpart yet (NR15 adds the world drawing the same blocks): ours and the empty
 *       frame only.</li>
 * </ul>
 */
public final class CgCostProbe {

    private enum Phase { WAITING, SETTLING, TYPE, RUN, DONE }

    private enum Kind { COMPONENTS, LEVELS }

    /** A workload: {@code count} of a kind; {@code around}: three in four behind the player, where vanilla culls. */
    private record Run(String name, Kind kind, int count, boolean around) {
        Run(String name, Kind kind, int count) {
            this(name, kind, count, false);
        }
    }

    private static final List<Run> RUNS = List.of(
            new Run("components1", Kind.COMPONENTS, 1), new Run("components10", Kind.COMPONENTS, 10),
            new Run("components50", Kind.COMPONENTS, 50), new Run("components200", Kind.COMPONENTS, 200),
            new Run("around200", Kind.COMPONENTS, 200, true),
            new Run("levels1", Kind.LEVELS, 1), new Run("levels8", Kind.LEVELS, 8));

    /** Ticks a side settles before it is measured, and measures for; stands spawned a tick. */
    private static final int SETTLE = 40, MEASURE = 60, SPAWN_PER_TICK = 25;
    /** The grid of objects ahead of the player: columns, spacing in blocks, and the nearest row's distance. */
    private static final int COLUMNS = 20;
    private static final double SPACING = 1.5, AHEAD = 6, CLEAR = 48;
    private static final String ONLY = System.getProperty("crystalgraphics.cost", "");

    private static Phase phase = Phase.WAITING;
    private static int ticks, run, side, spawned;
    private static double ax, ay, az, fx, fz, rx, rz;
    @Nullable private static String standType;
    private static final List<CgChildEntityComponent> components = new ArrayList<>();
    private static final List<CgLevelPicture> views = new ArrayList<>();
    @Nullable private static CgPocketLevel level;
    @Nullable private static CgRenderStage.Registration hud;
    private static long frameSum, frameLast;
    private static int frameCount;
    private static long bytesSum;
    private static int bytesFrames;
    private static double emptyMs = -1, oursMs, oursBytes;
    private static int oursDrawn, oursCulled;
    private static boolean installed;
    private static final double[] pose = new double[CgEntityQuery.POSE_LENGTH];
    @Nullable private static volatile Test active;

    private CgCostProbe() {
    }

    /** The gauntlet's {@code crystalgraphics:cost}. */
    public static CgGauntletController test() {
        return new Test();
    }

    private static final class Test extends CgGauntletController {
        @Override
        protected void onInit() {
            active = this;
            phase = Phase.WAITING;
            ticks = 0;
            run = -1;
            side = 0;
            emptyMs = -1;
            install();
            hud = CgRenderStage.HUD.register(Integer.MAX_VALUE - 3, frame -> {
                if (phase == Phase.RUN) timeFrame();
                if (phase != Phase.RUN || run < 0 || run >= RUNS.size() || side != 0 || RUNS.get(run).kind() != Kind.LEVELS) return;
                // The views in a grid down the left of the screen, 128 units square
                CgCanvas canvas = frame.canvas();
                float s = frame.scale();
                canvas.save().scale(s, s);
                for (int i = 0; i < views.size(); i++) canvas.picture(views.get(i), 4 + 132 * (i % 4), 4 + 132 * (i / 4), 128, 128);
                canvas.restore();
            });
        }

        @Override
        protected void onStateChange(CgGauntletState from, CgGauntletState to) {
            if (to.equals(CgGauntletState.TITLE)) enterWorld();
        }

        @Override
        protected int timeoutSeconds() {
            return 600;
        }

        @Override
        protected void onEnd() {
            active = null;
            if (hud != null) hud.close();
            hud = null;
            tidy();
        }

        void noted(String name, String detail) {
            info(name, detail);
        }

        void skipped(String name, String reason) {
            skip(name, reason);
        }

        void done() {
            endTest();
        }
    }

    private static synchronized void install() {
        if (installed) return;
        installed = true;
        CgEngineLifecycle.onClientTick(CgCostProbe::tick);
    }

    private static void tick() {
        Test test = active;
        if (test == null) return;
        CgWorldStimulus stimulus = CgPlatform.get(CgWorldStimulus.SERVICE);
        CgEntityQuery entities = CgEntityQueries.client();
        int me = entities.localPlayer();
        ticks++;
        switch (phase) {
            case WAITING -> {
                if (me < 0) return;
                stimulus.keepRunning();
                next(Phase.SETTLING);
            }
            case SETTLING -> {
                if (ticks < SETTLE || !entities.pose(me, 1f, pose)) return;
                if (Math.abs(pose[CgEntityQuery.PITCH]) > 2 && stimulus.turnLook(0f, (float) -pose[CgEntityQuery.PITCH])) return;
                double yaw = Math.toRadians(pose[CgEntityQuery.YAW]);
                fx = -Math.sin(yaw);
                fz = Math.cos(yaw);
                rx = -Math.cos(yaw);
                rz = -Math.sin(yaw);
                ax = pose[CgEntityQuery.X];
                ay = pose[CgEntityQuery.Y];
                az = pose[CgEntityQuery.Z];
                stimulus.clear(ax, ay, az, CLEAR);
                // The node's stand, read back, so the components draw the same entity
                stimulus.stand(at(0)[0], ay, at(0)[2]);
                next(Phase.TYPE);
            }
            case TYPE -> {
                if (ticks < 10) return;
                double[] p = at(0);
                standType = null;
                entities.within(p[0] - 1, ay - 1, p[2] - 1, p[0] + 1, ay + 3, p[2] + 1, id -> {
                    if (standType == null && id != me) standType = entities.type(id);
                });
                stimulus.clear(ax, ay, az, CLEAR);
                if (standType == null) test.skipped("cost.components", "no stand was made here to compare with");
                startSide();
                next(Phase.RUN);
            }
            case RUN -> {
                if (run >= 0 && run < RUNS.size() && side == 1 && RUNS.get(run).kind() == Kind.COMPONENTS
                        && spawned < RUNS.get(run).count()) {
                    // Vanilla's side: the stands spawned a few a tick, the measure starting once all stand
                    for (int i = 0; i < SPAWN_PER_TICK && spawned < RUNS.get(run).count(); i++, spawned++) {
                        double[] p = at(spawned, RUNS.get(run).around());
                        stimulus.stand(p[0], ay, p[2]);
                    }
                    ticks = 0;
                    return;
                }
                if (ticks < SETTLE + MEASURE) return;
                endSide(test);
            }
            default -> { }
        }
    }

    // Grid position i ahead of the player: COLUMNS across, rows going away
    private static double[] at(int i) {
        return at(i, false);
    }

    // Around: one in four on the grid ahead, the rest on the same grid behind the player, out of view
    private static double[] at(int i, boolean around) {
        boolean behind = around && i % 4 != 0;
        int k = !around ? i : behind ? i - i / 4 - 1 : i / 4;
        double across = (k % COLUMNS - (COLUMNS - 1) / 2.0) * SPACING, rows = AHEAD + (k / COLUMNS) * SPACING;
        double ahead = behind ? -rows : rows;
        return new double[]{ax + fx * ahead + rx * across, ay, az + fz * ahead + rz * across};
    }

    private static void timeFrame() {
        long now = System.nanoTime();
        boolean empty = run < 0 || run >= RUNS.size();
        if (frameLast != 0L && ticks >= SETTLE && (empty || side == 0 || spawned >= RUNS.get(run).count())) {
            frameSum += now - frameLast;
            frameCount++;
            long bytes = empty ? -1L : RUNS.get(run).kind() == Kind.COMPONENTS ? CgHostWorld.ourBytes()
                    : CgPocketLevel.ourBytes();
            if (side == 0 && bytes >= 0L) {
                bytesSum += bytes;
                bytesFrames++;
            }
        }
        frameLast = now;
    }

    // A run this node cannot draw, skipped by name with the host's reason
    private static boolean skipped(Test test, Run r) {
        String absent = r.kind() == Kind.LEVELS ? CgPlatform.get(CgPocketLevel.HOST).absent()
                : CgPlatform.get(CgHostWorld.HOST).absent();
        if (absent == null) return false;
        test.skipped("cost." + r.name(), absent);
        return true;
    }

    private static void startSide() {
        ticks = 0;
        frameSum = frameLast = 0L;
        frameCount = 0;
        bytesSum = 0L;
        bytesFrames = 0;
        spawned = 0;
        if (run < 0 || run >= RUNS.size() || side != 0) return;
        Run r = RUNS.get(run);
        if (r.kind() == Kind.COMPONENTS && standType != null) {
            CgEntityRef ref = CgEntityRef.of(CgEntityData.parse(standType));
            for (int i = 0; i < r.count(); i++) {
                double[] p = at(i, r.around());
                components.add(CgChildEntityComponent.of(CgScene.client(), ref).at(p[0], ay, p[2]).spawn());
            }
        } else if (r.kind() == Kind.LEVELS) {
            if (level == null) {
                level = CgPocketLevel.create();
                level.place(checkerboard(), 0, 0, 0);
            }
            for (int i = 0; i < r.count(); i++) {
                float turn = (float) (i * Math.PI / 4);
                views.add(CgLevelPicture.of(level).camera(CgCamera.perspective(50f, 1f, 0.05f, 200f)
                        .lookAt(8f + 24f * (float) Math.sin(turn), 20f, 8f + 24f * (float) Math.cos(turn), 8f, 8f, 8f)));
            }
        }
    }

    private static void endSide(Test test) {
        double ms = frameSum / 1e6 / Math.max(1, frameCount);
        CgWorldStimulus stimulus = CgPlatform.get(CgWorldStimulus.SERVICE);
        if (run >= RUNS.size()) {
            test.noted("cost.empty", String.format(Locale.ROOT, "%.3f ms at the end, %.3f ms at the start (%d frames)", ms,
                    emptyMs, frameCount));
            phase = Phase.DONE;
            test.done();
            return;
        }
        if (run < 0) {
            emptyMs = ms;
        } else {
            Run r = RUNS.get(run);
            if (side == 0) {
                oursMs = ms;
                oursBytes = bytesFrames == 0 ? -1 : (double) bytesSum / bytesFrames;
                oursDrawn = CgHostWorld.drawn();
                oursCulled = CgHostWorld.culled();
            }
            boolean vanillaSide = r.kind() == Kind.COMPONENTS && standType != null;
            if (side == 1 || !vanillaSide) {
                test.noted("cost." + r.name(), String.format(Locale.ROOT, "ours %.3f ms a frame, vanilla %s (%d frames); our "
                                + "draw point allocates %s a frame%s", oursMs,
                        vanillaSide ? String.format(Locale.ROOT, "%.3f ms", ms) : "none to compare", frameCount,
                        oursBytes < 0 ? "unmeasured" : String.format(Locale.ROOT, "%.0f B", oursBytes),
                        r.kind() == Kind.COMPONENTS ? "; " + oursDrawn + " drawn, " + oursCulled + " culled" : ""));
            }
        }
        // Tidy the side just measured
        for (CgChildEntityComponent c : components) c.remove();
        components.clear();
        views.clear();
        if (side == 1) stimulus.clear(ax, ay, az, CLEAR);
        // The empty frame, then each run's sides: ours, then vanilla's where it has one
        if (run >= 0 && side == 0 && RUNS.get(run).kind() == Kind.COMPONENTS && standType != null) side = 1;
        else {
            run++;
            while (run < RUNS.size() && (!ONLY.isEmpty() && !List.of(ONLY.split(",")).contains(RUNS.get(run).name())
                    || skipped(test, RUNS.get(run)))) run++;
            side = 0;
        }
        startSide();
    }

    /** A 16-cubed 3D checkerboard of stone and air: every block shows faces, the worst a structure's mesh gets. */
    private static CgStructure checkerboard() {
        CgStructure.Builder b = CgStructure.builder(16, 16, 16);
        CgBlockState stone = CgBlockState.parse("minecraft:stone");
        for (int x = 0; x < 16; x++) for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) {
            if (((x + y + z) & 1) == 0) b.block(x, y, z, stone);
        }
        return b.build();
    }

    private static void next(Phase p) {
        phase = p;
        ticks = 0;
    }

    private static void tidy() {
        for (CgChildEntityComponent c : components) c.remove();
        components.clear();
        views.clear();
        if (level != null) level.close();
        level = null;
        phase = Phase.DONE;
    }
}
