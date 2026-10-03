package com.crystalgraphics.compute.emit;

import com.crystalgraphics.platform.gl.CgCapabilities;

/**
 * What a kernel is emitted for: the GLSL the device compiles, its subgroup operations and float atomics, and the
 * limits a kernel is checked against. {@link #current()} on the render thread; a constant in a test.
 *
 * <pre>{@code
 * String glsl = CgKernelEmitter.emit(source, kernel, Set.of(), CgKernelTarget.current());
 * String noSubgroups = CgKernelEmitter.emit(source, kernel, Set.of(), CgKernelTarget.GL43.withSubgroups(0));
 * }</pre>
 *
 * @param arb                {@code #version 330} with the ARB compute extensions, for a context below GL 4.3
 * @param subgroupOperations {@link CgCapabilities#subgroupOperations()}'s bits
 * @param floatAtomics       {@code atomicAdd} on a float buffer element ({@code NV_shader_atomic_float})
 */
public record CgKernelTarget(boolean arb, int subgroupOperations, boolean floatAtomics, int maxSharedMemory,
                             int maxInvocations, int maxSizeX, int maxSizeY, int maxSizeZ) {

    /** The operations {@code CG_SUBGROUP_*} maps onto; a device short of any runs every one emulated. */
    public static final int NATIVE_SUBGROUPS = CgCapabilities.SUBGROUP_BASIC | CgCapabilities.SUBGROUP_VOTE
            | CgCapabilities.SUBGROUP_ARITHMETIC | CgCapabilities.SUBGROUP_BALLOT | CgCapabilities.SUBGROUP_SHUFFLE;

    /** GL 4.3's guaranteed limits, with every subgroup operation and no float atomics. */
    public static final CgKernelTarget GL43 = new CgKernelTarget(false, NATIVE_SUBGROUPS, false, 32768, 1024, 1024, 1024, 64);

    /** The current context's. */
    public static CgKernelTarget current() {
        CgCapabilities caps = CgCapabilities.detect();
        if (!caps.compute()) {
            throw new IllegalStateException("this context runs no compute shaders: kernels run at tier "
                    + caps.computeTier());
        }
        return new CgKernelTarget(caps.shaderBufferPath() == CgCapabilities.ShaderBufferPath.SSBO_ARB,
                caps.subgroupOperations(), caps.floatAtomics(), caps.maxComputeSharedMemory(),
                caps.maxComputeInvocations(), caps.maxComputeWorkGroupSize(0), caps.maxComputeWorkGroupSize(1),
                caps.maxComputeWorkGroupSize(2));
    }

    /** {@code CG_SUBGROUP_*} as the device's own operations, not the work-group emulation. */
    public boolean nativeSubgroups() {
        return (subgroupOperations & NATIVE_SUBGROUPS) == NATIVE_SUBGROUPS;
    }

    public CgKernelTarget withSubgroups(int operations) {
        return new CgKernelTarget(arb, operations, floatAtomics, maxSharedMemory, maxInvocations, maxSizeX, maxSizeY,
                maxSizeZ);
    }

    public CgKernelTarget withArb(boolean arb) {
        return new CgKernelTarget(arb, subgroupOperations, floatAtomics, maxSharedMemory, maxInvocations, maxSizeX,
                maxSizeY, maxSizeZ);
    }

    public CgKernelTarget withFloatAtomics(boolean floatAtomics) {
        return new CgKernelTarget(arb, subgroupOperations, floatAtomics, maxSharedMemory, maxInvocations, maxSizeX,
                maxSizeY, maxSizeZ);
    }

    /** The largest size along {@code axis}, 0 to 2. */
    public int maxSize(int axis) {
        return axis == 0 ? maxSizeX : axis == 1 ? maxSizeY : maxSizeZ;
    }
}
