package com.crystalgraphics.api.material;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.asset.kind.CgAssetKinds;
import com.crystalgraphics.asset.kind.CgLookKinds;
import com.crystalgraphics.asset.tree.CgAssetData;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * M7's gate: every kit material on an archetype the engine has ported compiles against it with its keywords, values
 * and textures. Runs over {@code -Pkits=<namespace>=<dir>;...}; skipped without, since the kits are private.
 */
public class CgArchetypeKitGateTest {

    /** The archetypes written so far, in census order (M7.1 on). A new one joins here. */
    static final Set<String> BUILT = new HashSet<>(Arrays.asList("cc2_effect", "cc2_toon", "cc2_outline",
            "cc2_scene_distortion", "cc2_scene_luma_ramp", "cc2_water", "cc2_surface", "cc2_scene_channel_ramp",
            "cc2_scene_tint"));

    private static final Map<String, Path> FILES = new HashMap<>();
    private static final Map<String, CgAssetData> RESOLVED = new HashMap<>();

    @BeforeClass
    public static void kits() throws IOException {
        String kits = System.getProperty("crystalgraphics.kits");
        Assume.assumeTrue("no -Pkits: the archetype gate did not run", kits != null && !kits.isEmpty());
        CgAssetKinds.register();
        for (String kit : kits.split(";")) {
            String[] nd = kit.split("=", 2);
            Path root = Paths.get(nd[1], "materials");
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(p -> p.toString().endsWith(".yaml")).forEach(p -> {
                    String rel = root.relativize(p).toString().replace('\\', '/');
                    FILES.put(nd[0] + ":" + rel.substring(0, rel.length() - ".yaml".length()), p);
                });
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static CgAssetData resolve(String id) throws IOException {
        CgAssetData had = RESOLVED.get(id);
        if (had != null) return had;
        Path file = FILES.get(id);
        if (file == null) throw new IOException("no material " + id + " in the kits");
        Map<Object, Object> tree = (Map<Object, Object>) CgAssetYaml.read(
                new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        Object parent = tree.get("parent");
        CgAssetData data = CgAssetData.of(id, CgLookKinds.MATERIAL, tree,
                parent instanceof String ? resolve((String) parent) : null);
        RESOLVED.put(id, data);
        return data;
    }

    @Test
    public void everyMaterialOnABuiltArchetypeCompiles() throws IOException {
        int compiled = 0;
        List<String> failed = new ArrayList<>();
        for (String id : FILES.keySet()) {
            CgAssetData asset = resolve(id);
            Object shader = asset.resolved().get("shader");
            if (!(shader instanceof String)) continue;
            String name = (String) shader;
            name = name.substring(name.lastIndexOf('/') + 1).replace(".shader", "");
            if (!BUILT.contains(name)) continue;
            try {
                CgMaterialDefinitions.compile(asset, CgMaterialArchetypes.FILES);
                compiled++;
            } catch (RuntimeException e) {
                failed.add(e.getMessage());
            }
        }
        System.out.println("[archetype gate] " + BUILT + ": " + compiled + " compiled, " + failed.size() + " failed");
        assertTrue("no material on " + BUILT + " in the kits", compiled + failed.size() > 0);
        assertEquals(failed.size() > 0 ? failed.subList(0, Math.min(20, failed.size())).toString() : "", 0,
                failed.size());
    }
}
