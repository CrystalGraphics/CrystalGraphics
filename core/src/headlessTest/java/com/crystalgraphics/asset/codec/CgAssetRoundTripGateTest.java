package com.crystalgraphics.asset.codec;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import com.crystalgraphics.asset.kind.CgAssetKinds;
import com.crystalgraphics.asset.registry.CgAssetEntry;
import com.crystalgraphics.asset.registry.CgAssetRegistry;
import com.crystalgraphics.asset.registry.CgAssetSource;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.asset.tree.CgEdit;
import com.crystalgraphics.asset.tree.CgRandomEdits;
import com.crystalgraphics.object.CgType;
import com.crystalgraphics.object.validate.CgIssue;
import com.crystalgraphics.object.validate.CgValidation;

import static org.junit.Assert.assertTrue;

/**
 * Every file of the kits round-trips YAML → tree → YAML to identical bytes, and every child
 * resolves over its parent with a delta that writes back to its file.
 *
 * <p>Runs over {@code -Dcrystalgraphics.kits=<namespace>=<dir>;...}, which {@code -Pkits=...} hands the build; skipped
 * without it, since the kits are private.</p>
 */
public class CgAssetRoundTripGateTest {

    /** By {@code kind|id}: an id is unique within its kind, so a cue and a sequence may share one. */
    private static final Map<String, Path> FILES = new HashMap<>();
    private static final Map<String, CgType> KINDS = new HashMap<>();
    private static final Map<String, String> IDS = new HashMap<>();
    private static final List<Path> ALL = new ArrayList<>();
    private static final Map<String, Path> KITS = new LinkedHashMap<>();

    @BeforeClass
    public static void kits() throws IOException {
        String kits = System.getProperty("crystalgraphics.kits");
        Assume.assumeTrue("no -Pkits: the round-trip gate did not run", kits != null && !kits.isEmpty());
        CgAssetKinds.register();
        for (String kit : kits.split(";")) {
            String[] nd = kit.split("=", 2);
            KITS.put(nd[0], Paths.get(nd[1]));
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
                    bad.add(IDS.get(e.getKey()) + ": its delta is not its file");
                }
            } catch (RuntimeException ex) {
                bad.add(IDS.get(e.getKey()) + ": " + ex.getMessage());
            }
        }
        assertTrue(bad.size() + " of " + children + " children:\n" + String.join("\n",
                bad.subList(0, Math.min(20, bad.size()))), bad.isEmpty());
    }

    /** Random edits on every 25th asset, undone in full, give its original bytes. */
    @Test
    public void randomEditsUndoneGiveTheOriginalBytes() throws IOException {
        Map<String, CgAssetData> loaded = new HashMap<>();
        List<String> bad = new ArrayList<>();
        List<String> ids = new ArrayList<>(FILES.keySet());
        Collections.sort(ids);
        int checked = 0;
        for (int i = 0; i < ids.size(); i += 25) {
            String id = ids.get(i);
            CgAssetData data;
            try {
                data = load(id, loaded, new HashSet<String>());
            } catch (RuntimeException unloadable) {
                continue;   // the delta test names it
            }
            CgType kind = KINDS.get(id);
            String before = CgAssetYaml.write(data.delta(), kind);
            try {
                List<CgEdit> undo = CgRandomEdits.run(data, new Random(i), 30);
                for (int u = undo.size() - 1; u >= 0; u--) data.apply(undo.get(u));
                if (!before.equals(CgAssetYaml.write(data.delta(), kind))) bad.add(id + ": not its bytes after undo");
            } catch (RuntimeException ex) {
                bad.add(id + ": " + ex);
            }
            checked++;
        }
        assertTrue(bad.size() + " of " + checked + " assets:\n" + String.join("\n", bad.subList(0, Math.min(20, bad.size()))),
                bad.isEmpty());
    }

    /** Every kit's every asset registers from its listing alone, each id once; this classpath is a dedicated server's. */
    @Test
    public void everyIdRegisters() {
        CgAssetRegistry registry = new CgAssetRegistry();
        for (Map.Entry<String, Path> kit : KITS.entrySet()) registry.scan(kit.getKey(), kit.getValue(), CgAssetSource.DEV);
        List<String> missing = new ArrayList<>();
        for (String key : FILES.keySet()) if (registry.entry(KINDS.get(key), IDS.get(key)) == null) missing.add(key);
        List<String> extra = new ArrayList<>();
        for (String ns : KITS.keySet()) {
            for (CgAssetEntry e : registry.entries(ns)) if (!e.binary() && !FILES.containsKey(key(e.kind(), e.id()))) extra.add(e.file().toString());
        }
        assertTrue(missing.size() + " of " + FILES.size() + " assets unregistered: " + missing.subList(0, Math.min(20, missing.size())),
                missing.isEmpty());
        assertTrue(extra.size() + " registered that are not assets: " + extra.subList(0, Math.min(20, extra.size())), extra.isEmpty());
    }

    /** Every asset that resolves validates with no error; the report counts them by code, then lists the first. */
    @Test
    public void everyAssetValidates() throws IOException {
        Set<String> parents = new HashSet<>();
        for (Path p : FILES.values()) {
            String parent = parentOf(p);
            if (parent != null) parents.add(parent);
        }
        // A parent's key: its child's kind and the id it names.
        Set<String> parentKeys = new HashSet<>();
        for (String key : FILES.keySet()) if (parents.contains(IDS.get(key))) parentKeys.add(key);
        Map<String, CgAssetData> loaded = new HashMap<>();
        Map<String, Integer> byWhat = new TreeMap<>();
        Map<String, String> first = new HashMap<>();
        int checked = 0, errors = 0;
        List<String> ids = new ArrayList<>(FILES.keySet());
        Collections.sort(ids);
        for (String id : ids) {
            CgAssetData data;
            try {
                data = load(id, loaded, new HashSet<String>());
            } catch (RuntimeException unloadable) {
                continue;   // the delta test names it
            }
            if (!parentKeys.contains(id)) loaded.remove(id);   // the kits do not fit in memory resolved
            checked++;
            for (CgIssue i : data.validate(CgValidation.Usecase.LOAD).issues()) {
                if (i.getSeverity() != CgIssue.Severity.ERROR) continue;
                errors++;
                // Alike when the code and the path agree but for ids: one line per kind of failure.
                String what = i.getCode() + " " + KINDS.get(id).id() + " "
                        + i.getPath().toString().substring(IDS.get(id).length()).replaceAll("\\[[^\\]]*\\]", "[]");
                byWhat.merge(what, 1, Integer::sum);
                first.putIfAbsent(what, i.toString());
            }
        }
        StringBuilder report = new StringBuilder();
        for (Map.Entry<String, Integer> e : byWhat.entrySet()) {
            report.append(String.format("%7d  %s%n         %s%n", e.getValue(), e.getKey(), first.get(e.getKey())));
        }
        assertTrue(errors + " errors in " + checked + " assets:\n" + report, errors == 0);
    }

    /** The parent a file names on its first lines, where the writer puts it; null for none. */
    private static String parentOf(Path file) throws IOException {
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            for (int i = 0; i < 4; i++) {
                String line = r.readLine();
                if (line == null) return null;
                if (line.startsWith(CgType.PARENT + ": ")) return line.substring(CgType.PARENT.length() + 2).trim();
            }
        }
        return null;
    }

    /** The asset {@code key} ({@code kind|id}) names, resolved over its parents. */
    private static CgAssetData load(String key, Map<String, CgAssetData> loaded, Set<String> loading) throws IOException {
        CgAssetData done = loaded.get(key);
        if (done != null) return done;
        Path file = FILES.get(key);
        if (file == null) throw new IllegalStateException("its parent " + key + " is not in the kits");
        if (!loading.add(key)) throw new IllegalStateException("a parent cycle through " + key);
        Map<?, ?> tree = tree(file);
        Object parent = tree.get(CgType.PARENT);
        CgType kind = KINDS.get(key);
        CgAssetData data = CgAssetData.of(IDS.get(key), kind, tree,
                parent instanceof String ? load(key(kind, (String) parent), loaded, loading) : null);
        loaded.put(key, data);
        return data;
    }

    private static String key(CgType kind, String id) {
        return kind.id() + '|' + id;
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
            FILES.put(key(kind, id), p);
            KINDS.put(key(kind, id), kind);
            IDS.put(key(kind, id), id);
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
