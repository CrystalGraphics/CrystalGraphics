package com.crystalgraphics.platform.rhi.pipeline;

import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.trace.CgTraceChannel;
import com.crystalgraphics.variable.CgVar;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A device's graphics pipelines, by their full description: each built once, on a worker ahead of its first draw where
 * the recorded list ({@link CgPipelineRecipes}) names it, and written to that list when a draw had to build it. Unreal's
 * {@code PipelineStateCache} with its bundled PSO cache; the device's own {@code VkPipelineCache} sits under it.
 *
 * <pre>{@code
 * CgPipelineCache cache = new CgPipelineCache(device);
 * // as a program links, on its compile worker: its recorded pipelines start building
 * Map<CgGraphicsPipelineDesc, Future<CgGraphicsPipeline>> started =
 *         cache.startRecorded(program.key(), recipe -> recipe.desc(label, layout, vertex, fragment));
 * cache.adopt(started);                                  // owner thread, once the link is installed
 * CgGraphicsPipeline p = cache.get(desc, program.key());   // at the draw: built, taken ahead, or built now
 * cache.endFrame();                                      // releases what was built for links that never installed
 * }</pre>
 *
 * <ul>
 *   <li>A pipeline a draw built is a miss ({@link #misses()}, the trace counter {@code pipelines.built}); its
 *       description is recorded under its program's key, so the next start builds it ahead.</li>
 *   <li>Owner thread, except {@link #startRecorded}, which any thread may call.</li>
 * </ul>
 */
public final class CgPipelineCache {

    private static final Logger LOGGER = LogManager.getLogger("CgPipelineCache");
    private static final CgTraceChannel CHANNEL = CgTrace.channel("crystalgraphics.gl");
    private static final CgVar.Bool LOG = CgVar.bool("crystalgraphics.pipelines.log", false, CgVar.DEV,
            "Log each pipeline the device built at a draw (pipelines.built), with its program.");
    private static final CgVar.Bool RECORDED = CgVar.bool("crystalgraphics.pipelines.recorded", true, CgVar.DEV,
            "Build a program's recorded pipelines as it links (CgPipelineRecipes); false builds each at its first draw.");
    private static final int BUILD = CgTrace.name("pipelines.build"), BUILT = CgTrace.name("pipelines.built"),
            AHEAD_ZONE = CgTrace.name("pipelines.buildAhead"), AHEAD_WAIT = CgTrace.name("pipelines.wait"),
            AHEAD = CgTrace.name("pipelines.ahead"), AHEAD_HITS = CgTrace.name("pipelines.ahead-hits");

    private final CgRHI device;
    private final Map<CgGraphicsPipelineDesc, CgGraphicsPipeline> pipelines = new HashMap<>();
    /** Recorded pipelines building on {@link #builders}, by description; taken by the first draw that needs one. */
    private final Map<CgGraphicsPipelineDesc, Future<CgGraphicsPipeline>> ahead = new HashMap<>();
    /** Built ahead for a link that will never install: released once done. */
    private final List<Future<CgGraphicsPipeline>> orphaned = new ArrayList<>();
    private ExecutorService builders;
    /** Closed with its device: no build starts. */
    private boolean closed;
    private long misses;

    /** A cache over {@code device}, closed as it closes ({@link CgRHI#onClose}). */
    public CgPipelineCache(CgRHI device) {
        this.device = device;
        device.onClose(this::close);
    }

    /**
     * Drains it as its device closes: no build starts, those under way finish, and every pipeline it holds is
     * released (Unreal's {@code PipelineStateCache::Shutdown} at RHI exit). A build still running when the device
     * goes would create on a device being destroyed.
     */
    public void close() {
        ExecutorService pool;
        synchronized (this) {
            closed = true;
            pool = builders;
            builders = null;
        }
        if (pool != null) {
            pool.shutdown();
            try {
                while (!pool.awaitTermination(10, TimeUnit.SECONDS)) LOGGER.warn("pipelines still building as the device closes");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        for (CgGraphicsPipeline p : pipelines.values()) device.release(p);
        pipelines.clear();
        for (Future<CgGraphicsPipeline> f : ahead.values()) releaseBuilt(f);
        ahead.clear();
        for (Future<CgGraphicsPipeline> f : orphaned) releaseBuilt(f);
        orphaned.clear();
    }

    /** Releases what {@code built} made, if it made anything. */
    private void releaseBuilt(Future<CgGraphicsPipeline> built) {
        if (!built.isDone()) return;
        try {
            device.release(built.get());
        } catch (ExecutionException e) {
            // It failed to build: nothing to release.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * The pipeline for {@code desc}: kept, taken from a build started ahead (waiting if it is still building), or built
     * now and counted a miss, then recorded under {@code programKey} (0 records nothing) for the next start.
     */
    public CgGraphicsPipeline get(CgGraphicsPipelineDesc desc, long programKey) {
        CgGraphicsPipeline p = pipelines.get(desc);
        if (p != null) return p;
        p = takeAhead(desc);
        if (p == null) {
            try (CgTrace.Zone ignored = CgTrace.zone(CHANNEL, BUILD)) {
                p = device.createPipeline(desc);
            }
            misses++;
            CgTrace.add(CHANNEL, BUILT, 1);
            if (LOG.get()) LOGGER.info("pipeline built at its draw: {} on {} colour targets", desc.label(), desc.colorTargets().size());
            if (programKey != 0 && device.createsOnAnyThread()) {
                recipes().record(programKey, CgPipelineRecipes.Recipe.of(desc));
            }
        }
        pipelines.put(desc, p);
        return p;
    }

    /** Pipelines a draw had to build this session: what the recorded list and the precache missed. */
    public long misses() {
        return misses;
    }

    /**
     * Starts every pipeline recorded for a program of {@code programKey} on the builders, each description made by
     * {@code describe} from its recipe. Any thread: a program's compile worker calls it as the program links.
     */
    public Map<CgGraphicsPipelineDesc, Future<CgGraphicsPipeline>> startRecorded(
            long programKey, Function<CgPipelineRecipes.Recipe, CgGraphicsPipelineDesc> describe) {
        if (programKey == 0 || !device.createsOnAnyThread() || !RECORDED.get()) return Map.of();
        ExecutorService pool = builders();
        if (pool == null) return Map.of();
        Map<CgGraphicsPipelineDesc, Future<CgGraphicsPipeline>> started = new HashMap<>();
        for (CgPipelineRecipes.Recipe recipe : recipes().of(programKey)) {
            CgGraphicsPipelineDesc desc = describe.apply(recipe);
            started.put(desc, pool.submit(() -> {
                try (CgTrace.Zone ignored = CgTrace.zone(CHANNEL, AHEAD_ZONE)) {
                    return device.createPipeline(desc);
                }
            }));
        }
        CgTrace.add(CHANNEL, AHEAD, started.size());
        return started;
    }

    /** Takes pipelines started ahead for a program now installed: its first draws take them. */
    public void adopt(Map<CgGraphicsPipelineDesc, Future<CgGraphicsPipeline>> started) {
        for (Map.Entry<CgGraphicsPipelineDesc, Future<CgGraphicsPipeline>> e : started.entrySet()) {
            if (pipelines.containsKey(e.getKey()) || ahead.containsKey(e.getKey())) orphaned.add(e.getValue());
            else ahead.put(e.getKey(), e.getValue());
        }
    }

    /** Pipelines started for a link that will never install: released as each finishes. */
    public void orphan(Collection<Future<CgGraphicsPipeline>> started) {
        orphaned.addAll(started);
    }

    /** Releases every kept pipeline whose description {@code built} matches: its program was relinked or deleted. */
    public void forget(Predicate<CgGraphicsPipelineDesc> built) {
        pipelines.entrySet().removeIf(e -> {
            boolean match = built.test(e.getKey());
            if (match) device.release(e.getValue());
            return match;
        });
        ahead.entrySet().removeIf(e -> {
            boolean match = built.test(e.getKey());
            if (match) orphaned.add(e.getValue());
            return match;
        });
    }

    /** Once a frame: releases what finished building for links that never installed. */
    public void endFrame() {
        for (int i = orphaned.size() - 1; i >= 0; i--) {
            Future<CgGraphicsPipeline> f = orphaned.get(i);
            if (!f.isDone()) continue;
            orphaned.remove(i);
            releaseBuilt(f);
        }
    }

    /** The pipeline started ahead for {@code desc}, waiting for it if it is still building; null if none was, or it failed. */
    private CgGraphicsPipeline takeAhead(CgGraphicsPipelineDesc desc) {
        Future<CgGraphicsPipeline> built = ahead.remove(desc);
        if (built == null) return null;
        try (CgTrace.Zone ignored = built.isDone() ? null : CgTrace.zone(CHANNEL, AHEAD_WAIT)) {
            CgGraphicsPipeline p = built.get();
            CgTrace.add(CHANNEL, AHEAD_HITS, 1);
            return p;
        } catch (ExecutionException e) {
            LOGGER.warn("pipeline built ahead for {} failed; building it at its draw", desc.label(), e.getCause());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private CgPipelineRecipes recipes() {
        return CgPipelineRecipes.shared();
    }

    /** Half the cores, from one to four: a driver compiles one pipeline a thread. Null once closed. */
    private synchronized ExecutorService builders() {
        if (closed) return null;
        if (builders == null) {
            int n = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));
            builders = Executors.newFixedThreadPool(n, r -> {
                Thread t = new Thread(r, "crystalgraphics-pipelines");
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            });
        }
        return builders;
    }
}
