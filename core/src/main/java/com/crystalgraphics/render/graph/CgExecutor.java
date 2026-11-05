package com.crystalgraphics.render.graph;

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
import com.crystalgraphics.gl.shader.CgShader;
import com.crystalgraphics.platform.rhi.pipeline.CgPipelineLayout;
import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.platform.gl.CgGLImports;
import com.crystalgraphics.render.shader.CgBindingModel;
import com.crystalgraphics.render.draw.CgBindingTable;
import com.crystalgraphics.render.draw.CgDeviceStates;
import com.crystalgraphics.render.draw.CgDeviceSets;
import com.crystalgraphics.render.draw.CgBoundResources;
import com.crystalgraphics.api.framebuffer.CgFrameBufferFormat;
import com.crystalgraphics.variable.CgVar;
import com.crystalgraphics.api.CgBindingPoints;
import com.crystalgraphics.render.draw.CgDrawChunk;
import com.crystalgraphics.api.buffer.CgBufferLifetime;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.state.CgAlphaState;
import com.crystalgraphics.api.state.CgColorMask;
import com.crystalgraphics.api.state.CgStencilState;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.compute.emit.CgKernelTarget;
import com.crystalgraphics.compute.program.CgComputeCheck;
import com.crystalgraphics.compute.program.CgKernelProgram;
import com.crystalgraphics.compute.source.CgImageAccess;
import com.crystalgraphics.compute.source.CgImageDecl;
import com.crystalgraphics.compute.source.CgImageDimension;
import com.crystalgraphics.gl.buffer.CgBufferReadback;
import com.crystalgraphics.gl.buffer.CgReadback;
import com.crystalgraphics.gl.texture.CgSamplers;
import com.crystalgraphics.gl.buffer.CgFrameRing;
import com.crystalgraphics.gl.buffer.CgStreamBuffer;
import com.crystalgraphics.gl.buffer.shader.CgEngineBufferRegistry;
import com.crystalgraphics.gl.buffer.shader.CgShaderBuffer;
import com.crystalgraphics.gl.buffer.shader.CgShaderBufferRegistry;
import com.crystalgraphics.gl.framebuffer.CgFrameBuffer;
import com.crystalgraphics.gpu.CgDeferral;
import com.crystalgraphics.platform.rhi.command.CgAccess;
import com.crystalgraphics.platform.gl.CgCapabilities.ComputeTier;
import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.platform.gl.state.CgGlScope;
import com.crystalgraphics.platform.gl.state.CgGlState;
import com.crystalgraphics.render.CgCpuMeter;
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
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

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
     * One copy of a framebuffer's depth, read by every pass after it that reads that depth until something may write
     * it: Unreal resolves scene depth once after the base pass, where each pass copying its own cost a blit apiece.
     * Released when the execution ends; -1 when it holds nothing.
     */
    private final CgTargetCopy sharedDepth = new CgTargetCopy();
    private int sharedDepthSource = -1, sharedDepthWidth, sharedDepthHeight;
    private static final int KINDS = CgInstanceKind.values().length;
    private static final int UNIT_KINDS = (1 << CgInstanceKind.QUAD.ordinal()) | (1 << CgInstanceKind.CURVE.ordinal());
    private static final int OBJECT = CgInstanceKind.OBJECT.ordinal();
    /** What every QUAD and CURVE instance expands. */
    private static final CgMesh UNIT_QUAD = CgMesh.quads(1);

    private static final List<CgExecutor> BY_DEPTH = new ArrayList<>();
    private static final CgTexturePool POOL = new CgTexturePool();
    private static final CgComposedTargets COMPOSED = new CgComposedTargets(true), BESIDE = new CgComposedTargets(false);
    private static final CgBufferPool BUFFERS = new CgBufferPool();
    private static final CgResourceStates STATES = new CgResourceStates();
    /**
     * The compute pass executing, numbered: its overlap sets, one a skip group ({@link CgDispatch#skipBarrier}), and
     * its claims on storage ({@link #prologue}).
     */
    private static int passSerial;
    private static final IntConsumer FORGET_BUFFER = CgExecutor::forget;
    private static final Consumer<CgFrameBuffer> FORGET_STORAGE = CgExecutor::forgetStorage;
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
    /** Every compute pass that can go async does, as if marked: a correctness check of the waits. */
    /** GL6's check: each batch also makes its device sets, and a slot left to a default is logged once. */
    /** GL6 part 4: a raster pass the device path covers draws through {@code CgRHI}; the rest through {@code CgGL}. */
    static final boolean DEVICE_RASTER = CgVar.bool("crystalgraphics.rhi.deviceRaster", false, CgVar.DEV | CgVar.RESTART,
            "A raster pass the device path covers draws through CgRHI, the rest through CgGL, each reason logged once.").get();
    /** Passes whose names hold one of these stay on {@code CgGL}: for finding which pass a picture differs in. */
    private static final String[] DEVICE_RASTER_SKIP = CgVar.text("crystalgraphics.rhi.deviceRasterSkip", "", CgVar.DEV
            | CgVar.RESTART, "Comma-separated parts of pass names kept on CgGL under deviceRaster.").get().split(",");
    private static final int DEVICE_PASSES = CgTrace.name("graph.device-passes"), GL_PASSES = CgTrace.name("graph.gl-passes"),
            MID_PASS_SPLITS = CgTrace.name("graph.device-pass-splits");
    private final CgDrawStateCache drawCache = new CgDrawStateCache();
    private final Set<String> glPassReasons = new HashSet<>();
    private final List<CgPassDesc.Color> deviceColors = new ArrayList<>(4);
    private final List<CgFormat> deviceFormats = new ArrayList<>(4);
    /** While a pass draws on the device: a bind notes what it binds ({@code CgBoundResources}) and issues nothing. */
    private boolean stating;
    private long devicePasses, glPasses, passesReport = 10;

    static final boolean DEVICE_SETS = CgVar.bool("crystalgraphics.rhi.deviceSets", false, CgVar.DEV | CgVar.RESTART,
            "Each batch also makes its view, pass and material sets on the device, logging a slot left to a default.").get();
    private final CgDeviceSets deviceSets = new CgDeviceSets();
    private final Set<String> deviceSetsLogged = new HashSet<>();

    static final boolean ASYNC_ALL = CgVar.bool("crystalgraphics.graph.asyncAll", false, CgVar.DEV | CgVar.RESTART,
            "Every compute pass that can go async does, as if marked async(): a check of the waits, which the compute"
                    + " scenes must pass under synchronization validation.").get();
    private static final int ASYNC_PASSES = CgTrace.name("graph.async-passes");
    /** The GPU's copies before a frame's first pass: what other threads asked of GPU objects, and the meshes' bytes. */
    private static final int GPU_DEFERRED = CgGpuTrace.name("upload.deferred"), GPU_MESHES = CgGpuTrace.name("upload.meshes");
    private static final int TRIM_POOLS = CgTrace.name("graph.trimPools");
    private static final int ASYNC_WAITS = CgTrace.name("graph.async-waits");
    private static final int ACQUIRE = CgTrace.name("graph.acquire"), MAKE_STORAGE = CgTrace.name("graph.makeStorage"),
            WRITE_COMMANDS = CgTrace.name("graph.writeCommands"), RASTER_BEGIN = CgTrace.name("graph.rasterBegin"),
            RASTER_TARGET = CgTrace.name("graph.rasterBegin.target"), RASTER_CLEAR = CgTrace.name("graph.rasterBegin.clear"),
            RASTER_BINDINGS = CgTrace.name("graph.rasterBegin.bindings"), RASTER_COMPOSED = CgTrace.name("graph.rasterBegin.composed"),
            RASTER_BESIDE = CgTrace.name("graph.rasterBegin.beside"), RASTER_OWN = CgTrace.name("graph.rasterBegin.own"),
            STORAGE_MADE = CgTrace.name("graph.storage-made-bytes"),
            BUFFER_STEP = CgTrace.name("graph.bufferStep"), DISPATCH_PROGRAM = CgTrace.name("graph.dispatch.program"),
            DISPATCH_BIND = CgTrace.name("graph.dispatch.bind"), DISPATCH_ISSUE = CgTrace.name("graph.dispatch.issue"),
            FILL = CgTrace.name("graph.fill"), UPDATE_STAGE = CgTrace.name("graph.update.stage"),
            UPDATE_COPY = CgTrace.name("graph.update.copy");
    // The rest of an execution's body, so a driver wait in any of it is named (T10.5).
    private static final int UPLOADS = CgTrace.name("graph.uploads"), GPU_TIMER = CgTrace.name("graph.gpuTimer"),
            AWAIT_ASYNC = CgTrace.name("graph.awaitAsync"),
            BARRIER = CgTrace.name("graph.barrier"), UNPIN = CgTrace.name("graph.unpin"),
            STEP_READBACK = CgTrace.name("graph.readback"), STEP_COPY = CgTrace.name("graph.copy"),
            STEP_UPLOAD = CgTrace.name("graph.upload"), STEP_CALLBACK = CgTrace.name("graph.callback"),
            STEP_COMPILE = CgTrace.name("graph.compile"), STEP_RELEASE = CgTrace.name("graph.release"),
            STALE = CgTrace.name("graph.stale"), GIVE_BACK = CgTrace.name("graph.giveBack"),
            FINISH = CgTrace.name("graph.finish");
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
    /** The engine buffers the last batch's buffer()s stood in for: the batch loop's, kept so it allocates nothing. */
    private final CgBindingPoints.Binding[] standIn = new CgBindingPoints.Binding[CgDrawChunk.BUFFERS];
    private final CgIndirectArgs commands = new CgIndirectArgs();
    /** Per file, what its dispatches below compute bind: lowered, or run by a Java body. */
    private final int[] range = new int[4];
    /** Per indirect batch starting a run of the pass about to draw, the run's last batch: itself when none joins it. */
    private int[] runs = new int[64];
    /** The framebuffer and viewport bound when this execution began, where a pass reads the current target's depth. */
    private int startFramebuffer;
    private boolean startNoted, otherBound;
    /**
     * Whether the pass executing drew through a framebuffer of ours: with its second attachment, or with its target
     * beside the current target's depth.
     */
    private boolean composed, beside;
    /**
     * The storage a pass that {@link CgRasterPass#replaces} its target found there, sampled as its scene colour while
     * it draws into fresh storage; given back to the pool when it ends. Null for every other pass.
     */
    @Nullable
    private CgFrameBuffer renamedFrom;
    /**
     * Whether a pipeline of the pass executing turned the stencil test on: turned off at its end, since a later pass's
     * pipeline that declares no stencil would inherit it, into a target with a stencil of its own.
     */
    private boolean stencilled;
    /**
     * Whether a raster pass left a slab's vertex array bound: kept for the next raster pass, unbound before any other
     * step and at the execution's end, since an element-buffer bind under it would rewrite the vertex array.
     */
    private boolean vaoHeld;
    /** The last sizes a {@link CgGraphTexture#besideCurrentDepth} target and its host disagreed at, logged once. */
    private static long besideMismatch;
    private final IntBuffer startViewport = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asIntBuffer();
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
     * Images it moved: key, GL name, levels, layers and whether it outlives the execution, each. Those that do return to
     * rest at its end; all of them before a callback's body, which is GL's and finds every image at rest.
     */
    private long[] movedKeys = new long[16];
    private int[] movedNames = new int[16], movedLevels = new int[16], movedLayers = new int[16];
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
    /** Where a blit's source framebuffer was before it ({@link #blitting}), returned there after. */
    private int blitSourceWas;

    private CgExecutor(int depth) {
        // A region of 1 MB: a ring outgrowing its region is new storage, which waits for a threaded driver to drain.
        ring = CgStreamBuffer.createFrameLocal(CgGL.GL_UNIFORM_BUFFER, 1 << 20);
        CgBoundResources.recording = DEVICE_SETS || DEVICE_RASTER;
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
                }
                trimmedFrame = ringFrame;
            }
        }
        if (BY_DEPTH.size() == depth) BY_DEPTH.add(new CgExecutor(depth));
        CgExecutor executor = BY_DEPTH.get(depth);
        depth++;
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, "graph.execute");
             CgGlScope ignored2 = restoreState ? CgGlState.saveAll() : null) {
            // A compatibility host's fixed-function alpha test discards our fragments too, and only a material
            // declaring AlphaTest means one: 1.7.10 leaves GREATER 0.1 on, which drops every faint additive draw.
            if (restoreState) CgAlphaState.DISABLED.apply();
            CgSamplers.forget();   // the host may have bound samplers of its own since
            try {
                executor.run(frame);
            } finally {
                executor.releaseVertexArray();
                int bound = CgGlState.drawFramebuffer();   // what the last pass left, as before the detach moved here
                if (COMPOSED.detach() | BESIDE.detach()) CgGL.glBindFramebuffer(CgGL.GL_FRAMEBUFFER, bound);
            }
        } finally {
            CgSamplers.unbindOwn();   // ours never reach the host's textures, nor an outer execution's next draw
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
     * Makes the executors of the first {@code depths} nesting depths now. Each maps a ring and an indirect-args table,
     * and a map waits for a threaded driver's queue, deepest while loading: an immediate draw's first nested execution
     * held the loading screen 117 ms (1.20.1). At the engine's start, after {@code CgBindingPoints.init}. Render thread.
     */
    public static void ahead(int depths) {
        while (BY_DEPTH.size() < depths) BY_DEPTH.add(new CgExecutor(BY_DEPTH.size()));
    }

    /** The host's targets are read again at their next composed bind: at a window resize, which may remake them. */
    public static void forgetHostTargets() {
        COMPOSED.forgetHost();
        BESIDE.forgetHost();
        for (CgExecutor executor : BY_DEPTH) executor.hostRead = -1;
    }

    /**
     * Frees every executor's ring, the transient pools and every persistent or history buffer's storage still held. At
     * context teardown, before the framebuffer sweep.
     */
    public static void destroyAll() {
        for (CgExecutor executor : BY_DEPTH) {
            if (DEVICE_SETS) executor.deviceSets.release();
            executor.ring.delete();
            executor.commands.delete(FORGET_BUFFER);
            if (executor.staging != null) executor.staging.delete();
        }
        BY_DEPTH.clear();
        POOL.delete();
        BUFFERS.delete();
        COMPOSED.delete();
        BESIDE.delete();
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
        takes = CgGL.cgTakesTransitions();
        boolean wasStated = takes && CgGL.cgGraphStates(true);
        boolean splits = takes && !again && SPLIT.get();
        moved = 0;
        splitCount = 0;
        runStart = runEnd = -1;
        multiDraw = caps.multiDraw() && CgMeshStore.get().multiDraw();
        // The current target, noted before any pass binds its own: rebound for a pass into it after one into another,
        // and what a copy of it reads. From the state shadow, since a glGet waits for the driver to drain the queue.
        startNoted = takes || frame.readsCurrentDepth || frame.rastersCurrent;
        otherBound = false;
        if (startNoted) {
            startFramebuffer = CgGlState.drawFramebuffer();
            CgGlState.viewport(startViewport);
        }
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, UPLOADS)) {
            frame.bindings.upload(ring);
            for (int k = 0; k < KINDS; k++) {
                if (frame.instanceFloats[k] > 0) instanceBuffers[k].uploadRaw(frame.instances[k], frame.instanceFloats[k]);
            }
        }
        boolean gpu = uploadGpuZones();
        if (gpu) CgGpuTrace.begin(GPU_MESHES);
        try {
            placeMeshes(frame);
        } finally {
            if (gpu) CgGpuTrace.end();
        }
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
                if (again) {
                    try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, STALE)) {
                        staleTransients(frame, s, skipped);
                    }
                }
                if (takes && !again && s > runEnd) merge(frame, s);
                if (splitCount > 0) endSplits(runEnd);
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
                        if (frame.transients.get(t) instanceof CgGraphTexture texture && texture.framebuffer() != null
                                && texture.framebuffer().getId() == sharedDepthSource) {
                            sharedDepthSource = -1;
                        }
                        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, GIVE_BACK)) {
                            giveBack(frame.transients.get(t));
                        }
                        resolved--;
                    }
                }
            }
        } finally {
            try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, FINISH)) {
                if (takes) {
                    if (splitCount > 0) endSplits(Integer.MAX_VALUE);
                    restMoved(false);
                    CgGL.cgGraphStates(wasStated);
                }
                sharedDepth.release(POOL);
                sharedDepthSource = -1;
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
        boolean kernelOrBuffer = pass instanceof CgComputePass || pass instanceof CgPass.Fill
                || pass instanceof CgPass.Update || pass instanceof CgPass.BufferCopy;
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

    private static void acquire(CgGraphResource resource) {
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, ACQUIRE)) {
            if (resource instanceof CgGraphTexture texture) {
                texture.resolve(POOL.acquire(texture.desc()));
            } else {
                CgGraphBuffer buffer = (CgGraphBuffer) resource;
                buffer.resolve(BUFFERS.acquire(buffer.desc()));
            }
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
                || pass instanceof CgPass.BufferRelease || pass instanceof CgPass.Readback) return true;
        if (pass instanceof CgPass.Fill || pass instanceof CgPass.Update || pass instanceof CgPass.BufferCopy) {
            return frame.outlives[s];
        }
        if (pass instanceof CgComputePass compute) return frame.outlives[s] && !compute.runsAgain();
        if (pass.target != null && pass.target.kind() == CgGraphTexture.Kind.HISTORY) return true;   // one version a frame
        return keepRequested && pass.target != null && pass.target.kind() == CgGraphTexture.Kind.REQUESTED;
    }

    private void step(CgFrame frame, int s) {
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
        if (!(pass instanceof CgRasterPass)) releaseVertexArray();
        try {
            boolean async = pass instanceof CgComputePass compute && runsAsync(compute);
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
            if (!(pass instanceof CgComputePass)) {
                try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, BARRIER)) {
                    barriers(frame, s);
                }
            }
            if (pass instanceof CgRasterPass raster) {
                raster(frame, raster, frame.rasters[s]);
                if (frame.rasters[s].writesDepth && depthDestination(raster) == sharedDepthSource) sharedDepthSource = -1;
                try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, UNPIN)) {
                    unpinLevels(frame, s);
                }
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
            } else if (pass instanceof CgPass.Fill || pass instanceof CgPass.Update || pass instanceof CgPass.BufferCopy) {
                try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, BUFFER_STEP)) {
                    bufferStep(pass);
                }
            } else if (pass instanceof CgPass.Readback readback) {
                try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, STEP_READBACK)) {
                    readback(readback);
                }
            } else if (pass instanceof CgPass.BufferRelease release) {
                try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, STEP_RELEASE)) {
                    freeKept(release.buffer);
                }
                KEPT.remove(release.buffer);
            } else if (pass instanceof CgPass.Copy copy) {
                try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, STEP_COPY)) {
                    CgFrameBuffer from = storage(copy.from), to = storage(copy.target);
                    CgFrameBuffer.blitFrom(from.getId(), to.getId(), copy.x, copy.y, copy.x + copy.w, copy.y + copy.h,
                            copy.tx, copy.ty, copy.tx + copy.tw, copy.ty + copy.th, CgGL.GL_COLOR_BUFFER_BIT,
                            copy.linear ? CgGL.GL_LINEAR : CgGL.GL_NEAREST);
                }
            } else if (pass instanceof CgPass.Upload upload) {
                sharedDepthSource = -1;
                try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, STEP_UPLOAD)) {
                    upload.writer.upload(storage(upload.target));
                }
                upload.request.complete();
            } else if (pass instanceof CgPass.Callback callback) {
                sharedDepthSource = -1;   // its body may draw anything
                boolean bound = otherBound;   // the scope restores the binding it found
                try (CgTrace.Zone zone = CgTrace.zone(CgChannels.GL, STEP_CALLBACK);
                     CgGlScope ignored = CgGlState.saveAll()) {
                    boolean drawsBeside = callback.target != null && callback.target.drawsBesideCurrentDepth()
                            && bindBeside(callback.target);
                    if (!drawsBeside) bindTarget(callback.target, 0, 0);
                    if (takes) restMoved(true);   // its body is GL's: the device infers, from images at rest
                    boolean stated = takes && CgGL.cgGraphStates(false);
                    try {
                        callback.body.run();
                    } finally {
                        if (takes) CgGL.cgGraphStates(stated);
                        if (drawsBeside) BESIDE.detach();
                    }
                }
                otherBound = bound;
                callback.request.complete();
            } else if (pass instanceof CgPass.Compile compile) {
                try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, STEP_COMPILE)) {
                    if (compile.pipeline.precache()) {
                        if (compile.pipeline.program() != null) compile.request.complete();
                        else compile.request.fail(String.valueOf(compile.pipeline.shader().lastCompileError()));
                    }
                }
            } else if (pass instanceof CgPass.Release) {
                sharedDepthSource = -1;
                try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, STEP_RELEASE)) {
                    CgFrameBuffer[] versions = pass.target.versions();
                    if (versions != null) {
                        for (int v = 0; v < versions.length; v++) {
                            free(versions[v]);
                            versions[v] = null;
                        }
                    } else {
                        free(pass.target.framebuffer());
                        pass.target.resolve(null);
                    }
                }
            }
        } catch (RuntimeException failure) {
            if (pass.request == null) throw failure;
            LOGGER.warn("{} failed", pass, failure);
            pass.request.fail(failure.getMessage() == null ? failure.toString() : failure.getMessage());
        }
    }

    private void bufferStep(CgPass pass) {
        if (pass instanceof CgPass.Fill fill) {
            int id = bufferStorage(fill.buffer, true);
            boolean detail = CgTrace.isEnabled(CgChannels.GL_DETAIL);
            try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL_DETAIL, FILL);
                 CgTrace.Zone named = detail ? CgTrace.zone(CgChannels.GL_DETAIL, CgTrace.name("graph.fill/" + fill.buffer.name())) : null) {
                CgGL.cgFillBuffer(id, fill.offset, fill.size, fill.value);
            }
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
        }
    }

    // ── Compute ──────────────────────────────────────────────────────────────

    private void compute(CgFrame frame, CgComputePass pass, CgFrame.Compute packed) {
        List<CgDispatch> dispatches = pass.dispatches();
        if (++passSerial == 1 << 28) passSerial = 1;   // a set keeps three bits for its group
        prologue(dispatches);
        try (CgTrace.Zone ignored = CgTrace.isEnabled(CgChannels.GL) ? CgTrace.zone(CgChannels.GL, CgTrace.name(pass.name())) : null) {
            frame.bindings.bind(packed.constants);
            for (int d = 0; d < dispatches.size(); d++) dispatch(frame, dispatches.get(d), packed.bindings[d]);
        }
        CgTrace.add(CgChannels.GL, DISPATCH_COUNT, dispatches.size());
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

    /** Whether {@code pass} goes beside the frame's queue: asked for, on a device with a compute queue. */
    private boolean runsAsync(CgComputePass pass) {
        return asyncCompute && (pass.isAsync() || ASYNC_ALL);
    }

    /** Step {@code s} on the compute queue; the storage it touches is pending until the frame's queue waits for it. */
    private void computeAsync(CgFrame frame, int s, CgComputePass pass) {
        if (CgKernelTarget.CHECKED) CgComputeCheck.prepare();
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
        if (frame.steps[s] instanceof CgPass.Callback) {
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
        CgGL.cgWaitAsync(point);
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
        CgFrameBuffer storage = texture.framebuffer();
        CgTexture color = storage == null ? null : storage.getColorTexture(0);
        if (color == null) return 0;
        boolean whole = texture.getLevels() == 1 && texture.getLayers() == 1 && storage.getDepth() <= 1;
        keyScratch[0] = whole ? CgResourceStates.framebuffer(storage.getId()) : CgResourceStates.texture(color.getId());
        return 1;
    }

    /** A history written by {@code d}: its next version is its newest from now on, however many bindings wrote it. */
    private void advanceHistories(CgDispatch d) {
        for (int i = 0; i < d.buffers.length; i++) {
            if (wroteHistory(d.buffers[i], d.bufferAccess[i]) && firstWriter(d, i)) d.buffers[i].advance();
        }
    }

    private void dispatch(CgFrame frame, CgDispatch d, int bindings) {
        CgKernelProgram program;
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL_DETAIL, DISPATCH_PROGRAM)) {
            program = d.kernel.program();
            program.use();
        }
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL_DETAIL, DISPATCH_BIND)) {
            CgGL.cgBeginBarriers();   // its bindings' barriers as one glMemoryBarrier on GL, before the dispatch
            try {
                bindDispatch(d, program, frame, bindings);
            } finally {
                CgGL.cgEndBarriers();
            }
        }
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL_DETAIL, DISPATCH_ISSUE)) {
            switch (d.form) {
                case ELEMENTS -> program.dispatchBound(d.x, d.y, d.z);
                case GROUPS -> program.dispatchBound(d.x * program.kernel().sizeX(), d.y * program.kernel().sizeY(),
                        d.z * program.kernel().sizeZ());
                case INDIRECT -> {
                    int args = bufferStorage(d.args, false);
                    bufferBarrier(args, CgAccess.INDIRECT);
                    program.dispatchIndirectBound(args, d.argsOffset);
                }
            }
        }
        advanceHistories(d);
    }

    private void bindDispatch(CgDispatch d, CgKernelProgram program, CgFrame frame, int bindings) {
        frame.bindings.bind(bindings);
        for (String token : d.source.engineBuffers()) CgEngineBufferRegistry.get(token).buffer().get().bind();
        for (int b = 0; b < d.buffers.length; b++) {
            if (d.buffers[b] != null) {
                int access = d.hoisted[b] == passSerial ? 0 : d.bufferAccess[b];
                bindStorage(b, d.buffers[b], d.offsets[b], d.sizes[b], access, set(d.skipGroup[b]));
            }
            if (d.counters[b] != null) {
                long base = CgKernelProgram.counterBase(d.counterOffsets[b]);
                int access = d.counterHoisted[b] == passSerial ? 0 : d.counterAccess[b];
                bindStorage(program.counterPoint(b), d.counters[b], base, d.counterOffsets[b] - base + 4, access,
                        set(d.countSkipGroup[b]));
                program.counterWord(b, d.counterOffsets[b]);
            }
        }
        for (CgTexture sampler : d.samplers) {
            CgGraphTexture graph = sampler == null ? null : CgGraphTexture.sampled(sampler);
            if (graph != null) move(graph, CgAccess.SAMPLED_READ, -1, -1);   // after a kernel wrote it as an image
        }
        for (int i = 0; i < d.images.length; i++) {
            CgGraphTexture texture = d.images[i];
            if (texture == null) continue;
            CgImageDecl image = d.source.images().get(i);
            int id = storage(texture).getColorTexture(0).getId();
            int access = imageAccess(d, i, id);
            if (access != 0) move(texture, access, -1, -1);
            boolean layered = d.layers[i] < 0 && image.dimension() != CgImageDimension.D2;
            CgGL.glBindImageTexture(i, id, d.levels[i], layered, Math.max(0, d.layers[i]), glAccess(image.access()),
                    image.format().glFormat);
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

    private static void bindStorage(int point, CgGraphBuffer buffer, long offset, long size, int access, int set) {
        int id = bufferStorage(buffer, (access & CgAccess.COMPUTE_WRITE) != 0);
        if (access != 0) barrier(id, access, set);
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
        int bytes = update.size;
        // 1 MB a region: a move's pools update about that in a frame, and each doubling is new storage (T10).
        if (staging == null) staging = CgStreamBuffer.create(CgGL.GL_COPY_READ_BUFFER, Math.max(bytes, 1 << 20));
        int at;
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL_DETAIL, UPDATE_STAGE)) {
            if (update.floats != null) {
                staging.mapFloats(bytes).put(update.floats, update.from, bytes / Float.BYTES);
            } else if (update.ints != null) {
                staging.mapInts(bytes).put(update.ints, update.from, bytes / Integer.BYTES);
            } else {
                staging.write(update.bytes, 0, bytes);
            }
            at = staging.commit(bytes);
        }
        CgGL.glBindBuffer(CgGL.GL_COPY_READ_BUFFER, staging.getGlBufferId());
        CgGL.glBindBuffer(CgGL.GL_COPY_WRITE_BUFFER, id);
        boolean detail = CgTrace.isEnabled(CgChannels.GL_DETAIL);
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL_DETAIL, UPDATE_COPY);
             CgTrace.Zone named = detail ? CgTrace.zone(CgChannels.GL_DETAIL, CgTrace.name("graph.update/" + update.buffer.name())) : null) {
            CgGL.glCopyBufferSubData(CgGL.GL_COPY_READ_BUFFER, CgGL.GL_COPY_WRITE_BUFFER, at, update.offset, bytes);
        }
        CgGL.glBindBuffer(CgGL.GL_COPY_READ_BUFFER, 0);
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
        if (!kernelsSeen && !takes) return;
        if (s > runStart && s <= runEnd) return;   // its run's first step stated them
        CgGL.cgBeginBarriers();   // one glMemoryBarrier for the step on GL, one transition on a device
        try {
            if (runEnd > s && runAttachment != null && ((CgRasterPass) frame.steps[s]).attachment() == null) {
                move(runAttachment, 0, 0, 0);   // a later pass's attachment, bound from the run's start
            }
            for (int j = s; j <= Math.max(s, runEnd); j++) {
                CgPass pass = frame.steps[j];
                for (int i = frame.accessFrom[j]; i < frame.accessFrom[j + 1]; i++) {
                    int bits = frame.accessBits[i];
                    if (bits == 0) continue;   // a release
                    if (frame.accessView[i] instanceof CgGraphBuffer buffer) {
                        bufferBarrier(bufferStorage(buffer, (bits & CgResourceStates.WRITES) != 0), bits);
                    } else if (pass instanceof CgRasterPass raster && (bits & CgAccess.COLOR_WRITE) != 0) {
                        if (j > s) continue;   // the run's attachments, moved with its first step
                        CgGraphTexture texture = (CgGraphTexture) frame.accessView[i];
                        boolean target = texture == raster.target;
                        boolean hostTested = texture.kind() == CgGraphTexture.Kind.CURRENT && hostDepth(raster);
                        if ((target && !hostDepth(raster) || hostTested) && readOnlyDepth(frame, j, raster)) {
                            move(texture, CgAccess.COLOR_WRITE | CgAccess.DEPTH_READ, -1, -1);   // its depth sampled in place
                            continue;
                        }
                        move(texture, bits & ~CgAccess.COLOR_WRITE, target ? raster.level : 0, target ? raster.layer : 0);
                    } else {
                        move((CgGraphTexture) frame.accessView[i], bits, -1, -1);
                    }
                }
            }
        } finally {
            CgGL.cgEndBarriers();
        }
    }

    /**
     * From raster step {@code s}, the run of raster steps that share its render pass, as Unreal RDG merges them
     * ({@code MergeRenderPasses}): each after it into the same target, level, layer and second attachment, loading
     * what is there, with no copy, rename, damage or indirect command of its own, reading nothing the run writes. The
     * run's first step states every transition of the run, so none falls between them and the device's render pass
     * stays open.
     */
    private void merge(CgFrame frame, int s) {
        runStart = runEnd = s;
        runAttachment = null;
        if (!(frame.steps[s] instanceof CgRasterPass first) || first.target == null || unmergeable(frame, s, first)) return;
        CgGraphTexture attachment = first.attachment();
        int k = s + 1;
        while (k < frame.stepCount && frame.steps[k] instanceof CgRasterPass next && joins(frame, first, attachment, k, next)) {
            if (attachment == null) attachment = next.attachment();
            k++;
        }
        runEnd = k - 1;
        if (runEnd > s) {
            CgTrace.add(CgChannels.GL, MERGED, runEnd - s);
            runAttachment = attachment;
        }
    }

    /**
     * Whether raster step {@code s} samples its target's depth in place, read-only, rather than copying it: on a device,
     * where no draw of the pass writes depth and its target is ours with a depth texture, a single level and layer.
     * Unreal's translucency binds {@code DepthRead} so; a pass writing the depth it reads keeps its copy.
     */
    private boolean readOnlyDepth(CgFrame frame, int s, CgRasterPass pass) {
        if (!takes || pass.sceneDepthUnit() < 0 || frame.rasters[s].writesDepth || pass.depthFrom() != null) return false;
        CgGraphTexture target = pass.target;
        if (hostDepth(pass)) return CgGL.cgDepthTexture(startFramebuffer) != 0;   // the host's, imported
        if (attachmentOf(pass) != null || target.getLevels() != 1 || target.getLayers() != 1) return false;
        CgFrameBuffer storage = target.kind() == CgGraphTexture.Kind.TRANSIENT ? target.framebuffer() : storage(target);
        return storage != null && storage.getDepthTexture() != null;
    }

    /** Whether {@code pass} tests the host's depth: into the current target, or beside its depth. */
    private static boolean hostDepth(CgRasterPass pass) {
        CgGraphTexture target = pass.target;
        return target == null || target.kind() == CgGraphTexture.Kind.CURRENT || target.drawsBesideCurrentDepth();
    }

    /** {@code pass}'s second attachment: its own, or inside a merged run the run's. */
    @Nullable
    private CgGraphTexture attachmentOf(CgRasterPass pass) {
        CgGraphTexture own = pass.attachment();
        return own != null ? own : runAttachment;
    }

    private boolean unmergeable(CgFrame frame, int s, CgRasterPass pass) {
        return pass.damage() != null && !frame.wholePasses || pass.target.kind() == CgGraphTexture.Kind.HISTORY;
    }

    /**
     * Whether raster step {@code k} joins the run {@code first} begins, whose second attachment so far is
     * {@code attachment}. A pass with none joins one with, and one with joins one without: the run binds it throughout,
     * each pass's writes to it masked off where its pipelines write none.
     */
    private boolean joins(CgFrame frame, CgRasterPass first, @Nullable CgGraphTexture attachment, int k, CgRasterPass next) {
        if (next.target != first.target || next.level != first.level || next.layer != first.layer
                || next.attachment() != null && attachment != null && next.attachment() != attachment || next.load.mask() != 0
                || next.replacesTarget()
                || next.depthFrom() != null || next.colorFrom() != null || unmergeable(frame, k, next)) {
            return false;
        }
        if (readOnlyDepth(frame, k, next) != readOnlyDepth(frame, runStart, first)) return false;   // one depth mode a pass
        CgFrame.Raster packed = frame.rasters[k];
        if (packed.indirects > 0 && gpuCounts) return false;   // its commands are written by a dispatch first
        for (int b = 0; b < packed.count; b++) if (packed.copyBefore[b] != 0) return false;
        for (int i = frame.accessFrom[k]; i < frame.accessFrom[k + 1]; i++) {
            CgGraphResource view = frame.accessView[i];
            int bits = frame.accessBits[i];
            if (view == CgGraphTexture.current() && first.target.drawsBesideCurrentDepth()) {
                // Beside the host's depth a pass names it read and written: ordered on it, sampling none of it.
                if ((bits & ~(CgAccess.COLOR_WRITE | CgAccess.SAMPLED_READ)) != 0) return false;
            } else if ((view == first.target || view == attachment || view == next.attachment())
                    && (bits & ~CgAccess.COLOR_WRITE) != 0) {
                return false;
            }
        }
        return true;
    }

    /** The barrier an access of {@code bits} to buffer {@code id} needs, issued. */
    private static void bufferBarrier(int id, int bits) {
        barrier(id, bits, 0);
    }

    /** {@link #bufferBarrier} through overlap set {@code set} (0 for none). */
    private static void barrier(int id, int bits, int set) {
        int from = STATES.access(CgResourceStates.buffer(id), bits, set);
        if (!issues(from, bits)) return;
        CgGL.cgBufferBarrier(id, from, bits);
        CgTrace.add(CgChannels.GL, BARRIER_COUNT, 1);
    }

    /** Whether a transition from {@code from} to {@code to} is issued: every one on a device, a kernel's on GL. */
    private static boolean issues(int from, int to) {
        return from != 0 && computeBarriers && (takes || ((from | to) & COMPUTE) != 0);
    }

    /**
     * Moves graph texture {@code texture} to {@code to}, stating what must move: whole, or where {@code attachedLevel}
     * is not -1, its subresource at that level and {@code attachedLayer} to {@link CgAccess#COLOR_WRITE} and the rest
     * to {@code to} (none where 0). A single-level, single-layer texture and the current target move as one
     * framebuffer, depth with colour; any other by each colour texture's subresources.
     */
    private void move(CgGraphTexture texture, int to, int attachedLevel, int attachedLayer) {
        boolean outlives = texture.kind() != CgGraphTexture.Kind.TRANSIENT;
        if (texture.kind() == CgGraphTexture.Kind.CURRENT) {
            moveFramebuffer(startFramebuffer, attachedLevel >= 0 ? CgAccess.COLOR_WRITE : to, true);
            return;
        }
        // A requested texture's or a history's storage is made at its first use: this statement, before its bind.
        CgFrameBuffer storage = outlives ? storage(texture) : texture.framebuffer();
        if (storage == null) return;
        int levels = texture.getLevels(), layers = texture.getLayers();
        if (levels == 1 && layers == 1 && storage.getDepth() <= 1) {
            moveFramebuffer(storage.getId(), attachedLevel >= 0 ? CgAccess.COLOR_WRITE : to, outlives);
            return;
        }
        for (int slot = 0; slot < storage.getFormat().colorSlotCount(); slot++) {
            CgTexture color = storage.getColorTexture(slot);
            if (color == null) continue;
            long key = CgResourceStates.texture(color.getId());
            int[] subs = STATES.image(key, levels * layers);
            noteMoved(key, color.getId(), levels, layers, outlives);
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
                            CgGL.cgTextureBarrier(color.getId(), level, 1, layer, end - layer,
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

    /** Moves framebuffer {@code fbo}'s attachments to {@code to}; answers where they were, 0 at rest. */
    private int moveFramebuffer(int fbo, int to, boolean outlives) {
        long key = CgResourceStates.framebuffer(fbo);
        int[] subs = STATES.image(key, 1);
        int from = subs[0];
        noteMoved(key, fbo, 0, 0, outlives);
        boolean moves = CgResourceStates.moves(from, to);
        if (moves && issues(from == 0 ? CgAccess.RESTING : from, to)) {
            CgGL.cgFramebufferBarrier(fbo, from == 0 ? CgAccess.RESTING : from, to);
            CgTrace.add(CgChannels.GL, BARRIER_COUNT, 1);
        }
        subs[0] = moves ? to : from | to;
        return from;
    }

    /** {@code key} was moved, and with {@code outlives} returns to rest at the execution's end; {@code levels} 0 for a framebuffer. */
    private void noteMoved(long key, int name, int levels, int layers, boolean outlives) {
        for (int m = 0; m < moved; m++) {
            if (movedKeys[m] != key) continue;
            movedOutlives[m] |= outlives;
            return;
        }
        if (moved == movedKeys.length) {
            movedKeys = Arrays.copyOf(movedKeys, moved * 2);
            movedNames = Arrays.copyOf(movedNames, moved * 2);
            movedLevels = Arrays.copyOf(movedLevels, moved * 2);
            movedLayers = Arrays.copyOf(movedLayers, moved * 2);
            movedOutlives = Arrays.copyOf(movedOutlives, moved * 2);
        }
        movedOutlives[moved] = outlives;
        movedKeys[moved] = key;
        movedNames[moved] = name;
        movedLevels[moved] = levels;
        movedLayers[moved++] = layers;
    }

    /**
     * Every image it moved that outlives the execution, or with {@code all} every one, back where it rests: where the
     * host and GL streams find it. A transient left out keeps its state with its storage.
     */
    private void restMoved(boolean all) {
        if (moved == 0) return;
        CgGL.cgBeginBarriers();
        try {
            for (int m = 0; m < moved; m++) {
                if (!all && !movedOutlives[m]) continue;
                int levels = movedLevels[m], layers = movedLayers[m];
                int[] subs = STATES.image(movedKeys[m], Math.max(1, levels * layers));
                if (levels == 0) {
                    if (subs[0] != 0) CgGL.cgFramebufferBarrier(movedNames[m], subs[0], CgAccess.RESTING);
                    subs[0] = 0;
                    continue;
                }
                for (int i = 0; i < levels * layers; i++) {
                    if (subs[i] == 0) continue;
                    CgGL.cgTextureBarrier(movedNames[m], i / layers, 1, i % layers, 1, subs[i], CgAccess.RESTING);
                    subs[i] = 0;
                }
            }
        } finally {
            CgGL.cgEndBarriers();
        }
        moved = 0;
    }

    /**
     * After step {@code s}: each access whose next is a read two or more steps on begins its transition now, ended
     * before that step ({@link #endSplits}), as Unreal RDG splits a transition from its producer's epilogue to its
     * consumer's prologue. Not across async work, a callback, or an attachment.
     */
    private void beginSplits(CgFrame frame, int s) {
        if (!splittable(frame, s)) return;
        int end = Integer.MAX_VALUE;
        CgGL.cgBeginBarriers();
        int began;
        try {
            for (int i = frame.accessFrom[s]; i < frame.accessFrom[s + 1]; i++) {
                int next = frame.nextAccess[i];
                if (next < 0) continue;
                int c = frame.accessStep[next], bits = frame.accessBits[next];
                if (c <= s + 1 || bits == 0 || (bits & CgResourceStates.WRITES) != 0 || !splittable(frame, c)
                        || frame.steps[c] instanceof CgRasterPass && frame.accessView[next] instanceof CgGraphTexture
                        && (bits & CgAccess.COLOR_WRITE) != 0) {
                    continue;
                }
                if (frame.accessView[next] instanceof CgGraphBuffer buffer) {
                    if (buffer.resource().kind() == CgGraphBuffer.Kind.HISTORY) continue;
                    bufferBarrier(bufferStorage(buffer, false), bits);
                } else {
                    move((CgGraphTexture) frame.accessView[next], bits, -1, -1);
                }
                end = Math.min(end, c);
            }
        } finally {
            began = CgGL.cgEndBarriersSplit();
        }
        if (began == 0) return;
        if (splitCount == splitIds.length) {
            splitIds = Arrays.copyOf(splitIds, splitCount * 2);
            splitEnds = Arrays.copyOf(splitEnds, splitCount * 2);
        }
        splitIds[splitCount] = began;
        splitEnds[splitCount++] = end;
        CgTrace.add(CgChannels.GL, SPLITS, 1);
    }

    /** Whether step {@code s} may begin or end a split: a raster, compute or buffer step on the frame's queue. */
    private boolean splittable(CgFrame frame, int s) {
        CgPass pass = frame.steps[s];
        if (pass instanceof CgComputePass compute) return !runsAsync(compute);
        return pass instanceof CgRasterPass || pass instanceof CgPass.Fill || pass instanceof CgPass.Update
                || pass instanceof CgPass.BufferCopy;
    }

    /** Ends every split due by step {@code s}. */
    private void endSplits(int s) {
        for (int k = 0; k < splitCount; ) {
            if (splitEnds[k] > s) {
                k++;
                continue;
            }
            CgGL.cgEndSplit(splitIds[k]);
            splitIds[k] = splitIds[--splitCount];
            splitEnds[k] = splitEnds[splitCount];
        }
    }

    /** Before a blit of framebuffer {@code source} into {@code dest} inside a pass: the source read, the destination written. */
    static void blitting(int source, int dest) {
        if (!takes || depth == 0) return;
        CgExecutor e = BY_DEPTH.get(depth - 1);
        CgGL.cgBeginBarriers();
        try {
            e.blitSourceWas = e.moveFramebuffer(source, CgAccess.COPY_READ, source == e.startFramebuffer);
            e.moveFramebuffer(dest, CgAccess.COPY_WRITE, false);
        } finally {
            CgGL.cgEndBarriers();
        }
    }

    /** After it: the source where it was, the copy sampled. */
    static void blitted(int source, int dest) {
        if (!takes || depth == 0) return;
        CgExecutor e = BY_DEPTH.get(depth - 1);
        CgGL.cgBeginBarriers();
        try {
            if (e.blitSourceWas != 0) {
                e.moveFramebuffer(source, e.blitSourceWas, false);
            } else {
                // Found at rest, perhaps under another framebuffer's name for the same images: back to rest.
                CgGL.cgFramebufferBarrier(source, CgAccess.COPY_READ, CgAccess.RESTING);
                STATES.image(CgResourceStates.framebuffer(source), 1)[0] = 0;
            }
            e.moveFramebuffer(dest, CgAccess.SAMPLED_READ, false);
        } finally {
            CgGL.cgEndBarriers();
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
                writeCommands(pass, packed);   // a dispatch never sits inside a render pass
            }
        }
        if (DEVICE_RASTER) {
            String refused = deviceRefuses(frame, pass, packed, damage);
            if (refused == null) {
                rasterDevice(frame, pass, packed, damage);
                return;
            }
            glPasses++;
            CgTrace.add(CgChannels.GL, GL_PASSES, 1);
            if (glPassReasons.add(refused + " " + pass.name())) LOGGER.warn("device raster: {} on CgGL: {}", pass.name(), refused);
        }
        if (damage == null && pass.replacesTarget()) renameTarget(pass, packed);
        try {
            try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, RASTER_BEGIN)) {
                rasterBegin(frame, pass, packed, damage);
            }
            if (packed.count > 0) rasterBatches(frame, pass, packed, damage);
        } finally {
            if (renamedFrom != null) {
                POOL.release(pass.target.desc(), renamedFrom);
                renamedFrom = null;
            }
        }
        if (stencilled) CgStencilState.DISABLED.apply();
        stencilled = false;
        if (depthInPlace) CgGL.cgReadOnlyDepth(false);
        depthInPlace = false;
        composed = beside = false;   // detached at the execution's end: an attach a pass cost 2 µs on a threaded driver
    }

    /**
     * Gives a pass that replaces its target fresh storage, keeping the old as what its first batch samples, where that
     * batch would otherwise copy the whole target's colour: Unreal RDG's post passes, each into a new texture.
     */
    private void renameTarget(CgRasterPass pass, CgFrame.Raster packed) {
        CgGraphTexture target = pass.target;
        if (packed.count == 0 || (packed.copyBefore[0] & CgTargetCopy.COLOR) == 0) return;
        if (target == null || target.kind() != CgGraphTexture.Kind.TRANSIENT || target.isArray()
                || pass.level != 0 || pass.layer != 0 || pass.load.mask() != 0 || pass.attachment() != null) {
            return;
        }
        CgFrameBuffer old = target.framebuffer();
        if (old == null || old.getFormat().colorSlotCount() != 1 || old.getFormat().hasDepth()) return;
        int[] rect = packed.copyRect;   // GL pixels x0, y0, x1, y1; x1 below 0 is the whole target
        if (rect[2] >= 0 && (rect[0] > 0 || rect[1] > 0 || rect[2] < old.getWidth() || rect[3] < old.getHeight())) return;
        renamedFrom = old;
        target.resolve(POOL.acquire(target.desc()));
        if (takes) {
            // After the step's transitions: the old storage is now what its batches sample, the new one its target.
            CgGL.cgBeginBarriers();
            try {
                moveFramebuffer(old.getId(), CgAccess.SAMPLED_READ, false);
                moveFramebuffer(target.framebuffer().getId(), CgAccess.COLOR_WRITE, false);
            } finally {
                CgGL.cgEndBarriers();
            }
        }
        CgTrace.add(CgChannels.GL, TARGET_RENAMES, 1);
    }

    /** {@code pass}'s target bound and cleared, and what every batch shares bound. */
    private void rasterBegin(CgFrame frame, CgRasterPass pass, CgFrame.Raster packed, @Nullable int[] damage) {
        depthInPlace = readOnlyDepth(frame, currentStep, pass);
        if (depthInPlace) {
            CgGL.cgReadOnlyDepth(true);
            CgTrace.add(CgChannels.GL, READ_ONLY_DEPTH, 1);
        }
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, RASTER_TARGET)) {
            try (CgTrace.Zone c = CgTrace.zone(CgChannels.GL_DETAIL, RASTER_COMPOSED)) {
                // Beside the current depth, a second attachment is ours too: the host's colour stays out
                composed = attachmentOf(pass) != null && !(pass.target != null && pass.target.drawsBesideCurrentDepth())
                        && bindComposed(pass);
            }
            try (CgTrace.Zone b = CgTrace.zone(CgChannels.GL_DETAIL, RASTER_BESIDE)) {
                beside = !composed && pass.target != null && pass.target.drawsBesideCurrentDepth()
                        && bindBeside(pass.target, attachmentOf(pass));
            }
            try (CgTrace.Zone t = CgTrace.zone(CgChannels.GL_DETAIL, RASTER_OWN)) {
                if (!composed && !beside) bindTarget(pass.target, pass.level, pass.layer);
            }
        }
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, RASTER_CLEAR)) {
            clear(pass.load, damage);
        }
        if (packed.count == 0) return;
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, RASTER_BINDINGS)) {
            frame.bindings.bind(packed.constants);
            for (int k = 0; k < KINDS; k++) if ((packed.kinds & (1 << k)) != 0) instanceBuffers[k].bind();
            packed.palette.pass(pass.viewOwner(), pass.viewX(), pass.viewY(), CgPassConstants.height(pass.constants));
            if ((packed.kinds & UNIT_KINDS) != 0 || packed.tables) {
                packed.clips.bindForDraw();
                packed.shapes.bindForDraw();
                packed.palette.bindForDraw();
            }
        }
    }

    /** {@code load}'s clear of the bound target, cut to {@code damage} where given. */
    private static void clear(CgLoad load, @Nullable int[] damage) {
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
            if ((load.mask() & CgGL.GL_STENCIL_BUFFER_BIT) != 0) {
                CgGL.glStencilMask(0xFF);
                CgGL.glClearStencil(load.stencil());
            }
            CgGL.glClear(load.mask());
        }
    }

    private void rasterBatches(CgFrame frame, CgRasterPass pass, CgFrame.Raster packed, @Nullable int[] damage) {
        int boundPipeline = -1, boundBinding = -1, boundScissor = CgRasterPass.INHERIT;
        if (damage != null) {
            // Every draw cut to the damage, with or without a scissor of its own.
            CgGL.glEnable(CgGL.GL_SCISSOR_TEST);
            CgGL.glScissor(damage[0], damage[1], damage[2], damage[3]);
        }
        CgPipeline pipeline = null;
        boolean usable = false, objectsBound = false;
        // The engine buffers a batch's buffer()s stand in for, bound again once a batch without them draws.
        CgBindingPoints.Binding[] standIn = this.standIn;
        Arrays.fill(standIn, null);
        boolean groups = CgTrace.isEnabled(CgChannels.GPU_GROUPS) && CgGpuTrace.isMeasuring();
        int slots = colorSlots(pass);
        int slot = 0;
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, "graph.batchLoop")) {
            if (pass.depthFrom() != null || pass.colorFrom() != null) {
                try (CgTrace.Zone copying = CgTrace.zone(CgChannels.GL_DETAIL, BATCH_COPY_FROM)) {
                    if (pass.depthFrom() != null) {
                        if (groups) CgGpuTrace.mark(DEPTH_FROM_GROUP);
                        copyDepthFrom(pass);
                    }
                    if (pass.colorFrom() != null) copyColorFrom(pass);
                }
            }
            for (int b = 0; b < packed.count; b++) {
                if (groups) CgGpuTrace.mark(packed.group[b] >= 0 ? packed.group[b] : groupLabel(packed.pipeline[b]));   // before its target copy, which it pays for
                int command = packed.counts[b] != null ? slot : -1;
                int end, commandCount = 0;
                if (command >= 0) {
                    end = gpuCounts ? runs[b] : b;
                    for (int k = b; k <= end; k++) commandCount += commandsOf(packed, k);
                    slot += commandCount;
                } else {
                    try (CgTrace.Zone joining = CgTrace.zone(CgChannels.GL_DETAIL, BATCH_JOIN)) {
                        end = packed.objects[b] == null && packed.buffers[b * CgDrawChunk.BUFFERS] == null && multiDraw
                                && b + 1 < packed.count && joinable(packed, b, b + 1)
                                ? joinRun(packed, b) : b;
                    }
                    if (multiDraw && end + 1 < packed.count && CgTrace.isEnabled(CgChannels.GL)
                            && breaksOnBinding(packed, b, end + 1)) {
                        CgTrace.add(CgChannels.GL, BINDING_BREAKS, 1);
                    }
                }
                if (packed.copyBefore[b] != 0) {
                    int bits = packed.copyBefore[b];
                    if (b == 0 && renamedFrom != null) {
                        // What the target held, read where it stands: the pass draws into fresh storage
                        renamedFrom.getColorTexture(0).bind(pass.sceneColorUnit());
                        bits &= ~CgTargetCopy.COLOR;
                    }
                    if ((bits & (CgTargetCopy.COLOR | CgTargetCopy.DEPTH)) != 0) {
                        try (CgTrace.Zone copying = CgTrace.zone(CgChannels.GL, "graph.targetCopy")) {
                            copyTarget(pass, bits, packed.copyRect, b * 4);
                        }
                    }
                }
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
                boolean joinedDraw = end > b || command >= 0 && gpuCounts && packed.eachFrom[b] >= 0 && eachJoins(packed, b);
                int id = packed.pipeline[b];
                if (id != boundPipeline) {
                    boundPipeline = id;
                    pipeline = CgPipeline.byId(id);
                    try (CgTrace.Zone binding = CgTrace.zone(CgChannels.GL_DETAIL, BATCH_PIPELINE)) {
                        if (pass.state != null) pass.state.apply();   // a pipeline's unset slots are the pass's
                        // A colour mask neither declares writes every channel, never what another pass's draw left.
                        if ((pass.state == null || pass.state.getColorMasks().isEmpty())
                                && pipeline.state().getColorMasks().isEmpty()) {
                            CgColorMask.ALL.apply();
                        }
                        CgStencilState stencil = pipeline.state().getStencil();
                        stencilled |= stencil != null && stencil.enabled();
                        usable = pipeline.bind();
                        if (slots > 1 && independentMasks) maskSlots(pipeline, slots);
                    }
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
                    try (CgTrace.Zone binding = CgTrace.zone(CgChannels.GL_DETAIL, BATCH_BINDINGS)) {
                        frame.bindings.bind(boundBinding);
                    }
                }
                int first = b * CgDrawChunk.BUFFERS;
                try (CgTrace.Zone records = CgTrace.zone(CgChannels.GL_DETAIL, BATCH_RECORDS)) {
                    if (packed.objects[b] != null) {
                        bindRecords(packed.objects[b], CgBindingPoints.OBJECT_DATA);
                        objectsBound = true;
                    } else if (objectsBound && packed.kind[b] == OBJECT) {
                        instanceBuffers[OBJECT].bind();
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
                }
                if (DEVICE_SETS) deviceSets(pipeline, frame.bindings, packed.constants, boundBinding);
                if (joinedDraw) {
                    try (CgTrace.Zone drawing = CgTrace.zone(CgChannels.GL_DETAIL, BATCH_DRAW)) {
                        if (command >= 0) {
                            CgMeshStore.get().drawIndirectJoined(mesh(packed, b), commands.buffer(),
                                    commands.offset(command), commandCount, commands.stride());
                        } else {
                            CgMeshStore.get().drawJoined();
                        }
                    }
                    b = end;
                    continue;
                }
                if (command >= 0 && packed.eachFrom[b] >= 0) {
                    // A record a command, drawn one by one where they cannot join.
                    CgMesh mesh = mesh(packed, b);
                    try (CgTrace.Zone drawing = CgTrace.zone(CgChannels.GL_DETAIL, BATCH_DRAW)) {
                        for (int k = 0; k < commandCount; k++) {
                            if (gpuCounts) {
                                CgMeshStore.get().drawIndirect(mesh, packed.submesh[b], commands.buffer(),
                                        commands.offset(command + k));
                            } else {
                                drawCounted(mesh, packed, b, packed.eachOffsets[packed.eachFrom[b] + k], 1,
                                        packed.first[b] + k);
                            }
                        }
                    }
                    continue;
                }
                CgMesh mesh = mesh(packed, b);
                try (CgTrace.Zone drawing = CgTrace.zone(CgChannels.GL_DETAIL, BATCH_DRAW)) {
                    if (command >= 0 && gpuCounts) {
                        CgMeshStore.get().drawIndirect(mesh, packed.submesh[b], commands.buffer(), commands.offset(command));
                    } else if (command >= 0) {
                        drawCounted(mesh, packed, b, packed.countOffsets[b], packed.instances[b], firstInstance(packed, b));
                    } else {
                        CgMeshStore.get().draw(mesh, packed.instances[b], packed.first[b], packed.submesh[b],
                                packed.rangeFirst[b], packed.rangeCount[b]);
                    }
                }
            }
            for (CgBindingPoints.Binding at : standIn) if (at != null) bindEngineBuffer(at);
        } finally {
            if (groups) CgGpuTrace.markEnd();
            if (pass.targetCopy() != null) pass.targetCopy().release(POOL);
            if (pass.depthFromCopy() != null) pass.depthFromCopy().release(POOL);
            if (pass.colorFromCopy() != null) pass.colorFromCopy().release(POOL);
        }
        vaoHeld = true;
    }

    /** Unbinds what {@link #vaoHeld} says a raster pass left bound. */
    private void releaseVertexArray() {
        if (!vaoHeld) return;
        vaoHeld = false;
        CgGL.glBindVertexArray(0);
    }

    private static final int BATCH_PIPELINE = CgTrace.name("graph.batch.pipeline"),
            BATCH_BINDINGS = CgTrace.name("graph.batch.bindings"), BATCH_DRAW = CgTrace.name("graph.batch.draw"),
            BATCH_COPY_FROM = CgTrace.name("graph.batch.copyFrom"), BATCH_JOIN = CgTrace.name("graph.batch.join"),
            BATCH_RECORDS = CgTrace.name("graph.batch.records");

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
        return packed.counts[k] == null && packed.objects[k] == null && packed.buffers[k * CgDrawChunk.BUFFERS] == null
                && packed.copyBefore[k] == 0 && packed.pipeline[k] == packed.pipeline[b]
                && packed.binding[k] == packed.binding[b] && packed.scissor[k] == packed.scissor[b]
                && packed.group[k] == packed.group[b];
    }

    /**
     * Indirect batch {@code k} drawn in one multi-draw after {@code b}: as {@link #joinable}, reading the same object
     * records, from a mesh the store {@link CgMeshStore#joins joins} with {@code b}'s.
     */
    private static boolean joinableIndirect(CgFrame.Raster packed, int b, int k) {
        return packed.counts[k] != null && packed.copyBefore[k] == 0
                && packed.pipeline[k] == packed.pipeline[b] && packed.binding[k] == packed.binding[b]
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
    private static void countBreak(CgFrame.Raster packed, int b, int k) {
        int why = packed.counts[k] == null ? 0 : packed.copyBefore[k] != 0 ? 1
                : packed.pipeline[k] != packed.pipeline[b] ? 2 : packed.binding[k] != packed.binding[b] ? 3
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

    /** Whether batch {@code k} would join {@code b}'s run but for its bindings: what bindless would join. */
    private static boolean breaksOnBinding(CgFrame.Raster packed, int b, int k) {
        return packed.objects[b] == null && packed.counts[k] == null && packed.objects[k] == null
                && packed.copyBefore[k] == 0 && packed.pipeline[k] == packed.pipeline[b]
                && packed.binding[k] != packed.binding[b] && packed.scissor[k] == packed.scissor[b]
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

    /**
     * Makes the batch's view, pass and material sets for its device pipeline's layout from what is bound now: the pass
     * constants' snapshot, the batch's, and the buffers the executor bound ({@code CgBoundResources}). Logs, once each, a
     * slot left to a default or a program that will not compile as written.
     */
    private void deviceSets(@Nullable CgPipeline pipeline, CgBindingTable table, int constants, int binding) {
        if (pipeline == null || !CgGLImports.installed()) return;
        CgShader linked = pipeline.program();
        if (linked == null) return;
        CgRHI rhi = CgRHI.get();
        CgPipelineLayout layout;
        try {
            layout = CgDeviceStates.layout(rhi, linked.explicit(pipeline.toString()));
        } catch (RuntimeException e) {
            if (deviceSetsLogged.add(pipeline.toString())) LOGGER.warn("device sets: {}: {}", pipeline, e.getMessage());
            return;
        }
        for (int set = 0; set < layout.sets().size() && set < CgRHI.BINDLESS_SET; set++) {
            if (layout.sets().get(set).slots().isEmpty()) continue;
            long before = deviceSets.defaulted();
            deviceSets.set(rhi, table, set == CgBindingModel.VIEW ? constants : binding, layout, set, null);
            if (deviceSets.defaulted() != before) {
                String what = pipeline + " " + deviceSets.lastDefaulted();
                if (deviceSetsLogged.add(what)) LOGGER.warn("device sets: {} defaulted", what);
            }
        }
        long asked = deviceSets.made() + deviceSets.reused();
        if (asked >= deviceSetsReport) {
            deviceSetsReport *= 10;
            LOGGER.warn("device sets: {} asked: {} made, {} reused, {} slots defaulted", asked, deviceSets.made(),
                    deviceSets.reused(), deviceSets.defaulted());
        }
    }

    /** {@link #deviceSets}' next report, at each power of ten sets asked for. */
    private long deviceSetsReport = 10;

    // ── Raster on the device (GL6 part 4) ──────────────────────────────────────

    /** The host framebuffer's attachment parameters, which {@code CgGL} does not name. */
    private static final int GL_ATTACHMENT_NAME = 0x8CD1, GL_ATTACHMENT_TEXTURE = 0x1702;
    /** The host framebuffer {@link #readHost} read, at what viewport size; -1 for none. */
    private int hostRead = -1, hostReadWidth, hostReadHeight;
    /** Its colour and depth as RHI textures; null where it has none, or a renderbuffer. */
    @Nullable
    private CgGpuTexture hostColor, hostDepth;
    /** Its attachments' names (0 for none, -1 for a renderbuffer) and their descriptions. */
    private int hostColorName, hostDepthName;
    @Nullable
    private CgGpuTexture.Desc hostColorDesc, hostDepthDesc;

    /**
     * Reads the host framebuffer's attachments and imports its textures (RDG's {@code RegisterExternalTexture}). The
     * names are read once a host framebuffer and size, again after {@link #forgetHostTargets}; the textures every time,
     * since a name's image may change under it (the tracked backend renames one an upload replaces whole).
     */
    private void readHost() {
        int host = startFramebuffer, width = startViewport.get(2), height = startViewport.get(3);
        CgGLImports imports = CgGLImports.get();
        if (host != hostRead || width != hostReadWidth || height != hostReadHeight) {
            CgGL.glBindFramebuffer(CgGL.GL_FRAMEBUFFER, host);
            otherBound = false;
            hostColorName = hostAttachment(CgGL.GL_COLOR_ATTACHMENT0);
            hostDepthName = hostAttachment(CgGL.GL_DEPTH_ATTACHMENT);
            hostColorDesc = hostColorName > 0 ? imports.describe(hostColorName) : null;
            hostDepthDesc = hostDepthName > 0 ? imports.describe(hostDepthName) : null;
            hostRead = host;
            hostReadWidth = width;
            hostReadHeight = height;
        }
        hostColor = hostColorDesc != null ? imports.texture(hostColorName, hostColorDesc) : null;
        hostDepth = hostDepthDesc != null ? imports.texture(hostDepthName, hostDepthDesc) : null;
    }

    /** The texture at {@code point} of the bound framebuffer: its name, 0 for nothing there, -1 for a renderbuffer. */
    private static int hostAttachment(int point) {
        int type = CgGL.glGetFramebufferAttachmentParameteriv(CgGL.GL_FRAMEBUFFER, point,
                CgGL.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
        if (type == CgGL.GL_NONE) return 0;
        if (type != GL_ATTACHMENT_TEXTURE) return -1;
        return CgGL.glGetFramebufferAttachmentParameteriv(CgGL.GL_FRAMEBUFFER, point, GL_ATTACHMENT_NAME);
    }

    /** Why {@code pass} cannot draw on the device yet; null when it can. */
    @Nullable
    private String deviceRefuses(CgFrame frame, CgRasterPass pass, CgFrame.Raster packed, @Nullable int[] damage) {
        if (!CgGLImports.installed() || !CgGlslCompiler.installed()) return "no device";
        for (String skip : DEVICE_RASTER_SKIP) if (!skip.isEmpty() && pass.name().contains(skip)) return "skipped by name";
        boolean current = pass.target == null || pass.target.kind() == CgGraphTexture.Kind.CURRENT;
        if (current) {
            if (startFramebuffer == 0) return "the default framebuffer";
            readHost();
            if (hostColor == null) return "a host colour renderbuffer, or none";
            if (hostDepthName < 0) return "a host depth renderbuffer";
        } else if (pass.target.drawsBesideCurrentDepth()) {
            if (startFramebuffer == 0) return "beside the default framebuffer's depth";
            readHost();
            if (hostDepth == null) return "beside a host depth renderbuffer, or none";
        }
        if (pass.layer != 0) return "a layer of an array target";
        if (readOnlyDepth(frame, currentStep, pass)) return "read-only depth";
        if (damage != null && (pass.load.mask() & (CgGL.GL_DEPTH_BUFFER_BIT | CgGL.GL_STENCIL_BUFFER_BIT)) != 0) {
            return "a depth clear under damage";
        }
        for (int b = 0; b < packed.count; b++) {
            if (packed.counts[b] != null && !gpuCounts) return "draws counted on the CPU";
        }
        CgGraphTexture attachment = attachmentOf(pass);
        CgFrameBuffer extra = attachment == null ? null : storage(attachment);
        if (attachment != null && (extra == null || extra.getColorTexture(0) == null
                || extra.getColorTexture(0).rhiView() == null)) {
            return "a second attachment with no texture";
        }
        if (current) {
            if (extra != null && (extra.getWidth() != hostColor.desc().width() || extra.getHeight() != hostColor.desc().height())) {
                return "a second attachment of another size";
            }
            return null;
        }
        CgFrameBuffer storage = storage(pass.target);
        if (storage == null) return "no storage";
        if (extra != null && (extra.getWidth() != storage.levelWidth(pass.level)
                || extra.getHeight() != storage.levelHeight(pass.level))) {
            return "a second attachment of another size";
        }
        if (pass.target.drawsBesideCurrentDepth() && (storage.levelWidth(pass.level) != hostDepth.desc().width()
                || storage.levelHeight(pass.level) != hostDepth.desc().height())) {
            return "beside a host depth of another size";
        }
        for (int i = 0; i < storage.getFormat().colorSlotCount(); i++) {
            CgTexture color = storage.getColorTexture(i);
            if (color == null || color.rhiView() == null) return "a colour renderbuffer";
        }
        if (storage.getFormat().hasDepth()) {
            CgTexture depth = storage.getDepthTexture();
            if (depth == null || depth.rhiView() == null) return "a depth renderbuffer";
        }
        return null;
    }

    /**
     * {@code pass} drawn through {@code CgRHI}: what binds through {@code CgGL} (the pass's snapshot, its instance
     * buffers and tables, every texture's upload, every mesh's placement) first; then, in a device section, a render
     * pass over its target's attachments, each batch's pipeline, sets and draw through the state cache.
     */
    private void rasterDevice(CgFrame frame, CgRasterPass pass, CgFrame.Raster packed, @Nullable int[] damage) {
        devicePasses++;
        CgTrace.add(CgChannels.GL, DEVICE_PASSES, 1);
        if (glPassReasons.add("device " + pass.name())) LOGGER.warn("device raster: {} on the device", pass.name());
        if (devicePasses + glPasses >= passesReport) {
            passesReport *= 10;
            LOGGER.warn("device raster: {} passes on the device, {} on CgGL", devicePasses, glPasses);
        }
        boolean current = pass.target == null || pass.target.kind() == CgGraphTexture.Kind.CURRENT;
        CgFrameBuffer storage = current ? null : storage(pass.target);
        int level = pass.level;
        int w = current ? hostColor.desc().width() : storage.levelWidth(level);
        int h = current ? hostColor.desc().height() : storage.levelHeight(level);
        CgMeshStore store = CgMeshStore.get();
        if (packed.count > 0) {
            frame.bindings.bind(packed.constants);
            CgBoundResources.forgetTextures(CgBindingModel.PASS);
            CgBoundResources.forgetTextures(CgBindingModel.MATERIAL);
            // Through CgGL and before the device section, as the GL path copies before its first batch: the copies'
            // binds are what the pass set reads
            depthInPlace = false;
            if (pass.depthFrom() != null) copyDepthFrom(pass);
            if (pass.colorFrom() != null) copyColorFrom(pass);
            if ((packed.copyBefore[0] & (CgTargetCopy.COLOR | CgTargetCopy.DEPTH)) != 0) {
                copyTarget(pass, packed.copyBefore[0], packed.copyRect, 0);
            }
            for (int k = 0; k < KINDS; k++) if ((packed.kinds & (1 << k)) != 0) instanceBuffers[k].bind();
            packed.palette.pass(pass.viewOwner(), pass.viewX(), pass.viewY(), CgPassConstants.height(pass.constants));
            if ((packed.kinds & UNIT_KINDS) != 0 || packed.tables) {
                packed.clips.bindForDraw();
                packed.shapes.bindForDraw();
                packed.palette.bindForDraw();
            }
            deviceSets.prepare(frame.bindings);
            for (int b = 0; b < packed.count; b++) store.ready(mesh(packed, b));
        }
        CgLoad load = pass.load;
        boolean clearColor = (load.mask() & CgGL.GL_COLOR_BUFFER_BIT) != 0;
        deviceColors.clear();
        deviceFormats.clear();
        // A second attachment (composed, or beside the current depth with one) is colour 1, after the target's colour 0.
        CgGraphTexture attachment = attachmentOf(pass);
        CgGpuTexture second = attachment == null ? null : storage(attachment).getColorTexture(0).rhiView().texture();
        int colors = current || second != null ? 1 : storage.getFormat().colorSlotCount();
        for (int i = 0; i < colors + (second != null ? 1 : 0); i++) {
            CgGpuTexture texture = i == colors ? second : current ? hostColor : storage.getColorTexture(i).rhiView().texture();
            CgTextureView view = CgTextureView.attachment(texture, i == colors ? 0 : level, 0);
            deviceColors.add(new CgPassDesc.Color(view, clearColor && damage == null ? CgPassDesc.LoadOp.CLEAR : CgPassDesc.LoadOp.LOAD,
                    load.r(), load.g(), load.b(), load.a(), CgPassDesc.StoreOp.STORE));
            deviceFormats.add(texture.desc().format());
        }
        CgPassDesc.Depth depth = null;
        CgFormat depthFormat = null;
        CgGpuTexture depthTexture = current || pass.target.drawsBesideCurrentDepth() ? hostDepth
                : storage.getFormat().hasDepth() ? storage.getDepthTexture().rhiView().texture() : null;
        if (depthTexture != null) {
            boolean clearDepth = (load.mask() & CgGL.GL_DEPTH_BUFFER_BIT) != 0;
            boolean clearStencil = (load.mask() & CgGL.GL_STENCIL_BUFFER_BIT) != 0;
            depth = new CgPassDesc.Depth(CgTextureView.attachment(depthTexture, level, 0),
                    clearDepth ? CgPassDesc.LoadOp.CLEAR : CgPassDesc.LoadOp.LOAD, (float) load.depth(),
                    clearStencil ? CgPassDesc.LoadOp.CLEAR : CgPassDesc.LoadOp.LOAD, load.stencil(), CgPassDesc.StoreOp.STORE, false);
            depthFormat = depthTexture.desc().format();
        }
        int samples = deviceColors.isEmpty() ? depth.view().texture().desc().samples()
                : deviceColors.get(0).view().texture().desc().samples();
        CgRenderState passState = pass.state != null ? pass.state : CgRenderState.DEFAULT;
        CgDeviceSection.begin();
        stating = true;
        CgRenderPass rp = null;
        try {
            CgRHI rhi = CgRHI.get();
            devicePassDesc = new CgPassDesc("graph", deviceColors, depth, w, h);
            devicePassCurrent = current;
            rp = open(rhi, devicePassDesc);
            if (clearColor && damage != null) {
                for (int i = 0; i < deviceColors.size(); i++) {
                    rp.clearColor(i, load.r(), load.g(), load.b(), load.a(), damage[0], damage[1], damage[2], damage[3]);
                }
            }
            if (packed.count > 0) deviceBatches(frame, pass, packed, damage, rhi, passState, depthFormat, samples, w, h);
            rp = null;
            CgRenderPass ending = devicePass;
            devicePass = null;
            ending.end();
        } finally {
            // Ended on a throw too, or the section's end throws over it for the open pass
            if (devicePass != null) {
                devicePass.end();
                devicePass = null;
            } else if (rp != null) {
                rp.end();
            }
            stating = false;
            CgDeviceSection.end();
            if (pass.targetCopy() != null) pass.targetCopy().release(POOL);
        }
    }

    /** The device's render pass open now, and what opened it, which a copy mid-pass opens again loading. */
    @Nullable
    private CgRenderPass devicePass;
    private CgPassDesc devicePassDesc;
    private boolean devicePassCurrent;

    /** Begins {@code desc} as {@link #devicePass}, its viewport the target's, with the state cache emptied. */
    private CgRenderPass open(CgRHI rhi, CgPassDesc desc) {
        CgRenderPass rp = rhi.encoder().beginPass(desc);
        devicePass = rp;
        if (devicePassCurrent) {
            rp.setViewport(startViewport.get(0), startViewport.get(1), startViewport.get(2), startViewport.get(3), 0, 1);
        } else {
            rp.setViewport(0, 0, desc.width(), desc.height(), 0, 1);
        }
        drawCache.reset();
        return rp;
    }

    /**
     * Batch {@code b}'s target copy, between render passes as Unreal RDG splits one around a copy: the pass ended, the
     * copy made through CgGL, the pass begun again loading what it drew. Its binds are what the next sets read.
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
        if (damage == null) rp.setScissor(0, 0, w, h);
        else rp.setScissor(damage[0], damage[1], damage[2], damage[3]);
        for (int b = 0; b < packed.count; b++) {
            if (b > 0 && (packed.copyBefore[b] & (CgTargetCopy.COLOR | CgTargetCopy.DEPTH)) != 0) {
                rp = copyMidPass(rhi, pass, packed, b);
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
                        && b + 1 < packed.count && joinable(packed, b, b + 1) ? joinRun(packed, b) : b;
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
            if (packed.pipeline[b] != boundPipeline) {
                boundPipeline = packed.pipeline[b];
                pipeline = CgPipeline.byId(boundPipeline);
                linked = pipeline.program();
                layout = linked == null ? null : CgDeviceStates.layout(rhi, linked.explicit(pipeline.toString()));
            }
            CgMesh mesh = mesh(packed, b);
            CgGraphicsPipeline device = linked == null ? null
                    : pipeline.device(passState, mesh.format(), CgMeshStore.topology(mesh), !CgGL.isDepthZeroToOne(),
                            deviceFormats, depthFormat, samples);
            if (device == null) {
                if (glPassReasons.add("skipped " + pipeline)) {
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
            int binding = packed.binding[b];
            for (int i = 0; i < table.buffers(binding); i++) table.buffer(binding, i).noteBound();
            int first = b * CgDrawChunk.BUFFERS;
            if (packed.objects[b] != null) {
                bindRecords(packed.objects[b], CgBindingPoints.OBJECT_DATA);
                objectsBound = true;
            } else if (objectsBound && packed.kind[b] == OBJECT) {
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
            for (int set = 0; set < layout.sets().size(); set++) {
                if (set == CgRHI.BINDLESS_SET) {
                    drawCache.set(rp, set, rhi.bindlessSet());
                    continue;
                }
                if (layout.sets().get(set).slots().isEmpty()) continue;
                drawCache.set(rp, set, deviceSets.set(rhi, table, set == CgBindingModel.VIEW ? packed.constants : binding,
                        layout, set, null));
            }
            CgGLImports imports = CgGLImports.get();
            if (end > b || command >= 0 && packed.eachFrom[b] >= 0 && eachJoins(packed, b)) {
                if (command >= 0) {
                    int name = commands.buffer();
                    store.drawIndirectJoinedOn(rp, drawCache, mesh, imports.buffer(name),
                            imports.bufferOffset(name) + commands.offset(command), commandCount, commands.stride());
                } else {
                    store.drawJoinedOn(rp, drawCache);
                }
                b = end;
                continue;
            }
            if (command >= 0) {
                int name = commands.buffer();
                int each = packed.eachFrom[b] >= 0 ? commandCount : 1;
                for (int k = 0; k < each; k++) {
                    store.drawIndirectOn(rp, drawCache, mesh, packed.submesh[b], imports.buffer(name),
                            imports.bufferOffset(name) + commands.offset(command + k));
                }
                continue;
            }
            store.drawOn(rp, drawCache, mesh, packed.instances[b], packed.first[b], packed.submesh[b], packed.rangeFirst[b],
                    packed.rangeCount[b]);
        }
        for (CgBindingPoints.Binding at : standIn) if (at != null) bindEngineBuffer(at);
    }

    /** Binds a GPU buffer of records where an engine buffer's macros read the frame's own. */
    private void bindRecords(CgBufferHandle records, CgBindingPoints.Binding at) {
        int id = records instanceof CgGraphBuffer graph ? bufferStorage(graph, false) : records.bufferId();
        if (!stating) CgGL.glBindBufferBase(CgGL.GL_SHADER_STORAGE_BUFFER, at.ssbo(), id);
        CgBoundResources.storage(at.ssbo(), id, 0, 0);
    }

    /** Binds the engine buffer made at {@code at} again, after draws that read their own there. */
    private void bindEngineBuffer(CgBindingPoints.Binding at) {
        CgShaderBuffer owner = CgShaderBufferRegistry.get().ownerOf(at);
        if (owner != null && stating) owner.noteBound();
        else if (owner != null) owner.bind();
    }

    /** Copies the depth of the target a pass reads besides its own, whole, and binds it. */
    private void copyDepthFrom(CgRasterPass pass) {
        CgGraphTexture from = pass.depthFrom();
        CgTargetCopy copy = pass.depthFromCopy();
        if (takes && from.kind() != CgGraphTexture.Kind.CURRENT && !from.drawsBesideCurrentDepth()
                && storage(from).getDepthTexture() != null) {
            // Not attached here, and moved to SAMPLED_READ with the step: sampled where it is, as Unreal samples SceneDepth.
            storage(from).getDepthTexture().bind(pass.depthFromUnit());
            CgTrace.add(CgChannels.GL, DEPTH_SAMPLED, 1);
            return;
        }
        if (from.kind() == CgGraphTexture.Kind.CURRENT || from.drawsBesideCurrentDepth()) {
            copy.borrowDepth(sharedDepth(startFramebuffer, null, startViewport.get(2), startViewport.get(3), pass));
        } else {
            CgFrameBuffer storage = storage(from);
            copy.borrowDepth(sharedDepth(storage.getId(), storage.getFormat(), storage.getWidth(), storage.getHeight(), pass));
        }
        copy.depth.bind(pass.depthFromUnit());
    }

    /** The framebuffer whose depth {@code pass} draws into, or -1 where its target has none. */
    private int depthDestination(CgRasterPass pass) {
        CgGraphTexture target = pass.target;
        if (target == null || target.kind() == CgGraphTexture.Kind.CURRENT || target.drawsBesideCurrentDepth()
                || attachmentOf(pass) != null) {
            return startFramebuffer;
        }
        CgFrameBuffer storage = storage(target);
        return storage.getFormat().hasDepth() ? storage.getId() : -1;
    }

    /**
     * The execution's copy of framebuffer {@code source}'s depth, copied now unless it already holds it: the current
     * target when {@code format} is null.
     */
    private CgTargetCopy sharedDepth(int source, @Nullable CgFrameBufferFormat format, int width, int height, CgPass pass) {
        if (source == sharedDepthSource && width == sharedDepthWidth && height == sharedDepthHeight) {
            CgTrace.add(CgChannels.GL, DEPTH_SHARED, 1);
            return sharedDepth;
        }
        sharedDepth.copyDepth(source, format, width, height, pass, POOL);
        sharedDepthSource = source;
        sharedDepthWidth = width;
        sharedDepthHeight = height;
        CgTrace.add(CgChannels.GL, TARGET_COPIES, 1);
        return sharedDepth;
    }

    /** Copies another target's colour whole, for a pass reading it ({@link CgRasterPass#sceneColor(int, CgGraphTexture)}). */
    private void copyColorFrom(CgRasterPass pass) {
        CgGraphTexture from = pass.colorFrom();
        CgTargetCopy copy = pass.colorFromCopy();
        if (from.kind() == CgGraphTexture.Kind.CURRENT) {
            copy.copyColor(startFramebuffer, null, startViewport.get(2), startViewport.get(3), POOL);
        } else {
            CgFrameBuffer storage = storage(from);
            copy.copyColor(storage.getId(), storage.getFormat(), storage.getWidth(), storage.getHeight(), POOL);
        }
        copy.color.bind(pass.colorFromUnit());
        CgTrace.add(CgChannels.GL, TARGET_COPIES, 1);
    }

    /**
     * The pass's indirect commands, one per indirect batch in batch order, written before the pass begins; each
     * count's own barrier came with the pass's access list. Where draws join, decides the runs a multi-draw draws
     * ({@link #runs}) and writes their commands in its form.
     */
    private void writeCommands(CgRasterPass pass, CgFrame.Raster packed) {
        int args = commands.reserve(packed.indirects, FORGET_BUFFER);
        bufferBarrier(args, CgAccess.COMPUTE_WRITE);
        if (runs.length < packed.count) runs = new int[Math.max(packed.count, runs.length * 2)];
        int slot = 0;
        for (int b = 0; b < packed.count; b++) {
            if (packed.counts[b] == null) continue;
            int end = b;
            if (multiDraw) {
                while (end + 1 < packed.count && joinableIndirect(packed, b, end + 1)) end++;
                if (end + 1 < packed.count && CgTrace.isEnabled(CgChannels.GL)) countBreak(packed, b, end + 1);
            }
            runs[b] = end;
            for (int k = b; k <= end; k++) slot += writeCommand(packed, k, slot);
            b = end;
        }
        commands.flush();
        bufferBarrier(args, CgAccess.INDIRECT);
        CgTrace.add(CgChannels.GL, COMMAND_COUNT, packed.indirects);
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

    /**
     * An indirect batch drawn by its count read on the CPU, where no draw can take the GPU's (tier G33, or a context
     * with no indirect draw): the same picture, at the cost of the read.
     */
    private void drawCounted(CgMesh mesh, CgFrame.Raster packed, int b, long countOffset, int records, int firstInstance) {
        CgBufferHandle handle = packed.counts[b];
        int countId = handle instanceof CgGraphBuffer graph ? bufferStorage(graph, false) : handle.bufferId();
        CgBufferReadback.readWords(countId, countOffset, countWord, 0, 1);
        long held = Integer.toUnsignedLong(countWord[0]);
        long n = held * (packed.countModes[b] >>> 2);
        if (packed.objects[b] != null) n = Math.min(n, packed.instances[b]);
        CgMeshStore store = CgMeshStore.get();
        int submesh = Math.max(0, packed.submesh[b]);
        if ((packed.countModes[b] & 3) == CgIndirect.INSTANCES.ordinal()) {
            if (n > 0) {
                store.draw(mesh, (int) Math.min(n, Integer.MAX_VALUE), firstInstance, submesh, packed.rangeFirst[b],
                        packed.rangeCount[b]);
            }
        } else if (store.range(mesh, submesh, packed.rangeFirst[b], packed.rangeCount[b], range)) {
            long count = Math.min(n, range[1]);
            if (count > 0) store.draw(mesh, records, firstInstance, submesh, packed.rangeFirst[b], (int) count);
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
    private void copyTarget(CgRasterPass pass, int bits, int[] rects, int at) {
        CgGraphTexture target = pass.target;
        CgTargetCopy copy = pass.targetCopy();
        if (depthInPlace && (bits & CgTargetCopy.DEPTH) != 0) {
            // Read-only in this pass: sampled where it is, no copy.
            if (hostDepth(pass)) CgGL.cgBindTexture(pass.sceneDepthUnit(), CgGL.GL_TEXTURE_2D, CgGL.cgDepthTexture(startFramebuffer));
            else storage(target).getDepthTexture().bind(pass.sceneDepthUnit());
            bits &= ~(CgTargetCopy.DEPTH | CgTargetCopy.CLEAN);
            if (bits == 0) return;
        }
        if ((bits & (CgTargetCopy.DEPTH | CgTargetCopy.CLEAN)) == (CgTargetCopy.DEPTH | CgTargetCopy.CLEAN)) {
            // The target's depth as the pass found it: what an earlier pass may have copied already.
            if (target == null || target.kind() == CgGraphTexture.Kind.CURRENT) {
                copy.borrowDepth(sharedDepth(startFramebuffer, null, startViewport.get(2), startViewport.get(3), pass));
            } else if (target.drawsBesideCurrentDepth()) {
                CgFrameBuffer storage = storage(target);
                copy.borrowDepth(sharedDepth(startFramebuffer, null, storage.getWidth(), storage.getHeight(), pass));
            } else {
                CgFrameBuffer storage = storage(target);
                copy.borrowDepth(sharedDepth(storage.getId(), storage.getFormat(), storage.getWidth(),
                        storage.getHeight(), pass));
            }
            copy.depth.bind(pass.sceneDepthUnit());
            bits &= ~(CgTargetCopy.DEPTH | CgTargetCopy.CLEAN);
            if (bits == 0) return;
        }
        bits &= ~CgTargetCopy.CLEAN;
        long pixels;
        if (target == null || target.kind() == CgGraphTexture.Kind.CURRENT) {
            pixels = copy.copy(startFramebuffer, null, startViewport.get(2), startViewport.get(3), bits, rects, at, POOL);
        } else if (target.drawsBesideCurrentDepth()) {
            CgFrameBuffer storage = storage(target);
            pixels = copy.copyBeside(storage.getId(), storage.getFormat(), startFramebuffer, storage.getWidth(),
                    storage.getHeight(), bits, rects, at, POOL);
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

    /** How many colour attachments a pass draws into: its target's slots, and its second attachment if bound. */
    private int colorSlots(CgRasterPass pass) {
        if (composed) return 2;
        CgGraphTexture target = pass.target;
        if (target == null || target.kind() == CgGraphTexture.Kind.CURRENT) return 1;
        return storage(target).getFormat().colorSlotCount();
    }

    /**
     * Masks off every slot above 0 that {@code pipeline}'s program does not write, so an unwritten output never lands;
     * on a device it is the pipeline's per-attachment write mask. Slot 0 keeps the pipeline's own mask.
     */
    private static void maskSlots(CgPipeline pipeline, int slots) {
        int writes = pipeline.slotWrites();
        for (int k = 1; k < slots; k++) {
            boolean on = (writes & 1 << k) != 0;
            CgGL.glColorMaski(k, on, on, on, on);
        }
    }

    /**
     * Binds a pass's target with its second attachment, and the target's viewport. Beside the current target that is
     * a framebuffer of ours holding the host's colour and depth ({@link CgComposedTargets}). False, binding nothing,
     * where the target takes none and the attachment is optional; where it is not, throws.
     */
    private boolean bindComposed(CgRasterPass pass) {
        CgGraphTexture attachment = attachmentOf(pass);
        CgFrameBuffer extra = storage(attachment);
        CgGraphTexture target = pass.target;
        int host, x, y, w, h;
        if (target == null || target.kind() == CgGraphTexture.Kind.CURRENT) {
            host = startFramebuffer;
            x = startViewport.get(0);
            y = startViewport.get(1);
            w = startViewport.get(2);
            h = startViewport.get(3);
        } else {
            CgFrameBuffer storage = storage(target);
            host = storage.getId();
            x = 0;
            y = 0;
            w = storage.getWidth();
            h = storage.getHeight();
        }
        String refused = null;
        if (x != 0 || y != 0 || extra.getWidth() != w || extra.getHeight() != h) {
            // GL would draw into the intersection, quietly.
            refused = attachment + " is " + extra.getWidth() + "x" + extra.getHeight() + ", beside a target of "
                    + w + "x" + h + " at " + x + "," + y;
        } else {
            refused = COMPOSED.bind(host, w, h, extra.getColorTexture(0).getId(), extra.getFormat().getColorSlot(0));
        }
        if (refused != null) {
            if (!pass.attachmentOptional()) throw new IllegalStateException(pass + ": " + refused);
            if (!CgComposedTargets.refused(host)) LOGGER.warn("{} draws without its second attachment: {}", pass, refused);
            CgComposedTargets.refuse(host);
            otherBound = true;   // bindTarget binds the start framebuffer again
            return false;
        }
        CgGL.glViewport(0, 0, w, h);
        otherBound = startNoted;
        return true;
    }

    /**
     * Binds {@code target} beside the current target's depth ({@link CgGraphTexture#besideCurrentDepth}), and its
     * viewport. False, binding nothing, where the current target lends none: logged once, and the pass draws into the
     * target alone.
     */
    private boolean bindBeside(CgGraphTexture target) {
        return bindBeside(target, null);
    }

    /** As {@link #bindBeside(CgGraphTexture)}, with {@code second} at colour attachment 1 where given. */
    private boolean bindBeside(CgGraphTexture target, @Nullable CgGraphTexture second) {
        CgFrameBuffer scene = storage(target);
        CgFrameBuffer extra = second != null ? storage(second) : null;
        int x = startViewport.get(0), y = startViewport.get(1), w = startViewport.get(2), h = startViewport.get(3);
        if (extra != null && (extra.getWidth() != scene.getWidth() || extra.getHeight() != scene.getHeight())) {
            throw new IllegalStateException(second + " is " + extra.getWidth() + "x" + extra.getHeight() + ", beside "
                    + target + " of " + scene.getWidth() + "x" + scene.getHeight());
        }
        if (x != 0 || y != 0 || scene.getWidth() != w || scene.getHeight() != h) {
            // A recorder sizing it from a stale size, as across a resize: this pass alone, not the framebuffer, refused.
            long sizes = (long) scene.getWidth() << 48 | (long) scene.getHeight() << 32 | (long) w << 16 | h;
            if (sizes != besideMismatch) {
                besideMismatch = sizes;
                LOGGER.warn("{} is {}x{}, beside a target of {}x{} at {},{}: drawn without the host's depth", target,
                        scene.getWidth(), scene.getHeight(), w, h, x, y);
            }
            return false;
        }
        String refused = extra == null
                ? BESIDE.bind(startFramebuffer, w, h, scene.getColorTexture(0).getId(), scene.getFormat().getColorSlot(0))
                : BESIDE.bind(startFramebuffer, w, h, scene.getColorTexture(0).getId(), scene.getFormat().getColorSlot(0),
                        extra.getColorTexture(0).getId(), extra.getFormat().getColorSlot(0));
        if (refused != null) {
            if (!CgComposedTargets.refused(startFramebuffer)) LOGGER.warn("{} draws without the host's depth: {}", target, refused);
            CgComposedTargets.refuse(startFramebuffer);
            return false;
        }
        CgGL.glViewport(0, 0, w, h);
        otherBound = startNoted;
        return true;
    }

    /**
     * Binds level {@code level} (or an array's layer {@code layer}) of a pass's target and its viewport. The current
     * target is left as it is, unless a pass of this execution bound another: then what was bound when it began is
     * bound again.
     */
    private void bindTarget(CgGraphTexture target, int level, int layer) {
        if (target == null || target.kind() == CgGraphTexture.Kind.CURRENT) {
            if (otherBound) {
                CgGL.glBindFramebuffer(CgGL.GL_FRAMEBUFFER, startFramebuffer);
                CgGL.glViewport(startViewport.get(0), startViewport.get(1), startViewport.get(2), startViewport.get(3));
                otherBound = false;
            }
            return;
        }
        CgFrameBuffer storage = storage(target);
        if (layer != 0) storage.bindLayer(layer);
        else storage.bindLevel(level);
        CgGL.glViewport(0, 0, storage.levelWidth(level), storage.levelHeight(level));
        otherBound = startNoted;
    }

    /** A readback's copy, issued here; its sink hears from {@link CgReadback#poll} once the GPU has finished. */
    private static void readback(CgPass.Readback r) {
        r.delivering = true;
        if (r.buffer != null) {
            CgReadback.buffer(bufferStorage(r.buffer, false), r.offset, r.size, r);
            return;
        }
        CgFrameBuffer storage = storage(r.texture);
        if (storage.isVolume() || storage.isArray()) {
            CgReadback.slices(storage.getColorTexture(0).getId(), r.level, r.x, r.y, r.z, r.w, r.h, r.d,
                    storage.getFormat().getColorSlot(0), r);
        } else {
            CgReadback.pixels(storage.levelId(r.level), r.x, r.y, r.w, r.h, storage.getFormat().getColorSlot(0), r);
        }
    }

    /** A released storage's framebuffer deleted, its states forgotten. */
    private static void free(@Nullable CgFrameBuffer storage) {
        if (storage == null) return;
        forgetStorage(storage);
        storage.delete();
    }

    /** {@code storage}'s states forgotten, before it is deleted: its names may be someone else's next. */
    private static void forgetStorage(CgFrameBuffer storage) {
        STATES.forget(CgResourceStates.framebuffer(storage.getId()));
        for (int slot = 0; slot < storage.getFormat().colorSlotCount(); slot++) {
            CgTexture color = storage.getColorTexture(slot);
            if (color != null) STATES.forget(CgResourceStates.texture(color.getId()));
        }
    }

    /** A texture's storage now: a requested one's is made on first use, a history's both versions, cleared. */
    private static CgFrameBuffer storage(CgGraphTexture texture) {
        CgFrameBuffer[] versions = texture.versions();
        if (versions != null && versions[0] == null) {
            CgTrace.add(CgChannels.GL, "graph.history.made", 1);
            boolean stated = takes && CgGL.cgGraphStates(false);   // the clears are GL's: the device lays them
            try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, MAKE_STORAGE)) {
                for (int v = 0; v < 2; v++) {
                    versions[v] = CgTexturePool.create("cg_graph_" + texture.resource().name() + v, texture.desc());
                    forgetStorage(versions[v]);
                    versions[v].clearColor(0f, 0f, 0f, 0f);
                }
            } finally {
                if (takes) CgGL.cgGraphStates(stated);
            }
        }
        CgFrameBuffer storage = texture.framebuffer();
        if (storage == null && texture.kind() == CgGraphTexture.Kind.REQUESTED) {
            // At a requested texture's first use; made again later, the picture it held was lost.
            CgTrace.add(CgChannels.GL, "graph.requested.made", 1);
            try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, MAKE_STORAGE)) {
                storage = CgTexturePool.create("cg_graph_" + texture.name(), texture.desc());
            }
            forgetStorage(storage);
            texture.resolve(storage);
        }
        if (storage == null) throw new IllegalStateException(texture + " has no storage in this pass");
        return storage;
    }
}
