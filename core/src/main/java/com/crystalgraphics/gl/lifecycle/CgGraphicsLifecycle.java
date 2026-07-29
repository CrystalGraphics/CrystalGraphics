package com.crystalgraphics.gl.lifecycle;

import com.crystalgraphics.demo.CgRenderDemo;
import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.service.CgLifecycleService;
import com.crystalgraphics.api.material.CgMaterialRegistry;
import com.crystalgraphics.api.render.CgRenderPipeline;
import com.crystalgraphics.gl.buffer.CgQuadIndexBuffer;
import com.crystalgraphics.gl.buffer.shader.CgShaderBufferRegistry;
//import com.crystalgraphics.gl.debug.CgDebugBlit;
import com.crystalgraphics.gl.framebuffer.CgFrameBufferRegistry;
import com.crystalgraphics.gl.material.CgMaterialShaderRegistry;
import com.crystalgraphics.gl.mesh.CgMeshRegistry;
import com.crystalgraphics.gl.render.CgInstanceRenderer;
import com.crystalgraphics.gl.texture.CgTextureCopy;
import com.crystalgraphics.gl.texture.CgFallbackTextures;
import com.crystalgraphics.gl.texture.CgTextureManager;
import com.crystalgraphics.gl.vertex.CgInstanceVertexArrayBinding;
import com.crystalgraphics.gl.vertex.CgVertexArray;
import com.crystalgraphics.gl.vertex.CgVertexArrayRegistry;
import com.crystalgraphics.gl.vertex.CgVertexBufferRegistry;
import com.crystalgraphics.text.cache.CgFontRegistry;
import com.crystalgraphics.NativeLoader;
import com.crystalgraphics.text.render.CgTextRenderer;
import com.crystalgraphics.text.render.CgTextRendererRegistry;
import java.util.logging.Level;
import java.util.logging.Logger;
import lombok.Getter;

/**
 * Coordinates teardown of all CrystalGraphics GL resources in the correct order.
 *
 * <p>Call {@link #destroyContext()} exactly once when the OpenGL context is being
 * destroyed (e.g. game shutdown, render context reset). All VAOs must be deleted
 * before their referenced VBOs to avoid stale GPU state.</p>
 *
 * <h3>Canonical 4-step teardown order</h3>
 * <ol>
 *   <li>{@link CgVertexArrayRegistry#deleteAll()} — ALL VAOs (instanced first, then non-instanced inside {@code deleteAll})</li>
 *   <li>{@link CgMeshRegistry#deleteAll()} — static mesh VBOs + IBOs + per-mesh VAOs</li>
 *   <li>{@link CgVertexBufferRegistry#deleteAll()} — ALL stream VBOs (base + instance)</li>
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

    private CgGraphicsLifecycle() {}

    /**
     * Initializes engine GL resources that require an active GL context.
     * Must be called once on the GL thread after context creation,
     * before any material or fallback-texture usage.
     */
    public static void initContext(int width, int height) {
        CgPlatform.gl().initContext();
        onResize(width, height);
        CgRenderPipeline.init();
        CgFallbackTextures.init();
        warmUpDeferredStartupCosts();

        initialized = true;

        // Last, and after `initialized` is set: a listener may legitimately touch anything the
        // engine just brought up (pipeline, fallback textures, capability probes), and may call back
        // into isInitialized().
        listeners.dispatch("onInit", l -> l.onInit(width, height));
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
        CgFrameBufferRegistry.get().onResize(width, height);
        CgTextRendererRegistry.get().onResize(width, height);
        CgRenderPipeline.onSceneResize();

        currentWidth = width;
        currentHeight = height;
    }

    /**
     * Called before MC's translucent terrain pass. Lazy-initialises the engine on the
     * first call. Performs the per-frame depth snapshot blit, then executes CG's opaque
     * passes (depth prepass + opaque forward).
     *
     * @param partialTick frame interpolation factor
     * @param w           current viewport width (pixels)
     * @param h           current viewport height (pixels)
     * @param sourceFboId GL framebuffer ID to read depth from (MC's main render target FBO)
     */
    public static void onOpaquePass(float partialTick, int w, int h, int sourceFboId) {
        if (!initialized) initContext(w, h);
        else if (w != currentWidth || h != currentHeight) onResize(w, h);
 
        CgRenderDemo.INSTANCE.renderOpaque(partialTick, w, h, sourceFboId);
    }

    /**
     * Canonical per-real-frame tick point for engine-owned singletons that need
     * per-frame bookkeeping — currently just {@link CgFontRegistry#tickFrame(long)}.
     * Wire additional systems here as needed, mirroring how {@link #destroyContext()}
     * enumerates every registry for teardown.
     *
     * <p><strong>Already wired — do not call this yourself.</strong> Each platform's
     * {@code CgLifecycleService.onFrameRendered()} implementation calls this exactly
     * once per real rendered frame (world frame or GUI-only frame alike): mc1710's
     * {@code LifecycleService1710}, mc1201's {@code LifecycleService1201}, and the
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
        frameCounter++;
        CgFontRegistry.get().tickFrame(frameCounter);
        listeners.dispatch("onFrame", l -> l.onFrame(frameCounter));
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
     * Called after MC's translucent terrain + particles pass. Executes CG's transparent
     * pass then ends the frame (releases the render command pool).
     *
     * <p>No-op if the engine context has not been initialised yet (e.g. GUI-only frames).</p>
     */
    public static void onTransparentPass() {
        if (!initialized) return;
        CgRenderDemo.INSTANCE.renderTransparent();
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
        // Step 0: External listeners, BEFORE the engine frees anything.
        //
        // This ordering is the whole contract. A listener (CrystalGUI's CgUiLifecycle, a mod's
        // renderer) owns GL objects the engine has no handle on — its own framebuffers, renderers,
        // buffers — and can only release them while the context is still whole. Run this after any
        // of the sweeps below and those handles already refer to deleted objects, and any cache the
        // listener holds of engine-owned resources (fonts, textures, materials) is silently stale.
        listeners.dispatchReverse("onDestroy", CgLifecycleListener::onDestroy);

        // Step 1: ALL VAOs — CgVertexArrayRegistry.deleteAll() deletes instanced VAOs first,
        //   then non-instanced VAOs, ensuring no VBO referenced by a VAO is deleted first.
        CgVertexArrayRegistry.get().deleteAll();

        // Step 2: Static mesh VBOs + IBOs + per-mesh VAOs.
        //   Mesh VAOs reference mesh VBOs, so meshes must be deleted after streaming VAOs
        //   (handled in step 1) but before streaming VBOs (step 3).
        CgMeshRegistry.get().deleteAll();

        // Step 3: ALL stream VBOs (base + instance streams).
        CgVertexBufferRegistry.get().deleteAll();

        // Step 4: Shared quad IBO.
        CgQuadIndexBuffer.freeAll();

        // Step 5: Free all cached textures.
        CgTextureManager.get().freeAll();

        // Step 5b: Free engine fallback textures.
        CgFallbackTextures.destroy();


        // Step 7a: Material instances (property UBOs) + their backing shader assets (GL programs).
        CgMaterialRegistry.get().deleteAll();
        CgMaterialShaderRegistry.get().deleteAll();

        // Step 7b: User-created SSBO/TBO/UBO resources managed by CgShaderBufferRegistry.
        //   Must be freed before the GL context is lost. Engine-owned pipeline buffers
        //   (frameUbo, objectBuffer in CgRenderPipeline) are NOT in this registry —
        //   they are freed in step 7c.
        CgShaderBufferRegistry.get().deleteAll();

        // Step 6a: All CgTextRenderer instances still alive (backstop for callers that
        //   forgot to call delete() themselves) — deletes each renderer's owned
        //   CgBatchRenderer (VAO/VBO) individually before the bulk VAO/VBO sweep below,
        //   so those objects are already gone (no-op) by the time steps 1/3 run.
        CgTextRendererRegistry.get().deleteAll();
        
        // Step 6b: Font/glyph atlas textures + background generation executor, then reset
        //   the shared registry back to a freshly-constructed, immediately reusable state
        //   (a new GL context can be initialized right after this method returns).
        CgFontRegistry.get().releaseAll();


        // Step 8: Pipeline-owned frame UBO + object SSBO + command queue.
        // Dispose the render demo first so its mesh/material handles are released before
        // the registries they reference are torn down.
        CgRenderDemo.INSTANCE.dispose();
        // CgRenderPipeline owns both the GPU pipeline buffers (formerly CgMaterialPipeline)
        // and the render command queue; one destroy call handles all of it.
        CgRenderPipeline.destroy();

        // Step 9: All owned framebuffers — must be first.
        CgFrameBufferRegistry.get().deleteAll();


        // Step 10: Debug utilities (lazy singleton — no-op if never used).
//        CgDebugBlit.dispose();

        // Scratch framebuffers used by the GPU-side texture copy path (lazily created —
        // no-op if no texture ever grew). Safe to reuse after this; they are recreated on demand.
        CgTextureCopy.dispose();

        // Reset all backend-capability caches so context recreation re-probes correctly.
        CgCapabilities.clearCache();
        CgVertexArray.resetCoreCache();
        CgInstanceVertexArrayBinding.resetCoreCache();
        CgInstanceRenderer.resetCoreCache();

        initialized = false;
        currentWidth = -1;
        currentHeight = -1;
        frameCounter = 0;
    }
}
