package com.crystalgraphics.render.post.stage;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialDefinition;
import com.crystalgraphics.asset.load.CgAssetPreparer;
import com.crystalgraphics.render.post.CgPostStack;
import com.crystalgraphics.render.post.fog.CgHeightFog;
import com.crystalgraphics.render.post.volume.CgPostBlendable;

/**
 * A stage asset made ready before it is {@code READY}: its post materials' textures decoded on a worker and their
 * passes built, the composite's passes for its volumes' looks, and the fog's pass, so the frame it is placed loads and
 * compiles nothing.
 *
 * <pre>{@code
 * assets.preparer(CgStageKinds.STAGE, new CgStagePreparer());   // CgVfx.preparers registers it
 * }</pre>
 */
public final class CgStagePreparer implements CgAssetPreparer {

    @Override
    public void plan(Object definition, Plan plan) {
        if (!(definition instanceof CgStageDefinition stage)) return;
        Set<CgMaterialDefinition> materials = Collections.newSetFromMap(new IdentityHashMap<>());
        for (CgStageDefinition.Volume v : stage.volumes()) {
            for (CgPostBlendable b : v.blendables()) if (b.material() != null) materials.add(b.material());
        }
        plan.pipelines(p -> {
            for (CgStageDefinition.Volume v : stage.volumes()) CgPostStack.get().lookPasses(v.settings(), p);
            if (stage.fog() != null) p.addPass(CgHeightFog.SHADER);
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
