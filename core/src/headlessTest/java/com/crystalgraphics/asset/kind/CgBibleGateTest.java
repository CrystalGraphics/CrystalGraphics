package com.crystalgraphics.asset.kind;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import com.crystalgraphics.object.CgProperty;
import com.crystalgraphics.object.CgPropertyFlag;
import com.crystalgraphics.object.CgType;
import com.crystalgraphics.object.CgTypeKind;
import com.crystalgraphics.object.CgTypes;
import com.crystalgraphics.object.CgValueType;
import com.crystalgraphics.object.value.CgValueForm;
import com.crystalgraphics.serialization.CgCodecs;
import com.crystalgraphics.serialization.format.CgFormats;
import com.crystalgraphics.util.io.CgIO;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;

/**
 * The bible gate: every declaration against the format's JSON schemas, both ways. A schema property with no
 * declaration fails, and so does a declaration the schema lacks or leaves open, since the reference writer keeps read
 * order there; key order, required keys, enum values and type shapes must agree. {@code modules.yaml}'s modules load
 * as types and match it input for input. The schemas win: a mismatch is the engine's to fix.
 *
 * <p>Reads the schemas from {@code -Dcrystalgraphics.spec}; skipped, saying so, without it.</p>
 */
public class CgBibleGateTest {

    private static Path spec;
    private static JsonObject common;

    @BeforeClass
    public static void spec() throws IOException {
        String dir = System.getProperty("crystalgraphics.spec");
        Assume.assumeTrue("no -Dcrystalgraphics.spec: the bible gate did not run",
                dir != null && Files.isDirectory(Paths.get(dir)));
        spec = Paths.get(dir);
        common = schema("common").getAsJsonObject("$defs");
        CgAssetKinds.register();
    }

    @Test
    public void everyKindMatchesItsSchemaBothWays() throws IOException {
        List<String> found = new ArrayList<>();
        for (CgType kind : CgAssetKinds.all()) {
            String name = kind.id().substring(kind.id().indexOf(':') + 1);
            JsonObject schema = schema(name);
            new Walk(schema.has("$defs") ? schema.getAsJsonObject("$defs") : new JsonObject(), found)
                    .struct(name, schema, kind, new HashSet<String>(), null);
        }
        assertTrue(found.size() + " mismatches between the declarations and the schemas:\n  " + String.join("\n  ", found),
                found.isEmpty());
    }

    @Test
    public void theShippedModulesAreTheSpecs() throws IOException {
        byte[] specs = Files.readAllBytes(spec.resolve("schema/modules.yaml"));
        byte[] shipped;
        try (InputStream in = CgIO.openStream(CgModuleTypes.RESOURCE)) {
            shipped = in.readAllBytes();
        }
        assertArrayEquals("core's " + CgModuleTypes.RESOURCE + " differs from the format's modules.yaml: copy it over", specs, shipped);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void everyModuleLoadsAsItsDeclarationSaysBothWays() throws IOException {
        String yaml = new String(Files.readAllBytes(spec.resolve("schema/modules.yaml")), StandardCharsets.UTF_8);
        CgModuleTypes modules = CgModuleTypes.load(yaml, CgTypes.create());
        Map<String, Object> doc = (Map<String, Object>) CgFormats.YAML.fromText(CgCodecs.PLAIN, yaml);
        List<String> found = new ArrayList<>();
        Set<String> inputKeys = new LinkedHashSet<>();
        List<Map<String, Object>> declared = (List<Map<String, Object>>) doc.get("modules");
        if (declared.size() != modules.all().size()) found.add("modules: " + declared.size() + " declared, " + modules.all().size() + " loaded");
        for (Map<String, Object> m : declared) {
            String id = "crystalgraphics:" + m.get("id");
            CgModuleTypes.Module module = modules.get(id);
            if (module == null) {
                found.add(id + ": not loaded");
                continue;
            }
            List<Map<String, Object>> inputs = (List<Map<String, Object>>) m.getOrDefault("inputs", new ArrayList<>());
            List<CgProperty> props = module.inputs().properties();
            // A seeded module loads one input more, its seed, after those it declares.
            if (Boolean.TRUE.equals(m.get("seeded"))) {
                if (!props.get(props.size() - 1).name().equals("seed")) found.add(id + ": seeded, and loads no seed last");
                props = props.subList(0, props.size() - 1);
            }
            if (inputs.size() != props.size()) found.add(id + ": " + inputs.size() + " inputs declared, " + props.size() + " loaded");
            for (int i = 0; i < Math.min(inputs.size(), props.size()); i++) {
                Map<String, Object> in = inputs.get(i);
                inputKeys.addAll(in.keySet());
                CgProperty p = props.get(i);
                String where = id + "." + in.get("name");
                if (!p.name().equals(in.get("name"))) found.add(where + ": loaded as '" + p.name() + "', out of order");
                Object q = in.getOrDefault("quantity", "none");
                if (!p.quantity().specName().equals(q)) found.add(where + ": quantity " + p.quantity().specName() + ", declared " + q);
                if (in.containsKey("default") && !Objects.equals(in.get("default"), p.defaultValue())) found.add(where + ": default " + p.defaultValue());
                if (in.containsKey("default") == p.flags().contains(CgPropertyFlag.NO_DEFAULT)) found.add(where + ": no default " + !in.containsKey("default"));
                if (in.containsKey("forms")) {
                    Set<String> forms = new LinkedHashSet<>();
                    for (CgValueForm f : p.forms()) forms.add(f.specName());
                    if (!forms.equals(new LinkedHashSet<>((List<String>) in.get("forms")))) found.add(where + ": forms " + forms);
                }
                if (in.containsKey("by") && !p.meta().by().equals(in.get("by"))) found.add(where + ": by " + p.meta().by());
                if (in.containsKey("rolls") && (p.meta().rolls() == null
                        || !p.meta().rolls().name().equalsIgnoreCase((String) in.get("rolls")))) found.add(where + ": rolls " + p.meta().rolls());
                if ("enum".equals(in.get("type")) && !p.type().enumValues().equals(in.get("values"))) found.add(where + ": values " + p.type().enumValues());
            }
        }
        // The input declaration struct must declare every key modules.yaml uses, and nothing it does not.
        Set<String> structKeys = new LinkedHashSet<>();
        for (CgProperty p : CgCommonStructs.INPUT_DECLARATION.properties()) structKeys.add(p.name());
        for (String k : inputKeys) if (!structKeys.contains(k)) found.add("crystalgraphics:input: modules.yaml uses '" + k + "', undeclared");
        assertTrue(found.size() + " mismatches between the loaded modules and modules.yaml:\n  " + String.join("\n  ", found), found.isEmpty());
    }

    // ── The walk ─────────────────────────────────────────────────────────────────────────

    /** Compares one schema file's tree with the declarations, collecting mismatches by path. */
    private static final class Walk {
        private final JsonObject defs;
        private final List<String> found;
        /** Each resolved allOf once, so a schema keeps one identity and a cycle is seen as one. */
        private final Map<JsonObject, JsonObject> merged = new IdentityHashMap<>();
        private final Map<JsonObject, JsonObject> required = new IdentityHashMap<>();

        Walk(JsonObject defs, List<String> found) {
            this.defs = defs;
            this.found = found;
        }

        /** A schema object with properties against a declared struct, kind or element. {@code skip}: a family's key. */
        void struct(String path, JsonObject s, CgType type, Set<String> seen, String skip) {
            s = resolve(s);
            if (!seen.add(System.identityHashCode(s) + "/" + type.id() + "/" + skip)) return;
            JsonObject props = s.has("properties") ? s.getAsJsonObject("properties") : new JsonObject();
            boolean open = !s.has("additionalProperties") || !s.get("additionalProperties").isJsonPrimitive()
                    || s.get("additionalProperties").getAsBoolean();
            Set<String> required = new HashSet<>();
            if (s.has("required")) for (JsonElement r : s.getAsJsonArray("required")) required.add(r.getAsString());

            List<String> schemaOrder = new ArrayList<>();
            for (Map.Entry<String, JsonElement> e : props.entrySet()) {
                String name = e.getKey();
                if (name.equals(skip)) continue;
                schemaOrder.add(name);
                CgProperty p = type.property(name);
                if (p == null) {
                    found.add(path + "." + name + ": in the schema, not declared");
                    continue;
                }
                if (required.contains(name) != p.required()) {
                    found.add(path + "." + name + ": " + (p.required() ? "declared required, optional in the schema" : "required in the schema, declared optional"));
                }
                value(path + "." + name, e.getValue().getAsJsonObject(), p.type(), type, seen);
            }
            List<String> declaredOrder = new ArrayList<>();
            for (CgProperty p : type.properties()) {
                if (props.has(p.name())) declaredOrder.add(p.name());
                else if (!p.name().equals(skip)) {
                    // An open schema still orders nothing it leaves undeclared, so the writers would disagree.
                    found.add(path + "." + p.name() + (open ? ": declared, the schema leaves it open" : ": declared, not in the schema"));
                }
            }
            if (!declaredOrder.equals(schemaOrder)) found.add(path + ": key order " + declaredOrder + ", the schema's " + schemaOrder);
            if (!open && type.open()) found.add(path + ": declared open, the schema is closed");
        }

        /** A schema property against a declared value type. */
        void value(String path, JsonObject s, CgValueType t, CgType owner, Set<String> seen) {
            s = resolve(s);
            if (t.kind() == CgValueType.Kind.SELF) {
                t = CgValueType.struct(owner);
            }
            if (s.entrySet().isEmpty() && ordered(t)) {
                found.add(path + ": declared " + t + ", the schema leaves it untyped");
                return;
            }
            if (s.entrySet().isEmpty() || t.kind() == CgValueType.Kind.VALUE) return;
            if (t.kind() == CgValueType.Kind.TREE) {
                if (s.has("properties")) found.add(path + ": the schema types a struct, declared an untyped tree");
                return;
            }
            if (t.kind() == CgValueType.Kind.ONE_OF) {
                for (CgValueType alt : t.alternatives()) if (matches(path, s, alt, owner, seen)) return;
                found.add(path + ": no alternative of " + t + " matches the schema's " + shape(s));
                return;
            }
            if (s.has("oneOf") && t.kind() == CgValueType.Kind.DISPATCH) {
                family(path, s, t.type(), seen);
                return;
            }
            if (s.has("oneOf") && t.kind() == CgValueType.Kind.STRUCT && t.type() != null && allStructs(s)) {
                flatUnion(path, s, t.type(), owner, seen);
                return;
            }
            String choice = s.has("oneOf") ? "oneOf" : s.has("anyOf") ? "anyOf" : null;
            if (choice != null) {
                for (JsonElement alt : s.getAsJsonArray(choice)) if (matches(path, alt.getAsJsonObject(), t, owner, seen)) return;
                found.add(path + ": " + t + " matches none of the schema's alternatives " + shape(s));
                return;
            }
            if (s.has("enum")) {
                List<String> values = new ArrayList<>();
                for (JsonElement v : s.getAsJsonArray("enum")) values.add(v.getAsString());
                if (t.kind() != CgValueType.Kind.ENUM) found.add(path + ": the schema's enum " + values + ", declared " + t);
                else if (!t.enumValues().equals(values)) found.add(path + ": enum " + t.enumValues() + ", the schema's " + values);
                return;
            }
            String type = s.has("type") && s.get("type").isJsonPrimitive() ? s.get("type").getAsString()
                    : s.has("properties") ? "object" : s.has("items") ? "array" : null;
            if (type == null) return;
            CgValueType.Kind k = t.kind();
            switch (type) {
                case "integer":
                    expect(path, s, t, k == CgValueType.Kind.INT);
                    return;
                case "number":
                    expect(path, s, t, k == CgValueType.Kind.FLOAT || k == CgValueType.Kind.INT);
                    return;
                case "boolean":
                    expect(path, s, t, k == CgValueType.Kind.BOOL);
                    return;
                case "string":
                    if (k == CgValueType.Kind.ENUM) found.add(path + ": declared " + t + ", the schema has a plain string");
                    else expect(path, s, t, k == CgValueType.Kind.STRING || k == CgValueType.Kind.REF
                            || k == CgValueType.Kind.ELEMENT_REF || k == CgValueType.Kind.BINARY_REF || k == CgValueType.Kind.CURVE);
                    return;
                case "array":
                    array(path, s, t, owner, seen);
                    return;
                case "object":
                    object(path, s, t, owner, seen);
                    return;
                default:
            }
        }

        /** Whether the writer orders keys inside a value of {@code t}: what an untyped schema leaves in read order. */
        private static boolean ordered(CgValueType t) {
            switch (t.kind()) {
                case STRUCT: case DISPATCH: case ID_LIST: case CURVE:
                    return true;
                case MAP: case LIST:
                    return ordered(t.element());
                case ONE_OF:
                    for (CgValueType alt : t.alternatives()) if (ordered(alt)) return true;
                    return false;
                default:
                    return false;
            }
        }

        private void array(String path, JsonObject s, CgValueType t, CgType owner, Set<String> seen) {
            CgValueType.Kind k = t.kind();
            JsonObject items = s.has("items") && s.get("items").isJsonObject() ? resolve(s.getAsJsonObject("items")) : null;
            switch (k) {
                case ID_LIST:
                    if (items == null) return;
                    element(path + "[]", items, t.type(), seen);
                    return;
                case LIST:
                    if (items != null) value(path + "[]", items, t.element(), owner, seen);
                    return;
                case VEC2: case VEC3: case VEC4: case COLOUR: case POSITION: case ROTATION: case ARRAY: case CURVE:
                    return;
                default:
                    found.add(path + ": the schema has a list, declared " + t);
            }
        }

        private void element(String path, JsonObject items, CgType type, Set<String> seen) {
            if (type.kind() == CgTypeKind.DISPATCH) {
                family(path, items, type, seen);
            } else if (items.has("oneOf") && allStructs(items)) {
                flatUnion(path, items, type, type, seen);   // members chosen by the key present: a spawnable
            } else {
                struct(path, items, type, seen, null);
            }
        }

        private void object(String path, JsonObject s, CgValueType t, CgType owner, Set<String> seen) {
            switch (t.kind()) {
                case STRUCT:
                    if (t.type() != null && s.has("properties")) struct(path, s, t.type(), seen, null);
                    return;
                case MAP:
                    if (s.has("additionalProperties") && s.get("additionalProperties").isJsonObject()) {
                        value(path + "{}", s.getAsJsonObject("additionalProperties"), t.element(), owner, seen);
                    }
                    return;
                case DISPATCH:
                    family(path, s, t.type(), seen);
                    return;
                case CURVE: case COLOUR: case ROTATION: case POSITION:
                    return;
                default:
                    found.add(path + ": the schema has a map, declared " + t);
            }
        }

        /**
         * A family against a {@code oneOf} whose alternatives are its members, each chosen by its key's {@code const};
         * or, flat, one object whose key's {@code enum} lists the members and whose keys every member holds.
         */
        private void family(String path, JsonObject s, CgType family, Set<String> seen) {
            s = resolve(s);
            String key = family.dispatchKey();
            if (!s.has("oneOf")) {
                flatFamily(path, s, family, seen);
                return;
            }
            Map<String, JsonObject> alternatives = new LinkedHashMap<>();
            for (JsonElement a : s.getAsJsonArray("oneOf")) {
                JsonObject alt = withRequired(resolve(a.getAsJsonObject()), s);
                JsonObject chooser = alt.has("properties") ? alt.getAsJsonObject("properties").getAsJsonObject(key) : null;
                if (chooser == null || !chooser.has("const")) {
                    found.add(path + ": an alternative without a const '" + key + "': " + shape(alt));
                    continue;
                }
                alternatives.put(chooser.get("const").getAsString(), alt);
            }
            List<String> members = new ArrayList<>(family.members().keySet());
            if (!members.equals(new ArrayList<>(alternatives.keySet()))) {
                found.add(path + "." + key + ": members " + members + ", the schema's " + alternatives.keySet());
            }
            for (Map.Entry<String, CgType> m : family.members().entrySet()) {
                JsonObject alt = alternatives.get(m.getKey());
                if (alt != null) struct(path + "<" + m.getKey() + ">", alt, m.getValue(), seen, key);
            }
        }

        private void flatFamily(String path, JsonObject s, CgType family, Set<String> seen) {
            String key = family.dispatchKey();
            JsonObject props = s.has("properties") ? s.getAsJsonObject("properties") : new JsonObject();
            if (!props.has(key)) {
                found.add(path + ": family keyed by '" + key + "', the schema has no such key");
                return;
            }
            JsonObject keySchema = resolve(props.getAsJsonObject(key));
            if (keySchema.has("enum")) {
                List<String> values = new ArrayList<>();
                for (JsonElement v : keySchema.getAsJsonArray("enum")) values.add(v.getAsString());
                List<String> members = new ArrayList<>(family.members().keySet());
                if (!members.equals(values)) found.add(path + "." + key + ": members " + members + ", the schema's " + values);
            }
            for (Map.Entry<String, CgType> m : family.members().entrySet()) {
                struct(path + "<" + m.getKey() + ">", s, m.getValue(), seen, key);
            }
        }

        /** {@code alt} with the keys {@code outer} requires: a list's {@code required: [id]} beside its family's {@code oneOf}. */
        private JsonObject withRequired(JsonObject alt, JsonObject outer) {
            if (!outer.has("required") || outer.getAsJsonArray("required").size() == 0) return alt;
            JsonObject copy = required.get(alt);   // one copy, so the seen-guard knows it on a recursive family
            if (copy != null) return copy;
            copy = alt.deepCopy();
            required.put(alt, copy);
            JsonArray keys = copy.has("required") ? copy.getAsJsonArray("required") : new JsonArray();
            for (JsonElement r : outer.getAsJsonArray("required")) if (!keys.contains(r)) keys.add(r);
            copy.add("required", keys);
            return copy;
        }

        private boolean allStructs(JsonObject s) {
            for (JsonElement alt : s.getAsJsonArray("oneOf")) {
                if (!resolve(alt.getAsJsonObject()).has("properties")) return false;
            }
            return true;
        }

        /**
         * One flat struct declared for a {@code oneOf} of structs told apart by which key is present (a placement, a
         * condition; its validator requires exactly one choosing key). Each alternative's keys are the declaration's,
         * in its order, and the declaration holds nothing outside their union.
         */
        private void flatUnion(String path, JsonObject s, CgType type, CgType owner, Set<String> seen) {
            if (!seen.add("union/" + System.identityHashCode(s) + "/" + type.id())) return;
            Set<String> union = new HashSet<>();
            boolean anyOpen = false;
            for (JsonElement a : s.getAsJsonArray("oneOf")) {
                JsonObject alt = resolve(a.getAsJsonObject());
                JsonObject props = alt.getAsJsonObject("properties");
                anyOpen |= !alt.has("additionalProperties") || !alt.get("additionalProperties").isJsonPrimitive()
                        || alt.get("additionalProperties").getAsBoolean();
                int last = -1;
                for (Map.Entry<String, JsonElement> e : props.entrySet()) {
                    union.add(e.getKey());
                    CgProperty p = type.property(e.getKey());
                    if (p == null) {
                        found.add(path + "." + e.getKey() + ": in the schema, not declared");
                        continue;
                    }
                    if (p.index() < last) found.add(path + "." + e.getKey() + ": out of the schema's order");
                    last = p.index();
                    value(path + "." + e.getKey(), e.getValue().getAsJsonObject(), p.type(), type, seen);
                }
            }
            if (!anyOpen) {
                for (CgProperty p : type.properties()) {
                    if (!union.contains(p.name())) found.add(path + "." + p.name() + ": declared, in none of the schema's alternatives");
                }
            }
        }

        /** Whether {@code t} matches {@code s} with no mismatch, without reporting. */
        private boolean matches(String path, JsonObject s, CgValueType t, CgType owner, Set<String> seen) {
            List<String> scratch = new ArrayList<>();
            Walk trial = new Walk(defs, scratch);
            trial.merged.putAll(merged);
            trial.value(path, s, t, owner, seen);
            JsonObject r = resolve(s);
            if (!scratch.isEmpty()) return false;
            String type = r.has("type") && r.get("type").isJsonPrimitive() ? r.get("type").getAsString() : null;
            // A shape check: a declared type of the wrong family matches nothing even where nothing was reported.
            if (type == null) return !(r.has("properties") && t.kind() != CgValueType.Kind.STRUCT && t.kind() != CgValueType.Kind.DISPATCH);
            switch (type) {
                case "string": return t.kind() == CgValueType.Kind.STRING || t.kind() == CgValueType.Kind.REF || t.kind() == CgValueType.Kind.CURVE
                        || t.kind() == CgValueType.Kind.BINARY_REF || t.kind() == CgValueType.Kind.ENUM
                        || t.kind() == CgValueType.Kind.ELEMENT_REF;
                case "object": return t.kind() == CgValueType.Kind.STRUCT || t.kind() == CgValueType.Kind.MAP || t.kind() == CgValueType.Kind.CURVE
                        || t.kind() == CgValueType.Kind.DISPATCH || t.kind() == CgValueType.Kind.TREE;
                case "array": return t.kind() == CgValueType.Kind.LIST || t.kind() == CgValueType.Kind.ID_LIST
                        || t.kind() == CgValueType.Kind.ARRAY || t.kind() == CgValueType.Kind.VEC2 || t.kind() == CgValueType.Kind.VEC3
                        || t.kind() == CgValueType.Kind.VEC4;
                case "boolean": return t.kind() == CgValueType.Kind.BOOL;
                case "number": return t.kind() == CgValueType.Kind.FLOAT || t.kind() == CgValueType.Kind.INT;
                case "integer": return t.kind() == CgValueType.Kind.INT;
                case "null": return false;
                default: return true;
            }
        }

        private void expect(String path, JsonObject s, CgValueType t, boolean ok) {
            if (!ok) found.add(path + ": the schema's " + shape(s) + ", declared " + t);
        }

        private JsonObject resolve(JsonObject s) {
            for (int guard = 0; s.has("$ref") && guard < 16; guard++) {
                String ref = s.get("$ref").getAsString();
                String name = ref.substring(ref.lastIndexOf('/') + 1);
                JsonObject target = ref.startsWith("common.schema.json") ? common.getAsJsonObject(name) : defs.getAsJsonObject(name);
                if (target == null) {
                    found.add("unresolved $ref " + ref);
                    return new JsonObject();
                }
                s = target;
            }
            if (!s.has("allOf")) return s;
            JsonObject cached = this.merged.get(s);
            if (cached != null) return cached;
            JsonObject merged = new JsonObject();
            JsonObject props = new JsonObject();
            JsonArray required = new JsonArray();
            for (Map.Entry<String, JsonElement> e : s.entrySet()) if (!e.getKey().equals("allOf")) merged.add(e.getKey(), e.getValue());
            for (JsonElement part : s.getAsJsonArray("allOf")) {
                JsonObject p = resolve(part.getAsJsonObject());
                if (p.has("properties")) for (Map.Entry<String, JsonElement> e : p.getAsJsonObject("properties").entrySet()) props.add(e.getKey(), e.getValue());
                if (p.has("required")) required.addAll(p.getAsJsonArray("required"));
                for (String k : Arrays.asList("type", "additionalProperties", "oneOf")) if (p.has(k) && !merged.has(k)) merged.add(k, p.get(k));
            }
            merged.add("properties", props);
            merged.add("required", required);
            this.merged.put(s, merged);
            return merged;
        }

        private static String shape(JsonObject s) {
            String text = s.toString();
            return text.length() > 90 ? text.substring(0, 90) + "…" : text;
        }
    }

    private static JsonObject schema(String name) throws IOException {
        return JsonParser.parseString(new String(Files.readAllBytes(spec.resolve("schema/" + name + ".schema.json")),
                StandardCharsets.UTF_8)).getAsJsonObject();
    }

}
