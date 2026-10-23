package com.crystalgraphics.render.world;

import com.crystalgraphics.compute.CgCompute;
import com.crystalgraphics.compute.CgKernel;
import com.crystalgraphics.render.graph.CgComputePass;

/**
 * A box of haze in the air: the world's lights glow in it and cut shafts through it where their shadows fall (docs/LIGHTS.md
 * § Lit air), as in Unreal's volumetric fog. Invisible itself: it only carries light, and dims what is transparent
 * inside it by its extinction.
 *
 * <pre>{@code
 * CgWorldHaze haze = new CgWorldHaze().box(x - 20, y, z - 20, x + 20, y + 12, z + 20).density(0.04f, 3f);
 * CgWorldRenderer.get().media().add(haze);      // once; move or thin it any frame
 * haze.density(0f, 3f);                           // none: it adds nothing, and nothing is recorded for it
 * CgWorldRenderer.get().media().remove(haze);
 * }</pre>
 *
 * <ul>
 *   <li>The box is absolute, in doubles; its edge thins over {@code edge} blocks inside it.</li>
 *   <li>A density of 0.02 to 0.05 a block is a light haze: half the light gets through 14 to 35 blocks.</li>
 * </ul>
 */
public final class CgWorldHaze implements CgWorldMedia.Injector {

    private static final String KERNELS = "crystalgraphics:shaders/env/compute/media_haze.compute";

    private double minX, minY, minZ, maxX, maxY, maxZ;
    private float density, edge = 1f;
    private CgKernel kernel;

    /** The box, absolute: any two opposite corners. */
    public CgWorldHaze box(double x0, double y0, double z0, double x1, double y1, double z1) {
        minX = Math.min(x0, x1);
        minY = Math.min(y0, y1);
        minZ = Math.min(z0, z1);
        maxX = Math.max(x0, x1);
        maxY = Math.max(y0, y1);
        maxZ = Math.max(z0, z1);
        return this;
    }

    /** Its extinction a block, and the blocks its edge thins over; 0 for none. */
    public CgWorldHaze density(float perBlock, float edgeBlocks) {
        density = Math.max(perBlock, 0f);
        edge = Math.max(edgeBlocks, 1.0e-3f);
        return this;
    }

    @Override
    public boolean inject(CgComputePass pass, CgWorldMedia media) {
        if (density <= 0f || maxX <= minX || maxY <= minY || maxZ <= minZ) return false;
        if (kernel == null) {
            kernel = CgCompute.load(KERNELS).kernel("Haze");
            kernel.prepare();
        }
        double cx = media.cameraX(), cy = media.cameraY(), cz = media.cameraZ();
        media.dispatch(pass, kernel).set("_HazeMin", (float) (minX - cx), (float) (minY - cy), (float) (minZ - cz), 0f)
                .set("_HazeMax", (float) (maxX - cx), (float) (maxY - cy), (float) (maxZ - cz), 0f)
                .set("_HazeDensity", density, edge, 0f, 0f);
        return true;
    }
}
