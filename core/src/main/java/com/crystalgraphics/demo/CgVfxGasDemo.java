package com.crystalgraphics.demo;

import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.easing.CgEasings;
import com.crystalgraphics.easing.CgKeyframes;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.shape.primitive.CgSphere;
import com.crystalgraphics.vfx.CgVfx;
import com.crystalgraphics.vfx.CgVfxEffect;
import com.crystalgraphics.vfx.CgVfxFrame;
import com.crystalgraphics.vfx.CgVfxOwner;
import com.crystalgraphics.vfx.CgVfxSystem;
import com.crystalgraphics.vfx.gas.CgVfxGas;
import com.crystalgraphics.vfx.gas.CgVfxGasAtlas;
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
import java.util.function.Function;

/**
 * Gas on the GPU ({@link CgVfxGas}), a station a brick of the atlas, each set up as Houdini Pyro sets up its own: a smoke
 * grenade's spinning jets flooding a dome of heavy smoke along the ground as CS2's does, punched open by two blasts and
 * healing; a bonfire's fuel burning into licking flames under a tall, leaning plume of soot, embers riding it; a geyser
 * of steam roaring up in pulses and billowing out white; and a mushroom cloud, a fireball ballooning off the ground,
 * rising into a rolling ring on a stem of dust over a base surge, its glow cooling into soot. Every station plays again
 * every {@link #PERIOD} seconds, each standing on the world's ground under it, else on the origin's height, which the gas
 * stands on where there is no world. The harness's {@code vfx-gas}, and a scene of {@link CgRenderDemo}.
 *
 * <pre>{@code
 * CgVfxGasDemo gas = new CgVfxGasDemo(font);     // on the render thread
 * world.onFrame(view -> gas.submit(world, x, y, z, CgFrameClock.seconds()));
 * gas.delete();
 * }</pre>
 */
public final class CgVfxGasDemo {

    private static final Logger LOG = LogManager.getLogger("CgVfxGasDemo");
    /** Seconds between plays: the cloud has thinned by then. */
    public static final float PERIOD = 26f;
    /** How far the stations reach either side of the origin along x, in blocks: what a camera frames. */
    public static final float REACH = 68f;
    private static final CgVfxSchema SCHEMA = CgVfxSchema.of("crystalgraphics:demo/gas");
    private static final CgVfxParam.Color EMBER = SCHEMA.color("ember", 0xFFFF4A0B, 1.2f);
    private static final CgVfxParam.Color FLAME = SCHEMA.color("flame", 0xFFFFC85B, 1.4f);
    private static final CgVfxParam.Color DEBRIS = SCHEMA.color("debris", 0xFF3A322B);
    private static final String PARTICLE = "crystalgraphics:shaders/vfx/particle/";
    private static final CgKeyframes FADE = CgKeyframes.start(0f, 0f).to(0.1f, 1f, CgEasings.OUT_QUAD)
            .to(0.7f, 1f, CgEasings.LINEAR).to(1f, 0f, CgEasings.IN_QUAD).build();

    /** Embers off the bonfire: riding its plume where the gas carries them, falling where it lets them go. */
    private static final CgVfxEmitter EMBERS = CgVfxEmitter.builder("gasEmbers").capacity(400).rate(45f, 0f, PERIOD - 4f)
            .shape(CgSphere.of(0.7)).speed(0.5f, 1.5f).life(2.5f, 4f).size(0.03f, 0.06f).heat(1f).trail(10)
            .module(new CgVfxModule.Flow(2.5f)).module(new CgVfxModule.Gravity(1.2f))
            .module(new CgVfxModule.Turbulence(0.8f, 0.3f, 0.5f)).opacity(FADE).build();
    /** Debris the mushroom cloud's blast throws: whirled up into the rising ring, then falling out of it. */
    private static final CgVfxEmitter RUBBLE = CgVfxEmitter.builder("gasRubble").capacity(500).burst(0.1f, 500)
            .shape(CgSphere.of(1.5)).launch(0.3f, 1f, 1f).speed(6f, 16f).life(6f, 9f).size(0.06f, 0.14f, 2f)
            .module(new CgVfxModule.Flow(1.5f)).module(new CgVfxModule.Gravity(9.8f))
            .module(new CgVfxModule.Ground(0.2f, 0.6f, 0.5f)).opacity(FADE).build();

    /**
     * One station: where it stands, its look, how high its label stands, what drives its sources, and its emitters,
     * which the gas carries.
     */
    private record Station(String name, float x, float z, CgVfxLook look, float label, Function<CgVfxGas, Driver> driver,
                           List<CgVfxEmitter> emitters) {
        Station(String name, float x, float z, CgVfxLook look, float label, Function<CgVfxGas, Driver> driver) {
            this(name, x, z, look, label, driver, List.of());
        }
    }

    /** A play's sources, set up on its gas and moved each tick between {@code before} and {@code age}. */
    private interface Driver {
        void tick(float before, float age);
    }

    private final CgVfxOwner vfx;
    private final boolean drives;
    private final CgFont font;
    private final List<Station> stations = new ArrayList<>();
    private float[] floors;
    private float nextPlay;

    /** A station playing: its gas and its emitters, carried by it, ticked and drawn until the next play. */
    private static final class Play extends CgVfxEffect<Play> {
        private final CgVfxGas gas;
        private final Driver driver;
        private final List<CgVfxEmitterInstance> emitters = new ArrayList<>();

        Play(Station station, double x, double y, double z) {
            super(station.look(), x + station.x(), y, z + station.z());
            ground(y);
            gas = new CgVfxGas(this).place(0f, 2f, 0f);
            driver = station.driver().apply(gas);
            for (int i = 0; i < station.emitters().size(); i++) {
                CgVfxEmitterInstance e = new CgVfxEmitterInstance(look().emitter(station.emitters().get(i).name()),
                        seed + i * 0.137f);
                e.start(0f, 0.6f, 0f);
                e.ground(0f);
                e.inGas(gas);
                emitters.add(e);
            }
        }

        @Override
        protected void tick(float dt) {
            driver.tick(age - dt, age);
            gas.tick(dt);
            for (int i = 0; i < emitters.size(); i++) tick(emitters.get(i), dt);
            if (age > PERIOD) die();
        }

        @Override
        protected void submit(CgVfxFrame frame) {
            gas.submit(frame);
            for (int i = 0; i < emitters.size(); i++) frame.particles(this, emitters.get(i));
        }
    }

    public CgVfxGasDemo(CgFont font) {
        this(font, null);
    }

    /** As {@link #CgVfxGasDemo(CgFont)}, played by {@code owner}: the world's system, which the engine updates and draws. */
    public CgVfxGasDemo(CgFont font, CgVfxOwner owner) {
        drives = owner == null;
        vfx = drives ? CgVfx.owner("crystalgraphics", new CgVfxSystem()) : owner;
        this.font = font;
        grenade(-52f, 0f);
        bonfire(-26f, 0f);
        vent(-2f, 0f);
        mushroom(36f, 0f);
        floors = new float[stations.size()];
        for (Station s : stations) LOG.info("[vfx-gas] {} at x {}, z {}", s.name(), s.x(), s.z());
        if (stations.size() > CgVfxGasAtlas.CAPACITY) throw new IllegalStateException("more stations than the atlas holds");
    }

    /**
     * A smoke grenade, as CS2's: a canister on the ground spinning as it hisses, two jets of heavy, cool smoke sweeping
     * round it along the floor, flooding out into a dome some sixteen blocks across that rolls and curls at its edge and
     * stands, opaque. Two blasts go off in it, each throwing it out and clearing a hole that heals as the smoke flows
     * back in.
     */
    private void grenade(float x, float z) {
        CgVfxLook look = CgVfxLook.builder(SCHEMA).with(CgVfxGas.SMOKE)
                .set(CgVfxGas.CELL, 0.4f).set(CgVfxGas.BUOYANCY, 0.7f).set(CgVfxGas.WEIGHT, 0.28f)
                .set(CgVfxGas.VORTICITY, 2.2f).set(CgVfxGas.DISSIPATION, 0.03f).set(CgVfxGas.COOLING, 0.5f)
                .set(CgVfxGas.DAMPING, 0.55f).set(CgVfxGas.TURBULENCE, 2f).set(CgVfxGas.TURBULENCE_SIZE, 2.5f)
                .set(CgVfxGas.DISTURBANCE, 3f).set(CgVfxGas.DISTURBANCE_SIZE, 1.2f)
                .set(CgVfxGas.EXTINCTION, 3f).set(CgVfxGas.SCATTERING, 0.5f).set(CgVfxGas.DETAIL, 0.55f)
                .set(CgVfxGas.DETAIL_SIZE, 0.9f).set(CgVfxGas.SOOT, 0f)
                .set(CgVfxGas.SMOKE_COLOR, 0xFFD2D4D8).build();
        stations.add(new Station("smoke grenade: flooding out, blasted open, healing", x, z, look, 12f, gas -> {
            CgVfxGas.Source a = gas.source(0f, 0.3f, 0f, 0.6f).density(16f).heat(1f);
            CgVfxGas.Source b = gas.source(0f, 0.3f, 0f, 0.6f).density(16f).heat(1f);
            return (before, age) -> {
                // The canister spins as it hisses, so its two jets sweep round it along the floor.
                float turn = age * 9f, c = (float) Math.cos(turn) * 14f, s = (float) Math.sin(turn) * 14f;
                a.velocity(c, 3f, s).at(c / 40f, 0.3f, s / 40f);
                b.velocity(-c, 3f, -s).at(-c / 40f, 0.3f, -s / 40f);
                if (age > 10f) {
                    a.off();
                    b.off();
                }
                if (before < 11f && age >= 11f) gas.blast(3f, 1.2f, 2f, 5f, 28f, 1f);
                if (before < 14.5f && age >= 14.5f) gas.blast(-4f, 2f, -2f, 4.5f, 24f, 1f);
            };
        }));
    }

    /**
     * A bonfire: fuel gusting off its logs and burning fast into short, licking flames (turbulence and fine disturbance
     * tearing them, expansion pushing them up), a tall plume of soot rising off them, cooling, slowing and spreading as
     * it climbs, and embers riding it.
     */
    private void bonfire(float x, float z) {
        CgVfxLook look = CgVfxLook.builder(SCHEMA).with(CgVfxGas.SMOKE).emitter(EMBERS)
                .layer(CgVfxLayer.of(PARTICLE + "streaks.shader").on(EMBERS).trails().size(0.7f).bind("_Color", EMBER)
                        .bind("_Core", FLAME).build())
                .layer(CgVfxLayer.of(PARTICLE + "spark.shader").on(EMBERS).bind("_Color", EMBER).bind("_Hot", FLAME).build())
                .set(CgVfxGas.CELL, 0.375f).set(CgVfxGas.BUOYANCY, 2.8f).set(CgVfxGas.WEIGHT, 0f)
                .set(CgVfxGas.VORTICITY, 2.5f).set(CgVfxGas.DISSIPATION, 0.1f).set(CgVfxGas.COOLING, 0.9f)
                .set(CgVfxGas.DAMPING, 0.3f).set(CgVfxGas.BURN, 5f).set(CgVfxGas.HEAT, 3.6f)
                .set(CgVfxGas.SMOKE_AMOUNT, 0.4f)
                .set(CgVfxGas.EXPANSION, 0.5f).set(CgVfxGas.TURBULENCE, 3f).set(CgVfxGas.TURBULENCE_SIZE, 1.8f)
                .set(CgVfxGas.DISTURBANCE, 6f).set(CgVfxGas.DISTURBANCE_SIZE, 0.9f)
                .set(CgVfxGas.EXTINCTION, 2f).set(CgVfxGas.SCATTERING, 0.45f).set(CgVfxGas.DETAIL, 0.5f)
                .set(CgVfxGas.DETAIL_SIZE, 0.8f).set(CgVfxGas.SOOT, 0.5f).set(CgVfxGas.GLOW, 1.2f)
                .set(CgVfxGas.SMOKE_COLOR, 0xFF5A5550).set(CgVfxGas.FIRE, 0xFFFFE0B0).build();
        stations.add(new Station("bonfire: licking flames under a plume of soot, embers riding it", x, z, look, 24f, gas -> {
            CgVfxGas.Source logs = gas.source(0f, 0.5f, 0f, 1.3f).fuel(6f).heat(1f).density(0.1f).velocity(0f, 2f, 0f);
            CgVfxGas.Source left = gas.source(-0.7f, 0.4f, 0.3f, 0.6f).fuel(3f).heat(1f);
            CgVfxGas.Source right = gas.source(0.6f, 0.4f, -0.4f, 0.6f).fuel(3f).heat(1f);
            return (before, age) -> {
                // A fire breathes: its fuel comes in gusts, each log in its own.
                float gust = 0.7f + 0.3f * (float) (Math.sin(age * 5.1f) * Math.sin(age * 1.3f + 1f));
                logs.fuel(6f * gust).at((float) Math.sin(age * 0.9f) * 0.25f, 0.5f, (float) Math.cos(age * 0.7f) * 0.25f);
                left.fuel(3f * (0.6f + 0.4f * (float) Math.sin(age * 7.3f + 2f)));
                right.fuel(3f * (0.6f + 0.4f * (float) Math.sin(age * 6.1f + 4f)));
                if (age > PERIOD - 4f) {
                    logs.off();
                    left.off();
                    right.off();
                }
            };
        }, List.of(EMBERS)));
    }

    /**
     * A steam geyser: a hot white jet roaring up at twenty blocks a second in pulses, its shear layer billowing out
     * (turbulence at two blocks, disturbance finer), rising on its heat and thinning fast as it evaporates, bright
     * where the sun shines through it.
     */
    private void vent(float x, float z) {
        CgVfxLook look = CgVfxLook.builder(SCHEMA).with(CgVfxGas.SMOKE)
                .set(CgVfxGas.CELL, 0.375f).set(CgVfxGas.BUOYANCY, 1.8f).set(CgVfxGas.WEIGHT, 0f)
                .set(CgVfxGas.VORTICITY, 3f).set(CgVfxGas.DISSIPATION, 0.35f).set(CgVfxGas.COOLING, 0.6f)
                .set(CgVfxGas.DAMPING, 0.2f).set(CgVfxGas.TURBULENCE, 4f).set(CgVfxGas.TURBULENCE_SIZE, 2f)
                .set(CgVfxGas.DISTURBANCE, 4f).set(CgVfxGas.DISTURBANCE_SIZE, 0.9f)
                .set(CgVfxGas.EXTINCTION, 2f).set(CgVfxGas.SCATTERING, 0.78f).set(CgVfxGas.DETAIL, 0.5f)
                .set(CgVfxGas.DETAIL_SIZE, 0.7f).set(CgVfxGas.SOOT, 0f)
                .set(CgVfxGas.SMOKE_COLOR, 0xFFF4F6F8).build();
        stations.add(new Station("steam geyser: roaring up in pulses, billowing white", x, z, look, 24f, gas -> {
            CgVfxGas.Source jet = gas.source(0f, 0.3f, 0f, 0.7f).density(8f).heat(2.5f).velocity(0f, 20f, 0f);
            return (before, age) -> {
                // Each pulse surges in, roars and chokes off.
                float phase = age % 3.2f;
                boolean on = phase < 2.2f && age < PERIOD - 4f;
                if (on != jet.isOn()) {
                    if (on) jet.on();
                    else jet.off();
                }
                float surge = Math.min(1f, phase / 0.3f) * (1f - 0.3f * Math.max(0f, phase - 1.6f));
                jet.velocity(0f, 20f * surge, 0f).density(8f * surge);
            };
        }));
    }

    /**
     * A mushroom cloud, as Pyro's fireball is set up: a ground blast throwing a base surge of dust out along the floor; a
     * big charge of fuel burning white-hot in a fraction of a second, bursting by the gas it releases (expansion, bounded
     * by the fuel, so the cloud settles at its real size), its heat throwing it up into a cap that
     * rolls its edges down and in, a ring vortex, turbulence breaking it into billows; a stem of dust drawn up under it
     * by the updraft; its glow cooling from yellow through orange into dark soot as it climbs. Rubble whirls up in it and
     * falls out.
     */
    private void mushroom(float x, float z) {
        CgVfxLook look = CgVfxLook.builder(SCHEMA).with(CgVfxGas.SMOKE).emitter(RUBBLE)
                .layer(CgVfxLayer.of(PARTICLE + "speck.shader").on(RUBBLE).bind("_Color", DEBRIS).build())
                .set(CgVfxGas.CELL, 0.6f).set(CgVfxGas.BUOYANCY, 3.5f).set(CgVfxGas.WEIGHT, 0.08f)
                .set(CgVfxGas.VORTICITY, 3.5f).set(CgVfxGas.DISSIPATION, 0.03f).set(CgVfxGas.COOLING, 0.25f)
                .set(CgVfxGas.DAMPING, 0.05f).set(CgVfxGas.BURN, 8f).set(CgVfxGas.HEAT, 4.5f)
                .set(CgVfxGas.SMOKE_AMOUNT, 0.5f)
                .set(CgVfxGas.EXPANSION, 0.2f).set(CgVfxGas.TURBULENCE, 3f).set(CgVfxGas.TURBULENCE_SIZE, 5f)
                .set(CgVfxGas.DISTURBANCE, 2f).set(CgVfxGas.DISTURBANCE_SIZE, 2f)
                .set(CgVfxGas.EXTINCTION, 1.8f).set(CgVfxGas.SCATTERING, 0.55f).set(CgVfxGas.DETAIL, 0.6f)
                .set(CgVfxGas.DETAIL_SIZE, 2f).set(CgVfxGas.SOOT, 0.6f)
                .set(CgVfxGas.SMOKE_COLOR, 0xFF6B5D52).set(CgVfxGas.FIRE, 0xFFFFD8A8).build();
        stations.add(new Station("mushroom cloud: a fireball ballooning into a rolling cap, a stem and a surge", x, z, look,
                44f, gas -> {
            CgVfxGas.Source core = gas.source(0f, 4f, 0f, 5f).fuel(120f).heat(6f).density(3f).velocity(0f, 6f, 0f);
            CgVfxGas.Source stem = gas.source(0f, 0.8f, 0f, 1.8f).density(2f).heat(2f).velocity(0f, 10f, 0f).off();
            CgVfxGas.Source[] surge = new CgVfxGas.Source[4];
            for (int i = 0; i < surge.length; i++) {
                double a = i * Math.PI * 0.5 + 0.4;
                float cx = (float) Math.cos(a), cz = (float) Math.sin(a);
                surge[i] = gas.source(cx * 4f, 0.6f, cz * 4f, 1.6f).density(4f).heat(0.2f).velocity(cx * 9f, 0.5f, cz * 9f)
                        .off();
            }
            return (before, age) -> {
                if (before < 0.05f && age >= 0.05f) gas.blast(0f, 0.5f, 0f, 6f, 30f, 0f);
                if (age > 0.15f) core.off();
                if (age > 0.3f && age < 7f) stem.on();
                else stem.off();
                for (CgVfxGas.Source s : surge) {
                    if (age > 0.2f && age < 2f) s.on();
                    else s.off();
                }
            };
        }, List.of(RUBBLE)));
    }

    /**
     * Plays every station round {@code (x, y, z)} each {@link #PERIOD} seconds and submits this frame's draws: once a
     * frame, before the world stages record.
     */
    public void submit(CgWorldRenderer world, double x, double y, double z, float seconds) {
        if (seconds >= nextPlay) {
            // A gas holds its brick until its play dies: the last plays go first, so the new ones find bricks.
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

    /** Warms every station's programs and the solver's kernels, so its first play stalls no frame. On the render thread. */
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
