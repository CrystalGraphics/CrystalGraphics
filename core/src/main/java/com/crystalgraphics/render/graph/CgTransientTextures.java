package com.crystalgraphics.render.graph;

import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.platform.rhi.memory.CgTransientAllocator;
import com.crystalgraphics.platform.rhi.memory.CgTransientMemory;
import com.crystalgraphics.platform.rhi.resource.CgGpuTexture;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Transient graph textures placed in the device's transient heaps, so textures never alive together share memory
 * (Unreal RDG's transient allocator, {@code SetTransientTextureRHI}). Each acquire takes ranges for the description's
 * attachments; the storage placed at those ranges before is reused, so a steady frame creates nothing, and one
 * unused for {@link CgTransientAllocator#GC_FRAMES} frames is deleted. Where the device cannot place,
 * {@link CgTexturePool} keeps them instead. Render thread.
 *
 * <pre>{@code
 * CgGraphStorage bloom = transients.acquire(desc, joined);   // its contents undefined
 * ...
 * transients.release(bloom, pendingAsync);                  // after its last use
 * transients.endFrame(waited, forget);
 * }</pre>
 */
final class CgTransientTextures {

    final CgRHI rhi;
    private final CgTransientMemory memory;
    private final CgTransientAllocator heaps;
    private final Map<CgTextureDesc, Shape> shapes = new HashMap<>();
    private final List<Shape> shapeList = new ArrayList<>();
    private final Map<CgGraphStorage, Placed> out = new IdentityHashMap<>();
    private long frame;
    private int created;

    CgTransientTextures(CgRHI rhi, CgTransientMemory memory) {
        this.rhi = rhi;
        this.memory = memory;
        this.heaps = new CgTransientAllocator(rhi, memory);
    }

    /** Whether {@code desc} is placed: a 2D storage's, every attachment a texture the device makes. */
    static boolean places(CgTextureDesc desc) {
        return !desc.isArray() && !desc.isVolume() && CgGraphStorage.allSampled(desc.format());
    }

    /**
     * Storage for {@code desc}, its contents undefined.
     *
     * @param joined the newest async point the queue of its first use has waited for, {@link Long#MAX_VALUE} for
     *               the async queue
     */
    CgGraphStorage acquire(CgTextureDesc desc, long joined) {
        Shape shape = shape(desc);
        long[] ranges = shape.ranges;
        for (int i = 0; i < ranges.length; i++) ranges[i] = heaps.allocate(shape.needs[i], joined);
        Placed placed = shape.find(ranges);
        if (placed == null) placed = place(shape, ranges.clone());
        placed.usedAt = frame;
        for (CgGpuTexture image : placed.images) heaps.discard(image);
        out.put(placed.storage, placed);
        return placed.storage;
    }

    /** Gives back {@code storage}'s ranges; {@code fence} is the async point of its last use, else 0. */
    void release(CgGraphStorage storage, long fence) {
        Placed placed = out.remove(storage);
        if (placed == null) throw new IllegalStateException(storage + " is not a placed transient out now");
        for (int i = 0; i < placed.ranges.length; i++) heaps.free(placed.ranges[i], placed.shape.needs[i].size(), fence);
    }

    /** Bytes the device needs for {@code desc}'s textures, unplaced: what a pooled storage of it holds. */
    long bytes(CgTextureDesc desc) {
        return shape(desc).bytes;
    }

    /** Bytes of every heap held. */
    long heapBytes() {
        return heaps.heapBytes();
    }

    /** The most bytes of textures alive at once in the last frame ended. */
    long framePeak() {
        return heaps.framePeak();
    }

    /**
     * Once a frame: storages unused for {@link CgTransientAllocator#GC_FRAMES} frames deleted, each handed to
     * {@code deleting} first, then the heaps' end of frame.
     *
     * @param joined the newest async point the frame's queue has waited for
     */
    void endFrame(long joined, Consumer<CgGraphStorage> deleting) {
        for (int s = 0; s < shapeList.size(); s++) {
            List<Placed> placed = shapeList.get(s).placed;
            for (int i = placed.size() - 1; i >= 0; i--) {
                Placed p = placed.get(i);
                if (frame - p.usedAt < CgTransientAllocator.GC_FRAMES || out.containsKey(p.storage)) continue;
                deleting.accept(p.storage);
                delete(p);
                placed.set(i, placed.get(placed.size() - 1));
                placed.remove(placed.size() - 1);
            }
        }
        heaps.endFrame(joined);
        frame++;
    }

    void delete() {
        for (Shape shape : shapeList) {
            for (Placed p : shape.placed) delete(p);
            shape.placed.clear();
        }
        out.clear();
        heaps.delete();
    }

    private Shape shape(CgTextureDesc desc) {
        Shape shape = shapes.get(desc);
        if (shape == null) {
            shapes.put(desc, shape = new Shape(desc, "cg_graph_" + desc.width() + "x" + desc.height() + "_" + created++));
            shapeList.add(shape);
        }
        return shape;
    }

    private Placed place(Shape shape, long[] ranges) {
        CgGpuTexture[] images = new CgGpuTexture[ranges.length];
        int[] next = {0};
        CgGraphStorage storage = CgGraphStorage.make(shape.name, shape.desc, desc -> {
                    int i = next[0]++;
                    if (i >= ranges.length || !desc.equals(shape.descs.get(i))) {
                        throw new IllegalStateException(shape.name + ": placed storage cannot be remade as " + desc);
                    }
                    return images[i] = heaps.place(ranges[i], desc);
                });
        Placed placed = new Placed(shape, ranges, images, storage);
        shape.placed.add(placed);
        return placed;
    }

    private void delete(Placed p) {
        p.storage.release();
        for (long range : p.ranges) heaps.unplaced(range);
    }

    /** One description's attachments, what each needs, and the storages placed for it so far. */
    private final class Shape {
        final CgTextureDesc desc;
        final String name;
        final List<CgGpuTexture.Desc> descs;
        final CgTransientMemory.Requirements[] needs;
        final long bytes;
        /** This acquire's ranges, compared against each placement's. */
        final long[] ranges;
        final List<Placed> placed = new ArrayList<>(2);

        Shape(CgTextureDesc desc, String name) {
            this.desc = desc;
            this.name = name;
            this.descs = CgGraphStorage.deviceDescs(name, desc);
            this.needs = new CgTransientMemory.Requirements[descs.size()];
            long sum = 0;
            for (int i = 0; i < needs.length; i++) sum += (needs[i] = memory.requirements(descs.get(i))).size();
            this.bytes = sum;
            this.ranges = new long[needs.length];
        }

        Placed find(long[] at) {
            for (int i = 0; i < placed.size(); i++) {
                if (Arrays.equals(placed.get(i).ranges, at)) return placed.get(i);
            }
            return null;
        }
    }

    /** A storage whose texture slots are placed at {@link #ranges}, in attachment order. */
    private static final class Placed {
        final Shape shape;
        final long[] ranges;
        final CgGpuTexture[] images;
        final CgGraphStorage storage;
        long usedAt;

        Placed(Shape shape, long[] ranges, CgGpuTexture[] images, CgGraphStorage storage) {
            this.shape = shape;
            this.ranges = ranges;
            this.images = images;
            this.storage = storage;
        }
    }
}
