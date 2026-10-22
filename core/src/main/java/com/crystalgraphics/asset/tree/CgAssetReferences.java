package com.crystalgraphics.asset.tree;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.crystalgraphics.object.CgPropertyPath;
import com.crystalgraphics.object.CgType;
import com.crystalgraphics.object.CgTypeKind;
import com.crystalgraphics.object.CgTypes;
import com.crystalgraphics.object.CgValueType;

/**
 * The assets a resolved asset names, found through its declarations: Unreal's {@code GetDependencies}, hard and soft.
 * A hard reference loads with its referrer and compiles before it; a soft one loads on first use.
 *
 * <pre>{@code
 * for (CgAssetReferences.Reference r : CgAssetReferences.of(data)) {
 *     if (r.strength() == CgValueType.Strength.HARD) pipeline.request(r.kind(), r.id());
 * }
 * }</pre>
 *
 * <ul>
 *   <li>Only asset references: a binary ({@code .png}, {@code .glb}) is a file, not an asset to compile.</li>
 *   <li>An {@code unreal:} id is listed as written; the host's alias table resolves it.</li>
 *   <li>A reference is found only where a declaration types it: a value kept as written ({@code TREE}, {@code VALUE})
 *       is not searched, but for a value in the field form, {@code { field: <id> }}: a hard reference to that field.</li>
 * </ul>
 */
public final class CgAssetReferences {

    /** One reference: the kind and id it names, how strongly, and where it is written. */
    public record Reference(CgType kind, String id, CgValueType.Strength strength, CgPropertyPath at) {
    }

    private final List<Reference> out = new ArrayList<>();

    private CgAssetReferences() {
    }

    /** Every reference {@code data}'s resolved tree holds, in tree order; a parent is its first, hard. */
    public static List<Reference> of(CgAssetData data) {
        return of(data.id(), data.kind(), data.resolved(), data.parent() == null ? null : data.parent().id());
    }

    /**
     * Every reference a file names itself, without resolving it: what the registry reads at scan, as Unreal's gatherer
     * reads a package's own imports. Its parent, if it names one, is first and hard.
     */
    public static List<Reference> ofFile(String id, CgType kind, Map<?, ?> file) {
        Object parent = file.get(CgType.PARENT);
        return of(id, kind, file, parent instanceof String ? (String) parent : null);
    }

    private static List<Reference> of(String id, CgType kind, Map<?, ?> tree, @Nullable String parent) {
        CgAssetReferences refs = new CgAssetReferences();
        if (parent != null) {
            refs.out.add(new Reference(kind, parent, CgValueType.Strength.HARD, CgPropertyPath.of(id).key(CgType.PARENT)));
        }
        Map<Object, Object> own = new LinkedHashMap<>(tree);
        own.remove(CgType.PARENT);
        refs.walk(CgValueType.struct(kind), own, CgPropertyPath.of(id));
        return refs.out;
    }

    private void walk(CgValueType t, @Nullable Object v, CgPropertyPath at) {
        if (v == null) return;
        switch (t.kind()) {
            case REF: {
                CgType kind = v instanceof String ? CgTypes.get("crystalgraphics:" + t.refKind()) : null;
                if (kind != null) out.add(new Reference(kind, (String) v, t.strength(), at));
                return;
            }
            case STRUCT: case DISPATCH: {
                if (!(v instanceof Map)) return;
                CgShape shape = CgShape.of(t, v);
                for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                    CgValueType c = shape.props().get(String.valueOf(e.getKey()));
                    if (c != null) walk(c, e.getValue(), at.key(String.valueOf(e.getKey())));
                }
                return;
            }
            case ID_LIST: {
                if (!(v instanceof List)) return;
                CgType type = t.type();
                CgValueType element = type.kind() == CgTypeKind.DISPATCH ? CgValueType.dispatch(type) : CgValueType.struct(type);
                List<?> l = (List<?>) v;
                for (int i = 0; i < l.size(); i++) {
                    Object item = l.get(i);
                    Object id = item instanceof Map ? ((Map<?, ?>) item).get(CgType.ID) : null;
                    walk(element, item, id instanceof String ? at.item((String) id) : at.index(i));
                }
                return;
            }
            case LIST: {
                if (!(v instanceof List)) return;
                List<?> l = (List<?>) v;
                for (int i = 0; i < l.size(); i++) walk(t.element(), l.get(i), at.index(i));
                return;
            }
            case MAP: {
                if (!(v instanceof Map)) return;
                for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) walk(t.element(), e.getValue(), at.key(String.valueOf(e.getKey())));
                return;
            }
            case VALUE: {
                // The field form names an asset whatever input it fills: a vector field module's grid.
                if (v instanceof Map<?, ?> m && m.size() == 1 && m.get("field") instanceof String field) {
                    CgType kind = CgTypes.get("crystalgraphics:field");
                    if (kind != null) out.add(new Reference(kind, field, CgValueType.Strength.HARD, at.key("field")));
                }
                return;
            }
            case ONE_OF: {
                for (CgValueType alt : t.alternatives()) {
                    if (fits(alt, v)) {
                        walk(alt, v, at);
                        return;
                    }
                }
                return;
            }
            default:
        }
    }

    /** Whether {@code v} is written in {@code alt}'s shape: a string for a reference, a map for a struct, a list. */
    private static boolean fits(CgValueType alt, Object v) {
        switch (alt.kind()) {
            case REF: case STRING: case ENUM: case BINARY_REF: case ELEMENT_REF:
                return v instanceof String;
            case STRUCT: case DISPATCH: case MAP:
                return v instanceof Map;
            case LIST: case ID_LIST: case ARRAY:
                return v instanceof List;
            default:
                return false;
        }
    }
}
