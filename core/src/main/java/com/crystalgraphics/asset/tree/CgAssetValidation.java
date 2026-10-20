package com.crystalgraphics.asset.tree;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import com.crystalgraphics.object.CgProperty;
import com.crystalgraphics.object.CgPropertyPath;
import com.crystalgraphics.object.CgType;
import com.crystalgraphics.object.CgTypeKind;
import com.crystalgraphics.object.CgValueType;
import com.crystalgraphics.object.validate.CgIssue;
import com.crystalgraphics.object.validate.CgValidation;
import com.crystalgraphics.object.validate.CgValidator;
import com.crystalgraphics.object.value.CgValueForm;

/**
 * The declared checks of a resolved asset, then each type's {@link CgValidator}s: keys, required keys, shapes,
 * enums, forms, ids and references, as the format's schemas state them.
 */
final class CgAssetValidation {

    private static final Pattern ID = Pattern.compile("[a-z0-9_]{1,32}");
    private static final Pattern REF = Pattern.compile("[a-z0-9_]+:\\S.*");

    private final CgValidation into;

    private CgAssetValidation(CgValidation into) {
        this.into = into;
    }

    static void run(String id, CgType kind, Map<?, ?> resolved, CgValidation into) {
        new CgAssetValidation(into).value(CgValueType.struct(kind), null, resolved, CgPropertyPath.of(id),
                new HashMap<String, CgPropertyPath>());
    }

    /** {@code v} against {@code t}; {@code p} the property it is written under, for its forms. */
    private void value(CgValueType t, @Nullable CgProperty p, @Nullable Object v, CgPropertyPath at, Map<String, CgPropertyPath> ids) {
        if (v == null) return;
        switch (t.kind()) {
            case STRUCT:
                if (t.type() == null) return;
                if (v instanceof Map) map(t.type(), CgShape.of(t, v), (Map<?, ?>) v, at, ids);
                else shape(at, "a map", v);
                return;
            case DISPATCH:
                if (v instanceof Map) member(t, (Map<?, ?>) v, at, ids);
                else shape(at, "a map", v);
                return;
            case ID_LIST:
                if (v instanceof List) elements(t, (List<?>) v, at, ids);
                else shape(at, "a list", v);
                return;
            case LIST: {
                if (!(v instanceof List)) {
                    if (!(v instanceof Map && form(p, (Map<?, ?>) v, at))) shape(at, "a list", v);
                    return;
                }
                List<?> l = (List<?>) v;
                for (int i = 0; i < l.size(); i++) value(t.element(), null, l.get(i), at.index(i), ids);
                return;
            }
            case MAP:
                if (!(v instanceof Map)) {
                    shape(at, "a map", v);
                    return;
                }
                for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                    value(t.element(), null, e.getValue(), at.key(String.valueOf(e.getKey())), ids);
                }
                return;
            case ONE_OF:
                for (CgValueType alt : t.alternatives()) {
                    if (accepts(alt, v)) {
                        value(alt, p, v, at, ids);
                        return;
                    }
                }
                shape(at, "one of " + t, v);
                return;
            case ENUM:
                if (v instanceof Map && form(p, (Map<?, ?>) v, at)) return;
                if (!t.enumValues().contains(v)) {
                    into.error(at, CgIssue.ENUM, "'" + v + "' is not one of " + t.enumValues());
                }
                return;
            case BOOL:
                if (!(v instanceof Boolean) && !(v instanceof Map && form(p, (Map<?, ?>) v, at))) shape(at, "true or false", v);
                return;
            case INT:
                if (v instanceof Map) form(p, (Map<?, ?>) v, at);
                else if (!(v instanceof Integer || v instanceof Long)) shape(at, "a whole number", v);
                return;
            case FLOAT:
                if (v instanceof Map) form(p, (Map<?, ?>) v, at);
                else if (!(v instanceof Number)) shape(at, "a number", v);
                return;
            case VEC2: case VEC3: case VEC4: case COLOUR: case ROTATION: case POSITION:
                if (v instanceof Map) form(p, (Map<?, ?>) v, at);
                else if (!(v instanceof List || v instanceof Number)) shape(at, "a number or a list of them", v);
                return;
            case STRING: case ELEMENT_REF:
                if (!(v instanceof String)) shape(at, "a string", v);
                return;
            case REF:
                if (!(v instanceof String)) shape(at, "an id", v);
                else if (!REF.matcher((String) v).matches()) into.error(at, CgIssue.REF, "'" + v + "' is not namespace:path");
                return;
            case BINARY_REF:
                if (!(v instanceof String)) shape(at, "a path", v);
                return;
            case CURVE:
                if (v instanceof String && !REF.matcher((String) v).matches()) {
                    into.error(at, CgIssue.REF, "'" + v + "' is not namespace:path");
                } else if (!(v instanceof String || v instanceof Map || v instanceof List)) {
                    // A list is the keys alone: a sequence channel's [[tick, value], ...].
                    shape(at, "a curve's id or keys", v);
                }
                return;
            case ARRAY:
                if (!(v instanceof List || v instanceof Map)) shape(at, "a list", v);
                return;
            default:
                // VALUE and TREE: written in any shape.
        }
    }

    /** A struct, an element or a kind: its keys, its required keys, then its validators. */
    private void map(CgType type, CgShape shape, Map<?, ?> m, CgPropertyPath at, Map<String, CgPropertyPath> ids) {
        if (type.ownsIds()) ids = new HashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            String key = String.valueOf(e.getKey());
            if (CgType.UNSUPPORTED.equals(key)) {
                unsupported(e.getValue(), at.key(key));
            } else if (CgType.EDITOR.equals(key) || CgType.REMOVE.equals(key)) {
                continue;
            } else if (shape.props().containsKey(key)) {
                value(shape.props().get(key), shape.property(key), e.getValue(), at.key(key), ids);
            } else if (!type.open()) {
                into.error(at.key(key), CgIssue.UNKNOWN_KEY, "not a key of " + type.id());
            }
        }
        for (CgProperty p : type.properties()) {
            if (p.required() && !m.containsKey(p.name())) {
                into.error(at.key(p.name()), CgIssue.REQUIRED, p.name() + " is required in " + type.id());
            }
        }
        for (CgValidator v : type.validators()) v.validate(m, at, into);
    }

    private void member(CgValueType t, Map<?, ?> m, CgPropertyPath at, Map<String, CgPropertyPath> ids) {
        CgType family = t.type();
        Object key = m.get(family.dispatchKey());
        CgType member = key instanceof String ? family.member((String) key) : null;
        if (member == null) {
            into.error(at.key(family.dispatchKey()), CgIssue.MEMBER, key == null
                    ? "no '" + family.dispatchKey() + "' choosing a member of " + family.id()
                    : family.id() + " has no member '" + key + "'");
            return;
        }
        map(member, CgShape.of(t, m), m, at, ids);
    }

    private void elements(CgValueType t, List<?> list, CgPropertyPath at, Map<String, CgPropertyPath> ids) {
        CgType type = t.type();
        CgValueType element = type.kind() == CgTypeKind.DISPATCH ? CgValueType.dispatch(type) : CgValueType.struct(type);
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            if (!(item instanceof Map)) {
                shape(at.index(i), "an element", item);
                continue;
            }
            Object id = ((Map<?, ?>) item).get(CgType.ID);
            CgPropertyPath here = id instanceof String ? at.item((String) id) : at.index(i);
            if (id instanceof String) {
                if (!ID.matcher((String) id).matches()) {
                    into.error(here.key(CgType.ID), CgIssue.ID, "id '" + id + "' is not 1 to 32 of a-z, 0-9 and _");
                }
                CgPropertyPath first = ids.putIfAbsent((String) id, here);
                if (first != null) into.error(here.key(CgType.ID), CgIssue.DUPLICATE_ID, "id '" + id + "' is also " + first);
            }
            value(element, null, item, here, ids);
        }
    }

    /** {@code unsupported: [ { what, source } ]}: each entry reported, then skipped. */
    private void unsupported(Object v, CgPropertyPath at) {
        if (!(v instanceof List)) return;
        List<?> l = (List<?>) v;
        for (int i = 0; i < l.size(); i++) {
            Object e = l.get(i);
            Object what = e instanceof Map ? ((Map<?, ?>) e).get("what") : e;
            Object source = e instanceof Map ? ((Map<?, ?>) e).get("source") : null;
            into.warning(at.index(i), CgIssue.UNSUPPORTED, "skipped: " + what + (source == null ? "" : " (" + source + ")"));
        }
    }

    /**
     * A map written where a value goes: the form its key names, which {@code p} must take. Answers whether it names
     * one; a map naming none is a struct value, checked by its declaration if it has one.
     */
    private boolean form(@Nullable CgProperty p, Map<?, ?> m, CgPropertyPath at) {
        CgValueForm form = CgValueForm.of(m);
        if (form == CgValueForm.CONSTANT) return false;
        if (p != null && !p.forms().contains(form)) {
            into.error(at, CgIssue.FORM, p.name() + " is written as a " + form.specName() + ", and takes " + names(p));
        }
        return true;
    }

    private static String names(CgProperty p) {
        StringBuilder b = new StringBuilder();
        for (CgValueForm f : p.forms()) b.append(b.length() == 0 ? "" : ", ").append(f.specName());
        return b.toString();
    }

    private void shape(CgPropertyPath at, String expected, Object v) {
        String held = v instanceof Map ? "a map" : v instanceof List ? "a list" : v instanceof String ? "'" + v + "'" : String.valueOf(v);
        into.error(at, CgIssue.SHAPE, "expects " + expected + ", holds " + held);
    }

    /** Whether {@code v} is written in {@code alt}'s shape, as a {@code oneOf} picks: a map holding its required keys. */
    private static boolean accepts(CgValueType alt, Object v) {
        switch (alt.kind()) {
            case STRUCT:
                if (!(v instanceof Map)) return false;
                if (alt.type() == null) return true;
                for (CgProperty p : alt.type().properties()) {
                    if (p.required() && !((Map<?, ?>) v).containsKey(p.name())) return false;
                }
                return true;
            case DISPATCH:
                return v instanceof Map && ((Map<?, ?>) v).containsKey(alt.type().dispatchKey());
            case MAP:
                return v instanceof Map;
            case LIST: case ID_LIST:
                return v instanceof List;
            case ENUM:
                return alt.enumValues().contains(v);
            case STRING: case REF: case BINARY_REF: case ELEMENT_REF:
                return v instanceof String;
            case BOOL:
                return v instanceof Boolean;
            case INT:
                return v instanceof Integer || v instanceof Long;
            case FLOAT:
                return v instanceof Number;
            case NULL:
                return false;   // a null never reaches here
            default:
                return true;
        }
    }
}
