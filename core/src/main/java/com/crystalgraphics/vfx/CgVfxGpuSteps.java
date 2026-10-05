package com.crystalgraphics.vfx;

import com.crystalgraphics.vfx.particle.CgVfxAir;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.CgVfxEmitterInstance;
import com.crystalgraphics.vfx.particle.gpu.CgVfxInstanceView;
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
 *   <li>A tenant its effect did not step this tick (killed, or gone) coasts: no spawns, its particles still moving and
 *       ageing until they die, since a slot closed early would hand them to its next owner.</li>
 *   <li>One system steps a pool: every step carries one wind and one length for all its slots. A second system playing
 *       the same shape throws.</li>
 * </ul>
 */
final class CgVfxGpuSteps {

    /** Which system steps each pool that has tenants. */
    private static final IdentityHashMap<CgVfxParticlePool, CgVfxGpuSteps> OWNERS = new IdentityHashMap<>();

    /** One scheduled instance and its slot. */
    static final class Tenant {
        final CgVfxEmitterInstance instance;
        final CgVfxParticlePool pool;
        final int slot;
        /** Its time after the last step: still that after the workers, its effect did not step it. */
        float timeAfter;

        private Tenant(CgVfxEmitterInstance instance, CgVfxParticlePool pool, int slot) {
            this.instance = instance;
            this.pool = pool;
            this.slot = slot;
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

    /** Opens a slot for {@code instance}, whose first {@code schedule} has just run. Between steps. */
    void admit(CgVfxEmitterInstance instance) {
        if (byInstance.containsKey(instance)) return;
        CgVfxEmitter definition = instance.emitter();
        CgVfxParticlePool pool = CgVfxParticlePool.of(definition);
        CgVfxGpuSteps owner = OWNERS.get(pool);
        if (owner == null) {
            OWNERS.put(pool, this);
            pools.add(pool);
        } else if (owner != this) {
            throw new IllegalStateException(definition.name() + "'s pool " + pool.shape().key()
                    + " is stepped by another CgVfxSystem; one system steps a pool");
        }
        Tenant tenant = new Tenant(instance, pool, pool.open(definition, definition.peakAlive()));
        tenant.timeAfter = instance.stepTime();
        tenants.add(tenant);
        byInstance.put(instance, tenant);
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
            tenant.pool.spawn(tenant.slot, instance.stepFirstSpawn(), instance.stepCandidates());
        }
        view.of = null;
        for (int i = 0; i < pools.size(); i++) pools.get(i).endStep();
        for (int i = tenants.size() - 1; i >= 0; i--) {
            Tenant tenant = tenants.get(i);
            if (tenant.instance.finished()) release(i);
        }
    }

    /** Gives every slot back: the system is going, and its particles with it. Between steps. */
    void clear() {
        for (int i = tenants.size() - 1; i >= 0; i--) release(i);
    }

    private void release(int i) {
        Tenant tenant = tenants.remove(i);
        byInstance.remove(tenant.instance);
        tenant.pool.close(tenant.slot);
        for (int k = 0; k < tenants.size(); k++) {
            if (tenants.get(k).pool == tenant.pool) return;
        }
        pools.remove(tenant.pool);
        OWNERS.remove(tenant.pool);
    }
}
