package com.crystalgraphics.platform.device.command;

import com.crystalgraphics.platform.device.pipeline.CgBindings;
import com.crystalgraphics.platform.device.pipeline.CgComputePipeline;
import com.crystalgraphics.platform.device.resource.CgGpuBuffer;

/**
 * One open compute pass: set a pipeline and its bindings, then dispatch. As in a render pass, nothing carries from one
 * pass to the next.
 *
 * <pre>{@code
 * CgComputePass pass = device.encoder().beginCompute("particles.simulate");
 * pass.setPipeline(simulate);
 * pass.pushBindings(bindings);
 * pass.dispatch(groups, 1, 1);
 * pass.end();
 * device.encoder().bufferBarrier(state, CgAccess.COMPUTE_WRITE, CgAccess.VERTEX_READ);
 * }</pre>
 *
 * <p>The encoder's barriers and transfers may sit between a pass's dispatches: a dispatch that reads what the one
 * before it wrote needs {@link CgCommandEncoder#bufferBarrier} between them.</p>
 */
public interface CgComputePass {

    void setPipeline(CgComputePipeline pipeline);

    /** Replaces every binding. */
    void pushBindings(CgBindings bindings);

    void dispatch(int groupsX, int groupsY, int groupsZ);

    /** Group counts from three uints at {@code offset}, which an earlier pass may have written. */
    void dispatchIndirect(CgGpuBuffer buffer, long offset);

    void end();
}
