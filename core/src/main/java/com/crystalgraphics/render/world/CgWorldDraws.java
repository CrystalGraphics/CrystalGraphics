package com.crystalgraphics.render.world;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshLods;
import com.crystalgraphics.compute.ops.CgGpuCount;
import com.crystalgraphics.render.draw.CgBufferHandle;
import com.crystalgraphics.render.draw.CgDrawChunk;
import com.crystalgraphics.render.draw.CgIndirect;
import com.crystalgraphics.render.graph.CgGraphBuffer;
import com.crystalgraphics.render.post.volume.CgPostSettings;

import java.util.Arrays;
import com.crystalgraphics.render.shader.CgBindingModel;
import com.crystalgraphics.api.material.CgMaterialInterface;

/**
 * The frame's world draws, flat: what {@link CgWorldRenderer.Draw#submit} wrote, one index a draw, read by every pass
 * of every firing that frame. Unreal's mesh batches a view gathers (FMeshBatch), as parallel arrays so a steady frame
 * allocates nothing. Render thread.
 *
 * <pre>{@code
 * for (int i = 0; i < draws.count; i++) {
 *     if (draws.queues[i] >= CgRenderQueue.TRANSPARENT_THRESHOLD) continue;
 *     use(draws.meshes[i], draws.materials[i], draws.positions, i * 3);
 * }
 * }</pre>
 *
 * <p>Arrays are replaced when they grow: read them through this object, never keep one.</p>
 */
final class CgWorldDraws {

    /** APART: separate translucency under the HDR scene, drawn into {@link CgSeparateTranslucency} rather than the scene. */
    static final byte SKIP = -1, FORWARD = 0, FORWARD_AND_PREPASS = 1, HALF = 2, AFTER = 3, VOLUME = 4, APART = 5;

    int count;
    CgMesh[] meshes = new CgMesh[64];
    CgMeshLods[] lods = new CgMeshLods[64];
    /** Per draw: submesh, first, count of its mesh; its stated bounds (6) and whether set; its padding. */
    int[] ranges = new int[64 * 3];
    float[] drawBounds = new float[64 * 6];
    boolean[] boundsStated = new boolean[64];
    float[] pads = new float[64];
    /** Per draw: the buffer an indirect draw's count is in, else null; the count's byte offset, mode and factor. */
    CgBufferHandle[] counts = new CgBufferHandle[64];
    long[] countOffsets = new long[64];
    CgIndirect[] countModes = new CgIndirect[64];
    int[] countFactors = new int[64];
    /** Per draw, two slots: the buffers it reads in place of engine buffers, and where; else null. */
    CgBufferHandle[] buffers = new CgBufferHandle[64 * CgDrawChunk.BUFFERS];
    CgBindingModel.Slot[] bufferAt = new CgBindingModel.Slot[64 * CgDrawChunk.BUFFERS];
    /** Per draw: the object records a set of instances draws, else null, and how many of them. */
    CgGraphBuffer[] sets = new CgGraphBuffer[64];
    int[] setFirsts = new int[64];
    /** Per draw: the customs it stated, a bit each, which a set's cull stamps; and a set's instance scale. */
    int[] customsStated = new int[64];
    float[] setScales = new float[64];
    CgGpuCount[] setCounts = new CgGpuCount[64];
    CgMaterialInterface[] materials = new CgMaterialInterface[64];
    double[] positions = new double[64 * 3];
    float[] transforms = new float[64 * 16];
    float[] customs = new float[64 * 16];
    /** Per draw: block and sky light, 0 to 15. */
    float[] lights = new float[64 * 2];
    /** Per draw: its emission scale, CG_OBJECT_EMISSION. */
    float[] emissions = new float[64];
    /** Per draw: where its palette starts in the frame's bones, -1 for an unskinned draw. */
    int[] boneBases = new int[64];
    /** Per draw: whether it asked to be drawn at half the target's size. */
    boolean[] halves = new boolean[64];
    /** Per draw: whether its count holds only what a GPU cull kept, so no CPU bounds test runs. */
    boolean[] culledOnGpu = new boolean[64];
    /** Per draw: the GPU group it is charged to, null for its material's. */
    String[] gpuGroups = new String[64];
    /** Per draw: the impact frame subject it belongs to ({@link CgWorldRenderer.Draw#subject}), 0 for none. */
    int[] subjects = new int[64];
    CgPostSettings[] grades = new CgPostSettings[64];
    /** Per draw: drawn into CustomDepth, its stencil value and its write mask << 8; -1 for not. */
    int[] customDepths = new int[64];
    int[] queues = new int[64];
    int[] orders = new int[64];
    int[] batchKeys = new int[64];
    CgSortLayer[] layers = new CgSortLayer[64];
    /** Per draw: whether it is in a group, and the group's absolute position (3). */
    boolean[] grouped = new boolean[64];
    double[] groupPositions = new double[64 * 3];
    /** Per draw of {@code indirectEach}: its first record in the record arrays and how many; 0 for none. */
    int[] recordFirsts = new int[64];
    int[] recordCounts = new int[64];
    /** Every such draw's records: absolute position (3), customs (16), its count's byte offset. */
    int records;
    double[] recordPositions = new double[64 * 3];
    float[] recordCustoms = new float[64 * 16];
    long[] recordOffsets = new long[64];
    /**
     * Per draw, a set's cull this stage: the records and level counts it wrote, where its own start in them, and its
     * handle. Every set one cull pass culls shares one records buffer and one counts buffer, so their draws join.
     */
    CgGraphBuffer[] culled = new CgGraphBuffer[64], culledCounts = new CgGraphBuffer[64];
    int[] culledFirst = new int[64], culledWord = new int[64], cullHandles = new int[64];
    /** Per draw this stage: its sort key, its phase, its box on screen in target pixels from the top left (NaN first for none). */
    long[] keys = new long[64];
    byte[] phase = new byte[64];
    float[] screens = new float[64 * 4];

    /** Room for one more draw at {@link #count}. */
    void reserve() {
        if (count == meshes.length) grow();
    }

    /** Room for {@code more} records past {@link #records}. */
    void reserveRecords(int more) {
        int n = records + more;
        if (n <= recordOffsets.length) return;
        int size = Math.max(n, recordOffsets.length * 2);
        recordPositions = Arrays.copyOf(recordPositions, size * 3);
        recordCustoms = Arrays.copyOf(recordCustoms, size * 16);
        recordOffsets = Arrays.copyOf(recordOffsets, size);
    }

    /** Forgets every draw, and the references they held. */
    void clear() {
        Arrays.fill(meshes, 0, count, null);
        Arrays.fill(lods, 0, count, null);
        Arrays.fill(materials, 0, count, null);
        Arrays.fill(counts, 0, count, null);
        Arrays.fill(layers, 0, count, null);
        Arrays.fill(sets, 0, count, null);
        Arrays.fill(setCounts, 0, count, null);
        Arrays.fill(buffers, 0, count * CgDrawChunk.BUFFERS, null);
        Arrays.fill(bufferAt, 0, count * CgDrawChunk.BUFFERS, null);
        Arrays.fill(culled, 0, count, null);
        Arrays.fill(culledCounts, 0, count, null);
        count = 0;
        records = 0;
    }

    private void grow() {
        int n = meshes.length * 2;
        meshes = Arrays.copyOf(meshes, n);
        lods = Arrays.copyOf(lods, n);
        materials = Arrays.copyOf(materials, n);
        positions = Arrays.copyOf(positions, n * 3);
        transforms = Arrays.copyOf(transforms, n * 16);
        customs = Arrays.copyOf(customs, n * 16);
        lights = Arrays.copyOf(lights, n * 2);
        emissions = Arrays.copyOf(emissions, n);
        boneBases = Arrays.copyOf(boneBases, n);
        halves = Arrays.copyOf(halves, n);
        culledOnGpu = Arrays.copyOf(culledOnGpu, n);
        gpuGroups = Arrays.copyOf(gpuGroups, n);
        subjects = Arrays.copyOf(subjects, n);
        grades = Arrays.copyOf(grades, n);
        customDepths = Arrays.copyOf(customDepths, n);
        queues = Arrays.copyOf(queues, n);
        orders = Arrays.copyOf(orders, n);
        batchKeys = Arrays.copyOf(batchKeys, n);
        recordFirsts = Arrays.copyOf(recordFirsts, n);
        recordCounts = Arrays.copyOf(recordCounts, n);
        layers = Arrays.copyOf(layers, n);
        grouped = Arrays.copyOf(grouped, n);
        groupPositions = Arrays.copyOf(groupPositions, n * 3);
        ranges = Arrays.copyOf(ranges, n * 3);
        drawBounds = Arrays.copyOf(drawBounds, n * 6);
        boundsStated = Arrays.copyOf(boundsStated, n);
        pads = Arrays.copyOf(pads, n);
        counts = Arrays.copyOf(counts, n);
        countOffsets = Arrays.copyOf(countOffsets, n);
        countModes = Arrays.copyOf(countModes, n);
        countFactors = Arrays.copyOf(countFactors, n);
        sets = Arrays.copyOf(sets, n);
        setFirsts = Arrays.copyOf(setFirsts, n);
        customsStated = Arrays.copyOf(customsStated, n);
        setScales = Arrays.copyOf(setScales, n);
        setCounts = Arrays.copyOf(setCounts, n);
        buffers = Arrays.copyOf(buffers, n * CgDrawChunk.BUFFERS);
        bufferAt = Arrays.copyOf(bufferAt, n * CgDrawChunk.BUFFERS);
        culled = Arrays.copyOf(culled, n);
        culledCounts = Arrays.copyOf(culledCounts, n);
        culledFirst = Arrays.copyOf(culledFirst, n);
        culledWord = Arrays.copyOf(culledWord, n);
        cullHandles = Arrays.copyOf(cullHandles, n);
        keys = Arrays.copyOf(keys, n);
        phase = Arrays.copyOf(phase, n);
        screens = Arrays.copyOf(screens, n * 4);
    }
}
