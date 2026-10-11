package com.crystalgraphics.vfx;

import com.crystalgraphics.easing.CgKeyframes;
import com.crystalgraphics.render.post.CgPostStack;
import com.crystalgraphics.render.post.volume.CgPostSettings;
import com.crystalgraphics.render.post.volume.CgPostVolume;
import com.crystalgraphics.vfx.look.CgVfxLook;
import com.crystalgraphics.vfx.look.CgVfxParam;
import com.crystalgraphics.vfx.look.CgVfxValues;
import com.crystalgraphics.vfx.particle.CgVfxAir;
import com.crystalgraphics.vfx.particle.CgVfxEmitterInstance;
import com.crystalgraphics.vfx.particle.gpu.CgVfxEventListener;
import com.crystalgraphics.vfx.camera.CgCameraShake;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An effect playing in a world: placed at an origin in doubles, simulated in floats relative to it on the
 * {@link CgVfxSystem}'s fixed tick, and drawn each frame through a {@link CgVfxFrame}.
 *
 * <pre>{@code
 * public final class MyEffect extends CgVfxEffect {
 *     public MyEffect(CgVfxLook look, double x, double y, double z) { super(look, x, y, z); }
 *     @Override protected void tick(float dt) { ... }          // CgVfxSystem.TICK seconds of simulation
 *     @Override protected void submit(CgVfxFrame frame) { ... } // draw, interpolated by frame.alpha() (particles:
 *                                                               // frame.particleAlpha())
 * }
 * CgVfxEffect effect = vfx.play(new MyEffect(look, x, y, z));
 * effect.stop();                                                // finishes what is in flight, then dies
 *
 * // Bound to an entity, and joined late: its origin follows the entity's hands, and it starts 2.5 s into its life
 * owner.play(new MyEffect(look, x, y, z).follow(entityId, CgEntityAttachments.HANDS_FORWARD).startAt(2.5f));
 * }</pre>
 *
 * <ul>
 *   <li>Its values start as its look's; {@link #set} changes this effect alone.</li>
 *   <li>An effect is done when it calls {@link #die()}; the system then drops it before the next frame.</li>
 *   <li>It announces the moments of its life with {@link #moment}, which a {@link CgVfxMomentListener} hears: name
 *       them as constants on the effect.</li>
 *   <li>It shakes the camera through shakes declared on its schema, so a look can change them:
 *       {@link #playShake} for a hit, {@link #holdShake} every tick for a tremor that lasts. What it holds stops when
 *       it dies.</li>
 *   <li>Its screen-wide look at a moment (a flash, an aberration) is a post volume it opens with {@link #openVolume}
 *       and weighs each tick; it closes when the effect dies.</li>
 * </ul>
 */
public abstract class CgVfxEffect {

    public enum State { PLAYING, STOPPING, DEAD }

    /** Where it is: fixed, or moved each frame by {@link #follow}. An effect reads it and never writes it. */
    protected double originX, originY, originZ;
    /** The system playing it, set by {@link CgVfxSystem#play}. */
    CgVfxSystem system;
    /** Who played it, or null for a system played into directly. */
    CgVfxOwner owner;
    /** Its index in its owner's list; -1 out of it. */
    int ownerSlot = -1;
    /** The entity it follows and the attachment point on it ({@code CgEntityAttachments}); -1 for none. */
    int followEntity = -1, followPoint;
    /** Seconds into its life it starts at, for its system's first update to fast-forward to; 0 once there. */
    float startAge;
    /** Fast-forwarding to its start age: shakes and moments are not played. */
    boolean seeking;
    /** What its emitters threw on a worker, for the render thread to kill it over. */
    volatile RuntimeException failure;
    /** Ended by {@link #kill}, not by {@link #die} alone: its GPU particles go too. */
    boolean killed;
    /** Every instance it has scheduled for the GPU: the slots a kill closes. */
    private CgVfxEmitterInstance[] scheduled = new CgVfxEmitterInstance[0];
    private int scheduledCount;
    /** Its look's materials have been handed to the system to compile ahead of use. */
    boolean warmed;
    /** Its emitters' GPU programs have been started, for {@code vfx.sim=gpu}. */
    boolean gpuPrepared;
    private final CgVfxLook look;
    private final CgVfxValues values;
    private State state = State.PLAYING;
    /** The air an effect's particles move through before it is played: still. */
    private static final CgVfxAir STILL = new CgVfxAir().wind(0f, 0f, 0f);
    private double groundY = Double.NaN;
    /** The shakes it holds, by parameter; made when first held. */
    private CgVfxParam[] heldParams;
    private CgCameraShake.Held[] held;
    /** The post volumes it opened, closed when it dies. */
    private CgPostVolume[] volumes;
    /** The emitters {@link #tick(CgVfxEmitterInstance, float)} queued this tick, run by the system after the steps. */
    private CgVfxEmitterInstance[] due = new CgVfxEmitterInstance[0];
    private int dueCount;
    /** Each queued emitter's step, in particle steps: an effect may tick each at its own dt. */
    private float[] dueDts = new float[0];
    /** Live particles of the queued emitters the CPU steps: what decides whether the system's workers wake. */
    private int dueCpuParticles;
    /** The emitters {@link #tickEmitters} scheduled for the first time, for the system's GPU queue. */
    private CgVfxEmitterInstance[] admitted = new CgVfxEmitterInstance[0];
    private int admittedCount;
    /** The CPU-stepped emitters whose step this tick left event rows, delivered by the system after the steps. */
    private CgVfxEmitterInstance[] reporting = new CgVfxEmitterInstance[0];
    private int reportingCount;
    /** Seconds simulated since it started. */
    protected float age;
    /** A stable random number for this effect, 0..1, which shaders read to tell two effects apart. */
    protected final float seed;
    /**
     * Its draws' impact frame subject key ({@code CgWorldRenderer.Draw.subject}), never 0: an impact frame it opens
     * names it ({@code CgPostSettings.subject}) to fill this effect alone in ink.
     */
    protected final int subject = SUBJECTS.incrementAndGet() & Integer.MAX_VALUE | 1;
    private static final AtomicInteger SUBJECTS = new AtomicInteger();

    protected CgVfxEffect(CgVfxLook look, double x, double y, double z) {
        this.look = look;
        this.values = look.copyValues();
        this.originX = x;
        this.originY = y;
        this.originZ = z;
        this.seed = (float) Math.random();
    }

    protected abstract void tick(float dt);

    protected abstract void submit(CgVfxFrame frame);

    /** Ends emission; what is in flight plays out. */
    public void stop() {
        if (state == State.PLAYING) state = State.STOPPING;
    }

    /** Removes it at once, its particles with it: gone by its system's next particle step. */
    public void kill() {
        killed = true;
        die();
    }

    /**
     * Follows entity {@code entity} ({@code CgEntityQuery}'s id): each frame, before it ticks, its origin moves to the
     * attachment point {@code point} ({@code CgEntityAttachments.FEET}, {@code HANDS_FORWARD}, ...) of the entity's pose
     * as drawn. Particles in flight move with it. When the entity is gone it stops, and what is in flight plays out
     * where the entity was last.
     *
     * <pre>{@code
     * owner.play(new Aura(look, x, y, z).follow(playerId, CgEntityAttachments.CENTRE));
     * }</pre>
     */
    public final CgVfxEffect follow(int entity, int point) {
        followEntity = entity;
        followPoint = point;
        return this;
    }

    /** As {@link #follow(int, int)}, at the entity's feet. */
    public final CgVfxEffect follow(int entity) {
        return follow(entity, 0);
    }

    /** The entity it follows, or -1. */
    public final int following() {
        return followEntity;
    }

    /**
     * Starts it {@code seconds} into its life: its system's next update steps it there at once, on its fixed tick, so its
     * phase is what it would be had it played from zero. Its particles are prewarmed over the last
     * {@link CgVfxSystem#PREWARM_SECONDS} at most, in coarse steps; older ones are not made. Call before its first tick:
     * before playing it, or just after.
     *
     * <pre>{@code
     * owner.play(new Beam(look, x, y, z).startAt(serverAge));   // a viewer joining while it fires
     * }</pre>
     *
     * <ul>
     *   <li>Its phase is stepped for {@link CgVfxSystem#MAX_START_SECONDS} at most; past that its age jumps, which suits an
     *       effect that works its phase out from {@link #age} and not one that accumulates it.</li>
     *   <li>While it steps there it plays no camera shake and announces no moment: those happened before the viewer
     *       saw it.</li>
     * </ul>
     */
    public final CgVfxEffect startAt(float seconds) {
        if (age > 0f) throw new IllegalStateException("startAt after its first tick: it has started");
        startAge = Math.max(0f, seconds);
        return this;
    }

    /** Who played it; null when played into a system directly. */
    public final CgVfxOwner owner() {
        return owner;
    }

    protected final void die() {
        state = State.DEAD;
        if (held != null) {
            for (CgCameraShake.Held h : held) {
                if (h != null) h.close();
            }
        }
        if (volumes != null) {
            for (CgPostVolume v : volumes) v.close();
            volumes = null;
        }
    }

    /**
     * A post volume applying {@code settings} at {@code priority}, centred at {@code (x, y, z)} relative to the
     * origin, at weight 0: size it, then weigh it each tick. It closes when the effect dies.
     *
     * <pre>{@code
     * flash = openVolume(10, new CgPostSettings().flash(1.5f), hitX, hitY, hitZ).radius(24f).blend(64f);
     * flash.weight(curve(FLASH).at(t));   // each tick
     * }</pre>
     */
    protected final CgPostVolume openVolume(int priority, CgPostSettings settings, float x, float y, float z) {
        CgPostVolume volume = CgPostStack.get().volume(priority, settings).at(originX + x, originY + y, originZ + z).weight(0f);
        volumes = volumes == null ? new CgPostVolume[1] : Arrays.copyOf(volumes, volumes.length + 1);
        volumes[volumes.length - 1] = volume;
        return volume;
    }

    public final State state() {
        return state;
    }

    public final CgVfxLook look() {
        return look;
    }

    public final float age() {
        return age;
    }

    public final float get(CgVfxParam param) {
        return values.get(param);
    }

    /** Component {@code index} of a colour. */
    public final float get(CgVfxParam param, int index) {
        return values.get(param, index);
    }

    public final CgVfxEffect set(CgVfxParam param, float value) {
        values.set(param, value);
        return this;
    }

    public final CgVfxEffect set(CgVfxParam param, float r, float g, float b, float a) {
        values.set(param, r, g, b, a);
        return this;
    }

    public final CgKeyframes curve(CgVfxParam param) {
        return values.curve(param);
    }

    public final CgVfxEffect set(CgVfxParam param, CgKeyframes curve) {
        values.set(param, curve);
        return this;
    }

    public final CgCameraShake shake(CgVfxParam param) {
        return values.shake(param);
    }

    public final CgVfxEffect set(CgVfxParam param, CgCameraShake shake) {
        values.set(param, shake);
        return this;
    }

    /** Plays the shake {@code param} at a point relative to its origin, its radii in units of {@code scale} blocks. */
    protected final void playShake(CgVfxParam param, float x, float y, float z, float scale) {
        if (!seeking) values.shake(param).play(originX + x, originY + y, originZ + z, scale);
    }

    /** As {@link #playShake(CgVfxParam, float, float, float, float)}, its punch shoving along {@code (dx, dy, dz)}. */
    protected final void playShake(CgVfxParam param, float x, float y, float z, float dx, float dy, float dz, float scale) {
        if (!seeking) values.shake(param).play(originX + x, originY + y, originZ + z, dx, dy, dz, scale);
    }

    /**
     * Holds the shake {@code param} at a point relative to its origin, its trauma times {@code level}: call it every tick
     * while it lasts. Left unset for a quarter second, it lapses.
     */
    protected final void holdShake(CgVfxParam param, float x, float y, float z, float scale, float level) {
        if (seeking) return;
        CgCameraShake shake = values.shake(param);
        int slot = 0;
        if (heldParams == null) {
            heldParams = new CgVfxParam[4];
            held = new CgCameraShake.Held[4];
        }
        while (slot < heldParams.length && heldParams[slot] != null && heldParams[slot] != param) slot++;
        if (slot == heldParams.length) {
            heldParams = Arrays.copyOf(heldParams, slot * 2);
            held = Arrays.copyOf(held, slot * 2);
        }
        if (held[slot] == null || held[slot].shake() != shake) {
            if (held[slot] != null) held[slot].close();
            heldParams[slot] = param;
            held[slot] = shake.hold();
        }
        held[slot].at(originX + x, originY + y, originZ + z).scale(scale).level(level);
    }

    /**
     * Where its particles land where the host has no world (the harness), as a world height; NaN, the default, for no
     * ground. In a world they land on its own surfaces ({@code CgVfxGround}).
     */
    public final CgVfxEffect ground(double worldY) {
        groundY = worldY;
        return this;
    }

    /** The ground's height relative to its origin, or NaN: what an emitter instance takes. */
    protected final float groundHeight() {
        return (float) (groundY - originY);
    }

    /** The air its particles move through: its system's, or still air before it is played. */
    protected final CgVfxAir air() {
        return system != null ? system.air() : STILL;
    }

    /**
     * Advances one of its emitters by a tick, in its system's air and at its spawn share, sampling turbulence at its
     * origin. Played, the emitter runs after {@link #tick(float)} returns, with this effect's other emitters, in the
     * order they were asked, on whichever thread takes the effect, and only every {@link CgVfxSystem#particleStep()}th
     * tick, by that many ticks' time; unplayed, at once, by {@code dt}. Call it every tick either way.
     *
     * <pre>{@code
     * for (CgVfxEmitterInstance e : blast) {
     *     tick(e, dt);
     *     done &= e.finished();          // as of the previous tick when played
     * }
     * }</pre>
     *
     * <ul>
     *   <li>What it reads of the emitter in the same {@code tick} is the previous tick's.</li>
     *   <li>Nothing the emitter's modules read may change after this call within the tick: its ground's
     *       {@code fill} belongs before it.</li>
     * </ul>
     */
    protected final void tick(CgVfxEmitterInstance emitter, float dt) {
        if (system == null) {
            emitter.tick(dt, air(), originX, originY, originZ);
            return;
        }
        if (!system.particleTick()) return;
        // The same test as tickEmitters': an instance keeps the path it first stepped on.
        boolean cpu = !emitter.scheduled() && !(system.stepsOnGpu() && emitter.time() == 0f);
        emitter.share(system.spawnShare(emitter.emitter()));
        if (cpu) dueCpuParticles += emitter.particles().count();
        for (int e = 0; e < emitter.emitter().events().size(); e++) {
            CgVfxEmitterInstance child = emitter.child(e);
            if (child == null) continue;
            child.share(system.spawnShare(child.emitter()));
            if (cpu) dueCpuParticles += child.particles().count();
        }
        if (dueCount == due.length) {
            due = Arrays.copyOf(due, Math.max(4, dueCount * 2));
            dueDts = Arrays.copyOf(dueDts, due.length);
        }
        dueDts[dueCount] = dt / CgVfxSystem.TICK * system.particleDt();
        due[dueCount++] = emitter;
    }

    /** Whether {@link #tick(CgVfxEmitterInstance, float)} queued emitters this tick. */
    final boolean hasEmitterTicks() {
        return dueCount > 0;
    }

    /** Live particles of the emitters queued this tick that the CPU steps; read before {@link #tickEmitters}. */
    final int dueCpuParticles() {
        return dueCpuParticles;
    }

    /**
     * Runs the emitters queued this tick, in order: each ticked, or scheduled for its system's GPU queue once it is
     * stepped there (an instance keeps the path it first stepped on). Any thread, one at a time per effect.
     */
    final void tickEmitters() {
        CgVfxAir air = air();
        boolean gpu = system != null && system.stepsOnGpu();
        for (int i = 0; i < dueCount; i++) {
            CgVfxEmitterInstance emitter = due[i];
            if (emitter.scheduled() || gpu && emitter.time() == 0f) {
                boolean fresh = !emitter.scheduled();
                emitter.schedule(dueDts[i], originX, originY, originZ);
                if (fresh) {
                    if (admitted.length == admittedCount) admitted = Arrays.copyOf(admitted, Math.max(4, admittedCount * 2));
                    admitted[admittedCount++] = emitter;
                    if (scheduled.length == scheduledCount) scheduled = Arrays.copyOf(scheduled, Math.max(4, scheduledCount * 2));
                    scheduled[scheduledCount++] = emitter;
                }
            } else {
                emitter.tick(dueDts[i], air, originX, originY, originZ);
                if (emitter.rowsDue()) {
                    if (reporting.length == reportingCount) reporting = Arrays.copyOf(reporting, Math.max(4, reportingCount * 2));
                    reporting[reportingCount++] = emitter;
                }
            }
            due[i] = null;
        }
        dueCount = 0;
        dueCpuParticles = 0;
    }

    /** Ends the slots of every instance it scheduled, particles and all, at {@code steps}' next step. Render thread. */
    final void killSlots(CgVfxGpuSteps steps) {
        for (int i = 0; i < scheduledCount; i++) steps.kill(scheduled[i]);
    }

    /** Hands the instances {@link #tickEmitters} scheduled for the first time to {@code steps}. Render thread. */
    final void admitScheduled(CgVfxGpuSteps steps) {
        for (int i = 0; i < admittedCount; i++) {
            steps.admit(admitted[i]);
            admitted[i] = null;
        }
        admittedCount = 0;
    }

    /** Hands the event rows its CPU-stepped emitters left this tick to {@code listeners}. Render thread. */
    final void deliverRows(List<CgVfxEventListener> listeners) {
        for (int i = 0; i < reportingCount; i++) {
            reporting[i].deliverRows(listeners);
            reporting[i] = null;
        }
        reportingCount = 0;
    }

    /** Whether anything hears its moments: skip working out a moment's framing when nothing does. */
    protected final boolean momentsHeard() {
        return system != null && !seeking && system.hasMomentListeners();
    }

    /**
     * Announces that it has reached the moment {@code name}, framed by a point relative to its origin and a radius that
     * holds what matters: what a capture tool photographs. Call it once, as the moment is crossed in {@link #tick}.
     */
    protected final void moment(String name, float x, float y, float z, float radius) {
        if (momentsHeard()) system.moment(this, name, originX + x, originY + y, originZ + z, radius);
    }

    final void step(float dt) {
        values.takeTuning();
        age += dt;
        tick(dt);
    }

    /** Moves the origin, between ticks: {@link #follow}'s. */
    final void moveTo(double x, double y, double z) {
        originX = x;
        originY = y;
        originZ = z;
    }

    /** Its own values, live: what its layers read each draw. */
    public final CgVfxValues values() {
        return values;
    }
}
