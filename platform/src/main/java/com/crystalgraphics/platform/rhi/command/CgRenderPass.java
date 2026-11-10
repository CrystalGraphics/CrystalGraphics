package com.crystalgraphics.platform.rhi.command;

import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.platform.rhi.binding.CgBindingLayout;
import com.crystalgraphics.platform.rhi.binding.CgBindingSet;
import com.crystalgraphics.platform.rhi.binding.CgBindings;
import com.crystalgraphics.platform.rhi.pipeline.CgGraphicsPipeline;
import com.crystalgraphics.platform.rhi.pipeline.CgGraphicsPipelineDesc;
import com.crystalgraphics.platform.rhi.resource.CgGpuBuffer;

import java.nio.ByteBuffer;

/**
 * One open render pass. Set a pipeline, its bindings and its buffers, then draw; nothing carries from one
 * pass to the next, so a resumed pass sets everything again.
 *
 * <pre>{@code
 * CgRenderPass pass = device.encoder().beginPass(desc);
 * pass.setPipeline(pipeline);
 * pass.pushBindings(bindings);
 * pass.setVertexBuffer(0, vertices, 0);
 * pass.setViewport(0, 0, w, h, 0, 1);
 * pass.setScissor(0, 0, w, h);
 * pass.draw(6, 1, 0, 0);
 * pass.end();
 * }</pre>
 *
 * <p>Viewport and scissor are in memory rows, as {@link CgCommandList} says: GL's values pass unchanged.
 * A draw needs every set of its pipeline's layout bound, every slot of the pushed {@link CgBindingLayout} pushed,
 * and a buffer at each of its vertex bindings.</p>
 *
 * <pre>{@code
 * pass.setPipeline(scenePipeline);                 // layout: view, pass, material sets; 8 bytes of constants
 * pass.setBindingSet(0, view);
 * pass.setBindingSet(2, stone);
 * pass.pushConstants(0, drawIndex);
 * pass.drawIndexed(count, 1, firstIndex, baseVertex, 0);
 * }</pre>
 */
public interface CgRenderPass {

    void setPipeline(CgGraphicsPipeline pipeline);

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

    void setVertexBuffer(int binding, CgGpuBuffer buffer, long offset);

    /** @param wide 32-bit indices, else 16-bit */
    void setIndexBuffer(CgGpuBuffer buffer, long offset, boolean wide);

    void setViewport(float x, float y, float width, float height, float minDepth, float maxDepth);

    void setScissor(int x, int y, int width, int height);

    /** Applies where the pipeline's {@link CgGraphicsPipelineDesc.Raster#depthBias} is on. */
    void setDepthBias(float constant, float slope);

    void setStencilReference(int reference);

    /** Clears one colour attachment's rectangle inside the pass. */
    void clearColor(int attachment, float r, float g, float b, float a, int x, int y, int width, int height);

    /** Clears the depth attachment's depth, stencil or both inside the pass. */
    void clearDepthStencil(boolean depth, float clearDepth, boolean stencil, int clearStencil,
                           int x, int y, int width, int height);

    void draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance);

    void drawIndexed(int indexCount, int instanceCount, int firstIndex, int baseVertex, int firstInstance);
    /** {@code drawCount} commands {@code stride} bytes apart from {@code offset}: GL's {@code DrawArraysIndirectCommand}. */
    void drawIndirect(CgGpuBuffer buffer, long offset, int drawCount, int stride);
    /** The same with GL's {@code DrawElementsIndirectCommand}, through the bound index buffer. */
    void drawIndexedIndirect(CgGpuBuffer buffer, long offset, int drawCount, int stride);
    /**
     * {@link #drawIndirect} with the count a uint at {@code countOffset}, at most {@code maxDraws}. Where
     * {@link CgRHI.Globals#indirectCount} holds.
     */
    void drawIndirectCount(CgGpuBuffer buffer, long offset, CgGpuBuffer count, long countOffset, int maxDraws, int stride);
    /** {@link #drawIndexedIndirect} with a count from the GPU, as {@link #drawIndirectCount}. */
    void drawIndexedIndirectCount(CgGpuBuffer buffer, long offset, CgGpuBuffer count, long countOffset, int maxDraws,
                                  int stride);

    void end();
}
