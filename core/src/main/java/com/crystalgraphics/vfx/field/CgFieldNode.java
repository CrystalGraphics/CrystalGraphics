package com.crystalgraphics.vfx.field;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.crystalgraphics.asset.kind.CgFieldKinds;
import com.crystalgraphics.object.CgProperty;
import com.crystalgraphics.object.CgType;
import com.crystalgraphics.object.CgValueType;
import com.crystalgraphics.object.value.CgUnits;

/**
 * One node of a field graph, compiled: its kind, its numbers in host units in its declaration's order, and the nodes
 * it reads by their place in the graph. Chaos's {@code FFieldNodeBase} as {@code FillSetupDatas} flattens it.
 *
 * <pre>{@code
 * CgFieldNode falloff = graph.nodes().get(1);
 * float radius = falloff.param("radius");          // blocks
 * int left = graph.nodes().get(2).inputs()[0];      // an operator's left side, a node before it
 * }</pre>
 *
 * @param id     its id in the graph
 * @param kind   what it computes
 * @param params its numbers, laid out by {@link Kind#offset}
 * @param inputs the graph places of the nodes it reads, in its declaration's order; -1 for one left unset
 */
public record CgFieldNode(String id, Kind kind, float[] params, int[] inputs) {

    /** A node's value: one number, or a vector. */
    public enum Output { SCALAR, VECTOR }

    /** What a kind's value is, from its inputs' (the nodes it reads, null for one unset). */
    @FunctionalInterface
    public interface OutputRule {
        Output output(CgFieldNode node, Output[] inputs);
    }

    /** Where a kind's value can be other than 0, from its numbers and its inputs' bounds. */
    @FunctionalInterface
    public interface BoundsRule {
        CgFieldBounds bounds(CgFieldNode node, CgFieldBounds[] inputs);
    }

    /**
     * A node kind: its declaration ({@code CgFieldKinds.NODES}' member), what it outputs, its bounds, and which of its
     * numbers scale its value. Chaos's field node classes; a mod registers its own after declaring its type.
     *
     * <pre>{@code
     * CgFieldNode.Kind.register(new CgFieldNode.Kind("mymod:swirl", CgFieldNode.Output.VECTOR,
     *         (n, in) -> CgFieldBounds.INFINITE, List.of("magnitude"), null));
     * }</pre>
     *
     * <ul>
     *   <li>{@code scales} are the numbers its value is linear in (its magnitude; a falloff's default too, which
     *       Chaos returns unscaled outside), so a unit folds into it exactly. {@code through} instead names the input
     *       it passes on unscaled, as culling passes its field.</li>
     *   <li>Its declaration's string properties are its inputs, in order.</li>
     * </ul>
     */
    public record Kind(String name, OutputRule output, BoundsRule bounds, List<String> scales, @Nullable String through,
                       CgType type, Map<String, Integer> offsets, List<String> inputNames, int size) {

        private static final Map<String, Kind> KINDS = new ConcurrentHashMap<>();

        /** A kind of {@code name}, whose declaration is {@code CgFieldKinds.NODES}' member of that name. */
        public Kind(String name, Output output, BoundsRule bounds, List<String> scales, @Nullable String through) {
            this(name, (n, in) -> output, bounds, scales, through);
        }

        /** A kind whose output depends on its inputs'. */
        public Kind(String name, OutputRule output, BoundsRule bounds, List<String> scales, @Nullable String through) {
            this(name, output, bounds, List.copyOf(scales), through, declaration(name), new LinkedHashMap<>(),
                    new ArrayList<>(), 0);
        }

        public Kind {
            if (offsets.isEmpty() && inputNames.isEmpty()) {
                int at = 0;
                for (CgProperty p : type.properties()) {
                    if (skipped(p)) continue;
                    if (p.type().kind() == CgValueType.Kind.STRING) {
                        inputNames.add(p.name());
                        continue;
                    }
                    offsets.put(p.name(), at);
                    at += width(p.type());
                }
                size = at;
            }
            offsets = Map.copyOf(offsets);
            inputNames = List.copyOf(inputNames);
        }

        /** Registers {@code kind} under its name. At init, before a graph names it. */
        public static Kind register(Kind kind) {
            if (KINDS.putIfAbsent(kind.name, kind) != null) throw new IllegalArgumentException("a field node kind " + kind.name + " is registered already");
            return kind;
        }

        /** The kind registered as {@code name}, or null. */
        public static @Nullable Kind of(String name) {
            CgFieldNodes.load();
            return KINDS.get(name);
        }

        /** Where {@code param} starts in a node's numbers. */
        public int offset(String param) {
            Integer at = offsets.get(param);
            if (at == null) throw new IllegalArgumentException(name + " has no number " + param + ": " + offsets.keySet());
            return at;
        }

        private static CgType declaration(String name) {
            CgType t = CgFieldKinds.NODES.member(name);
            if (t == null) throw new IllegalArgumentException("no field node " + name + " is declared");
            return t;
        }
    }

    private static boolean skipped(CgProperty p) {
        return p.name().equals("id") || p.name().equals("kind") || p.type().kind() == CgValueType.Kind.TREE;
    }

    /** Numbers a value of {@code type} takes: a rotation as a quaternion, a struct its properties'. */
    private static int width(CgValueType type) {
        return switch (type.kind()) {
            case FLOAT, INT, BOOL, ENUM -> 1;
            case VEC3, POSITION -> 3;
            case ROTATION -> 4;
            case STRUCT -> {
                int w = 0;
                for (CgProperty p : type.type().properties()) if (!skipped(p)) w += width(p.type());
                yield w;
            }
            default -> throw new IllegalArgumentException("a field node's " + type + " is not a number yet");
        };
    }

    /** Its number {@code param}, or the first of a vector's. */
    public float param(String param) {
        return params[kind.offset(param)];
    }

    /** Its vector {@code param} into {@code out}. */
    public Vector3f vector(String param, Vector3f out) {
        int at = kind.offset(param);
        return out.set(params[at], params[at + 1], params[at + 2]);
    }

    /** The node of {@code kind} read from {@code tree}, its values converted from {@code units}; {@code inputs} resolved by the caller. */
    static CgFieldNode read(String id, Kind kind, Map<?, ?> tree, CgUnits units, int[] inputs, String path) {
        float[] params = new float[kind.size];
        int at = 0;
        for (CgProperty p : kind.type.properties()) {
            if (skipped(p) || p.type().kind() == CgValueType.Kind.STRING) continue;
            at = pack(p, tree.containsKey(p.name()) ? tree.get(p.name()) : p.defaultValue(), units, params, at, path + "." + p.name());
        }
        return new CgFieldNode(id, kind, params, inputs);
    }

    private static int pack(CgProperty p, @Nullable Object v, CgUnits units, float[] out, int at, String path) {
        CgValueType t = p.type();
        switch (t.kind()) {
            case ENUM -> {
                int i = v == null ? 0 : t.enumValues().indexOf(String.valueOf(v));
                if (i < 0) throw new IllegalArgumentException(path + ": " + v + " is none of " + t.enumValues());
                out[at] = i;
                return at + 1;
            }
            case FLOAT, INT -> {
                out[at] = v == null ? 0f : number(units.toHost(v, p), path);
                return at + 1;
            }
            case BOOL -> {
                out[at] = Boolean.TRUE.equals(v) ? 1f : 0f;
                return at + 1;
            }
            case VEC3, POSITION -> {
                Object written = v instanceof Number n ? List.of(n, n, n) : v == null ? List.of(0, 0, 0) : v;
                List<?> host = (List<?>) units.toHost(written, p);
                for (int c = 0; c < 3; c++) out[at + c] = number(host.get(c), path);
                return at + 3;
            }
            case ROTATION -> {
                // A rotation's default, 0, is none.
                Quaternionf q = v == null || v instanceof Number ? new Quaternionf() : units.rotationToHost(v, new Quaternionf());
                out[at] = q.x;
                out[at + 1] = q.y;
                out[at + 2] = q.z;
                out[at + 3] = q.w;
                return at + 4;
            }
            case STRUCT -> {
                Map<?, ?> m = v instanceof Map<?, ?> s ? s : Map.of();
                for (CgProperty c : t.type().properties()) {
                    if (skipped(c)) continue;
                    at = pack(c, m.containsKey(c.name()) ? m.get(c.name()) : c.defaultValue(), units, out, at, path + "." + c.name());
                }
                return at;
            }
            default -> throw new IllegalArgumentException(path + ": a " + t + " is not a number yet");
        }
    }

    private static float number(Object v, String path) {
        if (!(v instanceof Number n)) throw new IllegalArgumentException(path + ": " + v + " is not a number");
        return n.floatValue();
    }

    /** Its tree as a graph's {@code nodes} entry, in host units: what {@link #read} reads back to this node. */
    Map<String, Object> write(List<CgFieldNode> graph) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("kind", kind.name);
        int at = 0, input = 0;
        for (CgProperty p : kind.type.properties()) {
            if (skipped(p)) continue;
            if (p.type().kind() == CgValueType.Kind.STRING) {
                int i = inputs[input++];
                if (i >= 0) out.put(p.name(), graph.get(i).id);
                continue;
            }
            Object[] value = new Object[1];
            at = unpack(p.type(), at, value);
            out.put(p.name(), value[0]);
        }
        return out;
    }

    private int unpack(CgValueType t, int at, Object[] value) {
        switch (t.kind()) {
            case ENUM -> value[0] = t.enumValues().get((int) params[at]);
            case FLOAT -> value[0] = (double) params[at];
            case INT -> value[0] = (int) params[at];
            case BOOL -> value[0] = params[at] != 0f;
            case VEC3, POSITION -> value[0] = List.of((double) params[at], (double) params[at + 1], (double) params[at + 2]);
            case ROTATION -> value[0] = List.of((double) params[at], (double) params[at + 1], (double) params[at + 2], (double) params[at + 3]);
            case STRUCT -> {
                Map<String, Object> m = new LinkedHashMap<>();
                for (CgProperty c : t.type().properties()) {
                    if (skipped(c)) continue;
                    Object[] v = new Object[1];
                    at = unpack(c.type(), at, v);
                    m.put(c.name(), v[0]);
                }
                value[0] = m;
                return at;
            }
            default -> throw new IllegalStateException(t.toString());
        }
        return at + width(t);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof CgFieldNode n && id.equals(n.id) && kind == n.kind && Arrays.equals(params, n.params)
                && Arrays.equals(inputs, n.inputs);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * id.hashCode() + kind.name.hashCode()) + Arrays.hashCode(params);
    }

    @Override
    public String toString() {
        return id + ": " + kind.name + Arrays.toString(params) + " <- " + Arrays.toString(inputs);
    }
}
