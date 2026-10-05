package com.crystalgraphics.vfx.particle;

import com.crystalgraphics.vfx.particle.gpu.CgVfxGpuModule;
import com.crystalgraphics.vfx.particle.gpu.CgVfxInstanceView;
import com.crystalgraphics.vfx.particle.gpu.CgVfxLane;
import com.crystalgraphics.vfx.particle.gpu.CgVfxWords;
import com.crystalgraphics.vfx.particle.gpu.CgVfxWorldInput;

/**
 * One step of an emitter's update, run over all its particles every tick, in the order the emitter lists them: the
 * pieces a particle's physics is built from, as Niagara's update modules are. Each is data, a kind and its numbers, so
 * the same stack drives the CPU path ({@link #apply}) and the GPU simulation ({@link CgVfxGpuModule}: each kind's
 * {@code fx_<kind>.glsl} in {@code shaders/lib/vfx/sim/}). Force modules add to the accelerations and drags the solver
 * integrates; {@link Ground} runs after the solver, on the moved particles. Units are blocks and seconds.
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

    /** A constant pull down, in blocks a second squared: 9.8 is Earth's. */
    record Gravity(float strength) implements CgVfxModule {
        @Override
        public void apply(CgVfxEmitterInstance emitter, float dt) {
            CgVfxParticleSet p = emitter.particles();
            for (int i = 0; i < p.count(); i++) p.ay[i] -= strength;
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
     * the solver.
     */
    record Ground(float restitution, float friction, float rest) implements CgVfxModule {
        private static final CgVfxWorldInput[] WORLD = {CgVfxWorldInput.FLOOR_Y};

        @Override
        public void apply(CgVfxEmitterInstance emitter, float dt) {
            if (!emitter.hasGround()) return;
            CgVfxParticleSet p = emitter.particles();
            for (int i = 0; i < p.count(); i++) {
                if (p.resting[i] != 0f) continue;
                float floor = emitter.floorUnder(i);
                if (Float.isNaN(floor) || p.y[i] > floor) continue;
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
            out.vec4(restitution, friction, rest, 0f);
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
}
