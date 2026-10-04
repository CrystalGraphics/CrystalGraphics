package com.crystalgraphics.compute;

import com.crystalgraphics.compute.cpu.CgCpuBody;
import com.crystalgraphics.compute.cpu.CgCpuMirrors;
import com.crystalgraphics.compute.cpu.CgCpuRunner;
import com.crystalgraphics.compute.emit.CgKernelTarget;
import com.crystalgraphics.compute.emit.CgPropertyBlock;
import com.crystalgraphics.compute.lower.CgLoweredKernel;
import com.crystalgraphics.compute.lower.CgLoweredResources;
import com.crystalgraphics.compute.parse.CgComputeParser;
import com.crystalgraphics.compute.program.CgComputeCheck;
import com.crystalgraphics.compute.program.CgKernelProgram;
import com.crystalgraphics.compute.source.CgComputeSource;
import com.crystalgraphics.compute.source.CgKernelDecl;
import com.crystalgraphics.gl.buffer.CgBufferReadback;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.io.CgIO;
import com.crystalgraphics.util.trace.CgChannels;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
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
public final class CgCompute {

    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphics");
    private static final Map<String, CgCompute> LOADED = new ConcurrentHashMap<>();
    private static final Map<String, CgCompute> GENERATED = new ConcurrentHashMap<>();
    /** Replaced generated files, whose programs the render thread deletes. */
    private static final Queue<CgCompute> RETIRED = new ConcurrentLinkedQueue<>();
    private static final int COMPILE = CgTrace.name("compute.compile"), COMPILE_LOWERED = CgTrace.name("compute.compileLowered");
    private static final int COMPILES = CgTrace.name("compute.compiles");
    private static final int PREPARE = CgTrace.name("compute.prepare"), COMPILE_WAIT = CgTrace.name("compute.compileWait");
    private static final int COMPILE_WAITS = CgTrace.name("compute.compile-waits");

    private final String path;
    private final boolean generated;
    private volatile CgComputeSource source;
    private volatile CgPropertyBlock properties;
    private volatile String text;
    /** Counts every release: a kernel holding a program from before asks again. */
    private volatile int generation;
    private final Map<String, CgKernelProgram> programs = new HashMap<>();
    /** Programs {@link CgKernel#prepare} started and no dispatch has taken yet. */
    private final Map<String, CgKernelProgram.Pending> preparing = new HashMap<>();
    private final Map<String, CgLoweredKernel> lowered = new HashMap<>();
    /** Java bodies by kernel name, kept across reloads. */
    private final Map<String, CgCpuBody> bodies = new ConcurrentHashMap<>();
    /** Each kernel with no keywords, made once: a kernel keeps its checks and its program across calls. */
    private final Map<String, CgKernel> kernels = new ConcurrentHashMap<>();
    /** Each kernel with keywords, made once, as {@link #kernels}. */
    private final Map<CgKernel, CgKernel> variants = new ConcurrentHashMap<>();
    /** Counts every body given: a kernel's form chosen before one may change. */
    private volatile int bodiesGiven;

    private CgCompute(String path, String text, boolean generated) {
        this.path = path;
        this.generated = generated;
        this.text = text;
        this.source = CgComputeParser.parse(text, path);
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
        if (previous != null) RETIRED.add(previous);
        return created;
    }

    /** The kernel {@code name}, with no keywords: the same instance every call, so a per-frame caller makes nothing. */
    public CgKernel kernel(String name) {
        if (source.kernel(name) == null) {
            throw new IllegalArgumentException("[" + path + "] has no kernel '" + name + "': " + kernelNames());
        }
        CgKernel kernel = kernels.get(name);
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
        String key = kernel.name() + kernel.keywords();
        CgKernelProgram program = programs.get(key);
        if (program == null || program.isDeleted()) {
            CgKernelProgram.Pending pending = preparing.remove(key);
            if (pending != null && pending.isDone()) {
                program = pending.finish();
            } else if (pending != null) {
                try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, COMPILE_WAIT)) {
                    program = pending.finish();
                }
                CgTrace.add(CgChannels.GL, COMPILE_WAITS, 1);
            } else {
                try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, COMPILE)) {
                    program = CgKernelProgram.build(source, decl(kernel), kernel.keywords());
                }
                CgTrace.add(CgChannels.GL, COMPILES, 1);
            }
            programs.put(key, program);
        }
        return program;
    }

    /** Starts {@code kernel}'s program without waiting, unless it is compiled or started. Render thread. */
    synchronized void prepare(CgKernel kernel) {
        for (CgCompute retired; (retired = RETIRED.poll()) != null; ) retired.release();
        String key = kernel.name() + kernel.keywords();
        CgKernelProgram program = programs.get(key);
        if (program != null && !program.isDeleted() || preparing.containsKey(key)) return;
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, PREPARE)) {
            preparing.put(key, CgKernelProgram.submit(source, decl(kernel), kernel.keywords(), CgKernelTarget.current()));
        }
        CgTrace.add(CgChannels.GL, COMPILES, 1);
    }

    private CgKernelDecl decl(CgKernel kernel) {
        CgKernelDecl decl = source.kernel(kernel.name());
        if (decl == null) throw new IllegalStateException("[" + path + "] no longer has kernel '" + kernel.name() + "'");
        return decl;
    }

    /** The lowered form of {@code kernel}, running {@code runs} (itself or its fallback), built the first time. */
    synchronized CgLoweredKernel lowered(CgKernel kernel, CgKernelDecl runs) {
        String key = runs.name() + kernel.keywords();
        CgLoweredKernel built = lowered.get(key);
        if (built == null || built.isDeleted()) {
            try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, COMPILE_LOWERED)) {
                built = CgLoweredKernel.build(source, runs, kernel.keywords());
            }
            CgTrace.add(CgChannels.GL, COMPILES, 1);
            lowered.put(key, built);
        }
        return built;
    }

    void cpu(String kernel, CgCpuBody body) {
        bodies.put(kernel, body);
        bodiesGiven++;
    }

    /** The Java body kernel {@code name} was given, or null. */
    public CgCpuBody cpuBody(String name) {
        return bodies.get(name);
    }

    int bodiesGiven() {
        return bodiesGiven;
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

    /** Deletes every program; the parsed source stays, and the next use compiles again. Render thread. */
    public synchronized void release() {
        for (CgKernelProgram program : programs.values()) program.delete();
        programs.clear();
        for (CgKernelProgram.Pending pending : preparing.values()) pending.delete();
        preparing.clear();
        for (CgLoweredKernel kernel : lowered.values()) kernel.delete();
        lowered.clear();
        generation++;
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

    /** Every program of every file, and what lowered dispatches share, at context teardown. */
    public static void releaseAll() {
        for (CgCompute retired; (retired = RETIRED.poll()) != null; ) retired.release();
        for (CgCompute compute : LOADED.values()) compute.release();
        for (CgCompute compute : GENERATED.values()) compute.release();
        CgLoweredResources.releaseAll();
        CgCpuMirrors.releaseAll();
        CgCpuRunner.releaseAll();
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
