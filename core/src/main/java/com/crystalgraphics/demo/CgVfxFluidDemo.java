package com.crystalgraphics.demo;

import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.vfx.CgVfx;
import com.crystalgraphics.vfx.CgVfxEffect;
import com.crystalgraphics.vfx.CgVfxFrame;
import com.crystalgraphics.vfx.CgVfxOwner;
import com.crystalgraphics.vfx.CgVfxSystem;
import com.crystalgraphics.vfx.effect.CgSmokeGrenade;
import com.crystalgraphics.vfx.fluid.CgVfxColormap;
import com.crystalgraphics.vfx.fluid.CgVfxFluid;
import com.crystalgraphics.vfx.fluid.CgVfxFluidAtlas;
import com.crystalgraphics.vfx.look.CgVfxKit;
import com.crystalgraphics.vfx.look.CgVfxLook;
import com.crystalgraphics.vfx.look.CgVfxSchema;
import com.crystalgraphics.world.CgWorldQueries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * The fluid engine ({@link CgVfxFluid}) playing scenes the engines it ports ship as code, a station each: NVIDIA Flow's
 * editor stage {@code sphere} ({@code flow/source/nvfloweditor/EditorFlowStages.cpp}: its default simulation, a default
 * sphere emitter, its fire colour map at colour scale 1), at a block for every 10 cm and the editor's cell size override
 * at 1 cm; Mantaflow's {@code scenes/fire.py} and {@code scenes/simpleplume.py}: their sources, noisy inflow and
 * vorticity, in their grids' cells, under Flow's model; and a smoke grenade as CS2's ({@link CgSmokeGrenade}), shot
 * through and blasted open, healing. Every station plays again every {@link #PERIOD} seconds, standing on the world's
 * ground under it, else on the origin's height. The harness's {@code vfx-fluid}, and a scene of {@link CgRenderDemo}.
 *
 * <pre>{@code
 * CgVfxFluidDemo fluid = new CgVfxFluidDemo(font);     // on the render thread
 * world.onFrame(view -> fluid.submit(world, x, y, z, CgFrameClock.seconds()));
 * fluid.delete();
 * }</pre>
 */
public final class CgVfxFluidDemo {

    private static final Logger LOG = LogManager.getLogger("CgVfxFluidDemo");
    /** Seconds between plays. */
    public static final float PERIOD = 20f;
    /** How far the stations reach either side of the origin along x, in blocks: what a camera frames. */
    public static final float REACH = 40f;
    private static final CgVfxSchema SCHEMA = CgVfxSchema.of("crystalgraphics:demo/fluid");
    /** Flow's editor overrides its fire map's colour scale to 1 for every stage ({@code editorFlowStage_applyOverrides}). */
    private static final CgVfxKit EDITOR_FIRE = CgVfxFluid.kit("crystalgraphics:demo/fluid/editor",
            CgVfxColormap.FIRE.withColorScale(1f));

    /** One station: where it stands, its look, how high its label stands, and what sets up its emitters; none: the grenade. */
    private record Station(String name, float x, float z, CgVfxLook look, float label, float placeY,
                           Function<CgVfxFluid, Driver> driver) {
        boolean grenade() {
            return driver == null;
        }
    }

    /** A play's emitters, set up on its fluid and changed each tick by its age. */
    private interface Driver {
        void tick(float age);
    }

    private final CgVfxOwner vfx;
    private final boolean drives;
    private final CgFont font;
    private final List<Station> stations = new ArrayList<>();
    private final float[] floors;
    private float nextPlay;
    /** The smoke grenade playing, which the demo shoots and blasts; where it stands. */
    private CgSmokeGrenade grenade;
    private double grenadeX, grenadeY, grenadeZ;
    private float grenadeBefore;

    /** A station playing: its fluid, ticked and drawn until the next play. */
    private static final class Play extends CgVfxEffect<Play> {
        private final CgVfxFluid fluid;
        private final Driver driver;

        Play(Station station, double x, double y, double z) {
            super(station.look(), x + station.x(), y, z + station.z());
            ground(y);
            fluid = new CgVfxFluid(this).place(0f, station.placeY(), 0f);
            driver = station.driver().apply(fluid);
        }

        @Override
        protected void tick(float dt) {
            driver.tick(age);
            fluid.tick(dt);
            if (age > PERIOD) die();
        }

        @Override
        protected void submit(CgVfxFrame frame) {
            fluid.submit(frame);
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
        grenade(-32f);
        flowSphere(-12f);
        mantaflowFire(8f);
        mantaflowPlume(26f);
        // -Dcrystalgraphics.demo.station=<words>: only the stations whose names hold them, for measuring one alone.
        String only = System.getProperty("crystalgraphics.demo.station");
        if (only != null) stations.removeIf(s -> !s.name().contains(only));
        floors = new float[stations.size()];
        for (Station s : stations) LOG.info("[vfx-fluid] {} at x {}, z {}", s.name(), s.x(), s.z());
        if (stations.size() > CgVfxFluidAtlas.CAPACITY) throw new IllegalStateException("more stations than the atlas holds");
    }

    /**
     * A smoke grenade, as CS2's ({@link CgSmokeGrenade}): smoke flooding out over the ground round whatever stands in its
     * way, held thick and churning; a burst of shots through it at eight seconds, each a tunnel that heals in a second
     * and a half, then a grenade going off in it at ten, clearing a hole that heals over five.
     */
    private void grenade(float x) {
        stations.add(new Station("CS2: smoke grenade: its flood, shot through and blasted open, healing", x, 0f,
                CgSmokeGrenade.LOOK, 8f, 0f, null));
    }

    /** The grenade's shots and its blast, as its age passes each. */
    private void driveGrenade() {
        if (grenade == null || grenade.state() == CgVfxEffect.State.DEAD) return;
        float before = grenadeBefore, age = grenade.age();
        grenadeBefore = age;
        for (int k = 0; k < 5; k++) {
            float at = 8f + k * 0.12f;
            if (before < at && age >= at) {
                double y = grenadeY + 1.2 + k * 0.15;
                grenade.shot(grenadeX - 10, y, grenadeZ - 3 + k * 1.5, grenadeX + 10, y, grenadeZ - 2 + k * 1.5);
            }
        }
        if (before < 10f && age >= 10f) grenade.blast(grenadeX + 2, grenadeY + 1, grenadeZ + 1.5, 3.5f);
    }

    /**
     * Flow's editor stage {@code sphere}: its default simulation and a default sphere emitter (10 cm round, jetting up at
     * 400 cm a second, temperature 0.5 and fuel 0.8 coupled at 2), its axis +z made our +y. At a block for every 10 cm,
     * every length, speed and acceleration of Flow's is a tenth of its centimetres, so in cells and seconds it runs as
     * Flow's does at the editor's cell size override of 1 cm.
     */
    private void flowSphere(float x) {
        CgVfxLook look = CgVfxLook.builder(SCHEMA).with(EDITOR_FIRE)
                .set(CgVfxFluid.CELL, 0.1f).set(CgVfxFluid.GRAVITY, 10f).set(CgVfxFluid.VELOCITY_FADE, 0.1f)
                .set(CgVfxFluid.SHADOW_ATTENUATION, 0.45f).set(CgVfxFluid.ATTENUATION, 0.5f).build();
        stations.add(new Station("Flow: sphere: the editor's default stage, fuel burning as it jets up", x, 0f, look, 22f,
                10f, fluid -> {
            // Flow's emitter stands at its origin with nothing under it; here a block over the ground.
            CgVfxFluid.Emitter e = fluid.emitter(0f, 1.2f, 0f, 1f).velocity(0f, 40f, 0f, 2f);
            return age -> {
                if (age > PERIOD - 6f) e.off();
            };
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
    private void mantaflowFire(float x) {
        float cell = 0.12f, res = 52f;
        CgVfxLook look = CgVfxLook.builder(SCHEMA).with(CgVfxFluid.FIRE)
                .set(CgVfxFluid.CELL, cell).set(CgVfxFluid.VORTICITY, 1f)
                .set(CgVfxFluid.VORTICITY_VELOCITY_MASK, 0f).set(CgVfxFluid.VORTICITY_CONSTANT_MASK, 0.1f)
                .set(CgVfxFluid.VORTICITY_FUEL_MASK, 0.5f).build();
        stations.add(new Station("Mantaflow: fire.py: a noisy box of fuel burning", x, 0f, look, 12f, res * 0.5f * cell,
                fluid -> {
            CgVfxFluid.Emitter source = fluid.emitter(0f, 0.15f * res * cell, 0f, 1f)
                    .box(res / 8f * cell, 0.05f * res * cell, res / 8f * cell)
                    .temperature(1f, 1f).fuel(1f, 1f).smoke(1f, 1f).velocity(0f, 0f, 0f, 0f).noise(0.75f);
            return age -> {
                if (age > PERIOD * 2f / 3f) source.off();
            };
        }));
    }

    /**
     * Mantaflow's {@code scenes/simpleplume.py} in its 64 by 96 by 64 grid at 0.15 blocks a cell: a cylinder source
     * {@code 0.14 res} round and {@code 0.02} of the grid's height each way at {@code (0.5, 0.1, 0.5)}, density raised to
     * wavelet noise offset by 0.75 for its first 100 of 250 steps; lifted by its density alone and never fading, no
     * confinement, no heat: Flow's smoke buoyancy at Flow's per-temperature default, with combustion off.
     */
    private void mantaflowPlume(float x) {
        float cell = 0.15f, res = 64f, height = 1.5f * res;
        // Smoke with no heat reads the colour map's middle: at temperature 0 Flow's smoke map is nearly clear.
        CgVfxLook look = CgVfxLook.builder(SCHEMA).with(CgVfxFluid.SMOKE).set(CgVfxFluid.COLORMAP_MIN, -1f)
                .set(CgVfxFluid.CELL, cell).set(CgVfxFluid.COMBUSTION, 0f).set(CgVfxFluid.BUOYANCY_PER_SMOKE, 2f)
                .set(CgVfxFluid.SMOKE_DAMPING, 0f).set(CgVfxFluid.SMOKE_FADE, 0f).set(CgVfxFluid.VORTICITY, 0f).build();
        stations.add(new Station("Mantaflow: simpleplume.py: a noisy disc of smoke rising", x, 0f, look, 20f,
                height * 0.5f * cell, fluid -> {
            CgVfxFluid.Emitter source = fluid.emitter(0f, 0.1f * height * cell, 0f, 1f)
                    .cylinder(0.14f * res * cell, 0.02f * height * cell)
                    .smoke(1f, 1f).temperature(0f, 0f).combustible(false).velocity(0f, 0f, 0f, 0f).noise(0.75f);
            return age -> {
                if (age > PERIOD * 0.4f) source.off();
            };
        }));
    }

    /**
     * Plays every station round {@code (x, y, z)} each {@link #PERIOD} seconds and submits this frame's draws: once a
     * frame, before the world stages record.
     */
    public void submit(CgWorldRenderer world, double x, double y, double z, float seconds) {
        if (seconds >= nextPlay) {
            // A fluid holds its place until its play dies: the last plays go first, so the new ones find places.
            vfx.clear();
            for (int i = 0; i < stations.size(); i++) {
                Station s = stations.get(i);
                double floor = CgWorldQueries.groundBelow(x + s.x(), y + 16.0, z + s.z(), 48);
                floors[i] = (float) (Double.isNaN(floor) ? y : floor);
                if (s.grenade()) {
                    grenadeX = x + s.x();
                    grenadeY = floors[i];
                    grenadeZ = z + s.z();
                    grenadeBefore = 0f;
                    grenade = vfx.play(new CgSmokeGrenade(s.look(), grenadeX, grenadeY, grenadeZ).ground(grenadeY));
                } else {
                    vfx.play(new Play(s, x, floors[i], z));
                }
            }
            nextPlay = seconds + PERIOD;
        }
        driveGrenade();
        if (drives) {
            vfx.system().update(seconds);
            vfx.system().submit(world);
        }
        if (font == null) return;
        for (int i = 0; i < stations.size(); i++) {
            Station s = stations.get(i);
            int split = s.name().indexOf(": ", s.name().indexOf(": ") + 2);
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
