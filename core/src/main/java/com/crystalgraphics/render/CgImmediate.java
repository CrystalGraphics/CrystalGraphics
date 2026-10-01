package com.crystalgraphics.render;

import com.crystalgraphics.api.render.CgRenderPipeline;
import com.crystalgraphics.api.state.CgRenderState;
import com.crystalgraphics.gl.framebuffer.CgFrameBuffer;
import com.crystalgraphics.platform.gl.CgGlStateManager;
import com.crystalgraphics.platform.gl.state.CgGlState;
import com.crystalgraphics.render.draw.CgBindingTable;
import com.crystalgraphics.render.draw.CgChunkBuilder;
import com.crystalgraphics.render.draw.CgDrawChunk;
import com.crystalgraphics.render.draw.CgOrder;
import com.crystalgraphics.render.draw.CgPassConstants;
import com.crystalgraphics.render.graph.CgExecutor;
import com.crystalgraphics.render.graph.CgFrame;
import com.crystalgraphics.render.graph.CgFrameBuilder;
import com.crystalgraphics.render.graph.CgFrameGraph;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgLoad;
import com.crystalgraphics.render.graph.CgRasterPass;
import com.crystalgraphics.render.graph.CgRecording;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
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
 * CgImmediate.flush(chunk, CgOrder.SORTED);   // under the frame block the caller prepared; state left as drawn
 * }</pre>
 *
 * <p>A caller drawing many runs into one target of its own holds the flushes back and executes them together:</p>
 *
 * <pre>{@code
 * CgImmediate.deferInto(frameTarget);   // flushes while frameTarget is bound wait, scissor and frame block kept
 * ...                                   // draws, scissor changes, material changes: no execution
 * CgImmediate.drain();                  // before anything reads frameTarget, or binds another target over it
 * CgImmediate.stopDeferring();          // drains, and flushes execute at once again
 * }</pre>
 *
 * <ul>
 *   <li>Render thread only, inside a frame. Nested ones close in reverse order, as try-with-resources does.</li>
 *   <li>A null render state leaves the pipelines' unset slots as the framebuffer has them.</li>
 *   <li>Deferring holds back only a flush made while the deferred target is bound and the state shadow knows the
 *       scissor: a flush into any other target executes at once, and one the shadow cannot place drains first.
 *       Raw GL drawn into the deferred target is not seen, so drain before it.</li>
 * </ul>
 */
public final class CgImmediate implements AutoCloseable {

    private static final List<CgImmediate> BY_DEPTH = new ArrayList<>();
    private static final CgFrameBuilder BUILDER = new CgFrameBuilder();
    private static final CgFrameGraph GRAPH = new CgFrameGraph();
    private static final float[] FRAME_BLOCK = new float[CgPassConstants.FLOATS];
    private static final CgPassConstants FRAME_CONSTANTS = new CgPassConstants();
    private static int depth;

    private final CgRecording recording = new CgRecording();
    private CgRasterPass pass;

    private CgImmediate() {
    }

    // ── Deferral ──────────────────────────────────────────────────────────────

    private static final CgRecording DEFERRED = new CgRecording();
    private static final float[] DEFERRED_BLOCK = new float[CgPassConstants.FLOATS];
    private static final int[] SCISSOR = new int[4];
    @Nullable
    private static CgFrameBuffer deferTarget;
    @Nullable
    private static CgGraphTexture deferTexture;
    @Nullable
    private static CgRasterPass deferPass;

    /**
     * Holds back every later {@link #flush} made while {@code target} is bound, until {@link #drain}. Drains what an
     * earlier target held.
     */
    public static void deferInto(CgFrameBuffer target) {
        if (target == deferTarget) return;
        drain();
        deferTarget = target;
        deferTexture = CgGraphTexture.imported("deferred", target);
    }

    /** Executes what is held, into its target, restoring GL state after. Nothing held is a no-op. */
    public static void drain() {
        CgRasterPass pass = deferPass;
        if (pass == null) return;
        deferPass = null;
        try {
            pass.end();
            GRAPH.add(DEFERRED.seal());
            CgFrame frame = BUILDER.build(GRAPH);
            GRAPH.clear();
            CgExecutor.execute(frame, true);
            BUILDER.recycle(frame);
        } finally {
            GRAPH.clear();
            DEFERRED.reset();
        }
    }

    /** Drains, and flushes execute at once again. */
    public static void stopDeferring() {
        drain();
        deferTarget = null;
        deferTexture = null;
    }

    /** Drops what is held without drawing it, and stops deferring: for a frame that failed part-way. */
    public static void abandonDeferred() {
        deferPass = null;
        deferTarget = null;
        deferTexture = null;
        GRAPH.clear();
        DEFERRED.reset();
    }

    /** Whether a draw into whatever is bound now would land on what the deferral holds, or might. */
    private static boolean mayReachDeferred() {
        if (deferTarget == null) return false;
        int bound = CgGlState.manager().knownDrawFramebuffer();
        return bound < 0 || bound == deferTarget.getId();
    }

    /** Holds {@code chunk} for the deferred target, or answers false when the shadow cannot say how to draw it. */
    private static boolean defer(CgDrawChunk chunk, CgOrder order) {
        CgGlStateManager state = CgGlState.manager();
        if (order != CgOrder.SORTED || state.knownDrawFramebuffer() != deferTarget.getId()) return false;
        int scissor = state.knownScissor(SCISSOR);
        if (scissor < 0) return false;
        if (CgRenderPipeline.copyFrameBlock(FRAME_BLOCK)) FRAME_CONSTANTS.read(FRAME_BLOCK, 0);
        FRAME_CONSTANTS.write(FRAME_BLOCK, 0);
        // A frame block that moved (a projection for another target size) starts a pass of its own: one pass is one
        // block. Passes on one target run in the order they were recorded.
        if (deferPass != null && !Arrays.equals(DEFERRED_BLOCK, FRAME_BLOCK)) {
            deferPass.end();
            deferPass = null;
        }
        if (deferPass == null) {
            System.arraycopy(FRAME_BLOCK, 0, DEFERRED_BLOCK, 0, CgPassConstants.FLOATS);
            deferPass = DEFERRED.raster(deferTexture, CgLoad.load(), FRAME_CONSTANTS, null, CgOrder.SORTED);
        }
        if (scissor == 1) deferPass.scissor(SCISSOR[0], SCISSOR[1], SCISSOR[2], SCISSOR[3]);
        else deferPass.noScissor();
        deferPass.add(chunk);
        return true;
    }

    /** Starts one, in painter's order, leaving render state as it is. */
    public static CgImmediate begin(CgPassConstants constants) {
        return begin(constants, null, CgOrder.LOOKBACK);
    }

    /** Starts one with the pass's render state and order. */
    public static CgImmediate begin(CgPassConstants constants, @Nullable CgRenderState state, CgOrder order) {
        if (mayReachDeferred()) drain();
        CgImmediate immediate = acquire(constants, state, order);
        immediate.recording.chunks().begin();
        return immediate;
    }

    /**
     * Draws {@code chunk} now, under the frame block its caller last prepared ({@code CgRenderPipeline.prepareFrame}),
     * and leaves GL state as the draw set it: what an immediate renderer's flush always did. {@link CgOrder#SORTED}
     * with equal keys keeps submission order exactly.
     */
    public static void flush(CgDrawChunk chunk, CgOrder order) {
        if (chunk.draws() == 0) return;
        if (deferTarget != null) {
            if (defer(chunk, order)) return;
            if (mayReachDeferred()) drain();
        }
        if (CgRenderPipeline.copyFrameBlock(FRAME_BLOCK)) FRAME_CONSTANTS.read(FRAME_BLOCK, 0);
        CgImmediate immediate = acquire(FRAME_CONSTANTS, null, order);
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
