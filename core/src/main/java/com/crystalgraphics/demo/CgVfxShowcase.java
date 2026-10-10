package com.crystalgraphics.demo;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshShapes;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.render.world.CgSortLayer;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.vfx.CgVfxTrace;
import com.crystalgraphics.vfx.look.CgVfxLook;
import com.crystalgraphics.vfx.CgVfxEffect;
import com.crystalgraphics.vfx.CgVfxSystem;
import com.crystalgraphics.vfx.effect.air.CgVfxHeatHaze;
import com.crystalgraphics.vfx.effect.beam.CgEnergyWave;
import org.joml.Matrix4f;

import java.util.Arrays;

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
 * showcase.submitSky(CgWorldRenderer.get(), cameraX, cameraY, cameraZ);             // or the sky alone, over a world
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

    /**
     * The sky, last in each world stage: where depth rejects whatever covers it before it is shaded, and its seal over
     * all that is blended. Its fade draws in {@code BACKGROUND}, under all that is blended, and the spheres in
     * {@code EFFECTS}, among the effects by distance.
     */
    private static final CgSortLayer SKY = CgSortLayer.after("crystalgraphics:showcase_sky", CgSortLayer.OVERLAY);

    private static final int SHIELD = 14, STORM = 9, BLACK_HOLE = 12, GALAXY = 13, SUPERNOVA = 11;
    /** The black hole is traced in a larger sphere than the rest, so its disk has room; the sphere itself never shows. */
    private static final float BLACK_HOLE_SIZE = 1.35f;
    /** The supernova's heart is a little larger than the rest, and its corona reaches this many hearts out. */
    private static final float SUPERNOVA_SIZE = 1.15f, CORONA_REACH = 3.2f;
    /** Bolts at the shield: seconds in flight, and how many radii out they come from. */
    private static final float BOLT_FLIGHT = 0.45f, BOLT_RANGE = 4.5f;
    /**
     * The three energy waves, a lane each on a side of the grid, relative to the floor point: each fires outward from
     * beside the grid, straight to a waypoint, then bends sharply toward one of two targets either side of its lane, in
     * turn. The Kamehameha and the Final Flash fire back to back, the Galick Gun away behind the grid; their shots are
     * staggered so their charges and blasts do not land together.
     */
    private static final Lane[] LANES = {
            new Lane("kamehameha", CgEnergyWave.kamehameha(), 0f, new float[]{-10f, 2.5f, 0f}, new float[]{-1f, 0f, 0f},
                    new float[]{-28f, 3f, 0f}, new float[][]{{-40f, 3f, -26f}, {-40f, 3f, 26f}}),
            new Lane("finalFlash", CgEnergyWave.finalFlash(), 3.3f, new float[]{10f, 2.5f, 0f}, new float[]{1f, 0f, 0f},
                    new float[]{28f, 3f, 0f}, new float[][]{{40f, 3f, 26f}, {40f, 3f, -26f}}),
            new Lane("galickGun", CgEnergyWave.galickGun(), 6.6f, new float[]{0f, 2.5f, -10f}, new float[]{0f, 0f, -1f},
                    new float[]{0f, 3f, -28f}, new float[][]{{-26f, 3f, -40f}, {26f, 3f, -40f}}),
    };
    private static final int SPHERES_ZONE = CgTrace.name("showcase.spheres");
    /** A stress lane's first shot lands this many seconds after the one before, so every beam holds at once. */
    private static final float STRESS_STAGGER = 0.1f;
    /** Seconds a wave fires before it stops; its lane's next follows CgVfxDemoControls' wait after that. */
    private static final float WAVE_HOLD = 5.4f;
    /** Heat haze on its own, at the spheres' height just in front of the front row, two spheres behind it. */
    private static final float HAZE_RADIUS = 4f;
    private static final float[] HAZE_AT = {0f, HEIGHT, 1.5f * SPACING + HAZE_RADIUS};
    /** Its strongest bend up close stays under 0.09 of the screen's height, inside the apply's copy margin of 0.1. */
    private static final float HAZE_INTENSITY = 2.2f;

    /** Where one wave fires from and at, and its look: slower so it is seen growing, harder-homing so it bends sharply. */
    private static final class Lane {

        final String name;
        final CgVfxLook look;
        final float offset;
        final float[] from, aim, via;
        final float[][] targets;

        Lane(String name, CgVfxLook base, float offset, float[] from, float[] aim, float[] via, float[][] targets) {
            this.name = name;
            this.look = base.toBuilder()
                    .set(CgEnergyWave.SPEED, 30f)
                    .set(CgEnergyWave.NAVIGATION, 5f)
                    .set(CgEnergyWave.TURN_RATE, 6f)
                    .build();
            this.offset = offset;
            this.from = from;
            this.aim = aim;
            this.via = via;
            this.targets = targets;
        }
    }

    /** A shared shape. */
    private CgMesh sphere;
    /** This showcase's own. */
    private CgMesh floor;
    private final CgMaterial[] materials = new CgMaterial[COUNT];
    private CgMaterial glow, corona, bolt, sky, horizon, seal, floorMaterial;
    private double gridX = Double.NaN, gridZ = Double.NaN;
    private final Matrix4f transform = new Matrix4f();
    /** The shield's three impacts this frame: per lane a direction from its centre and the seconds since it struck. */
    private final float[] impacts = new float[12];
    private final CgVfxSystem vfx = new CgVfxSystem();
    private final Lane[] lanes;
    /** Each lane's wave, the shot it is on, when it fired and when the next fires (NaN: not yet scheduled). */
    private final CgEnergyWave[] waves;
    private final int[] shots;
    private final float[] firedAt, nextShot;
    private CgVfxHeatHaze haze;
    private double waveX = Double.NaN, waveY, waveZ;

    /** The showcase with its three lanes. */
    public CgVfxShowcase() {
        this(LANES);
    }

    private CgVfxShowcase(Lane[] lanes) {
        this.lanes = lanes;
        this.waves = new CgEnergyWave[lanes.length];
        this.shots = filled(lanes.length, -1);
        this.firedAt = new float[lanes.length];
        this.nextShot = new float[lanes.length];
        Arrays.fill(nextShot, Float.NaN);
    }

    /**
     * The showcase with {@code beams} lanes instead of three, fired from a ring round the grid outward, across it and
     * round it, every shot of every lane holding at once: a baseline for a frame full of effects.
     *
     * <pre>{@code
     * CgVfxShowcase stress = CgVfxShowcase.stress(30);   // lanes beam00 .. beam29
     * }</pre>
     */
    public static CgVfxShowcase stress(int beams) {
        CgVfxLook[] bases = {CgEnergyWave.kamehameha(), CgEnergyWave.finalFlash(), CgEnergyWave.galickGun()};
        Lane[] lanes = new Lane[beams];
        for (int i = 0; i < beams; i++) {
            float angle = i * 2.39996f;
            float cos = (float) Math.cos(angle), sin = (float) Math.sin(angle);
            float ring = 9f + 5f * hash(i, 0, 1);
            float[] from = {cos * ring, 1.5f + 6f * hash(i, 0, 2), sin * ring};
            float rise = 0.25f * (hash(i, 0, 3) - 0.3f);
            // Outward from the grid, across it to the far side, or round it.
            float[] aim = switch (i % 3) {
                case 0 -> normalized(cos, rise, sin);
                case 1 -> normalized(-cos, rise * 0.5f, -sin);
                default -> normalized(-sin, rise, cos);
            };
            float reach = 14f + 8f * hash(i, 0, 4);
            float[] via = {from[0] + aim[0] * reach, from[1] + aim[1] * reach, from[2] + aim[2] * reach};
            float[][] targets = new float[2][];
            for (int side = 0; side < 2; side++) {
                float turn = (side == 0 ? 1f : -1f) * (0.5f + 0.6f * hash(i, side, 5));
                float c = (float) Math.cos(turn), s = (float) Math.sin(turn);
                float dx = aim[0] * c - aim[2] * s, dz = aim[0] * s + aim[2] * c;
                float onward = 25f + 10f * hash(i, side, 6);
                targets[side] = new float[]{via[0] + dx * onward, 1f + 13f * hash(i, side, 7), via[2] + dz * onward};
            }
            lanes[i] = new Lane(String.format("beam%02d", i), bases[i % 3], i * STRESS_STAGGER, from, aim, via, targets);
        }
        return new CgVfxShowcase(lanes);
    }

    private static float[] normalized(float x, float y, float z) {
        float length = (float) Math.sqrt(x * x + y * y + z * z);
        return new float[]{x / length, y / length, z / length};
    }

    /** How many lanes fire waves: three, or a stress showcase's beams. */
    public int laneCount() {
        return lanes.length;
    }

    /** Submits the sixteen spheres and their glow, on a floor point {@code (x, y, z)}, as they are at {@code seconds}. */
    public void submit(CgWorldRenderer world, double x, double y, double z, float seconds) {
        ensureResources();
        try (CgTrace.Zone ignored = CgTrace.zone(CgVfxTrace.CHANNEL, SPHERES_ZONE)) {
            spheres(world, x, y, z, seconds);
        }
        waves(world, x, y, z, seconds);
    }

    private void spheres(CgWorldRenderer world, double x, double y, double z, float seconds) {
        for (int k = 0; k < COUNT; k++) {
            int row = k / 4, column = k % 4;
            double cx = x + (column - 1.5) * SPACING;
            double cz = z + (row - 1.5) * SPACING;
            double cy = y + HEIGHT + 0.08 * Math.sin(seconds * 0.9 + k * 0.7);
            spin(k, seconds);
            if (k == SUPERNOVA) {
                transform.scale(SUPERNOVA_SIZE);
                world.draw(sphere, materials[k]).at(cx, cy, cz).transform(transform).layer(CgSortLayer.EFFECTS).submit();
                transform.identity().scale(SUPERNOVA_SIZE * CORONA_REACH);
                float flare = 1f + 0.15f * (float) Math.sin(seconds * 2.1) + 0.08f * flicker(seconds, k);
                world.draw(sphere, corona).at(cx, cy, cz).transform(transform)
                        .custom(1, 1f / CORONA_REACH, flare, 0f, 0f).layer(CgSortLayer.EFFECTS).submit();
                continue;
            }
            // Every sphere knows how high above the floor it is: the metals mirror the floor from there.
            float above = (float) (cy - y);
            if (k == SHIELD) {
                shieldImpacts(seconds);
                world.draw(sphere, materials[k]).at(cx, cy, cz).transform(transform)
                        .custom(0, impacts[0], impacts[1], impacts[2], impacts[3])
                        .custom(1, impacts[4], impacts[5], impacts[6], impacts[7])
                        .custom(2, impacts[8], impacts[9], impacts[10], impacts[11])
                        .custom(3, above, 0f, 0f, 0f).layer(CgSortLayer.EFFECTS).submit();
                boltsInFlight(world, cx, cy, cz);
                // What the field protects: a small gold core turning inside it.
                transform.identity().rotateY(-seconds * 0.6f).scale(0.45f);
                world.draw(sphere, materials[0]).at(cx, cy, cz).transform(transform).custom(3, above, 0f, 0f, 0f).layer(CgSortLayer.EFFECTS).submit();
            } else {
                world.draw(sphere, materials[k]).at(cx, cy, cz).transform(transform).custom(3, above, 0f, 0f, 0f).layer(CgSortLayer.EFFECTS).submit();
            }
            float strength = GLOW[k][3];
            // The black hole has no ball to glow round: its disk lights the floor alone.
            if (strength <= 0f || k == BLACK_HOLE) continue;
            strength *= k == STORM ? 0.55f + 0.9f * flicker(seconds, k) : 0.85f + 0.15f * (float) Math.sin(seconds * 2.3 + k);
            transform.identity().scale(GLOW_REACH[k]);
            world.draw(sphere, glow).at(cx, cy, cz).transform(transform)
                    .custom(1, GLOW[k][0], GLOW[k][1], GLOW[k][2], strength)
                    .custom(2, 1f / GLOW_REACH[k], 0f, 0f, 0f).layer(CgSortLayer.EFFECTS).submit();
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
        world.draw(sphere, sky).at(cameraX, cameraY, cameraZ).transform(transform).layer(SKY).submit();
    }

    /**
     * Submits the sky around the camera at {@code (cameraX, cameraY, cameraZ)}, over a host's world: it shows where
     * that world's own sky did, the distant terrain fades into it before the world's fog would tint it, and clouds and
     * weather the host draws after the world stages stay off it.
     */
    public void submitSky(CgWorldRenderer world, double cameraX, double cameraY, double cameraZ) {
        ensureResources();
        transform.identity().scale(80f);
        world.draw(sphere, sky).at(cameraX, cameraY, cameraZ).transform(transform).layer(SKY).submit();
        world.draw(sphere, horizon).at(cameraX, cameraY, cameraZ).transform(transform).layer(CgSortLayer.BACKGROUND).submit();
        world.draw(sphere, seal).at(cameraX, cameraY, cameraZ).transform(transform).layer(SKY).submit();
    }

    /** Makes the spheres' meshes and materials and warms every program the showcase draws with. On the render thread. */
    public void prepare() {
        ensureResources();
        CgWorldRenderer world = CgWorldRenderer.get();
        for (CgMaterial material : materials) world.prepare(material);
        for (CgMaterial material : new CgMaterial[]{glow, corona, bolt, sky, horizon, seal, floorMaterial}) {
            world.prepare(material);
        }
        for (Lane lane : lanes) vfx.prepare(lane.look);
        vfx.prepare(CgVfxHeatHaze.standard());
    }

    /** Whether everything {@link #prepare} started compiling is built. */
    public boolean warmed() {
        CgWorldRenderer world = CgWorldRenderer.get();
        boolean built = vfx.warmed();
        for (CgMaterial material : materials) built &= world.prepare(material);
        for (CgMaterial material : new CgMaterial[]{glow, corona, bolt, sky, horizon, seal, floorMaterial}) {
            built &= world.prepare(material);
        }
        return built;
    }

    /** Ends every wave and the haze at once; the next {@link #submit} starts the loop over. */
    public void clear() {
        vfx.clear();
        Arrays.fill(waves, null);
        Arrays.fill(shots, -1);
        Arrays.fill(nextShot, Float.NaN);
        haze = null;
        waveX = Double.NaN;
    }

    /** The system the showcase plays its effects through: register a {@code CgVfxMomentListener} on it to photograph their moments. */
    public CgVfxSystem vfx() {
        return vfx;
    }

    /** Releases the floor and the effects' meshes. Call on context teardown. */
    public void delete() {
        vfx.delete();
        Arrays.fill(waves, null);
        haze = null;
        waveX = Double.NaN;
        if (floor != null) floor.release();
        sphere = null;
        floor = null;
    }

    private static int[] filled(int length, int value) {
        int[] array = new int[length];
        Arrays.fill(array, value);
        return array;
    }

    /** Which lane's wave {@code effect} is, or null: what a capture names its moments by. */
    public String laneOf(CgVfxEffect effect) {
        for (Lane lane : lanes) {
            if (CgVfxDemoControls.get().sameLook(effect.look(), lane.look)) return lane.name;
        }
        return null;
    }

    /**
     * Each lane's loop around the grid at {@code (x, y, z)}: a shot from its offset on, then each {@link #WAVE_HOLD} plus
     * the demo's wait after the last,
     * alternating between its two targets, each charging, growing out, bending at the waypoint, holding, running out and
     * bursting. Starts over when the grid moves.
     */
    private void waves(CgWorldRenderer world, double x, double y, double z, float seconds) {
        if (x != waveX || y != waveY || z != waveZ) {
            for (int k = 0; k < waves.length; k++) {
                if (waves[k] != null) waves[k].kill();
                waves[k] = null;
                shots[k] = -1;
                nextShot[k] = Float.NaN;
            }
            if (haze != null) haze.kill();
            haze = vfx.play(new CgVfxHeatHaze(CgVfxHeatHaze.standard(), x + HAZE_AT[0], y + HAZE_AT[1], z + HAZE_AT[2]));
            haze.set(CgVfxHeatHaze.RADIUS, HAZE_RADIUS);
            haze.set(CgVfxHeatHaze.INTENSITY, HAZE_INTENSITY);
            waveX = x;
            waveY = y;
            waveZ = z;
        }
        CgVfxDemoControls controls = CgVfxDemoControls.get();
        for (int k = 0; k < lanes.length; k++) {
            Lane lane = lanes[k];
            if (Float.isNaN(nextShot[k])) nextShot[k] = seconds + lane.offset;
            if (seconds >= nextShot[k]) {
                shots[k]++;
                firedAt[k] = seconds;
                nextShot[k] = seconds + WAVE_HOLD + controls.waitSeconds();
                if (waves[k] != null) waves[k].stop();
                float[] target = lane.targets[shots[k] & 1];
                CgEnergyWave wave = vfx.play(new CgEnergyWave(controls.look(lane.look),
                        x + lane.from[0], y + lane.from[1], z + lane.from[2]));
                controls.apply(wave);
                wave.aim(lane.aim[0], lane.aim[1], lane.aim[2])
                        .via(x + lane.via[0], y + lane.via[1], z + lane.via[2])
                        .target(x + target[0], y + target[1], z + target[2]);
                // The grid is the floor its debris lands on.
                wave.ground(y);
                waves[k] = wave;
            }
            if (waves[k] != null && seconds - firedAt[k] > WAVE_HOLD) waves[k].stop();
        }
        vfx.update(seconds);
        vfx.submit(world);
    }

    /** Sphere {@code k}'s turn at {@code seconds}, into {@link #transform}: each at its own pace, two of them tilted. */
    private void spin(int k, float seconds) {
        float turn = seconds * (0.18f + 0.06f * (k % 3));
        transform.identity();
        if (k == BLACK_HOLE) transform.rotateX(0.38f).rotateZ(0.12f).scale(BLACK_HOLE_SIZE);
        if (k == GALAXY) transform.rotateX(0.55f).rotateZ(-0.2f);
        transform.rotateY(turn);
    }

    /**
     * The shield's impacts at {@code seconds}, into {@link #impacts}: three lanes, each struck once a period from a
     * direction of its own, mostly from above. The age is negative while that lane's bolt is still in flight.
     */
    private void shieldImpacts(float seconds) {
        for (int lane = 0; lane < 3; lane++) {
            float period = 2.2f + 0.7f * lane;
            float shifted = seconds + lane * period * 0.37f;
            float cycle = (float) Math.floor(shifted / period);
            float x = hash(cycle, lane, 1) * 2f - 1f;
            float y = hash(cycle, lane, 2) * 0.9f - 0.1f;
            float z = hash(cycle, lane, 3) * 2f - 1f;
            float length = (float) Math.sqrt(x * x + y * y + z * z);
            if (length < 1.0e-3f) {
                x = 0f;
                y = 1f;
                z = 0f;
                length = 1f;
            }
            int at = lane * 4;
            impacts[at] = x / length;
            impacts[at + 1] = y / length;
            impacts[at + 2] = z / length;
            impacts[at + 3] = shifted - cycle * period - BOLT_FLIGHT;
        }
    }

    /** The bolts still on their way to the shield centred at {@code (cx, cy, cz)}: streaks flying in along their lane. */
    private void boltsInFlight(CgWorldRenderer world, double cx, double cy, double cz) {
        for (int lane = 0; lane < 3; lane++) {
            int at = lane * 4;
            float age = impacts[at + 3];
            if (age >= 0f) continue;
            float dx = impacts[at], dy = impacts[at + 1], dz = impacts[at + 2];
            float travel = 1f + age / BOLT_FLIGHT;
            float distance = BOLT_RANGE + (1f - BOLT_RANGE) * travel;
            boolean steep = Math.abs(dy) > 0.95f;
            transform.identity().rotateTowards(dx, dy, dz, steep ? 1f : 0f, steep ? 0f : 1f, 0f).scale(0.05f, 0.05f, 0.34f);
            world.draw(sphere, bolt).at(cx + dx * distance, cy + dy * distance, cz + dz * distance).transform(transform).layer(CgSortLayer.EFFECTS).submit();
        }
    }

    /** A hash of {@code (a, lane, salt)} in [0, 1). */
    private static float hash(float a, int lane, int salt) {
        int h = Float.floatToIntBits(a) * 0x27d4eb2d ^ (lane + 1) * 0x165667b1 ^ salt * 0x61c88647;
        h ^= h >>> 15;
        h *= 0x85ebca6b;
        h ^= h >>> 13;
        h *= 0xc2b2ae35;
        h ^= h >>> 16;
        return (h & 0xFFFFFF) / (float) 0x1000000;
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
        sphere = CgMeshShapes.sphere(80, 160);
        floor = CgMesh.build(CgVertexFormat.SPATIAL, m -> CgMeshShapes.plane(m, 1, 1, 120f, 120f));
        for (int k = 0; k < COUNT; k++) materials[k] = CgMaterial.load("crystalgraphics:shaders/demo/vfx_" + SHADERS[k] + ".shader");
        glow = CgMaterial.load("crystalgraphics:shaders/demo/vfx_glow.shader");
        corona = CgMaterial.load("crystalgraphics:shaders/demo/vfx_supernova_corona.shader");
        bolt = CgMaterial.load("crystalgraphics:shaders/demo/vfx_bolt.shader");
        sky = CgMaterial.load("crystalgraphics:shaders/demo/vfx_sky.shader");
        horizon = CgMaterial.load("crystalgraphics:shaders/demo/vfx_sky_horizon.shader");
        seal = CgMaterial.load("crystalgraphics:shaders/demo/vfx_sky_seal.shader");
        floorMaterial = CgMaterial.newInstance("crystalgraphics:shaders/demo/vfx_floor.shader");
    }
}
