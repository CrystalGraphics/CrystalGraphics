package com.crystalgraphics.vfx.particle;

import com.crystalgraphics.vfx.fluid.CgVfxFluid;
import com.crystalgraphics.vfx.grid.CgVfxGrid3D;
import com.crystalgraphics.vfx.particle.gpu.CgVfxEvent;
import com.crystalgraphics.vfx.particle.gpu.CgVfxInstanceView;
import com.crystalgraphics.vfx.particle.spawn.CgVfxEmitterState;
import com.crystalgraphics.vfx.particle.spawn.CgVfxInactiveResponse;
import com.crystalgraphics.vfx.particle.spawn.CgVfxSpawnBurst;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.List;

/**
 * One running {@link CgVfxEmitter}: its clock, where it spawns from, and the books of what it spawns when. Its particles
 * live on the GPU, in a slot of its shape's pool that each particle step steps (vfx-gpu §13.7). An effect makes one per
 * emitter it plays, starts it where the burst happens, and ticks it through {@code CgVfxEffect.tick(instance, dt)}.
 *
 * <pre>{@code
 * CgVfxEmitterInstance embers = new CgVfxEmitterInstance(EMBERS, seed);
 * embers.start(impactX, impactY, impactZ);        // relative to the effect's origin
 *
 * // every tick, in the effect's tick
 * tick(embers, dt);
 * if (embers.finished()) ...                       // spawned everything and every particle has died
 *
 * embers.stop();                                   // no more spawns; what is alive lives out (or dies: inactiveResponse)
 * embers.kill();                                   // no more spawns, and every live particle gone at the next step
 * }</pre>
 *
 * <ul>
 *   <li>Nothing spawns before {@link #start}; a second {@code start} restarts it.</li>
 *   <li>Positions are relative to the effect's origin. Particles live in the world: if the effect moves, the instance
 *       keeps the origin it was first stepped at and spawns from its source carried along, so what it spawned stays
 *       put. An emitter marked {@code local()} takes each origin as it comes, and its particles ride the effect.</li>
 *   <li>{@link #share} thins what spawns by particle index, so a sparser burst keeps the same particles where it keeps
 *       any: the effect's shape, only fewer. A playing effect's share is its system's, set every tick.</li>
 *   <li>Each event that spawns children has an instance of its own ({@link #child}), fed by this one's slot.</li>
 *   <li>{@link #finished} needs nothing back from the GPU: each spawn's life is drawn by the hash the kernel draws it
 *       by, so it is known here. A particle a module kills early holds its slot until its life would have ended.</li>
 *   <li>A source that moves ({@link #moveTo}, or its effect following something) spreads each step's rate along where
 *       it went, and a {@code ratePerBlock} counts how far; a step farther than the emitter's {@code maxStep} is a
 *       teleport, spreading and counting nothing.</li>
 *   <li>Its emitter's capacity holds what is alive: a spawn past it is dropped here, so the GPU never gets it.</li>
 * </ul>
 */
public final class CgVfxEmitterInstance {

    private final CgVfxEmitter emitter;
    private final Books books = new Books();
    private final int seed;
    private float time = -1f, sourceX, sourceY, sourceZ, groundY = Float.NaN;
    private double originX, originY, originZ;
    /**
     * In the world, not riding the effect ({@link CgVfxEmitter#local}): the origin it was first stepped at, which it
     * keeps, and how far the effect has moved from it since, which carries its source along.
     */
    private final boolean local;
    private double anchorX = Double.NaN, anchorY, anchorZ;
    private float driftX, driftY, driftZ;
    /** The fluid its particles are carried by ({@link #inGas}); null for none. */
    @Nullable
    private CgVfxFluid gas;
    /** The grid its particles are in ({@link #inGrid}); null for none. */
    @Nullable
    private CgVfxGrid3D grid;
    private int spawned, burstsDone;
    /** The loop under way (its emitter's state), when its time began after its delay, and whether every loop is spent. */
    private int loop;
    private float loopStart;
    private boolean loopsOver;
    private float rateOwed, share = 1f;
    /** When {@link #stop} ended its spawning; infinite until then. What {@link #rateScale} multiplies its rate by. */
    private float stoppedAt = Float.POSITIVE_INFINITY, rateScale = 1f;
    /** Stepped by {@link #schedule} at least once: its slot is open. */
    private boolean scheduled;
    /** {@link #kill}ed since its last {@link #start}; a kill of its slot not yet taken by its system ({@link Books#takeKill}). */
    private boolean killed, killPending;
    private int stepFirst, stepCandidates, stepBursts;
    /** Where the source was at the step's start, and at the last step's end; whether there was a last step. */
    private float fromX, fromY, fromZ, lastX, lastY, lastZ;
    private boolean stepped;
    /** The followed entity's box, relative to the origin: its centre and half extents, 0 for none. */
    private float boxX, boxY, boxZ, boxHalfX, boxHalfY, boxHalfZ;
    /**
     * Its effect's rotation ({@link Books#orientation}), the one its last step was taken at, and their difference for a
     * local instance: unit quaternions, the identity unless turned. {@code turned} once a step has been taken at one.
     */
    private float rotX, rotY, rotZ, rotW = 1f, lastRotX, lastRotY, lastRotZ, lastRotW = 1f, turnX, turnY, turnZ, turnW = 1f;
    private boolean turned;
    /**
     * What is alive, by when each dies, in buckets of {@link #BUCKET} seconds round a ring: null unless its capacity can
     * bind ({@code CgVfxEmitter.uncappedPeak}). Deaths are booked a step late, so the GPU never holds more.
     */
    private final int[] dying;
    private int alive;
    private long retired;
    private static final float BUCKET = 1f / 240f;
    /** When its latest-dying particle dies, the last step's length, and when that step began. */
    private float lastDeath = -1f, stepDt, stepTime;
    /** By event index: the instance its children run in; null where it spawns none. */
    private final CgVfxEmitterInstance[] children;
    /** A child's: the event that feeds it. */
    private final CgVfxEvent feed;

    public CgVfxEmitterInstance(CgVfxEmitter emitter, float seed) {
        this(emitter, Float.floatToIntBits(seed), null, emitter.local);
    }

    private CgVfxEmitterInstance(CgVfxEmitter emitter, int seedBits, CgVfxEvent feed, boolean local) {
        this.emitter = emitter;
        this.seed = seedBits;
        this.feed = feed;
        this.local = local;
        List<CgVfxEvent> events = emitter.events;
        boolean capped = feed == null && emitter.capacity < emitter.uncappedPeak();
        dying = capped ? new int[(int) Math.ceil((emitter.lifeMax + 1f) / BUCKET) + 4] : null;
        children = new CgVfxEmitterInstance[events.size()];
        for (int e = 0; e < events.size(); e++) {
            CgVfxEvent event = events.get(e);
            // Seeded as its parent, as the kernel keys a child by its parent's id.
            if (event.child() != null) children[e] = new CgVfxEmitterInstance((CgVfxEmitter) event.child(), seedBits, event, local);
        }
    }

    /** Starts spawning at {@code (x, y, z)}, relative to the effect's origin, from this tick on. */
    public void start(float x, float y, float z) {
        sourceX = x;
        sourceY = y;
        sourceZ = z;
        anchorX = Double.NaN;
        driftX = driftY = driftZ = 0f;
        time = 0f;
        spawned = 0;
        burstsDone = 0;
        loop = 0;
        loopStart = emitter.state.delayBefore(seed, 0);
        loopsOver = false;
        rateOwed = 0f;
        lastDeath = -1f;
        stoppedAt = Float.POSITIVE_INFINITY;
        // A kill not yet taken still ends the old slot's particles; this start spawns in a fresh one.
        killed = false;
        stepped = false;
        alive = 0;
        retired = -1;
        if (dying != null) Arrays.fill(dying, 0);
        for (CgVfxEmitterInstance child : children) {
            if (child != null) child.start(x, y, z);
        }
    }

    /**
     * Spawns at {@code (x, y, z)}, relative to the effect's origin, from the next step on, without restarting: what it
     * has spawned stays where it is. For a source that travels, as dust kicked up along a beam.
     *
     * <pre>{@code
     * wake.moveTo(groundX, groundY, groundZ);   // each tick, before tick(wake, dt)
     * tick(wake, dt);
     * }</pre>
     *
     * Modules placed round the source ({@code Attract}, {@code Orbit}, a {@code Collide} volume) move with it.
     */
    public void moveTo(float x, float y, float z) {
        sourceX = x;
        sourceY = y;
        sourceZ = z;
    }

    /**
     * Ends its spawning now, bursts and rate alike: for a rate that runs until something stops it
     * ({@code rate(n, 0f, Float.POSITIVE_INFINITY)}), which never {@link #finished finishes} until then. What it spawned
     * lives out, or is {@link #kill}ed where its emitter's {@link CgVfxInactiveResponse} is {@code KILL}: Niagara's
     * deactivation. {@link #start} spawns again.
     */
    public void stop() {
        if (emitter.state.inactiveResponse() == CgVfxInactiveResponse.KILL) {
            kill();
            return;
        }
        if (time >= 0f) stoppedAt = Math.min(stoppedAt, time);
    }

    /**
     * Ends its spawning and every particle it has alive, its event children's too, in the next particle step, firing no
     * event: Niagara's {@code InactiveClear}, Cascade's {@code clear}. {@link #finished} is true from now on, and
     * {@link #start} spawns again, in a fresh slot.
     *
     * <pre>{@code
     * if (interrupted) aura.kill();     // gone at once, where stop() would let it fall out
     * ...
     * aura.start(0f, 1f, 0f);           // later: plays again
     * }</pre>
     *
     * <ul>
     *   <li>Nothing before its first {@link #start}.</li>
     *   <li>Spawns booked in the tick of the kill are dropped with it; a {@link #start} in that same tick spawns from
     *       the tick after.</li>
     *   <li>One emitter's: {@code CgVfxEffect.kill()} ends a whole effect and every particle of it.</li>
     * </ul>
     */
    public void kill() {
        if (time < 0f) return;
        stoppedAt = Math.min(stoppedAt, time);
        killed = true;
        // Without a slot nothing is alive on the GPU to end.
        killPending |= scheduled;
        for (CgVfxEmitterInstance child : children) {
            if (child != null) child.killed = true;
        }
    }

    /**
     * Multiplies its rate from the next step on, bursts untouched: 0 spawns nothing while it lasts, without ending
     * anything. Niagara's spawn rate scale, for a rate that follows the effect.
     *
     * <pre>{@code
     * wake.rateScale(blocksRunningLow);   // the emitter's rate is then per block
     * }</pre>
     */
    public CgVfxEmitterInstance rateScale(float scale) {
        rateScale = Math.max(scale, 0f);
        return this;
    }

    /**
     * The gas its particles are carried by: what a module taking {@code GAS} reads ({@link CgVfxModule.Flow}). Its
     * children are carried by it too.
     *
     * <pre>{@code
     * embers.inGas(smoke);                         // a CgVfxFluid of the same effect
     * }</pre>
     */
    public CgVfxEmitterInstance inGas(@Nullable CgVfxFluid gas) {
        this.gas = gas;
        for (CgVfxEmitterInstance child : children) {
            if (child != null) child.inGas(gas);
        }
        return this;
    }

    /**
     * The grid its particles are in: what a module taking {@code GRID} reads and {@code GRID_SPLAT} writes
     * ({@link CgVfxModule.Splat}, {@link CgVfxModule.Follow}, {@link CgVfxModule.Gradient}). Its children are in it too.
     *
     * <pre>{@code
     * agents.inGrid(trail);                        // a CgVfxGrid3D of the same effect
     * }</pre>
     */
    public CgVfxEmitterInstance inGrid(@Nullable CgVfxGrid3D grid) {
        this.grid = grid;
        for (CgVfxEmitterInstance child : children) {
            if (child != null) child.inGrid(grid);
        }
        return this;
    }

    /**
     * The height of the ground, relative to the effect's origin now, for {@link CgVfxModule.Ground} where the host has no
     * world (the harness); NaN for none. It is the world's: it stays put if the effect moves.
     */
    public CgVfxEmitterInstance ground(float y) {
        groundY = y + driftY;
        for (CgVfxEmitterInstance child : children) {
            if (child != null) child.ground(y);
        }
        return this;
    }

    /** The source's start this step, from where the last ended: how far it went, or 0 for none or a teleport. */
    private float travel() {
        float x = sourceX(), y = sourceY(), z = sourceZ(), travelled = 0f;
        fromX = x;
        fromY = y;
        fromZ = z;
        if (stepped) {
            float dx = x - lastX, dy = y - lastY, dz = z - lastZ, d = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (d <= emitter.maxStep) {
                fromX = lastX;
                fromY = lastY;
                fromZ = lastZ;
                travelled = d;
            }
        }
        lastX = x;
        lastY = y;
        lastZ = z;
        stepped = true;
        return travelled;
    }

    /** How many of {@code n} candidates from the next spawn index fit under its capacity, those the share thins counting none. */
    private int fit(int n) {
        if (dying == null) return n;
        int room = emitter.capacity - alive, k = spawned, taken = 0;
        while (taken < n) {
            if (!(share < 1f && rand(k, 10) >= share)) {
                if (room == 0) break;
                room--;
            }
            k++;
            taken++;
        }
        return taken;
    }

    /** Retires every death booked at or before {@code now}. */
    private void retire(float now) {
        if (dying == null) return;
        long at = (long) Math.floor(now / BUCKET);
        if (at - retired >= dying.length) {
            Arrays.fill(dying, 0);
            alive = 0;
            retired = at;
            return;
        }
        while (retired < at) {
            retired++;
            int i = (int) (retired % dying.length);
            alive -= dying[i];
            dying[i] = 0;
        }
    }

    /**
     * Takes the effect's origin for a step. One in the world keeps the origin it was first stepped at, so what it spawned
     * stays put, and its source is carried along by how far the effect has moved since. Its children take the same.
     */
    private void place(double x, double y, double z) {
        if (local) {
            originX = x;
            originY = y;
            originZ = z;
        } else {
            if (Double.isNaN(anchorX)) {
                anchorX = x;
                anchorY = y;
                anchorZ = z;
            }
            originX = anchorX;
            originY = anchorY;
            originZ = anchorZ;
            driftX = (float) (x - anchorX);
            driftY = (float) (y - anchorY);
            driftZ = (float) (z - anchorZ);
        }
        turn();
        for (CgVfxEmitterInstance child : children) {
            if (child == null) continue;
            child.originX = originX;
            child.originY = originY;
            child.originZ = originZ;
            child.driftX = driftX;
            child.driftY = driftY;
            child.driftZ = driftZ;
            child.rotX = rotX;
            child.rotY = rotY;
            child.rotZ = rotZ;
            child.rotW = rotW;
            child.turnX = turnX;
            child.turnY = turnY;
            child.turnZ = turnZ;
            child.turnW = turnW;
        }
    }

    /** Its rotation's change since its last step, {@code rot * conj(lastRot)}, for a local instance; else the identity. */
    private void turn() {
        if (local && turned) {
            turnX = lastRotW * rotX - rotW * lastRotX - (rotY * lastRotZ - rotZ * lastRotY);
            turnY = lastRotW * rotY - rotW * lastRotY - (rotZ * lastRotX - rotX * lastRotZ);
            turnZ = lastRotW * rotZ - rotW * lastRotZ - (rotX * lastRotY - rotY * lastRotX);
            turnW = rotW * lastRotW + rotX * lastRotX + rotY * lastRotY + rotZ * lastRotZ;
        } else {
            turnX = turnY = turnZ = 0f;
            turnW = 1f;
        }
        lastRotX = rotX;
        lastRotY = rotY;
        lastRotZ = rotZ;
        lastRotW = rotW;
        turned = true;
    }

    /**
     * A spawn candidate born {@code born} of the way through the step: thinned by the share as the kernel thins it, its
     * death kept if it spawns.
     */
    private void scheduleOne(float dt, float born) {
        int k = spawned++;
        if (share < 1f && rand(k, 10) >= share) return;
        float life = emitter.lifeMin + (emitter.lifeMax - emitter.lifeMin) * rand(k, 4);
        // It ages the rest of this step, then a step at a time, and goes at the end of the step its age reaches its life.
        float death = time + dt * (float) Math.ceil(life / dt + born);
        lastDeath = Math.max(lastDeath, death);
        if (dying != null) {
            alive++;
            dying[(int) ((long) Math.ceil((death + dt) / BUCKET) % dying.length)]++;
        }
    }

    /**
     * Fires every burst whose time in its loop has come, at this step's start, and moves on to each loop whose time has
     * come: its delay picked, its bursts armed again. A step longer than a loop crosses several.
     */
    private void fireBursts() {
        CgVfxEmitterState state = emitter.state;
        CgVfxSpawnBurst[] bursts = emitter.bursts;
        float duration = state.loopDuration();
        int loops = state.loops();
        while (!loopsOver && time < stoppedAt) {
            float age = time - loopStart;
            // A burst past the loop's end never fires; in the delay, age is negative and none does.
            while (burstsDone < bursts.length && bursts[burstsDone].time() <= Math.min(age, duration)) {
                int fit = fit(bursts[burstsDone].count(seed, loop, burstsDone));
                for (int n = 0; n < fit; n++) scheduleOne(stepDt, 0f);
                burstsDone++;
            }
            if (age < duration) {
                // Once its last loop's bursts are spent, one spawning by bursts alone has spawned everything.
                if (loop + 1 >= loops && burstsDone == bursts.length && !emitter.spawnsContinuously()) loopsOver = true;
                return;
            }
            if (loop + 1 >= loops) {
                loopsOver = true;
                return;
            }
            loop++;
            loopStart += duration + state.delayBefore(seed, loop);
            burstsDone = 0;
        }
    }

    /**
     * Its children's step, where the pool spawns them from the parent's fires. The last fire is at its latest-dying
     * particle's death, and a child spawned then lives its longest life after it.
     */
    private void coastChildren(float dt) {
        for (CgVfxEmitterInstance child : children) {
            if (child == null) continue;
            // The child's slot is placed by its own origin, which only schedule sets: it takes its parent's.
            child.originX = originX;
            child.originY = originY;
            child.originZ = originZ;
            child.books.coast(dt);
            child.lastDeath = Math.max(child.lastDeath, lastDeath + dt * (float) Math.ceil(child.emitter.lifeMax / dt));
        }
    }

    /**
     * True once it has spawned everything it will and every particle has died: a step after its latest death. True at
     * once after a {@link #kill}.
     */
    public boolean finished() {
        if (killed) return true;
        for (CgVfxEmitterInstance child : children) {
            if (child != null && !child.finished()) return false;
        }
        // A child spawns from its parent alone, which waits for it: its own schedule never runs.
        boolean spawnedAll = feed != null || time >= 0f && (time > stoppedAt || loopsOver);
        return spawnedAll && time >= lastDeath + stepDt;
    }

    /** The instance event {@code e}'s children run in, or null where it spawns none. */
    public CgVfxEmitterInstance child(int e) {
        return children[e];
    }

    public CgVfxEmitter emitter() {
        return emitter;
    }

    /** Seconds since {@link #start}, or -1 before it. */
    public float time() {
        return time;
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

    /** Where it spawns from, relative to its {@link #originX origin}: the source carried along with the effect. */
    public float sourceX() {
        return sourceX + driftX;
    }

    public float sourceY() {
        return sourceY + driftY;
    }

    public float sourceZ() {
        return sourceZ + driftZ;
    }

    public float groundY() {
        return groundY;
    }

    /**
     * Its books as its system keeps them: each step's spawns, its share, the box it spawns in, the step the GPU reads. The
     * system's and the pool's, never an effect's: an effect starts, moves and stops its instance, and ticks it through
     * {@code CgVfxEffect.tick(instance, dt)}.
     *
     * <pre>{@code
     * instance.books().schedule(dt, originX, originY, originZ);
     * pool.spawn(slot, instance.books().stepFirstSpawn(), instance.books().stepCandidates(), ...);
     * }</pre>
     */
    public Books books() {
        return books;
    }

    /** {@link #books()}: what the system books, steps and draws an instance by. */
    public final class Books implements CgVfxInstanceView {

        private Books() {
        }

        @Override
        public double originX() {
            return originX;
        }

        @Override
        public double originY() {
            return originY;
        }

        @Override
        public double originZ() {
            return originZ;
        }

        @Override
        public float time() {
            return time;
        }

        @Override
        public float sourceX() {
            return CgVfxEmitterInstance.this.sourceX();
        }

        @Override
        public float sourceY() {
            return CgVfxEmitterInstance.this.sourceY();
        }

        @Override
        public float sourceZ() {
            return CgVfxEmitterInstance.this.sourceZ();
        }

        /**
         * The box of the entity its effect follows, its centre relative to the effect's origin: what an {@code entity()}
         * emitter spawns in. The effect sets it each tick it steps this instance; all 0 while it follows none.
         */
        public void entityBox(float x, float y, float z, float halfX, float halfY, float halfZ) {
            boxX = x;
            boxY = y;
            boxZ = z;
            boxHalfX = halfX;
            boxHalfY = halfY;
            boxHalfZ = halfZ;
        }

        /**
         * How its effect is turned in the world, a unit quaternion: the effect sets it each tick it steps this instance,
         * from {@code CgVfxEffect.orientation()}. Its next step's spawns turn by it, and a local instance's live particles
         * by its change since the last.
         */
        public void orientation(float x, float y, float z, float w) {
            rotX = x;
            rotY = y;
            rotZ = z;
            rotW = w;
        }

        @Override
        public float rotationX() {
            return rotX;
        }

        @Override
        public float rotationY() {
            return rotY;
        }

        @Override
        public float rotationZ() {
            return rotZ;
        }

        @Override
        public float rotationW() {
            return rotW;
        }

        @Override
        public float turnX() {
            return turnX;
        }

        @Override
        public float turnY() {
            return turnY;
        }

        @Override
        public float turnZ() {
            return turnZ;
        }

        @Override
        public float turnW() {
            return turnW;
        }

        /** How far the {@link #entityBox} reaches from the source: what an arc's cull adds. */
        public float entityBoxReach() {
            if (boxHalfX == 0f && boxHalfY == 0f && boxHalfZ == 0f) return 0f;
            float x = Math.abs(boxX()) + boxHalfX, y = Math.abs(boxY()) + boxHalfY, z = Math.abs(boxZ()) + boxHalfZ;
            return (float) Math.sqrt(x * x + y * y + z * z);
        }

        /** The share of its particles to spawn, 0 to 1: the player's density, applied by the system. */
        public Books share(float share) {
            CgVfxEmitterInstance.this.share = share;
            return this;
        }

        /**
         * One step: books what spawns this step, which the pool's Step kernel spawns and moves. Read the spawns after it,
         * for the pool's queue: the step's bursts first, born where the source was at its start, then its rate, spread along
         * where the source went.
         *
         * <pre>{@code
         * instance.schedule(dt, originX, originY, originZ);
         * pool.instance(slot, instance.seedBits(), instance.share(), instance.groundY(), stepView);  // time() = stepTime()
         * pool.spawn(slot, instance.stepFirstSpawn(), instance.stepCandidates(), instance.stepCandidates() - instance.stepBursts());
         * if (instance.finished()) pool.close(slot);
         * }</pre>
         *
         * After it, {@link #time()} is the step's end; the pool's view of the step reads {@link #stepTime()}.
         */
        public void schedule(float dt, double originX, double originY, double originZ) {
            if (time < 0f) return;
            scheduled = true;
            place(originX, originY, originZ);
            float travelled = travel();
            stepDt = dt;
            stepTime = time;
            stepFirst = spawned;
            retire(time);
            CgVfxEmitter e = emitter;
            fireBursts();
            stepBursts = spawned - stepFirst;
            float age = time - loopStart;
            if (!loopsOver && time < stoppedAt && age >= 0f && age < e.state.loopDuration()) {
                rateOwed += (e.rate.at(age) * dt + e.perUnit.perBlock() * travelled) * rateScale;
                int n = (int) rateOwed;
                rateOwed -= n;
                // Spawn i of n is born (i + 1) / n of the way through the step, so the last is at its end.
                int fit = fit(n);
                for (int i = 0; i < fit; i++) scheduleOne(dt, (float) (i + 1) / fit);
            }
            stepCandidates = spawned - stepFirst;
            time += dt;
            coastChildren(dt);
        }

        /**
         * A {@link #schedule}d step that spawns nothing, for an instance whose effect has stopped stepping it (killed, or
         * gone): its particles in the pool still move and age until they die, and {@link #finished} says when.
         */
        public void coast(float dt) {
            if (time < 0f) return;
            // Spawning nothing from here on: an open rate finishes too.
            stoppedAt = Math.min(stoppedAt, time);
            scheduled = true;
            stepDt = dt;
            stepTime = time;
            stepFirst = spawned;
            stepCandidates = 0;
            // Its effect no longer turns it: nor do its particles.
            turnX = turnY = turnZ = 0f;
            turnW = 1f;
            time += dt;
            coastChildren(dt);
        }

        /** Whether {@link #schedule} has run since it was made: its slot is open. */
        public boolean scheduled() {
            return scheduled;
        }

        /**
         * Whether it was {@link CgVfxEmitterInstance#kill}ed since the last call: its system then ends its slot's
         * particles in the coming step. True once a kill.
         */
        public boolean takeKill() {
            boolean pending = killPending;
            killPending = false;
            return pending;
        }

        /**
         * Its slot was freed with every particle in it, by its effect's system putting the effect to sleep: its next
         * {@link #schedule} asks for a slot again, its capacity's books start empty, and its first step after has no motion.
         */
        public void slotLost() {
            scheduled = false;
            stepped = false;
            turned = false;
            alive = 0;
            retired = -1;
            lastDeath = -1f;
            if (dying != null) Arrays.fill(dying, 0);
            for (CgVfxEmitterInstance child : children) {
                if (child != null) child.books.slotLost();
            }
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

        /** How many of {@link #stepCandidates} are bursts, first: born where the source was at the step's start. */
        public int stepBursts() {
            return stepBursts;
        }

        /** Its seed's bits: what {@code fx_rand} and {@link #rand(int, int, int)} take. */
        public int seedBits() {
            return seed;
        }

        public float share() {
            return share;
        }

        /** The spawn index its next particle takes: the spawn indices handed out so far, kept or thinned. */
        public int spawnedSoFar() {
            return spawned;
        }

        /** Where the source was at the last step's start, relative to its origin: the source itself the first step. */
        @Override
        public float fromX() {
            return fromX;
        }

        @Override
        public float fromY() {
            return fromY;
        }

        @Override
        public float fromZ() {
            return fromZ;
        }

        @Override
        public float boxX() {
            return boxX - sourceX();
        }

        @Override
        public float boxY() {
            return boxY - sourceY();
        }

        @Override
        public float boxZ() {
            return boxZ - sourceZ();
        }

        @Override
        public float boxHalfX() {
            return boxHalfX;
        }

        @Override
        public float boxHalfY() {
            return boxHalfY;
        }

        @Override
        public float boxHalfZ() {
            return boxHalfZ;
        }

        @Override
        public CgVfxFluid gas() {
            return gas;
        }

        @Override
        public CgVfxGrid3D grid() {
            return grid;
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
