package com.crystalgraphics.asset.codec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.crystalgraphics.object.CgType;
import com.crystalgraphics.serialization.CgBinaryFormat;

/**
 * An asset's cooked form: the same tree as its YAML, as {@link CgBinaryFormat} bytes, with every
 * {@code editor} block dropped, since only tools read them.
 *
 * <pre>{@code
 * byte[] cooked = CgAssetBinary.cook(CgAssetYaml.read(text));
 * Map<Object, Object> tree = CgAssetBinary.read(cooked);   // the YAML's tree, less its editor blocks
 * }</pre>
 */
public final class CgAssetBinary {

    private CgAssetBinary() {
    }

    /** {@code tree} cooked: {@code editor} dropped at every level. */
    public static byte[] cook(Map<?, ?> tree) {
        return CgBinaryFormat.encode(withoutEditor(tree));
    }

    /** A cooked asset's tree. */
    @SuppressWarnings("unchecked")
    public static Map<Object, Object> read(byte[] cooked) {
        return (Map<Object, Object>) CgBinaryFormat.decode(cooked);
    }

    /** {@code v} with every {@code editor} key removed, copied: the tree it came from is left as it was. */
    static Object withoutEditor(Object v) {
        if (v instanceof Map) {
            Map<Object, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                if (!CgType.EDITOR.equals(e.getKey())) out.put(e.getKey(), withoutEditor(e.getValue()));
            }
            return out;
        }
        if (v instanceof List) {
            List<Object> out = new ArrayList<>();
            for (Object x : (List<?>) v) out.add(withoutEditor(x));
            return out;
        }
        return v;
    }
}
