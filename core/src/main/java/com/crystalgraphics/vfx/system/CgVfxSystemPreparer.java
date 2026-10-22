package com.crystalgraphics.vfx.system;

import com.crystalgraphics.api.material.CgMaterialDefinition;
import com.crystalgraphics.api.material.CgMaterialRegistry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.asset.load.CgAssetPreparer;
import com.crystalgraphics.asset.load.CgAssetRef;
import com.crystalgraphics.job.CgJob;
import com.crystalgraphics.job.CgJobLane;
import com.crystalgraphics.vfx.CgVfxSystem;
import com.crystalgraphics.vfx.look.CgVfxLook;

/**
 * A system asset's GPU work before it is {@code READY}: its layers' textures decoded on a worker and uploaded, then its
 * look prepared on a VFX system ({@link CgVfxSystem#prepare}: kernels, pools, slot meshes, every program its draws
 * take), and its pool there primed ({@link CgVfxSystem#pool}). A system played once its bundle is ready then uploads
 * and compiles nothing, and {@link CgVfxSystem#spawn} takes a primed instance.
 *
 * <pre>{@code
 * CgVfxAssets.instance().preparer(CgParticleKinds.SYSTEM, new CgVfxSystemPreparer(CgVfx::world));   // the client's
 * assets.preparer(CgParticleKinds.SYSTEM, new CgVfxSystemPreparer(() -> mySystem));                // a preview's own
 * }</pre>
 *
 * <ul>
 *   <li>It prepares on the system {@code system} answers when the step first runs, on the render thread.</li>
 *   <li>Its last step answers {@link CgVfxSystem#warmed}, the whole system's: an asset waits for everything warming
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
        CgVfxLook look = ((CgVfxSystemDefinition) definition).look();
        String name = ((CgVfxSystemDefinition) definition).name();
        plan.warm("textures of " + name, new Textures(look)::attempt);
        boolean[] started = {false};
        plan.warm("look of " + name, () -> {
            CgVfxSystem vfx = system.get();
            if (!started[0]) {
                started[0] = true;
                vfx.prepare(look);
            }
            return vfx.warmed();
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

    /** A look's layers' textures: made on the render thread, decoded on a worker, which queues their upload. */
    private static final class Textures {
        private final CgVfxLook look;
        private CompletableFuture<Void> decoded;

        Textures(CgVfxLook look) {
            this.look = look;
        }

        boolean attempt() {
            if (decoded == null) {
                // A renderer's material samples the registry's texture of each binding's path.
                Set<CgTexture> seen = Collections.newSetFromMap(new IdentityHashMap<>());
                List<CgTexture> textures = new ArrayList<>();
                for (List<CgVfxLook.Placed> drawn : List.of(look.placed())) {
                    for (CgVfxLook.Placed placed : drawn) {
                        CgMaterialDefinition d = placed.definition();
                        if (d == null) continue;
                        for (CgMaterialDefinition.Texture t : d.textures().values()) {
                            CgTexture texture = CgMaterialRegistry.texture(t.texture());
                            if (seen.add(texture)) textures.add(texture);
                        }
                    }
                }
                // Its size decodes it on the asking thread and queues the upload, which lands before the next frame.
                decoded = CompletableFuture.runAsync(() -> {
                    for (CgTexture t : textures) t.getWidth();
                }, CgJob.executor(CgJobLane.LATENCY));
                return false;
            }
            return decoded.isDone();
        }
    }
}
