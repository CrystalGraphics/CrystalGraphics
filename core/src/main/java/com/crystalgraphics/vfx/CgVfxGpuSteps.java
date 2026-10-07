package com.crystalgraphics.vfx;

import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.vfx.particle.CgVfxAir;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.CgVfxEmitterInstance;
import com.crystalgraphics.vfx.particle.gpu.CgVfxInstanceView;
import com.crystalgraphics.vfx.particle.gpu.draw.CgVfxRange;
import com.crystalgraphics.vfx.particle.gpu.sim.CgVfxParticlePool;

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

        private Tenant(CgVfxEmitterInstance instance, CgVfxParticlePool pool, int slot, Tenant parent) {
            this.instance = instance;
            this.pool = pool;
            this.slot = slot;
            this.parent = parent;
            timeAfter = instance.stepTime();
        }
    }

    /** The view a pool's row reads: the instance, at its step's start. */
    private static final class StepView implements CgVfxInstanceView {
        CgVfxEmitterInstance of;

        public double originX() { return of.originX(); }
        public double originY() { return of.originY(); }
        public double originZ() { return of.originZ(); }
        public float time() { return of.stepTime(); }
        public float sourceX() { return of.sourceX(); }
        public float sourceY() { return of.sourceY(); }
        public float sourceZ() { return of.sourceZ(); }
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
        if (byInstance.containsKey(instance)) return;
        CgVfxEmitter definition = instance.emitter();
        Tenant tenant = add(instance, definition.peakAlive(), null);
        for (int e = 0; e < definition.events().size(); e++) {
            CgVfxEmitterInstance child = instance.child(e);
            if (child == null) continue;
            Tenant fed = add(child, definition.peakChildren(e), tenant);
            fed.pool.feed(fed.slot, tenant.pool, tenant.slot, e);
        }
    }

    private Tenant add(CgVfxEmitterInstance instance, int capacity, Tenant parent) {
        CgVfxEmitter definition = instance.emitter();
        CgVfxParticlePool pool = CgVfxParticlePool.of(this, definition);
        if (!pools.contains(pool)) pools.add(pool);
        Tenant tenant = new Tenant(instance, pool, pool.open(definition, capacity), parent);
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

    /** The most steps any of its pools holds, queued and not yet recorded. */
    int queuedSteps() {
        int most = 0;
        for (int i = 0; i < pools.size(); i++) most = Math.max(most, pools.get(i).queuedSteps());
        return most;
    }

    /** Queues one step of {@code dt} seconds in {@code air}'s wind into every pool with tenants, then closes the finished. */
    void step(float dt, CgVfxAir air) {
        if (tenants.isEmpty()) return;
        for (int i = 0; i < pools.size(); i++) pools.get(i).beginStep(dt, air.windX(), air.windY(), air.windZ());
        for (int i = 0; i < tenants.size(); i++) {
            Tenant tenant = tenants.get(i);
            CgVfxEmitterInstance instance = tenant.instance;
            if (instance.time() == tenant.timeAfter) instance.coast(dt);
            tenant.timeAfter = instance.time();
            view.of = instance;
            tenant.pool.instance(tenant.slot, instance.seedBits(), instance.share(), instance.groundY(), view);
            // A fed slot spawns only what its parent's events spawn.
            if (tenant.parent == null) tenant.pool.spawn(tenant.slot, instance.stepFirstSpawn(), instance.stepCandidates());
        }
        view.of = null;
        for (int i = 0; i < pools.size(); i++) pools.get(i).endStep();
        CgVfxTrace.count(POOLS, pools.size());
        CgVfxTrace.count(SLOTS, tenants.size());
        for (int i = tenants.size() - 1; i >= 0; i--) {
            Tenant tenant = tenants.get(i);
            // A parent finishes once its children have; a child alone may look finished before its parent spawns any.
            if (tenant.parent == null && tenant.instance.finished()) releaseWithChildren(tenant);
        }
    }

    private void releaseWithChildren(Tenant parent) {
        for (int i = tenants.size() - 1; i >= 0; i--) {
            Tenant tenant = tenants.get(i);
            if (tenant == parent || tenant.parent == parent) release(i);
        }
    }

    /**
     * Where this frame draws every pool's particles: {@code alpha} of the way between their last two steps, spin pushed
     * {@code aheadSeconds} on, as the CPU path writes its records. Before the world records.
     */
    void frame(float alpha, float aheadSeconds) {
        for (int i = 0; i < pools.size(); i++) CgVfxRange.of(pools.get(i)).frame(alpha, aheadSeconds);
    }

    /** Gives every slot back and drops its pools: the system is going, and its particles with it. Between steps. */
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
