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
 * Gas on the GPU ({@link CgVfxGas}), a station a brick of the atlas: a smoke grenade's cloud spreading along the ground
 * as CS2's does, punched open by two blasts and healing; a bonfire burning fuel into flame and smoke; a steam vent
 * jetting in pulses; and a mushroom cloud, a fireball rising and rolling into a ring. Every station plays again every
 * {@link #PERIOD} seconds, each standing on the world's ground under it, else on the origin's height, which the gas
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
    public static final float PERIOD = 24f;
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
     * One station: where it stands, its look, where its gas's middle is above it, what drives its sources, and its
     * emitters, which the gas carries.
     */
    private record Station(String name, float x, float z, CgVfxLook look, float height, Function<CgVfxGas, Driver> driver,
                           List<CgVfxEmitter> emitters) {
        Station(String name, float x, float z, CgVfxLook look, float height, Function<CgVfxGas, Driver> driver) {
            this(name, x, z, look, height, driver, List.of());
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
            gas = new CgVfxGas(this).place(0f, station.height(), 0f);
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
        grenade(-27f, 0f);
        bonfire(-9f, 0f);
        vent(7f, 0f);
        mushroom(28f, 0f);
        floors = new float[stations.size()];
        for (Station s : stations) LOG.info("[vfx-gas] {} at x {}, z {}", s.name(), s.x(), s.z());
        if (stations.size() > CgVfxGasAtlas.CAPACITY) throw new IllegalStateException("more stations than the atlas holds");
    }

    /**
     * A smoke grenade, as CS2's: a canister pouring heavy, cool smoke that rolls out along the ground and piles up into a
     * cloud, curling at its edges. Two blasts go off in it, each throwing it out and clearing a hole that heals as the
     * smoke flows back in.
     */
    private void grenade(float x, float z) {
        CgVfxLook look = CgVfxLook.builder(SCHEMA).with(CgVfxGas.SMOKE)
                .set(CgVfxGas.SIZE, 18f).set(CgVfxGas.BUOYANCY, 0.8f).set(CgVfxGas.WEIGHT, 0.35f)
                .set(CgVfxGas.VORTICITY, 2.5f).set(CgVfxGas.DISSIPATION, 0.035f).set(CgVfxGas.COOLING, 0.6f)
                .set(CgVfxGas.DAMPING, 0.15f).set(CgVfxGas.EXTINCTION, 2.2f)
                .set(CgVfxGas.SMOKE_COLOR, 0xFFC8C9CC).build();
        stations.add(new Station("smoke grenade: rolling out, blasted open, healing", x, z, look, 7f, gas -> {
            CgVfxGas.Source vent = gas.source(0f, 0.5f, 0f, 1.1f).density(7f).heat(0.8f).velocity(0f, 2f, 0f);
            CgVfxGas.Source hiss = gas.source(0f, 0.3f, 0f, 0.6f).velocity(0f, 6f, 0f);
            return (before, age) -> {
                // The canister rocks, so the jet wanders.
                float wobble = (float) Math.sin(age * 2.3f) * 1.5f;
                vent.velocity(wobble, 2f, (float) Math.cos(age * 1.7f) * 1.5f);
                if (age > 11f) {
                    vent.off();
                    hiss.off();
                }
                if (before < 6f && age >= 6f) gas.blast(3f, 1.5f, 1f, 4.5f, 30f, 1f);
                if (before < 9.5f && age >= 9.5f) gas.blast(-3.5f, 2.5f, -2f, 4f, 26f, 1f);
            };
        }));
    }

    /** A bonfire: fuel burning into a flickering blackbody flame, thick smoke rising off it and curling as it cools. */
    private void bonfire(float x, float z) {
        CgVfxLook look = CgVfxLook.builder(SCHEMA).with(CgVfxGas.SMOKE).emitter(EMBERS)
                .layer(CgVfxLayer.of(PARTICLE + "streaks.shader").on(EMBERS).trails().size(0.7f).bind("_Color", EMBER)
                        .bind("_Core", FLAME).build())
                .layer(CgVfxLayer.of(PARTICLE + "spark.shader").on(EMBERS).bind("_Color", EMBER).bind("_Hot", FLAME).build())
                .set(CgVfxGas.SIZE, 14f).set(CgVfxGas.BUOYANCY, 3.2f).set(CgVfxGas.WEIGHT, 0f)
                .set(CgVfxGas.VORTICITY, 3f).set(CgVfxGas.DISSIPATION, 0.12f).set(CgVfxGas.COOLING, 1.4f)
                .set(CgVfxGas.DAMPING, 0.25f).set(CgVfxGas.BURN, 2.5f).set(CgVfxGas.HEAT, 3f)
                .set(CgVfxGas.EXTINCTION, 2.8f).set(CgVfxGas.SMOKE_COLOR, 0xFF4F4C49)
                .set(CgVfxGas.FIRE, 0xFFFFD9A6, 1.6f).build();
        stations.add(new Station("bonfire: fuel burning into flame and smoke, embers riding it", x, z, look, 6f, gas -> {
            CgVfxGas.Source logs = gas.source(0f, 0.6f, 0f, 1f).fuel(3.5f).heat(2f).density(0.2f).velocity(0f, 1.5f, 0f);
            return (before, age) -> {
                // A fire breathes: its fuel comes in gusts.
                float gust = 0.7f + 0.3f * (float) (Math.sin(age * 5.1f) * Math.sin(age * 1.3f + 1f));
                logs.fuel(3.5f * gust).at((float) Math.sin(age * 0.9f) * 0.25f, 0.6f, (float) Math.cos(age * 0.7f) * 0.25f);
                if (age > PERIOD - 4f) logs.off();
            };
        }, List.of(EMBERS)));
    }

    /** A steam vent: a hot white jet in pulses, billowing out and thinning fast. */
    private void vent(float x, float z) {
        CgVfxLook look = CgVfxLook.builder(SCHEMA).with(CgVfxGas.SMOKE)
                .set(CgVfxGas.SIZE, 14f).set(CgVfxGas.BUOYANCY, 1.6f).set(CgVfxGas.WEIGHT, 0f)
                .set(CgVfxGas.VORTICITY, 3.5f).set(CgVfxGas.DISSIPATION, 0.45f).set(CgVfxGas.COOLING, 0.9f)
                .set(CgVfxGas.DAMPING, 0.2f).set(CgVfxGas.EXTINCTION, 1.6f)
                .set(CgVfxGas.SMOKE_COLOR, 0xFFF2F4F7).build();
        stations.add(new Station("steam vent: a jet in pulses", x, z, look, 6f, gas -> {
            CgVfxGas.Source jet = gas.source(0f, 0.4f, 0f, 0.55f).density(5f).heat(1.2f).velocity(0f, 11f, 0f);
            return (before, age) -> {
                boolean on = (age % 3f) < 1.6f && age < PERIOD - 3f;
                if (on != jet.isOn()) {
                    if (on) jet.on();
                    else jet.off();
                }
            };
        }));
    }

    /**
     * A mushroom cloud: a fireball thrown up off the ground, its hot core rising fastest and rolling the edges down and
     * in, into a ring vortex on a stem, burning out into dark smoke as it climbs.
     */
    private void mushroom(float x, float z) {
        CgVfxLook look = CgVfxLook.builder(SCHEMA).with(CgVfxGas.SMOKE).emitter(RUBBLE)
                .layer(CgVfxLayer.of(PARTICLE + "speck.shader").on(RUBBLE).bind("_Color", DEBRIS).build())
                .set(CgVfxGas.SIZE, 26f).set(CgVfxGas.BUOYANCY, 4.5f).set(CgVfxGas.WEIGHT, 0f)
                .set(CgVfxGas.VORTICITY, 3f).set(CgVfxGas.DISSIPATION, 0.025f).set(CgVfxGas.COOLING, 0.3f)
                .set(CgVfxGas.DAMPING, 0.08f).set(CgVfxGas.BURN, 3f).set(CgVfxGas.HEAT, 2.4f)
                .set(CgVfxGas.EXTINCTION, 2f).set(CgVfxGas.SMOKE_COLOR, 0xFF6E6560)
                .set(CgVfxGas.FIRE, 0xFFFFC890, 2f).build();
        stations.add(new Station("mushroom cloud: a fireball rolling into a ring, rubble whirled up", x, z, look, 12f, gas -> {
            CgVfxGas.Source core = gas.source(0f, 1.5f, 0f, 2.6f).fuel(40f).heat(10f).density(3f).velocity(0f, 9f, 0f);
            CgVfxGas.Source stem = gas.source(0f, 0.8f, 0f, 1.2f).density(1.5f).heat(3f).velocity(0f, 5f, 0f);
            return (before, age) -> {
                if (before < 0.1f && age >= 0.1f) gas.blast(0f, 0.5f, 0f, 4f, 14f, 0f);
                if (age > 1.1f) core.off();
                if (age > 5f) stem.off();
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
            double lx = x + s.x(), ly = floors[i] + s.height() * 2f + 1.5f, lz = z + s.z();
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
