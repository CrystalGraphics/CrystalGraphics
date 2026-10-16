package com.crystalgraphics.demo;

import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.easing.CgEasings;
import com.crystalgraphics.easing.CgKeyframes;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.shape.primitive.CgRing;
import com.crystalgraphics.shape.primitive.CgSphere;
import com.crystalgraphics.vfx.CgVfx;
import com.crystalgraphics.vfx.CgVfxEffect;
import com.crystalgraphics.vfx.CgVfxFrame;
import com.crystalgraphics.vfx.CgVfxOwner;
import com.crystalgraphics.vfx.CgVfxSystem;
import com.crystalgraphics.vfx.effect.CgSmokeGrenade;
import com.crystalgraphics.vfx.fluid.CgVfxFluid;
import com.crystalgraphics.vfx.fluid.CgVfxSmoke;
import com.crystalgraphics.vfx.grid.CgVfxGrid3D;
import com.crystalgraphics.vfx.look.CgVfxLayer;
import com.crystalgraphics.vfx.look.CgVfxLook;
import com.crystalgraphics.vfx.look.CgVfxParam;
import com.crystalgraphics.vfx.look.CgVfxSchema;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.CgVfxEmitterInstance;
import com.crystalgraphics.vfx.particle.CgVfxModule;
import com.crystalgraphics.world.CgWorldQueries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Particles and smoke together, each station a pairing of G's parts: a slime mould of agents laying glowing trails and
 * following them ({@link CgVfxGrid3D}, {@code Splat}, {@code Follow}); a column of smoke made of particles, splatting a
 * lit body that pushes them apart ({@code Gradient}); a nebula of stars orbiting a core, splatting gas and starlight into
 * two channels; a fire whirl of embers spiralling up, feeding the flames they ride ({@link CgVfxFluid}, {@code Feed},
 * {@code Flow}); fireflies flocking inside a smoke grenade's smoke, carried by its churn as shots tunnel through it
 * ({@link CgVfxSmoke}, {@code Flock}); and a comet racing round a loop, its sparks feeding a smoke trail that lingers and
 * curls. Every station plays again every {@link #PERIOD} seconds, each standing on the world's ground under it, else on
 * the origin's height. The harness's {@code vfx-particle-smoke}, and a scene of {@link CgRenderDemo}.
 *
 * <pre>{@code
 * CgVfxParticleSmokeDemo demo = new CgVfxParticleSmokeDemo(font);     // on the render thread
 * world.onFrame(view -> demo.submit(world, x, y, z, CgFrameClock.seconds()));
 * demo.delete();
 * }</pre>
 */
public final class CgVfxParticleSmokeDemo {

    private static final Logger LOG = LogManager.getLogger("CgVfxParticleSmokeDemo");
    /** Seconds between plays. */
    public static final float PERIOD = 22f;
    /** How far the stations reach either side of the origin along x, in blocks: what a camera frames. */
    public static final float REACH = 60f;
    private static final float LIFE = PERIOD + 2f;
    private static final CgVfxSchema SCHEMA = CgVfxSchema.of("crystalgraphics:demo/particleSmoke");
    private static final CgVfxParam.Color CYAN = SCHEMA.color("cyan", 0xFF3FB8FF, 1.4f);
    private static final CgVfxParam.Color WHITE = SCHEMA.color("white", 0xFFFFFFFF, 1.6f);
    private static final CgVfxParam.Color EMBER = SCHEMA.color("ember", 0xFFFF4A0B, 1.3f);
    private static final CgVfxParam.Color FLAME = SCHEMA.color("flame", 0xFFFFC85B, 1.6f);
    private static final CgVfxParam.Color FIREFLY = SCHEMA.color("firefly", 0xFFB6FF4A, 1.6f);
    private static final CgVfxParam.Color FIREFLY_HOT = SCHEMA.color("fireflyHot", 0xFFF2FFC8, 2f);
    private static final CgVfxParam.Color NEBULA = SCHEMA.color("nebula", 0xFFFF3D9A, 1.2f);
    private static final CgVfxParam.Color NEBULA_HOT = SCHEMA.color("nebulaHot", 0xFFFFB45C, 2f);
    private static final CgVfxParam.Color STARLIGHT = SCHEMA.color("starlight", 0xFF5A8CFF, 1.2f);
    private static final String PARTICLE = "crystalgraphics:shaders/vfx/particle/";
    private static final String GRID_GLOW = "crystalgraphics:shaders/vfx/grid/glow.shader";
    private static final CgKeyframes FADE = CgKeyframes.start(0f, 0f).to(0.08f, 1f, CgEasings.OUT_QUAD)
            .to(0.8f, 1f, CgEasings.LINEAR).to(1f, 0f, CgEasings.IN_QUAD).build();

    /**
     * One station: where it stands, its look, how high its label stands, its emitters and their sources, what it makes on
     * a play (its fluid, grid or smoke) and what it does each tick.
     */
    private record Station(String name, float x, float z, CgVfxLook look, float label, List<CgVfxEmitter> emitters,
                           float[][] sources, Consumer<Play> setup, Driver driver) {
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
    private float[] floors;
    private float nextPlay;

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

    public CgVfxParticleSmokeDemo(CgFont font) {
        this(font, null);
    }

    /** As {@link #CgVfxParticleSmokeDemo(CgFont)}, played by {@code owner}: the world's system, which the engine updates and draws. */
    public CgVfxParticleSmokeDemo(CgFont font, CgVfxOwner owner) {
        drives = owner == null;
        vfx = drives ? CgVfx.owner("crystalgraphics", new CgVfxSystem()) : owner;
        this.font = font;
        slimeMould(-50f, 0f);
        particleSmoke(-34f, -26f, 0f);
        nebula(-10f, 0f);
        fireWhirl(10f, 0f);
        fireflies(30f, 0f);
        comet(50f, 0f);
        // -Dcrystalgraphics.demo.station=<words>: only the stations whose names hold them, for measuring one alone.
        String only = System.getProperty("crystalgraphics.demo.station");
        if (only != null) stations.removeIf(s -> !s.name().contains(only));
        floors = new float[stations.size()];
        for (Station s : stations) LOG.info("[vfx-particle-smoke] {} at x {}, z {}", s.name(), s.x(), s.z());
    }

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
        stations.add(new Station("slime mould: twenty thousand agents laying trails and following them", x, z, look, 10f,
                List.of(agents), new float[][] {{0f, 4.8f, 0f}},
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
        CgVfxEmitter.Builder rising = CgVfxEmitter.builder("smokeSprites").capacity(2400).rate(700f, 0f, PERIOD - 5f)
                .shape(CgSphere.of(0.7)).launch(0.6f, 1f, 1f).speed(1f, 2.2f).life(2.8f, 3.4f).size(0.7f, 0.7f)
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
        CgVfxEmitter puffs = rising.name("smokeParticles").capacity(4500).rate(1200f, 0f, PERIOD - 5f)
                .rasterize(0.02f, 0f, 0f, 0f).build();
        // Opaque in its core, so it shades as a body: the sun bright on its crown, its middle and underside dark.
        CgVfxLook volume = CgVfxLook.builder(SCHEMA).params(CgVfxGrid3D.SCHEMA).emitter(puffs)
                .layer(CgVfxLayer.of("crystalgraphics:shaders/vfx/grid/smoke.shader").on(CgVfxGrid3D.SLOT)
                        .role(CgVfxLayer.Role.SMOKE).set("_Detail", 0f).build())
                .set(CgVfxGrid3D.CELL, 0.28f).set(CgVfxGrid3D.EXTINCTION, 4f)
                .set(CgVfxGrid3D.SMOKE_COLOR, 0xFFC9C6C2).build();
        stations.add(new Station("smoke made of particles: one lit, self-shadowed body they splat", volumeX, z, volume, 13f,
                List.of(puffs), new float[][] {{0f, 0.5f, 0f}},
                p -> p.grid = new CgVfxGrid3D(p).place(0f, 5.6f, 0f).channel(0), NOTHING));
        CgVfxLook sprites = CgVfxLook.builder(SCHEMA).emitter(cards)
                .layer(CgVfxLayer.of(PARTICLE + "dust.shader").on(cards).set("_Aspect", 1f).set("_Thin", 0f)
                        .set("_Color", 0.79f, 0.78f, 0.76f, 0.35f).role(CgVfxLayer.Role.SMOKE).build())
                .build();
        stations.add(new Station("the same particles as sprites: soft cards, each lit alone", spritesX, z, sprites, 10f,
                List.of(cards), new float[][] {{0f, 0.5f, 0f}}, p -> { }, NOTHING));
    }

    /**
     * A nebula: stars wheeling round a core in a flattened vortex, each splatting gas into channel x, which lingers and
     * spreads into glowing filaments, and starlight into channel y, which fades fast and stays sharp; two glow layers
     * draw them, the gas pink to orange where it is thick, the starlight blue.
     */
    private void nebula(float x, float z) {
        CgVfxEmitter stars = CgVfxEmitter.builder("nebulaStars").capacity(12000).burst(0f, 12000).shape(CgRing.of(1.2, 5.2, 0.5))
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
        stations.add(new Station("nebula: stars wheeling round a core, splatting gas and starlight", x, z, look, 10f,
                List.of(stars), new float[][] {{0f, 5f, 0f}},
                p -> p.grid = new CgVfxGrid3D(p).place(0f, 5f, 0f).channel(0, 0.5f, 0.1f).channel(1, 3f, 0.01f).lit(false),
                NOTHING));
    }

    /**
     * A fire whirl: three fuel jets spinning tangentially round a burning core so the flames twist up into a column, and
     * embers spiralling up through it in a vortex, carried by the fluid's flow and pouring heat and smoke where they go, so
     * the column is fed by what rides it.
     */
    private void fireWhirl(float x, float z) {
        CgVfxEmitter embers = CgVfxEmitter.builder("whirlEmbers").capacity(4000).rate(600f, 0f, PERIOD - 4f)
                .shape(CgRing.of(1.2, 2.4, 0)).launch(0.7f, 1f, 1f).speed(1f, 3f).life(2.5f, 4f).size(0.03f, 0.06f).heat(1f)
                .trail(10)
                .module(new CgVfxModule.Vortex(0f, 1f, 0f, 7f, -1.2f, 0f, 0f, 0f))
                .module(new CgVfxModule.Force(0f, 3f, 0f))
                .module(new CgVfxModule.Flow(1.8f))
                .module(new CgVfxModule.Feed(0.15f, 0.6f))
                .module(new CgVfxModule.Turbulence(1.2f, 0.4f, 0.6f)).opacity(FADE).build();
        // Flow's fire, its confinement raised for the whirl, curl noise to break it up.
        CgVfxLook look = CgVfxLook.builder(SCHEMA).with(CgVfxFluid.FIRE).emitter(embers)
                .layer(CgVfxLayer.of(PARTICLE + "streaks.shader").on(embers).trails().size(0.6f).bind("_Color", EMBER)
                        .bind("_Core", FLAME).build())
                .layer(CgVfxLayer.of(PARTICLE + "spark.shader").on(embers).bind("_Color", EMBER).bind("_Hot", FLAME).build())
                .set(CgVfxFluid.CELL, 0.25f).set(CgVfxFluid.VORTICITY, 2f)
                .set(CgVfxFluid.TURBULENCE, 2f).set(CgVfxFluid.TURBULENCE_SIZE, 1.6f).build();
        stations.add(new Station("fire whirl: embers spiralling up, feeding the flames they ride", x, z, look, 16f,
                List.of(embers), new float[][] {{0f, 0.3f, 0f}},
                p -> {
                    p.fluid = new CgVfxFluid(p).place(0f, 2f, 0f);
                    p.sources.add(p.fluid.emitter(0f, 0.4f, 0f, 0.9f).velocity(0f, 3f, 0f, 2f));
                    for (int i = 0; i < 3; i++) p.sources.add(p.fluid.emitter(0f, 0.4f, 0f, 0.6f));
                },
                (play, before, age) -> {
                    // Three jets on a turning ring, each blowing along it: the flame twists into a whirl.
                    for (int i = 0; i < 3; i++) {
                        double a = age * 2.2 + i * Math.PI * 2 / 3;
                        float c = (float) Math.cos(a), s = (float) Math.sin(a);
                        CgVfxFluid.Emitter jet = play.sources.get(i + 1);
                        jet.at(c * 1.6f, 0.4f, s * 1.6f).velocity(-s * 9f, 2f, c * 9f, 4f);
                        if (age > PERIOD - 4f) jet.off();
                    }
                    if (age > PERIOD - 4f) play.sources.get(0).off();
                }));
    }

    /**
     * Fireflies in a smoke grenade's smoke: a flock wheeling through CS2's flood, carried by the fluid's churn as it holds
     * them, trailing light; at nine seconds a burst of shots tunnels through, and they stream through the holes as the
     * smoke heals.
     */
    private void fireflies(float x, float z) {
        CgVfxEmitter flies = CgVfxEmitter.builder("smokeFireflies").capacity(450).burst(0.5f, 450).shape(CgSphere.of(2.5))
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
        stations.add(new Station("fireflies in a smoke grenade: a flock carried by its churn, shot through", x, z, look, 9f,
                // Inside the dome: open ground floods about 4 blocks tall, its top 1.5 the soft shell.
                List.of(flies), new float[][] {{0f, 1.5f, 0f}},
                p -> p.smoke = new CgVfxSmoke(p),
                (play, before, age) -> {
                    for (int k = 0; k < 4; k++) {
                        float at = 9f + k * 0.15f;
                        if (before < at && age >= at) {
                            double y = play.originY() + 1.4 + k * 0.3;
                            play.smoke.shot(play.originX() - 10, y, play.originZ() - 2 + k * 1.3,
                                    play.originX() + 10, y, play.originZ() - 1 + k * 1.3);
                        }
                    }
                }));
    }

    /**
     * A comet racing round a loop: a white-hot head shedding sparks by the block it travels, each spark falling away and
     * pouring a little smoke into the fluid, so a thin trail hangs where it has been, curls as the fluid churns it and thins
     * away before the head comes round again.
     */
    private void comet(float x, float z) {
        CgVfxEmitter head = CgVfxEmitter.builder("cometHead").capacity(1500).ratePerBlock(60f, 0f, PERIOD - 3f)
                .shape(CgSphere.of(0.25)).speed(0.2f, 0.6f).life(0.15f, 0.3f).size(0.12f, 0.2f).heat(1f).opacity(FADE).build();
        CgVfxEmitter sparks = CgVfxEmitter.builder("cometSparks").capacity(2500).ratePerBlock(16f, 0f, PERIOD - 3f)
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
        stations.add(new Station("comet: a racing head whose sparks feed a smoke trail that thins behind it", x, z, look, 12f,
                List.of(head, sparks), new float[][] {{9f, 4f, 0f}, {9f, 4f, 0f}},
                p -> p.fluid = new CgVfxFluid(p).place(0f, 4f, 0f),
                (play, before, age) -> {
                    // A tilted loop nine blocks round, once every three seconds: about nineteen blocks a second, so the
                    // trail is a line that thins away before the head comes round again.
                    double a = age * Math.PI * 2 / 3;
                    float cx = (float) Math.cos(a) * 9f, cz = (float) Math.sin(a) * 9f, cy = 4f + (float) Math.sin(a * 2) * 2f;
                    for (CgVfxEmitterInstance e : play.emitters) e.moveTo(cx, cy, cz);
                }));
    }

    /**
     * Plays every station round {@code (x, y, z)} each {@link #PERIOD} seconds and submits this frame's draws: once a
     * frame, before the world stages record.
     */
    public void submit(CgWorldRenderer world, double x, double y, double z, float seconds) {
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
            int split = s.name().indexOf(": ");
            double lx = x + s.x(), ly = floors[i] + s.label(), lz = z + s.z();
            world.text(s.name().substring(0, split)).at(lx, ly + 1.1, lz).height(1.1f).font(font)
                    .stroke(0.08f, 0xFF000000).submit();
            world.text(s.name().substring(split + 2)).at(lx, ly, lz).height(0.6f).font(font).color(0xFFB8D8FF)
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
