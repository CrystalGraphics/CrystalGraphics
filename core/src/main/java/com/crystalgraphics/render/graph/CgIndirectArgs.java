package com.crystalgraphics.render.graph;

import com.crystalgraphics.compute.CgCompute;
import com.crystalgraphics.compute.CgKernel;
import com.crystalgraphics.compute.program.CgKernelProgram;
import com.crystalgraphics.gpu.resource.CgStreamBuffer;
import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.render.draw.CgIndirect;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;

import java.util.Arrays;
import java.util.function.IntConsumer;

/**
 * An executor's indirect commands: one per indirect draw of the raster pass about to run, written
 * before the pass begins by {@code crystalgraphics:shaders/env/compute/args.compute} from the count a kernel wrote and
 * the range the mesh store placed. Each command has a slot of its own, aligned for a storage binding, so each write
 * binds only its slot and no two writes overlap. Render thread.
 *
 * <pre>{@code
 * CgIndirectArgs commands = new CgIndirectArgs(this::dispatchCommands);   // stage() and dispatch program() there
 * int args = commands.reserve(indirects, freed);  // the buffer, big enough for the pass's commands
 * commands.write(slot, count, countOffset, mode, factor, range, records, most, first);
 * commands.flush();                                // write queues: the pass's commands in one dispatch
 * store.drawIndirectOn(pass, cache, mesh, submesh, args, commands.offset(slot));
 * }</pre>
 *
 * <p>A write naming another count buffer than the rows queued flushes them first, through the same dispatch.</p>
 */
final class CgIndirectArgs {

    /** Five {@code uint}s a command, for either form: arrays use four. */
    static final int COMMAND_BYTES = 20;

    private static final String SOURCE = "crystalgraphics:shaders/env/compute/args.compute";
    /** Words a command's row of {@code DrawArgsMany}'s PARAMS holds. */
    private static final int ROW = 11;
    private static final int DISPATCHES = CgTrace.name("graph.args-dispatches");

    /** Stages the queued rows and dispatches {@link #program} over them: the executor's, on the device. */
    private final Runnable dispatch;
    private int buffer;
    private int capacity;
    private CgKernel many;
    /** Queued rows as float bits, for {@link CgStreamBuffer#uploadFloats}; {@link #rowsCount} the count buffer they read. */
    private float[] table = new float[64 * ROW];
    /** Made with its executor, as the executor's own rings are: made at the first indirect draw, a map waits mid-frame. */
    private final CgStreamBuffer params = CgStreamBuffer.frameLocalStorage(table.length * 4);
    private int rows;
    private int rowsCount;
    /** The queued rows' first and last slots: the span their dispatch binds of the buffer. */
    private int firstSlot, lastSlot;
    /** Bytes from one command to the next: a command rounded up to the storage offset alignment. */
    private int stride;

    CgIndirectArgs(Runnable dispatch) {
        this.dispatch = dispatch;
    }

    /** Where command {@code slot} starts in the buffer. */
    long offset(int slot) {
        return (long) slot * stride;
    }

    /** Bytes from one command to the next, once a buffer is reserved: a multi-draw's stride. */
    int stride() {
        return stride;
    }

    /** The buffer the commands are in, as last reserved, by the name the graph knows it by. */
    int buffer() {
        return buffer;
    }

    /** The buffer, grown to hold {@code commands}; the name it had is answered to {@code freed} when it grows. */
    int reserve(int commands, IntConsumer freed) {
        if (stride == 0) {
            int align = Math.max(4, CgCapabilities.detect().storageOffsetAlignment());
            stride = (COMMAND_BYTES + align - 1) / align * align;
        }
        int bytes = commands * stride;
        if (bytes > capacity) {
            if (buffer != 0) {
                freed.accept(buffer);
                CgBufferPool.free(buffer);
            }
            capacity = Math.max(bytes, Math.max(capacity * 2, 16 * stride));
            buffer = CgBufferPool.create(capacity);
        }
        return buffer;
    }

    /**
     * Writes command {@code slot}: the {@code uint} at byte {@code countOffset} in buffer {@code count}, times
     * {@code factor}, read as {@code mode}, over {@code range} as {@code CgMeshStore.range} answered it. An
     * {@code INSTANCES} command draws at most {@code most} instances, -1 for any number. {@code firstInstance} is where
     * the batch's records start, {@code CgMeshStore.SHARED_RECORD} set where every instance reads that one.
     */
    void write(int slot, int count, long countOffset, CgIndirect mode, int factor, int[] range, int records, int most,
               int firstInstance) {
        // A row for DrawArgsMany: a dispatch per command was ~1,250 dispatches a frame in the blasts scene.
        if (rows > 0 && count != rowsCount) flush();
        rowsCount = count;
        if (rows == 0) firstSlot = lastSlot = slot;
        firstSlot = Math.min(firstSlot, slot);
        lastSlot = Math.max(lastSlot, slot);
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
        table[at + 10] = Float.intBitsToFloat(slot);
        rows++;
    }

    /** Writes the commands {@link #write} queued, in one dispatch. Before the commands' barrier. */
    void flush() {
        if (rows > 0) dispatch.run();
    }

    /** What {@link #stage} staged: the count buffer, the args' span, the params' span, the elements to dispatch. */
    int stagedCount, stagedElements;
    long stagedArgs, stagedArgsBytes, stagedParams, stagedParamsBytes;

    /**
     * Uploads the queued rows' parameters and notes what their dispatch binds, for the dispatch {@link #flush} runs;
     * false when nothing is queued.
     */
    boolean stage() {
        if (rows == 0) return false;
        // Each row's command in words from the span bound: two flushes binding the whole buffer overlap, which
        // synchronization validation reads as a write after write.
        long base = offset(firstSlot);
        for (int at = 10; at < rows * ROW; at += ROW) {
            int slot = Float.floatToRawIntBits(table[at]);
            table[at] = Float.intBitsToFloat((int) ((offset(slot) - base) >>> 2));
        }
        stagedParams = params.uploadFloats(table, rows * ROW);
        stagedParamsBytes = params.getCommittedBytes();
        stagedCount = rowsCount;
        stagedArgs = base;
        stagedArgsBytes = offset(lastSlot) - base + COMMAND_BYTES;
        stagedElements = rows * 5;
        rows = 0;
        CgTrace.add(CgChannels.GL, DISPATCHES, 1);
        return true;
    }

    /** The commands' buffer, by name, and the params ring: what a staged dispatch binds. */
    int argsBuffer() {
        return buffer;
    }

    CgStreamBuffer params() {
        return params;
    }

    /** The kernel writing the commands. */
    CgKernelProgram program() {
        return many();
    }

    private CgKernelProgram many() {
        if (many == null) many = CgCompute.load(SOURCE).kernel("DrawArgsMany");
        return many.program();
    }

    /** At the device's teardown; the buffer's name is answered to {@code freed}. */
    void delete(IntConsumer freed) {
        rows = 0;
        params.delete();   // its executor goes with it (CgExecutor.destroyAll)
        if (buffer == 0) return;
        freed.accept(buffer);
        CgBufferPool.free(buffer);
        buffer = 0;
        capacity = 0;
        stride = 0;
    }
}
