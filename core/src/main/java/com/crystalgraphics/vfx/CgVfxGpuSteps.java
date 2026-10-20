package com.crystalgraphics.vfx;

import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.vfx.fluid.CgVfxFluid;
import com.crystalgraphics.vfx.grid.CgVfxGrid3D;
import com.crystalgraphics.vfx.particle.CgVfxAir;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.CgVfxEmitterInstance;
import com.crystalgraphics.vfx.particle.gpu.CgVfxInstanceView;
import com.crystalgraphics.vfx.particle.gpu.draw.CgVfxRange;
import com.crystalgraphics.vfx.particle.gpu.sim.CgVfxParticlePool;
import com.crystalgraphics.vfx.particle.spawn.CgVfxInactiveResponse;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * A system's GPU simulation queue (vfx-gpu §13.7): every emitter instance stepped by {@code schedule} holds a slot in
 * its shape's pool from its first step until its last particle has died, and each particle step queues one step of
 * every pool those slots are in.
 *
 * <pre>{@code
 * // each particle tick, after the emitter workers have joined; render thread
 * steps.admit(instance);            // each instance whose first schedule ran this tick
 * steps.step(particleDt, air);      // every tenant's row and spawns, then closes the finished
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

    /** One scheduled instance and its slot. */
    static final class Tenant {
        final CgVfxEmitterInstance instance;
        final CgVfxParticlePool pool;
        final int slot;
        /** Its time after the last step: still that after the workers, its effect did not step it. */
        float timeAfter;
        /** A child's parent tenant, which releases it; null for a parent. */
        final Tenant parent;
        /** Admitted by a prewarm: what {@link #prewarmStep} steps. */
        boolean warm;
        /** Its particles end in the next step, and then its slot closes. */
        boolean killed;
        /** The effect that stepped it, whose look draws it; null for an instance admitted without one (a test's). */
        @Nullable
        final CgVfxEffect<?> effect;
        /** Its renderers, made at its first drawn frame ({@link CgVfxFrame#particles}). */
        CgVfxFrame.Renderers renderers;

        private Tenant(CgVfxEmitterInstance instance, CgVfxParticlePool pool, int slot, Tenant parent,
                       @Nullable CgVfxEffect<?> effect) {
            this.instance = instance;
            this.pool = pool;
            this.slot = slot;
            this.parent = parent;
            this.effect = effect;
            timeAfter = instance.books().stepTime();
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

        public double originX() { return of.originX(); }
        public double originY() { return of.originY(); }
        public double originZ() { return of.originZ(); }
        public float time() { return of.books().stepTime(); }
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
        public float turnX() { return of.books().turnX(); }
        public float turnY() { return of.books().turnY(); }
        public float turnZ() { return of.books().turnZ(); }
        public float turnW() { return of.books().turnW(); }
        public CgVfxFluid gas() { return of.books().gas(); }
        public CgVfxGrid3D grid() { return of.books().grid(); }
    }

    private final List<Tenant> tenants = new ArrayList<>();
    private final IdentityHashMap<CgVfxEmitterInstance, Tenant> byInstance = new IdentityHashMap<>();
    /** The pools with tenants, in the order first used. */
    private final List<CgVfxParticlePool> pools = new ArrayList<>();
    private final StepView view = new StepView();

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
        admitChildren(add(instance, alive, null, owner, 0), alive, owner);
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
        Tenant tenant = new Tenant(instance, pool, pool.open(definition, capacity, owner), parent,
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

    /** The most steps any of its pools holds, queued and not yet recorded. */
    int queuedSteps() {
        int most = 0;
        for (int i = 0; i < pools.size(); i++) most = Math.max(most, pools.get(i).queuedSteps());
        return most;
    }

    /** Queues one step of {@code dt} seconds in {@code air}'s wind into every pool with tenants, then closes the finished. */
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

    /** As {@link #step}, stepping the warm tenants alone: every other slot holds still, spawning nothing. */
    void prewarmStep(float dt, CgVfxAir air) {
        step(dt, air, true);
    }

    /** Ends a prewarm: its tenants step with the rest from now on. */
    void endPrewarm() {
        warming = false;
        for (int i = 0; i < tenants.size(); i++) tenants.get(i).warm = false;
    }

    private void step(float dt, CgVfxAir air, boolean warmOnly) {
        if (tenants.isEmpty()) return;
        for (int i = 0; i < pools.size(); i++) pools.get(i).beginStep(dt, air.windX(), air.windY(), air.windZ());
        for (int i = 0; i < tenants.size(); i++) {
            Tenant tenant = tenants.get(i);
            CgVfxEmitterInstance instance = tenant.instance;
            if (tenant.parent == null && !tenant.killed && !(warmOnly && !tenant.warm)) {
                // Its effect no longer steps it: deactivated, so its emitter's inactive response applies.
                boolean coasting = instance.time() == tenant.timeAfter;
                if (coasting && instance.emitter().state().inactiveResponse() == CgVfxInactiveResponse.KILL) instance.kill();
                // Its children come after it in the list, so they see the kill this step.
                if (instance.books().takeKill()) kill(instance);
            }
            view.of = instance;
            if (tenant.killed || warmOnly && !tenant.warm) {
                tenant.pool.instance(tenant.slot, instance.books().seedBits(), instance.books().share(), instance.groundY(), view, 0f,
                        tenant.killed);
                continue;
            }
            if (instance.time() == tenant.timeAfter) instance.books().coast(dt);
            tenant.timeAfter = instance.time();
            tenant.pool.instance(tenant.slot, instance.books().seedBits(), instance.books().share(), instance.groundY(), view);
            // A fed slot spawns only what its parent's events spawn.
            if (tenant.parent == null) {
                tenant.pool.spawn(tenant.slot, instance.books().stepFirstSpawn(), instance.books().stepCandidates(),
                        instance.books().stepCandidates() - instance.books().stepBursts());
            }
        }
        view.of = null;
        for (int i = 0; i < pools.size(); i++) pools.get(i).endStep();
        CgVfxTrace.count(POOLS, pools.size());
        CgVfxTrace.count(SLOTS, tenants.size());
        for (int i = tenants.size() - 1; i >= 0; i--) {
            if (i >= tenants.size()) continue;
            Tenant tenant = tenants.get(i);
            // Killed: this step ended its particles, so its slot holds none to hand on. Started again, it asks anew.
            if (tenant.killed) {
                release(i);
                if (tenant.parent == null) tenant.instance.books().slotLost();
                continue;
            }
            // A parent finishes once its children have; a child alone may look finished before its parent spawns any.
            if (tenant.parent == null && tenant.instance.finished()) releaseWithChildren(tenant);
        }
    }

    private void releaseWithChildren(Tenant parent) {
        for (int i = tenants.size() - 1; i >= 0; i--) {
            if (descends(tenants.get(i), parent)) release(i);
        }
    }

    /**
     * Where this frame draws every pool's particles: {@code alpha} of the way between their last two steps, which are
     * {@code stepSeconds} apart. Before the world records.
     */
    void frame(float alpha, float stepSeconds) {
        for (int i = 0; i < pools.size(); i++) CgVfxRange.of(pools.get(i)).frame(alpha, stepSeconds);
    }

    /** Gives every slot back and drops its pools, their particles with them; what plays next makes new ones. Between steps. */
    void clear() {
        for (int i = tenants.size() - 1; i >= 0; i--) release(i);
        CgVfxParticlePool.release(this);
    }

    private void release(int i) {
        Tenant tenant = tenants.remove(i);
        byInstance.remove(tenant.instance);
        tenant.pool.close(tenant.slot);
        for (int k = 0; k < tenants.size(); k++) {
            if (tenants.get(k).pool == tenant.pool) return;
        }
        pools.remove(tenant.pool);
    }
}
