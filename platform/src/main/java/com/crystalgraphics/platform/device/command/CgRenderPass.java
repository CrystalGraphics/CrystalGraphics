package com.crystalgraphics.platform.device.command;

import com.crystalgraphics.platform.device.pipeline.CgBindingLayout;
import com.crystalgraphics.platform.device.pipeline.CgBindings;
import com.crystalgraphics.platform.device.pipeline.CgPipeline;
import com.crystalgraphics.platform.device.pipeline.CgPipelineDesc;
import com.crystalgraphics.platform.device.resource.CgGpuBuffer;

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
 * <p>Viewport and scissor are in memory rows, as {@link CgCommandEncoder} says: GL's values pass unchanged.
 * A draw needs every slot of its pipeline's {@link CgBindingLayout} pushed, and a buffer at each of its
 * vertex bindings.</p>
 */
public interface CgRenderPass {

    void setPipeline(CgPipeline pipeline);

    /** Replaces every binding. */
    void pushBindings(CgBindings bindings);

    void setVertexBuffer(int binding, CgGpuBuffer buffer, long offset);

    /** @param wide 32-bit indices, else 16-bit */
    void setIndexBuffer(CgGpuBuffer buffer, long offset, boolean wide);

    void setViewport(float x, float y, float width, float height, float minDepth, float maxDepth);

    void setScissor(int x, int y, int width, int height);

    /** Applies where the pipeline's {@link CgPipelineDesc.Raster#depthBias} is on. */
    void setDepthBias(float constant, float slope);

    void setStencilReference(int reference);

    /** Clears one colour attachment's rectangle inside the pass. */
    void clearColor(int attachment, float r, float g, float b, float a, int x, int y, int width, int height);

    /** Clears the depth attachment's depth, stencil or both inside the pass. */
    void clearDepthStencil(boolean depth, float clearDepth, boolean stencil, int clearStencil,
                           int x, int y, int width, int height);

    void draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance);

    void drawIndexed(int indexCount, int instanceCount, int firstIndex, int baseVertex, int firstInstance);

    void end();
}
