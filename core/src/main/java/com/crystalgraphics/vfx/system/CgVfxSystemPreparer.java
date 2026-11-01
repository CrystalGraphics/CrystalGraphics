package com.crystalgraphics.vfx.system;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.crystalgraphics.asset.load.CgAssetPreparer;
import com.crystalgraphics.asset.load.CgAssetRef;
import com.crystalgraphics.render.graph.CgRehearsal;
import com.crystalgraphics.vfx.CgVfx;
import com.crystalgraphics.vfx.CgVfxSystem;
import com.crystalgraphics.vfx.look.CgVfxLook;
import org.joml.Vector3d;

/**
 * A system asset's work before it is {@code READY}: its look precached on a VFX system
 * ({@link CgVfxSystem#precache}: kernels, pools, slot meshes, every program its draws take, its textures and meshes
 * read ahead), its pool there primed ({@link CgVfxSystem#pool}), and a ref's system rehearsed ({@link CgVfx#rehearse}).
 * A system played once its bundle is ready then uploads, compiles and JIT-warms nothing, and {@link CgVfxSystem#spawn}
 * takes a primed instance.
 *
 * <pre>{@code
 * CgVfxAssets.instance().preparer(CgParticleKinds.SYSTEM, new CgVfxSystemPreparer(CgVfx::world));   // the client's
 * assets.preparer(CgParticleKinds.SYSTEM, new CgVfxSystemPreparer(() -> mySystem));                // a preview's own
 * }</pre>
 *
 * <ul>
 *   <li>It precaches on the system {@code system} answers when the step first runs, on the render thread.</li>
 *   <li>Its precache step answers {@link CgVfxSystem#warm}, the whole system's: an asset waits for everything warming
 *       there, never less. Its rehearsal joins the system's last one waiting, so a bundle's systems rehearse
 *       together.</li>
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
            return vfx.warm();
        });
    }

    /**
     * As {@link #plan(Object, Plan)}, then primes the ref's pool on the system with {@code pool_prime_size} instances, at
     * least one: Unreal primes {@code PoolPrimeSize} at load (0 by default), but a first play that makes its instance
     * allocates, which a READY asset never should. Then rehearses it ({@link CgVfx#rehearse}): spawned from its ref
     * in a hidden system, as a play would, until the JIT has settled on it, so its first play runs no cold code.
     */
    @Override
    @SuppressWarnings("unchecked")   // a SYSTEM preparer's refs are refs to system definitions
    public void plan(CgAssetRef<?> ref, Object definition, Plan plan) {
        plan(definition, plan);
        CgAssetRef<CgVfxSystemDefinition> systemRef = (CgAssetRef<CgVfxSystemDefinition>) ref;
        String name = ((CgVfxSystemDefinition) definition).name();
        int prime = Math.max(((CgVfxSystemDefinition) definition).poolPrimeSize(), 1);
        plan.warm("pool of " + name, () -> {
            system.get().pool(systemRef).prime(prime);
            return true;
        });
        plan.warm("rehearsal of " + name, new Rehearse(systemRef));
    }

    /** A system's rehearsal step: asked for at its first attempt, done once the rehearsal it joined is. */
    private final class Rehearse implements BooleanSupplier {
        private final CgAssetRef<CgVfxSystemDefinition> ref;
        private final Vector3d at = new Vector3d();
        private boolean asked;
        @Nullable
        private CgRehearsal rehearsal;

        Rehearse(CgAssetRef<CgVfxSystemDefinition> ref) {
            this.ref = ref;
        }

        @Override
        public boolean getAsBoolean() {
            if (!asked) {
                asked = true;
                rehearsal = CgVfx.rehearse(system.get(), hidden -> {
                    CgVfx.rehearsalPoint(at, null);
                    hidden.spawn(ref, at.x, at.y, at.z);
                });
            }
            return rehearsal == null || rehearsal.done();
        }
    }
}
