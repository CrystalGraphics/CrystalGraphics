package com.crystalgraphics.render.graph;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.render.draw.CgBufferHandle;
import com.crystalgraphics.render.draw.CgChunkBuilder;
import com.crystalgraphics.render.draw.CgIndirect;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.draw.CgPipeline;
import com.crystalgraphics.render.draw.CgPrimitiveTable;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import javax.annotation.Nullable;
import java.util.Arrays;
import com.crystalgraphics.render.shader.CgBindingModel;

/**
 * A mesh drawn under a material in a raster pass, made by {@link CgRasterPass#draw}: one or more instances, each an
 * object record the engine writes from what is set here. Every pass of the material's chain draws the same records.
 *
 * <pre>{@code
 * pass.draw(cube, stone).transform(model).customData(0, r, g, b, 1f);   // one instance
 *
 * CgDraw trees = pass.draw(tree, bark);                                   // many
 * for (int i = 0; i < n; i++) {
 *     if (i > 0) trees.instance();
 *     trees.transform(models[i]);
 * }
 *
 * pass.draw(cube, stone).objects(records, 0, count);                     // records a kernel wrote
 * pass.draw(cube, stone).indirect(counts, 0, CgIndirect.INSTANCES, 1).objects(records, 0, max);
 * }</pre>
 *
 * <ul>
 *   <li>Valid until the pass's next {@code draw}, {@code add} or {@code end}: one object a pass, reused.</li>
 *   <li>An instance with nothing set is the identity: the mesh where it was modelled, custom data zero.</li>
 *   <li>{@link #transform} writes the normal matrix too, as the model's inverse transpose.</li>
 *   <li>A material with no pass for meshes throws at {@code draw}.</li>
 * </ul>
 */
public final class CgDraw {

    private static final int OBJECT = CgInstanceKind.OBJECT.ordinal();
    private static final int CUSTOM = 32;

    private final CgRasterPass pass;
    private final CgChunkBuilder builder;
    private final Matrix4f normal = new Matrix4f();
    @Nullable
    private CgMesh mesh;
    /** Each pass of the material's chain that draws meshes: its pipeline and its bindings. */
    private CgPipeline[] pipelines = new CgPipeline[2];
    private int[] bindings = new int[2];
    private int links;
    /** The records written, by slot; the one being set, its first float in the table, or -1 for none yet. */
    private int[] slots = new int[8];
    private int slotCount;
    private int at = -1;
    /** What was set of the draw, replayed on every pass of the chain after the first. */
    private int submesh = -2, rangeFirst, rangeCount;
    @Nullable
    private CgBufferHandle count, objects;
    private long countOffset;
    private CgIndirect countMode;
    private int countFactor, objectsFirst, objectsCount;
    private boolean each;
    private long[] countsAt = new long[8];
    private final CgBindingModel.Slot[] bufferAt = new CgBindingModel.Slot[4];
    private final CgBufferHandle[] buffers = new CgBufferHandle[4];
    private int bufferCount;
    private long sortKey;
    @Nullable
    private String group;
    private boolean bounded;
    private float x0, y0, x1, y1;
    private boolean open;

    CgDraw(CgRasterPass pass, CgRecording recording) {
        this.pass = pass;
        this.builder = CgChunkBuilder.pooling(recording.bindings(), recording.primitives());
    }

    /** Opens a draw of {@code mesh} under every pass of {@code material}'s chain that draws meshes. */
    CgDraw begin(CgMesh mesh, CgMaterial material) {
        finish();
        links = 0;
        for (CgMaterial link = material; link != null; link = link.getNextPass()) {
            CgPipeline pipeline = link.pipeline(CgInstanceKind.OBJECT);
            if (pipeline == null) continue;
            if (links == pipelines.length) {
                pipelines = Arrays.copyOf(pipelines, links * 2);
                bindings = Arrays.copyOf(bindings, links * 2);
            }
            pipelines[links] = pipeline;
            bindings[links++] = link.captureBindings(builder.bindings());
        }
        if (links == 0) throw new IllegalArgumentException(material + " has no pass that draws a mesh");
        if (!builder.isOpen()) builder.begin();
        this.mesh = mesh;
        slotCount = 0;
        at = -1;
        submesh = -2;
        count = objects = null;
        each = false;
        bufferCount = 0;
        sortKey = 0;
        group = null;
        bounded = false;
        builder.draw(pipelines[0], bindings[0], mesh);
        open = true;
        return this;
    }

    // ── Its records ──────────────────────────────────────────────────────

    /** Where the instance's record is, written now if it is not yet. */
    private int record() {
        requireOpen();
        if (objects != null) throw new IllegalStateException("a draw of objects() writes no record");
        if (at < 0) {
            at = builder.instance();
            noteSlot(CgPrimitiveTable.slot(at));
            float[] data = builder.data();
            data[at] = data[at + 5] = data[at + 10] = data[at + 15] = 1f;
            data[at + 16] = data[at + 21] = data[at + 26] = data[at + 31] = 1f;
        }
        return at;
    }

    /** Another instance, set by what follows: the last one keeps what was set of it. */
    public CgDraw instance() {
        record();
        at = -1;
        record();
        return this;
    }

    /** The instance's model transform, and its normal matrix from it. */
    public CgDraw transform(Matrix4fc model) {
        int at = record();
        float[] data = builder.data();
        model.get(data, at);
        model.normal(normal).get(data, at + 16);
        return this;
    }

    /** The instance's {@code custom0} to {@code custom3} ({@code slot} 0 to 3), as its material's shader reads them. */
    public CgDraw customData(int slot, float x, float y, float z, float w) {
        if (slot < 0 || slot > 3) throw new IllegalArgumentException("custom data slot " + slot + ": 0 to 3");
        int at = record() + CUSTOM + slot * 4;
        float[] data = builder.data();
        data[at] = x;
        data[at + 1] = y;
        data[at + 2] = z;
        data[at + 3] = w;
        return this;
    }

    /**
     * {@code count} whole records written by the caller, read from {@code records} at float {@code from}, each
     * {@link CgPrimitiveTable#FLOATS} floats: model, normal matrix, custom data. For many instances at once.
     */
    public CgDraw records(float[] records, int from, int count) {
        requireOpen();
        if (objects != null) throw new IllegalStateException("a draw of objects() writes no record");
        if (at >= 0) at = -1;
        int first = builder.primitives().count();
        builder.instances(records, from, count);
        for (int i = 0; i < count; i++) noteSlot(first + i);
        return this;
    }

    /** Its records are {@code count} from {@code first} in {@code records}, a GPU buffer, none written here. */
    public CgDraw objects(CgBufferHandle records, int first, int count) {
        requireOpen();
        if (slotCount > 0) throw new IllegalStateException("objects() after records were written");
        builder.objects(records, first, count);
        objects = records;
        objectsFirst = first;
        objectsCount = count;
        return this;
    }

    // ── How it draws ─────────────────────────────────────────────────────

    /** Draws only {@code count} vertices or indices from {@code first} of {@code submesh} (-1: the whole mesh). */
    public CgDraw range(int submesh, int first, int count) {
        requireOpen();
        builder.range(submesh, first, count);
        this.submesh = submesh;
        rangeFirst = first;
        rangeCount = count;
        return this;
    }

    /** Draws as many as the GPU's count at {@code offset} of {@code count} says, read as {@code mode} times {@code factor}. */
    public CgDraw indirect(CgBufferHandle count, long offset, CgIndirect mode, int factor) {
        requireOpen();
        builder.indirect(count, offset, mode, factor);
        this.count = count;
        countOffset = offset;
        countMode = mode;
        countFactor = factor;
        return this;
    }

    /** Each record its own indirect command, its count at the offset {@link #countAt} gives after it. */
    public CgDraw indirectEach(CgBufferHandle count, CgIndirect mode, int factor) {
        requireOpen();
        builder.indirectEach(count, mode, factor);
        this.count = count;
        countMode = mode;
        countFactor = factor;
        each = true;
        return this;
    }

    /** The current record's count offset, in a draw of {@link #indirectEach}. */
    public CgDraw countAt(long offset) {
        int at = record();
        builder.countAt(offset);
        int k = slotCount - 1;
        if (k >= countsAt.length) countsAt = Arrays.copyOf(countsAt, Math.max(countsAt.length * 2, k + 1));
        countsAt[k] = offset;
        return this;
    }

    /** Reads {@code buffer} where the engine buffer at {@code binding} would be. */
    public CgDraw buffer(CgBindingModel.Slot binding, CgBufferHandle buffer) {
        requireOpen();
        if (bufferCount == buffers.length) throw new IllegalStateException("a draw takes " + buffers.length + " buffers");
        builder.buffer(binding, buffer);
        bufferAt[bufferCount] = binding;
        buffers[bufferCount++] = buffer;
        return this;
    }

    /** Its place among the pass's draws where they are sorted: lower first. */
    public CgDraw sortKey(long key) {
        requireOpen();
        builder.sortKey(key);
        sortKey = key;
        return this;
    }

    /** The GPU trace group it is counted under, in place of its material's path. */
    public CgDraw gpuGroup(String label) {
        requireOpen();
        builder.gpuGroup(label);
        group = label;
        return this;
    }

    /** Unions a rectangle into its bounds, in pixels of the target: what a reader of the scene's colour copies. */
    public CgDraw bounds(float x0, float y0, float x1, float y1) {
        requireOpen();
        builder.bounds(x0, y0, x1, y1);
        if (!bounded) {
            this.x0 = x0;
            this.y0 = y0;
            this.x1 = x1;
            this.y1 = y1;
            bounded = true;
        } else {
            this.x0 = Math.min(this.x0, x0);
            this.y0 = Math.min(this.y0, y0);
            this.x1 = Math.max(this.x1, x1);
            this.y1 = Math.max(this.y1, y1);
        }
        return this;
    }

    // ── Landing ──────────────────────────────────────────────────────────

    /** Ends the open draw: an instance where none was written, and the chain's other passes over the same records. */
    void finish() {
        if (!open) return;
        if (objects == null && slotCount == 0) record();
        open = false;
        for (int l = 1; l < links; l++) {
            builder.draw(pipelines[l], bindings[l], mesh);
            if (submesh != -2) builder.range(submesh, rangeFirst, rangeCount);
            if (count != null) {
                if (each) builder.indirectEach(count, countMode, countFactor);
                else builder.indirect(count, countOffset, countMode, countFactor);
            }
            for (int b = 0; b < bufferCount; b++) builder.buffer(bufferAt[b], buffers[b]);
            if (sortKey != 0) builder.sortKey(sortKey);
            if (group != null) builder.gpuGroup(group);
            if (bounded) builder.bounds(x0, y0, x1, y1);
            if (objects != null) {
                builder.objects(objects, objectsFirst, objectsCount);
                continue;
            }
            for (int k = 0; k < slotCount; k++) {
                builder.primitive(slots[k]);
                if (each) builder.countAt(countsAt[k]);
            }
        }
        mesh = null;
        Arrays.fill(pipelines, 0, links, null);
        Arrays.fill(buffers, 0, bufferCount, null);
        count = objects = null;
        group = null;
    }

    /** Lands what was drawn into the pass as a chunk. */
    void land() {
        finish();
        if (builder.isOpen()) pass.addDrawn(builder.end());
    }

    /** Forgets what was drawn and its chunks: the recording was reset. */
    void reset() {
        open = false;
        mesh = null;
        if (builder.isOpen()) builder.reset();
        builder.recycle();
    }

    private void requireOpen() {
        if (!open) throw new IllegalStateException("this draw has landed: draw again from its pass");
    }

    private void noteSlot(int slot) {
        if (slotCount == slots.length) slots = Arrays.copyOf(slots, slotCount * 2);
        slots[slotCount++] = slot;
    }
}
