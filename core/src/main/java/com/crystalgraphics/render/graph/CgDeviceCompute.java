package com.crystalgraphics.render.graph;

import com.crystalgraphics.render.draw.CgDeviceSets;
import com.crystalgraphics.platform.rhi.CgDeviceSection;
import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.platform.rhi.binding.CgBindingSet;
import com.crystalgraphics.platform.rhi.command.CgComputePass;
import com.crystalgraphics.platform.rhi.pipeline.CgComputePipeline;
import com.crystalgraphics.platform.rhi.pipeline.CgPipelineLayout;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;

import javax.annotation.Nullable;
import java.util.Arrays;

/**
 * A graph compute pass's dispatches on the device: its compute pass, opened in a device section at the first dispatch,
 * closed before what cannot run inside one (a copy, a barrier) and opened again after, as Unreal RDG places barriers
 * between passes. Its pipeline and sets are bound once each while it stays open.
 *
 * <pre>{@code
 * compute.begin(pass.name());
 * CgComputePass on = compute.pass(rhi);       // opens the section and the pass
 * compute.pipeline(pipeline, layout);
 * compute.set(2, kernelSet);
 * compute.close();                            // before a copy; the next pass(rhi) opens again, counted as a split
 * }</pre>
 */
final class CgDeviceCompute {

    private static final int SPLITS = CgTrace.name("graph.device-compute-splits");

    @Nullable
    private CgComputePass open;
    private String label = "";
    private boolean opened;
    @Nullable
    private CgComputePipeline pipeline;
    @Nullable
    private CgPipelineLayout layout;
    private final CgBindingSet[] sets = new CgBindingSet[CgRHI.BINDLESS_SET + 1];
    /** Each set's dynamic offsets as bound, and how many. */
    private final int[][] offsets = new int[CgRHI.BINDLESS_SET + 1][CgDeviceSets.MAX_DYNAMIC];
    private final int[] offsetCounts = new int[CgRHI.BINDLESS_SET + 1];

    /** A graph pass starts: its first {@link #pass} is no split. */
    void begin(String label) {
        this.label = label;
        opened = false;
    }

    /** The device's compute pass, opened in a device section if closed. */
    CgComputePass pass(CgRHI rhi) {
        if (open == null) {
            if (opened) CgTrace.add(CgChannels.GL, SPLITS, 1);
            CgDeviceSection.begin();
            open = rhi.encoder().beginCompute(label);
            opened = true;
            pipeline = null;
            layout = null;
            Arrays.fill(sets, null);
        }
        return open;
    }

    boolean isOpen() {
        return open != null;
    }

    /** Ends the pass and its section: before anything that runs outside a compute pass. */
    void close() {
        if (open == null) return;
        CgComputePass ending = open;
        open = null;
        try {
            ending.end();
        } finally {
            CgDeviceSection.end();
        }
    }

    /** Sets {@code p}; a layout of other sets drops what was bound. */
    void pipeline(CgComputePipeline p, CgPipelineLayout l) {
        if (p == pipeline) return;
        open.setPipeline(p);
        pipeline = p;
        if (l != layout && !l.equals(layout)) Arrays.fill(sets, null);   // layouts are shared: identity first, no iterator
        layout = l;
    }

    void set(int index, CgBindingSet s) {
        set(index, s, CgBindingSet.NO_OFFSETS, 0);
    }

    /** {@code s} at {@code index} with its dynamic offsets; skipped where both are bound. */
    void set(int index, CgBindingSet s, int[] dynamicOffsets, int count) {
        if (sets[index] == s && sameOffsets(index, dynamicOffsets, count)) return;
        open.setBindingSet(index, s, dynamicOffsets, count);
        sets[index] = s;
        System.arraycopy(dynamicOffsets, 0, offsets[index], 0, count);
        offsetCounts[index] = count;
    }

    private boolean sameOffsets(int index, int[] dynamicOffsets, int count) {
        if (offsetCounts[index] != count) return false;
        for (int i = 0; i < count; i++) if (offsets[index][i] != dynamicOffsets[i]) return false;
        return true;
    }
}
