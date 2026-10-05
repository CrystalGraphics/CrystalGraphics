package com.crystalgraphics.vfx.particle;

import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.vfx.CgVfxTrace;
import com.crystalgraphics.vfx.particle.gpu.CgVfxInstanceView;

import java.util.List;
import java.util.Locale;

/**
 * One running {@link CgVfxEmitter}: its particles, its clock, where it spawns from, and the solver that moves them. An
 * effect makes one per emitter it plays, starts it where the burst happens, and ticks it at the system's fixed step.
 * The same seed and the same ticks always give the same particles.
 *
 * <pre>{@code
 * CgVfxEmitterInstance embers = new CgVfxEmitterInstance(EMBERS, seed);
 * embers.start(impactX, impactY, impactZ);        // relative to the effect's origin
 * embers.ground(groundY - originY);               // optional: where the floor is, for CgVfxModule.Ground
 *
 * // every tick
 * embers.tick(CgVfxSystem.TICK, system.air(), originX, originY, originZ);
 * if (embers.finished()) ...                       // spawned everything and every particle has died
 * }</pre>
 *
 * <ul>
 *   <li>Nothing spawns before {@link #start}; a second {@code start} restarts it.</li>
 *   <li>Positions are relative to the effect's origin; the origin passed to {@link #tick} is only where turbulence is
 *       sampled, so effects at different places see different eddies.</li>
 *   <li>{@link #share} thins what spawns by particle index, so a sparser burst keeps the same particles where it keeps
 *       any: the effect's shape, only fewer. A playing effect's share is its system's, set every tick.</li>
 * </ul>
 */
public final class CgVfxEmitterInstance implements CgVfxInstanceView {

    private static final int SPAWN_NS = CgTrace.name("vfx.sim.spawn-ns"), SOLVE_NS = CgTrace.name("vfx.sim.solve-ns"),
            AGE_NS = CgTrace.name("vfx.sim.age-ns"), SPAWNED = CgTrace.name("vfx.particles.spawned"),
            TICKED = CgTrace.name("vfx.particles.ticked"), DROPPED = CgTrace.name("vfx.particles.dropped");
    /** Each module kind's time counter: {@code vfx.module.<kind>-ns}. */
    private static final ClassValue<Integer> MODULE_NS = new ClassValue<>() {
        @Override
        protected Integer computeValue(Class<?> kind) {
            return CgTrace.name("vfx.module." + kind.getSimpleName().toLowerCase(Locale.ROOT) + "-ns");
        }
    };

    private final CgVfxEmitter emitter;
    private final CgVfxParticleSet particles;
    private final int seed;
    private final float[] scratch = new float[3];
    private CgVfxAir air;
    private float time = -1f, sourceX, sourceY, sourceZ, groundY = Float.NaN;
    private CgVfxGround field;
    private double originX, originY, originZ;
    private int spawned, burstsDone;
    private float rateOwed, share = 1f;
    /** Stepped by {@link #schedule}: its particles live in a GPU pool, not in {@link #particles}. */
    private boolean scheduled;
    private int stepFirst, stepCandidates;
    /** When its latest-dying particle dies, and the last step's length. */
    private float lastDeath = -1f, stepDt;

    public CgVfxEmitterInstance(CgVfxEmitter emitter, float seed) {
        this.emitter = emitter;
        this.particles = new CgVfxParticleSet(emitter.capacity);
        this.seed = Float.floatToIntBits(seed);
    }

    /** Starts spawning at {@code (x, y, z)}, relative to the effect's origin, from this tick on. */
    public void start(float x, float y, float z) {
        sourceX = x;
        sourceY = y;
        sourceZ = z;
        time = 0f;
        spawned = 0;
        burstsDone = 0;
        rateOwed = 0f;
        lastDeath = -1f;
        particles.clear();
    }

    /**
     * The height of the ground, relative to the effect's origin, for {@link CgVfxModule.Ground}; NaN for none. With a
     * {@link CgVfxGround} as well, only where the host has no level.
     */
    public CgVfxEmitterInstance ground(float y) {
        groundY = y;
        return this;
    }

    /** The host world's surfaces around the burst, shared by its emitters; null for the fixed height alone. */
    public CgVfxEmitterInstance ground(CgVfxGround field) {
        this.field = field;
        return this;
    }

    /** The share of its particles to spawn, 0 to 1: the player's density, applied by the system. */
    public CgVfxEmitterInstance share(float share) {
        this.share = share;
        return this;
    }

    /** Whether {@link CgVfxModule.Ground} has anything to land particles on. */
    boolean hasGround() {
        return field != null || !Float.isNaN(groundY);
    }

    /** The floor under particle {@code i}, relative to the effect's origin, or NaN for none. */
    float floorUnder(int i) {
        CgVfxParticleSet p = particles;
        return field == null ? groundY : field.floor(p.x[i], p.y[i], p.py[i], p.z[i], groundY);
    }

    /** Spawns what is due, runs the module stack, solves and ages: one step of {@code dt} seconds. */
    public void tick(float dt, CgVfxAir air, double originX, double originY, double originZ) {
        if (time < 0f) return;
        this.air = air;
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        CgVfxParticleSet p = particles;
        long t = CgVfxTrace.start();
        int before = p.count();
        spawn(dt);
        if (t != 0L) CgVfxTrace.count(SPAWNED, p.count() - before);
        t = CgVfxTrace.lap(SPAWN_NS, t);
        List<CgVfxModule> modules = emitter.modules;
        for (int m = 0; m < modules.size(); m++) {
            CgVfxModule module = modules.get(m);
            if (module.afterSolve()) continue;
            module.apply(this, dt);
            if (t != 0L) t = CgVfxTrace.lap(MODULE_NS.get(module.getClass()), t);
        }
        solve(dt);
        t = CgVfxTrace.lap(SOLVE_NS, t);
        for (int m = 0; m < modules.size(); m++) {
            CgVfxModule module = modules.get(m);
            if (!module.afterSolve()) continue;
            module.apply(this, dt);
            if (t != 0L) t = CgVfxTrace.lap(MODULE_NS.get(module.getClass()), t);
        }
        if (t != 0L) CgVfxTrace.count(TICKED, p.count());
        for (int i = p.count() - 1; i >= 0; i--) {
            p.age[i] += dt;
            if (p.age[i] >= p.life[i]) p.remove(i);
        }
        CgVfxTrace.lap(AGE_NS, t);
        time += dt;
    }

    /**
     * One step of the GPU path (plan vfx-gpu §13.7): schedules what spawns this step exactly as {@link #tick} would
     * spawn it, and moves no particle; the pool's Step kernel does. Read the spawns after it, for the pool's queue.
     *
     * <pre>{@code
     * instance.schedule(dt, originX, originY, originZ);
     * pool.instance(slot, instance.seedBits(), instance.share(), instance.groundY(), instance);
     * pool.spawn(slot, instance.stepFirstSpawn(), instance.stepCandidates());
     * if (instance.finished()) pool.close(slot);
     * }</pre>
     *
     * <ul>
     *   <li>An instance is stepped one way from its {@link #start}: by {@code tick} or by this, never both.</li>
     *   <li>Its {@link #particles} stay empty; {@link #finished} comes from each spawn's life, drawn by the same hash
     *       the kernel draws it by, so it needs nothing back from the GPU while particles die only of age.</li>
     * </ul>
     */
    public void schedule(float dt, double originX, double originY, double originZ) {
        if (time < 0f) return;
        scheduled = true;
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        stepDt = dt;
        stepFirst = spawned;
        CgVfxEmitter e = emitter;
        while (burstsDone < e.burstTimes.length && e.burstTimes[burstsDone] <= time) {
            for (int n = 0; n < e.burstCounts[burstsDone]; n++) scheduleOne(dt);
            burstsDone++;
        }
        if (e.rate > 0f && time >= e.rateFrom && time < e.rateUntil) {
            rateOwed += e.rate * dt;
            while (rateOwed >= 1f) {
                scheduleOne(dt);
                rateOwed -= 1f;
            }
        }
        stepCandidates = spawned - stepFirst;
        time += dt;
    }

    /** A spawn candidate: thinned by the share as {@code spawnOne} is, its death kept if it spawns. */
    private void scheduleOne(float dt) {
        int k = spawned++;
        if (share < 1f && rand(k, 10) >= share) return;
        float life = emitter.lifeMin + (emitter.lifeMax - emitter.lifeMin) * rand(k, 4);
        // It ages a step at a time from this one, and goes at the end of the step its age reaches its life.
        lastDeath = Math.max(lastDeath, time + dt * (float) Math.ceil(life / dt));
    }

    /** The first spawn index {@link #schedule}'s last step queued. */
    public int stepFirstSpawn() {
        return stepFirst;
    }

    /** How many spawn candidates {@link #schedule}'s last step queued, before the share thins them. */
    public int stepCandidates() {
        return stepCandidates;
    }

    /** Its seed's bits: what {@code fx_rand} and {@link #rand(int, int, int)} take. */
    public int seedBits() {
        return seed;
    }

    public float share() {
        return share;
    }

    /**
     * True once it has spawned everything it will and every particle has died. Stepped by {@link #schedule}, a step
     * after its latest-dying particle's death, which its spawns' lives give.
     */
    public boolean finished() {
        boolean spawnedAll = time >= 0f && time > emitter.lastSpawn() && burstsDone == emitter.burstTimes.length;
        if (scheduled) return spawnedAll && time >= lastDeath + stepDt;
        return spawnedAll && particles.count() == 0;
    }

    /** Spawn indices handed out so far, kept or thinned. */
    int spawnedSoFar() {
        return spawned;
    }

    public CgVfxEmitter emitter() {
        return emitter;
    }

    public CgVfxParticleSet particles() {
        return particles;
    }

    /** Seconds since {@link #start}, or -1 before it. */
    public float time() {
        return time;
    }

    public CgVfxAir air() {
        return air;
    }

    public double originX() {
        return originX;
    }

    public double originY() {
        return originY;
    }

    public double originZ() {
        return originZ;
    }

    /** Where it spawns from, relative to the effect's origin. */
    public float sourceX() {
        return sourceX;
    }

    public float sourceY() {
        return sourceY;
    }

    public float sourceZ() {
        return sourceZ;
    }

    public float groundY() {
        return groundY;
    }

    /** A three-float scratch a module may use within one {@code apply}. */
    float[] scratch() {
        return scratch;
    }

    private void spawn(float dt) {
        CgVfxEmitter e = emitter;
        while (burstsDone < e.burstTimes.length && e.burstTimes[burstsDone] <= time) {
            for (int n = 0; n < e.burstCounts[burstsDone]; n++) spawnOne();
            burstsDone++;
        }
        if (e.rate > 0f && time >= e.rateFrom && time < e.rateUntil) {
            rateOwed += e.rate * dt;
            while (rateOwed >= 1f) {
                spawnOne();
                rateOwed -= 1f;
            }
        }
    }

    private void spawnOne() {
        CgVfxEmitter e = emitter;
        int k = spawned++;
        if (share < 1f && rand(k, 10) >= share) return;
        int i = particles.add();
        if (i < 0) {
            // The GPU simulation sizes for its schedule and never drops: a shipped emitter keeps this at 0 (vfx-gpu §13.7).
            CgVfxTrace.count(DROPPED, 1);
            return;
        }
        CgVfxParticleSet p = particles;
        p.id[i] = k;
        float up = e.upMin + (e.upMax - e.upMin) * (float) Math.pow(rand(k, 0), e.upBias);
        float heading = rand(k, 1) * 6.2831853f, across = (float) Math.sqrt(Math.max(1f - up * up, 0f));
        float dx = across * (float) Math.cos(heading), dz = across * (float) Math.sin(heading);
        float start = e.shapeRadius * (float) Math.cbrt(rand(k, 2));
        float speed = e.speedMin + (e.speedMax - e.speedMin) * rand(k, 3);
        p.x[i] = p.px[i] = sourceX + dx * start;
        p.y[i] = p.py[i] = sourceY + up * start;
        p.z[i] = p.pz[i] = sourceZ + dz * start;
        p.vx[i] = dx * speed;
        p.vy[i] = up * speed;
        p.vz[i] = dz * speed;
        p.life[i] = e.lifeMin + (e.lifeMax - e.lifeMin) * rand(k, 4);
        p.size[i] = e.sizeMin + (e.sizeMax - e.sizeMin) * (float) Math.pow(rand(k, 5), e.sizeSkew);
        p.seed[i] = rand(k, 6);
        float spin = e.spinMin + (e.spinMax - e.spinMin) * rand(k, 7);
        p.spinRate[i] = rand(k, 8) < 0.5f ? -spin : spin;
        p.spin[i] = rand(k, 9) * 6.2831853f;
        p.heat[i] = e.heat;
    }

    /**
     * Semi-implicit Euler: forces into velocity, drag solved implicitly so a strong one slows a particle without ever
     * reversing it, then position. Clears the accumulators for the next tick.
     */
    private void solve(float dt) {
        CgVfxParticleSet p = particles;
        for (int i = 0; i < p.count(); i++) {
            p.px[i] = p.x[i];
            p.py[i] = p.y[i];
            p.pz[i] = p.z[i];
            if (p.resting[i] == 0f) {
                float vx = p.vx[i] + p.ax[i] * dt, vy = p.vy[i] + p.ay[i] * dt, vz = p.vz[i] + p.az[i] * dt;
                float speed = (float) Math.sqrt(vx * vx + vy * vy + vz * vz);
                float keep = 1f / (1f + (p.drag[i] + p.dragQuad[i] * speed) * dt);
                p.vx[i] = vx * keep;
                p.vy[i] = vy * keep;
                p.vz[i] = vz * keep;
                p.x[i] += p.vx[i] * dt;
                p.y[i] += p.vy[i] * dt;
                p.z[i] += p.vz[i] * dt;
                p.spin[i] += p.spinRate[i] * dt;
            }
            p.ax[i] = p.ay[i] = p.az[i] = 0f;
            p.drag[i] = p.dragQuad[i] = 0f;
        }
    }

    private float rand(int n, int k) {
        return rand(seed, n, k);
    }

    /**
     * A number in 0..1 for the {@code k}-th draw of the {@code n}-th particle an instance seeded with {@code seed} (the
     * bits of its float seed) spawned. {@code fx_rand.glsl} gives the same bits on the GPU.
     *
     * <pre>{@code
     * float life = lifeMin + (lifeMax - lifeMin) * CgVfxEmitterInstance.rand(Float.floatToIntBits(seed), k, 4);
     * }</pre>
     */
    public static float rand(int seed, int n, int k) {
        int h = seed * 0x9E3779B1 ^ n * 0x85EBCA77 ^ k * 0xC2B2AE3D;
        h ^= h >>> 15;
        h *= 0x2C1B3C6D;
        h ^= h >>> 12;
        h *= 0x297A2D39;
        h ^= h >>> 15;
        return (h >>> 8) * (1f / (1 << 24));
    }
}
