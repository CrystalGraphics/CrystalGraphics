package com.crystalgraphics.render;

import com.crystalgraphics.api.state.CgRenderState;
import com.crystalgraphics.platform.rhi.resource.CgTextureView;
import com.crystalgraphics.platform.rhi.resource.CgGpuSampler;
import com.crystalgraphics.platform.rhi.command.CgPassDesc;
import com.crystalgraphics.platform.rhi.command.CgCommandList;
import com.crystalgraphics.platform.rhi.command.CgAccess;
import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.platform.rhi.CgExternalTarget;
import com.crystalgraphics.platform.rhi.CgDeviceSection;
import com.crystalgraphics.lifecycle.CgGraphicsLifecycle;
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
 * try (CgImmediate draw = CgImmediate.begin(constants)) {   // executed on close
 *     CgChunkBuilder c = draw.chunks();
 *     c.draw(material.pipeline(CgInstanceKind.QUAD), material.captureBindings(draw.bindings()));
 *     int at = c.instance();
 *     // ...the quad record
 * }
 *
 * CgImmediate.constants().projection.setOrtho(0, w, h, 0, -1, 1);   // what flushed draws are drawn under
 * CgImmediate.flush(chunk, CgOrder.SORTED);                          // built and executed
 * }</pre>
 *
 * <p>A caller recording a frame of its own draws into a {@code CgCanvas} recording into it, and executes the
 * recording with {@link #execute(CgRecording)} when it is done.</p>
 *
 * <p>Kernels, the same way, on buffers the caller owns:</p>
 * <pre>{@code
 * CgGraphBuffer cells = CgGraphBuffer.imported("cells", glBuffer, bytes);
 * try (CgImmediate.Compute run = CgImmediate.compute("bake")) {
 *     run.dispatch(bake, count).bind("CELLS", cells);
 * }                                  // executed: barriers derived as in a graph
 * list.bufferBarrier(buffer, CgAccess.COMPUTE_WRITE, CgAccess.VERTEX_READ);   // a reader outside the graph
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

    /** Builds and executes {@code recording} now, sealing it. Render thread, inside a frame. */
    public static void execute(CgRecording recording) {
        try {
            GRAPH.add(recording.seal());
            CgFrame frame = BUILDER.build(GRAPH);
            GRAPH.clear();
            CgExecutor.execute(frame);
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
            immediate.execute();
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

    /** Builds and executes what was recorded. */
    @Override
    public void close() {
        try {
            pass.add(recording.chunks().endInPlace());
            execute();
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

    private void execute() {
        try {
            pass.end();
            GRAPH.add(recording.seal());
            CgFrame frame = BUILDER.build(GRAPH);
            GRAPH.clear();
            CgExecutor.execute(frame);
            BUILDER.recycle(frame);
        } finally {
            GRAPH.clear();
        }
    }

    // ── A target's clears and copies, outside a graph ──────────────────────────

    /**
     * Clears {@code target} now, outside any graph: its colour to {@code (r, g, b, a)} where {@code color}, its depth to
     * {@code depth} (GL's convention, far at 1, mirrored for a host whose depth runs far to near) where {@code clearDepth},
     * its stencil to {@code stencil} where {@code clearStencil}. Render thread, outside a recording. In a graph, a pass's
     * {@code CgLoad.clear} is the clear.
     *
     * <pre>{@code
     * CgImmediate.clear(view, true, 0.5f, 0.7f, 1f, 1f, true, 1.0, true, 0);   // sky, far, no stencil
     * CgImmediate.clear(view, false, 0, 0, 0, 0, true, 1.0, false, 0);          // depth alone, for the overlays
     * }</pre>
     */
    public static void clear(CgExternalTarget target, boolean color, float r, float g, float b, float a,
                             boolean clearDepth, double depth, boolean clearStencil, int stencil) {
        CgTextureView colorView = color ? target.color() : null;
        CgTextureView depthView = clearDepth || clearStencil ? target.depth() : null;
        if (colorView == null && depthView == null) return;
        float far = (float) (CgGraphicsLifecycle.hostDepthReversed() ? 1.0 - depth : depth);
        List<CgPassDesc.Color> colors = colorView == null ? List.of() : List.of(new CgPassDesc.Color(colorView,
                CgPassDesc.LoadOp.CLEAR, r, g, b, a, CgPassDesc.StoreOp.STORE));
        CgPassDesc.Depth depthDesc = depthView == null ? null : new CgPassDesc.Depth(depthView,
                clearDepth ? CgPassDesc.LoadOp.CLEAR : CgPassDesc.LoadOp.LOAD, far,
                clearStencil ? CgPassDesc.LoadOp.CLEAR : CgPassDesc.LoadOp.LOAD, stencil, CgPassDesc.StoreOp.STORE, false);
        CgDeviceSection.begin();
        try {
            // From rest and back, as every use outside a graph leaves a texture (CgAccess.RESTING)
            CgCommandList list = CgRHI.get().encoder();
            if (colorView != null) list.imageBarrier(colorView.texture(), CgAccess.RESTING, CgAccess.COLOR_WRITE);
            if (depthView != null) list.imageBarrier(depthView.texture(), CgAccess.RESTING, CgAccess.DEPTH_WRITE);
            list.beginPass(new CgPassDesc("clear " + target.label(), colors, depthDesc, target.width(), target.height())).end();
            if (colorView != null) list.imageBarrier(colorView.texture(), CgAccess.COLOR_WRITE, CgAccess.RESTING);
            if (depthView != null) list.imageBarrier(depthView.texture(), CgAccess.DEPTH_WRITE, CgAccess.RESTING);
        } finally {
            CgDeviceSection.end();
        }
    }

    /**
     * Copies {@code from}'s colour into {@code to}'s at the same size, unfiltered: a frame drawn into a target of yours
     * presented to the window ({@code CgExternalTarget.host(0, w, h)}). Render thread, outside a recording.
     */
    public static void copyColor(CgExternalTarget from, CgExternalTarget to) {
        copy(from, to, false);
    }

    /**
     * {@link #copyColor}, with the depth too where {@code depth}: both targets' depth formats must match.
     *
     * @throws IllegalArgumentException where either lacks what is copied
     */
    public static void copy(CgExternalTarget from, CgExternalTarget to, boolean depth) {
        if (from.color() == null || to.color() == null || depth && (from.depth() == null || to.depth() == null)) {
            throw new IllegalArgumentException("a copy from " + from + " into " + to + ": both need what is copied");
        }
        CgDeviceSection.begin();
        try {
            CgRHI.get().encoder().blit(from.color(), 0, 0, from.width(), from.height(), to.color(), 0, 0, from.width(),
                    from.height(), CgGpuSampler.Filter.NEAREST);
            if (depth) {
                CgRHI.get().encoder().blit(from.depth(), 0, 0, from.width(), from.height(), to.depth(), 0, 0, from.width(),
                        from.height(), CgGpuSampler.Filter.NEAREST);
            }
        } finally {
            CgDeviceSection.end();
        }
    }
}
