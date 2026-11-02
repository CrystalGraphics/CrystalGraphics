package com.crystalgraphics.render.post.volume;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialDefinition;
import com.crystalgraphics.asset.load.CgAssetPreparer;
import com.crystalgraphics.render.post.CgPostStack;
import com.crystalgraphics.sequence.definition.CgSequenceDefinition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A sequence asset's post materials made ready before it is {@code READY}: every post volume it spawns, in it and in
 * every sequence it plays, its blendables' textures decoded on a worker ({@code plan.preload}) and their passes built
 * through the precache ({@code plan.pipelines}), and the composite's passes for its looks
 * ({@code CgPostStack.lookPasses}), so the frame its cut starts loads and compiles nothing. Unreal loads a level
 * sequence's post-process materials with it.
 *
 * <pre>{@code
 * assets.preparer(CgSequenceKinds.SEQUENCE, new CgPostVolumePreparer());
 * }</pre>
 *
 * <ul>
 *   <li>Each play still makes its own material instance ({@link CgPostVolumeSpawner}); their programs are the shader
 *       asset's, shared, so the instance requested here warms every later one.</li>
 *   <li>A dedicated server registers none: it draws no post material.</li>
 * </ul>
 */
public final class CgPostVolumePreparer implements CgAssetPreparer {

    @Override
    public void plan(Object definition, Plan plan) {
        if (!(definition instanceof CgSequenceDefinition sequence)) return;
        Set<CgMaterialDefinition> materials = Collections.newSetFromMap(new IdentityHashMap<>());
        List<CgPostSettings> looks = new ArrayList<>();
        List<String[]> regions = new ArrayList<>();
        collect(sequence, materials, looks, regions, Collections.newSetFromMap(new IdentityHashMap<>()));
        // Each colour region's program: a flash first shown at its hit compiled there.
        if (!regions.isEmpty()) plan.pipelines(p -> {
            for (String[] keywords : regions) p.addPass(CgColourRegion.SHADER, keywords);
        });
        // The composite's variant for each volume's looks: a chromatic fringe first shown at its cut compiled there.
        if (!looks.isEmpty()) plan.pipelines(p -> {
            for (CgPostSettings s : looks) CgPostStack.get().lookPasses(s, p);
        });
        if (materials.isEmpty()) return;
        plan.preload(l -> {
            for (CgMaterialDefinition m : materials) {
                for (CgMaterialDefinition.Texture t : m.textures().values()) l.texture(t.resolve());
            }
        });
        plan.pipelines(p -> {
            for (CgMaterialDefinition m : materials) p.addPass(CgMaterial.newInstance(m, null));
        });
    }

    /**
     * Every post material {@code sequence}'s post volumes blend and each volume's settings, and those of each sequence
     * it plays, once each.
     */
    private static void collect(CgSequenceDefinition sequence, Set<CgMaterialDefinition> into, List<CgPostSettings> looks,
                                List<String[]> regions, Set<CgSequenceDefinition> seen) {
        if (!seen.add(sequence)) return;
        for (CgSequenceDefinition.Spawnable s : sequence.spawnables()) {
            if (s.tree().get("post") instanceof Map<?, ?> block) looks.add(CgPostBlock.settings(block));
            if (s.tree().get("colour_region") instanceof Map<?, ?> region) {
                String[] keywords = CgColourRegion.keywords(region);
                if (keywords != null) regions.add(keywords);
            }
            if (!(s.definition() instanceof List<?> blendables)) continue;
            for (Object o : blendables) {
                if (o instanceof CgSequenceDefinition.Blendable b && b.material() != null) into.add(b.material());
            }
        }
        for (CgSequenceDefinition.Child c : sequence.children()) collect(c.definition(), into, looks, regions, seen);
    }
}
