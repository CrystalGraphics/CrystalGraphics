package com.crystalgraphics.compute;

import com.crystalgraphics.compute.emit.CgKernelEmitter;
import com.crystalgraphics.compute.emit.CgKernelTarget;
import com.crystalgraphics.compute.program.CgKernelProgram;
import com.crystalgraphics.compute.source.CgBufferAccessor;
import com.crystalgraphics.compute.source.CgBufferDecl;
import com.crystalgraphics.compute.source.CgComputeSource;
import com.crystalgraphics.compute.source.CgImageAccessor;
import com.crystalgraphics.compute.source.CgImageDecl;
import com.crystalgraphics.compute.source.CgKernelDecl;
import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.platform.gl.CgCapabilities.ComputeTier;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * One kernel of a {@link CgCompute} with a keyword set. Its file answers the same instance for the same set, which
 * keeps its program, so looking it up every frame costs a map read. Its program is compiled on the first
 * {@link #program()}.
 *
 * <pre>{@code
 * CgKernel blur = CgCompute.load("mymod:shaders/blur.compute").kernel("Blur");
 * CgKernel wide = blur.withKeywords("WIDE");          // a separate program
 * wide.program().use().image("OUT", target, 0).dispatch(width, height, 1);
 * }</pre>
 *
 * <p>A kernel runs as a compute shader, on GL 4.3 and up or a device; a dispatch on a context without compute is
 * refused where it is recorded ({@link #requireCompute}).</p>
 */
public final class CgKernel {

    private final CgCompute compute;
    private final String name;
    private final Set<String> keywords;
    /** Its file's key for its program, name then keywords: built once, since a precache looks it up each frame. */
    final String programKey;
    /** The program last answered, while its file has not released it. */
    private volatile CgKernelProgram program;
    private volatile int programGeneration = -1;
    /** Which accessors it uses, per buffer and image, and the declaration they were read from. */
    private volatile Accessors accessors;

    private record Accessors(CgKernelDecl decl, int[] buffers, int[] images) {}

    CgKernel(CgCompute compute, String name, Set<String> keywords) {
        this.compute = compute;
        this.name = name;
        this.keywords = keywords;
        this.programKey = name + keywords;
    }

    /**
     * This kernel with exactly {@code keywords} on: the same instance for the same set, so it keeps its program, but
     * the set is built each call: a per-frame caller holds the kernel.
     *
     * @throws IllegalArgumentException for a keyword the file does not declare with {@code #pragma cg_feature}
     */
    public CgKernel withKeywords(String... keywords) {
        Set<String> set = new TreeSet<>(Arrays.asList(keywords));
        for (String keyword : set) {
            if (!compute.source().features().contains(keyword)) {
                throw new IllegalArgumentException("[" + compute.path() + "] keyword '" + keyword + "' is not declared: "
                        + compute.source().features());
            }
        }
        if (set.isEmpty()) return compute.kernel(name);
        return compute.shared(new CgKernel(compute, name, Collections.unmodifiableSet(set)));
    }

    /** What the file declares about it: size, shape, what it reaches. */
    public CgKernelDecl decl() {
        CgComputeSource source = compute.source();
        if (source != declSource) {   // looked up again once a reload replaced the file's source
            declHeld = source.kernel(name);
            declSource = source;
        }
        return declHeld;
    }

    private CgComputeSource declSource;
    private CgKernelDecl declHeld;

    /**
     * Per buffer of its file, by {@code CgBufferDecl.index()}: the {@link CgBufferAccessor}s this kernel uses, bit
     * {@code ordinal()} of each. Read once per declaration, since a dispatch asks it of every binding. Do not write it.
     */
    public int[] bufferAccessors() {
        return accessors().buffers();
    }

    /** {@link #bufferAccessors()} for images: per {@code CgImageDecl.index()}, a bit per {@link CgImageAccessor}. */
    public int[] imageAccessors() {
        return accessors().images();
    }

    private Accessors accessors() {
        CgKernelDecl decl = decl();
        Accessors held = accessors;
        if (held != null && held.decl() == decl) return held;
        CgComputeSource source = compute.source();
        int[] buffers = new int[source.buffers().size()], images = new int[source.images().size()];
        for (CgBufferDecl b : source.buffers()) {
            for (CgBufferAccessor a : CgBufferAccessor.values()) {
                if (decl.accessors().contains(b.name() + a.suffix)) buffers[b.index()] |= 1 << a.ordinal();
            }
        }
        for (CgImageDecl image : source.images()) {
            for (CgImageAccessor a : CgImageAccessor.values()) {
                if (decl.accessors().contains(image.name() + a.suffix)) images[image.index()] |= 1 << a.ordinal();
            }
        }
        held = new Accessors(decl, buffers, images);
        accessors = held;
        return held;
    }

    /** Its program for the current context, compiled the first time. Render thread. */
    public CgKernelProgram program() {
        CgKernelProgram held = program;
        if (held != null && programGeneration == compute.generation() && !held.isDeleted()) return held;
        int generation = compute.generation();
        held = compute.program(this);
        program = held;
        programGeneration = generation;
        return held;
    }

    /**
     * Starts this kernel's program for the current context without waiting for it, so its first dispatch need not:
     * where the driver links on threads of its own ({@code KHR_parallel_shader_compile}) the link runs while frames go
     * on, and the dispatch that takes it waits only for what is left ({@code compute.compileWait}). Render thread.
     *
     * <pre>{@code
     * CgKernel simulate = CgCompute.load("mymod:shaders/particles.compute").kernel("Simulate").precache();   // at load
     * }</pre>
     */
    public CgKernel precache() {
        List<CgKernel> collecting = COLLECT.get();
        if (collecting != null) collecting.add(this);
        if (AHEAD.get()) compute.loadAhead(this);
        else compute.precache(this);
        return this;
    }

    private static final ThreadLocal<Boolean> AHEAD = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<List<CgKernel>> COLLECT = new ThreadLocal<>();

    /**
     * Whether a dispatch of it now would compile and wait for nothing, starting its program if nothing has: what work
     * held until its kernels are built polls each frame, Niagara's activation delayed until its PSOs are precached.
     * Render thread.
     *
     * <pre>{@code
     * List<CgKernel> kernels = CgKernel.collect(() -> MyEffect.precacheKernels());   // once
     * boolean go = true;
     * for (CgKernel k : kernels) go &= k.isReady();                                // each frame until true
     * }</pre>
     */
    public boolean isReady() {
        return compute.isReady(this);
    }

    /**
     * Every kernel {@code precaches} runs {@link #precache} on, in order, precached as well: the list to poll {@link #isReady}
     * over, gathered from a precache that already names them. Render thread.
     */
    public static List<CgKernel> collect(Runnable precaches) {
        List<CgKernel> outer = COLLECT.get(), collected = new ArrayList<>();
        COLLECT.set(collected);
        try {
            precaches.run();
        } finally {
            COLLECT.set(outer);
        }
        return collected;
    }

    /**
     * Runs {@code precaches} with every {@link #precache} in it making that kernel's sources rather than its program: on a
     * worker ahead of a warm-up, whose own {@code precache} then only submits.
     *
     * <pre>{@code
     * CgJob.executor(CgJobLane.LATENCY).execute(() -> CgKernel.ahead(CgVfxRange::precache));   // a worker
     * CgVfxRange.precache();                                                                   // later, the render thread
     * }</pre>
     */
    public static void ahead(Runnable precaches) {
        AHEAD.set(true);
        try {
            precaches.run();
        } finally {
            AHEAD.set(false);
        }
    }

    /**
     * Whether this thread is inside {@link #ahead}: a precache step that also starts a material skips it there, since
     * a material's precache drives the device and the render thread's pass after starts it.
     */
    public static boolean isAhead() {
        return AHEAD.get();
    }

    /**
     * Refuses a dispatch of it on a context that runs no compute, naming the tier. Where it is recorded, once the
     * context's capabilities are known.
     *
     * @throws IllegalStateException below GL 4.3 without a device
     */
    public void requireCompute() {
        ComputeTier tier = CgCapabilities.detect().computeTier();
        if (tier == ComputeTier.V || tier == ComputeTier.G43) return;
        throw new IllegalStateException("[" + compute.path() + "] kernel " + name + " cannot run at tier " + tier
                + ": kernels run as compute shaders, GL 4.3 and up or a device");
    }

    /** The GLSL it compiles from on the current context, includes unexpanded: what to read when it misbehaves. */
    public String glsl() {
        return CgKernelEmitter.emit(compute.source(), decl(), keywords, CgKernelTarget.current());
    }

    public String name() {
        return name;
    }

    public Set<String> keywords() {
        return keywords;
    }

    public CgCompute compute() {
        return compute;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof CgKernel k && k.compute == compute && k.name.equals(name) && k.keywords.equals(keywords);
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(compute) * 31 + name.hashCode() * 17 + keywords.hashCode();
    }

    @Override
    public String toString() {
        return compute.path() + "#" + name + (keywords.isEmpty() ? "" : keywords.toString());
    }
}
