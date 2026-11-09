package com.crystalgraphics.compute.emit;

import com.crystalgraphics.variable.CgVar;
import com.crystalgraphics.platform.gl.CgCapabilities;

import javax.annotation.Nullable;

/**
 * What a kernel is emitted for: its subgroup operations and float atomics, and the limits a kernel is checked against.
 * Every kernel is emitted at one GLSL, {@link CgKernelEmitter#VERSION}. {@link #current()} on the render thread; a constant in a test.
 *
 * <pre>{@code
 * String glsl = CgKernelEmitter.emit(source, kernel, Set.of(), CgKernelTarget.current());
 * String noSubgroups = CgKernelEmitter.emit(source, kernel, Set.of(), CgKernelTarget.GL43.withSubgroups(0));
 * }</pre>
 *
 * @param subgroupOperations {@link CgCapabilities#subgroupOperations()}'s bits
 * @param floatAtomics       {@code atomicAdd} on a float buffer element ({@code NV_shader_atomic_float})
 * @param checked            every access bounds-checked ({@code CgComputeCheck}'s checked mode)
 */
public record CgKernelTarget(int subgroupOperations, boolean floatAtomics, int maxSharedMemory,
                             int maxInvocations, int maxSizeX, int maxSizeY, int maxSizeZ, boolean checked) {

    /** The operations {@code CG_SUBGROUP_*} maps onto; a device short of any runs every one emulated. */
    public static final int NATIVE_SUBGROUPS = CgCapabilities.SUBGROUP_BASIC | CgCapabilities.SUBGROUP_VOTE
            | CgCapabilities.SUBGROUP_ARITHMETIC | CgCapabilities.SUBGROUP_BALLOT | CgCapabilities.SUBGROUP_SHUFFLE;

    /**
     * Checked mode, {@code -Dcrystalgraphics.compute.checked=true}: every kernel run as compute bounds-checks its
     * buffer and image accesses ({@code CgComputeCheck}).
     */
    public static final boolean CHECKED = CgVar.bool("crystalgraphics.compute.checked", false, CgVar.DEV | CgVar.RESTART,
            "Kernels run as compute bounds-check every buffer and image access: one out of range is skipped, and the first"
                    + " of each dispatch logged once with its .compute line. Every access tests its index.").get();

    /**
     * {@code -Dcrystalgraphics.compute.emulateSubgroups=true}: every kernel's {@code CG_SUBGROUP_*} on the work-group
     * emulation, as a device without subgroups runs it, so a scene's check covers that path on a device that has them.
     */
    public static final boolean EMULATE_SUBGROUPS = CgVar.bool("crystalgraphics.compute.emulateSubgroups", false,
            CgVar.DEV | CgVar.RESTART, "Kernels run their subgroup operations emulated over the work group, as on a device"
                    + " without subgroups.").get();

    /** GL 4.3's guaranteed limits, with every subgroup operation and no float atomics. */
    public static final CgKernelTarget GL43 = new CgKernelTarget(NATIVE_SUBGROUPS, false, 32768, 1024, 1024, 1024, 64, false);

    /** The current context's. */
    public static CgKernelTarget current() {
        return of(CgCapabilities.detect());
    }

    /**
     * The current context's if its capabilities are already detected, else null: for a job thread, which must never be
     * the one to probe GL (after teardown it has no context, and the driver aborts the process).
     */
    @Nullable
    public static CgKernelTarget detected() {
        CgCapabilities caps = CgCapabilities.detected();
        return caps == null ? null : of(caps);
    }

    private static CgKernelTarget of(CgCapabilities caps) {
        if (!caps.compute()) {
            throw new IllegalStateException("this context runs no compute shaders: kernels run at tier "
                    + caps.computeTier());
        }
        return new CgKernelTarget(EMULATE_SUBGROUPS ? 0 : caps.subgroupOperations(), caps.floatAtomics(),
                caps.maxComputeSharedMemory(),
                caps.maxComputeInvocations(), caps.maxComputeWorkGroupSize(0), caps.maxComputeWorkGroupSize(1),
                caps.maxComputeWorkGroupSize(2), CHECKED);
    }

    /** {@code CG_SUBGROUP_*} as the device's own operations, not the work-group emulation. */
    public boolean nativeSubgroups() {
        return (subgroupOperations & NATIVE_SUBGROUPS) == NATIVE_SUBGROUPS;
    }

    public CgKernelTarget withSubgroups(int operations) {
        return new CgKernelTarget(operations, floatAtomics, maxSharedMemory, maxInvocations, maxSizeX, maxSizeY, maxSizeZ,
                checked);
    }

    public CgKernelTarget withFloatAtomics(boolean floatAtomics) {
        return new CgKernelTarget(subgroupOperations, floatAtomics, maxSharedMemory, maxInvocations, maxSizeX, maxSizeY,
                maxSizeZ, checked);
    }

    public CgKernelTarget withChecked(boolean checked) {
        return new CgKernelTarget(subgroupOperations, floatAtomics, maxSharedMemory, maxInvocations, maxSizeX, maxSizeY,
                maxSizeZ, checked);
    }

    /** The largest size along {@code axis}, 0 to 2. */
    public int maxSize(int axis) {
        return axis == 0 ? maxSizeX : axis == 1 ? maxSizeY : maxSizeZ;
    }
}
