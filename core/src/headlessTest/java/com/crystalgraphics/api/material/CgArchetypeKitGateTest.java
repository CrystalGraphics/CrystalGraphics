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
 * Every kit material on an archetype the engine has ported compiles against it with its keywords, values and textures.
 * Runs over {@code -Pkits=<namespace>=<dir>;...}; skipped without, since the kits are private. A kit's own programs
 * resolve only with {@code -PkitRoots} naming a folder that holds them as {@code assets/<kit>/shaders}, and a kit's
 * shading models only with {@code -PkitShading=<namespace>=<kit dir>;...}.
 */
public class CgArchetypeKitGateTest {

    /** The archetypes written so far, in census order. A new one joins here. */
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
        String shading = System.getProperty("crystalgraphics.kits.shading");
        if (shading != null) {
            for (String pair : shading.split(";")) {
                if (pair.isBlank()) continue;
                CgShadingModels.registerKit(pair.substring(0, pair.indexOf('=')), Paths.get(pair.substring(pair.indexOf('=') + 1)));
            }
        }
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

    /**
     * Every kit material compiles, or fails with a message naming it and what it lacks (a property, a keyword, or an
     * archetype not yet written). None fails silently or without its id. Prints the failures by kit and cause.
     */
    @Test
    public void everyKitMaterialCompilesOrFailsNamingWhatItLacks() throws IOException {
        int compiled = 0, unbuilt = 0;
        List<String> unnamed = new ArrayList<>();
        Map<String, Integer> causes = new HashMap<>();
        for (String id : FILES.keySet()) {
            try {
                CgMaterialDefinitions.compile(resolve(id), CgMaterialArchetypes.FILES);
                compiled++;
            } catch (RuntimeException e) {
                String message = String.valueOf(e.getMessage());
                if (!message.contains(id)) unnamed.add(id + ": " + message);
                else unbuilt++;
                String cause = id.substring(0, id.indexOf(':')) + " " + message.replace(id, "<it>");
                causes.merge(cause.length() > 220 ? cause.substring(0, 220) : cause, 1, Integer::sum);
            }
        }
        System.out.println("[material gate] " + FILES.size() + " kit materials: " + compiled + " compiled, " + unbuilt
                + " failed naming what they lack, " + unnamed.size() + " failed without their id");
        causes.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(25)
                .forEach(c -> System.out.println("[material gate]   " + c.getValue() + " x " + c.getKey()));
        assertEquals(unnamed.size() > 0 ? unnamed.subList(0, Math.min(20, unnamed.size())).toString() : "", 0,
                unnamed.size());
    }

    /**
     * Every material a list names compiles, none excused: a move's closure, under {@code -PkitMaterials=<file>}, one id
     * a line. Skipped without.
     */
    @Test
    public void everyListedMaterialCompiles() throws IOException {
        String list = System.getProperty("crystalgraphics.kits.materials");
        Assume.assumeTrue("no -PkitMaterials: the listed-material gate did not run", list != null && !list.isBlank());
        List<String> ids = Files.readAllLines(Paths.get(list), StandardCharsets.UTF_8).stream()
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        List<String> failed = new ArrayList<>();
        for (String id : ids) {
            try {
                CgMaterialDefinitions.compile(resolve(id), CgMaterialArchetypes.FILES);
            } catch (RuntimeException | IOException e) {
                failed.add(id + ": " + e.getMessage());
            }
        }
        System.out.println("[listed gate] " + ids.size() + " materials: " + (ids.size() - failed.size()) + " compiled");
        assertTrue("an empty list: " + list, !ids.isEmpty());
        assertTrue(failed.size() + " of " + ids.size() + " failed:\n" + String.join("\n", failed), failed.isEmpty());
    }
}
