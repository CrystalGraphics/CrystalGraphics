package com.crystalgraphics.render.scene.component;

import com.crystalgraphics.api.material.CgMaterialInterface;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshLods;
import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.gpu.resource.CgFrameRing;
import com.crystalgraphics.render.scene.batch.CgMeshBatches;
import com.crystalgraphics.render.scene.gpu.CgGpuScene;
import com.crystalgraphics.render.view.CgView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * A scene's primitives: the components spawned into it, and the gather that turns them into a view's mesh batches each
 * frame (Unreal's per-view mesh collection over a scene's primitives). {@code CgScene} owns one and forwards to it.
 *
 * <pre>{@code
 * CgScenePrimitives primitives = scene.primitives();
 * primitives.gather(view, camera, batches);     // what the view draws of the scene this frame
 * int spawned = primitives.size();
 * }</pre>
 */
public final class CgScenePrimitives {

    private final CgGpuScene gpu;
    private final List<CgPrimitiveComponent<?>> primitives = new ArrayList<>();
    private final CgSceneGather gathering = new CgSceneGather();

    /** {@link #drawOnce}'s: this frame's and the next's. */
    private final OneFrame now = new OneFrame(), next = new OneFrame();
    private final CgDynamicComponent oneFrame;
    private long frame = -1;

    public CgScenePrimitives(CgGpuScene gpu) {
        this.gpu = Objects.requireNonNull(gpu, "gpu");
        this.oneFrame = new CgDynamicComponent(this, (view, out) -> now.collect(out));
    }

    public CgMeshComponent mesh(CgMesh mesh, CgMaterialInterface material) {
        return new CgMeshComponent(this, Objects.requireNonNull(mesh, "mesh"), null, material);
    }

    public CgMeshComponent mesh(CgMeshLods lods, CgMaterialInterface material) {
        return new CgMeshComponent(this, null, Objects.requireNonNull(lods, "lods"), material);
    }

    public CgInstancedMeshComponent instanced(CgMesh mesh, CgMaterialInterface material) {
        return new CgInstancedMeshComponent(this, mesh, material);
    }

    public CgDynamicComponent dynamic(CgDynamicPrimitive primitive) {
        return new CgDynamicComponent(this, Objects.requireNonNull(primitive, "primitive"));
    }

    public void drawOnce(CgMesh mesh, CgMaterialInterface material, double x, double y, double z) {
        next.add(Objects.requireNonNull(mesh, "mesh"), Objects.requireNonNull(material, "material"), x, y, z);
    }

    /** Spawned primitives. */
    public int size() {
        return primitives.size();
    }

    /** Removes every primitive and what {@link #drawOnce} queued. */
    public void clear() {
        for (int i = primitives.size() - 1; i >= 0; i--) primitives.get(i).remove();
        now.clear();
        next.clear();
    }

    /**
     * What {@code view}, seen through {@code camera}, draws this frame, into {@code out} (cleared first): every visible
     * primitive within its reach, a mesh with levels at the level its screen size picks. Not culled against the view's
     * frustum: that is the view's visibility pass, once per view.
     */
    public void gather(CgView view, CgCamera camera, CgMeshBatches out) {
        if (CgFrameRing.frame() != frame) beginFrame();
        out.clear();
        gathering.begin(camera);
        for (int i = 0, n = primitives.size(); i < n; i++) {
            CgPrimitiveComponent<?> p = primitives.get(i);
            if (!p.visible || !p.inReach(camera)) continue;
            p.gather(view, camera, out, gathering);
        }
        if (now.count > 0) oneFrame.gather(view, camera, out, gathering);
    }

    /** A new frame: what {@link #drawOnce} queued since the last is what this one draws. */
    void beginFrame() {
        frame = CgFrameRing.frame();
        now.takeFrom(next);
    }

    CgGpuScene gpu() {
        return gpu;
    }

    void added(CgPrimitiveComponent<?> p) {
        p.index = primitives.size();
        primitives.add(p);
    }

    void removed(CgPrimitiveComponent<?> p) {
        int i = p.index;
        CgPrimitiveComponent<?> last = primitives.remove(primitives.size() - 1);
        if (last != p) {
            primitives.set(i, last);
            last.index = i;
        }
        p.index = -1;
    }

    /** {@code p}'s transform and values into {@code slot}'s record. */
    void writeRecord(CgPrimitiveComponent<?> p, int slot) {
        gpu.transform(slot, p.world());
        writeValues(p, slot);
    }

    void writeValues(CgPrimitiveComponent<?> p, int slot) {
        gpu.light(slot, p.blockLight, p.skyLight);
        gpu.emission(slot, p.emission);
        float[] c = p.customData;
        for (int k = 0; k < 4; k++) gpu.custom(slot, k, c[k * 4], c[k * 4 + 1], c[k * 4 + 2], c[k * 4 + 3]);
    }

    /** One frame's {@link #drawOnce} calls. */
    private static final class OneFrame {
        CgMesh[] meshes = new CgMesh[16];
        CgMaterialInterface[] materials = new CgMaterialInterface[16];
        double[] positions = new double[48];
        int count;

        void add(CgMesh mesh, CgMaterialInterface material, double x, double y, double z) {
            if (count == meshes.length) {
                meshes = Arrays.copyOf(meshes, count * 2);
                materials = Arrays.copyOf(materials, count * 2);
                positions = Arrays.copyOf(positions, count * 6);
            }
            meshes[count] = mesh;
            materials[count] = material;
            positions[count * 3] = x;
            positions[count * 3 + 1] = y;
            positions[count * 3 + 2] = z;
            count++;
        }

        void collect(CgMeshCollector out) {
            for (int i = 0; i < count; i++) {
                out.mesh(meshes[i], materials[i]).at(positions[i * 3], positions[i * 3 + 1], positions[i * 3 + 2]).add();
            }
        }

        /** Takes {@code other}'s calls, leaving it empty; both keep their arrays. */
        void takeFrom(OneFrame other) {
            clear();
            CgMesh[] m = meshes;
            CgMaterialInterface[] mat = materials;
            double[] p = positions;
            meshes = other.meshes;
            materials = other.materials;
            positions = other.positions;
            count = other.count;
            other.meshes = m;
            other.materials = mat;
            other.positions = p;
            other.count = 0;
        }

        void clear() {
            Arrays.fill(meshes, 0, count, null);
            Arrays.fill(materials, 0, count, null);
            count = 0;
        }
    }
}
