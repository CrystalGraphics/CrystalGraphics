package com.crystalgraphics.loading;

import com.crystalgraphics.platform.rhi.CgExternalTarget;
import com.crystalgraphics.lifecycle.CgGraphicsLifecycle;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import org.joml.Matrix4f;

import com.crystalgraphics.api.framebuffer.CgFrameBufferFormat;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.asset.load.CgVfxAssets;
import com.crystalgraphics.compute.CgCompute;
import com.crystalgraphics.gl.framebuffer.CgFrameBuffer;
import com.crystalgraphics.gl.material.CgMaterialShader;
import com.crystalgraphics.gl.texture.CgTextureAbstract;
import com.crystalgraphics.loading.task.CgLoadPlan;
import com.crystalgraphics.loading.task.CgLoadTask;
import com.crystalgraphics.loading.task.CgLoadTracker;
import com.crystalgraphics.loading.view.CgLoadingView;
import com.crystalgraphics.loading.view.CgLogoLoadingView;
import com.crystalgraphics.loading.watchdog.CgLoadWatchdog;
import com.crystalgraphics.render.graph.CgExecutor;
import com.crystalgraphics.render.CgWarmBudget;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.draw.CgPipeline;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.render.world.CgPipelinePrecache;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;
import com.crystalgraphics.variable.CgVar;
import com.crystalgraphics.vfx.CgVfx;
import com.crystalgraphics.vfx.CgVfxSystem;

/**
 * The engine's one loading screen, Lyra's {@code ULoadingScreenManager}: shown while any {@link CgLoadingProcess} is
 * still loading, then held until a span of frames passes with no first-use work (no pipeline built at its draw, no
 * program waited for, no texture or asset loaded at its first use), then hidden. Every host has it from launch.
 *
 * <pre>{@code
 * CgLoadingScreen.add(() -> arena.ready() ? null : "building the arena");   // a process: "still loading, because"
 * CgLoadingScreen.Task join = CgLoadingScreen.task("joining the world");    // work started and ended in code
 * join.close();
 * CgLoadingScreen.onVisibility(shown -> { if (!shown) startClock(); });    // a harness scene's frame 1 at the hide
 *
 * // a host, each frame, on the render thread:
 * if (CgLoadingScreen.loading()) skipWorld();                // the world is not drawn while it loads
 * CgLoadingScreen.draw(framebuffer, width, height);          // over everything, while shown or fading out
 * }</pre>
 *
 * <p>A host whose own first screen must wait (Minecraft's first menu, or its Quick Play) holds it behind this one:</p>
 *
 * <pre>{@code
 * if (CgLoadingScreen.holdUntilHidden(() -> openFirstScreen())) return;   // where the host opens it: skipped for now
 * // each frame:
 * if (CgLoadingScreen.hostDraws()) CgLoadingScreen.draw(fbo, width, height);   // after the host's GUI
 * CgLoadingScreen.runHeld();                                               // after the frame's tick: opens it once hidden
 * }</pre>
 *
 * <ul>
 *   <li><b>The engine's own processes</b> (pipelines being built, assets loading, effects warming and rehearsing) keep a
 *       showing screen up but never raise a hidden one: a look precached at runtime spreads over frames under the
 *       warm budget, it does not bring the screen back. A process added with {@link #add} raises it.</li>
 *   <li><b>While shown, warming runs at loading-screen budgets</b> ({@link CgWarmBudget#loading}), as Unreal's PSO
 *       cache goes to its fast batch mode.</li>
 *   <li><b>Why it shows is always logged</b>, at each change of reason and at the hide with how long it took;
 *       {@code crystalgraphics.loading.logEveryFrame} logs it every frame.</li>
 *   <li>Its frames are marked in the trace: {@code loading.show} and {@code loading.hide} markers, and the counter
 *       {@code loading.shown} on each frame it shows, so a profile tells loading from steady state.</li>
 *   <li>What it draws is a {@link CgLoadingView}: the engine's logo over a load bar unless {@link #view} replaces it.</li>
 *   <li>A held opening runs once the screen hides, and at once if the engine stands down: nothing is held forever.</li>
 *   <li>Render thread.</li>
 * </ul>
 */
public final class CgLoadingScreen {

    /** Whether the screen shows: as its processes ask, never, or until the run ends. */
    public enum Mode { AUTO, NEVER, ALWAYS }

    private static final CgVar.Choice<Mode> MODE = CgVar.choice("crystalgraphics.loading.screen", Mode.AUTO,
            "The loading screen: AUTO while anything loads, NEVER (a scene that measures its own loading), ALWAYS (to look at it).");
    private static final CgVar.Bool LOG_EVERY_FRAME = CgVar.bool("crystalgraphics.loading.logEveryFrame", false,
            CgVar.DEV, "Log why the loading screen shows on every frame it shows.");
    private static final CgVar.Bool LOG_PROGRESS = CgVar.bool("crystalgraphics.loading.logProgress", false,
            CgVar.DEV, "Log the bar and the running load tasks once a second while the loading screen shows.");
    private static long lastProgressLog;
    private static final CgVar.Int HOLD_FRAMES = CgVar.integer("crystalgraphics.loading.holdFrames", 30, 0, 100_000,
            "Frames in a row with no first-use work the loading screen holds for once nothing loads, before it hides.");
    private static final CgVar.Num MAX_HOLD = CgVar.number("crystalgraphics.loading.maxHoldSeconds", 10f, 0f, 600f,
            "The longest the loading screen holds once nothing loads, however much first-use work goes on.");
    private static final CgVar.Num MAX_LOADING = CgVar.number("crystalgraphics.loading.maxSeconds", 120f, 0f, 3600f,
            "The longest the loading screen shows for a process still loading before it holds anyway, warning which.");
    /** Seconds the screen takes to fade out after it hides: its parts leave in turn (CgLogoLoadingView). */
    private static final float FADE_OUT = 1.4f;
    /** Seconds it takes to fade in from its first frame, over the host's own loading screen. */
    private static final float FADE_IN = 0.9f;
    /** The most a frame moves either fade: a held frame advances it one frame's worth, never skips it. */
    private static final float FADE_STEP = 1f / 30f;
    /** The fade-out is over this long after the hide whatever was drawn: a host that stops drawing it still opens. */
    private static final long FADE_OUT_LATEST_NANOS = 5_000_000_000L;
    /** The fade-in's and the fade-out's seconds so far, on frames drawn. */
    private static float appeared, left;
    /** The last {@link #draw}'s time, for the fades' steps. */
    private static long lastDrawn;
    /** The last {@link #draw}'s size: the host's window. */
    private static int windowWidth, windowHeight;
    /** The view answered {@link CgLoadingView#ready}: it draws, and its fade-in runs. */
    private static boolean viewReady;
    /** How long past the fade the watchdog still names what holds the render thread. */
    private static final long WATCH_AFTER_FADE_NANOS = 2_000_000_000L;

    private static final Logger LOGGER = LogManager.getLogger(CgLoadingScreen.class);
    private static final int SHOWN = CgTrace.name("loading.shown");

    private static final List<CgLoadingProcess> RAISING = new CopyOnWriteArrayList<>();
    private static final List<CgLoadingProcess> ENGINE = new CopyOnWriteArrayList<>();
    private static final List<Consumer<Boolean>> LISTENERS = new CopyOnWriteArrayList<>();

    /** Shown, held or hidden. */
    private enum State { LOADING, HOLDING, HIDDEN }

    private static State state = State.LOADING;
    @Nullable private static String reason = "starting";
    private static long shownAt = System.nanoTime(), holdingAt, hiddenAt, lastFrame;
    private static int framesShown, quietFrames;
    private static long lastFirstUse = -1;
    private static float shownProgress, targetProgress;
    private static boolean installed;
    /** The engine's processes outlasted {@code maxSeconds}: they no longer keep this showing up. */
    private static boolean gaveUp;
    /** What it draws: the engine's look until a consumer sets its own. */
    private static CgLoadingView view = new CgLogoLoadingView();
    /** The host's opening held behind the screen, or null. */
    @Nullable private static Runnable held;
    /** The host drew the screen or held its opening here, so it draws it until the fade ends. */
    private static boolean hostDraws;

    /** Whether a host fired a world stage since the last {@link #frame()}: if none did, the screen fires its own. */
    private static boolean worldFired;
    /** The world frame's target while no host draws a world: small, since nobody sees it. */
    private static final int WARM_SIZE = 256;
    private static final CgRenderStage[] WORLD = {CgRenderStage.WORLD_OPAQUE, CgRenderStage.WORLD_TRANSPARENT};
    private static final float WARM_FOV = (float) Math.toRadians(70), WARM_NEAR = 0.05f, WARM_FAR = 512f;
    private static final Matrix4f WARM_VIEW = new Matrix4f(),
            WARM_PROJECTION = new Matrix4f().perspective(WARM_FOV, 1f, WARM_NEAR, WARM_FAR);
    private static CgFrameBuffer warmTarget;
    /** The host world's depth format, as {@link #hostWorld} described it; its convention is the lifecycle's. */
    private static CgTextureType worldDepth = CgTextureType.DEPTH24_STENCIL8;

    private CgLoadingScreen() {
    }

    static {
        // The screen's own look, built at the graphics start with the engine's passes, before its first frame draws
        CgPipelinePrecache.register("crystalgraphics:loading", pipelines -> pipelines.addPass(CgLogoLoadingView.FX, CgInstanceKind.QUAD));
    }

    /** Adds {@code process}: while it is loading the screen shows, raised again if it had hidden. Any thread. */
    public static void add(CgLoadingProcess process) {
        RAISING.add(process);
    }

    /** Removes a process {@link #add}ed. Any thread. */
    public static void remove(CgLoadingProcess process) {
        RAISING.remove(process);
    }

    /**
     * Work started and ended in code, Lyra's {@code ULoadingProcessTask}: the screen shows until it is closed.
     *
     * <pre>{@code
     * try (CgLoadingScreen.Task task = CgLoadingScreen.task("joining the world")) {
     *     ...   // or keep it, and close() when the join completes
     * }
     * }</pre>
     */
    public static Task task(String reason) {
        Task task = new Task(reason);
        RAISING.add(task);
        return task;
    }

    /** Hears {@code true} when the screen shows and {@code false} when it hides. Render thread. */
    public static void onVisibility(Consumer<Boolean> listener) {
        LISTENERS.add(listener);
    }

    /** Stops {@code listener} hearing. */
    public static void removeVisibilityListener(Consumer<Boolean> listener) {
        LISTENERS.remove(listener);
    }

    /** Replaces what the screen draws: a consumer's look. Render thread. */
    public static void view(CgLoadingView replacement) {
        view = replacement;
    }

    /**
     * Holds {@code open}, the host's own first screen, until this one hides; false, holding nothing, if it is not
     * showing. The host skips its opening when this returns true. Render thread.
     */
    public static boolean holdUntilHidden(Runnable open) {
        if (!shown() || MODE.get() == Mode.NEVER) {
            // The held opening's own call, once released, passes through here again
            if (!releasing) LOGGER.info("[loading] the host's first screen opens at once: {}", shown() ? "the screen is off" : "loaded already");
            return false;
        }
        held = open;
        clearedFrame = false;
        hostDraws = true;
        LOGGER.info("[loading] the host's first screen waits for the hide, the fade-in {}% in",
                Math.round(Math.min(1f, appeared / FADE_IN) * 100f));
        return true;
    }

    /**
     * Whether the host draws this screen: while it shows, from the engine's start, and until its fade-out ends. Over
     * the host's own loading screen too, so the fade-in crosses from it: begun only once the host opened its first
     * screen, Minecraft's menu showed through the whole fade-in.
     */
    public static boolean hostDraws() {
        if (shown()) return true;
        if (hostDraws && fadedOut(System.nanoTime())) hostDraws = false;
        return hostDraws;
    }

    /** Asks the view whether it draws whole yet, until it does: each ask advances its build a step. */
    private static void askView(long now) {
        if (viewReady || !(viewReady = view.ready())) return;
        LOGGER.info("[loading] the view is ready after {} ms{}", (now - shownAt) / 1_000_000L,
                held == null ? ", under the host's loading screen" : ", after the host's first screen opened");
    }

    /** Hidden and its fade-out over: drawn to the end, or out of time. */
    private static boolean fadedOut(long now) {
        return !shown() && hiddenAt != 0L && (left >= FADE_OUT || now - hiddenAt >= FADE_OUT_LATEST_NANOS);
    }

    /**
     * Opens the held screen once this one has hidden and faded out; nothing otherwise. The host's frame end, after the
     * tick. After the fade, because a held Quick Play blocks the client thread seconds reading the world, and the
     * frame left up meanwhile would be this screen, frozen at its end (the user: the freeze is vanilla's to show).
     */
    public static void runHeld() {
        Runnable open = held;
        if (open == null || !fadedOut(System.nanoTime())) return;
        // A frame without the screen shown first: the frame the window keeps through a blocking open is the last one
        // presented, and the fade's last frame still holds the logo faintly
        if (!clearedFrame) {
            clearedFrame = true;
            LOGGER.info("[loading] faded out over {} frames, the last {}% through", fadeFrames, Math.round(fadeLastLeave * 100f));
            return;
        }
        held = null;
        releasing = true;
        try {
            open.run();
        } finally {
            releasing = false;
        }
    }

    /** Inside {@link #runHeld}: the held opening is running. */
    private static boolean releasing;
    /** A frame has ended past the fade, drawing none of this screen: the held opening runs at the next. */
    private static boolean clearedFrame;
    /** The fade-out's frames drawn, and the last one's opacity: logged as the held opening runs. */
    private static int fadeFrames;
    private static float fadeLastLeave;

    /** Hides it now, whatever loads: the engine stood down, and nothing would hide it. */
    public static void hideNow(String why) {
        if (!shown()) return;
        LOGGER.warn("[loading] hidden: {}", why);
        hide(System.nanoTime());
    }

    /** Whether the screen covers the frame: loading, or holding once loaded. */
    public static boolean shown() {
        return state != State.HIDDEN;
    }

    /**
     * The host's window, as it last drew the screen; 0 before. A world stage the screen fires draws into a 256-pixel
     * target, so anything sized from the window (a HUD's text) reads it here to warm at its real size.
     *
     * <pre>{@code
     * int side = CgLoadingScreen.shown() && CgLoadingScreen.windowHeight() > 0
     *         ? Math.min(CgLoadingScreen.windowWidth(), CgLoadingScreen.windowHeight())
     *         : Math.min(frame.host().width(), frame.host().height());
     * }</pre>
     */
    public static int windowWidth() {
        return windowWidth;
    }

    /** See {@link #windowWidth}. */
    public static int windowHeight() {
        return windowHeight;
    }

    /** Whether a process is still loading: the world is not drawn behind the screen until it holds. */
    public static boolean loading() {
        return state == State.LOADING;
    }

    /** Why it shows: the first process still loading, "holding" while it holds, or null hidden. */
    @Nullable
    public static String reason() {
        return reason;
    }

    /** The bar: 0 to 1, smoothed, never backwards. */
    public static float progress() {
        return shownProgress;
    }

    /**
     * Starts building what the screen's first frame draws at {@code width} x {@code height}, so it appears whole rather
     * than glyph by glyph: its view's {@link CgLoadingView#warm}. At the graphics start for the engine's own looks; a
     * consumer replacing the view after it calls it again. Render thread.
     */
    public static void warm(int width, int height) {
        if (width > 0 && height > 0) view.warm(width, height);
    }

    /** The engine's own processes, added once at the graphics start, and the view warmed. The lifecycle's. */
    public static void install(int width, int height) {
        if (installed) return;
        installed = true;
        warm(width, height);
        LOGGER.info("[loading] shown: {}", reason);
        // First of the stage: a host's own world firing this frame means the screen need not fire one.
        CgRenderStage.WORLD_OPAQUE.register(Integer.MIN_VALUE, frame -> worldFired = true);
        ENGINE.add(new CgLoadingProcess() {
            @Override
            public String loading() {
                int left = CgPipelinePrecache.remaining();
                return left > 0 ? "building pipelines" : null;
            }

            @Override
            public float progress() {
                int total = CgPipelinePrecache.total();
                return total == 0 ? Float.NaN : 1f - CgPipelinePrecache.remaining() / (float) total;
            }
        });
        ENGINE.add(() -> {
            CgVfxAssets assets = CgVfxAssets.instance();
            return assets != null && assets.inFlight() > 0 ? "loading assets" : null;
        });
        ENGINE.add(() -> {
            CgVfxSystem vfx = CgVfx.world();
            return vfx != null && !vfx.ready() ? "warming effects" : null;
        });
    }

    /**
     * Once a frame, before what it draws: asks the processes, steps the hold, and sets the warm budget. Every host's
     * frame tick runs it. Render thread.
     */
    public static void frame() {
        long now = System.nanoTime();
        CgLoadWatchdog.beat();
        float dt = lastFrame == 0L ? 0f : Math.min(0.25f, (now - lastFrame) / 1e9f);
        lastFrame = now;
        Mode mode = MODE.get();
        reasons.clear();
        String raised = collect(RAISING, "loading:"), asked = raised != null ? raised : shown() && !gaveUp ? collect(ENGINE, "crystalgraphics:") : null;
        trackReasons(now);
        if (asked == null && shown() && !gaveUp && CgLoadPlan.get().busy()) asked = planReason();
        if (mode == Mode.ALWAYS) asked = asked != null ? asked : "always shown";
        if (mode == Mode.NEVER) asked = null;
        switch (state) {
            case HIDDEN -> {
                if (raised != null && mode != Mode.NEVER || mode == Mode.ALWAYS) show(asked, now);
            }
            case HOLDING -> {
                if (asked != null) {
                    toLoading(asked, now);
                } else if (heldEnough(now) || mode == Mode.NEVER) {
                    hide(now);
                }
            }
            case LOADING -> {
                // ALWAYS asks again every frame: giving up on it would flip to holding and back each frame
                if (asked != null && raised == null && mode != Mode.ALWAYS && (now - shownAt) / 1e9f >= MAX_LOADING.get()) {
                    LOGGER.warn("[loading] still {} after {} s; holding anyway", asked, MAX_LOADING.get());
                    gaveUp = true;
                    asked = null;
                }
                if (asked == null) {
                    hold(now);
                } else if (!asked.equals(reason)) {
                    reason = asked;
                    LOGGER.info("[loading] {}", asked);
                }
            }
        }
        if (shown()) {
            if (framesShown == 0) CgLoadTracker.since(now);   // the bar starts with the first frame anyone sees
            framesShown++;
            CgTrace.counter(CgChannels.MISC, SHOWN, 1);
            if (LOG_EVERY_FRAME.get()) LOGGER.info("[loading] frame {}: {}", framesShown, reason);
        }
        reportAfterHide(now);
        CgWarmBudget.loading(shown());
        CgVfxAssets.inPlay(!shown() && hiddenAt != 0L);   // a load from here on compiles in game time
        // From the engine's start, not the first draw: a host draws nothing of it while its own loading screen is up,
        // and a view first asked then showed the host's first screen for half a second as it built
        if (shown()) askView(now);
        // Through the fade-out and a little after: a hold there freezes the screen as it leaves
        CgLoadWatchdog.arm(shown() || hiddenAt != 0L && (!fadedOut(now) || now - hiddenAt < (long) (FADE_OUT * 1e9f) + WATCH_AFTER_FADE_NANOS));
        worldFired = false;
        targetProgress = state == State.LOADING ? Math.max(targetProgress, CgLoadTracker.progress(now)) : 1f;
        if (shown() && LOG_PROGRESS.get() && now - lastProgressLog >= 1_000_000_000L) {
            lastProgressLog = now;
            StringBuilder s = new StringBuilder("[loading] ").append((now - shownAt) / 1_000_000L).append(" ms: bar ")
                    .append(Math.round(targetProgress * 100f)).append("%, running");
            for (CgLoadTask t : CgLoadTracker.running()) s.append(' ').append(t.id());
            LOGGER.info(s.toString());
        }
        // Minecraft's overlay eases toward the reload's progress: 0.95 of the last plus 0.05 of the new, a frame at 60.
        float k = 1f - (float) Math.pow(0.95, dt * 60f);
        shownProgress = Math.max(shownProgress, shownProgress + (targetProgress - shownProgress) * k);
    }

    /**
     * Draws the screen over {@code framebuffer} at {@code width} x {@code height} pixels, while it shows and through its
     * fade-out. A host calls it last each frame, inside the frame, inside a host section. Where the host fired no world
     * stage this frame, it fires its own first. Render thread.
     *
     * <pre>{@code
     * CgLoadingScreen.draw(mainFramebuffer, width, height);   // the host's target; 0 for the window's
     * }</pre>
     *
     * <p>The framebuffer is named, not taken as bound: a host binding its own with its own calls (Minecraft's
     * {@code bindWrite}) is unseen by the state shadow, and the warm frame's firing binds a target of its own.</p>
     */
    public static void draw(int framebuffer, int width, int height) {
        draw(framebuffer, null, width, height);
    }

    /** {@link #draw(int, int, int)} over a target given whole: a framebuffer of ours, as its {@code external()}. */
    public static void draw(CgExternalTarget target, int width, int height) {
        draw(-1, target, width, height);
    }

    private static void draw(int framebuffer, @Nullable CgExternalTarget target, int width, int height) {
        long now = System.nanoTime();
        float step = lastDrawn == 0L ? 0f : Math.min(FADE_STEP, (now - lastDrawn) / 1e9f);
        lastDrawn = now;
        windowWidth = width;
        windowHeight = height;
        boolean warm = false;
        if (shown()) {
            // Not while the engine's programs build behind it: a world firing would draw passes not built yet
            warm = !worldFired && !CgWorldRenderer.get().booting();
            // Nothing of its own until the view draws whole: the host's screen shows meanwhile. Asked here too, not
            // only in frame(): each ask advances its build a step, and the workers had the frame's GUI since
            askView(now);
            if (viewReady) appeared += step;
            hostDraws = true;   // its fade-out is drawn too, held opening or not
        } else {
            if (hiddenAt == 0L || !viewReady || fadedOut(now)) return;
            left += step;
            fadeFrames++;
            fadeLastLeave = Math.min(1f, left / FADE_OUT);
        }
        if (warm) warmFrame();
        // Never wait for the view's build here: it held the frame 220 ms cold
        if (!viewReady) return;
        // The host's framebuffer, or the target given, as the current target, nothing bound
        try (CgExecutor.Into into = CgExecutor.into(target != null ? target : CgExternalTarget.host(framebuffer, width, height))) {
            view.draw(width, height, shownProgress, Math.min(1f, appeared / FADE_IN),
                    shown() ? 0f : Math.min(1f, left / FADE_OUT), shown() ? reason : null);
        }
    }

    /**
     * Fires one warm frame now, as {@link #draw} does behind the screen each frame it shows: the world stages into a
     * {@value #WARM_SIZE}x{@value #WARM_SIZE} target of its own, from its fixed view. For a gate measuring what one
     * costs (the harness's {@code warm-frame-load}). Render thread, inside a host section.
     */
    public static void fireWarmFrame() {
        warmFrame();
    }

    /**
     * Fires the world stages into a target of its own: pipelines settle and effects warm and rehearse only in a world
     * frame, and behind the screen a host may draw none (Minecraft's menus, a harness scene not started).
     */
    private static void warmFrame() {
        if (warmTarget == null) {
            warmTarget = CgFrameBuffer.create("cg_loading_world", WARM_SIZE, WARM_SIZE, CgFrameBufferFormat
                    .builder("cg_loading_world").color(0, CgTextureType.RGBA8).depth(worldDepth).build());
        }
        try (CgExecutor.Into ignored = CgExecutor.into(warmTarget.external())) {
            CgGraphicsLifecycle.inWorldDepth(() -> {
                for (CgRenderStage stage : WORLD) {
                    stage.host().set(0f, WARM_SIZE, WARM_SIZE, warmTarget.external()).view().set(0, 0, 0, WARM_VIEW, WARM_PROJECTION);
                    stage.fire();
                }
                return true;
            });
        }
        worldFired = false;
    }

    /**
     * How the host draws its world: its target's depth format and its depth convention. The world frame the screen
     * fires while no world draws then draws as the world will, so what it builds is what the world draws with: on a
     * Vulkan device a pipeline is built for its target's formats and its depth test. The host's, once, before the
     * first {@link #draw}; GL's standard depth into {@code DEPTH24_STENCIL8} otherwise. Render thread.
     *
     * <pre>{@code
     * CgLoadingScreen.hostWorld(CgTextureType.DEPTH32F, true, true);   // Minecraft 26.2: reversed, 0..1, D32_FLOAT
     * }</pre>
     */
    public static void hostWorld(CgTextureType depth, boolean reversed, boolean zeroToOne) {
        if (depth == worldDepth && reversed == CgGraphicsLifecycle.worldDepthReversed()
                && (reversed && zeroToOne) == CgGraphicsLifecycle.worldDepthZeroToOne()) return;
        worldDepth = depth;
        // Its warm frame and every precache polled outside a world pass build what the world's draws take
        CgGraphicsLifecycle.worldDepth(reversed, zeroToOne);
        // A projection built for the pass is built reversed under a reversed convention (CgGraphicsLifecycle.hostDepth)
        if (reversed) WARM_PROJECTION.setPerspective(WARM_FOV, 1f, WARM_FAR, WARM_NEAR, zeroToOne);
        else WARM_PROJECTION.setPerspective(WARM_FOV, 1f, WARM_NEAR, WARM_FAR, zeroToOne);
        if (warmTarget != null) {
            warmTarget.delete();
            warmTarget = null;
        }
    }

    /** Whether the hold has seen enough quiet frames, or run as long as it may. */
    private static boolean heldEnough(long now) {
        long work = firstUseWork();
        if (work != lastFirstUse) {
            lastFirstUse = work;
            quietFrames = 0;
        } else {
            quietFrames++;
        }
        if (quietFrames >= HOLD_FRAMES.get()) return true;
        if ((now - holdingAt) / 1e9f >= MAX_HOLD.get()) {
            LOGGER.warn("[loading] hidden after holding {} s: first-use work never stopped", MAX_HOLD.get());
            return true;
        }
        return false;
    }

    /** How long after the hide the first-use report waits: the done-when's first minute in the world. */
    private static final long AFTER_HIDE_NANOS = 60_000_000_000L;
    /** Each first-use counter at the hide, for the report a minute after; null once reported. */
    @Nullable private static long[] atHide;

    /** Each first-use counter: pipelines missed at their draw, compile waits, texture loads, sync asset loads. */
    private static long[] firstUseCounts() {
        CgVfxAssets assets = CgVfxAssets.instance();
        return new long[]{CgPipeline.misses(), CgMaterialShader.compileWaits(), CgTextureAbstract.loads(),
                assets == null ? 0L : assets.syncLoads()};
    }

    /** A minute after the hide: what first-use work the screen let through, which should be none. */
    private static void reportAfterHide(long now) {
        if (atHide == null || state != State.HIDDEN || now - hiddenAt < AFTER_HIDE_NANOS) return;
        long[] c = firstUseCounts();
        long missed = c[0] - atHide[0], waits = c[1] - atHide[1], textures = c[2] - atHide[2], sync = c[3] - atHide[3];
        atHide = null;
        if (missed + waits + textures + sync == 0) {
            LOGGER.info("[loading] the minute after the hide did no first-use work");
        } else {
            LOGGER.warn("[loading] the minute after the hide: {} pipelines built at their draw, {} compile waits, {} textures"
                    + " loaded, {} assets loaded at their use (crystalgraphics.pipeline.logMisses names the pipelines)",
                    missed, waits, textures, sync);
        }
    }

    /**
     * Every first use the engine counts (pipelines built at their draw, kernels at their dispatch, compile waits, texture
     * and asset loads): what a hold waits to stop. Unchanged across frames, nothing is being built.
     *
     * <pre>{@code
     * long before = CgLoadingScreen.firstUseWork();
     * ... a frame ...
     * boolean settled = CgLoadingScreen.firstUseWork() == before;
     * }</pre>
     */
    public static long firstUseWork() {
        CgVfxAssets assets = CgVfxAssets.instance();
        return CgPipeline.misses() + CgCompute.dispatchBuilds() + CgMaterialShader.compileWaits() + CgTextureAbstract.loads()
                + (assets == null ? 0L : assets.syncLoads());
    }

    /** This frame's reasons, as task ids, each with its process's progress (NaN when it gives none). */
    private static final Map<String, Float> reasons = new LinkedHashMap<>();
    /** The reason tasks running: each ends the frame its reason is no longer given. */
    private static final Set<String> openReasons = new HashSet<>();

    /** Every reason {@code list}'s processes give, into {@link #reasons} under {@code prefix}; the first, or null. */
    @Nullable
    private static String collect(List<CgLoadingProcess> list, String prefix) {
        String first = null;
        for (int i = 0; i < list.size(); i++) {
            CgLoadingProcess p = list.get(i);
            String why;
            float progress;
            try {
                why = p.loading();
                progress = why == null ? Float.NaN : p.progress();
            } catch (RuntimeException e) {
                LOGGER.error("[loading] a process threw; it is dropped", e);
                list.remove(i--);
                continue;
            }
            if (why == null) continue;
            if (first == null) first = why;
            reasons.putIfAbsent(prefix + why, progress);
        }
        return first;
    }

    /** Each reason given is a task while it is given: the tracker's record and the bar see processes as they see plans. */
    private static void trackReasons(long now) {
        for (Map.Entry<String, Float> r : reasons.entrySet()) {
            CgLoadTracker.reason(r.getKey(), r.getValue(), now);
            openReasons.add(r.getKey());
        }
        for (Iterator<String> it = openReasons.iterator(); it.hasNext(); ) {
            String id = it.next();
            if (reasons.containsKey(id)) continue;
            CgLoadTracker.reasonEnded(id, now);
            it.remove();
        }
    }

    /** Why it shows while only plan tasks run: the oldest running, or waiting. */
    private static String planReason() {
        List<CgLoadTask> running = CgLoadTracker.running();
        return running.isEmpty() ? "loading" : running.get(0).id();
    }

    private static void show(String why, long now) {
        state = State.LOADING;
        reason = why;
        shownAt = now;
        gaveUp = false;
        framesShown = 0;
        shownProgress = targetProgress = 0f;
        CgLoadTracker.shown(now);
        LOGGER.info("[loading] shown: {}", why);
        CgTrace.marker(CgChannels.MISC, "loading.show", why);
        tell(true);
    }

    private static void toLoading(String why, long now) {
        state = State.LOADING;
        reason = why;
        LOGGER.info("[loading] {} (after {} ms held)", why, (now - holdingAt) / 1_000_000L);
    }

    private static void hold(long now) {
        state = State.HOLDING;
        holdingAt = now;
        quietFrames = 0;
        lastFirstUse = firstUseWork();
        reason = "holding";
        LOGGER.info("[loading] loaded after {} ms; holding", (now - shownAt) / 1_000_000L);
    }

    private static void hide(long now) {
        state = State.HIDDEN;
        hiddenAt = now;
        left = 0f;
        // Never drawn: nothing to fade
        if (!viewReady) left = FADE_OUT;
        reason = null;
        LOGGER.info("[loading] hidden after {} ms, {} frames ({} ms held)", (now - shownAt) / 1_000_000L, framesShown,
                (now - holdingAt) / 1_000_000L);
        LOGGER.info("[loading] assets at the hide: {}", CgVfxAssets.instance().summary());
        CgTrace.marker(CgChannels.MISC, "loading.hide", framesShown + " frames");
        atHide = firstUseCounts();
        for (String id : openReasons) CgLoadTracker.reasonEnded(id, now);
        openReasons.clear();
        CgLoadTracker.hidden(now);
        tell(false);
        LOGGER.info("[loading] the render thread was held by work {} times and waited {} times",
                CgLoadWatchdog.stalls(), CgLoadWatchdog.waits());
        if (CgLoadWatchdog.strict() && CgLoadWatchdog.stalls() > 0) {
            throw new IllegalStateException("[loading] the render thread was held by work " + CgLoadWatchdog.stalls()
                    + " times while loading (crystalgraphics.loading.strict): see the watchdog's reports");
        }
    }

    private static void tell(boolean shown) {
        List<Consumer<Boolean>> listeners = new ArrayList<>(LISTENERS);
        for (Consumer<Boolean> l : listeners) {
            try {
                l.accept(shown);
            } catch (RuntimeException e) {
                LOGGER.error("[loading] a visibility listener threw", e);
            }
        }
    }

    /** Work started and ended in code: loading until {@link #close()}d. */
    public static final class Task implements CgLoadingProcess, AutoCloseable {
        private final String reason;
        private volatile boolean done;

        private Task(String reason) {
            this.reason = reason;
        }

        @Override
        public String loading() {
            return done ? null : reason;
        }

        /** Ends it; any thread, more than once. */
        @Override
        public void close() {
            done = true;
            RAISING.remove(this);
        }
    }
}
