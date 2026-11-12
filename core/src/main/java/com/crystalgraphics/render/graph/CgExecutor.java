package com.crystalgraphics.render.graph;

import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.gpu.CgDeferral;
import com.crystalgraphics.gpu.resource.CgStreamBuffer;
import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.platform.gl.CgCapabilities.ComputeTier;
import com.crystalgraphics.platform.rhi.CgExternalTarget;
import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.platform.rhi.resource.CgGpuTexture;
import com.crystalgraphics.platform.rhi.shader.CgGlslCompiler;
import com.crystalgraphics.render.CgCpuMeter;
import com.crystalgraphics.render.CgGpuBudget;
import com.crystalgraphics.render.draw.CgBoundResources;
import com.crystalgraphics.render.draw.CgDeviceSets;
import com.crystalgraphics.render.draw.CgPipeline;
import com.crystalgraphics.render.mesh.CgMeshStore;
import com.crystalgraphics.trace.CgGpuTrace;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;
import com.crystalgraphics.variable.CgVar;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs a {@link CgFrame} on the render thread: uploads its snapshots and each kind's instances once, compiles what it
 * decides ({@link CgFrameCompiler}), then replays its steps in order — transients taken from the pool at their first
 * use and returned after their last, each step's transitions issued, its body encoded, requests completed or failed.
 *
 * <pre>{@code
 * CgFrame frame = builder.build(graph);   // any thread
 * CgExecutor.execute(frame);              // render thread, inside the host's section
 * builder.recycle(frame);
 * }</pre>
 *
 * <ul>
 *   <li>Render thread only, and inside a frame: the uploads are on the frame ring.</li>
 *   <li>A pass that throws fails its request and the frame goes on; one with no request rethrows.</li>
 *   <li>Re-entrant: a callback pass that executes a frame of its own gets an execution of its own, with its own ring
 *       and instance buffers, so the outer frame's are still bound when it returns.</li>
 *   <li>Transitions come from each storage's state as executed, across frames and executions
 *       ({@code CgResourceStates}): on a device every access's, images with their layouts, split where the next access
 *       is steps later. A texture outliving the execution is taken from where it rests and returned there at its end.
 *       {@code -Dcrystalgraphics.graph.barriers=false} records them and issues none, which synchronization validation
 *       must then report. A compute pass barriers each storage's first access before its first dispatch, and
 *       {@link CgDispatch#skipBarrier} accesses of one group of a pass wait for nothing between them.</li>
 *   <li>A compute pass needs a context that runs compute shaders, and throws naming the tier where it does not; so
 *       does a raster pass holding an indirect draw, whose command a kernel writes before the pass begins.</li>
 *   <li>An {@code async()} compute pass goes to the device's compute queue, where it has one; the first later step
 *       touching its storage waits for it, in this execution or a later one of the frame. An execution ends waiting
 *       only for its async work on imported, current or requested storage, which the host may touch.
 *       {@code -Dcrystalgraphics.graph.asyncAll=true} sends every pass that can go.</li>
 * </ul>
 */
public final class CgExecutor {

    private static final Logger LOGGER = LogManager.getLogger("CgExecutor");
    private static final int TARGET_COPIES = CgTrace.name("graph.target-copies");
    private static final int DEPTH_SHARED = CgTrace.name("graph.depth-copies-shared");
    /** Raster passes that joined the render pass of the one before them (Unreal RDG's {@code MergeRenderPasses}). */
    private static final int MERGED = CgTrace.name("graph.render-passes-merged");
    /** Another target's depth sampled where it is, where it was copied. */
    private static final int DEPTH_SAMPLED = CgTrace.name("graph.depth-sampled-in-place");
    /** What every QUAD and CURVE instance expands. */
    static final CgMesh UNIT_QUAD = CgMesh.quads(1);
    private static final CgVar.Bool SPLIT = CgVar.bool("crystalgraphics.graph.splits", true, CgVar.DEV,
            "Split a transition whose next access is a read two or more steps on, from its producer's end to its consumer;"
                    + " false records every transition whole before its consumer.");
    private static final CgVar.Bool BARRIERS = CgVar.bool("crystalgraphics.graph.barriers", true, CgVar.DEV,
            "Barriers where a kernel takes part; false keeps the bookkeeping and issues none: what synchronization validation"
                    + " must catch on --mode=compute-graph.");
    static final boolean ASYNC_ALL = CgVar.bool("crystalgraphics.graph.asyncAll", false, CgVar.DEV | CgVar.RESTART,
            "Every compute pass that can go async does, as if marked async(): a check of the waits, which the compute"
                    + " scenes must pass under synchronization validation.").get();
    /** The GPU's copies before a frame's first pass: what other threads asked of GPU objects, and the meshes' bytes. */
    private static final int GPU_DEFERRED = CgGpuTrace.name("upload.deferred"), GPU_MESHES = CgGpuTrace.name("upload.meshes");
    private static final int ACQUIRE = CgTrace.name("graph.acquire");
    // The rest of an execution's body, so a driver wait in any of it is named (T10.5).
    private static final int UPLOADS = CgTrace.name("graph.uploads"), GPU_TIMER = CgTrace.name("graph.gpuTimer"),
            AWAIT_ASYNC = CgTrace.name("graph.awaitAsync"), BARRIER = CgTrace.name("graph.barrier"),
            STEP_READBACK = CgTrace.name("graph.readback"), STEP_COPY = CgTrace.name("graph.copy"),
            STEP_UPLOAD = CgTrace.name("graph.upload"), STEP_CALLBACK = CgTrace.name("graph.callback"),
            STEP_INCLUDE = CgTrace.name("graph.include"), STEP_BUFFER = CgTrace.name("graph.bufferStep"),
            STEP_COMPILE = CgTrace.name("graph.compile"), STEP_RELEASE = CgTrace.name("graph.release"),
            STALE = CgTrace.name("graph.stale"), GIVE_BACK = CgTrace.name("graph.giveBack"),
            FINISH = CgTrace.name("graph.finish");

    // ── One execution ────────────────────────────────────────────────────────

    private final CgGraphDevice device;
    private final CgStreamBuffer ring;
    final CgDeviceSets deviceSets = new CgDeviceSets();
    final CgTransitions transitions;
    private final CgRasterEncoder raster;
    private final CgComputeEncoder compute;
    private final CgCopyEncoder copies;
    /**
     * This execution's: whether the backend takes every transition (a device's), whether a barrier is issued, whether
     * a draw takes a GPU count, whether an async pass runs beside the frame's queue, and whether runs of draws join.
     */
    boolean takes, computeBarriers, gpuCounts, multiDraw;
    private boolean asyncCompute;
    /** The target {@link #into} opened round this execution, as storage; null where none is open. */
    @Nullable
    private CgGraphStorage start;
    /** The last step of the merged run the step executing belongs to; -1 outside one. */
    private int runEnd = -1;
    /** The run's second attachment, which a pass of it without one of its own draws beside, writes masked off. */
    @Nullable
    CgGraphTexture runAttachment;
    /**
     * One copy of a storage's depth, read by every pass after it that reads that depth until something may write it:
     * Unreal resolves scene depth once after the base pass, where each pass copying its own cost a blit apiece.
     * Released when the execution ends; its source null when it holds nothing. Keyed by the depth image and layer, not
     * the storage: two graph textures importing one target are two storages over the same images.
     */
    private final CgTargetCopy sharedDepth = new CgTargetCopy();
    @Nullable
    private CgGpuTexture sharedDepthSource;
    private int sharedDepthLayer;
    /** Transients a step skipped this execution wrote, which a step run again may not read. */
    private final List<CgGraphResource> stale = new ArrayList<>();
    private final List<CgPass> staleBy = new ArrayList<>();

    private CgExecutor(CgGraphDevice device, int depth) {
        this.device = device;
        // A region of 1 MB: a ring outgrowing its region is new storage, which waits for a threaded driver to drain.
        ring = CgStreamBuffer.frameLocalUniforms(1 << 20);
        transitions = new CgTransitions(this, device);
        raster = new CgRasterEncoder(this, device, depth);
        compute = new CgComputeEncoder(this, device);
        copies = new CgCopyEncoder(this, device);
    }

    // ── The public calls ─────────────────────────────────────────────────────

    /** Executes {@code frame}. Render thread, inside a frame. */
    public static void execute(CgFrame frame) {
        CgGraphDevice device = CgGraphDevice.get();
        if (!drawsOnDevice()) {
            refuse(device, frame);
            return;
        }
        // GPU-object work asked for with the frame, or before it, where no GL could run.
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, "graph.deferrals")) {
            boolean gpu = uploadGpuZones();
            if (gpu) CgGpuTrace.begin(GPU_DEFERRED);
            try {
                CgDeferral.applyAll();
            } finally {
                if (gpu) CgGpuTrace.end();
            }
            device.trim();
        }
        if (device.executions.size() == device.depth) device.executions.add(new CgExecutor(device, device.depth));
        CgExecutor execution = device.executions.get(device.depth);
        device.depth++;
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, "graph.execute")) {
            execution.run(frame);
        } finally {
            device.depth--;
        }
    }

    /**
     * Executes {@code frame} again, after {@link #execute(CgFrame)}: what its passes read as it stands now -- property
     * values, a texture -- with its uploads, compiles and releases not repeated. With {@code keepRequested}, every pass
     * that writes a requested texture is skipped too, leaving it as the last execution did; without it, a pass
     * {@linkplain CgRasterPass#damage limited to its damage} draws whole, since the values may move what it drew. Render
     * thread, inside a frame.
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
            execute(frame);
        } finally {
            frame.keepRequested = false;
            frame.wholePasses = false;
        }
    }

    /**
     * Makes the executions of the first {@code depths} nesting depths now. Each maps a ring and an indirect-args table,
     * and a map waits for a threaded driver's queue, deepest while loading: an immediate draw's first nested execution
     * held the loading screen 117 ms (1.20.1). At the engine's start, after {@code CgBindingPoints.init}. Render thread.
     */
    public static void ahead(int depths) {
        CgGraphDevice device = CgGraphDevice.get();
        while (device.executions.size() < depths) device.executions.add(new CgExecutor(device, device.executions.size()));
    }

    /**
     * Whether an execution starting now draws, through {@code CgRHI}: false on a host whose context no device takes
     * (below GL 4.5), which draws nothing of ours.
     */
    public static boolean drawsOnDevice() {
        return CgRHI.installed() && CgGlslCompiler.installed();
    }

    /** No device: the frame's requests fail, and the host is told once. */
    private static void refuse(CgGraphDevice device, CgFrame frame) {
        if (!device.refusedLogged) {
            device.refusedLogged = true;
            LOGGER.error("no device is installed: the frame graph draws nothing on this host");
        }
        for (int s = 0; s < frame.stepCount; s++) {
            CgPass pass = frame.steps[s];
            if (pass.request != null) pass.request.fail("no device is installed");
        }
    }

    /**
     * Makes {@code target} the current target of every execution until it closes ({@link CgGraphTexture#current()}): a
     * host's framebuffer where its hook draws, a target of ours a stage is fired into, a loading view's. Nothing is
     * bound. Render thread.
     *
     * <pre>{@code
     * try (CgExecutor.Into ignored = CgExecutor.into(CgExternalTarget.host(mainFbo, width, height))) {
     *     stage.fire();
     * }
     * try (CgExecutor.Into ignored = CgExecutor.into(host.colorOnly())) {   // a GUI over the finished frame
     *     CgRenderStage.HUD.fire();
     * }
     * }</pre>
     *
     * <ul>
     *   <li>They nest; close them in reverse, through try-with-resources: one is reused once closed, so never keep it.</li>
     *   <li>An execution drawing into the current target with none open throws.</li>
     * </ul>
     */
    public static Into into(CgExternalTarget target) {
        CgGraphDevice device = CgGraphDevice.get();
        if (device.intoDepth == device.into.length) {
            throw new IllegalStateException("current targets nest deeper than " + device.into.length);
        }
        Into into = device.into[device.intoDepth];
        if (into == null) into = device.into[device.intoDepth] = new Into(device, device.intoDepth);
        into.open(target);
        device.intoDepth++;
        device.current = into;
        return into;
    }

    /** Whether a current target is open: a host's hook names one before anything draws. */
    public static boolean hasCurrentTarget() {
        return CgGraphDevice.get().current != null;
    }

    /** The current target {@link #into} opened; null where none is. */
    @Nullable
    public static CgExternalTarget currentTarget() {
        Into current = CgGraphDevice.get().current;
        return current == null ? null : current.target;
    }

    /** A current target {@link #into} named; closing it puts back the one before. */
    public static final class Into implements AutoCloseable {
        private final CgGraphDevice device;
        private final int level;
        @Nullable
        CgExternalTarget target;
        private boolean open;

        private Into(CgGraphDevice device, int level) {
            this.device = device;
            this.level = level;
        }

        private void open(CgExternalTarget target) {
            this.target = target;
            open = true;
        }

        @Override
        public void close() {
            if (!open) return;
            if (level != device.intoDepth - 1) throw new IllegalStateException("a current target closed out of order");
            open = false;
            target = null;
            device.intoDepth--;
            device.current = device.intoDepth == 0 ? null : device.into[device.intoDepth - 1];
        }
    }

    /** The host's targets are imported afresh: at a window resize, which may remake them. */
    public static void forgetHostTargets() {
        CgGraphDevice.get().forgetHostTargets();
        CgExternalTarget.forgetHosts();
    }

    /**
     * Frees every execution's ring, the transient pools and every persistent or history buffer's storage still held. At
     * context teardown, before the framebuffer sweep.
     */
    public static void destroyAll() {
        CgGraphDevice.get().destroy();
    }

    /**
     * Makes a persistent or history buffer's storage now rather than at its first use: what a precache calls so the
     * frame that first steps it allocates nothing. Render thread.
     *
     * <pre>{@code
     * CgGraphBuffer records = CgGraphBuffer.history("particles", CgBufferDesc.elements(n, 80, CgBufferUsage.STORAGE));
     * CgExecutor.makeStorage(records);   // at precache; its first pass finds it made
     * }</pre>
     */
    public static void makeStorage(CgGraphBuffer buffer) {
        CgGraphBuffer.Kind kind = buffer.resource().kind();
        if (kind != CgGraphBuffer.Kind.PERSISTENT && kind != CgGraphBuffer.Kind.HISTORY) {
            throw new IllegalArgumentException(buffer + " is " + kind + ": only a persistent or history buffer has storage ahead");
        }
        CgGraphDevice.get().bufferStorage(buffer, false);
    }

    /**
     * Frees storage {@link #makeStorage} made for a buffer no recorded pass has used yet, at once: a precache resizing
     * what it made. A buffer a pass has used goes through {@code CgRecording.release}, which waits for its passes.
     */
    public static void freeStorage(CgGraphBuffer buffer) {
        CgGraphDevice device = CgGraphDevice.get();
        if (device.keeps(buffer)) device.freeBuffer(buffer);
    }

    /**
     * Frees a requested or history texture's storage at once: teardown, where no frame will execute a recorded
     * release. Anything that still names it draws nothing from it. Render thread, between executions.
     *
     * <pre>{@code
     * for (CgGraphTexture kept : pictures) CgExecutor.freeStorage(kept);   // the context is going away
     * }</pre>
     */
    public static void freeStorage(CgGraphTexture texture) {
        if (texture.kind() != CgGraphTexture.Kind.REQUESTED && texture.kind() != CgGraphTexture.Kind.HISTORY) {
            throw new IllegalArgumentException(texture + " is " + texture.kind() + ": the graph frees it");
        }
        CgGraphDevice.get().freeTexture(texture);
    }

    /** Before a blit of {@code source} into {@code dest} inside an executing pass: the source read, the destination written. */
    static void blitting(CgGraphStorage source, CgGraphStorage dest) {
        CgExecutor execution = CgGraphDevice.get().executing();
        if (execution != null) execution.transitions.blitting(source, dest);
    }

    /** After it: the source where it was, the copy sampled. */
    static void blitted(CgGraphStorage source, CgGraphStorage dest) {
        CgExecutor execution = CgGraphDevice.get().executing();
        if (execution != null) execution.transitions.blitted(source, dest);
    }

    /** What {@code pass} draws into: its target, or the current target for none. */
    static CgGraphTexture targetOf(CgRasterPass pass) {
        return pass.target != null ? pass.target : CgGraphTexture.current();
    }

    // ── The execution ────────────────────────────────────────────────────────

    private void run(CgFrame frame) {
        boolean again = frame.executions++ > 0;
        device.kernelsSeen |= frame.kernels;
        CgBoundResources.recording = true;
        CgCapabilities caps = CgCapabilities.detect();
        ComputeTier tier = caps.computeTier();
        boolean computes = tier == ComputeTier.V || tier == ComputeTier.G43;
        computeBarriers = BARRIERS.get() && computes;
        gpuCounts = computes || tier == ComputeTier.G40 && caps.drawIndirect();
        asyncCompute = tier == ComputeTier.V && caps.asyncCompute();
        device.async.begin();
        compute.asyncEnd = 0;
        takes = CgRHI.installed() && CgRHI.get().takesTransitions();
        transitions.begin();
        runEnd = -1;
        multiDraw = caps.multiDraw() && CgMeshStore.get().multiDraw();
        // The current target: the one the host's hook or the engine opened (into), imported as storage
        if (device.current != null) {
            start = device.opened(device.current.target);
        } else if (frame.readsCurrentDepth || frame.rastersCurrent) {
            throw new IllegalStateException("an execution draws into the current target with none named: open"
                    + " CgExecutor.into round it");
        } else {
            start = null;
        }
        // A nested execution may open another: the outer one's is put back as it returns
        CgGraphStorage outer = CgGraphTexture.current().storage();
        CgGraphTexture.current().resolve(start);
        try {
            run(frame, again);
        } finally {
            CgGraphTexture.current().resolve(outer);
        }
    }

    private void run(CgFrame frame, boolean again) {
        boolean splits = takes && !again && SPLIT.get();
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, UPLOADS)) {
            frame.bindings.upload(ring);
            raster.upload(frame);
        }
        boolean gpu = uploadGpuZones();
        if (gpu) CgGpuTrace.begin(GPU_MESHES);
        try {
            placeMeshes(frame);
        } finally {
            if (gpu) CgGpuTrace.end();
        }
        CgFrameCompiler.renderPasses(frame, takes, takes && !again, gpuCounts);
        CgFrameCompiler.transitions(frame, asyncCompute, ASYNC_ALL, splits);
        CgFrameCompiler.multiDraws(frame, multiDraw);
        CgFrameCompiler.steps(frame, takes, again);
        int resolved = 0;
        try {
            if (!again) {
                compiles(frame);
                freeReleased(frame, -1);
            }
            for (int s = 0; s < frame.stepCount; s++) {
                for (int t = 0; t < frame.transients.size(); t++) {
                    if (frame.acquireAt[t] == s) {
                        acquire(frame.async[s], frame.transients.get(t));
                        resolved++;
                    }
                }
                CgPass pass = frame.steps[s];
                boolean skipped = frame.skipped[s];
                if (again) {
                    try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, STALE)) {
                        staleTransients(frame, s, skipped);
                    }
                }
                if (takes && !again && s > runEnd) enterRun(frame, s);
                // An included frame takes its storage from where it rests: no transition of ours left half done
                if (transitions.splitsOpen()) transitions.endSplits(pass instanceof CgPass.Include ? Integer.MAX_VALUE : runEnd);
                if (!skipped) step(frame, s);
                if (splits && !skipped && s >= runEnd) transitions.beginSplits(frame, s);
                // A window moved by its node executes no surface: what a re-execution drew into a kept texture.
                if (again && pass instanceof CgRasterPass && pass.target != null
                        && pass.target.kind() == CgGraphTexture.Kind.REQUESTED) {
                    CgTrace.add(CgChannels.GL, frame.keepRequested ? "graph.again.requested-kept"
                            : "graph.again.requested-drawn", 1);
                }
                for (int t = 0; t < frame.transients.size(); t++) {
                    if (frame.releaseAfter[t] == s) {
                        if (frame.transients.get(t) instanceof CgGraphTexture texture && sharesDepth(texture.storage())) {
                            sharedDepthSource = null;
                        }
                        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, GIVE_BACK)) {
                            device.giveBack(frame.transients.get(t));
                        }
                        resolved--;
                    }
                }
                if (!again) freeReleased(frame, s);
            }
        } finally {
            try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, FINISH)) {
                if (takes) transitions.finish();
                sharedDepth.release(device.pool);
                sharedDepthSource = null;
                device.async.await(compute.asyncEnd);   // what the host may touch; the rest waits for its reader, or the frame's end
                if (resolved > 0) {
                    for (CgGraphResource transientResource : frame.transients) {
                        if (isResolved(transientResource)) device.giveBack(transientResource);
                    }
                }
            }
        }
    }

    /** Enters the render pass run compiled for step {@code s} ({@link CgFrameCompiler#renderPasses}). */
    private void enterRun(CgFrame frame, int s) {
        runEnd = frame.runEnd[s];
        runAttachment = runEnd > s ? frame.runAttachment[s] : null;
        if (runEnd > s) CgTrace.add(CgChannels.GL, MERGED, runEnd - s);
    }

    /** Executing again: a compute or buffer step skipped leaves its transients unwritten, and one that reads them throws. */
    private void staleTransients(CgFrame frame, int s, boolean skipped) {
        if (s == 0) {
            stale.clear();
            staleBy.clear();
        }
        CgPass pass = frame.steps[s];
        boolean kernelOrBuffer = pass instanceof CgComputePass || pass instanceof CgPass.BufferFill
                || pass instanceof CgPass.BufferUpdate || pass instanceof CgPass.BufferCopy;
        for (int i = frame.accessFrom[s]; i < frame.accessFrom[s + 1]; i++) {
            CgGraphResource resource = frame.accessView[i];
            int bits = frame.accessBits[i];
            if (skipped) {
                if (kernelOrBuffer && resource.isTransient() && (bits & CgResourceStates.WRITES) != 0) {
                    stale.add(resource);
                    staleBy.add(pass);
                }
            } else if ((bits & ~CgResourceStates.WRITES) != 0) {
                int at = stale.indexOf(resource);
                if (at >= 0) {
                    throw new IllegalStateException(pass + " reads " + resource.name() + ", which " + staleBy.get(at)
                            + " writes; executing the frame again skips that pass, since it also writes what outlives "
                            + "the frame. Write " + resource.name() + " in a pass of its own, or mark it again()");
                }
            }
        }
    }

    /** {@code resource}'s storage, as its first user is about to run, on the compute queue where {@code async}. */
    private void acquire(boolean async, CgGraphResource resource) {
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, ACQUIRE)) {
            if (resource instanceof CgGraphTexture texture) {
                texture.resolve(device.takeStorage(texture.desc(), async));
            } else {
                CgGraphBuffer buffer = (CgGraphBuffer) resource;
                buffer.resolve(device.buffers.acquire(buffer.desc()));
            }
        }
    }

    private static boolean isResolved(CgGraphResource resource) {
        return resource instanceof CgGraphTexture texture ? texture.storage() != null
                : ((CgGraphBuffer) resource).bufferId() != 0;
    }

    /** On the detail channel: an enclosing GPU zone is timed around these, not through them. */
    private static boolean uploadGpuZones() {
        return CgTrace.isEnabled(CgChannels.GL_DETAIL) && CgGpuTrace.isMeasuring();
    }

    /** Every mesh the frame draws placed in the store, and what changed uploaded: before the first raster pass. */
    private static void placeMeshes(CgFrame frame) {
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, "graph.placeMeshes")) {
            CgMeshStore store = CgMeshStore.get();
            boolean unitQuad = false;
            for (int s = 0; s < frame.stepCount; s++) {
                CgFrame.Raster packed = frame.rasters[s];
                if (packed == null) continue;
                unitQuad |= (packed.kinds & CgRasterEncoder.UNIT_KINDS) != 0;
                for (int b = 0; b < packed.count; b++) {
                    if (packed.mesh[b] != null) store.place(packed.mesh[b]);
                }
            }
            if (unitQuad) store.place(UNIT_QUAD);
            store.upload();
        }
    }

    private void step(CgFrame frame, int s) {
        if (!CgGraphCheck.on()) {
            meteredStep(frame, s);
            return;
        }
        CgPass before = CgGraphCheck.allow(frame, s, 1, null);
        try {
            meteredStep(frame, s);
        } finally {
            CgGraphCheck.allow(frame, s, -1, before);
        }
    }

    private void meteredStep(CgFrame frame, int s) {
        CgPass pass = frame.steps[s];
        CgCpuMeter meter = pass.meter;
        if (meter == null) {
            timedStep(frame, s, pass);
            return;
        }
        long began = System.nanoTime();
        try {
            timedStep(frame, s, pass);
        } finally {
            meter.add(System.nanoTime() - began);
        }
    }

    private void timedStep(CgFrame frame, int s, CgPass pass) {
        CgGpuBudget budget = pass.budget;
        if (pass.gpuZone < 0 && budget == null) {
            run(frame, s, pass);
            return;
        }
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, GPU_TIMER)) {
            CgGpuTrace.begin(pass.gpuZone >= 0 ? pass.gpuZone : budget.zone(),
                    budget != null ? budget.slot() : CgGpuTrace.NO_BUDGET);
        }
        try {
            run(frame, s, pass);
        } finally {
            try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, GPU_TIMER)) {
                CgGpuTrace.end();
            }
        }
    }

    /** Step {@code s}: its waits, its prologue, its body through its encoder. */
    private void run(CgFrame frame, int s, CgPass pass) {
        try {
            boolean async = frame.async[s];
            if (device.async.unwaited && !async) {
                try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, AWAIT_ASYNC)) {
                    compute.awaitAsync(frame, s);
                }
            }
            // A history drawn into moves to its next version first: what the step's transitions and its reads name.
            CgGraphTexture drawn = pass instanceof CgRasterPass r ? r.target
                    : pass instanceof CgPass.Callback callback ? callback.target : null;
            if (drawn != null && drawn.kind() == CgGraphTexture.Kind.HISTORY) {
                if (drawn.isPreviousVersion()) throw new IllegalStateException(drawn + " is sampled only: draw into its history");
                device.storage(drawn);
                drawn.advance();
            }
            // An included frame states its own transitions, from where each storage rests
            if (!(pass instanceof CgComputePass) && !(pass instanceof CgPass.Include)) {
                try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, BARRIER)) {
                    transitions.prologue(frame, s);
                }
            }
            if (pass instanceof CgRasterPass r) {
                raster.raster(frame, s, r);
                if (frame.rasters[s].writesDepth && sharesDepth(depthDestination(r))) sharedDepthSource = null;
            } else if (pass instanceof CgComputePass c) {
                if (async) compute.computeAsync(frame, s, c);
                else compute.compute(frame, s, c);
                if (CgBufferInspector.watching()) compute.inspect(c);
            } else if (pass instanceof CgPass.BufferFill || pass instanceof CgPass.BufferUpdate || pass instanceof CgPass.BufferCopy) {
                try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, STEP_BUFFER)) {
                    copies.buffer(pass);
                }
            } else if (pass instanceof CgPass.Readback readback) {
                try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, STEP_READBACK)) {
                    copies.readback(readback);
                }
            } else if (pass instanceof CgPass.TextureCopy copy) {
                try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, STEP_COPY)) {
                    copies.texture(copy);
                }
            } else if (pass instanceof CgPass.TextureUpload upload) {
                try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, STEP_UPLOAD)) {
                    copies.upload(upload);
                }
                upload.request.complete();
            } else if (pass instanceof CgPass.Include include) {
                sharedDepthSource = null;   // what it executes may write any depth
                try (CgTrace.Zone zone = CgTrace.zone(CgChannels.GL, STEP_INCLUDE)) {
                    include.execute();
                }
            } else if (pass instanceof CgPass.Callback callback) {
                sharedDepthSource = null;   // what it executes may draw anything
                try (CgTrace.Zone zone = CgTrace.zone(CgChannels.GL, STEP_CALLBACK);
                     Into ignored = intoCallback(callback.target, callback.depth)) {
                    callback.body.run();
                }
                callback.request.complete();
            }
        } catch (RuntimeException failure) {
            if (pass.request == null) throw failure;
            LOGGER.warn("{} failed", pass, failure);
            pass.request.fail(failure.getMessage() == null ? failure.toString() : failure.getMessage());
        }
    }

    /** The frame's compiles, before its steps: each done once its program is ready, failed with the driver's reason. */
    private static void compiles(CgFrame frame) {
        for (int i = 0; i < frame.compiles.size(); i++) {
            CgPipeline pipeline = frame.compiles.get(i);
            CgRequest request = frame.compileRequests.get(i);
            try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, STEP_COMPILE)) {
                if (pipeline.precache()) {
                    if (pipeline.program() != null) request.complete();
                    else request.fail(String.valueOf(pipeline.shader().lastCompileError()));
                }
            } catch (RuntimeException failure) {
                LOGGER.warn("compile {} failed", pipeline, failure);
                request.fail(failure.getMessage() == null ? failure.toString() : failure.getMessage());
            }
        }
    }

    /** Frees what the frame released whose last use is step {@code s} (-1: before the first). */
    private void freeReleased(CgFrame frame, int s) {
        for (int i = 0; i < frame.released.size(); i++) {
            if (frame.releasedAfter[i] != s) continue;
            try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, STEP_RELEASE)) {
                if (frame.released.get(i) instanceof CgGraphBuffer buffer) {
                    device.freeBuffer(buffer);
                } else {
                    sharedDepthSource = null;
                    device.freeTexture((CgGraphTexture) frame.released.get(i));
                }
            }
        }
    }

    // ── Depth read from another texture ──────────────────────────────────────

    /** Binds the depth of the texture a pass reads besides its own: sampled in place where compiled so, else copied whole. */
    void depthFrom(CgPass pass, CgGraphTexture depth, CgTargetCopy copy, int unit, boolean inPlace) {
        if (inPlace) {
            // Not attached here, and moved to SAMPLED_READ with the step: sampled where it is, as Unreal samples SceneDepth.
            CgBoundResources.texture(unit, device.storage(depth).sampledDepth(), -1);
            CgTrace.add(CgChannels.GL, DEPTH_SAMPLED, 1);
            return;
        }
        copy.borrowDepth(sharedDepth(device.storage(depth), pass));
        CgBoundResources.texture(unit, copy.depth, -1);
    }

    /** The storage whose depth {@code pass} draws into; null where it draws with none. */
    @Nullable
    private CgGraphStorage depthDestination(CgRasterPass pass) {
        CgGraphStorage storage = device.storage(pass.depthTexture());
        return storage.depth() != null ? storage : null;
    }

    /** Whether {@link #sharedDepth} holds a copy of {@code storage}'s depth. */
    private boolean sharesDepth(@Nullable CgGraphStorage storage) {
        return storage != null && sharedDepthSource != null && storage.depth() == sharedDepthSource
                && storage.depthLayer() == sharedDepthLayer;
    }

    /** The execution's copy of {@code source}'s depth, copied now unless it already holds it. */
    CgTargetCopy sharedDepth(CgGraphStorage source, CgPass pass) {
        if (sharesDepth(source)) {
            CgTrace.add(CgChannels.GL, DEPTH_SHARED, 1);
            return sharedDepth;
        }
        sharedDepth.copyDepth(source, pass, device.pool);
        sharedDepthSource = source.depth();
        sharedDepthLayer = source.depthLayer();
        CgTrace.add(CgChannels.GL, TARGET_COPIES, 1);
        return sharedDepth;
    }

    /**
     * The current target what a callback executes takes ({@link #into}): its target's storage, with the depth it draws
     * with; null, the current target as it is, for none.
     */
    @Nullable
    private Into intoCallback(@Nullable CgGraphTexture target, @Nullable CgGraphTexture depth) {
        if (target == null || target.isCurrent()) return null;
        return into(device.storage(target).asTarget(depth != null ? device.storage(depth) : null));
    }

    /** Its buffers freed: at teardown. */
    void delete() {
        deviceSets.release();
        ring.delete();
        raster.delete();
        copies.delete();
    }
}
