package com.crystalgraphics.render.scene;

import com.crystalgraphics.api.material.CgMaterialInterface;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshLods;
import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.render.scene.batch.CgMeshBatches;
import com.crystalgraphics.render.scene.component.CgDynamicComponent;
import com.crystalgraphics.render.scene.component.CgDynamicPrimitive;
import com.crystalgraphics.render.scene.component.CgInstancedMeshComponent;
import com.crystalgraphics.render.scene.component.CgMeshComponent;
import com.crystalgraphics.render.scene.component.CgScenePrimitives;
import com.crystalgraphics.render.scene.gpu.CgGpuScene;
import com.crystalgraphics.render.view.CgView;

/**
 * A world of primitives kept until removed, Unreal's {@code FScene}: meshes, instanced meshes and dynamic primitives,
 * each a component made here, set up and spawned. Its {@link CgGpuScene} holds every persistent primitive's record on
 * the GPU; each frame, every view that draws it {@link #gather gathers} what it sees into its mesh batches, without a
 * submit.
 *
 * <pre>{@code
 * CgScene scene = CgScene.client();                  // the client world's; cleared with the level
 * CgScene preview = new CgScene();                   // one of your own: a preview, an editor, a test
 *
 * CgMeshComponent rock = scene.mesh(rockMesh, stone).at(x, y, z).spawn();
 * CgInstancedMeshComponent grass = scene.instanced(blade, grassMaterial).spawn();
 * CgDynamicComponent trail = scene.dynamic(new Trail()).spawn();
 * scene.drawOnce(marker, debugMaterial, x, y, z);    // this frame only: Unreal's debug draw
 * }</pre>
 *
 * <ul>
 *   <li>The frame thread's: its components are made, changed and gathered there.</li>
 *   <li>A component is a handle: keep it to change or remove what it made.</li>
 * </ul>
 */
public final class CgScene {

    private static final CgScene CLIENT = new CgScene("crystalgraphics.scene.client");

    /** The client world's scene. */
    public static CgScene client() {
        return CLIENT;
    }

    private final CgGpuScene gpu;
    private final CgScenePrimitives primitives;

    public CgScene() {
        this("crystalgraphics.scene");
    }

    private CgScene(String name) {
        gpu = new CgGpuScene(name);
        primitives = new CgScenePrimitives(gpu);
    }

    /** Its records on the GPU. */
    public CgGpuScene gpu() {
        return gpu;
    }

    /** Its primitives: what spawns into it and what a view gathers. */
    public CgScenePrimitives primitives() {
        return primitives;
    }

    /** A mesh, every submesh drawn with {@code material} until {@code material(slot, ...)} says otherwise. */
    public CgMeshComponent mesh(CgMesh mesh, CgMaterialInterface material) {
        return primitives.mesh(mesh, material);
    }

    /** A mesh with levels of detail, each view drawing the level its screen size picks. */
    public CgMeshComponent mesh(CgMeshLods lods, CgMaterialInterface material) {
        return primitives.mesh(lods, material);
    }

    /** Many of one mesh, each instance its own record. */
    public CgInstancedMeshComponent instanced(CgMesh mesh, CgMaterialInterface material) {
        return primitives.instanced(mesh, material);
    }

    /** Geometry asked for per view each frame. */
    public CgDynamicComponent dynamic(CgDynamicPrimitive primitive) {
        return primitives.dynamic(primitive);
    }

    /** {@code mesh} drawn this frame only, at an absolute position: a prototype or a debug shape. */
    public void drawOnce(CgMesh mesh, CgMaterialInterface material, double x, double y, double z) {
        primitives.drawOnce(mesh, material, x, y, z);
    }

    /** What {@code view}, seen through {@code camera}, draws of it this frame, into {@code out} (cleared first). */
    public void gather(CgView view, CgCamera camera, CgMeshBatches out) {
        primitives.gather(view, camera, out);
    }

    /** Removes every primitive: the level ended. */
    public void clear() {
        primitives.clear();
    }
}
