package com.crystalgraphics.asset.kind;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.crystalgraphics.object.CgProperty;
import com.crystalgraphics.object.CgPropertyFlag;
import com.crystalgraphics.object.CgType;
import com.crystalgraphics.object.CgTypeKind;
import com.crystalgraphics.object.CgValueType;
import com.crystalgraphics.object.value.CgValueForm;

/**
 * Writes a kind's JSON schema from its declaration: the bible gate's step 2. The format's kind schemas
 * ({@code spec/schema/<kind>.schema.json}) are this output with the spec's descriptions carried over; only
 * {@code common.schema.json} is written by hand. The output is a plain tree (maps in order, lists, strings, numbers,
 * booleans) that any JSON writer prints.
 *
 * <pre>{@code
 * Map<String, Object> schema = CgSchemaWriter.kind(CgLookKinds.CURVE_KIND);   // {$schema, $id, title, type, ...}
 * }</pre>
 *
 * <ul>
 *   <li>Shared shapes are {@code common.schema.json}'s {@code $defs}: {@code value}, {@code curve}, {@code units},
 *       {@code editor}, {@code unsupported}, {@code inputs}, and each type in {@link #COMMON_DEFS}.</li>
 *   <li>A number that takes every form is a {@code value}; one narrowed to a constant is a JSON number, with its
 *       declared range.</li>
 *   <li>A struct is closed unless its declaration is {@code open()}: {@code additionalProperties} is the declaration's.</li>
 * </ul>
 */
public final class CgSchemaWriter {

    private static final String COMMON = "common.schema.json#/$defs/";

    /** Types written as a {@code $ref} to the {@code common.schema.json} definition of this name: structs and families. */
    static final Map<String, String> COMMON_DEFS = commonDefs();

    private CgSchemaWriter() {
    }

    /** The schema of the kind {@code kind}. */
    public static Map<String, Object> kind(CgType kind) {
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

    private static Map<String, String> commonDefs() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("crystalgraphics:post", "post");
        m.put("crystalgraphics:channel", "channel");
        m.put("crystalgraphics:place", "place");
        m.put("crystalgraphics:mesh_slot", "mesh_slot");
        m.put("crystalgraphics:retarget_pose", "retarget_pose");
        m.put("crystalgraphics:cue.effects", "effects");
        m.put("crystalgraphics:condition", "condition");
        m.put("crystalgraphics:sequence.spawnable", "spawnable");
        m.put("crystalgraphics:renderer", "renderer");
        m.put("crystalgraphics:camera_node", "camera_node");
        m.put("crystalgraphics:field_node", "field_node");
        m.put("crystalgraphics:retargeter.op", "retarget_op");
        m.put("crystalgraphics:shake_pattern", "shake_pattern");
        m.put("crystalgraphics:track", "track");
        m.put("crystalgraphics:camera_blend", "camera_blend");
        return Collections.unmodifiableMap(m);
    }

    private static Map<String, Object> properties(CgType type) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (type.kind() == CgTypeKind.KIND) {
            out.put(CgType.VERSION, map("type", "integer", "minimum", 1));
            out.put(CgType.PARENT, string(CgValueType.NAMESPACED_PATTERN));
            out.put(CgType.UNITS, ref("units"));
        }
        for (CgProperty p : type.properties()) {
            if (type.kind() == CgTypeKind.KIND && (p.name().equals(CgType.VERSION) || p.name().equals(CgType.PARENT)
                    || p.name().equals(CgType.UNITS))) continue;
            out.put(p.name(), property(type, p));
        }
        return out;
    }

    private static Object property(CgType owner, CgProperty p) {
        String n = p.name();
        if (owner.kind() == CgTypeKind.KIND) {
            if (n.equals(CgType.UNSUPPORTED)) return ref("unsupported");
            if (n.equals(CgType.EDITOR)) return ref("editor");
            if (n.equals(CgType.REMOVE)) return map("type", "array", "items", string(CgValueType.LOCAL_ID_PATTERN));
        }
        if (n.equals(CgType.ID) && owner.kind() == CgTypeKind.ELEMENT) return string(CgValueType.LOCAL_ID_PATTERN);
        if (n.equals(CgType.EDITOR) && p.type().kind() == CgValueType.Kind.TREE) return ref("editor");
        // A module entry's inputs: the module's own, typed by its declaration.
        if (n.equals("inputs") && p.type().kind() == CgValueType.Kind.MAP
                && p.type().element().kind() == CgValueType.Kind.VALUE) return ref("inputs");
        return value(p.type(), p);
    }

    private static Object value(CgValueType t, CgProperty p) {
        switch (t.kind()) {
            case FLOAT:
                return number(p, "number");
            case INT:
                return number(p, "integer");
            case BOOL:
                return map("type", "boolean");
            case STRING:
                return t.pattern() == null ? map("type", "string") : string(t.pattern());
            case ENUM:
                return map("enum", new ArrayList<>(t.enumValues()));
            case VEC2: case VEC3: case VEC4: case COLOUR: case ROTATION: case POSITION:
            case MESH: case TEXTURE: case FIELD: case VALUE:
                return ref("value");
            case CURVE:
                return ref("curve");
            case ARRAY:
                return map("type", "array", "items", ref("value"));
            case REF: case BINARY_REF:
                return string(CgValueType.NAMESPACED_PATTERN);
            case ELEMENT_REF:
                return string(CgValueType.LOCAL_ID_PATTERN);
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

    private static Object number(CgProperty p, String json) {
        boolean constantOnly = p != null && p.forms().size() == 1 && p.forms().contains(CgValueForm.CONSTANT);
        boolean structural = p != null && p.has(CgPropertyFlag.STRUCTURAL);
        if (!constantOnly && !structural) return ref("value");
        Map<String, Object> s = map("type", json);
        if (p.meta().min() != null) s.put("minimum", whole(p.meta().min(), json));
        if (p.meta().max() != null) s.put("maximum", whole(p.meta().max(), json));
        return s;
    }

    private static Object whole(double v, String json) {
        return json.equals("integer") ? (Object) (long) v : (Object) v;
    }

    private static Object struct(CgType type) {
        String def = COMMON_DEFS.get(type.id());
        if (def != null) return ref(def);
        return body(type, required(type));
    }

    /** A struct's or element's own object: closed unless the declaration is open. */
    private static Map<String, Object> body(CgType type, List<Object> required) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("$comment", type.id());
        s.put("type", "object");
        s.put("properties", properties(type));
        if (!required.isEmpty()) s.put("required", required);
        s.put("additionalProperties", type.open());
        return s;
    }

    private static Object element(CgType type) {
        Map<String, Object> hasId = map("type", "object", "required", list(CgType.ID));
        if (type.kind() == CgTypeKind.DISPATCH) return map("allOf", list(family(type), hasId));
        String def = COMMON_DEFS.get(type.id());
        if (def != null) return map("allOf", list(ref(def), hasId));
        List<Object> required = required(type);
        required.remove(CgType.ID);
        return map("allOf", list(body(type, required), hasId));
    }

    private static Object family(CgType family) {
        String def = COMMON_DEFS.get(family.id());
        if (def != null) return ref(def);
        List<Object> members = new ArrayList<>();
        for (Map.Entry<String, CgType> m : family.members().entrySet()) members.add(member(family, m.getKey(), m.getValue()));
        return map("oneOf", members);
    }

    /** One member of a family, written as {@code $defs} holds it: its key a {@code const}. */
    public static Map<String, Object> member(CgType family, String key, CgType member) {
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
        List<Object> required = required(member);
        required.add(0, family.dispatchKey());
        s.put("required", required);
        s.put("additionalProperties", member.open());
        return s;
    }

    private static List<Object> required(CgType type) {
        List<Object> out = new ArrayList<>();
        for (CgProperty p : type.properties()) if (p.required()) out.add(p.name());
        return out;
    }

    private static Map<String, Object> string(String pattern) {
        return map("type", "string", "pattern", pattern);
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
