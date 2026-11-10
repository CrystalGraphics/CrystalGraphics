package com.crystalgraphics.platform.rhi.command;

import com.crystalgraphics.platform.rhi.binding.CgBindingSet;
import com.crystalgraphics.platform.rhi.binding.CgBindings;
import com.crystalgraphics.platform.rhi.pipeline.CgComputePipeline;
import com.crystalgraphics.platform.rhi.resource.CgGpuBuffer;

import java.nio.ByteBuffer;

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
 * before it wrote needs {@link CgCommandList#bufferBarrier} between them.</p>
 */
public interface CgComputePass {

    void setPipeline(CgComputePipeline pipeline);

    /** Fills the pipeline's pushed set, replacing what was pushed. */
    void pushBindings(CgBindings bindings);

    /** Binds {@code bindings} at {@code set}, a set with no dynamic slot; it stays bound across pipelines until replaced. */
    default void setBindingSet(int set, CgBindingSet bindings) {
        setBindingSet(set, bindings, CgBindingSet.NO_OFFSETS, 0);
    }

    /**
     * Binds {@code bindings} at {@code set} with its layout's dynamic offsets: the first {@code count} of
     * {@code dynamicOffsets}, in binding order, copied. A set bound again at other offsets reads its blocks there.
     *
     * <pre>{@code
     * offsets[0] = (int) viewBlockOffset;              // this frame's place in the ring
     * pass.setBindingSet(0, view, offsets, 1);
     * }</pre>
     *
     * @throws IllegalArgumentException where {@code count} is not the layout's {@code dynamicCount()}
     */
    void setBindingSet(int set, CgBindingSet bindings, int[] dynamicOffsets, int count);

    /** {@code value} at byte {@code offset} of the pipeline's push constants. */
    void pushConstants(int offset, int value);

    /** {@code data}'s remaining bytes at byte {@code offset} of the pipeline's push constants. */
    void pushConstants(int offset, ByteBuffer data);

    void dispatch(int groupsX, int groupsY, int groupsZ);

    /** Group counts from three uints at {@code offset}, which an earlier pass may have written. */
    void dispatchIndirect(CgGpuBuffer buffer, long offset);

    void end();
}
