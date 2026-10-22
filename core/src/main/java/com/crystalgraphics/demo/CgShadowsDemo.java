package com.crystalgraphics.demo;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshShapes;
import com.crystalgraphics.collision.debug.CgCollisionDebug;
import com.crystalgraphics.render.world.CgWorldLights;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.shape.CgPose;
import com.crystalgraphics.shape.primitive.CgSphere;
import com.crystalgraphics.variable.CgVar;
import com.crystalgraphics.variable.CgVars;
import org.joml.Matrix4f;

/**
 * The world's shadows round a point, each kind of caster on each kind of receiver: a plaza of ours that receives from
 * everything (our shapes, a lamp of ours circling over it, the sun, the player, mobs, and Minecraft's blocks at its
 * edge), and two stations on Minecraft's own ground that receive from our objects alone (a floating cube under the sun,
 * a pillar beside a low lamp and beside any torch the player places there).
 *
 * <pre>
 *   floating cube        plaza (ours)          pillar + low lamp
 *   (sun on MC ground)   cube, ball, pillar,   (our lamp and a torch
 *                        floating cube, lamp    on MC ground)
 * </pre>
 *
 * <pre>{@code
 * CgShadowsDemo demo = new CgShadowsDemo();
 * demo.prepare();                                         // then nothing until demo.warmed()
 * demo.submit(CgWorldRenderer.get(), x, y, z, seconds);   // each frame, before the world records
 * CgShadowsDemo.toggleShadows();                          // every world shadow off and on, to compare
 * }</pre>
 *
 * <p>{@code CgRenderDemo}'s {@code shadows}.</p>
 */
public final class CgShadowsDemo {

    private static final String MATERIAL = "crystalgraphics:shaders/demo_render.shader";
    private static final String SHADOWS = "crystalgraphics.world.lightsShadows";
    private static final CgSphere MARK = CgSphere.of(0.15);
    /** The plaza's half width, and the stations' distance from its middle. */
    private static final double PLAZA = 7, STATION = 12;

    private final Matrix4f transform = new Matrix4f();
    private final CgPose pose = new CgPose();
    private CgMaterial material;

    /** Every shadow off, or back on: the lamps, the sun and Minecraft's lights alike. */
    public static void toggleShadows() {
        CgVar.Bool shadows = (CgVar.Bool) CgVars.get(SHADOWS);
        shadows.set(!shadows.get());
    }

    public static boolean shadowsOn() {
        return ((CgVar.Bool) CgVars.get(SHADOWS)).get();
    }

    public void prepare() {
        if (material == null) material = CgMaterial.load(MATERIAL);
        CgWorldRenderer.get().prepare(material);
    }

    public boolean warmed() {
        return material != null && CgWorldRenderer.get().prepare(material);
    }

    /** The scene round a ground point {@code (x, y, z)} as it is at {@code seconds}. */
    public void submit(CgWorldRenderer world, double x, double y, double z, double seconds) {
        if (material == null) return;
        float t = (float) (seconds % 3600.0);
        CgMesh cube = CgMeshShapes.cube(), ball = CgMeshShapes.sphere(24, 32);
        CgWorldLights lights = world.lights();

        // The plaza, a slab of ours laid on the ground, and what stands on it.
        box(world, cube, x, y + 0.125, z, PLAZA * 2, 0.25, PLAZA * 2, 0.82f, 0.82f, 0.8f);
        double top = y + 0.25;
        box(world, cube, x - 3, top + 0.75, z - 2, 1.5, 1.5, 1.5, 0.85f, 0.25f, 0.2f);
        box(world, cube, x, top + 2, z + 2.5, 1, 4, 1, 0.25f, 0.4f, 0.9f);
        box(world, cube, x + 3.5, top + 3 + 0.4 * Math.sin(t * 0.8), z + 1.5, 1.2, 1.2, 1.2, 0.95f, 0.8f, 0.2f);
        world.draw(ball, material).at(x + 2, top + 0.8, z - 3).transform(transform.scaling(1.6f))
                .custom(0, 0.3f, 0.8f, 0.35f, 1f).submit();
        // A warm lamp circling over it, casting every shadow: our shapes, the player, mobs and blocks onto the plaza.
        double a = t * 0.35;
        double lx = x + Math.cos(a) * 4, ly = top + 5, lz = z + Math.sin(a) * 4;
        CgCollisionDebug.shape(MARK, pose.at(lx, ly, lz), 0xFFFFE0B0);
        lights.add(lx, ly, lz, 16f, 0xFFFFE0B0, lit(2.5f, 5f), 0f).source(0.15f);

        // West: a cube floating over Minecraft's ground, for the sun's shadow of our objects on it.
        box(world, cube, x - STATION - 2, y + 3, z, 2, 2, 2, 0.9f, 0.9f, 0.9f);

        // East: a pillar on Minecraft's ground, a low lamp north of it throwing its shadow south; a torch placed beside
        // it shows Minecraft's block light withheld the same way.
        box(world, cube, x + STATION + 2, y + 1.5, z, 1, 3, 1, 0.6f, 0.6f, 0.65f);
        double px = x + STATION + 2, pz = z - 3;
        CgCollisionDebug.shape(MARK, pose.at(px, y + 1.2, pz), 0xFFB0D0FF);
        lights.add(px, y + 1.2, pz, 12f, 0xFFB0D0FF, lit(2.5f, 3f), 0f).source(0.1f);
    }

    /** An intensity lighting a surface it faces {@code d} blocks off to {@code peak} times its colour, as Unreal's simple light. */
    private static float lit(float peak, float d) {
        return peak * (float) Math.PI * d * d;
    }

    private void box(CgWorldRenderer world, CgMesh cube, double x, double y, double z, double w, double h, double d,
                     float r, float g, float b) {
        world.draw(cube, material).at(x, y, z).transform(transform.scaling((float) w, (float) h, (float) d))
                .custom(0, r, g, b, 1f).submit();
    }

    /** What the HUD says under the scene's line. */
    public static String hud() {
        return "\nShadows [O]: " + (shadowsOn() ? "on" : "off")
                + " | east pillar: place a torch beside it; west cube: by day";
    }
}
