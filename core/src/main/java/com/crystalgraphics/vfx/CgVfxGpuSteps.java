package com.crystalgraphics.vfx;

import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.vfx.fluid.CgVfxFluid;
import com.crystalgraphics.vfx.grid.CgVfxGrid3D;
import com.crystalgraphics.vfx.look.CgVfxParam;
import com.crystalgraphics.vfx.look.CgVfxValues;
import com.crystalgraphics.vfx.particle.CgVfxAir;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.CgVfxEmitterInstance;
import com.crystalgraphics.vfx.particle.gpu.CgVfxInstanceView;
import com.crystalgraphics.vfx.particle.gpu.sim.CgVfxParticlePool;
import com.crystalgraphics.vfx.particle.spawn.CgVfxInactiveResponse;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * A system's GPU simulation queue: every emitter instance stepped by {@code schedule} holds a slot in its shape's pool
 * from its first step until its last particle has died. The CPU books every 120 Hz tick; the GPU steps once a frame
 * over the frame's particle time, each tick's births placed at their own offset within it (Niagara's GPU emitters step
 * once a frame at the frame's dt, {@code NiagaraEmitterInstance.cpp}'s {@code Tick}).
 *
 * <pre>{@code
 * // each tick, after the emitter workers have joined; render thread
 * steps.admit(instance);            // each instance whose first schedule ran this tick
 * steps.book(ticks * TICK);         // the tick's spawns, at its offset into the frame's step
 * // once an update, after its ticks
 * steps.step(ticks * TICK, air);    // every tenant's row and the frame's spawns, then closes the finished
 * }</pre>
 *
 * <ul>
 *   <li>Each event that spawns children gets a slot of its own in the child's pool, fed by the parent's slot, opened and
 *       released with it.</li>
 *   <li>A tenant its effect did not step this tick (killed, or gone) coasts: no spawns, its particles still moving and
 *       ageing until they die, since a slot closed early would hand them to its next owner.</li>
 *   <li>An effect starting at an age admits its instances warm and steps them alone ({@link #prewarmStep}), every
 *       other slot held still, then {@link #endPrewarm}.</li>
 * </ul>
 */
final class CgVfxGpuSteps {

    /** Per step: pools stepped, each one Step dispatch however many slots it holds, and the slots stepped in them. */
    private static final int POOLS = CgTrace.name("vfx.gpu.pools-stepped"), SLOTS = CgTrace.name("vfx.gpu.slots-stepped");

    /** One scheduled instance and its slot; given out again from {@link #idle} once released, so a play makes none. */
    static final class Tenant {
        CgVfxEmitterInstance instance;
        CgVfxParticlePool pool;
        int slot;
        /** Its time after the last step: still that after the workers, its effect did not step it. */
        float timeAfter;
        /** A child's parent tenant, which releases it; null for a parent. */
        Tenant parent;
        /** Admitted by a prewarm: what {@link #prewarmStep} steps. */
        boolean warm;
        /** Its particles end in the next step, and then its slot closes. */
        boolean killed;
        /** The effect that stepped it, whose look draws it; null for an instance admitted without one (a test's). */
        @Nullable
        CgVfxEffect<?> effect;
        /**
         * Its renderers, made at its first drawn frame ({@link CgVfxFrame#particles}) and kept through its reuse, built
         * again for each tenancy: a trails renderer reads its slot's ring.
         */
        CgVfxFrame.Renderers renderers;
        /** Its time as this frame's step began; NaN before its first book in the frame. */
        float stepTime;
        /** Its rotation's change over this frame's ticks, a quaternion: what a local slot's live particles turn by. */
        float turnX, turnY, turnZ, turnW;

        private void open(CgVfxEmitterInstance instance, CgVfxParticlePool pool, int slot, Tenant parent,
                          @Nullable CgVfxEffect<?> effect) {
            this.instance = instance;
            this.pool = pool;
            this.slot = slot;
            this.parent = parent;
            this.effect = effect;
            timeAfter = instance.books().stepTime();
            warm = killed = false;
            stepTime = Float.NaN;
            turnX = turnY = turnZ = 0f;
            turnW = 1f;
            if (renderers != null) renderers.stale(instance.emitter());
        }

        /** Drops what it pointed at, so an idle tenant holds no effect alive. */
        private void close() {
            instance = null;
            pool = null;
            parent = null;
            effect = null;
        }

        /**
         * Its step's share of the pool's time: its effect's pace (0 holds its particles still and unaged), 1 for one
         * admitted without an effect and for a prewarm's.
         */
        float timeRate(boolean prewarm) {
            if (parent != null) return parent.timeRate(prewarm);
            return effect == null || prewarm ? 1f : effect.timeRate();
        }

        /** Whether it is drawn: it and every instance feeding it are {@link CgVfxEmitterInstance#drawn()}. */
        boolean drawn() {
            for (Tenant t = this; t != null; t = t.parent) if (!t.instance.drawn()) return false;
            return true;
        }
    }

    /** What {@link #admit} marks warm: set while an effect prewarms. */
    private boolean warming;

    /**
     * The view a pool's row reads: the instance, at its step's start. It forwards every method, defaults too: one left
     * to its default hands the pool nothing (no gas, no grid) without a word.
     */
    private static final class StepView implements CgVfxInstanceView {
        CgVfxEmitterInstance of;
        Tenant tenant;
        /** Its effect's values, which its user bindings read; null for one admitted without an effect. */
        @Nullable CgVfxValues values;

        public double originX() { return of.originX(); }
        public double originY() { return of.originY(); }
        public double originZ() { return of.originZ(); }
        public float time() { return Float.isNaN(tenant.stepTime) ? of.books().stepTime() : tenant.stepTime; }
        public float sourceX() { return of.sourceX(); }
        public float sourceY() { return of.sourceY(); }
        public float sourceZ() { return of.sourceZ(); }
        public float fromX() { return of.books().fromX(); }
        public float fromY() { return of.books().fromY(); }
        public float fromZ() { return of.books().fromZ(); }
        public float boxX() { return of.books().boxX(); }
        public float boxY() { return of.books().boxY(); }
        public float boxZ() { return of.books().boxZ(); }
        public float boxHalfX() { return of.books().boxHalfX(); }
        public float boxHalfY() { return of.books().boxHalfY(); }
        public float boxHalfZ() { return of.books().boxHalfZ(); }
        public float rotationX() { return of.books().rotationX(); }
        public float rotationY() { return of.books().rotationY(); }
        public float rotationZ() { return of.books().rotationZ(); }
        public float rotationW() { return of.books().rotationW(); }
        public float turnX() { return tenant.turnX; }
        public float turnY() { return tenant.turnY; }
        public float turnZ() { return tenant.turnZ; }
        public float turnW() { return tenant.turnW; }
        public float loopSeconds() { return of.books().loopSeconds(); }
        public CgVfxFluid gas() { return of.books().gas(); }
        public CgVfxGrid3D grid() { return of.books().grid(); }
        public float user(CgVfxParam.Scalar param) { return values == null ? param.defaultValue() : values.get(param); }

        public float cameraDistance() { return tenant.effect == null ? 0f : tenant.effect.cullDistance; }

        public float user(String name, int component, float fallback) {
            CgVfxParam p = values == null ? null : values.layout().param(name);
            if (p instanceof CgVfxParam.Scalar s) return values.get(s);
            if (p instanceof CgVfxParam.Vector v) return values.get(v, component);
            if (p instanceof CgVfxParam.Color c) return values.get(c, component);
            return fallback;
        }
    }

    private final List<Tenant> tenants = new ArrayList<>();
    /** Released tenants, given out again by {@link #add}. */
    private Tenant[] idle = new Tenant[16];
    private int idleCount;
    private final IdentityHashMap<CgVfxEmitterInstance, Tenant> byInstance = new IdentityHashMap<>();
    /** The pools with tenants, in the order first used. */
    private final List<CgVfxParticlePool> pools = new ArrayList<>();
    private final StepView view = new StepView();
    /**
     * This frame's booked spawns, a CPU tick's each: its tenant, (first, candidates, bursts), (born, from xyz, to xyz,
     * the loop's seconds at the tick's start and end).
     */
    private static final int PENDING_FLOATS = 9;
    private Tenant[] pendingTenant = new Tenant[0];
    private int[] pendingInts = new int[0];
    private float[] pendingFloats = new float[0];
    private int pending;

    /**
     * Opens a slot for {@code instance}, whose first {@code schedule} has just run, and one for each event's children,
     * fed by its slot. Between steps.
     */
    void admit(CgVfxEmitterInstance instance) {
        admit(instance, null);
    }

    /**
     * As {@link #admit(CgVfxEmitterInstance)}, its slots and its children's reporting rows as {@code owner}'s. Children's
     * children too: each in the pool of its feed tier, after its parent in the list.
     */
    void admit(CgVfxEmitterInstance instance, Object owner) {
        if (byInstance.containsKey(instance)) return;
        int alive = instance.emitter().peakAlive();
        CgVfxEmitterInstance source = instance.source();
        if (source == null) {
            admitChildren(add(instance, alive, null, owner, 0), alive, owner);
            return;
        }
        if (source == instance) {
            // Born on its own particles: its own tier, reading what it published the step before.
            Tenant self = add(instance, alive, null, owner, 0);
            self.pool.readFrom(self.slot, self.pool, self.slot);
            admitChildren(self, alive, owner);
            return;
        }
        // A reader steps the tier after its source, which publishes its particles: admitted first.
        admit(source, owner);
        Tenant from = byInstance.get(source);
        Tenant reader = add(instance, alive, null, owner, from.pool.shape().tier() + 1);
        reader.pool.readFrom(reader.slot, from.pool, from.slot);
        admitChildren(reader, alive, owner);
    }

    private void admitChildren(Tenant tenant, int alive, Object owner) {
        CgVfxEmitter definition = tenant.instance.emitter();
        int tier = tenant.pool.shape().tier() + 1;
        for (int e = 0; e < definition.events().size(); e++) {
            CgVfxEmitterInstance child = tenant.instance.child(e);
            if (child == null) continue;
            int capacity = tenant.parent == null ? definition.peakChildren(e) : definition.peakChildren(e, alive);
            Tenant fed = add(child, capacity, tenant, owner, tier);
            fed.pool.feed(fed.slot, tenant.pool, tenant.slot, e);
            admitChildren(fed, capacity, owner);
        }
    }

    private Tenant add(CgVfxEmitterInstance instance, int capacity, Tenant parent, Object owner, int tier) {
        CgVfxEmitter definition = instance.emitter();
        CgVfxParticlePool pool = CgVfxParticlePool.of(this, definition, tier);
        if (!pools.contains(pool)) pools.add(pool);
        Tenant tenant = idleCount > 0 ? idle[--idleCount] : new Tenant();
        idle[idleCount] = null;
        tenant.open(instance, pool, pool.open(definition, capacity, owner), parent,
                owner instanceof CgVfxEffect<?> effect ? effect : null);
        tenant.warm = warming;
        tenants.add(tenant);
        byInstance.put(instance, tenant);
        return tenant;
    }

    /** {@code instance}'s tenancy, or null when it holds no slot. */
    Tenant of(CgVfxEmitterInstance instance) {
        return byInstance.get(instance);
    }

    boolean isEmpty() {
        return tenants.isEmpty();
    }

    int tenants() {
        return tenants.size();
    }

    /** Every tenant, parents before the children they feed, in the order admitted: what the frame draws. */
    List<Tenant> tenantList() {
        return tenants;
    }

    /** The most steps any of its pools holds, queued and not yet recorded: one a frame, more only while prewarming. */
    int queuedSteps() {
        int most = 0;
        for (int i = 0; i < pools.size(); i++) most = Math.max(most, pools.get(i).queuedSteps());
        return most;
    }

    /**
     * Books this tick for every tenant: each one its effect did not step coasts, kills land, and the tick's spawns are
     * kept for the frame's step at {@code at} seconds into it. Every tick, after admission.
     */
    void book(float at) {
        book(at, false);
    }

    /** Queues the frame's one step of {@code dt} seconds in {@code air}'s wind into every pool with tenants, then closes the finished. */
    void step(float dt, CgVfxAir air) {
        step(dt, air, false);
    }

    /**
     * Ends {@code instance}'s particles and its children's in the next step, firing no event, and closes their slots after
     * it. Nothing when it holds no slot.
     */
    void kill(CgVfxEmitterInstance instance) {
        Tenant parent = byInstance.get(instance);
        if (parent == null) return;
        for (int i = 0; i < tenants.size(); i++) {
            Tenant tenant = tenants.get(i);
            if (descends(tenant, parent)) tenant.killed = true;
        }
    }

    /** Whether {@code tenant} is {@code root} or fed by it, at any depth. */
    private static boolean descends(Tenant tenant, Tenant root) {
        for (Tenant t = tenant; t != null; t = t.parent) if (t == root) return true;
        return false;
    }

    /** Starts marking what {@link #admit} opens warm: the instances of an effect starting at an age. */
    void beginPrewarm() {
        warming = true;
    }

    /** As {@link #book}, booking the warm tenants alone. */
    void prewarmBook(float at) {
        book(at, true);
    }

    /** As {@link #step}, stepping the warm tenants alone: every other slot holds still, spawning nothing. */
    void prewarmStep(float dt, CgVfxAir air) {
        step(dt, air, true);
    }

    /** Ends a prewarm: its tenants step with the rest from now on. */
    void endPrewarm() {
        warming = false;
        for (int i = 0; i < tenants.size(); i++) tenants.get(i).warm = false;
    }

    /**
     * Moves every slot whose instance took a newer emitter ({@code CgVfxEmitterInstance.adopt}, a hot reload's
     * {@code VALUE} swap) to that emitter's numbers, its renderers made again. Between steps.
     */
    void rebind() {
        for (int i = 0; i < tenants.size(); i++) {
            Tenant tenant = tenants.get(i);
            CgVfxEmitter now = tenant.instance.emitter();
            if (tenant.pool.emitter(tenant.slot) == now) continue;
            tenant.pool.rebind(tenant.slot, now);
            if (tenant.renderers != null) tenant.renderers.stale(now);
        }
    }

    private void book(float at, boolean warmOnly) {
        for (int i = 0; i < tenants.size(); i++) {
            Tenant tenant = tenants.get(i);
            CgVfxEmitterInstance instance = tenant.instance;
            float scale = tenant.timeRate(warmOnly);
            if (tenant.parent == null && !tenant.killed && scale > 0f && !(warmOnly && !tenant.warm)) {
                // Its effect no longer steps it: deactivated, so its emitter's inactive response applies.
                boolean coasting = instance.time() == tenant.timeAfter;
                if (coasting && instance.emitter().state().inactiveResponse() == CgVfxInactiveResponse.KILL) instance.kill();
                // Its children come after it in the list, so they see the kill this tick.
                if (instance.books().takeKill()) kill(instance);
            }
            if (tenant.killed || warmOnly && !tenant.warm || scale == 0f) continue;
            float began;
            if (instance.time() == tenant.timeAfter) {
                began = instance.time();
                instance.books().coast(CgVfxSystem.TICK * scale);
            } else {
                began = instance.books().stepTime();
            }
            tenant.timeAfter = instance.time();
            if (Float.isNaN(tenant.stepTime)) tenant.stepTime = began;
            turn(tenant, instance.books());
            // A fed slot spawns only what its parent's events spawn.
            if (tenant.parent == null && instance.books().stepCandidates() > 0) keep(tenant, at);
        }
    }

    /** {@code tenant}'s frame turn, this tick's change composed after the ticks' before it. */
    private static void turn(Tenant t, CgVfxEmitterInstance.Books b) {
        float ax = b.turnX(), ay = b.turnY(), az = b.turnZ(), aw = b.turnW();
        float x = aw * t.turnX + ax * t.turnW + ay * t.turnZ - az * t.turnY;
        float y = aw * t.turnY - ax * t.turnZ + ay * t.turnW + az * t.turnX;
        float z = aw * t.turnZ + ax * t.turnY - ay * t.turnX + az * t.turnW;
        float w = aw * t.turnW - ax * t.turnX - ay * t.turnY - az * t.turnZ;
        t.turnX = x;
        t.turnY = y;
        t.turnZ = z;
        t.turnW = w;
    }

    /** Keeps {@code tenant}'s booked tick for the frame's step, born from {@code at} seconds into it. */
    private void keep(Tenant tenant, float at) {
        if (pending == pendingTenant.length) {
            int n = Math.max(16, pending * 2);
            pendingTenant = Arrays.copyOf(pendingTenant, n);
            pendingInts = Arrays.copyOf(pendingInts, n * 3);
            pendingFloats = Arrays.copyOf(pendingFloats, n * PENDING_FLOATS);
        }
        CgVfxEmitterInstance.Books b = tenant.instance.books();
        pendingTenant[pending] = tenant;
        int i = pending * 3, f = pending * PENDING_FLOATS;
        pendingInts[i] = b.stepFirstSpawn();
        pendingInts[i + 1] = b.stepCandidates();
        pendingInts[i + 2] = b.stepBursts();
        pendingFloats[f] = at;
        pendingFloats[f + 1] = b.fromX();
        pendingFloats[f + 2] = b.fromY();
        pendingFloats[f + 3] = b.fromZ();
        pendingFloats[f + 4] = tenant.instance.sourceX();
        pendingFloats[f + 5] = tenant.instance.sourceY();
        pendingFloats[f + 6] = tenant.instance.sourceZ();
        pendingFloats[f + 7] = b.stepLoopFrom();
        pendingFloats[f + 8] = b.stepLoopTo();
        pending++;
    }

    private void step(float dt, CgVfxAir air, boolean warmOnly) {
        if (tenants.isEmpty()) {
            dropPending();
            return;
        }
        for (int i = 0; i < pools.size(); i++) pools.get(i).beginStep(dt, air.windX(), air.windY(), air.windZ());
        for (int i = 0; i < tenants.size(); i++) {
            Tenant tenant = tenants.get(i);
            CgVfxEmitterInstance instance = tenant.instance;
            float scale = tenant.timeRate(warmOnly);
            view.of = instance;
            view.tenant = tenant;
            view.values = tenant.effect == null ? null : tenant.effect.values();
            boolean held = tenant.killed || warmOnly && !tenant.warm || scale == 0f;
            tenant.pool.instance(tenant.slot, instance.books().seedBits(), instance.books().share(), instance.groundY(), view,
                    held ? 0f : scale, tenant.killed);
            tenant.stepTime = Float.NaN;
            tenant.turnX = tenant.turnY = tenant.turnZ = 0f;
            tenant.turnW = 1f;
        }
        view.of = null;
        view.tenant = null;
        view.values = null;
        for (int k = 0; k < pending; k++) {
            Tenant tenant = pendingTenant[k];
            if (!tenant.killed && byInstance.get(tenant.instance) == tenant) {
                int i = k * 3, f = k * PENDING_FLOATS;
                tenant.pool.spawn(tenant.slot, pendingInts[i], pendingInts[i + 1], pendingInts[i + 2], pendingFloats[f],
                        CgVfxSystem.TICK, pendingFloats[f + 1], pendingFloats[f + 2], pendingFloats[f + 3],
                        pendingFloats[f + 4], pendingFloats[f + 5], pendingFloats[f + 6], pendingFloats[f + 7], pendingFloats[f + 8]);
            }
        }
        dropPending();
        for (int i = 0; i < pools.size(); i++) pools.get(i).endStep();
        CgVfxTrace.count(POOLS, pools.size());
        CgVfxTrace.count(SLOTS, tenants.size());
        for (int i = tenants.size() - 1; i >= 0; i--) {
            if (i >= tenants.size()) continue;
            Tenant tenant = tenants.get(i);
            // Killed: this step ended its particles, so its slot holds none to hand on. Started again, it asks anew.
            if (tenant.killed) {
                CgVfxEmitterInstance instance = tenant.parent == null ? tenant.instance : null;
                release(i);
                if (instance != null) instance.books().slotLost();
                continue;
            }
            // A parent finishes once its children have; a child alone may look finished before its parent spawns any.
            if (tenant.parent == null && tenant.instance.finished()) releaseWithChildren(tenant);
        }
    }

    private void dropPending() {
        Arrays.fill(pendingTenant, 0, pending, null);
        pending = 0;
    }

    private void releaseWithChildren(Tenant parent) {
        for (int i = tenants.size() - 1; i >= 0; i--) {
            if (descends(tenants.get(i), parent)) release(i);
        }
    }

    /** Drops every pool's queued step unrecorded: tests, which have no world to record it. */
    void discardQueued() {
        for (int i = 0; i < pools.size(); i++) pools.get(i).discardQueued();
    }

    /** Gives every slot back and drops its pools, their particles with them; what plays next makes new ones. Between steps. */
    void clear() {
        dropPending();
        for (int i = tenants.size() - 1; i >= 0; i--) release(i);
        CgVfxParticlePool.release(this);
    }

    private void release(int i) {
        Tenant tenant = tenants.remove(i);
        byInstance.remove(tenant.instance);
        CgVfxParticlePool pool = tenant.pool;
        pool.close(tenant.slot);
        tenant.close();
        if (idleCount == idle.length) idle = Arrays.copyOf(idle, Math.max(16, idleCount * 2));
        idle[idleCount++] = tenant;
        for (int k = 0; k < tenants.size(); k++) {
            if (tenants.get(k).pool == pool) return;
        }
        pools.remove(pool);
    }
}
