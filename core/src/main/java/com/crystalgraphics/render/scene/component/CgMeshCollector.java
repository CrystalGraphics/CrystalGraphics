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
import org.joml.Matrix3f;
import org.joml.Quaternionfc;

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
    private CgMaterialInterface material;
    private int submesh;
    private double x, y, z;
    private final Matrix3f basis = new Matrix3f();
    private final float[] custom = new float[16];
    private float blockLight, skyLight, emission;
    /** The row's GPU form: its count, its records, and the buffers it reads. */
    @Nullable
    private CgBufferHandle count, records;
    private long countOffset;
    private CgIndirect countMode;
    private int countFactor, recordCapacity, buffers;
    private final CgBufferHandle[] buffer = new CgBufferHandle[CgDrawChunk.BUFFERS];
    private final CgBindingModel.Slot[] bufferAt = new CgBindingModel.Slot[CgDrawChunk.BUFFERS];

    void begin(CgMeshBatches out, CgPrimitiveComponent<?> primitive, CgCamera camera, CgSceneGather gather) {
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

    /** Begins a row: {@code mesh} drawn whole with {@code material}, at the primitive's position. */
    public CgMeshCollector mesh(CgMesh mesh, CgMaterialInterface material) {
        this.mesh = Objects.requireNonNull(mesh, "mesh");
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
        count = records = null;
        buffers = 0;
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
        this.records = Objects.requireNonNull(records, "records");
        recordCapacity = count.capacity();
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

    public CgMeshCollector customData(int slot, float x, float y, float z, float w) {
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

    /** Writes the row. */
    public void add() {
        if (mesh == null) throw new IllegalStateException("add() with no mesh(...) begun");
        int b = primitive.batch(out, mesh, material, submesh);
        double[] p = out.positions();
        p[b * 3] = x;
        p[b * 3 + 1] = y;
        p[b * 3 + 2] = z;
        out.distance(b, gather.distance(x, y, z));
        float[] v = out.values();
        int at = b * CgMeshBatches.VALUES;
        basis.get(v, at + CgMeshBatches.BASIS);
        System.arraycopy(custom, 0, v, at + CgMeshBatches.CUSTOM, 16);
        v[at + CgMeshBatches.LIGHT] = blockLight;
        v[at + CgMeshBatches.LIGHT + 1] = skyLight;
        v[at + CgMeshBatches.EMISSION] = emission;
        if (count != null) out.count(b, count, countOffset, countMode, countFactor);
        if (records != null) out.records(b, records, recordCapacity);
        for (int s = 0; s < buffers; s++) out.buffer(b, bufferAt[s], buffer[s]);
        mesh = null;
    }
}
