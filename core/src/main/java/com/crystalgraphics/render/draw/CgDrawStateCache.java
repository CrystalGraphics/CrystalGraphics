package com.crystalgraphics.render.draw;

import com.crystalgraphics.platform.rhi.binding.CgBindingSet;
import com.crystalgraphics.platform.rhi.command.CgRenderPass;
import com.crystalgraphics.platform.rhi.pipeline.CgGraphicsPipeline;
import com.crystalgraphics.platform.rhi.pipeline.CgPipelineLayout;
import com.crystalgraphics.platform.rhi.resource.CgGpuBuffer;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;

/**
 * What a render pass has set, so a draw sets only what differs: Unreal's {@code FMeshDrawCommandStateCache}. One per
 * pass, {@link #reset} as it opens.
 *
 * <pre>{@code
 * cache.reset();                                   // a new pass: nothing is set
 * cache.pipeline(pass, p);                         // set, or skipped as already set
 * cache.set(pass, CgBindingModel.MATERIAL, material);
 * cache.vertexBuffer(pass, vertices, 0);
 * pass.drawIndexed(...);
 * }</pre>
 *
 * <ul>
 *   <li>A pipeline change keeps the sets its layout shares with the last one's, up to the first set that differs
 *       (Vulkan's pipeline layout compatibility); a set's layout is reflected per program, so a material's often
 *       differs.</li>
 *   <li>Counted: {@code bindings.sets-bound}, {@code bindings.sets-skipped}.</li>
 * </ul>
 */
public final class CgDrawStateCache {

    private static final int BOUND = CgTrace.name("bindings.sets-bound"), SKIPPED = CgTrace.name("bindings.sets-skipped");
    private static final int SETS = 4;

    private CgGraphicsPipeline pipeline;
    private final CgBindingSet[] sets = new CgBindingSet[SETS];
    /** Each set's dynamic offsets as bound, and how many. */
    private final int[][] offsets = new int[SETS][CgDeviceSets.MAX_DYNAMIC];
    private final int[] offsetCounts = new int[SETS];
    private CgGpuBuffer vertices, indices;
    private long vertexOffset, indexOffset;
    private int stencil = -1;
    private long bound, skipped;

    /** Nothing set: a pass just opened. */
    public void reset() {
        pipeline = null;
        for (int i = 0; i < SETS; i++) sets[i] = null;
        vertices = indices = null;
        stencil = -1;
    }

    public void pipeline(CgRenderPass pass, CgGraphicsPipeline p) {
        if (p == pipeline) return;
        if (pipeline != null) forgetFrom(compatibleSets(pipeline.desc().layout(), p.desc().layout()));
        pass.setPipeline(p);
        pipeline = p;
    }

    /** How many leading sets {@code b} may keep from {@code a}: binding layouts are interned, so compared by identity. */
    private static int compatibleSets(CgPipelineLayout a, CgPipelineLayout b) {
        if (a == b) return SETS;
        if (a.pushConstantBytes() != b.pushConstantBytes()) return 0;
        int n = Math.min(a.sets().size(), b.sets().size());
        for (int i = 0; i < n; i++) if (a.sets().get(i) != b.sets().get(i)) return i;
        return n;
    }

    private boolean sameOffsets(int index, int[] dynamicOffsets, int count) {
        if (offsetCounts[index] != count) return false;
        for (int i = 0; i < count; i++) if (offsets[index][i] != dynamicOffsets[i]) return false;
        return true;
    }

    private void forgetFrom(int first) {
        for (int i = first; i < SETS; i++) sets[i] = null;
    }

    /** {@code set} at {@code index}, a set with no dynamic slot. */
    public void set(CgRenderPass pass, int index, CgBindingSet set) {
        set(pass, index, set, CgBindingSet.NO_OFFSETS, 0);
    }

    /** {@code set} at {@code index} with its dynamic offsets ({@link CgDeviceSets#offsets}): skipped where both are set. */
    public void set(CgRenderPass pass, int index, CgBindingSet set, int[] dynamicOffsets, int count) {
        if (sets[index] == set && sameOffsets(index, dynamicOffsets, count)) {
            skipped++;
            CgTrace.add(CgChannels.GL, SKIPPED, 1);
            return;
        }
        pass.setBindingSet(index, set, dynamicOffsets, count);
        sets[index] = set;
        System.arraycopy(dynamicOffsets, 0, offsets[index], 0, count);
        offsetCounts[index] = count;
        bound++;
        CgTrace.add(CgChannels.GL, BOUND, 1);
    }

    public void vertexBuffer(CgRenderPass pass, CgGpuBuffer buffer, long offset) {
        if (buffer == vertices && offset == vertexOffset) return;
        pass.setVertexBuffer(0, buffer, offset);
        vertices = buffer;
        vertexOffset = offset;
    }

    public void indexBuffer(CgRenderPass pass, CgGpuBuffer buffer, long offset) {
        if (buffer == indices && offset == indexOffset) return;
        pass.setIndexBuffer(buffer, offset, true);
        indices = buffer;
        indexOffset = offset;
    }

    public void stencilReference(CgRenderPass pass, int reference) {
        if (reference == stencil) return;
        pass.setStencilReference(reference);
        stencil = reference;
    }

    /** Sets bound and skipped since it was made. */
    public long bound() {
        return bound;
    }

    public long skipped() {
        return skipped;
    }
}
