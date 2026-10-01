package com.crystalgraphics.demo;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.gl.mesh.CgMesh;
import com.crystalgraphics.gl.mesh.CgMeshBuilder;
import com.crystalgraphics.render.world.CgWorldRenderer;
import org.joml.Matrix4f;

/**
 * Sixteen spheres, each a different effect drawn by one {@code .shader} -- physically based gold, copper, liquid
 * mercury and colour-shift paint; a soap bubble, a crystal ball, a hologram and faceted ice; plasma, a lightning
 * globe, a lava world and a supernova; a black hole bending light, a force field, a galaxy in glass and a neon circuit --
 * with glow around everything that emits. {@link CgRenderDemo} draws them into a Minecraft world; the harness's
 * {@code vfx-spheres} scene stands them on a floor under a sky.
 *
 * <pre>{@code
 * CgVfxShowcase showcase = new CgVfxShowcase();
 * // every frame, before the world stages fire:
 * showcase.submit(CgWorldRenderer.get(), x, y, z, CgFrameClock.seconds());       // the spheres, on a floor at y
 * showcase.submitStage(CgWorldRenderer.get(), x, y, z, cameraX, cameraY, cameraZ); // and the floor and sky
 * // once, when the context goes:
 * showcase.delete();
 * }</pre>
 *
 * <ul>
 *   <li>The sixteen sit on a 4x4 grid {@link #SPACING} apart, {@link #HEIGHT} above {@code y}, the first row the
 *       furthest along -z. {@code vfx_floor.shader} mirrors the grid and {@link #GLOW}: change them together.</li>
 *   <li>Every effect animates on the frame clock its shaders read; pass the same seconds here.</li>
 *   <li>Materials belong to the material registry; {@link #delete} frees only the meshes.</li>
 * </ul>
 */
public final class CgVfxShowcase {

    /** Distance between neighbouring spheres' centres, and their height above the floor point. */
    public static final float SPACING = 2.8f, HEIGHT = 1.35f;
    public static final int COUNT = 16;

    private static final String[] SHADERS = {
            "gold", "copper", "mercury", "carpaint",
            "bubble", "crystal", "hologram", "ice",
            "plasma", "storm", "lava", "supernova",
            "blackhole", "galaxy", "shield", "circuit",
    };

    /** Per sphere, the glow around it: rgb and strength, 0 for none. Mirrored by vfx_floor.shader's pools. */
    static final float[][] GLOW = {
            {0f, 0f, 0f, 0f}, {0f, 0f, 0f, 0f}, {0f, 0f, 0f, 0f}, {0f, 0f, 0f, 0f},
            {0.6f, 0.8f, 1.0f, 0.15f}, {0f, 0f, 0f, 0f}, {0.2f, 0.8f, 1.2f, 0.6f}, {0.3f, 0.6f, 1.2f, 0.35f},
            {1.2f, 0.3f, 1.4f, 1.0f}, {0.4f, 0.7f, 1.6f, 0.8f}, {1.6f, 0.4f, 0.05f, 0.9f}, {2.0f, 1.1f, 0.3f, 1.4f},
            {1.5f, 0.7f, 0.25f, 0.5f}, {0.5f, 0.4f, 1.4f, 0.5f}, {0.3f, 0.6f, 1.6f, 0.6f}, {0.2f, 0.9f, 1.6f, 0.6f},
    };

    /** How far each sphere's glow reaches, as a multiple of its radius. */
    private static final float[] GLOW_REACH = {
            1f, 1f, 1f, 1f, 1.35f, 1f, 1.5f, 1.45f, 1.75f, 1.7f, 1.6f, 2.1f, 1.7f, 1.55f, 1.5f, 1.5f,
    };

    private static final int SHIELD = 14, STORM = 9, BLACK_HOLE = 12, GALAXY = 13, SUPERNOVA = 11;
    /** The supernova's heart is a little larger than the rest, and its corona reaches this many hearts out. */
    private static final float SUPERNOVA_SIZE = 1.15f, CORONA_REACH = 3.2f;

    private CgMesh sphere;
    private CgMesh floor;
    private final CgMaterial[] materials = new CgMaterial[COUNT];
    private CgMaterial glow, corona, sky, floorMaterial;
    private double gridX = Double.NaN, gridZ = Double.NaN;
    private final Matrix4f transform = new Matrix4f();

    /** Submits the sixteen spheres and their glow, on a floor point {@code (x, y, z)}, as they are at {@code seconds}. */
    public void submit(CgWorldRenderer world, double x, double y, double z, float seconds) {
        ensureResources();
        for (int k = 0; k < COUNT; k++) {
            int row = k / 4, column = k % 4;
            double cx = x + (column - 1.5) * SPACING;
            double cz = z + (row - 1.5) * SPACING;
            double cy = y + HEIGHT + 0.08 * Math.sin(seconds * 0.9 + k * 0.7);
            spin(k, seconds);
            if (k == SUPERNOVA) {
                transform.scale(SUPERNOVA_SIZE);
                world.draw(sphere, materials[k]).at(cx, cy, cz).transform(transform).submit();
                transform.identity().scale(SUPERNOVA_SIZE * CORONA_REACH);
                float flare = 1f + 0.15f * (float) Math.sin(seconds * 2.1) + 0.08f * flicker(seconds, k);
                world.draw(sphere, corona).at(cx, cy, cz).transform(transform)
                        .custom(1, 1f / CORONA_REACH, flare, 0f, 0f).submit();
                continue;
            }
            // Every sphere knows how high above the floor it is: the metals mirror the floor from there.
            float above = (float) (cy - y);
            world.draw(sphere, materials[k]).at(cx, cy, cz).transform(transform).custom(3, above, 0f, 0f, 0f).submit();
            if (k == SHIELD) {
                // What the field protects: a small gold core turning inside it.
                transform.identity().rotateY(-seconds * 0.6f).scale(0.45f);
                world.draw(sphere, materials[0]).at(cx, cy, cz).transform(transform).custom(3, above, 0f, 0f, 0f).submit();
            }
            float strength = GLOW[k][3];
            if (strength <= 0f) continue;
            strength *= k == STORM ? 0.55f + 0.9f * flicker(seconds, k) : 0.85f + 0.15f * (float) Math.sin(seconds * 2.3 + k);
            transform.identity().scale(GLOW_REACH[k]);
            world.draw(sphere, glow).at(cx, cy, cz).transform(transform)
                    .custom(1, GLOW[k][0], GLOW[k][1], GLOW[k][2], strength)
                    .custom(2, 1f / GLOW_REACH[k], 0f, 0f, 0f).submit();
        }
    }

    /**
     * Submits the floor under the grid at {@code (x, y, z)} and the sky around the camera: for a host with no world of
     * its own, as the harness is.
     */
    public void submitStage(CgWorldRenderer world, double x, double y, double z,
                            double cameraX, double cameraY, double cameraZ) {
        ensureResources();
        if (x != gridX || z != gridZ) {
            gridX = x;
            gridZ = z;
            float gx = (float) x, gz = (float) z;
            floorMaterial.applyProperties(b -> b.vec4("_Grid", gx, gz, SPACING, HEIGHT));
        }
        world.draw(floor, floorMaterial).at(x, y, z).submit();
        transform.identity().scale(80f);
        world.draw(sphere, sky).at(cameraX, cameraY, cameraZ).transform(transform).submit();
    }

    /** Frees the meshes. Call on context teardown. */
    public void delete() {
        if (sphere != null) sphere.delete();
        if (floor != null) floor.delete();
        sphere = null;
        floor = null;
    }

    /** Sphere {@code k}'s turn at {@code seconds}, into {@link #transform}: each at its own pace, two of them tilted. */
    private void spin(int k, float seconds) {
        float turn = seconds * (0.18f + 0.06f * (k % 3));
        transform.identity();
        if (k == BLACK_HOLE) transform.rotateX(0.38f).rotateZ(0.12f);
        if (k == GALAXY) transform.rotateX(0.55f).rotateZ(-0.2f);
        transform.rotateY(turn);
    }

    /** A lightning flicker: a new level about thirteen times a second. */
    private static float flicker(float seconds, int k) {
        long tick = (long) Math.floor(seconds * 13f) * 31L + k;
        tick ^= tick << 13;
        tick ^= tick >>> 7;
        tick ^= tick << 17;
        return (tick & 0xFFFF) / 65535f;
    }

    private void ensureResources() {
        if (sphere != null) return;
        sphere = CgMesh.upload(CgMeshBuilder.uvSphere(CgVertexFormat.SPATIAL, 80, 160, 1f));
        floor = CgMesh.upload(CgMeshBuilder.plane(CgVertexFormat.SPATIAL, 1, 1, 120f, 120f));
        for (int k = 0; k < COUNT; k++) materials[k] = CgMaterial.load("crystalgraphics:shaders/demo/vfx_" + SHADERS[k] + ".shader");
        glow = CgMaterial.load("crystalgraphics:shaders/demo/vfx_glow.shader");
        corona = CgMaterial.load("crystalgraphics:shaders/demo/vfx_supernova_corona.shader");
        sky = CgMaterial.load("crystalgraphics:shaders/demo/vfx_sky.shader");
        floorMaterial = CgMaterial.newInstance("crystalgraphics:shaders/demo/vfx_floor.shader");
    }
}
