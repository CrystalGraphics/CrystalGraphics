package com.crystalgraphics.vfx.particle;

import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.vfx.CgVfxTrace;
import com.crystalgraphics.vfx.particle.gpu.CgVfxEvent;
import com.crystalgraphics.vfx.particle.gpu.CgVfxEventListener;
import com.crystalgraphics.vfx.particle.gpu.CgVfxEventRows;
import com.crystalgraphics.vfx.particle.gpu.CgVfxInstanceView;

import java.util.Arrays;
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
 *   <li>An emitter's events ({@link CgVfxEmitter.Builder#event}) run inside it: each event's children are an instance
 *       of their own ({@link #child}), stepped right after it, drawn with it, and {@link #finished} waits for them.</li>
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
    /** When its latest-dying particle dies, the last step's length, and when that step began. */
    private float lastDeath = -1f, stepDt, stepTime;
    /** By event index: the instance its children run in, and this step's rows to the CPU; null where it has none. */
    private final CgVfxEmitterInstance[] children;
    private final CgVfxEventRows[] rows;
    private boolean rowsDue;
    /** Velocity after the solver and resting before Ground, per particle: a landing's and a collision's row. Null with neither event. */
    private final float[] hitVx, hitVy, hitVz, wasResting;
    /** A child's: the event that feeds it, its index in the parent's events, and the step's fires waiting to spawn. */
    private final CgVfxEvent feed;
    private final int feedEvent;
    private float[] fires;
    private int[] fireIds, fireFirings;
    private int fireCount;

    public CgVfxEmitterInstance(CgVfxEmitter emitter, float seed) {
        this(emitter, Float.floatToIntBits(seed), emitter.capacity, null, -1);
    }

    private CgVfxEmitterInstance(CgVfxEmitter emitter, int seedBits, int capacity, CgVfxEvent feed, int feedEvent) {
        this.emitter = emitter;
        this.particles = new CgVfxParticleSet(capacity);
        this.seed = seedBits;
        this.feed = feed;
        this.feedEvent = feedEvent;
        if (feed != null) {
            fires = new float[9 * 16];
            fireIds = new int[16];
            fireFirings = new int[16];
        }
        List<CgVfxEvent> events = emitter.events;
        children = new CgVfxEmitterInstance[events.size()];
        rows = new CgVfxEventRows[events.size()];
        boolean landings = false;
        for (int e = 0; e < events.size(); e++) {
            CgVfxEvent event = events.get(e);
            if (event.child() != null) {
                // Seeded as its parent, so a child is the same particle on both paths.
                children[e] = new CgVfxEmitterInstance((CgVfxEmitter) event.child(), seedBits, emitter.peakChildren(e), event, e);
            }
            if (event.readback() > 0) rows[e] = new CgVfxEventRows();
            landings |= event.trigger() == CgVfxEvent.Trigger.LANDING || event.trigger() == CgVfxEvent.Trigger.COLLISION;
        }
        hitVx = landings ? new float[capacity] : null;
        hitVy = landings ? new float[capacity] : null;
        hitVz = landings ? new float[capacity] : null;
        wasResting = landings ? new float[capacity] : null;
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
        fireCount = 0;
        for (CgVfxEmitterInstance child : children) {
            if (child != null) child.start(x, y, z);
        }
    }

    /**
     * The height of the ground, relative to the effect's origin, for {@link CgVfxModule.Ground}; NaN for none. With a
     * {@link CgVfxGround} as well, only where the host has no level.
     */
    public CgVfxEmitterInstance ground(float y) {
        groundY = y;
        for (CgVfxEmitterInstance child : children) {
            if (child != null) child.ground(y);
        }
        return this;
    }

    /** The host world's surfaces around the burst, shared by its emitters; null for the fixed height alone. */
    public CgVfxEmitterInstance ground(CgVfxGround field) {
        this.field = field;
        for (CgVfxEmitterInstance child : children) {
            if (child != null) child.ground(field);
        }
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
        clearRows();
        if (feed != null) spawnFed(); else spawn(dt);
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
        if (hitVx != null) {
            // What a particle strikes the floor with: Ground below takes its speed away.
            for (int i = 0; i < p.count(); i++) {
                hitVx[i] = p.vx[i];
                hitVy[i] = p.vy[i];
                hitVz[i] = p.vz[i];
                wasResting[i] = p.resting[i];
            }
        }
        // A strike is this step's alone, as fx_hit's is.
        for (int i = 0; i < p.count(); i++) p.hit[i] = 0f;
        for (int m = 0; m < modules.size(); m++) {
            CgVfxModule module = modules.get(m);
            if (!module.afterSolve()) continue;
            module.apply(this, dt);
            if (t != 0L) t = CgVfxTrace.lap(MODULE_NS.get(module.getClass()), t);
        }
        if (t != 0L) CgVfxTrace.count(TICKED, p.count());
        boolean events = !emitter.events.isEmpty();
        for (int i = p.count() - 1; i >= 0; i--) {
            p.age[i] += dt;
            if (events) fireEvents(i, dt);
            if (p.age[i] >= p.life[i]) p.remove(i);
        }
        CgVfxTrace.lap(AGE_NS, t);
        time += dt;
        for (CgVfxEmitterInstance child : children) {
            if (child != null) child.tick(dt, air, originX, originY, originZ);
        }
    }

    /** The events particle {@code i} fired this step, its age just moved on by {@code dt} ({@link CgVfxEvent}'s rules). */
    private void fireEvents(int i, float dt) {
        CgVfxParticleSet p = particles;
        List<CgVfxEvent> events = emitter.events;
        for (int e = 0; e < events.size(); e++) {
            CgVfxEvent event = events.get(e);
            switch (event.trigger()) {
                case LANDING -> {
                    if (wasResting[i] == 0f && p.resting[i] != 0f) fire(e, event, i, hitVx[i], hitVy[i], hitVz[i], 0f, 1f, 0f, 0);
                }
                case DEATH -> {
                    if (p.age[i] >= p.life[i]) fireMoving(e, event, i, 0);
                }
                case AGE -> {
                    if (p.age[i] - dt < event.age() && event.age() <= p.age[i]) fireMoving(e, event, i, 0);
                }
                case COLLISION -> {
                    if (p.hit[i] != 0f && p.collisions[i] <= event.firings()) fire(e, event, i, hitVx[i], hitVy[i], hitVz[i], p.hitNx[i], p.hitNy[i], p.hitNz[i], p.collisions[i]);
                }
                case RATE -> {
                    // fx: floor((age - dt) / T) < floor(age / T), in float as the Step kernel works it
                    float period = event.age(), before = (float) Math.floor((p.age[i] - dt) / period), now = (float) Math.floor(p.age[i] / period);
                    if (before < now && now <= event.firings()) fireMoving(e, event, i, (int) now);
                }
            }
        }
    }

    /** A death's, an age's or a rate's fire: its normal is its velocity's direction, up when it barely moves. */
    private void fireMoving(int e, CgVfxEvent event, int i, int firing) {
        CgVfxParticleSet p = particles;
        float vx = p.vx[i], vy = p.vy[i], vz = p.vz[i], d = vx * vx + vy * vy + vz * vz;
        if (d <= 1e-12f) {
            fire(e, event, i, vx, vy, vz, 0f, 1f, 0f, firing);
        } else {
            float inv = 1f / (float) Math.sqrt(d);
            fire(e, event, i, vx, vy, vz, vx * inv, vy * inv, vz * inv, firing);
        }
    }

    /** Event {@code e} of particle {@code i}; {@code firing} which of a repeating event's firings, 0 for one that fires once. */
    private void fire(int e, CgVfxEvent event, int i, float vx, float vy, float vz, float nx, float ny, float nz, int firing) {
        CgVfxParticleSet p = particles;
        if (children[e] != null) children[e].queue(p.id[i], firing, p.x[i], p.y[i], p.z[i], vx, vy, vz, nx, ny, nz);
        CgVfxEventRows r = rows[e];
        if (r != null) {
            if (r.count() < event.readback()) {
                r.add(-1, p.id[i], originX + p.x[i], originY + p.y[i], originZ + p.z[i], vx, vy, vz, nx, ny, nz, firing);
            } else {
                r.drop(1);
            }
            rowsDue = true;
        }
    }

    /** A parent's fire, for this child's next step to spawn its children from. */
    private void queue(int parentId, int firing, float x, float y, float z, float vx, float vy, float vz, float nx, float ny, float nz) {
        if (fireCount == fireIds.length) {
            fires = Arrays.copyOf(fires, fires.length * 2);
            fireIds = Arrays.copyOf(fireIds, fireIds.length * 2);
            fireFirings = Arrays.copyOf(fireFirings, fireFirings.length * 2);
        }
        fireFirings[fireCount] = firing;
        int at = fireCount * 9;
        fires[at] = x;
        fires[at + 1] = y;
        fires[at + 2] = z;
        fires[at + 3] = vx;
        fires[at + 4] = vy;
        fires[at + 5] = vz;
        fires[at + 6] = nx;
        fires[at + 7] = ny;
        fires[at + 8] = nz;
        fireIds[fireCount++] = parentId;
    }

    /** The instance event {@code event}'s children run in, or null where it spawns none. */
    public CgVfxEmitterInstance child(int event) {
        return children[event];
    }

    /** Whether its last step, or a child's, left rows for {@link #deliverRows}. */
    public boolean rowsDue() {
        if (rowsDue) return true;
        for (CgVfxEmitterInstance child : children) {
            if (child != null && child.rowsDue()) return true;
        }
        return false;
    }

    /** Hands its last step's rows, and its children's, to {@code listeners}, once. Render thread. */
    public void deliverRows(List<CgVfxEventListener> listeners) {
        if (rowsDue) {
            rowsDue = false;
            for (int e = 0; e < rows.length; e++) {
                CgVfxEventRows r = rows[e];
                if (r == null || r.count() == 0 && r.dropped() == 0) continue;
                for (int l = 0; l < listeners.size(); l++) listeners.get(l).events(emitter, e, r);
            }
        }
        for (CgVfxEmitterInstance child : children) {
            if (child != null) child.deliverRows(listeners);
        }
    }

    private void clearRows() {
        rowsDue = false;
        for (CgVfxEventRows r : rows) {
            if (r != null) r.clear();
        }
    }

    /**
     * One step of the GPU path (plan vfx-gpu §13.7): schedules what spawns this step exactly as {@link #tick} would
     * spawn it, and moves no particle; the pool's Step kernel does. Read the spawns after it, for the pool's queue.
     *
     * <pre>{@code
     * instance.schedule(dt, originX, originY, originZ);
     * pool.instance(slot, instance.seedBits(), instance.share(), instance.groundY(), stepView);  // time() = stepTime()
     * pool.spawn(slot, instance.stepFirstSpawn(), instance.stepCandidates());
     * if (instance.finished()) pool.close(slot);
     * }</pre>
     *
     * <ul>
     *   <li>An instance is stepped one way from its {@link #start}: by {@code tick} or by this, never both.</li>
     *   <li>After it, {@link #time()} is the step's end; the pool's view of the step reads {@link #stepTime()}.</li>
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
        stepTime = time;
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
        coastChildren(dt);
    }

    /** A spawn candidate: thinned by the share as {@code spawnOne} is, its death kept if it spawns. */
    private void scheduleOne(float dt) {
        int k = spawned++;
        if (share < 1f && rand(k, 10) >= share) return;
        float life = emitter.lifeMin + (emitter.lifeMax - emitter.lifeMin) * rand(k, 4);
        // It ages a step at a time from this one, and goes at the end of the step its age reaches its life.
        lastDeath = Math.max(lastDeath, time + dt * (float) Math.ceil(life / dt));
    }

    /**
     * A {@link #schedule}d step that spawns nothing, for an instance whose effect has stopped stepping it (killed, or
     * gone): its particles in the pool still move and age until they die, and {@link #finished} says when.
     */
    public void coast(float dt) {
        if (time < 0f) return;
        scheduled = true;
        stepDt = dt;
        stepTime = time;
        stepFirst = spawned;
        stepCandidates = 0;
        time += dt;
        coastChildren(dt);
    }

    /**
     * Its children's step on the GPU path, where the pool spawns them from the parent's fires. The last fire is at its
     * latest-dying particle's death, and a child spawned then lives its longest life after it.
     */
    private void coastChildren(float dt) {
        for (CgVfxEmitterInstance child : children) {
            if (child == null) continue;
            child.coast(dt);
            child.lastDeath = Math.max(child.lastDeath, lastDeath + dt * (float) Math.ceil(child.emitter.lifeMax / dt));
        }
    }

    /** Whether it is stepped by {@link #schedule}: its particles live in a GPU pool. */
    public boolean scheduled() {
        return scheduled;
    }

    /** When {@link #schedule}'s last step began: the time the pool's view of that step reads. */
    public float stepTime() {
        return stepTime;
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
        for (CgVfxEmitterInstance child : children) {
            if (child != null && !child.finished()) return false;
        }
        // A child spawns from its parent alone, which waits for it: its own schedule never runs.
        boolean spawnedAll = feed != null
                || time >= 0f && time > emitter.lastSpawn() && burstsDone == emitter.burstTimes.length;
        if (scheduled) return spawnedAll && time >= lastDeath + stepDt;
        return spawnedAll && particles.count() == 0 && fireCount == 0;
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
        int k = spawned++;
        if (share < 1f && rand(k, 10) >= share) return;
        int i = add();
        if (i < 0) return;
        CgVfxEmitter e = emitter;
        CgVfxParticleSet p = particles;
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
        draws(i, k);
    }

    /** This step's children, {@code feed.count()} at each fire its parent queued. */
    private void spawnFed() {
        int count = feed.count();
        for (int f = 0; f < fireCount; f++) {
            int firing = fireFirings[f];
            for (int c = 0; c < count; c++) {
                spawnChild(f, firing == 0 ? CgVfxEvent.childKey(fireIds[f], feedEvent, c) : CgVfxEvent.childKey(fireIds[f], feedEvent, c, firing));
            }
        }
        fireCount = 0;
    }

    /**
     * Spawn {@code k} launched as {@link #spawnOne} launches one, its frame turned from up onto fire {@code f}'s normal
     * (Duff et al. 2017, "Building an Orthonormal Basis, Revisited"), from where its parent was, plus a share of the
     * parent's velocity.
     */
    private void spawnChild(int f, int k) {
        if (share < 1f && rand(k, 10) >= share) return;
        int i = add();
        if (i < 0) return;
        CgVfxEmitter e = emitter;
        CgVfxParticleSet p = particles;
        float[] at = fires;
        int o = f * 9;
        float up = e.upMin + (e.upMax - e.upMin) * (float) Math.pow(rand(k, 0), e.upBias);
        float heading = rand(k, 1) * 6.2831853f, across = (float) Math.sqrt(Math.max(1f - up * up, 0f));
        float dx = across * (float) Math.cos(heading), dz = across * (float) Math.sin(heading);
        float start = e.shapeRadius * (float) Math.cbrt(rand(k, 2));
        float speed = e.speedMin + (e.speedMax - e.speedMin) * rand(k, 3);
        float nx = at[o + 6], ny = at[o + 7], nz = at[o + 8], wx, wy, wz;
        if (nx == 0f && nz == 0f && ny > 0f) {
            // Up exactly, as a flat landing's is: spawnOne's frame untouched, where the basis below would mirror z.
            wx = dx;
            wy = up;
            wz = dz;
        } else {
            // n.z >= 0 rather than copysign, so -0 takes GLSL's branch.
            float s = nz >= 0f ? 1f : -1f, a = -1f / (s + nz), b = nx * ny * a;
            float tx = 1f + s * nx * nx * a, ty = s * b, tz = -s * nx;
            float ux = b, uy = s + ny * ny * a, uz = -ny;
            wx = tx * dx + nx * up + ux * dz;
            wy = ty * dx + ny * up + uy * dz;
            wz = tz * dx + nz * up + uz * dz;
        }
        float inherit = feed.inherit();
        p.x[i] = p.px[i] = at[o] + wx * start;
        p.y[i] = p.py[i] = at[o + 1] + wy * start;
        p.z[i] = p.pz[i] = at[o + 2] + wz * start;
        p.vx[i] = wx * speed + inherit * at[o + 3];
        p.vy[i] = wy * speed + inherit * at[o + 4];
        p.vz[i] = wz * speed + inherit * at[o + 5];
        draws(i, k);
    }

    /** A new particle's index, or -1 when its set is full. */
    private int add() {
        int i = particles.add();
        // The GPU simulation sizes for its schedule and never drops: a shipped emitter keeps this at 0 (vfx-gpu §13.7).
        if (i < 0) CgVfxTrace.count(DROPPED, 1);
        return i;
    }

    /** Spawn {@code k}'s draws after its launch, into particle {@code i}: its id, life, size, seed and spin. */
    private void draws(int i, int k) {
        CgVfxEmitter e = emitter;
        CgVfxParticleSet p = particles;
        p.id[i] = k;
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
