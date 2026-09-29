package com.crystalgraphics.platform.gl.tracked;

import com.crystalgraphics.platform.device.CgBindingLayout;
import com.crystalgraphics.platform.device.CgBindings;
import com.crystalgraphics.platform.device.CgFormat;
import com.crystalgraphics.platform.device.CgGpuSampler;
import com.crystalgraphics.platform.device.CgPipelineDesc;
import com.crystalgraphics.platform.device.CgTextureView;

import java.util.Arrays;
import java.util.List;

/**
 * What the next draw uses, mirrored from GL by the tracked backend. The pipeline inputs are compared by
 * identity: replace a record when its GL state changes, never mutate one, and an unchanged draw looks up no
 * pipeline.
 *
 * <pre>{@code
 * CgDrawState s = tracker.state;
 * s.program = program;
 * s.blend = CgPipelineDesc.Blend.ALPHA;
 * s.vertexLayouts = vao.layouts();
 * s.vertexBuffer(0, vbo.allocation(), 0);
 * s.clearBindings().uniform(0, frameBlock.allocation(), 0, 256);
 * tracker.draw(CgPipelineDesc.Topology.TRIANGLES, 6, 1, 0, 0);
 * }</pre>
 */
public final class CgDrawState {

    public CgTrackedProgram program;
    public CgPipelineDesc.Raster raster = CgPipelineDesc.Raster.DEFAULT;
    public CgPipelineDesc.DepthStencil depthStencil = CgPipelineDesc.DepthStencil.OFF;
    /** {@code null} with blending off. */
    public CgPipelineDesc.Blend blend;
    /** Four bits per colour attachment, attachment {@code i} at bits {@code 4i..4i+3}: red, green, blue, alpha. */
    public int colorMasks = 0xFFFFFFFF;
    public List<CgPipelineDesc.VertexBuffer> vertexLayouts = List.of();

    public int viewportX, viewportY, viewportWidth, viewportHeight;
    public boolean scissorTest;
    public int scissorX, scissorY, scissorWidth, scissorHeight;
    public float depthBiasConstant, depthBiasSlope;
    public int stencilReference;

    final CgAllocation[] vertexAllocations = new CgAllocation[16];
    final long[] vertexOffsets = new long[16];
    CgAllocation index;
    long indexOffset;
    boolean wideIndex;

    final CgBindings bindings = new CgBindings();
    private CgAllocation[] bindingAllocations = new CgAllocation[8];

    public void vertexBuffer(int binding, CgAllocation allocation, long offset) {
        vertexAllocations[binding] = allocation;
        vertexOffsets[binding] = offset;
    }

    /** @param wide 32-bit indices */
    public void indexBuffer(CgAllocation allocation, long offset, boolean wide) {
        index = allocation;
        indexOffset = offset;
        wideIndex = wide;
    }

    public CgDrawState clearBindings() {
        Arrays.fill(bindingAllocations, 0, bindings.count(), null);
        bindings.clear();
        return this;
    }

    public CgDrawState uniform(int slot, CgAllocation a, long offset, long size) {
        return buffer(slot, CgBindingLayout.Type.UNIFORM_BUFFER, a, offset, size);
    }

    public CgDrawState storage(int slot, CgAllocation a, long offset, long size) {
        return buffer(slot, CgBindingLayout.Type.STORAGE_BUFFER, a, offset, size);
    }

    public CgDrawState texel(int slot, CgAllocation a, long offset, long size, CgFormat format) {
        keep(a);
        bindings.texel(slot, a.buffer, a.offset + offset, size, format);
        return this;
    }

    public CgDrawState texture(int slot, CgTextureView view, CgGpuSampler sampler) {
        keep(null);
        bindings.texture(slot, view, sampler);
        return this;
    }

    private CgDrawState buffer(int slot, CgBindingLayout.Type type, CgAllocation a, long offset, long size) {
        keep(a);
        bindings.buffer(slot, type, a.buffer, a.offset + offset, size);
        return this;
    }

    private void keep(CgAllocation a) {
        int i = bindings.count();
        if (i == bindingAllocations.length) bindingAllocations = Arrays.copyOf(bindingAllocations, i * 2);
        bindingAllocations[i] = a;
    }

    CgAllocation bindingAllocation(int i) { return bindingAllocations[i]; }
}
