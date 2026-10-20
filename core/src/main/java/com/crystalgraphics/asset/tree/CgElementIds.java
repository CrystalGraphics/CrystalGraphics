package com.crystalgraphics.asset.tree;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.crystalgraphics.object.CgType;
import com.crystalgraphics.util.CgRandom;

/**
 * An element id the editor makes: 40 random bits as 8 Crockford base-32 characters, lower case, a letter first so
 * YAML never reads it as a number, redrawn while it collides with an id the asset holds.
 *
 * <pre>{@code
 * String id = CgElementIds.fresh(CgElementIds.of(data));   // "k3q9x2ab"
 * }</pre>
 */
public final class CgElementIds {

    private static final String DIGITS = "0123456789abcdefghjkmnpqrstvwxyz";

    private CgElementIds() {
    }

    /** A new id none of {@code taken} is; it is added to {@code taken}. */
    public static String fresh(Set<String> taken) {
        while (true) {
            long bits = ((CgRandom.nextInt() & 0xFFFFFFFFL) << 8) ^ (CgRandom.nextInt() & 0xFFL);
            char[] id = new char[8];
            for (int i = 7; i >= 0; i--) {
                id[i] = DIGITS.charAt((int) (bits & 31));
                bits >>>= 5;
            }
            if (Character.isDigit(id[0])) continue;   // a letter first
            String s = new String(id);
            if (taken.add(s)) return s;
        }
    }

    /** Every element id a resolved asset holds, removed elements included: an id is never reused in its asset. */
    public static Set<String> of(CgAssetData data) {
        Set<String> out = new HashSet<>();
        collect(data.root(), out);
        return out;
    }

    /** Every element id inside a plain tree. */
    static void collect(Object plain, Set<String> out) {
        if (plain instanceof Map) {
            for (Object v : ((Map<?, ?>) plain).values()) collect(v, out);
        } else if (plain instanceof List) {
            for (Object v : (List<?>) plain) {
                if (v instanceof Map && ((Map<?, ?>) v).get(CgType.ID) instanceof String) out.add((String) ((Map<?, ?>) v).get(CgType.ID));
                collect(v, out);
            }
        }
    }

    private static void collect(CgAssetNode n, Set<String> out) {
        String id = n.id();
        if (id != null) out.add(id);
        for (CgAssetNode c : n.fields.values()) collect(c, out);
        for (CgAssetNode e : n.elements) collect(e, out);
        if (n.kind() == CgAssetNode.Kind.VALUE) collect(n.value, out);
    }
}
