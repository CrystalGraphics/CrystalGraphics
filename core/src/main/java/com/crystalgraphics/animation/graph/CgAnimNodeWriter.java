package com.crystalgraphics.animation.graph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import javax.annotation.Nullable;

/**
 * Where a node writes its data form: its kind, its fields, and the nodes it reads by name. {@link CgAnimGraphCodec}
 * hands one to each node's {@link CgAnimNode#write}.
 *
 * <pre>{@code
 * @Override
 * protected void write(CgAnimNodeWriter out) {
 *     out.kind("two_way_blend").node("a", a).node("b", b).alpha("alpha", alpha, alphaVariable);
 * }
 * }</pre>
 *
 * <ul>
 *   <li>Write a field only where it differs from its declared default, so a graph writes the same file however it was
 *       made.</li>
 *   <li>The kind comes first.</li>
 * </ul>
 */
public final class CgAnimNodeWriter {

    private final Map<String, Object> out = new LinkedHashMap<>();
    private final Function<CgAnimNode, String> names;
    private final Map<String, Object> sections;

    CgAnimNodeWriter(Function<CgAnimNode, String> names, Map<String, Object> sections) {
        this.names = names;
        this.sections = sections;
    }

    /** Its kind: the {@code anim_graph} node family's key. */
    public CgAnimNodeWriter kind(String kind) {
        out.put("kind", kind);
        return this;
    }

    /** A plain field: a string, number, bool, or a list or map of them. */
    public CgAnimNodeWriter put(String key, Object value) {
        out.put(key, value);
        return this;
    }

    /** A node it reads, by the name the graph gives it. */
    public CgAnimNodeWriter node(String key, CgAnimNode node) {
        out.put(key, names.apply(node));
        return this;
    }

    /** Nodes it reads, in order. */
    public CgAnimNodeWriter nodes(String key, List<CgAnimNode> nodes) {
        List<String> n = new ArrayList<>(nodes.size());
        for (CgAnimNode node : nodes) n.add(names.apply(node));
        out.put(key, n);
        return this;
    }

    /** The name {@code node} is written by: for a field of a struct the node writes itself. */
    public String name(CgAnimNode node) {
        return names.apply(node);
    }

    /** An alpha or weight: the variable's name where it reads one, else the constant, left out where it is {@code dflt}. */
    public CgAnimNodeWriter alpha(String key, float constant, @Nullable CgAnimVariable<?> variable, float dflt) {
        if (variable != null) out.put(key, variable.name());
        else if (constant != dflt) out.put(key, (double) constant);
        return this;
    }

    /**
     * An entry {@code name} of the graph's section {@code key}, beside its nodes: a state machine's definition under
     * {@code machines}.
     *
     * @throws IllegalArgumentException where another node wrote a different entry of that name
     */
    @SuppressWarnings("unchecked")
    public CgAnimNodeWriter section(String key, String name, Map<String, Object> value) {
        Map<String, Object> sec = (Map<String, Object>) sections.computeIfAbsent(key, k -> new LinkedHashMap<String, Object>());
        Object had = sec.putIfAbsent(name, value);
        if (had != null && !had.equals(value)) throw new IllegalArgumentException(key + "." + name + " is written twice, differently");
        return this;
    }

    Map<String, Object> written() {
        return out;
    }
}
