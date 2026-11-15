package com.crystalgraphics.render.scene.renderer.feature.light;

import com.crystalgraphics.api.material.CgMaterialValues;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.compute.CgCompute;
import com.crystalgraphics.compute.CgKernel;
import com.crystalgraphics.gpu.resource.CgFallbackTextures;
import com.crystalgraphics.gpu.resource.CgReadback;
import com.crystalgraphics.render.draw.CgPassConstants;
import com.crystalgraphics.render.graph.CgBufferDesc;
import com.crystalgraphics.render.graph.CgBufferUsage;
import com.crystalgraphics.render.graph.CgComputePass;
import com.crystalgraphics.render.graph.CgDispatch;
import com.crystalgraphics.render.graph.CgGraphBuffer;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.scene.renderer.feature.media.CgSceneMedia;
import com.crystalgraphics.trace.CgGpuTrace;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;

import static com.crystalgraphics.render.scene.renderer.feature.light.CgSceneLights.CAPACITY;
import static com.crystalgraphics.render.scene.renderer.feature.light.CgSceneLights.PARENT;
import static com.crystalgraphics.render.scene.renderer.feature.light.CgSceneLights.PER_CELL;
import static com.crystalgraphics.render.scene.renderer.feature.light.CgSceneLights.SLICES;
import static com.crystalgraphics.render.scene.renderer.feature.light.CgSceneLights.TILE;

/**
 * The light grid, Unreal's (LightGridInjection.cpp): the firing's view placed (its eye, slices, rays and tiles), then
 * each cell's lights binned from the list, the lights turned into the grid's space once, a parent grid {@link
 * CgSceneLights#PARENT} cells a side culled first, then each cell from its parent's list. What every pass reading the
 * lights finds them by.
 */
final class CgLightGrid {

    /**
     * Cells the grid keeps across a target's shorter side: under {@code MIN_CELLS * TILE} pixels its cells shrink, to
     * {@link #MIN_TILE} at least. A 256x256 target cut at TILE is 4x4 cells, each listing most of the view's lights.
     */
    static final int MIN_CELLS = 16, MIN_TILE = 8;
    /**
     * The slices' near and far eye depths, blocks: Minecraft's near plane past Unreal's offset of 9.5 cm, and a far
     * plane fixed where Unreal fits it to the furthest light, since a kernel's lights are not known here.
     */
    private static final float GRID_NEAR = 0.05f + 0.095f, GRID_FAR = 256.1f, GRID_SPREAD = 4.05f;
    static final String KERNELS = "crystalgraphics:shaders/world/lights/grid.compute";
    private static final int GPU_COARSE = CgGpuTrace.name("world.lightsCoarse"), GPU_CELLS = CgGpuTrace.name("world.lightsCells");
    private static final int ZONE_BIN = CgTrace.name("world.lights.bin");
    /**
     * The cells' shared budget's most, in {@link CgSceneLights#PER_CELL}s a cell: doubled whenever a firing drops lights
     * past it. Unreal's is fixed and warns; a flash lighting every cell would otherwise blank the cells that allocate
     * last, the far slices, along tile edges.
     */
    private static final int MOST_BUDGET = 32;

    // The firing's view: its eye (absolute, and from the view's position), the bob-free perspective and the bob, the
    // eye-relative world to the view, the unit-depth rays at the target's edges, the slices' B, O and S
    final Vector3f eye = new Vector3f();
    double cameraX, cameraY, cameraZ;
    final int[] eyeBlock = new int[3];
    final float[] eyeFrac = new float[3];
    final Matrix4f toView = new Matrix4f(), perspective = new Matrix4f();
    private final Matrix4f unproject = new Matrix4f(), bob = new Matrix4f();
    private final Vector4f corner = new Vector4f();
    final float[] rays = new float[4], zParams = new float[3];
    /** How far past a spot's cone the lit air widens it: CgSceneMedia.spread's. */
    final float[] airSpread = new float[2];
    float targetW, targetH, tileU, tileV;

    // The grid as it was last binned: cells across and up, and whether this firing binned it
    int gridX, gridY;
    boolean binned;
    CgGraphBuffer cells, indices, viewed;
    private CgGraphBuffer alloc, parentCells, parentIndices;
    private CgKernel view, coarse, cell;
    private int budgetScale = 2;
    volatile long cellsDropped;
    private final CgReadback.Sink readAlloc = data -> cellsDropped = Integer.toUnsignedLong(data.getInt(0));
    private final int[] zeros = new int[4];

    /**
     * The firing's view, every firing (the sun's shadows need it with no light listed): its eye from {@code view}'s
     * position and where {@code toWorld} puts it, the slices, the perspective and the bob, the rays and the tile size.
     * The grid binned nothing yet.
     */
    void place(CgCamera camera, Matrix4fc toWorld, float targetWidth, float targetHeight) {
        // The froxels' eye (CgSceneMedia's): the view's position plus where toWorld puts the eye.
        toWorld.transformPosition(eye.set(0f, 0f, 0f));
        cameraX = camera.x() + eye.x;
        cameraY = camera.y() + eye.y;
        cameraZ = camera.z() + eye.z;
        eyeBlock[0] = (int) Math.floor(cameraX);
        eyeBlock[1] = (int) Math.floor(cameraY);
        eyeBlock[2] = (int) Math.floor(cameraZ);
        eyeFrac[0] = (float) (cameraX - eyeBlock[0]);
        eyeFrac[1] = (float) (cameraY - eyeBlock[1]);
        eyeFrac[2] = (float) (cameraZ - eyeBlock[2]);
        targetW = targetWidth;
        targetH = targetHeight;
        // GetLightGridZParams: slice = log2(z * B + O) * S, with the last slice's start at GRID_FAR.
        double o = (GRID_FAR - GRID_NEAR * Math.pow(2, (SLICES - 1) / GRID_SPREAD)) / (GRID_FAR - GRID_NEAR);
        zParams[0] = (float) ((1 - o) / GRID_NEAR);
        zParams[1] = (float) o;
        zParams[2] = GRID_SPREAD;
        // The grid lives in the bobbed view: a host folding its camera's bob into the projection (Minecraft 1.17-1.21.5)
        // keeps a pure perspective over it, so the rays and the depth come from that and the lights go through the bob.
        splitPerspective(camera.projection(), perspective, bob);
        perspective.invert(unproject);
        for (int c = 0; c < 2; c++) {
            float ndc = c == 0 ? -1f : 1f;
            unproject.transform(corner.set(ndc, ndc, 0.5f, 1f));
            float z = -corner.z / corner.w;
            rays[c * 2] = corner.x / corner.w / z;
            rays[c * 2 + 1] = corner.y / corner.w / z;
        }
        // The lights are placed from the eye, not the view's position: back to the view's, then into the view.
        toWorld.invert(toView).translate(eye);
        bob.mul(toView, toView);
        float tile = tilePixels(targetWidth, targetHeight);
        tileU = tile / targetWidth;
        tileV = tile / targetHeight;
        CgSceneMedia.spread((rays[2] - rays[0]) / targetWidth, (rays[3] - rays[1]) / targetHeight, airSpread);
        binned = false;
        gridX = gridY = 1;
    }

    /** A cell's side in pixels: {@link CgSceneLights#TILE}, smaller where that leaves under {@link #MIN_CELLS} across the shorter side. */
    static float tilePixels(float targetWidth, float targetHeight) {
        return Math.max(MIN_TILE, Math.min(TILE, (float) Math.floor(Math.min(targetWidth, targetHeight) / MIN_CELLS)));
    }

    /**
     * {@code projection} as {@code perspective × bob}: a perspective looking down -z from the origin, and the rigid
     * turn and shift a host folded in after it (Minecraft's view bobbing, carried in its projection). Without one,
     * {@code perspective} is {@code projection} and {@code bob} the identity. An RQ split of the x, y and w rows.
     */
    static void splitPerspective(Matrix4fc projection, Matrix4f perspective, Matrix4f bob) {
        // Rows as floats: this runs every firing on the render thread, which allocates nothing.
        float a0x = projection.m00(), a0y = projection.m10(), a0z = projection.m20();
        float a1x = projection.m01(), a1y = projection.m11(), a1z = projection.m21();
        float r2x = projection.m03(), r2y = projection.m13(), r2z = projection.m23();
        float k22 = (float) Math.sqrt(r2x * r2x + r2y * r2y + r2z * r2z);
        // An orthographic projection's w row is constant: no eye to turn about.
        if (k22 < 1e-6f) {
            perspective.set(projection);
            bob.identity();
            return;
        }
        r2x /= k22;
        r2y /= k22;
        r2z /= k22;
        float k12 = a1x * r2x + a1y * r2y + a1z * r2z;
        float r1x = a1x - k12 * r2x, r1y = a1y - k12 * r2y, r1z = a1z - k12 * r2z;
        float k11 = (float) Math.sqrt(r1x * r1x + r1y * r1y + r1z * r1z);
        r1x /= k11;
        r1y /= k11;
        r1z /= k11;
        float k02 = a0x * r2x + a0y * r2y + a0z * r2z, k01 = a0x * r1x + a0y * r1y + a0z * r1z;
        float r0x = a0x - k01 * r1x - k02 * r2x, r0y = a0y - k01 * r1y - k02 * r2y, r0z = a0z - k01 * r1z - k02 * r2z;
        float k00 = (float) Math.sqrt(r0x * r0x + r0y * r0y + r0z * r0z);
        r0x /= k00;
        r0y /= k00;
        r0z /= k00;
        // A flipped axis (a y-down projection) stays the perspective's, so the bob is a turn and never a mirror.
        if (r0x < 0f) {
            r0x = -r0x;
            r0y = -r0y;
            r0z = -r0z;
            k00 = -k00;
        }
        if (r1y < 0f) {
            r1x = -r1x;
            r1y = -r1y;
            r1z = -r1z;
            k11 = -k11;
            k01 = -k01;
        }
        float t2 = projection.m33() / k22, t1 = (projection.m31() - k12 * t2) / k11;
        float t0 = (projection.m30() - k01 * t1 - k02 * t2) / k00;
        // The w row is -z: the bob's third row is r2 turned round, so no bob is the identity.
        bob.set(r0x, r1x, -r2x, 0f, r0y, r1y, -r2y, 0f, r0z, r1z, -r2z, 0f, t0, t1, -t2, 1f);
        projection.mul(bob.invertAffine(perspective), perspective);
    }

    /** Its kernels, made and started once. */
    void kernels() {
        if (view != null) return;
        CgCompute kernels = CgCompute.load(KERNELS);
        view = kernels.kernel("View");
        coarse = kernels.kernel("Coarse");
        cell = kernels.kernel("Cell");
        precache();
    }

    /** Every kernel a bin dispatches; inside {@link CgKernel#ahead}, their sources on a worker. */
    static void precache() {
        CgCompute kernels = CgCompute.load(KERNELS);
        kernels.kernel("View").precache();
        kernels.kernel("Coarse").precache();
        kernels.kernel("Cell").precache();
    }

    /** Whether its kernels are built. Flags, no GL. */
    boolean ready() {
        return view != null && view.isReady() && coarse.isReady() && cell.isReady();
    }

    /** The lights turned into the view, and the cells' allocation counter: made while no light is up. */
    void allocate() {
        if (viewed != null) return;
        viewed = CgGraphBuffer.persistent("cg_world_lights.view",
                CgBufferDesc.elements(CAPACITY, 80, CgBufferUsage.STORAGE, CgBufferUsage.COPY));
        alloc = CgGraphBuffer.persistent("cg_world_lights.alloc",
                CgBufferDesc.elements(4, 4, CgBufferUsage.STORAGE, CgBufferUsage.COPY));
    }

    /** The cells' allocation from nothing, before this firing's bin. */
    void clearAlloc(CgRecording recording) {
        recording.update(alloc, 0, zeros, 0, 4);
    }

    /** The cells' shared budget this firing, in lights a cell on average. */
    int budgetPerCell() {
        return PER_CELL * budgetScale;
    }

    /**
     * Each cell's lights from {@code list}, as Unreal 5 bins them (LightGridInjection.usf), after its View kernel in
     * {@code pass}, which this ends: sized to the target, {@link #tilePixels} a cell (LightGridInjection.cpp:659-670).
     */
    void bin(CgRecording recording, CgPassConstants stage, CgComputePass pass, CgLightList list, CgTexture lightmap) {
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.WORLD, ZONE_BIN)) {
            float tile = tilePixels(targetW, targetH);
            int gx = Math.max(1, (int) Math.ceil(targetW / tile)), gy = Math.max(1, (int) Math.ceil(targetH / tile));
            gridX = gx;
            gridY = gy;
            binned = true;
            kernels();
            int px = (gx + PARENT - 1) / PARENT, py = (gy + PARENT - 1) / PARENT, pz = SLICES / PARENT;
            // Persistent, grown only: what a later recording reads back is the last firing's grid.
            if (cellsDropped > 0 && budgetScale < MOST_BUDGET) {
                budgetScale *= 2;
                cellsDropped = 0;
            }
            int n = gx * gy * SLICES, budget = n * PER_CELL * budgetScale, parentBudget = budget, parents = px * py * pz;
            if (cells == null || cells.size() < n * 16L || parentCells.size() < parents * 8L
                    || parentIndices.size() < parentBudget * 4L || indices.size() < budget * 4L) {
                if (cells != null) {
                    recording.release(cells);
                    recording.release(indices);
                    recording.release(parentCells);
                    recording.release(parentIndices);
                }
                // Four words a cell: lights.glsl's CG_LIGHT_CELL_START.
                cells = CgGraphBuffer.persistent("cg_world_lights.cells",
                        CgBufferDesc.elements(n * 4, 4, CgBufferUsage.STORAGE, CgBufferUsage.COPY));
                indices = CgGraphBuffer.persistent("cg_world_lights.indices",
                        CgBufferDesc.elements(budget, 4, CgBufferUsage.STORAGE, CgBufferUsage.COPY));
                parentCells = CgGraphBuffer.persistent("cg_world_lights.parent-cells",
                        CgBufferDesc.elements(parents * 2, 4, CgBufferUsage.STORAGE, CgBufferUsage.COPY));
                parentIndices = CgGraphBuffer.persistent("cg_world_lights.parent-indices",
                        CgBufferDesc.elements(parentBudget, 4, CgBufferUsage.STORAGE, CgBufferUsage.COPY));
            }
            grid(pass.dispatch(view, CAPACITY).bind("LIGHTS", list.list).bind("COUNT", list.count).bind("VIEW", viewed)
                    .set("_MinScreen", CgLightSettings.MIN_SCREEN.get()).set("_DayFade", CgLightSettings.DAY_FADE.get())
                    .texture("_Lightmap", lightmap != null ? lightmap : CgFallbackTextures.WHITE_1x1),
                    gx, gy, 0, 0, 0, 0, budget, parentBudget);
            pass.end();
            // A pass a level, each timed: what the levels cost is the lever.
            pass = recording.compute("world.lightsCoarse", stage).timed(GPU_COARSE);
            grid(pass.dispatchGroups(coarse, px, py, pz).bind("COUNT", list.count).bind("VIEWED", viewed).bind("ALLOC", alloc)
                    .bind("PARENT_CELLS", parentCells).bind("PARENT_INDICES", parentIndices), gx, gy, px, py, pz, PARENT,
                    budget, parentBudget);
            pass.end();
            pass = recording.compute("world.lightsCells", stage).timed(GPU_CELLS);
            grid(pass.dispatchGroups(cell, gx, gy, SLICES).bind("COUNT", list.count).bind("VIEWED", viewed).bind("ALLOC", alloc)
                    .bind("PARENT_CELLS", parentCells).bind("PARENT_INDICES", parentIndices).bind("CELLS", cells)
                    .bind("INDICES", indices), gx, gy, px, py, pz, PARENT, budget, parentBudget);
            pass.end();
            // Every firing: a surge (a flash, a burst) drops lights the frame it comes, and the budget grows for the next.
            recording.fetch(alloc, 4, 4, readAlloc);
        }
    }

    /** The grid's values onto one of its kernels' dispatches. */
    private void grid(CgDispatch dispatch, int gx, int gy, int px, int py, int pz, int factor, int budget, int parentBudget) {
        dispatch.set("_Grid", gx, gy, SLICES, CAPACITY).set("_Parent", px, py, pz, factor)
                .set("_Budget", parentBudget, budget, 0f, 0f)
                .set("_Tile", tileU, tileV, 0f, 0f)
                .set("_ZParams", zParams[0], zParams[1], zParams[2], 0f)
                .set("_Rays", rays[0], rays[1], rays[2], rays[3])
                .set("_ToView0", toView.m00(), toView.m10(), toView.m20(), toView.m30())
                .set("_ToView1", toView.m01(), toView.m11(), toView.m21(), toView.m31())
                .set("_ToView2", toView.m02(), toView.m12(), toView.m22(), toView.m32())
                .set("_AirSpread", airSpread[0], airSpread[1], 0f, 0f);
    }

    /** The grid's values, which the passes reading the grid share. */
    void values(CgMaterialValues b) {
        b.vec4("_Grid", gridX, gridY, SLICES, 0f)
                .vec4("_Tile", tileU, tileV, 0f, 0f).vec4("_ZParams", zParams[0], zParams[1], zParams[2], 0f)
                .vec4("_Rays", rays[0], rays[1], rays[2], rays[3])
                .vec4("_ToView0", toView.m00(), toView.m10(), toView.m20(), toView.m30())
                .vec4("_ToView1", toView.m01(), toView.m11(), toView.m21(), toView.m31())
                .vec4("_ToView2", toView.m02(), toView.m12(), toView.m22(), toView.m32())
                .vec4("_Depth", perspective.m22(), perspective.m32(), perspective.m23(), perspective.m33())
                .set1f("_DayFade", CgLightSettings.DAY_FADE.get());
    }

    /** The grid's values onto a kernel's dispatch, as {@link #values} writes them into a material's. */
    CgDispatch values(CgDispatch d) {
        return d.set("_Grid", gridX, gridY, SLICES, 0f).set("_Tile", tileU, tileV, 0f, 0f)
                .set("_ZParams", zParams[0], zParams[1], zParams[2], 0f).set("_Rays", rays[0], rays[1], rays[2], rays[3])
                .set("_ToView0", toView.m00(), toView.m10(), toView.m20(), toView.m30())
                .set("_ToView1", toView.m01(), toView.m11(), toView.m21(), toView.m31())
                .set("_ToView2", toView.m02(), toView.m12(), toView.m22(), toView.m32())
                .set("_DayFade", CgLightSettings.DAY_FADE.get());
    }

    /** Where the grid lay when it was last binned, for {@code env/handed/lights.glsl}; {@code live} whether it holds lights. */
    void handValues(CgMaterialValues b, boolean live) {
        b.vec4("_cg_LightGrid", gridX, gridY, SLICES, live ? 1f : 0f)
                .vec4("_cg_LightTile", tileU, tileV, 0f, 0f).vec4("_cg_LightZ", zParams[0], zParams[1], zParams[2], 0f)
                .vec4("_cg_LightRays", rays[0], rays[1], rays[2], rays[3])
                .vec4("_cg_LightView0", toView.m00(), toView.m10(), toView.m20(), toView.m30())
                .vec4("_cg_LightView1", toView.m01(), toView.m11(), toView.m21(), toView.m31())
                .vec4("_cg_LightView2", toView.m02(), toView.m12(), toView.m22(), toView.m32())
                .vec4("_cg_LightEye", eye.x, eye.y, eye.z, 0f);
    }
}
