package com.crystalgraphics.compute;

import com.crystalgraphics.compute.emit.CgKernelEmitter;
import com.crystalgraphics.compute.emit.CgKernelTarget;
import com.crystalgraphics.compute.cpu.CgCpuBody;
import com.crystalgraphics.compute.cpu.CgCpuDispatch;
import com.crystalgraphics.compute.lower.CgLoweredKernel;
import com.crystalgraphics.compute.program.CgKernelProgram;
import com.crystalgraphics.compute.source.CgKernelDecl;
import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.platform.gl.CgCapabilities.ComputeTier;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/**
 * One kernel of a {@link CgCompute} with a keyword set. Its file answers the same instance for the same set, which
 * keeps its checks, its form and its program, so looking it up every frame costs a map read. Its program is compiled
 * on the first {@link #program()}.
 *
 * <pre>{@code
 * CgKernel blur = CgCompute.load("mymod:shaders/blur.compute").kernel("Blur");
 * CgKernel wide = blur.withKeywords("WIDE");          // a separate program
 * wide.program().use().image("OUT", target, 0).dispatch(width, height, 1);
 * }</pre>
 *
 * <p>Where the CPU tier is forced, for debugging and tests, a Java body runs instead. No player's tier runs one, so a
 * shipped kernel has none:</p>
 * <pre>{@code
 * CgKernel simulate = particles.kernel("Simulate").cpu(d -> { ... });   // every keyword set shares it
 * CgKernelForm form = simulate.form();                                  // COMPUTE, LOWERED or CPU, on this context
 * }</pre>
 *
 * <p>Every tier a player may have is asked of a kernel the first time it is recorded or its program is made, on any
 * machine ({@link #check}). A kernel meant for compute alone is declared {@code #pragma compute_only} and asked first:</p>
 * <pre>{@code
 * if (sort.runs()) recording.compute(pass -> pass.dispatch(sort, ...));   // false below compute
 * }</pre>
 */
public final class CgKernel {

    private final CgCompute compute;
    private final String name;
    private final Set<String> keywords;
    /** The program last answered, while its file has not released it. */
    private volatile CgKernelProgram program;
    private volatile int programGeneration = -1;
    /** The lowered form last answered, and what it was chosen under, as {@link #program}. */
    private volatile CgLoweredKernel lowered;
    private volatile long loweredKey = Long.MIN_VALUE;
    /** The form last chosen, or why there is none, and what it was chosen under. */
    private volatile Choice choice;
    /** The file generation the every-tier check last passed under. */
    private volatile long checkedKey = Long.MIN_VALUE;

    CgKernel(CgCompute compute, String name, Set<String> keywords) {
        this.compute = compute;
        this.name = name;
        this.keywords = keywords;
    }

    /**
     * This kernel with exactly {@code keywords} on: the same instance for the same set, so it keeps its checks and its
     * program, but the set is built each call: a per-frame caller holds the kernel.
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
        return compute.source().kernel(name);
    }

    /** Its program for the current context, compiled the first time. Render thread. */
    public CgKernelProgram program() {
        check();
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
     * on, and the dispatch that takes it waits only for what is left ({@code compute.compileWait}). A lowered kernel's
     * passes are built now; a Java body needs nothing. Render thread.
     *
     * <pre>{@code
     * CgKernel simulate = CgCompute.load("mymod:shaders/particles.compute").kernel("Simulate").prepare();   // at load
     * }</pre>
     *
     * @throws IllegalStateException as {@link #program()} does, for a kernel some tier or this context cannot run
     */
    public CgKernel prepare() {
        check();
        CgKernelForm f = form();
        if (f.how() == CgKernelForm.How.COMPUTE) compute.prepare(this);
        else if (f.how() == CgKernelForm.How.LOWERED) lowered();
        return this;
    }

    /**
     * Gives this kernel a Java body, which the CPU tier runs when it is forced: for debugging and tests. No player's
     * tier runs one, so a shipped kernel needs none. Every keyword set of the kernel shares it;
     * {@link CgCpuDispatch#keyword} tells them apart.
     */
    public CgKernel cpu(CgCpuBody body) {
        compute.cpu(name, body);
        return this;
    }

    /**
     * How the current context runs this kernel. Render thread, or any thread once the context's capabilities are
     * known.
     *
     * @throws IllegalStateException naming what stops it, where it can run nowhere
     */
    public CgKernelForm form() {
        Choice c = choice();
        if (c.refusal != null) throw c.refusal;
        return c.form;
    }

    /** The choice for the current context: kept, a refusal included, while the file, its bodies and the tier hold. */
    private Choice choice() {
        ComputeTier tier = CgCapabilities.detect().computeTier();
        long key = ((long) compute.generation() << 40) ^ ((long) compute.bodiesGiven() << 8) ^ tier.ordinal();
        Choice held = choice;
        if (held != null && held.key == key) return held;
        try {
            held = new Choice(key, CgKernelForm.choose(compute.source(), decl(), tier, n -> compute.cpuBody(n) != null),
                    null);
        } catch (IllegalStateException e) {
            held = new Choice(key, null, e);
        }
        choice = held;
        return held;
    }

    /**
     * Asks every tier a player's context may be at whether it can run this kernel, as {@link CgKernelForm#check}
     * does, once per file generation. Any thread.
     *
     * @throws IllegalStateException naming the tier and what stops it
     */
    public void check() {
        long key = compute.generation();
        if (checkedKey == key) return;
        CgKernelForm.check(compute.source(), decl());
        checkedKey = key;
    }

    /**
     * Whether the current context runs this kernel: false for a {@code compute_only} kernel below compute (and on the
     * forced CPU tier, without a Java body), which a feature built on it asks before it is offered. Render thread, or once capabilities are known.
     */
    public boolean runs() {
        return choice().refusal == null;
    }

    /** Its lowered form for the current context, built the first time: the kernel, or its fallback. Render thread. */
    public CgLoweredKernel lowered() {
        Choice c = choice();
        CgLoweredKernel held = lowered;
        if (held != null && loweredKey == c.key && !held.isDeleted()) return held;
        CgKernelForm f = form();
        held = compute.lowered(this, f.how() == CgKernelForm.How.LOWERED ? f.runs() : decl());
        lowered = held;
        loweredKey = c.key;
        return held;
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

    /** Keyed by the file's generation, the bodies given and the tier; one of form and refusal is null. */
    private record Choice(long key, @Nullable CgKernelForm form, @Nullable IllegalStateException refusal) {}
}
