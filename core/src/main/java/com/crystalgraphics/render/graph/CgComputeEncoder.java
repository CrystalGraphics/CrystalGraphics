package com.crystalgraphics.render.graph;

import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.compute.emit.CgKernelTarget;
import com.crystalgraphics.compute.program.CgComputeCheck;
import com.crystalgraphics.compute.program.CgKernelProgram;
import com.crystalgraphics.compute.source.CgImageDecl;
import com.crystalgraphics.compute.source.CgImageDimension;
import com.crystalgraphics.gpu.resource.CgEngineBufferRegistry;
import com.crystalgraphics.gpu.resource.CgReadback;
import com.crystalgraphics.platform.rhi.CgDeviceNames;
import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.platform.rhi.binding.CgBindingLayout;
import com.crystalgraphics.platform.rhi.binding.CgBindingSet;
import com.crystalgraphics.platform.rhi.binding.CgBindings;
import com.crystalgraphics.platform.rhi.command.CgAccess;
import com.crystalgraphics.platform.rhi.pipeline.CgPipelineLayout;
import com.crystalgraphics.platform.rhi.resource.CgGpuTexture;
import com.crystalgraphics.platform.rhi.resource.CgTextureView;
import com.crystalgraphics.render.draw.CgDeviceSets;
import com.crystalgraphics.render.shader.CgBindingModel;
import com.crystalgraphics.trace.CgGpuTrace;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * One execution's compute passes through the device's compute pass: each pass's prologue barriers batched before its
 * first dispatch, then each dispatch's pipeline, sets and dispatch; an async pass on the compute queue, and the frame's
 * waits for it. Unreal's compute passes, encoded on {@code FRHIComputeCommandList}.
 */
final class CgComputeEncoder {

    private static final Logger LOGGER = LogManager.getLogger("CgExecutor");
    private static final int DISPATCH_COUNT = CgTrace.name("graph.dispatches");
    private static final int DEVICE_COMPUTES = CgTrace.name("graph.device-computes");
    private static final int ASYNC_PASSES = CgTrace.name("graph.async-passes");

    private final CgExecutor execution;
    /** The pass's view textures stated as it executes: the depth it reads beside its own. */
    private final CgViewTextures view = new CgViewTextures();
    private final CgGraphDevice device;
    /** A dispatch's kernel slots its snapshot does not hold: buffers, counts, images, checked mode's report. */
    private final CgBindings kernelBindings = new CgBindings();
    private final Set<String> setsLogged = new HashSet<>();
    private final long[] keyScratch = new long[2];
    /** This execution's: the newest async point covering storage outside the graph, waited for at its end. */
    long asyncEnd;

    CgComputeEncoder(CgExecutor execution, CgGraphDevice device) {
        this.execution = execution;
        this.device = device;
    }

    /** Step {@code s}, on the frame's queue: timed on the detail channel, where an enclosing zone is not timed through it. */
    void compute(CgFrame frame, int s, CgComputePass pass) {
        boolean gpu = CgTrace.isEnabled(CgChannels.GL_DETAIL) && CgGpuTrace.isMeasuring();
        if (gpu) CgGpuTrace.begin(device.gpuZone(pass.name()));
        try {
            encode(frame, s, pass);
        } finally {
            if (gpu) CgGpuTrace.end();
        }
    }

    private void encode(CgFrame frame, int s, CgComputePass pass) {
        if (++device.passSerial == 1 << 28) device.passSerial = 1;   // a set keeps three bits for its group
        List<CgDispatch> dispatches = pass.dispatches();
        CgFrame.Compute packed = frame.computes[s];
        CgRHI rhi = CgRHI.get();
        CgTrace.add(CgChannels.GL, DEVICE_COMPUTES, 1);
        // Before the compute pass, as a raster pass's copy: what the view set reads
        view.clear();
        if (pass.depthFrom() != null) {
            view.put(CgBindingModel.SCENE_DEPTH, execution.scene.depthFrom(frame.depthFromOp[s],
                    device.storage(pass.depthFrom()), pass));
        }
        execution.deviceSets.prepare(frame.bindings);
        if (CgKernelTarget.CHECKED) CgComputeCheck.prepare();
        for (int i = 0; i < dispatches.size(); i++) {
            CgDispatch d = dispatches.get(i);
            List<String> tokens = d.source.engineBuffers();
            for (int t = 0; t < tokens.size(); t++) CgEngineBufferRegistry.get(tokens.get(t)).buffer().get().flush();
            d.kernel.program().devicePipeline(rhi);
            for (int b = 0; b < d.buffers.length; b++) {
                if (d.buffers[b] != null) device.bufferStorage(d.buffers[b], false);
                if (d.counters[b] != null) device.bufferStorage(d.counters[b], false);
            }
            if (d.args != null) device.bufferStorage(d.args, false);
        }
        device.compute.begin(pass.name());
        device.computing = true;
        try {
            prologue(dispatches);
            for (int i = 0; i < dispatches.size(); i++) dispatch(frame, rhi, dispatches.get(i), packed.bindings[i], packed.constants);
            CgTrace.add(CgChannels.GL, DISPATCH_COUNT, dispatches.size());
        } finally {
            device.compute.close();
            execution.transitions.endDeviceBarriers();
            device.computing = false;
        }
    }

    /**
     * Barriers every storage's first access in the pass before its first dispatch, as Unreal RDG batches a pass's
     * prologue barriers: dispatches reading what earlier passes wrote then wait once together, not each in turn. A
     * history is left to its binding, since its version moves within the pass.
     */
    private void prologue(List<CgDispatch> dispatches) {
        for (int d = 0; d < dispatches.size(); d++) {
            CgDispatch dispatch = dispatches.get(d);
            // Claimed, never hoisted: a write later in the pass must not move ahead of this read.
            if (dispatch.args != null) {
                device.states.claim(CgResourceStates.buffer(device.bufferStorage(dispatch.args, false)), device.passSerial, d);
            }
            for (int b = 0; b < dispatch.buffers.length; b++) {
                hoist(d, dispatch.buffers[b], dispatch.bufferAccess[b], set(dispatch.skipGroup[b]), dispatch.hoisted, b);
                hoist(d, dispatch.counters[b], dispatch.counterAccess[b], set(dispatch.countSkipGroup[b]),
                        dispatch.counterHoisted, b);
            }
        }
    }

    private void hoist(int dispatch, @Nullable CgGraphBuffer buffer, int access, int set, int[] hoisted, int b) {
        if (buffer == null || access == 0 || buffer.resource().kind() == CgGraphBuffer.Kind.HISTORY) return;
        int id = device.bufferStorage(buffer, (access & CgAccess.COMPUTE_WRITE) != 0);
        if (!device.states.claim(CgResourceStates.buffer(id), device.passSerial, dispatch)) return;
        execution.transitions.buffer(id, access, set);
        hoisted[b] = device.passSerial;
    }

    private void dispatch(CgFrame frame, CgRHI rhi, CgDispatch d, int bindings, int constants) {
        CgKernelProgram program = d.kernel.program();
        CgBindings k = kernelBindings.clear();
        CgTransitions transitions = execution.transitions;
        int serial = device.passSerial;
        for (int b = 0; b < d.buffers.length; b++) {
            if (d.buffers[b] != null) {
                int access = d.hoisted[b] == serial ? 0 : d.bufferAccess[b];
                int id = device.bufferStorage(d.buffers[b], (access & CgAccess.COMPUTE_WRITE) != 0);
                if (access != 0) transitions.buffer(id, access, set(d.skipGroup[b]));
                storageSlot(k, CgBindingModel.FIRST_KERNEL_BUFFER + b, id, d.offsets[b], d.sizes[b]);
            }
            if (d.counters[b] != null) {
                long base = CgKernelProgram.counterBase(d.counterOffsets[b]);
                int access = d.counterHoisted[b] == serial ? 0 : d.counterAccess[b];
                int id = device.bufferStorage(d.counters[b], (access & CgAccess.COMPUTE_WRITE) != 0);
                if (access != 0) transitions.buffer(id, access, set(d.countSkipGroup[b]));
                storageSlot(k, CgBindingModel.FIRST_KERNEL_BUFFER + program.counterPoint(b), id, base,
                        d.counterOffsets[b] - base + 4);
                program.counterWordOn(b, d.counterOffsets[b]);
            }
        }
        for (CgTexture sampler : d.samplers) {
            CgGraphTexture graph = sampler == null ? null : CgGraphTexture.sampled(sampler);
            if (graph != null) transitions.move(graph, CgAccess.SAMPLED_READ, -1, -1);   // after a kernel wrote it as an image
        }
        for (int i = 0; i < d.images.length; i++) {
            CgGraphTexture texture = d.images[i];
            if (texture == null) continue;
            CgGpuTexture color = device.storage(texture).color(0);
            int access = imageAccess(d, i, color);
            if (access != 0) transitions.move(texture, access, -1, -1);
            k.image(CgBindingModel.FIRST_KERNEL_IMAGE + i, imageView(d, i, color));
        }
        if (program.checkPoint() >= 0) {
            k.buffer(CgBindingModel.FIRST_KERNEL_BUFFER + program.checkPoint(), CgBindingLayout.Type.STORAGE_BUFFER,
                    CgComputeCheck.buffer(), CgComputeCheck.claim(program), CgComputeCheck.SLOT_BYTES);
        }
        int args = d.form == CgDispatch.Form.INDIRECT ? device.bufferStorage(d.args, false) : 0;
        if (args != 0) transitions.buffer(args, CgAccess.INDIRECT);
        transitions.endDeviceBarriers();
        CgDeviceCompute compute = device.compute;
        compute.pass(rhi);
        CgPipelineLayout layout = program.deviceLayout();
        compute.pipeline(program.devicePipeline(rhi), layout);
        CgDeviceSets sets = execution.deviceSets;
        for (int set = 0; set < layout.sets().size(); set++) {
            if (set == CgRHI.BINDLESS_SET) {
                compute.set(set, rhi.bindlessSet());
                continue;
            }
            if (layout.sets().get(set).slots().isEmpty()) continue;
            long before = sets.defaulted();
            CgBindingSet s = sets.set(rhi, frame.bindings, set == CgBindingModel.VIEW ? constants : bindings, layout,
                    set, set == CgBindingModel.MATERIAL ? k : set == CgBindingModel.VIEW ? view.bindings(rhi) : null);
            if (sets.defaulted() != before) {
                String what = d.source.path() + " " + d.decl.name() + " " + sets.lastDefaulted();
                if (setsLogged.add(what)) LOGGER.warn("device compute: {} defaulted", what);
            }
            compute.set(set, s, sets.offsets(), sets.offsetCount());
        }
        switch (d.form) {
            case ELEMENTS -> program.dispatchOn(compute.pass(rhi), d.x, d.y, d.z);
            case GROUPS -> program.dispatchOn(compute.pass(rhi), d.x * program.kernel().sizeX(),
                    d.y * program.kernel().sizeY(), d.z * program.kernel().sizeZ());
            case INDIRECT -> program.dispatchIndirectOn(compute.pass(rhi), CgDeviceNames.buffer(args), d.argsOffset);
        }
        advanceHistories(d);
    }

    /** {@code id}'s range at {@code binding} of {@code k}: from {@code offset}, {@code size} bytes or the rest for 0. */
    static void storageSlot(CgBindings k, int binding, int id, long offset, long size) {
        long whole = CgDeviceNames.buffer(id).size();
        k.buffer(binding, CgBindingLayout.Type.STORAGE_BUFFER, CgDeviceNames.buffer(id), offset,
                size == 0 ? whole - offset : size);
    }

    /** Image binding {@code i} of {@code d} as {@code glBindImageTexture} takes it: a level, one layer or all of them. */
    private CgTextureView imageView(CgDispatch d, int i, CgGpuTexture texture) {
        CgImageDecl image = d.source.images().get(i);
        boolean all = d.layers[i] < 0 && image.dimension() != CgImageDimension.D2;
        int level = d.levels[i], layer = all ? -1 : Math.max(0, d.layers[i]);
        CgGraphDevice.ImageViews kept = device.imageViews.get(texture);
        if (kept == null) device.imageViews.put(texture, kept = new CgGraphDevice.ImageViews());
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

    /** The overlap set of skip group {@code group1} (1 + the group, 0 for none) in the executing pass. */
    private int set(int group1) {
        return group1 == 0 ? 0 : device.passSerial << 3 | (group1 - 1);
    }

    /** What {@code pass} bound, noted for {@link CgBufferInspector}, and the reads armed for it copied as it left them. */
    void inspect(CgComputePass pass) {
        CgBufferInspector.note(pass);
        for (CgBufferInspector.Request read = CgBufferInspector.due(); read != null; read = CgBufferInspector.due()) {
            if (device.async.unwaited) device.async.await(device.async.latest);
            int id = device.bufferStorage(read.view(), false);
            execution.transitions.buffer(id, CgAccess.COPY_READ);
            CgReadback.buffer(id, read.offset(), read.size(), read);
        }
    }

    /** A history written by {@code d}: its next version is its newest from now on, however many bindings wrote it. */
    private static void advanceHistories(CgDispatch d) {
        for (int i = 0; i < d.buffers.length; i++) {
            if (wroteHistory(d.buffers[i], d.bufferAccess[i]) && firstWriter(d, i)) d.buffers[i].advance();
        }
    }

    /**
     * What dispatch {@code d} does to texture {@code image} through every image binding naming it, at the first such
     * binding; 0 at the rest. One barrier for all of them: a transition made for one binding must be visible to the
     * others, a level read beside a level written.
     */
    private int imageAccess(CgDispatch d, int binding, CgGpuTexture image) {
        int access = 0;
        for (int i = 0; i < d.images.length; i++) {
            if (d.images[i] == null || device.storage(d.images[i]).color(0) != image) continue;
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

    // ── Async compute ────────────────────────────────────────────────────────

    /** Step {@code s} on the compute queue; the storage it touches is pending until the frame's queue waits for it. */
    void computeAsync(CgFrame frame, int s, CgComputePass pass) {
        if (CgKernelTarget.CHECKED) CgComputeCheck.prepare();
        CgRHI.get().encoder().beginAsync();
        long point;
        try {
            encode(frame, s, pass);
        } finally {
            point = CgRHI.get().encoder().endAsync();
        }
        CgTrace.add(CgChannels.GL, ASYNC_PASSES, 1);
        if (point == 0) return;   // the device ran it in order
        for (int i = frame.accessFrom[s]; i < frame.accessFrom[s + 1]; i++) {
            CgGraphResource view = frame.accessView[i];
            int n = keys(view);
            for (int k = 0; k < n; k++) device.async.pend(keyScratch[k], point);
            if (!graphOnly(view)) asyncEnd = point;
        }
        device.async.submitted(point);
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
    void awaitAsync(CgFrame frame, int s) {
        long point = 0;
        if (frame.steps[s] instanceof CgPass.Callback || frame.steps[s] instanceof CgPass.Include) {
            point = device.async.latest;
        } else {
            for (int i = frame.accessFrom[s]; i < frame.accessFrom[s + 1]; i++) {
                int n = keys(frame.accessView[i]);
                for (int k = 0; k < n; k++) point = Math.max(point, device.async.point(keyScratch[k]));
            }
        }
        if (point > 0) device.async.await(point);
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
}
