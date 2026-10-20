package com.crystalgraphics.asset.tree;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.crystalgraphics.object.CgPropertyPath;
import com.crystalgraphics.object.CgType;

/** Random edits on an asset, for the undo gates: every value, element and list it can find, set, reset, moved or dropped. */
public final class CgRandomEdits {

    private CgRandomEdits() {
    }

    /** {@code count} edits that apply, in order; answers their inverses in the order applied. */
    public static List<CgEdit> run(CgAssetData d, Random random, int count) {
        List<CgEdit> undo = new ArrayList<>();
        int tries = 0;
        while (undo.size() < count && tries++ < count * 20) {
            List<CgPropertyPath> values = new ArrayList<>(), elements = new ArrayList<>();
            collect(d.root(), CgPropertyPath.of(d.id()), values, elements);
            CgEdit e = pick(d, random, values, elements, undo.size());
            if (e == null) continue;
            try {
                undo.add(d.apply(e));
            } catch (CgAssetException refused) {
                // an edit the asset cannot take (a move of an inherited element): another is picked
            }
        }
        return undo;
    }

    private static CgEdit pick(CgAssetData d, Random r, List<CgPropertyPath> values, List<CgPropertyPath> elements, int n) {
        int what = r.nextInt(6);
        if ((what < 2 || elements.isEmpty()) && !values.isEmpty()) {
            CgPropertyPath p = values.get(r.nextInt(values.size()));
            if (what == 1) return CgEdit.reset(p);
            return CgEdit.set(p, changed(d, p, r));
        }
        if (elements.isEmpty()) return null;
        CgPropertyPath el = elements.get(r.nextInt(elements.size()));
        String id = el.last().name();
        switch (what) {
            case 2:
                return r.nextBoolean() ? CgEdit.disable(el) : CgEdit.enable(el);
            case 3:
                return CgEdit.remove(el);
            case 4: {
                Map<Object, Object> copy = new LinkedHashMap<>();
                copy.put(CgType.ID, "zz" + n + "x" + r.nextInt(1000));
                copy.put("name", "added");
                return r.nextBoolean() ? CgEdit.insertAfter(el.parent(), copy, id) : CgEdit.insert(el.parent(), copy);
            }
            default: {
                CgPropertyPath other = elements.get(r.nextInt(elements.size()));
                if (!other.parent().equals(el.parent())) return null;
                return CgEdit.moveBefore(el, other.last().name());
            }
        }
    }

    /** A value of the same kind as the one there, different from it. */
    private static Object changed(CgAssetData d, CgPropertyPath p, Random r) {
        CgAssetNode n = d.root();
        for (CgPropertyPath.Segment s : p.segments()) {
            n = n.kind() == CgAssetNode.Kind.ID_LIST ? n.element(s.name()) : n.get(s.name());
        }
        Object v = n.value();
        if (v instanceof Boolean) return !(Boolean) v;
        if (v instanceof Integer) return (Integer) v + 1 + r.nextInt(5);
        if (v instanceof Number) return ((Number) v).doubleValue() + 0.25 * (1 + r.nextInt(8));
        if (v instanceof String) return v + "_e" + r.nextInt(100);
        if (v instanceof List) {
            List<Object> l = new ArrayList<>((List<?>) v);
            if (!l.isEmpty()) l.remove(l.size() - 1);
            return l;
        }
        return 1.5;
    }

    private static void collect(CgAssetNode n, CgPropertyPath at, List<CgPropertyPath> values, List<CgPropertyPath> elements) {
        if (n.kind() == CgAssetNode.Kind.MAP) {
            for (Object k : n.keys()) {
                if (CgType.ID.equals(k) || CgType.PARENT.equals(k) || CgType.VERSION.equals(k) || CgType.REMOVE.equals(k)
                        || CgType.UNITS.equals(k)) {
                    continue;
                }
                CgAssetNode c = n.get(k);
                CgPropertyPath p = at.key(String.valueOf(k));
                if (c.kind() == CgAssetNode.Kind.VALUE) values.add(p);
                else collect(c, p, values, elements);
            }
        } else if (n.kind() == CgAssetNode.Kind.ID_LIST) {
            for (CgAssetNode e : n.elements()) {
                if (e.origin() == CgOrigin.REMOVED || e.id() == null) continue;
                CgPropertyPath p = at.item(e.id());
                elements.add(p);
                collect(e, p, values, elements);
            }
        }
    }
}
