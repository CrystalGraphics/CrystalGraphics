package com.crystalgraphics.asset.kind;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import com.crystalgraphics.object.CgType;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/**
 * The bible gate, step 2: each kind's schema generated from its declaration ({@link CgSchemaWriter}) against the
 * checked-in one, key for key, descriptions aside. The checked-in kind schemas are this output with their descriptions,
 * so a difference is a declaration changed without its spec change (an {@code aaa-design} entry); the differences are
 * printed, grouped by what each expected.
 *
 * <p>Reads the schemas from {@code -Dcrystalgraphics.spec}; skipped, saying so, without it.</p>
 */
public class CgSchemaGenerationTest {

    /** Where the generated schemas are written, to copy into the spec with their descriptions. */
    private static final Path OUT = Paths.get("build", "generated-schemas");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private static Path spec;

    @BeforeClass
    public static void spec() throws IOException {
        String dir = System.getProperty("crystalgraphics.spec");
        Assume.assumeTrue("no -Dcrystalgraphics.spec: schema generation was not checked",
                dir != null && Files.isDirectory(Paths.get(dir)));
        spec = Paths.get(dir);
        CgAssetKinds.register();
    }

    @Test
    public void generatedSchemasEqualTheCheckedInOnes() throws IOException {
        List<String> diffs = new ArrayList<>();
        Map<String, Integer> byShape = new TreeMap<>();
        for (CgType kind : CgAssetKinds.all()) {
            String name = kind.id().substring(kind.id().indexOf(':') + 1);
            Map<String, Object> generated = CgSchemaWriter.kind(kind);
            Files.createDirectories(OUT);
            Files.write(OUT.resolve(name + ".schema.json"), GSON.toJson(generated).getBytes(StandardCharsets.UTF_8));
            diff(name, plain(schema(name)), generated, diffs, byShape);
        }
        StringBuilder report = new StringBuilder();
        report.append(diffs.size()).append(" differences, by what the checked-in schema has:\n");
        byShape.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(40)
                .forEach(e -> report.append("  ").append(e.getValue()).append("  ").append(e.getKey()).append('\n'));
        report.append("first differences:\n");
        for (int i = 0; i < Math.min(60, diffs.size()); i++) report.append("  ").append(diffs.get(i)).append('\n');
        System.out.println(report);
        assertTrue(report.toString(), diffs.isEmpty());
    }

    private static void diff(String path, Object want, Object got, List<String> diffs, Map<String, Integer> byShape) {
        if (want instanceof Map<?, ?> w && got instanceof Map<?, ?> g) {
            List<Object> wantKeys = new ArrayList<>(w.keySet()), gotKeys = new ArrayList<>(g.keySet());
            wantKeys.remove("description");
            gotKeys.remove("description");
            for (Object k : wantKeys) {
                if (!g.containsKey(k)) note(path + "/" + k, "missing " + k + " = " + shape(w.get(k)), diffs, byShape);
                else diff(path + "/" + k, w.get(k), g.get(k), diffs, byShape);
            }
            for (Object k : gotKeys) if (!w.containsKey(k)) note(path + "/" + k, "extra " + k + " = " + shape(g.get(k)), diffs, byShape);
            List<Object> common = new ArrayList<>(wantKeys);
            common.retainAll(gotKeys);
            List<Object> gotCommon = new ArrayList<>(gotKeys);
            gotCommon.retainAll(wantKeys);
            if (!common.equals(gotCommon)) note(path, "key order", diffs, byShape);
        } else if (want instanceof List<?> w && got instanceof List<?> g) {
            if (w.size() != g.size()) {
                note(path, "list of " + w.size() + " " + shape(w), diffs, byShape);
                return;
            }
            for (int i = 0; i < w.size(); i++) diff(path + "[" + i + "]", w.get(i), g.get(i), diffs, byShape);
        } else if (!same(want, got)) {
            note(path, shape(want) + " not " + shape(got), diffs, byShape);
        }
    }

    private static boolean same(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) return x.doubleValue() == y.doubleValue();
        return a == null ? b == null : a.equals(b);
    }

    private static void note(String path, String what, List<String> diffs, Map<String, Integer> byShape) {
        diffs.add(path + ": " + what);
        String key = what.length() > 140 ? what.substring(0, 140) : what;
        byShape.merge(key, 1, Integer::sum);
    }

    private static String shape(Object o) {
        String s = String.valueOf(o);
        return s.length() > 110 ? s.substring(0, 110) + "..." : s;
    }

    private static Object plain(JsonElement e) {
        if (e.isJsonObject()) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> x : e.getAsJsonObject().entrySet()) m.put(x.getKey(), plain(x.getValue()));
            return m;
        }
        if (e.isJsonArray()) {
            List<Object> l = new ArrayList<>();
            for (JsonElement x : (JsonArray) e) l.add(plain(x));
            return l;
        }
        if (e.isJsonNull()) return null;
        JsonPrimitive p = e.getAsJsonPrimitive();
        if (p.isBoolean()) return p.getAsBoolean();
        if (p.isNumber()) return p.getAsDouble();
        return p.getAsString();
    }

    private static JsonObject schema(String name) throws IOException {
        return JsonParser.parseString(new String(Files.readAllBytes(spec.resolve("schema/" + name + ".schema.json")),
                StandardCharsets.UTF_8)).getAsJsonObject();
    }
}
