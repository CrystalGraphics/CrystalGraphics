package com.crystalgraphics.render.post.volume;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialDefinition;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.asset.load.CgAssetPreparer;
import com.crystalgraphics.job.CgJob;
import com.crystalgraphics.job.CgJobLane;
import com.crystalgraphics.render.post.CgPostStack;
import com.crystalgraphics.render.world.CgPipelinePrecache;
import com.crystalgraphics.sequence.definition.CgSequenceDefinition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * A sequence asset's post materials made ready before it is {@code READY}: every post volume it spawns, in it and in
 * every sequence it plays, its blendables' textures decoded on a worker and their passes requested through
 * {@code CgPipelinePrecache}, and the composite's passes for its looks ({@code CgPostStack.lookPasses}), so the frame
 * its cut starts loads and compiles nothing. Unreal loads a level sequence's post-process materials with it.
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
        collect(sequence, materials, looks, Collections.newSetFromMap(new IdentityHashMap<>()));
        if (!looks.isEmpty()) {
            // The composite's variant for each volume's looks: a chromatic fringe first shown at its cut compiled there.
            CgPipelinePrecache.Request[] requested = new CgPipelinePrecache.Request[1];
            plan.warm("post looks of " + sequence.id(), () -> {
                if (requested[0] == null) {
                    requested[0] = CgPipelinePrecache.request(p -> {
                        for (CgPostSettings s : looks) CgPostStack.get().lookPasses(s, p);
                    });
                }
                return requested[0].ready();
            });
        }
        if (materials.isEmpty()) return;

        CompletableFuture<?>[] decoded = new CompletableFuture<?>[1];
        plan.warm("post textures of " + sequence.id(), () -> {
            if (decoded[0] == null) {
                List<CgTexture> textures = new ArrayList<>();
                for (CgMaterialDefinition m : materials) {
                    for (CgMaterialDefinition.Texture t : m.textures().values()) textures.add(t.resolve());
                }
                // Its size decodes it on the asking thread and queues the upload, which lands before the next frame.
                decoded[0] = CompletableFuture.runAsync(() -> {
                    for (CgTexture t : textures) t.getWidth();
                }, CgJob.executor(CgJobLane.LATENCY));
            }
            return decoded[0].isDone();
        });

        CgPipelinePrecache.Request[] passes = new CgPipelinePrecache.Request[1];
        plan.warm("post materials of " + sequence.id(), () -> {
            if (passes[0] == null) {
                passes[0] = CgPipelinePrecache.request(p -> {
                    for (CgMaterialDefinition m : materials) p.addPass(CgMaterial.newInstance(m, null));
                });
            }
            return passes[0].ready();
        });
    }

    /**
     * Every post material {@code sequence}'s post volumes blend and each volume's settings, and those of each sequence
     * it plays, once each.
     */
    private static void collect(CgSequenceDefinition sequence, Set<CgMaterialDefinition> into, List<CgPostSettings> looks,
                                Set<CgSequenceDefinition> seen) {
        if (!seen.add(sequence)) return;
        for (CgSequenceDefinition.Spawnable s : sequence.spawnables()) {
            if (s.tree().get("post") instanceof Map<?, ?> block) looks.add(CgPostBlock.settings(block));
            if (!(s.definition() instanceof List<?> blendables)) continue;
            for (Object o : blendables) {
                if (o instanceof CgSequenceDefinition.Blendable b && b.material() != null) into.add(b.material());
            }
        }
        for (CgSequenceDefinition.Child c : sequence.children()) collect(c.definition(), into, looks, seen);
    }
}
