package com.crystalgraphics.vfx.particle;

import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.shape.CgShape;
import com.crystalgraphics.shape.primitive.CgBox;
import com.crystalgraphics.shape.primitive.CgPlane;
import com.crystalgraphics.shape.primitive.CgSphere;
import com.crystalgraphics.vfx.particle.form.CgVfxFormCompiler;
import com.crystalgraphics.vfx.field.CgFieldDefinition;
import com.crystalgraphics.vfx.particle.gpu.CgVfxDataInterface;
import com.crystalgraphics.vfx.particle.gpu.CgVfxEvent;
import com.crystalgraphics.vfx.particle.gpu.CgVfxGpuModule;
import com.crystalgraphics.vfx.particle.gpu.CgVfxInstanceView;
import com.crystalgraphics.vfx.particle.gpu.CgVfxLane;
import com.crystalgraphics.vfx.particle.gpu.CgVfxWords;
import com.crystalgraphics.vfx.particle.gpu.sim.CgVfxDataInterfaces;

import org.joml.Vector3f;
import org.joml.Vector3fc;

import javax.annotation.Nullable;
import java.util.Locale;
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
 *   <li>A new kind is a record here, its declaration in {@link CgVfxModuleTypes} (its inputs, which are its GPU
 *       numbers) and its GPU side: {@code fx_<kind>.glsl} and the {@link CgVfxGpuModule} methods. A mod's own kind
 *       implements {@link CgVfxGpuModule} instead and declares itself.</li>
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

    /**
     * A constant acceleration, in blocks a second squared: Niagara's Gravity Force, whose {@code Gravity} is a vector.
     *
     * <pre>{@code
     * new CgVfxModule.Gravity(9.8f)               // Earth's, straight down
     * new CgVfxModule.Gravity(0f, -4f, 1.5f)      // down and drifting along +Z
     * }</pre>
     */
    record Gravity(float x, float y, float z) implements CgVfxModule {

        /** {@code strength} straight down. */
        public Gravity(float strength) {
            this(0f, -strength, 0f);
        }

        @Override
        public CgVfxModule scaled(float scale) {
            return new Gravity(x * scale, y * scale, z * scale);
        }

        @Override
        public float pull(float heat) {
            return (float) Math.sqrt(x * x + y * y + z * z);
        }

        @Override
        public String gpuKind() {
            return "gravity";
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

        /** Its declared inputs' words: the drag, a scale of 1, use_quadratic and the quadratic term ({@code fx_drag.glsl}). */
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
            return "curl_noise";
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
        private static final CgVfxDataInterface[] WORLD = {CgVfxDataInterfaces.FLOOR_Y};

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
        public CgVfxDataInterface[] dataInterfaces() {
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
        private static final CgVfxDataInterface[] WORLD = {CgVfxDataInterfaces.FLOOR_Y};

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
        public CgVfxDataInterface[] dataInterfaces() {
            return WORLD;
        }
    }

    /** Spinning slows down: the spin rate decays by {@code drag} a second. */
    record Spin(float drag) implements CgVfxModule {

        @Override
        public String gpuKind() {
            return "spin";
        }
    }

    /**
     * Turns a sprite by {@code radians} each update, or scales its turn by it: Cascade's RotationOverLifetime, which
     * adds its value to the particle's rotation every update rather than once (ParticleModules.cpp:2000-2019). Half a
     * turn flips a sprite every frame; a whole number of turns shows nothing.
     *
     * <pre>{@code
     * new CgVfxModule.SpriteRotation((float) Math.PI, false)   // mirrored every other frame
     * new CgVfxModule.SpriteRotation(0.5f, true)               // its turn scaled by half each update
     * }</pre>
     */
    record SpriteRotation(float radians, boolean scales) implements CgVfxModule {

        @Override
        public String gpuKind() {
            return "sprite_rotation";
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
     * new CgVfxModule.Attract(CgSphere.of(6), 30f).momentary()                  // Cascade's, its base velocity untouched
     * }</pre>
     *
     * <ul>
     *   <li>A lasting pull builds up velocity, a force (Niagara's, and Cascade's {@code bAffectBaseVelocity}). A
     *       {@link #momentary} one moves the particle for the step alone, as Cascade's other pull does over a base
     *       velocity reset each tick ({@code ParticleModules.cpp}, {@code UParticleModuleAttractorPoint::Update}).</li>
     *   <li>{@link #byInverseSquare} pulls by Cascade's GPU attractor instead: {@code strength} out to the edge, then
     *       falling with the square of the distance past it, and never cut off.</li>
     *   <li>A plane attracts nothing, and is refused, as is any shape but a sphere or box.</li>
     *   <li>Pair it with {@link Orbit} round the same centre for a spiral in, Godot's orbit velocity.</li>
     *   <li>{@link #clamped} holds the pull's direction to some axes and senses, as Cascade's {@code Positive_X} to
     *       {@code Negative_Z} do: each component of it, pointing the way the pull goes, kept between its low and high
     *       ends ({@code ParticleModules.cpp}, {@code UParticleModuleAttractorPoint::Update}).</li>
     * </ul>
     */
    record Attract(CgShape shape, float x, float y, float z, float strength, float attenuation, float directionality,
                   float axisX, float axisY, float axisZ, boolean lasting, boolean inverseSquare,
                   float lowX, float lowY, float lowZ, float highX, float highY, float highZ) implements CgVfxModule {
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
            this(shape, 0f, 0f, 0f, strength, 1f, 0f, 0f, 1f, 0f, true, false);
        }

        /** A pull every way, every other field given. */
        public Attract(CgShape shape, float x, float y, float z, float strength, float attenuation, float directionality,
                       float axisX, float axisY, float axisZ, boolean lasting, boolean inverseSquare) {
            this(shape, x, y, z, strength, attenuation, directionality, axisX, axisY, axisZ, lasting, inverseSquare,
                    -1f, -1f, -1f, 1f, 1f, 1f);
        }

        /**
         * This pull's direction held between {@code (lx, ly, lz)} and {@code (hx, hy, hz)}, component by component, -1 to 1:
         * Cascade's {@code Negative_X} is {@code lx} -1 (0 without it), {@code Positive_X} {@code hx} 1.
         *
         * <pre>{@code
         * new CgVfxModule.Attract(CgSphere.of(5), 20f).momentary().clamped(0f, -1f, -1f, 1f, 1f, 1f)   // never toward -x
         * }</pre>
         */
        public Attract clamped(float lx, float ly, float lz, float hx, float hy, float hz) {
            return new Attract(shape, x, y, z, strength, attenuation, directionality, axisX, axisY, axisZ, lasting, inverseSquare,
                    lx, ly, lz, hx, hy, hz);
        }

        /** A pull by attenuation, every field but that one given. */
        public Attract(CgShape shape, float x, float y, float z, float strength, float attenuation, float directionality,
                       float axisX, float axisY, float axisZ, boolean lasting) {
            this(shape, x, y, z, strength, attenuation, directionality, axisX, axisY, axisZ, lasting, false);
        }

        /** A lasting pull, every field but that one given. */
        public Attract(CgShape shape, float x, float y, float z, float strength, float attenuation, float directionality,
                       float axisX, float axisY, float axisZ) {
            this(shape, x, y, z, strength, attenuation, directionality, axisX, axisY, axisZ, true, false);
        }

        /** This pull for the step alone, leaving the velocity it builds on as it was. */
        public Attract momentary() {
            return new Attract(shape, x, y, z, strength, attenuation, directionality, axisX, axisY, axisZ, false, inverseSquare, lowX, lowY, lowZ, highX, highY, highZ);
        }

        /**
         * This pull by Cascade's GPU attractor: {@code strength} within the shape, {@code strength * (r / d)^2} past it,
         * with no edge ({@code ParticleSimulationShader.usf}, {@code ComputeAttractionForce}). Its attenuation goes unread.
         */
        public Attract byInverseSquare() {
            return new Attract(shape, x, y, z, strength, attenuation, directionality, axisX, axisY, axisZ, lasting, true, lowX, lowY, lowZ, highX, highY, highZ);
        }

        /** The shape centred {@code (x, y, z)} from the source. */
        public Attract at(float x, float y, float z) {
            return new Attract(shape, x, y, z, strength, attenuation, directionality, axisX, axisY, axisZ, lasting, inverseSquare, lowX, lowY, lowZ, highX, highY, highZ);
        }

        /** How the pull falls off toward the edge: 0 even, 1 linear (the default), 2 and up held to the centre. */
        public Attract attenuation(float attenuation) {
            return new Attract(shape, x, y, z, strength, attenuation, directionality, axisX, axisY, axisZ, lasting, inverseSquare, lowX, lowY, lowZ, highX, highY, highZ);
        }

        /** A push along {@code (ax, ay, az)} in place of {@code directionality} of the pull. */
        public Attract directed(float directionality, float ax, float ay, float az) {
            return new Attract(shape, x, y, z, strength, attenuation, directionality, ax, ay, az, lasting, inverseSquare, lowX, lowY, lowZ, highX, highY, highZ);
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
        public CgVfxLane[] instanceLanes() {
            return LANES;
        }

        @Override
        public void writeInstance(CgVfxInstanceView instance, CgVfxWords out) {
            CgVfxShapeRows.writeCentre(shape, x, y, z, instance, out);
        }
    }

    /**
     * Carries each particle along with its source's movement this step, times {@code (x, y, z)} per axis, once it has
     * lived past two steps: Cascade's SourceMovement, which moves a world-space emitter's particles with its component.
     * Runs after the solver.
     *
     * <pre>{@code
     * new CgVfxModule.SourceMovement(1f, 1f, 1f)     // sparks that stay with a moving hand
     * new CgVfxModule.SourceMovement(1f, 0f, 1f)     // follow across the ground, keep their own height
     * }</pre>
     *
     * <ul>
     *   <li>A particle's first two steps are left alone, as Cascade's are: a new one already starts at the source.</li>
     * </ul>
     */
    record SourceMovement(float x, float y, float z) implements CgVfxModule {
        private static final CgVfxLane[] LANES = {CgVfxLane.VEC4};

        @Override
        public boolean afterSolve() {
            return true;
        }

        @Override
        public String gpuKind() {
            return "source_movement";
        }

        @Override
        public CgVfxLane[] instanceLanes() {
            return LANES;
        }

        @Override
        public void writeInstance(CgVfxInstanceView instance, CgVfxWords out) {
            out.vec4(instance.sourceX() - instance.fromX(), instance.sourceY() - instance.fromY(),
                    instance.sourceZ() - instance.fromZ(), 0f);
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
        private static final CgVfxDataInterface[] NEIGHBOURS = {CgVfxDataInterfaces.NEIGHBOURS};

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
        public CgVfxDataInterface[] dataInterfaces() {
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
        private static final CgVfxDataInterface[] GAS = {CgVfxDataInterfaces.GAS};
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
        public CgVfxDataInterface[] dataInterfaces() {
            return GAS;
        }
    }

    /**
     * The fields its group places, applied for its target: Niagara's PhysicsField module reading Chaos's physics
     * fields. Every field of the group whose bounds hold a particle adds its value, times its weight and the module's
     * scale, to what the target moves; a file's scale rolled per particle is a form ({@code scale: { range: [a, b] }}).
     *
     * <pre>{@code
     * .module(new CgVfxModule.PhysicsField(CgFieldWorld.group("mymod.pull"), CgFieldDefinition.Target.LINEAR_FORCE, 1f))
     * }</pre>
     *
     * <ul>
     *   <li>{@code linear_force} adds to the acceleration; {@code linear_velocity} moves it by the value for the step,
     *       never kept; {@code kill} ends it where the value is above 0; {@code orbit} turns it about the value's
     *       direction through the field's place, the value's length a rate.</li>
     *   <li>{@code speed_scale}, {@code angular_velocity} and {@code scale} add the value, a rate per 30 fps frame, to
     *       what the particle keeps: its speed scale from 1 (floored at 0, multiplying its motion), a rotator on its mesh
     *       turn, its scale per axis from 1 (none drawn once an axis reaches 0).</li>
     *   <li>{@code strain} acts on the stage, never a particle: it fails here, naming it.</li>
     *   <li>An emitter with none feels no field; a group's fields reach only its own modules.</li>
     * </ul>
     *
     * @param group  its group's number, {@code CgFieldWorld.group(name)}
     * @param target what the fields move: {@code CgFieldDefinition.Target}'s place, one of those above
     */
    record PhysicsField(int group, int target, float scale) implements CgVfxModule {
        private static final CgVfxDataInterface[] FIELDS = {CgVfxDataInterfaces.FIELDS};

        public PhysicsField(int group, CgFieldDefinition.Target target, float scale) {
            this(group, applied(target), scale);
        }

        /** {@code target}'s place, where this module applies it; one it does not apply yet fails, naming it. */
        public static int applied(CgFieldDefinition.Target target) {
            if (target == CgFieldDefinition.Target.STRAIN) {
                throw new IllegalArgumentException("a physics_field of target " + target.name() + " acts on the stage, not on particles");
            }
            return target.index();
        }

        @Override
        public String gpuKind() {
            return "physics_field";
        }

        @Override
        public boolean afterSolve() {
            return false;
        }

        @Override
        public CgVfxDataInterface[] dataInterfaces() {
            return FIELDS;
        }
    }

    /**
     * Pours into a gas and pushes it: each particle adds {@code density} and {@code heat} a second where it is, and pulls
     * the gas's velocity there toward its own at {@code push} a second (NVIDIA Flow's point emitter and its velocity
     * couple rate). The gas takes it at its next step, so a jet's particles leave smoke along their paths, embers warm the
     * air they rise through, and a flock flying through smoke drags wakes behind it. The gas is the instance's
     * ({@code CgVfxEmitterInstance.inGas}); with none, it does nothing.
     *
     * <pre>{@code
     * .module(new CgVfxModule.Feed(4f, 0.5f))         // each spark trailing smoke and a little heat
     * .module(new CgVfxModule.Feed(0f, 0f, 6f))       // pushing alone: wakes in the smoke behind each
     * .module(new CgVfxModule.Feed(0.2f, 1.2f, 8f))   // a flamethrower's stream: hot, and driving the flame with it
     * sparks.inGas(smoke);                             // in the effect, after making both
     * }</pre>
     *
     * <ul>
     *   <li>Lands where the gas holds tiles: the engine asks for tiles round each instance feeding it, as far as its
     *       particles reach (16 blocks at most).</li>
     *   <li>A deposit is spread over the cells round the particle by distance, so its mark is a soft cloud about a
     *       block across at a 0.25 cell. Where many push one cell, it takes their velocities' mean, weighted by how near
     *       each is, as Flow's does.</li>
     *   <li>{@code push} is a share of the way a second, as an emitter's couple rates are: past about 60 a step takes the
     *       particles' velocity whole.</li>
     *   <li>At most {@code CgVfxFluidAtlas.DEPOSITS} particles pour a frame, every instance's together; the rest pour nothing.</li>
     * </ul>
     */
    record Feed(float density, float heat, float push) implements CgVfxModule {
        private static final CgVfxDataInterface[] FEEDS = {CgVfxDataInterfaces.GAS_FEED};

        public Feed {
            if (!(density >= 0f) || !(heat >= 0f) || !(push >= 0f)) {
                throw new IllegalArgumentException("a feed of " + density + ", " + heat + ", " + push);
            }
        }

        /** Pouring alone: no push. */
        public Feed(float density, float heat) {
            this(density, heat, 0f);
        }

        @Override
        public String gpuKind() {
            return "gas_feed";
        }

        @Override
        public CgVfxDataInterface[] dataInterfaces() {
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
        private static final CgVfxDataInterface[] SPLATS = {CgVfxDataInterfaces.GRID_SPLAT};

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
        public CgVfxDataInterface[] dataInterfaces() {
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
        private static final CgVfxDataInterface[] GRID = {CgVfxDataInterfaces.GRID};

        public Follow {
            if (channel < 0 || channel > 3) throw new IllegalArgumentException("a grid's channel " + channel + ": 0 to 3");
            if (!(full >= 0f)) throw new IllegalArgumentException("full " + full);
        }

        /** No ceiling: a trail draws harder the more it holds. */
        public Follow(int channel, float sense, float angle, float turn) {
            this(channel, sense, angle, turn, 0f);
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
        public CgVfxDataInterface[] dataInterfaces() {
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
        private static final CgVfxDataInterface[] GRID = {CgVfxDataInterfaces.GRID};

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
        public CgVfxDataInterface[] dataInterfaces() {
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
    }

    /**
     * A {@link CgVfxField} over a box (Godot's vector field attractor): each particle pushed by the field where it is,
     * {@code strength} blocks a second squared for a unit vector, a longer one as {@code length^attenuation}. Outside the
     * box nothing pushes, unless the field tiles. The box is centred on the source, or {@link #at} a point from it.
     * {@code tightness} draws each particle's velocity toward the field's own by that share every thirtieth of a second
     * whatever the step, Cascade's vector field tightness under its fixed 1/30 s steps
     * ({@code ParticleSimulationShader.usf:236-245, 584-585}): 0 pushes alone, 1 moves with the field.
     *
     * <pre>{@code
     * new CgVfxModule.VectorField(swirl, CgBox.of(4, 4, 4), 3f, 1f).at(0f, 4f, 0f)
     * }</pre>
     */
    record VectorField(CgVfxField field, CgBox box, float x, float y, float z, float strength, float attenuation,
                       float tightness, CgVfxFieldTurn turn) implements CgVfxModule {
        private static final CgVfxLane[] LANES = {CgVfxLane.VEC4, CgVfxLane.VEC4};
        /** Its turn's roll once an instance, keyed as a form's roll is by its module and input. */
        private static final int TURN_KEY = CgVfxFormCompiler.key("vector_field", "initial_rotation");

        public VectorField {
            Objects.requireNonNull(field, "field");
            Objects.requireNonNull(box, "box");
            if (!(attenuation >= 0f)) throw new IllegalArgumentException("attenuation " + attenuation);
        }

        public VectorField(CgVfxField field, CgBox box, float strength, float attenuation) {
            this(field, box, 0f, 0f, 0f, strength, attenuation, 0f, CgVfxFieldTurn.NONE);
        }

        public VectorField(CgVfxField field, CgBox box, float x, float y, float z, float strength, float attenuation, float tightness) {
            this(field, box, x, y, z, strength, attenuation, tightness, CgVfxFieldTurn.NONE);
        }

        /** The box centred {@code (x, y, z)} from the source. */
        public VectorField at(float x, float y, float z) {
            return new VectorField(field, box, x, y, z, strength, attenuation, tightness, turn);
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
        public CgVfxLane[] instanceLanes() {
            return LANES;
        }

        @Override
        public void writeInstance(CgVfxInstanceView instance, CgVfxWords out) {
            CgVfxShapeRows.writeCentre(box, x, y, z, instance, out);
            float[] q = TURNED.get();
            turn.at(CgVfxEmitterInstance.rand(instance.seed() ^ TURN_KEY, 0, 0), instance.time(), q);
            out.vec4(q[0], q[1], q[2], q[3]);
        }

        @Override
        public CgTexture[] textures() {
            return field.textures();
        }

        /** A turn being written, a thread's own: instances write on the render thread and the books' workers. */
        private static final ThreadLocal<float[]> TURNED = ThreadLocal.withInitial(() -> new float[4]);
    }

    /**
     * Cascade's LocationDirect (ParticleModules_Location.cpp:353-385): each step each particle is aimed at
     * {@code (x, y, z)} plus {@code (ox, oy, oz)} from its effect's origin, {@code share} of the way by the velocity it
     * is given and the rest moved at once. A curve over life on the position is its input's form: a path to follow.
     *
     * <pre>{@code
     * .module(new CgVfxModule.SetPosition(0f, 2f, 0f, 0f, 0f, 0f, 1f))   // held 2 blocks above its source
     * }</pre>
     *
     * <ul>
     *   <li>It overrides the velocity forces before it gave; list it first.</li>
     * </ul>
     */
    record SetPosition(float x, float y, float z, float ox, float oy, float oz, float share) implements CgVfxModule {

        @Override
        public String gpuKind() {
            return "set_position";
        }
    }

    /**
     * A beam's start, Cascade's BeamSource ({@code ParticleBeamModules.cpp:1874-2130}): each beam particle's source
     * point, its tangent and the tangent's strength, resolved at its birth and again each step unless locked. A beam
     * emitter's renderer is a {@code beam}; its far end is {@link BeamTarget}.
     *
     * <pre>{@code
     * .module(new CgVfxModule.BeamSource(0f, 0f, 0f, false, 1f, 0f, 0f, Tangent.DIRECT, 25f, true, true, true))
     * }</pre>
     *
     * <ul>
     *   <li>{@code point} is from the effect's origin, turned with it, unless {@code absolute}: then a world position.</li>
     *   <li>{@link Tangent#DIRECT} is the effect's own forward (+X turned by its rotation), and ignores {@code tangent}.</li>
     *   <li>A range on {@code point} rolls once a particle: a locked end keeps it, as Cascade's does.</li>
     *   <li>{@code actor} names a point of the entity its effect follows ({@code CgEntityAttachments}), as Cascade's
     *       {@code Actor} method names an actor parameter; where nothing answers it, {@code point} is the end, as
     *       Cascade's falls back to its distribution ({@code ParticleBeamModules.cpp:1985-2015}).</li>
     * </ul>
     *
     * <pre>{@code
     * // from the followed character's right hand, or 1.5 blocks up when it has none
     * .module(new CgVfxModule.BeamSource(0f, 1.5f, 0f, false, 1f, 0f, 0f, Tangent.DIRECT, 25f, false, true, true, "hand_r"))
     * }</pre>
     */
    record BeamSource(float x, float y, float z, boolean absolute, float tx, float ty, float tz, Tangent method,
                      float strength, boolean lock, boolean lockTangent, boolean lockStrength, @Nullable String actor)
            implements CgVfxModule {
        private static final CgVfxDataInterface[] BEAM = {CgVfxDataInterfaces.BEAM};

        /**
         * How an end's tangent is found ({@code ParticleBeamModules.cpp:2044-2092}): the effect's forward; {@code tangent}
         * turned with the effect unless {@code absolute} (Cascade's user-set, which nothing sets here); or {@code tangent}
         * as written.
         */
        public enum Tangent { DIRECT, USER_SET, DISTRIBUTION }

        public BeamSource {
            Objects.requireNonNull(method, "method");
        }

        /** Its actor's point: one vec4 an end. */
        static final CgVfxLane[] ACTOR_LANES = {CgVfxLane.VEC4};
        /** A point being written, a thread's own: instances write on the render thread and the books' workers. */
        private static final ThreadLocal<float[]> POINT = ThreadLocal.withInitial(() -> new float[3]);

        static void writeActor(@Nullable String actor, CgVfxInstanceView instance, CgVfxWords out) {
            float[] p = POINT.get();
            if (actor != null && instance.point(actor, p)) out.vec4(p[0], p[1], p[2], 1f);
            else out.vec4(0f, 0f, 0f, 0f);
        }

        /** An end at {@code point} alone, naming no actor. */
        public BeamSource(float x, float y, float z, boolean absolute, float tx, float ty, float tz, Tangent method,
                          float strength, boolean lock, boolean lockTangent, boolean lockStrength) {
            this(x, y, z, absolute, tx, ty, tz, method, strength, lock, lockTangent, lockStrength, null);
        }

        @Override
        public String gpuKind() {
            return "beam_source";
        }

        @Override
        public CgVfxLane[] instanceLanes() {
            return BeamSource.ACTOR_LANES;
        }

        /** Its actor's point from the effect's origin, w 1 where something answers it, else 0. */
        @Override
        public void writeInstance(CgVfxInstanceView instance, CgVfxWords out) {
            BeamSource.writeActor(actor, instance, out);
        }

        @Override
        public boolean afterSolve() {
            return true;
        }

        @Override
        public CgVfxDataInterface[] dataInterfaces() {
            return BEAM;
        }
    }

    /**
     * A beam's far end, Cascade's BeamTarget ({@code ParticleBeamModules.cpp:2324-2560}): {@link BeamSource}'s inputs
     * for the target, and how its end travels there. At {@code speed} 0 the beam reaches its target at once; above it the
     * particle flies from the source at {@code speed} blocks a second and the beam is drawn to where it has got, locking
     * on within {@code lockRadius} ({@code :300-326}).
     *
     * <pre>{@code
     * .module(new CgVfxModule.BeamTarget(8f, 0f, 0f, false, -1f, 0f, 0f, BeamSource.Tangent.DIRECT, 25f, true, true, true, 0f, 0f))
     * }</pre>
     *
     * <ul>
     *   <li>{@code speed} is its renderer's; an asset's compiler copies it here.</li>
     *   <li>{@code actor} as {@link BeamSource}'s.</li>
     * </ul>
     */
    record BeamTarget(float x, float y, float z, boolean absolute, float tx, float ty, float tz, BeamSource.Tangent method,
                      float strength, boolean lock, boolean lockTangent, boolean lockStrength, float lockRadius, float speed,
                      @Nullable String actor) implements CgVfxModule {
        private static final CgVfxDataInterface[] BEAM = {CgVfxDataInterfaces.BEAM};

        public BeamTarget {
            Objects.requireNonNull(method, "method");
        }

        /** A far end at {@code point} alone, naming no actor. */
        public BeamTarget(float x, float y, float z, boolean absolute, float tx, float ty, float tz, BeamSource.Tangent method,
                          float strength, boolean lock, boolean lockTangent, boolean lockStrength, float lockRadius, float speed) {
            this(x, y, z, absolute, tx, ty, tz, method, strength, lock, lockTangent, lockStrength, lockRadius, speed, null);
        }

        @Override
        public String gpuKind() {
            return "beam_target";
        }

        @Override
        public CgVfxLane[] instanceLanes() {
            return BeamSource.ACTOR_LANES;
        }

        /** Its actor's point from the effect's origin, w 1 where something answers it, else 0. */
        @Override
        public void writeInstance(CgVfxInstanceView instance, CgVfxWords out) {
            BeamSource.writeActor(actor, instance, out);
        }

        @Override
        public boolean afterSolve() {
            return true;
        }

        @Override
        public CgVfxDataInterface[] dataInterfaces() {
            return BEAM;
        }
    }

    /**
     * Lightning along a beam, Cascade's BeamNoise ({@code ParticleBeamModules.cpp:1372-1570}): {@code frequency + 1}
     * points along it, each offset by a pick from {@code min..max} (blocks, in the effect's frame), picked again each
     * {@code lockTime} seconds (each step at 0, never below 0). With {@code smooth} a new pick is where each point heads,
     * at {@code speed} times the seconds since the pick, arriving within {@code lockRadius}.
     *
     * <pre>{@code
     * .module(new CgVfxModule.BeamNoise(12, 0, 0.06f, false, -0.3f, -0.3f, -0.3f, 0.3f, 0.3f, 0.3f, true, false,
     *         0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0f, 2, 1f, 1f, 0.5f, 0.5f, 0f, 1f, false, 1))
     * }</pre>
     *
     * <ul>
     *   <li>{@code uniform} with {@code oscillate} alternates each point between {@code min} and {@code max}, as a uniform
     *       distribution's extremes; otherwise each channel rolls between them.</li>
     *   <li>Speed, range scale and tangent strength are each a range Cascade reads at its emitter's time each frame: one
     *       roll an emitter a step between its two ends, equal ends for a constant.</li>
     *   <li>{@code frequencyDistance} above 0 sets the points by the beam's length, one each that many blocks, at most
     *       {@code frequency}, scaled by {@code scale}.</li>
     *   <li>{@code sheets} is its renderer's: Cascade's smooth points move once each time a sheet is drawn.</li>
     * </ul>
     */
    record BeamNoise(int frequency, int frequencyLow, float lockTime, boolean smooth, float minX, float minY, float minZ,
                     float maxX, float maxY, float maxZ, boolean uniform, boolean oscillate, float speedMinX,
                     float speedMinY, float speedMinZ, float speedMaxX, float speedMaxY, float speedMaxZ, float lockRadius,
                     int tessellation, float rangeScaleMin, float rangeScaleMax, float tangentStrengthMin,
                     float tangentStrengthMax, float frequencyDistance, float scale, boolean targetNoise, int sheets)
            implements CgVfxModule {
        private static final CgVfxDataInterface[] BEAM = {CgVfxDataInterfaces.BEAM};

        public BeamNoise {
            if (frequency < 1) throw new IllegalArgumentException("a beam's noise of frequency " + frequency);
            if (sheets < 1) throw new IllegalArgumentException("a beam of " + sheets + " sheets");
        }

        @Override
        public String gpuKind() {
            return "beam_noise";
        }

        @Override
        public boolean afterSolve() {
            return true;
        }

        @Override
        public CgVfxDataInterface[] dataInterfaces() {
            return BEAM;
        }
    }

    /**
     * Shifts one end of a beam after it resolves, Cascade's BeamModifier ({@code ParticleBeamModules.cpp:1083-1180},
     * {@code ParticleHelper.h}'s {@code FBeamParticleModifierPayloadData}): its position, tangent and strength each
     * added to, or multiplied by where {@code scale...}, when {@code modify...}. A tangent is turned from +X onto the end's
     * own unless {@code absoluteTangent}.
     *
     * <pre>{@code
     * .module(new CgVfxModule.BeamModifier(true, 0f, 1f, 0f, true, false, 0f, 0f, 0f, false, false, false, 0f, false, false))
     * }</pre>
     *
     * <ul>
     *   <li>List it after its end's module. On an end that is locked it applies every step, so an offset adds up, as
     *       Cascade's does.</li>
     * </ul>
     */
    record BeamModifier(boolean target, float x, float y, float z, boolean modifyPosition, boolean scalePosition,
                        float tx, float ty, float tz, boolean modifyTangent, boolean scaleTangent, boolean absoluteTangent,
                        float strength, boolean modifyStrength, boolean scaleStrength) implements CgVfxModule {

        @Override
        public String gpuKind() {
            return "beam_modifier";
        }

        @Override
        public boolean afterSolve() {
            return true;
        }
    }

    /**
     * Cascade's velocity over life (ParticleModules_Velocity.cpp:266-330): each particle moves at its velocity times
     * {@code (x, y, z)}, or at {@code (x, y, z)} itself when {@code absolute}, while its velocity keeps what the forces
     * give it, as Cascade's base velocity does. A curve over life is its input's form.
     *
     * <pre>{@code
     * .module(new CgVfxModule.ScaleVelocity(0.5f, 0.5f, 0.5f, false))   // half as fast, however it is pushed
     * }</pre>
     *
     * <ul>
     *   <li>Collisions and what reads the velocity see the stored velocity, not the scaled motion.</li>
     * </ul>
     */
    record ScaleVelocity(float x, float y, float z, boolean absolute) implements CgVfxModule {

        @Override
        public String gpuKind() {
            return "scale_velocity";
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
     *   <li>Cascade's damping, delay, count and completion are its {@link CollisionResponse}.</li>
     * </ul>
     */
    record Collide(CgShape shape, float x, float y, float z, float bounce, float friction, float rest, float radius,
                   boolean keepsInside, CollisionResponse response) implements CgVfxModule {
        private static final CgVfxLane[] LANES = {CgVfxLane.VEC4};

        public Collide {
            CgVfxShapeRows.check(shape);
        }

        public Collide(CgShape shape, float bounce, float friction, float rest) {
            this(shape, 0f, 0f, 0f, bounce, friction, rest, 1f, false, CollisionResponse.NONE);
        }

        /** The shape centred {@code (x, y, z)} from the source. */
        public Collide at(float x, float y, float z) {
            return new Collide(shape, x, y, z, bounce, friction, rest, radius, keepsInside, response);
        }

        /** Collides as a ball of {@code radius} times a particle's size. */
        public Collide radius(float radius) {
            return new Collide(shape, x, y, z, bounce, friction, rest, radius, keepsInside, response);
        }

        /** Keeps particles inside the shape rather than out of it. */
        public Collide container() {
            return new Collide(shape, x, y, z, bounce, friction, rest, radius, true, response);
        }

        /** Kills a particle on contact rather than bouncing it. */
        public Collide killing() {
            return response(response.limit(1, CollisionCompletion.KILL));
        }

        /** Responds with {@code response}'s damping, delay, count and completion. */
        public Collide response(CollisionResponse response) {
            return new Collide(shape, x, y, z, bounce, friction, rest, radius, keepsInside, response);
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
        public CgVfxLane[] instanceLanes() {
            return LANES;
        }

        @Override
        public void writeInstance(CgVfxInstanceView instance, CgVfxWords out) {
            CgVfxShapeRows.writeCentre(shape, x, y, z, instance, out);
        }
    }

    /** What ends a particle's collisions once its count is spent: Cascade's {@code EParticleCollisionComplete}. */
    enum CollisionCompletion {
        /** It dies there. */
        KILL,
        /** It holds still and unturned where it struck, and collides no more; it still ages and dies. */
        FREEZE,
        /** It carries on as it was and collides no more. */
        HALT,
        /** It holds still where it struck, still turning, and collides no more. */
        FREEZE_TRANSLATION,
        /** It stops turning and carries on, colliding no more. */
        FREEZE_ROTATION,
        /** {@link #FREEZE_TRANSLATION} and {@link #FREEZE_ROTATION}. */
        FREEZE_MOVEMENT;

        /** Its name in a file: {@code freeze_translation}. */
        public String fileName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * A collider's Cascade half (4.21's {@code UParticleModuleCollision}): what a contact does to a particle beyond the
     * collider's own bounce. Damping, when set, replaces the bounce and friction: the whole velocity mirrored about the
     * surface and scaled per axis, rolled per particle between its ends. A count, when set, makes every contact a hit,
     * and the one that spends it runs the completion instead of bouncing.
     *
     * <pre>{@code
     * new CgVfxModule.CollideWorld(0f, 0f, 0f).response(CgVfxModule.CollisionResponse.NONE
     *         .damping(0.5f, 0.25f, 0.5f)                                  // Cascade's DampingFactor, y vertical
     *         .limit(3, CgVfxModule.CollisionCompletion.FREEZE))           // two bounces, then lies where the third lands
     *
     * CgVfxModule.CollisionResponse.NONE
     *         .damping(0f, 0f, 0f, 0.3f, 0.1f, 0.3f, CgVfxModule.CollisionResponse.PER_AXIS)   // each its own
     *         .delay(0.2f)                                                 // nothing for its first fifth of life
     *         .rotation(0.5f, 0.5f, 0.5f)                                  // each bounce halves its spin
     *         .limit(1f, 3f, CgVfxModule.CollisionCompletion.KILL)         // a count rolled and rounded per particle
     * }</pre>
     *
     * <ul>
     *   <li>Damping's axes are the world's: its vertical is y.</li>
     *   <li>A range's rolls are {@link CgVfxLifeRoll#rolls}': which of three rolls each axis takes, x always its own.</li>
     *   <li>The delay is a share of the particle's life, 0 to 1, as Cascade compares it, not seconds.</li>
     *   <li>A count below 1 is 1: Cascade decrements it before it tests it.</li>
     *   <li>A frozen particle's colour still plays over its life, which Cascade's {@code Freeze} holds at its start.</li>
     * </ul>
     *
     * @param dampingMin    the damping's low end per axis; null for none: the collider's bounce and friction
     * @param dampingMax    its high end
     * @param dampingRolls  which roll each axis of it takes
     * @param delay         the share of its life a particle collides after
     * @param rotation      what each bounce scales its rotation rates by, its low end: a sprite's by x, a mesh's per axis
     * @param rotationMax   its high end; null for the constant {@code rotation}
     * @param rotationRolls which roll each axis of it takes
     * @param completion    what spending its count does; null for no count
     * @param mostMin       the count's low end, contacts until the completion
     * @param mostMax       its high end
     * @param lead          a world collider's test led along the particle's motion by this many times its size:
     *                      Cascade's trace reaching its size over {@code DirScalar} ahead ({@code :360}); 0 for none
     */
    record CollisionResponse(@Nullable Vector3fc dampingMin, @Nullable Vector3fc dampingMax, int[] dampingRolls,
                             float delay, Vector3fc rotation, @Nullable Vector3fc rotationMax, int[] rotationRolls,
                             @Nullable CollisionCompletion completion, float mostMin, float mostMax, float lead,
                             boolean onlyVertical, float verticalFudge) {
        /** A roll for each axis: Cascade's per-channel range. */
        public static final int[] PER_AXIS = {0, 1, 2};
        /** One roll for every axis. */
        public static final int[] ONE_ROLL = {0, 0, 0};
        /** The collider's bounce alone, at every contact, with no count. */
        public static final CollisionResponse NONE = new CollisionResponse(null, null, PER_AXIS, 0f, new Vector3f(1f), null,
                PER_AXIS, null, 0f, 0f, 0f, false, 0.1f);
        /** Death at the first contact. */
        public static final CollisionResponse KILL = NONE.limit(1, CollisionCompletion.KILL);

        public CollisionResponse {
            dampingRolls = dampingRolls.clone();
            rotationRolls = rotationRolls.clone();
            if (dampingRolls.length != 3 || rotationRolls.length != 3) throw new IllegalArgumentException("three rolls an axis");
        }

        /** Damped by {@code (x, y, z)} at every bounce. */
        public CollisionResponse damping(float x, float y, float z) {
            return damping(x, y, z, x, y, z, PER_AXIS);
        }

        /** Damped between the two ends, rolled per particle, each axis taking the roll {@code rolls} names. */
        public CollisionResponse damping(float x0, float y0, float z0, float x1, float y1, float z1, int[] rolls) {
            return new CollisionResponse(new Vector3f(x0, y0, z0), new Vector3f(x1, y1, z1), rolls, delay, rotation,
                    rotationMax, rotationRolls, completion, mostMin, mostMax, lead, onlyVertical, verticalFudge);
        }

        /** Colliding only after {@code share} of a particle's life. */
        public CollisionResponse delay(float share) {
            return new CollisionResponse(dampingMin, dampingMax, dampingRolls, share, rotation, rotationMax, rotationRolls,
                    completion, mostMin, mostMax, lead, onlyVertical, verticalFudge);
        }

        /** Each bounce scaling its rotation rates by {@code (x, y, z)}. */
        public CollisionResponse rotation(float x, float y, float z) {
            return new CollisionResponse(dampingMin, dampingMax, dampingRolls, delay, new Vector3f(x, y, z), null, PER_AXIS,
                    completion, mostMin, mostMax, lead, onlyVertical, verticalFudge);
        }

        /** Each bounce scaling its rotation rates between the two ends, rolled per particle as {@code rolls} names. */
        public CollisionResponse rotation(float x0, float y0, float z0, float x1, float y1, float z1, int[] rolls) {
            return new CollisionResponse(dampingMin, dampingMax, dampingRolls, delay, new Vector3f(x0, y0, z0),
                    new Vector3f(x1, y1, z1), rolls, completion, mostMin, mostMax, lead, onlyVertical, verticalFudge);
        }

        /** {@code most} contacts, the last running {@code completion}. */
        public CollisionResponse limit(int most, CollisionCompletion completion) {
            return limit(most, most, completion);
        }

        /** A count rolled between the two and rounded per particle, the last contact running {@code completion}. */
        public CollisionResponse limit(float mostMin, float mostMax, CollisionCompletion completion) {
            return new CollisionResponse(dampingMin, dampingMax, dampingRolls, delay, rotation, rotationMax, rotationRolls,
                    completion, mostMin, mostMax, lead, onlyVertical, verticalFudge);
        }

        /**
         * Only near-vertical hits spending its count: those whose normal's vertical, plus {@code fudge}, reaches 1
         * (Cascade's {@code bOnlyVerticalNormalsDecrementCount}, ParticleModules_Collision.cpp:397-408). The others
         * still bounce and fire their collision events.
         */
        public CollisionResponse onlyVertical(float fudge) {
            return new CollisionResponse(dampingMin, dampingMax, dampingRolls, delay, rotation, rotationMax, rotationRolls,
                    completion, mostMin, mostMax, lead, true, fudge);
        }

        /** A world collider's test led {@code times} a particle's size along its motion. */
        public CollisionResponse lead(float times) {
            return new CollisionResponse(dampingMin, dampingMax, dampingRolls, delay, rotation, rotationMax, rotationRolls,
                    completion, mostMin, mostMax, times, onlyVertical, verticalFudge);
        }

        /** Whether it changes a particle's rotation rates: a rotation restitution other than 1, or a completion freezing rotation. */
        public boolean turns() {
            return rotation.x() != 1f || rotation.y() != 1f || rotation.z() != 1f || rotationMax != null
                    || completion == CollisionCompletion.FREEZE
                    || completion == CollisionCompletion.FREEZE_ROTATION || completion == CollisionCompletion.FREEZE_MOVEMENT;
        }

        /** {@code module}'s response, or null for a module that is no collider. */
        @Nullable
        public static CollisionResponse of(CgVfxModule module) {
            if (module instanceof Collide c) return c.response();
            if (module instanceof CollideWorld c) return c.response();
            if (module instanceof CollideDepth c) return c.response();
            if (module instanceof CollideEntities c) return c.response();
            return null;
        }
    }

    /** A collider's lane: 1 while the camera is within its reach of the effect this step, else 0. */
    CgVfxLane[] NEAR_LANES = {CgVfxLane.VEC4};

    /** 1 where the camera is within {@code reach}, grown by Cascade's 7.5%, of {@code instance}'s effect; else 0. */
    static float near(CgVfxInstanceView instance, float reach) {
        return reach == Float.POSITIVE_INFINITY || instance.cameraDistance() <= reach * 1.075f ? 1f : 0f;
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
     * new CgVfxModule.CollideWorld(0.4f, 0.3f, 0.3f).within(10f)  // only while a camera is within 10 blocks
     * }</pre>
     *
     * <ul>
     *   <li>{@code reach}: Cascade's {@code MaxCollisionDistance}, its instance colliding only while the camera is
     *       within that of its effect's bounds, grown by 7.5% (ParticleModules_Collision.cpp:227-262, 4.21); infinite
     *       for always. Its effect's bounds hold its emitter's, so it collides as far as Cascade's or a little
     *       further.</li>
     * </ul>
     */
    record CollideWorld(float bounce, float friction, float rest, CollisionResponse response, float reach) implements CgVfxModule {
        private static final CgVfxDataInterface[] WORLD = {CgVfxDataInterfaces.WORLD_DISTANCE};

        public CollideWorld(float bounce, float friction, float rest) {
            this(bounce, friction, rest, CollisionResponse.NONE.lead(LEAD), Float.POSITIVE_INFINITY);
        }

        /** Cascade's default lead: a trace reaching its size over {@code DirScalar}'s 3.5, the size a full width. */
        public static final float LEAD = 2f / 3.5f;

        public CollideWorld killing() {
            return response(response.limit(1, CollisionCompletion.KILL));
        }

        /** Responds with {@code response}'s damping, delay, count and completion. */
        public CollideWorld response(CollisionResponse response) {
            return new CollideWorld(bounce, friction, rest, response, reach);
        }

        /** The same, colliding only while the camera is within {@code blocks} of its effect. */
        public CollideWorld within(float blocks) {
            return new CollideWorld(bounce, friction, rest, response, blocks);
        }

        @Override
        public CgVfxLane[] instanceLanes() {
            return NEAR_LANES;
        }

        @Override
        public void writeInstance(CgVfxInstanceView instance, CgVfxWords out) {
            out.vec4(near(instance, reach), 0f, 0f, 0f);
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
        public CgVfxDataInterface[] dataInterfaces() {
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
    record CollideDepth(float bounce, float friction, float rest, float thickness, CollisionResponse response, float reach)
            implements CgVfxModule {
        private static final CgVfxDataInterface[] DEPTH = {CgVfxDataInterfaces.DEPTH};

        public CollideDepth(float bounce, float friction, float rest, float thickness) {
            this(bounce, friction, rest, thickness, CollisionResponse.NONE, Float.POSITIVE_INFINITY);
        }

        public CollideDepth killing() {
            return response(response.limit(1, CollisionCompletion.KILL));
        }

        /** Responds with {@code response}'s damping, delay, count and completion. */
        public CollideDepth response(CollisionResponse response) {
            return new CollideDepth(bounce, friction, rest, thickness, response, reach);
        }

        /** The same, colliding only while the camera is within {@code blocks} of its effect ({@link CollideWorld#reach}). */
        public CollideDepth within(float blocks) {
            return new CollideDepth(bounce, friction, rest, thickness, response, blocks);
        }

        @Override
        public CgVfxLane[] instanceLanes() {
            return NEAR_LANES;
        }

        @Override
        public void writeInstance(CgVfxInstanceView instance, CgVfxWords out) {
            out.vec4(near(instance, reach), 0f, 0f, 0f);
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
        public CgVfxDataInterface[] dataInterfaces() {
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
     *   <li>Each particle tests every box, at most {@code CgWorldEntityBoxes.MAX} of the nearest the camera: keep it to
     *       emitters of thousands, not millions.</li>
     *   <li>An entity walking into resting or slow particles engulfs them: they began the step inside.</li>
     * </ul>
     *
     * @param size       the share of a particle's size that collides: its box is grown by that
     * @param keepsCount whether a hit spends none of {@code response}'s count: Cascade's
     *                   {@code bPawnsDoNotDecrementCount}, every entity here a pawn
     * @param reach      how near the camera must be, in blocks, for it to collide: infinite for always
     */
    record CollideEntities(float bounce, float friction, float rest, float size, CollisionResponse response,
                           boolean keepsCount, float reach) implements CgVfxModule {
        private static final CgVfxDataInterface[] ENTITIES = {CgVfxDataInterfaces.ENTITIES};

        public CollideEntities(float bounce, float friction, float rest, float size) {
            this(bounce, friction, rest, size, CollisionResponse.NONE, false, Float.POSITIVE_INFINITY);
        }

        public CollideEntities killing() {
            return response(response.limit(1, CollisionCompletion.KILL));
        }

        /** Responds with {@code response}'s damping, delay, count and completion. */
        public CollideEntities response(CollisionResponse response) {
            return new CollideEntities(bounce, friction, rest, size, response, keepsCount, reach);
        }

        /** Its hits bounce but spend none of the count, as Cascade spares a pawn's. */
        public CollideEntities keepingCount() {
            return new CollideEntities(bounce, friction, rest, size, response, true, reach);
        }

        /** The same, colliding only while the camera is within {@code blocks} of its effect. */
        public CollideEntities within(float blocks) {
            return new CollideEntities(bounce, friction, rest, size, response, keepsCount, blocks);
        }

        @Override
        public CgVfxLane[] instanceLanes() {
            return NEAR_LANES;
        }

        @Override
        public void writeInstance(CgVfxInstanceView instance, CgVfxWords out) {
            out.vec4(near(instance, reach), 0f, 0f, 0f);
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
        public CgVfxDataInterface[] dataInterfaces() {
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
        public CgVfxLane[] instanceLanes() {
            return LANES;
        }

        @Override
        public void writeInstance(CgVfxInstanceView instance, CgVfxWords out) {
            CgVfxShapeRows.writeCentre(shape, x, y, z, instance, out);
        }
    }
}
