package com.crystalgraphics.asset.load;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.annotation.Nullable;

import com.crystalgraphics.asset.tree.CgEdit;
import com.crystalgraphics.object.CgPropertyPath;
import com.crystalgraphics.object.CgType;

/**
 * A saved file diffed against the file it replaces, as the edits that turn one into the other: so a hand edit in a
 * text editor and a slider in the editor take one path and are classed by the same flags.
 *
 * <pre>{@code
 * List<CgEdit> edits = CgAssetFileDiff.diff(id, live.delta(), savedTree);
 * if (edits == null) rebuildWhole();             // a parent, units or removal changed: not expressible as edits
 * else assets.edit(ref, edits);
 * }</pre>
 *
 * <ul>
 *   <li>Maps recurse by key; a key gone is a reset. Id lists diff by id: removed, moved, inserted after their
 *       predecessor, and their common elements recursed; {@code enabled} becomes enable or disable.</li>
 *   <li>Plain lists, value forms and scalars are set whole.</li>
 *   <li>{@code _v} is ignored; a change of {@code parent}, {@code units}, {@code remove}, or an element's
 *       {@code after}/{@code before}, answers null.</li>
 * </ul>
 */
public final class CgAssetFileDiff {

    private static final String[] FORM_KEYS = {"range", "pick", "curve", "curves", "gradient", "array", "bind", "fn",
            "mesh", "texture", "field", "unit"};
    private static final String[] WHOLE = {CgType.PARENT, CgType.UNITS, CgType.REMOVE};

    private CgAssetFileDiff() {
    }

    /** The edits turning file {@code before} of asset {@code id} into {@code after}; null where none can. */
    @Nullable
    public static List<CgEdit> diff(String id, Map<?, ?> before, Map<?, ?> after) {
        for (String whole : WHOLE) {
            if (!Objects.equals(before.get(whole), after.get(whole))) return null;
        }
        List<CgEdit> out = new ArrayList<>();
        Map<Object, Object> b = new LinkedHashMap<>(before), a = new LinkedHashMap<>(after);
        b.remove("_v");
        a.remove("_v");
        for (String skip : WHOLE) {
            b.remove(skip);
            a.remove(skip);
        }
        return map(CgPropertyPath.of(id), b, a, out) ? out : null;
    }

    private static boolean map(CgPropertyPath at, Map<?, ?> b, Map<?, ?> a, List<CgEdit> out) {
        for (Map.Entry<?, ?> e : a.entrySet()) {
            Object was = b.get(e.getKey()), now = e.getValue();
            if (Objects.equals(was, now)) continue;
            if (!value(at.key(String.valueOf(e.getKey())), was, now, out)) return false;
        }
        for (Object key : b.keySet()) {
            if (!a.containsKey(key)) out.add(CgEdit.reset(at.key(String.valueOf(key))));
        }
        return true;
    }

    private static boolean value(CgPropertyPath at, @Nullable Object was, Object now, List<CgEdit> out) {
        if (was instanceof Map && now instanceof Map && !isForm((Map<?, ?>) was) && !isForm((Map<?, ?>) now)) {
            return map(at, (Map<?, ?>) was, (Map<?, ?>) now, out);
        }
        if (was instanceof List && now instanceof List && idList((List<?>) was) && idList((List<?>) now)) {
            return elements(at, (List<?>) was, (List<?>) now, out);
        }
        out.add(CgEdit.set(at, now));
        return true;
    }

    private static boolean elements(CgPropertyPath list, List<?> was, List<?> now, List<CgEdit> out) {
        Map<String, Map<?, ?>> before = byId(was), after = byId(now);
        for (String id : before.keySet()) {
            if (!after.containsKey(id)) out.add(CgEdit.remove(list.item(id)));
        }
        List<String> kept = new ArrayList<>();
        for (String id : before.keySet()) if (after.containsKey(id)) kept.add(id);
        // moves: each kept element out of the new order goes after its kept predecessor there
        List<String> order = new ArrayList<>();
        for (String id : after.keySet()) if (before.containsKey(id)) order.add(id);
        for (int i = 0; i < order.size(); i++) {
            String id = order.get(i);
            if (kept.indexOf(id) == i) continue;
            kept.remove(id);
            if (i == 0) {
                out.add(CgEdit.moveBefore(list.item(id), kept.get(0)));
                kept.add(0, id);
            } else {
                out.add(CgEdit.moveAfter(list.item(id), order.get(i - 1)));
                kept.add(kept.indexOf(order.get(i - 1)) + 1, id);
            }
        }
        String previous = null;
        for (Map.Entry<String, Map<?, ?>> e : after.entrySet()) {
            String id = e.getKey();
            Map<?, ?> el = e.getValue();
            Map<?, ?> old = before.get(id);
            if (old == null) {
                Map<Object, Object> element = new LinkedHashMap<>(el);
                Object afterId = element.remove("after"), beforeId = element.remove("before");
                if (afterId instanceof String) out.add(CgEdit.insertAfter(list, element, (String) afterId));
                else if (beforeId instanceof String) out.add(CgEdit.insertBefore(list, element, (String) beforeId));
                else if (previous != null) out.add(CgEdit.insertAfter(list, element, previous));
                else if (!kept.isEmpty()) out.add(CgEdit.insertBefore(list, element, kept.get(0)));
                else out.add(CgEdit.insert(list, element));
            } else if (!old.equals(el)) {
                if (!Objects.equals(old.get("after"), el.get("after")) || !Objects.equals(old.get("before"), el.get("before"))) {
                    return false;
                }
                Map<Object, Object> o = new LinkedHashMap<>(old), n = new LinkedHashMap<>(el);
                for (String skip : new String[] {CgType.ID, "after", "before"}) {
                    o.remove(skip);
                    n.remove(skip);
                }
                Object wasOn = o.remove("enabled"), isOn = n.remove("enabled");
                if (!Objects.equals(wasOn, isOn)) {
                    if (isOn instanceof Boolean) {
                        out.add((Boolean) isOn ? CgEdit.enable(list.item(id)) : CgEdit.disable(list.item(id)));
                    } else {
                        out.add(CgEdit.reset(list.item(id).key("enabled")));
                    }
                }
                if (!map(list.item(id), o, n, out)) return false;
            }
            previous = id;
        }
        return true;
    }

    private static Map<String, Map<?, ?>> byId(List<?> list) {
        Map<String, Map<?, ?>> out = new LinkedHashMap<>();
        for (Object o : list) {
            Map<?, ?> m = (Map<?, ?>) o;
            out.put(String.valueOf(m.get(CgType.ID)), m);
        }
        return out;
    }

    private static boolean idList(List<?> list) {
        if (list.isEmpty()) return true;
        for (Object o : list) if (!(o instanceof Map) || !((Map<?, ?>) o).containsKey(CgType.ID)) return false;
        return true;
    }

    private static boolean isForm(Map<?, ?> m) {
        for (String k : FORM_KEYS) if (m.containsKey(k)) return true;
        return false;
    }
}
