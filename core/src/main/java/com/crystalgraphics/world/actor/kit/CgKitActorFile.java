package com.crystalgraphics.world.actor.kit;

import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.loading.task.CgLoadPlan;
import com.crystalgraphics.platform.service.CgGameDirectory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
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
 *   clips: [anims/Hero/BAS, anims/Hero/BTL]   # optional: its clips' folders, what the animation demo plays on it
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
                CgKitActor.Spec spec = spec(name, m, null);
                if (spec != null) out.put(name, spec);
            }
        } catch (IOException | RuntimeException ex) {
            LOGGER.warn("[actors] {} did not read: {}", file, ex.toString());
        }
        return out;
    }

    /**
     * The character a kit folder carries in its own {@code actor.yaml}: the same keys as an {@code actors.yaml} entry
     * but {@code kit}, which is the folder itself. Null, logged, where it has none or it does not read.
     *
     * <pre>{@code
     * # <kit>/actor.yaml
     * name: Hero
     * namespace: herokit
     * shading: mystudio
     * mesh: models/Hero.glb
     * skeleton: skeletons/Hero_Skeleton.yaml
     * prefab: prefabs/Hero_BP.yaml
     * clips: [anims/Hero/BAS]
     * }</pre>
     */
    @Nullable
    public static CgKitActor.Spec readActor(Path kit) {
        Path file = kit.resolve("actor.yaml");
        if (!Files.isRegularFile(file)) return null;
        try {
            if (!(CgAssetYaml.read(Files.readString(file)) instanceof Map<?, ?> m)) {
                LOGGER.warn("[actors] {}: not a map", file);
                return null;
            }
            return spec(m.get("name") == null ? kit.getFileName().toString() : String.valueOf(m.get("name")), m, kit);
        } catch (IOException | RuntimeException ex) {
            LOGGER.warn("[actors] {} did not read: {}", file, ex.toString());
            return null;
        }
    }

    /** An entry's spec, {@code kit} its folder where given (else its {@code kit} key); null, logged, where a key is missing. */
    @Nullable
    private static CgKitActor.Spec spec(String name, Map<?, ?> m, @Nullable Path kit) {
        String[] v = new String[KEYS.length];
        for (int i = 0; i < KEYS.length; i++) v[i] = m.get(KEYS[i]) == null ? null : String.valueOf(m.get(KEYS[i]));
        if (kit != null) v[0] = "";
        for (int i = 0; i < KEYS.length; i++) {
            if (v[i] == null) {
                LOGGER.warn("[actors] {}: no {}", name, KEYS[i]);
                return null;
            }
        }
        // Kept as given: a mounted namespace's folder may be inside a jar, which a string loses
        return new CgKitActor.Spec(name, kit != null ? kit : Paths.get(v[0]), v[1], v[2], v[3], v[4], v[5],
                m.get("outline") == null ? null : String.valueOf(m.get("outline")),
                m.get("stencil") instanceof Number n ? n.intValue() : 0, clips(m.get("clips")));
    }

    /** {@code clips:}, one folder or a list of them; none where absent. */
    private static List<String> clips(@Nullable Object value) {
        if (value == null) return List.of();
        if (!(value instanceof List<?> list)) return List.of(String.valueOf(value));
        List<String> out = new ArrayList<>();
        for (Object o : list) out.add(String.valueOf(o));
        return out;
    }
}
