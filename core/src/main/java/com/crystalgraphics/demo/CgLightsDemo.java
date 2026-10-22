package com.crystalgraphics.demo;

import com.crystalgraphics.api.material.CgBlendMode;
import com.crystalgraphics.api.material.CgMaterialDefinition;
import com.crystalgraphics.api.material.CgMaterialUsage;
import com.crystalgraphics.collision.debug.CgCollisionDebug;
import com.crystalgraphics.easing.CgEasings;
import com.crystalgraphics.easing.CgKeyframes;
import com.crystalgraphics.render.world.CgWorldLights;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.shape.CgPose;
import com.crystalgraphics.shape.primitive.CgBox;
import com.crystalgraphics.shape.primitive.CgSphere;
import com.crystalgraphics.vfx.CgVfxOwner;
import com.crystalgraphics.vfx.look.CgVfxLook;
import com.crystalgraphics.vfx.look.CgVfxSchema;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.gpu.CgVfxDrawnChannel;
import com.crystalgraphics.vfx.particle.renderer.CgVfxRenderers;
import com.crystalgraphics.vfx.particle.spawn.CgVfxEmitterState;
import com.crystalgraphics.vfx.system.CgVfxSystemInstance;

/**
 * Every kind of world light ({@link CgWorldLights}) on the ground round a point, each station moving so its falloff is
 * seen sweeping over the blocks: best at night. Each light's source is a small sphere of debug lines; a spot's cone and
 * a tube's line are drawn too.
 *
 * <pre>
 * west to east, front row:
 *   point     inverse-squared, orange, circling
 *   point     exponent 8, cyan, pulsing
 *   spot      a lamp high up, pointing down, sweeping a circle
 *   spot      a lighthouse at head height, turning
 *   tube      a magenta neon bar lying along the ground
 * back row:
 *   tube      a blue saber swinging upright
 *   embers    particles rising from the ground, each a light (Cascade's Light module, half of them)
 * </pre>
 *
 * <pre>{@code
 * CgLightsDemo demo = new CgLightsDemo(CgVfx.owner("crystalgraphics"));
 * demo.prepare();                                         // then nothing until demo.warmed()
 * demo.submit(CgWorldRenderer.get(), x, y, z, seconds);   // each frame, before the world records
 * }</pre>
 *
 * <p>{@code CgRenderDemo}'s {@code lights}.</p>
 */
public final class CgLightsDemo {

    private static final CgVfxSchema SCHEMA = CgVfxSchema.of("crystalgraphics:demo/lights");
    private static final int ORANGE = 0xFFFF8A30, CYAN = 0xFF40E0FF, WHITE = 0xFFFFF2D8, YELLOW = 0xFFFFD060,
            MAGENTA = 0xFFFF40C0, BLUE = 0xFF4080FF;
    private static final CgSphere SOURCE = CgSphere.of(0.15);

    private final CgVfxOwner vfx;
    private final CgVfxLook embers = CgVfxLook.builder(SCHEMA).emitter(embers()).build();
    private final CgPose pose = new CgPose();
    private CgVfxSystemInstance<?> playing;
    private double playingX = Double.NaN, playingY, playingZ;

    public CgLightsDemo(CgVfxOwner vfx) {
        this.vfx = vfx;
    }

    /** Embers drifting up off a patch of ground, each glowing and half of them lighting it. */
    private static CgVfxEmitter embers() {
        return CgVfxEmitter.builder("embers").capacity(256).state(CgVfxEmitterState.infinite(1f)).rate(30f)
                .shape(CgBox.of(3, 0.05, 3)).direction(0f, 1f, 0f, 25f).speed(0.3f, 1.2f).life(4f, 7f).size(0.15f, 0.35f)
                .color(0xFFFF7020, 6f)
                .light(10f, 4f, CgVfxDrawnChannel.over(CgKeyframes.constant(1f)),
                        CgVfxDrawnChannel.over(CgKeyframes.constant(0.8f)), CgVfxDrawnChannel.over(CgKeyframes.constant(0.6f)),
                        CgVfxDrawnChannel.over(CgKeyframes.start(0f, 0f).to(0.15f, 0.5f, CgEasings.LINEAR)
                                .to(1f, 0f, CgEasings.LINEAR).build()))
                .renderer(CgVfxRenderers.sprite("ember").material(CgMaterialDefinition.of(
                        "crystalgraphics:materials/demo_lights_ember", "crystalgraphics:shaders/vfx/particle/spark.shader")
                        .blend(CgBlendMode.ADDITIVE).usage(CgMaterialUsage.SPRITES).build()).build())
                .renderer(CgVfxRenderers.light("glow").field("spawn_fraction", 0.5).field("inverse_squared_falloff", false)
                        .build())
                .build();
    }

    public void prepare() {
        vfx.prepare(embers);
    }

    public boolean warmed() {
        return vfx.warmed();
    }

    /** The lights round a ground point {@code (x, y, z)} as they are at {@code seconds}. */
    public void submit(CgWorldRenderer world, double x, double y, double z, double seconds) {
        CgWorldLights lights = world.lights();
        float t = (float) (seconds % 3600.0);

        // Inverse-squared, circling 1.5 blocks up: about 1.5 on the ground below it.
        double px = x - 12 + 3 * Math.cos(t * 0.8), pz = z + 3 * Math.sin(t * 0.8);
        source(px, y + 1.5, pz, ORANGE);
        lights.add(px, y + 1.5, pz, 10f, ORANGE, 1.5f * 1.5f * 1.5f, 0f);

        // Unreal's exponent, pulsing: the brightness at its centre.
        source(x - 6, y + 2, z, CYAN);
        lights.add(x - 6, y + 2, z, 7f, CYAN, 1.2f + (float) Math.sin(t * 2.0), 8f);

        // A lamp 8 blocks up, its cone sweeping a circle on the ground.
        float sx = 0.45f * (float) Math.cos(t * 0.6), sz = 0.45f * (float) Math.sin(t * 0.6);
        spot(lights, x, y + 8, z, sx, -1f, sz, 12f, 22f, 20f, WHITE, 2f * 8f * 8f);

        // A lighthouse at head height, turning.
        spot(lights, x + 6, y + 1.7, z, (float) Math.cos(t * 0.9), -0.08f, (float) Math.sin(t * 0.9), 6f, 14f, 24f, YELLOW,
                2f * 6f * 6f);

        // A neon bar lying along x, half a block up.
        tube(lights, x + 12, y + 0.5, z, 1f, 0f, 0f, 5f, 8f, MAGENTA, 1.2f * 0.5f * 0.5f);

        // A saber swinging upright, behind.
        float swing = 0.9f * (float) Math.sin(t * 1.3);
        tube(lights, x - 4, y + 1.8, z + 8, (float) Math.sin(swing), (float) Math.cos(swing), 0f, 3f, 7f, BLUE,
                1.5f * 1.8f * 1.8f);

        // The embers: played once where the scene stands, again where it moves to.
        if (playing == null || playing.state() == CgVfxSystemInstance.State.DEAD || playingX != x || playingY != y
                || playingZ != z) {
            if (playing != null) playing.kill();
            playing = vfx.play(new CgVfxSystemInstance<>(embers, x + 4, y, z + 8));
            playingX = x;
            playingY = y;
            playingZ = z;
        }
    }

    private void spot(CgWorldLights lights, double x, double y, double z, float dx, float dy, float dz, float inner,
                      float outer, float radius, int argb, float intensity) {
        float n = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        dx /= n;
        dy /= n;
        dz /= n;
        lights.add(x, y, z, radius, argb, intensity, 0f).spot(dx, dy, dz, inner, outer);
        source(x, y, z, argb);
        // The cone's axis and four edges, three blocks long.
        CgCollisionDebug.line(x, y, z, x + dx * 3, y + dy * 3, z + dz * 3, argb);
        float ux = Math.abs(dy) < 0.9f ? 0f : 1f, uy = Math.abs(dy) < 0.9f ? 1f : 0f;
        float ax = uy * dz, ay = -ux * dz, az = ux * dy - uy * dx;
        float m = (float) Math.sqrt(ax * ax + ay * ay + az * az);
        ax /= m;
        ay /= m;
        az /= m;
        float bx = dy * az - dz * ay, by = dz * ax - dx * az, bz = dx * ay - dy * ax;
        double tan = Math.tan(Math.toRadians(outer)) * 3;
        for (int k = 0; k < 4; k++) {
            double c = Math.cos(k * Math.PI / 2) * tan, s = Math.sin(k * Math.PI / 2) * tan;
            CgCollisionDebug.line(x, y, z, x + dx * 3 + ax * c + bx * s, y + dy * 3 + ay * c + by * s,
                    z + dz * 3 + az * c + bz * s, argb);
        }
    }

    private void tube(CgWorldLights lights, double x, double y, double z, float dx, float dy, float dz, float length,
                      float radius, int argb, float intensity) {
        lights.add(x, y, z, radius, argb, intensity, 0f).tube(dx, dy, dz, length);
        float n = (float) Math.sqrt(dx * dx + dy * dy + dz * dz), h = length / (2 * n);
        CgCollisionDebug.line(x - dx * h, y - dy * h, z - dz * h, x + dx * h, y + dy * h, z + dz * h, argb);
    }

    private void source(double x, double y, double z, int argb) {
        CgCollisionDebug.shape(SOURCE, pose.at(x, y, z), argb);
    }

    /** Ends the embers; the next submit plays them again. */
    public void clear() {
        if (playing != null) playing.kill();
        playing = null;
    }

    public void delete() {
        clear();
    }
}
