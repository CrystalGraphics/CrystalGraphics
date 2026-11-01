package com.crystalgraphics.render;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.crystalgraphics.render.world.CgWorldPrograms;
import com.github.bsideup.jabel.Desugar;

/**
 * What every renderer, the engine's and a mod's, will draw and dispatch, built while the game loads rather than at a
 * frame's first draw: Unreal's global PSO collectors ({@code FRegisterGlobalPSOCollectorFunction}), registered as
 * Minecraft's atlases take their sources. A collector names its shaders' keyword sets, its materials and its kernels;
 * the graphics start builds every one registered by then, in parallel, waiting for them inside the host's loading
 * screen, and the world renderer keeps them built under every setting after (the HDR scene, half size, the tier).
 *
 * <pre>{@code
 * // a mod's constructor, before the graphics start: built with the engine's own
 * CgPrecache.register("mymod:runes", programs -> {
 *     programs.add("mymod:shaders/rune.shader");
 *     programs.add("mymod:shaders/rune.shader", "GLOW");
 *     programs.add(RUNE_MATERIAL, false);                              // a material, every pass the world draws it with
 *     programs.add(CgCompute.load("mymod:shaders/runes.compute").kernel("Step"));
 * });
 * }</pre>
 *
 * <ul>
 *   <li>Name every keyword set a setting can switch to, not only today's: a set not named compiles at its first draw,
 *       counted as {@code pipeline.missed} and logged under {@code crystalgraphics.pipeline.logMisses}.</li>
 *   <li>A collector registered after the graphics start is collected at the next world frame and built over frames,
 *       inside the warm budget: register before it.</li>
 *   <li>Effects are prepared by their looks ({@code CgVfxOwner.prepare}), not here.</li>
 * </ul>
 */
public final class CgPrecache {

    /** Names what to build into the program list it is handed. Render thread. */
    @FunctionalInterface
    public interface Collector {
        void collect(CgWorldPrograms programs);
    }

    private static final Logger LOGGER = LogManager.getLogger("CgPrecache");
    private static final Queue<Collector> PENDING = new ConcurrentLinkedQueue<>();

    private CgPrecache() {
    }

    /** Registers {@code collector}, named {@code name} in a failure's log, to be collected once. Any thread. */
    public static void register(String name, Collector collector) {
        PENDING.add(new Named(name, collector));
    }

    /** Collects every collector registered since the last call into {@code programs}. The world renderer's. */
    public static void collect(CgWorldPrograms programs) {
        for (Collector c; (c = PENDING.poll()) != null; ) c.collect(programs);
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
