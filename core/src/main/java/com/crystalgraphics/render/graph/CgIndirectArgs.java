package com.crystalgraphics.render.graph;

import com.crystalgraphics.api.shader.CgShaderBindings;
import com.crystalgraphics.compute.CgCompute;
import com.crystalgraphics.compute.CgKernel;
import com.crystalgraphics.compute.CgKernelForm;
import com.crystalgraphics.compute.CgDispatchBindings;
import com.crystalgraphics.compute.lower.CgLoweredKernel;
import com.crystalgraphics.compute.program.CgKernelProgram;
import com.crystalgraphics.gl.buffer.CgFrameRing;
import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.render.draw.CgIndirect;

import java.util.function.IntConsumer;

/**
 * An executor's indirect commands (gpu-compute C4): one per indirect draw of the raster pass about to run, written
 * before the pass begins by {@code crystalgraphics:shaders/env/compute/args.compute} from the count a kernel wrote and
 * the range the mesh store placed. Each command has a slot of its own, aligned for a storage binding, so each write
 * binds only its slot and no two writes overlap. Below compute the kernel runs lowered (tier G40). Render thread.
 *
 * <pre>{@code
 * int args = commands.reserve(indirects, freed);  // the buffer, big enough for the pass's commands
 * commands.write(slot, count, countOffset, mode, factor, range, records);
 * store.drawIndirect(mesh, pipeline, submesh, commands.buffer(), commands.offset(slot));
 * }</pre>
 */
final class CgIndirectArgs {

    /** Five {@code uint}s a command, for either form: arrays use four. */
    static final int COMMAND_BYTES = 20;

    private static final String SOURCE = "crystalgraphics:shaders/env/compute/args.compute";

    private int buffer;
    private int capacity;
    private CgDispatchBindings lowered;
    /** Bytes from one command to the next: a command rounded up to the storage offset alignment. */
    private int stride;

    /** Where command {@code slot} starts in the buffer. */
    long offset(int slot) {
        return (long) slot * stride;
    }

    /** The GL buffer the commands are in, as last reserved. */
    int buffer() {
        return buffer;
    }

    /** The buffer, grown to hold {@code commands}; the GL name it had is answered to {@code freed} when it grows. */
    int reserve(int commands, IntConsumer freed) {
        if (stride == 0) {
            int align = Math.max(4, CgCapabilities.detect().storageOffsetAlignment());
            stride = (COMMAND_BYTES + align - 1) / align * align;
        }
        int bytes = commands * stride;
        if (bytes > capacity) {
            if (buffer != 0) {
                freed.accept(buffer);
                CgGL.glDeleteBuffers(buffer);
            }
            capacity = Math.max(bytes, Math.max(capacity * 2, 16 * stride));
            buffer = CgBufferPool.create(capacity);
        }
        return buffer;
    }

    /**
     * Writes command {@code slot}: the {@code uint} at byte {@code countOffset} in GL buffer {@code count}, times
     * {@code factor}, read as {@code mode}, over {@code range} as {@code CgMeshStore.range} answered it.
     */
    void write(int slot, int count, long countOffset, long countBytes, CgIndirect mode, int factor, int[] range,
               int records) {
        CgKernel kernel = kernel();
        if (kernel.form().how() == CgKernelForm.How.COMPUTE) {
            CgKernelProgram program = kernel.program();
            program.use();
            set(program.properties(), countOffset, mode, factor, range, records);
            program.buffer("COUNT", count).buffer("ARGS", buffer, offset(slot), COMMAND_BYTES).dispatch(5);
            return;
        }
        CgLoweredKernel program = kernel.lowered();
        set(program.properties(), countOffset, mode, factor, range, records);
        if (lowered == null) lowered = new CgDispatchBindings(kernel.compute().source());
        lowered.buffer(kernel.compute().source().buffer("COUNT").index(), count, 0, countBytes)
                .buffer(kernel.compute().source().buffer("ARGS").index(), buffer, offset(slot), COMMAND_BYTES)
                .elements(5, 1, 1)
                .frame(CgFrameRing.frame());
        program.dispatch(lowered);
    }

    /** Whether the commands are written by draws, below compute: what they bind, the pass after must not see. */
    boolean lowered() {
        return kernel().form().how() != CgKernelForm.How.COMPUTE;
    }

    private static CgKernel kernel() {
        return CgCompute.load(SOURCE).kernel("DrawArgs");
    }

    private static void set(CgShaderBindings p, long countOffset, CgIndirect mode, int factor, int[] range, int records) {
        p.set1i("_Count", (int) (countOffset >>> 2))
                .set1i("_Mode", mode.ordinal())
                .set1i("_Factor", factor)
                .set1i("_First", range[0])
                .set1i("_Elements", range[1])
                .set1i("_Base", range[2])
                .set1i("_Indexed", range[3])
                .set1i("_Instances", records);
    }

    /** At context teardown; the GL name is answered to {@code freed}. */
    void delete(IntConsumer freed) {
        if (buffer == 0) return;
        freed.accept(buffer);
        CgGL.glDeleteBuffers(buffer);
        buffer = 0;
        capacity = 0;
        stride = 0;
    }
}
