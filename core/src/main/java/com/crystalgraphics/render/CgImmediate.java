package com.crystalgraphics.render;

import com.crystalgraphics.api.render.CgRenderPipeline;
import com.crystalgraphics.api.state.CgRenderState;
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
 * <p>A caller with a recording of its own routes the flushes into it instead, and executes it when it is done:</p>
 *
 * <pre>{@code
 * CgImmediate.recordInto(frame, frameTarget, CgLoad.clear(0, 0, 0, 0));   // flushes become chunks in frame's passes
 * ...                                                                     // renderers draw as ever
 * CgImmediate.recordInto(frame, layer, CgLoad.clear(0, 0, 0, 0));         // another target; the open pass ends
 * CgImmediate.recordInto(frame, frameTarget, CgLoad.load());              // back, in a pass after the layer's
 * CgImmediate.stopRecording();
 * CgImmediate.execute(frame);
 * }</pre>
 *
 * <ul>
 *   <li>Render thread only, inside a frame. Nested ones close in reverse order, as try-with-resources does.</li>
 *   <li>A null render state leaves the pipelines' unset slots as the framebuffer has them.</li>
 *   <li>A routed chunk takes the scissor GL holds at its flush and the frame block the caller prepared; whatever is
 *       bound is not consulted. {@link #begin} while routing throws: a recorder records.</li>
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

    // ── Routing into a recording ─────────────────────────────────────────────

    private static final float[] ROUTE_BLOCK = new float[CgPassConstants.FLOATS];
    private static final int[] SCISSOR = new int[4];
    @Nullable
    private static CgRecording route;
    @Nullable
    private static CgGraphTexture routeTarget;
    private static CgLoad routeLoad = CgLoad.load();
    @Nullable
    private static CgRasterPass routePass;

    /**
     * Routes every later {@link #flush} into raster passes on {@code target} in {@code recording}, instead of executing
     * it, and ends the pass that was open. The first pass takes {@code load} and later ones load what it drew; a
     * clearing load opens its pass at once, so a target nothing draws into is still cleared.
     */
    public static void recordInto(CgRecording recording, CgGraphTexture target, CgLoad load) {
        endPass();
        route = recording;
        routeTarget = target;
        routeLoad = load;
        if (load.mask() != 0) {
            frameBlock();
            openPass();
        }
    }

    /** Ends the open pass: the next flush opens another on the same target, loading what this one drew. */
    public static void endPass() {
        if (routePass == null) return;
        routePass.end();
        routePass = null;
    }

    /** Ends the open pass, and flushes execute at once again. */
    public static void stopRecording() {
        endPass();
        route = null;
        routeTarget = null;
    }

    /** Stops routing without ending the open pass: for a recording its owner is discarding. */
    public static void abandonRecording() {
        routePass = null;
        route = null;
        routeTarget = null;
    }

    /** Builds and executes a sealed or sealable {@code recording} now, restoring GL state after. */
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

    /** The frame block the caller last prepared, into {@link #FRAME_BLOCK} and {@link #FRAME_CONSTANTS}. */
    private static void frameBlock() {
        if (CgRenderPipeline.copyFrameBlock(FRAME_BLOCK)) FRAME_CONSTANTS.read(FRAME_BLOCK, 0);
        FRAME_CONSTANTS.write(FRAME_BLOCK, 0);
    }

    private static void openPass() {
        System.arraycopy(FRAME_BLOCK, 0, ROUTE_BLOCK, 0, CgPassConstants.FLOATS);
        routePass = route.raster(routeTarget, routeLoad, FRAME_CONSTANTS, null, CgOrder.SORTED);
        routeLoad = CgLoad.load();
    }

    /** Adds {@code chunk} to the routed target's pass, under the scissor GL holds now and the frame block. */
    private static void routeChunk(CgDrawChunk chunk) {
        frameBlock();
        // One pass is one frame block: a projection that moved (another target size) starts a pass of its own.
        if (routePass != null && !Arrays.equals(ROUTE_BLOCK, FRAME_BLOCK)) endPass();
        if (routePass == null) openPass();
        if (CgGlState.manager().knownScissor(SCISSOR) == 1) {
            routePass.scissor(SCISSOR[0], SCISSOR[1], SCISSOR[2], SCISSOR[3]);
        } else {
            routePass.noScissor();
        }
        routePass.add(chunk);
    }

    /** Starts one, in painter's order, leaving render state as it is. */
    public static CgImmediate begin(CgPassConstants constants) {
        return begin(constants, null, CgOrder.LOOKBACK);
    }

    /** Starts one with the pass's render state and order. */
    public static CgImmediate begin(CgPassConstants constants, @Nullable CgRenderState state, CgOrder order) {
        if (route != null) throw new IllegalStateException("an immediate draw while flushes are routed into a recording");
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
        if (route != null) {
            routeChunk(chunk);
            return;
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
