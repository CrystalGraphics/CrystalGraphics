package com.crystalgraphics.render.scene.batch;

import com.crystalgraphics.api.buffer.CgBuffer;
import com.crystalgraphics.api.buffer.CgBufferLayout;
import com.crystalgraphics.render.scene.sort.CgSortLayer;
import com.crystalgraphics.api.material.CgMaterialInterface;
import com.crystalgraphics.api.mesh.CgMesh;

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
 *   <li>Reused: {@link #clear} keeps its storage, so a warm frame allocates nothing.</li>
 *   <li>A row's arrays ({@link #values}, {@link #positions}) are the batches' own: read them before the next
 *       visibility of the view.</li>
 * </ul>
 */
public final class CgMeshBatches {

    /** {@link #flags} bits: the primitive's passes. */
    public static final int HALF_RESOLUTION = 1, AFTER_DISTORTION = 2, CASTS_SHADOW = 4, CUSTOM_DEPTH = 8;

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
    private int[] ints = new int[64 * INTS];
    private Instances[] instances = new Instances[64];
    private float[] distances = new float[64];
    private long[] keys = new long[64];
    /** A dynamic row's object-to-world: its translation in doubles, then a 3x3 in floats; and its record's values. */
    private double[] positions = new double[64 * 3];
    private float[] values = new float[64 * VALUES];

    private static final int INTS = 8, SUBMESH = 0, OBJECT = 1, COUNT = 2, FLAGS = 3, PRIORITY = 4, STENCIL = 5, MASK = 6, BONES = 7;
    /** Per dynamic row: the 3x3 (9), custom data (16), light (2), emission (1). */
    public static final int VALUES = 28, BASIS = 0, CUSTOM = 9, LIGHT = 25, EMISSION = 27;

    public void clear() {
        Arrays.fill(meshes, 0, size, null);
        Arrays.fill(materials, 0, size, null);
        Arrays.fill(instances, 0, size, null);
        size = 0;
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

    /** The GPU scene record's object id ({@code CgGpuScene.id}), or -1 for a dynamic or instanced row. */
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

    /** {@link #HALF_RESOLUTION}, {@link #AFTER_DISTORTION}, {@link #CASTS_SHADOW}, {@link #CUSTOM_DEPTH}. */
    public int flags(int b) {
        return ints[b * INTS + FLAGS];
    }

    public CgSortLayer layer(int b) {
        return layers[b];
    }

    public int sortPriority(int b) {
        return ints[b * INTS + PRIORITY];
    }

    /** The custom depth stencil value and its write mask, where {@link #CUSTOM_DEPTH} is set. */
    public int stencil(int b) {
        return ints[b * INTS + STENCIL];
    }

    public int stencilMask(int b) {
        return ints[b * INTS + MASK];
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
     * The gather's: a row of a primitive's settings, answering its index; {@link #object}, {@link #instances(int,
     * Instances)}, the dynamic values and {@link #distance(int, float)} are set after.
     */
    public int add(CgMesh mesh, CgMaterialInterface material, int submesh, CgSortLayer layer, int flags, int sortPriority,
                   int stencil, int stencilMask) {
        if (size == meshes.length) grow();
        int b = size++;
        meshes[b] = mesh;
        materials[b] = material;
        layers[b] = layer;
        int i = b * INTS;
        ints[i + SUBMESH] = submesh;
        ints[i + OBJECT] = -1;
        ints[i + COUNT] = 1;
        ints[i + FLAGS] = flags;
        ints[i + PRIORITY] = sortPriority;
        ints[i + STENCIL] = stencil;
        ints[i + MASK] = stencilMask;
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
        ints = Arrays.copyOf(ints, n * INTS);
        instances = Arrays.copyOf(instances, n);
        distances = Arrays.copyOf(distances, n);
        keys = Arrays.copyOf(keys, n);
        positions = Arrays.copyOf(positions, n * 3);
        values = Arrays.copyOf(values, n * VALUES);
    }
}
