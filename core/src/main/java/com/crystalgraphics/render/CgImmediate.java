package com.crystalgraphics.render;

import com.crystalgraphics.api.state.CgRenderState;
import com.crystalgraphics.compute.CgKernel;
import com.crystalgraphics.render.draw.CgBindingTable;
import com.crystalgraphics.render.draw.CgChunkBuilder;
import com.crystalgraphics.render.draw.CgDrawChunk;
import com.crystalgraphics.render.draw.CgOrder;
import com.crystalgraphics.render.draw.CgPassConstants;
import com.crystalgraphics.render.graph.CgComputePass;
import com.crystalgraphics.render.graph.CgDispatch;
import com.crystalgraphics.render.graph.CgExecutor;
import com.crystalgraphics.render.graph.CgFrame;
import com.crystalgraphics.render.graph.CgFrameBuilder;
import com.crystalgraphics.render.graph.CgFrameGraph;
import com.crystalgraphics.render.graph.CgGraphBuffer;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgLoad;
import com.crystalgraphics.render.graph.CgRasterPass;
import com.crystalgraphics.render.graph.CgRecording;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * "Draw this now", through the same recording, batching and executor as everything else: chunks into whatever
 * framebuffer and viewport are bound, built and executed at once. For a caller with no frame graph to record into —
 * a harness scene, a draw inside a callback pass, a renderer that still flushes.
 *
 * <pre>{@code
 * try (CgImmediate draw = CgImmediate.begin(constants)) {   // GL state restored on close
 *     CgChunkBuilder c = draw.chunks();
 *     c.draw(material.pipeline(CgInstanceKind.QUAD), material.captureBindings(draw.bindings()));
 *     int at = c.instance();
 *     // ...the quad record
 * }
 *
 * CgImmediate.constants().projection.setOrtho(0, w, h, 0, -1, 1);   // what flushed draws are drawn under
 * CgImmediate.flush(chunk, CgOrder.SORTED);                          // state left as drawn
 * }</pre>
 *
 * <p>A caller recording a frame of its own hands its renderers a {@code CgPassRecorder} as their sink, and executes the
 * recording with {@link #execute(CgRecording)} when it is done.</p>
 *
 * <p>Kernels, the same way, on buffers the caller owns:</p>
 * <pre>{@code
 * CgGraphBuffer cells = CgGraphBuffer.imported("cells", glBuffer, bytes);
 * try (CgImmediate.Compute run = CgImmediate.compute("bake")) {
 *     run.dispatch(bake, count).bind("CELLS", cells);
 * }                                  // executed: barriers derived as in a graph, GL state restored
 * CgGL.cgBufferBarrier(glBuffer, CgAccess.COMPUTE_WRITE, CgAccess.VERTEX_READ);   // a reader outside the graph
 * }</pre>
 *
 * <ul>
 *   <li>Render thread only, inside a frame. Nested ones close in reverse order, as try-with-resources does.</li>
 *   <li>A null render state leaves the pipelines' unset slots as the framebuffer has them.</li>
 * </ul>
 */
public final class CgImmediate implements AutoCloseable {

    private static final List<CgImmediate> BY_DEPTH = new ArrayList<>();
    private static final CgFrameBuilder BUILDER = new CgFrameBuilder();
    private static final CgFrameGraph GRAPH = new CgFrameGraph();
    private static final CgPassConstants CONSTANTS = new CgPassConstants();
    private static int depth;

    private final CgRecording recording = new CgRecording();
    private CgRasterPass pass;

    private CgImmediate() {
    }

    /** Builds and executes {@code recording} now, sealing it, and restores GL state after. Render thread, inside a frame. */
    public static void execute(CgRecording recording) {
        try {
            GRAPH.add(recording.seal());
            CgFrame frame = BUILDER.build(GRAPH);
            GRAPH.clear();
            CgExecutor.execute(frame, true);
            BUILDER.recycle(frame);
        } finally {
            GRAPH.clear();
        }
    }

    /** Starts one, in painter's order, leaving render state as it is. */
    public static CgImmediate begin(CgPassConstants constants) {
        return begin(constants, null, CgOrder.LOOKBACK);
    }

    /** Starts one with the pass's render state and order. */
    public static CgImmediate begin(CgPassConstants constants, @Nullable CgRenderState state, CgOrder order) {
        CgImmediate immediate = acquire(constants, state, order);
        immediate.recording.chunks().begin();
        return immediate;
    }

    /**
     * The pass constants {@link #flush} draws under: a renderer flushing into the bound framebuffer has no pass of its
     * own to carry them, so its caller sets them here. Render thread; they stay as set.
     */
    public static CgPassConstants constants() {
        return CONSTANTS;
    }

    /**
     * Draws {@code chunk} now, under {@link #constants()}, and leaves GL state as the draw set it: what an immediate
     * renderer's flush always did. {@link CgOrder#SORTED} with equal keys keeps submission order exactly.
     */
    public static void flush(CgDrawChunk chunk, CgOrder order) {
        if (chunk.draws() == 0) return;
        CgImmediate immediate = acquire(CONSTANTS, null, order);
        try {
            immediate.pass.add(chunk);
            immediate.execute(false);
        } finally {
            depth--;
        }
    }

    /** The chunk being written, already begun under the root nodes. */
    public CgChunkBuilder chunks() {
        return recording.chunks();
    }

    /** The table binding ids come from. */
    public CgBindingTable bindings() {
        return recording.bindings();
    }

    /** Builds and executes what was recorded, and restores GL state. */
    @Override
    public void close() {
        try {
            pass.add(recording.chunks().end());
            execute(true);
        } finally {
            depth--;
        }
    }

    private static CgImmediate acquire(CgPassConstants constants, @Nullable CgRenderState state, CgOrder order) {
        if (BY_DEPTH.size() == depth) BY_DEPTH.add(new CgImmediate());
        CgImmediate immediate = BY_DEPTH.get(depth++);
        immediate.recording.reset();
        immediate.pass = immediate.recording.raster(CgGraphTexture.current(), CgLoad.load(), constants, state, order);
        return immediate;
    }

    /**
     * A compute pass executed when closed, as a graph of its own. Render thread, inside a frame. What it writes must
     * outlive it — an imported, persistent or history buffer — or it is culled, as in any graph.
     */
    public static Compute compute(String name) {
        return new Compute(name);
    }

    /** A compute pass run now. @see #compute(String) */
    public static final class Compute implements AutoCloseable {
        private final CgRecording recording = new CgRecording();
        private final CgComputePass pass;

        private Compute(String name) {
            pass = recording.compute(name);
        }

        public CgDispatch dispatch(CgKernel kernel, int count) {
            return pass.dispatch(kernel, count);
        }

        public CgDispatch dispatch(CgKernel kernel, int x, int y, int z) {
            return pass.dispatch(kernel, x, y, z);
        }

        public CgDispatch dispatchGroups(CgKernel kernel, int x, int y, int z) {
            return pass.dispatchGroups(kernel, x, y, z);
        }

        public CgDispatch dispatchIndirect(CgKernel kernel, CgGraphBuffer args, long offset) {
            return pass.dispatchIndirect(kernel, args, offset);
        }

        /** Its recording: a fill or an update before the dispatches, ordered like any write. */
        public CgRecording recording() {
            return recording;
        }

        @Override
        public void close() {
            pass.end();
            execute(recording);
        }
    }

    private void execute(boolean restoreState) {
        try {
            pass.end();
            GRAPH.add(recording.seal());
            CgFrame frame = BUILDER.build(GRAPH);
            GRAPH.clear();
            CgExecutor.execute(frame, restoreState);
            BUILDER.recycle(frame);
        } finally {
            GRAPH.clear();
        }
    }
}
