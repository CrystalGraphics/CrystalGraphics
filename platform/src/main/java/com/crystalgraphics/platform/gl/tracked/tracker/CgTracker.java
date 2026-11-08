package com.crystalgraphics.platform.gl.tracked.tracker;

import com.crystalgraphics.platform.gl.tracked.CgImplicitLayouts;
import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.platform.rhi.resource.CgRHIResource;
import com.crystalgraphics.platform.rhi.command.CgAccess;
import com.crystalgraphics.platform.rhi.command.CgCommandList;
import com.crystalgraphics.platform.rhi.command.CgComputePass;
import com.crystalgraphics.platform.rhi.command.CgPassDesc;
import com.crystalgraphics.platform.rhi.command.CgRenderPass;
import com.crystalgraphics.platform.rhi.command.CgTransition;
import com.crystalgraphics.platform.rhi.format.CgAttribFormat;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.platform.rhi.binding.CgBindingLayout;
import com.crystalgraphics.platform.rhi.pipeline.CgComputePipeline;
import com.crystalgraphics.platform.rhi.pipeline.CgGraphicsPipeline;
import com.crystalgraphics.platform.rhi.pipeline.CgGraphicsPipelineDesc;
import com.crystalgraphics.platform.rhi.pipeline.CgPipelineCache;
import com.crystalgraphics.platform.rhi.resource.CgGpuBuffer;
import com.crystalgraphics.platform.rhi.resource.CgGpuTexture;
import com.crystalgraphics.platform.rhi.resource.CgTextureView;
import com.crystalgraphics.platform.rhi.shader.CgShaderModule;
import com.crystalgraphics.platform.rhi.memory.CgAllocation;
import com.crystalgraphics.platform.rhi.memory.CgFrameArena;
import com.crystalgraphics.platform.rhi.memory.CgSlabAllocator;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.trace.CgTraceChannel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.function.IntFunction;

/**
 * Turns GL's immediate model into passes, pipelines and per-draw bindings on a {@link CgRHI} (spec §5.1).
 *
 * <pre>{@code
 * CgTracker tracker = new CgTracker(device, false);
 * tracker.bindTarget(CgTarget.surface(device));      // glBindFramebuffer: the next pass's target
 * tracker.clear(true, 0, 0, 0, 1, true, 1, false, 0); // before any draw: the pass's load op
 * tracker.state.program = program;                    // ... and the rest of the draw's state
 * tracker.draw(CgGraphicsPipelineDesc.Topology.TRIANGLES, 6, 1, 0, 0);   // begins the pass
 * tracker.transfer().writeTexture(...);               // ends it; the next draw resumes with LOAD
 * tracker.endFrame();
 * }</pre>
 *
 * <ul>
 *   <li>A pass begins at the first draw after its target is bound, and ends at a draw into another target, a
 *       transfer ({@link #transfer()}), a dispatch, a barrier, a host section or the end of the frame.</li>
 *   <li>Consecutive dispatches share a compute pass, which barriers do not end. The first dispatch after a draw
 *       waits for the draws before it, as GL orders a kernel; a draw reading what a kernel wrote waits only for the
 *       barrier GL asks for too ({@link #memoryBarrier}).</li>
 *   <li>A clear before a pass's first draw, over the whole target, is its load op; any other is an attachment
 *       clear inside the pass. A colour clear under a partial write mask is drawn, through the mask, since a
 *       device's clear writes every channel; a partial stencil mask still clears every bit, with one warning.</li>
 *   <li>With {@code debug} on, sampling a texture the open pass renders to is refused.</li>
 *   <li>Owner thread only.</li>
 * </ul>
 */
public final class CgTracker {

    private static final long SLAB = 4L << 20;
    private static final CgTraceChannel GL = CgTrace.channel("crystalgraphics.gl");
    /** A pipeline the device built at its first draw, and how many: on a cold driver cache 10-100 ms each. */
    private static final Logger LOGGER = LogManager.getLogger("CgTracker");

    public final CgDrawState state = new CgDrawState();
    public final CgTrackerStats stats = new CgTrackerStats();

    private final CgRHI device;
    private final boolean debug;
    private final CgSlabAllocator host, local, readable, stream;
    private final CgFrameArena frameUploads;
    /** Where the last {@link #frameAllocate} put its bytes in the allocation it answered. */
    private long frameOffset;
    /** Every graphics pipeline, by its full description: the RHI's cache, with the recorded list. */
    private final CgPipelineCache pipelines;
    private boolean zeroToOneClip;
    private boolean warnedMaskedClear;
    private IntFunction<CgTrackedProgram> clearPrograms;

    /** What a masked clear draws: a position and the clear colour per vertex, three vertices over the target. */
    private static final List<CgGraphicsPipelineDesc.VertexBuffer> CLEAR_LAYOUT = List.of(new CgGraphicsPipelineDesc.VertexBuffer(0,
            24, false, List.of(new CgGraphicsPipelineDesc.VertexAttrib(0, CgAttribFormat.FLOAT32X2, 0),
                    new CgGraphicsPipelineDesc.VertexAttrib(1, CgAttribFormat.FLOAT32X4, 8))));

    private CgTarget target;
    private CgRenderPass pass;
    private CgTarget passTarget;

    private CgComputePass compute;
    /** Between {@link #beginAsync} and {@link #endAsync}: what is recorded goes to the compute queue. */
    private boolean async;
    /**
     * Dispatches recorded; the barriers asked for since the last one, which the encoder records before the next; and
     * the last such barrier known recorded: after how many dispatches, on which queue, for what accesses.
     */
    private long dispatchesRecorded, flushedAt = -1;
    private int pendingFrom, pendingTo, flushedOn, flushedFrom, flushedTo;
    private boolean barrierPending;
    /** Between {@link #beginTransfer} and {@link #endTransfer}: buffer copies may go to the transfer queue. */
    private boolean transfer;
    private CgComputePipeline computePipeline;
    private boolean drawnSinceCompute = true;

    private int pendingColors;
    private float clearR, clearG, clearB, clearA;
    private boolean pendingDepth, pendingStencil;
    private float clearDepthValue;
    private int clearStencilValue;

    private CgGraphicsPipeline passPipeline;
    private boolean passFresh;
    /** The passes begun lately, each reused while its target, its loads and its clears are a new one's: a frame's few. */
    private final CgPassDesc[] recentPasses = new CgPassDesc[8];
    private int recentNext;
    private int vx, vy, vw, vh, sx, sy, sw, sh, stencilRef;
    private float biasConstant, biasSlope;

    private CgTrackedProgram keyProgram;
    private CgShaderModule keyVertex;
    private CgGraphicsPipelineDesc.Raster keyRaster;
    private CgGraphicsPipelineDesc.DepthStencil keyDepthStencil;
    private CgGraphicsPipelineDesc.Blend keyBlend;
    private int keyMasks;
    private List<CgGraphicsPipelineDesc.VertexBuffer> keyLayouts;
    private CgGraphicsPipelineDesc.Topology keyTopology;
    private CgTarget keyTarget;
    private CgGraphicsPipeline keyPipeline;

    /**
     * Pipelines by the identity of what picks them, kept across frames: the hit for draws alternating between states,
     * which the last key misses, with no description built or hashed. Holds because records keep their identity while
     * their values do (TrackedRenderState), and so does a target (TrackedFramebuffers).
     */
    private final Map<PipelineKey, CgGraphicsPipeline> byIdentity = new HashMap<>();
    private final PipelineKey probe = new PipelineKey();
    private static final int MAX_BY_IDENTITY = 1024;

    /** @param debug refuse feedback loops; a check per sampled texture per draw */
    public CgTracker(CgRHI device, boolean debug) {
        this.device = device;
        this.pipelines = new CgPipelineCache(device);
        this.debug = debug;
        long align = Math.max(16, Math.max(device.globals().limits().uniformOffsetAlignment(),
                Math.max(device.globals().limits().storageOffsetAlignment(), device.globals().limits().texelOffsetAlignment())));
        this.host = new CgSlabAllocator(device, true, false, false, SLAB, align);
        this.local = new CgSlabAllocator(device, false, false, false, SLAB, align);
        this.readable = new CgSlabAllocator(device, true, true, false, SLAB, align);
        this.stream = new CgSlabAllocator(device, true, false, true, SLAB, align);
        this.frameUploads = new CgFrameArena(stream, align);
    }

    public CgRHI device() { return device; }

    public CgTrackerStats stats() { return stats; }

    /** Programs draw with their zero-to-one vertex stage: a pass into a host depth that uses that range. */
    public void setZeroToOneClip(boolean zeroToOne) { zeroToOneClip = zeroToOne; }

    public boolean zeroToOneClip() { return zeroToOneClip; }

    // ── targets and clears ─────────────────────────────────────────────────────

    /** {@code glBindFramebuffer} for drawing. The open pass continues until something needs another target. */
    public void bindTarget(CgTarget t) {
        if (t == target) return;
        // An equal target is taken too, so the next draw finds it by identity rather than comparing again.
        if (!t.equals(target)) flushPendingClears();
        target = t;
    }

    public CgTarget target() { return target; }

    /**
     * The programs a colour clear under a partial write mask is drawn with, by the target's attachment count:
     * position at location 0, colour at 1, the colour written to each output. Without them, such a clear writes
     * every channel, with one warning.
     */
    public void setClearPrograms(IntFunction<CgTrackedProgram> programFor) { clearPrograms = programFor; }

    /**
     * {@code glClear} on the bound target, honouring the write masks and scissor in {@link #state} as GL does.
     */
    public void clear(boolean color, float r, float g, float b, float a,
                      boolean depth, float depthValue, boolean stencil, int stencilValue) {
        if (target == null) throw new IllegalStateException("glClear with no framebuffer bound");
        int colors = 0, masked = 0;
        if (color) {
            for (int i = 0; i < target.colors().size(); i++) {
                int mask = (state.colorMasks >>> (4 * i)) & 0xF;
                if (mask == 0) continue;
                if (mask != 0xF && clearPrograms != null) {
                    masked |= 1 << i;
                    continue;
                }
                if (mask != 0xF) warnMasked("colour mask " + Integer.toBinaryString(mask));
                colors |= 1 << i;
            }
        }
        boolean hasDepth = target.depth() != null;
        CgFormat df = hasDepth ? target.depth().texture().desc().format() : null;
        depth &= hasDepth && df.hasDepth() && state.depthStencil.depthWrite();
        stencil &= hasDepth && df.hasStencil() && (state.depthStencil.writeMask() & 0xFF) != 0;
        if (stencil && (state.depthStencil.writeMask() & 0xFF) != 0xFF) warnMasked("stencil mask");
        if (colors == 0 && masked == 0 && !depth && !stencil) return;

        boolean whole = !state.scissorTest || (state.scissorX <= 0 && state.scissorY <= 0
                && state.scissorX + state.scissorWidth >= target.width() && state.scissorY + state.scissorHeight >= target.height());
        boolean inPass = pass != null && (passTarget == target || passTarget.equals(target));
        if (!inPass && whole && masked == 0) {
            pendingColors |= colors;
            if (colors != 0) { clearR = r; clearG = g; clearB = b; clearA = a; }
            if (depth) { pendingDepth = true; clearDepthValue = depthValue; }
            if (stencil) { pendingStencil = true; clearStencilValue = stencilValue; }
            stats.clearsAsLoadOps++;
            return;
        }
        ensurePass();
        int x = whole ? 0 : state.scissorX, y = whole ? 0 : state.scissorY;
        int w = whole ? target.width() : state.scissorWidth, h = whole ? target.height() : state.scissorHeight;
        for (int i = 0; i < target.colors().size(); i++) {
            if ((colors & (1 << i)) != 0) pass.clearColor(i, r, g, b, a, x, y, w, h);
            if ((masked & (1 << i)) != 0) clearByDraw(i, (state.colorMasks >>> (4 * i)) & 0xF, r, g, b, a, x, y, w, h);
        }
        if (depth || stencil) pass.clearDepthStencil(depth, depthValue, stencil, stencilValue, x, y, w, h);
        stats.clearsInPass++;
    }

    /** A colour clear through a partial write mask: a triangle over the target, scissored to the cleared rect. */
    private void clearByDraw(int attachment, int mask, float r, float g, float b, float a, int x, int y, int w, int h) {
        CgTrackedProgram clearProgram = clearPrograms.apply(passTarget.colors().size());
        List<CgGraphicsPipelineDesc.ColorTarget> targets = new ArrayList<>(passTarget.colors().size());
        for (int i = 0; i < passTarget.colors().size(); i++) {
            targets.add(new CgGraphicsPipelineDesc.ColorTarget(passTarget.colors().get(i).texture().desc().format(), null,
                    i == attachment ? mask : 0));
        }
        CgFormat depthFormat = passTarget.depth() == null ? null : passTarget.depth().texture().desc().format();
        CgGraphicsPipelineDesc desc = new CgGraphicsPipelineDesc(clearProgram.label, clearProgram.pipelineLayout, clearProgram.vertexGlDepth,
                clearProgram.fragment, CLEAR_LAYOUT, CgGraphicsPipelineDesc.Topology.TRIANGLES, CgGraphicsPipelineDesc.Raster.DEFAULT,
                CgGraphicsPipelineDesc.DepthStencil.OFF, targets, depthFormat,
                passTarget.colors().get(attachment).texture().desc().samples());
        CgGraphicsPipeline p = pipeline(desc, 0, false);
        pass.setPipeline(p);
        passPipeline = p;

        CgAllocation vertices = frameAllocate(3 * 24);
        long first = vertices.offset + frameOffset;
        ByteBuffer m = vertices.buffer.mapped();
        float[] corners = {-1, -1, 3, -1, -1, 3};
        for (int v = 0; v < 3; v++) {
            int at = (int) first + 24 * v;
            m.putFloat(at, corners[2 * v]).putFloat(at + 4, corners[2 * v + 1]).putFloat(at + 8, r).putFloat(at + 12, g)
                    .putFloat(at + 16, b).putFloat(at + 20, a);
        }
        pass.setVertexBuffer(0, vertices.buffer, first);
        pass.setViewport(0, 0, passTarget.width(), passTarget.height(), 0, 1);
        pass.setScissor(x, y, w, h);
        pass.draw(3, 1, 0, 0);
        passFresh = true;                   // the next draw sets its own viewport and scissor again
    }

    // ── draws ──────────────────────────────────────────────────────────────────

    public void draw(CgGraphicsPipelineDesc.Topology topology, int vertexCount, int instanceCount, int firstVertex, int firstInstance) {
        prepareDraw(topology);
        pass.draw(vertexCount, instanceCount, firstVertex, firstInstance);
        stats.draws++;
    }

    public void drawIndexed(CgGraphicsPipelineDesc.Topology topology, int indexCount, int instanceCount, int firstIndex,
                            int baseVertex, int firstInstance) {
        if (state.index == null) throw new IllegalStateException("drawIndexed with no element buffer");
        prepareDraw(topology);
        pass.setIndexBuffer(state.index.buffer, state.index.offset + state.indexOffset, state.wideIndex);
        markUsed(state.index);
        pass.drawIndexed(indexCount, instanceCount, firstIndex, baseVertex, firstInstance);
        stats.draws++;
    }

    /**
     * {@code glDrawArraysIndirect} and its kin: {@code draws} argument records {@code stride} bytes apart at
     * {@code offset} in {@code args}, as many as {@code count} holds at {@code countOffset} when it is not null.
     */
    public void drawIndirect(CgGraphicsPipelineDesc.Topology topology, boolean indexed, CgAllocation args, long offset,
                             int draws, int stride, CgAllocation count, long countOffset) {
        if (indexed && state.index == null) throw new IllegalStateException("An indexed indirect draw with no element buffer");
        prepareDraw(topology);
        if (indexed) {
            pass.setIndexBuffer(state.index.buffer, state.index.offset + state.indexOffset, state.wideIndex);
            markUsed(state.index);
        }
        markUsed(args);
        long at = args.offset + offset;
        if (count == null) {
            if (indexed) pass.drawIndexedIndirect(args.buffer, at, draws, stride);
            else pass.drawIndirect(args.buffer, at, draws, stride);
        } else {
            markUsed(count);
            long countAt = count.offset + countOffset;
            if (indexed) pass.drawIndexedIndirectCount(args.buffer, at, count.buffer, countAt, draws, stride);
            else pass.drawIndirectCount(args.buffer, at, count.buffer, countAt, draws, stride);
        }
        stats.draws++;
    }

    private void prepareDraw(CgGraphicsPipelineDesc.Topology topology) {
        if (state.program == null) throw new IllegalStateException("Draw with no program");
        ensurePass();
        CgGraphicsPipeline p = pipeline(topology);
        if (p != passPipeline) {
            pass.setPipeline(p);
            passPipeline = p;
            stats.pipelineBinds++;
        }
        for (int i = 0; i < state.bindings.count(); i++) {
            if (state.bindings.type(i) == CgBindingLayout.Type.SAMPLED_TEXTURE) {
                if (debug && passTarget.attaches(state.bindings.view(i)) && !(readOnlyDepth && passTarget.depth() != null
                        && passTarget.depth().texture() == state.bindings.view(i).texture()))   // read-only depth: sampled in place
                    throw new IllegalStateException(state.program.label + " samples '"
                            + state.bindings.view(i).texture().label() + "', which the pass renders to");
            } else {
                markUsed(state.bindingAllocation(i));
            }
        }
        pass.pushBindings(state.bindings);
        List<CgGraphicsPipelineDesc.VertexBuffer> layouts = state.vertexLayouts;
        for (int i = 0; i < layouts.size(); i++) {
            CgGraphicsPipelineDesc.VertexBuffer vb = layouts.get(i);
            CgAllocation a = state.vertexAllocations[vb.binding()];
            if (a == null) throw new IllegalStateException("Vertex binding " + vb.binding() + " has no buffer");
            pass.setVertexBuffer(vb.binding(), a.buffer, a.offset + state.vertexOffsets[vb.binding()]);
            markUsed(a);
        }
        dynamicState();
    }

    private void dynamicState() {
        CgDrawState s = state;
        int x = 0, y = 0, w = passTarget.width(), h = passTarget.height();
        if (s.scissorTest) { x = s.scissorX; y = s.scissorY; w = s.scissorWidth; h = s.scissorHeight; }
        if (passFresh || s.viewportX != vx || s.viewportY != vy || s.viewportWidth != vw || s.viewportHeight != vh) {
            vx = s.viewportX; vy = s.viewportY; vw = s.viewportWidth; vh = s.viewportHeight;
            pass.setViewport(vx, vy, vw, vh, 0, 1);
        }
        if (passFresh || x != sx || y != sy || w != sw || h != sh) {
            sx = x; sy = y; sw = w; sh = h;
            pass.setScissor(sx, sy, sw, sh);
        }
        if (s.raster.depthBias() && (passFresh || s.depthBiasConstant != biasConstant || s.depthBiasSlope != biasSlope)) {
            biasConstant = s.depthBiasConstant;
            biasSlope = s.depthBiasSlope;
            pass.setDepthBias(biasConstant, biasSlope);
        }
        if (s.depthStencil.stencilTest() && (passFresh || s.stencilReference != stencilRef)) {
            stencilRef = s.stencilReference;
            pass.setStencilReference(stencilRef);
        }
        passFresh = false;
    }

    /**
     * Builds the pipeline a draw of {@code topology} would bind with the current state and the bound target, without
     * opening a pass or drawing: what puts a program through the device's own compiler ahead of its first draw.
     */
    public void buildPipeline(CgGraphicsPipelineDesc.Topology topology) {
        if (state.program == null) throw new IllegalStateException("A pipeline with no program");
        if (target == null) throw new IllegalStateException("A pipeline with no framebuffer bound");
        cachedPipeline(topology, target, state.program.vertex(zeroToOneClip));
    }

    private CgGraphicsPipeline pipeline(CgGraphicsPipelineDesc.Topology topology) {
        CgDrawState s = state;
        CgShaderModule vertex = s.program.vertex(zeroToOneClip);
        if (keyPipeline != null && s.program == keyProgram && vertex == keyVertex && s.raster == keyRaster
                && s.depthStencil == keyDepthStencil && s.blend == keyBlend && s.colorMasks == keyMasks
                && s.vertexLayouts == keyLayouts && topology == keyTopology && passTarget == keyTarget) {
            return keyPipeline;
        }
        CgGraphicsPipeline p = byIdentity.get(probe.set(s.program, vertex, s.raster, s.depthStencil, s.blend, s.colorMasks,
                s.vertexLayouts, topology, passTarget));
        if (p == null) {
            p = cachedPipeline(topology, passTarget, vertex);
            if (byIdentity.size() == MAX_BY_IDENTITY) byIdentity.clear();
            byIdentity.put(probe.copy(), p);
        }
        keyProgram = s.program;
        keyVertex = vertex;
        keyRaster = s.raster;
        keyDepthStencil = s.depthStencil;
        keyBlend = s.blend;
        keyMasks = s.colorMasks;
        keyLayouts = s.vertexLayouts;
        keyTopology = topology;
        keyTarget = passTarget;
        return keyPipeline = p;
    }

    private CgGraphicsPipeline cachedPipeline(CgGraphicsPipelineDesc.Topology topology, CgTarget on, CgShaderModule vertex) {
        CgDrawState s = state;
        List<CgGraphicsPipelineDesc.ColorTarget> targets = new ArrayList<>(on.colors().size());
        for (int i = 0; i < on.colors().size(); i++) {
            CgFormat f = on.colors().get(i).texture().desc().format();
            CgGraphicsPipelineDesc.Blend blend = f.numeric() == CgFormat.Numeric.INT ? null : s.blend;
            targets.add(new CgGraphicsPipelineDesc.ColorTarget(f, blend, (s.colorMasks >>> (4 * i)) & 0xF));
        }
        CgFormat depthFormat = on.depth() == null ? null : on.depth().texture().desc().format();
        CgGraphicsPipelineDesc.DepthStencil ds = s.depthStencil;
        if (depthFormat == null) {
            ds = CgGraphicsPipelineDesc.DepthStencil.OFF;
        } else if (!depthFormat.hasStencil() && ds.stencilTest() || !depthFormat.hasDepth() && ds.depthTest()) {
            ds = new CgGraphicsPipelineDesc.DepthStencil(ds.depthTest() && depthFormat.hasDepth(), ds.depthWrite(),
                    ds.depthCompare(), ds.stencilTest() && depthFormat.hasStencil(), ds.front(), ds.back(),
                    ds.readMask(), ds.writeMask());
        }
        CgTextureView any = on.colors().isEmpty() ? on.depth() : on.colors().get(0);
        CgGraphicsPipelineDesc desc = new CgGraphicsPipelineDesc(s.program.label, s.program.pipelineLayout, vertex, s.program.fragment,
                s.vertexLayouts, topology, s.raster, ds, targets, depthFormat, any.texture().desc().samples());
        return pipeline(desc, s.program.key, vertex == s.program.vertexZeroToOne);
    }

    /** {@link CgPipelineCache#get}, counting a pipeline built at this draw in {@link #stats}. */
    private CgGraphicsPipeline pipeline(CgGraphicsPipelineDesc desc, long programKey, boolean zeroToOne) {
        long missed = pipelines.misses();
        CgGraphicsPipeline p = pipelines.get(desc, programKey, zeroToOne);
        if (pipelines.misses() != missed) stats.pipelineMisses++;
        return p;
    }

    /**
     * Starts every pipeline recorded for {@code program} building ({@link CgPipelineCache#startRecorded}): as it links,
     * on the compile worker. Any thread; the owner adopts the answer ({@link #adoptAhead}) when it installs the link.
     */
    public Map<CgGraphicsPipelineDesc, Future<CgGraphicsPipeline>> startRecorded(CgTrackedProgram program) {
        return pipelines.startRecorded(program.key, recipe -> recipe.desc(program.label, program.pipelineLayout,
                program.vertex(recipe.zeroToOne()), program.fragment));
    }

    /** Takes pipelines started ahead for a program now installed: its first draws take them. Owner thread. */
    public void adoptAhead(Map<CgGraphicsPipelineDesc, Future<CgGraphicsPipeline>> started) {
        pipelines.adopt(started);
    }

    /** Pipelines started for a link that will never install: released as each finishes. Owner thread. */
    public void orphan(Collection<Future<CgGraphicsPipeline>> started) {
        pipelines.orphan(started);
    }

    /** Drops every pipeline built from {@code program}: it was relinked or deleted. */
    public void forgetPipelines(CgTrackedProgram program) {
        pipelines.forget(desc -> desc.layout() == program.pipelineLayout);
        byIdentity.keySet().removeIf(k -> k.program == program);
        if (keyProgram == program) keyPipeline = null;
        passPipeline = null;
    }

    // ── compute ────────────────────────────────────────────────────────────────

    /** {@code glDispatchCompute}, reading the bindings in {@link #state}. */
    public void dispatch(CgComputePipeline pipeline, int groupsX, int groupsY, int groupsZ) {
        prepareDispatch(pipeline).dispatch(groupsX, groupsY, groupsZ);
        dispatchesRecorded++;
        stats.dispatches++;
    }

    /** {@code glDispatchComputeIndirect}: three group counts at {@code offset} in {@code args}. */
    public void dispatchIndirect(CgComputePipeline pipeline, CgAllocation args, long offset) {
        CgComputePass c = prepareDispatch(pipeline);
        markUsed(args);
        c.dispatchIndirect(args.buffer, args.offset + offset);
        dispatchesRecorded++;
        stats.dispatches++;
    }

    private CgComputePass prepareDispatch(CgComputePipeline pipeline) {
        outsideRenderPass();
        if (barrierPending) {
            // The encoder records what was asked before this dispatch: it orders every dispatch before it.
            barrierPending = false;
            flushedAt = dispatchesRecorded;
            flushedOn = queue();
            flushedFrom = pendingFrom;
            flushedTo = pendingTo;
            pendingFrom = pendingTo = 0;
        }
        if (compute == null) {
            if (drawnSinceCompute) {
                device.encoder().memoryBarrier(CgAccess.GRAPHICS, CgAccess.COMPUTE_READ | CgAccess.COMPUTE_WRITE);
                drawnSinceCompute = false;
            }
            compute = device.encoder().beginCompute("dispatch");
            computePipeline = null;
            stats.computePasses++;
        }
        if (pipeline != computePipeline) {
            compute.setPipeline(pipeline);
            computePipeline = pipeline;
        }
        for (int i = 0; i < state.bindings.count(); i++) {
            CgBindingLayout.Type type = state.bindings.type(i);
            if (type == CgBindingLayout.Type.SAMPLED_TEXTURE) {
                if (debug && stores(state.bindings.view(i).texture()))
                    throw new IllegalStateException("A dispatch samples '" + state.bindings.view(i).texture().label()
                            + "', which it also writes as an image");
            } else if (type != CgBindingLayout.Type.STORAGE_IMAGE) {
                CgAllocation a = state.bindingAllocation(i);
                markUsed(a);
                if (a != null) {
                    a.dispatchedAt = dispatchesRecorded + 1;
                    a.dispatchedOn = queue();
                }
            }
        }
        compute.pushBindings(state.bindings);
        return compute;
    }

    private boolean stores(CgGpuTexture texture) {
        for (int i = 0; i < state.bindings.count(); i++) {
            if (state.bindings.type(i) == CgBindingLayout.Type.STORAGE_IMAGE && state.bindings.view(i).texture() == texture)
                return true;
        }
        return false;
    }

    /** {@code glMemoryBarrier}: every resource's uses at {@code from} before {@code to}, as {@link CgAccess} bits. */
    public void memoryBarrier(int from, int to) {
        outsideRenderPass();
        device.encoder().memoryBarrier(from, to);
        pend(from, to);
    }

    /**
     * Dropped where a barrier already recorded orders {@code a}'s last dispatch: so independent emitters' dispatches
     * between two barriers share one, as a Niagara dispatch group shares one transition batch
     * (NiagaraGpuComputeDispatch.cpp, ExecuteTicks). Judged per allocation, since many share a device buffer.
     */
    public void bufferBarrier(CgAllocation a, int from, int to) {
        outsideRenderPass();
        markUsed(a);
        if (covered(a, from, to)) {
            stats.barriersCovered++;
            return;
        }
        device.encoder().bufferBarrier(a.buffer, from, to);
        pend(from, to);
    }

    /** Barriers asked for across a change of queue may land on either: none is claimed recorded. */
    private void forgetPending() {
        barrierPending = false;
        pendingFrom = pendingTo = 0;
    }

    private void pend(int from, int to) {
        barrierPending = true;
        pendingFrom |= from;
        pendingTo |= to;
    }

    /**
     * Whether the last barrier known recorded came after {@code a}'s last dispatch, on its queue, for every access asked.
     * A barrier orders everything submitted before it on its queue, so an earlier frame's dispatch counts too.
     */
    private boolean covered(CgAllocation a, int from, int to) {
        if ((from & ~(CgAccess.COMPUTE_READ | CgAccess.COMPUTE_WRITE)) != 0) return false;
        return a.dispatchedOn == flushedOn && a.dispatchedAt <= flushedAt
                && (flushedFrom & from) == from && (flushedTo & to) == to;
    }

    /** The queue what is recorded now runs on: the frame's, or the compute queue's between async brackets. */
    private int queue() {
        return async ? 2 : 1;
    }

    public void imageBarrier(CgGpuTexture texture, int from, int to) {
        textureBarrier(texture, from, to);
    }

    /** Every layer an image has: a volume's one. */
    public static int layers(CgGpuTexture image) {
        return image.desc().kind() == CgGpuTexture.Kind.D3 ? 1 : image.desc().depthOrLayers();
    }

    /** A whole texture's transition. */
    public void textureBarrier(CgGpuTexture texture, int from, int to) {
        textureBarrier(texture, 0, texture.desc().mips(), 0, layers(texture), from, to);
    }

    /** A range's transition: joins the open bracket's batch, or is recorded here. */
    public void textureBarrier(CgGpuTexture texture, int baseMip, int mips, int baseLayer, int layers, int from, int to) {
        if (batchDepth > 0) {
            batch.texture(texture, baseMip, mips, baseLayer, layers, from, to);
            return;
        }
        outsideRenderPass();
        device.encoder().transition(batch.clear().texture(texture, baseMip, mips, baseLayer, layers, from, to));
        batch.clear();
    }

    // ── transitions in batches, whole or split ─────────────────────────────────

    /** The open bracket's barriers, recorded as one transition at its end. */
    private CgTransition batch = new CgTransition();
    private int batchDepth;
    /** Split transitions begun, by id - 1; and cleared batches to reuse. */
    private CgTransition[] splits = new CgTransition[8];
    private final ArrayDeque<CgTransition> spare = new ArrayDeque<>();
    /** Whether a frame graph states every transition of what is recorded now. */
    private boolean graphStates;

    /** {@code cgBeginBarriers}: brackets nest, and the outermost end records. */
    public void beginBatch() {
        if (batchDepth++ == 0) batch.clear();
    }

    /**
     * {@code cgEndBarriers}, or with {@code split} {@code cgEndBarriersSplit}: answers the split's id for
     * {@link #endSplit}, 0 when it was recorded whole or held nothing.
     */
    public int endBatch(boolean split) {
        if (batchDepth == 0) throw new IllegalStateException("cgEndBarriers without cgBeginBarriers");
        if (--batchDepth > 0 || batch.count() == 0) return 0;
        outsideRenderPass();
        if (!split) {
            device.encoder().transition(batch);
            batch.clear();
            return 0;
        }
        int id = 0;
        while (id < splits.length && splits[id] != null) id++;
        if (id == splits.length) splits = Arrays.copyOf(splits, id * 2);
        splits[id] = batch;
        batch = spare.isEmpty() ? new CgTransition() : spare.pop();
        device.encoder().beginTransitions(splits[id]);
        return id + 1;
    }

    /** {@code cgEndSplit}: the second half of split {@code id}. */
    public void endSplit(int id) {
        if (id == 0) return;
        CgTransition t = splits[id - 1];
        if (t == null) throw new IllegalStateException("cgEndSplit(" + id + "): no such split begun");
        splits[id - 1] = null;
        outsideRenderPass();
        device.encoder().endTransitions(t);
        spare.push(t.clear());
    }

    /** Whether the passes begun from here test depth without writing it, sampling it in place. */
    private boolean readOnlyDepth;

    /** {@code cgReadOnlyDepth}: a pass open with the other mode ends, and the next begins with this one. */
    public void readOnlyDepth(boolean readOnly) {
        if (readOnly == readOnlyDepth) return;
        outsideRenderPass();
        readOnlyDepth = readOnly;
    }

    /** {@code cgGraphStates}: while on, the device infers no transition of what is recorded. Answers what it was. */
    public boolean graphStates(boolean on) {
        boolean was = graphStates;
        if (was == on) return was;
        // What the stream before recorded finishes under its own mode: a pending clear, the open pass, compute pass.
        outsideRenderPass();
        endCompute();
        graphStates = on;
        if (device instanceof CgImplicitLayouts layouts) layouts.implicitLayouts(!on);
        return was;
    }

    /** Writes out a clear still pending and ends the render pass; a compute pass stays open. */
    private void outsideRenderPass() {
        flushPendingClears();
        if (pass != null) {
            endPass();
            stats.passBreaks++;
        }
    }

    private void endCompute() {
        if (compute == null) return;
        compute.end();
        compute = null;
    }

    /** {@code cgBeginAsync}: what follows goes to the device's compute queue, after everything recorded before it. */
    public void beginAsync() {
        if (async) throw new IllegalStateException("cgBeginAsync inside async work");
        if (transfer) throw new IllegalStateException("cgBeginAsync inside a transfer");
        outsideRenderPass();
        endCompute();
        device.encoder().beginAsync();
        forgetPending();
        async = true;
        stats.asyncSections++;
    }

    /** {@code cgEndAsync}: back to the frame's queue; the point {@link #waitAsync} waits for. */
    public long endAsync() {
        if (!async) throw new IllegalStateException("cgEndAsync with no async work open");
        endCompute();
        forgetPending();
        async = false;
        return device.encoder().endAsync();
    }

    /** {@code cgWaitAsync}: what follows runs after the async work up to {@code point}. */
    public void waitAsync(long point) {
        if (async) throw new IllegalStateException("cgWaitAsync inside async work");
        outsideRenderPass();
        endCompute();
        device.encoder().waitAsync(point);
    }

    /** {@code cgBeginTransfer}: the buffer copies that follow may go to the device's transfer queue. */
    public void beginTransfer() {
        if (async) throw new IllegalStateException("cgBeginTransfer inside async work");
        if (transfer) throw new IllegalStateException("cgBeginTransfer inside a transfer");
        outsideRenderPass();
        endCompute();
        device.encoder().beginTransfer();
        transfer = true;
    }

    /** {@code cgEndTransfer}. */
    public void endTransfer() {
        if (!transfer) throw new IllegalStateException("cgEndTransfer with no transfer open");
        transfer = false;
        device.encoder().endTransfer();
    }

    // ── passes ─────────────────────────────────────────────────────────────────

    /**
     * Ends the open pass, writing out any clear still pending, and returns the encoder for a copy, an upload or
     * a readback. The next draw begins a new pass that loads what this one stored.
     */
    public CgCommandList transfer() {
        outsideRenderPass();
        endCompute();
        return device.encoder();
    }

    /**
     * The host takes its frame back: a clear still pending is written out and our open pass ends, so the host
     * never records into a pass of ours. Inside async work too, where what the host records goes to the compute
     * queue: {@link #beginAsync} left no pass and no clear to end.
     */
    public void toHost() {
        toDevice();
        device.toHost();
    }

    /**
     * Code drawing through the device itself takes the command list ({@code CgDeviceSection}): a clear still pending
     * is written out and our open pass ends, as for the host, but the frame stays ours. Both fronts record into the
     * same commands; nothing is handed to the host.
     */
    public void toDevice() {
        if (transfer) throw new IllegalStateException("A device section begins inside a transfer");
        // A section's dispatches are recorded past the tracker: no barrier flushed before it covers what follows it.
        forgetPending();
        flushedAt = -1;
        if (!async) {
            flushPendingClears();
            if (pass != null) endPass();
        }
        endCompute();
    }

    /** The host hands us its frame: the next draw begins a pass that loads what is there. */
    public void fromHost() {
        device.fromHost();
    }

    public void endFrame() {
        if (async) throw new IllegalStateException("The frame ends inside async work");
        if (transfer) throw new IllegalStateException("The frame ends inside a transfer");
        flushPendingClears();
        if (pass != null) endPass();
        endCompute();
        pipelines.endFrame();
        device.endFrame();
        keyPipeline = null;
    }

    /** Destroys a device object once every frame that may use it has retired. */
    public void release(CgRHIResource object) {
        device.release(object);
    }

    private void ensurePass() {
        if (pass != null && passTarget == target) return;
        if (pass != null && passTarget.equals(target)) {
            passTarget = target;
            return;
        }
        if (target == null) throw new IllegalStateException("Draw with no framebuffer bound");
        if (pass != null) endPass();
        beginPass();
    }

    private void flushPendingClears() {
        if (pendingColors == 0 && !pendingDepth && !pendingStencil) return;
        if (pass != null) endPass();
        beginPass();
        endPass();
    }

    /** A recent pass whose target, loads and clears are what the next would be built from; null if none. */
    private CgPassDesc recentPass() {
        List<CgTextureView> views = target.colors();
        for (CgPassDesc d : recentPasses) {
            if (d == null || d.width() != target.width() || d.height() != target.height() || d.colors().size() != views.size()) {
                continue;
            }
            boolean same = true;
            for (int i = 0; i < views.size() && same; i++) {
                CgPassDesc.Color c = d.colors().get(i);
                boolean clear = (pendingColors & (1 << i)) != 0;
                same = c.view() == views.get(i) && c.load() == (clear ? CgPassDesc.LoadOp.CLEAR : CgPassDesc.LoadOp.LOAD)
                        && (!clear || c.r() == clearR && c.g() == clearG && c.b() == clearB && c.a() == clearA);
            }
            CgPassDesc.Depth z = d.depth();
            if (target.depth() == null) {
                same &= z == null;
            } else {
                same &= z != null && z.view() == target.depth()
                        && z.depthLoad() == (pendingDepth ? CgPassDesc.LoadOp.CLEAR : CgPassDesc.LoadOp.LOAD)
                        && z.stencilLoad() == (pendingStencil ? CgPassDesc.LoadOp.CLEAR : CgPassDesc.LoadOp.LOAD)
                        && z.readOnly() == readOnlyDepth
                        && (!pendingDepth || z.clearDepth() == clearDepthValue)
                        && (!pendingStencil || z.clearStencil() == clearStencilValue);
            }
            if (same) return d;
        }
        return null;
    }

    private CgPassDesc newPass() {
        List<CgPassDesc.Color> colors = new ArrayList<>(target.colors().size());
        for (int i = 0; i < target.colors().size(); i++) {
            boolean clear = (pendingColors & (1 << i)) != 0;
            colors.add(new CgPassDesc.Color(target.colors().get(i), clear ? CgPassDesc.LoadOp.CLEAR : CgPassDesc.LoadOp.LOAD,
                    clearR, clearG, clearB, clearA, CgPassDesc.StoreOp.STORE));
        }
        CgPassDesc.Depth depth = target.depth() == null ? null : new CgPassDesc.Depth(target.depth(),
                pendingDepth ? CgPassDesc.LoadOp.CLEAR : CgPassDesc.LoadOp.LOAD, clearDepthValue,
                pendingStencil ? CgPassDesc.LoadOp.CLEAR : CgPassDesc.LoadOp.LOAD, clearStencilValue,
                CgPassDesc.StoreOp.STORE, readOnlyDepth);
        CgTextureView first = target.colors().isEmpty() ? target.depth() : target.colors().get(0);
        return new CgPassDesc(first.texture().label(), colors, depth, target.width(), target.height());
    }

    private void beginPass() {
        if (async) throw new IllegalStateException("A draw or clear inside async work: a compute queue draws nothing");
        if (transfer) throw new IllegalStateException("A draw or clear inside a transfer");
        endCompute();
        drawnSinceCompute = true;
        CgPassDesc desc = recentPass();
        if (desc == null) {
            desc = recentPasses[recentNext] = newPass();
            recentNext = (recentNext + 1) % recentPasses.length;
        }
        pendingColors = 0;
        pendingDepth = pendingStencil = false;
        pass = device.encoder().beginPass(desc);
        passTarget = target;
        passPipeline = null;
        passFresh = true;
        // Nothing is set in a new pass: the first draw needing a reference or a bias sets it, whatever its value.
        stencilRef = Integer.MIN_VALUE;
        biasConstant = biasSlope = Float.NaN;
        stats.passes++;
    }

    private void endPass() {
        pass.end();
        pass = null;
        passTarget = null;
    }

    // ── memory ─────────────────────────────────────────────────────────────────

    /** @param hostReads the CPU reads it back: cached memory, implying {@code hostVisible} */
    public CgAllocation allocate(long size, boolean hostVisible, boolean hostReads, String label) {
        return (hostReads ? readable : hostVisible ? host : local).allocate(size, label);
    }

    /**
     * Host-visible memory the CPU writes for the GPU to read about once, as GL's persistently mapped storage is: the
     * frame ring, upload leases. System memory on a discrete GPU ({@link CgGpuBuffer.Desc#streamed}).
     */
    public CgAllocation allocateStreamed(long size, String label) {
        return stream.allocate(size, label);
    }

    /**
     * Host-visible memory for this frame only, a per-draw upload: {@code size} bytes at {@link #frameOffset()} in the
     * allocation answered, until the next call. Up to a page comes from {@link CgFrameArena}, allocating nothing.
     */
    public CgAllocation frameAllocate(long size) {
        if (size <= CgFrameArena.PAGE) {
            frameOffset = frameUploads.allocate(size, device.frameIndex(), device.retiredFrame());
            return frameUploads.page();
        }
        CgAllocation a = stream.allocate(size, "frame");
        a.lastUse = device.frameIndex();
        device.whenRetired(a.lastUse, () -> stream.free(a));
        frameOffset = 0;
        return a;
    }

    /** Where the last {@link #frameAllocate}'s bytes start in the allocation it answered. */
    public long frameOffset() {
        return frameOffset;
    }

    /** Frees {@code a} once the last frame that used it has retired. */
    public void free(CgAllocation a) {
        if (a.lastUse <= device.retiredFrame()) a.owner.free(a);
        else device.whenRetired(a.lastUse, () -> a.owner.free(a));
    }

    /** Whether the CPU may write {@code a} now: no frame that used it is still in flight. */
    public boolean writable(CgAllocation a) {
        return a.lastUse <= device.retiredFrame();
    }

    public void markUsed(CgAllocation a) {
        if (a != null) a.lastUse = device.frameIndex();
    }

    private void warnMasked(String what) {
        if (warnedMaskedClear) return;
        warnedMaskedClear = true;
        System.err.println("[crystalgraphics] tracked backend: a clear under a partial " + what
                + " clears every channel (warned once)");
    }

    /** What picks a draw's pipeline, compared by identity. */
    private static final class PipelineKey {
        CgTrackedProgram program;
        CgShaderModule vertex;
        CgGraphicsPipelineDesc.Raster raster;
        CgGraphicsPipelineDesc.DepthStencil depthStencil;
        CgGraphicsPipelineDesc.Blend blend;
        int masks;
        List<CgGraphicsPipelineDesc.VertexBuffer> layouts;
        CgGraphicsPipelineDesc.Topology topology;
        CgTarget target;
        int hash;

        PipelineKey set(CgTrackedProgram program, CgShaderModule vertex, CgGraphicsPipelineDesc.Raster raster,
                        CgGraphicsPipelineDesc.DepthStencil depthStencil, CgGraphicsPipelineDesc.Blend blend, int masks,
                        List<CgGraphicsPipelineDesc.VertexBuffer> layouts, CgGraphicsPipelineDesc.Topology topology, CgTarget target) {
            this.program = program;
            this.vertex = vertex;
            this.raster = raster;
            this.depthStencil = depthStencil;
            this.blend = blend;
            this.masks = masks;
            this.layouts = layouts;
            this.topology = topology;
            this.target = target;
            int h = System.identityHashCode(program);
            h = 31 * h + System.identityHashCode(vertex);
            h = 31 * h + System.identityHashCode(raster);
            h = 31 * h + System.identityHashCode(depthStencil);
            h = 31 * h + System.identityHashCode(blend);
            h = 31 * h + masks;
            h = 31 * h + System.identityHashCode(layouts);
            h = 31 * h + topology.ordinal();
            hash = 31 * h + System.identityHashCode(target);
            return this;
        }

        PipelineKey copy() {
            return new PipelineKey().set(program, vertex, raster, depthStencil, blend, masks, layouts, topology, target);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof PipelineKey k)) return false;
            return program == k.program && vertex == k.vertex && raster == k.raster && depthStencil == k.depthStencil
                    && blend == k.blend && masks == k.masks && layouts == k.layouts && topology == k.topology
                    && target == k.target;
        }
    }
}
