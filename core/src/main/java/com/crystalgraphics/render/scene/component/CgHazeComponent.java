package com.crystalgraphics.render.scene.component;

import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.compute.CgCompute;
import com.crystalgraphics.compute.CgKernel;
import com.crystalgraphics.render.graph.CgComputePass;
import com.crystalgraphics.render.scene.batch.CgMeshBatches;
import com.crystalgraphics.render.scene.renderer.feature.media.CgSceneMedia;
import com.crystalgraphics.render.view.CgView;
import com.crystalgraphics.shape.bounds.CgAABB;
import org.joml.Matrix4dc;

/**
 * A box of haze standing in a scene, Unreal's local fog volume in role: the scene's lights glow in it and cut shafts
 * through it where their shadows fall (docs/LIGHTS.md § Lit air), as in Unreal's volumetric fog. Invisible itself: it
 * only carries light, and dims what is transparent inside it.
 *
 * <pre>{@code
 * CgHazeComponent haze = scene.haze().box(x - 20, y, z - 20, x + 20, y + 12, z + 20).density(0.04f, 3f).spawn();
 * haze.density(0.01f, 3f);                     // thinned: drawn as it stands
 * haze.remove();
 * }</pre>
 *
 * <pre>{@code
 * scene.haze().at(x, y + 6, z).extent(20f, 6f, 20f).density(0.02f, 3f).attach(lantern, null).spawn();   // carried
 * }</pre>
 *
 * <ul>
 *   <li>It stands at its box's middle, {@code extent} blocks out each way; the box stays axis-aligned whatever its
 *       turn, and its scale sizes it.</li>
 *   <li>A density of 0.02 to 0.05 a block is a light haze: half the light gets through 14 to 35 blocks. Its edge thins
 *       over {@code edge} blocks inside the box.</li>
 *   <li>Hidden ({@code visible(false)}) or at density 0, it adds nothing and nothing is recorded for it.</li>
 * </ul>
 */
public final class CgHazeComponent extends CgPrimitiveComponent<CgHazeComponent> {

    private static final String KERNELS = "crystalgraphics:shaders/world/media/haze.compute";
    private static CgKernel kernel;

    private float extentX = 1f, extentY = 1f, extentZ = 1f, density, edge = 1f;

    CgHazeComponent(CgScenePrimitives scene) {
        super(scene);
    }

    /** Its kernel, for the world's collector: compiled at the first haze otherwise. */
    public static void precacheKernels() {
        CgCompute.load(KERNELS).kernel("Haze").precache();
    }

    /** Blocks its box reaches out from its middle each way, before its scale. */
    public CgHazeComponent extent(float x, float y, float z) {
        extentX = Math.max(x, 0f);
        extentY = Math.max(y, 0f);
        extentZ = Math.max(z, 0f);
        return this;
    }

    /** Placed and sized to the box between two opposite absolute corners. */
    public CgHazeComponent box(double x0, double y0, double z0, double x1, double y1, double z1) {
        at((x0 + x1) * 0.5, (y0 + y1) * 0.5, (z0 + z1) * 0.5);
        return extent((float) Math.abs(x1 - x0) * 0.5f, (float) Math.abs(y1 - y0) * 0.5f, (float) Math.abs(z1 - z0) * 0.5f);
    }

    /** Its extinction a block, and the blocks its edge thins over; 0 for none. */
    public CgHazeComponent density(float perBlock, float edgeBlocks) {
        density = Math.max(perBlock, 0f);
        edge = Math.max(edgeBlocks, 1.0e-3f);
        return this;
    }

    public float density() {
        return density;
    }

    // ── Engine side ───────────────────────────────────────────────────────────────────────────────

    /** Whether it adds anything: shown, thick and of some size. */
    boolean holds() {
        return visible && density > 0f && extentX > 0f && extentY > 0f && extentZ > 0f;
    }

    /** Its box into the media's grid, placed from the firing's eye. */
    boolean inject(CgComputePass pass, CgSceneMedia media) {
        if (!holds()) return false;
        if (kernel == null) {
            kernel = CgCompute.load(KERNELS).kernel("Haze");
            kernel.precache();
        }
        Matrix4dc m = worldTransform();
        double sx = Math.sqrt(m.m00() * m.m00() + m.m01() * m.m01() + m.m02() * m.m02());
        double sy = Math.sqrt(m.m10() * m.m10() + m.m11() * m.m11() + m.m12() * m.m12());
        double sz = Math.sqrt(m.m20() * m.m20() + m.m21() * m.m21() + m.m22() * m.m22());
        double cx = m.m30() - media.cameraX(), cy = m.m31() - media.cameraY(), cz = m.m32() - media.cameraZ();
        float hx = (float) (extentX * sx), hy = (float) (extentY * sy), hz = (float) (extentZ * sz);
        media.dispatch(pass, kernel).set("_HazeMin", (float) cx - hx, (float) cy - hy, (float) cz - hz, 0f)
                .set("_HazeMax", (float) cx + hx, (float) cy + hy, (float) cz + hz, 0f)
                .set("_HazeDensity", density, edge, 0f, 0f);
        return true;
    }

    @Override
    void onSpawn() {
        scene.hazes.add(this);
    }

    @Override
    void onRemove() {
        scene.hazes.remove(this);
    }

    @Override
    void onMoved() {
    }

    @Override
    void onLooked() {
    }

    @Override
    boolean kindBounds(CgAABB out) {
        return false;
    }

    @Override
    void gather(CgView view, CgCamera camera, CgMeshBatches out, CgSceneGather gather) {
    }

    @Override
    public String toString() {
        return "CgHazeComponent";
    }
}
