package com.crystalgraphics.render.scene.component;

import com.crystalgraphics.animation.CgAnimInstance;
import com.crystalgraphics.api.material.CgMaterialInterface;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgSkeletalMesh;
import com.crystalgraphics.api.mesh.CgMeshLods;
import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.gpu.resource.CgFrameRing;
import com.crystalgraphics.render.scene.batch.CgMeshBatches;
import com.crystalgraphics.render.scene.gpu.CgGpuScene;
import com.crystalgraphics.render.view.CgView;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * A scene's primitives: the components spawned into it, the update that places what rides another and poses skeletal
 * meshes' palettes, and the gather that turns them into a view's mesh batches each frame (Unreal's per-view mesh
 * collection over a scene's primitives). {@code CgScene} owns one and forwards to it.
 *
 * <pre>{@code
 * CgScenePrimitives primitives = scene.primitives();
 * primitives.update();                          // ATTACH: attachments, then skeletal palettes
 * primitives.gather(view, camera, batches);     // what the view sees of the scene: its CgViewVisibility's
 * int spawned = primitives.size();
 * }</pre>
 */
public final class CgScenePrimitives {

    /** What runs a scene's frame: the client world for the client's scene. */
    public interface Driver {
        /** A skeletal mesh spawned or a primitive attached: its frame must run from now. */
        void started();

        /** Poses {@code anim} each frame from now when {@code on}, else stops. */
        void pose(CgAnimInstance anim, boolean on);
    }

    private static final Comparator<CgPrimitiveComponent<?>> PARENTS_FIRST = Comparator.comparingInt(CgPrimitiveComponent::attachDepth);

    private final CgGpuScene gpu;
    @Nullable
    private final Driver driver;
    private final List<CgPrimitiveComponent<?>> primitives = new ArrayList<>();
    /** Every live primitive riding another, spawned or not, parents first after each {@link #update}. */
    private final List<CgPrimitiveComponent<?>> attached = new ArrayList<>();
    private final List<CgSkeletalMeshComponent> skeletal = new ArrayList<>();
    private final List<CgSkeletalMeshComponent> skeletalView = Collections.unmodifiableList(skeletal);
    private final CgSceneGather gathering = new CgSceneGather();

    /** {@link #drawOnce}'s: this frame's and the next's. */
    private final OneFrame now = new OneFrame(), next = new OneFrame();
    private final CgDynamicComponent oneFrame;
    private long frame = -1;

    /** A scene of {@code gpu}'s records whose frame {@code driver} runs; null leaves it to its owner. */
    public CgScenePrimitives(CgGpuScene gpu, @Nullable Driver driver) {
        this.gpu = Objects.requireNonNull(gpu, "gpu");
        this.driver = driver;
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

    public CgSceneRoot root() {
        return new CgSceneRoot(this);
    }

    public CgSkeletalMeshComponent skeletal(CgSkeletalMesh mesh) {
        return new CgSkeletalMeshComponent(this, Objects.requireNonNull(mesh, "mesh"));
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

    /** Its spawned skeletal mesh components, in no order: what a renderer draws skinned. */
    public List<CgSkeletalMeshComponent> skeletal() {
        return skeletalView;
    }

    /**
     * This frame's placement, in {@code ATTACH} after {@code POSE}: every attached primitive from its parent's socket,
     * parents first (Unreal's attachment update after animation), then each skeletal mesh's palettes.
     */
    public void update() {
        if (!attached.isEmpty()) {
            attached.sort(PARENTS_FIRST);
            for (int i = 0, n = attached.size(); i < n; i++) attached.get(i).resolveAttachment();
        }
        for (int i = 0, n = skeletal.size(); i < n; i++) skeletal.get(i).writePalettes();
    }

    /** Removes every primitive and what {@link #drawOnce} queued. */
    public void clear() {
        for (int i = primitives.size() - 1; i >= 0; i--) primitives.get(i).remove();
        now.clear();
        next.clear();
    }

    /**
     * What {@code view}, seen through {@code camera}, sees of the scene, into {@code out} (cleared first): every visible
     * primitive within its reach whose world box meets the view's frustum (Unreal's {@code FrustumCull}), a mesh with
     * levels at the level its screen size picks, a dynamic primitive asked only once in view, each row at its distance
     * ahead of the eye. A primitive with no bounds is never culled; an instanced one's instances are the GPU's to cull.
     * The view's {@code CgViewVisibility} runs it once a frame; a test or a tool may call it directly.
     */
    public void gather(CgView view, CgCamera camera, CgMeshBatches out) {
        if (CgFrameRing.frame() != frame) beginFrame();
        out.clear();
        gathering.begin(camera);
        for (int i = 0, n = primitives.size(); i < n; i++) {
            CgPrimitiveComponent<?> p = primitives.get(i);
            if (!p.visible || !p.inReach(camera) || !gathering.inView(p)) continue;
            int first = out.size();
            p.gather(view, camera, out, gathering);
            for (int b = first, rows = out.size(); b < rows; b++) {
                if (Float.isNaN(out.distance(b))) out.distance(b, gathering.distance);
            }
        }
        if (now.count > 0) oneFrame.gather(view, camera, out, gathering);
    }

    /** Primitives the last {@link #gather} tested against its view's frustum. */
    public int tested() {
        return gathering.tested;
    }

    /** Of those, the ones the frustum left out. */
    public int culled() {
        return gathering.culled;
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
        if (p instanceof CgSkeletalMeshComponent s) skeletal.add(s);
    }

    void attached(CgPrimitiveComponent<?> p) {
        attached.add(p);
        if (driver != null) driver.started();
    }

    void detached(CgPrimitiveComponent<?> p) {
        attached.remove(p);
    }

    /** {@code p} is gone: what rode it stands where it is. */
    void parentRemoved(CgPrimitiveComponent<?> p) {
        for (int i = attached.size() - 1; i >= 0; i--) {
            if (i < attached.size() && attached.get(i).attachParent() == p) attached.get(i).parentGone();
        }
    }

    void pose(CgAnimInstance anim, boolean on) {
        if (driver == null) return;
        if (on) driver.started();
        driver.pose(anim, on);
    }

    void removed(CgPrimitiveComponent<?> p) {
        int i = p.index;
        CgPrimitiveComponent<?> last = primitives.remove(primitives.size() - 1);
        if (last != p) {
            primitives.set(i, last);
            last.index = i;
        }
        p.index = -1;
        if (p instanceof CgSkeletalMeshComponent s) skeletal.remove(s);
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
