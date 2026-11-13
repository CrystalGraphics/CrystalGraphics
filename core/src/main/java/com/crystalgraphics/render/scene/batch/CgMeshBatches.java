package com.crystalgraphics.render.scene.batch;

import com.crystalgraphics.render.scene.sort.CgSortLayer;
import com.crystalgraphics.api.material.CgMaterialInterface;
import com.crystalgraphics.api.mesh.CgMesh;

import javax.annotation.Nullable;
import java.util.Arrays;

/**
 * What a view draws of a scene this frame: one row per mesh batch, Unreal's {@code FMeshBatch}, gathered by
 * {@code CgScene.gather} and read by the mesh passes. A persistent primitive's row names its GPU scene record
 * ({@link #objectId}); a dynamic one's carries its own transform and values, written into a frame record by the pass.
 *
 * <pre>{@code
 * CgMeshBatches batches = new CgMeshBatches();       // a view's, reused frame to frame
 * scene.gather(view, camera, batches);
 * for (int b = 0; b < batches.size(); b++) {
 *     if (batches.instances(b) != null) ...           // an instanced component: its ids, instanceCount(b) of them
 * }
 * }</pre>
 *
 * <ul>
 *   <li>Reused: {@link #clear} keeps its storage, so a warm frame allocates nothing.</li>
 *   <li>A row's arrays ({@link #instances}, {@link #model}) are the scene's and the batches' own: read them before the
 *       next gather.</li>
 * </ul>
 */
public final class CgMeshBatches {

    /** {@link #flags} bits: the primitive's passes. */
    public static final int HALF_RESOLUTION = 1, AFTER_DISTORTION = 2, CASTS_SHADOW = 4, CUSTOM_DEPTH = 8;

    private int size;
    private CgMesh[] meshes = new CgMesh[64];
    private CgMaterialInterface[] materials = new CgMaterialInterface[64];
    private CgSortLayer[] layers = new CgSortLayer[64];
    private int[] ints = new int[64 * INTS];
    private int[][] instances = new int[64][];
    /** A dynamic row's object-to-world: its translation in doubles, then a 3x3 in floats; and its record's values. */
    private double[] positions = new double[64 * 3];
    private float[] values = new float[64 * VALUES];

    private static final int INTS = 7, SUBMESH = 0, OBJECT = 1, COUNT = 2, FLAGS = 3, PRIORITY = 4, STENCIL = 5, MASK = 6;
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

    /** The GPU scene record's object id ({@link CgGpuScene#id}), or -1 for a dynamic row, whose values are its own. */
    public int objectId(int b) {
        return ints[b * INTS + OBJECT];
    }

    /** An instanced row's object ids, {@link #instanceCount} of them; null for one object. */
    @Nullable
    public int[] instances(int b) {
        return instances[b];
    }

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

    /** A dynamic row's absolute translation along {@code axis}. */
    public double position(int b, int axis) {
        return positions[b * 3 + axis];
    }

    /** A dynamic row's values: its 3x3 basis, custom data, light and emission, {@value #VALUES} floats from {@code b * VALUES}. */
    public float[] values() {
        return values;
    }

    /**
     * The gather's: a row of a primitive's settings, answering its index; {@link #object}, {@link #instances(int, int[],
     * int)} or the dynamic values are set after.
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
        instances[b] = null;
        return b;
    }

    /** The gather's: row {@code b} draws GPU scene record {@code objectId}. */
    public void object(int b, int objectId) {
        ints[b * INTS + OBJECT] = objectId;
    }

    /** The gather's: row {@code b} draws {@code count} records, {@code ids}' first. */
    public void instances(int b, int[] ids, int count) {
        instances[b] = ids;
        ints[b * INTS + COUNT] = count;
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
        positions = Arrays.copyOf(positions, n * 3);
        values = Arrays.copyOf(values, n * VALUES);
    }
}
