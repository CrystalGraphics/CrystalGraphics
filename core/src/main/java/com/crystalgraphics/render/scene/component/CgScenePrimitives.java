package com.crystalgraphics.render.scene.component;

import com.crystalgraphics.animation.CgAnimInstance;
import com.crystalgraphics.api.material.CgMaterialInterface;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgSkeletalMesh;
import com.crystalgraphics.api.mesh.CgMeshLods;
import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.gpu.resource.CgFrameRing;
import com.crystalgraphics.render.graph.CgComputePass;
import com.crystalgraphics.render.post.settings.CgPostSettings;
import com.crystalgraphics.render.post.CgPostContext;
import com.crystalgraphics.render.graph.CgScreenTexture;
import com.crystalgraphics.render.scene.CgScene;
import com.crystalgraphics.render.scene.batch.CgMeshBatches;
import com.crystalgraphics.render.scene.gpu.CgGpuScene;
import com.crystalgraphics.render.scene.renderer.feature.light.CgLightCollector;
import com.crystalgraphics.render.scene.renderer.feature.media.CgSceneMedia;
import com.crystalgraphics.render.view.CgView;
import com.crystalgraphics.shape.bounds.CgAABBc;
import org.joml.Intersectiond;
import org.joml.Matrix4f;
import org.joml.Vector2d;
import org.joml.Vector4f;

import javax.annotation.Nullable;
import java.util.ArrayList;
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
    private final List<CgDecalComponent> decals = new ArrayList<>();
    /** Its spawned lights, written into each frame's light list. */
    final List<CgLightComponent<?>> lights = new ArrayList<>();
    /** Its spawned haze, injected into each firing's air, and its height fogs, the first shown drawn. */
    final List<CgHazeComponent> hazes = new ArrayList<>();
    final List<CgHeightFogComponent> fogs = new ArrayList<>();
    /** Its spawned colour regions, by priority ascending, ties in the order spawned. */
    final List<CgColourRegion> colourRegions = new ArrayList<>();
    /** Its spawned post volumes, by priority ascending, ties in the order spawned. */
    final List<CgPostVolume> postVolumes = new ArrayList<>();
    private final Matrix4f viewProjection = new Matrix4f();
    private final Vector4f clip = new Vector4f();
    /** Its spawned scene captures, and the ring frame they last ran in. */
    final List<CgSceneCaptureComponent> captures = new ArrayList<>();
    private long capturedFrame = Long.MIN_VALUE;
    private final List<CgSkeletalMeshComponent> skeletalView = Collections.unmodifiableList(skeletal);
    private final CgSceneGather gathering = new CgSceneGather();
    private final Vector2d hit = new Vector2d();

    /** {@link #once}'s rows, and the ring frame they were added in: no view of a later frame draws them. */
    private final CgMeshBatches onceRows = new CgMeshBatches();
    private final CgMeshCollector onceOut;
    private long onceFrame = Long.MIN_VALUE;
    private final CgDynamicComponent oneFrame;
    private long frame = -1;

    /** A scene of {@code gpu}'s records whose frame {@code driver} runs; null leaves it to its owner. */
    public CgScenePrimitives(CgGpuScene gpu, @Nullable Driver driver) {
        this.gpu = Objects.requireNonNull(gpu, "gpu");
        this.driver = driver;
        this.oneFrame = new CgDynamicComponent(this, (view, out) -> out.take(onceRows));
        this.onceOut = CgMeshCollector.into(onceRows, oneFrame);
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

    /** A material projected onto what is inside a box: a {@code #domain decal} one. */
    public CgDecalComponent decal(CgMaterialInterface material) {
        return new CgDecalComponent(this, material);
    }

    public CgDynamicComponent dynamic(CgDynamicPrimitive primitive) {
        return new CgDynamicComponent(this, Objects.requireNonNull(primitive, "primitive"));
    }

    public CgPointLight pointLight() {
        return new CgPointLight(this);
    }

    public CgSpotLight spotLight() {
        return new CgSpotLight(this);
    }

    public CgHazeComponent haze() {
        return new CgHazeComponent(this);
    }

    public CgHeightFogComponent heightFog() {
        return new CgHeightFogComponent(this);
    }

    /** A colour region of this scene: a grade inside a shape where it stands. */
    public CgColourRegion colourRegion() {
        return new CgColourRegion(this);
    }

    void addColourRegion(CgColourRegion r) {
        int at = colourRegions.size();
        while (at > 0 && colourRegions.get(at - 1).priority() > r.priority()) at--;
        colourRegions.add(at, r);
    }

    void sortColourRegions() {
        colourRegions.sort((a, b) -> Integer.compare(a.priority(), b.priority()));   // stable: ties keep their order
    }

    /** Engine side, a chain's at {@code TRANSLUCENT}: every spawned colour region over {@code input}, by priority. */
    public void drawColourRegions(CgPostContext ctx, CgScreenTexture input) {
        for (int i = 0, n = colourRegions.size(); i < n; i++) colourRegions.get(i).draw(ctx, input);
    }

    /** A look over the views of this scene, everywhere or round where it stands: {@code settings} blended by weight. */
    public CgPostVolume postVolume(CgPostSettings settings) {
        return new CgPostVolume(this, settings);
    }

    void addPostVolume(CgPostVolume v) {
        int at = postVolumes.size();
        while (at > 0 && postVolumes.get(at - 1).priority() > v.priority()) at--;
        postVolumes.add(at, v);
    }

    void sortPostVolumes() {
        postVolumes.sort((a, b) -> Integer.compare(a.priority(), b.priority()));   // stable: ties keep their order
    }

    /**
     * Engine side, the post chain's at a view's opaque callback: every spawned volume blended into {@code settings} at
     * {@code camera}, by priority, a placed one's focus where it stands on screen unless its settings set one.
     */
    public void blendPostVolumes(CgCamera camera, CgPostSettings settings) {
        if (postVolumes.isEmpty()) return;
        double cx = camera.x(), cy = camera.y(), cz = camera.z();
        viewProjection.set(camera.projection()).mul(camera.view());
        for (int i = 0, n = postVolumes.size(); i < n; i++) {
            CgPostVolume v = postVolumes.get(i);
            float w = v.weightAt(cx, cy, cz);
            if (w <= 0f) continue;
            float fx = v.settings().focusX(), fy = v.settings().focusY();
            if (v.placed() && !v.settings().overrides(CgPostSettings.FOCUS)) {
                viewProjection.transform((float) (v.x() - cx), (float) (v.y() - cy), (float) (v.z() - cz), 1f, clip);
                if (clip.w > 0f) {
                    fx = clip.x / clip.w * 0.5f + 0.5f;
                    fy = clip.y / clip.w * 0.5f + 0.5f;
                }
            }
            settings.blend(v.settings(), w, fx, fy);
        }
    }

    /** Engine side, the media's: whether any spawned haze adds to the air. */
    public boolean holdsHaze() {
        for (int i = 0; i < hazes.size(); i++) if (hazes.get(i).holds()) return true;
        return false;
    }

    /** Engine side, the media's injector: every spawned haze into this firing's air; whether any added. */
    public boolean injectHaze(CgComputePass pass, CgSceneMedia media) {
        boolean added = false;
        for (int i = 0; i < hazes.size(); i++) added |= hazes.get(i).inject(pass, media);
        return added;
    }

    /** Engine side, the height fog pass's: the fog its views draw, the first spawned that shows; null for none. */
    @Nullable
    public CgHeightFogComponent shownFog() {
        for (int i = 0; i < fogs.size(); i++) if (fogs.get(i).shows()) return fogs.get(i);
        return null;
    }

    public CgTubeLight tubeLight() {
        return new CgTubeLight(this);
    }

    /** Engine side, the scene renderer's once a frame: every spawned light into {@code out}, where it stands. */
    public void writeLights(CgLightCollector out) {
        for (int i = 0; i < lights.size(); i++) lights.get(i).write(out);
    }

    /** A camera drawing {@code scene}, whose primitives these are, into a render target. */
    public CgSceneCaptureComponent capture(CgScene scene) {
        return new CgSceneCaptureComponent(this, Objects.requireNonNull(scene, "scene"));
    }

    /**
     * Engine side, before the scene's first view of a ring frame draws: every capture due draws into its target now,
     * Unreal's scene capture update ahead of the main view. Once a ring frame, so a capture drawing this scene runs no
     * captures inside itself. Render thread, outside a firing's recording.
     */
    public void updateCaptures() {
        long now = CgFrameRing.frame();
        if (now == capturedFrame) return;
        capturedFrame = now;
        for (int i = 0; i < captures.size(); i++) {
            CgSceneCaptureComponent c = captures.get(i);
            if (c.due()) c.capture();
        }
    }

    public void drawOnce(CgMesh mesh, CgMaterialInterface material, double x, double y, double z) {
        once().mesh(Objects.requireNonNull(mesh, "mesh"), Objects.requireNonNull(material, "material")).at(x, y, z).add();
    }

    /** Rows drawn in this ring frame only, by every view of it that draws after: each {@code mesh(...)...add()} is one. */
    public CgMeshCollector once() {
        long now = CgFrameRing.frame();
        if (now != onceFrame) {
            onceRows.clear();
            onceFrame = now;
        }
        return onceOut;
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
     * parents first (Unreal's attachment update after animation), then each skeletal mesh's palettes, then each decal's
     * fade, removing the decals faded out that asked.
     */
    public void update() {
        if (!attached.isEmpty()) {
            attached.sort(PARENTS_FIRST);
            for (int i = 0, n = attached.size(); i < n; i++) attached.get(i).resolveAttachment();
        }
        for (int i = 0, n = skeletal.size(); i < n; i++) skeletal.get(i).writePalettes();
        for (int i = decals.size() - 1; i >= 0; i--) decals.get(i).tick();
    }

    /** Removes every primitive and what {@link #drawOnce} queued. */
    public void clear() {
        for (int i = primitives.size() - 1; i >= 0; i--) primitives.get(i).remove();
        onceRows.clear();
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
            if (!p.visible || !view.sees(p) || !p.inReach(camera) || !gathering.inView(p)) continue;
            int first = out.size();
            p.gather(view, camera, out, gathering);
            for (int b = first, rows = out.size(); b < rows; b++) {
                if (Float.isNaN(out.distance(b))) out.distance(b, gathering.distance);
                if (Float.isNaN(out.primitiveDistance(b))) out.primitiveDistance(b, gathering.distance);
            }
        }
        if (onceFrame == CgFrameRing.frame() && onceRows.size() > 0 && !view.showsOnly()) oneFrame.gather(view, camera, out, gathering);
    }

    /**
     * The nearest visible primitive whose world box the ray from {@code (ox, oy, oz)} along {@code (dx, dy, dz)} meets
     * ahead of its origin, absolute; null for none. Godot's editor selection ray: a box hit, not a triangle. A primitive
     * with no bounds is never picked.
     */
    @Nullable
    public CgPrimitiveComponent<?> pick(double ox, double oy, double oz, double dx, double dy, double dz) {
        CgPrimitiveComponent<?> nearest = null;
        double best = Double.MAX_VALUE;
        for (int i = 0, n = primitives.size(); i < n; i++) {
            CgPrimitiveComponent<?> p = primitives.get(i);
            CgAABBc box = p.visible ? p.worldBounds() : null;
            if (box == null || !Intersectiond.intersectRayAab(ox, oy, oz, dx, dy, dz, box.minX(), box.minY(), box.minZ(),
                    box.maxX(), box.maxY(), box.maxZ(), hit)) continue;
            double at = Math.max(hit.x, 0.0);   // the eye inside a box picks it at 0
            if (hit.y >= 0.0 && at < best) {
                best = at;
                nearest = p;
            }
        }
        return nearest;
    }

    /** Primitives the last {@link #gather} tested against its view's frustum. */
    public int tested() {
        return gathering.tested;
    }

    /** Of those, the ones the frustum left out. */
    public int culled() {
        return gathering.culled;
    }

    /** A new frame: the gather's stamp. */
    void beginFrame() {
        frame = CgFrameRing.frame();
    }

    CgGpuScene gpu() {
        return gpu;
    }

    void added(CgPrimitiveComponent<?> p) {
        p.index = primitives.size();
        primitives.add(p);
        if (p instanceof CgSkeletalMeshComponent s) skeletal.add(s);
        if (p instanceof CgDecalComponent d) {
            decals.add(d);
            if (driver != null) driver.started();   // its fades run in the frame's update
        }
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
        if (p instanceof CgDecalComponent d) decals.remove(d);
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

}
