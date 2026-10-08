package com.crystalgraphics.demo;

import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshShapes;
import com.crystalgraphics.api.shader.CgShaderBindings;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.api.texture.CgTextureSpec;
import com.crystalgraphics.easing.CgEasings;
import com.crystalgraphics.easing.CgKeyframes;
import com.crystalgraphics.gl.texture.CgTexture2D;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.vfx.CgVfxEffect;
import com.crystalgraphics.vfx.CgVfxFrame;
import com.crystalgraphics.vfx.CgVfxSystem;
import com.crystalgraphics.vfx.look.CgVfxLayer;
import com.crystalgraphics.vfx.look.CgVfxLook;
import com.crystalgraphics.vfx.look.CgVfxParam;
import com.crystalgraphics.vfx.look.CgVfxSchema;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.CgVfxEmitterInstance;
import com.crystalgraphics.vfx.particle.CgVfxField;
import com.crystalgraphics.vfx.particle.CgVfxModule;
import com.crystalgraphics.vfx.particle.CgVfxModule.Volume;
import com.crystalgraphics.vfx.particle.gpu.CgVfxEvent;
import com.crystalgraphics.world.CgWorldQueries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * X6's module catalogue, a station each, to walk round and compare: energy gathering into a core, a tornado, a bar
 * magnet's field, a shield taking shape; colliders (a sphere with collision events, a cage, a slope, a block, an
 * obstacle course); damping beside drag, a speed limit; flipbook smoke and fire, and the five facing modes. Every station
 * replays every {@link #PERIOD} seconds, so a switch of {@link CgVfxSystem#simulation} reaches it within one. The
 * stations span about 60 blocks along x and 30 along z round the origin; each stands on the world's ground under it
 * ({@link CgWorldQueries#groundBelow}), else at the origin's height. The harness's {@code vfx-modules}, and a scene of
 * {@link CgRenderDemo}.
 *
 * <pre>{@code
 * CgVfxModules modules = new CgVfxModules(font);    // on the render thread: it makes its textures and materials
 * world.onFrame(view -> modules.submit(world, x, y, z, CgFrameClock.seconds()));
 * modules.delete();
 * }</pre>
 *
 * <p>Labels are drawn only with a font; {@code null} leaves them out.</p>
 */
public final class CgVfxModules {

    private static final Logger LOG = LogManager.getLogger("CgVfxModules");
    private static final String PARTICLE = "crystalgraphics:shaders/vfx/particle/";
    private static final float PERIOD = 7f, FOREVER = 1.0e6f;
    /** The station grid: columns along x, rows along z, toward the camera. */
    private static final float[] COLUMNS = {-21f, -7f, 7f, 21f}, ROWS = {-14f, 0f, 14f};

    private static final CgVfxSchema SCHEMA = new CgVfxSchema();
    private static final CgVfxParam GOLD = SCHEMA.color("gold", 1f, 0.55f, 0.12f, 1f);
    private static final CgVfxParam WHITE = SCHEMA.color("white", 1f, 1f, 1f, 1f);
    private static final CgVfxParam CYAN = SCHEMA.color("cyan", 0.15f, 0.7f, 1.2f, 1f);
    private static final CgVfxParam VIOLET = SCHEMA.color("violet", 0.7f, 0.25f, 1.3f, 1f);
    private static final CgVfxParam SMOKE = SCHEMA.color("smoke", 0.55f, 0.55f, 0.6f, 0.8f);
    private static final CgVfxParam EMBER = SCHEMA.color("ember", 1.2f, 0.35f, 0.05f, 1f);
    private static final CgVfxParam FLAME = SCHEMA.color("flame", 1.4f, 1.1f, 0.5f, 1f);
    private static final CgVfxParam DEBRIS = SCHEMA.color("debris", 0.3f, 0.26f, 0.22f, 1f);
    private static final CgVfxParam DUST = SCHEMA.color("dust", 0.45f, 0.38f, 0.3f, 0.5f);
    private static final CgVfxParam FUNNEL = SCHEMA.color("funnel", 0.5f, 0.52f, 0.56f, 0.85f);
    private static final CgVfxParam SOOT = SCHEMA.color("soot", 0.2f, 0.19f, 0.19f, 0.5f);
    // The fire sheet carries its own colours: a flame's tint reddens from these as it cools. Flames overlap and add, so
    // each is faint (A's alpha): bright where many cross, never a white blob.
    private static final CgVfxParam FIRE_COOL = SCHEMA.color("fireCool", 0.9f, 0.32f, 0.1f, 0.45f);
    private static final CgVfxParam FIRE_HOT = SCHEMA.color("fireHot", 1f, 0.78f, 0.5f, 1f);
    private static final CgVfxParam MAGENTA = SCHEMA.color("magenta", 1.2f, 0.2f, 0.9f, 1f);
    private static final CgVfxParam ICE = SCHEMA.color("ice", 0.35f, 0.75f, 1.2f, 0.9f);
    private static final CgVfxParam SNOW = SCHEMA.color("snow", 0.85f, 0.9f, 1f, 0.55f);
    private static final CgVfxParam SPIRIT_COOL = SCHEMA.color("spiritCool", 0.1f, 0.3f, 1f, 0.5f);
    private static final CgVfxParam SPIRIT_HOT = SCHEMA.color("spiritHot", 0.55f, 0.95f, 1.1f, 1f);

    private static final CgKeyframes FADE = CgKeyframes.start(0f, 0f).to(0.1f, 1f, CgEasings.OUT_QUAD)
            .to(0.7f, 1f, CgEasings.LINEAR).to(1f, 0f, CgEasings.IN_QUAD).build();

    /** One station: its emitters started at their sources, its look, and what it stands beside. */
    private record Station(String name, float x, float z, CgVfxLook look, List<CgVfxEmitter> emitters, float[][] sources,
                           Consumer<Props> props) {
    }

    /** What a station draws besides its particles: a collider's shape, a label over part of it. */
    private interface Props {
        void sphere(float x, float y, float z, float radius);

        void box(float x, float y, float z, Matrix4f transform);

        void label(float x, float y, float z, float height, String text, int color);
    }

    private final CgVfxSystem vfx = new CgVfxSystem();
    private final List<Station> stations = new ArrayList<>();
    private final List<CgTexture> sheets = new ArrayList<>();
    private final Matrix4f transform = new Matrix4f();
    private float nextPlay;
    private CgMesh sphere, cube;
    private CgMaterial mercury, copper;
    private CgWorldRenderer world;
    private final CgFont font;
    /** Each station's floor this play, absolute. */
    private float[] floors;

    /** A station playing: its emitters ticked until the period ends and they have all finished. */
    private static final class Play extends CgVfxEffect {
        private final List<CgVfxEmitterInstance> emitters = new ArrayList<>();

        Play(Station station, double x, double y, double z) {
            super(station.look(), x + station.x(), y, z + station.z());
            for (int i = 0; i < station.emitters().size(); i++) {
                CgVfxEmitterInstance emitter = new CgVfxEmitterInstance(station.emitters().get(i), seed + i * 0.137f);
                float[] s = station.sources()[i];
                emitter.start(s[0], s[1], s[2]);
                emitter.ground(0f);
                emitters.add(emitter);
            }
        }

        @Override
        protected void tick(float dt) {
            boolean done = true;
            for (int i = 0; i < emitters.size(); i++) {
                tick(emitters.get(i), dt);
                done &= emitters.get(i).finished();
            }
            if (done && age > 1f) die();
        }

        @Override
        protected void submit(CgVfxFrame frame) {
            for (int i = 0; i < emitters.size(); i++) frame.particles(this, emitters.get(i));
        }
    }

    /** Builds every station, its textures and materials: on the render thread. */
    public CgVfxModules(CgFont font) {
        this.font = font;
        sphere = CgMeshShapes.sphere(40, 80);
        cube = CgMeshShapes.cube();
        mercury = CgMaterial.load("crystalgraphics:shaders/demo/vfx_mercury.shader");
        copper = CgMaterial.load("crystalgraphics:shaders/demo/vfx_copper.shader");
        CgTexture smokeSheet = sheet(8, 8, 128, CgVfxModules::smokeFrame);
        CgTexture fireSheet = sheet(4, 4, 128, CgVfxModules::fireFrame);
        CgTexture dot = sheet(1, 1, 64, (f, u, v, out) -> dot(u, v, out));
        CgTexture wispSheet = sheet(4, 4, 128, CgVfxModules::wispFrame);
        CgTexture star = sheet(1, 1, 128, (f, u, v, out) -> star(u, v, out));
        CgTexture rune = sheet(1, 1, 128, (f, u, v, out) -> rune(u, v, out));
        CgTexture ring = sheet(1, 1, 128, (f, u, v, out) -> ring(u, v, out));
        CgTexture shard = sheet(1, 1, 64, (f, u, v, out) -> shard(u, v, out));

        gather(COLUMNS[0], ROWS[0]);
        tornado(COLUMNS[1], ROWS[0], smokeSheet);
        field(COLUMNS[2], ROWS[0], dot, ring);
        // The uncapped fountain in limit sprays far: it stands in a corner.
        limit(COLUMNS[3], ROWS[0]);
        boulder(COLUMNS[0], ROWS[1]);
        cage(COLUMNS[1], ROWS[1]);
        slope(COLUMNS[2], ROWS[1], shard, smokeSheet);
        block(COLUMNS[3], ROWS[1], dot, ring);
        // Past the last column: the course runs ten blocks along x.
        obstacles(COLUMNS[3] + 15f, ROWS[1]);
        dampingAndDrag(COLUMNS[0], ROWS[2]);
        shield(COLUMNS[1], ROWS[2], dot);
        flipbooks(COLUMNS[2], ROWS[2], smokeSheet, fireSheet);
        facings(COLUMNS[3], ROWS[2], star, rune, wispSheet, dot, ring);
        floors = new float[stations.size()];
        for (Station s : stations) LOG.info("[vfx-modules] {} at x {}, z {}", s.name(), s.x(), s.z());
        LOG.info("[vfx-modules] every station replays every {}s; a simulation switch reaches it from the next", PERIOD);
    }

    // ── stations ──────────────────────────────────────────────────────────────

    /**
     * Energy gathering in a hand: motes drawn in by a pull that strengthens toward the centre, whirled faster the closer
     * they come (a constant push round the axis against drag: a free vortex), stirred by curl noise and swallowed by the
     * core, which swells as it charges and crackles with sparks.
     */
    private void gather(float x, float z) {
        CgVfxEmitter motes = CgVfxEmitter.builder("gather").renderer(CgVfxEmitter.Renderer.QUADS).capacity(2500)
                .rate(380f, 0f, PERIOD - 3f).shape(6.5f).launch(-0.4f, 0.4f, 1f).speed(0f, 0.3f).life(3f, 4f)
                .size(0.04f, 0.1f, 1.5f).heat(1f)
                .module(new CgVfxModule.Attract(Volume.sphere(9f), 30f).attenuation(1.6f))
                .module(new CgVfxModule.Vortex(0.15f, 1f, 0.1f, 7f, 0f, 0f, 0f, 0f))
                .module(new CgVfxModule.Turbulence(2.5f, 0.45f, 0.5f))
                .module(new CgVfxModule.Drag(1.1f, 0f))
                .module(new CgVfxModule.Kill(Volume.sphere(0.45f), true))
                .size(CgKeyframes.start(0f, 0.6f).to(0.15f, 1f, CgEasings.OUT_QUAD).to(1f, 0.5f, CgEasings.IN_QUAD).build())
                .opacity(CgKeyframes.start(0f, 0f).to(0.12f, 1f, CgEasings.OUT_QUAD).to(0.85f, 1f, CgEasings.LINEAR)
                        .to(1f, 0f, CgEasings.IN_QUAD).build()).build();
        CgVfxEmitter core = CgVfxEmitter.builder("gatherCore").renderer(CgVfxEmitter.Renderer.QUADS).capacity(4)
                .burst(0f, 1).speed(0f, 0f).life(PERIOD - 1.5f, PERIOD - 1.5f).size(0.7f, 0.7f, 1f).heat(1f)
                .size(CgKeyframes.start(0f, 0.2f).to(0.85f, 1.6f, CgEasings.IN_QUAD).to(0.92f, 2.4f, CgEasings.OUT_QUAD)
                        .to(1f, 0f, CgEasings.IN_QUAD).build())
                .opacity(CgKeyframes.start(0f, 0f).to(0.1f, 1f, CgEasings.OUT_QUAD).to(1f, 1f, CgEasings.LINEAR).build())
                .build();
        CgVfxEmitter crackle = CgVfxEmitter.builder("gatherCrackle").renderer(CgVfxEmitter.Renderer.QUADS).capacity(600)
                .rate(90f, 0.8f, PERIOD - 2f).shape(0.4f).launch(-1f, 1f, 1f).speed(2f, 5f).life(0.08f, 0.2f)
                .size(0.02f, 0.04f, 1.5f).heat(1f)
                .module(new CgVfxModule.Turbulence(20f, 2f, 3f))
                .module(new CgVfxModule.Drag(6f, 0f))
                .opacity(CgKeyframes.start(0f, 1f).to(1f, 0f, CgEasings.IN_QUAD).build()).build();
        CgVfxLook look = CgVfxLook.builder(SCHEMA).emitter(motes).emitter(core).emitter(crackle)
                .layer(sparkLayer(motes, GOLD, WHITE)).layer(sparkLayer(core, GOLD, WHITE))
                .layer(sparkLayer(crackle, GOLD, WHITE)).build();
        float[] centre = {0f, 3f, 0f};
        stations.add(new Station("gather: Attract + Vortex + Turbulence + Kill", x, z, look,
                List.of(motes, core, crackle), new float[][]{centre, centre, centre}, null));
    }

    /**
     * A tornado as the air it is: a funnel {@link CgVfxField} of a Rankine vortex round a wall that widens with height,
     * air drawn onto the wall, rising up it and spreading at the top. Drag holds each puff to the field's velocity, so
     * the smoke traces the funnel; turbulence makes it wander. Debris, held loosely, is flung round its foot and falls
     * back, under a skirt of dust.
     */
    private void tornado(float x, float z, CgTexture smokeSheet) {
        // A particle follows air turning at w radians a second only while w / drag stays well under 1: the funnel's
        // drag holds its smoke to the wall above the foot; the debris's lets it fly off.
        float drag = 8f;
        CgVfxField air = tornadoField();
        Volume box = Volume.box(TORNADO_HALF, TORNADO_TOP / 2f, TORNADO_HALF).at(0f, TORNADO_TOP / 2f - 0.3f, 0f);
        CgVfxEmitter funnel = CgVfxEmitter.builder("tornado").renderer(CgVfxEmitter.Renderer.QUADS).capacity(2000)
                .rate(320f, 0f, PERIOD - 2.5f).shape(0.6f).launch(0f, 1f, 1f).speed(0f, 0.5f).life(3f, 4f)
                .size(0.25f, 0.4f, 1f).spin(0.5f, 1.5f)
                .module(new CgVfxModule.VectorField(air, box, drag, 1f))
                .module(new CgVfxModule.Turbulence(10f, 0.6f, 0.8f))
                .module(new CgVfxModule.Drag(drag, 0f))
                .size(CgKeyframes.start(0f, 0.6f).to(1f, 4f, CgEasings.IN_QUAD).build())
                .opacity(CgKeyframes.start(0f, 0f).to(0.1f, 0.9f, CgEasings.OUT_QUAD).to(0.75f, 0.8f, CgEasings.LINEAR)
                        .to(1f, 0f, CgEasings.IN_QUAD).build()).build();
        CgVfxEmitter debris = CgVfxEmitter.builder("tornadoDebris").renderer(CgVfxEmitter.Renderer.QUADS).capacity(800)
                .rate(120f, 0f, PERIOD - 2.5f).shape(2.5f).launch(0f, 0.3f, 1f).speed(0.5f, 2f).life(2f, 3f)
                .size(0.05f, 0.12f, 1.5f).spin(2f, 8f)
                .module(new CgVfxModule.VectorField(air, box, 1.2f, 1f))
                .module(new CgVfxModule.Gravity(9.8f))
                .module(new CgVfxModule.Drag(0.7f, 0f))
                .module(new CgVfxModule.Collide(Volume.plane(0f, 1f, 0f).at(0f, -0.3f, 0f), 0.2f, 0.05f, 0.3f))
                .opacity(FADE).build();
        CgVfxEmitter dust = CgVfxEmitter.builder("tornadoDust").renderer(CgVfxEmitter.Renderer.QUADS).capacity(300)
                .rate(45f, 0f, PERIOD - 2.5f).shape(2.5f).launch(0f, 0.1f, 1f).speed(0.2f, 0.8f).life(1.6f, 2.4f)
                .size(0.9f, 1.4f, 1f).spin(0.2f, 0.8f)
                .module(new CgVfxModule.VectorField(air, box, 1.5f, 1f))
                .module(new CgVfxModule.Force(0f, -2f, 0f))
                .module(new CgVfxModule.Drag(1.5f, 0f))
                .module(new CgVfxModule.Collide(Volume.plane(0f, 1f, 0f).at(0f, -0.3f, 0f), 0f, 0.05f, 0.1f))
                .size(CgKeyframes.start(0f, 0.6f).to(1f, 2.4f, CgEasings.OUT_QUAD).build())
                .opacity(CgKeyframes.start(0f, 0f).to(0.2f, 0.55f, CgEasings.OUT_QUAD).to(1f, 0f, CgEasings.IN_QUAD).build())
                .build();
        Consumer<CgShaderBindings> sheet = b -> b.sampler("_Sheet", 0, smokeSheet).vec4("_Frames", 8f, 8f, 64f, 1f)
                .vec4("_Flip", 0.3f, 0f, 1f, 0f);
        CgVfxLook look = CgVfxLook.builder(SCHEMA).emitter(funnel).emitter(debris).emitter(dust)
                .layer(layerOf("sprite", dust, DUST, DUST, sheet)).layer(layerOf("sprite", funnel, FUNNEL, FUNNEL, sheet))
                .layer(layer("speck", debris, DEBRIS, DEBRIS, 1f, null)).build();
        float[] base = {0f, 0.3f, 0f};
        stations.add(new Station("tornado: a Rankine funnel VectorField, debris and dust", x, z, look,
                List.of(funnel, debris, dust), new float[][]{base, base, base}, null));
    }

    /** The tornado field's box: this many blocks either side of the axis, and up from the floor. */
    private static final float TORNADO_HALF = 5.5f, TORNADO_TOP = 14f;

    /**
     * A tornado's air, its velocity in blocks a second: round a funnel wall of radius 0.3 at the floor widening 0.28 a
     * block, a Rankine vortex (turning as a solid inside the wall, slowing as 1/r out of it), a pull onto the wall,
     * rising along it, and from 11 blocks up, slowing and spreading. A {@link CgVfxModule.VectorField} of strength
     * equal to a particle's drag settles it to this velocity.
     */
    private static CgVfxField tornadoField() {
        int nx = 24, ny = 32;
        return CgVfxField.of(nx, ny, nx, false, (i, j, k, out) -> {
            float x = ((i + 0.5f) / nx * 2f - 1f) * TORNADO_HALF, z = ((k + 0.5f) / nx * 2f - 1f) * TORNADO_HALF;
            float y = (j + 0.5f) / ny * TORNADO_TOP;
            float r = Math.max((float) Math.sqrt(x * x + z * z), 1e-3f), wall = 0.3f + 0.28f * y;
            float spin = 5f * (r < wall ? r / wall : wall / r);
            float top = smooth(11f, 14f, y);
            float in = Math.max(-4f, Math.min(4f, -2.5f * (r - wall))) * (1f - top) + 3f * top;
            float near = (r - wall) / (0.6f + 0.15f * y);
            float rise = (5f * (float) Math.exp(-near * near) + 0.4f) * (1f - top);
            // Tangent (-z, x) / r turns counter-clockwise seen from above; (x, z) / r points out.
            out[0] = -z / r * spin + x / r * in;
            out[1] = rise;
            out[2] = x / r * spin + z / r * in;
        });
    }

    /**
     * A bar magnet standing on end, its field drawn as iron filings draw it: comet streaks leave its north pole (top) at
     * four angles, each tracing one shell of field lines round to the south, nested and coloured inner to outer, the
     * whole field slowly twisting. Its poles pulse and crackle, and a pulse rings out from its middle. The air is a
     * {@link CgVfxField} of the two poles' field, and drag holds each streak to its line.
     */
    private void field(float x, float z, CgTexture dot, CgTexture ring) {
        float drag = 10f;
        // Source-relative, from the north pole: the field's box round the magnet, and the south pole under it.
        Volume box = Volume.box(MAGNET_HALF, MAGNET_HALF_Y, MAGNET_HALF).at(0f, -MAGNET_POLE, 0f);
        CgVfxField air = magnetField();
        // A line's launch angle picks its shell: leaving at these vertical shares, they loop about 1.5, 2, 2.6 and 3.2
        // blocks out. One leaving upward would fly off the field.
        float[] shares = {-0.65f, -0.45f, -0.25f, -0.08f};
        CgVfxParam[][] colours = {{CYAN, WHITE}, {CYAN, VIOLET}, {VIOLET, MAGENTA}, {MAGENTA, GOLD}};
        float top = 2.4f + 2f * MAGNET_POLE, middle = top - MAGNET_POLE;
        CgVfxLook.Builder look = CgVfxLook.builder(SCHEMA);
        List<CgVfxEmitter> emitters = new ArrayList<>();
        List<float[]> sources = new ArrayList<>();
        for (int s = 0; s < shares.length; s++) {
            CgVfxEmitter shell = CgVfxEmitter.builder("fieldShell" + s).renderer(CgVfxEmitter.Renderer.QUADS)
                    .capacity(900).rate(110f, 0f, PERIOD - 3.5f).shape(0.35f).launch(shares[s], shares[s] + 0.02f, 1f)
                    .speed(0.5f, 1f).life(4.5f, 5.5f).size(0.022f, 0.032f, 1.5f).heat(1f)
                    .module(new CgVfxModule.VectorField(air, box, drag, 1f))
                    .module(new CgVfxModule.Vortex(0f, 1f, 0f, 6f, 0f, 0f, -MAGNET_POLE, 0f))
                    .module(new CgVfxModule.Drag(drag, 0f))
                    .module(new CgVfxModule.Buoyancy(0f, 2.2f))
                    .module(new CgVfxModule.Kill(Volume.sphere(0.3f).at(0f, -2f * MAGNET_POLE, 0f), true))
                    .module(new CgVfxModule.Kill(box, false))
                    .opacity(CgKeyframes.start(0f, 0f).to(0.05f, 1f, CgEasings.OUT_QUAD).to(0.85f, 1f, CgEasings.LINEAR)
                            .to(1f, 0f, CgEasings.IN_QUAD).build()).build();
            look.emitter(shell).layer(layerOf("sprite_glow", shell, colours[s][0], colours[s][1], b -> b
                    .sampler("_Sheet", 0, dot).vec4("_Frames", 1f, 1f, 1f, 1f).set1f("_Facing", 3f)
                    .set1f("_Stretch", 3f).set1f("_MaxStretch", 40f)));
            emitters.add(shell);
            sources.add(new float[]{0f, top, 0f});
        }
        CgVfxEmitter north = pole("fieldNorth"), south = pole("fieldSouth");
        CgVfxEmitter northCrackle = crackle("fieldNorthCrackle"), southCrackle = crackle("fieldSouthCrackle");
        CgVfxEmitter pulse = CgVfxEmitter.builder("fieldPulse").renderer(CgVfxEmitter.Renderer.QUADS).capacity(8)
                .rate(0.8f, 0.5f, PERIOD - 3f).shape(0f).speed(0f, 0f).life(1.6f, 1.6f).size(1f, 1f, 1f).heat(1f)
                .module(new CgVfxModule.Buoyancy(0f, 1f))
                .size(CgKeyframes.start(0f, 0.3f).to(1f, 3.6f, CgEasings.OUT_CUBIC).build())
                .opacity(CgKeyframes.start(0f, 0.8f).to(1f, 0f, CgEasings.IN_QUAD).build()).build();
        look.emitter(north).emitter(south).emitter(northCrackle).emitter(southCrackle).emitter(pulse)
                .layer(sparkLayer(north, VIOLET, WHITE)).layer(sparkLayer(south, CYAN, WHITE))
                .layer(sparkLayer(northCrackle, VIOLET, WHITE)).layer(sparkLayer(southCrackle, CYAN, WHITE))
                .layer(facing(pulse, VIOLET, WHITE, ring, 4, null));
        emitters.addAll(List.of(north, south, northCrackle, southCrackle, pulse));
        sources.addAll(List.of(new float[]{0f, top, 0f}, new float[]{0f, 2.4f, 0f}, new float[]{0f, top, 0f},
                new float[]{0f, 2.4f, 0f}, new float[]{0f, middle, 0f}));
        stations.add(new Station("field: a bar magnet's VectorField", x, z, look.build(), emitters,
                sources.toArray(new float[0][]), p -> {
                    transform.identity().scale(0.36f, 2f * MAGNET_POLE - 0.2f, 0.36f);
                    p.box(0f, middle, 0f, transform);
                }));
    }

    /** Sparks crackling round a pole: short-lived, torn about by fast noise. */
    private static CgVfxEmitter crackle(String name) {
        return CgVfxEmitter.builder(name).renderer(CgVfxEmitter.Renderer.QUADS).capacity(300)
                .rate(70f, 0f, PERIOD - 2.5f).shape(0.25f).speed(2f, 5f).life(0.08f, 0.22f).size(0.02f, 0.04f, 1.5f)
                .heat(1f)
                .module(new CgVfxModule.Turbulence(20f, 2f, 3f))
                .module(new CgVfxModule.Drag(6f, 0f))
                .opacity(CgKeyframes.start(0f, 1f).to(1f, 0f, CgEasings.IN_QUAD).build()).build();
    }

    /** The field magnet's box: this many blocks either side of its axis, and above and below its middle. */
    private static final float MAGNET_HALF = 4.2f, MAGNET_HALF_Y = 3.2f, MAGNET_POLE = 1.6f;

    /**
     * A bar magnet's field round its middle, poles {@link #MAGNET_POLE} above (north) and below: each pole's field
     * summed, followed at a speed easing with its strength, fastest near the poles.
     */
    private static CgVfxField magnetField() {
        int nx = 32, ny = 24;
        return CgVfxField.of(nx, ny, nx, false, (i, j, k, out) -> {
            float x = ((i + 0.5f) / nx * 2f - 1f) * MAGNET_HALF, z = ((k + 0.5f) / nx * 2f - 1f) * MAGNET_HALF;
            float y = ((j + 0.5f) / ny * 2f - 1f) * MAGNET_HALF_Y;
            float bx = 0f, by = 0f, bz = 0f;
            for (int pole = 0; pole < 2; pole++) {
                float sign = pole == 0 ? 1f : -1f, dy = y - sign * MAGNET_POLE;
                float r = (float) Math.sqrt(x * x + dy * dy + z * z) + 1e-4f, r3 = sign / (r * r * r);
                bx += x * r3;
                by += dy * r3;
                bz += z * r3;
            }
            float b = (float) Math.sqrt(bx * bx + by * by + bz * bz) + 1e-9f;
            float speed = Math.min(6f, 2.2f * (float) Math.pow(b, 0.25)) / b;
            out[0] = bx * speed;
            out[1] = by * speed;
            out[2] = bz * speed;
        });
    }

    /** A pole's glow, beating as it charges and fading as the station's period ends. */
    private static CgVfxEmitter pole(String name) {
        return CgVfxEmitter.builder(name).renderer(CgVfxEmitter.Renderer.QUADS).capacity(4)
                .burst(0f, 1).speed(0f, 0f).life(PERIOD - 2f, PERIOD - 2f).size(0.45f, 0.45f, 1f).heat(1f)
                .size(CgKeyframes.start(0f, 0.6f).to(0.15f, 1.25f, CgEasings.OUT_QUAD).to(0.3f, 0.9f, CgEasings.IN_OUT_QUAD)
                        .to(0.45f, 1.25f, CgEasings.IN_OUT_QUAD).to(0.6f, 0.9f, CgEasings.IN_OUT_QUAD)
                        .to(0.75f, 1.25f, CgEasings.IN_OUT_QUAD).to(1f, 0.4f, CgEasings.IN_QUAD).build())
                .opacity(CgKeyframes.start(0f, 0f).to(0.1f, 1f, CgEasings.OUT_QUAD).to(0.8f, 1f, CgEasings.LINEAR)
                        .to(1f, 0f, CgEasings.IN_QUAD).build()).build();
    }

    /**
     * bevy_hanabi's conform to sphere: a shield taking shape, its motes drawn onto the surface and carried over it by
     * curl-noise currents, which flow and part as one field, round a slow drift.
     */
    private void shield(float x, float z, CgTexture dot) {
        CgVfxEmitter motes = CgVfxEmitter.builder("shield").renderer(CgVfxEmitter.Renderer.QUADS).capacity(2500)
                .rate(520f, 0f, PERIOD - 3f).shape(5.5f).launch(-0.5f, 0.5f, 1f).speed(0.3f, 1.5f).life(2.6f, 3.4f)
                .size(0.06f, 0.14f, 1.5f).heat(0.7f)
                .module(new CgVfxModule.Conform(Volume.sphere(2.6f), 4.5f, 40f, 6f))
                .module(new CgVfxModule.Turbulence(9f, 0.9f, 0.6f))
                .module(new CgVfxModule.Vortex(0.2f, 1f, 0f, 2.5f, 0f, 0f, 0f, 0f))
                .module(new CgVfxModule.Drag(0.9f, 0f))
                .opacity(CgKeyframes.start(0f, 0f).to(0.2f, 1f, CgEasings.OUT_QUAD).to(0.8f, 1f, CgEasings.LINEAR)
                        .to(1f, 0f, CgEasings.IN_QUAD).build()).build();
        add("shield: Conform + Turbulence", x, z, List.of(motes), new float[][]{{0f, 3.2f, 0f}},
                layer("sprite_glow", motes, VIOLET, WHITE, 1f,
                        b -> b.sampler("_Sheet", 0, dot).vec4("_Frames", 1f, 1f, 1f, 1f)), null);
    }

    /**
     * Godot's sphere collider and rigid response; each bounce a collision event: a flash at the contact, popping and gone
     * within a tenth of a second, and a few hot chips glancing off the surface about its normal.
     */
    private void boulder(float x, float z) {
        CgVfxEmitter flash = CgVfxEmitter.builder("boulderFlash").renderer(CgVfxEmitter.Renderer.QUADS).capacity(64)
                .speed(0f, 0f).life(0.05f, 0.09f).size(0.3f, 0.5f, 1f).heat(1f)
                .size(CgKeyframes.start(0f, 0.4f).to(0.2f, 1f, CgEasings.OUT_QUAD).to(1f, 0.8f, CgEasings.LINEAR).build())
                .opacity(CgKeyframes.start(0f, 1f).to(1f, 0f, CgEasings.OUT_QUAD).build()).build();
        CgVfxEmitter chips = CgVfxEmitter.builder("boulderChips").renderer(CgVfxEmitter.Renderer.QUADS).capacity(256)
                .launch(0.15f, 0.9f, 1f).speed(2.5f, 6f).life(0.15f, 0.4f).size(0.025f, 0.05f, 1.5f).heat(1f)
                .module(new CgVfxModule.Gravity(9.8f))
                .module(new CgVfxModule.Drag(1.2f, 0f))
                .opacity(CgKeyframes.start(0f, 1f).to(1f, 0f, CgEasings.IN_QUAD).build()).build();
        CgVfxEmitter sparks = CgVfxEmitter.builder("boulder").renderer(CgVfxEmitter.Renderer.QUADS).capacity(1200)
                .rate(120f, 0f, PERIOD - 3f).shape(1.2f).launch(-1f, -0.7f, 1f).speed(0.5f, 2f).life(2.5f, 3f)
                .size(0.08f, 0.14f, 1f).heat(1f)
                .module(new CgVfxModule.Gravity(9.8f))
                .module(new CgVfxModule.Collide(Volume.sphere(1.8f).at(0f, -5.2f, 0f), 0.6f, 0.05f, 0.3f))
                .module(new CgVfxModule.Collide(Volume.plane(0f, 1f, 0f).at(0f, -7f, 0f), 0.35f, 0.4f, 0.4f))
                .event(CgVfxEvent.onCollision().spawn(flash, 1))
                .event(CgVfxEvent.onCollision().spawn(chips, 4))
                .opacity(FADE).build();
        CgVfxLook look = CgVfxLook.builder(SCHEMA).emitter(sparks).emitter(flash).emitter(chips)
                .layer(sparkLayer(sparks, EMBER, FLAME)).layer(sparkLayer(flash, WHITE, FLAME))
                .layer(sparkLayer(chips, FLAME, EMBER)).build();
        stations.add(new Station("boulder: Collide sphere, onCollision spawns a flash and chips", x, z, look, List.of(sparks),
                new float[][]{{0f, 7f, 0f}}, p -> p.sphere(0f, 1.8f, 0f, 1.75f)));
    }

    /**
     * A container box drawn as a copper cage: balls burst inside it, lose a share of their speed at every bounce and
     * settle on its floor, glinting where they strike hard; a second burst halfway knocks the pile about again.
     */
    private void cage(float x, float z) {
        CgVfxEmitter glint = CgVfxEmitter.builder("cageGlint").renderer(CgVfxEmitter.Renderer.QUADS).capacity(256)
                .speed(0f, 0f).life(0.06f, 0.12f).size(0.18f, 0.3f, 1f).heat(1f)
                .opacity(CgKeyframes.start(0f, 1f).to(1f, 0f, CgEasings.OUT_QUAD).build()).build();
        CgVfxEmitter balls = CgVfxEmitter.builder("cage").renderer(CgVfxEmitter.Renderer.QUADS).capacity(500)
                .burst(0f, 300).burst(3.5f, 150).shape(1f).launch(-1f, 1f, 1f).speed(4f, 9f)
                .life(PERIOD - 1f, PERIOD - 0.5f).size(0.1f, 0.2f, 1f).heat(0.9f)
                .module(new CgVfxModule.Gravity(9.8f))
                .module(new CgVfxModule.Collide(Volume.box(2.5f, 2.5f, 2.5f), 0.78f, 0.03f, 0.4f).container())
                .event(CgVfxEvent.onCollision().spawn(glint, 1))
                .opacity(FADE).build();
        CgVfxLook look = CgVfxLook.builder(SCHEMA).emitter(balls).emitter(glint)
                .layer(sparkLayer(balls, CYAN, WHITE)).layer(sparkLayer(glint, WHITE, WHITE)).build();
        stations.add(new Station("cage: Collide container box, onCollision glints", x, z, look, List.of(balls),
                new float[][]{{0f, 2.6f, 0f}}, p -> {
                    // The box's twelve edges: along x, y and z in turn, at each corner of the other two axes.
                    float h = 2.5f, cy = 2.6f, bar = 0.08f, span = 2f * h + bar;
                    for (int a = 0; a < 2; a++) {
                        for (int b = 0; b < 2; b++) {
                            float s = a * 2f - 1f, t = b * 2f - 1f;
                            transform.identity().scale(span, bar, bar);
                            p.box(0f, cy + s * h, t * h, transform);
                            transform.identity().scale(bar, span, bar);
                            p.box(s * h, cy, t * h, transform);
                            transform.identity().scale(bar, bar, span);
                            p.box(s * h, cy + t * h, 0f, transform);
                        }
                    }
                }));
    }

    /**
     * An avalanche down a plane collider tilted into a ramp: glowing ice shards tumble onto it, cooling from white to
     * ice blue, each impact kicking up a puff of snow, and slide off onto the floor, killed past a box.
     */
    private void slope(float x, float z, CgTexture shard, CgTexture smokeSheet) {
        // The plane stands top blocks over the floor at the station's centre; ramp is its length, end to end, from as
        // high again down to the floor.
        float nx = 0.35f, ny = 1f, length = (float) Math.sqrt(nx * nx + ny * ny), top = 1.5f, source = 6.5f;
        float ramp = 2f * top * length / nx;
        CgVfxEmitter snow = CgVfxEmitter.builder("slopeSnow").renderer(CgVfxEmitter.Renderer.QUADS).capacity(900)
                .launch(0.3f, 1f, 1f).speed(0.3f, 0.9f).life(0.8f, 1.4f).size(0.25f, 0.4f, 1f).spin(0.2f, 0.8f)
                .module(new CgVfxModule.Force(0f, 0.3f, 0f))
                .module(new CgVfxModule.Drag(2f, 0f))
                .size(CgKeyframes.start(0f, 0.5f).to(1f, 2.2f, CgEasings.OUT_CUBIC).build())
                .opacity(CgKeyframes.start(0f, 0f).to(0.15f, 0.6f, CgEasings.OUT_QUAD).to(1f, 0f, CgEasings.IN_QUAD).build())
                .build();
        CgVfxEmitter shards = CgVfxEmitter.builder("slope").renderer(CgVfxEmitter.Renderer.QUADS).capacity(1500)
                .rate(200f, 0f, PERIOD - 3f).shape(2.5f).launch(-1f, -0.8f, 1f).speed(0.5f, 1.5f).life(3f, 4f)
                .size(0.08f, 0.2f, 1.5f).spin(2f, 8f).heat(1f)
                .module(new CgVfxModule.Gravity(9.8f))
                .module(new CgVfxModule.Buoyancy(0f, 1.5f))
                .module(new CgVfxModule.Collide(Volume.plane(nx, ny, 0f).at(0f, top - source, 0f), 0.3f, 0.02f, 0f))
                // Friction takes its share of the sliding speed every step: 0.05 skids a piece off the ramp a block or two.
                .module(new CgVfxModule.Collide(Volume.plane(0f, 1f, 0f).at(0f, -source, 0f), 0.3f, 0.05f, 0.1f))
                .module(new CgVfxModule.Kill(Volume.box(6.5f, 8f, 6.5f), false))
                .event(CgVfxEvent.onCollision().spawn(snow, 1))
                .opacity(FADE).build();
        CgVfxLook look = CgVfxLook.builder(SCHEMA).emitter(shards).emitter(snow)
                .layer(layerOf("sprite", snow, SNOW, SNOW, b -> b.sampler("_Sheet", 0, smokeSheet)
                        .vec4("_Frames", 8f, 8f, 64f, 1f).vec4("_Flip", 0.3f, 0f, 1f, 0f)))
                // Solid pieces: pulled toward the eye to rest on the ramp whole, never faded into it.
                .layer(facing(shards, ICE, WHITE, shard, 0, b -> b.set1f("_SoftDistance", 0f).set1f("_CameraOffset", 1f)))
                .build();
        stations.add(new Station("slope: Collide plane, an avalanche kicking up snow", x, z, look, List.of(shards),
                new float[][]{{0f, source, 0f}}, p -> {
                    // the plane's surface as a thin slab just under it, from its high end to the floor; rotateZ turns +y
                    // toward -x, so the normal's lean toward +x is a negative angle
                    float angle = -(float) Math.atan2(nx, ny);
                    transform.identity().rotateZ(angle).translate(0f, -0.05f, 0f).scale(ramp, 0.1f, 8f);
                    p.box(0f, top, 0f, transform);
                }));
    }

    /**
     * Neon rain on Godot's box collider: streaked drops pelt a block and the floor round it, each splashing into droplets
     * and a ripple ring flat on what it struck, and dying there.
     */
    private void block(float x, float z, CgTexture dot, CgTexture ring) {
        float source = 8f;
        CgVfxEmitter splash = CgVfxEmitter.builder("blockSplash").renderer(CgVfxEmitter.Renderer.QUADS).capacity(2000)
                .launch(0.5f, 1f, 1f).speed(1.5f, 3f).life(0.25f, 0.45f).size(0.02f, 0.035f, 1f).heat(0.5f)
                .module(new CgVfxModule.Gravity(9.8f))
                .opacity(CgKeyframes.start(0f, 1f).to(1f, 0f, CgEasings.IN_QUAD).build()).build();
        CgVfxEmitter ripple = CgVfxEmitter.builder("blockRipple").renderer(CgVfxEmitter.Renderer.QUADS).capacity(600)
                .speed(0f, 0f).life(0.45f, 0.45f).size(1f, 1f, 1f).heat(0.6f)
                .size(CgKeyframes.start(0f, 0.05f).to(1f, 0.4f, CgEasings.OUT_CUBIC).build())
                .opacity(CgKeyframes.start(0f, 0.9f).to(1f, 0f, CgEasings.IN_QUAD).build()).build();
        // A ripple is flat and cannot bend over an edge, so each rain keeps to what it falls on: the block's starts within
        // 1.1 of its middle, a ripple's 0.35 radius short of its 1.5 edge; the floor's dies in the column over the block.
        CgVfxEmitter onBlock = rain("block", 180f, 1.1f, source, splash, ripple);
        CgVfxEmitter onFloor = rain("blockFloor", 420f, 3f, source, splash, ripple);
        Consumer<CgShaderBindings> streak = b -> b.set1f("_Stretch", 0.25f).set1f("_MaxStretch", 12f);
        CgVfxLook look = CgVfxLook.builder(SCHEMA).emitter(onBlock).emitter(onFloor).emitter(splash).emitter(ripple)
                .layer(facing(onBlock, CYAN, WHITE, dot, 3, streak)).layer(facing(onFloor, CYAN, WHITE, dot, 3, streak))
                .layer(facing(splash, CYAN, WHITE, dot, 3, streak)).layer(facing(ripple, CYAN, WHITE, ring, 4, null))
                .build();
        stations.add(new Station("block: Collide box, rain splashing and rippling", x, z, look, List.of(onBlock, onFloor),
                new float[][]{{0f, source, 0f}, {0f, source, 0f}}, p -> {
                    transform.identity().scale(3f, 2f, 3f);
                    p.box(0f, 1f, 0f, transform);
                }));
    }

    /**
     * Rain from {@code source} blocks over the floor, starting within {@code spread} of the middle, onto the 3x2x3 block
     * and the floor; each drop dies where it lands, splashing and rippling. Spread past the block's edge, it is the
     * floor's rain, and dies in the column over the block instead.
     */
    private static CgVfxEmitter rain(String name, float rate, float spread, float source, CgVfxEmitter splash,
                                     CgVfxEmitter ripple) {
        // Still at launch, so gravity alone sets them falling straight down; the launch only spreads where they start.
        CgVfxEmitter.Builder rain = CgVfxEmitter.builder(name).renderer(CgVfxEmitter.Renderer.QUADS).capacity(1000)
                .rate(rate, 0f, PERIOD - 3f).shape(spread).launch(-1f, 0f, 1f).speed(0f, 0f).life(1.5f, 1.5f)
                .size(0.025f, 0.035f, 1f).heat(0.4f)
                .module(new CgVfxModule.Gravity(20f))
                .module(new CgVfxModule.Collide(Volume.box(1.5f, 1f, 1.5f).at(0f, 1f - source, 0f), 0f, 0f, 0f))
                .module(new CgVfxModule.Collide(Volume.plane(0f, 1f, 0f).at(0f, -source, 0f), 0f, 0f, 0f))
                // Just past each surface: a drop is killed the step it lands, after its collision fired.
                .module(new CgVfxModule.Kill(Volume.box(1.52f, 1.03f, 1.52f).at(0f, 1f - source, 0f), true))
                .module(new CgVfxModule.Kill(Volume.plane(0f, 1f, 0f).at(0f, 0.03f - source, 0f), true));
        if (spread > 1.5f) {
            float column = (source - 2f) / 2f;
            rain.module(new CgVfxModule.Kill(Volume.box(1.55f, column, 1.55f).at(0f, -column, 0f), true));
        }
        return rain.event(CgVfxEvent.onCollision().spawn(splash, 3)).event(CgVfxEvent.onCollision().spawn(ripple, 1))
                .build();
    }

    /**
     * An obstacle course, colliders in a row: molten beads pour onto a sphere, glance off it onto a cube sitting in a
     * ramp, run down the ramp and skid across the floor, cooling from white-hot to red and throwing sparks at each
     * impact. The ramp is a plane: a kill box keeps beads to the part drawn.
     */
    private void obstacles(float x, float z) {
        // World heights and offsets from the station's centre; every volume is from the source, so each is less it.
        // Poured 1.2 blocks over the sphere's shoulder: a higher drop glances off fast enough to clear the cube.
        float sx = -2.55f, sy = 7.6f;
        float sphereX = -3f, sphereY = 6.4f, sphereR = 0.9f;
        float nx = 0.45f, rampY = 2.25f, rampFrom = -5f, rampTo = rampY / nx;   // its height at 0, and where it meets the floor
        float cubeX = -1.5f, cubeHalf = 0.9f, cubeY = rampY - nx * (cubeX + cubeHalf) + cubeHalf;   // resting on the ramp downhill
        CgVfxEmitter sparks = CgVfxEmitter.builder("obstacleSparks").renderer(CgVfxEmitter.Renderer.QUADS).capacity(1500)
                .launch(0.2f, 1f, 1f).speed(1.5f, 4f).life(0.15f, 0.4f).size(0.015f, 0.03f, 1.5f).heat(1f)
                .module(new CgVfxModule.Gravity(9.8f))
                .module(new CgVfxModule.Drag(1.5f, 0f))
                .opacity(CgKeyframes.start(0f, 1f).to(1f, 0f, CgEasings.IN_QUAD).build()).build();
        CgVfxEmitter beads = CgVfxEmitter.builder("obstacles").renderer(CgVfxEmitter.Renderer.QUADS).capacity(1500)
                .rate(220f, 0f, PERIOD - 3f).shape(0.4f).launch(-1f, -0.9f, 1f).speed(0f, 0f).life(4.5f, 5.5f)
                .size(0.05f, 0.08f, 1.5f).heat(1f)
                .module(new CgVfxModule.Gravity(9.8f))
                .module(new CgVfxModule.Buoyancy(0f, 2.5f))
                .module(new CgVfxModule.Collide(Volume.sphere(sphereR).at(sphereX - sx, sphereY - sy, 0f), 0.5f, 0.05f, 0.3f))
                .module(new CgVfxModule.Collide(Volume.box(cubeHalf, cubeHalf, cubeHalf).at(cubeX - sx, cubeY - sy, 0f),
                        0.35f, 0.05f, 0.3f))
                .module(new CgVfxModule.Collide(Volume.plane(nx, 1f, 0f).at(-sx, rampY - sy, 0f), 0.3f, 0.02f, 0f))
                // A grippy floor stops a bead within a block or so, well inside the box: it cools and fades where it rests.
                .module(new CgVfxModule.Collide(Volume.plane(0f, 1f, 0f).at(0f, -sy, 0f), 0.3f, 0.12f, 0.1f))
                .module(new CgVfxModule.Kill(Volume.box(7.1f, 6f, 3.2f).at(1.9f - sx, 6f - sy, 0f), false))
                .event(CgVfxEvent.onCollision().spawn(sparks, 2))
                .opacity(FADE).build();
        CgVfxLook look = CgVfxLook.builder(SCHEMA).emitter(beads).emitter(sparks)
                .layer(sparkLayer(beads, EMBER, FLAME)).layer(sparkLayer(sparks, FLAME, WHITE)).build();
        float angle = -(float) Math.atan2(nx, 1f), rampMid = (rampFrom + rampTo) / 2f;
        float rampLength = (rampTo - rampFrom) * (float) Math.sqrt(1f + nx * nx);
        stations.add(new Station("obstacles: a sphere, a cube in a ramp, the floor", x, z, look, List.of(beads),
                new float[][]{{sx, sy, 0f}}, p -> {
                    p.sphere(sphereX, sphereY, 0f, sphereR - 0.02f);
                    transform.identity().scale(2f * cubeHalf);
                    p.box(cubeX, cubeY, 0f, transform);
                    transform.identity().rotateZ(angle).translate(0f, -0.05f, 0f).scale(rampLength, 0.1f, 6f);
                    p.box(rampMid, rampY - nx * rampMid, 0f, transform);
                }));
    }

    /**
     * Godot's damping (left, gold) beside drag (right, cyan), as a graph: each row's columns fire pucks straight up at 1
     * to 7 blocks a second, with no gravity, so each stops where its brake alone stops it. Damping brakes at a constant
     * rate, so a puck stops at speed squared over twice it and the row's tops trace a parabola; drag brakes in proportion
     * to speed, so they stop at speed over it, a straight line. Both reach 3.5 blocks at 7.
     */
    private void dampingAndDrag(float x, float z) {
        CgVfxLook.Builder look = CgVfxLook.builder(SCHEMA);
        List<CgVfxEmitter> emitters = new ArrayList<>();
        List<float[]> sources = new ArrayList<>();
        for (int row = 0; row < 2; row++) {
            boolean damping = row == 0;
            for (int c = 0; c < BRAKE_COLUMNS; c++) {
                float speed = c + 1f;
                CgVfxEmitter puck = CgVfxEmitter.builder((damping ? "damping" : "drag") + c)
                        .renderer(CgVfxEmitter.Renderer.QUADS).capacity(8)
                        .burst(0f, 1).burst(1.3f, 1).burst(2.6f, 1).burst(3.9f, 1).launch(1f, 1f, 1f).speed(speed, speed)
                        .life(2.6f, 2.6f).size(0.12f, 0.12f, 1f).heat(1f)
                        .module(damping ? new CgVfxModule.Damping(7f) : new CgVfxModule.Drag(2f, 0f))
                        .opacity(CgKeyframes.start(0f, 0f).to(0.03f, 1f, CgEasings.OUT_QUAD).to(0.85f, 1f, CgEasings.LINEAR)
                                .to(1f, 0f, CgEasings.IN_QUAD).build()).build();
                look.emitter(puck).layer(damping ? sparkLayer(puck, GOLD, WHITE) : sparkLayer(puck, CYAN, WHITE));
                emitters.add(puck);
                sources.add(new float[]{brakeColumn(row, c), 0.3f, 0f});
            }
        }
        stations.add(new Station("damping: Damping (gold) beside Drag (cyan)", x, z, look.build(), emitters,
                sources.toArray(new float[0][]), p -> {
                    for (int row = 0; row < 2; row++) {
                        float middle = (brakeColumn(row, 0) + brakeColumn(row, BRAKE_COLUMNS - 1)) / 2f;
                        transform.identity().scale(BRAKE_SPACING * BRAKE_COLUMNS, 0.06f, 0.3f);
                        p.box(middle, 0.27f, 0f, transform);
                        p.label(middle, 4.7f, 0f, 0.42f, row == 0 ? "damping: a steady brake" : "drag: brakes with speed",
                                0xFFFFFFFF);
                        p.label(middle, 4.2f, 0f, 0.26f, row == 0 ? "stops at speed squared: a curve"
                                : "stops at speed: a straight line", 0xFFB8D8FF);
                        p.label(middle, 0.55f, 0.5f, 0.22f, "1 to 7 blocks a second, left to right", 0xFFB8D8FF);
                    }
                }));
    }

    /** The damping station's columns a row, and how far apart. */
    private static final int BRAKE_COLUMNS = 7;
    private static final float BRAKE_SPACING = 0.75f;

    /** Where column {@code c} of {@code row} stands: damping's row left of the middle, drag's right. */
    private static float brakeColumn(int row, int c) {
        return (row == 0 ? -1.25f - (BRAKE_COLUMNS - 1) * BRAKE_SPACING : 1.25f) + c * BRAKE_SPACING;
    }

    /** Godot's velocity limit: the same fountain with (gold) and without (cyan) a 3 blocks a second ceiling. */
    private void limit(float x, float z) {
        CgVfxEmitter free = CgVfxEmitter.builder("free").renderer(CgVfxEmitter.Renderer.QUADS).capacity(1500)
                .rate(120f, 0f, PERIOD - 3f).launch(0.85f, 1f, 1f).speed(9f, 12f).life(3f, 3.5f)
                .size(0.08f, 0.12f, 1f).heat(1f)
                .module(new CgVfxModule.Gravity(9.8f))
                .module(new CgVfxModule.Collide(Volume.plane(0f, 1f, 0f).at(0f, -0.3f, 0f), 0.3f, 0.3f, 0.5f))
                .opacity(FADE).build();
        CgVfxEmitter limited = free.toBuilder().name("limited").module(new CgVfxModule.LimitSpeed(3f)).build();
        CgVfxLook look = CgVfxLook.builder(SCHEMA).emitter(free).emitter(limited)
                .layer(sparkLayer(free, CYAN, WHITE)).layer(sparkLayer(limited, GOLD, WHITE)).build();
        stations.add(new Station("limit: LimitSpeed 3 (gold) beside none (cyan)", x, z, look, List.of(free, limited),
                new float[][]{{3f, 0.3f, 0f}, {-3f, 0.3f, 0f}}, null));
    }

    /**
     * Flipbooks: a smoke column (left), each puff the smoke sheet over its life, lifted by its heat as it cools and
     * rolled by slow curl noise as it spreads; and a fire (right), upright flames on a looping sheet, lofted by their
     * heat and reddening as they cool, with embers and its own smoke rising off it.
     */
    private void flipbooks(float x, float z, CgTexture smokeSheet, CgTexture fireSheet) {
        CgVfxEmitter puffs = CgVfxEmitter.builder("puffs").renderer(CgVfxEmitter.Renderer.QUADS).capacity(300)
                .rate(18f, 0f, PERIOD - 3f).shape(0.5f).launch(0.8f, 1f, 1f).speed(0.8f, 1.5f).life(3f, 4f)
                .size(0.7f, 1.1f, 1f).spin(0.05f, 0.35f).heat(0.7f)
                .module(new CgVfxModule.Buoyancy(3f, 1.5f))
                .module(new CgVfxModule.Turbulence(1.4f, 0.3f, 0.35f))
                .module(new CgVfxModule.Drag(0.7f, 0f))
                .size(CgKeyframes.start(0f, 0.55f).to(1f, 3f, CgEasings.OUT_CUBIC).build())
                .opacity(CgKeyframes.start(0f, 0f).to(0.15f, 0.95f, CgEasings.OUT_QUAD).to(0.6f, 0.75f, CgEasings.LINEAR)
                        .to(1f, 0f, CgEasings.IN_QUAD).build()).build();
        CgVfxEmitter fire = CgVfxEmitter.builder("fire").renderer(CgVfxEmitter.Renderer.QUADS).capacity(300)
                .rate(55f, 0f, PERIOD - 3f).shape(0.4f).launch(0.85f, 1f, 1f).speed(0.4f, 1f).life(0.6f, 1f)
                .size(0.55f, 0.9f, 1.5f).heat(1f)
                .module(new CgVfxModule.Buoyancy(5f, 1.2f))
                .module(new CgVfxModule.Turbulence(2.5f, 0.9f, 1.4f))
                .module(new CgVfxModule.Drag(1.6f, 0f))
                .size(CgKeyframes.start(0f, 0.7f).to(0.3f, 1.1f, CgEasings.OUT_QUAD).to(1f, 0.55f, CgEasings.IN_QUAD).build())
                .opacity(CgKeyframes.start(0f, 0f).to(0.1f, 1f, CgEasings.OUT_QUAD).to(0.7f, 0.9f, CgEasings.LINEAR)
                        .to(1f, 0f, CgEasings.IN_QUAD).build()).build();
        CgVfxEmitter embers = CgVfxEmitter.builder("fireEmbers").renderer(CgVfxEmitter.Renderer.QUADS).capacity(200)
                .rate(18f, 0f, PERIOD - 3f).shape(0.3f).launch(0.8f, 1f, 1f).speed(1f, 2.5f).life(1.2f, 2.2f)
                .size(0.025f, 0.05f, 1.5f).heat(1f)
                .module(new CgVfxModule.Buoyancy(7f, 0.9f))
                .module(new CgVfxModule.Turbulence(7f, 1.1f, 1.5f))
                .module(new CgVfxModule.Drag(0.8f, 0f))
                .opacity(CgKeyframes.start(0f, 1f).to(1f, 0f, CgEasings.IN_QUAD).build()).build();
        CgVfxEmitter soot = CgVfxEmitter.builder("fireSmoke").renderer(CgVfxEmitter.Renderer.QUADS).capacity(100)
                .rate(9f, 0.4f, PERIOD - 3f).shape(0.3f).launch(0.9f, 1f, 1f).speed(0.4f, 0.8f).life(2.5f, 3.5f)
                .size(0.6f, 0.9f, 1f).spin(0.1f, 0.4f).heat(0.5f)
                .module(new CgVfxModule.Buoyancy(2.5f, 2f))
                .module(new CgVfxModule.Turbulence(1.2f, 0.35f, 0.3f))
                .module(new CgVfxModule.Drag(0.6f, 0f))
                .size(CgKeyframes.start(0f, 0.6f).to(1f, 3.2f, CgEasings.OUT_CUBIC).build())
                .opacity(CgKeyframes.start(0f, 0f).to(0.2f, 1f, CgEasings.OUT_QUAD).to(1f, 0f, CgEasings.IN_QUAD).build())
                .build();
        Consumer<CgShaderBindings> smoke = b -> b.sampler("_Sheet", 0, smokeSheet).vec4("_Frames", 8f, 8f, 64f, 1f)
                .vec4("_Flip", 0f, 0f, 1f, 0f);
        CgVfxLook look = CgVfxLook.builder(SCHEMA).emitter(puffs).emitter(fire).emitter(embers).emitter(soot)
                .layer(layerOf("sprite", puffs, SMOKE, SMOKE, smoke))
                .layer(layerOf("sprite", soot, SOOT, SOOT, smoke))
                .layer(layerOf("sprite_glow", fire, FIRE_COOL, FIRE_HOT,
                        b -> b.sampler("_Sheet", 0, fireSheet).vec4("_Frames", 4f, 4f, 16f, 2f).vec4("_Flip", 1f, 1f, 1f, 0f)
                                .set1f("_Facing", 2f)))
                .layer(sparkLayer(embers, EMBER, FLAME))
                .build();
        stations.add(new Station("flipbooks: a smoke column (left), a fire with embers and smoke (right)", x, z, look,
                List.of(puffs, fire, embers, soot),
                new float[][]{{-2.5f, 0.5f, 0f}, {2.5f, 0.2f, 0f}, {2.5f, 0.3f, 0f}, {2.5f, 1.6f, 0f}}, null));
    }

    /**
     * The five facing modes, left to right, each on the effect it is for: twinkling glints (camera), runes spiralling up
     * (camera position), spirit flames that stay standing seen from above (upright), a fountain of sparks streaked along
     * their arcs (velocity), and shockwaves flat on the floor (axis).
     */
    private void facings(float x, float z, CgTexture star, CgTexture rune, CgTexture wisp, CgTexture dot,
                         CgTexture ring) {
        CgVfxEmitter glints = CgVfxEmitter.builder("faceCamera").renderer(CgVfxEmitter.Renderer.QUADS).capacity(120)
                .rate(16f, 0f, PERIOD - 3f).shape(1f).speed(0.05f, 0.2f).life(1.5f, 2.5f).size(0.2f, 0.45f, 1.5f)
                .spin(0.3f, 1.2f).heat(1f)
                .module(new CgVfxModule.Buoyancy(0.4f, 1.5f))
                .module(new CgVfxModule.Drag(1f, 0f))
                .opacity(CgKeyframes.start(0f, 0f).to(0.2f, 1f, CgEasings.OUT_QUAD).to(0.45f, 0.35f, CgEasings.IN_OUT_QUAD)
                        .to(0.7f, 1f, CgEasings.IN_OUT_QUAD).to(1f, 0f, CgEasings.IN_QUAD).build()).build();
        CgVfxEmitter runes = CgVfxEmitter.builder("facePosition").renderer(CgVfxEmitter.Renderer.QUADS).capacity(40)
                .rate(4f, 0f, PERIOD - 3.5f).shape(0.9f).launch(-0.2f, 0.2f, 1f).speed(0f, 0f).life(3f, 3.5f)
                .size(0.4f, 0.55f, 1f).spin(0.2f, 0.5f).heat(1f)
                .module(new CgVfxModule.Vortex(0f, 1f, 0f, 1.2f, -0.8f, 0f, 0f, 0f))
                .module(new CgVfxModule.Force(0f, 0.35f, 0f))
                .module(new CgVfxModule.Buoyancy(0f, 2f))
                .module(new CgVfxModule.Drag(1f, 0f))
                .opacity(FADE).build();
        CgVfxEmitter flames = CgVfxEmitter.builder("faceUpright").renderer(CgVfxEmitter.Renderer.QUADS).capacity(150)
                .rate(30f, 0f, PERIOD - 3f).shape(0.3f).launch(0.85f, 1f, 1f).speed(0.3f, 0.8f).life(0.6f, 1f)
                .size(0.55f, 0.85f, 1.5f).heat(1f)
                .module(new CgVfxModule.Buoyancy(4f, 1f))
                .module(new CgVfxModule.Turbulence(2.5f, 0.6f, 1.2f))
                .module(new CgVfxModule.Drag(1.6f, 0f))
                .size(CgKeyframes.start(0f, 0.7f).to(0.3f, 1.1f, CgEasings.OUT_QUAD).to(1f, 0.5f, CgEasings.IN_QUAD).build())
                .opacity(CgKeyframes.start(0f, 0f).to(0.1f, 1f, CgEasings.OUT_QUAD).to(1f, 0f, CgEasings.IN_QUAD).build())
                .build();
        // Near upright, so the fountain stays in its lane: 1.5 blocks out at most, bounces included.
        CgVfxEmitter sparks = CgVfxEmitter.builder("faceVelocity").renderer(CgVfxEmitter.Renderer.QUADS).capacity(250)
                .rate(90f, 0f, PERIOD - 3f).shape(0.05f).launch(0.99f, 1f, 1f).speed(5f, 7f).life(1.2f, 1.8f)
                .size(0.045f, 0.07f, 1.5f).heat(1f)
                .module(new CgVfxModule.Gravity(9.8f))
                .module(new CgVfxModule.Buoyancy(0f, 0.6f))
                .module(new CgVfxModule.Drag(0.3f, 0f))
                .module(new CgVfxModule.Collide(Volume.plane(0f, 1f, 0f).at(0f, -0.4f, 0f), 0.35f, 0.1f, 0.2f))
                .opacity(CgKeyframes.start(0f, 1f).to(0.8f, 1f, CgEasings.LINEAR).to(1f, 0f, CgEasings.IN_QUAD).build())
                .build();
        CgVfxEmitter waves = CgVfxEmitter.builder("faceAxis").renderer(CgVfxEmitter.Renderer.QUADS).capacity(8)
                .rate(1.6f, 0f, PERIOD - 3f).shape(0f).speed(0f, 0f).life(1.4f, 1.4f).size(1f, 1f, 1f).heat(1f)
                .module(new CgVfxModule.Buoyancy(0f, 0.8f))
                .size(CgKeyframes.start(0f, 0.15f).to(1f, 1.5f, CgEasings.OUT_CUBIC).build())
                .opacity(CgKeyframes.start(0f, 1f).to(1f, 0f, CgEasings.IN_QUAD).build()).build();
        CgVfxLook look = CgVfxLook.builder(SCHEMA).emitter(glints).emitter(runes).emitter(flames).emitter(sparks)
                .emitter(waves)
                .layer(facing(glints, GOLD, WHITE, star, 0, null))
                .layer(facing(runes, VIOLET, WHITE, rune, 1, null))
                .layer(facing(flames, SPIRIT_COOL, SPIRIT_HOT, wisp, 2, b -> b.vec4("_Frames", 4f, 4f, 16f, 2f)
                        .vec4("_Flip", 1f, 1f, 1f, 0f)))
                .layer(facing(sparks, GOLD, WHITE, dot, 3, b -> b.set1f("_Stretch", 0.6f).set1f("_MaxStretch", 10f)))
                .layer(facing(waves, CYAN, WHITE, ring, 4, null)).build();
        stations.add(new Station("facing: camera, camera position, upright, velocity, axis", x, z, look,
                List.of(glints, runes, flames, sparks, waves),
                new float[][]{{-7f, 2.5f, 0f}, {-3.5f, 1.8f, 0f}, {0f, 0.2f, 0f}, {3.5f, 0.4f, 0f}, {7f, 0.04f, 0f}}, p -> {
                    for (int lane = 0; lane < 5; lane++) {
                        float lx = -7f + lane * 3.5f;
                        p.label(lx, 5.2f, 0f, 0.42f, FACING_NAMES[lane], 0xFFFFFFFF);
                        p.label(lx, 4.7f, 0f, 0.26f, FACING_HINTS[lane], 0xFFB8D8FF);
                    }
                }));
    }

    /** Each facing lane's mode, and how it turns: what to look for, flying round it. */
    private static final String[] FACING_NAMES = {"camera", "camera position", "upright", "velocity", "axis"};
    private static final String[] FACING_HINTS = {"lies in the screen", "turns to your eye", "stands up, turns round y",
            "lies along its motion", "flat across an axis"};

    /** A glowing sprite of {@code sheet}, a single frame unless {@code more} says otherwise, facing as {@code mode}. */
    private static CgVfxLayer facing(CgVfxEmitter e, CgVfxParam cool, CgVfxParam hot, CgTexture sheet, int mode,
                                     Consumer<CgShaderBindings> more) {
        return layerOf("sprite_glow", e, cool, hot, b -> {
            b.sampler("_Sheet", 0, sheet).vec4("_Frames", 1f, 1f, 1f, 1f).set1f("_Facing", mode)
                    .vec4("_Axis", 0f, 1f, 0f, 0f);
            // Lying on a surface on purpose: a soft fade would fade it out.
            if (mode == 4) b.set1f("_SoftDistance", 0f);
            if (more != null) more.accept(b);
        });
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void add(String name, float x, float z, List<CgVfxEmitter> emitters, float[][] sources, CgVfxLayer layer,
                     Consumer<Props> props) {
        CgVfxLook.Builder look = CgVfxLook.builder(SCHEMA);
        for (CgVfxEmitter e : emitters) look.emitter(e);
        stations.add(new Station(name, x, z, look.layer(layer).build(), emitters, sources, props));
    }

    private static CgVfxLayer layer(String shader, CgVfxEmitter e, CgVfxParam a, CgVfxParam b, float radius,
                                    Consumer<CgShaderBindings> properties) {
        CgVfxLayer.Builder l = CgVfxLayer.builder(PARTICLE + shader + ".shader").slot(e.layer()).colors(a, b).radius(radius);
        if (properties != null) l.properties(properties);
        return l.build();
    }

    private static CgVfxLayer layerOf(String shader, CgVfxEmitter e, CgVfxParam a, CgVfxParam b,
                                      Consumer<CgShaderBindings> properties) {
        return layer(shader, e, a, b, 1f, properties);
    }

    private static CgVfxLayer sparkLayer(CgVfxEmitter e, CgVfxParam a, CgVfxParam b) {
        return layer("spark", e, a, b, 1f, null);
    }

    /** Fills one texel of a flipbook frame: {@code (u, v)} 0..1 across the frame, v up; straight-alpha RGBA. */
    private interface Texel {
        void at(int frame, float u, float v, float[] out);
    }

    /** A {@code columns} x {@code rows} sheet of {@code cell}-texel frames, frame 0 at the top left. */
    private CgTexture sheet(int columns, int rows, int cell, Texel texel) {
        int w = columns * cell, h = rows * cell;
        ByteBuffer pixels = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder());
        float[] out = new float[4];
        for (int y = 0; y < h; y++) {
            int row = rows - 1 - y / cell;
            for (int x = 0; x < w; x++) {
                int frame = row * columns + x / cell;
                texel.at(frame, (x % cell + 0.5f) / cell, (y % cell + 0.5f) / cell, out);
                for (int c = 0; c < 4; c++) pixels.put((byte) Math.round(Math.max(0f, Math.min(1f, out[c])) * 255f));
            }
        }
        pixels.flip();
        CgTexture2D texture = CgTexture2D.createEmpty(w, h, CgTextureSpec.RGBA8_LINEAR);
        texture.uploadRegion(0, 0, 0, w, h, pixels, CgGL.GL_RGBA, CgGL.GL_UNSIGNED_BYTE);
        sheets.add(texture);
        return texture;
    }

    /**
     * A smoke puff over 64 frames: a lumpy, domain-warped billow swelling out of a tight core, churning as it goes and
     * thinning to wisps, lit from above by how much of itself lies between each point and the light.
     */
    private static void smokeFrame(int frame, float u, float v, float[] out) {
        float t = frame / 63f;
        float grow = 0.5f + 0.5f * (1f - (1f - t) * (1f - t));
        float px = (u - 0.5f) / grow, py = (v - 0.5f) / grow;
        float density = smokeDensity(px, py, t);
        // Self-shadowing: the density a short step toward the light (up and a little left) dims what is under it.
        float shadow = smokeDensity(px - 0.05f, py + 0.12f, t) + 0.5f * smokeDensity(px - 0.1f, py + 0.24f, t);
        float shade = 0.38f + 0.62f * (float) Math.exp(-1.8f * shadow);
        out[0] = shade;
        out[1] = shade;
        out[2] = shade * 1.03f;
        out[3] = density * (1f - 0.8f * t * t);
    }

    /** The puff's density at {@code (x, y)} in its own units (radius about 0.5) at {@code t}, 0..1. */
    private static float smokeDensity(float x, float y, float t) {
        float wx = fbm(x * 2.2f + 3.1f, y * 2.2f - t * 0.6f, 3) - 0.5f;
        float wy = fbm(x * 2.2f - 1.7f + t * 0.4f, y * 2.2f + 5.3f, 3) - 0.5f;
        float d = fbm(x * 3f + wx * 1.6f, y * 3f + wy * 1.6f - t * 0.8f, 5);
        float r = (float) Math.sqrt(x * x + y * y) * 2f;
        // Billows: dense in the middle, a ragged edge that grows wispier as the puff spreads.
        float body = 1f - smooth(0.3f - 0.1f * t, 1.05f, r + (d - 0.5f) * (0.8f + 0.6f * t));
        return clamp01(body * (0.5f + 1.1f * (d - 0.3f)));
    }

    /**
     * A flame over 16 frames that loop: a teardrop wide at its base, its tongues torn upward by rising noise, coloured
     * by temperature: white-yellow at the root through orange to a dark red edge.
     */
    private static void fireFrame(int frame, float u, float v, float[] out) {
        float t = frame / 16f;
        // Two scrolls a loop apart, crossfaded, so frame 16 is frame 0.
        float a = flameNoise(u, v, t), b = flameNoise(u, v, t - 1f);
        float n = a + (b - a) * t;
        float dx = u - 0.5f + (n - 0.5f) * 0.3f * v;
        float width = 0.34f * (float) Math.pow(1f - v, 0.6f) + 0.02f;
        // Soft sides, so overlapping flames blend rather than showing each one's outline.
        float shape = 1f - smooth(0.15f, 1f, Math.abs(dx) / width);
        float heat = clamp01((shape * (1.05f - v * 1.1f) + (n - 0.5f) * 0.8f * v) * smooth(0f, 0.08f, v));
        flameColour(heat, out);
        out[3] = smooth(0.03f, 0.6f, heat) * (0.55f + 0.45f * shape);
    }

    /** Fine flame noise rising {@code t} loops: 4 lattice cells a loop, stretched upward. */
    private static float flameNoise(float u, float v, float t) {
        return fbm(u * 5f + 7.3f, v * 3f - t * 4f, 4);
    }

    /** A blackbody-like ramp, by heat: dark red, orange, yellow, pale yellow. Each stop is a heat, then RGB. */
    private static final float[][] FLAME_STOPS = {{0f, 0.25f, 0.02f, 0f}, {0.3f, 0.85f, 0.18f, 0.02f},
            {0.55f, 1f, 0.45f, 0.05f}, {0.8f, 1f, 0.75f, 0.25f}, {1f, 1f, 0.92f, 0.6f}};

    private static void flameColour(float heat, float[] out) {
        float[][] s = FLAME_STOPS;
        int i = 1;
        while (i < s.length - 1 && heat > s[i][0]) i++;
        float k = clamp01((heat - s[i - 1][0]) / (s[i][0] - s[i - 1][0]));
        for (int c = 0; c < 3; c++) out[c] = s[i - 1][c + 1] + (s[i][c + 1] - s[i - 1][c + 1]) * k;
    }

    private static void dot(float u, float v, float[] out) {
        float dx = u - 0.5f, dy = v - 0.5f, r = (float) Math.sqrt(dx * dx + dy * dy) * 2f;
        out[0] = out[1] = out[2] = 1f;
        out[3] = (float) Math.exp(-r * r * 5f) * smooth(1f, 0.85f, r);
    }

    /** A four-pointed glint: thin rays along u and v, fainter diagonals, round a bright core. */
    private static void star(float u, float v, float[] out) {
        float dx = Math.abs(u - 0.5f) * 2f, dy = Math.abs(v - 0.5f) * 2f, r2 = dx * dx + dy * dy;
        float cross = (float) (Math.exp(-dx * 30f) * Math.max(0f, 1f - dy) + Math.exp(-dy * 30f) * Math.max(0f, 1f - dx));
        float ex = (dx + dy) * 0.7071f, ey = Math.abs(dx - dy) * 0.7071f;
        float diagonal = 0.4f * (float) Math.exp(-ey * 40f) * Math.max(0f, 1f - ex * 1.6f);
        out[0] = out[1] = out[2] = 1f;
        out[3] = clamp01(cross + diagonal + (float) Math.exp(-r2 * 25f));
    }

    /** A rune circle: two rings, ticks between them, and a triangle inside. */
    private static void rune(float u, float v, float[] out) {
        float x = (u - 0.5f) * 2f, y = (v - 0.5f) * 2f, r = (float) Math.sqrt(x * x + y * y);
        float ink = Math.max(line(Math.abs(r - 0.9f), 0.035f), line(Math.abs(r - 0.66f), 0.02f));
        double angle = Math.atan2(y, x);
        float tick = (float) Math.abs(Math.IEEEremainder(angle, Math.PI / 8)) * r;
        if (r > 0.7f && r < 0.86f) ink = Math.max(ink, line(tick, 0.018f));
        for (int k = 0; k < 3; k++) {
            double a0 = Math.PI / 2 + k * 2 * Math.PI / 3, a1 = a0 + 2 * Math.PI / 3;
            ink = Math.max(ink, line(segment(x, y, 0.62f * (float) Math.cos(a0), 0.62f * (float) Math.sin(a0),
                    0.62f * (float) Math.cos(a1), 0.62f * (float) Math.sin(a1)), 0.022f));
        }
        out[0] = out[1] = out[2] = 1f;
        out[3] = Math.max(ink, 0.12f * (1f - smooth(0.6f, 0.95f, r)));
    }

    /** An ice shard: a long diamond, one facet lit and one in shade, a bright ridge between and a bright rim. */
    private static void shard(float u, float v, float[] out) {
        float dx = (u - 0.5f) / 0.22f, dy = (v - 0.5f) / 0.47f, d = Math.abs(dx) + Math.abs(dy);
        float shade = dx > 0f ? 1f : 0.55f, ridge = (float) Math.exp(-dx * dx * 60f), rim = smooth(0.7f, 0.95f, d);
        out[0] = out[1] = out[2] = clamp01(shade * (0.6f + 0.4f * rim) + ridge * 0.5f);
        out[3] = (1f - smooth(0.9f, 1f, d)) * (0.55f + 0.45f * Math.max(rim, ridge));
    }

    /** A shockwave: a bright thin edge trailing a soft glow inward. */
    private static void ring(float u, float v, float[] out) {
        float dx = u - 0.5f, dy = v - 0.5f, r = (float) Math.sqrt(dx * dx + dy * dy) * 2f;
        float edge = (r - 0.88f) / 0.04f, trail = (r - 0.72f) / 0.16f;
        out[0] = out[1] = out[2] = 1f;
        out[3] = clamp01((float) (Math.exp(-edge * edge) + 0.35f * Math.exp(-trail * trail)) * (r < 0.98f ? 1f : 0f));
    }

    /** {@link #fireFrame} in grey, brightest where hottest, for a layer's colours to tint. */
    private static void wispFrame(int frame, float u, float v, float[] out) {
        fireFrame(frame, u, v, out);
        out[0] = out[1] = out[2] = clamp01(0.2f + (out[0] + out[1] + out[2]) / 2.5f);
    }

    /** Ink of a stroke {@code half} wide at {@code distance} from its middle, antialiased over a texel or so. */
    private static float line(float distance, float half) {
        return 1f - smooth(half - 0.012f, half + 0.012f, distance);
    }

    /** The distance from {@code (x, y)} to the segment from {@code (ax, ay)} to {@code (bx, by)}. */
    private static float segment(float x, float y, float ax, float ay, float bx, float by) {
        float ex = bx - ax, ey = by - ay;
        float t = clamp01(((x - ax) * ex + (y - ay) * ey) / (ex * ex + ey * ey));
        float px = x - ax - ex * t, py = y - ay - ey * t;
        return (float) Math.sqrt(px * px + py * py);
    }

    private static float smooth(float e0, float e1, float x) {
        float t = Math.max(0f, Math.min(1f, (x - e0) / (e1 - e0)));
        return t * t * (3f - 2f * t);
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    /** {@link #noise}'s fractal, 0..1: octaves doubling in frequency, halving in weight, turned so they never line up. */
    private static float fbm(float x, float y, int octaves) {
        float sum = 0f, amplitude = 0.5f, norm = 0f;
        for (int k = 0; k < octaves; k++) {
            sum += amplitude * noise(x, y);
            norm += amplitude;
            float rx = 0.8f * x - 0.6f * y, ry = 0.6f * x + 0.8f * y;
            x = rx * 2.03f + 1.7f;
            y = ry * 2.03f - 0.9f;
            amplitude *= 0.5f;
        }
        return sum / norm;
    }

    /** Smooth value noise, 0..1. */
    private static float noise(float x, float y) {
        int ix = (int) Math.floor(x), iy = (int) Math.floor(y);
        float fx = x - ix, fy = y - iy;
        fx = fx * fx * (3f - 2f * fx);
        fy = fy * fy * (3f - 2f * fy);
        float a = hash(ix, iy), b = hash(ix + 1, iy), c = hash(ix, iy + 1), d = hash(ix + 1, iy + 1);
        return a + (b - a) * fx + (c - a) * fy + (a - b - c + d) * fx * fy;
    }

    private static float hash(int x, int y) {
        int h = x * 374761393 + y * 668265263;
        h = (h ^ (h >>> 13)) * 1274126177;
        return ((h ^ (h >>> 16)) & 0xFFFFFF) / (float) 0xFFFFFF;
    }

    // ── frame ─────────────────────────────────────────────────────────────────

    private final Props props = new Props() {
        private final Matrix4f scaled = new Matrix4f();

        @Override
        public void sphere(float x, float y, float z, float radius) {
            scaled.identity().scale(radius);
            world.draw(sphere, mercury).at(currentX + x, currentY + y, currentZ + z).transform(scaled).submit();
        }

        @Override
        public void box(float x, float y, float z, Matrix4f transform) {
            world.draw(cube, copper).at(currentX + x, currentY + y, currentZ + z).transform(transform).submit();
        }

        @Override
        public void label(float x, float y, float z, float height, String text, int color) {
            if (font == null) return;
            world.text(text).at(currentX + x, currentY + y, currentZ + z).height(height).font(font).color(color)
                    .stroke(0.08f, 0xFF000000).submit();
        }
    };
    private double currentX, currentY, currentZ;

    /**
     * Plays every station round {@code (x, y, z)} each {@link #PERIOD} seconds and submits this frame's draws: once a
     * frame, before the world stages record.
     */
    public void submit(CgWorldRenderer world, double x, double y, double z, float seconds) {
        this.world = world;
        if (seconds >= nextPlay) {
            for (int i = 0; i < stations.size(); i++) {
                Station s = stations.get(i);
                double floor = CgWorldQueries.groundBelow(x + s.x(), y + 16.0, z + s.z(), 48);
                floors[i] = (float) (Double.isNaN(floor) ? y : floor);
                vfx.play(new Play(s, x, floors[i], z));
            }
            nextPlay = seconds + PERIOD;
        }
        vfx.update(seconds);
        vfx.submit(world);
        for (int i = 0; i < stations.size(); i++) {
            Station s = stations.get(i);
            currentX = x + s.x();
            currentY = floors[i];
            currentZ = z + s.z();
            if (font != null) {
                // Its name over it, and what it shows under that.
                int split = s.name().indexOf(": ");
                String title = split < 0 ? s.name() : s.name().substring(0, split);
                world.text(title).at(currentX, currentY + 10.2, currentZ).height(1.1f).font(font)
                        .stroke(0.08f, 0xFF000000).submit();
                if (split >= 0) {
                    world.text(s.name().substring(split + 2)).at(currentX, currentY + 9.1, currentZ).height(0.6f)
                            .font(font).color(0xFFB8D8FF).stroke(0.08f, 0xFF000000).submit();
                }
            }
            if (s.props() != null) s.props().accept(props);
        }
    }

    /** Warms every station's kernels and programs, so its first play stalls no frame. On the render thread. */
    public void prepare() {
        for (Station s : stations) vfx.prepare(s.look());
        CgWorldRenderer world = CgWorldRenderer.get();
        world.prepare(mercury);
        world.prepare(copper);
    }

    /** Ends every station's play at once; the next {@link #submit} plays them all again. */
    public void clear() {
        vfx.clear();
        nextPlay = 0f;
    }

    /** The system the stations play in. */
    public CgVfxSystem vfx() {
        return vfx;
    }

    /** Stops every station and frees what it made. The font is the caller's. */
    public void delete() {
        vfx.delete();
        for (CgTexture sheet : sheets) sheet.delete();
        sheets.clear();
    }
}
