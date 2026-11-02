package com.crystalgraphics.api.material;

import com.crystalgraphics.asset.load.CgAssetPreparer;

/**
 * A material asset's textures decoded on a worker before it is {@code READY}, as Unreal loads a material's textures
 * with it: a character drawn in its kit materials, or a system rehearsing them, binds textures already uploaded rather
 * than decoding each at its first draw on the render thread.
 *
 * <pre>{@code
 * assets.preparer(CgLookKinds.MATERIAL, new CgMaterialPreparer());   // CgVfx.preparers registers it
 * }</pre>
 *
 * <ul>
 *   <li>Textures only: a material's programs depend on what draws it (a mesh, a skinned mesh, a particle), which its
 *       drawer precaches.</li>
 *   <li>A dedicated server registers none: it decodes no texture.</li>
 * </ul>
 */
public final class CgMaterialPreparer implements CgAssetPreparer {

    @Override
    public void plan(Object definition, Plan plan) {
        if (!(definition instanceof CgMaterialDefinition material) || material.textures().isEmpty()) return;
        plan.preload(l -> {
            for (CgMaterialDefinition.Texture t : material.textures().values()) l.texture(t.resolve());
        });
    }
}
