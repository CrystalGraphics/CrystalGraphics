package com.crystalgraphics.render.world;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.compute.CgKernel;
import com.github.bsideup.jabel.Desugar;

/**
 * The one way to have pipelines built before they draw or dispatch, Unreal's PSO precaching: what is always known
 * registers a collector at load (its global collectors, {@code FRegisterGlobalPSOCollectorFunction}), and what is
 * known later, a look, an asset bundle, a scene, requests at runtime (a component's {@code PrecachePSOs}). Both name
 * materials, shaders' keyword sets and kernels into one {@link CgPipelineList}, kept built for the session under every
 * setting (the HDR scene, half size, the tier); on Vulkan each program's recorded pipelines build with it.
 *
 * <pre>{@code
 * // at load: built with the engine's own, inside the loading screen
 * CgPipelinePrecache.register("mymod:runes", pipelines -> {
 *     pipelines.add(RUNE_MATERIAL, false);                              // a material, every pass the world draws it with
 *     pipelines.add("mymod:shaders/rune.shader", "GLOW");               // a keyword set a setting switches to
 *     pipelines.add(CgCompute.load("mymod:shaders/runes.compute").kernel("Step"));
 * });
 *
 * // later, when what draws is known: started now, ready() once built
 * CgPipelinePrecache.Request scene = CgPipelinePrecache.request(pipelines -> {
 *     pipelines.add(FLOOR, false);
 *     pipelines.add(ARMOUR, false, true);                               // a material a skinned mesh draws
 *     pipelines.kernels(MyOps::precache);                                // every kernel it precaches
 * });
 * if (scene.ready()) draw();                                            // each frame until true
 * }</pre>
 *
 * <p><b>When a collector runs</b>: at the graphics start (the first {@code CgEngineLifecycle.ensureGraphicsContext}, in
 * Minecraft's loading screen), every one registered by then is collected and built in parallel, and the start waits for
 * them. One registered later is collected at the next world frame and built over frames inside the warm budget, its
 * draws held until built. A request is collected at once: before the start it joins the start's build.
 *
 * <p><b>When to register</b>: in pre-init or init ({@code CgEngineLifecycle.preInit}/{@code init}: a mod's constructor
 * or common setup, or a static initialiser they reach). On a client post-init runs after the graphics start, so a
 * collector registered there is a late one. Registering on a dedicated server is harmless: nothing collects there.
 *
 * <ul>
 *   <li>Name every keyword set a setting can switch to, not only today's: a set not named compiles at its first draw,
 *       counted as {@code pipeline.missed} and logged under {@code crystalgraphics.pipeline.logMisses}.</li>
 *   <li>Name what draws, not every file: a shader's base variant is rarely the one a draw binds (keywords, skinned,
 *       half size, multi-draw), and every variant of a directory is thousands of programs.</li>
 *   <li>An effect's look requests its own through {@code CgVfxOwner.precache}; a skinned mesh's materials are requested
 *       by its {@code CgAnimInstance} as they are set.</li>
 * </ul>
 */
public final class CgPipelinePrecache {

    /** Names what to build into the list it is handed. Render thread. */
    @FunctionalInterface
    public interface Collector {
        void collect(CgPipelineList pipelines);
    }

    /**
     * What one {@link #request} named, built in the background: {@link #ready()} once every entry is built under the
     * settings in force. A setting changing makes it not ready again until its entries are rebuilt under it.
     */
    public static final class Request {
        final List<CgMaterial> materials = new ArrayList<>();
        final List<CgKernel> kernels = new ArrayList<>();
        final List<CgMaterial> passes = new ArrayList<>();

        Request() {
        }

        /** Whether everything it named is built, polling what is still building. Render thread. */
        public boolean ready() {
            return CgWorldRenderer.get().pipelines().ready(this);
        }
    }

    private static final Logger LOGGER = LogManager.getLogger("CgPipelinePrecache");
    private static final Queue<Collector> PENDING = new ConcurrentLinkedQueue<>();

    private CgPipelinePrecache() {
    }

    /**
     * Registers {@code collector}, named {@code name} in a failure's log, to be collected once. Any thread. Past
     * {@link #MAX_PENDING} uncollected it is dropped: a dedicated server, which never collects, holds no more.
     */
    public static void register(String name, Collector collector) {
        if (PENDING_COUNT.get() >= MAX_PENDING) {
            if (!warnedFull) {
                warnedFull = true;
                LOGGER.warn("precache queue full ({} uncollected; nothing draws here?): {} and later dropped", MAX_PENDING, name);
            }
            return;
        }
        PENDING_COUNT.incrementAndGet();
        PENDING.add(new Named(name, collector));
    }

    /**
     * Collects {@code collector} now and starts what it names, answering a handle to poll: what is known only at
     * runtime. Render thread; what it names is kept built for the session, as a registered collector's.
     */
    public static Request request(Collector collector) {
        Request request = new Request();
        CgWorldRenderer.get().pipelines().collect(collector, request);
        return request;
    }

    /** The most collectors waiting to be collected. */
    public static final int MAX_PENDING = 4096;
    private static final AtomicInteger PENDING_COUNT = new AtomicInteger();
    private static volatile boolean warnedFull;

    /** Collects every collector registered since the last call into {@code pipelines}. */
    static void collect(CgPipelineList pipelines) {
        for (Collector c; (c = PENDING.poll()) != null; ) {
            PENDING_COUNT.decrementAndGet();
            pipelines.collect(c, null);
        }
    }

    @Desugar
    private record Named(String name, Collector collector) implements Collector {
        @Override
        public void collect(CgPipelineList pipelines) {
            try {
                collector.collect(pipelines);
            } catch (RuntimeException e) {
                LOGGER.error("precache collector {} failed", name, e);
            }
        }
    }
}
