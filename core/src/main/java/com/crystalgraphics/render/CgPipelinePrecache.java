package com.crystalgraphics.render;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.crystalgraphics.render.world.CgWorldPrograms;
import com.github.bsideup.jabel.Desugar;

/**
 * The pipelines every renderer, the engine's and a mod's, will draw and dispatch, built while the game loads rather
 * than at a frame's first draw: Unreal's PSO precaching and its global collectors
 * ({@code FRegisterGlobalPSOCollectorFunction}), registered as Minecraft's atlases take their sources. A collector
 * names its shaders' keyword sets, its materials and its kernels; on Vulkan each program's recorded pipelines build
 * with it.
 *
 * <p><b>When it is collected</b>: at the graphics start (the first {@code CgEngineLifecycle.ensureGraphicsContext}, in
 * Minecraft's loading screen), every collector registered by then is collected and built in parallel, and the start
 * waits for them. One registered later is collected at the next world frame and built over frames inside the warm
 * budget, its draws held until built. The world renderer keeps all of it built under every setting after (the HDR
 * scene, half size, the tier).
 *
 * <p><b>When to register</b>: in pre-init or init ({@code CgEngineLifecycle.preInit}/{@code init}: a mod's constructor
 * or common setup, or a static initialiser they reach). On a client post-init runs after the graphics start, so a
 * collector registered there is a late one. Registering on a dedicated server is harmless: nothing collects there.
 *
 * <pre>{@code
 * // a mod's constructor: built with the engine's own, inside the loading screen
 * CgPipelinePrecache.register("mymod:runes", programs -> {
 *     programs.add("mymod:shaders/rune.shader");                       // a shader's base variant
 *     programs.add("mymod:shaders/rune.shader", "GLOW");               // and a keyword set it switches to
 *     programs.add(RUNE_MATERIAL, false);                              // a material, every pass the world draws it with
 *     programs.add(ARMOUR, false, true);                               // a material a skinned mesh draws
 *     programs.add(CgCompute.load("mymod:shaders/runes.compute").kernel("Step"));
 *     programs.kernels(MyOps::prepare);                                // every kernel a preparer starts
 * });
 * }</pre>
 *
 * <ul>
 *   <li>Name every keyword set a setting can switch to, not only today's: a set not named compiles at its first draw,
 *       counted as {@code pipeline.missed} and logged under {@code crystalgraphics.pipeline.logMisses}.</li>
 *   <li>Name what draws, not every file: a shader's base variant is rarely the one a draw binds (keywords, skinned,
 *       half size, multi-draw), and every variant of a directory is thousands of programs.</li>
 *   <li>Effects are prepared by their looks ({@code CgVfxOwner.prepare}), not here; a skinned mesh's materials by its
 *       {@code CgAnimInstance}, as they are set.</li>
 * </ul>
 */
public final class CgPipelinePrecache {

    /** Names what to build into the program list it is handed. Render thread. */
    @FunctionalInterface
    public interface Collector {
        void collect(CgWorldPrograms programs);
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

    /** The most collectors waiting to be collected. */
    public static final int MAX_PENDING = 4096;
    private static final AtomicInteger PENDING_COUNT = new AtomicInteger();
    private static volatile boolean warnedFull;

    /** Collects every collector registered since the last call into {@code programs}. The world renderer's. */
    public static void collect(CgWorldPrograms programs) {
        for (Collector c; (c = PENDING.poll()) != null; ) {
            PENDING_COUNT.decrementAndGet();
            c.collect(programs);
        }
    }

    @Desugar
    private record Named(String name, Collector collector) implements Collector {
        @Override
        public void collect(CgWorldPrograms programs) {
            try {
                collector.collect(programs);
            } catch (RuntimeException e) {
                LOGGER.error("precache collector {} failed", name, e);
            }
        }
    }
}
