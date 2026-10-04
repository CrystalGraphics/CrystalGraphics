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
    final float[] constants;
    private final List<CgDispatch> dispatches = new ArrayList<>();
    private boolean again;
    private boolean async;
    private boolean ended;

    CgComputePass(CgRecording recording, String name, float[] constants) {
        super(name, null, null);
        this.recording = recording;
        this.constants = constants;
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
        return add(new CgDispatch(this, kernel, CgDispatch.Form.ELEMENTS, x, y, z, null, 0));
    }

    /** Whole work groups: every invocation they hold is an element. */
    public CgDispatch dispatchGroups(CgKernel kernel, int x, int y, int z) {
        requireOpen();
        return add(new CgDispatch(this, kernel, CgDispatch.Form.GROUPS, x, y, z, null, 0));
    }

    /** Group counts from three {@code uint}s at {@code offset} in {@code args}, which a pass before it wrote. */
    public CgDispatch dispatchIndirect(CgKernel kernel, CgGraphBuffer args, long offset) {
        requireOpen();
        return add(new CgDispatch(this, kernel, CgDispatch.Form.INDIRECT, 0, 0, 0, args, offset));
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
     * steps after it that touch nothing it reads or writes overlap it, and the first that does waits for it. The builder
     * places it as early, and what reads its results as late, as the graph allows. Elsewhere, and for a pass with a
     * dispatch below compute, it runs in order with the same result.
     *
     * <pre>{@code
     * CgComputePass cull = recording.compute("instances.cull", constants).async();
     * cull.dispatch(cullKernel, count).bind("INSTANCES", instances).counter("VISIBLE", visible, 0);
     * cull.end();
     * recording.raster(shadowMap, ...);   // touches neither buffer: drawn while the cull runs, wherever recorded
     * }</pre>
     */
    public CgComputePass async() {
        requireOpen();
        async = true;
        return this;
    }

    /** Ends the pass: every dispatch's bindings complete, its values and samplers captured. */
    public void end() {
        requireOpen();
        for (CgDispatch dispatch : dispatches) {
            dispatch.requireBound();
            CgBindingTable table = recording.bindings().begin();
            table.block(CgBindingPoints.FRAME_DATA_UBO, constants, 0, CgPassConstants.FLOATS);
            if (dispatch.values != null) {
                table.block(CgBindingPoints.MATERIAL_PROPERTIES_UBO, dispatch.values, 0, dispatch.values.length);
            }
            for (int unit = 0; unit < dispatch.samplers.length; unit++) {
                if (dispatch.samplers[unit] != null) table.texture(unit, dispatch.samplers[unit]);
            }
            dispatch.bindings = table.end();
        }
        ended = true;
    }

    public List<CgDispatch> dispatches() {
        return Collections.unmodifiableList(dispatches);
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

    private CgDispatch add(CgDispatch dispatch) {
        dispatches.add(dispatch);
        return dispatch;
    }
}
