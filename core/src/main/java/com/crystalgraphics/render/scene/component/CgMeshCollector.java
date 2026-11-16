package com.crystalgraphics.render.scene.component;

import com.crystalgraphics.compute.ops.CgGpuCount;
import com.crystalgraphics.render.draw.CgBufferHandle;
import com.crystalgraphics.render.draw.CgDrawChunk;
import com.crystalgraphics.render.draw.CgIndirect;
import com.crystalgraphics.render.scene.batch.CgMeshBatches;
import com.crystalgraphics.api.material.CgMaterialInterface;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.render.shader.CgBindingModel;
import com.crystalgraphics.render.graph.CgGraphBuffer;
import com.crystalgraphics.render.post.settings.CgPostSettings;
import com.crystalgraphics.render.scene.sort.CgSortLayer;
import org.joml.Matrix3f;
import org.joml.Matrix4fc;
import org.joml.Quaternionfc;

import com.crystalgraphics.api.mesh.CgMeshLods;
import com.crystalgraphics.shape.bounds.CgAABBc;
import javax.annotation.Nullable;
import java.util.Objects;

/**
 * Where a {@link CgDynamicPrimitive} adds this frame's meshes, each with its own transform and values: a row of the
 * view's {@link CgMeshBatches} with the primitive's settings.
 *
 * <pre>{@code
 * out.mesh(ribbon, material).at(x, y, z).rotation(q).scale(0.5f).customData(0, r, g, b, a).add();
 * out.mesh(spark, glow).at(px, py, pz).emission(4f).add();
 * }</pre>
 *
 * <p>Draws a kernel feeds are its forms, never options on every row:</p>
 *
 * <pre>{@code
 * out.indirect(CgMesh.quads(capacity), sparks)                    // how much from a count a kernel wrote
 *         .count(alive, 0, CgIndirect.INDICES, 6).buffer(PARTICLES, particles).at(x, y, z).add();
 * out.perRecord(billow, smoke)                                    // a row per record, each its own count
 *         .counts(counts, CgIndirect.INSTANCES, 1).countAt(r * 4L).at(rx, ry, rz).add();
 * out.records(shard, crystal)                                     // records of its own, from a GPU buffer
 *         .from(shards, CgGpuCount.at(alive, 0, capacity)).add();
 * }</pre>
 *
 * <ul>
 *   <li>A row is written by {@link #add}; one begun and not added is dropped by the next row's beginning.</li>
 *   <li>Unset values are the primitive's: its custom data, light and emission.</li>
 *   <li>A count is read where the row draws: its kernel is recorded before the pass, in the same recording.</li>
 * </ul>
 */
public final class CgMeshCollector {

    private CgMeshBatches out;
    private CgPrimitiveComponent<?> primitive;
    private CgCamera camera;
    private CgSceneGather gather;
    private CgMesh mesh;
    @Nullable
    private CgMeshLods lods;
    private CgMaterialInterface material;
    private int submesh;
    private double x, y, z;
    private final Matrix3f basis = new Matrix3f();
    private final float[] custom = new float[16];
    private float blockLight, skyLight, emission;
    private int flags, batchKey, subject, sortPriority, customMask, bones, firstIndex, indexCount;
    @Nullable
    private CgAABBc bounds;
    private float boundsPad;
    @Nullable
    private CgGpuCount recordCount;
    private float cullScale = Float.NaN, cullPad;
    private CgSortLayer layer;
    @Nullable
    private CgPostSettings grade;
    private double sortX = Double.NaN, sortY, sortZ;
    /** The row's GPU form: its count, its records, and the buffers it reads. */
    @Nullable
    private CgBufferHandle count, records;
    private long countOffset;
    private CgIndirect countMode;
    private int countFactor, recordCapacity, recordFirst, buffers;
    private final CgBufferHandle[] buffer = new CgBufferHandle[CgDrawChunk.BUFFERS];
    private final CgBindingModel.Slot[] bufferAt = new CgBindingModel.Slot[CgDrawChunk.BUFFERS];

    /**
     * A collector writing {@code owner}'s rows into {@code rows} rather than a view's: rows made once a frame, as Unreal
     * makes a primitive's dynamic data, which each view then {@link #take}s. Clear {@code rows} before each frame's.
     *
     * <pre>{@code
     * final CgMeshBatches rows = new CgMeshBatches();
     * final CgMeshCollector build = CgMeshCollector.into(rows, component);
     *
     * rows.clear();                                                  // once a frame
     * build.mesh(spark, glow).at(x, y, z).sortAt(ox, oy, oz).add();
     *
     * public void collect(CgView view, CgMeshCollector out) { out.take(rows); }   // each view
     * }</pre>
     */
    public static CgMeshCollector into(CgMeshBatches rows, CgDynamicComponent owner) {
        CgMeshCollector c = new CgMeshCollector();
        c.begin(rows, owner, null, null);
        return c;
    }

    /**
     * Every row of {@code rows}, made through {@link #into}, into this view: each at its distance from its eye, of its
     * level for its height in it; a row whose box is outside the view's frustum is left out.
     */
    public void take(CgMeshBatches rows) {
        for (int b = 0, n = rows.size(); b < n; b++) {
            if (!gather.rowInView(rows, b)) continue;
            CgMeshLods levels = rows.lods(b);
            CgMesh level = levels == null ? null : levels.pick(gather.rowHeight);
            if (levels != null && level == null) continue;
            int c = out.copy(rows, b);
            if (level != null) out.mesh(c, level);
            out.distance(c, gather.distance(rows.position(b, 0), rows.position(b, 1), rows.position(b, 2)));
            if (rows.hasSortPoint(b)) {
                out.primitiveDistance(c, gather.distance(rows.sortPoint(b, 0), rows.sortPoint(b, 1), rows.sortPoint(b, 2)));
            }
        }
    }

    void begin(CgMeshBatches out, CgPrimitiveComponent<?> primitive, @Nullable CgCamera camera, @Nullable CgSceneGather gather) {
        this.out = out;
        this.primitive = primitive;
        this.camera = camera;
        this.gather = gather;
        this.mesh = null;
    }

    /** The camera the view is drawn through: what a collector faces or picks a level by. */
    public CgCamera camera() {
        return camera;
    }

    /**
     * Begins a row of the level of {@code lods} for how tall it stands in each view: picked there, and dropped where it
     * is smaller than the coarsest level's height.
     */
    public CgMeshCollector mesh(CgMeshLods lods, CgMaterialInterface material) {
        mesh(lods.finest(), material);
        this.lods = lods;
        return this;
    }

    /** Begins a row: {@code mesh} drawn whole with {@code material}, at the primitive's position. */
    public CgMeshCollector mesh(CgMesh mesh, CgMaterialInterface material) {
        this.mesh = Objects.requireNonNull(mesh, "mesh");
        this.lods = null;
        this.material = Objects.requireNonNull(material, "material");
        submesh = -1;
        x = primitive.x();
        y = primitive.y();
        z = primitive.z();
        basis.identity();
        System.arraycopy(primitive.customData, 0, custom, 0, 16);
        blockLight = primitive.blockLight;
        skyLight = primitive.skyLight;
        emission = primitive.emission;
        flags = batchKey = customMask = firstIndex = 0;
        bones = indexCount = -1;
        bounds = null;
        boundsPad = 0f;
        recordCount = null;
        cullScale = Float.NaN;
        layer = primitive.layer;
        sortPriority = primitive.sortPriority;
        grade = primitive.grade();
        subject = primitive.subject();
        sortX = Double.NaN;
        count = records = null;
        buffers = 0;
        return this;
    }

    /**
     * The row's box in object space, in place of its mesh's: what each view culls it by and picks its level by. For a
     * mesh whose shader places its vertices (a quad grid, a ribbon), whose own box bounds nothing.
     *
     * <pre>{@code
     * scene.once().mesh(CgMesh.quads(1024), grid).at(x, y, z).bounds(CgAABB.of(0, 0, 0, 3.2, 3.2, 0)).add();
     * }</pre>
     */
    public CgMeshCollector bounds(CgAABBc local) {
        bounds = local;
        return this;
    }

    /** Grows the row's box by {@code pad} on every side: what bones or a vertex wave move it past its rest pose by. */
    public CgMeshCollector pad(float pad) {
        boundsPad = pad;
        return this;
    }

    /**
     * Draws {@code count} of the submesh's indices from {@code first} rather than all of them: one band of a strip, the
     * part of a buffer written so far (Unreal's element {@code FirstIndex}, {@code NumPrimitives}). A range is within a
     * submesh: on a mesh of several, name it with {@link #submesh} first, or {@code add()} refuses.
     */
    public CgMeshCollector indices(int first, int count) {
        firstIndex = first;
        indexCount = count;
        return this;
    }

    /**
     * Skins the row by the bone palette starting at {@code base} (a skinned mesh's, from {@code CgBonePalettes}): a
     * skinned mesh drawn by hand rather than as a skeletal mesh component.
     */
    public CgMeshCollector bones(int base) {
        bones = base;
        return this;
    }

    /** Begins a row whose extent a kernel wrote: give its {@link #count}. One record, drawn as the count says. */
    public CgMeshCollector indirect(CgMesh mesh, CgMaterialInterface material) {
        return mesh(mesh, material);
    }

    /** Begins one row of many sharing a count buffer, each record its own count: give {@link #counts} and {@link #countAt}. */
    public CgMeshCollector perRecord(CgMesh mesh, CgMaterialInterface material) {
        return mesh(mesh, material);
    }

    /** Begins a row of records a kernel wrote: give them with {@link #from}. Its transform is each record's own. */
    public CgMeshCollector records(CgMesh mesh, CgMaterialInterface material) {
        return mesh(mesh, material);
    }

    /** The row draws the {@code uint} at {@code offset} of {@code buffer} times {@code factor}, as {@code mode}. */
    public CgMeshCollector count(CgBufferHandle buffer, long offset, CgIndirect mode, int factor) {
        count = Objects.requireNonNull(buffer, "buffer");
        countOffset = offset;
        countMode = Objects.requireNonNull(mode, "mode");
        countFactor = factor;
        return this;
    }

    /** {@link #count}'s buffer, mode and factor, for a {@link #perRecord} row; its offset by {@link #countAt}. */
    public CgMeshCollector counts(CgBufferHandle buffer, CgIndirect mode, int factor) {
        return count(buffer, 0L, mode, factor);
    }

    /** A {@link #perRecord} row's count: the word at this byte offset of its {@link #counts} buffer. */
    public CgMeshCollector countAt(long offset) {
        if (count == null) throw new IllegalStateException("countAt(...) before counts(...)");
        countOffset = offset;
        return this;
    }

    /** A {@link #records} row's records, 48 floats each ({@code cg_Objects}), as many as {@code count} says. */
    public CgMeshCollector from(CgBufferHandle records, CgGpuCount count) {
        return from(records, 0, count);
    }

    /** As {@link #from(CgBufferHandle, CgGpuCount)}, from record {@code first} of {@code records}: a slot of a shared buffer. */
    public CgMeshCollector from(CgBufferHandle records, int first, CgGpuCount count) {
        this.records = Objects.requireNonNull(records, "records");
        recordFirst = first;
        recordCapacity = count.capacity();
        recordCount = count;
        if (count.onGpu()) count(count.buffer(), count.word() * 4L, CgIndirect.INSTANCES, 1);
        return this;
    }

    /** The row reads {@code buffer} at pass slot {@code at}, in place of the engine's: a kernel's particles. */
    public CgMeshCollector buffer(CgBindingModel.Slot at, CgBufferHandle buffer) {
        if (buffers == this.buffer.length) throw new IllegalStateException("a row reads at most " + this.buffer.length + " buffers");
        bufferAt[buffers] = Objects.requireNonNull(at, "at");
        this.buffer[buffers++] = Objects.requireNonNull(buffer, "buffer");
        return this;
    }

    public CgMeshCollector submesh(int i) {
        submesh = i;
        return this;
    }

    /** Its absolute position. */
    public CgMeshCollector at(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
        return this;
    }

    /**
     * Its local transform about its position: the basis of {@code local}, its translation added to the position. What
     * a scaled, turned or sheared mesh is placed by.
     */
    public CgMeshCollector transform(Matrix4fc local) {
        local.get3x3(basis);
        x += local.m30();
        y += local.m31();
        z += local.m32();
        return this;
    }

    public CgMeshCollector rotation(Quaternionfc q) {
        basis.rotate(q);
        return this;
    }

    public CgMeshCollector scale(float s) {
        basis.scale(s);
        return this;
    }

    public CgMeshCollector scale(float sx, float sy, float sz) {
        basis.scale(sx, sy, sz);
        return this;
    }

    /**
     * Culls a {@link #records} row's records on the GPU against each view and its depth pyramid, each placed by the
     * row's transform scaled by {@code scale} and given the custom data the row states: a particle system's meshes,
     * kept where a particle is in view. {@code pad} widens each record's box by its own reach (blocks).
     *
     * <pre>{@code
     * out.records(rock, stone).from(objects, base, CgGpuCount.at(visible, word, capacity)).culled(size, reach)
     *         .at(ox, oy, oz).customData(3, row, age, seed, presence).add();
     * }</pre>
     */
    public CgMeshCollector culled(float scale, float pad) {
        if (!(records instanceof CgGraphBuffer)) throw new IllegalStateException("culled(...) after from(...) of a graph buffer");
        cullScale = scale;
        cullPad = pad;
        return this;
    }

    /** {@code CG_OBJECT_CUSTOM0..3}: the row's own, over its primitive's; a culled row stamps the slots it states. */
    public CgMeshCollector customData(int slot, float x, float y, float z, float w) {
        customMask |= 1 << slot;
        int c = slot * 4;
        custom[c] = x;
        custom[c + 1] = y;
        custom[c + 2] = z;
        custom[c + 3] = w;
        return this;
    }

    public CgMeshCollector light(float block, float sky) {
        blockLight = block;
        skyLight = sky;
        return this;
    }

    public CgMeshCollector emission(float scale) {
        emission = scale;
        return this;
    }

    /** Draws this row at half the target's size, whatever its primitive says: a soft volume, a wide smoke. */
    public CgMeshCollector halfResolution() {
        flags |= CgMeshBatches.HALF_RESOLUTION;
        return this;
    }

    /** Draws this translucent row after the distortion pass, unbent by the hazes behind it. */
    public CgMeshCollector afterDistortion() {
        flags |= CgMeshBatches.AFTER_DISTORTION;
        return this;
    }

    /** Its sort layer, over its primitive's. */
    public CgMeshCollector layer(CgSortLayer layer) {
        this.layer = Objects.requireNonNull(layer, "layer");
        return this;
    }

    /** Its sort priority among rows of one layer and distance, over its primitive's: a higher draws later. */
    public CgMeshCollector sortPriority(int priority) {
        sortPriority = priority;
        return this;
    }

    /**
     * Sorts the row as one with every row sorting at the same point: an effect's draws at its origin, ordered among
     * themselves by their priority and batch key, as Unreal sorts a primitive's sections at its bounds' centre.
     */
    public CgMeshCollector sortAt(double x, double y, double z) {
        sortX = x;
        sortY = y;
        sortZ = z;
        return this;
    }

    /** Grades the row by {@code look} apart from the scene's, over its primitive's grade; null for the scene's look. */
    public CgMeshCollector grade(@Nullable CgPostSettings look) {
        grade = look;
        return this;
    }

    /** The impact frame subject key the row glows for, over its primitive's; 0 for none. */
    public CgMeshCollector subject(int key) {
        subject = key;
        return this;
    }

    /**
     * What orders this row among its primitive's translucent rows at one distance: a VFX renderer's draw order. Rows
     * sort by their primitive's distance first, then their sort priority, then this.
     */
    public CgMeshCollector batchKey(int key) {
        batchKey = key;
        return this;
    }

    /** Writes the row. */
    public void add() {
        if (mesh == null) throw new IllegalStateException("add() with no mesh(...) begun");
        boolean ranged = firstIndex != 0 || indexCount >= 0;
        if (ranged && submesh < 0) {
            if (mesh.submeshCount() > 1) {
                throw new IllegalStateException("indices() on a mesh of " + mesh.submeshCount() + " submeshes names none: submesh(i) first");
            }
            submesh = 0;   // a range is within a submesh, as the store draws it
        }
        int b = primitive.batch(out, mesh, material, submesh);
        double[] p = out.positions();
        p[b * 3] = x;
        p[b * 3 + 1] = y;
        p[b * 3 + 2] = z;
        out.layer(b, layer);
        out.sortPriority(b, sortPriority);
        out.grade(b, grade);
        out.subject(b, subject);
        if (!Double.isNaN(sortX)) out.sortAt(b, sortX, sortY, sortZ);
        if (gather != null) {
            out.distance(b, gather.distance(x, y, z));
            if (!Double.isNaN(sortX)) out.primitiveDistance(b, gather.distance(sortX, sortY, sortZ));
        }
        float[] v = out.values();
        int at = b * CgMeshBatches.VALUES;
        basis.get(v, at + CgMeshBatches.BASIS);
        System.arraycopy(custom, 0, v, at + CgMeshBatches.CUSTOM, 16);
        v[at + CgMeshBatches.LIGHT] = blockLight;
        v[at + CgMeshBatches.LIGHT + 1] = skyLight;
        v[at + CgMeshBatches.EMISSION] = emission;
        if (flags != 0) out.addFlags(b, flags);
        out.batchKey(b, batchKey);
        if (count != null) out.count(b, count, countOffset, countMode, countFactor);
        if (records != null) out.records(b, records, recordFirst, recordCapacity);
        out.customMask(b, customMask);
        if (!Float.isNaN(cullScale)) out.culled(b, recordCount, cullScale, cullPad);
        for (int s = 0; s < buffers; s++) out.buffer(b, bufferAt[s], buffer[s]);
        if (bones >= 0) out.bones(b, bones);
        if (ranged) out.indices(b, firstIndex, indexCount);
        if (bounds != null || boundsPad != 0f) out.bounds(b, bounds, boundsPad);
        mesh = null;
        if (lods == null) return;
        out.lods(b, lods);
        if (gather == null) return;   // made once a frame: each view picks at take
        gather.rowInView(out, b);
        CgMesh level = lods.pick(gather.rowHeight);
        if (level != null) out.mesh(b, level);
        else out.removeLast();
    }
}
