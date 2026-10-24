package com.crystalgraphics.render.graph;

import com.crystalgraphics.api.CgBindingPoints;
import com.crystalgraphics.compute.CgKernel;
import com.crystalgraphics.render.CgGpuBudget;
import com.crystalgraphics.render.draw.CgBindingTable;
import com.crystalgraphics.render.draw.CgPassConstants;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Kernels run in order, made with {@link CgRecording#compute}: each dispatch's bindings decide what the pass reads and
 * writes, so the graph runs it after what wrote its inputs and before what reads its outputs, and puts a barrier
 * wherever a kernel's access meets another's — between two of its own dispatches too.
 *
 * <pre>{@code
 * CgComputePass sim = recording.compute("particles.simulate", constants);
 * sim.dispatch(simulate, capacity).bind("STATE_IN", state).bind("STATE_OUT", state).set("_Drag", 0.1f);
 * sim.dispatchIndirect(spawn, spawnArgs, 0).bind("SPAWNS", spawns).counter("SPAWNS", spawnCount, 0);
 * sim.end();
 * }</pre>
 *
 * <ul>
 *   <li>Culled when nothing reads what it writes, unless it writes a persistent, history or imported buffer, or a
 *       texture that outlives the frame.</li>
 *   <li>A frame executed again runs it again only if everything it writes is transient, or it is marked
 *       {@link #again()}: a frame shown again must not step a simulation. A pass skipped that way must not write a
 *       transient a pass run again reads — the execution throws, naming both — so a step and what only feeds this
 *       frame's drawing are two passes.</li>
 *   <li>A count past the device's group limit runs as several dispatches, each from its own base.</li>
 * </ul>
 */
public final class CgComputePass extends CgPass {

    final CgRecording recording;
    final float[] constants = new float[CgPassConstants.FLOATS];
    /** The recording's snapshot of its constants, made when it ends: bound once for every dispatch, as a raster pass's. */
    int constantsBinding = -1;
    private final List<CgDispatch> dispatches = new ArrayList<>();
    private final List<CgDispatch> dispatchView = Collections.unmodifiableList(dispatches);
    /** Dispatches made so far, refilled after a reset when they run the same kernel; the first {@link #shellsUsed} are this use's. */
    private final List<CgDispatch> shells = new ArrayList<>();
    private int shellsUsed;
    private boolean again;
    private boolean async;
    private boolean ended;

    CgComputePass(CgRecording recording, String name, CgPassConstants constants) {
        super(name, null, null);
        this.recording = recording;
        reuse(name, constants);
    }

    /** Makes this pass a new one: its recording's, after a reset, so a steady frame allocates none. */
    void reuse(String name, CgPassConstants constants) {
        this.name = name;
        constants.write(this.constants, 0);
        gpuZone = -1;
        budget = null;
        dispatches.clear();
        shellsUsed = 0;
        again = false;
        async = false;
        ended = false;
        constantsBinding = -1;
    }

    @Override
    void clearReferences() {
        dispatches.clear();
        // Only [0, shellsUsed) is cleared before it resets: a shell past it was cleared when it was last used.
        for (int i = 0; i < shellsUsed; i++) shells.get(i).clearReferences();
        shellsUsed = 0;
        budget = null;
    }

    int shellCount() {
        return shells.size();
    }

    CgDispatch shell(int i) {
        return shells.get(i);
    }

    /** The recording it belongs to: where an op takes its {@linkplain CgRecording#scratch scratch}. */
    public CgRecording recording() {
        return recording;
    }

    /** {@code count} elements in one dimension. */
    public CgDispatch dispatch(CgKernel kernel, int count) {
        return dispatch(kernel, count, 1, 1);
    }

    /** {@code x * y * z} elements: {@code CG_DISPATCH_COUNT}, with groups rounded up. */
    public CgDispatch dispatch(CgKernel kernel, int x, int y, int z) {
        requireOpen();
        return add(kernel, CgDispatch.Form.ELEMENTS, x, y, z, null, 0);
    }

    /** Whole work groups: every invocation they hold is an element. */
    public CgDispatch dispatchGroups(CgKernel kernel, int x, int y, int z) {
        requireOpen();
        return add(kernel, CgDispatch.Form.GROUPS, x, y, z, null, 0);
    }

    /** Group counts from three {@code uint}s at {@code offset} in {@code args}, which a pass before it wrote. */
    public CgDispatch dispatchIndirect(CgKernel kernel, CgGraphBuffer args, long offset) {
        requireOpen();
        return add(kernel, CgDispatch.Form.INDIRECT, 0, 0, 0, args, offset);
    }

    /** Runs when the frame executes again, even writing what outlives the frame: a cull, a derivation. */
    public CgComputePass again() {
        requireOpen();
        again = true;
        return this;
    }

    /**
     * Times it on the GPU on its own, under {@code zone}, a name made once with {@code CgGpuTrace.name}. Inside a stage
     * it splits the stage's own zone, which GPU zones' not nesting allows.
     */
    public CgComputePass timed(int zone) {
        requireOpen();
        gpuZone = zone;
        return this;
    }

    /**
     * Charges its GPU time to {@code budget}, which scales its consumer's work to fit. Timed whatever the trace, under
     * the budget's zone unless {@link #timed(int)} names one.
     */
    public CgComputePass timed(CgGpuBudget budget) {
        requireOpen();
        this.budget = Objects.requireNonNull(budget, "budget");
        return this;
    }

    /**
     * Runs beside the frame's queue where the device has a compute queue ({@code CgCapabilities.asyncCompute()}): the
     * steps after it that touch nothing it reads or writes overlap it, and the first that does waits for it, in this
     * execution or a later one of the frame. Elsewhere, and for a pass with a dispatch below compute, it runs in order
     * with the same result.
     *
     * <p><b>Mark every pass that fits</b>: all compute, big enough to hide (thousands of elements, or dispatches with
     * barriers between them), with drawing between it and its first reader. Left in order, such a pass costs the frame
     * its whole time. Leave it off a small pass and one whose reader comes straight after.
     *
     * <pre>{@code
     * // A simulation stepped at the opaque stage, drawn at the transparent one: the world draws beside it.
     * CgRenderStage.WORLD_OPAQUE.registerOncePerFrame(order, frame -> {
     *     CgComputePass step = frame.recording().compute("sparks.step").async();
     *     step.dispatch(simulate, capacity).bind("IN", sparks).bind("OUT", sparks);
     *     step.end();
     * });
     *
     * // A cull ahead of a shadow map that touches neither buffer: drawn while the cull runs, wherever recorded.
     * CgComputePass cull = recording.compute("instances.cull", constants).async();
     * cull.dispatch(cullKernel, count).bind("INSTANCES", instances).counter("VISIBLE", visible, 0);
     * cull.end();
     * recording.raster(shadowMap, ...);
     * }</pre>
     *
     * <ul>
     *   <li>The builder places it as early, and what reads its results as late, as the graph allows: recording order
     *       does not decide the overlap.</li>
     *   <li>Waits cross executions for graph buffers (not imported ones) and transient textures; work on anything else
     *       is waited for at the end of its execution, since the host may touch it.</li>
     *   <li>On Minecraft's device it may not touch Minecraft's own textures, and throws naming one.</li>
     * </ul>
     */
    public CgComputePass async() {
        requireOpen();
        async = true;
        return this;
    }

    /** Ends the pass: every dispatch's bindings complete, its values and samplers captured. */
    public void end() {
        requireOpen();
        constantsBinding = recording.bindings().begin()
                .block(CgBindingPoints.FRAME_DATA_UBO, constants, 0, CgPassConstants.FLOATS).end();
        for (int d = 0; d < dispatches.size(); d++) {
            CgDispatch dispatch = dispatches.get(d);
            dispatch.requireBound();
            CgBindingTable table = recording.bindings().begin();
            if (dispatch.values != null) {
                table.block(CgBindingPoints.MATERIAL_PROPERTIES_UBO, dispatch.values, 0, dispatch.values.length);
            }
            for (int unit = 0; unit < dispatch.samplers.length; unit++) {
                if (dispatch.samplers[unit] != null) table.texture(unit, dispatch.samplers[unit]);
            }
            // Each dispatch's values are its own: interning would hash every float to find no equal.
            dispatch.bindings = table.endUnique();
        }
        ended = true;
    }

    public List<CgDispatch> dispatches() {
        return dispatchView;
    }

    boolean ended() {
        return ended;
    }

    boolean runsAgain() {
        return again;
    }

    boolean isAsync() {
        return async;
    }

    void requireOpen() {
        if (ended) throw new IllegalStateException(this + " has ended");
        recording.requireOpen();
    }

    /** A dispatch of {@code kernel}: the shell made for it last use when the kernel and its file are the same. */
    private CgDispatch add(CgKernel kernel, CgDispatch.Form form, int x, int y, int z, CgGraphBuffer args, long offset) {
        CgDispatch dispatch;
        if (shellsUsed < shells.size() && shells.get(shellsUsed).reusableFor(kernel)) {
            dispatch = shells.get(shellsUsed);
            dispatch.reuse(form, x, y, z, args, offset);
        } else {
            dispatch = new CgDispatch(this, kernel, form, x, y, z, args, offset);
            if (shellsUsed < shells.size()) shells.set(shellsUsed, dispatch);
            else shells.add(dispatch);
        }
        shellsUsed++;
        dispatches.add(dispatch);
        return dispatch;
    }
}
