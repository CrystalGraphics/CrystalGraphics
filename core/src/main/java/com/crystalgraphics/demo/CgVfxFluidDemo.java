package com.crystalgraphics.demo;

import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.easing.CgEasings;
import com.crystalgraphics.easing.CgKeyframes;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.shape.primitive.CgBox;
import com.crystalgraphics.shape.primitive.CgRing;
import com.crystalgraphics.shape.primitive.CgSphere;
import com.crystalgraphics.variable.CgVar;
import com.crystalgraphics.vfx.CgVfx;
import com.crystalgraphics.vfx.CgVfxEffect;
import com.crystalgraphics.vfx.CgVfxFrame;
import com.crystalgraphics.vfx.CgVfxOwner;
import com.crystalgraphics.vfx.CgVfxSystem;
import com.crystalgraphics.vfx.effect.CgSmokeGrenade;
import com.crystalgraphics.vfx.fluid.CgVfxColormap;
import com.crystalgraphics.vfx.fluid.CgVfxFluid;
import com.crystalgraphics.vfx.fluid.CgVfxSmoke;
import com.crystalgraphics.vfx.grid.CgVfxGrid3D;
import com.crystalgraphics.vfx.look.CgVfxKit;
import com.crystalgraphics.vfx.look.CgVfxLayer;
import com.crystalgraphics.vfx.look.CgVfxLook;
import com.crystalgraphics.vfx.look.CgVfxParam;
import com.crystalgraphics.vfx.look.CgVfxSchema;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.CgVfxEmitterInstance;
import com.crystalgraphics.vfx.particle.CgVfxModule;
import com.crystalgraphics.vfx.particle.spawn.CgVfxEmitterState;
import com.crystalgraphics.world.CgWorldQueries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * The engine's gas and smoke, a station each in a grid with a mushroom cloud towering behind it: the fluid engine
 * ({@link CgVfxFluid}) playing scenes the engines it ports ship as code (NVIDIA Flow's editor stage {@code sphere},
 * Mantaflow's {@code fire.py} and {@code simpleplume.py}); a CS2 smoke grenade with fireflies riding its churn; a fire
 * whirl spun up by its inflow; particles and fluids pushing each other (a dragon's breath of particles driving the
 * flame, spirits carving wakes through a fog bank, a comet's sparks feeding its trail); and particles splatting grids
 * ({@link CgVfxGrid3D}: a slime mould, smoke made of particles beside the same particles as sprites, a nebula). Every
 * station plays again every {@link #PERIOD} seconds, standing on the world's ground under it, else on the origin's
 * height. The harness's {@code vfx-fluid}, and a scene of {@link CgRenderDemo}.
 *
 * <pre>{@code
 * CgVfxFluidDemo fluid = new CgVfxFluidDemo(font);     // on the render thread
 * CgWorld.client().schedule().add(CgTickGroup.SUBMIT, "mymod:fluid", dt -> fluid.submit(world, x, y, z, CgFrameClock.seconds()));
 * CgVfxFluidDemo.Shot shot = fluid.shots().get(3);     // where a camera stands to frame the fourth station
 * fluid.delete();
 * }</pre>
 */
public final class CgVfxFluidDemo {

    private static final Logger LOG = LogManager.getLogger("CgVfxFluidDemo");
    private static final CgVar.Text ONLY = CgVar.text("crystalgraphics.demo.station", "", CgVar.DEV,
            "The fluid demo shows only the stations whose titles hold these words, for measuring one alone.");
    /** Seconds between plays: long enough for the mushroom cloud to rise and spread. */
    public static final float PERIOD = 30f;
    /** How far the grid reaches either side of the origin along x, in blocks. */
    public static final float REACH = 50f;
    /** Blocks between two columns of the grid, and between two rows. */
    private static final float COLUMN = 24f, ROW = 26f;
    private static final float LIFE = PERIOD + 2f;
    private static final CgVfxSchema SCHEMA = CgVfxSchema.of("crystalgraphics:demo/fluid");
    private static final CgVfxParam.Color CYAN = SCHEMA.color("cyan", 0xFF3FB8FF, 1.4f);
    private static final CgVfxParam.Color WHITE = SCHEMA.color("white", 0xFFFFFFFF, 1.6f);
    private static final CgVfxParam.Color EMBER = SCHEMA.color("ember", 0xFFFF4A0B, 1.3f);
    private static final CgVfxParam.Color FLAME = SCHEMA.color("flame", 0xFFFFC85B, 1.6f);
    private static final CgVfxParam.Color FIREFLY = SCHEMA.color("firefly", 0xFFB6FF4A, 1.6f);
    private static final CgVfxParam.Color FIREFLY_HOT = SCHEMA.color("fireflyHot", 0xFFF2FFC8, 2f);
    private static final CgVfxParam.Color NEBULA = SCHEMA.color("nebula", 0xFFFF3D9A, 1.2f);
    private static final CgVfxParam.Color NEBULA_HOT = SCHEMA.color("nebulaHot", 0xFFFFB45C, 2f);
    private static final CgVfxParam.Color STARLIGHT = SCHEMA.color("starlight", 0xFF5A8CFF, 1.2f);
    private static final CgVfxParam.Color SPIRIT = SCHEMA.color("spirit", 0xFF7FE8FF, 1.5f);
    private static final CgVfxParam.Color SPIRIT_HOT = SCHEMA.color("spiritHot", 0xFFE8FFFF, 2.2f);
    private static final CgVfxParam.Color FLASH = SCHEMA.color("flash", 0xFFFFF4E0, 6f);
    private static final String PARTICLE = "crystalgraphics:shaders/vfx/particle/";
    private static final String GRID_GLOW = "crystalgraphics:shaders/vfx/grid/glow.shader";
    private static final CgKeyframes FADE = CgKeyframes.start(0f, 0f).to(0.08f, 1f, CgEasings.OUT_QUAD)
            .to(0.8f, 1f, CgEasings.LINEAR).to(1f, 0f, CgEasings.IN_QUAD).build();
    /** Flow's editor overrides its fire map's colour scale to 1 for every stage ({@code editorFlowStage_applyOverrides}). */
    private static final CgVfxKit EDITOR_FIRE = CgVfxFluid.kit("crystalgraphics:demo/fluid/editor",
            CgVfxColormap.FIRE.withColorScale(1f));
    /**
     * A nuclear fireball cooling: white-hot, yellow, orange, a dull red glow, then brown soot, and at the air's
     * temperature the pale tan of the dust and condensed cloud it leaves. Up to 1 it is what the cloud scatters, past 1
     * what it gives off.
     */
    private static final CgVfxColormap MUSHROOM_MAP = CgVfxColormap.builder()
            .at(0f, 0.66f, 0.6f, 0.52f, 0.85f).at(0.08f, 0.44f, 0.38f, 0.32f, 0.95f)
            .at(0.22f, 0.2f, 0.12f, 0.08f, 0.95f).at(0.4f, 1.6f, 0.35f, 0.06f, 0.95f)
            .at(0.6f, 6f, 2f, 0.35f, 0.9f).at(0.8f, 18f, 10f, 3.5f, 0.85f).at(1f, 50f, 44f, 34f, 0.8f).build();
    private static final CgVfxKit MUSHROOM = CgVfxFluid.kit("crystalgraphics:demo/fluid/mushroom", MUSHROOM_MAP);

    /**
     * One station: its title and what it shows, where it stands, its look, how high its label stands and how far back
     * a camera frames it, its emitters and their sources, what it makes on a play (its fluid, grid or smoke) and what it
     * does each tick.
     */
    private record Station(String title, String subtitle, float x, float z, CgVfxLook look, float label, float view,
                           List<CgVfxEmitter> emitters, float[][] sources, Consumer<Play> setup, Driver driver) {
    }

    /**
     * Where a camera looks to frame a station, from the origin the demo plays round, and how far back it stands: the
     * harness's tour.
     */
    public record Shot(String title, float x, float y, float z, float distance) {
    }

    /** What a play does each tick between {@code before} and {@code age}: moving its sources, shooting its smoke. */
    private interface Driver {
        void tick(Play play, float before, float age);
    }

    private static final Driver NOTHING = (play, before, age) -> {
    };

    private final CgVfxOwner vfx;
    private final boolean drives;
    private final CgFont font;
    private final List<Station> stations = new ArrayList<>();
    private final List<Shot> shots = new ArrayList<>();
    private float[] floors;
    private double nextPlay;

    /** A station playing: its fluid, grid or smoke, and its emitters in them, ticked and drawn until the next play. */
    private static final class Play extends CgVfxEffect<Play> {
        CgVfxFluid fluid;
        CgVfxGrid3D grid;
        CgVfxSmoke smoke;
        /** Its fluid's emitters, in the order its setup made them, for its driver to move. */
        final List<CgVfxFluid.Emitter> sources = new ArrayList<>();
        final List<CgVfxEmitterInstance> emitters = new ArrayList<>();
        private final Driver driver;

        Play(Station station, double x, double y, double z) {
            super(station.look(), x + station.x(), y, z + station.z());
            ground(y);
            station.setup().accept(this);
            if (smoke != null) fluid = smoke.fluid();
            driver = station.driver();
            for (int i = 0; i < station.emitters().size(); i++) {
                CgVfxEmitterInstance e = new CgVfxEmitterInstance(look().emitter(station.emitters().get(i).name()),
                        seed + i * 0.137f);
                float[] s = station.sources()[i];
                e.start(s[0], s[1], s[2]);
                e.ground(0f);
                if (fluid != null) e.inGas(fluid);
                if (grid != null) e.inGrid(grid);
                emitters.add(e);
            }
        }

        @Override
        protected void tick(float dt) {
            driver.tick(this, age - dt, age);
            if (smoke != null) {
                if (smoke.fill() == null) smoke.flood();
                smoke.tick(dt);
            } else if (fluid != null) {
                fluid.tick(dt);
            }
            if (grid != null) grid.tick(dt);
            for (int i = 0; i < emitters.size(); i++) tick(emitters.get(i), dt);
            if (age > PERIOD) die();
        }

        @Override
        protected void submit(CgVfxFrame frame) {
            if (smoke != null) smoke.submit(frame);
            else if (fluid != null) fluid.submit(frame);
            if (grid != null) grid.submit(frame);
            for (int i = 0; i < emitters.size(); i++) frame.particles(this, emitters.get(i));
        }
    }

    public CgVfxFluidDemo(CgFont font) {
        this(font, null);
    }

    /** As {@link #CgVfxFluidDemo(CgFont)}, played by {@code owner}: the world's system, which the engine updates and draws. */
    public CgVfxFluidDemo(CgFont font, CgVfxOwner owner) {
        drives = owner == null;
        vfx = drives ? CgVfx.owner("crystalgraphics", new CgVfxSystem()) : owner;
        this.font = font;
        // Four wide and three deep, the short stations nearest the eye and the tall ones behind, so none hides another;
        // the mushroom cloud towering behind the grid.
        float a = -1.5f * COLUMN, b = -0.5f * COLUMN, c = 0.5f * COLUMN, d = 1.5f * COLUMN;
        grenade(a, ROW);
        fogWakes(b, ROW);
        dragonBreath(c, ROW);
        mantaflowFire(d, ROW);
        slimeMould(a, 0f);
        particleSmoke(b, c, 0f);
        nebula(d, 0f);
        flowSphere(a, -ROW);
        mantaflowPlume(b, -ROW);
        fireWhirl(c, -ROW);
        comet(d, -ROW);
        mushroomCloud(0f, -ROW - 50f);
        String only = ONLY.get();
        if (!only.isEmpty()) stations.removeIf(s -> !(s.title() + ": " + s.subtitle()).contains(only));
        floors = new float[stations.size()];
        for (Station s : stations) {
            LOG.info("[vfx-fluid] {} at x {}, z {}", s.title(), s.x(), s.z());
            shots.add(new Shot(s.title(), s.x(), s.label() * 0.45f, s.z(), s.view()));
        }
    }

    /** Each station's framing, front row to back, left to right, the mushroom cloud last: what a tour steps through. */
    public List<Shot> shots() {
        return Collections.unmodifiableList(shots);
    }

    // ---- The ported engines' own scenes -------------------------------------------------------------------------------

    /**
     * Flow's editor stage {@code sphere}: its default simulation and a default sphere emitter (10 cm round, jetting up at
     * 400 cm a second, temperature 0.5 and fuel 0.8 coupled at 2), its axis +z made our +y. At a block for every 10 cm,
     * every length, speed and acceleration of Flow's is a tenth of its centimetres, so in cells and seconds it runs as
     * Flow's does at the editor's cell size override of 1 cm.
     */
    private void flowSphere(float x, float z) {
        CgVfxLook look = CgVfxLook.builder(SCHEMA).with(EDITOR_FIRE)
                .set(CgVfxFluid.CELL, 0.1f).set(CgVfxFluid.GRAVITY, 10f).set(CgVfxFluid.VELOCITY_FADE, 0.1f)
                .set(CgVfxFluid.SHADOW_ATTENUATION, 0.45f).set(CgVfxFluid.ATTENUATION, 0.5f).build();
        stations.add(new Station("Flow: sphere", "the editor's default stage, fuel burning as it jets up", x, z, look,
                22f, 34f, List.of(), new float[0][],
                // Flow's emitter stands at its origin with nothing under it; here a block over the ground.
                p -> {
                    p.fluid = new CgVfxFluid(p).place(0f, 9f, 0f).domain(3f, 9f, 3f);
                    p.sources.add(p.fluid.emitter(0f, 1.2f, 0f, 1f).velocity(0f, 40f, 0f, 2f));
                },
                (play, before, age) -> {
                    if (age > PERIOD - 6f) play.sources.get(0).off();
                }));
    }

    /**
     * Mantaflow's {@code scenes/fire.py} in its 52-cell grid at 0.12 blocks a cell: a box source half {@code res/8},
     * {@code 0.05 res}, {@code res/8} cells wide at {@code (0.5, 0.15, 0.5)} of the grid, density, heat and fuel raised to
     * wavelet noise offset by 0.75 ({@code densityInflow}, {@code valOffset}) for two thirds of its run; confinement 0.1
     * everywhere and 0.5 times the fuel ({@code vortGlobal}, {@code vortFlames}) as Flow's constant and fuel masks. It
     * burns and lifts by Flow's combustion and buoyancy, and its smoke fades by Flow's channel defaults, where the
     * script's open top clears it.
     */
    private void mantaflowFire(float x, float z) {
        float cell = 0.12f, res = 52f;
        CgVfxLook look = CgVfxLook.builder(SCHEMA).with(CgVfxFluid.FIRE)
                .set(CgVfxFluid.CELL, cell).set(CgVfxFluid.VORTICITY, 1f)
                .set(CgVfxFluid.VORTICITY_VELOCITY_MASK, 0f).set(CgVfxFluid.VORTICITY_CONSTANT_MASK, 0.1f)
                .set(CgVfxFluid.VORTICITY_FUEL_MASK, 0.5f).build();
        stations.add(new Station("Mantaflow: fire.py", "a noisy box of fuel burning", x, z, look, 12f, 22f, List.of(),
                new float[0][],
                p -> {
                    // The script's grid: res cells a side, open at the top and bottom.
                    float half = res * 0.5f * cell;
                    p.fluid = new CgVfxFluid(p).place(0f, half, 0f).domain(half, half, half);
                    p.sources.add(p.fluid.emitter(0f, 0.15f * res * cell, 0f, 1f)
                            .box(res / 8f * cell, 0.05f * res * cell, res / 8f * cell)
                            .temperature(1f, 1f).fuel(1f, 1f).smoke(1f, 1f).velocity(0f, 0f, 0f, 0f).noise(0.75f));
                },
                (play, before, age) -> {
                    if (age > PERIOD * 2f / 3f) play.sources.get(0).off();
                }));
    }

    /**
     * Mantaflow's {@code scenes/simpleplume.py} in its 64 by 96 by 64 grid at 0.15 blocks a cell: a cylinder source
     * {@code 0.14 res} round and {@code 0.02} of the grid's height each way at {@code (0.5, 0.1, 0.5)}, density raised to
     * wavelet noise offset by 0.75 for its first 100 of 250 steps; lifted by its density alone and never fading, no
     * confinement, no heat: Flow's smoke buoyancy at Flow's per-temperature default, with combustion off.
     */
    private void mantaflowPlume(float x, float z) {
        float cell = 0.15f, res = 64f, height = 1.5f * res;
        // Smoke with no heat reads the colour map's middle: at temperature 0 Flow's smoke map is nearly clear.
        CgVfxLook look = CgVfxLook.builder(SCHEMA).with(CgVfxFluid.SMOKE).set(CgVfxFluid.COLORMAP_MIN, -1f)
                .set(CgVfxFluid.CELL, cell).set(CgVfxFluid.COMBUSTION, 0f).set(CgVfxFluid.BUOYANCY_PER_SMOKE, 2f)
                .set(CgVfxFluid.SMOKE_DAMPING, 0f).set(CgVfxFluid.SMOKE_FADE, 0f).set(CgVfxFluid.VORTICITY, 0f).build();
        stations.add(new Station("Mantaflow: simpleplume.py", "a noisy disc of smoke rising", x, z, look, 20f, 32f,
                List.of(), new float[0][],
                p -> {
                    p.fluid = new CgVfxFluid(p).place(0f, height * 0.5f * cell, 0f)
                            .domain(res * 0.5f * cell, height * 0.5f * cell, res * 0.5f * cell);
                    p.sources.add(p.fluid.emitter(0f, 0.1f * height * cell, 0f, 1f)
                            .cylinder(0.14f * res * cell, 0.02f * height * cell)
                            .smoke(1f, 1f).temperature(0f, 0f).combustible(false).velocity(0f, 0f, 0f, 0f).noise(0.75f));
                },
                (play, before, age) -> {
                    if (age > PERIOD * 0.4f) play.sources.get(0).off();
                }));
    }

    // ---- Smoke and particles pushing each other -----------------------------------------------------------------------

    /**
     * A smoke grenade, as CS2's ({@link CgSmokeGrenade}): smoke flooding out over the ground round whatever stands in its
     * way, held thick and churning, a flock of fireflies wheeling through it, carried by its churn; a burst of shots
     * through it at eight seconds, each a tunnel that heals in a second and a half, the flock streaming through the holes,
     * then a grenade going off in it at ten, clearing a hole that heals over five.
     */
    private void grenade(float x, float z) {
        CgVfxEmitter flies = CgVfxEmitter.builder("smokeFireflies").state(CgVfxEmitterState.ONCE)
                .capacity(450).burst(0.5f, 450).shape(CgSphere.of(2.5))
                .speed(1f, 2f).life(LIFE, LIFE).size(0.04f, 0.06f).heat(1f).trail(12)
                .module(new CgVfxModule.Flock(1.4f, 0.45f, 3f, 1.5f, 0.7f))
                .module(new CgVfxModule.Flow(1.2f))
                .module(new CgVfxModule.Attract(CgSphere.of(2.5), 1.2f))
                .module(new CgVfxModule.Turbulence(0.8f, 0.3f, 0.5f))
                .module(new CgVfxModule.LimitSpeed(3.5f)).opacity(FADE).build();
        CgVfxLook look = CgSmokeGrenade.LOOK.derive().emitter(flies)
                .layer(CgVfxLayer.of(PARTICLE + "streaks.shader").on(flies).trails().size(0.7f).bind("_Color", FIREFLY)
                        .bind("_Core", FIREFLY_HOT).build())
                .layer(CgVfxLayer.of(PARTICLE + "spark.shader").on(flies).bind("_Color", FIREFLY).bind("_Hot", FIREFLY_HOT)
                        .build())
                .params(SCHEMA).build();
        stations.add(new Station("CS2: smoke grenade", "its flood, fireflies riding its churn, shot through and blasted open",
                x, z, look, 8f, 22f,
                // Inside the dome: open ground floods about 4 blocks tall, its top 1.5 the soft shell.
                List.of(flies), new float[][] {{0f, 1.5f, 0f}},
                p -> {
                    p.smoke = new CgVfxSmoke(p);
                    p.smoke.fluid().domain(9f, 6f, 9f);
                },
                (play, before, age) -> {
                    for (int k = 0; k < 5; k++) {
                        float at = 8f + k * 0.12f;
                        if (before < at && age >= at) {
                            double y = play.originY() + 1.2 + k * 0.15;
                            play.smoke.shot(play.originX() - 10, y, play.originZ() - 3 + k * 1.5,
                                    play.originX() + 10, y, play.originZ() - 2 + k * 1.5);
                        }
                    }
                    if (before < 10f && age >= 10f) {
                        play.smoke.blast(play.originX() + 2, play.originY() + 1, play.originZ() + 1.5, 3.5f);
                    }
                }));
    }

    /**
     * A fire whirl, as one forms over a burning pool: fuel burns only in a small core at the base, while three jets on a
     * ring round it blow air along the ring and a little inward, carrying no fuel or heat. The swirl drawn in and up
     * spins faster as it narrows, so the flame stretches into a thin, tall, turning column with smoke above it; embers
     * spiral up through it, carried by its flow and leaving a little smoke where they go.
     */
    private void fireWhirl(float x, float z) {
        CgVfxEmitter embers = CgVfxEmitter.builder("whirlEmbers").state(CgVfxEmitterState.once(PERIOD - 4f))
                .capacity(4000).rate(600f)
                .shape(CgRing.of(1.2, 2.4, 0)).launch(0.7f, 1f, 1f).speed(1f, 3f).life(2.5f, 4f).size(0.03f, 0.06f).heat(1f)
                .trail(10)
                .module(new CgVfxModule.Vortex(0f, 1f, 0f, 7f, -1.2f, 0f, 0f, 0f))
                .module(new CgVfxModule.Force(0f, 3f, 0f))
                .module(new CgVfxModule.Flow(1.8f))
                .module(new CgVfxModule.Feed(0.03f, 0f))
                .module(new CgVfxModule.Turbulence(1.2f, 0.4f, 0.6f)).opacity(FADE).build();
        // Flow's fire at its editor's colour scale, its confinement raised to hold the whirl together.
        CgVfxLook look = CgVfxLook.builder(SCHEMA).with(EDITOR_FIRE).emitter(embers)
                .layer(CgVfxLayer.of(PARTICLE + "streaks.shader").on(embers).trails().size(0.6f).bind("_Color", EMBER)
                        .bind("_Core", FLAME).build())
                .layer(CgVfxLayer.of(PARTICLE + "spark.shader").on(embers).bind("_Color", EMBER).bind("_Hot", FLAME).build())
                .set(CgVfxFluid.CELL, 0.2f).set(CgVfxFluid.VORTICITY, 2f)
                .set(CgVfxFluid.TURBULENCE, 0.6f).set(CgVfxFluid.TURBULENCE_SIZE, 1f).build();
        stations.add(new Station("fire whirl", "a burning pool's swirl, drawn in and spun up into a column", x, z, look, 16f, 26f,
                List.of(embers), new float[][] {{0f, 0.3f, 0f}},
                p -> {
                    p.fluid = new CgVfxFluid(p).place(0f, 8f, 0f).domain(5f, 8.5f, 5f);
                    p.sources.add(p.fluid.emitter(0f, 0.4f, 0f, 0.6f).fuel(1f, 3f).temperature(0.6f, 3f)
                            .velocity(0f, 2f, 0f, 1f));
                    for (int i = 0; i < 3; i++) {
                        p.sources.add(p.fluid.emitter(0f, 0.6f, 0f, 0.9f).temperature(0f, 0f).fuel(0f, 0f));
                    }
                },
                (play, before, age) -> {
                    // Three jets on a turning ring, each blowing along it and a little inward: the swirl the
                    // updraft draws in and narrows, so it spins up.
                    for (int i = 0; i < 3; i++) {
                        double a = age * 1.4 + i * Math.PI * 2 / 3;
                        float c = (float) Math.cos(a), s = (float) Math.sin(a);
                        CgVfxFluid.Emitter jet = play.sources.get(i + 1);
                        jet.at(c * 2.6f, 0.6f, s * 2.6f).velocity(-s * 7f - c * 2.5f, 0.5f, c * 7f - s * 2.5f, 3f);
                        if (age > PERIOD - 4f) jet.off();
                    }
                    if (age > PERIOD - 4f) play.sources.get(0).off();
                }));
    }

    /**
     * A dragon's breath: no fluid emitter at all, only a stream of particles from a mouth three and a half blocks up,
     * each pouring heat and a little smoke where it flies and dragging the fluid along with it (Flow's point emitter,
     * {@code Feed}'s push), so the flame is the particles' wake: a jet that rolls along the ground where it lands, curls
     * up at its edges and rises as it cools. The head sweeps side to side, its particles inheriting the swing, so the
     * stream fans like a hose; it breathes in bursts of three seconds.
     */
    private void dragonBreath(float x, float z) {
        CgVfxEmitter breath = CgVfxEmitter.builder("dragonBreath")
                .state(CgVfxEmitterState.once(PERIOD - 6f - 1f).delay(1f))
                .capacity(2400).rate(1400f)
                .direction(0f, -0.2f, -1f, 7f).speed(20f, 26f).inherit(0.6f).life(0.7f, 1f).size(0.08f, 0.14f).heat(1f)
                .trail(6)
                .module(new CgVfxModule.Drag(1.4f, 0f))
                .module(new CgVfxModule.Gravity(2f))
                .module(new CgVfxModule.Ground(0.1f, 0.5f, 0.3f, 0f))
                .module(new CgVfxModule.Feed(0.25f, 1.5f, 9f))
                .opacity(CgKeyframes.start(0f, 1f).to(0.7f, 0.8f, CgEasings.LINEAR).to(1f, 0f, CgEasings.IN_QUAD).build())
                .build();
        // Flow's fire at its editor's colour scale: white only where it is hottest, at the mouth.
        CgVfxLook look = CgVfxLook.builder(SCHEMA).with(EDITOR_FIRE).emitter(breath)
                .layer(CgVfxLayer.of(PARTICLE + "streaks.shader").on(breath).trails().size(0.5f).bind("_Color", EMBER)
                        .bind("_Core", FLAME).build())
                .set(CgVfxFluid.CELL, 0.22f).set(CgVfxFluid.BUOYANCY_PER_TEMP, 2.5f)
                .set(CgVfxFluid.VORTICITY, 1.5f).set(CgVfxFluid.TURBULENCE, 1.4f).set(CgVfxFluid.TURBULENCE_SIZE, 1.2f)
                .set(CgVfxFluid.VELOCITY_DAMPING, 0.1f).set(CgVfxFluid.SMOKE_DAMPING, 0.4f).set(CgVfxFluid.SMOKE_FADE, 0.5f)
                .build();
        stations.add(new Station("dragon's breath", "particles that heat the air and drive it: the flame is their wake", x,
                z, look, 9f, 30f, List.of(breath), new float[][] {{0f, 3.5f, 6f}},
                p -> p.fluid = new CgVfxFluid(p).place(0f, 4f, -4f).domain(7f, 4.5f, 10f),
                (play, before, age) -> {
                    CgVfxEmitterInstance mouth = play.emitters.get(0);
                    mouth.moveTo((float) Math.sin(age * 1.1) * 4f, 3.5f + (float) Math.sin(age * 2.3) * 0.4f, 6f);
                    mouth.rateScale(age % 4.5f < 3.2f ? 1f : 0f);
                }));
    }

    /**
     * Spirits through a fog bank: a layer of cold smoke hugging the ground (below the air's temperature, so it sinks and
     * spreads), still until a flock of glowing spirits flies through it. They pour nothing; they only push (Flow's point
     * emitter's velocity coupling), so every wake curling behind them, every swirl rolling off a turn, is the fog
     * answering what moved through it, and it settles again once they pass.
     */
    private void fogWakes(float x, float z) {
        CgVfxEmitter spirits = CgVfxEmitter.builder("fogSpirits").state(CgVfxEmitterState.ONCE)
                .capacity(260).burst(0.3f, 260).shape(CgSphere.of(5))
                .speed(4f, 6f).life(LIFE, LIFE).size(0.05f, 0.08f).heat(1f).trail(16)
                .module(new CgVfxModule.Flock(1.6f, 0.5f, 3f, 1.5f, 0.8f))
                .module(new CgVfxModule.Attract(CgBox.of(6, 0.4, 6), 1.5f).at(0f, 1f, 0f))
                .module(new CgVfxModule.Turbulence(1f, 0.5f, 0.8f))
                .module(new CgVfxModule.LimitSpeed(7f))
                .module(new CgVfxModule.Feed(0f, 0f, 10f)).opacity(FADE).build();
        CgVfxLook look = CgVfxLook.builder(SCHEMA).with(CgVfxFluid.SMOKE).emitter(spirits)
                .layer(CgVfxLayer.of(PARTICLE + "streaks.shader").on(spirits).trails().size(0.6f).bind("_Color", SPIRIT)
                        .bind("_Core", SPIRIT_HOT).build())
                .layer(CgVfxLayer.of(PARTICLE + "spark.shader").on(spirits).bind("_Color", SPIRIT).bind("_Hot", SPIRIT_HOT)
                        .build())
                .set(CgVfxFluid.CELL, 0.2f).set(CgVfxFluid.COMBUSTION, 0f).set(CgVfxFluid.COLORMAP_MIN, -1f)
                .set(CgVfxFluid.SMOKE_DAMPING, 0.05f).set(CgVfxFluid.SMOKE_FADE, 0f).set(CgVfxFluid.VELOCITY_DAMPING, 0.3f)
                .set(CgVfxFluid.VORTICITY, 1.2f).set(CgVfxFluid.ATTENUATION, 2.2f).set(CgVfxFluid.DETAIL, 0.7f)
                .set(CgVfxFluid.DETAIL_SIZE, 0.4f).build();
        stations.add(new Station("spirits through a fog bank", "a flock that only pushes: every wake is the fog answering",
                x, z, look, 7f, 26f, List.of(spirits), new float[][] {{0f, 1.2f, 0f}},
                p -> {
                    p.fluid = new CgVfxFluid(p).place(0f, 3f, 0f).domain(8f, 3f, 8f);
                    p.sources.add(p.fluid.emitter(0f, 0.6f, 0f, 1f).box(7f, 0.5f, 7f).smoke(0.9f, 1.5f)
                            .temperature(-0.3f, 1f).combustible(false).velocity(0f, 0f, 0f, 0f));
                },
                (play, before, age) -> {
                    if (age > PERIOD - 6f) play.sources.get(0).off();
                }));
    }

    /**
     * A comet racing round a loop: a white-hot head shedding sparks by the block it travels, each spark falling away and
     * pouring a little smoke into the fluid, so a thin trail hangs where it has been, curls as the fluid churns it and thins
     * away before the head comes round again.
     */
    private void comet(float x, float z) {
        CgVfxEmitter head = CgVfxEmitter.builder("cometHead").state(CgVfxEmitterState.once(PERIOD - 3f))
                .capacity(1500).ratePerBlock(60f)
                .shape(CgSphere.of(0.25)).speed(0.2f, 0.6f).life(0.15f, 0.3f).size(0.12f, 0.2f).heat(1f).opacity(FADE).build();
        CgVfxEmitter sparks = CgVfxEmitter.builder("cometSparks").state(CgVfxEmitterState.once(PERIOD - 3f))
                .capacity(2500).ratePerBlock(16f)
                .shape(CgSphere.of(0.2)).speed(0.3f, 1.2f).inherit(0.1f).life(0.8f, 1.4f).size(0.03f, 0.05f).heat(1f).trail(8)
                .module(new CgVfxModule.Gravity(1.5f))
                .module(new CgVfxModule.Drag(2f, 0f))
                .module(new CgVfxModule.Feed(0.25f, 0f)).opacity(FADE).build();
        CgVfxLook look = CgVfxLook.builder(SCHEMA).with(CgVfxFluid.SMOKE).emitter(head).emitter(sparks)
                .layer(CgVfxLayer.of(PARTICLE + "spark.shader").on(head).size(1.4f).bind("_Color", FLAME).bind("_Hot", WHITE).build())
                .layer(CgVfxLayer.of(PARTICLE + "streaks.shader").on(sparks).trails().size(0.6f).bind("_Color", EMBER)
                        .bind("_Core", FLAME).build())
                .layer(CgVfxLayer.of(PARTICLE + "spark.shader").on(sparks).bind("_Color", EMBER).bind("_Hot", FLAME).build())
                // Flow's smoke with no fire, cold, read at its colour map's middle.
                .set(CgVfxFluid.CELL, 0.25f).set(CgVfxFluid.COMBUSTION, 0f).set(CgVfxFluid.COLORMAP_MIN, -1f)
                .set(CgVfxFluid.TURBULENCE, 1.2f).set(CgVfxFluid.TURBULENCE_SIZE, 1.2f).set(CgVfxFluid.ATTENUATION, 3f)
                .build();
        stations.add(new Station("comet", "a racing head whose sparks feed a smoke trail that thins behind it", x, z, look,
                12f, 30f, List.of(head, sparks), new float[][] {{9f, 4f, 0f}, {9f, 4f, 0f}},
                p -> p.fluid = new CgVfxFluid(p).place(0f, 4f, 0f).domain(11f, 4f, 11f),
                (play, before, age) -> {
                    // A tilted loop nine blocks round, once every three seconds: about nineteen blocks a second, so the
                    // trail is a line that thins away before the head comes round again.
                    double a = age * Math.PI * 2 / 3;
                    float cx = (float) Math.cos(a) * 9f, cz = (float) Math.sin(a) * 9f, cy = 4f + (float) Math.sin(a * 2) * 2f;
                    for (CgVfxEmitterInstance e : play.emitters) e.moveTo(cx, cy, cz);
                }));
    }

    // ---- Particles splatting grids ------------------------------------------------------------------------------------

    /**
     * Physarum, a slime mould (Jones 2010): twenty thousand agents each laying a trail where it goes and steering toward
     * the strongest trail its sensors find ahead, so a network of glowing veins grows, thickens where many agree and fades
     * where none follow. The trail is the grid's channel x, fading and spreading; the agents are faint sparks along it.
     */
    private void slimeMould(float x, float z) {
        CgVfxEmitter agents = CgVfxEmitter.builder("mouldAgents").capacity(20000).burst(0f, 20000).shape(CgSphere.of(4))
                .speed(2.4f, 2.6f).life(LIFE, LIFE).size(0.012f, 0.02f).heat(1f)
                // Jones 2010's ratios at 0.15-block cells and 2.5 blocks a second (a cell moved each 0.06 s): sensors 6 cells
                // out, 45 degrees off, a 45-degree turn a cell, in a domain ten sensor reaches across; shorter sensors and
                // lasting trails fold the network into spots, a domain a few reaches across into a few loops. Full under the
                // glow's hot level: a vein that strong pulls no harder, so the web never coarsens into one white tube.
                .module(new CgVfxModule.Follow(0, 0.9f, 0.785f, 13f, 1.5f))
                // Jones's agents only steer: a constant speed, and a domain they bounce off rather than a pull.
                .module(new CgVfxModule.Collide(CgSphere.of(4.5), 1f, 0f, 0f).container())
                .module(new CgVfxModule.Splat(1f, 0f, 0f, 0f)).opacity(FADE).build();
        CgVfxLook look = CgVfxLook.builder(SCHEMA).with(CgVfxGrid3D.GLOW).emitter(agents)
                .layer(CgVfxLayer.of(PARTICLE + "spark.shader").on(agents).size(0.6f).bind("_Color", CYAN).bind("_Hot", WHITE)
                        .build())
                .set(CgVfxGrid3D.CELL, 0.15f)
                .set(CgVfxGrid3D.GLOW_COLOR, 0xFF1E5BFF, 1.3f).set(CgVfxGrid3D.HOT, 0xFFAEF4FF, 3f)
                .set(CgVfxGrid3D.GLOW_GAIN, 0.35f).set(CgVfxGrid3D.HOT_AT, 2.5f).build();
        stations.add(new Station("slime mould", "twenty thousand agents laying trails and following them", x, z, look, 10f,
                22f, List.of(agents), new float[][] {{0f, 4.8f, 0f}},
                // Jones's trail: a tenth lost and a 3x3 blur each cell moved.
                p -> p.grid = new CgVfxGrid3D(p).place(0f, 4.8f, 0f).channel(0, 1.75f, 0.125f).lit(false), NOTHING));
    }

    /**
     * Smoke made of particles, beside the same particles as sprites: puffs rising from a vent through billow-sized curl
     * noise. The first station rasterizes them into a grid each frame, a soft ball each where it is drawn, and draws the
     * grid as one lit body (the sun's shadow and the sky through all of it: a bright crown, a dark core). The second draws
     * them as soft lit cards, the usual way.
     */
    private void particleSmoke(float volumeX, float spritesX, float z) {
        CgVfxEmitter.Builder rising = CgVfxEmitter.builder("smokeSprites").state(CgVfxEmitterState.once(PERIOD - 5f))
                .capacity(2400).rate(700f).shape(CgSphere.of(0.7)).launch(0.6f, 1f, 1f).speed(1f, 2.2f)
                .life(2.8f, 3.4f).size(0.7f, 0.7f)
                // Each puff eases in and swells as it leaves the vent: born whole, a step's batch at a time, the base was
                // mostly newborns and pulsed with each.
                .size(CgKeyframes.start(0f, 0.4f).to(0.25f, 1f, CgEasings.OUT_QUAD).build())
                .opacity(CgKeyframes.start(0f, 0f).to(0.12f, 1f, CgEasings.OUT_QUAD).to(0.75f, 1f, CgEasings.LINEAR)
                        .to(1f, 0f, CgEasings.IN_QUAD).build())
                .module(new CgVfxModule.Force(0f, 1.6f, 0f))
                // Swirls of a billow's size, gently: fine strong noise scattered the column into a haze.
                .module(new CgVfxModule.Turbulence(1.2f, 1.5f, 0.6f))
                .module(new CgVfxModule.Drag(0.7f, 0f));
        CgVfxEmitter cards = rising.build();
        // A hundred or so overlap at any point of the column: each faint, together opaque.
        CgVfxEmitter puffs = rising.name("smokeParticles").capacity(4500).rate(1200f)
                .rasterize(0.02f, 0f, 0f, 0f).build();
        // Opaque in its core, so it shades as a body: the sun bright on its crown, its middle and underside dark.
        CgVfxLook volume = CgVfxLook.builder(SCHEMA).params(CgVfxGrid3D.SCHEMA).emitter(puffs)
                .layer(CgVfxLayer.of("crystalgraphics:shaders/vfx/grid/smoke.shader").on(CgVfxGrid3D.SLOT)
                        .role(CgVfxLayer.Role.SMOKE).set("_Detail", 0f).build())
                .set(CgVfxGrid3D.CELL, 0.28f).set(CgVfxGrid3D.EXTINCTION, 4f)
                .set(CgVfxGrid3D.SMOKE_COLOR, 0xFFC9C6C2).build();
        stations.add(new Station("smoke made of particles", "one lit, self-shadowed body they splat", volumeX, z, volume,
                13f, 24f, List.of(puffs), new float[][] {{0f, 0.5f, 0f}},
                p -> p.grid = new CgVfxGrid3D(p).place(0f, 5.6f, 0f).channel(0), NOTHING));
        CgVfxLook sprites = CgVfxLook.builder(SCHEMA).emitter(cards)
                .layer(CgVfxLayer.of(PARTICLE + "dust.shader").on(cards).set("_Aspect", 1f).set("_Thin", 0f)
                        .set("_Color", 0.79f, 0.78f, 0.76f, 0.35f).role(CgVfxLayer.Role.SMOKE).build())
                .build();
        stations.add(new Station("the same particles as sprites", "soft cards, each lit alone", spritesX, z, sprites, 10f,
                22f, List.of(cards), new float[][] {{0f, 0.5f, 0f}}, p -> { }, NOTHING));
    }

    /**
     * A nebula: stars wheeling round a core in a flattened vortex, each splatting gas into channel x, which lingers and
     * spreads into glowing filaments, and starlight into channel y, which fades fast and stays sharp; two glow layers
     * draw them, the gas pink to orange where it is thick, the starlight blue.
     */
    private void nebula(float x, float z) {
        CgVfxEmitter stars = CgVfxEmitter.builder("nebulaStars").state(CgVfxEmitterState.ONCE)
                .capacity(12000).burst(0f, 12000).shape(CgRing.of(1.2, 5.2, 0.5))
                .speed(0.5f, 1.2f).life(LIFE, LIFE).size(0.015f, 0.03f, 2f).heat(1f)
                // At the speed limit the pull holds an orbit of 2.5² / 1.2, 5.2 blocks: inside the grid's 7.7.
                .module(new CgVfxModule.Vortex(0f, 1f, 0f, 1.2f, -1.2f, 0f, 0f, 0f))
                .module(new CgVfxModule.Attract(CgSphere.of(1), 0.6f))
                .module(new CgVfxModule.Turbulence(0.9f, 0.35f, 0.3f))
                .module(new CgVfxModule.LimitSpeed(2.5f))
                .module(new CgVfxModule.Splat(0.1f, 0.4f, 0f, 0f)).opacity(FADE).build();
        CgVfxLook look = CgVfxLook.builder(SCHEMA).params(CgVfxGrid3D.SCHEMA).emitter(stars)
                .layer(CgVfxLayer.of(GRID_GLOW).on(CgVfxGrid3D.SLOT).set("_Channel", 0f).set("_Gain", 0.5f).set("_HotAt", 3f)
                        .set("_Detail", 0.75f).set("_DetailSize", 0.8f).bind("_Glow", NEBULA).bind("_Hot", NEBULA_HOT).build())
                .layer(CgVfxLayer.of(GRID_GLOW).on(CgVfxGrid3D.SLOT).set("_Channel", 1f).set("_Gain", 0.25f).set("_HotAt", 2f)
                        .set("_Absorb", 0f).bind("_Glow", STARLIGHT).bind("_Hot", WHITE).build())
                .layer(CgVfxLayer.of(PARTICLE + "spark.shader").on(stars).size(0.7f).bind("_Color", STARLIGHT).bind("_Hot", WHITE)
                        .build())
                .set(CgVfxGrid3D.CELL, 0.32f).build();
        stations.add(new Station("nebula", "stars wheeling round a core, splatting gas and starlight", x, z, look, 10f, 24f,
                List.of(stars), new float[][] {{0f, 5f, 0f}},
                p -> p.grid = new CgVfxGrid3D(p).place(0f, 5f, 0f).channel(0, 0.5f, 0.1f).channel(1, 3f, 0.01f).lit(false),
                NOTHING));
    }

    // ---- The mushroom cloud -------------------------------------------------------------------------------------------

    /**
     * A mushroom cloud, as a buoyant fireball makes one: a flash; a ball of fuel twelve blocks across, clear of the
     * ground, burning in a second and expanding as it burns (Flow's divergence per burn); the fireball, far hotter than
     * the air, rising and rolling (confinement weighted by heat and speed), with afterwinds lifting a narrow stem of dust
     * up under it. What makes the cap: a real one stalls where it meets a stable layer of air and is pushed out sideways
     * by the updraft still rising beneath it, its edges sinking back as the rim. Flow's air has no layers, so a slab at
     * cap height cools what reaches it to a little below the air's temperature, coupling nothing else: the fireball
     * loses its lift there, spreads into the cap and rolls under at its edge. A brief low skirt of dust runs out along
     * the ground, its particles pushing the fluid with them (Flow's point emitter); a few fragments ride the updraft
     * with thin trails. Its colour map cools it from white through orange and a dull red to brown soot and the pale tan
     * of dust and condensed cloud.
     */
    private void mushroomCloud(float x, float z) {
        CgVfxEmitter flash = CgVfxEmitter.builder("mushroomFlash").state(CgVfxEmitterState.ONCE)
                .capacity(4).burst(0f, 1).life(0.6f, 0.6f)
                .size(9f, 9f).heat(1f)
                .opacity(CgKeyframes.start(0f, 1f).to(1f, 0f, CgEasings.IN_QUAD).build()).build();
        CgVfxEmitter debris = CgVfxEmitter.builder("mushroomDebris").state(CgVfxEmitterState.ONCE)
                .capacity(400).burst(0.05f, 400)
                .shape(CgSphere.of(3)).launch(0.5f, 1f, 1f).speed(8f, 20f).life(2.5f, 4f).size(0.05f, 0.1f, 2f).heat(1f)
                .trail(14)
                .module(new CgVfxModule.Gravity(9.8f))
                .module(new CgVfxModule.Drag(0.25f, 0f))
                .module(new CgVfxModule.Flow(0.6f))
                .module(new CgVfxModule.Feed(0.06f, 0f))
                .module(new CgVfxModule.Ground(0.2f, 0.6f, 0.6f, 0f))
                .opacity(FADE).build();
        CgVfxEmitter surge = CgVfxEmitter.builder("mushroomSurge").state(CgVfxEmitterState.ONCE)
                .capacity(600).burst(0.2f, 600)
                .shape(CgRing.of(4, 7, 0)).edge().outward().speed(14f, 18f).life(2f, 3f).size(0.06f, 0.1f)
                .module(new CgVfxModule.Drag(2.6f, 0f))
                .module(new CgVfxModule.Ground(0f, 0.8f, 0.6f, 0f))
                .module(new CgVfxModule.Feed(0.15f, 0f, 2f)).opacity(FADE).build();
        CgVfxLook look = CgVfxLook.builder(SCHEMA).with(MUSHROOM).emitter(flash).emitter(debris).emitter(surge)
                .layer(CgVfxLayer.of(PARTICLE + "spark.shader").on(flash).bind("_Color", FLASH).bind("_Hot", FLASH).build())
                .layer(CgVfxLayer.of(PARTICLE + "streaks.shader").on(debris).trails().size(0.7f).bind("_Color", EMBER)
                        .bind("_Core", FLAME).build())
                .layer(CgVfxLayer.of(PARTICLE + "spark.shader").on(debris).bind("_Color", EMBER).bind("_Hot", FLAME).build())
                .layer(CgVfxLayer.of(PARTICLE + "dust.shader").on(surge).set("_Color", 0.66f, 0.6f, 0.52f, 0.25f)
                        .role(CgVfxLayer.Role.SMOKE).build())
                .set(CgVfxFluid.CELL, 0.6f).set(CgVfxFluid.BUOYANCY_PER_TEMP, 2.2f).set(CgVfxFluid.COOLING_RATE, 0.3f)
                .set(CgVfxFluid.DIVERGENCE_PER_BURN, 3f)
                .set(CgVfxFluid.VELOCITY_DAMPING, 0.02f).set(CgVfxFluid.SMOKE_DAMPING, 0.02f)
                .set(CgVfxFluid.SMOKE_FADE, 0.01f)
                .set(CgVfxFluid.VORTICITY, 1.5f).set(CgVfxFluid.VORTICITY_TEMPERATURE_MASK, 1f)
                .set(CgVfxFluid.TURBULENCE, 0.4f).set(CgVfxFluid.TURBULENCE_SIZE, 4f)
                .set(CgVfxFluid.DETAIL, 0.8f).set(CgVfxFluid.DETAIL_SIZE, 1.5f)
                .set(CgVfxFluid.ATTENUATION, 1.2f).set(CgVfxFluid.SHADOW_ATTENUATION, 0.8f)
                .set(CgVfxFluid.SHADOW_MIN_INTENSITY, 0.15f).set(CgVfxFluid.SCATTERING, 0.5f).build();
        stations.add(new Station("mushroom cloud", "a fireball rising into a ring, its stem of dust, its surge along the ground",
                x, z, look, 64f, 110f, List.of(flash, debris, surge), new float[][] {{0f, 8f, 0f}, {0f, 6f, 0f}, {0f, 0.4f, 0f}},
                p -> {
                    p.fluid = new CgVfxFluid(p).place(0f, 30f, 0f).domain(20f, 30f, 20f);
                    p.sources.add(p.fluid.emitter(0f, 8f, 0f, 6f).temperature(1f, 40f).fuel(0.6f, 40f).smoke(1f, 20f)
                            .velocity(0f, 14f, 0f, 10f).divergence(8f, 30f));
                    p.sources.add(p.fluid.emitter(0f, 1.5f, 0f, 1f).cylinder(1.6f, 1.5f).temperature(0.55f, 2f)
                            .smoke(0.7f, 2f).combustible(false).velocity(0f, 12f, 0f, 3f).off());
                    // The stable layer the cap stalls under: temperature alone, a little below the air's.
                    p.sources.add(p.fluid.emitter(0f, 42f, 0f, 1f).box(20f, 7f, 20f).temperature(-0.15f, 1.5f)
                            .fuel(0f, 0f).velocity(0f, 0f, 0f, 0f));
                },
                (play, before, age) -> {
                    if (before < 0.35f && age >= 0.35f) play.sources.get(0).off();
                    if (before < 0.3f && age >= 0.3f) play.sources.get(1).on();
                    if (before < 14f && age >= 14f) play.sources.get(1).off();
                }));
    }

    /**
     * Plays every station round {@code (x, y, z)} each {@link #PERIOD} seconds and submits this frame's draws: once a
     * frame, before the world stages record.
     */
    public void submit(CgWorldRenderer world, double x, double y, double z, double seconds) {
        if (seconds >= nextPlay) {
            // A fluid or grid holds its place until its play dies: the last plays go first, so the new ones find places.
            vfx.clear();
            for (int i = 0; i < stations.size(); i++) {
                Station s = stations.get(i);
                double floor = CgWorldQueries.groundBelow(x + s.x(), y + 16.0, z + s.z(), 48);
                floors[i] = (float) (Double.isNaN(floor) ? y : floor);
                vfx.play(new Play(s, x, floors[i], z));
            }
            nextPlay = seconds + PERIOD;
        }
        if (drives) {
            vfx.system().update(seconds);
            vfx.system().submit(world);
        }
        if (font == null) return;
        for (int i = 0; i < stations.size(); i++) {
            Station s = stations.get(i);
            float size = Math.max(1.1f, s.view() / 26f);
            double lx = x + s.x(), ly = floors[i] + s.label(), lz = z + s.z();
            world.text(s.title()).at(lx, ly + size, lz).height(size).font(font).stroke(0.08f, 0xFF000000).submit();
            world.text(s.subtitle()).at(lx, ly, lz).height(size * 0.55f).font(font).color(0xFFB8D8FF)
                    .stroke(0.08f, 0xFF000000).submit();
        }
    }

    /** Warms every station's programs and kernels, so its first play stalls no frame. On the render thread. */
    public void prepare() {
        for (Station s : stations) vfx.prepare(s.look());
    }

    public boolean warmed() {
        return vfx.warmed();
    }

    /** Ends every play at once; the next {@link #submit} plays them all again. */
    public void clear() {
        if (drives) vfx.system().clear();
        else vfx.clear();
        nextPlay = 0f;
    }

    public CgVfxSystem vfx() {
        return vfx.system();
    }

    /** Stops every station: its own system, or its plays on the world's. The font is the caller's. */
    public void delete() {
        if (drives) vfx.system().delete();
        else vfx.clear();
    }
}
