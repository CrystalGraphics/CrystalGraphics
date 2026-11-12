package com.crystalgraphics.render;

import com.crystalgraphics.lifecycle.CgGraphicsLifecycle;
import com.crystalgraphics.platform.rhi.CgDeviceSection;
import com.crystalgraphics.platform.rhi.CgExternalTarget;
import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.platform.rhi.command.CgAccess;
import com.crystalgraphics.platform.rhi.command.CgCommandList;
import com.crystalgraphics.platform.rhi.command.CgPassDesc;
import com.crystalgraphics.platform.rhi.resource.CgGpuSampler;
import com.crystalgraphics.platform.rhi.resource.CgTextureView;
import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.render.draw.CgDrawChunk;
import com.crystalgraphics.render.draw.CgOrder;
import com.crystalgraphics.render.graph.CgDepthAccess;
import com.crystalgraphics.render.graph.CgExecutor;
import com.crystalgraphics.render.graph.CgFrame;
import com.crystalgraphics.render.graph.CgFrameBuilder;
import com.crystalgraphics.render.graph.CgFrameGraph;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgLoad;
import com.crystalgraphics.render.graph.CgRecording;

import java.util.ArrayList;
import java.util.List;

/**
 * Drawing outside the world: a recording executed now, rather than in a view or a stage. A preview, a thumbnail, a
 * bake, a test.
 *
 * <pre>{@code
 * CgRecording rec = new CgRecording();
 * CgGraphTexture picture = CgGraphTexture.requested("preview", CgTextureDesc.of(256, 256, CgFormat.RGBA8));
 * CgRasterPass pass = rec.raster("preview")
 *         .color(picture, CgLoad.clear(0, 0, 0, 0))
 *         .depth(rec.scratch("preview.depth", ...), CgLoad.clearDepth(1f), CgDepthAccess.WRITE)
 *         .camera(CgCamera.perspective(60f, 1f, 0.05f, 100f).lookAt(eye, target));
 * pass.draw(mesh, material).transform(model);
 * pass.end();
 * CgRenderer.executeNow(rec);                              // render thread, inside a frame
 *
 * CgRecording bake = new CgRecording();                   // kernels, the same way
 * CgComputePass run = bake.compute("bake");
 * run.dispatch(kernel, count).bind("CELLS", cells);
 * run.end();
 * CgRenderer.executeNow(bake);
 * }</pre>
 *
 * <ul>
 *   <li>Render thread only, inside a frame. It may run inside an executing callback: a nested execution of its own.</li>
 *   <li>What it writes must outlive it -- a requested, imported, persistent or history resource, or the current target
 *       -- or the graph culls the pass, as in any graph.</li>
 *   <li>{@link #clear} and {@link #copy} work on an external target outside any graph: a window's or a target of
 *       yours, between frames of a scene.</li>
 * </ul>
 */
public final class CgRenderer {

    private static final CgFrameBuilder BUILDER = new CgFrameBuilder();
    private static final CgFrameGraph GRAPH = new CgFrameGraph();
    /** The recordings {@link #drawNow} draws through, one per nesting depth, reused. */
    private static final List<CgRecording> NOW = new ArrayList<>();
    private static int nowDepth;

    private CgRenderer() {
    }

    /** Builds and executes {@code recording} now, sealing it. Render thread, inside a frame. */
    public static void executeNow(CgRecording recording) {
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

    /**
     * Draws {@code chunk} into the current target now, seen through {@code camera}, beside the target's depth: a
     * renderer of the engine's that flushes as it goes (a canvas drawing now). {@link CgOrder#SORTED} with equal keys
     * keeps submission order exactly. Render thread, inside a frame and a current target.
     */
    public static void drawNow(CgDrawChunk chunk, CgCamera camera, CgOrder order) {
        if (chunk.draws() == 0) return;
        if (NOW.size() == nowDepth) NOW.add(new CgRecording());
        CgRecording recording = NOW.get(nowDepth++);
        try {
            recording.reset();
            recording.raster("immediate").color(CgGraphTexture.current(), CgLoad.load())
                    .depth(CgGraphTexture.current(), CgLoad.load(), CgDepthAccess.WRITE).camera(camera).order(order)
                    .add(chunk).end();
            executeNow(recording);
        } finally {
            nowDepth--;
        }
    }

    // ── A target's clears and copies, outside a graph ────────────────────

    /**
     * Clears {@code target} now, outside any graph: its colour to {@code (r, g, b, a)} where {@code color}, its depth to
     * {@code depth} (GL's convention, far at 1, mirrored for a host whose depth runs far to near) where {@code clearDepth},
     * its stencil to {@code stencil} where {@code clearStencil}. Render thread, outside a recording. In a graph, a pass's
     * {@code CgLoad.clear} is the clear.
     *
     * <pre>{@code
     * CgRenderer.clear(view, true, 0.5f, 0.7f, 1f, 1f, true, 1.0, true, 0);   // sky, far, no stencil
     * CgRenderer.clear(view, false, 0, 0, 0, 0, true, 1.0, false, 0);          // depth alone, for the overlays
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
