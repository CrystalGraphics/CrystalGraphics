package com.crystalgraphics.asset.tree;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.annotation.Nullable;

import com.crystalgraphics.object.CgType;
import com.crystalgraphics.object.CgValueType;

/**
 * An asset in memory: its file resolved over its parent's, every node knowing where its value comes from
 * (blueprint 01 §3.3–3.4, {@code spec/core.md} § Inheritance). One tree for every kind; a kind's facade reads it.
 *
 * <pre>{@code
 * CgAssetData charge = CgAssetData.of("dragonforge:kamehameha/charge", SYSTEM, chargeTree, null);
 * CgAssetData big = CgAssetData.of("dragonforge:kamehameha/charge_big", SYSTEM, bigTree, charge);
 * big.resolved();     // the parent with the child's delta applied
 * big.delta();        // what the child's file says, and nothing else: it writes back to the same bytes
 * big.origin("emitters", "h1");   // INHERITED; "r7m2n0qe" is ADDED, a removed id REMOVED
 * }</pre>
 *
 * <ul>
 *   <li>Maps merge by key; id lists by {@code id}, a new id placed by {@code after:} or {@code before:}, else
 *       appended; {@code remove:} drops inherited elements; plain lists and value forms replace whole.</li>
 *   <li>A child's {@code units} are its parent's: declaring others throws.</li>
 *   <li>The parent must be resolved first, and of the same kind.</li>
 * </ul>
 */
public final class CgAssetData {

    private static final String[] FORM_KEYS = {"range", "pick", "curve", "curves", "gradient", "array", "bind", "fn",
            "mesh", "texture", "field", "unit"};

    private final String id;
    private final CgType kind;
    @Nullable private final CgAssetData parent;
    private final CgAssetNode root;

    private CgAssetData(String id, CgType kind, @Nullable CgAssetData parent, CgAssetNode root) {
        this.id = id;
        this.kind = kind;
        this.parent = parent;
        this.root = root;
    }

    /**
     * {@code tree}, a file's plain tree, resolved over {@code parent}.
     *
     * @throws CgAssetException if the file names another parent, declares other units, or removes an id it does not
     *                          inherit
     */
    public static CgAssetData of(String id, CgType kind, Map<?, ?> tree, @Nullable CgAssetData parent) {
        Object named = tree.get(CgType.PARENT);
        if (parent == null) {
            if (named != null) throw new CgAssetException(id + " names parent " + named + ", which is not loaded");
            CgAssetNode root = build(tree, CgValueType.struct(kind), CgOrigin.OVERRIDDEN, id);
            return new CgAssetData(id, kind, null, root);
        }
        if (!parent.id.equals(named)) {
            throw new CgAssetException(id + " names parent " + named + ", not " + parent.id);
        }
        if (parent.kind != kind) throw new CgAssetException(id + " is a " + kind.id() + "; its parent " + parent.id + " is not");
        CgAssetNode root = parent.root.inherit(parent.id);
        Object units = tree.get(CgType.UNITS);
        CgAssetNode theirs = root.get(CgType.UNITS);
        if (units != null && theirs != null && !units.equals(theirs.toPlain())) {
            throw new CgAssetException(id + " declares units " + units + "; its parent " + parent.id + "'s are "
                    + theirs.toPlain() + ", and a child's units are its parent's");
        }
        CgAssetData data = new CgAssetData(id, kind, parent, root);
        data.apply(root, tree, CgShape.of(CgValueType.struct(kind), tree), true);
        Object remove = tree.get(CgType.REMOVE);
        if (remove instanceof List) {
            for (Object r : (List<?>) remove) {
                if (!(r instanceof String) || !data.removeElement(root, (String) r)) {
                    throw new CgAssetException(id + " removes " + r + ", which " + parent.id + " does not hold");
                }
            }
        }
        return data;
    }

    /** Its id, {@code namespace:path}. */
    public String id() {
        return id;
    }

    /** Its kind. */
    public CgType kind() {
        return kind;
    }

    /** The asset it inherits from, resolved; null for a root asset. */
    @Nullable
    public CgAssetData parent() {
        return parent;
    }

    /** The resolved tree's root. */
    public CgAssetNode root() {
        return root;
    }

    /** The resolved tree, plain, removed elements dropped. */
    @SuppressWarnings("unchecked")
    public Map<Object, Object> resolved() {
        return (Map<Object, Object>) root.toPlain();
    }

    /** What this asset's file says: its overrides and additions, in the file's order, and nothing it inherits. */
    @SuppressWarnings("unchecked")
    public Map<Object, Object> delta() {
        return parent == null ? (Map<Object, Object>) root.toPlain() : delta(root);
    }

    /**
     * Where the value at {@code path} comes from: map keys and element ids from the root. A path nothing sets is
     * {@link CgOrigin#DEFAULT}; an inherited element this asset disabled is {@link CgOrigin#DISABLED}.
     */
    public CgOrigin origin(String... path) {
        CgAssetNode n = at(path);
        if (n == null) return CgOrigin.DEFAULT;
        if (n.origin == CgOrigin.INHERITED && n.kind() == CgAssetNode.Kind.MAP && n.id() != null) {
            CgAssetNode enabled = n.get("enabled");
            if (enabled != null && enabled.origin == CgOrigin.OVERRIDDEN && Boolean.FALSE.equals(enabled.value)) {
                return CgOrigin.DISABLED;
            }
        }
        return n.origin;
    }

    /** The asset that set an inherited value at {@code path}, or null. */
    @Nullable
    public String from(String... path) {
        CgAssetNode n = at(path);
        return n == null ? null : n.from;
    }

    @Nullable
    private CgAssetNode at(String... path) {
        CgAssetNode n = root;
        for (String p : path) {
            if (n == null) return null;
            n = n.kind() == CgAssetNode.Kind.ID_LIST ? n.element(p) : n.get(p);
        }
        return n;
    }

    // ── The merge ────────────────────────────────────────────────────────────────────────

    private void apply(CgAssetNode target, Map<?, ?> child, CgShape shape, boolean top) {
        for (Map.Entry<?, ?> e : child.entrySet()) {
            Object k = e.getKey();
            Object v = e.getValue();
            target.said.add(k);
            CgValueType type = shape.child(k);
            CgAssetNode existing = target.fields.get(k);
            CgShape sub = CgShape.of(type, v);
            if (v instanceof List && sub.idItems() && existing != null && existing.kind() == CgAssetNode.Kind.ID_LIST
                    && elements((List<?>) v)) {
                mergeList(existing, (List<?>) v, sub.items());
            } else if (v instanceof Map && existing != null && existing.kind() == CgAssetNode.Kind.MAP
                    && !(top && CgType.REMOVE.equals(k)) && mergeable(type, existing, (Map<?, ?>) v)) {
                apply(existing, (Map<?, ?>) v, CgShape.of(type, merged(existing, (Map<?, ?>) v)), false);
            } else {
                CgAssetNode n = build(v, type, CgOrigin.OVERRIDDEN, id);
                n.replaced = true;
                target.fields.put(k, n);
            }
        }
    }

    private void mergeList(CgAssetNode list, List<?> child, @Nullable CgValueType elementType) {
        for (Object o : child) {
            Map<?, ?> e = (Map<?, ?>) o;
            String eid = String.valueOf(e.get(CgType.ID));
            list.said.add(eid);
            CgAssetNode existing = list.element(eid);
            if (existing != null) {
                apply(existing, e, CgShape.of(elementType, merged(existing, e)), false);
                continue;
            }
            CgAssetNode n = build(e, elementType, CgOrigin.ADDED, id);
            n.replaced = true;
            list.elements.add(place(list, e), n);
        }
    }

    /** Where a new element goes: after or before the id it names, else at the end. */
    private int place(CgAssetNode list, Map<?, ?> e) {
        Object after = e.get("after"), before = e.get("before");
        for (int i = 0; i < list.elements.size(); i++) {
            String at = list.elements.get(i).id();
            if (after != null && after.equals(at)) return i + 1;
            if (before != null && before.equals(at)) return i;
        }
        if (after != null || before != null) {
            throw new CgAssetException(id + ": element " + e.get(CgType.ID) + " is placed by " + (after != null
                    ? "after: " + after : "before: " + before) + ", an id its list does not hold");
        }
        return list.elements.size();
    }

    private boolean removeElement(CgAssetNode n, String target) {
        for (CgAssetNode e : n.elements) {
            if (target.equals(e.id()) && e.origin != CgOrigin.ADDED) {
                e.origin = CgOrigin.REMOVED;
                e.from = id;
                return true;
            }
        }
        for (CgAssetNode c : n.fields.values()) {
            if (removeElement(c, target)) return true;
        }
        for (CgAssetNode e : n.elements) {
            if (removeElement(e, target)) return true;
        }
        return false;
    }

    /** Maps merge by key, but a value form ({@code { range: [...] }}) is a value, and a family member keeps its kind. */
    private static boolean mergeable(@Nullable CgValueType type, CgAssetNode existing, Map<?, ?> v) {
        if (valueLike(type) && (isForm(v) || isForm(existing.fields))) return false;
        if (type != null && type.kind() == CgValueType.Kind.DISPATCH) {
            String key = type.type().dispatchKey();
            CgAssetNode was = existing.get(key);
            return !v.containsKey(key) || (was != null && Objects.equals(v.get(key), was.value));
        }
        return true;
    }

    /** Whether {@code type} holds a value, where a map naming a form is that value: untyped counts. */
    private static boolean valueLike(@Nullable CgValueType type) {
        if (type == null) return true;
        switch (type.kind()) {
            case STRUCT: case DISPATCH: case MAP: case ID_LIST: case LIST:
                return false;
            case ONE_OF:
                for (CgValueType alt : type.alternatives()) {
                    if (valueLike(alt)) return true;
                }
                return false;
            default:
                return true;
        }
    }

    private static boolean isForm(Map<?, ?> m) {
        for (String k : FORM_KEYS) {
            if (m.containsKey(k)) return true;
        }
        return false;
    }

    private static boolean elements(List<?> list) {
        for (Object o : list) {
            if (!(o instanceof Map) || !((Map<?, ?>) o).containsKey(CgType.ID)) return false;
        }
        return true;
    }

    /** What a dispatch member's shape is read from: the child's keys over the inherited ones. */
    private static Map<Object, Object> merged(CgAssetNode existing, Map<?, ?> child) {
        Map<Object, Object> m = new LinkedHashMap<>();
        for (Map.Entry<Object, CgAssetNode> e : existing.fields.entrySet()) {
            if (e.getValue().kind() == CgAssetNode.Kind.VALUE) m.put(e.getKey(), e.getValue().value);
        }
        m.putAll(child);
        return m;
    }

    /** A file's subtree as nodes, every one set by {@code asset}. */
    private static CgAssetNode build(Object v, @Nullable CgValueType type, CgOrigin origin, String asset) {
        CgShape shape = CgShape.of(type, v);
        if (v instanceof Map && !(valueLike(type) && isForm((Map<?, ?>) v))) {
            CgAssetNode n = new CgAssetNode(CgAssetNode.Kind.MAP, origin, null);
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                n.fields.put(e.getKey(), build(e.getValue(), shape.child(e.getKey()), origin == CgOrigin.ADDED
                        ? CgOrigin.ADDED : CgOrigin.OVERRIDDEN, asset));
            }
            return n;
        }
        if (v instanceof List && shape.idItems() && elements((List<?>) v)) {
            CgAssetNode n = new CgAssetNode(CgAssetNode.Kind.ID_LIST, origin, null);
            for (Object e : (List<?>) v) n.elements.add(build(e, shape.items(), CgOrigin.ADDED, asset));
            return n;
        }
        CgAssetNode n = new CgAssetNode(CgAssetNode.Kind.VALUE, origin, null);
        n.value = v;
        return n;
    }

    // ── The delta ────────────────────────────────────────────────────────────────────────

    private static Map<Object, Object> delta(CgAssetNode map) {
        Map<Object, Object> out = new LinkedHashMap<>();
        for (Object k : map.said) out.put(k, deltaOf(map.fields.get(k)));
        return out;
    }

    private static Object deltaOf(CgAssetNode n) {
        if (n.replaced) return n.toPlain();
        if (n.kind() == CgAssetNode.Kind.MAP) return delta(n);
        List<Object> out = new ArrayList<>();
        for (Object eid : n.said) {
            CgAssetNode e = n.element((String) eid);
            out.add(e.replaced ? e.toPlain() : delta(e));
        }
        return out;
    }
}
