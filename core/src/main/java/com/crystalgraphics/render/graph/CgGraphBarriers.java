package com.crystalgraphics.render.graph;

import com.crystalgraphics.platform.gl.CgGLImports;
import com.crystalgraphics.platform.rhi.CgDeviceSection;
import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.platform.rhi.command.CgCommandList;
import com.crystalgraphics.platform.rhi.command.CgTransition;
import com.crystalgraphics.platform.rhi.resource.CgGpuTexture;

import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * The executor's transitions on a device, batched as Unreal RDG batches a pass's: one {@link CgTransition} a batch on
 * the device's command list.
 *
 * <pre>{@code
 * barriers.begin();
 * barriers.buffer(name, from, to);
 * barriers.texture(name, image, level, 1, layer, 1, from, to);
 * barriers.framebuffer(fbo, from, to);
 * barriers.end(compute);            // or int split = barriers.endSplit(compute), then barriers.split(split, compute)
 * }</pre>
 *
 * <ul>
 *   <li>Batches nest; the outermost end records. A barrier outside any batch is a batch of its own.</li>
 *   <li>On a device it records inside the open compute pass where there is one, else in a device section of its own:
 *       never later, since {@code CgGL} work between two passes may depend on it.</li>
 *   <li>A framebuffer the device cannot name images for (GL's) is a memory barrier of its accesses.</li>
 *   <li>A host with no device states them through {@code CgGL} instead: the executor's own.</li>
 *   <li>Render thread.</li>
 * </ul>
 */
final class CgGraphBarriers {

    private CgTransition batch = new CgTransition();
    private int depth, memoryFrom, memoryTo;
    private CgTransition[] splits = new CgTransition[8];
    private final ArrayDeque<CgTransition> spare = new ArrayDeque<>();

    /** Opens a batch. */
    void begin() {
        if (depth++ > 0) return;
        batch.clear();
        memoryFrom = memoryTo = 0;
    }

    void buffer(int name, int from, int to) {
        boolean alone = depth == 0;
        if (alone) begin();
        batch.buffer(CgGLImports.get().buffer(name), from, to);
        if (alone) end(false);
    }

    /** A range of {@code image}. */
    void texture(CgGpuTexture image, int level, int levels, int layer, int layers, int from, int to) {
        boolean alone = depth == 0;
        if (alone) begin();
        batch.texture(image, level, levels, layer, layers, from, to);
        if (alone) end(false);
    }

    /** Framebuffer {@code fbo}'s attachments, 0 the window's. */
    void framebuffer(int fbo, int from, int to) {
        boolean alone = depth == 0;
        if (alone) begin();
        if (!CgGLImports.get().framebufferBarrier(fbo, from, to, batch)) {
            memoryFrom |= from;
            memoryTo |= to;
        }
        if (alone) end(false);
    }

    /** Closes a batch, recorded whole at the outermost; {@code compute} the open compute pass, if any. */
    void end(boolean compute) {
        if (--depth > 0) return;
        if (batch.count() == 0 && memoryFrom == 0) return;
        boolean section = enter(compute);
        try {
            CgCommandList list = CgRHI.get().encoder();
            if (memoryFrom != 0) list.memoryBarrier(memoryFrom, memoryTo);
            if (batch.count() > 0) list.transition(batch);
        } finally {
            leave(section);
        }
        batch.clear();
    }

    /**
     * Closes the outermost batch as the first half of a split transition; answers its id for {@link #split}, 0 when it
     * held nothing. A memory barrier has no halves: recorded whole here.
     */
    int endSplit(boolean compute) {
        if (--depth > 0) return 0;
        if (batch.count() == 0 && memoryFrom == 0) return 0;
        int id = 0;
        boolean section = enter(compute);
        try {
            CgCommandList list = CgRHI.get().encoder();
            if (memoryFrom != 0) list.memoryBarrier(memoryFrom, memoryTo);
            if (batch.count() == 0) return 0;
            while (id < splits.length && splits[id] != null) id++;
            if (id == splits.length) splits = Arrays.copyOf(splits, id * 2);
            splits[id] = batch;
            list.beginTransitions(batch);
        } finally {
            leave(section);
        }
        batch = spare.isEmpty() ? new CgTransition() : spare.pop();
        return id + 1;
    }

    /** The second half of split {@code id}. */
    void split(int id, boolean compute) {
        if (id == 0) return;
        CgTransition t = splits[id - 1];
        if (t == null) throw new IllegalStateException("split " + id + " was not begun");
        splits[id - 1] = null;
        boolean section = enter(compute);
        try {
            CgRHI.get().encoder().endTransitions(t);
        } finally {
            leave(section);
        }
        spare.push(t.clear());
    }

    /** A device section of its own unless a compute pass is open: answers whether it opened one. */
    private static boolean enter(boolean compute) {
        if (compute) return false;
        CgDeviceSection.begin();
        return true;
    }

    private static void leave(boolean section) {
        if (section) CgDeviceSection.end();
    }
}
