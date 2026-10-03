package com.crystalgraphics.compute.source;

import java.util.Set;

/**
 * One kernel of a {@code .compute}: what its {@code #pragma kernel} declares, and what its code reaches, which is
 * what the emitter writes for it and nothing more.
 *
 * <pre>{@code
 * #pragma kernel Blur 8 8 image     // name Blur, local size 8x8x1, two dimensions, shape image
 * #pragma kernel Bin 256 general
 * #pragma fallback Bin BinScatter   // a tier without compute runs BinScatter instead
 * #pragma compute_only Sort         // runs only where compute does; kernel.runs() asks
 * }</pre>
 *
 * @param dimensions  how many sizes the pragma gave: 1 for an unsized kernel, which takes {@link #DEFAULT_SIZE}
 * @param fallback    the kernel a tier that cannot run this one runs instead, or null
 * @param functions   the file's functions this kernel calls, itself included, at any depth
 * @param shared      the {@code shared} variables those functions name
 * @param accessors   the generated names those functions use: {@code STATE}, {@code STATE_WRITE}, {@code BINS_INC}
 * @param subgroups   the {@code CG_SUBGROUP_*} macros those functions use
 * @param sharedBytes what {@code shared} holds, or -1 where an array's size is not a constant this compiler reads
 * @param builtins    the builtins newer than GLSL 3.30 those functions name ({@code CgGlslBuiltins})
 * @param computeOnly declared {@code #pragma compute_only}: never lowered, and no tier below compute is asked of it
 */
public record CgKernelDecl(String name, int sizeX, int sizeY, int sizeZ, int dimensions, CgKernelShape shape,
                           String fallback, Set<String> functions, Set<String> shared, Set<String> accessors,
                           Set<String> subgroups, int sharedBytes, Set<String> builtins, boolean computeOnly) {

    /**
     * An unsized kernel's local size: a multiple of every vendor's subgroup (32 on NVIDIA, 32 or 64 on AMD, 8 to 32
     * on Intel) within Vulkan's 128-invocation minimum.
     */
    public static final int DEFAULT_SIZE = 64;

    /** Invocations in one work group. */
    public int groupSize() { return sizeX * sizeY * sizeZ; }

    /** The size along {@code axis}, 0 to 2. */
    public int size(int axis) { return axis == 0 ? sizeX : axis == 1 ? sizeY : sizeZ; }
}
