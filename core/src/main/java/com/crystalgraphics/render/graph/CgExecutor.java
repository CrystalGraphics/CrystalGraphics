package com.crystalgraphics.render.graph;

import com.crystalgraphics.compute.program.CgKernelProgram;
import com.crystalgraphics.compute.source.CgImageAccess;
import com.crystalgraphics.compute.source.CgImageDecl;
import com.crystalgraphics.compute.source.CgImageDimension;
import com.crystalgraphics.gl.buffer.shader.CgEngineBufferRegistry;
import com.crystalgraphics.gpu.CgDeferral;
import com.crystalgraphics.platform.device.command.CgAccess;
import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.api.CgBindingPoints;
import com.crystalgraphics.api.buffer.CgBufferLifetime;
import com.crystalgraphics.gl.buffer.CgFrameRing;
import com.crystalgraphics.gl.buffer.CgStreamBuffer;
import com.crystalgraphics.gl.buffer.shader.CgShaderBuffer;
import com.crystalgraphics.gl.buffer.shader.CgShaderBufferRegistry;
import com.crystalgraphics.gl.framebuffer.CgFrameBuffer;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.texture.CgTexture;
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
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
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
 *   <li>A compute pass needs a context that runs compute shaders, and throws naming the tier where it does not.</li>
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
    private static final CgBufferPool BUFFERS = new CgBufferPool();
    private static final CgHazards HAZARDS = new CgHazards();
    private static final IntConsumer FORGET_BUFFER = buffer -> HAZARDS.forget(CgHazards.buffer(buffer));
    /** Persistent and history buffers whose storage this executor made, freed at teardown if never released. */
    private static final List<CgGraphBuffer> KEPT = new ArrayList<>();
    private static final boolean BARRIERS = !"false".equalsIgnoreCase(System.getProperty("crystalgraphics.graph.barriers"));
    private static final int BARRIER_COUNT = CgTrace.name("graph.barriers");
    private static final int DISPATCH_COUNT = CgTrace.name("graph.dispatches");
    /**
     * Set by the first frame with a kernel or a buffer operation. Until then nothing can race what a draw does but a
     * kernel's later write, which the tracked backend's graphics-to-compute wait already orders; so a process that
     * never runs one keeps no accesses at all.
     */
    private static boolean kernelsSeen;
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
        for (CgExecutor executor : BY_DEPTH) executor.ring.delete();
        BY_DEPTH.clear();
        POOL.delete();
        BUFFERS.delete();
        for (CgGraphBuffer buffer : KEPT) freeKept(buffer);
        KEPT.clear();
        HAZARDS.clear();
    }

    private void run(CgFrame frame) {
        boolean again = frame.executions++ > 0;
        kernelsSeen |= frame.kernels;
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
            if (resolved > 0) {
                for (CgGraphResource transientResource : frame.transients) {
                    if (isResolved(transientResource)) giveBack(transientResource);
                }
            }
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
                || pass instanceof CgPass.BufferRelease) return true;
        if (pass instanceof CgPass.Fill || pass instanceof CgPass.Update || pass instanceof CgPass.BufferCopy) {
            return frame.outlives[s];
        }
        if (pass instanceof CgComputePass compute) return frame.outlives[s] && !compute.runsAgain();
        return keepRequested && pass.target != null && pass.target.kind() == CgGraphTexture.Kind.REQUESTED;
    }

    private void step(CgFrame frame, int s) {
        CgPass pass = frame.steps[s];
        try {
            if (!(pass instanceof CgComputePass)) barriers(frame, s);
            if (pass instanceof CgRasterPass raster) {
                raster(frame, raster, frame.rasters[s]);
            } else if (pass instanceof CgComputePass compute) {
                compute(frame, compute, frame.computes[s]);
            } else if (pass instanceof CgPass.Fill fill) {
                CgGL.cgFillBuffer(bufferStorage(fill.buffer, true), fill.offset, fill.size, fill.value);
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
                written(copy.to);
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

    // ── Compute ──────────────────────────────────────────────────────────────

    private void compute(CgFrame frame, CgComputePass pass, CgFrame.Compute packed) {
        CgCapabilities caps = CgCapabilities.detect();
        if (!caps.compute()) {
            throw new IllegalStateException(pass + ": this context runs no compute shaders; it runs kernels at tier "
                    + caps.computeTier() + ", lowered, which gpu-compute C5 brings");
        }
        List<CgDispatch> dispatches = pass.dispatches();
        for (int d = 0; d < dispatches.size(); d++) dispatch(frame, dispatches.get(d), packed.bindings[d]);
        CgTrace.add(CgChannels.GL, DISPATCH_COUNT, dispatches.size());
    }

    private void dispatch(CgFrame frame, CgDispatch d, int bindings) {
        CgKernelProgram program = d.kernel.program();
        program.use();
        frame.bindings.bind(bindings);
        for (String token : d.source.engineBuffers()) CgEngineBufferRegistry.get(token).buffer().get().bind();
        for (int b = 0; b < d.buffers.length; b++) {
            if (d.buffers[b] != null) bindStorage(b, d.buffers[b], d.offsets[b], d.sizes[b], d.bufferAccess[b]);
            if (d.counters[b] != null) bindStorage(program.counterPoint(b), d.counters[b], d.counterOffsets[b], 4, d.counterAccess[b]);
        }
        for (int i = 0; i < d.images.length; i++) {
            CgGraphTexture texture = d.images[i];
            if (texture == null) continue;
            CgImageDecl image = d.source.images().get(i);
            int id = storage(texture).getColorTexture(0).getId();
            if (d.imageAccess[i] != 0) barrier(false, id, d.imageAccess[i]);
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

    private ByteBuffer updateScratch;

    private void update(CgPass.Update update) {
        int id = bufferStorage(update.buffer, true);
        if (updateScratch == null || updateScratch.capacity() < update.bytes.length) {
            updateScratch = ByteBuffer.allocateDirect(Math.max(update.bytes.length, 4096));
        }
        updateScratch.clear();
        updateScratch.put(update.bytes);
        ((Buffer) updateScratch).flip();
        CgGL.glBindBuffer(CgGL.GL_COPY_WRITE_BUFFER, id);
        CgGL.glBufferSubData(CgGL.GL_COPY_WRITE_BUFFER, update.offset, updateScratch);
        CgGL.glBindBuffer(CgGL.GL_COPY_WRITE_BUFFER, 0);
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
    private static void freeKept(CgGraphBuffer buffer) {
        if (buffer.kind() == CgGraphBuffer.Kind.HISTORY) {
            int[] versions = buffer.versions();
            for (int v = 0; v < 2; v++) {
                if (versions[v] == 0) continue;
                CgGL.glDeleteBuffers(versions[v]);
                HAZARDS.forget(CgHazards.buffer(versions[v]));
                versions[v] = 0;
            }
        } else if (buffer.bufferId() != 0) {
            CgGL.glDeleteBuffers(buffer.bufferId());
            HAZARDS.forget(CgHazards.buffer(buffer.bufferId()));
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
        if (from == 0 || !BARRIERS) return;
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
