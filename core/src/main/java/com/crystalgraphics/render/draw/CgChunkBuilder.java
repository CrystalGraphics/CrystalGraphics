package com.crystalgraphics.render.draw;

import com.crystalgraphics.api.CgBindingPoints;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.trace.CgGpuTrace;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Objects;

/**
 * Writes {@link CgDrawChunk}s: open a chunk under a property state, open draws in it, write each draw's instance
 * records, end the chunk. Reusable — {@link #end()} hands the arrays to the chunk and starts fresh ones. CPU only.
 *
 * <pre>{@code
 * CgChunkBuilder c = recording.chunks();
 * c.begin(spatial, clip, effect);
 * c.draw(material.pipeline(CgInstanceKind.QUAD), material.captureBindings(recording.bindings()));
 * for (Glyph g : run) {
 *     int at = c.instance();               // one zeroed record of the draw's kind
 *     float[] r = c.data();
 *     r[at] = g.x; r[at + 1] = g.y;         // ...the rest of the record
 *     c.bounds(g.x0, g.y0, g.x1, g.y1);     // unions into the draw's bounds
 * }
 * CgDrawChunk chunk = c.end();
 * }</pre>
 *
 * <ul>
 *   <li>{@link #data()} is the open draw's kind's array, and is replaced when it grows: read it after
 *       {@link #instance()}, never across a call.</li>
 *   <li>A draw with no instances is dropped. A draw whose bounds are never set covers everything.</li>
 *   <li>An {@link CgInstanceKind#OBJECT} draw names the mesh its instances expand; the other kinds expand the unit
 *       quad and take none.</li>
 *   <li>One thread at a time, like the recording it writes for.</li>
 * </ul>
 */
public final class CgChunkBuilder {

    private CgBindingTable bindings;

    private boolean open;
    private int spatial;
    private int clip;
    private int effect;

    private int count;
    private int[] pipelines = new int[16];
    private int[] bindingIds = new int[16];
    private int[] kinds = new int[16];
    private int[] firsts = new int[16];
    private int[] instanceCounts = new int[16];
    @Nullable
    private CgMesh[] meshes;
    /** Per mesh draw: submesh (-1 for every one, whole), first, count (-1 to the end). Null until one is drawn. */
    @Nullable
    private int[] ranges;
    /** Per draw: the buffer an indirect draw's count is in, null for a direct one. Null until one is drawn. */
    @Nullable
    private CgBufferHandle[] counts;
    /** Per indirect draw: the count's byte offset; its mode's ordinal with the factor above it. */
    @Nullable
    private long[] countOffsets;
    @Nullable
    private int[] countModes;
    /** Per draw: the buffer its object records are in, null for records written here. Null until one is drawn. */
    @Nullable
    private CgBufferHandle[] objects;
    /** Per draw: a buffer it reads in place of an engine buffer, and where; null for none. Null until one is drawn. */
    @Nullable
    private CgBufferHandle[] buffers;
    @Nullable
    private CgBindingPoints.Binding[] bufferAt;
    private float[] bounds = new float[64];
    private long[] sortKeys = new long[16];
    /** Per draw: its GPU group's label, -1 for its material's. Null until one is named. */
    @Nullable
    private int[] groups;
    private boolean boundsSet;
    /** The last chunk was ended in place and still reads these arrays: its references are dropped at the next begin. */
    private boolean lent;

    /** Per kind ordinal: records written so far, and their floats. */
    private final float[][] instances = new float[CgInstanceKind.values().length][];
    private final int[] records = new int[CgInstanceKind.values().length];

    /** The open draw, or -1. */
    private int drawing = -1;
    private int drawingKind;
    private int drawingFloats;

    public CgChunkBuilder(CgBindingTable bindings) {
        this.bindings = bindings;
    }

    /** The table binding ids given to {@link #draw} must come from. */
    public CgBindingTable bindings() {
        return bindings;
    }

    /** Takes later chunks' binding ids from {@code table}: a recording's, for a builder that records into several. */
    public CgChunkBuilder bindings(CgBindingTable table) {
        if (open) throw new IllegalStateException("a chunk is open over another table");
        this.bindings = table;
        return this;
    }

    /** Starts a chunk under the root nodes. */
    public CgChunkBuilder begin() {
        return begin(0, 0, 0);
    }

    /** Starts a chunk whose draws are positioned in {@code spatial}, clipped by {@code clip}, grouped in {@code effect}. */
    public CgChunkBuilder begin(int spatial, int clip, int effect) {
        if (open) throw new IllegalStateException("begin() inside a chunk: end() the last one first");
        if (lent) dropReferences();
        open = true;
        this.spatial = spatial;
        this.clip = clip;
        this.effect = effect;
        return this;
    }

    /** Opens a draw; the instances written until the next draw or {@link #end()} are its. */
    public CgChunkBuilder draw(CgPipeline pipeline, int bindingId) {
        return draw(pipeline, bindingId, null);
    }

    /** As {@link #draw(CgPipeline, int)}, for an {@link CgInstanceKind#OBJECT} draw of {@code mesh}. */
    public CgChunkBuilder draw(CgPipeline pipeline, int bindingId, @Nullable CgMesh mesh) {
        if (!open) throw new IllegalStateException("draw() outside a chunk: begin() first");
        if ((pipeline.kind() == CgInstanceKind.OBJECT) != (mesh != null)) {
            throw new IllegalArgumentException(pipeline.kind() == CgInstanceKind.OBJECT
                    ? "an OBJECT draw names the mesh it expands" : "only an OBJECT draw takes a mesh");
        }
        closeDraw();
        if (count == pipelines.length) grow();
        int d = count;
        pipelines[d] = pipeline.id();
        bindingIds[d] = bindingId;
        drawingKind = pipeline.kind().ordinal();
        kinds[d] = drawingKind;
        firsts[d] = records[drawingKind];
        instanceCounts[d] = 0;
        if (mesh != null && meshes == null) {
            meshes = new CgMesh[pipelines.length];
            ranges = new int[pipelines.length * 3];
        }
        if (meshes != null) {
            meshes[d] = mesh;   // also clears what a dropped draw left in this slot
            ranges[d * 3] = -1;
            ranges[d * 3 + 1] = 0;
            ranges[d * 3 + 2] = -1;
        }
        if (counts != null) counts[d] = null;
        if (objects != null) objects[d] = null;
        if (buffers != null) {
            buffers[d] = null;
            bufferAt[d] = null;
        }
        if (groups != null) groups[d] = -1;
        sortKeys[d] = 0;
        boundsSet = false;
        drawingFloats = pipeline.kind().floats();
        drawing = d;
        return this;
    }

    /** Reserves one zeroed record for the open draw and answers its first float in {@link #data()}. */
    public int instance() {
        if (drawing < 0) throw new IllegalStateException("instance() with no draw open: draw() first");
        if (objects != null && objects[drawing] != null) throw new IllegalStateException("instance() on a draw of objects()");
        float[] data = instances[drawingKind];
        int at = records[drawingKind] * drawingFloats;
        if (data == null || at + drawingFloats > data.length) {
            data = data == null ? new float[drawingFloats * 64] : Arrays.copyOf(data, Math.max(data.length * 2, at + drawingFloats));
            instances[drawingKind] = data;
        } else {
            Arrays.fill(data, at, at + drawingFloats, 0f);
        }
        records[drawingKind]++;
        instanceCounts[drawing]++;
        return at;
    }

    /** Appends {@code count} whole records of the open draw's kind, read from {@code records} at float {@code from}. */
    public CgChunkBuilder instances(float[] records, int from, int count) {
        if (drawing < 0) throw new IllegalStateException("instances() with no draw open: draw() first");
        if (objects != null && objects[drawing] != null) throw new IllegalStateException("instances() on a draw of objects()");
        int floats = count * drawingFloats;
        float[] data = instances[drawingKind];
        int at = this.records[drawingKind] * drawingFloats;
        if (data == null || at + floats > data.length) {
            data = data == null ? new float[Math.max(drawingFloats * 64, floats)]
                    : Arrays.copyOf(data, Math.max(data.length * 2, at + floats));
            instances[drawingKind] = data;
        }
        System.arraycopy(records, from, data, at, floats);
        this.records[drawingKind] += count;
        instanceCounts[drawing] += count;
        return this;
    }

    /** The open draw's kind's records. Read it after {@link #instance()}; it is replaced when it grows. */
    public float[] data() {
        return instances[drawingKind];
    }

    /** Unions a rectangle into the open draw's bounds, in the chunk's spatial node. */
    public CgChunkBuilder bounds(float x0, float y0, float x1, float y1) {
        int b = drawing * 4;
        if (!boundsSet) {
            bounds[b] = x0;
            bounds[b + 1] = y0;
            bounds[b + 2] = x1;
            bounds[b + 3] = y1;
            boundsSet = true;
        } else {
            bounds[b] = Math.min(bounds[b], x0);
            bounds[b + 1] = Math.min(bounds[b + 1], y0);
            bounds[b + 2] = Math.max(bounds[b + 2], x1);
            bounds[b + 3] = Math.max(bounds[b + 3], y1);
        }
        return this;
    }

    /**
     * Draws part of the open draw's mesh: {@code submesh}'s indices from {@code first}, {@code count} of them (-1 to
     * its end); a submesh drawn without indices takes the range of its vertices. {@code CG_VERTEX_ID} is never moved
     * by a range: the quads of a second chunk of {@code CgMesh.quads(n)} continue where the first's stopped.
     *
     * <pre>{@code
     * chunks.draw(pipeline, bindings, CgMesh.quads(1024)).range(0, 0, live * 6);
     * }</pre>
     */
    public CgChunkBuilder range(int submesh, int first, int count) {
        if (drawing < 0 || meshes == null || meshes[drawing] == null) {
            throw new IllegalStateException("range() on a draw with no mesh");
        }
        if (submesh < 0 || first < 0) throw new IllegalArgumentException("range " + submesh + ", " + first);
        ranges[drawing * 3] = submesh;
        ranges[drawing * 3 + 1] = first;
        ranges[drawing * 3 + 2] = count;
        return this;
    }

    /**
     * Makes the open draw indirect: how much of its mesh it draws is the {@code uint} at byte {@code offset} in
     * {@code count}, written on the GPU, times {@code factor}, read as {@code mode} says and never past the draw's
     * range. A graph buffer's count is read after the pass that writes it in the frame.
     *
     * <pre>{@code
     * chunks.draw(pipeline, bindings, CgMesh.quads(1024)).indirect(live, 0, CgIndirect.INDICES, 6);   // a quad per element
     * chunks.instance();                                                                                // its one record
     * }</pre>
     *
     * <ul>
     *   <li>An {@link CgIndirect#INSTANCES} draw holds exactly one record, which every instance reads.</li>
     *   <li>A mesh of several submeshes names one with {@link #range}.</li>
     *   <li>An indirect draw never batches with another draw.</li>
     * </ul>
     */
    public CgChunkBuilder indirect(CgBufferHandle count, long offset, CgIndirect mode, int factor) {
        if (drawing < 0 || meshes == null || meshes[drawing] == null) {
            throw new IllegalStateException("indirect() on a draw with no mesh");
        }
        if (offset < 0 || (offset & 3) != 0) throw new IllegalArgumentException("a count's offset is a whole uint's: " + offset);
        if (factor < 1) throw new IllegalArgumentException("factor " + factor);
        boolean indexed = meshes[drawing].isIndexed();
        if (mode == CgIndirect.INDICES && !indexed || mode == CgIndirect.VERTICES && indexed) {
            throw new IllegalArgumentException(mode + " on a mesh " + (indexed ? "with" : "without") + " indices");
        }
        if (counts == null) {
            counts = new CgBufferHandle[pipelines.length];
            countOffsets = new long[pipelines.length];
            countModes = new int[pipelines.length];
        }
        counts[drawing] = count;
        countOffsets[drawing] = offset;
        countModes[drawing] = mode.ordinal() | factor << 2;
        return this;
    }

    /**
     * Draws the open draw's mesh once per object record in {@code records}, {@code count} of them from {@code first},
     * in place of records written here: {@link CgInstanceKind#OBJECT}'s layout, written on the GPU by a kernel or a
     * cull. Every material reads them as it reads any draw's, through {@code CG_OBJECT_DATA}.
     *
     * <pre>{@code
     * chunks.draw(pipeline, bindings, rock).objects(rocks, 0, 500);                       // records 0 to 499
     * chunks.draw(pipeline, bindings, rockLod1).objects(visible, capacity, capacity)
     *       .indirect(counts, 4, CgIndirect.INSTANCES, 1);                                // as many as the GPU counted
     * }</pre>
     *
     * <ul>
     *   <li>With an {@link CgIndirect#INSTANCES} count, instance i reads record {@code first + i} and the count draws
     *       no more than {@code count}.</li>
     *   <li>{@link #instance()} has nothing to write for such a draw, and refuses.</li>
     *   <li>A graph buffer is read after the pass writing it; it needs {@code STORAGE}.</li>
     *   <li>It never batches with another draw.</li>
     * </ul>
     */
    public CgChunkBuilder objects(CgBufferHandle records, int first, int count) {
        if (drawing < 0 || meshes == null || meshes[drawing] == null) {
            throw new IllegalStateException("objects() on a draw with no mesh");
        }
        if (instanceCounts[drawing] > 0) throw new IllegalStateException("objects() on a draw that wrote its own records");
        if (count < 1 || first < 0) throw new IllegalArgumentException("first " + first + ", count " + count);
        if (objects == null) objects = new CgBufferHandle[pipelines.length];
        objects[drawing] = records;
        firsts[drawing] = first;
        instanceCounts[drawing] = count;
        return this;
    }

    /**
     * Has the open draw read {@code buffer} where its shader reads the engine buffer at {@code at}: records in that
     * buffer's layout, which a kernel wrote, read through the same macros. The engine buffer is bound again for the
     * draws after it.
     *
     * <pre>{@code
     * chunks.draw(pipeline, bindings, quads).buffer(CgBindingPoints.PARTICLES, range.drawn())
     *       .indirect(range.visible(), range.visibleWord(slot) * 4L, CgIndirect.INDICES, 6);
     * }</pre>
     *
     * <ul>
     *   <li>One a draw. Object records take {@link #objects}, never {@code OBJECT_DATA} here.</li>
     *   <li>A graph buffer is read after the pass writing it; it needs {@code STORAGE}.</li>
     *   <li>The draw batches alone.</li>
     * </ul>
     */
    public CgChunkBuilder buffer(CgBindingPoints.Binding at, CgBufferHandle buffer) {
        if (drawing < 0) throw new IllegalStateException("buffer() with no draw open: draw() first");
        if (at.equals(CgBindingPoints.OBJECT_DATA)) throw new IllegalArgumentException("object records take objects()");
        if (buffers == null) {
            buffers = new CgBufferHandle[pipelines.length];
            bufferAt = new CgBindingPoints.Binding[pipelines.length];
        }
        buffers[drawing] = Objects.requireNonNull(buffer, "buffer");
        bufferAt[drawing] = at;
        return this;
    }

    /**
     * Charges the open draw's GPU time to {@code label} under {@code crystalgraphics.gpu.groups}, in place of its
     * material's path. Draws of different groups never batch while that channel is on, and always may while it is off.
     *
     * <pre>{@code
     * chunks.draw(pipeline, bindings, beam).gpuGroup("vfx.beam.core");
     * }</pre>
     */
    public CgChunkBuilder gpuGroup(String label) {
        if (drawing < 0) throw new IllegalStateException("gpuGroup() with no draw open: draw() first");
        if (groups == null) {
            groups = new int[pipelines.length];
            Arrays.fill(groups, -1);
        }
        groups[drawing] = CgGpuTrace.label(label);
        return this;
    }

    /** What a sorted pass orders the open draw by, ascending. */
    public CgChunkBuilder sortKey(long key) {
        sortKeys[drawing] = key;
        return this;
    }

    /** Ends the chunk and answers it; the builder is ready for the next. */
    public CgDrawChunk end() {
        if (!open) throw new IllegalStateException("end() outside a chunk");
        closeDraw();
        float[][] kept = new float[instances.length][];
        for (int k = 0; k < instances.length; k++) {
            kept[k] = instances[k] == null ? new float[0]
                    : Arrays.copyOf(instances[k], records[k] * CgInstanceKind.of(k).floats());
        }
        CgDrawChunk chunk = new CgDrawChunk(spatial, clip, effect, bindings, count,
                Arrays.copyOf(pipelines, count), Arrays.copyOf(bindingIds, count), Arrays.copyOf(kinds, count),
                Arrays.copyOf(firsts, count), Arrays.copyOf(instanceCounts, count),
                meshes == null ? null : Arrays.copyOf(meshes, count), ranges == null ? null : Arrays.copyOf(ranges, count * 3),
                counts == null ? null : Arrays.copyOf(counts, count), counts == null ? null : Arrays.copyOf(countOffsets, count),
                counts == null ? null : Arrays.copyOf(countModes, count),
                objects == null ? null : Arrays.copyOf(objects, count),
                buffers == null ? null : Arrays.copyOf(buffers, count),
                buffers == null ? null : Arrays.copyOf(bufferAt, count), Arrays.copyOf(bounds, count * 4),
                Arrays.copyOf(sortKeys, count), groups == null ? null : Arrays.copyOf(groups, count), kept);
        open = false;
        count = 0;
        Arrays.fill(records, 0);
        dropReferences();
        return chunk;
    }

    /**
     * Ends the chunk without copying it: the chunk reads this builder's own arrays, so it holds only until the
     * builder's next {@link #begin}. For a chunk built into a frame before then, as {@code CgImmediate} does.
     *
     * <pre>{@code
     * CgImmediate.flush(chunks.endInPlace(), CgOrder.SORTED);   // built and executed before it returns
     * }</pre>
     *
     * <ul>
     *   <li>Never hand it to a recording that executes later, nor keep it: the next chunk overwrites it.</li>
     *   <li>Its {@link CgDrawChunk#data} arrays run past the records it wrote, and a kind it wrote none of is null.</li>
     * </ul>
     */
    public CgDrawChunk endInPlace() {
        if (!open) throw new IllegalStateException("endInPlace() outside a chunk");
        closeDraw();
        CgDrawChunk chunk = new CgDrawChunk(spatial, clip, effect, bindings, count, pipelines, bindingIds, kinds,
                firsts, instanceCounts, meshes, ranges, counts, countOffsets, countModes, objects, buffers, bufferAt, bounds,
                sortKeys, groups, instances);
        open = false;
        count = 0;
        Arrays.fill(records, 0);
        lent = true;
        return chunk;
    }

    /** Discards an open chunk, if any: for an owner reusing the builder after a failure. */
    public void reset() {
        open = false;
        drawing = -1;
        count = 0;
        Arrays.fill(records, 0);
        dropReferences();
    }

    private void dropReferences() {
        lent = false;
        if (meshes != null) Arrays.fill(meshes, null);
        if (counts != null) Arrays.fill(counts, null);
        if (objects != null) Arrays.fill(objects, null);
        if (buffers != null) {
            Arrays.fill(buffers, null);
            Arrays.fill(bufferAt, null);
        }
    }

    /** Keeps the open draw if it has instances; a draw without bounds covers everything. */
    private void closeDraw() {
        if (drawing < 0) return;
        if (instanceCounts[drawing] > 0) {
            if (counts != null && counts[drawing] != null) checkIndirect(drawing);
            if (!boundsSet) {
                int b = drawing * 4;
                bounds[b] = Float.NEGATIVE_INFINITY;
                bounds[b + 1] = Float.NEGATIVE_INFINITY;
                bounds[b + 2] = Float.POSITIVE_INFINITY;
                bounds[b + 3] = Float.POSITIVE_INFINITY;
            }
            count++;
        }
        drawing = -1;
    }

    private void checkIndirect(int d) {
        boolean ofObjects = objects != null && objects[d] != null;
        if ((countModes[d] & 3) == CgIndirect.INSTANCES.ordinal() && !ofObjects && instanceCounts[d] != 1) {
            throw new IllegalStateException("an INSTANCES indirect draw holds one record, which every instance reads; "
                    + "this one holds " + instanceCounts[d]);
        }
        if (ranges[d * 3] < 0 && meshes[d].submeshCount() > 1) {
            throw new IllegalStateException("an indirect draw of a mesh of " + meshes[d].submeshCount()
                    + " submeshes names one with range()");
        }
    }

    private void grow() {
        int n = pipelines.length * 2;
        pipelines = Arrays.copyOf(pipelines, n);
        bindingIds = Arrays.copyOf(bindingIds, n);
        kinds = Arrays.copyOf(kinds, n);
        firsts = Arrays.copyOf(firsts, n);
        instanceCounts = Arrays.copyOf(instanceCounts, n);
        if (meshes != null) {
            meshes = Arrays.copyOf(meshes, n);
            ranges = Arrays.copyOf(ranges, n * 3);
        }
        if (counts != null) {
            counts = Arrays.copyOf(counts, n);
            countOffsets = Arrays.copyOf(countOffsets, n);
            countModes = Arrays.copyOf(countModes, n);
        }
        if (objects != null) objects = Arrays.copyOf(objects, n);
        if (buffers != null) {
            buffers = Arrays.copyOf(buffers, n);
            bufferAt = Arrays.copyOf(bufferAt, n);
        }
        if (groups != null) groups = Arrays.copyOf(groups, n);
        bounds = Arrays.copyOf(bounds, n * 4);
        sortKeys = Arrays.copyOf(sortKeys, n);
    }
}
