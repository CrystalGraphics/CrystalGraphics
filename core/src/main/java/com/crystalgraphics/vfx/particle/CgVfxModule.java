package com.crystalgraphics.vfx.particle;

import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.shape.CgShape;
import com.crystalgraphics.shape.primitive.CgBox;
import com.crystalgraphics.shape.primitive.CgPlane;
import com.crystalgraphics.shape.primitive.CgSphere;
import com.crystalgraphics.vfx.particle.gpu.CgVfxEvent;
import com.crystalgraphics.vfx.particle.gpu.CgVfxGpuModule;
import com.crystalgraphics.vfx.particle.gpu.CgVfxInstanceView;
import com.crystalgraphics.vfx.particle.gpu.CgVfxLane;
import com.crystalgraphics.vfx.particle.gpu.CgVfxWords;
import com.crystalgraphics.vfx.particle.gpu.CgVfxWorldInput;

import java.util.Objects;

/**
 * One step of an emitter's update, run over all its particles every tick, in the order the emitter lists them: the
 * pieces a particle's physics is built from, as Niagara's update modules are. Each is data, a kind and its numbers,
 * which the GPU simulation runs ({@link CgVfxGpuModule}: each kind's {@code fx_<kind>.glsl} in
 * {@code shaders/lib/vfx/sim/}). Force modules add to the accelerations and drags the solver integrates;
 * {@link Ground}, {@link Current}, {@link Collide}, {@link Kill}, {@link Orbit} and {@link LimitSpeed} run after the
 * solver, on the moved particles. Units are blocks and seconds.
 *
 * <p>Ported from Godot Engine (MIT, © 2014-present Godot Engine contributors): {@link Attract}, {@link VectorField},
 * {@link Orbit}, {@link Damping}, {@link LimitSpeed} and {@link Collide}'s shapes and response, from
 * {@code particles.glsl} and {@code particle_process_material.cpp}. From bevy_hanabi (MIT, © 2021 Jerome Humbert):
 * {@link Vortex}, {@link Conform} and {@link Kill}, from its {@code accel}, {@code force} and {@code kill} modifiers.</p>
 *
 * <pre>{@code
 * // an ember: light, lofted by its heat, carried by the air
 * List.of(new CgVfxModule.Gravity(9.8f),
 *         new CgVfxModule.Drag(4f, 0f),                  // settles at 9.8 / 4 = 2.45 blocks a second
 *         new CgVfxModule.Buoyancy(16f, 0.9f),
 *         new CgVfxModule.Turbulence(8f, 0.12f, 0.6f),
 *         new CgVfxModule.Wind(1f));
 *
 * // debris: heavy, lands and settles
 * List.of(new CgVfxModule.Gravity(9.8f),
 *         new CgVfxModule.Drag(0.25f, 0f),
 *         new CgVfxModule.Ground(0.3f, 0.5f, 0.6f),
 *         new CgVfxModule.Spin(0.8f));
 *
 * // energy gathering: spiralling into a hand and absorbed there
 * List.of(new CgVfxModule.Attract(CgSphere.of(6), 30f).at(0f, 1.5f, 0f),
 *         new CgVfxModule.Orbit(0.6f, 0f, 1f, 0f, 0f, 1.5f, 0f),
 *         new CgVfxModule.Kill(CgSphere.of(0.3), true).at(0f, 1.5f, 0f));
 * }</pre>
 *
 * <ul>
 *   <li>Order matters only among forces that read velocity ({@link Wind}); the solver sums all forces before it moves.</li>
 *   <li>{@link Attract}, {@link VectorField}, {@link Conform}, {@link Collide} and {@link Kill} act in a collision
 *       engine shape, a {@link CgSphere}, {@link CgBox} or {@link CgPlane}, centred on the source or {@code at} a point
 *       from it. Any other shape is refused.</li>
 *   <li>{@link Ground} lands particles on the voxel window's blocks, or on the instance's ground height where the host
 *       has no world ({@link CgVfxEmitterInstance#ground(float)}).</li>
 *   <li>A new kind is a record here and its GPU side: {@code fx_<kind>.glsl} and the {@link CgVfxGpuModule} methods.
 *       A mod's own kind implements {@link CgVfxGpuModule} instead.</li>
 * </ul>
 */
public sealed interface CgVfxModule extends CgVfxGpuModule {

    /** True for a module that runs after the solver has moved the particles. */
    @Override
    default boolean afterSolve() {
        return false;
    }

    /**
     * The most acceleration it gives a particle of {@code heat}, in blocks a second squared: what bounds how far one
     * gets ({@link CgVfxEmitter#reach}). 0 for one that only slows or turns a particle, and for {@link Wind}, whose
     * bound is the wind's speed.
     */
    default float pull(float heat) {
        return 0f;
    }

    /**
     * This module for particles {@code scale} times the size, on the same clock ({@link CgVfxEmitter#scaled}): lengths,
     * speeds and accelerations times {@code scale}, what is per block divided by it, rates and times as they are. A kind
     * that does not override it plays unscaled: so far only the kinds the explosion kit uses do.
     */
    default CgVfxModule scaled(float scale) {
        return this;
    }

    /** A constant pull down, in blocks a second squared: 9.8 is Earth's. */
    record Gravity(float strength) implements CgVfxModule {
        @Override
        public CgVfxModule scaled(float scale) {
            return new Gravity(strength * scale);
        }

        @Override
        public float pull(float heat) {
            return Math.abs(strength);
        }

        @Override
        public String gpuKind() {
            return "gravity";
        }

        @Override
        public void writeParams(CgVfxWords out) {
            out.vec4(strength, 0f, 0f, 0f);
        }
    }

    /**
     * Air resistance: {@code linear} a second, and {@code quadratic} a block, so drag grows with speed and brakes a fast
     * burst hard. A falling particle settles at gravity / linear when the quadratic term is 0.
     */
    record Drag(float linear, float quadratic) implements CgVfxModule {
        @Override
        public CgVfxModule scaled(float scale) {
            return new Drag(linear, quadratic / scale);
        }

        @Override
        public String gpuKind() {
            return "drag";
        }

        @Override
        public void writeParams(CgVfxWords out) {
            out.vec4(linear, quadratic, 0f, 0f);
        }
    }

    /**
     * The system's wind ({@link CgVfxAir}) pulling a particle along: {@code resistance} a second is how quickly it
     * comes up to the wind's speed, as Niagara's Wind Force. One already moving faster along the wind is not pushed.
     */
    record Wind(float resistance) implements CgVfxModule {

        @Override
        public String gpuKind() {
            return "wind";
        }

        @Override
        public void writeParams(CgVfxWords out) {
            out.vec4(resistance, 0f, 0f, 0f);
        }
    }

    /**
     * Swirling air: a curl-noise force (Bridson's, {@code fx_curl.glsl}) of up to about {@code strength} blocks a second squared,
     * with eddies about 1 / {@code frequency} blocks across, the field drifting at {@code evolve} a second. The same field
     * for every emitter at a point, so neighbouring particles swirl together.
     */
    record Turbulence(float strength, float frequency, float evolve) implements CgVfxModule {
        @Override
        public CgVfxModule scaled(float scale) {
            return new Turbulence(strength * scale, frequency / scale, evolve);
        }

        /** Each octave's lattice cell and fraction of where the instance's origin samples (fx_curl.glsl). */
        private static final CgVfxLane[] LANES = {CgVfxLane.IVEC4, CgVfxLane.VEC4, CgVfxLane.IVEC4, CgVfxLane.VEC4};
        /** fx_curl's second octave: x * 2.03 + offset. */
        private static final double OCTAVE = 2.03, OFFSET_X = 5.2, OFFSET_Y = 1.3, OFFSET_Z = 7.9;

        @Override
        public float pull(float heat) {
            return Math.abs(strength);
        }

        @Override
        public String gpuKind() {
            return "turbulence";
        }

        @Override
        public void writeParams(CgVfxWords out) {
            out.vec4(strength, frequency, evolve, 0f);
        }

        @Override
        public CgVfxLane[] instanceLanes() {
            return LANES;
        }

        /** Where the origin samples, split per octave in doubles: the particle's offset adds to the fractions. */
        @Override
        public void writeInstance(CgVfxInstanceView instance, CgVfxWords out) {
            float drift = instance.time() * evolve;
            double x = instance.originX() * frequency + drift, y = instance.originY() * frequency,
                    z = instance.originZ() * frequency - drift * 0.7f;
            split(out, x, y, z);
            split(out, x * OCTAVE + OFFSET_X, y * OCTAVE + OFFSET_Y, z * OCTAVE + OFFSET_Z);
        }

        private static void split(CgVfxWords out, double x, double y, double z) {
            double cx = Math.floor(x), cy = Math.floor(y), cz = Math.floor(z);
            out.ivec4((int) cx, (int) cy, (int) cz, 0).vec4((float) (x - cx), (float) (y - cy), (float) (z - cz), 0f);
        }
    }

    /**
     * Lift from heat: {@code lift} blocks a second squared at full heat, the heat cooling with a time constant of
     * {@code cooling} seconds. What makes smoke rise late and embers loft before they fall.
     */
    record Buoyancy(float lift, float cooling) implements CgVfxModule {
        @Override
        public CgVfxModule scaled(float scale) {
            return new Buoyancy(lift * scale, cooling);
        }

        @Override
        public float pull(float heat) {
            return Math.abs(lift * heat);
        }

        @Override
        public String gpuKind() {
            return "buoyancy";
        }

        @Override
        public void writeParams(CgVfxWords out) {
            out.vec4(lift, cooling, 0f, 0f);
        }
    }

    /**
     * The rising air over a blast: an upward pull of {@code strength} blocks a second squared above the emitter's source,
     * falling off over {@code radius} blocks sideways and {@code height} blocks up, and dying away over {@code duration}
     * seconds. What carries light particles up over a fire.
     */
    record Updraft(float strength, float radius, float height, float duration) implements CgVfxModule {
        @Override
        public CgVfxModule scaled(float scale) {
            return new Updraft(strength * scale, radius * scale, height * scale, duration);
        }

        /** Its fade at the step's start, then the source. */
        private static final CgVfxLane[] LANES = {CgVfxLane.VEC4};

        @Override
        public float pull(float heat) {
            return Math.abs(strength);
        }

        private static float smooth(float edge0, float edge1, float x) {
            float t = Math.max(0f, Math.min(1f, (x - edge0) / (edge1 - edge0)));
            return t * t * (3f - 2f * t);
        }

        @Override
        public String gpuKind() {
            return "updraft";
        }

        @Override
        public void writeParams(CgVfxWords out) {
            out.vec4(strength, radius, height, duration);
        }

        @Override
        public CgVfxLane[] instanceLanes() {
            return LANES;
        }

        @Override
        public void writeInstance(CgVfxInstanceView instance, CgVfxWords out) {
            out.vec4(1f - smooth(0f, duration, instance.time()), instance.sourceX(), instance.sourceY(), instance.sourceZ());
        }
    }

    /**
     * The ground: the host world's surface under each particle (the voxel window's blocks), or the instance's fixed
     * height where there is no world. A particle reaching it bounces back up at {@code restitution} of its speed, loses
     * {@code friction} of its sliding speed, and comes to rest once slower than {@code rest} blocks a second. Runs after
     * the solver. A particle rests on it as a ball of {@code radius} times its size, all of it by default, as
     * {@link Collide} does.
     */
    record Ground(float restitution, float friction, float rest, float radius) implements CgVfxModule {
        private static final CgVfxWorldInput[] WORLD = {CgVfxWorldInput.FLOOR_Y};

        public Ground(float restitution, float friction, float rest) {
            this(restitution, friction, rest, 1f);
        }

        /** Rests as a ball of {@code radius} times a particle's size. */
        public Ground radius(float radius) {
            return new Ground(restitution, friction, rest, radius);
        }

        @Override
        public CgVfxModule scaled(float scale) {
            return new Ground(restitution, friction, rest * scale, radius);
        }

        @Override
        public boolean afterSolve() {
            return true;
        }

        @Override
        public String gpuKind() {
            return "ground";
        }

        @Override
        public void writeParams(CgVfxWords out) {
            out.vec4(restitution, friction, rest, radius);
        }

        @Override
        public CgVfxWorldInput[] worldInputs() {
            return WORLD;
        }
    }

    /**
     * Dust in a gravity current along the ground: a base surge, a downburst's outflow, a dust storm's front. The air
     * under the head rises at {@code roll} a second times its speed, fading to nothing {@code depth} blocks over the
     * floor, so a fast front rides up into a head; over the head's upper half the return flow brakes it by {@code roll}
     * a second, so the top lags behind and falls back over, as the head rolls. Once slower than {@code slow} blocks a
     * second, the warm dust lofts, lifted by up to {@code loft} blocks a second squared. Runs after the solver, on the
     * floor under each particle; does nothing without one, and nothing to a resting particle.
     *
     * <pre>{@code
     * // a surge two blocks deep that lofts once it has stalled
     * .module(new CgVfxModule.Gravity(1.5f))
     * .module(new CgVfxModule.Drag(1f, 0.06f))
     * .module(new CgVfxModule.Current(2f, 0.3f, 2f, 2.5f))
     * .module(new CgVfxModule.Ground(0f, 0.02f, 0f, 0.3f))   // rest 0: slides, never freezes, so it can loft
     * }</pre>
     */
    record Current(float depth, float roll, float loft, float slow) implements CgVfxModule {
        private static final CgVfxWorldInput[] WORLD = {CgVfxWorldInput.FLOOR_Y};

        @Override
        public CgVfxModule scaled(float scale) {
            return new Current(depth * scale, roll, loft * scale, slow * scale);
        }

        @Override
        public boolean afterSolve() {
            return true;
        }

        /** The loft alone: the head's lift ends {@code depth} over the floor. */
        @Override
        public float pull(float heat) {
            return Math.abs(loft);
        }

        @Override
        public String gpuKind() {
            return "current";
        }

        @Override
        public void writeParams(CgVfxWords out) {
            out.vec4(depth, roll, loft, slow);
        }

        @Override
        public CgVfxWorldInput[] worldInputs() {
            return WORLD;
        }
    }

    /** Spinning slows down: the spin rate decays by {@code drag} a second. */
    record Spin(float drag) implements CgVfxModule {

        @Override
        public String gpuKind() {
            return "spin";
        }

        @Override
        public void writeParams(CgVfxWords out) {
            out.vec4(drag, 0f, 0f, 0f);
        }
    }

    /**
     * A pull toward a sphere or box (Godot's attractors): {@code strength} blocks a second squared at the centre, falling
     * to nothing at the edge as {@code (1 - d)^attenuation}, d being 0 at the centre and 1 at the edge. A negative
     * strength pushes out. Directionality turns the pull, 0 to 1, into a push along {@code axis}. The shape is centred
     * on the source, or {@link #at} a point from it.
     *
     * <pre>{@code
     * new CgVfxModule.Attract(CgSphere.of(6), 30f).at(0f, 1.5f, 0f)              // energy gathering in a hand
     * new CgVfxModule.Attract(CgBox.of(3, 3, 3), 12f).attenuation(0f)            // even over the box
     * new CgVfxModule.Attract(CgSphere.of(4), 10f).directed(1f, 0f, 1f, 0f)      // a lift inside the sphere
     * }</pre>
     *
     * <ul>
     *   <li>A plane attracts nothing, and is refused, as is any shape but a sphere or box.</li>
     *   <li>Pair it with {@link Orbit} round the same centre for a spiral in, Godot's orbit velocity.</li>
     * </ul>
     */
    record Attract(CgShape shape, float x, float y, float z, float strength, float attenuation, float directionality,
                   float axisX, float axisY, float axisZ) implements CgVfxModule {
        private static final CgVfxLane[] LANES = {CgVfxLane.VEC4};

        public Attract {
            if (CgVfxShapeRows.check(shape) instanceof CgPlane) throw new IllegalArgumentException("a plane attracts nothing");
            if (!(attenuation >= 0f)) throw new IllegalArgumentException("attenuation " + attenuation);
            float length = (float) Math.sqrt(axisX * axisX + axisY * axisY + axisZ * axisZ);
            if (length > 0f) {
                axisX /= length;
                axisY /= length;
                axisZ /= length;
            }
        }

        public Attract(CgShape shape, float strength) {
            this(shape, 0f, 0f, 0f, strength, 1f, 0f, 0f, 1f, 0f);
        }

        /** The shape centred {@code (x, y, z)} from the source. */
        public Attract at(float x, float y, float z) {
            return new Attract(shape, x, y, z, strength, attenuation, directionality, axisX, axisY, axisZ);
        }

        /** How the pull falls off toward the edge: 0 even, 1 linear (the default), 2 and up held to the centre. */
        public Attract attenuation(float attenuation) {
            return new Attract(shape, x, y, z, strength, attenuation, directionality, axisX, axisY, axisZ);
        }

        /** A push along {@code (ax, ay, az)} in place of {@code directionality} of the pull. */
        public Attract directed(float directionality, float ax, float ay, float az) {
            return new Attract(shape, x, y, z, strength, attenuation, directionality, ax, ay, az);
        }

        @Override
        public float pull(float heat) {
            return Math.abs(strength);
        }

        @Override
        public String gpuKind() {
            return "attract";
        }

        @Override
        public int paramVectors() {
            return 3;
        }

        @Override
        public void writeParams(CgVfxWords out) {
            CgVfxShapeRows.writeShape(shape, out);
            out.vec4(strength, attenuation, directionality, 0f).vec4(axisX, axisY, axisZ, 0f);
        }

        @Override
        public CgVfxLane[] instanceLanes() {
            return LANES;
        }

        @Override
        public void writeInstance(CgVfxInstanceView instance, CgVfxWords out) {
            CgVfxShapeRows.writeCentre(shape, x, y, z, instance, out);
        }
    }

    /**
     * Turns particles round an axis through a point {@code (x, y, z)} from the source, {@code turns} a second (Godot's
     * orbit velocity): it moves them without adding to their velocity, so it never flings one out. Runs after the solver.
     *
     * <pre>{@code
     * new CgVfxModule.Orbit(0.5f, 0f, 1f, 0f, 0f, 1.5f, 0f)   // half a turn a second round the vertical, 1.5 up
     * }</pre>
     */
    record Orbit(float turns, float axisX, float axisY, float axisZ, float x, float y, float z) implements CgVfxModule {
        private static final CgVfxLane[] LANES = {CgVfxLane.VEC4};

        public Orbit {
            float length = (float) Math.sqrt(axisX * axisX + axisY * axisY + axisZ * axisZ);
            if (!(length > 0f)) throw new IllegalArgumentException("an orbit needs an axis");
            axisX /= length;
            axisY /= length;
            axisZ /= length;
        }

        @Override
        public boolean afterSolve() {
            return true;
        }

        @Override
        public String gpuKind() {
            return "orbit";
        }

        @Override
        public void writeParams(CgVfxWords out) {
            out.vec4(axisX, axisY, axisZ, turns);
        }

        @Override
        public CgVfxLane[] instanceLanes() {
            return LANES;
        }

        @Override
        public void writeInstance(CgVfxInstanceView instance, CgVfxWords out) {
            out.vec4(instance.sourceX() + x, instance.sourceY() + y, instance.sourceZ() + z, 0f);
        }
    }

    /**
     * A whirl round an axis through a point {@code (x, y, z)} from the source: {@code tangential} blocks a second squared
     * round it and {@code radial} out from the point, negative pulling in (bevy_hanabi's tangent and radial
     * accelerations). A tornado is a vertical axis with a little pull in.
     *
     * <pre>{@code
     * new CgVfxModule.Vortex(0f, 1f, 0f, 14f, -6f, 0f, 0f, 0f)
     * }</pre>
     */
    record Vortex(float axisX, float axisY, float axisZ, float tangential, float radial, float x, float y, float z)
            implements CgVfxModule {
        private static final CgVfxLane[] LANES = {CgVfxLane.VEC4};

        public Vortex {
            float length = (float) Math.sqrt(axisX * axisX + axisY * axisY + axisZ * axisZ);
            if (!(length > 0f)) throw new IllegalArgumentException("a vortex needs an axis");
            axisX /= length;
            axisY /= length;
            axisZ /= length;
        }

        @Override
        public float pull(float heat) {
            return Math.abs(tangential) + Math.abs(radial);
        }

        @Override
        public String gpuKind() {
            return "vortex";
        }

        @Override
        public int paramVectors() {
            return 2;
        }

        @Override
        public void writeParams(CgVfxWords out) {
            out.vec4(axisX, axisY, axisZ, tangential).vec4(radial, 0f, 0f, 0f);
        }

        @Override
        public CgVfxLane[] instanceLanes() {
            return LANES;
        }

        @Override
        public void writeInstance(CgVfxInstanceView instance, CgVfxWords out) {
            out.vec4(instance.sourceX() + x, instance.sourceY() + y, instance.sourceZ() + z, 0f);
        }
    }

    /**
     * Reynolds' boids (1987): each particle steers apart from those nearest it, along with its neighbours' heading and
     * toward their middle, among its own instance's particles within {@code radius} blocks: a flock of spirits, a swarm
     * of embers, a school of fish. Reads the pool's neighbour grid, built after every step on every tier.
     *
     * <pre>{@code
     * .module(new CgVfxModule.Flock(1.6f, 0.5f, 3f, 1.5f, 0.8f))      // a loose flock
     * .module(new CgVfxModule.Flock(0.8f, 0.3f, 6f, 4f, 0.3f, 16))    // a tight school, sixteen neighbours each
     * .module(new CgVfxModule.LimitSpeed(4f))                          // boids want a top speed
     * }</pre>
     *
     * <ul>
     *   <li>{@code separation} pushes a particle from each neighbour nearer than {@code apart} blocks, harder the nearer;
     *       {@code alignment} matches its velocity to theirs, a rate a second; {@code cohesion} pulls it to their middle,
     *       blocks a second squared a block.</li>
     *   <li>Counts at most {@code most} neighbours (24 unless given): a dense flock costs the same as a sparse one.</li>
     *   <li>A newborn joins the next step: the grid holds last step's particles.</li>
     * </ul>
     */
    record Flock(float radius, float apart, float separation, float alignment, float cohesion, int most)
            implements CgVfxModule {
        private static final CgVfxWorldInput[] NEIGHBOURS = {CgVfxWorldInput.NEIGHBOURS};

        public Flock {
            if (!(radius > 0f)) throw new IllegalArgumentException("a flock's radius " + radius);
            if (most < 1) throw new IllegalArgumentException("a flock counting " + most + " neighbours");
        }

        public Flock(float radius, float apart, float separation, float alignment, float cohesion) {
            this(radius, apart, separation, alignment, cohesion, 24);
        }

        @Override
        public float pull(float heat) {
            return cohesion * radius + separation / Math.max(apart, 0.05f);
        }

        @Override
        public CgVfxModule scaled(float scale) {
            return new Flock(radius * scale, apart * scale, separation * scale * scale, alignment, cohesion, most);
        }

        @Override
        public String gpuKind() {
            return "flock";
        }

        @Override
        public int paramVectors() {
            return 2;
        }

        @Override
        public void writeParams(CgVfxWords out) {
            out.vec4(radius, separation, alignment, cohesion).vec4(apart, most, 0f, 0f);
        }

        @Override
        public CgVfxWorldInput[] worldInputs() {
            return NEIGHBOURS;
        }

        @Override
        public float neighbourRadius() {
            return radius;
        }
    }

    /**
     * Carried by a gas: each particle's velocity drawn toward the gas's where it is, {@code follow} of the difference a
     * second, inside the gas's box alone. Embers in a fire's plume, debris whirled through a blast's cloud, sparks curling
     * in smoke. The gas is the instance's ({@code CgVfxEmitterInstance.inGas}); with none, it does nothing.
     *
     * <pre>{@code
     * .module(new CgVfxModule.Flow(3f))              // embers riding a plume
     * embers.inGas(smoke);                            // in the effect, after making both
     * }</pre>
     *
     * <ul>
     *   <li>Read from the gas's last step: a frame behind it, which nothing at 60 Hz shows.</li>
     *   <li>Around 1 drifts with the gas; past 10 sticks to it like dust.</li>
     * </ul>
     */
    record Flow(float follow) implements CgVfxModule {
        private static final CgVfxWorldInput[] GAS = {CgVfxWorldInput.GAS};
        /** The fastest a gas is taken to move, blocks a second: what bounds the pull for culling. */
        private static final float FASTEST = 20f;

        public Flow {
            if (!(follow >= 0f)) throw new IllegalArgumentException("a flow followed at " + follow);
        }

        @Override
        public float pull(float heat) {
            return follow * FASTEST;
        }

        @Override
        public String gpuKind() {
            return "flow";
        }

        @Override
        public void writeParams(CgVfxWords out) {
            out.vec4(follow, 0f, 0f, 0f);
        }

        @Override
        public CgVfxWorldInput[] worldInputs() {
            return GAS;
        }
    }

    /**
     * Pours into a gas: each particle adds {@code density} and {@code heat} a second where it is, which the gas takes at
     * its next step, so a jet's particles leave smoke along their paths and embers warm the air they rise through. The
     * gas is the instance's ({@code CgVfxEmitterInstance.inGas}); with none, it does nothing.
     *
     * <pre>{@code
     * .module(new CgVfxModule.Feed(4f, 0.5f))         // each spark trailing smoke and a little heat
     * sparks.inGas(smoke);                             // in the effect, after making both
     * }</pre>
     *
     * <ul>
     *   <li>Lands where the gas holds tiles: the engine asks for tiles round each instance feeding it, as far as its
     *       particles reach (16 blocks at most).</li>
     *   <li>A deposit is spread over the cells round the particle by distance, so its mark is a soft cloud about a
     *       block across at a 0.25 cell.</li>
     *   <li>At most {@code CgVfxFluidAtlas.DEPOSITS} particles pour a frame, every instance's together; the rest pour nothing.</li>
     * </ul>
     */
    record Feed(float density, float heat) implements CgVfxModule {
        private static final CgVfxWorldInput[] FEEDS = {CgVfxWorldInput.GAS_FEED};

        public Feed {
            if (!(density >= 0f) || !(heat >= 0f)) throw new IllegalArgumentException("a feed of " + density + ", " + heat);
        }

        @Override
        public String gpuKind() {
            return "gas_feed";
        }

        @Override
        public void writeParams(CgVfxWords out) {
            out.vec4(density, heat, 0f, 0f);
        }

        @Override
        public CgVfxWorldInput[] worldInputs() {
            return FEEDS;
        }
    }

    /**
     * Writes into a grid: each particle adds {@code x}, {@code y}, {@code z} and {@code w} a second to its instance's
     * grid's four channels, in the cell where it is (Niagara's set-grid-value), after the solver. Particles become a
     * field: a density drawn as smoke, a trail other particles follow.
     *
     * <pre>{@code
     * .module(new CgVfxModule.Splat(1f, 0f, 0f, 0f))       // density into channel x, a unit a second
     * agents.inGrid(trail);                                 // in the effect, after making both
     * }</pre>
     *
     * <ul>
     *   <li>The grid is the instance's ({@code CgVfxEmitterInstance.inGrid}); with none, it does nothing.</li>
     *   <li>A splat is spread over the eight cells about it by distance; one outside the grid is lost.</li>
     *   <li>At most {@code CgVfxGridAtlas.SPLATS} particles splat a frame, every instance's together; the rest splat
     *       nothing.</li>
     * </ul>
     */
    record Splat(float x, float y, float z, float w) implements CgVfxModule {
        private static final CgVfxWorldInput[] SPLATS = {CgVfxWorldInput.GRID_SPLAT};

        @Override
        public String gpuKind() {
            return "grid_splat";
        }

        @Override
        public String gpuFile() {
            return "crystalgraphics:shaders/lib/vfx/sim/grid/fx_grid_splat.glsl";
        }

        @Override
        public boolean afterSolve() {
            return true;
        }

        @Override
        public void writeParams(CgVfxWords out) {
            out.vec4(x, y, z, w);
        }

        @Override
        public CgVfxWorldInput[] worldInputs() {
            return SPLATS;
        }
    }

    /**
     * Steers toward where a grid holds more of a channel, Physarum's sensor rule (Jones 2010) in 3D: five sensors
     * {@code sense} blocks ahead, one straight on and four {@code angle} radians off it, and the particle turns toward
     * the strongest at {@code turn} a second, keeping its speed. Particles that also {@link Splat} into the channel lay
     * trails and follow each other's: slime mould, ant paths, a nervous system of light. Past {@code full} a trail draws
     * them no harder, Jones's crowding rule (one agent a cell) in a continuous field: without it every agent ends on one
     * thick trail.
     *
     * <pre>{@code
     * .module(new CgVfxModule.Follow(0, 0.9f, 0.785f, 13f, 2f))   // channel x, sensors 0.9 blocks out, 45 degrees, full at 2
     * .module(new CgVfxModule.Splat(1f, 0f, 0f, 0f))              // and lay what the others follow
     * }</pre>
     *
     * <ul>
     *   <li>A particle with no speed does not turn: give them a launch speed, and something to keep it (no drag).</li>
     *   <li>A network wants a domain about ten sensor reaches across, and nothing else steering: a container
     *       ({@link Collide#container()}), never a pull.</li>
     *   <li>{@code full} 0 sets no ceiling.</li>
     *   <li>It reads the grid as the last frame left it.</li>
     * </ul>
     */
    record Follow(int channel, float sense, float angle, float turn, float full) implements CgVfxModule {
        private static final CgVfxWorldInput[] GRID = {CgVfxWorldInput.GRID};

        public Follow {
            if (channel < 0 || channel > 3) throw new IllegalArgumentException("a grid's channel " + channel + ": 0 to 3");
            if (!(full >= 0f)) throw new IllegalArgumentException("full " + full);
        }

        /** No ceiling: a trail draws harder the more it holds. */
        public Follow(int channel, float sense, float angle, float turn) {
            this(channel, sense, angle, turn, 0f);
        }

        @Override
        public int paramVectors() {
            return 2;
        }

        @Override
        public String gpuKind() {
            return "grid_follow";
        }

        @Override
        public String gpuFile() {
            return "crystalgraphics:shaders/lib/vfx/sim/grid/fx_grid_follow.glsl";
        }

        @Override
        public void writeParams(CgVfxWords out) {
            out.vec4(channel, sense, angle, turn).vec4(full, 0f, 0f, 0f);
        }

        @Override
        public CgVfxWorldInput[] worldInputs() {
            return GRID;
        }
    }

    /**
     * Pushed down a grid's slope of a channel, {@code strength} blocks a second squared a unit a block: particles that
     * {@link Splat} density spread out of where they crowd, a pressure for fluid-like bodies; a negative strength draws
     * them up it, together.
     *
     * <pre>{@code
     * .module(new CgVfxModule.Gradient(0, 4f))               // out of where channel x is dense
     * }</pre>
     */
    record Gradient(int channel, float strength) implements CgVfxModule {
        private static final CgVfxWorldInput[] GRID = {CgVfxWorldInput.GRID};

        public Gradient {
            if (channel < 0 || channel > 3) throw new IllegalArgumentException("a grid's channel " + channel + ": 0 to 3");
        }

        @Override
        public String gpuKind() {
            return "grid_gradient";
        }

        @Override
        public String gpuFile() {
            return "crystalgraphics:shaders/lib/vfx/sim/grid/fx_grid_gradient.glsl";
        }

        @Override
        public void writeParams(CgVfxWords out) {
            out.vec4(channel, strength, 0f, 0f);
        }

        @Override
        public CgVfxWorldInput[] worldInputs() {
            return GRID;
        }
    }

    /** A constant push, blocks a second squared: Niagara's acceleration force, {@link Gravity} in any direction. */
    record Force(float x, float y, float z) implements CgVfxModule {

        @Override
        public float pull(float heat) {
            return (float) Math.sqrt(x * x + y * y + z * z);
        }

        @Override
        public String gpuKind() {
            return "force";
        }

        @Override
        public void writeParams(CgVfxWords out) {
            out.vec4(x, y, z, 0f);
        }
    }

    /**
     * A {@link CgVfxField} over a box (Godot's vector field attractor): each particle pushed by the field where it is,
     * {@code strength} blocks a second squared for a unit vector, a longer one as {@code length^attenuation}. Outside the
     * box nothing pushes, unless the field tiles. The box is centred on the source, or {@link #at} a point from it.
     *
     * <pre>{@code
     * new CgVfxModule.VectorField(swirl, CgBox.of(4, 4, 4), 3f, 1f).at(0f, 4f, 0f)
     * }</pre>
     */
    record VectorField(CgVfxField field, CgBox box, float x, float y, float z, float strength, float attenuation)
            implements CgVfxModule {
        private static final CgVfxLane[] LANES = {CgVfxLane.VEC4};

        public VectorField {
            Objects.requireNonNull(field, "field");
            Objects.requireNonNull(box, "box");
            if (!(attenuation >= 0f)) throw new IllegalArgumentException("attenuation " + attenuation);
        }

        public VectorField(CgVfxField field, CgBox box, float strength, float attenuation) {
            this(field, box, 0f, 0f, 0f, strength, attenuation);
        }

        /** The box centred {@code (x, y, z)} from the source. */
        public VectorField at(float x, float y, float z) {
            return new VectorField(field, box, x, y, z, strength, attenuation);
        }

        @Override
        public float pull(float heat) {
            return Math.abs(strength) * (float) Math.pow(field.largest(), attenuation);
        }

        @Override
        public String gpuKind() {
            return "vector_field";
        }

        @Override
        public int paramVectors() {
            return 2;
        }

        @Override
        public void writeParams(CgVfxWords out) {
            out.vec4((float) box.halfX(), (float) box.halfY(), (float) box.halfZ(), field.tiles() ? 1f : 0f)
                    .vec4(strength, attenuation, 0f, 0f);
        }

        @Override
        public CgVfxLane[] instanceLanes() {
            return LANES;
        }

        @Override
        public void writeInstance(CgVfxInstanceView instance, CgVfxWords out) {
            CgVfxShapeRows.writeCentre(box, x, y, z, instance, out);
        }

        @Override
        public CgTexture[] textures() {
            return field.textures();
        }
    }

    /**
     * Slows particles by a constant {@code rate}, blocks a second squared, to a stop (Godot's damping): unlike
     * {@link Drag}, a slow particle stops as soon as a fast one slows by the same amount.
     */
    record Damping(float rate) implements CgVfxModule {

        @Override
        public String gpuKind() {
            return "damping";
        }

        @Override
        public void writeParams(CgVfxWords out) {
            out.vec4(rate, 0f, 0f, 0f);
        }
    }

    /**
     * No particle faster than {@code most} blocks a second (Godot's velocity limit), its step's move redone at that
     * speed. Runs after the solver; list it before any other module that does.
     */
    record LimitSpeed(float most) implements CgVfxModule {
        public LimitSpeed {
            if (!(most >= 0f)) throw new IllegalArgumentException("a limit of " + most);
        }

        @Override
        public boolean afterSolve() {
            return true;
        }

        @Override
        public String gpuKind() {
            return "limit_speed";
        }

        @Override
        public void writeParams(CgVfxWords out) {
            out.vec4(most, 0f, 0f, 0f);
        }
    }

    /**
     * Draws particles onto a sphere's surface and holds them there while they move along it (bevy_hanabi's conform to
     * sphere): those within {@code influence} blocks of the surface, or inside, are pushed toward it at
     * {@code attraction} blocks a second squared, no faster than {@code speed}, and harder ({@code sticky} times) in a
     * shell {@code shell} blocks either side of it, so they settle rather than overshoot. The sphere is centred on the
     * source, or {@link #at} a point from it.
     *
     * <pre>{@code
     * new CgVfxModule.Conform(CgSphere.of(2), 3f, 40f, 6f).at(0f, 2f, 0f)   // a shield taking shape
     * }</pre>
     */
    record Conform(CgSphere sphere, float x, float y, float z, float influence, float attraction, float speed, float shell,
                   float sticky) implements CgVfxModule {
        private static final CgVfxLane[] LANES = {CgVfxLane.VEC4};

        public Conform {
            Objects.requireNonNull(sphere, "sphere");
            if (!(shell > 0f)) throw new IllegalArgumentException("a shell of " + shell);
        }

        /** bevy_hanabi's defaults: a shell of 0.1 blocks, twice as sticky in it. */
        public Conform(CgSphere sphere, float influence, float attraction, float speed) {
            this(sphere, 0f, 0f, 0f, influence, attraction, speed, 0.1f, 2f);
        }

        /** The sphere centred {@code (x, y, z)} from the source. */
        public Conform at(float x, float y, float z) {
            return new Conform(sphere, x, y, z, influence, attraction, speed, shell, sticky);
        }

        @Override
        public float pull(float heat) {
            return Math.abs(attraction * Math.max(1f, sticky));
        }

        @Override
        public String gpuKind() {
            return "conform";
        }

        @Override
        public int paramVectors() {
            return 2;
        }

        @Override
        public void writeParams(CgVfxWords out) {
            out.vec4((float) sphere.radius(), influence, attraction, speed).vec4(shell, sticky, 0f, 0f);
        }

        @Override
        public CgVfxLane[] instanceLanes() {
            return LANES;
        }

        @Override
        public void writeInstance(CgVfxInstanceView instance, CgVfxWords out) {
            CgVfxShapeRows.writeCentre(sphere, x, y, z, instance, out);
        }
    }

    /**
     * Particles meet a sphere, box or plane (Godot's colliders and rigid response): each is pushed out of it, loses its
     * speed into the surface and {@code friction} of its sliding, and bounces at {@code bounce} once it strikes fast
     * enough, Godot's slide-to-bounce threshold of 2 / (bounce + 1) blocks a second; slower than {@code rest} after, it
     * rests. Each bounce is a hit: it counts toward and fires {@link CgVfxEvent#onCollision()}. The shape is centred on
     * the source, or {@link #at} a point from it. Runs after the solver.
     *
     * <pre>{@code
     * new CgVfxModule.Collide(CgSphere.of(1.5), 0.6f, 0.1f, 0.2f).at(0f, 1.5f, 0f)    // a boulder sparks bounce off
     * new CgVfxModule.Collide(CgBox.of(3, 3, 3), 0.8f, 0f, 0f).container()           // kept inside a box
     * new CgVfxModule.Collide(CgPlane.of(0, 1, 0), 0f, 0f, 0f).killing()             // die on the plane
     * new CgVfxModule.Collide(CgPlane.of(0.4, 1, 0, 5), 0.3f, 0f, 0f)                // a ramp pieces fall off
     * }</pre>
     *
     * <ul>
     *   <li>A particle collides as a ball of {@code radius} times its size, its quad's half-width: all of it by default,
     *       so a piece rests on a surface rather than sunk into it. Godot's base size is a diameter; this is not.</li>
     *   <li>A plane ends at its {@link CgPlane#radius() radius}: a particle past the edge falls off rather than sliding
     *       on.</li>
     *   <li>A {@link #killing()} collider is Godot's hide on contact: a hit, then death at the end of the step.</li>
     * </ul>
     */
    record Collide(CgShape shape, float x, float y, float z, float bounce, float friction, float rest, float radius,
                   boolean keepsInside, boolean kill) implements CgVfxModule {
        private static final CgVfxLane[] LANES = {CgVfxLane.VEC4};

        public Collide {
            CgVfxShapeRows.check(shape);
        }

        public Collide(CgShape shape, float bounce, float friction, float rest) {
            this(shape, 0f, 0f, 0f, bounce, friction, rest, 1f, false, false);
        }

        /** The shape centred {@code (x, y, z)} from the source. */
        public Collide at(float x, float y, float z) {
            return new Collide(shape, x, y, z, bounce, friction, rest, radius, keepsInside, kill);
        }

        /** Collides as a ball of {@code radius} times a particle's size. */
        public Collide radius(float radius) {
            return new Collide(shape, x, y, z, bounce, friction, rest, radius, keepsInside, kill);
        }

        /** Keeps particles inside the shape rather than out of it. */
        public Collide container() {
            return new Collide(shape, x, y, z, bounce, friction, rest, radius, true, kill);
        }

        /** Kills a particle on contact rather than bouncing it. */
        public Collide killing() {
            return new Collide(shape, x, y, z, bounce, friction, rest, radius, keepsInside, true);
        }

        @Override
        public boolean afterSolve() {
            return true;
        }

        @Override
        public String gpuKind() {
            return "collide";
        }

        @Override
        public int paramVectors() {
            return 3;
        }

        @Override
        public void writeParams(CgVfxWords out) {
            CgVfxShapeRows.writeShape(shape, out);
            out.vec4(bounce, friction, rest, kill ? 1f : 0f).vec4(radius, keepsInside ? 1f : 0f, 0f, 0f);
        }

        @Override
        public CgVfxLane[] instanceLanes() {
            return LANES;
        }

        @Override
        public void writeInstance(CgVfxInstanceView instance, CgVfxWords out) {
            CgVfxShapeRows.writeCentre(shape, x, y, z, instance, out);
        }
    }

    /**
     * The host world's blocks, with {@link Collide}'s response: every solid half-block octant of the voxel window, a
     * particle inside one leaving by the face the distance field points through. With no level, the instance's ground
     * ({@link CgVfxEmitterInstance#ground(float)}), as {@link Ground} has. Unlike {@link Ground} it fires
     * {@link CgVfxEvent#onCollision()} and can kill. Runs after the solver.
     *
     * <pre>{@code
     * new CgVfxModule.CollideWorld(0.4f, 0.3f, 0.3f)             // debris on stairs and walls
     * new CgVfxModule.CollideWorld(0f, 0f, 0f).killing()          // sparks die on any block
     * }</pre>
     */
    record CollideWorld(float bounce, float friction, float rest, boolean kill) implements CgVfxModule {
        private static final CgVfxWorldInput[] WORLD = {CgVfxWorldInput.WORLD_DISTANCE};

        public CollideWorld(float bounce, float friction, float rest) {
            this(bounce, friction, rest, false);
        }

        public CollideWorld killing() {
            return new CollideWorld(bounce, friction, rest, true);
        }

        @Override
        public boolean afterSolve() {
            return true;
        }

        @Override
        public String gpuKind() {
            return "collide_world";
        }

        @Override
        public void writeParams(CgVfxWords out) {
            out.vec4(bounce, friction, rest, kill ? 1f : 0f);
        }

        @Override
        public CgVfxWorldInput[] worldInputs() {
            return WORLD;
        }
    }

    /**
     * What the camera sees, with {@link Collide}'s response (Niagara's and Unity's depth buffer collision): a particle
     * behind the scene's depth by at most {@code thickness} blocks goes back to where it was and responds along the
     * surface's normal there. Blind off screen and behind the first surface. Runs after the solver.
     *
     * <pre>{@code
     * new CgVfxModule.CollideDepth(0.5f, 0.2f, 0.3f, 0.6f)       // sparks off anything on screen
     * }</pre>
     */
    record CollideDepth(float bounce, float friction, float rest, float thickness, boolean kill) implements CgVfxModule {
        private static final CgVfxWorldInput[] DEPTH = {CgVfxWorldInput.DEPTH};

        public CollideDepth(float bounce, float friction, float rest, float thickness) {
            this(bounce, friction, rest, thickness, false);
        }

        public CollideDepth killing() {
            return new CollideDepth(bounce, friction, rest, thickness, true);
        }

        @Override
        public boolean afterSolve() {
            return true;
        }

        @Override
        public String gpuKind() {
            return "collide_depth";
        }

        @Override
        public int paramVectors() {
            return 2;
        }

        @Override
        public void writeParams(CgVfxWords out) {
            out.vec4(bounce, friction, rest, kill ? 1f : 0f).vec4(thickness, 0f, 0f, 0f);
        }

        @Override
        public CgVfxWorldInput[] worldInputs() {
            return DEPTH;
        }
    }

    /**
     * The entities round the camera (mobs, players; not items or projectiles), with {@link Collide}'s response at the
     * face of the box a particle entered, on screen or not. A particle strikes a box only on entering it, so one born
     * inside its caster flies out; its collision events carry the entity ({@code CgVfxEventRows.entity}). Runs after the
     * solver.
     *
     * <pre>{@code
     * new CgVfxModule.CollideEntities(0.4f, 0.3f, 0.3f, 0.5f)            // debris bouncing off mobs
     * new CgVfxModule.CollideEntities(0f, 0f, 0f, 1f).killing()          // a spray that stops on whoever it meets
     * }</pre>
     *
     * <ul>
     *   <li>Each particle tests every box, at most {@code CgVfxEntityBoxes.MAX} of the nearest the camera: keep it to
     *       emitters of thousands, not millions.</li>
     *   <li>An entity walking into resting or slow particles engulfs them: they began the step inside.</li>
     * </ul>
     *
     * @param size the share of a particle's size that collides: its box is grown by that
     */
    record CollideEntities(float bounce, float friction, float rest, float size, boolean kill) implements CgVfxModule {
        private static final CgVfxWorldInput[] ENTITIES = {CgVfxWorldInput.ENTITIES};

        public CollideEntities(float bounce, float friction, float rest, float size) {
            this(bounce, friction, rest, size, false);
        }

        public CollideEntities killing() {
            return new CollideEntities(bounce, friction, rest, size, true);
        }

        @Override
        public boolean afterSolve() {
            return true;
        }

        @Override
        public String gpuKind() {
            return "collide_entities";
        }

        @Override
        public int paramVectors() {
            return 2;
        }

        @Override
        public void writeParams(CgVfxWords out) {
            out.vec4(bounce, friction, rest, kill ? 1f : 0f).vec4(size, 0f, 0f, 0f);
        }

        @Override
        public CgVfxWorldInput[] worldInputs() {
            return ENTITIES;
        }
    }

    /**
     * Kills the particles inside a shape, or with {@code inside} false those outside it (bevy_hanabi's kill sphere and
     * kill box; a plane kills behind it, out to its radius). They die, and their death events fire, at the end of the
     * step. The shape is centred on the source, or {@link #at} a point from it. Runs after the solver.
     *
     * <pre>{@code
     * new CgVfxModule.Kill(CgSphere.of(0.4), true).at(0f, 1.5f, 0f)   // absorbed at the centre
     * new CgVfxModule.Kill(CgBox.of(20, 20, 20), false)                // never past the play area
     * }</pre>
     */
    record Kill(CgShape shape, float x, float y, float z, boolean inside) implements CgVfxModule {
        private static final CgVfxLane[] LANES = {CgVfxLane.VEC4};

        public Kill {
            CgVfxShapeRows.check(shape);
        }

        public Kill(CgShape shape, boolean inside) {
            this(shape, 0f, 0f, 0f, inside);
        }

        /** The shape centred {@code (x, y, z)} from the source. */
        public Kill at(float x, float y, float z) {
            return new Kill(shape, x, y, z, inside);
        }

        @Override
        public boolean afterSolve() {
            return true;
        }

        @Override
        public String gpuKind() {
            return "kill";
        }

        @Override
        public int paramVectors() {
            return 2;
        }

        @Override
        public void writeParams(CgVfxWords out) {
            CgVfxShapeRows.writeShape(shape, out);
            out.vec4(inside ? 1f : 0f, 0f, 0f, 0f);
        }

        @Override
        public CgVfxLane[] instanceLanes() {
            return LANES;
        }

        @Override
        public void writeInstance(CgVfxInstanceView instance, CgVfxWords out) {
            CgVfxShapeRows.writeCentre(shape, x, y, z, instance, out);
        }
    }
}
