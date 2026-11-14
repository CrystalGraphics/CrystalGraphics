package com.crystalgraphics.world.actor.kit;

import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.loading.task.CgLoadPlan;
import com.crystalgraphics.platform.service.CgGameDirectory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The characters a dev install can wear, from {@code <game folder>/crystalgraphics/actors.yaml}: each key a name for
 * {@code /cgc actor <name>}, each value a {@link CgKitActor.Spec}. Read on each ask, so an edit applies at once.
 *
 * <pre>{@code
 * hero:
 *   kit: D:/kits/mykit              # the kit's folder
 *   namespace: mykit                # how its assets are named
 *   shading: mystudio               # the namespace its materials name their shading models by
 *   mesh: models/Hero.glb           # the rest are under the kit
 *   skeleton: skeletons/Hero_Skeleton.yaml
 *   prefab: prefabs/Hero_BP.yaml
 *   outline: mykit:Renderer/Outline   # optional: a kit material drawn as the engine's outline
 *   stencil: 1                        # and the custom depth stencil value it reads the character by
 * }</pre>
 *
 * <ul>
 *   <li>A malformed entry is logged and left out.</li>
 *   <li>Every character in it loads behind the loading screen ({@link #preload}), so asking for one draws it at once;
 *       one added while the game runs loads at its first ask.</li>
 * </ul>
 */
public final class CgKitActorFile {

    private static final Logger LOGGER = LogManager.getLogger(CgKitActorFile.class);
    private static final String[] KEYS = {"kit", "namespace", "shading", "mesh", "skeleton", "prefab"};

    private CgKitActorFile() {
    }

    /**
     * Loads every character of the file as the load task {@code crystalgraphics:actors}: its files on a worker, then its
     * kit's materials, until each is ready or failed. The engine's client start names it.
     */
    public static void preload(CgLoadPlan plan) {
        AtomicReference<List<CgKitActor.Spec>> specs = new AtomicReference<>();
        boolean[] started = {false};
        plan.task("crystalgraphics:actors").work(() -> specs.set(List.copyOf(read().values()))).render(task -> {
            List<CgKitActor.Spec> all = specs.get();
            if (!started[0]) {
                started[0] = true;
                all.forEach(CgKitActor::preload);
            }
            int settled = 0;
            for (CgKitActor.Spec s : all) if (CgKitActor.settled(s)) settled++;
            if (settled == all.size()) return true;
            task.progress(settled / (float) all.size());
            task.nextFrame();
            return false;
        }).start();
    }

    /** The file's characters by name; empty where there is no file. */
    public static Map<String, CgKitActor.Spec> read() {
        Map<String, CgKitActor.Spec> out = new LinkedHashMap<>();
        Path file = CgGameDirectory.game().resolve("crystalgraphics").resolve("actors.yaml");
        if (!Files.isRegularFile(file)) return out;
        try {
            Object tree = CgAssetYaml.read(Files.readString(file));
            if (!(tree instanceof Map<?, ?> top)) return out;
            for (Map.Entry<?, ?> e : top.entrySet()) {
                String name = String.valueOf(e.getKey());
                if (!(e.getValue() instanceof Map<?, ?> m)) {
                    LOGGER.warn("[actors] {}: not a map", name);
                    continue;
                }
                String[] v = new String[KEYS.length];
                for (int i = 0; i < KEYS.length; i++) v[i] = m.get(KEYS[i]) == null ? null : String.valueOf(m.get(KEYS[i]));
                String missing = null;
                for (int i = 0; i < KEYS.length && missing == null; i++) if (v[i] == null) missing = KEYS[i];
                if (missing != null) {
                    LOGGER.warn("[actors] {}: no {}", name, missing);
                    continue;
                }
                out.put(name, new CgKitActor.Spec(name, Paths.get(v[0]), v[1], v[2], v[3], v[4], v[5],
                        m.get("outline") == null ? null : String.valueOf(m.get("outline")),
                        m.get("stencil") instanceof Number n ? n.intValue() : 0));
            }
        } catch (IOException | RuntimeException ex) {
            LOGGER.warn("[actors] {} did not read: {}", file, ex.toString());
        }
        return out;
    }
}
