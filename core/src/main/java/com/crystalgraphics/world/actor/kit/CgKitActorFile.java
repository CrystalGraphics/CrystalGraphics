package com.crystalgraphics.world.actor.kit;

import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.platform.service.CgGameDirectory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

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
 *   clips: anims/hero
 *   skeleton: skeletons/Hero_Skeleton.yaml
 *   prefab: prefabs/Hero_BP.yaml
 * }</pre>
 *
 * <p>A malformed entry is logged and left out.</p>
 */
public final class CgKitActorFile {

    private static final Logger LOGGER = LogManager.getLogger(CgKitActorFile.class);
    private static final String[] KEYS = {"kit", "namespace", "shading", "mesh", "clips", "skeleton", "prefab"};

    private CgKitActorFile() {
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
                out.put(name, new CgKitActor.Spec(name, Paths.get(v[0]), v[1], v[2], v[3], v[4], v[5], v[6]));
            }
        } catch (IOException | RuntimeException ex) {
            LOGGER.warn("[actors] {} did not read: {}", file, ex.toString());
        }
        return out;
    }
}
