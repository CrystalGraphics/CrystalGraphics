package com.crystalgraphics.render.graph;

import com.crystalgraphics.gpu.CgDeferral;
import com.crystalgraphics.api.CgBindingPoints;
import com.crystalgraphics.api.buffer.CgBufferLifetime;
import com.crystalgraphics.gl.buffer.CgFrameRing;
import com.crystalgraphics.gl.buffer.CgStreamBuffer;
import com.crystalgraphics.gl.buffer.shader.CgShaderBuffer;
import com.crystalgraphics.gl.buffer.shader.CgShaderBufferRegistry;
import com.crystalgraphics.gl.framebuffer.CgFrameBuffer;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.render.mesh.CgMeshStore;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.platform.gl.state.CgGlScope;
import com.crystalgraphics.platform.gl.state.CgGlState;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.draw.CgPassConstants;
import com.crystalgraphics.render.draw.CgPipeline;
import com.crystalgraphics.render.property.CgPalette;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs a {@link CgFrame} on the render thread: uploads its snapshots and each kind's instances once, then executes its
 * passes in order — transients taken from the pool and returned after their last use, requests completed or failed.
 * GL state is restored after.
 *
 * <pre>{@code
 * CgFrame frame = builder.build(graph);   // any thread
 * CgExecutor.execute(frame);              // render thread, inside the host's section
 * builder.recycle(frame);
 * }</pre>
 *
 * <ul>
 *   <li>Render thread only, and inside a frame: the uploads are on the frame ring.</li>
 *   <li>A pass that throws fails its request and the frame goes on; one with no request rethrows after GL state is
 *       restored.</li>
 *   <li>Re-entrant: a callback pass that executes a frame of its own (a {@code CgImmediate} inside it) gets its
 *       own ring and instance buffers, so the outer frame's are still bound when it returns.</li>
 * </ul>
 */
public final class CgExecutor {

    private static final Logger LOGGER = LogManager.getLogger("CgExecutor");
    private static final int KINDS = CgInstanceKind.values().length;
    private static final int UNIT_KINDS = (1 << CgInstanceKind.QUAD.ordinal()) | (1 << CgInstanceKind.CURVE.ordinal());
    /** What every QUAD and CURVE instance expands. */
    private static final CgMesh UNIT_QUAD = CgMesh.quads(1);

    private static final List<CgExecutor> BY_DEPTH = new ArrayList<>();
    private static final CgTexturePool POOL = new CgTexturePool();
    private static int depth;
    private static long trimmedFrame = -1;

    private final CgStreamBuffer ring;
    private final CgShaderBuffer[] instanceBuffers = new CgShaderBuffer[KINDS];
    private final int[] scissorRect = new int[4], scissorPart = new int[4];

    private CgExecutor(int depth) {
        ring = CgStreamBuffer.createFrameLocal(CgGL.GL_UNIFORM_BUFFER, 64 * 1024);
        CgShaderBufferRegistry registry = CgShaderBufferRegistry.get();
        instanceBuffers[CgInstanceKind.QUAD.ordinal()] = registry.getOrCreateInternal("CgGraphQuadInstances" + depth,
                CgInstanceKind.QUAD.format(), CgBindingPoints.QUAD_RENDERER, CgBufferLifetime.FRAME);
        instanceBuffers[CgInstanceKind.CURVE.ordinal()] = registry.getOrCreateInternal("CgGraphCurveInstances" + depth,
                CgInstanceKind.CURVE.format(), CgBindingPoints.CURVE_RENDERER, CgBufferLifetime.FRAME);
        instanceBuffers[CgInstanceKind.OBJECT.ordinal()] = registry.getOrCreateInternal("CgGraphObjectInstances" + depth,
                CgInstanceKind.OBJECT.format(), CgBindingPoints.OBJECT_DATA, CgBufferLifetime.FRAME);
    }

    /** Executes {@code frame} and restores GL state after. Render thread, inside a frame. */
    public static void execute(CgFrame frame) {
        execute(frame, true);
    }

    /**
     * Executes {@code frame}; without {@code restoreState}, the state its last pass set is left bound, as an
     * immediate draw always left it. Render thread, inside a frame.
     */
    public static void execute(CgFrame frame, boolean restoreState) {
        // GPU-object work asked for with the frame, or before it, where no GL could run.
        CgDeferral.applyAll();
        long ringFrame = CgFrameRing.frame();
        if (depth == 0 && ringFrame != trimmedFrame) {
            POOL.endFrame();
            trimmedFrame = ringFrame;
        }
        if (BY_DEPTH.size() == depth) BY_DEPTH.add(new CgExecutor(depth));
        CgExecutor executor = BY_DEPTH.get(depth);
        depth++;
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, "graph.execute");
             CgGlScope ignored2 = restoreState ? CgGlState.saveAll() : null) {
            executor.run(frame);
        } finally {
            depth--;
        }
    }

    /**
     * Executes {@code frame} again, after {@link #execute(CgFrame)}: what its passes read as it stands now -- property
     * values, a texture -- with its uploads, compiles and releases not repeated. With {@code keepRequested}, every pass
     * that writes a requested texture is skipped too, leaving it as the last execution did; without it, a pass
     * {@linkplain CgRasterPass#damage limited to its damage} draws whole, since the values may move what it drew. Render thread, inside a
     * frame; GL state restored after.
     *
     * <pre>{@code
     * CgExecutor.execute(frame);
     * values.translate(node, 0f, -12f);
     * CgExecutor.executeAgain(frame, true);   // scrolled, and the previews it rendered left as they were
     * }</pre>
     */
    public static void executeAgain(CgFrame frame, boolean keepRequested) {
        if (frame.executions == 0) throw new IllegalStateException("executeAgain before the frame's first execution");
        frame.keepRequested = keepRequested;
        frame.wholePasses = !keepRequested;
        try {
            execute(frame, true);
        } finally {
            frame.keepRequested = false;
            frame.wholePasses = false;
        }
    }

    /** Frees every executor's ring and the transient pool. At context teardown, before the framebuffer sweep. */
    public static void destroyAll() {
        for (CgExecutor executor : BY_DEPTH) executor.ring.delete();
        BY_DEPTH.clear();
        POOL.delete();
    }

    private void run(CgFrame frame) {
        boolean again = frame.executions++ > 0;
        frame.bindings.upload(ring);
        for (int k = 0; k < KINDS; k++) {
            if (frame.instanceFloats[k] > 0) instanceBuffers[k].uploadRaw(frame.instances[k], frame.instanceFloats[k]);
        }
        placeMeshes(frame);
        int resolved = 0;
        try {
            for (int s = 0; s < frame.stepCount; s++) {
                for (int t = 0; t < frame.transients.size(); t++) {
                    if (frame.acquireAt[t] == s) {
                        CgGraphTexture texture = frame.transients.get(t);
                        texture.resolve(POOL.acquire(texture.desc()));
                        resolved++;
                    }
                }
                CgPass pass = frame.steps[s];
                if (!again || !doneOnce(pass, frame.keepRequested)) step(frame, s);
                // A WINDOW MOVED BY ITS NODE executes no surface: what a re-execution drew into a kept texture.
                if (again && pass instanceof CgRasterPass && pass.target != null
                        && pass.target.kind() == CgGraphTexture.Kind.REQUESTED) {
                    CgTrace.add(CgChannels.GL, frame.keepRequested ? "graph.again.requested-kept"
                            : "graph.again.requested-drawn", 1);
                }
                for (int t = 0; t < frame.transients.size(); t++) {
                    if (frame.releaseAfter[t] == s) {
                        CgGraphTexture texture = frame.transients.get(t);
                        POOL.release(texture.desc(), texture.framebuffer());
                        texture.resolve(null);
                        resolved--;
                    }
                }
            }
        } finally {
            if (resolved > 0) {
                for (CgGraphTexture texture : frame.transients) {
                    if (texture.framebuffer() == null) continue;
                    POOL.release(texture.desc(), texture.framebuffer());
                    texture.resolve(null);
                }
            }
        }
    }

    /** Every mesh the frame draws placed in the store, and what changed uploaded: before the first raster pass. */
    private static void placeMeshes(CgFrame frame) {
        CgMeshStore store = CgMeshStore.get();
        boolean unitQuad = false;
        for (int s = 0; s < frame.stepCount; s++) {
            CgFrame.Raster packed = frame.rasters[s];
            if (packed == null) continue;
            unitQuad |= (packed.kinds & UNIT_KINDS) != 0;
            for (int b = 0; b < packed.count; b++) {
                if (packed.mesh[b] != null) store.place(packed.mesh[b]);
            }
        }
        if (unitQuad) store.place(UNIT_QUAD);
        store.upload();
    }

    /** Whether a frame executing again skips {@code pass}. */
    private static boolean doneOnce(CgPass pass, boolean keepRequested) {
        if (pass instanceof CgPass.Upload || pass instanceof CgPass.Compile || pass instanceof CgPass.Release) return true;
        return keepRequested && pass.target != null && pass.target.kind() == CgGraphTexture.Kind.REQUESTED;
    }

    private void step(CgFrame frame, int s) {
        CgPass pass = frame.steps[s];
        try {
            if (pass instanceof CgRasterPass raster) {
                raster(frame, raster, frame.rasters[s]);
            } else if (pass instanceof CgPass.Copy copy) {
                CgFrameBuffer from = storage(copy.from), to = storage(copy.target);
                CgFrameBuffer.blitFrom(from.getId(), to.getId(), copy.x, copy.y, copy.x + copy.w, copy.y + copy.h,
                        copy.tx, copy.ty, copy.tx + copy.tw, copy.ty + copy.th, CgGL.GL_COLOR_BUFFER_BIT,
                        copy.linear ? CgGL.GL_LINEAR : CgGL.GL_NEAREST);
            } else if (pass instanceof CgPass.Upload upload) {
                upload.writer.upload(storage(upload.target));
                upload.request.complete();
            } else if (pass instanceof CgPass.Callback callback) {
                try (CgGlScope ignored = CgGlState.saveAll()) {
                    bindTarget(callback.target);
                    callback.body.run();
                }
                callback.request.complete();
            } else if (pass instanceof CgPass.Compile compile) {
                if (compile.pipeline.prepare()) {
                    if (compile.pipeline.program() != null) compile.request.complete();
                    else compile.request.fail(String.valueOf(compile.pipeline.shader().lastCompileError()));
                }
            } else if (pass instanceof CgPass.Release) {
                CgFrameBuffer storage = pass.target.framebuffer();
                if (storage != null) storage.delete();
                pass.target.resolve(null);
            }
        } catch (RuntimeException failure) {
            if (pass.request == null) throw failure;
            LOGGER.warn("{} failed", pass, failure);
            pass.request.fail(failure.getMessage() == null ? failure.toString() : failure.getMessage());
        }
    }

    private void raster(CgFrame frame, CgRasterPass pass, CgFrame.Raster packed) {
        int[] damage = frame.wholePasses ? null : pass.damage();
        raster(frame, pass, packed, damage);
    }

    private void raster(CgFrame frame, CgRasterPass pass, CgFrame.Raster packed, @Nullable int[] damage) {
        if (damage != null && (damage[2] == 0 || damage[3] == 0)) {
            // NOTHING CHANGED IN IT: the target keeps what the last execution left.
            CgTrace.add(CgChannels.GL, "graph.passes.undamaged", 1);
            return;
        }
        if (damage != null) CgTrace.add(CgChannels.GL, "graph.damage-kpx", (long) damage[2] * damage[3] / 1000L);
        bindTarget(pass.target);
        CgLoad load = pass.load;
        if (load.mask() != 0) {
            if (damage == null) {
                CgGL.glDisable(CgGL.GL_SCISSOR_TEST);
            } else {
                CgGL.glEnable(CgGL.GL_SCISSOR_TEST);
                CgGL.glScissor(damage[0], damage[1], damage[2], damage[3]);
            }
            CgGL.glColorMask(true, true, true, true);
            CgGL.glClearColor(load.r(), load.g(), load.b(), load.a());
            if ((load.mask() & CgGL.GL_DEPTH_BUFFER_BIT) != 0) {
                CgGL.glDepthMask(true);
                CgGL.glClearDepth(load.depth());
            }
            CgGL.glClear(load.mask());
        }
        if (packed.count == 0) return;
        frame.bindings.bind(packed.constants);
        for (int k = 0; k < KINDS; k++) if ((packed.kinds & (1 << k)) != 0) instanceBuffers[k].bind();
        packed.palette.pass(pass.viewOwner(), pass.viewX(), pass.viewY(), CgPassConstants.height(pass.constants));
        if ((packed.kinds & UNIT_KINDS) != 0) {
            packed.clips.bindForDraw();
            packed.shapes.bindForDraw();
            packed.palette.bindForDraw();
        }

        int boundPipeline = -1, boundBinding = -1, boundScissor = CgRasterPass.INHERIT;
        if (damage != null) {
            // Every draw cut to the damage, with or without a scissor of its own.
            CgGL.glEnable(CgGL.GL_SCISSOR_TEST);
            CgGL.glScissor(damage[0], damage[1], damage[2], damage[3]);
        }
        CgPipeline pipeline = null;
        boolean usable = false;
        for (int b = 0; b < packed.count; b++) {
            if (packed.scissor[b] != boundScissor) {
                boundScissor = packed.scissor[b];
                if (boundScissor == CgRasterPass.NO_SCISSOR) {
                    if (damage == null) CgGL.glDisable(CgGL.GL_SCISSOR_TEST);
                    else CgGL.glScissor(damage[0], damage[1], damage[2], damage[3]);
                } else if (boundScissor >= 0) {
                    scissorRect(pass, packed.palette, boundScissor);
                    if (damage != null) cutToDamage(damage);
                    CgGL.glEnable(CgGL.GL_SCISSOR_TEST);
                    CgGL.glScissor(scissorRect[0], scissorRect[1], scissorRect[2], scissorRect[3]);
                }
            }
            if (packed.pipeline[b] != boundPipeline) {
                boundPipeline = packed.pipeline[b];
                pipeline = CgPipeline.byId(boundPipeline);
                if (pass.state != null) pass.state.apply();   // a pipeline's unset slots are the pass's
                usable = pipeline.bind();
                boundBinding = -1;
            }
            if (!usable) {
                CgTrace.add(CgChannels.GL, "graph.batches.skipped", 1);
                continue;
            }
            pipeline.instanceBase(packed.first[b]);
            if (packed.binding[b] != boundBinding) {
                boundBinding = packed.binding[b];
                frame.bindings.bind(boundBinding);
            }
            CgMesh mesh = packed.kind[b] == CgInstanceKind.OBJECT.ordinal() ? packed.mesh[b] : UNIT_QUAD;
            CgMeshStore.get().draw(mesh, pipeline, packed.instances[b], packed.submesh[b], packed.rangeFirst[b],
                    packed.rangeCount[b]);
        }
        CgGL.glBindVertexArray(0);
    }

    /** {@link #scissorRect} cut by a pass's damage. */
    private void cutToDamage(int[] damage) {
        int x0 = Math.max(scissorRect[0], damage[0]), y0 = Math.max(scissorRect[1], damage[1]);
        int x1 = Math.min(scissorRect[0] + scissorRect[2], damage[0] + damage[2]);
        int y1 = Math.min(scissorRect[1] + scissorRect[3], damage[1] + damage[3]);
        scissorRect[0] = x0;
        scissorRect[1] = y0;
        scissorRect[2] = Math.max(0, x1 - x0);
        scissorRect[3] = Math.max(0, y1 - y0);
    }

    /** Scissor {@code index} and every one it is inside, cut together, into {@link #scissorRect}. */
    private void scissorRect(CgRasterPass pass, CgPalette palette, int index) {
        int[] rects = pass.scissorRects();
        int x0 = Integer.MIN_VALUE, y0 = Integer.MIN_VALUE, x1 = Integer.MAX_VALUE, y1 = Integer.MAX_VALUE;
        for (int s = index; s >= 0; s = pass.scissorParent(s)) {
            int node = pass.scissorNode(s);
            int[] rect = scissorPart;
            if (node < 0) System.arraycopy(rects, s * 4, rect, 0, 4);
            else palette.scissorOf(node, pass.scissorBoxes(), s * 4, rect);
            x0 = Math.max(x0, rect[0]);
            y0 = Math.max(y0, rect[1]);
            x1 = Math.min(x1, rect[0] + rect[2]);
            y1 = Math.min(y1, rect[1] + rect[3]);
        }
        scissorRect[0] = x0;
        scissorRect[1] = y0;
        scissorRect[2] = Math.max(0, x1 - x0);
        scissorRect[3] = Math.max(0, y1 - y0);
    }

    /** Binds a pass's target and its viewport; the current target is left as it is. */
    private static void bindTarget(CgGraphTexture target) {
        if (target == null || target.kind() == CgGraphTexture.Kind.CURRENT) return;
        CgFrameBuffer storage = storage(target);
        storage.bind();
        CgGL.glViewport(0, 0, storage.getWidth(), storage.getHeight());
    }

    /** A texture's storage now: a requested one's is made on first use. */
    private static CgFrameBuffer storage(CgGraphTexture texture) {
        CgFrameBuffer storage = texture.framebuffer();
        if (storage == null && texture.kind() == CgGraphTexture.Kind.REQUESTED) {
            // At a requested texture's first use; made again later, the picture it held was lost.
            CgTrace.add(CgChannels.GL, "graph.requested.made", 1);
            CgTextureDesc desc = texture.desc();
            storage = CgFrameBuffer.createOwned("cg_graph_" + texture.name(), desc.width(), desc.height(), desc.format());
            texture.resolve(storage);
        }
        if (storage == null) throw new IllegalStateException(texture + " has no storage in this pass");
        return storage;
    }
}
