package com.crystalgraphics.vfx.system;

import java.util.function.Supplier;

import com.crystalgraphics.asset.load.CgAssetPreparer;
import com.crystalgraphics.asset.load.CgAssetRef;
import com.crystalgraphics.vfx.CgVfxSystem;
import com.crystalgraphics.vfx.look.CgVfxLook;

/**
 * A system asset's GPU work before it is {@code READY}: its look precached on a VFX system
 * ({@link CgVfxSystem#precache}: kernels, pools, slot meshes, every program its draws take, its textures and meshes
 * read ahead), and its pool there primed ({@link CgVfxSystem#pool}). A system played once its bundle is ready then uploads
 * and compiles nothing, and {@link CgVfxSystem#spawn} takes a primed instance.
 *
 * <pre>{@code
 * CgVfxAssets.instance().preparer(CgParticleKinds.SYSTEM, new CgVfxSystemPreparer(CgVfx::world));   // the client's
 * assets.preparer(CgParticleKinds.SYSTEM, new CgVfxSystemPreparer(() -> mySystem));                // a preview's own
 * }</pre>
 *
 * <ul>
 *   <li>It precaches on the system {@code system} answers when the step first runs, on the render thread.</li>
 *   <li>Its last step answers {@link CgVfxSystem#ready}, the whole system's: an asset waits for everything warming
 *       there, never less.</li>
 *   <li>A dedicated server registers no preparer: a system is ready there once it compiled.</li>
 * </ul>
 */
public final class CgVfxSystemPreparer implements CgAssetPreparer {

    private final Supplier<CgVfxSystem> system;

    public CgVfxSystemPreparer(Supplier<CgVfxSystem> system) {
        this.system = system;
    }

    @Override
    public void plan(Object definition, Plan plan) {
        CgVfxSystemDefinition d = (CgVfxSystemDefinition) definition;
        planLook(d.name(), d.look(), d.fixedBounds() == null, system, plan);
    }

    /**
     * Plans {@code look}'s warming on {@code system}: its textures decoded and uploaded, then the look precached there.
     * A preparer of another kind drawing through a look (a prefab's) plans with it.
     *
     * <pre>{@code
     * CgVfxSystemPreparer.planLook(prefab.id(), prefab.look(), false, CgVfx::world, plan);
     * }</pre>
     *
     * @param refuseBounds whether a material reading the drawer's bounds fails: a system's without fixed bounds
     */
    public static void planLook(String name, CgVfxLook look, boolean refuseBounds, Supplier<CgVfxSystem> system, Plan plan) {
        boolean[] started = {false};
        plan.warm("look of " + name, () -> {
            CgVfxSystem vfx = system.get();
            if (!started[0]) {
                started[0] = true;
                // A material reading the system's bounds reads fixed ones only: refused here rather than at its first draw.
                if (refuseBounds && vfx.readsBounds(look)) {
                    throw new IllegalStateException(name + " draws a material reading its bounds (CG_OBJECT_POSITION,"
                            + " CG_OBJECT_RADIUS) and has no fixed_bounds");
                }
                vfx.precache(look);
            }
            return vfx.ready();
        });
    }

    /**
     * As {@link #plan(Object, Plan)}, then primes the ref's pool on the system with {@code pool_prime_size} instances, at
     * least one: Unreal primes {@code PoolPrimeSize} at load (0 by default), but a first play that makes its instance
     * allocates, which a READY asset never should.
     */
    @Override
    @SuppressWarnings("unchecked")   // a SYSTEM preparer's refs are refs to system definitions
    public void plan(CgAssetRef<?> ref, Object definition, Plan plan) {
        plan(definition, plan);
        CgAssetRef<CgVfxSystemDefinition> systemRef = (CgAssetRef<CgVfxSystemDefinition>) ref;
        int prime = Math.max(((CgVfxSystemDefinition) definition).poolPrimeSize(), 1);
        plan.warm("pool of " + ((CgVfxSystemDefinition) definition).name(), () -> {
            system.get().pool(systemRef).prime(prime);
            return true;
        });
    }
}
