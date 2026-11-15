package com.crystalgraphics.vfx.prefab;

import java.util.function.Supplier;

import com.crystalgraphics.asset.load.CgAssetPreparer;
import com.crystalgraphics.render.scene.prefab.CgPrefab;
import com.crystalgraphics.vfx.CgVfxSystem;
import com.crystalgraphics.vfx.system.CgVfxSystemPreparer;

/**
 * A prefab asset's work before it is {@code READY}: its parts' meshes read on a worker ({@code plan.preload}), their
 * textures decoded, then its look precached on a VFX system, as a system's are ({@link CgVfxSystemPreparer}). A
 * sequence spawning it once its bundle is ready then reads, uploads and compiles nothing.
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
        CgPrefab prefab = (CgPrefab) definition;
        // Its parts' mesh files read on a worker: a part asked for its mesh at its first draw would read it there.
        plan.preload(l -> {
            for (int k = 0; k < prefab.firstPart(prefab.nodes()); k++) {
                int part = k;
                l.read(() -> prefab.partMesh(part));
            }
            for (int n = 0; n < prefab.nodes(); n++) {
                CgPrefab.Skeletal skeletal = prefab.skeletal(n);
                if (skeletal != null) l.read(skeletal::mesh);
            }
        });
        CgVfxSystemPreparer.planLook(prefab.id(), CgVfxPrefabLook.of(prefab).look(), false, system, plan);
    }
}
