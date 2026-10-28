package com.crystalgraphics.vfx.prefab;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import com.crystalgraphics.asset.load.CgAssetPreparer;
import com.crystalgraphics.job.CgJob;
import com.crystalgraphics.job.CgJobLane;
import com.crystalgraphics.vfx.CgVfxSystem;
import com.crystalgraphics.vfx.system.CgVfxSystemPreparer;

/**
 * A prefab asset's work before it is {@code READY}: its parts' meshes read on a worker, their textures decoded and
 * uploaded, then its look prepared on a VFX system, as a system's are ({@link CgVfxSystemPreparer}). A sequence spawning it once its bundle is ready
 * then uploads and compiles nothing.
 *
 * <pre>{@code
 * CgVfxAssets.instance().preparer(CgBodyKinds.PREFAB, new CgVfxPrefabPreparer(CgVfx::world));   // the client's
 * assets.preparer(CgBodyKinds.PREFAB, new CgVfxPrefabPreparer(() -> mySystem));                // a preview's own
 * }</pre>
 *
 * <ul>
 *   <li>A dedicated server registers no preparer: a prefab is ready there once it compiled.</li>
 * </ul>
 */
public final class CgVfxPrefabPreparer implements CgAssetPreparer {

    private final Supplier<CgVfxSystem> system;

    public CgVfxPrefabPreparer(Supplier<CgVfxSystem> system) {
        this.system = system;
    }

    @Override
    public void plan(Object definition, Plan plan) {
        CgVfxPrefab prefab = (CgVfxPrefab) definition;
        CompletableFuture<?>[] meshes = {null};
        // Its parts' mesh files read on a worker: a part asked for its mesh at its first draw would read it there.
        plan.warm("meshes of " + prefab.id(), () -> {
            if (meshes[0] == null) {
                meshes[0] = CompletableFuture.runAsync(() -> {
                    for (int k = 0; k < prefab.firstPart(prefab.nodes()); k++) prefab.partMesh(k);
                }, CgJob.executor(CgJobLane.LATENCY));
            }
            return meshes[0].isDone();
        });
        CgVfxSystemPreparer.planLook(prefab.id(), prefab.look(), false, system, plan);
    }
}
