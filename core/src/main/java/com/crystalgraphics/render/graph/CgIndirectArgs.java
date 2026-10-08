package com.crystalgraphics.render.graph;

import com.crystalgraphics.api.shader.CgShaderBindings;
import com.crystalgraphics.compute.CgCompute;
import com.crystalgraphics.compute.CgKernel;
import com.crystalgraphics.compute.CgKernelForm;
import com.crystalgraphics.compute.CgDispatchBindings;
import com.crystalgraphics.compute.lower.CgLoweredKernel;
import com.crystalgraphics.compute.program.CgKernelProgram;
import com.crystalgraphics.gl.buffer.CgFrameRing;
import com.crystalgraphics.gl.buffer.CgStreamBuffer;
import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.render.draw.CgIndirect;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;

import java.util.Arrays;
import java.util.function.IntConsumer;

/**
 * An executor's indirect commands (gpu-compute C4): one per indirect draw of the raster pass about to run, written
 * before the pass begins by {@code crystalgraphics:shaders/env/compute/args.compute} from the count a kernel wrote and
 * the range the mesh store placed. Each command has a slot of its own, aligned for a storage binding, so each write
 * binds only its slot and no two writes overlap. Below compute the kernel runs lowered (tier G40). Render thread.
 *
 * <pre>{@code
 * int args = commands.reserve(indirects, freed);  // the buffer, big enough for the pass's commands
 * commands.write(slot, count, countOffset, countBytes, mode, factor, range, records, most, 0);
 * commands.flush();                                // as compute, write queues: the pass's commands in one dispatch
 * store.drawIndirect(mesh, pipeline, submesh, commands.buffer(), commands.offset(slot));
 *
 * // Consecutive slots, written from CgMeshStore.joinedRange with each batch's first instance, draw as one call
 * store.drawIndirectJoined(mesh, commands.buffer(), commands.offset(slot), n, commands.stride());
 * }</pre>
 */
final class CgIndirectArgs {

    /** Five {@code uint}s a command, for either form: arrays use four. */
    static final int COMMAND_BYTES = 20;

    private static final String SOURCE = "crystalgraphics:shaders/env/compute/args.compute";
    /** Words a command's row of {@code DrawArgsMany}'s PARAMS holds. */
    private static final int ROW = 11;
    private static final int DISPATCHES = CgTrace.name("graph.args-dispatches");

    private int buffer;
    private int capacity;
    private CgKernel kernel;
    private CgKernel many;
    private CgStreamBuffer params;
    /** Queued rows as float bits, for {@link CgStreamBuffer#uploadFloats}; {@link #rowsCount} the count buffer they read. */
    private float[] table = new float[64 * ROW];
    private int rows;
    private int rowsCount;
    private CgDispatchBindings lowered;
    /** Bytes from one command to the next: a command rounded up to the storage offset alignment. */
    private int stride;

    /** Where command {@code slot} starts in the buffer. */
    long offset(int slot) {
        return (long) slot * stride;
    }

    /** Bytes from one command to the next, once a buffer is reserved: a multi-draw's stride. */
    int stride() {
        return stride;
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
     * {@code factor}, read as {@code mode}, over {@code range} as {@code CgMeshStore.range} answered it. An
     * {@code INSTANCES} command draws at most {@code most} instances, -1 for any number. {@code firstInstance} is 0
     * but for a command a multi-draw draws, whose pipeline reads it in place of {@code cg_InstanceBase}.
     */
    void write(int slot, int count, long countOffset, long countBytes, CgIndirect mode, int factor, int[] range,
               int records, int most, int firstInstance) {
        CgKernel kernel = kernel();
        if (kernel.form().how() == CgKernelForm.How.COMPUTE) {
            // A row for DrawArgsMany: a dispatch per command was ~1,250 dispatches a frame in the blasts scene.
            if (rows > 0 && count != rowsCount) flush();
            rowsCount = count;
            int at = rows * ROW;
            if (at + ROW > table.length) table = Arrays.copyOf(table, table.length * 2);
            table[at] = Float.intBitsToFloat((int) (countOffset >>> 2));
            table[at + 1] = Float.intBitsToFloat(mode.ordinal());
            table[at + 2] = Float.intBitsToFloat(factor);
            table[at + 3] = Float.intBitsToFloat(range[0]);
            table[at + 4] = Float.intBitsToFloat(range[1]);
            table[at + 5] = Float.intBitsToFloat(range[2]);
            table[at + 6] = Float.intBitsToFloat(range[3]);
            table[at + 7] = Float.intBitsToFloat(records);
            table[at + 8] = Float.intBitsToFloat(most);
            table[at + 9] = Float.intBitsToFloat(firstInstance);
            table[at + 10] = Float.intBitsToFloat((int) (offset(slot) >>> 2));
            rows++;
            return;
        }
        CgLoweredKernel program = kernel.lowered();
        set(program.properties(), countOffset, mode, factor, range, records, most, firstInstance);
        if (lowered == null) lowered = new CgDispatchBindings(kernel.compute().source());
        lowered.buffer(kernel.compute().source().buffer("COUNT").index(), count, 0, countBytes)
                .buffer(kernel.compute().source().buffer("ARGS").index(), buffer, offset(slot), COMMAND_BYTES)
                .elements(5, 1, 1)
                .frame(CgFrameRing.frame());
        program.dispatch(lowered);
        CgTrace.add(CgChannels.GL, DISPATCHES, 1);
    }

    /** Writes the commands {@link #write} queued as compute, in one dispatch. Before the commands' barrier. */
    void flush() {
        if (rows == 0) return;
        if (params == null) params = CgStreamBuffer.createFrameLocal(CgGL.GL_SHADER_STORAGE_BUFFER, table.length * 4);
        int at = params.uploadFloats(table, rows * ROW);
        CgKernelProgram program = many();
        program.use();
        program.buffer("COUNT", rowsCount).buffer("ARGS", buffer)
                .buffer("PARAMS", params.getGlBuffer(), at, params.getCommittedBytes())
                .dispatch(rows * 5);
        rows = 0;
        CgTrace.add(CgChannels.GL, DISPATCHES, 1);
    }

    private CgKernelProgram many() {
        if (many == null) many = CgCompute.load(SOURCE).kernel("DrawArgsMany");
        return many.program();
    }

    /** Whether the commands are written by draws, below compute: what they bind, the pass after must not see. */
    boolean lowered() {
        return kernel().form().how() != CgKernelForm.How.COMPUTE;
    }

    private CgKernel kernel() {
        if (kernel == null) kernel = CgCompute.load(SOURCE).kernel("DrawArgs");
        return kernel;
    }

    private static void set(CgShaderBindings p, long countOffset, CgIndirect mode, int factor, int[] range, int records,
                            int most, int firstInstance) {
        p.set1i("_Count", (int) (countOffset >>> 2))
                .set1i("_Mode", mode.ordinal())
                .set1i("_Factor", factor)
                .set1i("_First", range[0])
                .set1i("_Elements", range[1])
                .set1i("_Base", range[2])
                .set1i("_Indexed", range[3])
                .set1i("_Instances", records)
                .set1i("_Most", most)
                .set1i("_FirstInstance", firstInstance);
    }

    /** At context teardown; the GL name is answered to {@code freed}. */
    void delete(IntConsumer freed) {
        rows = 0;
        if (params != null) {
            params.delete();
            params = null;
        }
        if (buffer == 0) return;
        freed.accept(buffer);
        CgGL.glDeleteBuffers(buffer);
        buffer = 0;
        capacity = 0;
        stride = 0;
    }
}
