package com.crystalgraphics.asset.tree;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.crystalgraphics.object.CgType;

/**
 * One node of a resolved asset: a map, a list of elements merged by id, or a value kept whole (a number, a vector, a
 * plain list, a form). It knows where its value comes from and whether this asset's file names it.
 *
 * <pre>{@code
 * CgAssetNode emitters = data.root().get("emitters");
 * for (CgAssetNode e : emitters.elements()) {
 *     if (e.origin() == CgOrigin.REMOVED) continue;   // kept, so reset restores it
 *     Object name = e.get("name").value();
 * }
 * }</pre>
 *
 * <ul>
 *   <li>{@link #elements()} keeps removed elements; {@link #toPlain()} drops them.</li>
 *   <li>A value node's {@link #value()} is plain and shared: copy it before changing it.</li>
 * </ul>
 */
public final class CgAssetNode {

    /** What a node holds. */
    public enum Kind { MAP, ID_LIST, VALUE }

    private final Kind kind;
    CgOrigin origin;
    @Nullable String from;
    /** Replaced whole by this asset: its delta is all of it. */
    boolean replaced;
    final Map<Object, CgAssetNode> fields;
    final List<CgAssetNode> elements;
    /** The keys or ids this asset's file names here, in the file's order. */
    final List<Object> said;
    @Nullable Object value;

    CgAssetNode(Kind kind, CgOrigin origin, @Nullable String from) {
        this.kind = kind;
        this.origin = origin;
        this.from = from;
        this.fields = kind == Kind.MAP ? new LinkedHashMap<Object, CgAssetNode>() : Collections.<Object, CgAssetNode>emptyMap();
        this.elements = kind == Kind.ID_LIST ? new ArrayList<CgAssetNode>() : Collections.<CgAssetNode>emptyList();
        this.said = new ArrayList<>();
    }

    /** Whether it is a map, an id list or a value. */
    public Kind kind() {
        return kind;
    }

    /** Where its value comes from. */
    public CgOrigin origin() {
        return origin;
    }

    /** The asset that set an {@link CgOrigin#INHERITED} value; null otherwise. */
    @Nullable
    public String from() {
        return from;
    }

    /** A map's node under {@code key}, or null. */
    @Nullable
    public CgAssetNode get(Object key) {
        return fields.get(key);
    }

    /** A map's keys, in resolved order: the parent's, then this asset's new ones. */
    public List<Object> keys() {
        return new ArrayList<>(fields.keySet());
    }

    /** An id list's elements in resolved order, removed ones included. */
    public List<CgAssetNode> elements() {
        return Collections.unmodifiableList(elements);
    }

    /** An id list's element {@code id}, or null. */
    @Nullable
    public CgAssetNode element(String id) {
        for (CgAssetNode e : elements) {
            if (id.equals(e.id())) return e;
        }
        return null;
    }

    /** An element's {@code id}; null for anything else. */
    @Nullable
    public String id() {
        CgAssetNode id = fields.get(CgType.ID);
        return id != null && id.value instanceof String ? (String) id.value : null;
    }

    /** A value node's value, plain; null for a map or a list. */
    @Nullable
    public Object value() {
        return value;
    }

    /** The resolved value as a plain tree: removed elements dropped. */
    public Object toPlain() {
        switch (kind) {
            case MAP: {
                Map<Object, Object> out = new LinkedHashMap<>();
                for (Map.Entry<Object, CgAssetNode> e : fields.entrySet()) out.put(e.getKey(), e.getValue().toPlain());
                return out;
            }
            case ID_LIST: {
                List<Object> out = new ArrayList<>();
                for (CgAssetNode e : elements) {
                    if (e.origin != CgOrigin.REMOVED) out.add(e.toPlain());
                }
                return out;
            }
            default:
                return value;
        }
    }

    /** A copy for a child to inherit: every value {@link CgOrigin#INHERITED} from where it was set. */
    CgAssetNode inherit(String parent) {
        CgAssetNode c = new CgAssetNode(kind, origin == CgOrigin.REMOVED ? CgOrigin.REMOVED : CgOrigin.INHERITED,
                origin == CgOrigin.INHERITED ? from : parent);
        if (origin == CgOrigin.REMOVED) c.from = from;
        for (Map.Entry<Object, CgAssetNode> e : fields.entrySet()) c.fields.put(e.getKey(), e.getValue().inherit(parent));
        for (CgAssetNode e : elements) c.elements.add(e.inherit(parent));
        c.value = value;
        return c;
    }
}
