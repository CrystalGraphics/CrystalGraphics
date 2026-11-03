package com.crystalgraphics.render.world;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nullable;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.compute.CgKernel;
import com.crystalgraphics.gl.material.CgMaterialShader;
import com.crystalgraphics.gl.material.CgMaterialShaderRegistry;
import com.crystalgraphics.job.CgJob;
import com.crystalgraphics.job.CgJobLane;
import com.crystalgraphics.render.CgWarmBudget;
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
 *     pipelines.add("mymod:shaders/rune.shader");                       // every keyword set its cg_permutation names
 *     pipelines.add(CgCompute.load("mymod:shaders/runes.compute").kernel("Step"));
 * });
 *
 * // later, when what draws is known: started now, ready() once built
 * CgPipelinePrecache.Request scene = CgPipelinePrecache.request(pipelines -> {
 *     pipelines.add(FLOOR, false);
 *     pipelines.add(ARMOUR, false, true);                               // a material a skinned mesh draws
 *     pipelines.kernels(MyOps::precache);                               // every kernel it precaches
 * });
 * if (scene.ready()) draw();                                            // each frame until true
 * }</pre>
 *
 * <p>A request too big for one frame names its work in steps, each collected while the frame's {@link CgWarmBudget}
 * lasts, after an optional worker phase that does its CPU-only half (parsing, generating sources):
 *
 * <pre>{@code
 * CgPipelinePrecache.Request move = CgPipelinePrecache.steps("mymod:move")
 *         .ahead(() -> CgKernel.ahead(MyOps::precache))                 // on a worker, first
 *         .step(p -> p.add(BEAM, true))
 *         .step(p -> p.kernels(MyOps::precache))                        // a later frame, if the budget ran out
 *         .start();
 * }</pre>
 *
 * <p><b>When a collector runs</b>: at the graphics start (the first {@code CgEngineLifecycle.ensureGraphicsContext}, in
 * Minecraft's loading screen), every one registered by then is collected and built in parallel, and the start waits for
 * them. One registered later is collected at the next world frame and built over frames inside the warm budget, its
 * draws held until built. A request's steps are collected from the frame it starts, each while the budget lasts, and
 * every frame after until the last: before the start, the budget is unlimited and a request joins the start's build.
 *
 * <p><b>When to register</b>: in pre-init or init ({@code CgEngineLifecycle.preInit}/{@code init}: a mod's constructor
 * or common setup, or a static initialiser they reach). On a client post-init runs after the graphics start, so a
 * collector registered there is a late one. Registering on a dedicated server is harmless: nothing collects there.
 *
 * <ul>
 *   <li>Keywords a renderer switches at runtime are the shader's permutation domain ({@code #pragma cg_permutation},
 *       Unreal's {@code FShaderPermutationDomain}): {@code add(shader)} then builds every combination, never a list typed
 *       here. A set nothing covers compiles at its first draw, counted as {@code pipeline.missed} and logged under
 *       {@code crystalgraphics.pipeline.logMisses}.</li>
 *   <li>Name what draws, not every file: a shader's base variant is rarely the one a draw binds (keywords, skinned,
 *       half size, multi-draw), and every variant of a directory is thousands of programs.</li>
 *   <li>A step that throws is logged under its request's name and skipped; the request goes on with the rest.</li>
 *   <li>An effect's look requests its own through {@code CgVfxOwner.precache}; a skinned mesh's materials are requested
 *       by its {@code CgAnimInstance} as they are set. Textures and meshes are {@code CgPreload}'s, not this.</li>
 * </ul>
 */
public final class CgPipelinePrecache {

    /** Names what to build into the list it is handed. Render thread. */
    @FunctionalInterface
    public interface Collector {
        void collect(CgPipelineList pipelines);
    }

    /**
     * What one request named, built in the background: {@link #ready()} once every step is collected and every entry is
     * built under the settings in force. A setting changing makes it not ready again until its entries are rebuilt.
     */
    public static final class Request {
        final List<CgMaterial> materials = new ArrayList<>();
        final List<CgKernel> kernels = new ArrayList<>();
        final List<CgMaterial> passes = new ArrayList<>();
        final String name;
        final Queue<Collector> steps;
        /** Its worker phase, null once done or when it has none. */
        @Nullable CompletableFuture<Void> ahead;

        Request(String name, @Nullable Runnable ahead, List<Collector> steps) {
            this.name = name;
            this.steps = new ArrayDeque<>(steps);
            if (ahead != null) {
                this.ahead = CompletableFuture.runAsync(() -> {
                    try {
                        ahead.run();
                    } catch (RuntimeException e) {
                        // left for the step that reaches the same work, which reports it
                        LOGGER.debug("precache {}: worker phase failed: {}", name, e.toString());
                    }
                }, CgJob.executor(CgJobLane.LATENCY));
            }
        }

        /** Whether every step is collected and everything it named is built, collecting and polling the rest. Render thread. */
        public boolean ready() {
            return CgWorldRenderer.get().pipelines().ready(this);
        }

        /** Whether its steps are all collected: what it names is final, though not yet built. */
        public boolean collected() {
            return ahead == null && steps.isEmpty();
        }
    }

    /**
     * A request named in steps: an optional worker phase, then each step collected while a frame's warm budget lasts.
     *
     * <pre>{@code
     * CgPipelinePrecache.steps("mymod:arena").step(p -> p.add(FLOOR, false)).step(p -> p.add(SKY, false)).start();
     * }</pre>
     */
    public static final class Steps {
        private final String name;
        private final List<Collector> steps = new ArrayList<>();
        @Nullable private Runnable ahead;

        private Steps(String name) {
            this.name = name;
        }

        /** CPU-only work run on a worker before the first step: sources generated and parsed, nothing built. */
        public Steps ahead(Runnable work) {
            this.ahead = work;
            return this;
        }

        /** The next step, collected on the render thread in order. */
        public Steps step(Collector step) {
            steps.add(step);
            return this;
        }

        /** Starts it: the worker phase now, the steps from this frame on. Render thread. */
        public Request start() {
            Request request = new Request(name, ahead, steps);
            CgWorldRenderer.get().pipelines().start(request);
            return request;
        }
    }

    static final Logger LOGGER = LogManager.getLogger("CgPipelinePrecache");
    private static final Queue<Collector> PENDING = new ConcurrentLinkedQueue<>();

    private CgPipelinePrecache() {
    }

    /**
     * Parses {@code shaders} on a worker now, so the collect or warm-up that first names one finds it parsed: a parse
     * there is the render thread's, up to 100 ms of a loading frame. From the graphics start (a listener's
     * {@code onContextInit}), once the host's resources are readable. Any thread.
     *
     * <pre>{@code
     * CgPipelinePrecache.parseAhead("mymod:shaders/rune.shader", "mymod:shaders/glow.shader");
     * }</pre>
     */
    public static void parseAhead(String... shaders) {
        CgMaterialShaderRegistry registry = CgMaterialShaderRegistry.get();
        List<CgMaterialShader> found = new ArrayList<>(shaders.length);
        for (String shader : shaders) found.add(registry.getOrCreate(shader));
        CgJob.executor(CgJobLane.LATENCY).execute(() -> {
            for (CgMaterialShader shader : found) shader.ensureParsed();
        });
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
     * Starts {@code collector} as a request of one step: collected now unless this frame's warm budget is spent, then
     * built in the background. What is known only at runtime. Render thread; what it names is kept built for the
     * session, as a registered collector's.
     */
    public static Request request(Collector collector) {
        return steps("request").step(collector).start();
    }

    /** A request of several steps named {@code name} in a failure's log, started with {@link Steps#start()}. */
    public static Steps steps(String name) {
        return new Steps(name);
    }

    /** The most collectors waiting to be collected. */
    public static final int MAX_PENDING = 4096;
    private static final AtomicInteger PENDING_COUNT = new AtomicInteger();
    private static volatile boolean warnedFull;

    /** Pipelines not yet built under the settings in force, and collectors not yet collected: the loading screen's. */
    public static int remaining() {
        return CgWorldRenderer.get().pipelines().pending() + PENDING_COUNT.get();
    }

    /** Every pipeline kept built, and collectors not yet collected: {@link #remaining()}'s whole. */
    public static int total() {
        return CgWorldRenderer.get().pipelines().size() + PENDING_COUNT.get();
    }

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
