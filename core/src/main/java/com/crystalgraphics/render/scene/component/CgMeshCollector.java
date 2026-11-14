package com.crystalgraphics.render.scene.component;

import com.crystalgraphics.render.scene.batch.CgMeshBatches;
import com.crystalgraphics.api.material.CgMaterialInterface;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.camera.CgCamera;
import org.joml.Matrix3f;
import org.joml.Quaternionfc;

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
 * <ul>
 *   <li>A row is written by {@link #add}; one begun and not added is dropped by the next {@link #mesh}.</li>
 *   <li>Unset values are the primitive's: its custom data, light and emission.</li>
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
        mesh = null;
    }
}
