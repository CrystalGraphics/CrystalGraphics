package com.crystalgraphics.render.draw;

import com.crystalgraphics.api.mesh.CgMesh;

import javax.annotation.Nullable;

/**
 * An immutable run of recorded draws under one property state — spatial node, clip node, effect node — with the
 * instance records they draw: Blink's paint chunk. What a recorder hands over, and what a box's paint is made of.
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
 * </ul>
 */
public final class CgDrawChunk {

    private final int spatial;
    private final int clip;
    private final int effect;
    private final CgBindingTable bindings;

    private final int count;
    private final int[] pipelines;
    private final int[] bindingIds;
    private final int[] kinds;
    private final int[] firsts;
    private final int[] instanceCounts;
    @Nullable
    private final CgMesh[] meshes;
    @Nullable
    private final int[] ranges;
    @Nullable
    private final CgBufferHandle[] counts;
    @Nullable
    private final long[] countOffsets;
    @Nullable
    private final int[] countModes;
    @Nullable
    private final CgBufferHandle[] objects;
    private final float[] bounds;
    private final long[] sortKeys;

    /** Instance records per kind ordinal, each trimmed to what the chunk wrote. */
    private final float[][] instances;

    CgDrawChunk(int spatial, int clip, int effect, CgBindingTable bindings, int count, int[] pipelines, int[] bindingIds,
                int[] kinds, int[] firsts, int[] instanceCounts, @Nullable CgMesh[] meshes, @Nullable int[] ranges,
                @Nullable CgBufferHandle[] counts, @Nullable long[] countOffsets, @Nullable int[] countModes,
                @Nullable CgBufferHandle[] objects, float[] bounds, long[] sortKeys, float[][] instances) {
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
        this.bounds = bounds;
        this.sortKeys = sortKeys;
        this.instances = instances;
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

    public CgIndirect indirectMode(int draw) {
        return CgIndirect.values()[countModes[draw] & 3];
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

    /**
     * This chunk positioned in {@code spatial}, naming its snapshots in {@code bindings} by {@code bindingIds}, over
     * {@code instances}, each null for this chunk's own: how a replay adds kept drawing to a later recording once it
     * has renumbered what the records name. Arrays given become the copy's own.
     */
    public CgDrawChunk with(int spatial, CgBindingTable bindings, @Nullable int[] bindingIds,
                            @Nullable float[][] instances) {
        return new CgDrawChunk(spatial, clip, effect, bindings, count, pipelines,
                bindingIds != null ? bindingIds : this.bindingIds, kinds, firsts, instanceCounts, meshes, ranges, counts,
                countOffsets, countModes, objects, bounds, sortKeys, instances != null ? instances : this.instances);
    }

    /** The instance records of {@code kind}, every draw of that kind's in turn. Read only. */
    public float[] data(CgInstanceKind kind) {
        return instances[kind.ordinal()];
    }
}
