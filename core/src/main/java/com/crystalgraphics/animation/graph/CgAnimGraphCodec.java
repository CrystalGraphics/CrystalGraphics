package com.crystalgraphics.animation.graph;

import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.joml.Vector3f;

import com.crystalgraphics.api.animation.CgAnimSequence;
import com.crystalgraphics.api.animation.CgSkeleton;
import com.crystalgraphics.asset.kind.CgBodyKinds;
import com.crystalgraphics.asset.load.CgAssetCompiler;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.asset.tree.CgAssetException;

/**
 * An {@code anim_graphs/} file and a {@link CgAnimGraph}, each way: {@link #compile} reads a resolved asset into a
 * {@link CgAnimGraphDefinition}; {@link #toTree} writes a graph so that compiling and binding it gives the same graph.
 *
 * <pre>{@code
 * # anim_graphs/mymod/hero.yaml
 * _v: 1
 * variables:
 *   run: { type: float }
 * output: body
 * nodes:
 *   body: { kind: slot, source: locomotion }
 *   locomotion: { kind: two_way_blend, a: idle, b: jog, alpha: run }
 *   idle: { kind: sequence_player, clip: mymod:hero/idle, loop: true }
 *   jog: { kind: sequence_player, clip: mymod:hero/jog, loop: true }
 * }</pre>
 *
 * <pre>{@code
 * CgAnimGraphCodec.register();                                          // the kind's compiler, once
 * String yaml = CgAssetYaml.write(CgAnimGraphCodec.toTree(graph), CgBodyKinds.ANIM_GRAPH);
 * }</pre>
 *
 * <ul>
 *   <li>Nodes are a map by name; a node reads others by name, each read once. A pose read twice is a
 *       {@code save_cached_pose} (its own name is the cache's) read through {@code use_cached_pose}.</li>
 *   <li>An alpha or a weight is a number or a declared variable's name.</li>
 *   <li>A name no node has, a node read twice or reached from nowhere, an unknown kind, an undeclared variable: each
 *       fails at load, naming the asset and the node.</li>
 *   <li>A graph built in Java writes each clip by its name, and nodes by generated names ({@code slot_1}).</li>
 * </ul>
 */
public final class CgAnimGraphCodec {

    /** The kind's compiler: the file checked by a bind against a stand-in skeleton and clip. */
    public static final CgAssetCompiler<CgAnimGraphDefinition> COMPILER = CgAnimGraphCodec::compile;

    private static final CgSkeleton STAND_IN = CgSkeleton.builder().bone("root", null, new Vector3f()).build();
    private static final CgAnimSequence STAND_IN_CLIP = CgAnimSequence.builder("stand-in", 30, 1).build();

    private CgAnimGraphCodec() {
    }

    /** Registers {@link #COMPILER} as the anim graph kind's. */
    public static void register() {
        CgAssetCompiler.register(CgBodyKinds.ANIM_GRAPH, COMPILER);
    }

    /**
     * {@code asset}'s graph, resolved over its parent and checked.
     *
     * @throws CgAssetException a graph that cannot run, naming the asset and the node
     */
    public static CgAnimGraphDefinition compile(CgAssetData asset, CgAssetCompiler.Dependencies deps) {
        CgAnimGraphDefinition definition = new CgAnimGraphDefinition(asset.id(), asset.resolved());
        // Everything but the clips and the mask's bones is checked here, so a bad file fails at load
        definition.graph(STAND_IN, id -> STAND_IN_CLIP);
        return definition;
    }

    // ── reading ──────────────────────────────────────────────────────────────────────────

    static CgAnimGraph build(String id, Map<?, ?> tree, CgSkeleton skeleton, Function<String, CgAnimSequence> clips) {
        Reader r = new Reader(id, tree, clips);
        CgAnimGraph.Builder b = CgAnimGraph.builder(id, skeleton);
        b.variables(r.variables.values().toArray(new CgAnimVariable<?>[0]));
        if (!(tree.get("output") instanceof String output)) throw new CgAssetException(id + ": output: none");
        b.output(r.pose(output));
        for (Map.Entry<String, CgAnimNode> save : r.saves.entrySet()) b.save(save.getKey(), save.getValue());
        for (Object name : r.nodes.keySet()) {
            if (!r.reached.contains(String.valueOf(name))) {
                throw new CgAssetException(id + ": nodes." + name + ": no node reaches it from " + output);
            }
        }
        try {
            return b.build();
        } catch (IllegalArgumentException e) {
            throw new CgAssetException(e.getMessage());
        }
    }

    private static final class Reader {

        final String id;
        final Map<?, ?> nodes;
        final Function<String, CgAnimSequence> clips;
        final Map<String, CgAnimVariable<?>> variables = new LinkedHashMap<>();
        final Map<String, CgAnimNode> saves = new LinkedHashMap<>();
        final Set<String> reached = new HashSet<>(), saving = new HashSet<>();

        Reader(String id, Map<?, ?> tree, Function<String, CgAnimSequence> clips) {
            this.id = id;
            this.clips = clips;
            if (!(tree.get("nodes") instanceof Map<?, ?> n)) throw new CgAssetException(id + ": nodes: none");
            this.nodes = n;
            if (tree.get("variables") instanceof Map<?, ?> vars) {
                for (Map.Entry<?, ?> e : vars.entrySet()) variables.put(String.valueOf(e.getKey()), variable(e));
            }
        }

        private CgAnimVariable<?> variable(Map.Entry<?, ?> e) {
            String name = String.valueOf(e.getKey());
            Map<?, ?> m = (Map<?, ?>) e.getValue();
            Object d = m.get("default");
            return switch (String.valueOf(m.get("type"))) {
                case "float" -> CgAnimVariable.floating(name, d instanceof Number n ? n.floatValue() : 0f);
                case "bool" -> CgAnimVariable.bool(name, Boolean.TRUE.equals(d));
                case "int" -> CgAnimVariable.integer(name, d instanceof Number n ? n.intValue() : 0);
                default -> throw new CgAssetException(id + ": variables." + name + ".type: " + m.get("type") + " is no variable type");
            };
        }

        /** Node {@code name} read as a pose, once. */
        CgAnimNode pose(String name) {
            String at = id + ": nodes." + name;
            if (!(nodes.get(name) instanceof Map<?, ?> m)) throw new CgAssetException(at + ": a node reads it; nodes lacks it");
            if ("save_cached_pose".equals(m.get("kind"))) {
                throw new CgAssetException(at + ": a cached pose is read through use_cached_pose");
            }
            if (!reached.add(name)) throw new CgAssetException(at + ": two nodes read it; save its pose and use it instead");
            try {
                return node(at, m);
            } catch (IllegalArgumentException e) {
                throw new CgAssetException(at + ": " + e.getMessage());
            }
        }

        private CgAnimNode node(String at, Map<?, ?> m) {
            String kind = String.valueOf(m.get("kind"));
            return switch (kind) {
                case "sequence_player" -> {
                    String clipId = string(at, m, "clip");
                    CgAnimSequence clip = clips.apply(clipId);
                    if (clip == null) throw new CgAssetException(at + ".clip: " + clipId + " names no clip of this mesh");
                    CgSequencePlayerNode p = CgSequencePlayerNode.of(clip, clipId);
                    if (Boolean.TRUE.equals(m.get("loop"))) p = p.loop();
                    if (m.get("play_rate") instanceof Number n) p = p.playRate(n.floatValue());
                    if (m.get("start_position") instanceof Number n) p = p.startPosition(n.floatValue());
                    yield p;
                }
                case "ref_pose" -> CgRefPoseNode.create();
                case "slot" -> CgSlotNode.of(m.get("slot_name") instanceof String s ? s : CgAnimGraph.DEFAULT_SLOT, pose(string(at, m, "source")));
                case "two_way_blend" -> {
                    CgAnimNode a = pose(string(at, m, "a")), b = pose(string(at, m, "b"));
                    Object alpha = m.get("alpha");
                    yield alpha instanceof String v ? CgTwoWayBlendNode.of(a, b, floatVariable(at + ".alpha", v))
                            : CgTwoWayBlendNode.of(a, b, number(at + ".alpha", alpha));
                }
                case "blend_list_by_bool" -> {
                    CgAnimVariable<Boolean> selector = typed(at + ".selector", string(at, m, "selector"), CgAnimVariable.Type.BOOL);
                    yield blendList(at, m, CgBlendListNode.byBool(selector, pose(string(at, m, "when_true")), pose(string(at, m, "when_false"))));
                }
                case "blend_list_by_int" -> {
                    CgAnimVariable<Integer> selector = typed(at + ".selector", string(at, m, "selector"), CgAnimVariable.Type.INT);
                    if (!(m.get("poses") instanceof List<?> names) || names.isEmpty()) throw new CgAssetException(at + ".poses: none");
                    CgAnimNode[] poses = new CgAnimNode[names.size()];
                    for (int i = 0; i < poses.length; i++) poses[i] = pose(String.valueOf(names.get(i)));
                    yield blendList(at, m, CgBlendListNode.byInt(selector, poses));
                }
                case "layered_blend" -> {
                    CgLayeredBlendNode node = CgLayeredBlendNode.of(pose(string(at, m, "base")));
                    if (!(m.get("layers") instanceof List<?> layers)) throw new CgAssetException(at + ".layers: none");
                    for (int i = 0; i < layers.size(); i++) node = layer(at + ".layers[" + i + "]", (Map<?, ?>) layers.get(i), node);
                    yield node;
                }
                case "apply_additive" -> {
                    CgAnimNode base = pose(string(at, m, "base")), additive = pose(string(at, m, "additive"));
                    Object alpha = m.get("alpha");
                    yield alpha instanceof String v ? CgApplyAdditiveNode.of(base, additive, floatVariable(at + ".alpha", v))
                            : CgApplyAdditiveNode.of(base, additive, alpha == null ? 1f : number(at + ".alpha", alpha));
                }
                case "use_cached_pose" -> {
                    String save = string(at, m, "save");
                    if (!(nodes.get(save) instanceof Map<?, ?> s) || !"save_cached_pose".equals(s.get("kind"))) {
                        throw new CgAssetException(at + ".save: " + save + " is no save_cached_pose");
                    }
                    // A save is read once, however many use it; one already being read is a cycle the graph names
                    if (!saves.containsKey(save) && saving.add(save)) {
                        reached.add(save);
                        String source = string(id + ": nodes." + save, s, "pose");
                        saves.put(save, pose(source));
                    }
                    yield CgCachedPoseNode.use(save);
                }
                default -> throw new CgAssetException(at + ".kind: " + kind + " is no anim graph node kind");
            };
        }

        private CgBlendListNode blendList(String at, Map<?, ?> m, CgBlendListNode node) {
            if (m.get("blend_times") instanceof List<?> times) {
                float[] t = new float[times.size()];
                for (int i = 0; i < t.length; i++) t[i] = number(at + ".blend_times[" + i + "]", times.get(i));
                node = node.blendTimes(t);
            }
            return Boolean.TRUE.equals(m.get("reset_child_on_activate")) ? node.resetOnActivate() : node;
        }

        private CgLayeredBlendNode layer(String at, Map<?, ?> l, CgLayeredBlendNode node) {
            CgAnimNode pose = pose(string(at, l, "pose"));
            if (!(l.get("mask") instanceof Map<?, ?> m)) throw new CgAssetException(at + ".mask: none");
            CgBlendMask mask = null;
            if (m.get("branches") instanceof List<?> branches) {
                for (Object o : branches) {
                    Map<?, ?> b = (Map<?, ?>) o;
                    String bone = string(at + ".mask.branches", b, "bone");
                    int depth = b.get("depth") instanceof Number n ? n.intValue() : 0;
                    mask = mask == null ? CgBlendMask.branch(bone, depth) : mask.andBranch(bone, depth);
                }
            }
            if (m.get("bones") instanceof List<?> bones) {
                for (Object o : bones) {
                    Map<?, ?> b = (Map<?, ?>) o;
                    String bone = string(at + ".mask.bones", b, "bone");
                    float w = number(at + ".mask.bones." + bone + ".weight", b.get("weight"));
                    mask = mask == null ? CgBlendMask.bone(bone, w) : mask.andBone(bone, w);
                }
            }
            if (mask == null) throw new CgAssetException(at + ".mask: reaches no bone");
            Object weight = l.get("weight");
            node = weight instanceof String v ? node.layer(pose, mask, floatVariable(at + ".weight", v))
                    : node.layer(pose, mask, weight == null ? 1f : number(at + ".weight", weight));
            Object curves = l.get("curves");
            if ("use_base_only".equals(curves)) return node.curvesFromBase();
            if (curves != null && !"max_weight".equals(curves)) throw new CgAssetException(at + ".curves: " + curves + " is no curve blend");
            return node;
        }

        private CgAnimVariable<Float> floatVariable(String at, String name) {
            return typed(at, name, CgAnimVariable.Type.FLOAT);
        }

        @SuppressWarnings("unchecked")
        private <T> CgAnimVariable<T> typed(String at, String name, CgAnimVariable.Type type) {
            CgAnimVariable<?> v = variables.get(name);
            if (v == null) throw new CgAssetException(at + ": " + name + " is no declared variable");
            if (v.type() != type) {
                throw new CgAssetException(at + ": " + name + " is a " + v.type().name().toLowerCase(Locale.ROOT) + ", not a "
                        + type.name().toLowerCase(Locale.ROOT));
            }
            return (CgAnimVariable<T>) v;
        }

        private static String string(String at, Map<?, ?> m, String key) {
            if (!(m.get(key) instanceof String s)) throw new CgAssetException(at + "." + key + ": none");
            return s;
        }

        private static float number(String at, Object o) {
            if (!(o instanceof Number n)) throw new CgAssetException(at + ": " + o + " is not a number");
            return n.floatValue();
        }
    }

    // ── writing ──────────────────────────────────────────────────────────────────────────

    /** Names each node the first time it is read, by its kind and a count, and writes it. */
    private static final class Namer implements Function<CgAnimNode, String> {

        final Map<String, Object> nodes = new LinkedHashMap<>();
        private final Map<CgAnimNode, String> names = new IdentityHashMap<>();
        private final Map<String, Integer> counts = new HashMap<>();
        private final Set<String> taken;

        Namer(Set<String> saves) {
            taken = new HashSet<>(saves);
        }

        @Override
        public String apply(CgAnimNode node) {
            String known = names.get(node);
            if (known != null) return known;
            CgAnimNodeWriter w = new CgAnimNodeWriter(this);
            node.write(w);
            String kind = String.valueOf(w.written().get("kind"));
            String n;
            do {
                n = kind + "_" + counts.merge(kind, 1, Integer::sum);
            } while (!taken.add(n));
            names.put(node, n);
            nodes.put(n, w.written());
            return n;
        }
    }

    /**
     * {@code graph}'s data form: compiled and bound to the same clips, it is the same graph.
     *
     * @throws UnsupportedOperationException where a node has no data form, naming it
     */
    public static Map<String, Object> toTree(CgAnimGraph graph) {
        Namer names = new Namer(graph.saves().keySet());
        String output = names.apply(graph.output());
        for (Map.Entry<String, CgAnimNode> save : graph.saves().entrySet()) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("kind", "save_cached_pose");
            s.put("pose", names.apply(save.getValue()));
            names.nodes.put(save.getKey(), s);
        }
        Map<String, Object> nodes = names.nodes;
        Map<String, Object> tree = new LinkedHashMap<>();
        if (!graph.variables().isEmpty()) {
            Map<String, Object> vars = new LinkedHashMap<>();
            for (CgAnimVariable<?> v : graph.variables()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("type", v.type().name().toLowerCase(Locale.ROOT));
                if (v.defaultValue() != 0f) {
                    m.put("default", switch (v.type()) {
                        case FLOAT -> (Object) (double) v.defaultValue();
                        case BOOL -> true;
                        case INT -> (int) v.defaultValue();
                    });
                }
                vars.put(v.name(), m);
            }
            tree.put("variables", vars);
        }
        tree.put("output", output);
        tree.put("nodes", nodes);
        return tree;
    }
}
