package com.crystalgraphics.render.graph;

import com.crystalgraphics.util.CgBufferUtils;
import com.crystalgraphics.platform.rhi.CgExternalTarget;
import java.util.WeakHashMap;
import com.crystalgraphics.platform.rhi.CgDeviceNames;
import com.crystalgraphics.lifecycle.CgGraphicsLifecycle;
import com.crystalgraphics.platform.rhi.memory.CgTransientMemory;
import com.crystalgraphics.platform.rhi.resource.CgTextureRegion;
import com.crystalgraphics.platform.rhi.resource.CgGpuSampler;
import com.crystalgraphics.platform.rhi.resource.CgGpuBuffer;
import com.crystalgraphics.platform.rhi.resource.CgGpuTexture;
import com.crystalgraphics.api.state.CgRenderState;
import com.crystalgraphics.render.draw.CgDrawStateCache;
import com.crystalgraphics.platform.rhi.shader.CgGlslCompiler;
import com.crystalgraphics.platform.rhi.resource.CgTextureView;
import com.crystalgraphics.platform.rhi.pipeline.CgGraphicsPipeline;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.platform.rhi.command.CgRenderPass;
import com.crystalgraphics.platform.rhi.command.CgPassDesc;
import com.crystalgraphics.platform.rhi.CgDeviceSection;
import java.util.Set;
import java.util.HashSet;
import com.crystalgraphics.material.CgShader;
import com.crystalgraphics.platform.rhi.pipeline.CgPipelineLayout;
import com.crystalgraphics.platform.rhi.binding.CgBindingLayout;
import com.crystalgraphics.platform.rhi.binding.CgBindingSet;
import com.crystalgraphics.platform.rhi.binding.CgBindings;
import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.platform.rhi.pipeline.CgComputePipeline;
import com.crystalgraphics.platform.rhi.command.CgCommandList;
import com.crystalgraphics.render.shader.CgBindingModel;
import com.crystalgraphics.render.draw.CgBindingTable;
import com.crystalgraphics.render.draw.CgDeviceStates;
import com.crystalgraphics.render.draw.CgDeviceSets;
import com.crystalgraphics.render.draw.CgBoundResources;
import com.crystalgraphics.variable.CgVar;
import com.crystalgraphics.api.CgBindingPoints;
import com.crystalgraphics.render.draw.CgDrawChunk;
import com.crystalgraphics.gpu.resource.CgBufferLifetime;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.state.CgStencilState;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.compute.emit.CgKernelTarget;
import com.crystalgraphics.compute.program.CgComputeCheck;
import com.crystalgraphics.compute.program.CgKernelProgram;
import com.crystalgraphics.compute.source.CgImageDecl;
import com.crystalgraphics.compute.source.CgImageDimension;
import com.crystalgraphics.gpu.resource.CgReadback;
import com.crystalgraphics.gpu.resource.CgFrameRing;
import com.crystalgraphics.gpu.resource.CgStreamBuffer;
import com.crystalgraphics.gpu.resource.CgEngineBufferRegistry;
import com.crystalgraphics.gpu.resource.CgShaderBuffer;
import com.crystalgraphics.gpu.resource.CgShaderBufferRegistry;
import com.crystalgraphics.gpu.CgDeferral;
import com.crystalgraphics.platform.rhi.command.CgAccess;
import com.crystalgraphics.platform.gl.CgCapabilities.ComputeTier;
import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.render.CgCpuMeter;
import com.crystalgraphics.render.CgGpuBudget;
import com.crystalgraphics.render.draw.CgBufferHandle;
import com.crystalgraphics.render.draw.CgIndirect;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.draw.CgPassConstants;
import com.crystalgraphics.render.draw.CgPipeline;
import com.crystalgraphics.render.draw.CgPrimitiveTable;
import com.crystalgraphics.render.mesh.CgMeshStore;
import com.crystalgraphics.render.property.CgPalette;
import com.crystalgraphics.trace.CgGpuTrace;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import com.crystalgraphics.gpu.resource.CgTextureDesc;

/**
 * Runs a {@link CgFrame} on the render thread: uploads its snapshots and each kind's instances once, then executes its
 * passes in order — transients taken from the pool and returned after their last use, every access's transition
 * stated, requests completed or failed. GL state is restored after.
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
 *   <li>Transitions come from each storage's state as executed, across frames and executions
 *       ({@code CgResourceStates}): on a device every access's, images with their layouts, split where the next access
 *       is steps later; on GL only those a kernel takes part in, GL ordering the rest. A texture outliving the
 *       execution is taken from where it rests and returned there at its end. {@code -Dcrystalgraphics.graph.barriers=false}
 *       records them and issues none, which synchronization validation must then report. A compute pass barriers each
 *       storage's first access before its first dispatch, and {@link CgDispatch#skipBarrier} accesses of one group of a
 *       pass wait for nothing between them.</li>
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
    private static final int TARGET_COPY_PIXELS = CgTrace.name("graph.target-copy-pixels");
    private static final int DEPTH_SHARED = CgTrace.name("graph.depth-copies-shared");
    private static final int TARGET_RENAMES = CgTrace.name("graph.target-renames");

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
    private static final int KINDS = CgInstanceKind.values().length;
    private static final int UNIT_KINDS = (1 << CgInstanceKind.QUAD.ordinal()) | (1 << CgInstanceKind.CURVE.ordinal());
    private static final int OBJECT = CgInstanceKind.OBJECT.ordinal();
    /** Records written to the frame's table, once each, and ids the passes copied to name them: SR1's two counts. */
    private static final int PRIMITIVES = CgTrace.name("graph.primitives"), PRIMITIVE_IDS = CgTrace.name("graph.primitive-ids");
    /** {@code i} at {@code i}: what a draw of its own records reads them through, grown as one reaches further. */
    private static float[] identity = new float[0];
    /** What every QUAD and CURVE instance expands. */
    private static final CgMesh UNIT_QUAD = CgMesh.quads(1);

    private static final List<CgExecutor> BY_DEPTH = new ArrayList<>();
    private static final CgTexturePool POOL = new CgTexturePool();
    /** Transients placed in the device's transient heaps, made with the first frame on a device that places; else null. */
    private static CgTransientTextures transients;
    private static final boolean ALIASING = CgVar.bool("crystalgraphics.graph.aliasing", true, CgVar.DEV | CgVar.RESTART,
            "Transient 2D textures share memory where the device can place them (Vulkan); false pools them by description,"
                    + " as GL does: Unreal's r.RDG.TransientAllocator 0, for comparing the two.").get();
    /** KiB of 2D transient textures held: heaps where placed, the pooled framebuffers' images where not. */
    private static final int TRANSIENT_KB = CgTrace.name("graph.transient-kb");
    /** The texture pool's framebuffers and their bytes, on every backend: transient-kb is the device heap's alone. */
    private static final int POOL_FRAMEBUFFERS = CgTrace.name("graph.pool-framebuffers"),
            POOL_KB = CgTrace.name("graph.pool-kb");
    /** KiB of placed transient textures alive at once at the last frame's most. */
    private static final int TRANSIENT_PEAK_KB = CgTrace.name("graph.transient-peak-kb");
    private static final CgBufferPool BUFFERS = new CgBufferPool();
    private static final CgResourceStates STATES = new CgResourceStates();
    /**
     * The compute pass executing, numbered: its overlap sets, one a skip group ({@link CgDispatch#skipBarrier}), and
     * its claims on storage ({@link #prologue}).
     */
    private static int passSerial;
    private static final IntConsumer FORGET_BUFFER = CgExecutor::forget;
    private static final Consumer<CgGraphStorage> FORGET_STORAGE = CgExecutor::forgetStorage;
    private static final int COMPUTE = CgAccess.COMPUTE_READ | CgAccess.COMPUTE_WRITE;
    /**
     * This execution's: whether the backend takes every transition, a device's; GL takes only a kernel's. While it
     * does, the device infers none of what the graph records ({@code cgGraphStates}).
     */
    private static boolean takes;
    private static final int SPLITS = CgTrace.name("graph.split-transitions");
    private static final CgVar.Bool SPLIT = CgVar.bool("crystalgraphics.graph.splits", true, CgVar.DEV,
            "Split a transition whose next access is a read two or more steps on, from its producer's end to its consumer;"
                    + " false records every transition whole before its consumer.");
    /** Raster passes that joined the render pass of the one before them (Unreal RDG's {@code MergeRenderPasses}). */
    private static final int MERGED = CgTrace.name("graph.render-passes-merged");
    /** Raster passes that sampled their target's depth in place, read-only, where they would have copied it. */
    private static final int READ_ONLY_DEPTH = CgTrace.name("graph.depth-read-only");
    /** Another target's depth sampled where it is, where it was copied. */
    private static final int DEPTH_SAMPLED = CgTrace.name("graph.depth-sampled-in-place");
    /** Persistent and history buffers whose storage this executor made, freed at teardown if never released. */
    private static final List<CgGraphBuffer> KEPT = new ArrayList<>();
    private static final CgVar.Bool BARRIERS = CgVar.bool("crystalgraphics.graph.barriers", true, CgVar.DEV,
            "Barriers where a kernel takes part; false keeps the bookkeeping and issues none: what synchronization validation"
                    + " must catch on --mode=compute-graph.");
    private static final int BARRIER_COUNT = CgTrace.name("graph.barriers");
    private static final int DISPATCH_COUNT = CgTrace.name("graph.dispatches");
    /** Each compute pass's GPU zone by its name, interned once: a pass is recorded anew each frame. */
    private static final Map<String, Integer> GPU_ZONES = new HashMap<>();
    /** By pipeline id, its GPU group's label plus one: its material's path. */
    private static int[] groupLabels = new int[64];
    private static final int DEPTH_FROM_GROUP = CgGpuTrace.label("(depth copied in)");
    private static final int COMMAND_COUNT = CgTrace.name("graph.indirect-commands");
    /** Runs of draws a multi-draw ended only because the next draw binds other textures or properties. */
    private static final int BINDING_BREAKS = CgTrace.name("graph.multi-draw.binding-breaks");
    private static final int DEVICE_PASSES = CgTrace.name("graph.device-passes"),
            MID_PASS_SPLITS = CgTrace.name("graph.device-pass-splits");
    private final CgDrawStateCache drawCache = new CgDrawStateCache();
    /** What was logged once: a pipeline skipped, a second attachment dropped. */
    private final Set<String> loggedOnce = new HashSet<>();
    /** Each device pass's description, kept while its attachments and loads are the same. */
    private static final CgPassDescs PASS_DESCS = new CgPassDescs();
    private final List<CgFormat> deviceFormats = new ArrayList<>(4);
    private final CgDeviceSets deviceSets = new CgDeviceSets();
    private final Set<String> deviceSetsLogged = new HashSet<>();
    /** The device compute pass between dispatches. */
    private static final CgDeviceCompute DEVICE_COMPUTE = new CgDeviceCompute();

    /** Every transition the executor states on a device run, on the device's command list. */
    private static final CgGraphBarriers TRANSITIONS = new CgGraphBarriers();
    /** Whether a compute pass dispatches on the device now, and whether a barrier batch is open in it. */
    private static boolean deviceComputing, deviceBarrierBatch;
    /** A dispatch's kernel slots its snapshot does not hold: buffers, counts, images, checked mode's report. */
    private final CgBindings kernelBindings = new CgBindings();
    private static final int DEVICE_COMPUTES = CgTrace.name("graph.device-computes");

    static final boolean ASYNC_ALL = CgVar.bool("crystalgraphics.graph.asyncAll", false, CgVar.DEV | CgVar.RESTART,
            "Every compute pass that can go async does, as if marked async(): a check of the waits, which the compute"
                    + " scenes must pass under synchronization validation.").get();
    private static final int ASYNC_PASSES = CgTrace.name("graph.async-passes");
    /** The GPU's copies before a frame's first pass: what other threads asked of GPU objects, and the meshes' bytes. */
    private static final int GPU_DEFERRED = CgGpuTrace.name("upload.deferred"), GPU_MESHES = CgGpuTrace.name("upload.meshes");
    private static final int TRIM_POOLS = CgTrace.name("graph.trimPools");
    private static final int ASYNC_WAITS = CgTrace.name("graph.async-waits");
    private static final int ACQUIRE = CgTrace.name("graph.acquire"), MAKE_STORAGE = CgTrace.name("graph.makeStorage"),
            WRITE_COMMANDS = CgTrace.name("graph.writeCommands"),
            STORAGE_MADE = CgTrace.name("graph.storage-made-bytes"),
            BUFFER_STEP = CgTrace.name("graph.bufferStep"),
            FILL = CgTrace.name("graph.fill"), UPDATE_STAGE = CgTrace.name("graph.update.stage"),
            UPDATE_COPY = CgTrace.name("graph.update.copy");
    // The rest of an execution's body, so a driver wait in any of it is named (T10.5).
    private static final int UPLOADS = CgTrace.name("graph.uploads"), GPU_TIMER = CgTrace.name("graph.gpuTimer"),
            AWAIT_ASYNC = CgTrace.name("graph.awaitAsync"),
            BARRIER = CgTrace.name("graph.barrier"),
            STEP_READBACK = CgTrace.name("graph.readback"), STEP_COPY = CgTrace.name("graph.copy"),
            STEP_UPLOAD = CgTrace.name("graph.upload"), STEP_CALLBACK = CgTrace.name("graph.callback"),
            STEP_INCLUDE = CgTrace.name("graph.include"),
            STEP_COMPILE = CgTrace.name("graph.compile"), STEP_RELEASE = CgTrace.name("graph.release"),
            STALE = CgTrace.name("graph.stale"), GIVE_BACK = CgTrace.name("graph.giveBack"),
            FINISH = CgTrace.name("graph.finish");
    /**
     * Set by the first frame with a kernel or a buffer operation. Until then nothing can race what a draw does but a
     * kernel's later write, which the device's graphics-to-compute wait already orders; so a process that
     * never runs one keeps no accesses at all.
     */
    private static boolean kernelsSeen;
    /** This execution's: whether a barrier is issued (kernels run as compute), and whether a draw takes a GPU count. */
    private static boolean computeBarriers, gpuCounts;
    private static int depth;
    private static long trimmedFrame = -1;

    private final CgStreamBuffer ring;
    private final CgShaderBuffer[] instanceBuffers = new CgShaderBuffer[KINDS];
    /** The frame's object records at {@code OBJECT_DATA}, uploaded once; {@code instanceBuffers[OBJECT]} holds the ids. */
    private final CgShaderBuffer primitives;
    /** Identity ids at {@code OBJECT_IDS}, for a draw of its own records; kept across frames, {@link #identityIds} of them. */
    private final CgShaderBuffer identityBuffer;
    private int identityIds;
    /** Whether the frame executing has records in its table, which a pass of OBJECT draws binds. */
    private boolean tabled;
    private final int[] scissorRect = new int[4], scissorPart = new int[4];
    /** The engine buffers the last batch's buffer()s stood in for: the batch loop's, kept so it allocates nothing. */
    private final CgBindingPoints.Binding[] standIn = new CgBindingPoints.Binding[CgDrawChunk.BUFFERS];
    private final CgIndirectArgs commands = new CgIndirectArgs(this::dispatchCommands);
    /** The snapshot table and constants the pass's indirect commands are written under, for {@link #dispatchCommands}. */
    private CgBindingTable commandsTable;
    private int commandsConstants;
    /** Per file, what its dispatches below compute bind: lowered, or run by a Java body. */
    private final int[] range = new int[4];
    /** Per indirect batch starting a run of the pass about to draw, the run's last batch: itself when none joins it. */
    private int[] runs = new int[64];
    /** The target {@link #into} opened round this execution, as storage; null where none is open. */
    @Nullable
    private CgGraphStorage start;
    /** The last sizes a texture and the depth it draws with disagreed at, logged once. */
    private static long besideMismatch;
    /**
     * This execution's: whether an async pass runs beside the frame's queue, whether runs of draws join, and whether a
     * slot above 0 may be masked apart from slot 0 (without it a pipeline writes every slot, as one mask serves all).
     */
    private boolean asyncCompute, multiDraw, independentMasks;
    /**
     * Storage async passes touched that the frame's queue has not waited for, each with the point covering it: across
     * the frame's executions, and dropped at the next, whose start the device orders after all of it.
     */
    private static long[] asyncKeys = new long[64], asyncPoints = new long[64];
    /** {@link #asyncKeys} is open-addressed by {@link CgResourceStates} key, 0 a free entry (no key is 0); a pass names hundreds. */
    private static int asyncCount;
    private static long[] keptKeys = new long[64], keptPoints = new long[64];
    private static long asyncLatest, asyncWaited, asyncFrame = -1;
    private static boolean asyncUnwaited;
    /** This execution's: the newest async point covering storage outside the graph, waited for at its end. */
    private long asyncEnd;
    private final long[] keyScratch = new long[2];
    /**
     * Images it moved: key, storage or texture, levels, layers and whether it outlives the execution, each. Those that do
     * return to rest at its end; all of them before a callback's body, which finds every image at rest.
     */
    private long[] movedKeys = new long[16];
    private int[] movedLevels = new int[16], movedLayers = new int[16];
    /** Each moved storage, where it moved whole; else null. */
    private CgGraphStorage[] movedStorages = new CgGraphStorage[16];
    /** Each moved texture, where it moved by subresource; else null. */
    private CgGpuTexture[] movedImages = new CgGpuTexture[16];
    private boolean[] movedOutlives = new boolean[16];
    private int moved;
    /** Split transitions begun after a step and not yet ended: each one's id, and the step it ends before. */
    private int[] splitIds = new int[8], splitEnds = new int[8];
    private int splitCount;
    /** The run of merged raster steps the step executing belongs to: its first and last, -1 outside one. */
    private int runStart = -1, runEnd = -1;
    /** Whether the raster pass executing samples its target's depth in place ({@link #readOnlyDepth}); its step. */
    private boolean depthInPlace;
    private int currentStep;
    /** The run's second attachment, which a pass of it without one of its own draws beside, writes masked off. */
    @Nullable
    private CgGraphTexture runAttachment;
    /** Where a blit's source storage was before it ({@link #blitting}), returned there after. */
    private int blitSourceWas;

    private CgExecutor(int depth) {
        // A region of 1 MB: a ring outgrowing its region is new storage, which waits for a threaded driver to drain.
        ring = CgStreamBuffer.frameLocalUniforms(1 << 20);
        CgShaderBufferRegistry registry = CgShaderBufferRegistry.get();
        instanceBuffers[CgInstanceKind.QUAD.ordinal()] = registry.getOrCreateInternal("CgGraphQuadInstances" + depth,
                CgInstanceKind.QUAD.format(), CgBindingPoints.QUAD_RENDERER, CgBufferLifetime.FRAME);
        instanceBuffers[CgInstanceKind.CURVE.ordinal()] = registry.getOrCreateInternal("CgGraphCurveInstances" + depth,
                CgInstanceKind.CURVE.format(), CgBindingPoints.CURVE_RENDERER, CgBufferLifetime.FRAME);
        instanceBuffers[CgInstanceKind.OBJECT.ordinal()] = registry.getOrCreateInternal("CgGraphObjectIds" + depth,
                CgInstanceKind.OBJECT.format(), CgBindingPoints.OBJECT_IDS, CgBufferLifetime.FRAME);
        primitives = registry.getOrCreateInternal("CgGraphPrimitives" + depth, CgPrimitiveTable.FORMAT,
                CgBindingPoints.OBJECT_DATA, CgBufferLifetime.FRAME);
        identityBuffer = registry.getOrCreateInternal("CgGraphIdentityIds" + depth, CgInstanceKind.OBJECT.format(),
                CgBindingPoints.OBJECT_IDS, CgBufferLifetime.RETAINED);
    }

    /** Executes {@code frame}. Render thread, inside a frame. */
    public static void execute(CgFrame frame) {
        if (!drawsOnDevice()) {
            refuse(frame);
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
            long ringFrame = CgFrameRing.frame();
            if (depth == 0 && ringFrame != trimmedFrame) {
                try (CgTrace.Zone trim = CgTrace.zone(CgChannels.GL, TRIM_POOLS)) {
                    POOL.endFrame(FORGET_STORAGE);
                    BUFFERS.endFrame(FORGET_BUFFER);
                    CgTrace.counter(CgChannels.GL, POOL_FRAMEBUFFERS, POOL.held());
                    CgTrace.counter(CgChannels.GL, POOL_KB, POOL.footprintBytes() >> 10);
                    // The last frame's end waited for all its async work, as run() takes it.
                    if (transients != null) {
                        transients.endFrame(asyncLatest, FORGET_STORAGE);
                        CgTrace.counter(CgChannels.GL, TRANSIENT_KB, (ALIASING ? transients.heapBytes() : POOL.bytes()) >> 10);
                        if (ALIASING) CgTrace.counter(CgChannels.GL, TRANSIENT_PEAK_KB, transients.framePeak() >> 10);
                    }
                }
                trimmedFrame = ringFrame;
            }
        }
        if (BY_DEPTH.size() == depth) BY_DEPTH.add(new CgExecutor(depth));
        CgExecutor executor = BY_DEPTH.get(depth);
        depth++;
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, "graph.execute")) {
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
            execute(frame);
        } finally {
            frame.keepRequested = false;
            frame.wholePasses = false;
        }
    }

    /**
     * Makes the executors of the first {@code depths} nesting depths now. Each maps a ring and an indirect-args table,
     * and a map waits for a threaded driver's queue, deepest while loading: an immediate draw's first nested execution
     * held the loading screen 117 ms (1.20.1). At the engine's start, after {@code CgBindingPoints.init}. Render thread.
     */
    public static void ahead(int depths) {
        while (BY_DEPTH.size() < depths) BY_DEPTH.add(new CgExecutor(BY_DEPTH.size()));
    }

    /**
     * Whether an execution starting now draws, through {@code CgRHI}: false on a host whose context no device takes
     * (below GL 4.5), which draws nothing of ours.
     */
    public static boolean drawsOnDevice() {
        return CgRHI.installed() && CgGlslCompiler.installed();
    }

    /** No device: the frame's requests fail, and the host is told once. */
    private static void refuse(CgFrame frame) {
        if (!refusedLogged) {
            refusedLogged = true;
            LOGGER.error("no device is installed: the frame graph draws nothing on this host");
        }
        for (int s = 0; s < frame.stepCount; s++) {
            CgPass pass = frame.steps[s];
            if (pass.request != null) pass.request.fail("no device is installed");
        }
    }

    private static boolean refusedLogged;

    /** The current targets {@link #into} opened, innermost last; reused, so opening one allocates nothing. */
    private static final Into[] INTO = new Into[8];
    private static int intoDepth;
    /** What executions take as their current target while it is open ({@link #into}); null where none is. */
    @Nullable
    private static Into current;

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
        if (intoDepth == INTO.length) throw new IllegalStateException("current targets nest deeper than " + INTO.length);
        Into into = INTO[intoDepth];
        if (into == null) into = INTO[intoDepth] = new Into(intoDepth);
        into.open(target);
        intoDepth++;
        current = into;
        return into;
    }

    /** Whether a current target is open: a host's hook names one before anything draws. */
    public static boolean hasCurrentTarget() {
        return current != null;
    }

    /** A current target {@link #into} named; closing it puts back the one before. */
    public static final class Into implements AutoCloseable {
        private final int level;
        @Nullable
        CgExternalTarget target;
        private boolean open;

        private Into(int level) {
            this.level = level;
        }

        private void open(CgExternalTarget target) {
            this.target = target;
            open = true;
        }

        @Override
        public void close() {
            if (!open) return;
            if (level != intoDepth - 1) throw new IllegalStateException("a current target closed out of order");
            open = false;
            target = null;
            intoDepth--;
            current = intoDepth == 0 ? null : INTO[intoDepth - 1];
        }
    }

    /** The storages of the external targets opened last, most recent first: a host alternates a few. */
    private static final CgExternalTarget[] OPENED = new CgExternalTarget[8];
    private static final CgGraphStorage[] OPENED_STORAGE = new CgGraphStorage[8];

    /** {@code target}'s storage, made at its first opening and kept while it is among the last opened. */
    private static CgGraphStorage opened(CgExternalTarget target) {
        for (int i = 0; i < OPENED.length; i++) {
            if (OPENED[i] != target) continue;
            CgGraphStorage found = OPENED_STORAGE[i];
            if (i > 0) {
                System.arraycopy(OPENED, 0, OPENED, 1, i);
                System.arraycopy(OPENED_STORAGE, 0, OPENED_STORAGE, 1, i);
                OPENED[0] = target;
                OPENED_STORAGE[0] = found;
            }
            return found;
        }
        System.arraycopy(OPENED, 0, OPENED, 1, OPENED.length - 1);
        System.arraycopy(OPENED_STORAGE, 0, OPENED_STORAGE, 1, OPENED.length - 1);
        OPENED[0] = target;
        return OPENED_STORAGE[0] = CgGraphStorage.imported(target);
    }

    /** The host's targets are imported afresh: at a window resize, which may remake them. */
    public static void forgetHostTargets() {
        PASS_DESCS.clear();
        Arrays.fill(OPENED, null);
        Arrays.fill(OPENED_STORAGE, null);
        CgExternalTarget.forgetHosts();
    }

    /**
     * Frees every executor's ring, the transient pools and every persistent or history buffer's storage still held. At
     * context teardown, before the framebuffer sweep.
     */
    public static void destroyAll() {
        for (CgExecutor executor : BY_DEPTH) {
            executor.deviceSets.release();
            executor.ring.delete();
            executor.commands.delete(FORGET_BUFFER);
            if (executor.staging != null) executor.staging.delete();
        }
        BY_DEPTH.clear();
        POOL.delete();
        if (transients != null) transients.delete();
        transients = null;
        BUFFERS.delete();
        for (CgGraphBuffer buffer : KEPT) freeKept(buffer);
        KEPT.clear();
        STATES.clear();
        CgBufferInspector.reset();
        // The next device's timelines start again at 0.
        clearAsync();
        asyncLatest = asyncWaited = 0;
        asyncFrame = -1;
        asyncUnwaited = false;
    }

    private void run(CgFrame frame) {
        boolean again = frame.executions++ > 0;
        kernelsSeen |= frame.kernels;
        CgBoundResources.recording = true;
        CgCapabilities caps = CgCapabilities.detect();
        ComputeTier tier = caps.computeTier();
        boolean compute = tier == ComputeTier.V || tier == ComputeTier.G43;
        computeBarriers = BARRIERS.get() && compute;
        gpuCounts = compute || tier == ComputeTier.G40 && caps.drawIndirect();
        asyncCompute = tier == ComputeTier.V && caps.asyncCompute();
        independentMasks = caps.independentBlend();
        if (asyncUnwaited && CgFrameRing.frame() != asyncFrame) {   // the last frame's end waited for all of it
            clearAsync();
            asyncUnwaited = false;
        }
        asyncEnd = 0;
        takes = CgRHI.installed() && CgRHI.get().takesTransitions();
        moved = 0;
        splitCount = 0;
        runStart = runEnd = -1;
        multiDraw = caps.multiDraw() && CgMeshStore.get().multiDraw();
        // The current target: the one the host's hook or the engine opened (into), imported as storage
        if (current != null) {
            start = opened(current.target);
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
            for (int k = 0; k < KINDS; k++) {
                if (frame.instanceFloats[k] > 0) instanceBuffers[k].uploadRaw(frame.instances[k], frame.instanceFloats[k]);
            }
            tabled = frame.primitiveFloats > 0;
            if (tabled) primitives.uploadRaw(frame.primitives, frame.primitiveFloats);
            if (frame.identityIds > identityIds) uploadIdentity(frame.identityIds);
            CgTrace.add(CgChannels.GL, PRIMITIVES, frame.primitives());
            CgTrace.add(CgChannels.GL, PRIMITIVE_IDS, frame.instanceFloats[OBJECT]);
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
                boolean skipped = again && doneOnce(frame, s, frame.keepRequested);
                if (again) {
                    try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, STALE)) {
                        staleTransients(frame, s, skipped);
                    }
                }
                if (takes && !again && s > runEnd) merge(frame, s);
                // An included frame takes its storage from where it rests: no transition of ours left half done
                if (splitCount > 0) endSplits(pass instanceof CgPass.Include ? Integer.MAX_VALUE : runEnd);
                if (!skipped) step(frame, s);
                if (splits && !skipped && s >= runEnd) beginSplits(frame, s);
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
                            giveBack(frame.transients.get(t));
                        }
                        resolved--;
                    }
                }
                if (!again) freeReleased(frame, s);
            }
        } finally {
            try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, FINISH)) {
                if (takes) {
                    if (splitCount > 0) endSplits(Integer.MAX_VALUE);
                    restMoved(false);
                }
                sharedDepth.release(POOL);
                sharedDepthSource = null;
                waitAsync(asyncEnd);   // what the host may touch; the rest waits for its reader, or the frame's end
                if (resolved > 0) {
                    for (CgGraphResource transientResource : frame.transients) {
                        if (isResolved(transientResource)) giveBack(transientResource);
                    }
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
                texture.resolve(takeStorage(texture.desc(), async));
            } else {
                CgGraphBuffer buffer = (CgGraphBuffer) resource;
                buffer.resolve(BUFFERS.acquire(buffer.desc()));
            }
        }
    }

    private static void giveBack(CgGraphResource resource) {
        if (resource instanceof CgGraphTexture texture) {
            giveStorage(texture.desc(), texture.storage());
            texture.resolve(null);
        } else {
            CgGraphBuffer buffer = (CgGraphBuffer) resource;
            BUFFERS.release(buffer.desc(), buffer.bufferId());
            buffer.resolve(0);
        }
    }

    /**
     * Storage for a transient of {@code desc} first used on the compute queue where {@code async}: placed in the
     * device's transient heaps where it places them, its contents undefined, else pooled.
     */
    private CgGraphStorage takeStorage(CgTextureDesc desc, boolean async) {
        CgTransientTextures placing = placing(desc);
        if (placing == null) return POOL.acquire(desc);
        return placing.acquire(desc, async ? Long.MAX_VALUE : joined());
    }

    /** {@code storage} given back after its last use, with the async work still pending on it. */
    private static void giveStorage(CgTextureDesc desc, CgGraphStorage storage) {
        CgTransientTextures placing = placing(desc);
        if (placing != null) placing.release(storage, pendingAsync(storage));
        else POOL.release(desc, storage);
    }

    /** The device's transient heaps where {@code desc} is placed in them; null where it is pooled. */
    private static CgTransientTextures placing(CgTextureDesc desc) {
        if (!CgRHI.installed()) return null;
        if (transients == null) {
            CgRHI rhi = CgRHI.get();
            CgTransientMemory memory = rhi.transientMemory();
            if (memory == null) return null;
            CgTransientTextures made = new CgTransientTextures(rhi, memory);
            POOL.measure(d -> CgTransientTextures.places(d) ? made.bytes(d) : 0);
            transients = made;
        }
        return ALIASING && CgTransientTextures.places(desc) ? transients : null;
    }

    /** The newest async point the frame's queue has waited for: the newest of all, once none is left unwaited. */
    private static long joined() {
        return asyncUnwaited ? asyncWaited : asyncLatest;
    }

    /** The newest async point not yet waited for on {@code storage}, under either name a step keys it by; else 0. */
    private static long pendingAsync(CgGraphStorage storage) {
        if (!asyncUnwaited) return 0;
        long point = pendingPoint(storage.key());
        CgGpuTexture color = storage.color(0);
        return color == null ? point : Math.max(point, pendingPoint(CgGraphStorage.key(color)));
    }

    private static long pendingPoint(long key) {
        int j = asyncEntry(key);
        return asyncKeys[j] != 0 ? asyncPoints[j] : 0;
    }

    private static boolean isResolved(CgGraphResource resource) {
        return resource instanceof CgGraphTexture texture ? texture.storage() != null
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
        if (pass instanceof CgPass.TextureUpload || pass instanceof CgPass.Readback) return true;
        if (pass instanceof CgPass.BufferFill || pass instanceof CgPass.BufferUpdate || pass instanceof CgPass.BufferCopy) {
            return frame.outlives[s];
        }
        if (pass instanceof CgComputePass compute) return frame.outlives[s] && !compute.runsAgain();
        if (pass.target != null && pass.target.kind() == CgGraphTexture.Kind.HISTORY) return true;   // one version a frame
        return keepRequested && pass.target != null && pass.target.kind() == CgGraphTexture.Kind.REQUESTED;
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

    private void run(CgFrame frame, int s, CgPass pass) {
        currentStep = s;
        try {
            boolean async = frame.async[s];
            if (asyncUnwaited && !async) {
                try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, AWAIT_ASYNC)) {
                    awaitAsync(frame, s);
                }
            }
            // A history drawn into moves to its next version first: what the step's transitions and its reads name.
            CgGraphTexture drawn = pass instanceof CgRasterPass raster ? raster.target
                    : pass instanceof CgPass.Callback callback ? callback.target : null;
            if (drawn != null && drawn.kind() == CgGraphTexture.Kind.HISTORY) {
                if (drawn.isPreviousVersion()) throw new IllegalStateException(drawn + " is sampled only: draw into its history");
                storage(drawn);
                drawn.advance();
            }
            // An included frame states its own transitions, from where each storage rests
            if (!(pass instanceof CgComputePass) && !(pass instanceof CgPass.Include)) {
                try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, BARRIER)) {
                    barriers(frame, s);
                }
            }
            if (pass instanceof CgRasterPass raster) {
                raster(frame, raster, frame.rasters[s]);
                if (frame.rasters[s].writesDepth && sharesDepth(depthDestination(raster))) sharedDepthSource = null;
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
            } else if (pass instanceof CgPass.BufferFill || pass instanceof CgPass.BufferUpdate || pass instanceof CgPass.BufferCopy) {
                try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, BUFFER_STEP)) {
                    bufferStep(pass);
                }
            } else if (pass instanceof CgPass.Readback readback) {
                try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, STEP_READBACK)) {
                    readback(readback);
                }
            } else if (pass instanceof CgPass.TextureCopy copy) {
                try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, STEP_COPY)) {
                    copyOnDevice(copy);
                }
            } else if (pass instanceof CgPass.TextureUpload upload) {
                try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, STEP_UPLOAD)) {
                    upload(upload);
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
                    freeKept(buffer);
                    KEPT.remove(buffer);
                } else {
                    sharedDepthSource = null;
                    freeTexture((CgGraphTexture) frame.released.get(i));
                }
            }
        }
    }

    private void bufferStep(CgPass pass) {
        // Storage made and the update staged first: nothing of it is made inside the section
        int dst = 0;
        CgGpuBuffer src = null;
        long at = 0;
        if (pass instanceof CgPass.BufferFill fill) {
            dst = bufferStorage(fill.buffer, true);
        } else if (pass instanceof CgPass.BufferUpdate update) {
            dst = bufferStorage(update.buffer, true);
            at = stage(update);
            src = staging.gpuBuffer();
        } else if (pass instanceof CgPass.BufferCopy copy) {
            int from = bufferStorage(copy.from, false);
            src = CgDeviceNames.buffer(from);
            at = 0L;
            dst = bufferStorage(copy.to, true);
        }
        CgDeviceSection.begin();
        try {
            bufferStepOnDevice(pass, CgDeviceNames.buffer(dst), 0L, src, at);
        } finally {
            CgDeviceSection.end();
        }
    }

    /** {@code copy}'s blit on the device's command list, between two storages. */
    private void copyOnDevice(CgPass.TextureCopy copy) {
        CgGraphStorage from = storage(copy.from), target = storage(copy.target);
        CgGpuTexture source = from.color(0);
        if (source == null) throw new IllegalStateException("copy " + copy.from.name() + " has no colour to read");
        blitting(from, target);
        CgDeviceSection.begin();
        try {
            CgRHI.get().encoder().blit(CgTextureView.attachment(source, from.colorLevel(), from.colorLayer()), copy.x,
                    copy.y, copy.x + copy.w, copy.y + copy.h, CgTextureView.attachment(target.color(0), 0, 0), copy.tx,
                    copy.ty, copy.tx + copy.tw, copy.ty + copy.th,
                    copy.linear ? CgGpuSampler.Filter.LINEAR : CgGpuSampler.Filter.NEAREST);
        } finally {
            CgDeviceSection.end();
        }
        blitted(from, target);
    }

    /** An update's bytes on the render thread, where every update runs: one buffer serves them all. */
    @Nullable
    private static ByteBuffer texels;

    /** {@code upload}'s bytes written into its texture's colour 0, its step's barriers having made it a copy's. */
    private static void upload(CgPass.TextureUpload upload) {
        CgGraphStorage target = storage(upload.target);
        CgGpuTexture image = target.color(0);
        if (image == null) throw new IllegalStateException(upload.target + " has no colour texture to update");
        if (texels == null || texels.capacity() < upload.bytes.length) {
            texels = CgBufferUtils.createByteBuffer(Math.max(upload.bytes.length, 4096));
        }
        texels.clear();
        texels.put(upload.bytes).flip();
        boolean volume = target.isVolume();
        CgDeviceSection.begin();
        try {
            CgRHI.get().encoder().writeTexture(image, new CgTextureRegion(upload.level, upload.x, upload.y,
                    volume ? upload.z : 0, upload.w, upload.h, volume ? upload.d : 1), texels);
        } finally {
            CgDeviceSection.end();
        }
    }

    /**
     * {@link #bufferStep} on the device's command list, in a device section: into {@code dst} from byte {@code dstBase},
     * from {@code src} at {@code srcBase} (an update's staging ring, a copy's source) where it copies.
     */
    private static void bufferStepOnDevice(CgPass pass, CgGpuBuffer dst, long dstBase, @Nullable CgGpuBuffer src, long srcBase) {
        CgCommandList list = CgRHI.get().encoder();
        if (pass instanceof CgPass.BufferFill fill) {
            try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL_DETAIL, FILL)) {
                list.fillBuffer(dst, dstBase + fill.offset, fill.size, fill.value);
            }
            written(fill.buffer);
        } else if (pass instanceof CgPass.BufferUpdate update) {
            try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL_DETAIL, UPDATE_COPY)) {
                list.copyBuffer(src, srcBase, dst, dstBase + update.offset, update.size);
            }
            written(update.buffer);
        } else if (pass instanceof CgPass.BufferCopy copy) {
            list.copyBuffer(src, srcBase + copy.fromOffset, dst, dstBase + copy.toOffset, copy.size);
            written(copy.to);
        }
    }

    // ── Compute ──────────────────────────────────────────────────────────────

    private void compute(CgFrame frame, CgComputePass pass, CgFrame.Compute packed) {
        if (++passSerial == 1 << 28) passSerial = 1;   // a set keeps three bits for its group
        computeDevice(frame, pass, packed);
    }

    /**
     * Barriers every storage's first access in the pass before its first dispatch, as Unreal RDG batches a pass's
     * prologue barriers: dispatches reading what earlier passes wrote then wait once together, not each in turn. A
     * history is left to its binding, since its version moves within the pass.
     */
    private static void prologue(List<CgDispatch> dispatches) {
        for (int d = 0; d < dispatches.size(); d++) {
            CgDispatch dispatch = dispatches.get(d);
            // Claimed, never hoisted: a write later in the pass must not move ahead of this read.
            if (dispatch.args != null) STATES.claim(CgResourceStates.buffer(bufferStorage(dispatch.args, false)), passSerial, d);
            for (int b = 0; b < dispatch.buffers.length; b++) {
                hoist(d, dispatch.buffers[b], dispatch.bufferAccess[b], set(dispatch.skipGroup[b]), dispatch.hoisted, b);
                hoist(d, dispatch.counters[b], dispatch.counterAccess[b], set(dispatch.countSkipGroup[b]),
                        dispatch.counterHoisted, b);
            }
        }
    }

    private static void hoist(int dispatch, @Nullable CgGraphBuffer buffer, int access, int set, int[] hoisted, int b) {
        if (buffer == null || access == 0 || buffer.resource().kind() == CgGraphBuffer.Kind.HISTORY) return;
        int id = bufferStorage(buffer, (access & CgAccess.COMPUTE_WRITE) != 0);
        if (!STATES.claim(CgResourceStates.buffer(id), passSerial, dispatch)) return;
        barrier(id, access, set);
        hoisted[b] = passSerial;
    }

    /**
     * {@code pass}'s dispatches through the device's compute pass: what runs outside it first (the constants'
     * snapshot, the tokens' buffers, every storage, each kernel's pipeline), then each dispatch's pipeline, sets, pushed
     * words and dispatch. Its barriers record inside the compute pass ({@link CgGraphBarriers}).
     */
    private void computeDevice(CgFrame frame, CgComputePass pass, CgFrame.Compute packed) {
        List<CgDispatch> dispatches = pass.dispatches();
        CgRHI rhi = CgRHI.get();
        CgTrace.add(CgChannels.GL, DEVICE_COMPUTES, 1);
        frame.bindings.bind(packed.constants);
        CgBoundResources.forgetTextures(CgBindingModel.PASS);
        CgBoundResources.forgetTextures(CgBindingModel.MATERIAL);
        // Before the compute pass, after the constants, as a raster pass's copy: the bind is what the view set reads
        if (pass.depthFrom() != null) copyDepthFrom(pass, pass.depthFrom(), pass.depthFromCopy(), CgBindingPoints.DEPTH_TEXTURE_UNIT);
        deviceSets.prepare(frame.bindings);
        if (CgKernelTarget.CHECKED) CgComputeCheck.prepare();
        for (int i = 0; i < dispatches.size(); i++) {
            CgDispatch d = dispatches.get(i);
            List<String> tokens = d.source.engineBuffers();
            for (int t = 0; t < tokens.size(); t++) CgEngineBufferRegistry.get(tokens.get(t)).buffer().get().bind();
            d.kernel.program().devicePipeline(rhi);
            for (int b = 0; b < d.buffers.length; b++) {
                if (d.buffers[b] != null) bufferStorage(d.buffers[b], false);
                if (d.counters[b] != null) bufferStorage(d.counters[b], false);
            }
            if (d.args != null) bufferStorage(d.args, false);
        }
        DEVICE_COMPUTE.begin(pass.name());
        deviceComputing = true;
        try {
            prologue(dispatches);
            for (int i = 0; i < dispatches.size(); i++) dispatchDevice(frame, rhi, dispatches.get(i), packed.bindings[i], packed.constants);
            CgTrace.add(CgChannels.GL, DISPATCH_COUNT, dispatches.size());
        } finally {
            DEVICE_COMPUTE.close();
            endDeviceBarriers();
            deviceComputing = false;
            if (pass.depthFromCopy() != null) pass.depthFromCopy().release(POOL);
        }
    }

    private void dispatchDevice(CgFrame frame, CgRHI rhi, CgDispatch d, int bindings, int constants) {
        CgKernelProgram program = d.kernel.program();
        CgBindings k = kernelBindings.clear();
        for (int b = 0; b < d.buffers.length; b++) {
            if (d.buffers[b] != null) {
                int access = d.hoisted[b] == passSerial ? 0 : d.bufferAccess[b];
                int id = bufferStorage(d.buffers[b], (access & CgAccess.COMPUTE_WRITE) != 0);
                if (access != 0) barrier(id, access, set(d.skipGroup[b]));
                storageSlot(k, CgBindingModel.FIRST_KERNEL_BUFFER + b, id, d.offsets[b], d.sizes[b]);
            }
            if (d.counters[b] != null) {
                long base = CgKernelProgram.counterBase(d.counterOffsets[b]);
                int access = d.counterHoisted[b] == passSerial ? 0 : d.counterAccess[b];
                int id = bufferStorage(d.counters[b], (access & CgAccess.COMPUTE_WRITE) != 0);
                if (access != 0) barrier(id, access, set(d.countSkipGroup[b]));
                storageSlot(k, CgBindingModel.FIRST_KERNEL_BUFFER + program.counterPoint(b), id, base,
                        d.counterOffsets[b] - base + 4);
                program.counterWordOn(b, d.counterOffsets[b]);
            }
        }
        for (CgTexture sampler : d.samplers) {
            CgGraphTexture graph = sampler == null ? null : CgGraphTexture.sampled(sampler);
            if (graph != null) move(graph, CgAccess.SAMPLED_READ, -1, -1);   // after a kernel wrote it as an image
        }
        for (int i = 0; i < d.images.length; i++) {
            CgGraphTexture texture = d.images[i];
            if (texture == null) continue;
            CgGpuTexture color = storage(texture).color(0);
            int access = imageAccess(d, i, color);
            if (access != 0) move(texture, access, -1, -1);
            k.image(CgBindingModel.FIRST_KERNEL_IMAGE + i, imageView(d, i, color));
        }
        if (program.checkPoint() >= 0) {
            k.buffer(CgBindingModel.FIRST_KERNEL_BUFFER + program.checkPoint(), CgBindingLayout.Type.STORAGE_BUFFER,
                    CgComputeCheck.buffer(), CgComputeCheck.claim(program), CgComputeCheck.SLOT_BYTES);
        }
        int args = d.form == CgDispatch.Form.INDIRECT ? bufferStorage(d.args, false) : 0;
        if (args != 0) bufferBarrier(args, CgAccess.INDIRECT);
        endDeviceBarriers();
        DEVICE_COMPUTE.pass(rhi);
        CgPipelineLayout layout = program.deviceLayout();
        DEVICE_COMPUTE.pipeline(program.devicePipeline(rhi), layout);
        for (int set = 0; set < layout.sets().size(); set++) {
            if (set == CgRHI.BINDLESS_SET) {
                DEVICE_COMPUTE.set(set, rhi.bindlessSet());
                continue;
            }
            if (layout.sets().get(set).slots().isEmpty()) continue;
            long before = deviceSets.defaulted();
            CgBindingSet s = deviceSets.set(rhi, frame.bindings, set == CgBindingModel.VIEW ? constants : bindings, layout,
                    set, set == CgBindingModel.MATERIAL ? k : null);
            if (deviceSets.defaulted() != before) {
                String what = d.source.path() + " " + d.decl.name() + " " + deviceSets.lastDefaulted();
                if (deviceSetsLogged.add(what)) LOGGER.warn("device compute: {} defaulted", what);
            }
            DEVICE_COMPUTE.set(set, s, deviceSets.offsets(), deviceSets.offsetCount());
        }
        switch (d.form) {
            case ELEMENTS -> program.dispatchOn(DEVICE_COMPUTE.pass(rhi), d.x, d.y, d.z);
            case GROUPS -> program.dispatchOn(DEVICE_COMPUTE.pass(rhi), d.x * program.kernel().sizeX(),
                    d.y * program.kernel().sizeY(), d.z * program.kernel().sizeZ());
            case INDIRECT -> program.dispatchIndirectOn(DEVICE_COMPUTE.pass(rhi), CgDeviceNames.buffer(args),
                    d.argsOffset);
        }
        advanceHistories(d);
    }

    /** {@code id}'s range as {@link #bindStorage} binds it, at {@code binding} of {@code k}. */
    private static void storageSlot(CgBindings k, int binding, int id, long offset, long size) {
        long base = 0;
        long whole = CgDeviceNames.buffer(id).size() - base;
        k.buffer(binding, CgBindingLayout.Type.STORAGE_BUFFER, CgDeviceNames.buffer(id), base + offset,
                size == 0 ? whole - offset : size);
    }

    /** Image binding {@code i} of {@code d} as {@code glBindImageTexture} takes it: a level, one layer or all of them. */
    private static CgTextureView imageView(CgDispatch d, int i, CgGpuTexture texture) {
        CgImageDecl image = d.source.images().get(i);
        boolean all = d.layers[i] < 0 && image.dimension() != CgImageDimension.D2;
        int level = d.levels[i], layer = all ? -1 : Math.max(0, d.layers[i]);
        ImageViews kept = IMAGE_VIEWS.get(texture);
        if (kept == null) IMAGE_VIEWS.put(texture, kept = new ImageViews());
        for (int k = 0; k < kept.count; k++) {
            if (kept.levels[k] == level && kept.layers[k] == layer) return kept.views[k];
        }
        CgTextureView made;
        if (all) {
            CgTextureView whole = CgTextureView.whole(texture);
            made = new CgTextureView(texture, level, 1, whole.baseLayer(), whole.layers());
        } else {
            made = new CgTextureView(texture, level, 1, layer, 1);
        }
        kept.add(level, layer, made);
        return made;
    }

    /** Each texture's image views a dispatch bound, kept while it lives: one per level and layer (-1: all) asked. */
    private static final Map<CgGpuTexture, ImageViews> IMAGE_VIEWS = new WeakHashMap<>();

    /** A texture's kept image views and what each was asked as. */
    private static final class ImageViews {
        int count;
        int[] levels = new int[2], layers = new int[2];
        CgTextureView[] views = new CgTextureView[2];

        void add(int level, int layer, CgTextureView view) {
            if (count == views.length) {
                levels = Arrays.copyOf(levels, count * 2);
                layers = Arrays.copyOf(layers, count * 2);
                views = Arrays.copyOf(views, count * 2);
            }
            levels[count] = level;
            layers[count] = layer;
            views[count++] = view;
        }
    }

    /**
     * Before a barrier while a pass dispatches on the device: a batch opens, recorded inside the compute pass when the
     * next dispatch or the pass's end closes it.
     */
    private static void leaveDeviceCompute() {
        if (!deviceComputing || deviceBarrierBatch) return;
        beginMoves();
        deviceBarrierBatch = true;
    }

    private static void endDeviceBarriers() {
        if (!deviceBarrierBatch) return;
        deviceBarrierBatch = false;
        endMoves();
    }

    /** The overlap set of skip group {@code group1} (1 + the group, 0 for none) in the executing pass. */
    private static int set(int group1) {
        return group1 == 0 ? 0 : passSerial << 3 | (group1 - 1);
    }

    /** What {@code pass} bound, noted for {@link CgBufferInspector}, and the reads armed for it copied as it left them. */
    private void inspect(CgComputePass pass) {
        CgBufferInspector.note(pass);
        for (CgBufferInspector.Request read = CgBufferInspector.due(); read != null; read = CgBufferInspector.due()) {
            if (asyncUnwaited) waitAsync(asyncLatest);
            int id = bufferStorage(read.view(), false);
            bufferBarrier(id, CgAccess.COPY_READ);
            CgReadback.buffer(id, read.offset(), read.size(), read);
        }
    }

    private static int gpuZone(String pass) {
        Integer id = GPU_ZONES.get(pass);
        if (id == null) GPU_ZONES.put(pass, id = CgGpuTrace.name(pass));
        return id;
    }

    // ── Async compute ────────────────────────────────────────────────────────

    /** On the detail channel: an enclosing GPU zone is timed around these, not through them. */
    private static boolean uploadGpuZones() {
        return CgTrace.isEnabled(CgChannels.GL_DETAIL) && CgGpuTrace.isMeasuring();
    }

    /** Step {@code s} on the compute queue; the storage it touches is pending until the frame's queue waits for it. */
    private void computeAsync(CgFrame frame, int s, CgComputePass pass) {
        if (CgKernelTarget.CHECKED) CgComputeCheck.prepare();
        CgRHI.get().encoder().beginAsync();
        long point;
        try {
            compute(frame, pass, frame.computes[s]);
        } finally {
            point = CgRHI.get().encoder().endAsync();
        }
        CgTrace.add(CgChannels.GL, ASYNC_PASSES, 1);
        if (point == 0) return;   // the device ran it in order
        for (int i = frame.accessFrom[s]; i < frame.accessFrom[s + 1]; i++) {
            CgGraphResource view = frame.accessView[i];
            int n = keys(view);
            for (int k = 0; k < n; k++) pend(keyScratch[k], point);
            if (!graphOnly(view)) asyncEnd = point;
        }
        asyncLatest = point;
        asyncUnwaited = true;
        asyncFrame = CgFrameRing.frame();
    }

    /**
     * Whether nothing outside the graph reaches {@code view}'s storage, so its async work may be waited for by its
     * first reader in a later execution of the frame. Imported, current and requested storage the host may touch.
     */
    private static boolean graphOnly(CgGraphResource view) {
        if (view instanceof CgGraphBuffer buffer) return buffer.resource().kind() != CgGraphBuffer.Kind.IMPORTED;
        return ((CgGraphTexture) view).kind() == CgGraphTexture.Kind.TRANSIENT;
    }

    /**
     * The frame's queue waits before step {@code s} for the async work whose storage it touches, under any name the
     * pool gave it since: everything before a callback, which may touch anything.
     */
    private void awaitAsync(CgFrame frame, int s) {
        long point = 0;
        if (frame.steps[s] instanceof CgPass.Callback || frame.steps[s] instanceof CgPass.Include) {
            point = asyncLatest;
        } else {
            for (int i = frame.accessFrom[s]; i < frame.accessFrom[s + 1]; i++) {
                int n = keys(frame.accessView[i]);
                for (int k = 0; k < n; k++) {
                    int j = asyncEntry(keyScratch[k]);
                    if (asyncKeys[j] != 0) point = Math.max(point, asyncPoints[j]);
                }
            }
        }
        if (point > 0) waitAsync(point);
    }

    private void waitAsync(long point) {
        if (point <= asyncWaited) return;
        CgRHI.get().encoder().waitAsync(point);
        asyncWaited = point;
        CgTrace.add(CgChannels.GL, ASYNC_WAITS, 1);
        int kept = 0;
        if (keptKeys.length < asyncKeys.length) {
            keptKeys = new long[asyncKeys.length];
            keptPoints = new long[asyncKeys.length];
        }
        for (int j = 0; j < asyncKeys.length; j++) {
            if (asyncKeys[j] == 0 || asyncPoints[j] <= point) continue;
            keptKeys[kept] = asyncKeys[j];
            keptPoints[kept++] = asyncPoints[j];
        }
        clearAsync();
        for (int j = 0; j < kept; j++) pend(keptKeys[j], keptPoints[j]);
        asyncUnwaited = point < asyncLatest;
    }

    private static void clearAsync() {
        if (asyncCount > 0) Arrays.fill(asyncKeys, 0L);
        asyncCount = 0;
    }

    /** {@code key}'s entry in {@link #asyncKeys}, or the free entry where it would go. */
    private static int asyncEntry(long key) {
        int mask = asyncKeys.length - 1, j = (int) ((key * 0x9E3779B97F4A7C15L) >>> 40) & mask;
        while (asyncKeys[j] != 0 && asyncKeys[j] != key) j = (j + 1) & mask;
        return j;
    }

    private static void pend(long key, long point) {
        if ((asyncCount + 1) * 2 > asyncKeys.length) {
            long[] keys = asyncKeys, points = asyncPoints;
            asyncKeys = new long[keys.length * 2];
            asyncPoints = new long[keys.length * 2];
            asyncCount = 0;
            for (int j = 0; j < keys.length; j++) if (keys[j] != 0) pend(keys[j], points[j]);
        }
        int j = asyncEntry(key);
        if (asyncKeys[j] == 0) {
            asyncKeys[j] = key;
            asyncCount++;
        }
        asyncPoints[j] = point;
    }

    /** The storage {@code view} names now, as {@link CgResourceStates} keys into {@link #keyScratch}: a history's both versions. */
    private int keys(CgGraphResource view) {
        if (view instanceof CgGraphBuffer buffer) {
            CgGraphBuffer resource = buffer.resource();
            if (resource.kind() == CgGraphBuffer.Kind.HISTORY) {
                int[] versions = resource.versions();
                keyScratch[0] = CgResourceStates.buffer(versions[0]);
                keyScratch[1] = CgResourceStates.buffer(versions[1]);
                return 2;
            }
            keyScratch[0] = CgResourceStates.buffer(resource.bufferId());
            return resource.bufferId() == 0 ? 0 : 1;
        }
        CgGraphTexture texture = (CgGraphTexture) view;
        CgGraphStorage storage = texture.storage();
        CgGpuTexture color = storage == null ? null : storage.color(0);
        if (color == null) return 0;
        boolean whole = texture.getLevels() == 1 && texture.getLayers() == 1 && storage.depthSlices() <= 1;
        keyScratch[0] = whole ? storage.key() : CgGraphStorage.key(color);
        return 1;
    }

    /** A history written by {@code d}: its next version is its newest from now on, however many bindings wrote it. */
    private void advanceHistories(CgDispatch d) {
        for (int i = 0; i < d.buffers.length; i++) {
            if (wroteHistory(d.buffers[i], d.bufferAccess[i]) && firstWriter(d, i)) d.buffers[i].advance();
        }
    }

    /**
     * What dispatch {@code d} does to texture {@code image} through every image binding naming it, at the first such binding;
     * 0 at the rest. One barrier for all of them: a transition made for one binding must be visible to the others, a
     * level read beside a level written.
     */
    private static int imageAccess(CgDispatch d, int binding, CgGpuTexture image) {
        int access = 0;
        for (int i = 0; i < d.images.length; i++) {
            if (d.images[i] == null || storage(d.images[i]).color(0) != image) continue;
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

    // ── Buffers ──────────────────────────────────────────────────────────────

    /** Where an update's bytes wait for the GPU's copy: graph storage takes no glBufferSubData. */
    @Nullable
    private CgStreamBuffer staging;

    /** {@code update}'s bytes written into the staging ring; answers where. */
    private int stage(CgPass.BufferUpdate update) {
        int bytes = update.size;
        // 1 MB a region: a move's pools update about that in a frame, and each doubling is new storage (T10).
        if (staging == null) staging = CgStreamBuffer.create(CgStreamBuffer.Use.STAGING, Math.max(bytes, 1 << 20));
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL_DETAIL, UPDATE_STAGE)) {
            if (update.floats != null) {
                staging.mapFloats(bytes).put(update.floats, update.from, bytes / Float.BYTES);
            } else if (update.ints != null) {
                staging.mapInts(bytes).put(update.ints, update.from, bytes / Integer.BYTES);
            } else {
                staging.write(update.bytes, 0, bytes);
            }
            return staging.commit(bytes);
        }
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
                    CgTrace.marker(CgChannels.GL, "graph.storage-made", buffer.name());   // which, at first use
                    try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, MAKE_STORAGE)) {
                        buffer.resolve(CgBufferPool.create(buffer.size()));
                    }
                    CgTrace.add(CgChannels.GL, STORAGE_MADE, buffer.size());
                    KEPT.add(buffer);
                }
            }
            case HISTORY -> {
                int[] versions = buffer.versions();
                if (versions[0] == 0) {
                    CgTrace.marker(CgChannels.GL, "graph.storage-made", buffer.name());
                    try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, MAKE_STORAGE)) {
                        versions[0] = CgBufferPool.create(buffer.size());
                        versions[1] = CgBufferPool.create(buffer.size());
                    }
                    CgTrace.add(CgChannels.GL, STORAGE_MADE, 2 * buffer.size());
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

    /** GL buffer {@code buffer} is freed: what is known of it goes with it. */
    private static void forget(int buffer) {
        STATES.forget(CgResourceStates.buffer(buffer));
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
        bufferStorage(buffer, false);
    }

    /**
     * Frees storage {@link #makeStorage} made for a buffer no recorded pass has used yet, at once: a precache resizing
     * what it made. A buffer a pass has used goes through {@code CgRecording.release}, which waits for its passes.
     */
    public static void freeStorage(CgGraphBuffer buffer) {
        if (KEPT.remove(buffer)) freeKept(buffer);
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
        freeTexture(texture);
    }

    private static void freeTexture(CgGraphTexture texture) {
        CgGraphStorage[] versions = texture.versions();
        if (versions != null) {
            for (int v = 0; v < versions.length; v++) {
                free(versions[v]);
                versions[v] = null;
            }
        } else {
            free(texture.storage());
            texture.resolve(null);
        }
    }

    private static void freeKept(CgGraphBuffer buffer) {
        if (buffer.kind() == CgGraphBuffer.Kind.HISTORY) {
            int[] versions = buffer.versions();
            for (int v = 0; v < 2; v++) {
                if (versions[v] == 0) continue;
                CgBufferPool.free(versions[v]);
                forget(versions[v]);
                versions[v] = 0;
            }
        } else if (buffer.bufferId() != 0) {
            CgBufferPool.free(buffer.bufferId());
            forget(buffer.bufferId());
            buffer.resolve(0);
        }
    }

    // ── Barriers ─────────────────────────────────────────────────────────────

    /**
     * The barriers step {@code s} needs before it runs, from its access list. Storage that does not exist yet is left
     * alone: a texture a draw samples before anything made it binds nothing, as it always has.
     */
    /**
     * Step {@code s}'s transitions, one batch: each buffer's barrier, and each texture moved to its access. A raster
     * step's attachments move to {@link CgAccess#COLOR_WRITE}, its target's other levels and layers to what it reads
     * of them.
     */
    private void barriers(CgFrame frame, int s) {
        if (!kernelsSeen && !takes || !frame.prologued[s]) return;   // a run's first step stated its
        beginMoves();   // one glMemoryBarrier for the step on GL, one transition on a device
        try {
            for (int e = frame.prologueFrom[s]; e < frame.prologueFrom[s + 1]; e++) {
                int bits = frame.prologueBits[e];
                if (frame.prologueView[e] instanceof CgGraphBuffer buffer) {
                    bufferBarrier(bufferStorage(buffer, (bits & CgResourceStates.WRITES) != 0), bits);
                } else {
                    move((CgGraphTexture) frame.prologueView[e], bits, frame.prologueLevel[e], frame.prologueLayer[e]);
                }
            }
        } finally {
            endMoves();
        }
    }

    /** Enters the render pass run compiled for step {@code s} ({@link CgFrameCompiler#renderPasses}). */
    private void merge(CgFrame frame, int s) {
        runStart = s;
        runEnd = frame.runEnd[s];
        runAttachment = runEnd > s ? frame.runAttachment[s] : null;
        if (runEnd > s) CgTrace.add(CgChannels.GL, MERGED, runEnd - s);
    }

    /** What {@code pass} draws into: its target, or the current target for none. */
    private static CgGraphTexture targetOf(CgRasterPass pass) {
        return pass.target != null ? pass.target : CgGraphTexture.current();
    }

    /** The texture whose depth {@code pass} tests and writes: its declared one, or its target, whose own depth it may be. */
    private static CgGraphTexture depthTexture(CgRasterPass pass) {
        return pass.depthTexture();
    }

    /** {@code pass}'s second attachment: its own, or inside a merged run the run's. */
    @Nullable
    private CgGraphTexture attachmentOf(CgRasterPass pass) {
        CgGraphTexture own = pass.attachment();
        return own != null ? own : runAttachment;
    }

    // ── Transitions, on the device's command list ─────────────────────────────

    private static void beginMoves() {
        TRANSITIONS.begin();
    }

    private static void endMoves() {
        TRANSITIONS.end(DEVICE_COMPUTE.isOpen());
    }

    private static int endMovesSplit() {
        return TRANSITIONS.endSplit(DEVICE_COMPUTE.isOpen());
    }

    private static void endSplit(int id) {
        TRANSITIONS.split(id, DEVICE_COMPUTE.isOpen());
    }

    private static void bufferMoves(int name, int from, int to) {
        TRANSITIONS.buffer(name, from, to);
    }

    private static void textureMoves(CgGpuTexture image, int level, int levels, int layer, int layers, int from, int to) {
        TRANSITIONS.texture(image, level, levels, layer, layers, from, to);
    }

    private static void storageMoves(CgGraphStorage storage, int from, int to) {
        TRANSITIONS.storage(storage, from, to);
    }

    /** The barrier an access of {@code bits} to buffer {@code id} needs, issued. */
    private static void bufferBarrier(int id, int bits) {
        barrier(id, bits, 0);
    }

    /** {@link #bufferBarrier} through overlap set {@code set} (0 for none). */
    private static void barrier(int id, int bits, int set) {
        int from = STATES.access(CgResourceStates.buffer(id), bits, set);
        if (!issues(from, bits)) return;
        leaveDeviceCompute();
        bufferMoves(id, from, bits);
        CgTrace.add(CgChannels.GL, BARRIER_COUNT, 1);
    }

    /** Whether a transition from {@code from} to {@code to} is issued: every one on a device, a kernel's on GL. */
    private static boolean issues(int from, int to) {
        return from != 0 && computeBarriers && (takes || ((from | to) & COMPUTE) != 0);
    }

    /**
     * Moves graph texture {@code texture} to {@code to}, stating what must move: whole, or where {@code attachedLevel}
     * is not -1, its subresource at that level and {@code attachedLayer} to {@link CgAccess#COLOR_WRITE} and the rest
     * to {@code to} (none where 0). A single-level, single-layer texture moves as one storage, depth with colour; any
     * other by each colour texture's subresources.
     */
    private void move(CgGraphTexture texture, int to, int attachedLevel, int attachedLayer) {
        boolean outlives = texture.kind() != CgGraphTexture.Kind.TRANSIENT;
        // A requested texture's or a history's storage is made at its first use: this statement, before its bind.
        CgGraphStorage storage = outlives ? storage(texture) : texture.storage();
        if (storage == null) return;
        int levels = storage.levels(), layers = storage.layers();
        if (levels == 1 && layers == 1 && storage.depthSlices() <= 1) {
            moveStorage(storage, attachedLevel >= 0 ? CgAccess.COLOR_WRITE : to, outlives);
            return;
        }
        for (int slot = 0; slot < storage.colorSlots(); slot++) {
            CgGpuTexture color = storage.color(slot);
            if (color == null) continue;
            long key = CgGraphStorage.key(color);
            int[] subs = STATES.image(key, levels * layers);
            noteMoved(key, null, color, levels, layers, outlives);
            for (int level = 0; level < levels; level++) {
                int layer = 0;
                while (layer < layers) {
                    int want = level == attachedLevel && layer == attachedLayer ? CgAccess.COLOR_WRITE : to;
                    int from = subs[level * layers + layer], end = layer + 1;
                    while (end < layers && subs[level * layers + end] == from && (level != attachedLevel
                            || (end != attachedLayer && layer != attachedLayer))) end++;
                    if (want != 0) {
                        boolean moves = CgResourceStates.moves(from, want);
                        if (moves && issues(from == 0 ? CgAccess.RESTING : from, want)) {
                            leaveDeviceCompute();
                            textureMoves(color, level, 1, layer, end - layer,
                                    from == 0 ? CgAccess.RESTING : from, want);
                            CgTrace.add(CgChannels.GL, BARRIER_COUNT, 1);
                        }
                        for (int l = layer; l < end; l++) subs[level * layers + l] = moves ? want : from | want;
                    }
                    layer = end;
                }
            }
        }
    }

    /** Moves {@code storage}'s textures to {@code to}; answers where they were, 0 at rest. */
    private int moveStorage(CgGraphStorage storage, int to, boolean outlives) {
        long key = storage.key();
        int[] subs = STATES.image(key, 1);
        int from = subs[0];
        noteMoved(key, storage, null, 0, 0, outlives);
        boolean moves = CgResourceStates.moves(from, to);
        if (moves && issues(from == 0 ? CgAccess.RESTING : from, to)) {
            leaveDeviceCompute();
            storageMoves(storage, from == 0 ? CgAccess.RESTING : from, to);
            CgTrace.add(CgChannels.GL, BARRIER_COUNT, 1);
        }
        subs[0] = moves ? to : from | to;
        return from;
    }

    /** {@code key} was moved, and with {@code outlives} returns to rest at the execution's end; {@code levels} 0 for a storage. */
    private void noteMoved(long key, @Nullable CgGraphStorage storage, @Nullable CgGpuTexture image, int levels, int layers,
                           boolean outlives) {
        for (int m = 0; m < moved; m++) {
            if (movedKeys[m] != key) continue;
            movedOutlives[m] |= outlives;
            return;
        }
        if (moved == movedKeys.length) {
            movedKeys = Arrays.copyOf(movedKeys, moved * 2);
            movedStorages = Arrays.copyOf(movedStorages, moved * 2);
            movedImages = Arrays.copyOf(movedImages, moved * 2);
            movedLevels = Arrays.copyOf(movedLevels, moved * 2);
            movedLayers = Arrays.copyOf(movedLayers, moved * 2);
            movedOutlives = Arrays.copyOf(movedOutlives, moved * 2);
        }
        movedOutlives[moved] = outlives;
        movedKeys[moved] = key;
        movedStorages[moved] = storage;
        movedImages[moved] = image;
        movedLevels[moved] = levels;
        movedLayers[moved++] = layers;
    }

    /**
     * Every image it moved that outlives the execution, or with {@code all} every one, back where it rests: where the
     * host and GL streams find it. A transient left out keeps its state with its storage.
     */
    private void restMoved(boolean all) {
        if (moved == 0) return;
        beginMoves();
        try {
            for (int m = 0; m < moved; m++) {
                if (!all && !movedOutlives[m]) continue;
                int levels = movedLevels[m], layers = movedLayers[m];
                int[] subs = STATES.image(movedKeys[m], Math.max(1, levels * layers));
                if (levels == 0) {
                    if (subs[0] != 0) storageMoves(movedStorages[m], subs[0], CgAccess.RESTING);
                    subs[0] = 0;
                    continue;
                }
                for (int i = 0; i < levels * layers; i++) {
                    if (subs[i] == 0) continue;
                    textureMoves(movedImages[m], i / layers, 1, i % layers, 1, subs[i], CgAccess.RESTING);
                    subs[i] = 0;
                }
            }
        } finally {
            endMoves();
        }
        Arrays.fill(movedStorages, 0, moved, null);
        Arrays.fill(movedImages, 0, moved, null);
        moved = 0;
    }

    /**
     * After step {@code s}: each access whose next is a read two or more steps on begins its transition now, ended
     * before that step ({@link #endSplits}), as Unreal RDG splits a transition from its producer's epilogue to its
     * consumer's prologue. Not across async work, a callback, or an attachment.
     */
    private void beginSplits(CgFrame frame, int s) {
        if (!frame.splits[s]) return;
        beginMoves();
        int began;
        try {
            for (int e = frame.splitFrom[s]; e < frame.splitFrom[s + 1]; e++) {
                int next = frame.splitAccess[e], bits = frame.accessBits[next];
                if (frame.accessView[next] instanceof CgGraphBuffer buffer) bufferBarrier(bufferStorage(buffer, false), bits);
                else move((CgGraphTexture) frame.accessView[next], bits, -1, -1);
            }
        } finally {
            began = endMovesSplit();
        }
        if (began == 0) return;
        if (splitCount == splitIds.length) {
            splitIds = Arrays.copyOf(splitIds, splitCount * 2);
            splitEnds = Arrays.copyOf(splitEnds, splitCount * 2);
        }
        splitIds[splitCount] = began;
        splitEnds[splitCount++] = frame.splitEnd[s];
        CgTrace.add(CgChannels.GL, SPLITS, 1);
    }

    /** Ends every split due by step {@code s}. */
    private void endSplits(int s) {
        for (int k = 0; k < splitCount; ) {
            if (splitEnds[k] > s) {
                k++;
                continue;
            }
            endSplit(splitIds[k]);
            splitIds[k] = splitIds[--splitCount];
            splitEnds[k] = splitEnds[splitCount];
        }
    }

    /** Before a blit of {@code source} into {@code dest} inside a pass: the source read, the destination written. */
    static void blitting(CgGraphStorage source, CgGraphStorage dest) {
        if (!takes || depth == 0) return;
        CgExecutor e = BY_DEPTH.get(depth - 1);
        beginMoves();
        try {
            e.blitSourceWas = e.moveStorage(source, CgAccess.COPY_READ, source.external() != null);
            e.moveStorage(dest, CgAccess.COPY_WRITE, false);
        } finally {
            endMoves();
        }
    }

    /** After it: the source where it was, the copy sampled. */
    static void blitted(CgGraphStorage source, CgGraphStorage dest) {
        if (!takes || depth == 0) return;
        CgExecutor e = BY_DEPTH.get(depth - 1);
        beginMoves();
        try {
            if (e.blitSourceWas != 0) {
                e.moveStorage(source, e.blitSourceWas, false);
            } else {
                // Found at rest: back to rest.
                storageMoves(source, CgAccess.COPY_READ, CgAccess.RESTING);
                STATES.image(source.key(), 1)[0] = 0;
            }
            e.moveStorage(dest, CgAccess.SAMPLED_READ, false);
        } finally {
            endMoves();
        }
    }

    // ── Raster ───────────────────────────────────────────────────────────────

    private void raster(CgFrame frame, CgRasterPass pass, CgFrame.Raster packed) {
        int[] damage = frame.wholePasses ? null : pass.damage();
        raster(frame, pass, packed, damage);
    }

    private void raster(CgFrame frame, CgRasterPass pass, CgFrame.Raster packed, @Nullable int[] damage) {
        if (damage != null && (damage[2] == 0 || damage[3] == 0)) {
            // Nothing changed in it: the target keeps what the last execution left.
            CgTrace.add(CgChannels.GL, "graph.passes.undamaged", 1);
            return;
        }
        if (damage != null) CgTrace.add(CgChannels.GL, "graph.damage-kpx", (long) damage[2] * damage[3] / 1000L);
        if (packed.indirects > 0 && gpuCounts) {
            try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, WRITE_COMMANDS)) {
                writeCommands(frame.bindings, pass, packed);   // a dispatch never sits inside a render pass
            }
        }
        rasterDevice(frame, pass, packed, damage);
    }

    private static final int BATCH_PIPELINE = CgTrace.name("graph.batch.pipeline"),
            BATCH_BINDINGS = CgTrace.name("graph.batch.bindings"), BATCH_DRAW = CgTrace.name("graph.batch.draw");

    private static int groupLabel(int pipeline) {
        if (pipeline >= groupLabels.length) groupLabels = Arrays.copyOf(groupLabels, Math.max(pipeline + 1, groupLabels.length * 2));
        int label = groupLabels[pipeline] - 1;
        if (label < 0) {
            String path = CgPipeline.byId(pipeline).shader().getResourcePath();
            label = CgGpuTrace.label(path != null ? path : "unnamed");
            groupLabels[pipeline] = label + 1;
        }
        return label;
    }

    /** Whether batch {@code b}'s instances name records by ids the frame builder wrote, each with its material's slot. */
    private static boolean slotsInIds(CgFrame.Raster packed, int b) {
        return packed.kind[b] == OBJECT && packed.objects[b] == null;
    }

    private static CgMesh mesh(CgFrame.Raster packed, int b) {
        return packed.kind[b] == CgInstanceKind.OBJECT.ordinal() ? packed.mesh[b] : UNIT_QUAD;
    }

    /**
     * Joins the run of batches from {@code b} that one multi-draw call draws ({@link CgMeshStore#join}), answering its
     * last: those after it that are {@link #joinable} with it, while the store takes their meshes. {@code b} itself,
     * nothing left joined, when none follows.
     */
    private static int joinRun(CgBindingTable table, CgFrame.Raster packed, int b) {
        CgMeshStore store = CgMeshStore.get();
        if (!join(store, packed, b)) return b;
        int end = b;
        while (end + 1 < packed.count && joinable(table, packed, b, end + 1) && join(store, packed, end + 1)) end++;
        if (end == b) store.dropJoined();
        return end;
    }

    private static boolean join(CgMeshStore store, CgFrame.Raster packed, int k) {
        return store.join(mesh(packed, k), packed.instances[k], packed.submesh[k], packed.rangeFirst[k],
                packed.rangeCount[k], packed.first[k]);
    }

    /**
     * Batch {@code k} drawn directly under {@code b}'s pipeline, bindings and scissor, with no target copy before it: its
     * bindings {@code b}'s {@link CgBindingTable#share share}, so another material of one arena joins (Bevy's slab key).
     */
    private static boolean joinable(CgBindingTable table, CgFrame.Raster packed, int b, int k) {
        return packed.counts[k] == null && packed.objects[k] == null && packed.buffers[k * CgDrawChunk.BUFFERS] == null
                && packed.copyBefore[k] == 0 && packed.pipeline[k] == packed.pipeline[b]
                && table.share(packed.binding[k]) == table.share(packed.binding[b]) && packed.scissor[k] == packed.scissor[b]
                && packed.group[k] == packed.group[b];
    }

    /**
     * Indirect batch {@code k} drawn in one multi-draw after {@code b}: as {@link #joinable}, reading the same object
     * records, from a mesh the store {@link CgMeshStore#joins joins} with {@code b}'s.
     */
    private static boolean joinableIndirect(CgBindingTable table, CgFrame.Raster packed, int b, int k) {
        return packed.counts[k] != null && packed.copyBefore[k] == 0
                && packed.pipeline[k] == packed.pipeline[b] && table.share(packed.binding[k]) == table.share(packed.binding[b])
                && packed.scissor[k] == packed.scissor[b] && packed.objects[k] == packed.objects[b]
                && sameBuffers(packed, b, k) && packed.group[k] == packed.group[b]
                && CgMeshStore.get().joins(mesh(packed, b), mesh(packed, k));
    }

    private static final CgIndirect[] INDIRECT_MODES = CgIndirect.values();

    private static final int[] BREAKS = {
            CgTrace.name("graph.multi-draw.breaks.direct"), CgTrace.name("graph.multi-draw.breaks.copy"), CgTrace.name("graph.multi-draw.breaks.pipeline"),
            CgTrace.name("graph.multi-draw.breaks.binding"), CgTrace.name("graph.multi-draw.breaks.scissor"),
            CgTrace.name("graph.multi-draw.breaks.records"), CgTrace.name("graph.multi-draw.breaks.group"),
            CgTrace.name("graph.multi-draw.breaks.mesh")};

    /** Counts the first reason indirect batch {@code k} does not join {@code b}'s run, by {@link #joinableIndirect}'s terms. */
    private static void countBreak(CgBindingTable table, CgFrame.Raster packed, int b, int k) {
        int why = packed.counts[k] == null ? 0 : packed.copyBefore[k] != 0 ? 1
                : packed.pipeline[k] != packed.pipeline[b] ? 2 : table.share(packed.binding[k]) != table.share(packed.binding[b]) ? 3
                : packed.scissor[k] != packed.scissor[b] ? 4
                : packed.objects[k] != packed.objects[b] || !sameBuffers(packed, b, k) ? 5
                : packed.group[k] != packed.group[b] ? 6 : 7;
        CgTrace.add(CgChannels.GL, BREAKS[why], 1);
    }

    /**
     * Indirect batch {@code b}'s first instance: where its records start, or with {@link CgMeshStore#SHARED_RECORD} the
     * one record every instance reads, an INSTANCES draw of the frame's.
     */
    private static int firstInstance(CgFrame.Raster packed, int b) {
        boolean shared = packed.objects[b] == null && (packed.countModes[b] & 3) == CgIndirect.INSTANCES.ordinal();
        return shared ? packed.first[b] | CgMeshStore.SHARED_RECORD : packed.first[b];
    }

    /** Whether batch {@code k} would join {@code b}'s run but for its bindings, shares compared: what bindless would join. */
    private static boolean breaksOnBinding(CgBindingTable table, CgFrame.Raster packed, int b, int k) {
        return packed.objects[b] == null && packed.counts[k] == null && packed.objects[k] == null
                && packed.copyBefore[k] == 0 && packed.pipeline[k] == packed.pipeline[b]
                && table.share(packed.binding[k]) != table.share(packed.binding[b]) && packed.scissor[k] == packed.scissor[b]
                && CgMeshStore.get().joins(mesh(packed, b), mesh(packed, k));
    }

    /** Whether batches {@code b} and {@code k} read the same buffers at the same bindings. */
    private static boolean sameBuffers(CgFrame.Raster packed, int b, int k) {
        int n = CgDrawChunk.BUFFERS;
        for (int s = 0; s < n; s++) {
            if (packed.buffers[k * n + s] != packed.buffers[b * n + s] || packed.bufferAt[k * n + s] != packed.bufferAt[b * n + s]) {
                return false;
            }
        }
        return true;
    }

    /** Whether the batch whose buffer slots start at {@code first} reads a buffer of its own at {@code at}. */
    private static boolean reads(CgFrame.Raster packed, int first, CgBindingPoints.Binding at) {
        for (int s = 0; s < CgDrawChunk.BUFFERS; s++) if (at.equals(packed.bufferAt[first + s])) return true;
        return false;
    }

    // ── Raster on the device (GL6 part 4) ──────────────────────────────────────

    private static final int DEVICE_PREPARE = CgTrace.name("graph.device.prepare"), DEVICE_PASS = CgTrace.name("graph.device.pass"),
            DEVICE_SECTION = CgTrace.name("graph.device.section"), DEVICE_OPEN = CgTrace.name("graph.device.open"),
            DEVICE_BATCHES = CgTrace.name("graph.device.batches"), DEVICE_END = CgTrace.name("graph.device.end");

    /**
     * The depth {@code target}'s passes draw with when it draws with another's ({@code depth}), {@code width} x
     * {@code height}; null where that one lends none or is another size, drawn without depth, logged once.
     */
    @Nullable
    private CgGpuTexture besideDepth(CgGraphTexture target, CgGraphStorage depth, int width, int height) {
        String refused = depth.depth() == null ? depth + " has no depth to draw beside"
                : !depth.lendsAttachments() ? depth + " lends no attachment to a target of ours" : null;
        if (refused != null) {
            if (loggedOnce.add("beside " + target.name())) LOGGER.warn("{} draws without depth: {}", target, refused);
            return null;
        }
        int w = depth.depth().desc().width(), h = depth.depth().desc().height();
        if (w != width || h != height) {
            long sizes = (long) width << 48 | (long) height << 32 | (long) w << 16 | h;
            if (sizes != besideMismatch) {
                besideMismatch = sizes;
                LOGGER.warn("{} is {}x{}, beside a depth of {}x{}: drawn without it", target, width, height, w, h);
            }
            return null;
        }
        return depth.depth();
    }

    /** Throws where {@code pass} cannot be drawn: no storage, or draws counted on the CPU. */
    private void checkDevicePass(CgRasterPass pass, CgFrame.Raster packed) {
        CgGraphStorage storage = storage(targetOf(pass));
        if (storage.color(0) == null && storage.depth() == null) throw new IllegalStateException(pass + " draws into " + storage + ", which has nothing attached");
        // A device runs compute, so its draws are counted on the GPU (gpuCounts)
        for (int b = 0; b < packed.count; b++) {
            if (packed.counts[b] != null && !gpuCounts) throw new IllegalStateException(pass + " counts draws on the CPU beside a device");
        }
    }

    /** The device texture at {@code storage}'s colour slot {@code slot}, or its depth where {@code slot} is -1. */
    private static CgGpuTexture deviceTexture(CgGraphStorage storage, int slot) {
        CgGpuTexture texture = slot < 0 ? storage.depth() : storage.color(slot);
        if (texture == null) throw new IllegalStateException(storage + (slot < 0 ? "'s depth" : "'s colour " + slot) + " has no device texture");
        return texture;
    }

    /**
     * The device texture of {@code pass}'s second attachment, drawn {@code width} x {@code height} beside
     * {@code storage}; null for none. One the device could not attach (another size; beside a target that lends none)
     * is dropped when optional, logged once, and throws when not.
     */
    @Nullable
    private CgGpuTexture deviceSecond(CgRasterPass pass, CgGraphStorage storage, int width, int height) {
        CgGraphTexture attachment = attachmentOf(pass);
        if (attachment == null) return null;
        CgGraphStorage extra = storage(attachment);
        String refused = !storage.lendsAttachments() ? storage + " lends no attachment to draw beside"
                : storage.color(0) == null ? storage + " has no colour to draw beside"
                : extra.width() != width || extra.height() != height
                ? attachment + " is " + extra.width() + "x" + extra.height() + ", beside a target of " + width + "x" + height
                : null;
        if (refused == null) return deviceTexture(extra, 0);
        if (!pass.attachmentOptional()) throw new IllegalStateException(pass + ": " + refused);
        if (loggedOnce.add("second " + pass.name())) LOGGER.warn("{} draws without its second attachment: {}", pass, refused);
        return null;
    }

    /**
     * {@code pass} drawn through {@code CgRHI}: what runs outside a render pass (the pass's snapshot, its instance
     * buffers and tables, every texture's upload, every mesh's placement) first; then, in a device section, a render
     * pass over its target's attachments, each batch's pipeline, sets and draw through the state cache.
     */
    private void rasterDevice(CgFrame frame, CgRasterPass pass, CgFrame.Raster packed, @Nullable int[] damage) {
        CgTrace.add(CgChannels.GL, DEVICE_PASSES, 1);
        long prepared = CgTrace.stamp(CgChannels.GL);
        checkDevicePass(pass, packed);
        CgGraphTexture target = targetOf(pass);
        CgGraphStorage storage = storage(target);
        int level = pass.level;
        int w = storage.levelWidth(level), h = storage.levelHeight(level);
        if (packed.count > 0) {
            frame.bindings.bind(packed.constants);
            CgBoundResources.forgetTextures(CgBindingModel.PASS);
            CgBoundResources.forgetTextures(CgBindingModel.MATERIAL);
            // Before the render pass, as a copy runs outside one: the copies' binds are what the pass set reads
            depthInPlace = frame.readOnlyDepth[currentStep];
            if (pass.depthFrom() != null) copyDepthFrom(pass, pass.depthFrom(), pass.depthFromCopy(), pass.depthFromUnit());
            if (pass.colorFrom() != null) copyColorFrom(pass);
            if ((packed.copyBefore[0] & (CgTargetCopy.COLOR | CgTargetCopy.DEPTH)) != 0) {
                copyTarget(pass, packed.copyBefore[0], packed.copyRect, 0);
            }
            for (int k = 0; k < KINDS; k++) if ((packed.kinds & (1 << k)) != 0) instanceBuffers[k].bind();
            if (tabled && (packed.kinds & (1 << OBJECT)) != 0) primitives.bind();
            packed.palette.pass(pass.viewOwner(), pass.viewX(), pass.viewY(), CgPassConstants.height(pass.constants));
            if ((packed.kinds & UNIT_KINDS) != 0 || packed.tables) {
                packed.clips.bindForDraw();
                packed.shapes.bindForDraw();
                packed.palette.bindForDraw();
            }
            deviceSets.prepare(frame.bindings);
            linkBatches(packed);
        }
        CgLoad load = pass.load;
        boolean clearColor = load.clearsColor();
        PASS_DESCS.begin(w, h);
        deviceFormats.clear();
        // A second attachment is colour 1, after the target's colour 0.
        CgGpuTexture second = deviceSecond(pass, storage, w, h);
        int colors = storage.color(0) != null ? 1 : 0;
        for (int i = 0; i < colors + (second != null ? 1 : 0); i++) {
            CgGpuTexture texture = i == colors ? second : deviceTexture(storage, i);
            // The pass's level and layer of the target, from where an imported one is attached; a second attachment's 0
            PASS_DESCS.color(texture, i == colors ? 0 : storage.colorLevel() + level,
                    i == colors ? 0 : storage.colorLayer() + pass.layer,
                    clearColor && damage == null ? CgPassDesc.LoadOp.CLEAR : CgPassDesc.LoadOp.LOAD,
                    load.r(), load.g(), load.b(), load.a());
            deviceFormats.add(texture.desc().format());
        }
        CgFormat depthFormat = null;
        CgGraphTexture depthOf = pass.depthBeside();
        CgGraphStorage depthStorage = depthOf != null ? storage(depthOf) : storage;
        // A depth only where the pass names one: its own beside its colour, or its target's
        CgGpuTexture depthTexture = depthOf != null ? besideDepth(target, depthStorage, w, h)
                : pass.depthNamed() ? storage.depth() : null;
        boolean clearDepth = depthTexture != null && load.clearsDepth();
        boolean clearStencil = depthTexture != null && load.clearsStencil();
        // A load's depth is far at 1: mirrored for a host whose depth runs far to near
        float clearDepthValue = (float) (CgGraphicsLifecycle.hostDepthReversed() ? 1.0 - load.depth() : load.depth());
        if (depthTexture != null) {
            int depthLayer = depthStorage == storage ? storage.depthLayer() + pass.layer : depthStorage.depthLayer();
            // Under damage loaded and cleared over the damage alone, as colour is
            PASS_DESCS.depth(depthTexture, level, depthLayer,
                    clearDepth && damage == null ? CgPassDesc.LoadOp.CLEAR : CgPassDesc.LoadOp.LOAD, clearDepthValue,
                    clearStencil && damage == null ? CgPassDesc.LoadOp.CLEAR : CgPassDesc.LoadOp.LOAD, load.stencil(),
                    depthInPlace);
            depthFormat = depthTexture.desc().format();
        }
        CgPassDesc desc = PASS_DESCS.end();
        int samples = desc.samples();
        CgRenderState passState = pass.state != null ? pass.state : CgRenderState.DEFAULT;
        CgTrace.zoneDone(CgChannels.GL, DEVICE_PREPARE, prepared);
        long passed = CgTrace.stamp(CgChannels.GL);
        long t = passed;
        CgDeviceSection.begin();
        CgTrace.zoneDone(CgChannels.GL, DEVICE_SECTION, t);
        CgRenderPass rp = null;
        try {
            CgRHI rhi = CgRHI.get();
            devicePassDesc = desc;
            t = CgTrace.stamp(CgChannels.GL);
            rp = open(rhi, devicePassDesc);
            CgTrace.zoneDone(CgChannels.GL, DEVICE_OPEN, t);
            if (clearColor && damage != null) {
                for (int i = 0; i < desc.colors().size(); i++) {
                    rp.clearColor(i, load.r(), load.g(), load.b(), load.a(), damage[0], damage[1], damage[2], damage[3]);
                }
            }
            if ((clearDepth || clearStencil) && damage != null) {
                rp.clearDepthStencil(clearDepth, clearDepthValue, clearStencil, load.stencil(),
                        damage[0], damage[1], damage[2], damage[3]);
            }
            if (packed.count > 0) {
                t = CgTrace.stamp(CgChannels.GL);
                deviceBatches(frame, pass, packed, damage, rhi, passState, depthFormat, samples, w, h);
                CgTrace.zoneDone(CgChannels.GL, DEVICE_BATCHES, t);
            }
            rp = null;
            CgRenderPass ending = devicePass;
            devicePass = null;
            t = CgTrace.stamp(CgChannels.GL);
            ending.end();
            CgTrace.zoneDone(CgChannels.GL, DEVICE_END, t);
        } finally {
            // Ended on a throw too, or the section's end throws over it for the open pass
            if (devicePass != null) {
                devicePass.end();
                devicePass = null;
            } else if (rp != null) {
                rp.end();
            }
            depthInPlace = false;
            long ended = CgTrace.stamp(CgChannels.GL);
            CgDeviceSection.end();
            CgTrace.zoneDone(CgChannels.GL, DEVICE_SECTION, ended);
            CgTrace.zoneDone(CgChannels.GL, DEVICE_PASS, passed);
            if (pass.targetCopy() != null) pass.targetCopy().release(POOL);
        }
    }

    /** Each batch's program, resolved before the device section: what {@link #deviceBatches} draws with. */
    private CgShader[] batchLinked = new CgShader[64];

    /**
     * Every batch's program, resolved before the render pass opens: a program whose compile has landed is built at its
     * first resolve, and the draw loop then only reads what is resolved.
     */
    private void linkBatches(CgFrame.Raster packed) {
        if (batchLinked.length < packed.count) batchLinked = new CgShader[Math.max(packed.count, batchLinked.length * 2)];
        int id = -1;
        CgShader linked = null;
        for (int b = 0; b < packed.count; b++) {
            if (packed.pipeline[b] != id) {
                id = packed.pipeline[b];
                linked = CgPipeline.byId(id).program();
            }
            batchLinked[b] = linked;
        }
    }

    /** The device's render pass open now, and what opened it, which a copy mid-pass opens again loading. */
    @Nullable
    private CgRenderPass devicePass;
    private CgPassDesc devicePassDesc;

    /** Begins {@code desc} as {@link #devicePass}, its viewport the target's, with the state cache emptied. */
    private CgRenderPass open(CgRHI rhi, CgPassDesc desc) {
        CgRenderPass rp = rhi.encoder().beginPass(desc);
        devicePass = rp;
        rp.setViewport(0, 0, desc.width(), desc.height(), 0, 1);
        drawCache.reset();
        return rp;
    }

    /**
     * Batch {@code b}'s target copy, between render passes as Unreal RDG splits one around a copy: the pass ended, the
     * copy made, the pass begun again loading what it drew. Its binds are what the next sets read.
     */
    private CgRenderPass copyMidPass(CgRHI rhi, CgRasterPass pass, CgFrame.Raster packed, int b) {
        CgRenderPass ending = devicePass;
        devicePass = null;
        ending.end();
        CgDeviceSection.end();
        copyTarget(pass, packed.copyBefore[b], packed.copyRect, b * 4);
        CgDeviceSection.begin();
        CgPassDesc d = devicePassDesc;
        List<CgPassDesc.Color> colors = new ArrayList<>(d.colors().size());
        for (CgPassDesc.Color c : d.colors()) {
            colors.add(new CgPassDesc.Color(c.view(), CgPassDesc.LoadOp.LOAD, c.r(), c.g(), c.b(), c.a(), c.store()));
        }
        CgPassDesc.Depth depth = d.depth() == null ? null : new CgPassDesc.Depth(d.depth().view(), CgPassDesc.LoadOp.LOAD,
                d.depth().clearDepth(), CgPassDesc.LoadOp.LOAD, d.depth().clearStencil(), d.depth().store(), d.depth().readOnly());
        CgTrace.add(CgChannels.GL, MID_PASS_SPLITS, 1);
        return open(rhi, new CgPassDesc(d.label(), colors, depth, d.width(), d.height()));
    }

    private void deviceBatches(CgFrame frame, CgRasterPass pass, CgFrame.Raster packed, @Nullable int[] damage,
                               CgRHI rhi, CgRenderState passState, @Nullable CgFormat depthFormat,
                               int samples, int w, int h) {
        CgRenderPass rp = devicePass;
        CgMeshStore store = CgMeshStore.get();
        CgBindingTable table = frame.bindings;
        int boundPipeline = -1, boundScissor = CgRasterPass.INHERIT, slot = 0;
        CgPipeline pipeline = null;
        CgShader linked = null;
        CgPipelineLayout layout = null;
        boolean objectsBound = false;
        CgBindingPoints.Binding[] standIn = this.standIn;
        Arrays.fill(standIn, null);
        Arrays.fill(setLayout, null);
        if (damage == null) rp.setScissor(0, 0, w, h);
        else rp.setScissor(damage[0], damage[1], damage[2], damage[3]);
        for (int b = 0; b < packed.count; b++) {
            if (b > 0 && (packed.copyBefore[b] & (CgTargetCopy.COLOR | CgTargetCopy.DEPTH)) != 0) {
                rp = copyMidPass(rhi, pass, packed, b);
                Arrays.fill(setLayout, null);
                boundScissor = CgRasterPass.INHERIT;
                if (damage == null) rp.setScissor(0, 0, w, h);
                else rp.setScissor(damage[0], damage[1], damage[2], damage[3]);
            }
            int command = packed.counts[b] != null ? slot : -1;
            int end, commandCount = 0;
            if (command >= 0) {
                end = runs[b];
                for (int k = b; k <= end; k++) commandCount += commandsOf(packed, k);
                slot += commandCount;
            } else {
                end = packed.objects[b] == null && packed.buffers[b * CgDrawChunk.BUFFERS] == null && multiDraw
                        && b + 1 < packed.count && joinable(table, packed, b, b + 1) ? joinRun(table, packed, b) : b;
                if (multiDraw && end + 1 < packed.count && CgTrace.isEnabled(CgChannels.GL)
                        && breaksOnBinding(table, packed, b, end + 1)) {
                    CgTrace.add(CgChannels.GL, BINDING_BREAKS, 1);
                }
            }
            if (packed.scissor[b] != boundScissor) {
                boundScissor = packed.scissor[b];
                if (boundScissor == CgRasterPass.NO_SCISSOR) {
                    if (damage == null) rp.setScissor(0, 0, w, h);
                    else rp.setScissor(damage[0], damage[1], damage[2], damage[3]);
                } else if (boundScissor >= 0) {
                    scissorRect(pass, packed.palette, boundScissor);
                    if (damage != null) cutToDamage(damage);
                    rp.setScissor(scissorRect[0], scissorRect[1], scissorRect[2], scissorRect[3]);
                }
            }
            long t = CgTrace.stamp(CgChannels.GL_DETAIL);
            if (packed.pipeline[b] != boundPipeline) {
                boundPipeline = packed.pipeline[b];
                pipeline = CgPipeline.byId(boundPipeline);
                linked = batchLinked[b];
                layout = linked == null ? null : deviceLayout(rhi, boundPipeline, pipeline, linked);
            }
            CgMesh mesh = mesh(packed, b);
            CgGraphicsPipeline device = linked == null ? null
                    : pipeline.device(passState, CgMeshStore.topology(mesh), !CgGraphicsLifecycle.hostDepthZeroToOne(),
                            CgGraphicsLifecycle.hostDepthReversed(), deviceFormats, depthFormat, samples);
            if (device == null) {
                if (loggedOnce.add("skipped " + pipeline)) {
                    LOGGER.warn("device raster: {} skipped in {}: {}", pipeline, pass.name(),
                            linked == null ? "its program did not link" : "no device pipeline");
                }
                if (end > b && command < 0) store.dropJoined();
                CgTrace.add(CgChannels.GL, "graph.batches.skipped", end - b + 1);
                b = end;
                continue;
            }
            drawCache.pipeline(rp, device);
            CgStencilState stencil = pipeline.state().getStencil();
            if (stencil != null && stencil.enabled()) drawCache.stencilReference(rp, stencil.ref());
            CgTrace.zoneDone(CgChannels.GL_DETAIL, BATCH_PIPELINE, t);
            t = CgTrace.stamp(CgChannels.GL_DETAIL);
            int binding = packed.binding[b];
            for (int i = 0; i < table.buffers(binding); i++) table.buffer(binding, i).noteBound();
            int first = b * CgDrawChunk.BUFFERS;
            if (packed.objects[b] != null) {
                bindRecords(packed.objects[b], CgBindingPoints.OBJECT_DATA);
                identityBuffer.noteBound();
                objectsBound = true;
            } else if (objectsBound && packed.kind[b] == OBJECT) {
                if (tabled) primitives.noteBound();
                instanceBuffers[OBJECT].noteBound();
                objectsBound = false;
            }
            for (int s = 0; s < CgDrawChunk.BUFFERS; s++) {
                if (standIn[s] != null && !reads(packed, first, standIn[s])) bindEngineBuffer(standIn[s]);
            }
            for (int s = 0; s < CgDrawChunk.BUFFERS; s++) {
                CgBindingPoints.Binding at = packed.bufferAt[first + s];
                if (at != null) bindRecords(packed.buffers[first + s], at);
                standIn[s] = at;
            }
            // The words its vertex stage pulls from, which a joined run's meshes share
            CgGpuBuffer words = store.vertexWords(mesh);
            if (words != null) CgBoundResources.storage(CgBindingPoints.VERTICES.ssbo(), words, 0, 0);
            // A batch whose ids carry its material's slot binds the arena whole, made from its share; any other its slot
            boolean whole = slotsInIds(packed, b);
            int shared = whole ? table.share(binding) : binding;
            for (int set = 0; set < layout.sets().size(); set++) {
                if (set == CgRHI.BINDLESS_SET) {
                    drawCache.set(rp, set, rhi.bindlessSet());
                    continue;
                }
                CgBindingLayout bl = layout.sets().get(set);
                if (bl.slots().isEmpty()) continue;
                int snapshot = set == CgBindingModel.VIEW ? packed.constants : shared;
                int memo = set == CgBindingModel.VIEW ? snapshot : snapshot * 2 + (whole ? 1 : 0);
                int version = CgBoundResources.version(set);
                if (set < setLayout.length && setLayout[set] == bl && setSnapshot[set] == memo
                        && setVersion[set] == version) {
                    CgTrace.add(CgChannels.GL, SETS_KEPT, 1);
                    drawCache.set(rp, set, setMade[set], setOffsets[set], setOffsetCount[set]);
                    continue;
                }
                CgBindingSet made = deviceSets.set(rhi, table, snapshot, layout, set, null, whole);
                if (set < setLayout.length) {
                    setLayout[set] = bl;
                    setSnapshot[set] = memo;
                    setVersion[set] = version;
                    setMade[set] = made;
                    System.arraycopy(deviceSets.offsets(), 0, setOffsets[set], 0, deviceSets.offsetCount());
                    setOffsetCount[set] = deviceSets.offsetCount();
                }
                drawCache.set(rp, set, made, deviceSets.offsets(), deviceSets.offsetCount());
            }
            CgTrace.zoneDone(CgChannels.GL_DETAIL, BATCH_BINDINGS, t);
            t = CgTrace.stamp(CgChannels.GL_DETAIL);
            if (end > b || command >= 0 && packed.eachFrom[b] >= 0 && eachJoins(packed, b)) {
                if (command >= 0) {
                    int name = commands.buffer();
                    store.drawIndirectJoinedOn(rp, drawCache, mesh, CgDeviceNames.buffer(name),
                            commands.offset(command), commandCount, commands.stride());
                } else {
                    store.drawJoinedOn(rp, drawCache);
                }
                CgTrace.zoneDone(CgChannels.GL_DETAIL, BATCH_DRAW, t);
                b = end;
                continue;
            }
            if (command >= 0) {
                int name = commands.buffer();
                int each = packed.eachFrom[b] >= 0 ? commandCount : 1;
                for (int k = 0; k < each; k++) {
                    store.drawIndirectOn(rp, drawCache, mesh, packed.submesh[b], CgDeviceNames.buffer(name),
                            commands.offset(command + k));
                }
                CgTrace.zoneDone(CgChannels.GL_DETAIL, BATCH_DRAW, t);
                continue;
            }
            store.drawOn(rp, drawCache, mesh, packed.instances[b], packed.first[b], packed.submesh[b], packed.rangeFirst[b],
                    packed.rangeCount[b]);
            CgTrace.zoneDone(CgChannels.GL_DETAIL, BATCH_DRAW, t);
        }
        for (CgBindingPoints.Binding at : standIn) if (at != null) bindEngineBuffer(at);
    }

    /**
     * Each set index's last set this pass and what made it, as Unreal's pending state keeps its descriptors until a binding
     * is dirty: a batch of the same layout, snapshot and {@link CgBoundResources} version binds it again unwritten. For one
     * pass: between passes a buffer may be made again under its name.
     */
    private final CgBindingLayout[] setLayout = new CgBindingLayout[8];
    private final int[] setSnapshot = new int[8], setVersion = new int[8];
    private final CgBindingSet[] setMade = new CgBindingSet[8];
    /** Each kept set's dynamic offsets: a snapshot's blocks are where they were for the batch that made it. */
    private final int[][] setOffsets = new int[8][CgDeviceSets.MAX_DYNAMIC];
    private final int[] setOffsetCount = new int[8];
    private static final int SETS_KEPT = CgTrace.name("bindings.sets-kept");

    /** Each pipeline's device layout by id, made on its first batch; again where its program was linked again. */
    private CgPipelineLayout[] deviceLayouts = new CgPipelineLayout[64];
    private CgShader[] deviceLayoutsOf = new CgShader[64];
    @Nullable
    private CgRHI deviceLayoutsOn;

    private CgPipelineLayout deviceLayout(CgRHI rhi, int id, CgPipeline pipeline, CgShader linked) {
        if (deviceLayoutsOn != rhi) {
            Arrays.fill(deviceLayouts, null);
            Arrays.fill(deviceLayoutsOf, null);
            deviceLayoutsOn = rhi;
        }
        if (id >= deviceLayouts.length) {
            int n = Integer.highestOneBit(id) << 1;
            deviceLayouts = Arrays.copyOf(deviceLayouts, n);
            deviceLayoutsOf = Arrays.copyOf(deviceLayoutsOf, n);
        }
        if (deviceLayoutsOf[id] != linked) {
            deviceLayouts[id] = CgDeviceStates.layout(rhi, linked.explicit());
            deviceLayoutsOf[id] = linked;
        }
        return deviceLayouts[id];
    }

    /** Binds a GPU buffer of records where an engine buffer's macros read the frame's own. */
    /** The identity ids reaching {@code n} at least, uploaded where they reached less: rarely, as a draw's records grow. */
    private void uploadIdentity(int n) {
        int ids = Math.max(n, identityIds * 2);
        if (identity.length < ids * 2) {
            // (i, 0): record i, material slot 0, which an arena bound as one slot reads
            float[] grown = new float[ids * 2];
            for (int i = 0; i < ids; i++) grown[i * 2] = Float.intBitsToFloat(i);
            identity = grown;
        }
        identityBuffer.uploadRaw(identity, ids * 2);
        identityIds = ids;
    }

    private void bindRecords(CgBufferHandle records, CgBindingPoints.Binding at) {
        int id = records instanceof CgGraphBuffer graph ? bufferStorage(graph, false) : records.bufferId();
        CgBoundResources.storage(at.ssbo(), id, 0, 0);
    }

    /** Binds the engine buffer made at {@code at} again, after draws that read their own there. */
    private void bindEngineBuffer(CgBindingPoints.Binding at) {
        CgShaderBuffer owner = CgShaderBufferRegistry.get().ownerOf(at);
        if (owner != null) owner.noteBound();
    }

    /** Copies the depth of the target a pass reads besides its own, whole, and binds it. */
    private void copyDepthFrom(CgPass pass, CgGraphTexture from, CgTargetCopy copy, int unit) {
        CgGraphTexture depth = from;
        if (takes && depth.kind() != CgGraphTexture.Kind.EXTERNAL && storage(depth).sampledDepth() != null
                && !(pass instanceof CgRasterPass raster && depthTexture(raster) == depth)) {
            // Not attached here, and moved to SAMPLED_READ with the step: sampled where it is, as Unreal samples SceneDepth.
            CgBoundResources.texture(unit, storage(depth).sampledDepth(), -1);
            CgTrace.add(CgChannels.GL, DEPTH_SAMPLED, 1);
            return;
        }
        copy.borrowDepth(sharedDepth(storage(depth), pass));
        CgBoundResources.texture(unit, copy.depth, -1);
    }

    /** The storage whose depth {@code pass} draws into; null where it draws with none. */
    @Nullable
    private CgGraphStorage depthDestination(CgRasterPass pass) {
        CgGraphStorage storage = storage(depthTexture(pass));
        return storage.depth() != null ? storage : null;
    }

    /** Whether {@link #sharedDepth} holds a copy of {@code storage}'s depth. */
    private boolean sharesDepth(@Nullable CgGraphStorage storage) {
        return storage != null && sharedDepthSource != null && storage.depth() == sharedDepthSource
                && storage.depthLayer() == sharedDepthLayer;
    }

    /** The execution's copy of {@code source}'s depth, copied now unless it already holds it. */
    private CgTargetCopy sharedDepth(CgGraphStorage source, CgPass pass) {
        if (sharesDepth(source)) {
            CgTrace.add(CgChannels.GL, DEPTH_SHARED, 1);
            return sharedDepth;
        }
        sharedDepth.copyDepth(source, pass, POOL);
        sharedDepthSource = source.depth();
        sharedDepthLayer = source.depthLayer();
        CgTrace.add(CgChannels.GL, TARGET_COPIES, 1);
        return sharedDepth;
    }

    /** Copies another target's colour whole, for a pass reading it ({@link CgRasterPass#readsColor(CgGraphTexture)}). */
    private void copyColorFrom(CgRasterPass pass) {
        CgTargetCopy copy = pass.colorFromCopy();
        copy.copyColor(storage(pass.colorFrom()), POOL);
        CgBoundResources.texture(pass.colorFromUnit(), copy.color, -1);
        CgTrace.add(CgChannels.GL, TARGET_COPIES, 1);
    }

    /**
     * The pass's indirect commands, one per indirect batch in batch order, written before the pass begins; each
     * count's own barrier came with the pass's access list. Where draws join, decides the runs a multi-draw draws
     * ({@link #runs}) and writes their commands in its form.
     */
    private void writeCommands(CgBindingTable table, CgRasterPass pass, CgFrame.Raster packed) {
        int args = commands.reserve(packed.indirects, FORGET_BUFFER);
        bufferBarrier(args, CgAccess.COMPUTE_WRITE);
        commandsTable = table;
        commandsConstants = packed.constants;
        if (runs.length < packed.count) runs = new int[Math.max(packed.count, runs.length * 2)];
        int slot = 0;
        for (int b = 0; b < packed.count; b++) {
            if (packed.counts[b] == null) continue;
            int end = b;
            if (multiDraw) {
                while (end + 1 < packed.count && joinableIndirect(table, packed, b, end + 1)) end++;
                if (end + 1 < packed.count && CgTrace.isEnabled(CgChannels.GL)) countBreak(table, packed, b, end + 1);
            }
            runs[b] = end;
            for (int k = b; k <= end; k++) slot += writeCommand(packed, k, slot);
            b = end;
        }
        commands.flush();
        commandsTable = null;
        bufferBarrier(args, CgAccess.INDIRECT);
        CgTrace.add(CgChannels.GL, COMMAND_COUNT, packed.indirects);
    }

    /** The commands {@link #writeCommands} queued, written by their kernel: what {@link #commands} flushes into. */
    private void dispatchCommands() {
        commandsOnDevice(commandsTable, commandsConstants);
    }

    /** The commands queued, written by their kernel through the device's compute pass. */
    private void commandsOnDevice(CgBindingTable table, int constants) {
        if (!commands.stage()) return;
        CgRHI rhi = CgRHI.get();
        CgKernelProgram program = commands.program();
        CgComputePipeline pipeline = program.devicePipeline(rhi);   // made outside the section
        CgBindings k = kernelBindings.clear();
        storageSlot(k, CgBindingModel.FIRST_KERNEL_BUFFER + program.bufferIndex("COUNT"), commands.stagedCount, 0, 0);
        storageSlot(k, CgBindingModel.FIRST_KERNEL_BUFFER + program.bufferIndex("ARGS"), commands.argsBuffer(),
                commands.stagedArgs, commands.stagedArgsBytes);
        k.buffer(CgBindingModel.FIRST_KERNEL_BUFFER + program.bufferIndex("PARAMS"), CgBindingLayout.Type.STORAGE_BUFFER,
                commands.params().gpuBuffer(), commands.stagedParams, commands.stagedParamsBytes);
        DEVICE_COMPUTE.begin("graph.commands");
        try {
            CgPipelineLayout layout = program.deviceLayout();
            DEVICE_COMPUTE.pass(rhi);
            DEVICE_COMPUTE.pipeline(pipeline, layout);
            for (int set = 0; set < layout.sets().size(); set++) {
                if (set == CgRHI.BINDLESS_SET || layout.sets().get(set).slots().isEmpty()) continue;
                CgBindingSet s = deviceSets.set(rhi, table, constants, layout, set, set == CgBindingModel.MATERIAL ? k : null);
                DEVICE_COMPUTE.set(set, s, deviceSets.offsets(), deviceSets.offsetCount());
            }
            program.dispatchOn(DEVICE_COMPUTE.pass(rhi), commands.stagedElements, 1, 1);
        } finally {
            DEVICE_COMPUTE.close();
        }
    }

    /**
     * Batch {@code b}'s commands from {@code slot}, answering how many: one, or one per record of a batch of
     * {@code indirectEach}, each with its first instance.
     */
    private int writeCommand(CgFrame.Raster packed, int b, int slot) {
        CgBufferHandle count = packed.counts[b];
        int countId;
        if (count instanceof CgGraphBuffer graph) {
            countId = bufferStorage(graph, false);
        } else {
            countId = count.bufferId();
            bufferBarrier(countId, CgAccess.COMPUTE_READ);
        }
        CgMeshStore store = CgMeshStore.get();
        boolean drawn = store.range(packed.mesh[b], packed.submesh[b], packed.rangeFirst[b], packed.rangeCount[b], range);
        if (!drawn) Arrays.fill(range, 0);   // a mesh with nothing to draw: a command of nothing
        CgIndirect mode = INDIRECT_MODES[packed.countModes[b] & 3];
        int factor = packed.countModes[b] >>> 2;
        if (packed.eachFrom[b] >= 0) {
            int n = packed.instances[b];
            for (int k = 0; k < n; k++) {
                long at = packed.eachOffsets[packed.eachFrom[b] + k];
                commands.write(slot + k, countId, at, mode, factor, range, 1, -1, packed.first[b] + k);
            }
            return n;
        }
        commands.write(slot, countId, packed.countOffsets[b], mode, factor, range, packed.instances[b],
                packed.objects[b] != null ? packed.instances[b] : -1, firstInstance(packed, b));
        return 1;
    }

    /** Commands batch {@code b} draws: one, or one per record of a batch of {@code indirectEach}. */
    private static int commandsOf(CgFrame.Raster packed, int b) {
        return packed.eachFrom[b] >= 0 ? packed.instances[b] : 1;
    }

    /** Whether batch {@code b} of {@code indirectEach} draws its commands as one multi-draw: where draws join its mesh. */
    private boolean eachJoins(CgFrame.Raster packed, int b) {
        CgMesh mesh = mesh(packed, b);
        return multiDraw && CgMeshStore.get().joins(mesh, mesh);
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

    /**
     * Copies what {@code bits} name from the pass's target for the draws sampling it, colour in the rect at {@code at}
     * of {@code rects}, and binds the copies.
     */
    private void copyTarget(CgRasterPass pass, int bits, int[] rects, int at) {
        CgTargetCopy copy = pass.targetCopy();
        CgGraphStorage colour = storage(targetOf(pass)), depth = storage(depthTexture(pass));
        if (depthInPlace && (bits & CgTargetCopy.DEPTH) != 0) {
            // Read-only in this pass: sampled where it is, no copy.
            CgBoundResources.texture(pass.sceneDepthUnit(), depth.sampledDepth(), -1);
            bits &= ~(CgTargetCopy.DEPTH | CgTargetCopy.CLEAN);
            if (bits == 0) return;
        }
        if ((bits & (CgTargetCopy.DEPTH | CgTargetCopy.CLEAN)) == (CgTargetCopy.DEPTH | CgTargetCopy.CLEAN)) {
            // The target's depth as the pass found it: what an earlier pass may have copied already.
            copy.borrowDepth(sharedDepth(depth, pass));
            CgBoundResources.texture(pass.sceneDepthUnit(), copy.depth, -1);
            bits &= ~(CgTargetCopy.DEPTH | CgTargetCopy.CLEAN);
            if (bits == 0) return;
        }
        bits &= ~CgTargetCopy.CLEAN;
        long pixels = copy.copy(colour, depth, bits, rects, at, POOL);
        if ((bits & CgTargetCopy.COLOR) != 0) CgBoundResources.texture(pass.sceneColorUnit(), copy.color, -1);
        if ((bits & CgTargetCopy.DEPTH) != 0) CgBoundResources.texture(pass.sceneDepthUnit(), copy.depth, -1);
        CgTrace.add(CgChannels.GL, TARGET_COPIES, 1);
        CgTrace.add(CgChannels.GL, TARGET_COPY_PIXELS, pixels);
    }

    /**
     * The current target what a callback executes takes ({@link #into}): its target's storage, with the depth it draws
     * with; null, the current target as it is, for none.
     */
    @Nullable
    private Into intoCallback(@Nullable CgGraphTexture target, @Nullable CgGraphTexture depth) {
        if (target == null || target.isCurrent()) return null;
        return into(storage(target).asTarget(depth != null ? storage(depth) : null));
    }

    /**
     * {@code r}'s copy on the device, its step's barriers having made the source readable as a copy's; its sink hears
     * from {@link CgReadback#poll} once the GPU has finished.
     */
    private static void readback(CgPass.Readback r) {
        r.delivering = true;
        if (r.buffer != null) {
            CgReadback.buffer(bufferStorage(r.buffer, false), r.offset, r.size, r);
            return;
        }
        CgGraphStorage storage = storage(r.texture);
        boolean slices = storage.isVolume() || storage.isArray();
        CgReadback.texture(storage.color(0), new CgTextureRegion(r.level, r.x, r.y, slices ? r.z : 0,
                r.w, r.h, slices ? r.d : 1), r);
    }

    /** A released storage's textures released, its states forgotten. */
    private static void free(@Nullable CgGraphStorage storage) {
        if (storage == null) return;
        forgetStorage(storage);
        storage.release();
    }

    /** {@code storage}'s states forgotten, before it is released: its images' names are dropped with it. */
    private static void forgetStorage(CgGraphStorage storage) {
        STATES.forget(storage.key());
        for (int slot = 0; slot < storage.colorSlots(); slot++) {
            CgGpuTexture color = storage.color(slot);
            if (color != null) STATES.forget(CgGraphStorage.key(color));
        }
    }

    /**
     * A texture's storage now: the current target's as the execution opened it, an imported one's as made with it, a
     * requested one's made on first use, a history's both versions, cleared.
     */
    static CgGraphStorage storage(CgGraphTexture texture) {
        CgGraphStorage[] versions = texture.versions();
        if (versions != null && versions[0] == null) {
            CgTrace.add(CgChannels.GL, "graph.history.made", 1);
            try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, MAKE_STORAGE)) {
                for (int v = 0; v < 2; v++) {
                    versions[v] = CgTexturePool.create("cg_graph_" + texture.resource().name() + v, texture.desc());
                    forgetStorage(versions[v]);
                    versions[v].clearColor();
                }
            }
        }
        CgGraphStorage storage = texture.storage();
        if (storage == null && texture.kind() == CgGraphTexture.Kind.REQUESTED) {
            // At a requested texture's first use; made again later, the picture it held was lost.
            CgTrace.add(CgChannels.GL, "graph.requested.made", 1);
            try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, MAKE_STORAGE)) {
                storage = CgTexturePool.create("cg_graph_" + texture.name(), texture.desc());
            }
            forgetStorage(storage);
            texture.resolve(storage);
        }
        if (storage == null) {
            throw new IllegalStateException(texture.isCurrent() ? "no current target is open: open CgExecutor.into round"
                    + " the execution" : texture + " has no storage in this pass");
        }
        return storage;
    }
}
