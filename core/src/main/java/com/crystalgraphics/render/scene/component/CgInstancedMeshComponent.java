package com.crystalgraphics.render.scene.component;

import com.crystalgraphics.render.scene.gpu.CgGpuScene;
import com.crystalgraphics.render.scene.batch.CgMeshBatches;
import com.crystalgraphics.api.material.CgMaterialInterface;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.render.view.CgView;
import com.crystalgraphics.shape.bounds.CgAABB;
import org.joml.Matrix4d;
import org.joml.Quaternionfc;

import java.util.Arrays;
import java.util.Objects;

/**
 * Many of one mesh in one component, each instance its own GPU scene record: Unreal's
 * {@code UInstancedStaticMeshComponent}. An instance is an index, stable until removed; its transform and custom data
 * are its own, every other setting the component's.
 *
 * <pre>{@code
 * CgInstancedMeshComponent grass = scene.instanced(bladeMesh, grassMaterial).spawn();
 * int i = grass.add(x, y, z, rotation, 1f);       // absolute position, rotation and uniform scale
 * grass.move(i, x, y + 1, z);
 * grass.customData(i, 0, r, g, b, 1f);            // per instance
 * grass.remove(i);
 * }</pre>
 *
 * <ul>
 *   <li>Instances are added once it is spawned; {@link #add} before {@link #spawn} throws.</li>
 *   <li>The component's own position is where the instances' sort group is; its transform does not move them.</li>
 *   <li>A view draws every instance of it in one batch, none culled.</li>
 * </ul>
 */
public final class CgInstancedMeshComponent extends CgPrimitiveComponent<CgInstancedMeshComponent> {

    private final CgMesh mesh;
    private final CgMaterialInterface material;
    /** Per instance index: its GPU scene slot, or -1 once removed. */
    private int[] slots = new int[16];
    private int indices;
    private int[] freeIndices = new int[16];
    private int freeCount;
    /** The live instances' object ids, dense: rebuilt when one is added or removed. */
    private int[] ids = new int[16];
    private int live;
    private boolean idsStale;
    private final Matrix4d instance = new Matrix4d();

    CgInstancedMeshComponent(CgScenePrimitives scene, CgMesh mesh, CgMaterialInterface material) {
        super(scene);
        this.mesh = Objects.requireNonNull(mesh, "mesh");
        this.material = Objects.requireNonNull(material, "material");
    }

    /** Adds an instance at an absolute position; answers its index. */
    public int add(double x, double y, double z, Quaternionfc rotation, float scale) {
        if (!spawned()) throw new IllegalStateException("add instances once the component is spawned");
        int index;
        if (freeCount > 0) index = freeIndices[--freeCount];
        else {
            index = indices++;
            if (index == slots.length) slots = Arrays.copyOf(slots, slots.length * 2);
        }
        int slot = scene.gpu().add();
        slots[index] = slot;
        scene.gpu().transform(slot, instance.translation(x, y, z).rotate(rotation).scale(scale));
        scene.writeValues(this, slot);
        live++;
        idsStale = true;
        return index;
    }

    /** Moves instance {@code index} to an absolute position, keeping its rotation and scale. */
    public void move(int index, double x, double y, double z) {
        int slot = slotOf(index);
        scene.gpu().translate(slot, x, y, z);
    }

    /** Instance {@code index}'s whole transform, absolute. */
    public void transform(int index, Matrix4d localToWorld) {
        scene.gpu().transform(slotOf(index), localToWorld);
    }

    /** Instance {@code index}'s {@code CG_OBJECT_CUSTOM0..3}. */
    public void customData(int index, int dataSlot, float x, float y, float z, float w) {
        scene.gpu().custom(slotOf(index), dataSlot, x, y, z, w);
    }

    /** Removes instance {@code index}; the index may be handed out again. */
    public void remove(int index) {
        int slot = slotOf(index);
        scene.gpu().remove(slot);
        slots[index] = -1;
        if (freeCount == freeIndices.length) freeIndices = Arrays.copyOf(freeIndices, freeIndices.length * 2);
        freeIndices[freeCount++] = index;
        live--;
        idsStale = true;
    }

    /** Live instances. */
    public int count() {
        return live;
    }

    private int slotOf(int index) {
        if (index < 0 || index >= indices || slots[index] < 0) throw new IllegalArgumentException("instance " + index + " is not live");
        return slots[index];
    }

    @Override
    void onSpawn() {
    }

    @Override
    void onRemove() {
        for (int i = 0; i < indices; i++) if (slots[i] >= 0) scene.gpu().remove(slots[i]);
        indices = freeCount = live = 0;
        idsStale = true;
    }

    /** Its own position moves no instance. */
    @Override
    void onMoved() {
    }

    /** The component's light and emission reach every instance; custom data is each instance's own. */
    @Override
    void onLooked() {
        for (int i = 0; i < indices; i++) {
            if (slots[i] < 0) continue;
            scene.gpu().light(slots[i], blockLight, skyLight);
            scene.gpu().emission(slots[i], emission);
        }
    }

    @Override
    boolean kindBounds(CgAABB out) {
        return false;
    }

    @Override
    void gather(CgView view, CgCamera camera, CgMeshBatches out, CgSceneGather gather) {
        if (live == 0) return;
        if (idsStale) {
            if (ids.length < live) ids = new int[Integer.highestOneBit(live) << 1];
            int n = 0;
            for (int i = 0; i < indices; i++) if (slots[i] >= 0) ids[n++] = CgGpuScene.id(slots[i]);
            idsStale = false;
        }
        out.instances(batch(out, mesh, material, -1), ids, live);
    }
}
