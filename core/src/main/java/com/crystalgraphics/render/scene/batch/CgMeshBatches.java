package com.crystalgraphics.render.scene.batch;

import com.crystalgraphics.api.buffer.CgBuffer;
import com.crystalgraphics.api.buffer.CgBufferLayout;
import com.crystalgraphics.render.scene.sort.CgSortLayer;
import com.crystalgraphics.api.material.CgMaterialInterface;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.render.draw.CgBufferHandle;
import com.crystalgraphics.render.draw.CgDrawChunk;
import com.crystalgraphics.render.draw.CgIndirect;
import com.crystalgraphics.render.meshpass.CgPrimitiveInfo;
import com.crystalgraphics.render.shader.CgBindingModel;

import javax.annotation.Nullable;
import java.util.Arrays;

/**
 * What a view draws of a scene this frame: one row per mesh batch, Unreal's {@code FMeshBatch}, made by the view's
 * visibility ({@code CgViewVisibility}) and read by the mesh passes. A persistent primitive's row names its GPU scene
 * record ({@link #objectId}); a dynamic one's carries its own transform and values, written into a frame record by the
 * pass; an instanced one's names its component's {@link Instances}, which the GPU culls per view.
 *
 * <pre>{@code
 * CgMeshBatches batches = CgViewVisibility.of(view, scene, camera).batches();
 * for (int b = 0; b < batches.size(); b++) {
 *     long key = batches.key(b);                         // its sort key in this view: CgSortKey's
 *     if (batches.instances(b) != null) ...              // an instanced component: CgInstanceCulling's
 * }
 * }</pre>
 *
 * <ul>
 *   <li>A row names its primitive ({@link #primitive}, what a mesh pass processor reads) and, for a persistent one, the
 *       primitive's kept commands ({@link #cache}) with the row's place among its rows ({@link #ordinal}).</li>
 *   <li>Reused: {@link #clear} keeps its storage, so a warm frame allocates nothing.</li>
 *   <li>A row's arrays ({@link #values}, {@link #positions}) are the batches' own: read them before the next
 *       visibility of the view.</li>
 * </ul>
 */
public final class CgMeshBatches {

    /** {@link #flags} bits: the primitive's passes. */
    public static final int HALF_RESOLUTION = 1, AFTER_DISTORTION = 2, CASTS_SHADOW = 4;

    /**
     * An instanced component's instances, as a row names them: their object ids on the CPU and, for the GPU's cull, in a
     * buffer of its own kept while they stand.
     */
    public interface Instances {
        /** The live instances' object ids, {@link #count} of them from the first. */
        int[] ids();

        int count();

        /** The same ids on the GPU, four to an {@link #ID_LAYOUT} record, written again when an instance comes or goes. */
        CgBuffer gpuIds();
    }

    /**
     * Four instances' object ids on the GPU, as {@code instance_cull.compute} reads {@code InstanceIds}: a record is never
     * narrower than 16 bytes, so four ids share one, byte for byte a dense {@code uint} array.
     */
    public static final CgBufferLayout ID_LAYOUT = CgBufferLayout.builder("InstanceIds", CgBufferLayout.MemoryLayout.STD430)
            .uvec4("ids").build();

    private int size;
    private CgMesh[] meshes = new CgMesh[64];
    private CgMaterialInterface[] materials = new CgMaterialInterface[64];
    private CgSortLayer[] layers = new CgSortLayer[64];
    private CgPrimitiveInfo[] primitives = new CgPrimitiveInfo[64];
    private CgCachedCommands[] caches = new CgCachedCommands[64];
    private int[] ints = new int[64 * INTS];
    private Instances[] instances = new Instances[64];
    private float[] distances = new float[64];
    private long[] keys = new long[64];
    /**
     * A GPU-driven row's (a dynamic primitive's {@code indirect}, {@code perRecord} or {@code records}): the buffer its
     * count is in, else null, the count's byte offset, mode and factor; its records' buffer and capacity, else null.
     */
    private CgBufferHandle[] counts = new CgBufferHandle[64];
    private long[] countOffsets = new long[64];
    private CgIndirect[] countModes = new CgIndirect[64];
    private int[] countFactors = new int[64];
    private CgBufferHandle[] records = new CgBufferHandle[64];
    private int[] recordCapacity = new int[64];
    /** Per row, buffers it reads in place of engine buffers, and where: {@code CgDrawChunk.BUFFERS} slots, null unused. */
    private CgBufferHandle[] rowBuffers = new CgBufferHandle[64 * CgDrawChunk.BUFFERS];
    private CgBindingModel.Slot[] rowBufferAt = new CgBindingModel.Slot[64 * CgDrawChunk.BUFFERS];
    /** A dynamic row's object-to-world: its translation in doubles, then a 3x3 in floats; and its record's values. */
    private double[] positions = new double[64 * 3];
    private float[] values = new float[64 * VALUES];

    private static final int INTS = 6, SUBMESH = 0, OBJECT = 1, COUNT = 2, FLAGS = 3, PRIORITY = 4, BONES = 5;
    private int[] ordinal = new int[64];
    /** Per dynamic row: the 3x3 (9), custom data (16), light (2), emission (1). */
    public static final int VALUES = 28, BASIS = 0, CUSTOM = 9, LIGHT = 25, EMISSION = 27;

    public void clear() {
        Arrays.fill(meshes, 0, size, null);
        Arrays.fill(materials, 0, size, null);
        Arrays.fill(instances, 0, size, null);
        Arrays.fill(primitives, 0, size, null);
        Arrays.fill(caches, 0, size, null);
        Arrays.fill(counts, 0, size, null);
        Arrays.fill(records, 0, size, null);
        Arrays.fill(rowBuffers, 0, size * CgDrawChunk.BUFFERS, null);
        Arrays.fill(rowBufferAt, 0, size * CgDrawChunk.BUFFERS, null);
        size = 0;
    }

    /** A GPU-driven row's count buffer, or null for a row drawn as many times as it has objects. */
    @Nullable
    public CgBufferHandle count(int b) {
        return counts[b];
    }

    public long countOffset(int b) {
        return countOffsets[b];
    }

    public CgIndirect countMode(int b) {
        return countModes[b];
    }

    public int countFactor(int b) {
        return countFactors[b];
    }

    /** A {@code records} row's buffer of its own 48-float records, or null. */
    @Nullable
    public CgBufferHandle records(int b) {
        return records[b];
    }

    public int recordCapacity(int b) {
        return recordCapacity[b];
    }

    /** Row {@code b}'s buffer in slot {@code s} (0 to {@code CgDrawChunk.BUFFERS}), or null. */
    @Nullable
    public CgBufferHandle buffer(int b, int s) {
        return rowBuffers[b * CgDrawChunk.BUFFERS + s];
    }

    @Nullable
    public CgBindingModel.Slot bufferAt(int b, int s) {
        return rowBufferAt[b * CgDrawChunk.BUFFERS + s];
    }

    /** The collector's: row {@code b} draws as many as the {@code uint} at {@code offset} of {@code buffer} times {@code factor} says. */
    public void count(int b, CgBufferHandle buffer, long offset, CgIndirect mode, int factor) {
        counts[b] = buffer;
        countOffsets[b] = offset;
        countModes[b] = mode;
        countFactors[b] = factor;
    }

    /** The collector's: row {@code b} draws records of its own from {@code buffer}, at most {@code capacity}. */
    public void records(int b, CgBufferHandle buffer, int capacity) {
        records[b] = buffer;
        recordCapacity[b] = capacity;
    }

    /** The collector's: row {@code b} reads {@code buffer} at pass slot {@code at}. */
    public void buffer(int b, CgBindingModel.Slot at, CgBufferHandle buffer) {
        for (int s = b * CgDrawChunk.BUFFERS, end = s + CgDrawChunk.BUFFERS; s < end; s++) {
            if (rowBufferAt[s] == null || rowBufferAt[s] == at) {
                rowBufferAt[s] = at;
                rowBuffers[s] = buffer;
                return;
            }
        }
        throw new IllegalStateException("a row reads at most " + CgDrawChunk.BUFFERS + " buffers of its own");
    }

    public int size() {
        return size;
    }

    public CgMesh mesh(int b) {
        return meshes[b];
    }

    public CgMaterialInterface material(int b) {
        return materials[b];
    }

    /** The submesh drawn, or -1 for the whole mesh. */
    public int submesh(int b) {
        return ints[b * INTS + SUBMESH];
    }

    /** What {@link #objectId} answers for a row naming no GPU scene record: no scene id, whose top bit is set, is -1. */
    public static final int NO_OBJECT = -1;

    /** The GPU scene record's object id ({@code CgGpuScene.id}, negative), or {@link #NO_OBJECT} for a dynamic or instanced row. */
    public int objectId(int b) {
        return ints[b * INTS + OBJECT];
    }

    /** An instanced row's instances; null for one object. */
    @Nullable
    public Instances instances(int b) {
        return instances[b];
    }

    /** How many objects the row draws before any GPU cull: its instances', else 1. */
    public int instanceCount(int b) {
        return ints[b * INTS + COUNT];
    }

    /** {@link #HALF_RESOLUTION}, {@link #AFTER_DISTORTION}, {@link #CASTS_SHADOW}. */
    public int flags(int b) {
        return ints[b * INTS + FLAGS];
    }

    /** The primitive the row is of: what a mesh pass processor reads. */
    public CgPrimitiveInfo primitive(int b) {
        return primitives[b];
    }

    /** Its primitive's kept commands, or null for a row built every frame (a dynamic primitive's). */
    @Nullable
    public CgCachedCommands cache(int b) {
        return caches[b];
    }

    /** Its place among its primitive's rows this frame, 0 up: what its kept commands are found by. */
    public int ordinal(int b) {
        return ordinal[b];
    }

    public CgSortLayer layer(int b) {
        return layers[b];
    }

    public int sortPriority(int b) {
        return ints[b * INTS + PRIORITY];
    }


    /** A skinned row's palette base this frame ({@code CgBonePalettes}), or -1 for a row that is not skinned. */
    public int bones(int b) {
        return ints[b * INTS + BONES];
    }

    /** How far ahead of the view's eye the row is, along its forward: its primitive's box centre, or its own position. */
    public float distance(int b) {
        return distances[b];
    }

    /** Its sort key in the view: {@code CgSortKey}'s, opaque front to back, transparent back to front. */
    public long key(int b) {
        return keys[b];
    }

    /** A dynamic row's absolute translation along {@code axis}. */
    public double position(int b, int axis) {
        return positions[b * 3 + axis];
    }

    /** A dynamic row's values: its 3x3 basis, custom data, light and emission, {@value #VALUES} floats from {@code b * VALUES}. */
    public float[] values() {
        return values;
    }

    /**
     * The gather's: a row of {@code primitive}'s, answering its index; {@link #object}, {@link #instances(int,
     * Instances)}, the dynamic values and {@link #distance(int, float)} are set after. A primitive's rows are added
     * together, so each takes the next {@link #ordinal}.
     */
    public int add(CgMesh mesh, CgMaterialInterface material, int submesh, CgSortLayer layer, int flags, int sortPriority,
                   CgPrimitiveInfo primitive, @Nullable CgCachedCommands cache) {
        if (size == meshes.length) grow();
        int b = size++;
        meshes[b] = mesh;
        materials[b] = material;
        layers[b] = layer;
        primitives[b] = primitive;
        caches[b] = cache;
        counts[b] = null;
        records[b] = null;
        Arrays.fill(rowBuffers, b * CgDrawChunk.BUFFERS, (b + 1) * CgDrawChunk.BUFFERS, null);
        Arrays.fill(rowBufferAt, b * CgDrawChunk.BUFFERS, (b + 1) * CgDrawChunk.BUFFERS, null);
        ordinal[b] = b > 0 && primitives[b - 1] == primitive ? ordinal[b - 1] + 1 : 0;
        int i = b * INTS;
        ints[i + SUBMESH] = submesh;
        ints[i + OBJECT] = NO_OBJECT;
        ints[i + COUNT] = 1;
        ints[i + FLAGS] = flags;
        ints[i + PRIORITY] = sortPriority;
        ints[i + BONES] = -1;
        instances[b] = null;
        distances[b] = Float.NaN;
        keys[b] = 0L;
        return b;
    }

    /** The gather's: row {@code b} draws GPU scene record {@code objectId}. */
    public void object(int b, int objectId) {
        ints[b * INTS + OBJECT] = objectId;
    }

    /** The gather's: row {@code b} is skinned by the palette starting at {@code base}. */
    public void bones(int b, int base) {
        ints[b * INTS + BONES] = base;
    }

    /** The gather's: row {@code b} draws {@code of}'s instances. */
    public void instances(int b, Instances of) {
        instances[b] = of;
        ints[b * INTS + COUNT] = of.count();
    }

    /** The gather's: row {@code b}'s distance ahead of the eye. */
    public void distance(int b, float distance) {
        distances[b] = distance;
    }

    /** The visibility's: row {@code b}'s sort key in its view. */
    public void key(int b, long key) {
        keys[b] = key;
    }

    /** The gather's: every dynamic row's absolute translation, three doubles from {@code b * 3}. */
    public double[] positions() {
        return positions;
    }

    private void grow() {
        int n = meshes.length * 2;
        meshes = Arrays.copyOf(meshes, n);
        materials = Arrays.copyOf(materials, n);
        layers = Arrays.copyOf(layers, n);
        primitives = Arrays.copyOf(primitives, n);
        caches = Arrays.copyOf(caches, n);
        ordinal = Arrays.copyOf(ordinal, n);
        ints = Arrays.copyOf(ints, n * INTS);
        instances = Arrays.copyOf(instances, n);
        distances = Arrays.copyOf(distances, n);
        keys = Arrays.copyOf(keys, n);
        counts = Arrays.copyOf(counts, n);
        countOffsets = Arrays.copyOf(countOffsets, n);
        countModes = Arrays.copyOf(countModes, n);
        countFactors = Arrays.copyOf(countFactors, n);
        records = Arrays.copyOf(records, n);
        recordCapacity = Arrays.copyOf(recordCapacity, n);
        rowBuffers = Arrays.copyOf(rowBuffers, n * CgDrawChunk.BUFFERS);
        rowBufferAt = Arrays.copyOf(rowBufferAt, n * CgDrawChunk.BUFFERS);
        positions = Arrays.copyOf(positions, n * 3);
        values = Arrays.copyOf(values, n * VALUES);
    }
}
