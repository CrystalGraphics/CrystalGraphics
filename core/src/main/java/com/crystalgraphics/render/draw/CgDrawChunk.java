package com.crystalgraphics.render.draw;

import com.crystalgraphics.api.CgBindingPoints;
import com.crystalgraphics.api.mesh.CgMesh;

import javax.annotation.Nullable;
import java.util.Arrays;

/**
 * A run of recorded draws under one property state — spatial node, clip node, effect node — with the instance records
 * they draw: Blink's paint chunk. What a recorder hands over, and what a box's paint is made of.
 *
 * <pre>{@code
 * CgChunkBuilder c = recording.chunks().begin();
 * c.draw(pipeline, bindings);
 * int at = c.instance();
 * c.data()[at] = x;                       // the record, in the kind's format
 * c.bounds(x, y, x + w, y + h);
 * pass.add(c.end());
 * }</pre>
 *
 * <ul>
 *   <li>Binding ids index {@link #bindings()}, the table of the recording it was made in; a frame builder re-interns
 *       them into its own, so a chunk may be added to a later recording unchanged.</li>
 *   <li>Bounds are in the spatial node's space and are what batching and damage trust: ink bounds, antialiasing
 *       included. A draw recorded without bounds covers everything.</li>
 *   <li>Nothing in it changes after {@link CgChunkBuilder#end()}; the arrays the accessors return are its own and
 *       must not be written.</li>
 *   <li>One ended by a recording's own builder ({@code recording.chunks()}) is that recording's, refilled once the
 *       recording is reset: keep one past that through {@link #with}, which copies it. Its {@link #data} arrays may run
 *       past the records its draws name.</li>
 * </ul>
 */
public final class CgDrawChunk {

    int spatial;
    int clip;
    int effect;
    CgBindingTable bindings;

    int count;
    int[] pipelines;
    int[] bindingIds;
    int[] kinds;
    int[] firsts;
    int[] instanceCounts;
    @Nullable
    CgMesh[] meshes;
    @Nullable
    int[] ranges;
    @Nullable
    CgBufferHandle[] counts;
    @Nullable
    long[] countOffsets;
    @Nullable
    int[] countModes;
    @Nullable
    CgBufferHandle[] objects;
    @Nullable
    CgBufferHandle[] buffers;
    @Nullable
    CgBindingPoints.Binding[] bufferAt;
    float[] bounds;
    long[] sortKeys;
    @Nullable
    int[] groups;

    /** Instance records per kind ordinal, each trimmed to what the chunk wrote. */
    float[][] instances;
    /** Per draw, whether each record is its own command; per OBJECT record of such a draw, its count's offset. */
    @Nullable
    boolean[] each;
    @Nullable
    long[] recordCounts;

    /** Whether a recording's builder owns it, refilling it after the recording's reset. */
    final boolean pooled;
    /** A shell's own arrays, kept across refills; the fields above point at them, or are null where no draw had one. */
    private CgMesh[] ownMeshes;
    private int[] ownRanges, ownCountModes, ownGroups;
    private CgBufferHandle[] ownCounts, ownObjects, ownBuffers;
    private long[] ownCountOffsets, ownRecordCounts;
    private CgBindingPoints.Binding[] ownBufferAt;
    private boolean[] ownEach;

    private static final float[] NO_RECORDS = new float[0];

    /** An empty shell for a recording builder's pool. */
    CgDrawChunk() {
        pooled = true;
        pipelines = new int[0];
        bindingIds = new int[0];
        kinds = new int[0];
        firsts = new int[0];
        instanceCounts = new int[0];
        bounds = new float[0];
        sortKeys = new long[0];
        instances = new float[CgInstanceKind.values().length][];
    }

    CgDrawChunk(int spatial, int clip, int effect, CgBindingTable bindings, int count, int[] pipelines, int[] bindingIds,
                int[] kinds, int[] firsts, int[] instanceCounts, @Nullable CgMesh[] meshes, @Nullable int[] ranges,
                @Nullable CgBufferHandle[] counts, @Nullable long[] countOffsets, @Nullable int[] countModes,
                @Nullable CgBufferHandle[] objects, @Nullable CgBufferHandle[] buffers,
                @Nullable CgBindingPoints.Binding[] bufferAt, float[] bounds, long[] sortKeys,
                @Nullable int[] groups, float[][] instances, @Nullable boolean[] each, @Nullable long[] recordCounts) {
        this.pooled = false;
        this.spatial = spatial;
        this.clip = clip;
        this.effect = effect;
        this.bindings = bindings;
        this.count = count;
        this.pipelines = pipelines;
        this.bindingIds = bindingIds;
        this.kinds = kinds;
        this.firsts = firsts;
        this.instanceCounts = instanceCounts;
        this.meshes = meshes;
        this.ranges = ranges;
        this.counts = counts;
        this.countOffsets = countOffsets;
        this.countModes = countModes;
        this.objects = objects;
        this.buffers = buffers;
        this.bufferAt = bufferAt;
        this.bounds = bounds;
        this.sortKeys = sortKeys;
        this.groups = groups;
        this.instances = instances;
        this.each = each;
        this.recordCounts = recordCounts;
    }

    /** The spatial node its draws are positioned in; 0 is the root. */
    public int spatial() {
        return spatial;
    }

    /** The clip node its draws are clipped by; 0 is none. */
    public int clip() {
        return clip;
    }

    /** The effect node its draws are grouped under; 0 is the root. */
    public int effect() {
        return effect;
    }

    /** The table its binding ids index. */
    public CgBindingTable bindings() {
        return bindings;
    }

    /** How many draws it holds. */
    public int draws() {
        return count;
    }

    public int pipeline(int draw) {
        return pipelines[draw];
    }

    public int binding(int draw) {
        return bindingIds[draw];
    }

    public CgInstanceKind kind(int draw) {
        return CgInstanceKind.of(kinds[draw]);
    }

    /** The draw's first instance, in records, within {@link #data(CgInstanceKind)} of its kind, or its {@link #objects}. */
    public int first(int draw) {
        return firsts[draw];
    }

    public int instances(int draw) {
        return instanceCounts[draw];
    }

    /** The mesh an {@link CgInstanceKind#OBJECT} draw expands; null for the other kinds. */
    @Nullable
    public CgMesh mesh(int draw) {
        return meshes == null ? null : meshes[draw];
    }

    /** The submesh a mesh draw draws, or -1 for every one of them, whole. */
    public int rangeSubmesh(int draw) {
        return ranges == null ? -1 : ranges[draw * 3];
    }

    /** Where in its submesh a mesh draw starts, in indices (or vertices, for one without). */
    public int rangeFirst(int draw) {
        return ranges == null ? 0 : ranges[draw * 3 + 1];
    }

    /** How many a mesh draw draws, or -1 to its submesh's end. */
    public int rangeCount(int draw) {
        return ranges == null ? -1 : ranges[draw * 3 + 2];
    }

    /** The buffer an indirect draw's count is in; null for a direct draw. */
    @Nullable
    public CgBufferHandle indirectCount(int draw) {
        return counts == null ? null : counts[draw];
    }

    /** The count's byte offset in {@link #indirectCount}. */
    public long indirectOffset(int draw) {
        return countOffsets[draw];
    }

    /** Whether each of the draw's records is its own indirect command, its count at {@link #recordCount}. */
    public boolean indirectEach(int draw) {
        return each != null && each[draw];
    }

    /** The byte offset of the count of record {@code k} of a draw of {@code indirectEach}. */
    public long recordCount(int draw, int k) {
        return recordCounts[firsts[draw] + k];
    }

    /** {@code CgIndirect.values()}, once: {@code values()} copies its array each call. */
    private static final CgIndirect[] INDIRECT_MODES = CgIndirect.values();

    public CgIndirect indirectMode(int draw) {
        return INDIRECT_MODES[countModes[draw] & 3];
    }

    /** What the count is multiplied by. */
    public int indirectFactor(int draw) {
        return countModes[draw] >>> 2;
    }

    /** The buffer a draw of {@code objects()} reads its object records from; null for records in this chunk. */
    @Nullable
    public CgBufferHandle objects(int draw) {
        return objects == null ? null : objects[draw];
    }

    /** How many buffers a draw may read in place of engine buffers ({@code CgChunkBuilder.buffer}). */
    public static final int BUFFERS = 2;

    /** The {@code slot}th buffer a draw of {@code buffer()} reads in place of an engine buffer; null for none. */
    @Nullable
    public CgBufferHandle buffer(int draw, int slot) {
        return buffers == null ? null : buffers[draw * BUFFERS + slot];
    }

    /** Where {@link #buffer} is read: the engine buffer's binding it stands in for; null for none. */
    @Nullable
    public CgBindingPoints.Binding bufferAt(int draw, int slot) {
        return bufferAt == null ? null : bufferAt[draw * BUFFERS + slot];
    }

    public float x0(int draw) {
        return bounds[draw * 4];
    }

    public float y0(int draw) {
        return bounds[draw * 4 + 1];
    }

    public float x1(int draw) {
        return bounds[draw * 4 + 2];
    }

    public float y1(int draw) {
        return bounds[draw * 4 + 3];
    }

    /** What a sorted pass orders the draw by: ascending, so the recorder writes front-to-back or back-to-front. */
    public long sortKey(int draw) {
        return sortKeys[draw];
    }

    /** The label {@code CgChunkBuilder.gpuGroup} gave the draw, from {@code CgGpuTrace.label}; -1 for its material's. */
    public int gpuGroup(int draw) {
        return groups == null ? -1 : groups[draw];
    }

    /**
     * This chunk positioned in {@code spatial}, naming its snapshots in {@code bindings} by {@code bindingIds}, over
     * {@code instances}, each null for this chunk's own: how a replay adds kept drawing to a later recording once it
     * has renumbered what the records name. Arrays given become the copy's own.
     */
    public CgDrawChunk with(int spatial, CgBindingTable bindings, @Nullable int[] bindingIds,
                            @Nullable float[][] instances) {
        if (pooled) return copy().with(spatial, bindings, bindingIds, instances);
        return new CgDrawChunk(spatial, clip, effect, bindings, count, pipelines,
                bindingIds != null ? bindingIds : this.bindingIds, kinds, firsts, instanceCounts, meshes, ranges, counts,
                countOffsets, countModes, objects, buffers, bufferAt, bounds, sortKeys, groups,
                instances != null ? instances : this.instances, each, recordCounts);
    }

    /** This chunk with arrays of its own, trimmed to what it holds: what outlives a pooled chunk's recording. */
    private CgDrawChunk copy() {
        float[][] kept = new float[instances.length][];
        for (int k = 0; k < instances.length; k++) kept[k] = instances[k] == null ? NO_RECORDS : instances[k].clone();
        return new CgDrawChunk(spatial, clip, effect, bindings, count, Arrays.copyOf(pipelines, count),
                Arrays.copyOf(bindingIds, count), Arrays.copyOf(kinds, count), Arrays.copyOf(firsts, count),
                Arrays.copyOf(instanceCounts, count), meshes == null ? null : Arrays.copyOf(meshes, count),
                ranges == null ? null : Arrays.copyOf(ranges, count * 3), counts == null ? null : Arrays.copyOf(counts, count),
                countOffsets == null ? null : Arrays.copyOf(countOffsets, count),
                countModes == null ? null : Arrays.copyOf(countModes, count),
                objects == null ? null : Arrays.copyOf(objects, count),
                buffers == null ? null : Arrays.copyOf(buffers, count * BUFFERS),
                bufferAt == null ? null : Arrays.copyOf(bufferAt, count * BUFFERS), Arrays.copyOf(bounds, count * 4),
                Arrays.copyOf(sortKeys, count), groups == null ? null : Arrays.copyOf(groups, count), kept,
                each == null ? null : Arrays.copyOf(each, count), recordCounts == null ? null : recordCounts.clone());
    }

    /**
     * Refills a shell from a builder's arrays: copied into arrays it keeps, grown only when a chunk holds more than any
     * before it. {@code records} is each kind's record count; a null source leaves its field null.
     */
    void fill(int spatial, int clip, int effect, CgBindingTable bindings, int count, int[] pipelines, int[] bindingIds,
              int[] kinds, int[] firsts, int[] instanceCounts, @Nullable CgMesh[] meshes, @Nullable int[] ranges,
              @Nullable CgBufferHandle[] counts, @Nullable long[] countOffsets, @Nullable int[] countModes,
              @Nullable CgBufferHandle[] objects, @Nullable CgBufferHandle[] buffers,
              @Nullable CgBindingPoints.Binding[] bufferAt, float[] bounds, long[] sortKeys, @Nullable int[] groups,
              float[][] instances, int[] records, @Nullable boolean[] each, @Nullable long[] recordCounts) {
        this.spatial = spatial;
        this.clip = clip;
        this.effect = effect;
        this.bindings = bindings;
        this.count = count;
        this.pipelines = ints(this.pipelines, pipelines, count);
        this.bindingIds = ints(this.bindingIds, bindingIds, count);
        this.kinds = ints(this.kinds, kinds, count);
        this.firsts = ints(this.firsts, firsts, count);
        this.instanceCounts = ints(this.instanceCounts, instanceCounts, count);
        if (this.bounds.length < count * 4) this.bounds = new float[Math.max(count * 4, this.bounds.length * 2)];
        System.arraycopy(bounds, 0, this.bounds, 0, count * 4);
        if (this.sortKeys.length < count) this.sortKeys = new long[Math.max(count, this.sortKeys.length * 2)];
        System.arraycopy(sortKeys, 0, this.sortKeys, 0, count);
        if (meshes == null) {
            this.meshes = null;
            this.ranges = null;
        } else {
            if (ownMeshes == null || ownMeshes.length < count) ownMeshes = new CgMesh[Math.max(count, 16)];
            System.arraycopy(meshes, 0, ownMeshes, 0, count);
            this.meshes = ownMeshes;
            this.ranges = ownRanges = ints(ownRanges, ranges, count * 3);
        }
        if (counts == null) {
            this.counts = null;
            this.countOffsets = null;
            this.countModes = null;
        } else {
            this.counts = ownCounts = handles(ownCounts, counts, count);
            if (ownCountOffsets == null || ownCountOffsets.length < count) ownCountOffsets = new long[Math.max(count, 16)];
            System.arraycopy(countOffsets, 0, ownCountOffsets, 0, count);
            this.countOffsets = ownCountOffsets;
            this.countModes = ownCountModes = ints(ownCountModes, countModes, count);
        }
        this.objects = objects == null ? null : (ownObjects = handles(ownObjects, objects, count));
        if (buffers == null) {
            this.buffers = null;
            this.bufferAt = null;
        } else {
            int n = count * BUFFERS;
            this.buffers = ownBuffers = handles(ownBuffers, buffers, n);
            if (ownBufferAt == null || ownBufferAt.length < n) ownBufferAt = new CgBindingPoints.Binding[Math.max(n, 32)];
            System.arraycopy(bufferAt, 0, ownBufferAt, 0, n);
            this.bufferAt = ownBufferAt;
        }
        this.groups = groups == null ? null : (ownGroups = ints(ownGroups, groups, count));
        for (int k = 0; k < instances.length; k++) {
            int n = records[k] * CgInstanceKind.of(k).floats();
            if (n == 0) {
                this.instances[k] = NO_RECORDS;
                continue;
            }
            float[] own = this.instances[k];
            if (own == null || own.length < n) own = new float[Math.max(n, own == null ? 0 : own.length * 2)];
            System.arraycopy(instances[k], 0, own, 0, n);
            this.instances[k] = own;
        }
        if (each == null) {
            this.each = null;
            this.recordCounts = null;
        } else {
            if (ownEach == null || ownEach.length < count) ownEach = new boolean[Math.max(count, 16)];
            System.arraycopy(each, 0, ownEach, 0, count);
            this.each = ownEach;
            int n = records[CgInstanceKind.OBJECT.ordinal()];
            if (ownRecordCounts == null || ownRecordCounts.length < n) ownRecordCounts = new long[Math.max(n, 64)];
            System.arraycopy(recordCounts, 0, ownRecordCounts, 0, n);
            this.recordCounts = ownRecordCounts;
        }
    }

    /** Drops what a shell names, so a reset recording keeps no mesh, buffer or table alive. */
    void clearReferences() {
        bindings = null;
        if (ownMeshes != null) Arrays.fill(ownMeshes, null);
        if (ownCounts != null) Arrays.fill(ownCounts, null);
        if (ownObjects != null) Arrays.fill(ownObjects, null);
        if (ownBuffers != null) Arrays.fill(ownBuffers, null);
        if (ownBufferAt != null) Arrays.fill(ownBufferAt, null);
    }

    private static int[] ints(@Nullable int[] own, int[] from, int n) {
        if (own == null || own.length < n) own = new int[Math.max(n, own == null ? 16 : own.length * 2)];
        System.arraycopy(from, 0, own, 0, n);
        return own;
    }

    private static CgBufferHandle[] handles(@Nullable CgBufferHandle[] own, CgBufferHandle[] from, int n) {
        if (own == null || own.length < n) own = new CgBufferHandle[Math.max(n, own == null ? 16 : own.length * 2)];
        System.arraycopy(from, 0, own, 0, n);
        return own;
    }

    /** The instance records of {@code kind}, every draw of that kind's in turn. Read only. */
    public float[] data(CgInstanceKind kind) {
        return instances[kind.ordinal()];
    }
}
