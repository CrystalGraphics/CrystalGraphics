package com.crystalgraphics.vfx.particle;

import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.vfx.particle.gpu.CgVfxEvent;
import com.crystalgraphics.vfx.particle.gpu.CgVfxGpuModule;
import com.crystalgraphics.vfx.particle.gpu.CgVfxInstanceView;
import com.crystalgraphics.vfx.particle.gpu.CgVfxLane;
import com.crystalgraphics.vfx.particle.gpu.CgVfxWords;
import com.crystalgraphics.vfx.particle.gpu.CgVfxWorldInput;

import java.util.Objects;

/**
 * One step of an emitter's update, run over all its particles every tick, in the order the emitter lists them: the
 * pieces a particle's physics is built from, as Niagara's update modules are. Each is data, a kind and its numbers, so
 * the same stack drives the CPU path ({@link #apply}) and the GPU simulation ({@link CgVfxGpuModule}: each kind's
 * {@code fx_<kind>.glsl} in {@code shaders/lib/vfx/sim/}). Force modules add to the accelerations and drags the solver
 * integrates; {@link Ground}, {@link Collide}, {@link Kill}, {@link Orbit} and {@link LimitSpeed} run after the solver,
 * on the moved particles. Units are blocks and seconds.
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
 * Volume hand = Volume.sphere(6f).at(0f, 1.5f, 0f);
 * List.of(new CgVfxModule.Attract(hand, 30f),
 *         new CgVfxModule.Orbit(0.6f, 0f, 1f, 0f, 0f, 1.5f, 0f),
 *         new CgVfxModule.Kill(Volume.sphere(0.3f).at(0f, 1.5f, 0f), true));
 * }</pre>
 *
 * <ul>
 *   <li>Order matters only among forces that read velocity ({@link Wind}); the solver sums all forces before it moves.</li>
 *   <li>{@link Ground} does nothing unless the instance has a ground height
 *       ({@link CgVfxEmitterInstance#ground(float)}).</li>
 *   <li>A new kind is a record here, its {@code apply}, and its GPU side: {@code fx_<kind>.glsl} and the
 *       {@link CgVfxGpuModule} methods. Both paths must give the same step (harness {@code vfx-sim-equivalence}).</li>
 * </ul>
 */
public sealed interface CgVfxModule extends CgVfxGpuModule {

    /** Runs over every particle of {@code emitter} for a tick of {@code dt} seconds. */
    void apply(CgVfxEmitterInstance emitter, float dt);

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

    /** A constant pull down, in blocks a second squared: 9.8 is Earth's. */
    record Gravity(float strength) implements CgVfxModule {
        @Override
        public void apply(CgVfxEmitterInstance emitter, float dt) {
            CgVfxParticleSet p = emitter.particles();
            for (int i = 0; i < p.count(); i++) p.ay[i] -= strength;
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
        public void apply(CgVfxEmitterInstance emitter, float dt) {
            CgVfxParticleSet p = emitter.particles();
            for (int i = 0; i < p.count(); i++) {
                p.drag[i] += linear;
                p.dragQuad[i] += quadratic;
            }
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
        public void apply(CgVfxEmitterInstance emitter, float dt) {
            CgVfxAir air = emitter.air();
            float wx = air.windX(), wy = air.windY(), wz = air.windZ();
            float speed = (float) Math.sqrt(wx * wx + wy * wy + wz * wz);
            if (speed < 1.0e-5f) return;
            float dx = wx / speed, dy = wy / speed, dz = wz / speed;
            CgVfxParticleSet p = emitter.particles();
            for (int i = 0; i < p.count(); i++) {
                float along = p.vx[i] * dx + p.vy[i] * dy + p.vz[i] * dz;
                if (along >= speed) continue;
                float push = (speed - along) * resistance;
                p.ax[i] += dx * push;
                p.ay[i] += dy * push;
                p.az[i] += dz * push;
            }
        }

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
     * Swirling air: a curl-noise force ({@link CgVfxCurlNoise}) of up to about {@code strength} blocks a second squared,
     * with eddies about 1 / {@code frequency} blocks across, the field drifting at {@code evolve} a second. The same field
     * for every emitter at a point, so neighbouring particles swirl together.
     */
    record Turbulence(float strength, float frequency, float evolve) implements CgVfxModule {
        /** Each octave's lattice cell and fraction of where the instance's origin samples (fx_curl.glsl). */
        private static final CgVfxLane[] LANES = {CgVfxLane.IVEC4, CgVfxLane.VEC4, CgVfxLane.IVEC4, CgVfxLane.VEC4};
        /** CgVfxCurlNoise's second octave: x * 2.03 + offset. */
        private static final double OCTAVE = 2.03, OFFSET_X = 5.2, OFFSET_Y = 1.3, OFFSET_Z = 7.9;

        @Override
        public void apply(CgVfxEmitterInstance emitter, float dt) {
            CgVfxParticleSet p = emitter.particles();
            float[] v = emitter.scratch();
            float drift = emitter.time() * evolve;
            double ox = emitter.originX(), oy = emitter.originY(), oz = emitter.originZ();
            for (int i = 0; i < p.count(); i++) {
                if (p.resting[i] != 0f) continue;
                CgVfxCurlNoise.sample((float) ((ox + p.x[i]) * frequency) + drift, (float) ((oy + p.y[i]) * frequency),
                        (float) ((oz + p.z[i]) * frequency) - drift * 0.7f, v);
                p.ax[i] += v[0] * strength * 0.5f;
                p.ay[i] += v[1] * strength * 0.5f;
                p.az[i] += v[2] * strength * 0.5f;
            }
        }

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
        public void apply(CgVfxEmitterInstance emitter, float dt) {
            CgVfxParticleSet p = emitter.particles();
            float keep = (float) Math.exp(-dt / Math.max(cooling, 1.0e-3f));
            for (int i = 0; i < p.count(); i++) {
                p.ay[i] += lift * p.heat[i];
                p.heat[i] *= keep;
            }
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
        /** Its fade at the step's start, then the source. */
        private static final CgVfxLane[] LANES = {CgVfxLane.VEC4};

        @Override
        public void apply(CgVfxEmitterInstance emitter, float dt) {
            float fading = 1f - smooth(0f, duration, emitter.time());
            if (fading <= 0f) return;
            CgVfxParticleSet p = emitter.particles();
            float sx = emitter.sourceX(), sy = emitter.sourceY(), sz = emitter.sourceZ();
            float inv = 1f / (radius * radius);
            for (int i = 0; i < p.count(); i++) {
                float dx = p.x[i] - sx, dz = p.z[i] - sz, up = p.y[i] - sy;
                float column = (float) Math.exp(-(dx * dx + dz * dz) * inv) * (1f - smooth(0f, height, up));
                p.ay[i] += strength * column * fading;
            }
        }

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
     * The ground: the host world's surface under each particle (the instance's {@link CgVfxGround}), or its fixed
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
        public void apply(CgVfxEmitterInstance emitter, float dt) {
            if (!emitter.hasGround()) return;
            CgVfxParticleSet p = emitter.particles();
            for (int i = 0; i < p.count(); i++) {
                if (p.resting[i] != 0f) continue;
                float floor = emitter.floorUnder(i);
                if (Float.isNaN(floor)) continue;
                floor += radius * p.size[i];
                if (p.y[i] > floor) continue;
                p.y[i] = floor;
                if (p.vy[i] < 0f) p.vy[i] = -p.vy[i] * restitution;
                p.vx[i] *= 1f - friction;
                p.vz[i] *= 1f - friction;
                float speed = (float) Math.sqrt(p.vx[i] * p.vx[i] + p.vy[i] * p.vy[i] + p.vz[i] * p.vz[i]);
                if (speed < rest) {
                    p.vx[i] = p.vy[i] = p.vz[i] = 0f;
                    p.spinRate[i] = 0f;
                    p.resting[i] = 1f;
                }
            }
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

    /** Spinning slows down: the spin rate decays by {@code drag} a second. */
    record Spin(float drag) implements CgVfxModule {
        @Override
        public void apply(CgVfxEmitterInstance emitter, float dt) {
            CgVfxParticleSet p = emitter.particles();
            float keep = (float) Math.exp(-drag * dt);
            for (int i = 0; i < p.count(); i++) p.spinRate[i] *= keep;
        }

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
     * Where a module acts, placed from the emitter's source: a sphere, a box by its half extents, or a plane, solid
     * behind its normal. Godot's attractor and collider shapes, bevy_hanabi's kill volumes.
     *
     * <pre>{@code
     * Volume.sphere(3f).at(0f, 2f, 0f)            // radius 3, two blocks over the source
     * Volume.box(4f, 1f, 4f)                       // 8 x 2 x 8 round the source
     * Volume.plane(0f, 1f, 0f).at(0f, -1f, 0f)    // solid below the block under the source
     * }</pre>
     */
    record Volume(Form form, float x, float y, float z, float ex, float ey, float ez) {
        /** Its shape; a plane's extents are its unit normal. */
        public enum Form { SPHERE, BOX, PLANE }

        public Volume {
            Objects.requireNonNull(form, "form");
            if (form == Form.SPHERE && !(ex > 0f)) throw new IllegalArgumentException("a sphere of radius " + ex);
            if (form == Form.BOX && !(ex > 0f && ey > 0f && ez > 0f)) {
                throw new IllegalArgumentException("a box of half extents " + ex + ", " + ey + ", " + ez);
            }
        }

        public static Volume sphere(float radius) {
            return new Volume(Form.SPHERE, 0f, 0f, 0f, radius, radius, radius);
        }

        public static Volume box(float halfX, float halfY, float halfZ) {
            return new Volume(Form.BOX, 0f, 0f, 0f, halfX, halfY, halfZ);
        }

        /** Through the source, solid on the side away from {@code (nx, ny, nz)}, which need not be unit. */
        public static Volume plane(float nx, float ny, float nz) {
            float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (!(length > 0f)) throw new IllegalArgumentException("a plane needs a normal");
            return new Volume(Form.PLANE, 0f, 0f, 0f, nx / length, ny / length, nz / length);
        }

        /** The same volume centred {@code (x, y, z)} from the source. */
        public Volume at(float x, float y, float z) {
            return new Volume(form, x, y, z, ex, ey, ez);
        }

        void writeShape(CgVfxWords out) {
            out.vec4(ex, ey, ez, form.ordinal());
        }

        void writeCentre(CgVfxInstanceView instance, CgVfxWords out) {
            out.vec4(instance.sourceX() + x, instance.sourceY() + y, instance.sourceZ() + z, 0f);
        }
    }

    /**
     * A pull toward a sphere or box (Godot's attractors): {@code strength} blocks a second squared at the centre, falling
     * to nothing at the edge as {@code (1 - d)^attenuation}, d being 0 at the centre and 1 at the edge. A negative
     * strength pushes out. Directionality turns the pull, 0 to 1, into a push along {@code axis}.
     *
     * <pre>{@code
     * new CgVfxModule.Attract(Volume.sphere(6f).at(0f, 1.5f, 0f), 30f)                  // energy gathering in a hand
     * new CgVfxModule.Attract(Volume.box(3f, 3f, 3f), 12f).attenuation(0f)               // even over the box
     * new CgVfxModule.Attract(Volume.sphere(4f), 10f).directed(1f, 0f, 1f, 0f)           // a lift inside the sphere
     * }</pre>
     *
     * <ul>
     *   <li>A plane attracts nothing, and is refused.</li>
     *   <li>Pair it with {@link Orbit} round the same centre for a spiral in, Godot's orbit velocity.</li>
     * </ul>
     */
    record Attract(Volume volume, float strength, float attenuation, float directionality, float axisX, float axisY,
                   float axisZ) implements CgVfxModule {
        private static final CgVfxLane[] LANES = {CgVfxLane.VEC4};

        public Attract {
            if (volume.form() == Volume.Form.PLANE) throw new IllegalArgumentException("a plane attracts nothing");
            if (!(attenuation >= 0f)) throw new IllegalArgumentException("attenuation " + attenuation);
            float length = (float) Math.sqrt(axisX * axisX + axisY * axisY + axisZ * axisZ);
            if (length > 0f) {
                axisX /= length;
                axisY /= length;
                axisZ /= length;
            }
        }

        public Attract(Volume volume, float strength) {
            this(volume, strength, 1f, 0f, 0f, 1f, 0f);
        }

        /** How the pull falls off toward the edge: 0 even, 1 linear (the default), 2 and up held to the centre. */
        public Attract attenuation(float attenuation) {
            return new Attract(volume, strength, attenuation, directionality, axisX, axisY, axisZ);
        }

        /** A push along {@code (x, y, z)} in place of {@code directionality} of the pull. */
        public Attract directed(float directionality, float x, float y, float z) {
            return new Attract(volume, strength, attenuation, directionality, x, y, z);
        }

        @Override
        public void apply(CgVfxEmitterInstance emitter, float dt) {
            CgVfxParticleSet p = emitter.particles();
            float[] dir = emitter.scratch();
            float cx = emitter.sourceX() + volume.x(), cy = emitter.sourceY() + volume.y(), cz = emitter.sourceZ() + volume.z();
            float keep = 1f - directionality;
            for (int i = 0; i < p.count(); i++) {
                float rx = p.x[i] - cx, ry = p.y[i] - cy, rz = p.z[i] - cz;
                float d = CgVfxContacts.reach(volume, rx, ry, rz);
                if (d > 1f) continue;
                float amount = (float) Math.pow(Math.max(0f, 1f - d), attenuation);
                CgVfxContacts.safeNormalize(rx, ry, rz, dir);
                CgVfxContacts.safeNormalize(dir[0] * keep + -axisX * directionality, dir[1] * keep + -axisY * directionality,
                        dir[2] * keep + -axisZ * directionality, dir);
                float pull = amount * strength;
                p.ax[i] -= dir[0] * pull;
                p.ay[i] -= dir[1] * pull;
                p.az[i] -= dir[2] * pull;
            }
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
            volume.writeShape(out);
            out.vec4(strength, attenuation, directionality, 0f).vec4(axisX, axisY, axisZ, 0f);
        }

        @Override
        public CgVfxLane[] instanceLanes() {
            return LANES;
        }

        @Override
        public void writeInstance(CgVfxInstanceView instance, CgVfxWords out) {
            volume.writeCentre(instance, out);
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
        public void apply(CgVfxEmitterInstance emitter, float dt) {
            CgVfxParticleSet p = emitter.particles();
            float a = turns * 6.2831855f * dt;
            float c = (float) Math.cos(a), s = (float) Math.sin(a), turn = 1f - c;
            float cx = emitter.sourceX() + x, cy = emitter.sourceY() + y, cz = emitter.sourceZ() + z;
            for (int i = 0; i < p.count(); i++) {
                if (p.resting[i] != 0f) continue;
                float vx = p.x[i] - cx, vy = p.y[i] - cy, vz = p.z[i] - cz;
                float along = (axisX * vx + axisY * vy + axisZ * vz) * turn;
                p.x[i] = cx + vx * c + (axisY * vz - axisZ * vy) * s + axisX * along;
                p.y[i] = cy + vy * c + (axisZ * vx - axisX * vz) * s + axisY * along;
                p.z[i] = cz + vz * c + (axisX * vy - axisY * vx) * s + axisZ * along;
            }
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
        public void apply(CgVfxEmitterInstance emitter, float dt) {
            CgVfxParticleSet p = emitter.particles();
            float[] v = emitter.scratch();
            float cx = emitter.sourceX() + x, cy = emitter.sourceY() + y, cz = emitter.sourceZ() + z;
            for (int i = 0; i < p.count(); i++) {
                CgVfxContacts.safeNormalize(p.x[i] - cx, p.y[i] - cy, p.z[i] - cz, v);
                float ox = v[0], oy = v[1], oz = v[2];
                CgVfxContacts.safeNormalize(axisY * oz - axisZ * oy, axisZ * ox - axisX * oz, axisX * oy - axisY * ox, v);
                p.ax[i] += v[0] * tangential + ox * radial;
                p.ay[i] += v[1] * tangential + oy * radial;
                p.az[i] += v[2] * tangential + oz * radial;
            }
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

    /** A constant push, blocks a second squared: Niagara's acceleration force, {@link Gravity} in any direction. */
    record Force(float x, float y, float z) implements CgVfxModule {
        @Override
        public void apply(CgVfxEmitterInstance emitter, float dt) {
            CgVfxParticleSet p = emitter.particles();
            for (int i = 0; i < p.count(); i++) {
                p.ax[i] += x;
                p.ay[i] += y;
                p.az[i] += z;
            }
        }

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
     * box nothing pushes, unless the field tiles.
     *
     * <pre>{@code
     * new CgVfxModule.VectorField(swirl, Volume.box(4f, 4f, 4f).at(0f, 4f, 0f), 3f, 1f)
     * }</pre>
     */
    record VectorField(CgVfxField field, Volume box, float strength, float attenuation) implements CgVfxModule {
        private static final CgVfxLane[] LANES = {CgVfxLane.VEC4};

        public VectorField {
            Objects.requireNonNull(field, "field");
            if (box.form() != Volume.Form.BOX) throw new IllegalArgumentException("a vector field fills a box, not a " + box.form());
            if (!(attenuation >= 0f)) throw new IllegalArgumentException("attenuation " + attenuation);
        }

        @Override
        public void apply(CgVfxEmitterInstance emitter, float dt) {
            CgVfxParticleSet p = emitter.particles();
            float[] v = emitter.scratch();
            float cx = emitter.sourceX() + box.x(), cy = emitter.sourceY() + box.y(), cz = emitter.sourceZ() + box.z();
            boolean tiles = field.tiles();
            for (int i = 0; i < p.count(); i++) {
                float u = ((p.x[i] - cx) / box.ex() + 1f) * 0.5f, w = ((p.y[i] - cy) / box.ey() + 1f) * 0.5f;
                float t = ((p.z[i] - cz) / box.ez() + 1f) * 0.5f;
                if (!tiles && (u < 0f || w < 0f || t < 0f || u > 1f || w > 1f || t > 1f)) continue;
                field.sample(u, w, t, v);
                float length = (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
                if (length <= 0f) continue;
                float push = (float) Math.pow(length, attenuation) * strength;
                p.ax[i] += v[0] / length * push;
                p.ay[i] += v[1] / length * push;
                p.az[i] += v[2] / length * push;
            }
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
            out.vec4(box.ex(), box.ey(), box.ez(), field.tiles() ? 1f : 0f).vec4(strength, attenuation, 0f, 0f);
        }

        @Override
        public CgVfxLane[] instanceLanes() {
            return LANES;
        }

        @Override
        public void writeInstance(CgVfxInstanceView instance, CgVfxWords out) {
            box.writeCentre(instance, out);
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
        public void apply(CgVfxEmitterInstance emitter, float dt) {
            CgVfxParticleSet p = emitter.particles();
            float lose = rate * dt;
            for (int i = 0; i < p.count(); i++) {
                float v = (float) Math.sqrt(p.vx[i] * p.vx[i] + p.vy[i] * p.vy[i] + p.vz[i] * p.vz[i]);
                if (v <= 0f) continue;
                float left = v - lose;
                if (left < 0f) {
                    p.vx[i] = p.vy[i] = p.vz[i] = 0f;
                } else {
                    p.vx[i] = p.vx[i] / v * left;
                    p.vy[i] = p.vy[i] / v * left;
                    p.vz[i] = p.vz[i] / v * left;
                }
            }
        }

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
        public void apply(CgVfxEmitterInstance emitter, float dt) {
            CgVfxParticleSet p = emitter.particles();
            for (int i = 0; i < p.count(); i++) {
                float v = (float) Math.sqrt(p.vx[i] * p.vx[i] + p.vy[i] * p.vy[i] + p.vz[i] * p.vz[i]);
                if (v <= most) continue;
                float scale = most / v;
                p.vx[i] *= scale;
                p.vy[i] *= scale;
                p.vz[i] *= scale;
                p.x[i] = p.px[i] + p.vx[i] * dt;
                p.y[i] = p.py[i] + p.vy[i] * dt;
                p.z[i] = p.pz[i] + p.vz[i] * dt;
            }
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
     * shell {@code shell} blocks either side of it, so they settle rather than overshoot.
     *
     * <pre>{@code
     * new CgVfxModule.Conform(Volume.sphere(2f).at(0f, 2f, 0f), 3f, 40f, 6f)   // a shield taking shape
     * }</pre>
     */
    record Conform(Volume sphere, float influence, float attraction, float speed, float shell, float sticky)
            implements CgVfxModule {
        private static final CgVfxLane[] LANES = {CgVfxLane.VEC4};

        public Conform {
            if (sphere.form() != Volume.Form.SPHERE) throw new IllegalArgumentException("conforms to a sphere, not a " + sphere.form());
            if (!(shell > 0f)) throw new IllegalArgumentException("a shell of " + shell);
        }

        /** bevy_hanabi's defaults: a shell of 0.1 blocks, twice as sticky in it. */
        public Conform(Volume sphere, float influence, float attraction, float speed) {
            this(sphere, influence, attraction, speed, 0.1f, 2f);
        }

        @Override
        public void apply(CgVfxEmitterInstance emitter, float dt) {
            CgVfxParticleSet p = emitter.particles();
            float cx = emitter.sourceX() + sphere.x(), cy = emitter.sourceY() + sphere.y(), cz = emitter.sourceZ() + sphere.z();
            for (int i = 0; i < p.count(); i++) {
                if (p.resting[i] != 0f) continue;
                float rx = cx - p.x[i], ry = cy - p.y[i], rz = cz - p.z[i];
                float distance = (float) Math.sqrt(rx * rx + ry * ry + rz * rz);
                if (distance <= 0f) continue;
                float dx = rx / distance, dy = ry / distance, dz = rz / distance;
                float surface = distance - sphere.ex();
                if (surface > influence) continue;
                float radial = p.vx[i] * dx + p.vy[i] * dy + p.vz[i] * dz;
                float t = Math.max(0f, Math.min(1f, Math.abs(surface) / shell));
                float within = t * t * (3f - 2f * t);
                float delta = Math.signum(surface) * within * speed - radial;
                float accel = attraction * sticky * (1f - within) + attraction * within;
                float change = Math.signum(delta) * Math.min(Math.abs(delta), dt * accel);
                p.vx[i] += dx * change;
                p.vy[i] += dy * change;
                p.vz[i] += dz * change;
            }
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
            out.vec4(sphere.ex(), influence, attraction, speed).vec4(shell, sticky, 0f, 0f);
        }

        @Override
        public CgVfxLane[] instanceLanes() {
            return LANES;
        }

        @Override
        public void writeInstance(CgVfxInstanceView instance, CgVfxWords out) {
            sphere.writeCentre(instance, out);
        }
    }

    /**
     * Particles meet a sphere, box or plane (Godot's colliders and rigid response): each is pushed out of it, loses its
     * speed into the surface and {@code friction} of its sliding, and bounces at {@code bounce} once it strikes fast
     * enough, Godot's slide-to-bounce threshold of 2 / (bounce + 1) blocks a second; slower than {@code rest} after, it
     * rests. Each bounce is a hit: it counts toward and fires {@link CgVfxEvent#onCollision()}.
     * Runs after the solver.
     *
     * <pre>{@code
     * new CgVfxModule.Collide(Volume.sphere(1.5f).at(0f, 1.5f, 0f), 0.6f, 0.1f, 0.2f)    // a boulder sparks bounce off
     * new CgVfxModule.Collide(Volume.box(3f, 3f, 3f), 0.8f, 0f, 0f).container()          // kept inside a box
     * new CgVfxModule.Collide(Volume.plane(0f, 1f, 0f), 0f, 0f, 0f).killing()            // die on the plane
     * }</pre>
     *
     * <ul>
     *   <li>A particle collides as a ball of {@code radius} times its size, its quad's half-width: all of it by default,
     *       so a piece rests on a surface rather than sunk into it. Godot's base size is a diameter; this is not.</li>
     *   <li>A {@link #killing()} collider is Godot's hide on contact: a hit, then death at the end of the step.</li>
     * </ul>
     */
    record Collide(Volume volume, float bounce, float friction, float rest, float radius, boolean keepsInside, boolean kill)
            implements CgVfxModule {
        private static final CgVfxLane[] LANES = {CgVfxLane.VEC4};

        public Collide {
            Objects.requireNonNull(volume, "volume");
        }

        public Collide(Volume volume, float bounce, float friction, float rest) {
            this(volume, bounce, friction, rest, 1f, false, false);
        }

        /** Collides as a ball of {@code radius} times a particle's size. */
        public Collide radius(float radius) {
            return new Collide(volume, bounce, friction, rest, radius, keepsInside, kill);
        }

        /** Keeps particles inside the volume rather than out of it. */
        public Collide container() {
            return new Collide(volume, bounce, friction, rest, radius, true, kill);
        }

        /** Kills a particle on contact rather than bouncing it. */
        public Collide killing() {
            return new Collide(volume, bounce, friction, rest, radius, keepsInside, true);
        }

        @Override
        public void apply(CgVfxEmitterInstance emitter, float dt) {
            CgVfxParticleSet p = emitter.particles();
            float[] n = emitter.scratch();
            float cx = emitter.sourceX() + volume.x(), cy = emitter.sourceY() + volume.y(), cz = emitter.sourceZ() + volume.z();
            for (int i = 0; i < p.count(); i++) {
                if (p.resting[i] != 0f) continue;
                float depth = CgVfxContacts.contact(volume, keepsInside, p.x[i] - cx, p.y[i] - cy, p.z[i] - cz,
                        radius * p.size[i], n);
                if (depth == depth) CgVfxContacts.respond(p, i, n[0], n[1], n[2], depth, bounce, friction, rest, kill);
            }
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
            volume.writeShape(out);
            out.vec4(bounce, friction, rest, kill ? 1f : 0f).vec4(radius, keepsInside ? 1f : 0f, 0f, 0f);
        }

        @Override
        public CgVfxLane[] instanceLanes() {
            return LANES;
        }

        @Override
        public void writeInstance(CgVfxInstanceView instance, CgVfxWords out) {
            volume.writeCentre(instance, out);
        }
    }

    /**
     * The host world's blocks, with {@link Collide}'s response: on the GPU every solid half-block octant of the voxel
     * window, a particle inside one leaving by the face the distance field points through. The CPU path has only the
     * instance's ground ({@link CgVfxEmitterInstance#ground(float)}), as {@link Ground} has. Runs after the solver.
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
        public void apply(CgVfxEmitterInstance emitter, float dt) {
            if (!emitter.hasGround()) return;
            CgVfxParticleSet p = emitter.particles();
            for (int i = 0; i < p.count(); i++) {
                if (p.resting[i] != 0f) continue;
                float floor = emitter.floorUnder(i);
                if (Float.isNaN(floor) || p.y[i] >= floor) continue;
                CgVfxContacts.respond(p, i, 0f, 1f, 0f, floor - p.y[i] + CgVfxContacts.EPSILON, bounce, friction, rest, kill);
            }
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
     * surface's normal there. Blind off screen and behind the first surface; the CPU path has no depth and skips it.
     * Runs after the solver.
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
        public void apply(CgVfxEmitterInstance emitter, float dt) {
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
     * Kills the particles inside a volume, or with {@code inside} false those outside it (bevy_hanabi's kill sphere and
     * kill box; a plane kills behind it). They die, and their death events fire, at the end of the step. Runs after the
     * solver.
     *
     * <pre>{@code
     * new CgVfxModule.Kill(Volume.sphere(0.4f).at(0f, 1.5f, 0f), true)   // absorbed at the centre
     * new CgVfxModule.Kill(Volume.box(20f, 20f, 20f), false)              // never past the play area
     * }</pre>
     */
    record Kill(Volume volume, boolean inside) implements CgVfxModule {
        private static final CgVfxLane[] LANES = {CgVfxLane.VEC4};

        public Kill {
            Objects.requireNonNull(volume, "volume");
        }

        @Override
        public void apply(CgVfxEmitterInstance emitter, float dt) {
            CgVfxParticleSet p = emitter.particles();
            float cx = emitter.sourceX() + volume.x(), cy = emitter.sourceY() + volume.y(), cz = emitter.sourceZ() + volume.z();
            for (int i = 0; i < p.count(); i++) {
                if (CgVfxContacts.inside(volume, p.x[i] - cx, p.y[i] - cy, p.z[i] - cz) == inside) p.life[i] = p.age[i];
            }
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
            volume.writeShape(out);
            out.vec4(inside ? 1f : 0f, 0f, 0f, 0f);
        }

        @Override
        public CgVfxLane[] instanceLanes() {
            return LANES;
        }

        @Override
        public void writeInstance(CgVfxInstanceView instance, CgVfxWords out) {
            volume.writeCentre(instance, out);
        }
    }
}
