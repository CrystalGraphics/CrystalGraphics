package com.crystalgraphics.render.scene.prefab;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialDefinition;
import com.crystalgraphics.asset.load.CgAssetPreparer;
import com.crystalgraphics.render.post.CgPostRenderer;
import com.crystalgraphics.render.post.fog.CgHeightFogPass;
import com.crystalgraphics.render.post.settings.CgPostBlendable;

/**
 * A prefab made ready before it is {@code READY}: its post nodes' materials decoded on a worker and their passes built,
 * the composite's passes for their looks, and the fog's pass where a node is a fog, so the frame it is spawned loads and
 * compiles nothing.
 *
 * <pre>{@code
 * assets.preparer(CgBodyKinds.PREFAB, new CgPrefabPostPreparer());   // CgVfx.preparers registers it,
 * assets.preparer(CgStageKinds.STAGE, new CgPrefabPostPreparer());   // and for stages, which compile to prefabs
 * }</pre>
 */
public final class CgPrefabPostPreparer implements CgAssetPreparer {

    @Override
    public void plan(Object definition, Plan plan) {
        if (!(definition instanceof CgPrefab prefab)) return;
        Set<CgMaterialDefinition> materials = Collections.newSetFromMap(new IdentityHashMap<>());
        boolean posts = false, fog = false;
        for (int i = 0; i < prefab.nodes(); i++) {
            CgPrefab.Post post = prefab.post(i);
            if (post != null) {
                posts = true;
                for (CgPostBlendable b : post.materials()) if (b.material() != null) materials.add(b.material());
            }
            fog |= prefab.fog(i) != null;
        }
        if (!posts && !fog) return;
        boolean withFog = fog;
        plan.pipelines(p -> {
            for (int i = 0; i < prefab.nodes(); i++) {
                CgPrefab.Post post = prefab.post(i);
                if (post != null) CgPostRenderer.lookPasses(post.settings(), p);
            }
            if (withFog) p.addPass(CgHeightFogPass.SHADER);
            for (CgMaterialDefinition m : materials) p.addPass(CgMaterial.of(m));
        });
        if (materials.isEmpty()) return;
        plan.preload(l -> {
            for (CgMaterialDefinition m : materials) {
                for (CgMaterialDefinition.Texture t : m.textures().values()) l.texture(t.resolve());
            }
        });
    }
}
