package com.crystalgraphics.gl.lifecycle;

import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.render.CgFrameClock;
import com.crystalgraphics.demo.CgRenderDemo;
import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.gl.state.CgGlCensus;
import com.crystalgraphics.platform.gl.state.CgGlScope;
import com.crystalgraphics.platform.gl.state.CgGlState;
import com.crystalgraphics.platform.service.CgLifecycleService;
import com.crystalgraphics.api.material.CgMaterialRegistry;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.api.CgBindingPoints;
import com.crystalgraphics.gl.buffer.CgFrameRing;
import com.crystalgraphics.gl.buffer.CgQuadIndexBuffer;
import com.crystalgraphics.gl.buffer.shader.CgShaderBufferRegistry;
//import com.crystalgraphics.gl.debug.CgDebugBlit;
import com.crystalgraphics.gl.framebuffer.CgFrameBufferRegistry;
import com.crystalgraphics.gl.material.CgMaterialShaderRegistry;
import com.crystalgraphics.render.mesh.CgMeshStore;
import com.crystalgraphics.gl.texture.CgTextureCopy;
import com.crystalgraphics.gl.texture.CgFallbackTextures;
import com.crystalgraphics.gl.texture.CgTextureManager;
import com.crystalgraphics.text.cache.CgFontRegistry;
import com.crystalgraphics.NativeLoader;
import com.crystalgraphics.text.render.CgTextRenderer;
import com.crystalgraphics.text.render.CgTextRendererRegistry;
import com.crystalgraphics.trace.CgGpuTrace;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;
import java.util.logging.Level;
import java.util.logging.Logger;
import lombok.Getter;
import com.crystalgraphics.render.graph.CgExecutor;
import com.crystalgraphics.shadergraph.CgPreviewPool;

/**
 * Coordinates teardown of all CrystalGraphics GL resources in the correct order.
 *
 * <p>Call {@link #destroyContext()} exactly once when the OpenGL context is being
 * destroyed (e.g. game shutdown, render context reset).</p>
 *
 * <h3>Teardown order, geometry first</h3>
 * <ol>
 *   <li>{@link CgMeshStore#releaseAll()} — each slab's VAO, then its buffers</li>
 *   <li>{@link CgQuadIndexBuffer#freeAll()} — shared quad IBO</li>
 * </ol>
 *
 * <p>After all GL objects are freed, backend-capability caches are reset so that
 * context recreation will re-probe the new context's capabilities.</p>
 */
public final class CgGraphicsLifecycle {

    private static final Logger LOGGER = Logger.getLogger(CgGraphicsLifecycle.class.getName());

    private static volatile boolean initialized = false;

    /**
     * Set by {@link #destroyContext()}, cleared only by an EXPLICIT {@link #initContext}.
     *
     * <p>The lazy init in {@link #ensureContext}, which a stage's first firing calls, exists for a host that never
     * announced its context.
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
    private static long frameCounter = 0;

    // ── External lifecycle listeners ────────────────────────────────────────
    /**
     * Listeners owned by code outside CrystalGraphics — see {@link CgLifecycleListener}.
     *
     * <p>Storage, iteration order and failure isolation all live in
     * {@link CgLifecycleListener.Registry}; what stays here is the policy that only this class can
     * know — when a context becomes live, when it is torn down, and that a late registrant must be
     * caught up.</p>
     */
    private static final CgLifecycleListener.Registry listeners = new CgLifecycleListener.Registry();

    /**
     * Registers a lifecycle listener. Idempotent — registering the same instance twice does not
     * make it fire twice, since a double-fire of {@code onDestroy} would mean a double free.
     *
     * <p>Registration order is dispatch order for init/frame, and reverse dispatch order for
     * destroy.</p>
     *
     * <h3>Late registration still receives {@code onInit}</h3>
     * <p>If a context is already live ({@link #isInitialized()}), {@link CgLifecycleListener#onInit}
     * fires <b>immediately, from this call</b>, with the current viewport size. The guarantee a
     * listener can rely on is therefore "{@code onInit} exactly once per context, whether I
     * registered before or after that context existed" — not "only if I happened to register early
     * enough".</p>
     *
     * <p>Removing and re-adding a listener while a context is live will therefore deliver
     * {@code onInit} again — which is the intended reading of re-subscribing.</p>
     */
    public static void addListener(CgLifecycleListener listener) {
        if (!listeners.add(listener)) return;
        if (initialized) listeners.fire(listener, "onInit", l -> l.onInit(currentWidth, currentHeight));
        
    }

    /** Unregisters a listener. Safe to call from inside a callback. */
    public static boolean removeListener(CgLifecycleListener listener) {
        return listeners.remove(listener);
    }

    /** Whether a GL context is currently initialised. See {@link #addListener} for why this matters. */
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
    public static void initContext(int width, int height) {
        if (stoodDown) return;
        CgPlatform.gl().initContext();

        // Probe capabilities here, on the render thread with a live context, so CgGL.CORE is set
        // before anything can paint. Its guards read the field rather than calling detect() per GL
        // call, and a fixed-function call that beat the first probe would see false and reach a
        // backend that refuses it. Cached, so this costs one probe.
        CgCapabilities.detect();

        CgGL.fromHost();
        try {
            inOwnDepthConvention(() -> {
                // One scope for everything built below: pipeline targets, fallback textures, the text
                // material's first bind. It runs inside the host's world pass, and left its bindings and
                // render state behind.
                try (CgGlScope ignored = CgGlState.saveAll()) {
                    resizeTargets(width, height);
                    CgBindingPoints.init(CgCapabilities.detect());
                    CgFallbackTextures.init();
                    warmUpDeferredStartupCosts();
                }
                CgWorldRenderer.get().install();
                CgRenderDemo.INSTANCE.install();

                initialized = true;
                destroyed = false;   // an explicit init is what makes a context live again

                // Last, and after `initialized` is set: a listener may legitimately touch anything the
                // engine just brought up (pipeline, fallback textures, capability probes), and may call back
                // into isInitialized().
                listeners.dispatch("onInit", l -> l.onInit(width, height));
            });
        } finally {
            CgGL.toHost();
        }
    }

    /**
     * Runs {@code body} with depth unmirrored, for work on our own targets: init and resize usually arrive
     * inside a world pass, which on 26.2 mirrors every depth function and clear for Minecraft's reversed-Z
     * world. @see CgGL#setDepthReversed
     */
    private static void inOwnDepthConvention(Runnable body) {
        boolean reversed = CgGL.isDepthReversed(), zeroToOne = CgGL.isDepthZeroToOne();
        CgGL.setDepthReversed(false);
        try {
            body.run();
        } finally {
            CgGL.setDepthReversed(reversed, zeroToOne);
        }
    }

    /**
     * Pays lazily-triggered one-time costs here, where no frame is being rendered yet.
     *
     * <p>Several startup costs are lazy, so whichever frame happens to touch them first absorbs the
     * whole thing. Measured on the CJK warmup that produced two separate visible stalls: **frame 1
     * spent ~130 ms** inside the first {@code CgFont.load} (the JNI library load, charged to
     * whichever native call ran first), and **frame 2 spent ~134 ms** in {@code material.doBind}
     * compiling the text shader's first variant. Neither is avoidable work — but neither has to
     * land on a frame the user is watching.
     *
     * <p>This does not make startup faster. It moves the cost to init, where a stall is expected
     * and invisible, instead of appearing as two dropped frames after rendering has begun. Total
     * time to first *usable* frame is unchanged; time to first *smooth* frame improves.
     *
     * <p>Failures are logged and swallowed rather than propagated. Everything here is an
     * optimisation — if a platform cannot pre-warm something, the lazy path still works exactly as
     * it did before, and refusing to boot over a failed warmup would be strictly worse than the
     * hitch it was trying to avoid.
     */
    private static void warmUpDeferredStartupCosts() {
        // The JNI library backing FreeType/HarfBuzz/msdfgen. Not GL work, but it is the single
        // largest deferred cost and it lands on whichever thread first touches a font.
        try {
            NativeLoader.ensureLoaded();
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "Native text library pre-load failed; "
                    + "it will load lazily on first use instead", t);
        }

        // The text material's first bind compiles its shader variant. Doing it here means the
        // first drawn string does not.
        try {
            CgTextRenderer.warmUpMaterial();
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "Text material pre-warm failed; "
                    + "the shader will compile on first text draw instead", t);
        }
    }

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
        // Nothing is built yet, and initContext applies its own size. Returning also keeps a splash-thread
        // resize from claiming the state manager before the client thread's first frame does.
        if (!initialized) return;

        // A RESIZE FROM A FOREIGN THREAD IS DEFERRED, NOT REFUSED. A host hands this event on from
        // whatever thread its window loop runs on, and on 1.7.10 that is FML's splash thread while the
        // client thread already owns the GL shadow. Recreating framebuffers there is a cross-thread GL
        // write; the state manager throws, the splash thread dies holding the context, and the game
        // goes down in `SplashProgress.finish` with nothing naming the resize.
        //
        // Returning WITHOUT recording the size is what makes this a deferral: a stage's `ensureContext` compares
        // the frame's dimensions against `currentWidth`/`currentHeight` and calls back here on the
        // owning thread, so the next real frame applies it.
        if (!CgGlState.manager().ownedByCurrentThread()) return;

        // Rebuilds screen-sized targets, binding textures and framebuffers as it goes; the host's come back.
        CgGL.fromHost();
        try {
            inOwnDepthConvention(() -> {
                try (CgGlScope ignored = CgGlState.saveAll()) {
                    resizeTargets(width, height);
                }
            });
        } finally {
            CgGL.toHost();
        }
    }

    private static void resizeTargets(int width, int height) {
        CgFrameBufferRegistry.get().onResize(width, height);
        CgTextRendererRegistry.get().onResize(width, height);

        currentWidth = width;
        currentHeight = height;
    }

    /**
     * Makes sure there is a context to draw into, for a caller about to draw OUTSIDE the world pass.
     *
     * <p>The engine initialises lazily on the first world render, which is the right moment for
     * anything drawn in a world and the wrong one for everything else: on a title screen or a menu
     * there is no world pass, so nothing here ever ran and {@link #isInitialized()} stayed false. A UI
     * that politely checks before painting then drew NOTHING, and since Minecraft only clears the
     * colour buffer when it renders a level, the frame still held the previous screen — so a
     * screenshot came back showing the main menu and read as a working UI that had simply not been
     * asked to draw.</p>
     *
     * <pre>{@code
     * // in a Screen's render(), before painting:
     * CgGraphicsLifecycle.ensureContext(window.getWidth(), window.getHeight());
     * }</pre>
     *
     * <p>Safe to call every frame: it initialises once, resizes when the viewport changed, and does
     * nothing at all after {@link #destroyContext()}. GL thread only, like everything else here.</p>
     */
    public static void ensureContext(int w, int h) {
        if (destroyed || stoodDown) return;
        if (!initialized) initContext(w, h);
        else if (w != currentWidth || h != currentHeight) onResize(w, h);
    }

    /**
     * Canonical per-real-frame tick point for engine-owned singletons that need
     * per-frame bookkeeping — currently just {@link CgFontRegistry#tickFrame(long)}.
     * Wire additional systems here as needed, mirroring how {@link #destroyContext()}
     * enumerates every registry for teardown.
     *
     * <p><strong>Already wired — do not call this yourself.</strong> Each platform's
     * {@code CgLifecycleService.onFrameRendered()} implementation calls this exactly
     * once per real rendered frame (world frame or GUI-only frame alike): 1.7.10's
     * {@code LifecycleService1710}, 1.20.x's {@code LifecycleService}, and the
     * harness's {@code LifecycleServiceHarness} each delegate their {@code
     * onFrameRendered()} straight here. That is the only place this method should be
     * invoked from — see {@link CgLifecycleService#onFrameRendered()}'s contract.
     * Feature-level code ({@code CgUiPaintContext}, demo overlays, scenes, etc.) must
     * never call this directly; doing so would tick the frame counter and the MSDF
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

        frameCounter++;

        CgGlCensus.at("frame");
        CgGL.fromHost();
        try {
            // Frame boundary: trust nothing about GL state. Control was outside CrystalGraphics between
            // frames, so anything could have written state through an API we cannot observe.
            CgGlState.invalidateAllIfPresent();

            CgFontRegistry.get().tickFrame(frameCounter);
            listeners.dispatch("onFrame", l -> l.onFrame(frameCounter));

            // And again AFTER dispatch. Listeners are third-party code that may render, and anything they
            // wrote lands after the invalidation above — leaving the shadow stale for the rest of the frame.
            // Two integer writes per frame is not a cost worth reasoning about; a silently elided GL call is.
            CgGlState.invalidateAllIfPresent();

            // Last: listeners may have streamed geometry, and it belongs to this frame's fence.
            if (initialized) CgFrameRing.endFrame();
            CgFrameClock.advanceToNow();
        } finally {
            CgGL.toHost();
        }
    }

    /**
     * Returns the current authoritative frame number, as last advanced by
     * {@link #tickFrame()}. Callers that need a {@code frame} argument for
     * {@code CgTextRenderer.draw(...)}'s atlas-LRU bookkeeping should read this instead
     * of maintaining their own local frame counter.
     */
    public static long getCurrentFrame() {
        return frameCounter;
    }

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
     * destroy-then-{@link #initContext} cycle inside a running game, and calling {@code initContext}
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
    public static void destroyContext() {
        // Nothing was ever built, and the sweeps below would call into nothing: a stood-down host has no GL
        // context, and with no backend installed no host section ever opened, as on a client quitting
        // before its first frame.
        if (stoodDown || !CgGL.isInstalled()) {
            shutdown();
            return;
        }

        // Step 0: External listeners, BEFORE the engine frees anything.
        //
        // This ordering is the whole contract. A listener (CrystalGUI's CgUiLifecycle, a mod's
        // renderer) owns GL objects the engine has no handle on — its own framebuffers, renderers,
        // buffers — and can only release them while the context is still whole. Run this after any
        // of the sweeps below and those handles already refer to deleted objects, and any cache the
        // listener holds of engine-owned resources (fonts, textures, materials) is silently stale.
        // Scoped: a listener releasing its renderers unbinds their materials behind the host.
        try (CgGlScope ignored = CgGlState.saveAll()) {
            listeners.dispatchReverse("onDestroy", CgLifecycleListener::onDestroy);
        }

        // Step 1: Meshes: the store's slabs, each slab's VAO, then its buffers. Meshes keep their data.
        CgMeshStore.get().releaseAll();

        // Step 2: Shared quad IBO.
        CgQuadIndexBuffer.freeAll();

        // Step 5: Free all cached textures.
        CgTextureManager.get().freeAll();

        // Step 5b: Free engine fallback textures.
        CgFallbackTextures.destroy();


        // Step 7a: Material instances (property UBOs) + their backing shader assets (GL programs).
        CgMaterialRegistry.get().deleteAll();
        CgMaterialShaderRegistry.get().deleteAll();

        // Step 7b: User-created SSBO/TBO/UBO resources managed by CgShaderBufferRegistry.
        //   Must be freed before the GL context is lost.
        CgShaderBufferRegistry.get().deleteAll();

        // Step 6a: All CgTextRenderer instances still alive (backstop for callers that
        //   forgot to call delete() themselves).
        CgTextRendererRegistry.get().deleteAll();
        
        // Step 6b: Font/glyph atlas textures + background generation executor, then reset
        //   the shared registry back to a freshly-constructed, immediately reusable state
        //   (a new GL context can be initialized right after this method returns).
        CgFontRegistry.get().releaseAll();


        // Step 8: the world renderer's draws and depth snapshot (whose framebuffer step 9 frees), and the demo's mesh,
        // before the registries they reference are torn down.
        CgRenderDemo.INSTANCE.dispose();
        CgWorldRenderer.get().release();

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


        // Step 10: Debug utilities (lazy singleton — no-op if never used).
//        CgDebugBlit.dispose();

        // Scratch framebuffers used by the GPU-side texture copy path (lazily created —
        // no-op if no texture ever grew). Safe to reuse after this; they are recreated on demand.
        CgTextureCopy.dispose();

        // Its fences name the dying context.
        CgFrameRing.reset();

        // Capabilities are the dead context's.
        CgCapabilities.clearCache();

        // (initialized was cleared at the top of this method.)
        currentWidth = -1;
        currentHeight = -1;
        frameCounter = 0;
        
        shutdown();
    }

    /**
     * Stops the engine without freeing anything — for a host that is <b>still rendering</b>.
     *
     * <p>Every entry point below becomes a no-op, and nothing is released: at process exit the OS
     * reclaims it regardless, and releasing early is what leaves the engine half-dead while frames are
     * still arriving. Minecraft dispatches render stages after its shutdown signal, which is that
     * case exactly.</p>
     *
     * <p>Prefer {@link #destroyContext()} where rendering has definitively stopped and the resources
     * should be released. Both are terminal: neither supports a later {@link #initContext}.</p>
     */
    public static void shutdown() {
        boolean first = !destroyed;
        destroyed = true;
        initialized = false;
        if (first) listeners.dispatch("onShutdown", CgLifecycleListener::onShutdown);
    }

}
