package com.crystalgraphics.render.scene.batch;

import com.crystalgraphics.api.buffer.CgBuffer;
import com.crystalgraphics.api.buffer.CgBufferLayout;
import com.crystalgraphics.render.scene.sort.CgSortLayer;
import com.crystalgraphics.api.material.CgMaterialInterface;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.render.draw.CgBufferHandle;
import com.crystalgraphics.render.draw.CgDrawChunk;
import com.crystalgraphics.render.draw.CgIndirect;
import com.crystalgraphics.render.meshpass.CgMeshBatch;
import com.crystalgraphics.render.meshpass.CgPrimitiveInfo;
import com.crystalgraphics.render.post.volume.CgPostSettings;
import com.crystalgraphics.compute.ops.CgGpuCount;
import com.crystalgraphics.render.shader.CgBindingModel;

import com.crystalgraphics.api.mesh.CgMeshLods;
import com.crystalgraphics.shape.bounds.CgAABBc;
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
    public static final int HALF_RESOLUTION = CgMeshBatch.HALF_RESOLUTION, AFTER_DISTORTION = CgMeshBatch.AFTER_DISTORTION,
            CASTS_SHADOW = CgMeshBatch.CASTS_SHADOW;

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
    /** Per dynamic row: the levels its mesh is picked from in each view, else null. */
    private CgMeshLods[] lods = new CgMeshLods[64];
    /** Per dynamic row: its stated object-space box (min, max), x NaN for its mesh's, and what pads it. */
    private float[] statedBounds = new float[64 * 6];
    private float[] boundsPad = new float[64];
    private CgMaterialInterface[] materials = new CgMaterialInterface[64];
    private CgSortLayer[] layers = new CgSortLayer[64];
    private CgPrimitiveInfo[] primitives = new CgPrimitiveInfo[64];
    private CgCachedCommands[] caches = new CgCachedCommands[64];
    private int[] ints = new int[64 * INTS];
    private Instances[] instances = new Instances[64];
    private float[] distances = new float[64], primitiveDistances = new float[64];
    /** Per row: the look it is graded by, null for the scene's; and the point it sorts as one at (x NaN for its primitive's). */
    private CgPostSettings[] grades = new CgPostSettings[64];
    private double[] sortPoints = new double[64 * 3];
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
    private int[] recordCapacity = new int[64], recordFirst = new int[64];
    /** Per row, buffers it reads in place of engine buffers, and where: {@code CgDrawChunk.BUFFERS} slots, null unused. */
    private CgBufferHandle[] rowBuffers = new CgBufferHandle[64 * CgDrawChunk.BUFFERS];
    private CgBindingModel.Slot[] rowBufferAt = new CgBindingModel.Slot[64 * CgDrawChunk.BUFFERS];
    /** A dynamic row's object-to-world: its translation in doubles, then a 3x3 in floats; and its record's values. */
    private double[] positions = new double[64 * 3];
    private float[] values = new float[64 * VALUES];

    private static final int INTS = 11, SUBMESH = 0, OBJECT = 1, COUNT = 2, FLAGS = 3, PRIORITY = 4, BONES = 5, BATCH = 6,
            SUBJECT = 7, CUSTOM_MASK = 8, FIRST_INDEX = 9, INDEX_COUNT = 10;
    /** Per culled {@code records} row: its records' count, the scale and pad its cull places them by; null where unculled. */
    private CgGpuCount[] cullCounts = new CgGpuCount[64];
    private float[] cullScales = new float[64], cullPads = new float[64];
    private int[] ordinal = new int[64];
    /** Per dynamic row: the 3x3 (9), custom data (16), light (2), emission (1). */
    public static final int VALUES = 28, BASIS = 0, CUSTOM = 9, LIGHT = 25, EMISSION = 27;

    public void clear() {
        Arrays.fill(meshes, 0, size, null);
        Arrays.fill(lods, 0, size, null);
        Arrays.fill(materials, 0, size, null);
        Arrays.fill(instances, 0, size, null);
        Arrays.fill(primitives, 0, size, null);
        Arrays.fill(caches, 0, size, null);
        Arrays.fill(grades, 0, size, null);
        Arrays.fill(cullCounts, 0, size, null);
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

    /** The first of a {@code records} row's records in its buffer. */
    public int recordFirst(int b) {
        return recordFirst[b];
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

    /** The collector's: row {@code b} draws records of its own from {@code buffer}, from record {@code first}, at most {@code capacity}. */
    public void records(int b, CgBufferHandle buffer, int first, int capacity) {
        records[b] = buffer;
        recordFirst[b] = first;
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

    /** A dynamic row's levels, which each view picks its mesh from, or null. */
    @Nullable
    public CgMeshLods lods(int b) {
        return lods[b];
    }

    /** Whether row {@code b} states its box rather than taking its mesh's. */
    public boolean statesBounds(int b) {
        return !Float.isNaN(statedBounds[b * 6]);
    }

    /** Row {@code b}'s stated box, object space: {@code min x, y, z, max x, y, z} at {@code k} 0 to 5. */
    public float statedBounds(int b, int k) {
        return statedBounds[b * 6 + k];
    }

    /** How far row {@code b}'s box is grown on every side: what its vertices move past it by (bones, a wave). */
    public float boundsPad(int b) {
        return boundsPad[b];
    }

    /** The collector's: row {@code b}'s box, object space, grown by {@code pad}; a null box keeps its mesh's. */
    public void bounds(int b, @Nullable CgAABBc box, float pad) {
        if (box != null) {
            statedBounds[b * 6] = (float) box.minX();
            statedBounds[b * 6 + 1] = (float) box.minY();
            statedBounds[b * 6 + 2] = (float) box.minZ();
            statedBounds[b * 6 + 3] = (float) box.maxX();
            statedBounds[b * 6 + 4] = (float) box.maxY();
            statedBounds[b * 6 + 5] = (float) box.maxZ();
        }
        boundsPad[b] = pad;
    }

    /** The collector's: row {@code b} picks its mesh from {@code levels} in each view. */
    public void lods(int b, @Nullable CgMeshLods levels) {
        lods[b] = levels;
    }

    /** The view's: row {@code b} draws {@code level}, its levels' pick. */
    public void mesh(int b, CgMesh level) {
        meshes[b] = level;
    }

    /** Whether the GPU places what row {@code b} draws (a count, records or instances): its mesh's box bounds nothing. */
    public boolean gpuPlaced(int b) {
        return counts[b] != null || records[b] != null || instances[b] != null;
    }

    /** Drops the last row: one its view found nothing of to draw. */
    public void removeLast() {
        int b = --size;
        meshes[b] = null;
        lods[b] = null;
        materials[b] = null;
        instances[b] = null;
        primitives[b] = null;
        caches[b] = null;
        grades[b] = null;
        cullCounts[b] = null;
        counts[b] = null;
        records[b] = null;
        Arrays.fill(rowBuffers, b * CgDrawChunk.BUFFERS, (b + 1) * CgDrawChunk.BUFFERS, null);
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

    /** The collector's: row {@code b} takes {@code flags} besides its primitive's. */
    public void addFlags(int b, int flags) {
        ints[b * INTS + FLAGS] |= flags;
    }

    /** Its batch key: what orders a primitive's translucent rows of one distance (a VFX renderer's draw order), 0 by default. */
    public int batchKey(int b) {
        return ints[b * INTS + BATCH];
    }

    /** The collector's: row {@code b}'s batch key. */
    public void batchKey(int b, int key) {
        ints[b * INTS + BATCH] = key;
    }

    /** Which of row {@code b}'s four custom data slots it stated itself: bit k for slot k. */
    public int customMask(int b) {
        return ints[b * INTS + CUSTOM_MASK];
    }

    public void customMask(int b, int mask) {
        ints[b * INTS + CUSTOM_MASK] = mask;
    }

    /** Whether row {@code b}'s records are culled on the GPU per view, each placed by its scale and stated custom data. */
    public boolean culled(int b) {
        return cullCounts[b] != null;
    }

    /** A culled row's records' count. */
    public CgGpuCount cullCount(int b) {
        return cullCounts[b];
    }

    public float cullScale(int b) {
        return cullScales[b];
    }

    public float cullPad(int b) {
        return cullPads[b];
    }

    /** The collector's: row {@code b}'s records culled on the GPU, as many as {@code count}, each scaled by {@code scale}. */
    public void culled(int b, CgGpuCount count, float scale, float pad) {
        cullCounts[b] = count;
        cullScales[b] = scale;
        cullPads[b] = pad;
    }

    /** The look row {@code b} is graded by apart from the scene's ({@code grade(look)}), or null. */
    @Nullable
    public CgPostSettings grade(int b) {
        return grades[b];
    }

    public void grade(int b, @Nullable CgPostSettings look) {
        grades[b] = look;
    }

    /** The impact frame subject key row {@code b} glows for ({@code subject(key)}), 0 for none. */
    public int subject(int b) {
        return ints[b * INTS + SUBJECT];
    }

    public void subject(int b, int key) {
        ints[b * INTS + SUBJECT] = key;
    }

    /** Whether row {@code b} sorts as one at a point of its own rather than its primitive's: a group of a primitive's rows. */
    public boolean hasSortPoint(int b) {
        return !Double.isNaN(sortPoints[b * 3]);
    }

    /** Row {@code b}'s sort point along {@code axis}, absolute. */
    public double sortPoint(int b, int axis) {
        return sortPoints[b * 3 + axis];
    }

    /** The collector's: row {@code b} sorts at ({@code x}, {@code y}, {@code z}) with every row sorting there. */
    public void sortAt(int b, double x, double y, double z) {
        sortPoints[b * 3] = x;
        sortPoints[b * 3 + 1] = y;
        sortPoints[b * 3 + 2] = z;
    }

    /**
     * A copy of {@code from}'s row {@code b}, answering its index: every setting, form and value it has, its distances
     * left to the view that takes it. What a primitive whose rows are made once a frame hands each view.
     */
    public int copy(CgMeshBatches from, int b) {
        int c = add(from.meshes[b], from.materials[b], from.submesh(b), from.layers[b], from.flags(b), from.sortPriority(b),
                from.primitives[b], null);
        System.arraycopy(from.ints, b * INTS, ints, c * INTS, INTS);
        lods[c] = from.lods[b];
        System.arraycopy(from.statedBounds, b * 6, statedBounds, c * 6, 6);
        boundsPad[c] = from.boundsPad[b];
        instances[c] = from.instances[b];
        counts[c] = from.counts[b];
        countOffsets[c] = from.countOffsets[b];
        countModes[c] = from.countModes[b];
        countFactors[c] = from.countFactors[b];
        records[c] = from.records[b];
        recordFirst[c] = from.recordFirst[b];
        recordCapacity[c] = from.recordCapacity[b];
        System.arraycopy(from.rowBuffers, b * CgDrawChunk.BUFFERS, rowBuffers, c * CgDrawChunk.BUFFERS, CgDrawChunk.BUFFERS);
        System.arraycopy(from.rowBufferAt, b * CgDrawChunk.BUFFERS, rowBufferAt, c * CgDrawChunk.BUFFERS, CgDrawChunk.BUFFERS);
        System.arraycopy(from.positions, b * 3, positions, c * 3, 3);
        System.arraycopy(from.sortPoints, b * 3, sortPoints, c * 3, 3);
        System.arraycopy(from.values, b * VALUES, values, c * VALUES, VALUES);
        grades[c] = from.grades[b];
        cullCounts[c] = from.cullCounts[b];
        cullScales[c] = from.cullScales[b];
        cullPads[c] = from.cullPads[b];
        return c;
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

    /** The collector's: row {@code b}'s sort layer, over its primitive's. */
    public void layer(int b, CgSortLayer layer) {
        layers[b] = layer;
    }

    /** The collector's: row {@code b}'s sort priority, over its primitive's. */
    public void sortPriority(int b, int priority) {
        ints[b * INTS + PRIORITY] = priority;
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

    /**
     * How far ahead of the eye its primitive is: what its translucent rows sort by as one, as Unreal sorts a primitive's
     * sections at its bounds' centre; a row's own {@link #distance} orders them after its batch key.
     */
    public float primitiveDistance(int b) {
        return primitiveDistances[b];
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
        lods[b] = null;
        statedBounds[b * 6] = Float.NaN;
        boundsPad[b] = 0f;
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
        ints[i + BATCH] = 0;
        ints[i + SUBJECT] = 0;
        ints[i + CUSTOM_MASK] = 0;
        ints[i + FIRST_INDEX] = 0;
        ints[i + INDEX_COUNT] = -1;
        grades[b] = null;
        cullCounts[b] = null;
        sortPoints[b * 3] = Double.NaN;
        instances[b] = null;
        distances[b] = Float.NaN;
        primitiveDistances[b] = Float.NaN;
        keys[b] = 0L;
        return b;
    }

    /** The gather's: row {@code b} draws GPU scene record {@code objectId}. */
    public void object(int b, int objectId) {
        ints[b * INTS + OBJECT] = objectId;
    }

    /** Where row {@code b}'s draw starts in its submesh (or mesh), in indices: 0 for the whole. */
    public int firstIndex(int b) {
        return ints[b * INTS + FIRST_INDEX];
    }

    /** How many indices row {@code b} draws from {@link #firstIndex}: -1 to the end. */
    public int indexCount(int b) {
        return ints[b * INTS + INDEX_COUNT];
    }

    /** The collector's: row {@code b} draws {@code count} indices from {@code first} (Unreal's element index range). */
    public void indices(int b, int first, int count) {
        ints[b * INTS + FIRST_INDEX] = first;
        ints[b * INTS + INDEX_COUNT] = count;
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

    /** The gather's: row {@code b}'s primitive's distance ahead of the eye. */
    public void primitiveDistance(int b, float distance) {
        primitiveDistances[b] = distance;
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
        lods = Arrays.copyOf(lods, n);
        statedBounds = Arrays.copyOf(statedBounds, n * 6);
        boundsPad = Arrays.copyOf(boundsPad, n);
        materials = Arrays.copyOf(materials, n);
        layers = Arrays.copyOf(layers, n);
        primitives = Arrays.copyOf(primitives, n);
        caches = Arrays.copyOf(caches, n);
        ordinal = Arrays.copyOf(ordinal, n);
        ints = Arrays.copyOf(ints, n * INTS);
        instances = Arrays.copyOf(instances, n);
        distances = Arrays.copyOf(distances, n);
        primitiveDistances = Arrays.copyOf(primitiveDistances, n);
        keys = Arrays.copyOf(keys, n);
        counts = Arrays.copyOf(counts, n);
        countOffsets = Arrays.copyOf(countOffsets, n);
        countModes = Arrays.copyOf(countModes, n);
        countFactors = Arrays.copyOf(countFactors, n);
        records = Arrays.copyOf(records, n);
        recordCapacity = Arrays.copyOf(recordCapacity, n);
        recordFirst = Arrays.copyOf(recordFirst, n);
        grades = Arrays.copyOf(grades, n);
        cullCounts = Arrays.copyOf(cullCounts, n);
        cullScales = Arrays.copyOf(cullScales, n);
        cullPads = Arrays.copyOf(cullPads, n);
        sortPoints = Arrays.copyOf(sortPoints, n * 3);
        rowBuffers = Arrays.copyOf(rowBuffers, n * CgDrawChunk.BUFFERS);
        rowBufferAt = Arrays.copyOf(rowBufferAt, n * CgDrawChunk.BUFFERS);
        positions = Arrays.copyOf(positions, n * 3);
        values = Arrays.copyOf(values, n * VALUES);
    }
}
