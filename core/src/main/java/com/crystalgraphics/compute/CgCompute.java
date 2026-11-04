package com.crystalgraphics.compute;

import com.crystalgraphics.compute.emit.CgKernelTarget;
import com.crystalgraphics.compute.emit.CgPropertyBlock;
import com.crystalgraphics.compute.parse.CgComputeParser;
import com.crystalgraphics.compute.program.CgComputeCheck;
import com.crystalgraphics.compute.program.CgKernelProgram;
import com.crystalgraphics.compute.source.CgComputeSource;
import com.crystalgraphics.compute.source.CgKernelDecl;
import com.crystalgraphics.gl.shader.CgLinkWorker;
import com.crystalgraphics.gl.buffer.CgBufferReadback;
import com.crystalgraphics.mc.CgAssetReloader;
import com.crystalgraphics.mc.CgReloadable;
import com.crystalgraphics.render.CgWarmBudget;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.io.CgIO;
import com.crystalgraphics.util.trace.CgChannels;
import com.crystalgraphics.variable.CgVar;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * A {@code .compute} file: its kernels, each compiled per keyword set on first use and cached, as a material's
 * variants are. Loaded once per path; generated source is cached by key and re-parsed when it changes.
 *
 * <pre>{@code
 * CgCompute particles = CgCompute.load("mymod:shaders/particles.compute");
 * CgKernel simulate = particles.kernel("Simulate");
 * CgKernel colliding = simulate.withKeywords("COLLISION");
 * CgKernelProgram program = colliding.program();     // render thread; compiles the first time
 * }</pre>
 *
 * <p>Generated kernels, from the shader graph or a module compiler:</p>
 * <pre>{@code
 * CgCompute graph = CgCompute.fromSource("shadergraph:" + id, generatedText);   // same text: the same instance
 * }</pre>
 *
 * <p>Below compute a kernel runs lowered ({@link CgKernel#lowered()}) or by a Java body it is given
 * ({@link CgKernel#cpu}); {@link CgKernel#form()} says which, and the frame graph runs whichever it is.</p>
 *
 * <ul>
 *   <li>A file that fails to parse throws {@code CgShaderParseException} from {@link #load}, naming the file, the
 *       kernel and the line; one that parses but exceeds the device throws from {@link CgKernel#program()}.</li>
 *   <li>{@link #load} and {@link #fromSource} may run on any thread; programs are the render thread's.</li>
 * </ul>
 */
public final class CgCompute implements CgReloadable {

    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphics");
    private static final Map<String, CgCompute> LOADED = new ConcurrentHashMap<>();
    private static final Map<String, CgCompute> GENERATED = new ConcurrentHashMap<>();
    /** Replaced generated files, whose programs the render thread deletes. */
    private static final Queue<CgCompute> RETIRED = new ConcurrentLinkedQueue<>();
    private static final int COMPILE = CgTrace.name("compute.compile");
    private static final int COMPILES = CgTrace.name("compute.compiles");
    private static final CgVar.Bool LOG_COMPILES = CgVar.bool("crystalgraphics.compute.logCompiles", false, CgVar.DEV,
            "Log each kernel program compiled or started, by file and program key, with whether a dispatch waited.");
    /** Of {@link #COMPILES}, those whose sources a worker made ahead ({@link CgKernel#ahead}). */
    private static final int AHEAD_HITS = CgTrace.name("compute.compiles-ahead");
    private static final int PRECACHE = CgTrace.name("compute.precache"), COMPILE_WAIT = CgTrace.name("compute.compileWait");
    private static final int COMPILE_WAITS = CgTrace.name("compute.compile-waits");
    private static final int FINISH = CgTrace.name("compute.finishPrecached");
    /** Files holding a program {@link CgKernel#precache} started that nothing has finished yet. */
    private static final Set<CgCompute> PRECACHING = ConcurrentHashMap.newKeySet();
    /** What {@link #finishPrecached} spends a frame at most, and at least whatever the warm budget has left. */
    private static final long FINISH_NANOS = 1_000_000L, FINISH_LEAST = 200_000L;

    private final String path;
    private final boolean generated;
    private volatile CgComputeSource source;
    private volatile CgPropertyBlock properties;
    private volatile String text;
    /** Counts every release: a kernel holding a program from before asks again. */
    private volatile int generation;
    private final Map<String, CgKernelProgram> programs = new HashMap<>();
    /** Programs {@link CgKernel#precache} started and no dispatch has taken yet. */
    private final Map<String, CgKernelProgram.Pending> precaching = new HashMap<>();
    /** Each kernel with no keywords, made once: a kernel keeps its program across calls. */
    private final Map<String, CgKernel> kernels = new ConcurrentHashMap<>();
    /** Each kernel with keywords, made once, as {@link #kernels}. */
    private final Map<CgKernel, CgKernel> variants = new ConcurrentHashMap<>();
    /** Every file its parse and its programs read, normalized: what an edit to one of them refreshes it for. */
    private volatile Set<String> reads = Collections.emptySet();

    private CgCompute(String path, String text, boolean generated) {
        this.path = path;
        this.generated = generated;
        this.text = text;
        CgIO.Reads recorded = CgIO.recordReads();
        try {
            this.source = CgComputeParser.parse(text, path);
        } finally {
            recorded.close();
            addReads(recorded.paths());
        }
        this.properties = CgPropertyBlock.of(source.properties());
    }

    /**
     * The {@code .compute} at {@code path} ({@code "mymod:shaders/particles.compute"}), parsed once.
     *
     * @throws IllegalArgumentException if nothing is at {@code path}
     */
    public static CgCompute load(String path) {
        CgCompute loaded = LOADED.get(path);
        return loaded != null ? loaded : LOADED.computeIfAbsent(path, p -> new CgCompute(p, read(p), false));
    }

    /** Kernels from text under {@code key}: the instance already under it when the text is the same. */
    public static synchronized CgCompute fromSource(String key, String text) {
        CgCompute existing = GENERATED.get(key);
        if (existing != null && existing.text.equals(text)) return existing;
        CgCompute created = new CgCompute(key, text, true);
        CgCompute previous = GENERATED.put(key, created);
        if (previous != null) {
            CgAssetReloader.untrack(previous);
            RETIRED.add(previous);
        }
        return created;
    }

    /** The kernel {@code name}, with no keywords: the same instance every call, so a per-frame caller makes nothing. */
    public CgKernel kernel(String name) {
        CgKernel kernel = kernels.get(name);
        if (kernel != null && kernel.decl() != null) return kernel;   // decl() looks again only after a reload
        if (source.kernel(name) == null) {
            throw new IllegalArgumentException("[" + path + "] has no kernel '" + name + "': " + kernelNames());
        }
        return kernel != null ? kernel : kernels.computeIfAbsent(name, n -> new CgKernel(this, n, Set.of()));
    }

    /** The one instance equal to {@code made}: what {@link CgKernel#withKeywords} answers. */
    CgKernel shared(CgKernel made) {
        CgKernel held = variants.putIfAbsent(made, made);
        return held != null ? held : made;
    }

    public CgComputeSource source() {
        return source;
    }

    public String path() {
        return path;
    }

    /** Where its {@code Properties} values sit in the block, and their defaults. */
    public CgPropertyBlock properties() {
        return properties;
    }

    int generation() {
        return generation;
    }

    /** The compiled program for {@code kernel}, compiled and cached on the first call. Render thread. */
    synchronized CgKernelProgram program(CgKernel kernel) {
        for (CgCompute retired; (retired = RETIRED.poll()) != null; ) retired.release();
        String key = kernel.programKey;
        CgKernelProgram program = programs.get(key);
        if (program == null || program.isDeleted()) {
            CgKernelProgram.Pending pending = precaching.remove(key);
            if (pending != null && pending.isDone()) {
                program = pending.finish();
            } else if (pending != null) {
                try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, COMPILE_WAIT)) {
                    program = pending.finish();
                }
                CgTrace.add(CgChannels.GL, COMPILE_WAITS, 1);
                if (LOG_COMPILES.get()) LOGGER.warn("compute dispatch waited for: {} {}", path, key);
            } else {
                try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, COMPILE)) {
                    program = build(source, decl(kernel), kernel);
                }
                CgTrace.add(CgChannels.GL, COMPILES, 1);
                if (LOG_COMPILES.get()) LOGGER.warn("compute compiled at dispatch: {} {}", path, key);
            }
            programs.put(key, program);
        }
        return program;
    }

    /** Sources a worker made for {@link #precache}, by program key, with what making them read. */
    private final Map<String, Ahead> ahead = new ConcurrentHashMap<>();

    private record Ahead(CgKernelProgram.Sources sources, Set<String> reads) {
    }

    /** {@code kernel}'s sources for the current target, made for the {@link #precache} that follows. CPU only, any thread. */
    void loadAhead(CgKernel kernel) {
        String key = kernel.programKey;
        CgComputeSource now = source;
        CgKernelDecl decl = now.kernel(kernel.name());
        if (decl == null || ahead.containsKey(key)) return;
        try (CgIO.Reads reads = CgIO.recordReads()) {
            CgKernelProgram.Sources made = CgKernelProgram.Sources.of(now, decl, kernel.keywords(), CgKernelTarget.current());
            ahead.put(key, new Ahead(made, new HashSet<>(reads.paths())));
        }
    }

    /**
     * Whether {@code kernel}'s program is built, so a dispatch takes it as it is: starting it if nothing has, and
     * finishing it here once linked while the link worker is between links. Render thread.
     */
    synchronized boolean isReady(CgKernel kernel) {
        String key = kernel.programKey;
        CgKernelProgram program = programs.get(key);
        if (program != null && !program.isDeleted()) return true;
        if (!precaching.containsKey(key)) precache(kernel);
        CgKernelProgram.Pending pending = precaching.get(key);
        if (pending == null) return true;
        // Finished only between links: during one, NVIDIA makes its program calls wait for it (compute.finishPrecached).
        if (!pending.isDone() || CgLinkWorker.linking()) return false;
        precaching.remove(key);
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, FINISH)) {
            programs.put(key, pending.finish());
        } catch (IllegalStateException e) {
            // Its dispatch builds it again, and throws there with the log.
            LOGGER.warn("[{}] a precached kernel failed: {}", path, e.getMessage());
        }
        return true;
    }

    /** Starts {@code kernel}'s program without waiting, unless it is compiled or started. Render thread. */
    synchronized void precache(CgKernel kernel) {
        for (CgCompute retired; (retired = RETIRED.poll()) != null; ) retired.release();
        String key = kernel.programKey;
        CgKernelProgram program = programs.get(key);
        if (program != null && !program.isDeleted() || precaching.containsKey(key)) return;
        Ahead made = ahead.remove(key);
        if (made != null && made.sources().source() == source && made.sources().target().equals(CgKernelTarget.current())) {
            try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, PRECACHE)) {
                precaching.put(key, CgKernelProgram.submit(made.sources(), decl(kernel), kernel.keywords()));
            }
            addReads(made.reads());
            PRECACHING.add(this);
            CgTrace.add(CgChannels.GL, COMPILES, 1);
            CgTrace.add(CgChannels.GL, AHEAD_HITS, 1);
            if (LOG_COMPILES.get()) LOGGER.info("compute precached: {} {}", path, key);
            return;
        }
        CgIO.Reads recorded = CgIO.recordReads();
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, PRECACHE)) {
            precaching.put(key, CgKernelProgram.submit(source, decl(kernel), kernel.keywords(), CgKernelTarget.current()));
        } finally {
            recorded.close();
            addReads(recorded.paths());
        }
        PRECACHING.add(this);
        CgTrace.add(CgChannels.GL, COMPILES, 1);
        if (LOG_COMPILES.get()) LOGGER.info("compute precached: {} {}", path, key);
    }

    private CgKernelDecl decl(CgKernel kernel) {
        CgKernelDecl decl = source.kernel(kernel.name());
        if (decl == null) throw new IllegalStateException("[" + path + "] no longer has kernel '" + kernel.name() + "'");
        return decl;
    }

    /** Re-reads the file and drops its programs, which the next use compiles from the new text. */
    public synchronized void reload() {
        if (generated) return;
        String latest = read(path);
        CgComputeSource parsed = CgComputeParser.parse(latest, path);
        release();
        text = latest;
        source = parsed;
        properties = CgPropertyBlock.of(parsed.properties());
    }

    /**
     * Parses its text again (read again from its file, unless generated) and rebuilds each program it had compiled
     * before taking any of it: a file that no longer parses or a program that no longer compiles throws, and it keeps
     * everything it had. Lowered forms and programs being precached are dropped, and built again at their next use.
     * Render thread: what {@link CgAssetReloader} calls when a file it read changes.
     */
    @Override
    public synchronized void refresh() {
        String latest = generated ? text : read(path);
        CgIO.Reads recorded = CgIO.recordReads();
        CgComputeSource parsed;
        Map<String, CgKernelProgram> built = new HashMap<>();
        try {
            parsed = CgComputeParser.parse(latest, path);
            List<CgKernel> compiled = new ArrayList<>(kernels.values());
            compiled.addAll(variants.keySet());
            for (CgKernel kernel : compiled) {
                String key = kernel.programKey;
                CgKernelProgram old = programs.get(key);
                CgKernelDecl decl = parsed.kernel(kernel.name());
                if (old == null || old.isDeleted() || decl == null || built.containsKey(key)) continue;
                built.put(key, CgKernelProgram.build(parsed, decl, kernel.keywords()));
            }
        } catch (RuntimeException e) {
            for (CgKernelProgram program : built.values()) program.delete();
            throw e;
        } finally {
            recorded.close();
        }
        release();
        programs.putAll(built);
        text = latest;
        source = parsed;
        properties = CgPropertyBlock.of(parsed.properties());
        reads = new HashSet<>(recorded.paths());
        track();
    }

    private CgKernelProgram build(CgComputeSource from, CgKernelDecl decl, CgKernel kernel) {
        CgIO.Reads recorded = CgIO.recordReads();
        try {
            return CgKernelProgram.build(from, decl, kernel.keywords());
        } finally {
            recorded.close();
            addReads(recorded.paths());
        }
    }

    private synchronized void addReads(Set<String> paths) {
        if (reads.containsAll(paths)) return;
        Set<String> union = new HashSet<>(reads);
        union.addAll(paths);
        reads = union;
        track();
    }

    /** Its own file too: {@link #load} reads it before the constructor records anything. */
    private void track() {
        Set<String> tracked = new HashSet<>(reads);
        if (!generated) tracked.add(path);
        CgAssetReloader.track(this, tracked);
    }

    @Override
    public String toString() {
        return path;
    }

    /** Deletes every program; the parsed source stays, and the next use compiles again. Render thread. */
    public synchronized void release() {
        for (CgKernelProgram program : programs.values()) program.delete();
        programs.clear();
        for (CgKernelProgram.Pending pending : precaching.values()) pending.delete();
        precaching.clear();
        generation++;
    }

    /**
     * Makes each precached program the driver has linked into the program a dispatch takes, about a millisecond's worth a
     * frame: the link's result read, its blocks wired and its first bind, which on NVIDIA is where the driver finishes a
     * program, up to 1.6 ms each. Without it the first dispatch of each pays that. Render thread, between frames.
     */
    public static void finishPrecached() {
        // Between links only: during one, NVIDIA makes the finish's program calls wait for it, up to half a second.
        if (PRECACHING.isEmpty() || CgLinkWorker.linking()) return;
        long start = System.nanoTime();
        // Inside the frame's warm budget, with a floor so finishing never starves while warming spends it.
        long until = start + Math.max(FINISH_LEAST, Math.min(FINISH_NANOS, CgWarmBudget.remaining()));
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, FINISH)) {
            for (Iterator<CgCompute> it = PRECACHING.iterator(); it.hasNext(); ) {
                CgCompute compute = it.next();
                if (compute.finishPrecached(until)) it.remove();
                if (System.nanoTime() > until) return;
            }
        } finally {
            CgWarmBudget.spend(System.nanoTime() - start);
        }
    }

    /** Whether nothing it precached is left unfinished. */
    private synchronized boolean finishPrecached(long until) {
        for (Iterator<Map.Entry<String, CgKernelProgram.Pending>> it = precaching.entrySet().iterator(); it.hasNext(); ) {
            if (System.nanoTime() > until) return false;
            Map.Entry<String, CgKernelProgram.Pending> entry = it.next();
            if (!entry.getValue().isDone()) continue;
            it.remove();
            try {
                programs.put(entry.getKey(), entry.getValue().finish());
            } catch (IllegalStateException e) {
                // Its first dispatch builds it again, and throws there with the log.
                LOGGER.warn("[{}] a precached kernel failed: {}", path, e.getMessage());
            }
        }
        return precaching.isEmpty();
    }

    /** Every loaded file read again: what F3+T does. A file that no longer parses keeps its last good version. */
    public static void reloadAll() {
        for (CgCompute compute : LOADED.values()) {
            try {
                compute.reload();
            } catch (RuntimeException e) {
                LOGGER.error("Kept the last good {}: {}", compute.path, e.getMessage());
            }
        }
    }

    /** Every program of every file, at context teardown. */
    public static void releaseAll() {
        for (CgCompute retired; (retired = RETIRED.poll()) != null; ) retired.release();
        for (CgCompute compute : LOADED.values()) compute.release();
        for (CgCompute compute : GENERATED.values()) compute.release();
        CgBufferReadback.release();
        CgComputeCheck.release();
    }

    private List<String> kernelNames() {
        List<String> names = new ArrayList<>();
        for (CgKernelDecl k : source.kernels()) names.add(k.name());
        return names;
    }

    private static String read(String path) {
        String text = CgIO.loadSource(path);
        if (text == null) throw new IllegalArgumentException("no .compute at '" + path + "'");
        return text;
    }
}
