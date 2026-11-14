package com.crystalgraphics.render.scene.component;

import com.crystalgraphics.render.scene.gpu.CgGpuScene;
import com.crystalgraphics.render.scene.batch.CgMeshBatches;
import com.crystalgraphics.api.material.CgMaterialInterface;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshLods;
import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.render.view.CgView;
import com.crystalgraphics.shape.bounds.CgAABB;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.IntFunction;

/**
 * A mesh placed in a scene, kept until removed: Unreal's {@code UStaticMeshComponent}. Each submesh draws with its slot's
 * material; a {@link CgMeshLods} draws the level its screen size picks, per view. Its GPU record is written when it is
 * spawned and again only when it moves or its values change, so a still mesh costs a view only its gather.
 *
 * <pre>{@code
 * CgMeshComponent ship = scene.mesh(model.mesh(), hull)
 *         .materials(slot -> materialNamed(model.material(slot)))   // a material per submesh
 *         .at(x, y, z).spawn();
 * ship.material(2, gold);                                            // Unreal's override materials
 * ship.submeshes(0, 1);                                              // draw only these
 *
 * CgMeshComponent smoke = scene.mesh(CgMeshShapes.sphereLods(), smokeMaterial).at(x, y, z).spawn();
 * }</pre>
 *
 * <ul>
 *   <li>A slot past the materials given draws with the last one given.</li>
 *   <li>A mesh edited in place ({@code CgMesh.edit}) is drawn edited; its bounds are read again when it moves.</li>
 * </ul>
 */
public final class CgMeshComponent extends CgPrimitiveComponent<CgMeshComponent> {

    @Nullable
    private final CgMesh mesh;
    @Nullable
    private final CgMeshLods lods;
    private CgMaterialInterface[] materials;
    /** The submeshes drawn, or null for every one. */
    @Nullable
    private int[] submeshes;
    private int slot = -1;

    CgMeshComponent(CgScenePrimitives scene, @Nullable CgMesh mesh, @Nullable CgMeshLods lods, CgMaterialInterface material) {
        super(scene);
        this.mesh = mesh;
        this.lods = lods;
        this.materials = new CgMaterialInterface[]{Objects.requireNonNull(material, "material")};
    }

    /** Submesh {@code slot}'s material. */
    public CgMeshComponent material(int slot, CgMaterialInterface material) {
        requireLive();
        Objects.requireNonNull(material, "material");
        if (slot < 0) throw new IndexOutOfBoundsException("material slot " + slot);
        if (slot >= materials.length) {
            CgMaterialInterface last = materials[materials.length - 1];
            int from = materials.length;
            materials = Arrays.copyOf(materials, slot + 1);
            Arrays.fill(materials, from, slot, last);
        }
        materials[slot] = material;
        return this;
    }

    /** Every submesh's material from {@code bySlot}, for as many submeshes as the finest mesh has. */
    public CgMeshComponent materials(IntFunction<CgMaterialInterface> bySlot) {
        requireLive();
        int n = Math.max(1, finest().submeshCount());
        CgMaterialInterface[] all = new CgMaterialInterface[n];
        for (int i = 0; i < n; i++) all[i] = Objects.requireNonNull(bySlot.apply(i), "material for slot " + i);
        materials = all;
        return this;
    }

    public CgMaterialInterface material(int slot) {
        return materials[Math.min(slot, materials.length - 1)];
    }

    /** Draws only these submeshes; none given draws every one. */
    public CgMeshComponent submeshes(int... which) {
        requireLive();
        submeshes = which.length == 0 ? null : which.clone();
        return this;
    }

    /** Its record's object id in its scene's GPU scene, or -1 before it is spawned. */
    public int objectId() {
        return slot < 0 ? -1 : CgGpuScene.id(slot);
    }

    private CgMesh finest() {
        return mesh != null ? mesh : lods.finest();
    }

    @Override
    void onSpawn() {
        slot = scene.gpu().add();
        scene.writeRecord(this, slot);
    }

    @Override
    void onRemove() {
        scene.gpu().remove(slot);
        slot = -1;
    }

    @Override
    void onMoved() {
        scene.gpu().transform(slot, world());
    }

    @Override
    void onLooked() {
        scene.writeValues(this, slot);
    }

    @Override
    boolean kindBounds(CgAABB out) {
        finest().bounds(out);
        return true;
    }

    @Override
    void gather(CgView view, CgCamera camera, CgMeshBatches out, CgSceneGather gather) {
        CgMesh drawn = mesh != null ? mesh : lods.pick(gather.screenHeight(this));
        if (drawn == null) return;
        int id = CgGpuScene.id(slot);
        int count = drawn.submeshCount();
        if (count <= 1) {
            out.object(batch(out, drawn, materials[0], -1), id);
            return;
        }
        if (submeshes == null) {
            for (int s = 0; s < count; s++) out.object(batch(out, drawn, material(s), s), id);
        } else {
            for (int s : submeshes) if (s < count) out.object(batch(out, drawn, material(s), s), id);
        }
    }
}
