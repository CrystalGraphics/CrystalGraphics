package com.crystalgraphics.compute;

import com.crystalgraphics.compute.parse.CgComputeParser;
import com.crystalgraphics.compute.program.CgKernelProgram;
import com.crystalgraphics.compute.source.CgComputeSource;
import com.crystalgraphics.compute.source.CgKernelDecl;
import com.crystalgraphics.util.io.CgIO;
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

    private final String path;
    private final boolean generated;
    private volatile CgComputeSource source;
    private volatile String text;
    private final Map<String, CgKernelProgram> programs = new HashMap<>();

    private CgCompute(String path, String text, boolean generated) {
        this.path = path;
        this.generated = generated;
        this.text = text;
        this.source = CgComputeParser.parse(text, path);
    }

    /**
     * The {@code .compute} at {@code path} ({@code "mymod:shaders/particles.compute"}), parsed once.
     *
     * @throws IllegalArgumentException if nothing is at {@code path}
     */
    public static CgCompute load(String path) {
        return LOADED.computeIfAbsent(path, p -> new CgCompute(p, read(p), false));
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

    /** The kernel {@code name}, with no keywords. */
    public CgKernel kernel(String name) {
        if (source.kernel(name) == null) {
            throw new IllegalArgumentException("[" + path + "] has no kernel '" + name + "': " + kernelNames());
        }
        return new CgKernel(this, name, Set.of());
    }

    public CgComputeSource source() {
        return source;
    }

    public String path() {
        return path;
    }

    /** The compiled program for {@code kernel}, compiled and cached on the first call. Render thread. */
    synchronized CgKernelProgram program(CgKernel kernel) {
        for (CgCompute retired; (retired = RETIRED.poll()) != null; ) retired.release();
        String key = kernel.name() + kernel.keywords();
        CgKernelProgram program = programs.get(key);
        if (program == null || program.isDeleted()) {
            CgKernelDecl decl = source.kernel(kernel.name());
            if (decl == null) throw new IllegalStateException("[" + path + "] no longer has kernel '" + kernel.name() + "'");
            program = CgKernelProgram.build(source, decl, kernel.keywords());
            programs.put(key, program);
        }
        return program;
    }

    /** Re-reads the file and drops its programs, which the next use compiles from the new text. */
    public synchronized void reload() {
        if (generated) return;
        String latest = read(path);
        CgComputeSource parsed = CgComputeParser.parse(latest, path);
        release();
        text = latest;
        source = parsed;
    }

    /** Deletes every program; the parsed source stays, and the next use compiles again. Render thread. */
    public synchronized void release() {
        for (CgKernelProgram program : programs.values()) program.delete();
        programs.clear();
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
