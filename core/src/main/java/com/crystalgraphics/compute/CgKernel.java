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

import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/**
 * One kernel of a {@link CgCompute} with a keyword set: a value, cheap to make and to keep. Its program is compiled
 * on the first {@link #program()} and shared by every equal kernel.
 *
 * <pre>{@code
 * CgKernel blur = CgCompute.load("mymod:shaders/blur.compute").kernel("Blur");
 * CgKernel wide = blur.withKeywords("WIDE");          // a separate program
 * wide.program().use().image("OUT", target, 0).dispatch(width, height, 1);
 * }</pre>
 *
 * <p>Where no GPU tier can run it, or the CPU tier is forced, a Java body runs instead:</p>
 * <pre>{@code
 * CgKernel simulate = particles.kernel("Simulate").cpu(d -> { ... });   // every keyword set shares it
 * CgKernelForm form = simulate.form();                                  // COMPUTE, LOWERED or CPU, on this context
 * }</pre>
 */
public final class CgKernel {

    private final CgCompute compute;
    private final String name;
    private final Set<String> keywords;
    /** The program last answered, while its file has not released it. */
    private volatile CgKernelProgram program;
    private volatile int programGeneration = -1;
    /** The form last chosen, and what it was chosen under: the file's generation, the bodies given, the tier. */
    private volatile CgKernelForm form;
    private volatile long formKey = Long.MIN_VALUE;

    CgKernel(CgCompute compute, String name, Set<String> keywords) {
        this.compute = compute;
        this.name = name;
        this.keywords = keywords;
    }

    /**
     * This kernel with exactly {@code keywords} on.
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
        return new CgKernel(compute, name, Collections.unmodifiableSet(set));
    }

    /** What the file declares about it: size, shape, what it reaches. */
    public CgKernelDecl decl() {
        return compute.source().kernel(name);
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
     * Gives this kernel a Java body, which the CPU tier runs: where no GPU tier can run the kernel, or where the CPU
     * tier is forced. Every keyword set of the kernel shares it; {@link CgCpuDispatch#keyword} tells them apart.
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
        ComputeTier tier = CgCapabilities.detect().computeTier();
        long key = ((long) compute.generation() << 40) ^ ((long) compute.bodiesGiven() << 8) ^ tier.ordinal();
        CgKernelForm held = form;
        if (held != null && formKey == key) return held;
        held = CgKernelForm.choose(compute.source(), decl(), tier, n -> compute.cpuBody(n) != null);
        form = held;
        formKey = key;
        return held;
    }

    /** Its lowered form for the current context, built the first time: the kernel, or its fallback. Render thread. */
    public CgLoweredKernel lowered() {
        CgKernelForm f = form();
        return compute.lowered(this, f.how() == CgKernelForm.How.LOWERED ? f.runs() : decl());
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
