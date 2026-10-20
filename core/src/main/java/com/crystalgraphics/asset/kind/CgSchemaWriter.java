package com.crystalgraphics.asset.kind;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.crystalgraphics.object.CgProperty;
import com.crystalgraphics.object.CgPropertyFlag;
import com.crystalgraphics.object.CgType;
import com.crystalgraphics.object.CgTypeKind;
import com.crystalgraphics.object.CgValueType;
import com.crystalgraphics.object.value.CgValueForm;

/**
 * Writes a kind's JSON schema from its declaration: the bible gate's step 2, where the schemas are generated from the
 * declarations rather than checked against them. The output is a plain tree (maps in order, lists, strings, numbers,
 * booleans) that any JSON writer prints.
 *
 * <pre>{@code
 * CgSchemaWriter writer = CgSchemaWriter.of(family -> "renderer");     // each dispatch family's $defs name
 * Map<String, Object> schema = writer.kind(CgLookKinds.CURVE_KIND);    // {$schema, $id, title, type, properties, ...}
 * }</pre>
 *
 * <ul>
 *   <li>Shared shapes are {@code common.schema.json}'s {@code $defs}: {@code value}, {@code curve}, {@code units},
 *       {@code editor}, {@code unsupported}, {@code inputs}, and one per dispatch family, named by {@code defs}.</li>
 *   <li>A number that takes every form is a {@code value}; one narrowed to a constant is a JSON number.</li>
 * </ul>
 */
public final class CgSchemaWriter {

    /** An asset id: a reference. */
    public static final String REF_PATTERN = "^[a-z0-9_]+:\\S.*$";
    /** An element id. */
    public static final String ID_PATTERN = "^[a-z0-9_]{1,32}$";
    private static final String COMMON = "common.schema.json#/$defs/";

    private final Function<CgType, String> defs;

    private CgSchemaWriter(Function<CgType, String> defs) {
        this.defs = defs;
    }

    /** A writer naming each dispatch family's {@code $defs} entry with {@code defs}; null writes the family inline. */
    public static CgSchemaWriter of(Function<CgType, String> defs) {
        return new CgSchemaWriter(defs);
    }

    /** The schema of the kind {@code kind}. */
    public Map<String, Object> kind(CgType kind) {
        String name = kind.id().substring(kind.id().indexOf(':') + 1);
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        s.put("$id", "https://crystalgraphics/aaa/" + name + ".schema.json");
        s.put("title", name);
        s.put("type", "object");
        s.put("properties", properties(kind));
        s.put("additionalProperties", kind.open());
        List<Object> required = new ArrayList<>();
        required.add(CgType.VERSION);
        for (CgProperty p : kind.properties()) if (p.required() && !p.name().equals(CgType.VERSION)) required.add(p.name());
        s.put("required", required);
        return s;
    }

    private Map<String, Object> properties(CgType type) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (type.kind() == CgTypeKind.KIND) {
            out.put(CgType.VERSION, map("type", "integer", "minimum", 1));
            out.put(CgType.PARENT, map("type", "string", "pattern", REF_PATTERN));
            out.put(CgType.UNITS, ref("units"));
        }
        for (CgProperty p : type.properties()) {
            if (type.kind() == CgTypeKind.KIND && (p.name().equals(CgType.VERSION) || p.name().equals(CgType.PARENT)
                    || p.name().equals(CgType.UNITS))) continue;
            out.put(p.name(), property(type, p));
        }
        return out;
    }

    private Object property(CgType owner, CgProperty p) {
        String n = p.name();
        if (owner.kind() == CgTypeKind.KIND) {
            if (n.equals(CgType.UNSUPPORTED)) return ref("unsupported");
            if (n.equals(CgType.EDITOR)) return ref("editor");
            if (n.equals(CgType.REMOVE)) return map("type", "array", "items", map("type", "string", "pattern", ID_PATTERN));
        }
        if (n.equals(CgType.ID) && owner.kind() == CgTypeKind.ELEMENT) return map("type", "string", "pattern", ID_PATTERN);
        if (n.equals(CgType.EDITOR) && p.type().kind() == CgValueType.Kind.TREE) return ref("editor");
        return value(p.type(), p);
    }

    private Object value(CgValueType t, CgProperty p) {
        switch (t.kind()) {
            case FLOAT:
                return number(p, "number");
            case INT:
                return number(p, "integer");
            case BOOL:
                return map("type", "boolean");
            case STRING:
                return map("type", "string");
            case ENUM:
                return map("enum", new ArrayList<>(t.enumValues()));
            case VEC2: case VEC3: case VEC4: case COLOUR: case ROTATION: case POSITION:
            case MESH: case TEXTURE: case FIELD: case VALUE:
                return ref("value");
            case CURVE:
                return ref("curve");
            case ARRAY:
                return map("type", "array", "items", ref("value"));
            case REF:
                return map("type", "string", "pattern", REF_PATTERN);
            case ELEMENT_REF:
                return map("type", "string", "pattern", ID_PATTERN);
            case BINARY_REF:
                return map("type", "string");
            case STRUCT:
                return t.type() == null ? map("type", "object") : struct(t.type());
            case LIST:
                return map("type", "array", "items", value(t.element(), null));
            case ID_LIST:
                return map("type", "array", "items", element(t.type()));
            case MAP:
                return map("type", "object", "additionalProperties", value(t.element(), null));
            case DISPATCH:
                return family(t.type());
            case ONE_OF: {
                List<Object> any = new ArrayList<>();
                for (CgValueType a : t.alternatives()) any.add(value(a, p));
                return map("anyOf", any);
            }
            case NULL:
                return map("type", "null");
            default:
                return map();
        }
    }

    private Object number(CgProperty p, String json) {
        boolean constantOnly = p != null && p.forms().size() == 1 && p.forms().contains(CgValueForm.CONSTANT);
        boolean structural = p != null && p.has(CgPropertyFlag.STRUCTURAL);
        return constantOnly || structural ? map("type", json) : ref("value");
    }

    private Map<String, Object> struct(CgType type) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("$comment", type.id());
        s.put("properties", properties(type));
        List<Object> required = required(type);
        if (!required.isEmpty()) s.put("required", required);
        if (type.open()) s.put("additionalProperties", true);
        return s;
    }

    private Object element(CgType type) {
        if (type.kind() == CgTypeKind.DISPATCH) {
            return map("allOf", list(family(type), map("type", "object", "required", list(CgType.ID))));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "object");
        body.put("properties", properties(type));
        body.put("additionalProperties", type.open());
        List<Object> required = required(type);
        required.remove(CgType.ID);
        if (!required.isEmpty()) body.put("required", required);
        return map("allOf", list(body, map("type", "object", "required", list(CgType.ID))));
    }

    private Object family(CgType family) {
        String name = defs.apply(family);
        if (name != null) return ref(name);
        List<Object> members = new ArrayList<>();
        for (Map.Entry<String, CgType> m : family.members().entrySet()) members.add(member(family, m.getKey(), m.getValue()));
        return map("oneOf", members);
    }

    /** One member of a family, written as {@code $defs} holds it: its key a {@code const}. */
    public Map<String, Object> member(CgType family, String key, CgType member) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("$comment", member.id());
        s.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        boolean keyed = false;
        for (CgProperty p : member.properties()) {
            props.put(p.name(), property(member, p));
            if (p.name().equals(CgType.ID) && !keyed) {
                props.put(family.dispatchKey(), map("const", key));
                keyed = true;
            }
        }
        if (!keyed) {
            Map<String, Object> withKey = new LinkedHashMap<>();
            withKey.put(family.dispatchKey(), map("const", key));
            withKey.putAll(props);
            props = withKey;
        }
        s.put("properties", props);
        s.put("additionalProperties", member.open());
        List<Object> required = required(member);
        required.add(0, family.dispatchKey());
        s.put("required", required);
        return s;
    }

    private static List<Object> required(CgType type) {
        List<Object> out = new ArrayList<>();
        for (CgProperty p : type.properties()) if (p.required()) out.add(p.name());
        return out;
    }

    private static Map<String, Object> ref(String def) {
        return map("$ref", COMMON + def);
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static List<Object> list(Object... items) {
        List<Object> out = new ArrayList<>(items.length);
        Collections.addAll(out, items);
        return out;
    }
}
