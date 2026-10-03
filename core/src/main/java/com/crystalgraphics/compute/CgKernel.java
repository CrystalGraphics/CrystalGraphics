package com.crystalgraphics.compute;

import com.crystalgraphics.compute.emit.CgKernelEmitter;
import com.crystalgraphics.compute.emit.CgKernelTarget;
import com.crystalgraphics.compute.program.CgKernelProgram;
import com.crystalgraphics.compute.source.CgKernelDecl;

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
 */
public final class CgKernel {

    private final CgCompute compute;
    private final String name;
    private final Set<String> keywords;
    /** The program last answered, while its file has not released it. */
    private volatile CgKernelProgram program;
    private volatile int programGeneration = -1;

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
