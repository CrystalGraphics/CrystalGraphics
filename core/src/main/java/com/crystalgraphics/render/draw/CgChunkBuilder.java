package com.crystalgraphics.render.draw;

import com.crystalgraphics.gl.mesh.CgMesh;

import javax.annotation.Nullable;
import java.util.Arrays;

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

    private final CgBindingTable bindings;

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
    private float[] bounds = new float[64];
    private long[] sortKeys = new long[16];
    private boolean boundsSet;

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

    /** Starts a chunk under the root nodes. */
    public CgChunkBuilder begin() {
        return begin(0, 0, 0);
    }

    /** Starts a chunk whose draws are positioned in {@code spatial}, clipped by {@code clip}, grouped in {@code effect}. */
    public CgChunkBuilder begin(int spatial, int clip, int effect) {
        if (open) throw new IllegalStateException("begin() inside a chunk: end() the last one first");
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
        if (mesh != null && meshes == null) meshes = new CgMesh[pipelines.length];
        if (meshes != null) meshes[d] = mesh;   // also clears what a dropped draw left in this slot
        sortKeys[d] = 0;
        boundsSet = false;
        drawingFloats = pipeline.kind().floats();
        drawing = d;
        return this;
    }

    /** Reserves one zeroed record for the open draw and answers its first float in {@link #data()}. */
    public int instance() {
        if (drawing < 0) throw new IllegalStateException("instance() with no draw open: draw() first");
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
                meshes == null ? null : Arrays.copyOf(meshes, count), Arrays.copyOf(bounds, count * 4),
                Arrays.copyOf(sortKeys, count), kept);
        open = false;
        count = 0;
        Arrays.fill(records, 0);
        if (meshes != null) Arrays.fill(meshes, null);
        return chunk;
    }

    /** Discards an open chunk, if any: for an owner reusing the builder after a failure. */
    public void reset() {
        open = false;
        drawing = -1;
        count = 0;
        Arrays.fill(records, 0);
        if (meshes != null) Arrays.fill(meshes, null);
    }

    /** Keeps the open draw if it has instances; a draw without bounds covers everything. */
    private void closeDraw() {
        if (drawing < 0) return;
        if (instanceCounts[drawing] > 0) {
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

    private void grow() {
        int n = pipelines.length * 2;
        pipelines = Arrays.copyOf(pipelines, n);
        bindingIds = Arrays.copyOf(bindingIds, n);
        kinds = Arrays.copyOf(kinds, n);
        firsts = Arrays.copyOf(firsts, n);
        instanceCounts = Arrays.copyOf(instanceCounts, n);
        if (meshes != null) meshes = Arrays.copyOf(meshes, n);
        bounds = Arrays.copyOf(bounds, n * 4);
        sortKeys = Arrays.copyOf(sortKeys, n);
    }
}
