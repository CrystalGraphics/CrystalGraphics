package com.crystalgraphics.asset.tree;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.crystalgraphics.asset.kind.CgCommonStructs;
import com.crystalgraphics.object.CgProperty;
import com.crystalgraphics.object.CgType;
import com.crystalgraphics.object.CgTypeKind;
import com.crystalgraphics.object.CgValueType;

/**
 * What a declaration says of the value written under it: the keys it orders and their types, the type of any other
 * key, and the type of a list's items. The writer orders and styles by it; inheritance merges by it.
 *
 * <pre>{@code
 * CgShape s = CgShape.of(CgValueType.struct(CgParticleKinds.SYSTEM), tree);
 * s.props().keySet();             // [_v, parent, units, …, unsupported, editor, remove]: the order a file writes
 * s.child("emitters");            // id-list<crystalgraphics:system.emitter>
 * CgShape.of(s.child("emitters"), tree.get("emitters")).idItems();   // true: merged by id, written a line each
 * }</pre>
 *
 * <ul>
 *   <li>A value of several shapes ({@link CgValueType#oneOf}, a number that takes every form) takes the first
 *       alternative whose required keys it holds, as the schema's {@code oneOf} reads.</li>
 *   <li>A dispatch member is resolved by its family's key: its {@code id}, the key, then its own fields.</li>
 *   <li>{@link #NONE} orders nothing: a tree kept as written, an open struct's own keys.</li>
 * </ul>
 */
public final class CgShape {

    /** A shape that orders nothing and types nothing. */
    public static final CgShape NONE = new CgShape(Collections.<String, CgValueType>emptyMap(), null, null, false);

    private final Map<String, CgValueType> props;
    @Nullable private final CgValueType extra;
    @Nullable private final CgValueType items;
    private final boolean idItems;

    private CgShape(Map<String, CgValueType> props, @Nullable CgValueType extra, @Nullable CgValueType items, boolean idItems) {
        this.props = props;
        this.extra = extra;
        this.items = items;
        this.idItems = idItems;
    }

    /** The shape {@code type} gives {@code value}; {@code type} null gives {@link #NONE}. */
    public static CgShape of(@Nullable CgValueType type, @Nullable Object value) {
        if (type == null) return NONE;
        switch (type.kind()) {
            case STRUCT:
                return type.type() == null ? NONE : ofType(type.type());
            case DISPATCH:
                return member(type.type(), value);
            case ID_LIST: {
                CgType t = type.type();
                CgValueType element = t.kind() == CgTypeKind.DISPATCH ? CgValueType.dispatch(t) : CgValueType.struct(t);
                return new CgShape(Collections.<String, CgValueType>emptyMap(), null, element, true);
            }
            case LIST:
                return new CgShape(Collections.<String, CgValueType>emptyMap(), null, type.element(), false);
            case MAP:
                return new CgShape(Collections.<String, CgValueType>emptyMap(), type.element(), null, false);
            case ONE_OF:
                for (CgValueType alt : type.alternatives()) {
                    if (fits(alt, value)) return of(alt, value);
                }
                return NONE;
            case FLOAT: case INT: case VEC2: case VEC3: case VEC4: case COLOUR: case ROTATION: case POSITION:
            case VALUE:
                return of(CgCommonStructs.VALUE_FORMS, value);
            case CURVE:
                return of(CgCommonStructs.CURVE, value);
            default:
                return NONE;
        }
    }

    /** The keys it orders, each with its type, in the order a file writes them. */
    public Map<String, CgValueType> props() {
        return props;
    }

    /** The type of the value under {@code key}: a declared key's, else what the shape gives every other key. */
    @Nullable
    public CgValueType child(Object key) {
        CgValueType t = key instanceof String ? props.get(key) : null;
        return t != null ? t : extra;
    }

    /** A list's items' type; null where it is not a list or types nothing. */
    @Nullable
    public CgValueType items() {
        return items;
    }

    /** Whether it is an id list: elements merged by id, written a line each. */
    public boolean idItems() {
        return idItems;
    }

    private static CgShape ofType(CgType t) {
        Map<String, CgValueType> props = new LinkedHashMap<>();
        for (CgProperty p : t.properties()) props.put(p.name(), self(p.type(), t));
        return new CgShape(props, null, null, false);
    }

    /** {@code type} with {@link CgValueType#SELF} as {@code owner}: a condition's {@code not}, its {@code all}. */
    private static CgValueType self(CgValueType type, CgType owner) {
        if (type.kind() == CgValueType.Kind.SELF) return CgValueType.struct(owner);
        if (type.kind() == CgValueType.Kind.LIST && type.element().kind() == CgValueType.Kind.SELF) {
            return CgValueType.listOf(CgValueType.struct(owner));
        }
        return type;
    }

    private static CgShape member(CgType family, @Nullable Object value) {
        Map<String, CgValueType> props = new LinkedHashMap<>();
        props.put(CgType.ID, CgValueType.STRING);
        props.put(family.dispatchKey(), CgValueType.STRING);
        Object key = value instanceof Map ? ((Map<?, ?>) value).get(family.dispatchKey()) : null;
        CgType member = key instanceof String ? family.member((String) key) : null;
        if (member != null) {
            for (CgProperty p : member.properties()) {
                if (!props.containsKey(p.name())) props.put(p.name(), self(p.type(), member));
            }
        }
        return new CgShape(props, null, null, false);
    }

    /** Whether {@code value} is written in alternative {@code alt}'s shape: a map holding its required keys. */
    private static boolean fits(CgValueType alt, @Nullable Object value) {
        if (value instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) value;
            switch (alt.kind()) {
                case STRUCT:
                    if (alt.type() == null) return true;
                    for (CgProperty p : alt.type().properties()) {
                        if (p.required() && !m.containsKey(p.name())) return false;
                    }
                    return true;
                case DISPATCH:
                    return m.containsKey(alt.type().dispatchKey());
                case MAP: case ONE_OF: case TREE: case VALUE:
                    return true;
                default:
                    return false;
            }
        }
        if (value instanceof List) {
            switch (alt.kind()) {
                case LIST: case ID_LIST: case ARRAY: case VEC2: case VEC3: case VEC4:
                    return true;
                default:
                    return false;
            }
        }
        switch (alt.kind()) {
            case STRUCT: case MAP: case DISPATCH: case LIST: case ID_LIST: case ARRAY:
                return false;
            default:
                return true;
        }
    }
}
