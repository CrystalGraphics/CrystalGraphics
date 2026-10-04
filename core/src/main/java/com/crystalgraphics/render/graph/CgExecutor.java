package com.crystalgraphics.render.graph;

import com.crystalgraphics.api.CgBindingPoints;
import com.crystalgraphics.api.buffer.CgBufferLifetime;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.compute.CgDispatchBindings;
import com.crystalgraphics.compute.CgKernelForm;
import com.crystalgraphics.compute.cpu.CgCpuMirrors;
import com.crystalgraphics.compute.cpu.CgCpuRunner;
import com.crystalgraphics.compute.lower.CgLoweredKernel;
import com.crystalgraphics.compute.lower.CgLoweredResources;
import com.crystalgraphics.compute.program.CgKernelProgram;
import com.crystalgraphics.compute.source.CgComputeSource;
import com.crystalgraphics.compute.source.CgImageAccess;
import com.crystalgraphics.compute.source.CgImageDecl;
import com.crystalgraphics.compute.source.CgImageDimension;
import com.crystalgraphics.compute.source.CgKernelDecl;
import com.crystalgraphics.gl.buffer.CgBufferReadback;
import com.crystalgraphics.gl.buffer.CgBufferTextures;
import com.crystalgraphics.gl.buffer.CgReadback;
import com.crystalgraphics.gl.buffer.CgFrameRing;
import com.crystalgraphics.gl.buffer.CgStreamBuffer;
import com.crystalgraphics.gl.buffer.shader.CgEngineBufferRegistry;
import com.crystalgraphics.gl.buffer.shader.CgShaderBuffer;
import com.crystalgraphics.gl.buffer.shader.CgShaderBufferRegistry;
import com.crystalgraphics.gl.framebuffer.CgFrameBuffer;
import com.crystalgraphics.gpu.CgDeferral;
import com.crystalgraphics.platform.device.command.CgAccess;
import com.crystalgraphics.platform.gl.CgCapabilities.ComputeTier;
import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.platform.gl.state.CgGlScope;
import com.crystalgraphics.platform.gl.state.CgGlState;
import com.crystalgraphics.render.CgGpuBudget;
import com.crystalgraphics.render.draw.CgBufferHandle;
import com.crystalgraphics.render.draw.CgIndirect;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.draw.CgPassConstants;
import com.crystalgraphics.render.draw.CgPipeline;
import com.crystalgraphics.render.mesh.CgMeshStore;
import com.crystalgraphics.render.property.CgPalette;
import com.crystalgraphics.trace.CgGpuTrace;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;

/**
 * Runs a {@link CgFrame} on the render thread: uploads its snapshots and each kind's instances once, then executes its
 * passes in order — transients taken from the pool and returned after their last use, a barrier before each access a
 * kernel takes part in, requests completed or failed. GL state is restored after.
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
 *   <li>Barriers come from each storage's accesses as executed, across frames and executions
 *       ({@code CgHazards}); {@code -Dcrystalgraphics.graph.barriers=false} records them and issues none, which
 *       synchronization validation must then report.</li>
 *   <li>A compute pass needs a context that runs compute shaders, and throws naming the tier where it does not; so
 *       does a raster pass holding an indirect draw, whose command a kernel writes before the pass begins.</li>
 *   <li>An {@code async()} compute pass goes to the device's compute queue, where it has one; the first later step
 *       touching its storage waits for it, and the execution ends waiting for all of it.
 *       {@code -Dcrystalgraphics.graph.asyncAll=true} sends every pass that can go.</li>
 * </ul>
 */
public final class CgExecutor {

    private static final Logger LOGGER = LogManager.getLogger("CgExecutor");
    private static final int TARGET_COPIES = CgTrace.name("graph.target-copies");
    private static final int TARGET_COPY_PIXELS = CgTrace.name("graph.target-copy-pixels");
    private static final int KINDS = CgInstanceKind.values().length;
    private static final int UNIT_KINDS = (1 << CgInstanceKind.QUAD.ordinal()) | (1 << CgInstanceKind.CURVE.ordinal());
    private static final int OBJECT = CgInstanceKind.OBJECT.ordinal();
    /** What every QUAD and CURVE instance expands. */
    private static final CgMesh UNIT_QUAD = CgMesh.quads(1);

    private static final List<CgExecutor> BY_DEPTH = new ArrayList<>();
    private static final CgTexturePool POOL = new CgTexturePool();
    private static final CgBufferPool BUFFERS = new CgBufferPool();
    private static final CgHazards HAZARDS = new CgHazards();
    private static final IntConsumer FORGET_BUFFER = CgExecutor::forget;
    /** Persistent and history buffers whose storage this executor made, freed at teardown if never released. */
    private static final List<CgGraphBuffer> KEPT = new ArrayList<>();
    private static final boolean BARRIERS = !"false".equalsIgnoreCase(System.getProperty("crystalgraphics.graph.barriers"));
    private static final int BARRIER_COUNT = CgTrace.name("graph.barriers");
    private static final int DISPATCH_COUNT = CgTrace.name("graph.dispatches");
    private static final int LOWERED_COUNT = CgTrace.name("graph.dispatches.lowered");
    private static final int CPU_COUNT = CgTrace.name("graph.dispatches.cpu");
    /** Each compute pass's GPU zone by its name, interned once: a pass is recorded anew each frame. */
    private static final Map<String, Integer> GPU_ZONES = new HashMap<>();
    private static final int COMMAND_COUNT = CgTrace.name("graph.indirect-commands");
    /** Runs of draws a multi-draw ended only because the next draw binds other textures or properties. */
    private static final int BINDING_BREAKS = CgTrace.name("graph.multi-draw.binding-breaks");
    /** Every compute pass that can go async does, as if marked: a correctness check of the waits. */
    private static final boolean ASYNC_ALL = Boolean.getBoolean("crystalgraphics.graph.asyncAll");
    private static final int ASYNC_PASSES = CgTrace.name("graph.async-passes");
    private static final int ASYNC_WAITS = CgTrace.name("graph.async-waits");
    /**
     * Set by the first frame with a kernel or a buffer operation. Until then nothing can race what a draw does but a
     * kernel's later write, which the tracked backend's graphics-to-compute wait already orders; so a process that
     * never runs one keeps no accesses at all.
     */
    private static boolean kernelsSeen;
    /** This execution's: whether a barrier is issued (kernels run as compute), and whether a draw takes a GPU count. */
    private static boolean computeBarriers, gpuCounts;
    private static int depth;
    private static long trimmedFrame = -1;

    private final CgStreamBuffer ring;
    private final CgShaderBuffer[] instanceBuffers = new CgShaderBuffer[KINDS];
    private final int[] scissorRect = new int[4], scissorPart = new int[4];
    private final CgIndirectArgs commands = new CgIndirectArgs();
    /** Per file, what its dispatches below compute bind: lowered, or run by a Java body. */
    private final Map<CgComputeSource, CgDispatchBindings> belowCompute = new IdentityHashMap<>();
    private final int[] range = new int[4];
    /** Per indirect batch starting a run of the pass about to draw, the run's last batch: itself when none joins it. */
    private int[] runs = new int[64];
    /** The framebuffer and viewport bound when this execution began, where a pass reads the current target's depth. */
    private int startFramebuffer;
    private boolean startNoted, otherBound;
    private final IntBuffer startViewport = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asIntBuffer();
    /** This execution's: whether an async pass runs beside the frame's queue, and whether runs of draws join. */
    private boolean asyncCompute, multiDraw;
    /** Storage async passes touched that the frame's queue has not waited for, each with the point covering it. */
    private long[] asyncKeys = new long[16], asyncPoints = new long[16];
    private int asyncCount;
    private long asyncLatest;
    private boolean asyncUnwaited;
    private final long[] keyScratch = new long[2];

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
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, "graph.deferrals")) {
            CgDeferral.applyAll();
            long ringFrame = CgFrameRing.frame();
            if (depth == 0 && ringFrame != trimmedFrame) {
                POOL.endFrame();
                BUFFERS.endFrame(FORGET_BUFFER);
                trimmedFrame = ringFrame;
            }
        }
        if (BY_DEPTH.size() == depth) BY_DEPTH.add(new CgExecutor(depth));
        CgExecutor executor = BY_DEPTH.get(depth);
        depth++;
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, "graph.execute");
             CgGlScope ignored2 = restoreState ? CgGlState.saveAll() : null) {
            executor.run(frame);
        } finally {
            depth--;
            if (depth == 0) CgCpuMirrors.endFrame();   // copies of buffers written outside the graph
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

    /**
     * Frees every executor's ring, the transient pools and every persistent or history buffer's storage still held. At
     * context teardown, before the framebuffer sweep.
     */
    public static void destroyAll() {
        for (CgExecutor executor : BY_DEPTH) {
            executor.ring.delete();
            executor.commands.delete(FORGET_BUFFER);
            if (executor.staging != null) executor.staging.delete();
        }
        BY_DEPTH.clear();
        POOL.delete();
        BUFFERS.delete();
        for (CgGraphBuffer buffer : KEPT) freeKept(buffer);
        KEPT.clear();
        HAZARDS.clear();
        CgBufferInspector.reset();
    }

    private void run(CgFrame frame) {
        boolean again = frame.executions++ > 0;
        kernelsSeen |= frame.kernels;
        ComputeTier tier = CgCapabilities.detect().computeTier();
        boolean compute = tier == ComputeTier.V || tier == ComputeTier.G43;
        computeBarriers = BARRIERS && compute;
        gpuCounts = compute || tier == ComputeTier.G40 && CgCapabilities.detect().drawIndirect();
        asyncCompute = tier == ComputeTier.V && CgCapabilities.detect().asyncCompute();
        multiDraw = CgCapabilities.detect().multiDraw() && CgMeshStore.get().multiDraw();
        // The current target, noted before any pass binds its own: rebound for a pass into it after one into another.
        startNoted = frame.readsCurrentDepth || frame.rastersCurrent && frame.rastersOther;
        otherBound = false;
        if (startNoted) {
            startFramebuffer = CgGL.glGetInteger(CgGL.GL_DRAW_FRAMEBUFFER_BINDING);
            CgGL.glGetInteger(CgGL.GL_VIEWPORT, startViewport);
        }
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
                        acquire(frame.transients.get(t));
                        resolved++;
                    }
                }
                CgPass pass = frame.steps[s];
                boolean skipped = again && doneOnce(frame, s, frame.keepRequested);
                if (again) staleTransients(frame, s, skipped);
                if (!skipped) step(frame, s);
                // A WINDOW MOVED BY ITS NODE executes no surface: what a re-execution drew into a kept texture.
                if (again && pass instanceof CgRasterPass && pass.target != null
                        && pass.target.kind() == CgGraphTexture.Kind.REQUESTED) {
                    CgTrace.add(CgChannels.GL, frame.keepRequested ? "graph.again.requested-kept"
                            : "graph.again.requested-drawn", 1);
                }
                for (int t = 0; t < frame.transients.size(); t++) {
                    if (frame.releaseAfter[t] == s) {
                        giveBack(frame.transients.get(t));
                        resolved--;
                    }
                }
            }
        } finally {
            if (asyncUnwaited) waitAsync(asyncLatest);   // nothing after the graph reads what async work has not finished
            if (resolved > 0) {
                for (CgGraphResource transientResource : frame.transients) {
                    if (isResolved(transientResource)) giveBack(transientResource);
                }
            }
            CgLoweredResources.landAll();   // what lowered dispatches left in their targets, before anything outside reads it
        }
    }

    /** Transients a step skipped this execution wrote, which a step run again may not read. */
    private final List<CgGraphResource> stale = new ArrayList<>();
    private final List<CgPass> staleBy = new ArrayList<>();

    /** Executing again: a compute or buffer step skipped leaves its transients unwritten, and one that reads them throws. */
    private void staleTransients(CgFrame frame, int s, boolean skipped) {
        if (s == 0) {
            stale.clear();
            staleBy.clear();
        }
        CgPass pass = frame.steps[s];
        boolean kernelOrBuffer = pass instanceof CgComputePass || pass instanceof CgPass.Fill
                || pass instanceof CgPass.Update || pass instanceof CgPass.BufferCopy;
        for (int i = frame.accessFrom[s]; i < frame.accessFrom[s + 1]; i++) {
            CgGraphResource resource = frame.accessView[i];
            int bits = frame.accessBits[i];
            if (skipped) {
                if (kernelOrBuffer && resource.isTransient() && (bits & CgHazards.WRITES) != 0) {
                    stale.add(resource);
                    staleBy.add(pass);
                }
            } else if ((bits & ~CgHazards.WRITES) != 0) {
                int at = stale.indexOf(resource);
                if (at >= 0) {
                    throw new IllegalStateException(pass + " reads " + resource.name() + ", which " + staleBy.get(at)
                            + " writes; executing the frame again skips that pass, since it also writes what outlives "
                            + "the frame. Write " + resource.name() + " in a pass of its own, or mark it again()");
                }
            }
        }
    }

    private static void acquire(CgGraphResource resource) {
        if (resource instanceof CgGraphTexture texture) {
            texture.resolve(POOL.acquire(texture.desc()));
        } else {
            CgGraphBuffer buffer = (CgGraphBuffer) resource;
            buffer.resolve(BUFFERS.acquire(buffer.desc()));
        }
    }

    private static void giveBack(CgGraphResource resource) {
        if (resource instanceof CgGraphTexture texture) {
            POOL.release(texture.desc(), texture.framebuffer());
            texture.resolve(null);
        } else {
            CgGraphBuffer buffer = (CgGraphBuffer) resource;
            CgLoweredResources.drop(buffer.bufferId());   // its last reader ran: what a target held of it goes unread
            BUFFERS.release(buffer.desc(), buffer.bufferId());
            buffer.resolve(0);
        }
    }

    private static boolean isResolved(CgGraphResource resource) {
        return resource instanceof CgGraphTexture texture ? texture.framebuffer() != null
                : ((CgGraphBuffer) resource).bufferId() != 0;
    }

    /** Every mesh the frame draws placed in the store, and what changed uploaded: before the first raster pass. */
    private static void placeMeshes(CgFrame frame) {
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, "graph.placeMeshes")) {
            placeAndUpload(frame);
        }
    }

    private static void placeAndUpload(CgFrame frame) {
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

    /**
     * Whether a frame executing again skips step {@code s}: a step of a simulation — anything writing what outlives the
     * frame — is taken once.
     */
    private static boolean doneOnce(CgFrame frame, int s, boolean keepRequested) {
        CgPass pass = frame.steps[s];
        if (pass instanceof CgPass.Upload || pass instanceof CgPass.Compile || pass instanceof CgPass.Release
                || pass instanceof CgPass.BufferRelease || pass instanceof CgPass.Readback) return true;
        if (pass instanceof CgPass.Fill || pass instanceof CgPass.Update || pass instanceof CgPass.BufferCopy) {
            return frame.outlives[s];
        }
        if (pass instanceof CgComputePass compute) return frame.outlives[s] && !compute.runsAgain();
        return keepRequested && pass.target != null && pass.target.kind() == CgGraphTexture.Kind.REQUESTED;
    }

    private void step(CgFrame frame, int s) {
        CgPass pass = frame.steps[s];
        CgGpuBudget budget = pass.budget;
        if (pass.gpuZone < 0 && budget == null) {
            run(frame, s, pass);
            return;
        }
        CgGpuTrace.begin(pass.gpuZone >= 0 ? pass.gpuZone : budget.zone(),
                budget != null ? budget.slot() : CgGpuTrace.NO_BUDGET);
        try {
            run(frame, s, pass);
        } finally {
            CgGpuTrace.end();
        }
    }

    private void run(CgFrame frame, int s, CgPass pass) {
        try {
            boolean async = pass instanceof CgComputePass compute && runsAsync(compute);
            if (asyncUnwaited && !async) awaitAsync(frame, s);
            if (CgLoweredResources.holding()) landHeld(frame, s);
            if (!(pass instanceof CgComputePass)) barriers(frame, s);
            if (pass instanceof CgRasterPass raster) {
                raster(frame, raster, frame.rasters[s]);
                unpinLevels(frame, s);
            } else if (pass instanceof CgComputePass compute) {
                if (async) {
                    computeAsync(frame, s, compute);
                } else {
                    // On the detail channel: an enclosing GPU zone is timed around this one, not through it.
                    boolean gpu = CgTrace.isEnabled(CgChannels.GL_DETAIL) && CgGpuTrace.isMeasuring();
                    if (gpu) CgGpuTrace.begin(gpuZone(compute.name()));
                    try {
                        compute(frame, compute, frame.computes[s]);
                    } finally {
                        if (gpu) CgGpuTrace.end();
                    }
                }
                if (CgBufferInspector.watching()) inspect(compute);
                unpinLevels(frame, s);
            } else if (pass instanceof CgPass.Fill fill) {
                int id = bufferStorage(fill.buffer, true);
                CgGL.cgFillBuffer(id, fill.offset, fill.size, fill.value);
                if (fill.value == 0) CgLoweredResources.zeroed(id, fill.offset, fill.size, CgFrameRing.frame());
                else CgLoweredResources.written(id);
                written(fill.buffer);
            } else if (pass instanceof CgPass.Update update) {
                update(update);
            } else if (pass instanceof CgPass.BufferCopy copy) {
                int from = bufferStorage(copy.from, false), to = bufferStorage(copy.to, true);
                CgGL.glBindBuffer(CgGL.GL_COPY_READ_BUFFER, from);
                CgGL.glBindBuffer(CgGL.GL_COPY_WRITE_BUFFER, to);
                CgGL.glCopyBufferSubData(CgGL.GL_COPY_READ_BUFFER, CgGL.GL_COPY_WRITE_BUFFER, copy.fromOffset,
                        copy.toOffset, copy.size);
                CgGL.glBindBuffer(CgGL.GL_COPY_READ_BUFFER, 0);
                CgGL.glBindBuffer(CgGL.GL_COPY_WRITE_BUFFER, 0);
                CgLoweredResources.written(to);
                written(copy.to);
            } else if (pass instanceof CgPass.Readback readback) {
                readback(readback);
            } else if (pass instanceof CgPass.BufferRelease release) {
                freeKept(release.buffer);
                KEPT.remove(release.buffer);
            } else if (pass instanceof CgPass.Copy copy) {
                CgFrameBuffer from = storage(copy.from), to = storage(copy.target);
                CgFrameBuffer.blitFrom(from.getId(), to.getId(), copy.x, copy.y, copy.x + copy.w, copy.y + copy.h,
                        copy.tx, copy.ty, copy.tx + copy.tw, copy.ty + copy.th, CgGL.GL_COLOR_BUFFER_BIT,
                        copy.linear ? CgGL.GL_LINEAR : CgGL.GL_NEAREST);
            } else if (pass instanceof CgPass.Upload upload) {
                upload.writer.upload(storage(upload.target));
                upload.request.complete();
            } else if (pass instanceof CgPass.Callback callback) {
                boolean bound = otherBound;   // the scope restores the binding it found
                try (CgGlScope ignored = CgGlState.saveAll()) {
                    bindTarget(callback.target, 0);
                    callback.body.run();
                }
                otherBound = bound;
                callback.request.complete();
            } else if (pass instanceof CgPass.Compile compile) {
                if (compile.pipeline.prepare()) {
                    if (compile.pipeline.program() != null) compile.request.complete();
                    else compile.request.fail(String.valueOf(compile.pipeline.shader().lastCompileError()));
                }
            } else if (pass instanceof CgPass.Release) {
                CgFrameBuffer storage = pass.target.framebuffer();
                if (storage != null) {
                    CgTexture color = storage.getColorTexture(0);
                    if (color != null) HAZARDS.forget(CgHazards.texture(color.getId()));
                    storage.delete();
                }
                pass.target.resolve(null);
            }
        } catch (RuntimeException failure) {
            if (pass.request == null) throw failure;
            LOGGER.warn("{} failed", pass, failure);
            pass.request.fail(failure.getMessage() == null ? failure.toString() : failure.getMessage());
        }
    }

    /**
     * What a lowered dispatch left in a target, read back into its buffer before a step other than a compute pass touches
     * the buffer; everything before a callback, which may read anything. A compute pass decides per dispatch.
     */
    private static void landHeld(CgFrame frame, int s) {
        CgPass pass = frame.steps[s];
        if (pass instanceof CgComputePass || pass instanceof CgPass.BufferRelease) return;
        if (pass instanceof CgPass.Callback) {
            CgLoweredResources.landAll();
            return;
        }
        for (int i = frame.accessFrom[s]; i < frame.accessFrom[s + 1]; i++) {
            if (!(frame.accessView[i] instanceof CgGraphBuffer view)) continue;
            CgGraphBuffer buffer = view.resource();
            if (buffer.kind() == CgGraphBuffer.Kind.HISTORY) {
                CgLoweredResources.land(buffer.versions()[0]);
                CgLoweredResources.land(buffer.versions()[1]);
            } else {
                CgLoweredResources.land(buffer.bufferId());
            }
        }
    }

    // ── Compute ──────────────────────────────────────────────────────────────

    private void compute(CgFrame frame, CgComputePass pass, CgFrame.Compute packed) {
        List<CgDispatch> dispatches = pass.dispatches();
        boolean draws = false;
        for (int d = 0; d < dispatches.size(); d++) draws |= dispatches.get(d).kernel.form().how() != CgKernelForm.How.COMPUTE;
        int lowered = 0, cpu = 0;
        // A lowered dispatch is draws: what they bind must not reach the passes after, which draw into what is bound.
        try (CgTrace.Zone ignored = CgTrace.isEnabled(CgChannels.GL) ? CgTrace.zone(CgChannels.GL, CgTrace.name(pass.name())) : null;
             CgGlScope scope = draws ? CgLoweredKernel.scope() : null) {
            for (int d = 0; d < dispatches.size(); d++) {
                CgDispatch dispatch = dispatches.get(d);
                switch (dispatch.kernel.form().how()) {
                    case COMPUTE -> dispatch(frame, dispatch, packed.bindings[d]);
                    case LOWERED -> {
                        lowered(frame, dispatch, packed.bindings[d]);
                        lowered++;
                    }
                    case CPU -> {
                        cpu(dispatch);
                        cpu++;
                    }
                }
            }
        }
        CgTrace.add(CgChannels.GL, DISPATCH_COUNT, dispatches.size());
        if (lowered > 0) CgTrace.add(CgChannels.GL, LOWERED_COUNT, lowered);
        if (cpu > 0) CgTrace.add(CgChannels.GL, CPU_COUNT, cpu);
    }

    /** What {@code pass} bound, noted for {@link CgBufferInspector}, and the reads armed for it copied as it left them. */
    private void inspect(CgComputePass pass) {
        CgBufferInspector.note(pass);
        for (CgBufferInspector.Request read = CgBufferInspector.due(); read != null; read = CgBufferInspector.due()) {
            if (asyncUnwaited) waitAsync(asyncLatest);
            int id = bufferStorage(read.view(), false);
            CgLoweredResources.land(id);
            barrier(true, id, CgAccess.COPY_READ);
            CgReadback.buffer(id, read.offset(), read.size(), read);
        }
    }

    private static int gpuZone(String pass) {
        Integer id = GPU_ZONES.get(pass);
        if (id == null) GPU_ZONES.put(pass, id = CgGpuTrace.name(pass));
        return id;
    }

    // ── Async compute ────────────────────────────────────────────────────────

    /** Whether {@code pass} goes beside the frame's queue: asked for, on a device with a compute queue, all compute. */
    private boolean runsAsync(CgComputePass pass) {
        if (!asyncCompute || !(pass.isAsync() || ASYNC_ALL)) return false;
        List<CgDispatch> dispatches = pass.dispatches();
        for (int d = 0; d < dispatches.size(); d++) {
            if (dispatches.get(d).kernel.form().how() != CgKernelForm.How.COMPUTE) return false;
        }
        return true;
    }

    /** Step {@code s} on the compute queue; the storage it touches is pending until the frame's queue waits for it. */
    private void computeAsync(CgFrame frame, int s, CgComputePass pass) {
        CgGL.cgBeginAsync();
        long point;
        try {
            compute(frame, pass, frame.computes[s]);
        } finally {
            point = CgGL.cgEndAsync();
        }
        CgTrace.add(CgChannels.GL, ASYNC_PASSES, 1);
        if (point == 0) return;   // the device ran it in order
        for (int i = frame.accessFrom[s]; i < frame.accessFrom[s + 1]; i++) {
            int n = keys(frame.accessView[i]);
            for (int k = 0; k < n; k++) pend(keyScratch[k], point);
        }
        asyncLatest = point;
        asyncUnwaited = true;
    }

    /**
     * The frame's queue waits before step {@code s} for the async work whose storage it touches, under any name the
     * pool gave it since: everything before a callback, which may touch anything.
     */
    private void awaitAsync(CgFrame frame, int s) {
        long point = 0;
        if (frame.steps[s] instanceof CgPass.Callback) {
            point = asyncLatest;
        } else {
            for (int i = frame.accessFrom[s]; i < frame.accessFrom[s + 1]; i++) {
                int n = keys(frame.accessView[i]);
                for (int k = 0; k < n; k++) {
                    for (int j = 0; j < asyncCount; j++) {
                        if (asyncKeys[j] == keyScratch[k]) point = Math.max(point, asyncPoints[j]);
                    }
                }
            }
        }
        if (point > 0) waitAsync(point);
    }

    private void waitAsync(long point) {
        CgGL.cgWaitAsync(point);
        CgTrace.add(CgChannels.GL, ASYNC_WAITS, 1);
        int kept = 0;
        for (int j = 0; j < asyncCount; j++) {
            if (asyncPoints[j] <= point) continue;
            asyncKeys[kept] = asyncKeys[j];
            asyncPoints[kept++] = asyncPoints[j];
        }
        asyncCount = kept;
        asyncUnwaited = point < asyncLatest;
    }

    private void pend(long key, long point) {
        for (int j = 0; j < asyncCount; j++) {
            if (asyncKeys[j] == key) {
                asyncPoints[j] = point;
                return;
            }
        }
        if (asyncCount == asyncKeys.length) {
            asyncKeys = Arrays.copyOf(asyncKeys, asyncCount * 2);
            asyncPoints = Arrays.copyOf(asyncPoints, asyncCount * 2);
        }
        asyncKeys[asyncCount] = key;
        asyncPoints[asyncCount++] = point;
    }

    /** The storage {@code view} names now, as {@link CgHazards} keys into {@link #keyScratch}: a history's both versions. */
    private int keys(CgGraphResource view) {
        if (view instanceof CgGraphBuffer buffer) {
            CgGraphBuffer resource = buffer.resource();
            if (resource.kind() == CgGraphBuffer.Kind.HISTORY) {
                int[] versions = resource.versions();
                keyScratch[0] = CgHazards.buffer(versions[0]);
                keyScratch[1] = CgHazards.buffer(versions[1]);
                return 2;
            }
            keyScratch[0] = CgHazards.buffer(resource.bufferId());
            return resource.bufferId() == 0 ? 0 : 1;
        }
        CgFrameBuffer storage = ((CgGraphTexture) view).framebuffer();
        CgTexture color = storage == null ? null : storage.getColorTexture(0);
        if (color == null) return 0;
        keyScratch[0] = CgHazards.texture(color.getId());
        return 1;
    }

    /** A dispatch below compute (gpu-compute C5): the kernel's lowered passes over what the dispatch bound. */
    private void lowered(CgFrame frame, CgDispatch d, int bindings) {
        CgLoweredKernel kernel = d.kernel.lowered();
        frame.bindings.bind(bindings);
        for (String token : d.source.engineBuffers()) CgEngineBufferRegistry.get(token).buffer().get().bind();
        kernel.dispatchBound(bind(d), true);
        advanceHistories(d);
    }

    /** A dispatch on the CPU tier (gpu-compute C6): its kernel's Java body, over CPU copies of what it binds. */
    private void cpu(CgDispatch d) {
        CgKernelDecl runs = d.kernel.form().runs();
        CgDispatchBindings b = bind(d);
        CgLoweredResources.land(b);   // the body reads and writes the buffers themselves
        for (int i = 0; i < d.buffers.length; i++) {
            if (imported(d.buffers[i])) CgCpuMirrors.external(b.buffer(i));
            if (imported(d.counters[i])) CgCpuMirrors.external(b.counter(i));
        }
        if (imported(d.args)) CgCpuMirrors.external(b.args());
        CgCpuRunner.dispatch(d.source, runs, d.kernel.keywords(), d.kernel.compute().cpuBody(runs.name()), b, d.values,
                d.pass.constants, d.kernel.compute().properties(), d.samplers);
        advanceHistories(d);
    }

    private static boolean imported(@Nullable CgGraphBuffer buffer) {
        return buffer != null && buffer.kind() == CgGraphBuffer.Kind.IMPORTED;
    }

    private void advanceHistories(CgDispatch d) {
        for (int i = 0; i < d.buffers.length; i++) {
            if (wroteHistory(d.buffers[i], d.bufferAccess[i]) && firstWriter(d, i)) d.buffers[i].advance();
        }
    }

    /** What {@code d} binds, in GL names: what a dispatch below compute runs over. */
    private CgDispatchBindings bind(CgDispatch d) {
        CgDispatchBindings b = belowCompute.computeIfAbsent(d.source, CgDispatchBindings::new);
        for (int i = 0; i < d.buffers.length; i++) {
            CgGraphBuffer view = d.buffers[i];
            if (view == null) {
                b.buffer(i, 0, 0, 0);
            } else {
                long bytes = d.sizes[i] == 0 ? view.size() - d.offsets[i] : d.sizes[i];
                b.buffer(i, bufferStorage(view, (d.bufferAccess[i] & CgAccess.COMPUTE_WRITE) != 0), d.offsets[i], bytes);
            }
            if (d.counters[i] != null) {
                b.counter(i, bufferStorage(d.counters[i], (d.counterAccess[i] & CgAccess.COMPUTE_WRITE) != 0),
                        d.counterOffsets[i]);
            }
        }
        for (int i = 0; i < d.images.length; i++) {
            if (d.images[i] == null) continue;
            CgTexture color = storage(d.images[i]).getColorTexture(0);
            int level = d.levels[i];
            b.image(i, color.getId(), CgGL.GL_TEXTURE_2D, level, color.getLevels(), d.layers[i],
                    Math.max(1, color.getWidth() >> level),
                    Math.max(1, color.getHeight() >> level), 1);
        }
        switch (d.form) {
            case ELEMENTS -> b.elements(d.x, d.y, d.z);
            case GROUPS -> b.elements(d.x * d.decl.sizeX(), d.y * d.decl.sizeY(), d.z * d.decl.sizeZ());
            case INDIRECT -> b.indirect(bufferStorage(d.args, false), d.argsOffset);
        }
        return b.frame(CgFrameRing.frame());
    }

    private void dispatch(CgFrame frame, CgDispatch d, int bindings) {
        CgKernelProgram program = d.kernel.program();
        program.use();
        frame.bindings.bind(bindings);
        for (String token : d.source.engineBuffers()) CgEngineBufferRegistry.get(token).buffer().get().bind();
        for (int b = 0; b < d.buffers.length; b++) {
            if (d.buffers[b] != null) bindStorage(b, d.buffers[b], d.offsets[b], d.sizes[b], d.bufferAccess[b]);
            if (d.counters[b] != null) {
                long base = CgKernelProgram.counterBase(d.counterOffsets[b]);
                bindStorage(program.counterPoint(b), d.counters[b], base, d.counterOffsets[b] - base + 4, d.counterAccess[b]);
                program.counterWord(b, d.counterOffsets[b]);
            }
        }
        for (CgTexture sampler : d.samplers) {
            CgGraphTexture graph = sampler == null ? null : CgGraphTexture.sampled(sampler);
            CgTexture color = graph == null ? null : storage(graph).getColorTexture(0);
            if (color != null) barrier(false, color.getId(), CgAccess.SAMPLED_READ);   // after a kernel wrote it as an image
        }
        for (int i = 0; i < d.images.length; i++) {
            CgGraphTexture texture = d.images[i];
            if (texture == null) continue;
            CgImageDecl image = d.source.images().get(i);
            int id = storage(texture).getColorTexture(0).getId();
            int access = imageAccess(d, i, id);
            if (access != 0) barrier(false, id, access);
            boolean layered = d.layers[i] < 0 && image.dimension() != CgImageDimension.D2;
            CgGL.glBindImageTexture(i, id, d.levels[i], layered, Math.max(0, d.layers[i]), glAccess(image.access()),
                    image.format().glFormat);
        }
        switch (d.form) {
            case ELEMENTS -> program.dispatchBound(d.x, d.y, d.z);
            case GROUPS -> program.dispatchBound(d.x * program.kernel().sizeX(), d.y * program.kernel().sizeY(),
                    d.z * program.kernel().sizeZ());
            case INDIRECT -> {
                int args = bufferStorage(d.args, false);
                barrier(true, args, CgAccess.INDIRECT);
                program.dispatchIndirectBound(args, d.argsOffset);
            }
        }
        // A history written here: its next version is its newest from now on, once however many bindings wrote it.
        for (int b = 0; b < d.buffers.length; b++) {
            if (wroteHistory(d.buffers[b], d.bufferAccess[b]) && firstWriter(d, b)) d.buffers[b].advance();
        }
    }

    /**
     * What dispatch {@code d} does to texture {@code id} through every image binding naming it, at the first such binding;
     * 0 at the rest. One barrier for all of them: a transition made for one binding must be visible to the others, a
     * level read beside a level written.
     */
    private static int imageAccess(CgDispatch d, int binding, int id) {
        int access = 0;
        for (int i = 0; i < d.images.length; i++) {
            if (d.images[i] == null || storage(d.images[i]).getColorTexture(0).getId() != id) continue;
            if (i < binding) return 0;
            access |= d.imageAccess[i];
        }
        return access;
    }

    private static boolean wroteHistory(@Nullable CgGraphBuffer buffer, int access) {
        return buffer != null && buffer.kind() == CgGraphBuffer.Kind.HISTORY && (access & CgAccess.COMPUTE_WRITE) != 0;
    }

    private static boolean firstWriter(CgDispatch d, int b) {
        for (int i = 0; i < b; i++) if (d.buffers[i] == d.buffers[b] && wroteHistory(d.buffers[i], d.bufferAccess[i])) return false;
        return true;
    }

    private static void bindStorage(int point, CgGraphBuffer buffer, long offset, long size, int access) {
        int id = bufferStorage(buffer, (access & CgAccess.COMPUTE_WRITE) != 0);
        if (access != 0) barrier(true, id, access);
        if (offset == 0 && size == 0) CgGL.glBindBufferBase(CgGL.GL_SHADER_STORAGE_BUFFER, point, id);
        else CgGL.glBindBufferRange(CgGL.GL_SHADER_STORAGE_BUFFER, point, id, offset, size == 0 ? buffer.size() - offset : size);
    }

    private static int glAccess(CgImageAccess access) {
        return access == CgImageAccess.READONLY ? CgGL.GL_READ_ONLY
                : access == CgImageAccess.WRITEONLY ? CgGL.GL_WRITE_ONLY : CgGL.GL_READ_WRITE;
    }

    // ── Buffers ──────────────────────────────────────────────────────────────

    /** Where an update's bytes wait for the GPU's copy: graph storage takes no glBufferSubData. */
    @Nullable
    private CgStreamBuffer staging;
    /** A count read back below indirect draws. */
    private final int[] countWord = new int[1];

    private void update(CgPass.Update update) {
        int id = bufferStorage(update.buffer, true);
        int bytes = update.bytes.length;
        if (staging == null) staging = CgStreamBuffer.create(CgGL.GL_COPY_READ_BUFFER, Math.max(bytes, 1 << 16));
        staging.map(bytes).put(update.bytes);
        int at = staging.commit(bytes);
        CgGL.glBindBuffer(CgGL.GL_COPY_READ_BUFFER, staging.getGlBufferId());
        CgGL.glBindBuffer(CgGL.GL_COPY_WRITE_BUFFER, id);
        CgGL.glCopyBufferSubData(CgGL.GL_COPY_READ_BUFFER, CgGL.GL_COPY_WRITE_BUFFER, at, update.offset, bytes);
        CgGL.glBindBuffer(CgGL.GL_COPY_READ_BUFFER, 0);
        CgGL.glBindBuffer(CgGL.GL_COPY_WRITE_BUFFER, 0);
        CgLoweredResources.written(id);
        written(update.buffer);
    }

    /** A history written by a whole-buffer operation: the version written is its newest. */
    private static void written(CgGraphBuffer buffer) {
        if (buffer.kind() == CgGraphBuffer.Kind.HISTORY) buffer.advance();
    }

    /**
     * The storage {@code view} is used through now, made at a persistent or history buffer's first use: a write to a
     * history goes to its next version, a read to its newest, its previous view's read to the other.
     */
    private static int bufferStorage(CgGraphBuffer view, boolean writes) {
        CgGraphBuffer buffer = view.resource();
        switch (buffer.kind()) {
            case PERSISTENT -> {
                if (buffer.bufferId() == 0) {
                    buffer.resolve(CgBufferPool.create(buffer.size()));
                    KEPT.add(buffer);
                }
            }
            case HISTORY -> {
                int[] versions = buffer.versions();
                if (versions[0] == 0) {
                    versions[0] = CgBufferPool.create(buffer.size());
                    versions[1] = CgBufferPool.create(buffer.size());
                    KEPT.add(buffer);
                }
                return writes ? buffer.nextVersion() : view.bufferId();
            }
            default -> { }
        }
        int id = buffer.bufferId();
        if (id == 0) throw new IllegalStateException(view + " has no storage in this pass");
        return id;
    }

    /** A persistent or history buffer's storage freed, and forgotten by the barrier rule. */
    /** GL buffer {@code buffer} is freed: what is known of it goes with it. */
    private static void forget(int buffer) {
        HAZARDS.forget(CgHazards.buffer(buffer));
        CgLoweredResources.drop(buffer);
        CgLoweredResources.written(buffer);
    }

    private static void freeKept(CgGraphBuffer buffer) {
        if (buffer.kind() == CgGraphBuffer.Kind.HISTORY) {
            int[] versions = buffer.versions();
            for (int v = 0; v < 2; v++) {
                if (versions[v] == 0) continue;
                CgGL.glDeleteBuffers(versions[v]);
                forget(versions[v]);
                versions[v] = 0;
            }
        } else if (buffer.bufferId() != 0) {
            CgGL.glDeleteBuffers(buffer.bufferId());
            forget(buffer.bufferId());
            buffer.resolve(0);
        }
    }

    // ── Barriers ─────────────────────────────────────────────────────────────

    /**
     * The barriers step {@code s} needs before it runs, from its access list. Storage that does not exist yet is left
     * alone: a texture a draw samples before anything made it binds nothing, as it always has.
     */
    private static void barriers(CgFrame frame, int s) {
        if (!kernelsSeen) return;
        for (int i = frame.accessFrom[s]; i < frame.accessFrom[s + 1]; i++) {
            int bits = frame.accessBits[i];
            if (bits == 0) continue;   // a release
            if (frame.accessView[i] instanceof CgGraphBuffer buffer) {
                barrier(true, bufferStorage(buffer, (bits & CgHazards.WRITES) != 0), bits);
            } else {
                CgGraphTexture texture = (CgGraphTexture) frame.accessView[i];
                CgFrameBuffer storage = texture.framebuffer();
                CgTexture color = storage == null ? null : storage.getColorTexture(0);
                if (color != null) barrier(false, color.getId(), bits);
            }
        }
    }

    /** The barrier an access of {@code bits} to buffer or texture {@code id} needs, issued. */
    private static void barrier(boolean buffer, int id, int bits) {
        int from = HAZARDS.access(buffer ? CgHazards.buffer(id) : CgHazards.texture(id), bits);
        if (from == 0 || !computeBarriers) return;
        if (buffer) CgGL.cgBufferBarrier(id, from, bits);
        else CgGL.cgImageBarrier(id, from, bits);
        CgTrace.add(CgChannels.GL, BARRIER_COUNT, 1);
    }

    // ── Raster ───────────────────────────────────────────────────────────────

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
        if (packed.indirects > 0 && gpuCounts) writeCommands(pass, packed);   // a dispatch never sits inside a render pass
        bindTarget(pass.target, pass.level);
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
        boolean usable = false, objectsBound = false;
        int slot = 0;
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, "graph.batchLoop")) {
            if (pass.depthFrom() != null) copyDepthFrom(pass);
            for (int b = 0; b < packed.count; b++) {
                int command = packed.counts[b] != null ? slot : -1;
                int end;
                if (command >= 0) {
                    end = gpuCounts ? runs[b] : b;
                    slot += end - b + 1;
                } else {
                    end = packed.objects[b] == null && multiDraw && b + 1 < packed.count && joinable(packed, b, b + 1)
                            ? joinRun(packed, b) : b;
                    if (multiDraw && end + 1 < packed.count && CgTrace.isEnabled(CgChannels.GL)
                            && breaksOnBinding(packed, b, end + 1)) {
                        CgTrace.add(CgChannels.GL, BINDING_BREAKS, 1);
                    }
                }
                if (packed.copyBefore[b] != 0) copyTarget(pass, packed.copyBefore[b], packed.copyRect, b * 4);
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
                int id = end > b ? CgPipeline.byId(packed.pipeline[b]).multiDraw().id() : packed.pipeline[b];
                if (id != boundPipeline) {
                    boundPipeline = id;
                    pipeline = CgPipeline.byId(id);
                    if (pass.state != null) pass.state.apply();   // a pipeline's unset slots are the pass's
                    usable = pipeline.bind();
                    boundBinding = -1;
                }
                if (!usable) {
                    if (end > b && command < 0) CgMeshStore.get().dropJoined();
                    CgTrace.add(CgChannels.GL, "graph.batches.skipped", end - b + 1);
                    b = end;
                    continue;
                }
                if (packed.binding[b] != boundBinding) {
                    boundBinding = packed.binding[b];
                    frame.bindings.bind(boundBinding);
                }
                if (packed.objects[b] != null) {
                    bindObjects(packed.objects[b]);
                    objectsBound = true;
                } else if (objectsBound && packed.kind[b] == OBJECT) {
                    instanceBuffers[OBJECT].bind();
                    objectsBound = false;
                }
                if (end > b) {
                    if (command >= 0) {
                        CgMeshStore.get().drawIndirectJoined(mesh(packed, b), commands.buffer(), commands.offset(command),
                                end - b + 1, commands.stride());
                    } else {
                        CgMeshStore.get().drawJoined();
                    }
                    b = end;
                    continue;
                }
                if (command >= 0 && packed.objects[b] == null
                        && (packed.countModes[b] & 3) == CgIndirect.INSTANCES.ordinal()) {
                    pipeline.sharedInstance(packed.first[b]);
                } else {
                    pipeline.instanceBase(packed.first[b]);
                }
                CgMesh mesh = mesh(packed, b);
                if (command >= 0 && gpuCounts) {
                    CgMeshStore.get().drawIndirect(mesh, pipeline, packed.submesh[b], commands.buffer(),
                            commands.offset(command));
                } else if (command >= 0) {
                    drawCounted(mesh, pipeline, packed, b);
                } else {
                    CgMeshStore.get().draw(mesh, pipeline, packed.instances[b], packed.submesh[b], packed.rangeFirst[b],
                            packed.rangeCount[b]);
                }
            }
        } finally {
            if (pass.targetCopy() != null) pass.targetCopy().release(POOL);
            if (pass.depthFromCopy() != null) pass.depthFromCopy().release(POOL);
        }
        CgGL.glBindVertexArray(0);
    }

    private static CgMesh mesh(CgFrame.Raster packed, int b) {
        return packed.kind[b] == CgInstanceKind.OBJECT.ordinal() ? packed.mesh[b] : UNIT_QUAD;
    }

    /**
     * Joins the run of batches from {@code b} that one multi-draw call draws ({@link CgMeshStore#join}), answering its
     * last: those after it that are {@link #joinable} with it, while the store takes their meshes. {@code b} itself,
     * nothing left joined, when none follows.
     */
    private static int joinRun(CgFrame.Raster packed, int b) {
        CgMeshStore store = CgMeshStore.get();
        if (!join(store, packed, b)) return b;
        int end = b;
        while (end + 1 < packed.count && joinable(packed, b, end + 1) && join(store, packed, end + 1)) end++;
        if (end == b) store.dropJoined();
        return end;
    }

    private static boolean join(CgMeshStore store, CgFrame.Raster packed, int k) {
        return store.join(mesh(packed, k), packed.instances[k], packed.submesh[k], packed.rangeFirst[k],
                packed.rangeCount[k], packed.first[k]);
    }

    /** Batch {@code k} drawn directly under {@code b}'s pipeline, bindings and scissor, with no target copy before it. */
    private static boolean joinable(CgFrame.Raster packed, int b, int k) {
        return packed.counts[k] == null && packed.objects[k] == null && packed.copyBefore[k] == 0
                && packed.pipeline[k] == packed.pipeline[b]
                && packed.binding[k] == packed.binding[b] && packed.scissor[k] == packed.scissor[b];
    }

    /**
     * Indirect batch {@code k} drawn in one multi-draw after {@code b}: as {@link #joinable}, reading the same object
     * records, from a mesh the store {@link CgMeshStore#joins joins} with {@code b}'s. Never a draw whose instances
     * share one record, which a multi-draw's pipeline cannot read.
     */
    private static boolean joinableIndirect(CgFrame.Raster packed, int b, int k) {
        return packed.counts[k] != null && !sharesRecord(packed, k) && packed.copyBefore[k] == 0
                && packed.pipeline[k] == packed.pipeline[b] && packed.binding[k] == packed.binding[b]
                && packed.scissor[k] == packed.scissor[b] && packed.objects[k] == packed.objects[b]
                && CgMeshStore.get().joins(mesh(packed, b), mesh(packed, k));
    }

    /** Whether every instance of indirect batch {@code b} reads its one record: an INSTANCES draw of the frame's. */
    private static boolean sharesRecord(CgFrame.Raster packed, int b) {
        return packed.objects[b] == null && (packed.countModes[b] & 3) == CgIndirect.INSTANCES.ordinal();
    }

    /** Whether batch {@code k} would join {@code b}'s run but for its bindings: what bindless would join. */
    private static boolean breaksOnBinding(CgFrame.Raster packed, int b, int k) {
        return packed.objects[b] == null && packed.counts[k] == null && packed.objects[k] == null
                && packed.copyBefore[k] == 0 && packed.pipeline[k] == packed.pipeline[b]
                && packed.binding[k] != packed.binding[b] && packed.scissor[k] == packed.scissor[b]
                && CgMeshStore.get().joins(mesh(packed, b), mesh(packed, k));
    }

    /** Binds a GPU buffer of object records where {@code CG_OBJECT_DATA} reads the frame's own. */
    private static void bindObjects(CgBufferHandle objects) {
        int id = objects instanceof CgGraphBuffer graph ? bufferStorage(graph, false) : objects.bufferId();
        if (CgBindingPoints.PATH == CgCapabilities.ShaderBufferPath.TBO) {
            CgBufferTextures.bind(CgBindingPoints.OBJECT_DATA.tbo(), CgGL.GL_RGBA32F, id);
            CgTexture.active(0);
        } else {
            CgGL.glBindBufferBase(CgGL.GL_SHADER_STORAGE_BUFFER, CgBindingPoints.OBJECT_DATA.ssbo(), id);
        }
    }

    /** Copies the depth of the target a pass reads besides its own, whole, and binds it. */
    private void copyDepthFrom(CgRasterPass pass) {
        CgGraphTexture from = pass.depthFrom();
        CgTargetCopy copy = pass.depthFromCopy();
        if (from.kind() == CgGraphTexture.Kind.CURRENT) {
            copy.copyDepth(startFramebuffer, null, startViewport.get(2), startViewport.get(3), pass, POOL);
        } else {
            CgFrameBuffer storage = storage(from);
            copy.copyDepth(storage.getId(), storage.getFormat(), storage.getWidth(), storage.getHeight(), pass, POOL);
        }
        copy.depth.bind(pass.depthFromUnit());
        CgTrace.add(CgChannels.GL, TARGET_COPIES, 1);
    }

    /**
     * The pass's indirect commands, one per indirect batch in batch order, written before the pass begins; each
     * count's own barrier came with the pass's access list. Where draws join, decides the runs a multi-draw draws
     * ({@link #runs}) and writes their commands in its form.
     */
    private void writeCommands(CgRasterPass pass, CgFrame.Raster packed) {
        int args = commands.reserve(packed.indirects, FORGET_BUFFER);
        barrier(true, args, CgAccess.COMPUTE_WRITE);
        if (runs.length < packed.count) runs = new int[Math.max(packed.count, runs.length * 2)];
        int slot = 0;
        try (CgGlScope scope = commands.lowered() ? CgLoweredKernel.scope() : null) {
            for (int b = 0; b < packed.count; b++) {
                if (packed.counts[b] == null) continue;
                int end = b;
                if (multiDraw && !sharesRecord(packed, b)) {
                    while (end + 1 < packed.count && joinableIndirect(packed, b, end + 1)) end++;
                }
                runs[b] = end;
                for (int k = b; k <= end; k++) writeCommand(packed, k, slot++, end > b);
                b = end;
            }
        }
        barrier(true, args, CgAccess.INDIRECT);
        CgTrace.add(CgChannels.GL, COMMAND_COUNT, packed.indirects);
    }

    /** Batch {@code b}'s command into {@code slot}; {@code joined}, in a multi-draw's form with its first instance. */
    private void writeCommand(CgFrame.Raster packed, int b, int slot, boolean joined) {
        CgBufferHandle count = packed.counts[b];
        int countId;
        if (count instanceof CgGraphBuffer graph) {
            countId = bufferStorage(graph, false);
        } else {
            countId = count.bufferId();
            barrier(true, countId, CgAccess.COMPUTE_READ);
        }
        CgMeshStore store = CgMeshStore.get();
        boolean drawn = joined
                ? store.joinedRange(packed.mesh[b], packed.submesh[b], packed.rangeFirst[b], packed.rangeCount[b], range)
                : store.range(packed.mesh[b], packed.submesh[b], packed.rangeFirst[b], packed.rangeCount[b], range);
        if (!drawn) Arrays.fill(range, 0);   // a mesh with nothing to draw: a command of nothing
        long countBytes = count instanceof CgGraphBuffer graph ? graph.size() : packed.countOffsets[b] + 4;
        commands.write(slot, countId, packed.countOffsets[b], countBytes, CgIndirect.values()[packed.countModes[b] & 3],
                packed.countModes[b] >>> 2, range, packed.instances[b],
                packed.objects[b] != null ? packed.instances[b] : -1, joined ? packed.first[b] : 0);
    }

    /**
     * An indirect batch drawn by its count read on the CPU, where no draw can take the GPU's (tier G33, or a context
     * with no indirect draw): the same picture, at the cost of the read.
     */
    private void drawCounted(CgMesh mesh, CgPipeline pipeline, CgFrame.Raster packed, int b) {
        CgBufferHandle handle = packed.counts[b];
        int countId = handle instanceof CgGraphBuffer graph ? bufferStorage(graph, false) : handle.bufferId();
        long held = CgCpuMirrors.word(countId, packed.countOffsets[b]);   // a count the CPU tier wrote: no read
        if (held < 0) {
            CgBufferReadback.readWords(countId, packed.countOffsets[b], countWord, 0, 1);
            held = Integer.toUnsignedLong(countWord[0]);
        }
        long n = held * (packed.countModes[b] >>> 2);
        if (packed.objects[b] != null) n = Math.min(n, packed.instances[b]);
        CgMeshStore store = CgMeshStore.get();
        int submesh = Math.max(0, packed.submesh[b]);
        if ((packed.countModes[b] & 3) == CgIndirect.INSTANCES.ordinal()) {
            if (n > 0) {
                store.draw(mesh, pipeline, (int) Math.min(n, Integer.MAX_VALUE), submesh, packed.rangeFirst[b],
                        packed.rangeCount[b]);
            }
        } else if (store.range(mesh, submesh, packed.rangeFirst[b], packed.rangeCount[b], range)) {
            long count = Math.min(n, range[1]);
            if (count > 0) store.draw(mesh, pipeline, packed.instances[b], submesh, packed.rangeFirst[b], (int) count);
        }
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

    /** What a step sampled one level of samples every level again: no pin outlives the pass that made it. */
    private static void unpinLevels(CgFrame frame, int s) {
        for (int i = frame.accessFrom[s]; i < frame.accessFrom[s + 1]; i++) {
            if (frame.accessView[i] instanceof CgGraphTexture texture && texture.framebuffer() != null) texture.unpinLevels();
        }
    }

    /**
     * Copies what {@code bits} name from the pass's target for the draws sampling it, colour in the rect at {@code at}
     * of {@code rects}, and binds the copies.
     */
    private static void copyTarget(CgRasterPass pass, int bits, int[] rects, int at) {
        CgGraphTexture target = pass.target;
        CgTargetCopy copy = pass.targetCopy();
        long pixels;
        if (target == null || target.kind() == CgGraphTexture.Kind.CURRENT) {
            pixels = copy.copy(CgGL.glGetInteger(CgGL.GL_DRAW_FRAMEBUFFER_BINDING), null, 0, 0, bits, rects, at, POOL);
        } else {
            CgFrameBuffer storage = storage(target);
            pixels = copy.copy(storage.getId(), storage.getFormat(), storage.getWidth(), storage.getHeight(), bits,
                    rects, at, POOL);
        }
        if ((bits & CgTargetCopy.COLOR) != 0) copy.color.bind(pass.sceneColorUnit());
        if ((bits & CgTargetCopy.DEPTH) != 0) copy.depth.bind(pass.sceneDepthUnit());
        CgTrace.add(CgChannels.GL, TARGET_COPIES, 1);
        CgTrace.add(CgChannels.GL, TARGET_COPY_PIXELS, pixels);
    }

    /**
     * Binds level {@code level} of a pass's target and its viewport. The current target is left as it is, unless a pass
     * of this execution bound another: then what was bound when it began is bound again.
     */
    private void bindTarget(CgGraphTexture target, int level) {
        if (target == null || target.kind() == CgGraphTexture.Kind.CURRENT) {
            if (otherBound) {
                CgGL.glBindFramebuffer(CgGL.GL_FRAMEBUFFER, startFramebuffer);
                CgGL.glViewport(startViewport.get(0), startViewport.get(1), startViewport.get(2), startViewport.get(3));
                otherBound = false;
            }
            return;
        }
        CgFrameBuffer storage = storage(target);
        storage.bindLevel(level);
        CgGL.glViewport(0, 0, storage.levelWidth(level), storage.levelHeight(level));
        otherBound = startNoted;
    }

    /** A readback's copy, issued here; its sink hears from {@link CgReadback#poll} once the GPU has finished. */
    private static void readback(CgPass.Readback r) {
        if (r.buffer != null) {
            CgReadback.buffer(bufferStorage(r.buffer, false), r.offset, r.size, r);
            return;
        }
        CgFrameBuffer storage = storage(r.texture);
        CgReadback.pixels(storage.levelId(r.level), r.x, r.y, r.w, r.h, storage.getFormat().getColorSlot(0), r);
    }

    /** A texture's storage now: a requested one's is made on first use. */
    private static CgFrameBuffer storage(CgGraphTexture texture) {
        CgFrameBuffer storage = texture.framebuffer();
        if (storage == null && texture.kind() == CgGraphTexture.Kind.REQUESTED) {
            // At a requested texture's first use; made again later, the picture it held was lost.
            CgTrace.add(CgChannels.GL, "graph.requested.made", 1);
            CgTextureDesc desc = texture.desc();
            storage = CgFrameBuffer.createOwned("cg_graph_" + texture.name(), desc.width(), desc.height(), desc.format(),
                    desc.levels());
            texture.resolve(storage);
        }
        if (storage == null) throw new IllegalStateException(texture + " has no storage in this pass");
        return storage;
    }
}
