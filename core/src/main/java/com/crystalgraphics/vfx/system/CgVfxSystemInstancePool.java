package com.crystalgraphics.vfx.system;

import com.crystalgraphics.asset.load.CgAssetFollower;
import com.crystalgraphics.asset.load.CgAssetRef;

import javax.annotation.Nullable;
import java.util.Arrays;

/**
 * One system's idle instances, taken instead of made, so playing it again allocates nothing: Unreal's
 * {@code FNCPool}. A system played often (a hit spark, a muzzle flash) plays from its pool; its system keeps one per
 * definition or ref and primes it when the asset is ready ({@link #prime}).
 *
 * <pre>{@code
 * CgVfxSystemInstance<?> spark = vfx.spawn(SPARK_REF, x, y, z);   // its system's pool for the ref: taken or made
 * spark.ground(floorY);                                            // as on an instance made by new
 * // once it completes and its particles are gone, its system hands it back here
 * }</pre>
 *
 * <pre>{@code
 * CgVfxSystemInstancePool pool = vfx.pool(SPARK_REF);
 * pool.prime(4);                                                   // four made ahead: what a READY asset does
 * }</pre>
 *
 * <ul>
 *   <li>A pool keeps at most its definition's {@code max_pool_size} idle ({@link CgVfxSystemDefinition#maxPoolSize}),
 *       read as each comes back: one past it is dropped, as Unreal's {@code Reclaim} destroys it.</li>
 *   <li>An instance taken is a fresh one in every way a caller sees: its age, state, values, seed, execution state
 *       and emitters start over, and its behaviour is made anew. Hold nothing of it after it completes.</li>
 *   <li>Taking and handing back are safe on any thread; playing what was taken is the system's.</li>
 * </ul>
 */
public final class CgVfxSystemInstancePool {

    /** The definition its instances play, or null for a ref's pool. */
    @Nullable
    private final CgVfxSystemDefinition definition;
    /** The ref its instances follow, or null for a definition's pool. */
    @Nullable
    private final CgAssetRef<CgVfxSystemDefinition> ref;
    private CgVfxSystemInstance<?>[] free = new CgVfxSystemInstance<?>[4];
    private int count;

    /** A pool of {@code definition}'s instances. */
    public CgVfxSystemInstancePool(CgVfxSystemDefinition definition) {
        this.definition = definition;
        this.ref = null;
    }

    /** A pool of instances following {@code ref}, each taking its newer definitions as one made from it does. */
    public CgVfxSystemInstancePool(CgAssetRef<CgVfxSystemDefinition> ref) {
        this.definition = null;
        this.ref = ref;
    }

    /**
     * An idle instance started again at {@code (x, y, z)}, or a new one when none is idle: Unreal's {@code Acquire}. Hand
     * it to a system to play.
     */
    @SuppressWarnings("unchecked")
    public synchronized <B extends CgVfxBehaviour> CgVfxSystemInstance<B> acquire(double x, double y, double z) {
        CgVfxSystemInstance<?> taken = count > 0 ? free[--count] : make();
        free[count] = null;
        taken.reuse(x, y, z);
        return (CgVfxSystemInstance<B>) taken;
    }

    /**
     * Makes instances until {@code n} are idle, never past its definition's {@code max_pool_size}: Unreal's
     * {@code PrimePool}. Loads a ref's definition if it has not loaded ({@link CgAssetRef#get}).
     */
    public synchronized void prime(int n) {
        int most = Math.min(n, current().maxPoolSize());
        while (count < most) keep(make());
    }

    /** How many are idle. */
    public synchronized int idle() {
        return count;
    }

    /** Takes {@code instance} back, its system having dropped it: kept unless {@code max_pool_size} are idle already. */
    synchronized void reclaim(CgVfxSystemInstance<?> instance) {
        if (count < instance.definition().maxPoolSize()) keep(instance);
    }

    private void keep(CgVfxSystemInstance<?> instance) {
        if (count == free.length) free = Arrays.copyOf(free, count * 2);
        free[count++] = instance;
    }

    private CgVfxSystemInstance<?> make() {
        return ref == null ? new CgVfxSystemInstance<>(this, definition, null)
                : new CgVfxSystemInstance<>(this, ref.get(), new CgAssetFollower<>(ref));
    }

    private CgVfxSystemDefinition current() {
        return ref == null ? definition : ref.get();
    }
}
