package com.crystalgraphics.lifecycle;

import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.platform.rhi.CgDeviceSection;
import com.crystalgraphics.platform.gl.state.GLCheckedHostState;
import com.crystalgraphics.collision.debug.CgCollisionDebug;
import com.crystalgraphics.mc.CgHotReload;
import com.crystalgraphics.compute.CgCompute;
import com.crystalgraphics.compute.CgComputeSelfTest;
import com.crystalgraphics.compute.ops.CgGpuOpsCheck;
import com.crystalgraphics.compute.program.CgComputeCheck;
import com.crystalgraphics.frame.CgFrameBoundary;
import com.crystalgraphics.render.CgFrameClock;
import com.crystalgraphics.loading.CgLoadingScreen;
import com.crystalgraphics.loading.task.CgLoadPlan;
import com.crystalgraphics.render.CgWarmBudget;
import com.crystalgraphics.demo.CgRenderDemo;
import com.crystalgraphics.gpu.CgUploads;
import com.crystalgraphics.probe.CgWorldProbe;
import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.service.CgCacheDirectory;
import com.crystalgraphics.job.CgJob;
import com.crystalgraphics.job.CgJobLane;
import com.crystalgraphics.vfx.CgVfxRegistry;
import com.crystalgraphics.vfx.effect.CgSmokeGrenade;
import com.crystalgraphics.vfx.effect.beam.CgEnergyWave;
import com.crystalgraphics.vfx.element.CgVfxBlast;
import com.crystalgraphics.vfx.prefab.CgVfxPrefabAssets;
import com.crystalgraphics.vfx.system.CgVfxSystemAssets;
import com.crystalgraphics.api.font.CgDefaultFont;
import com.crystalgraphics.api.material.CgMaterialRegistry;
import com.crystalgraphics.render.canvas.CgCanvasMaterials;
import com.crystalgraphics.render.post.CgPostStack;
import com.crystalgraphics.render.shader.CgToolchainDevice;
import com.crystalgraphics.render.world.CgPipelinePrecache;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.vfx.CgVfx;
import com.crystalgraphics.api.CgBindingPoints;
import com.crystalgraphics.gl.buffer.CgFrameRing;
import com.crystalgraphics.gl.buffer.CgStreamBuffer;
import com.crystalgraphics.gl.buffer.CgReadback;
import com.crystalgraphics.render.CgGpuBudget;
import com.crystalgraphics.gl.buffer.shader.CgShaderBufferRegistry;
import com.crystalgraphics.gl.material.CgMaterialShader;
import com.crystalgraphics.gl.framebuffer.CgFrameBufferRegistry;
import com.crystalgraphics.gl.material.CgMaterialShaderRegistry;
import com.crystalgraphics.render.mesh.CgMeshStore;
import com.crystalgraphics.gl.texture.CgFallbackTextures;
import com.crystalgraphics.noise.CgNoiseVolumes;
import com.crystalgraphics.gl.texture.CgTextureManager;
import com.crystalgraphics.settings.CgSettings;
import com.crystalgraphics.text.cache.CgFontRegistry;
import com.crystalgraphics.NativeLoader;
import com.crystalgraphics.text.render.CgTextMaterials;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.trace.CgTraceRun;
import com.crystalgraphics.util.trace.CgChannels;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nullable;
import java.util.logging.Level;
import java.util.logging.Logger;
import lombok.Getter;
import com.crystalgraphics.render.graph.CgExecutor;
import com.crystalgraphics.shadergraph.CgPreviewPool;

/**
 * The engine's graphics context: what it builds once a context is current, its frame tick and resize, and teardown
 * of every CrystalGraphics GL resource in the correct order.
 *
 * <p>Its start and stop are issued by {@link CgEngineLifecycle}, the engine's lifecycle:
 * {@code CgEngineLifecycle.ensureGraphicsContext}, {@code destroyContext} and {@code shutdown}. A
 * host calls {@link #tickFrame()} and {@link #onResize} here directly, from its frame-end and resize hooks.</p>
 *
 * <h3>Teardown order, geometry first</h3>
 * <ol>
 *   <li>{@link CgMeshStore#releaseAll()} — its pools' buffers and its one VAO</li>
 * </ol>
 *
 * <p>After all GL objects are freed, backend-capability caches are reset so that
 * context recreation will re-probe the new context's capabilities.</p>
 */
public final class CgGraphicsLifecycle {

    private static final Logger LOGGER = Logger.getLogger(CgGraphicsLifecycle.class.getName());

    private static volatile boolean initialized = false;

    /**
     * Set by {@link #destroyContext()}, cleared only by an EXPLICIT {@link #init}.
     *
     * <p>The lazy init in {@link #ensureContext}, which a host's frame end and a stage's firing call, exists for a
     * host that never announced its context.
     * After a teardown it is harmful: every registry is gone and stays gone, so re-initialising only
     * flips this back to true and invites the next frame to bind a deleted material.</p>
     */
    private static volatile boolean destroyed = false;

    /** Set by {@link #standDown}: the host renders with no GL context, and the engine does nothing. */
    private static volatile boolean stoodDown = false;

    /**
     * -- GETTER --
     * Current window width in pixels, as last reported to 
     * ; -1 before the first resize/init. 
     */
    @Getter
    private static int currentWidth = -1;
    /**
     * -- GETTER --
     * Current window height in pixels, as last reported to 
     * ; -1 before the first resize/init. 
     */
    @Getter
    private static int currentHeight = -1;

    // ── Canonical per-frame tick ────────────────────────────────────────────
    private static final int SETTINGS = CgTrace.name("lifecycle.settings"), CHECK = CgTrace.name("lifecycle.computeCheck"),
            READBACK = CgTrace.name("lifecycle.readback"), UPLOADS = CgTrace.name("lifecycle.uploads"),
            BUDGET = CgTrace.name("lifecycle.budget"), HOT_RELOAD = CgTrace.name("lifecycle.hotReload"), LISTENERS = CgTrace.name("lifecycle.listeners"), LOAD_PLAN = CgTrace.name("lifecycle.loadPlan"),
            SELF_TESTS = CgTrace.name("lifecycle.selfTests"), RING = CgTrace.name("lifecycle.frameRing");

    /** Whether a GL context is currently initialised: what {@code CgEngineLifecycle.addListener} catches a listener up to. */
    public static boolean isInitialized() {
        return initialized;
    }

    /**
     * Stands the engine down for the rest of the session: the host is rendering, but not through a GL
     * context this engine can use — Minecraft 26.2 under its Vulkan backend. Logs {@code reason} once; every
     * entry point then returns at once and {@link #isInitialized()} stays false, so a consumer that
     * checks it before painting simply draws nothing.
     *
     * <pre>{@code
     * if (GLFW.glfwGetCurrentContext() == 0L)
     *     CgGraphicsLifecycle.standDown("no GL context on the render thread (backend: Vulkan)");
     * }</pre>
     *
     * <p>Registration is untouched: it builds no GL backend, and a dedicated server must never be asked
     * for one.</p>
     */
    public static void standDown(String reason) {
        if (stoodDown) return;
        stoodDown = true;
        initialized = false;
        LOGGER.log(Level.WARNING, "CrystalGraphics stands down for this session: " + reason);
        // Nothing would ever hide it: what a host held behind it goes on now
        CgLoadingScreen.hideNow("the engine stood down");
    }

    /** Whether {@link #standDown} was called. */
    public static boolean isStoodDown() {
        return stoodDown;
    }

    private CgGraphicsLifecycle() {}

    /**
     * Initializes engine GL resources that require an active GL context.
     * Must be called once on the GL thread after context creation,
     * before any material or fallback-texture usage.
     */
    /**
     * Whether the GL context has been torn down and not explicitly re-initialised.
     *
     * <p>What tells a <em>dead</em> resource from a <em>misused</em> one. Every registry is emptied by
     * {@link #destroyContext()}, and a host keeps dispatching render events until the process actually
     * exits — so a material bound after this point is a shutdown race, not a caller bug, and the
     * resource layer answers it by doing nothing rather than by throwing.</p>
     */
    public static boolean isContextDestroyed() {
        return destroyed;
    }

    /**
     * Initializes engine GL resources that require an active GL context.
     * Must be called once on the GL thread after context creation,
     * before any material or fallback-texture usage.
     */
    private static void init(int width, int height) {
        while (!stoodDown && !initialized) startStage(width, height);
    }

    /** The next of the start's stages: the one {@link #startStage} runs. */
    private static int stage;

    /**
     * Runs the start's next stage: one a frame from a host's frame end, so its frames go on between them (the whole start
     * at once held 1.7.10's 0.6 s and 1.20.1's 0.3 s). The last sets {@link #initialized}. Render thread.
     */
    private static void startStage(int width, int height) {
        if (stoodDown) return;
        int at = stage++;
        if (at == 0) {
            CgStartSteps.begin();
            // What the start needs that touches no GL, on a worker beside the render thread's own steps below: begun at
            // the host's client start where it said so (CgEngineLifecycle.clientStarting), else now
            startAhead();
            // A GL-era host's device, before the probe below (capabilities are the device's) and before anything
            // compiles: its first programs are the driver's own. Inside a section, which the device joins as it installs
            CgDeviceSection.begin();
            try {
                CgPlatform.startDevice();
                CgToolchainDevice.startAll();
            } finally {
                CgDeviceSection.end();
            }
            CgStartSteps.step("device");
            // The device's, probed once before anything asks for it
            CgCapabilities.detect();
            CgStartSteps.step("capabilities");
            // While the loading screen shows: a check's glGet waits for the driver's queue, deepest then
            GLCheckedHostState.deferChecksWhile(CgLoadingScreen::hostDraws);
            CgStartSteps.pause();
            return;
        }
        CgStartSteps.resume();
        CgDeviceSection.begin();
        try {
            inOwnDepthConvention(() -> startStage(at, width, height));
        } finally {
            CgDeviceSection.end();
        }
        if (!initialized) CgStartSteps.pause();
    }

    private static void startStage(int at, int width, int height) {
        switch (at) {
            case 1 -> {
                resizeTargets(width, height);
                CgBindingPoints.init(CgCapabilities.detect());
                CgFallbackTextures.init();
                // A frame's execution and the immediate draws nested in its callbacks (a HUD's text)
                CgExecutor.ahead(2);
                CgStartSteps.step("targets and fallbacks");
                CgNoiseVolumes.install();
                CgStartSteps.step("noise");
            }
            case 2 -> {
                // Before the first install that may reach what the worker initializes: two threads initializing classes
                // that name each other would deadlock
                try {
                    startAhead().join();
                } catch (RuntimeException e) {
                    LOGGER.log(Level.WARNING, "The start's work ahead failed; it runs at first use instead", e);
                }
                CgStartSteps.step("waiting for the work ahead");
                // The loading screen's own look before anything else compiles: it shows nothing until built, and queued
                // behind the engine's programs it left the host's first screen up two seconds. Its link runs while the
                // stages below take their frames.
                CgLoadingScreen.warm(width, height);
                CgStartSteps.step("loading view");
            }
            case 3 -> {
                CgWorldRenderer.get().install();
                CgStartSteps.step("world renderer");
            }
            case 4 -> {
                CgPostStack.get().install();
                CgRenderDemo.INSTANCE.install();
                CgStartSteps.step("post and demo");
            }
            case 5 -> {
                // The demo plays in PLAYS, ahead of the VFX system's EFFECTS: what it plays starts the same frame.
                CgVfx.install();
                CgStartSteps.step("vfx");
            }
            default -> finishStart(width, height);
        }
    }

    private static void finishStart(int width, int height) {
        CgCollisionDebug.install();
        CgWorldProbe.installIfEnabled();
        // The engine's own passes, built together behind the loading screen rather than each at its first draw;
        // no world stage fires until they are
        CgWorldRenderer.get().beginBoot();
        // Never after a host's shader toolchain fetch: offline it fails only after its retries and timeouts, and the
        // driver compiles these as fast as the toolchain would
        CgLoadPlan.get().task("crystalgraphics:programs").render(task -> {
            boolean done = CgWorldRenderer.get().bootStep();
            int total = CgPipelinePrecache.total();
            if (total > 0) task.progress(1f - CgPipelinePrecache.remaining() / (float) total);
            return done;
        }).start();
        // After the boot used this session's binaries, so the prune keeps them; a worker's, beside the frames.
        CgJob.run(() -> { for (String kind : CgCacheDirectory.PRUNED) CgCacheDirectory.prune(kind); });
        // From here warm-ups spread over frames: the demo's first scene took the first frame 0.7-10 s on 1.7.10.
        CgWarmBudget.start();
        CgLoadingScreen.install(width, height);
        CgStartSteps.step("loading screen");

        initialized = true;
        destroyed = false;   // an explicit init is what makes a context live again

        // Last, and after `initialized` is set: a listener may legitimately touch anything the
        // engine just brought up (pipeline, fallback textures, capability probes), and may call back
        // into isInitialized().
        CgEngineLifecycle.contextInitialized(width, height);
        CgStartSteps.step("listeners' onContextInit");
    }

    /**
     * Whether the host's frame draws with reversed depth now (Minecraft 26.2's world), as a stage's constants take it.
     * @see CgPassConstants#hostDepth()
     */
    public static boolean hostDepthReversed() {
        return depthReversed;
    }

    /** Whether the host's reversed depth runs {@code 0..1} in clip space; false where it is not reversed. */
    public static boolean hostDepthZeroToOne() {
        return depthZeroToOne;
    }

    private static boolean depthReversed, depthZeroToOne;

    /**
     * The host draws into a reversed depth buffer from here (Minecraft 26.2's world: nearer greater, cleared to 0), or
     * no longer: every pass after it into the host's depth mirrors its depth tests and clears, and a projection built
     * for the pass is built reversed. Render thread, around the host's world passes.
     *
     * <pre>{@code
     * CgGraphicsLifecycle.hostDepth(true, zeroToOne);   // zeroToOne: the host's clip-space depth runs 0..1
     * try {
     *     CgRenderStage.WORLD_OPAQUE.fire();
     * } finally {
     *     CgGraphicsLifecycle.hostDepth(false, false);
     * }
     * }</pre>
     */
    public static void hostDepth(boolean reversed, boolean zeroToOne) {
        depthReversed = reversed;
        depthZeroToOne = reversed && zeroToOne;
    }

    /**
     * Runs {@code body} with depth unmirrored, for work on our own targets: init and resize usually arrive
     * inside a world pass, which on 26.2 mirrors every depth function and clear for Minecraft's reversed-Z
     * world. @see #hostDepth
     */
    private static void inOwnDepthConvention(Runnable body) {
        boolean reversed = depthReversed, zeroToOne = depthZeroToOne;
        hostDepth(false, false);
        try {
            body.run();
        } finally {
            hostDepth(reversed, zeroToOne);
        }
    }

    /**
     * The start's work that touches no GL, on a worker, begun once: the text library's natives (about 130 ms charged to
     * whichever thread first touched a font), the built-in effects' classes (their static definitions: materials, kits,
     * YAML read from the jar, which a dedicated server initializes too, so they hold no GL). Never a texture: until the
     * render thread's first GL call claims the context, any thread passes {@code CgRHI.onDeviceThread}, and a worker loading
     * one would issue GL with no context. The text material is not warmed here: the world renderer's collector builds
     * {@code text.shader} with the engine's programs. A failure is logged and its work happens at first use instead.
     */
    static synchronized CompletableFuture<Void> startAhead() {
        if (ahead == null) ahead = CompletableFuture.runAsync(CgGraphicsLifecycle::workAhead, CgJob.executor(CgJobLane.BACKGROUND));
        return ahead;
    }

    @Nullable private static CompletableFuture<Void> ahead;

    private static void workAhead() {
        long begun = System.nanoTime();
        try {
            NativeLoader.ensureLoaded();
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "Native text library pre-load failed; it will load lazily on first use instead", t);
        }
        StringBuilder took = new StringBuilder("[start] ahead, on a worker: natives ")
                .append((System.nanoTime() - begun) / 1_000_000L);
        long font = System.nanoTime();
        try {
            CgDefaultFont.ahead();
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "Opening the default font ahead failed; it opens at first use instead", t);
        }
        took.append(", default font ").append((System.nanoTime() - font) / 1_000_000L);
        for (Class<?> c : AHEAD) {
            long at = System.nanoTime();
            try {
                Class.forName(c.getName(), true, c.getClassLoader());
            } catch (Throwable t) {
                LOGGER.log(Level.WARNING, "Initializing " + c.getName() + " ahead failed; it initializes at first use", t);
            }
            took.append(", ").append(c.getSimpleName()).append(' ').append((System.nanoTime() - at) / 1_000_000L);
        }
        LOGGER.info(took.toString());
    }

    /** Classes whose static definitions are the start's largest CPU cost (CgVfx.install's 540 ms, mostly these). */
    private static final Class<?>[] AHEAD = {CgVfxSystemAssets.class, CgVfxPrefabAssets.class, CgVfxRegistry.class,
            CgEnergyWave.class, CgVfxBlast.class, CgSmokeGrenade.class};

    /**
     * Notifies CrystalGraphics that the window has been resized.
     * Triggers recreation of all screen-sized framebuffers.
     *
     * @param width  new viewport width in pixels
     * @param height new viewport height in pixels
     */
    public static void onResize(int width, int height) {
        // Every registry below is gone after a teardown, and a host forwards its window events until
        // the process actually exits. @see CgRenderStage#fire
        if (destroyed || stoodDown) return;
        // Nothing is built yet, and init applies its own size. Returning also keeps a splash-thread
        // resize from claiming the state manager before the client thread's first frame does.
        if (!initialized) return;

        // A RESIZE FROM A FOREIGN THREAD IS DEFERRED, NOT REFUSED. A host hands this event on from
        // whatever thread its window loop runs on, and on 1.7.10 that is FML's splash thread while the
        // client thread already owns the GL shadow. Recreating framebuffers there is a cross-thread GL
        // write; the state manager throws, the splash thread dies holding the context, and the game
        // goes down in `SplashProgress.finish` with nothing naming the resize.
        //
        // Returning WITHOUT recording the size is what makes this a deferral: a stage's `ensureGraphicsContext` compares
        // the frame's dimensions against `currentWidth`/`currentHeight` and calls back here on the
        // owning thread, so the next real frame applies it.
        if (!CgRHI.onDeviceThread()) return;

        CgDeviceSection.begin();
        try {
            inOwnDepthConvention(() -> resizeTargets(width, height));
        } finally {
            CgDeviceSection.end();
        }
    }

    private static void resizeTargets(int width, int height) {
        CgExecutor.forgetHostTargets();
        CgFrameBufferRegistry.get().onResize(width, height);

        currentWidth = width;
        currentHeight = height;
    }

    /**
     * Makes sure there is a context to draw into: initialises once, and resizes when the viewport changed.
     *
     * <p>Every Minecraft host calls it at each frame's end, the title screen's included, so the engine
     * starts, and its shaders compile, before a world exists. A caller drawing before that first frame
     * end calls it too: a UI that checks {@link #isInitialized()} before painting would otherwise draw
     * nothing.</p>
     *
     * <p>Safe to call every frame: it initialises once, resizes when the viewport changed, and does
     * nothing at all after {@link #destroyContext()}. GL thread only, like everything else here.</p>
     */
    static void ensureContext(int w, int h) {
        ensureContext(w, h, false);
    }

    /** As {@link #ensureContext(int, int)}; {@code oneStage} runs only the start's next stage. */
    static void ensureContext(int w, int h, boolean oneStage) {
        if (destroyed || stoodDown) return;
        if (!initialized) {
            if (oneStage) startStage(w, h);
            else init(w, h);
        } else if (w != currentWidth || h != currentHeight) onResize(w, h);
    }

    /**
     * Canonical per-real-frame tick point for engine-owned singletons that need
     * per-frame bookkeeping — currently just {@link CgFontRegistry#tickFrame(long)}.
     * Wire additional systems here as needed, mirroring how {@link #destroyContext()}
     * enumerates every registry for teardown.
     *
     * <p><strong>Already wired — do not call this yourself.</strong> Each host's frame-end hook calls this exactly
     * once per real rendered frame, world frame or GUI-only frame alike: 1.7.10's and legacy Forge's
     * {@code CgRenderHook} at {@code updateCameraAndRender}'s tail, the modern {@code FrameHooks.endFrame}, and the
     * harness's frame loop. Feature-level code ({@code CgUiPaintContext}, demo overlays, scenes, etc.) must
     * never call this directly; doing so would advance {@code CgFrameClock} and the MSDF
     * per-frame generation budget an extra time outside the platform's actual frame
     * cadence.</p>
     *
     * <p><strong>Not for synthetic/prewarm frame sequencing.</strong> Code that
     * deliberately fast-forwards through many fake frames with no real time passing
     * (e.g. the harness's MSDF-generation prewarm loops, forcing convergence before a
     * single screenshot) must call {@link CgFontRegistry#tickFrame(long)} directly with
     * its own synthetic frame numbers instead.</p>
     */
    public static void tickFrame() {
        // As onResize: a host keeps calling this until the process exits. @see CgRenderStage#fire
        if (destroyed || stoodDown) return;

        CgFrameClock.advance();
        CgWarmBudget.nextFrame();   // first: the finishers below charge this frame's budget
        if (initialized) CgLoadingScreen.frame();   // sets that budget

        CgDeviceSection.begin();
        try {
            CgFontRegistry.get().tickFrame(CgFrameClock.frameIndex());
            try (CgTrace.Zone z = CgTrace.zone(CgChannels.MISC, SETTINGS)) {
                CgSettings.tickFrame();
            }
            if (initialized) {
                try (CgTrace.Zone z = CgTrace.zone(CgChannels.MISC, CHECK)) {
                    CgComputeCheck.endFrame();   // the frame's checked dispatches, read back
                }
                try (CgTrace.Zone z = CgTrace.zone(CgChannels.MISC, READBACK)) {
                    CgReadback.poll();   // before the listeners, which may read what landed
                }
                try (CgTrace.Zone z = CgTrace.zone(CgChannels.MISC, UPLOADS)) {
                    CgUploads.tick();
                }
                try (CgTrace.Zone z = CgTrace.zone(CgChannels.MISC, BUDGET)) {
                    CgGpuBudget.tick();
                }
                try (CgTrace.Zone z = CgTrace.zone(CgChannels.MISC, HOT_RELOAD)) {
                    CgHotReload.tick();   // before the dispatches and binds that take what it refreshed
                }
                CgFrameBoundary.run();        // after hot reload queued its saves; swaps land between frames
                CgCompute.finishPrecached();   // ahead of the dispatches that take them
                CgMaterialShader.advanceSubmits();
            }
            try (CgTrace.Zone z = CgTrace.zone(CgChannels.MISC, LISTENERS)) {
                CgEngineLifecycle.frame();
            }
            if (initialized) {
                // Inside the host section: a load task's render part may compile, upload and bind
                try (CgTrace.Zone z = CgTrace.zone(CgChannels.MISC, LOAD_PLAN)) {
                    CgLoadPlan.get().frame();
                }
            }
            if (initialized) {
                try (CgTrace.Zone z = CgTrace.zone(CgChannels.MISC, SELF_TESTS)) {
                    CgComputeSelfTest.runIfAsked();
                    CgGpuOpsCheck.runIfAsked();
                }
            }

            // Last: listeners may have streamed geometry, and it belongs to this frame's fence.
            if (initialized) {
                try (CgTrace.Zone z = CgTrace.zone(CgChannels.MISC, RING)) {
                    CgFrameRing.endFrame();
                }
            }
        } finally {
            CgDeviceSection.end();
        }
        // A host frame nothing else framed for the trace (no UI document open) is framed here, so a world with no
        // screen still records; one a document or the harness framed is left to them.
        if (CgTrace.currentFrameIndex() == traceFramed) {
            CgTrace.frameEnd();
            CgTrace.frameBegin();
        }
        traceFramed = CgTrace.currentFrameIndex();
        CgTraceRun.frame();
    }

    /** The trace frame index as {@link #tickFrame()} last left it. */
    private static long traceFramed = -1L;

    /**
     * Destroys all CrystalGraphics GL resources in canonical dependency order,
     * then resets all backend-capability caches.
     *
     * <p><strong>Must be called on the GL thread.</strong></p>
     *
     * <p>After this call, all VAOs, VBOs, IBOs, and cached GL capability flags are cleared.</p>
     *
     * <h3>This is a shutdown path, not a recycle path</h3>
     * <p><strong>Call this once, when the process is going away.</strong> There is no supported
     * destroy-then-{@link #init} cycle inside a running game, and calling {@code init}
     * again after this would not work: several singletons latch a {@code deleted} flag that nothing
     * resets. {@link CgMaterialRegistry} is the clearest case — its
     * {@code INSTANCE} is {@code static final} and its {@code checkNotDeleted()} throws
     * {@code IllegalStateException} on every subsequent {@code getOrCreate}, so the first material
     * load in a second context would fail outright. The one reset that exists,
     * {@code CgMaterialShaderRegistry.resetForTest()}, is package-private and documented as
     * test-only.</p>
     *
     * <p>An earlier version of this javadoc claimed "a new GL context can be initialised immediately
     * afterwards". It could not, and downstream code was written against that promise — hence the
     * correction here rather than a quiet deletion. Supporting genuine context recreation means
     * giving every latching singleton a real reset path first; until then, treat this as terminal.</p>
     */
    static void destroyContext() {
        // Freed once: the engine stops right after, and a second shutdown finds it stopped. Nothing was ever built
        // where the host stood down or no backend was installed (a client quitting before its first frame).
        if (destroyed || stoodDown || !CgRHI.installed()) return;

        // Step 0: External listeners, BEFORE the engine frees anything.
        //
        // This ordering is the whole contract. A listener (CrystalGUI's CgUiLifecycle, a mod's
        // renderer) owns GL objects the engine has no handle on — its own framebuffers, renderers,
        // buffers — and can only release them while the context is still whole. Run this after any
        // of the sweeps below and those handles already refer to deleted objects, and any cache the
        // listener holds of engine-owned resources (fonts, textures, materials) is silently stale.
        CgEngineLifecycle.contextDestroying();
        // Step 1: Meshes: the store's pools and its VAO. Meshes keep their data.
        CgMeshStore.get().releaseAll();

        // Step 2: Shared quad IBO.

        // Step 5: Free all cached textures.
        CgTextureManager.get().freeAll();

        // Step 5b: Free engine fallback textures and the noise volumes.
        CgFallbackTextures.destroy();
        CgNoiseVolumes.release();


        // Step 7a: Material instances (property UBOs) + their backing shader assets (GL programs).
        CgMaterialRegistry.get().deleteAll();
        CgMaterialShaderRegistry.get().deleteAll();

        // Step 7a2: Kernel programs and their blocks; the parsed files stay for the next context.
        CgCompute.releaseAll();
        CgStreamBuffer.releaseShared();   // the staging every retained shader buffer copies through
        CgReadback.releaseAll();

        // Step 7b: User-created SSBO/UBO resources managed by CgShaderBufferRegistry.
        //   Must be freed before the GL context is lost.
        CgShaderBufferRegistry.get().deleteAll();

        // Step 6a: text's and the canvas's material instances; the material registry frees them, so the caches only
        // forget them.
        CgTextMaterials.releaseAll();
        CgCanvasMaterials.releaseAll();
        
        // Step 6b: Font/glyph atlas textures + background generation executor, then reset
        //   the shared registry back to a freshly-constructed, immediately reusable state
        //   (a new GL context can be initialized right after this method returns).
        CgFontRegistry.get().releaseAll();


        // Step 8: the world renderer's draws and depth snapshot (whose framebuffer step 9 frees), and the demo's mesh,
        // before the registries they reference are torn down.
        CgRenderDemo.INSTANCE.dispose();
        CgVfx.release();
        CgCollisionDebug.release();
        CgWorldRenderer.get().release();
        CgPostStack.get().release();

        // Step 8b: Shader-graph preview targets.
        //
        // Owned by the CONTEXT rather than by any renderer, which is the point of CgPreviewPool: the
        // targets are createOwned, so no registry below sweeps them, and release previously depended on
        // every CgPreviewRenderer's owner remembering to call delete(). Now the context frees them
        // because the context owns them.
        //
        // Before the framebuffer registry, since a target holds framebuffers of its own.
        CgPreviewPool.deleteAll();

        // Step 8c: The frame graph's transient pool and rings -- createOwned framebuffers no registry reaches.
        CgExecutor.destroyAll();

        // Step 9: All owned framebuffers — must be first.
        CgFrameBufferRegistry.get().deleteAll();

        // Upload staging, once every texture above has given back its queued leases.
        CgUploads.releaseAll();

        // Its fences name the dying context.
        CgFrameRing.reset();

        // Capabilities are the dead context's.
        CgCapabilities.clearCache();

        // (initialized was cleared at the top of this method.)
        currentWidth = -1;
        currentHeight = -1;
    }

    /**
     * Stops the engine: every entry point becomes a no-op, so a host still dispatching frames after its shutdown
     * signal (Minecraft) reaches nothing freed. {@code CgEngineLifecycle.shutdown} frees first, where it may. Terminal.
     */
    static void shutdown() {
        boolean first = !destroyed;
        destroyed = true;
        initialized = false;
        if (first) CgEngineLifecycle.stopped();
    }

}
