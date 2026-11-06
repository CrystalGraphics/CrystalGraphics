package com.crystalgraphics.render.graph;

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
 * closed before anything goes through {@code CgGL} (a barrier) and opened again after, as Unreal RDG places barriers
 * between passes. Its pipeline and sets are bound once each while it stays open.
 *
 * <pre>{@code
 * compute.begin(pass.name());
 * CgComputePass on = compute.pass(rhi);       // opens the section and the pass
 * compute.pipeline(pipeline, layout);
 * compute.set(2, kernelSet);
 * compute.close();                            // before CgGL; the next pass(rhi) opens again, counted as a split
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

    /** Ends the pass and its section: before anything through {@code CgGL}. */
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
        if (!l.equals(layout)) Arrays.fill(sets, null);
        layout = l;
    }

    void set(int index, CgBindingSet s) {
        if (sets[index] == s) return;
        open.setBindingSet(index, s);
        sets[index] = s;
    }
}
