package com.crystalgraphics.platform.gl.tracked.tracker;

import com.crystalgraphics.platform.device.CgDevice;
import com.crystalgraphics.platform.device.CgDeviceObject;
import com.crystalgraphics.platform.device.command.CgAccess;
import com.crystalgraphics.platform.device.command.CgCommandEncoder;
import com.crystalgraphics.platform.device.command.CgComputePass;
import com.crystalgraphics.platform.device.command.CgPassDesc;
import com.crystalgraphics.platform.device.command.CgRenderPass;
import com.crystalgraphics.platform.device.format.CgAttribFormat;
import com.crystalgraphics.platform.device.format.CgFormat;
import com.crystalgraphics.platform.device.pipeline.CgBindingLayout;
import com.crystalgraphics.platform.device.pipeline.CgComputePipeline;
import com.crystalgraphics.platform.device.pipeline.CgPipeline;
import com.crystalgraphics.platform.device.pipeline.CgPipelineDesc;
import com.crystalgraphics.platform.device.resource.CgGpuTexture;
import com.crystalgraphics.platform.device.resource.CgTextureView;
import com.crystalgraphics.platform.device.shader.CgShaderModule;
import com.crystalgraphics.platform.gl.tracked.memory.CgAllocation;
import com.crystalgraphics.platform.gl.tracked.memory.CgSlabAllocator;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

/**
 * Turns GL's immediate model into passes, pipelines and per-draw bindings on a {@link CgDevice} (spec §5.1).
 *
 * <pre>{@code
 * CgTracker tracker = new CgTracker(device, false);
 * tracker.bindTarget(CgTarget.surface(device));      // glBindFramebuffer: the next pass's target
 * tracker.clear(true, 0, 0, 0, 1, true, 1, false, 0); // before any draw: the pass's load op
 * tracker.state.program = program;                    // ... and the rest of the draw's state
 * tracker.draw(CgPipelineDesc.Topology.TRIANGLES, 6, 1, 0, 0);   // begins the pass
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
 *   <li>With {@code debug} on, sampling a texture the open pass renders to is refused (decision 21).</li>
 *   <li>Owner thread only.</li>
 * </ul>
 */
public final class CgTracker {

    private static final long SLAB = 4L << 20;

    public final CgDrawState state = new CgDrawState();
    public final CgTrackerStats stats = new CgTrackerStats();

    private final CgDevice device;
    private final boolean debug;
    private final CgSlabAllocator host, local;
    private final Map<CgPipelineDesc, CgPipeline> pipelines = new HashMap<>();
    private boolean zeroToOneClip;
    private boolean warnedMaskedClear;
    private IntFunction<CgTrackedProgram> clearPrograms;

    /** What a masked clear draws: a position and the clear colour per vertex, three vertices over the target. */
    private static final List<CgPipelineDesc.VertexBuffer> CLEAR_LAYOUT = List.of(new CgPipelineDesc.VertexBuffer(0,
            24, false, List.of(new CgPipelineDesc.VertexAttrib(0, CgAttribFormat.FLOAT32X2, 0),
                    new CgPipelineDesc.VertexAttrib(1, CgAttribFormat.FLOAT32X4, 8))));

    private CgTarget target;
    private CgRenderPass pass;
    private CgTarget passTarget;

    private CgComputePass compute;
    private CgComputePipeline computePipeline;
    private boolean drawnSinceCompute = true;

    private int pendingColors;
    private float clearR, clearG, clearB, clearA;
    private boolean pendingDepth, pendingStencil;
    private float clearDepthValue;
    private int clearStencilValue;

    private CgPipeline passPipeline;
    private boolean passFresh;
    private int vx, vy, vw, vh, sx, sy, sw, sh, stencilRef;
    private float biasConstant, biasSlope;

    private CgTrackedProgram keyProgram;
    private CgShaderModule keyVertex;
    private CgPipelineDesc.Raster keyRaster;
    private CgPipelineDesc.DepthStencil keyDepthStencil;
    private CgPipelineDesc.Blend keyBlend;
    private int keyMasks;
    private List<CgPipelineDesc.VertexBuffer> keyLayouts;
    private CgPipelineDesc.Topology keyTopology;
    private CgTarget keyTarget;
    private CgPipeline keyPipeline;

    /** @param debug refuse feedback loops; a check per sampled texture per draw */
    public CgTracker(CgDevice device, boolean debug) {
        this.device = device;
        this.debug = debug;
        long align = Math.max(16, Math.max(device.info().limits().uniformOffsetAlignment(),
                Math.max(device.info().limits().storageOffsetAlignment(), device.info().limits().texelOffsetAlignment())));
        this.host = new CgSlabAllocator(device, true, SLAB, align);
        this.local = new CgSlabAllocator(device, false, SLAB, align);
    }

    public CgDevice device() { return device; }

    public CgTrackerStats stats() { return stats; }

    /** Programs draw with their zero-to-one vertex stage: a pass into a host depth that uses that range. */
    public void setZeroToOneClip(boolean zeroToOne) { zeroToOneClip = zeroToOne; }

    public boolean zeroToOneClip() { return zeroToOneClip; }

    // ── targets and clears ─────────────────────────────────────────────────────

    /** {@code glBindFramebuffer} for drawing. The open pass continues until something needs another target. */
    public void bindTarget(CgTarget t) {
        if (t.equals(target)) return;
        flushPendingClears();
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
        boolean inPass = pass != null && passTarget.equals(target);
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
        List<CgPipelineDesc.ColorTarget> targets = new ArrayList<>(passTarget.colors().size());
        for (int i = 0; i < passTarget.colors().size(); i++) {
            targets.add(new CgPipelineDesc.ColorTarget(passTarget.colors().get(i).texture().desc().format(), null,
                    i == attachment ? mask : 0));
        }
        CgFormat depthFormat = passTarget.depth() == null ? null : passTarget.depth().texture().desc().format();
        CgPipelineDesc desc = new CgPipelineDesc(clearProgram.label, clearProgram.layout, clearProgram.vertexGlDepth,
                clearProgram.fragment, CLEAR_LAYOUT, CgPipelineDesc.Topology.TRIANGLES, CgPipelineDesc.Raster.DEFAULT,
                CgPipelineDesc.DepthStencil.OFF, targets, depthFormat,
                passTarget.colors().get(attachment).texture().desc().samples());
        CgPipeline p = pipelines.get(desc);
        if (p == null) {
            p = device.createPipeline(desc);
            pipelines.put(desc, p);
            stats.pipelineMisses++;
        }
        pass.setPipeline(p);
        passPipeline = p;

        CgAllocation vertices = frameAllocate(3 * 24);
        ByteBuffer m = vertices.memory();
        float[] corners = {-1, -1, 3, -1, -1, 3};
        for (int v = 0; v < 3; v++) {
            m.putFloat(corners[2 * v]).putFloat(corners[2 * v + 1]).putFloat(r).putFloat(g).putFloat(b).putFloat(a);
        }
        pass.setVertexBuffer(0, vertices.buffer, vertices.offset);
        pass.setViewport(0, 0, passTarget.width(), passTarget.height(), 0, 1);
        pass.setScissor(x, y, w, h);
        pass.draw(3, 1, 0, 0);
        passFresh = true;                   // the next draw sets its own viewport and scissor again
    }

    // ── draws ──────────────────────────────────────────────────────────────────

    public void draw(CgPipelineDesc.Topology topology, int vertexCount, int instanceCount, int firstVertex, int firstInstance) {
        prepareDraw(topology);
        pass.draw(vertexCount, instanceCount, firstVertex, firstInstance);
        stats.draws++;
    }

    public void drawIndexed(CgPipelineDesc.Topology topology, int indexCount, int instanceCount, int firstIndex,
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
    public void drawIndirect(CgPipelineDesc.Topology topology, boolean indexed, CgAllocation args, long offset,
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

    private void prepareDraw(CgPipelineDesc.Topology topology) {
        if (state.program == null) throw new IllegalStateException("Draw with no program");
        ensurePass();
        CgPipeline p = pipeline(topology);
        if (p != passPipeline) {
            pass.setPipeline(p);
            passPipeline = p;
            stats.pipelineBinds++;
        }
        for (int i = 0; i < state.bindings.count(); i++) {
            if (state.bindings.type(i) == CgBindingLayout.Type.SAMPLED_TEXTURE) {
                if (debug && passTarget.attaches(state.bindings.view(i)))
                    throw new IllegalStateException(state.program.label + " samples '"
                            + state.bindings.view(i).texture().label() + "', which the pass renders to");
            } else {
                markUsed(state.bindingAllocation(i));
            }
        }
        pass.pushBindings(state.bindings);
        for (CgPipelineDesc.VertexBuffer vb : state.vertexLayouts) {
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
    public void buildPipeline(CgPipelineDesc.Topology topology) {
        if (state.program == null) throw new IllegalStateException("A pipeline with no program");
        if (target == null) throw new IllegalStateException("A pipeline with no framebuffer bound");
        cachedPipeline(topology, target, state.program.vertex(zeroToOneClip));
    }

    private CgPipeline pipeline(CgPipelineDesc.Topology topology) {
        CgDrawState s = state;
        CgShaderModule vertex = s.program.vertex(zeroToOneClip);
        if (keyPipeline != null && s.program == keyProgram && vertex == keyVertex && s.raster == keyRaster
                && s.depthStencil == keyDepthStencil && s.blend == keyBlend && s.colorMasks == keyMasks
                && s.vertexLayouts == keyLayouts && topology == keyTopology && passTarget == keyTarget) {
            return keyPipeline;
        }
        CgPipeline p = cachedPipeline(topology, passTarget, vertex);
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

    private CgPipeline cachedPipeline(CgPipelineDesc.Topology topology, CgTarget on, CgShaderModule vertex) {
        CgDrawState s = state;
        List<CgPipelineDesc.ColorTarget> targets = new ArrayList<>(on.colors().size());
        for (int i = 0; i < on.colors().size(); i++) {
            CgFormat f = on.colors().get(i).texture().desc().format();
            CgPipelineDesc.Blend blend = f.numeric() == CgFormat.Numeric.INT ? null : s.blend;
            targets.add(new CgPipelineDesc.ColorTarget(f, blend, (s.colorMasks >>> (4 * i)) & 0xF));
        }
        CgFormat depthFormat = on.depth() == null ? null : on.depth().texture().desc().format();
        CgPipelineDesc.DepthStencil ds = s.depthStencil;
        if (depthFormat == null) {
            ds = CgPipelineDesc.DepthStencil.OFF;
        } else if (!depthFormat.hasStencil() && ds.stencilTest() || !depthFormat.hasDepth() && ds.depthTest()) {
            ds = new CgPipelineDesc.DepthStencil(ds.depthTest() && depthFormat.hasDepth(), ds.depthWrite(),
                    ds.depthCompare(), ds.stencilTest() && depthFormat.hasStencil(), ds.front(), ds.back(),
                    ds.readMask(), ds.writeMask());
        }
        CgTextureView any = on.colors().isEmpty() ? on.depth() : on.colors().get(0);
        CgPipelineDesc desc = new CgPipelineDesc(s.program.label, s.program.layout, vertex, s.program.fragment,
                s.vertexLayouts, topology, s.raster, ds, targets, depthFormat, any.texture().desc().samples());
        CgPipeline p = pipelines.get(desc);
        if (p == null) {
            p = device.createPipeline(desc);
            pipelines.put(desc, p);
            stats.pipelineMisses++;
        }
        return p;
    }

    /** Drops every pipeline built from {@code program}: it was relinked or deleted. */
    public void forgetPipelines(CgTrackedProgram program) {
        pipelines.entrySet().removeIf(e -> {
            boolean mine = e.getKey().layout() == program.layout;
            if (mine) device.release(e.getValue());
            return mine;
        });
        if (keyProgram == program) keyPipeline = null;
        passPipeline = null;
    }

    // ── compute ────────────────────────────────────────────────────────────────

    /** {@code glDispatchCompute}, reading the bindings in {@link #state}. */
    public void dispatch(CgComputePipeline pipeline, int groupsX, int groupsY, int groupsZ) {
        prepareDispatch(pipeline).dispatch(groupsX, groupsY, groupsZ);
        stats.dispatches++;
    }

    /** {@code glDispatchComputeIndirect}: three group counts at {@code offset} in {@code args}. */
    public void dispatchIndirect(CgComputePipeline pipeline, CgAllocation args, long offset) {
        CgComputePass c = prepareDispatch(pipeline);
        markUsed(args);
        c.dispatchIndirect(args.buffer, args.offset + offset);
        stats.dispatches++;
    }

    private CgComputePass prepareDispatch(CgComputePipeline pipeline) {
        outsideRenderPass();
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
                markUsed(state.bindingAllocation(i));
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
    }

    public void bufferBarrier(CgAllocation a, int from, int to) {
        outsideRenderPass();
        markUsed(a);
        device.encoder().bufferBarrier(a.buffer, from, to);
    }

    public void imageBarrier(CgGpuTexture texture, int from, int to) {
        outsideRenderPass();
        device.encoder().imageBarrier(texture, from, to);
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

    // ── passes ─────────────────────────────────────────────────────────────────

    /**
     * Ends the open pass, writing out any clear still pending, and returns the encoder for a copy, an upload or
     * a readback. The next draw begins a new pass that loads what this one stored.
     */
    public CgCommandEncoder transfer() {
        outsideRenderPass();
        endCompute();
        return device.encoder();
    }

    /**
     * The host takes its frame back: a clear still pending is written out and our open pass ends, so the host
     * never records into a pass of ours.
     */
    public void toHost() {
        flushPendingClears();
        if (pass != null) endPass();
        endCompute();
        device.toHost();
    }

    /** The host hands us its frame: the next draw begins a pass that loads what is there. */
    public void fromHost() {
        device.fromHost();
    }

    public void endFrame() {
        flushPendingClears();
        if (pass != null) endPass();
        endCompute();
        device.endFrame();
        keyPipeline = null;
    }

    /** Destroys a device object once every frame that may use it has retired. */
    public void release(CgDeviceObject object) {
        device.release(object);
    }

    private void ensurePass() {
        if (pass != null && passTarget.equals(target)) return;
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

    private void beginPass() {
        endCompute();
        drawnSinceCompute = true;
        List<CgPassDesc.Color> colors = new ArrayList<>(target.colors().size());
        for (int i = 0; i < target.colors().size(); i++) {
            boolean clear = (pendingColors & (1 << i)) != 0;
            colors.add(new CgPassDesc.Color(target.colors().get(i), clear ? CgPassDesc.LoadOp.CLEAR : CgPassDesc.LoadOp.LOAD,
                    clearR, clearG, clearB, clearA, CgPassDesc.StoreOp.STORE));
        }
        CgPassDesc.Depth depth = target.depth() == null ? null : new CgPassDesc.Depth(target.depth(),
                pendingDepth ? CgPassDesc.LoadOp.CLEAR : CgPassDesc.LoadOp.LOAD, clearDepthValue,
                pendingStencil ? CgPassDesc.LoadOp.CLEAR : CgPassDesc.LoadOp.LOAD, clearStencilValue,
                CgPassDesc.StoreOp.STORE);
        pendingColors = 0;
        pendingDepth = pendingStencil = false;
        CgTextureView first = target.colors().isEmpty() ? target.depth() : target.colors().get(0);
        pass = device.encoder().beginPass(new CgPassDesc(first.texture().label(), colors, depth,
                target.width(), target.height()));
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

    public CgAllocation allocate(long size, boolean hostVisible, String label) {
        return (hostVisible ? host : local).allocate(size, label);
    }

    /** Host-visible memory for this frame only, freed when it retires: a per-draw upload. */
    public CgAllocation frameAllocate(long size) {
        CgAllocation a = host.allocate(size, "frame");
        a.lastUse = device.frameIndex();
        device.whenRetired(a.lastUse, () -> host.free(a));
        return a;
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
}
