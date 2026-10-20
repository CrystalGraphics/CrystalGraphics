package com.crystalgraphics.asset.tree;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.crystalgraphics.object.CgProperty;
import com.crystalgraphics.object.CgPropertyFlag;
import com.crystalgraphics.object.CgPropertyPath;
import com.crystalgraphics.object.CgType;
import com.crystalgraphics.object.CgValueType;

/**
 * Applies {@link CgEdit}s to an asset's file and re-resolves it. An edit walks the file beside the resolved tree,
 * making what an override needs on the way: a map the parent holds, an {@code { id }} stub for an inherited element,
 * a copy of a plain value being edited inside.
 */
final class CgAssetEdits {

    private static final Object ABSENT = new Object(), ORDER = new Object();

    private CgAssetEdits() {
    }

    /** {@code e} applied: the edit as applied, with its {@code before}, and its inverse. */
    static CgEdit[] apply(CgAssetData d, CgEdit e) {
        List<CgPropertyPath.Segment> segs = e.path().segments();
        if (!e.path().asset().equals(d.id())) throw new CgAssetException(e + ": not an edit of " + d.id());
        if (segs.isEmpty() || segs.get(0).item()) throw new CgAssetException(e + ": an edit starts at a key of the asset");
        Map<Object, Object> snapshot = snapshot(d.file, segs.get(0).name());
        Object before = valueAt(d, e);
        try {
            if (e.restore != null) restore(d.file, e.restore);
            else perform(d, e);
            d.resolve();
        } catch (RuntimeException failed) {
            restore(d.file, snapshot);
            d.resolve();
            throw failed instanceof CgAssetException ? failed : new CgAssetException(e + ": " + failed.getMessage());
        }
        CgEdit applied = new CgEdit(e.path(), e.op(), before, e.after(), e.anchor(), e.anchorAfter(), e.isInteractive(), null);
        return new CgEdit[] {applied, inverse(e, before, snapshot)};
    }

    /** What {@code e} costs a player of the asset: the deepest declared property it touches, by its flags. */
    static CgChange change(CgAssetData d, CgEdit e) {
        List<CgPropertyPath.Segment> segs = e.path().segments();
        if (e.op() != CgEdit.Op.SET && e.op() != CgEdit.Op.RESET && e.op() != CgEdit.Op.INSERT) {
            segs = segs.subList(0, segs.size() - 1);   // an element's move, removal or switch is its list's change
        }
        CgValueType type = CgValueType.struct(d.kind());
        CgAssetNode n = d.root();
        CgProperty deepest = null;
        for (CgPropertyPath.Segment s : segs) {
            CgShape shape = CgShape.of(type, shallow(n));
            if (!s.item()) {
                CgProperty p = shape.property(s.name());
                if (p != null) deepest = p;
                type = shape.child(s.name());
                n = n != null && n.kind() == CgAssetNode.Kind.MAP ? n.get(s.name()) : null;
            } else {
                type = shape.items();
                n = n != null && n.kind() == CgAssetNode.Kind.ID_LIST ? n.element(s.name()) : null;
            }
        }
        return deepest != null && deepest.has(CgPropertyFlag.STRUCTURAL) ? CgChange.STRUCTURAL : CgChange.VALUE;
    }

    private static void perform(CgAssetData d, CgEdit e) {
        List<CgPropertyPath.Segment> segs = e.path().segments();
        List<CgPropertyPath.Segment> up = segs.subList(0, segs.size() - 1);
        CgPropertyPath.Segment last = segs.get(segs.size() - 1);
        switch (e.op()) {
            case SET:
                set(walk(d, up, true, e), last, CgAssetData.copy(e.after()), e);
                return;
            case RESET: {
                Object c = walk(d, up, false, e);
                if (c != null) unset(c, last);
                return;
            }
            case ENABLE: case DISABLE: {
                Object el = walk(d, segs, true, e);
                if (!(el instanceof Map)) throw new CgAssetException(e + ": not an element");
                CgAssetData.map(el).put("enabled", e.op() == CgEdit.Op.ENABLE);
                return;
            }
            case INSERT:
                insert(d, e);
                return;
            case REMOVE:
                remove(d, e, up, last);
                return;
            case MOVE:
                move(d, e, up, last);
                return;
            default:
                throw new IllegalStateException(e.op().name());
        }
    }

    private static void insert(CgAssetData d, CgEdit e) {
        if (!(e.after() instanceof Map) || !(((Map<?, ?>) e.after()).get(CgType.ID) instanceof String)) {
            throw new CgAssetException(e + ": an inserted element is a map with an id");
        }
        CgAssetNode list = node(d, e.path().segments());
        String id = (String) ((Map<?, ?>) e.after()).get(CgType.ID);
        if (list != null && list.element(id) != null) throw new CgAssetException(e + ": the list already holds " + id);
        Object c = walk(d, e.path().segments(), true, e);
        if (!(c instanceof List)) throw new CgAssetException(e + ": not a list");
        List<Object> l = CgAssetData.list(c);
        Map<Object, Object> element = CgAssetData.map(CgAssetData.copy(e.after()));
        if (e.anchor() == null) {
            l.add(element);
            return;
        }
        int at = indexOf(l, e.anchor());
        if (at >= 0) {
            l.add(e.anchorAfter() ? at + 1 : at, element);
        } else if (d.parent() != null) {
            element.put(e.anchorAfter() ? "after" : "before", e.anchor());   // placed against an inherited element
            l.add(element);
        } else {
            throw new CgAssetException(e + ": the list holds no " + e.anchor());
        }
    }

    private static void remove(CgAssetData d, CgEdit e, List<CgPropertyPath.Segment> up, CgPropertyPath.Segment last) {
        CgAssetNode el = node(d, e.path().segments());
        if (el == null || el.origin == CgOrigin.REMOVED) throw new CgAssetException(e + ": no such element");
        Object c = walk(d, up, true, e);
        List<Object> l = CgAssetData.list(c);
        int at = indexOf(l, last.name());
        if (at >= 0) l.remove(at);
        if (d.parent() == null || el.origin == CgOrigin.ADDED) return;
        Object remove = d.file.get(CgType.REMOVE);
        if (!(remove instanceof List)) {
            remove = new ArrayList<>();
            d.file.put(CgType.REMOVE, remove);
        }
        CgAssetData.list(remove).add(last.name());
    }

    private static void move(CgAssetData d, CgEdit e, List<CgPropertyPath.Segment> up, CgPropertyPath.Segment last) {
        CgAssetNode el = node(d, e.path().segments());
        if (el == null) throw new CgAssetException(e + ": no such element");
        if (d.parent() != null && el.origin != CgOrigin.ADDED) {
            throw new CgAssetException(e + ": an inherited element keeps its parent's place");
        }
        List<Object> l = CgAssetData.list(walk(d, up, false, e));
        int from = indexOf(l, last.name());
        Map<Object, Object> moved = CgAssetData.map(l.remove(from));
        int at = indexOf(l, e.anchor());
        if (at >= 0) {
            l.add(e.anchorAfter() ? at + 1 : at, moved);
            return;
        }
        if (d.parent() == null) {
            l.add(from, moved);
            throw new CgAssetException(e + ": the list holds no " + e.anchor());
        }
        moved.remove("after");
        moved.remove("before");
        moved.put(e.anchorAfter() ? "after" : "before", e.anchor());
        l.add(from, moved);
    }

    /**
     * The file's object at {@code segs}, made where {@code create} and missing: a map, an id list, a stub for an
     * inherited element, or a copy of an inherited plain value.
     */
    @Nullable
    private static Object walk(CgAssetData d, List<CgPropertyPath.Segment> segs, boolean create, CgEdit e) {
        Object f = d.file;
        CgAssetNode n = d.root();
        for (CgPropertyPath.Segment s : segs) {
            if (!s.item()) {
                if (!(f instanceof Map)) throw new CgAssetException(e + ": " + s.name() + " is under no map");
                Map<Object, Object> m = CgAssetData.map(f);
                Object key = keyOf(m, n, s.name());
                CgAssetNode next = n != null && n.kind() == CgAssetNode.Kind.MAP ? n.get(key) : null;
                Object c = m.get(key);
                if (c == null) {
                    if (!create) return null;
                    c = fresh(next);
                    m.put(key, c);
                }
                f = c;
                n = next;
            } else {
                if (!(f instanceof List)) throw new CgAssetException(e + ": [" + s.name() + "] is in no list");
                List<Object> l = CgAssetData.list(f);
                if (n != null && n.kind() == CgAssetNode.Kind.ID_LIST) {
                    CgAssetNode el = n.element(s.name());
                    if (el == null) throw new CgAssetException(e + ": the list holds no " + s.name());
                    int at = indexOf(l, s.name());
                    if (at < 0) {
                        if (!create) return null;
                        Map<Object, Object> stub = new LinkedHashMap<>();
                        stub.put(CgType.ID, s.name());
                        l.add(stub);
                        at = l.size() - 1;
                    }
                    f = l.get(at);
                    n = el;
                } else {
                    f = l.get(index(s, e, l.size()));
                    n = null;
                }
            }
        }
        return f;
    }

    private static Object fresh(@Nullable CgAssetNode next) {
        if (next == null || next.kind() == CgAssetNode.Kind.MAP) return new LinkedHashMap<>();
        if (next.kind() == CgAssetNode.Kind.ID_LIST) return new ArrayList<>();
        return CgAssetData.copy(next.value);
    }

    private static void set(@Nullable Object c, CgPropertyPath.Segment last, Object value, CgEdit e) {
        if (c instanceof Map && !last.item()) {
            Map<Object, Object> m = CgAssetData.map(c);
            m.put(keyOf(m, null, last.name()), value);
        } else if (c instanceof List && last.item()) {
            List<Object> l = CgAssetData.list(c);
            int at = indexOf(l, last.name());
            if (at >= 0) l.set(at, value);
            else l.set(index(last, e, l.size()), value);
        } else {
            throw new CgAssetException(e + ": nothing to set there");
        }
    }

    private static void unset(Object c, CgPropertyPath.Segment last) {
        if (c instanceof Map && !last.item()) {
            Map<Object, Object> m = CgAssetData.map(c);
            m.remove(keyOf(m, null, last.name()));
        } else if (c instanceof List && last.item()) {
            List<Object> l = CgAssetData.list(c);
            int at = indexOf(l, last.name());
            if (at >= 0) l.remove(at);
        }
    }

    /** The map's key spelled {@code name}: a YAML key may be a number. */
    private static Object keyOf(Map<Object, Object> m, @Nullable CgAssetNode n, String name) {
        if (m.containsKey(name)) return name;
        for (Object k : m.keySet()) {
            if (String.valueOf(k).equals(name)) return k;
        }
        if (n != null) {
            for (Object k : n.fields.keySet()) {
                if (String.valueOf(k).equals(name)) return k;
            }
        }
        return name;
    }

    private static int indexOf(List<Object> l, @Nullable String id) {
        if (id == null) return -1;
        for (int i = 0; i < l.size(); i++) {
            Object x = l.get(i);
            if (x instanceof Map && id.equals(String.valueOf(((Map<?, ?>) x).get(CgType.ID)))) return i;
        }
        return -1;
    }

    private static int index(CgPropertyPath.Segment s, CgEdit e, int size) {
        try {
            int i = Integer.parseInt(s.name());
            if (i >= 0 && i < size) return i;
        } catch (NumberFormatException notAnIndex) {
            // reported below
        }
        throw new CgAssetException(e + ": [" + s.name() + "] is not an index of a list of " + size);
    }

    /** The resolved node at {@code segs}, or null. */
    @Nullable
    private static CgAssetNode node(CgAssetData d, List<CgPropertyPath.Segment> segs) {
        CgAssetNode n = d.root();
        for (CgPropertyPath.Segment s : segs) {
            if (n == null) return null;
            n = n.kind() == CgAssetNode.Kind.ID_LIST ? n.element(s.name()) : n.get(s.name());
        }
        return n;
    }

    /** The resolved value an edit replaces: a copy, or null where there is none. */
    @Nullable
    private static Object valueAt(CgAssetData d, CgEdit e) {
        if (e.op() == CgEdit.Op.INSERT) return null;
        CgAssetNode n = d.root();
        Object plain = null;
        for (CgPropertyPath.Segment s : e.path().segments()) {
            if (n != null) {
                if (n.kind() == CgAssetNode.Kind.VALUE) {
                    plain = n.value;
                    n = null;
                } else {
                    n = n.kind() == CgAssetNode.Kind.ID_LIST ? n.element(s.name()) : n.get(s.name());
                    if (n == null) return null;
                    continue;
                }
            }
            if (plain instanceof Map) plain = ((Map<?, ?>) plain).get(s.name());
            else if (plain instanceof List) {
                int i;
                try {
                    i = Integer.parseInt(s.name());
                } catch (NumberFormatException e2) {
                    return null;
                }
                plain = i >= 0 && i < ((List<?>) plain).size() ? ((List<?>) plain).get(i) : null;
            } else {
                return null;
            }
        }
        return CgAssetData.copy(n != null ? n.toPlain() : plain);
    }

    private static CgEdit inverse(CgEdit e, @Nullable Object before, Map<Object, Object> snapshot) {
        CgPropertyPath path = e.path();
        CgEdit.Op op;
        switch (e.op()) {
            case SET: op = before == null ? CgEdit.Op.RESET : CgEdit.Op.SET; break;
            case RESET: op = CgEdit.Op.SET; break;
            case INSERT:
                op = CgEdit.Op.REMOVE;
                path = path.item(String.valueOf(((Map<?, ?>) e.after()).get(CgType.ID)));
                break;
            case REMOVE:
                op = CgEdit.Op.INSERT;
                path = path.parent();
                break;
            case ENABLE: op = CgEdit.Op.DISABLE; break;
            case DISABLE: op = CgEdit.Op.ENABLE; break;
            default: op = e.op();
        }
        return new CgEdit(path, op, e.after(), before, e.anchor(), e.anchorAfter(), e.isInteractive(), snapshot);
    }

    /** The file's top-level key {@code top} and its {@code remove:}, as they are: what restores it exactly. */
    private static Map<Object, Object> snapshot(Map<Object, Object> file, Object top) {
        Map<Object, Object> s = new LinkedHashMap<>();
        for (Object k : new Object[] {top, CgType.REMOVE}) {
            Object key = keyOf(file, null, String.valueOf(k));
            s.put(key, file.containsKey(key) ? CgAssetData.copy(file.get(key)) : ABSENT);
        }
        s.put(ORDER, new ArrayList<>(file.keySet()));
        return s;
    }

    /** The snapshot's keys back as they were, in the file's order then: a key no declaration orders keeps its place. */
    private static void restore(Map<Object, Object> file, Map<Object, Object> snapshot) {
        for (Map.Entry<Object, Object> e : snapshot.entrySet()) {
            if (e.getKey() == ORDER) continue;
            if (e.getValue() == ABSENT) file.remove(e.getKey());
            else file.put(e.getKey(), CgAssetData.copy(e.getValue()));
        }
        Map<Object, Object> now = new LinkedHashMap<>(file);
        file.clear();
        for (Object k : CgAssetData.list(snapshot.get(ORDER))) {
            if (now.containsKey(k)) file.put(k, now.remove(k));
        }
        file.putAll(now);
    }

    /** A node's keys, with its values where they are values: what a shape is chosen by. */
    @Nullable
    private static Map<Object, Object> shallow(@Nullable CgAssetNode n) {
        if (n == null || n.kind() != CgAssetNode.Kind.MAP) return null;
        Map<Object, Object> m = new LinkedHashMap<>();
        for (Map.Entry<Object, CgAssetNode> f : n.fields.entrySet()) {
            m.put(f.getKey(), f.getValue().kind() == CgAssetNode.Kind.VALUE ? f.getValue().value : Collections.emptyMap());
        }
        return m;
    }
}
