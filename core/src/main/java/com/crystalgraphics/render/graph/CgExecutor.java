package com.crystalgraphics.render.graph;

import com.crystalgraphics.gpu.CgDeferral;
import com.crystalgraphics.api.CgBindingPoints;
import com.crystalgraphics.api.buffer.CgBufferLifetime;
import com.crystalgraphics.gl.buffer.CgFrameRing;
import com.crystalgraphics.gl.buffer.CgStreamBuffer;
import com.crystalgraphics.gl.buffer.shader.CgShaderBuffer;
import com.crystalgraphics.gl.buffer.shader.CgShaderBufferRegistry;
import com.crystalgraphics.gl.framebuffer.CgFrameBuffer;
import com.crystalgraphics.gl.mesh.CgMesh;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.platform.gl.state.CgGlScope;
import com.crystalgraphics.platform.gl.state.CgGlState;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.draw.CgPassConstants;
import com.crystalgraphics.render.draw.CgPipeline;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

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

    private static final List<CgExecutor> BY_DEPTH = new ArrayList<>();
    private static final CgTexturePool POOL = new CgTexturePool();
    private static int depth;
    private static long trimmedFrame = -1;

    private final CgStreamBuffer ring;
    private final CgShaderBuffer[] instanceBuffers = new CgShaderBuffer[KINDS];

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

    /** Frees every executor's ring and the transient pool. At context teardown, before the framebuffer sweep. */
    public static void destroyAll() {
        for (CgExecutor executor : BY_DEPTH) executor.ring.delete();
        BY_DEPTH.clear();
        POOL.delete();
    }

    private void run(CgFrame frame) {
        frame.bindings.upload(ring);
        for (int k = 0; k < KINDS; k++) {
            if (frame.instanceFloats[k] > 0) instanceBuffers[k].uploadRaw(frame.instances[k], frame.instanceFloats[k]);
        }
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
                step(frame, s);
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
        bindTarget(pass.target);
        CgLoad load = pass.load;
        if (load.mask() != 0) {
            CgGL.glDisable(CgGL.GL_SCISSOR_TEST);
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
        if ((packed.kinds & UNIT_KINDS) != 0) {
            packed.clips.bindForDraw();
            packed.palette.bindForDraw(null, CgPassConstants.height(pass.constants));
        }

        int boundPipeline = -1, boundBinding = -1, boundScissor = CgRasterPass.INHERIT;
        CgPipeline pipeline = null;
        boolean usable = false;
        int[] rects = pass.scissorRects();
        for (int b = 0; b < packed.count; b++) {
            if (packed.scissor[b] != boundScissor) {
                boundScissor = packed.scissor[b];
                if (boundScissor == CgRasterPass.NO_SCISSOR) {
                    CgGL.glDisable(CgGL.GL_SCISSOR_TEST);
                } else if (boundScissor >= 0) {
                    int r = boundScissor * 4;
                    CgGL.glEnable(CgGL.GL_SCISSOR_TEST);
                    CgGL.glScissor(rects[r], rects[r + 1], rects[r + 2], rects[r + 3]);
                }
            }
            if (packed.pipeline[b] != boundPipeline) {
                boundPipeline = packed.pipeline[b];
                pipeline = CgPipeline.byId(boundPipeline);
                if (pass.state != null) pass.state.apply();   // a pipeline's unset slots are the pass's
                usable = pipeline.bind();
                boundBinding = -1;
            }
            if (!usable) continue;
            pipeline.instanceBase(packed.first[b]);
            if (packed.binding[b] != boundBinding) {
                boundBinding = packed.binding[b];
                frame.bindings.bind(boundBinding);
            }
            CgMesh mesh = packed.kind[b] == CgInstanceKind.OBJECT.ordinal() ? packed.mesh[b] : CgInstanceGeometry.unitQuad();
            mesh.drawInstanced(packed.instances[b]);
        }
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
            CgTextureDesc desc = texture.desc();
            storage = CgFrameBuffer.createOwned("cg_graph_" + texture.name(), desc.width(), desc.height(), desc.format());
            texture.resolve(storage);
        }
        if (storage == null) throw new IllegalStateException(texture + " has no storage in this pass");
        return storage;
    }
}
