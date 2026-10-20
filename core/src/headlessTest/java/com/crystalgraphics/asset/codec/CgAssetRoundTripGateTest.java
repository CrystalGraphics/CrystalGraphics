package com.crystalgraphics.asset.codec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import com.crystalgraphics.asset.kind.CgAssetKinds;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.object.CgType;

import static org.junit.Assert.assertTrue;

/**
 * O3's gate (blueprint 01): every file of the kits round-trips YAML → tree → YAML to identical bytes, and every child
 * resolves over its parent with a delta that writes back to its file.
 *
 * <p>Runs over {@code -Dcrystalgraphics.kits=<namespace>=<dir>;...}, which {@code -Pkits=...} hands the build; skipped
 * without it, since the kits are private and canonical only once REA's CO1 rewrites them.</p>
 */
public class CgAssetRoundTripGateTest {

    private static final Map<String, Path> FILES = new HashMap<>();
    private static final Map<String, CgType> KINDS = new HashMap<>();
    private static final List<Path> ALL = new ArrayList<>();

    @BeforeClass
    public static void kits() throws IOException {
        String kits = System.getProperty("crystalgraphics.kits");
        Assume.assumeTrue("no -Pkits: the round-trip gate did not run", kits != null && !kits.isEmpty());
        CgAssetKinds.register();
        for (String kit : kits.split(";")) {
            String[] nd = kit.split("=", 2);
            index(nd[0], Paths.get(nd[1]));
        }
    }

    @Test
    public void everyFileRoundTripsToIdenticalBytes() throws IOException {
        List<String> bad = new ArrayList<>();
        for (Path p : ALL) {
            String text = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            String again;
            try {
                again = CgAssetYaml.canonical(p.toString(), text);
            } catch (RuntimeException e) {
                bad.add(p + ": " + e.getMessage());
                continue;
            }
            if (!again.equals(text)) bad.add(bad.size() < 20 ? p + "\n" + firstDiff(text, again) : p.toString());
        }
        assertTrue(bad.size() + " of " + ALL.size() + " files are not their own bytes:\n" + String.join("\n",
                bad.subList(0, Math.min(20, bad.size()))), bad.isEmpty());
    }

    @Test
    public void everyChildsDeltaWritesBackToItsFile() throws IOException {
        Map<String, CgAssetData> loaded = new HashMap<>();
        List<String> bad = new ArrayList<>();
        int children = 0;
        for (Map.Entry<String, Path> e : FILES.entrySet()) {
            Map<?, ?> tree = tree(e.getValue());
            if (!(tree.get(CgType.PARENT) instanceof String)) continue;
            children++;
            try {
                CgAssetData data = load(e.getKey(), loaded, new HashSet<String>());
                CgType kind = KINDS.get(e.getKey());
                if (!CgAssetYaml.write(tree, kind).equals(CgAssetYaml.write(data.delta(), kind))) {
                    bad.add(e.getKey() + ": its delta is not its file");
                }
            } catch (RuntimeException ex) {
                bad.add(e.getKey() + ": " + ex.getMessage());
            }
        }
        assertTrue(bad.size() + " of " + children + " children:\n" + String.join("\n",
                bad.subList(0, Math.min(20, bad.size()))), bad.isEmpty());
    }

    private static CgAssetData load(String id, Map<String, CgAssetData> loaded, Set<String> loading) throws IOException {
        CgAssetData done = loaded.get(id);
        if (done != null) return done;
        Path file = FILES.get(id);
        if (file == null) throw new IllegalStateException("its parent " + id + " is not in the kits");
        if (!loading.add(id)) throw new IllegalStateException("a parent cycle through " + id);
        Map<?, ?> tree = tree(file);
        Object parent = tree.get(CgType.PARENT);
        CgAssetData data = CgAssetData.of(id, KINDS.get(id), tree,
                parent instanceof String ? load((String) parent, loaded, loading) : null);
        loaded.put(id, data);
        return data;
    }

    private static Map<?, ?> tree(Path file) throws IOException {
        return (Map<?, ?>) CgAssetYaml.read(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
    }

    private static void index(String namespace, Path root) throws IOException {
        List<Path> all;
        try (Stream<Path> s = Files.walk(root)) {
            all = s.filter(p -> p.toString().endsWith(".yaml")).sorted().collect(Collectors.toList());
        }
        for (Path p : all) {
            ALL.add(p);
            String rel = root.relativize(p).toString().replace('\\', '/');
            CgType kind = CgAssetYaml.kindOf(rel);
            if (kind == null || !rel.startsWith(kind.folder() + "/")) continue;
            String id = namespace + ":" + rel.substring(kind.folder().length() + 1, rel.length() - ".yaml".length());
            FILES.put(id, p);
            KINDS.put(id, kind);
        }
    }

    private static String firstDiff(String want, String got) {
        String[] a = want.split("\n", -1), b = got.split("\n", -1);
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            String x = i < a.length ? a[i] : "<none>", y = i < b.length ? b[i] : "<none>";
            if (!x.equals(y)) return "  line " + (i + 1) + "\n  file:   " + x + "\n  writer: " + y;
        }
        return "  (same lines)";
    }
}
