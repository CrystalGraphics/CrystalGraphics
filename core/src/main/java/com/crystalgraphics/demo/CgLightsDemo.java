package com.crystalgraphics.demo;

import com.crystalgraphics.api.material.CgBlendMode;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialDefinition;
import com.crystalgraphics.api.material.CgMaterialUsage;
import com.crystalgraphics.api.mesh.CgMeshShapes;
import com.crystalgraphics.collision.debug.CgCollisionDebug;
import com.crystalgraphics.easing.CgEasings;
import com.crystalgraphics.easing.CgKeyframes;
import com.crystalgraphics.render.world.CgWorldHaze;
import com.crystalgraphics.render.world.CgWorldLights;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.shape.CgPose;
import com.crystalgraphics.shape.primitive.CgBox;
import com.crystalgraphics.shape.primitive.CgSphere;
import com.crystalgraphics.vfx.CgVfxOwner;
import com.crystalgraphics.vfx.look.CgVfxLook;
import com.crystalgraphics.vfx.look.CgVfxSchema;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.CgVfxModule;
import com.crystalgraphics.vfx.particle.gpu.CgVfxDrawnChannel;
import com.crystalgraphics.vfx.particle.renderer.CgVfxRendererProperties;
import com.crystalgraphics.vfx.particle.renderer.CgVfxRenderers;
import com.crystalgraphics.vfx.particle.spawn.CgVfxEmitterState;
import com.crystalgraphics.vfx.system.CgVfxSystemInstance;
import org.joml.Matrix4f;

/**
 * A light show over the ground round a point, every kind of world light ({@link CgWorldLights}) at once, made for the
 * night: a mirror ball sweeping coloured spots over the ground, two searchlights crossing the arena, a carousel of
 * rainbow lights, a neon arch of tubes with colour chasing along it, a lightsaber duel flashing where the blades meet,
 * fireworks and a swarm of fireflies whose particles are lights, and lightning flooding everything every few seconds.
 * The lights light what is transparent too (the world lights' translucency volume): a ring of tinted glass round the
 * carousel, and smoke rising behind the duel. And the whole arena stands in a thin haze the lights glow in, the spots
 * and searchlights as beams.
 *
 * <pre>
 *                  arch (tubes)
 *   fireflies    ball (spots)    duel (tubes), smoke
 *                carousel (points) in glass
 *   searchlight    fireworks     searchlight
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
    private static final CgSphere MARK = CgSphere.of(0.12), BALL_MARK = CgSphere.of(0.6);
    private static final int BALL = 16, CAROUSEL = 12, ARCH = 9;
    /** Seconds between lightning strikes, and when in it each of its three flashes falls and how long it lasts. */
    private static final float STORM = 9f;
    private static final float[][] FLASHES = {{0f, 0.07f}, {0.16f, 0.06f}, {0.34f, 0.22f}};

    private final CgVfxOwner vfx;
    private final CgVfxLook particles = CgVfxLook.builder(SCHEMA)
            .emitter(fireworks("fireworksGold", 0xFFFFC040, 0xFFFF4020, 3.1f, -4f))
            .emitter(fireworks("fireworksNeon", 0xFF40E0FF, 0xFFFF40E0, 2.3f, 4f))
            .emitter(fireflies()).emitter(smoke()).build();
    /** The glass ring's panes round the carousel, and their tints, each with its alpha. */
    private static final int PANES = 8;
    private static final float[][] TINTS = {{0.85f, 0.9f, 1f, 0.35f}, {0.6f, 0.8f, 1f, 0.45f}, {1f, 0.7f, 0.8f, 0.45f},
            {0.75f, 1f, 0.75f, 0.45f}};
    private final Matrix4f pane = new Matrix4f();
    private CgMaterial glass;
    /** The haze over the arena, added to the world's media while the show plays. */
    private final CgWorldHaze haze = new CgWorldHaze();
    private boolean hazing;
    private final CgPose pose = new CgPose();
    private CgVfxSystemInstance<?> playing;
    private double playingX = Double.NaN, playingY, playingZ;

    public CgLightsDemo(CgVfxOwner vfx) {
        this.vfx = vfx;
    }

    /**
     * An intensity lighting a surface it faces {@code d} blocks off to {@code peak} times its colour: Unreal's simple
     * light gives albedo × intensity × N·L / (π d²).
     */
    private static float lit(float peak, float d) {
        return peak * (float) Math.PI * d * d;
    }

    /** Glowing sparks, added over the scene. */
    private static CgVfxRendererProperties sparks(String id) {
        return CgVfxRenderers.sprite(id).material(CgMaterialDefinition.of("crystalgraphics:materials/demo_lights_" + id,
                "crystalgraphics:shaders/vfx/particle/spark.shader").blend(CgBlendMode.ADDITIVE).usage(CgMaterialUsage.SPRITES)
                .build()).build();
    }

    /** A shell bursting high over the arena every {@code every} seconds, its sparks falling as lights. */
    private static CgVfxEmitter fireworks(String name, int a, int b, float every, float dx) {
        return CgVfxEmitter.builder(name).capacity(600).state(CgVfxEmitterState.infinite(every)).burst(0f, 220)
                .at(dx, 11f, 0f).shape(CgSphere.of(0.2)).outward().speed(6f, 9f).life(1.4f, 2.2f).size(0.06f, 0.1f)
                .color(a, 6f, b, 6f).module(new CgVfxModule.Gravity(4f)).module(new CgVfxModule.Drag(1.2f, 0f))
                .opacity(CgKeyframes.start(0f, 1f).to(0.6f, 1f, CgEasings.LINEAR).to(1f, 0f, CgEasings.IN_QUAD).build())
                .light(160f, 2f, CgVfxDrawnChannel.over(CgKeyframes.constant(1f)), CgVfxDrawnChannel.over(CgKeyframes.constant(1f)),
                        CgVfxDrawnChannel.over(CgKeyframes.constant(1f)),
                        // About 90 sparks a burst, each nearly full 5 blocks off (Cascade's (1 - d²/r²)²): together
                        // about three times the colour of what they light there.
                        CgVfxDrawnChannel.over(CgKeyframes.start(0f, 2.15f).to(1f, 0f, CgEasings.IN_QUAD).build()))
                .renderer(sparks(name)).renderer(CgVfxRenderers.light(name + "Light").field("spawn_fraction", 0.4)
                        .field("inverse_squared_falloff", false).build())
                .build();
    }

    /**
     * Smoke rising just behind the duel, the sabers a block or two off it and the clash between: transparent, so lit
     * through the world lights' translucency volume rather than the scene's lighting.
     */
    private static CgVfxEmitter smoke() {
        CgKeyframes grow = CgKeyframes.start(0f, 1f).to(1f, 2.6f, CgEasings.OUT_QUAD).build();
        return CgVfxEmitter.builder("smoke").capacity(400).state(CgVfxEmitterState.infinite(1f)).rate(14f)
                .at(11f, 0.4f, 2.2f).shape(CgBox.of(0.5, 0.1, 0.5)).direction(0f, 1f, 0f, 15f).speed(0.7f, 1.1f)
                .life(5f, 7f).size(0.9f, 1.3f).color(0xFFB8B8B8, 1f, 0xFF9A9A9A, 1f)
                .module(new CgVfxModule.Turbulence(0.8f, 0.4f, 0.4f)).module(new CgVfxModule.Drag(0.4f, 0f))
                .scaleOverLife(grow, grow, grow)
                .opacity(CgKeyframes.start(0f, 0f).to(0.15f, 0.55f, CgEasings.OUT_QUAD).to(0.7f, 0.4f, CgEasings.LINEAR)
                        .to(1f, 0f, CgEasings.IN_QUAD).build())
                .renderer(CgVfxRenderers.sprite("smoke").material(CgMaterialDefinition.of("crystalgraphics:materials/demo_lights_smoke",
                        "crystalgraphics:shaders/demo/demo_lit_smoke.shader").blend(CgBlendMode.PREMULTIPLIED)
                        .usage(CgMaterialUsage.SPRITES).build()).build())
                .build();
    }

    /** Fireflies drifting over a meadow beside the arena, every one a small light fading in and out. */
    private static CgVfxEmitter fireflies() {
        return CgVfxEmitter.builder("fireflies").capacity(512).state(CgVfxEmitterState.infinite(1f)).rate(70f)
                .at(-11f, 1.2f, 4f).shape(CgBox.of(5, 1, 5)).speed(0.1f, 0.5f).life(4f, 7f).size(0.05f, 0.09f)
                .color(0xFFC8FF50, 5f, 0xFF60FF90, 5f).module(new CgVfxModule.Turbulence(1.4f, 0.6f, 0.5f))
                .module(new CgVfxModule.Drag(1.5f, 0f))
                .opacity(CgKeyframes.start(0f, 0f).to(0.2f, 1f, CgEasings.OUT_QUAD).to(0.8f, 1f, CgEasings.LINEAR)
                        .to(1f, 0f, CgEasings.IN_QUAD).build())
                // Inverse-squared, as the world's lights: brighter the nearer, where a falloff exponent caps at its colour.
                .light(40f, 0f, CgVfxDrawnChannel.over(CgKeyframes.constant(1f)), CgVfxDrawnChannel.over(CgKeyframes.constant(1f)),
                        CgVfxDrawnChannel.over(CgKeyframes.constant(1f)),
                        CgVfxDrawnChannel.over(CgKeyframes.start(0f, 0f).to(0.2f, 4.5f, CgEasings.OUT_QUAD)
                                .to(0.8f, 1.5f, CgEasings.LINEAR).to(1f, 0f, CgEasings.IN_QUAD).build()))
                .renderer(sparks("fireflies")).renderer(CgVfxRenderers.light("firefliesLight").field("spawn_fraction", 1.0)
                        .build())
                .build();
    }

    public void prepare() {
        vfx.prepare(particles);
        glass = CgMaterial.load("crystalgraphics:shaders/demo/demo_glass.shader");
        CgWorldRenderer.get().prepare(glass);
    }

    public boolean warmed() {
        return vfx.warmed() && glass != null && CgWorldRenderer.get().prepare(glass);
    }

    /** The show round a ground point {@code (x, y, z)} as it is at {@code seconds}. */
    public void submit(CgWorldRenderer world, double x, double y, double z, double seconds) {
        CgWorldLights lights = world.lights();
        float t = (float) (seconds % 3600.0);
        haze.box(x - 24, y - 1, z - 20, x + 24, y + 18, z + 20).density(0.03f, 4f);
        if (!hazing) {
            world.media().add(haze);
            hazing = true;
        }
        mirrorBall(lights, x, y + 10, z - 2, t);
        searchlights(lights, x, y, z, t);
        carousel(lights, x, y, z + 4, t);
        glass(world, x, y, z + 4);
        arch(lights, x, y, z - 13, t);
        duel(lights, x + 11, y, z + 3, t);
        lightning(lights, x, y, z, t);
        // The particles: played once where the show stands, again where it moves to.
        if (playing == null || playing.state() == CgVfxSystemInstance.State.DEAD || playingX != x || playingY != y
                || playingZ != z) {
            if (playing != null) playing.kill();
            playing = vfx.play(new CgVfxSystemInstance<>(particles, x, y, z));
            playingX = x;
            playingY = y;
            playingZ = z;
        }
    }

    /** Sixteen narrow spots round a turning ball, each its own colour, sweeping circles over the ground. */
    private void mirrorBall(CgWorldLights lights, double x, double y, double z, float t) {
        CgCollisionDebug.shape(BALL_MARK, pose.at(x, y, z), 0xFFFFFFFF);
        for (int i = 0; i < BALL; i++) {
            double turn = i * 2 * Math.PI / BALL + t * 0.7, tilt = Math.toRadians(30 + 35 * ((i * 7) % BALL) / (double) BALL);
            float dx = (float) (Math.cos(turn) * Math.sin(tilt)), dy = (float) -Math.cos(tilt);
            float dz = (float) (Math.sin(turn) * Math.sin(tilt));
            lights.add(x, y, z, 30f, hue(i / (float) BALL + t * 0.05f), lit(4f, 14f), 0f).spot(dx, dy, dz, 3f, 7f);
        }
    }

    /** Two white searchlights from the arena's front corners, crossing and parting over it. */
    private void searchlights(CgWorldLights lights, double x, double y, double z, float t) {
        for (int side = -1; side <= 1; side += 2) {
            double sx = x + side * 15, sz = z + 12, sweep = Math.sin(t * 0.45 + side) * 0.9;
            float dx = (float) (-side * Math.cos(sweep) * 0.7), dz = (float) (-Math.cos(sweep * 0.7) * 0.9 + Math.sin(sweep) * 0.3);
            CgCollisionDebug.shape(MARK, pose.at(sx, y + 3, sz), 0xFFFFF4E0);
            lights.add(sx, y + 3, sz, 45f, 0xFFFFF4E0, lit(4f, 12f), 0f).spot(dx, -0.3f, dz, 5f, 10f);
        }
    }

    /** Twelve rainbow lights wheeling round the arena's middle, bobbing as they go. */
    private void carousel(CgWorldLights lights, double x, double y, double z, float t) {
        for (int i = 0; i < CAROUSEL; i++) {
            double a = i * 2 * Math.PI / CAROUSEL + t * 0.9;
            double lx = x + Math.cos(a) * 6, lz = z + Math.sin(a) * 6, ly = y + 1.4 + 0.6 * Math.sin(t * 2.2 + i);
            int colour = hue(i / (float) CAROUSEL + t * 0.08f);
            CgCollisionDebug.shape(MARK, pose.at(lx, ly, lz), colour);
            lights.add(lx, ly, lz, 7f, colour, lit(3f, 1.4f), 0f);
        }
    }

    /** Tinted panes standing in a ring just outside the carousel, facing in: each light lights the glass it passes. */
    private void glass(CgWorldRenderer world, double x, double y, double z) {
        for (int i = 0; i < PANES; i++) {
            double a = (i + 0.5) * 2 * Math.PI / PANES;
            float[] tint = TINTS[i % TINTS.length];
            pane.rotationY((float) (Math.PI / 2 - a)).scale(2.6f, 2.8f, 0.06f);
            world.draw(CgMeshShapes.cube(), glass).at(x + Math.cos(a) * 7.3, y + 1.4, z + Math.sin(a) * 7.3).transform(pane)
                    .custom(0, tint[0], tint[1], tint[2], tint[3]).submit();
        }
    }

    /** A neon arch of tubes, a pulse of brightness chasing round it as its colours turn. */
    private void arch(CgWorldLights lights, double x, double y, double z, float t) {
        double radius = 6;
        for (int i = 0; i < ARCH; i++) {
            double a0 = Math.PI * i / ARCH, a1 = Math.PI * (i + 1) / ARCH;
            double x0 = x + Math.cos(a0) * radius, y0 = y + Math.sin(a0) * radius;
            double x1 = x + Math.cos(a1) * radius, y1 = y + Math.sin(a1) * radius;
            float pulse = 0.35f + 0.65f * (float) Math.pow(0.5 + 0.5 * Math.cos((i / (float) ARCH - t * 0.6) * 2 * Math.PI), 4);
            int colour = hue(0.75f + 0.25f * i / ARCH + t * 0.1f);
            CgCollisionDebug.line(x0, y0, z, x1, y1, z, colour);
            lights.add((x0 + x1) / 2, (y0 + y1) / 2, z, 14f, colour, lit(3f * pulse, 5f), 0f)
                    .tube((float) (x1 - x0), (float) (y1 - y0), 0f, (float) Math.hypot(x1 - x0, y1 - y0));
        }
    }

    /** A red and a blue saber swinging at each other; where they meet, a white flash. */
    private void duel(CgWorldLights lights, double x, double y, double z, float t) {
        float swing = (float) Math.sin(t * 2.6);
        float clash = (float) Math.pow(Math.max(0, Math.sin(t * 2.6 + Math.PI / 2)), 12);
        for (int side = -1; side <= 1; side += 2) {
            double hx = x + side * 1.3, hy = y + 1.1, a = Math.PI / 2 - side * (0.2 + 0.7 * (0.5 + 0.5 * swing));
            float dx = (float) (Math.cos(a) * -side), dy = (float) Math.sin(a);
            int colour = side < 0 ? 0xFFFF3030 : 0xFF3060FF;
            CgCollisionDebug.line(hx, hy, z, hx + dx * 1.8, hy + dy * 1.8, z, colour);
            lights.add(hx + dx * 0.9, hy + dy * 0.9, z, 10f, colour, lit(5f, 2f), 0f).tube(dx, dy, 0f, 1.8f);
        }
        if (clash > 0.05f) lights.add(x, y + 2.6, z, 16f, 0xFFFFFFFF, lit(8f * clash, 4f), 0f);
    }

    /** Lightning far overhead every {@link #STORM} seconds: three flashes flooding the whole arena. */
    private void lightning(CgWorldLights lights, double x, double y, double z, float t) {
        float at = t % STORM, strength = 0f;
        for (float[] flash : FLASHES) {
            if (at >= flash[0] && at < flash[0] + flash[1]) strength = Math.max(strength, 1f - (at - flash[0]) / flash[1] * 0.6f);
        }
        if (strength > 0f) lights.add(x - 6, y + 40, z - 10, 90f, 0xFFD8E4FF, lit(2.5f * strength, 40f), 0f);
    }

    /** A saturated colour round the hue wheel, {@code h} in turns. */
    private static int hue(float h) {
        float r = channel(h + 1f / 3), g = channel(h), b = channel(h - 1f / 3);
        return 0xFF000000 | Math.round(r * 255) << 16 | Math.round(g * 255) << 8 | Math.round(b * 255);
    }

    private static float channel(float h) {
        h -= (float) Math.floor(h);
        return Math.max(0f, Math.min(1f, Math.abs(h * 6f - 3f) - 1f));
    }

    /** Ends the particles and the haze; the next submit plays them again. */
    public void clear() {
        if (playing != null) playing.kill();
        playing = null;
        if (hazing) CgWorldRenderer.get().media().remove(haze);
        hazing = false;
    }

    public void delete() {
        clear();
    }
}
