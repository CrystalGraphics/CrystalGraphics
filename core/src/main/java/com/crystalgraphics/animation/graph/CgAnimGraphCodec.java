package com.crystalgraphics.animation.graph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import javax.annotation.Nullable;

import org.joml.Vector3f;

import com.crystalgraphics.animation.sync.CgAnimGroupRole;
import com.crystalgraphics.animation.sync.CgSyncGroup;
import com.crystalgraphics.api.animation.CgAnimSequence;
import com.crystalgraphics.api.animation.CgSkeleton;
import com.crystalgraphics.asset.kind.CgBodyKinds;
import com.crystalgraphics.asset.load.CgAssetCompiler;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.asset.tree.CgAssetException;
import com.crystalgraphics.object.CgType;
import com.crystalgraphics.tag.CgGameplayTag;
import com.crystalgraphics.tag.CgTagException;
import com.crystalgraphics.tag.CgTagRegistry;
import com.crystalgraphics.world.CgEntityVariable;
import com.crystalgraphics.world.CgEntityVariables;

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
 * entity:
 *   run: entity.input               # set each frame by the entity's actor
 * tags:
 *   flying: { tag: mymod:state.flying }       # a bool: owned; an int: its count
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
    /** Node kinds read through {@link CgAnimNodeKind}: those outside this package. */
    private static final Map<String, CgAnimNodeKind> KINDS = new ConcurrentHashMap<>();

    private CgAnimGraphCodec() {
    }

    /** Registers {@link #COMPILER} as the anim graph kind's. */
    public static void register() {
        CgAssetCompiler.register(CgBodyKinds.ANIM_GRAPH, COMPILER);
    }

    /**
     * Reads node kind {@code kind} with {@code reader} from now on: a kind outside this package.
     *
     * @throws IllegalArgumentException where {@code kind} is one of this package's own
     */
    public static void registerKind(String kind, CgAnimNodeKind reader) {
        if (BUILT_IN.contains(kind)) throw new IllegalArgumentException(kind + " is a built-in node kind");
        KINDS.put(kind, reader);
    }

    private static final Set<String> BUILT_IN = Set.of("sequence_player", "ref_pose", "slot", "two_way_blend",
            "blend_list_by_bool", "blend_list_by_int", "layered_blend", "apply_additive", "save_cached_pose", "use_cached_pose",
            "linked_input_pose", "linked_anim_layer", "linked_anim_graph");

    /**
     * {@code asset}'s graph, resolved over its parent and checked.
     *
     * @throws CgAssetException a graph that cannot run, naming the asset and the node
     */
    public static CgAnimGraphDefinition compile(CgAssetData asset, @Nullable CgAssetCompiler.Dependencies deps) {
        Map<CgType, Map<String, Object>> found = new LinkedHashMap<>();
        // Everything but the clips and the mask's bones is checked here, so a bad file fails at load
        build(asset.id(), asset.resolved(), STAND_IN, id -> STAND_IN_CLIP, (at, kind, id) -> {
            Object d = deps == null ? null : deps.definition(kind, id);
            if (d == null) throw new CgAssetException(at + ": " + id + " resolves to no " + kind);
            found.computeIfAbsent(kind, k -> new LinkedHashMap<>()).put(id, d);
            return d;
        });
        return new CgAnimGraphDefinition(asset.id(), asset.resolved(), found);
    }

    /** How a graph's reader finds a compiled dependency: through the pipeline at compile, from the definition at bind. */
    @FunctionalInterface
    interface Resolver {
        Object definition(String at, CgType kind, String id);
    }

    // ── reading ──────────────────────────────────────────────────────────────────────────

    static CgAnimGraph build(String id, Map<?, ?> tree, CgSkeleton skeleton, Function<String, CgAnimSequence> clips, Resolver resolver) {
        Reader r = new Reader(id, tree, skeleton, clips, resolver);
        CgAnimGraph.Builder b = CgAnimGraph.builder(id, skeleton);
        b.variables(r.variables.values().toArray(new CgAnimVariable<?>[0]));
        if (tree.get("entity") instanceof Map<?, ?> entity) {
            for (Map.Entry<?, ?> e : entity.entrySet()) {
                String name = String.valueOf(e.getKey()), at = id + ": entity." + name;
                CgAnimVariable<?> v = r.variables.get(name);
                if (v == null) throw new CgAssetException(at + ": the graph declares no variable " + name);
                CgEntityVariable source = CgEntityVariables.of(String.valueOf(e.getValue()));
                if (source == null) throw new CgAssetException(at + ": " + e.getValue() + " is no entity variable");
                b.entity(v, source);
            }
        }
        if (tree.get("tags") instanceof Map<?, ?> tags) {
            for (Map.Entry<?, ?> e : tags.entrySet()) {
                String name = String.valueOf(e.getKey()), at = id + ": tags." + name;
                CgAnimVariable<?> v = r.variables.get(name);
                if (v == null) throw new CgAssetException(at + ": the graph declares no variable " + name);
                Map<?, ?> m = (Map<?, ?>) e.getValue();
                CgGameplayTag tag;
                try {
                    tag = CgTagRegistry.global().require(r.string(at, m, "tag"));
                } catch (CgTagException ex) {
                    throw new CgAssetException(at + ".tag: " + ex.getMessage());
                }
                b.tag(v, tag, m.get("as") == null ? CgAnimGraph.TagAs.of(v.type())
                        : CgAnimGraph.TagAs.valueOf(String.valueOf(m.get("as")).toUpperCase(Locale.ROOT)));
            }
        }
        if (tree.get("inputs") instanceof List<?> ins) for (Object in : ins) b.inputs(String.valueOf(in));
        if (!(tree.get("output") instanceof String output)) throw new CgAssetException(id + ": output: none");
        b.output(r.pose(output));
        if (tree.get("layers") instanceof Map<?, ?> layers) {
            for (Map.Entry<?, ?> e : layers.entrySet()) {
                String name = String.valueOf(e.getKey()), at = id + ": layers." + name;
                Map<?, ?> l = (Map<?, ?>) e.getValue();
                List<String> ins = new ArrayList<>();
                if (l.get("inputs") instanceof List<?> li) for (Object in : li) ins.add(String.valueOf(in));
                b.layer(new CgAnimGraph.Layer(name, ins, r.pose(r.string(at, l, "output")),
                        l.get("group") instanceof String g ? g : null,
                        l.get("blend_in") == null ? 0f : r.number(at + ".blend_in", l.get("blend_in")),
                        l.get("blend_out") == null ? 0f : r.number(at + ".blend_out", l.get("blend_out"))));
            }
        }
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

    private static final class Reader implements CgAnimNodeKind.Context {

        final String id;
        final Map<?, ?> tree;
        final Map<?, ?> nodes;
        final Function<String, CgAnimSequence> clips;
        final Resolver resolver;
        final CgSkeleton skeleton;
        final Map<String, CgAnimVariable<?>> variables = new LinkedHashMap<>();
        final Map<String, CgAnimNode> saves = new LinkedHashMap<>();
        final Set<String> reached = new HashSet<>(), saving = new HashSet<>();

        Reader(String id, Map<?, ?> tree, CgSkeleton skeleton, Function<String, CgAnimSequence> clips, Resolver resolver) {
            this.id = id;
            this.skeleton = skeleton;
            this.tree = tree;
            this.clips = clips;
            this.resolver = resolver;
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
        @Override
        public CgAnimNode pose(String name) {
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
                    CgSequencePlayerNode p = CgSequencePlayerNode.of(clip(at + ".clip", clipId), clipId);
                    if (Boolean.TRUE.equals(m.get("loop"))) p = p.loop();
                    if (m.get("play_rate") instanceof String v) p = p.playRate(floatVariable(at + ".play_rate", v));
                    else if (m.get("play_rate") != null) p = p.playRate(number(at + ".play_rate", m.get("play_rate")));
                    if (m.get("start_position") instanceof Number n) p = p.startPosition(n.floatValue());
                    CgSyncGroup group = syncGroup(at, m);
                    yield group == null ? p : p.sync(group);
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
                case "linked_input_pose" -> CgLinkedInputPoseNode.of(string(at, m, "name"));
                case "linked_anim_layer" -> {
                    CgLinkedAnimLayerNode node = CgLinkedAnimLayerNode.of(string(at, m, "layer"));
                    if (m.get("inputs") instanceof Map<?, ?> ins) {
                        for (Map.Entry<?, ?> e : ins.entrySet()) node = node.input(String.valueOf(e.getKey()), pose(String.valueOf(e.getValue())));
                    }
                    yield node;
                }
                case "linked_anim_graph" -> {
                    String gid = string(at, m, "graph");
                    if (!(definition(at + ".graph", CgBodyKinds.ANIM_GRAPH, gid) instanceof CgAnimGraphDefinition d)) {
                        throw new CgAssetException(at + ".graph: " + gid + " is no anim_graph");
                    }
                    CgLinkedAnimGraphNode node = CgLinkedAnimGraphNode.of(d.graph(skeleton, clips));
                    if (m.get("tag") instanceof String tag) node = node.tag(tag);
                    if (m.get("inputs") instanceof Map<?, ?> ins) {
                        for (Map.Entry<?, ?> e : ins.entrySet()) node = node.input(String.valueOf(e.getKey()), pose(String.valueOf(e.getValue())));
                    }
                    yield node;
                }
                default -> {
                    CgAnimNodeKind reader = KINDS.get(kind);
                    if (reader == null) throw new CgAssetException(at + ".kind: " + kind + " is no anim graph node kind");
                    yield reader.read(this, at, m);
                }
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

        @Override
        public <T> CgAnimVariable<T> variable(String at, String name, CgAnimVariable.Type type) {
            return typed(at, name, type);
        }

        @Override
        public CgAnimVariable<?> variable(String at, String name) {
            CgAnimVariable<?> v = variables.get(name);
            if (v == null) throw new CgAssetException(at + ": " + name + " is no declared variable");
            return v;
        }

        @Override
        public Map<?, ?> section(String key, String name) {
            return tree.get(key) instanceof Map<?, ?> sec && sec.get(name) instanceof Map<?, ?> m ? m : null;
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

        @Override
        public String string(String at, Map<?, ?> m, String key) {
            if (!(m.get(key) instanceof String s)) throw new CgAssetException(at + "." + key + ": none");
            return s;
        }

        @Override
        public float number(String at, Object o) {
            if (!(o instanceof Number n)) throw new CgAssetException(at + ": " + o + " is not a number");
            return n.floatValue();
        }

        @Override
        public CgAnimSequence clip(String at, String clipId) {
            CgAnimSequence clip = clips.apply(clipId);
            if (clip == null) throw new CgAssetException(at + ": " + clipId + " names no clip of this mesh");
            return clip;
        }

        @Override
        public Object definition(String at, CgType kind, String did) {
            return resolver.definition(at, kind, did);
        }

        @Override
        @Nullable
        public CgSyncGroup syncGroup(String at, Map<?, ?> m) {
            if (m.get("sync_group") == null) return null;
            String name = string(at, m, "sync_group");
            Object role = m.get("group_role");
            if (role == null) return CgSyncGroup.of(name);
            try {
                return CgSyncGroup.of(name, CgAnimGroupRole.valueOf(String.valueOf(role).toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw new CgAssetException(at + ".group_role: " + role + " is no group role");
            }
        }
    }

    // ── writing ──────────────────────────────────────────────────────────────────────────

    /** Names each node the first time it is read, by its kind and a count, and writes it. */
    private static final class Namer implements Function<CgAnimNode, String> {

        final Map<String, Object> nodes = new LinkedHashMap<>();
        final Map<String, Object> sections = new LinkedHashMap<>();
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
            CgAnimNodeWriter w = new CgAnimNodeWriter(this, sections);
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
        if (!graph.entity().isEmpty()) {
            Map<String, Object> entity = new LinkedHashMap<>();
            for (Map.Entry<String, CgEntityVariable> e : graph.entity().entrySet()) entity.put(e.getKey(), e.getValue().name());
            tree.put("entity", entity);
        }
        if (!graph.tags().isEmpty()) {
            Map<String, Object> tags = new LinkedHashMap<>();
            for (Map.Entry<String, CgAnimGraph.TagBinding> e : graph.tags().entrySet()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("tag", e.getValue().tag().name());
                CgAnimVariable<?> v = graph.variables().get(graph.variable(e.getKey()));
                if (e.getValue().as() != CgAnimGraph.TagAs.of(v.type())) m.put("as", e.getValue().as().name().toLowerCase(Locale.ROOT));
                tags.put(e.getKey(), m);
            }
            tree.put("tags", tags);
        }
        if (!graph.inputs().isEmpty()) tree.put("inputs", new ArrayList<>(graph.inputs()));
        tree.put("output", output);
        Map<String, Object> layers = new LinkedHashMap<>();
        for (CgAnimGraph.Layer l : graph.layers().values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            if (!l.inputs().isEmpty()) m.put("inputs", new ArrayList<>(l.inputs()));
            m.put("output", names.apply(l.output()));
            if (l.group() != null) m.put("group", l.group());
            if (l.blendIn() != 0f) m.put("blend_in", (double) l.blendIn());
            if (l.blendOut() != 0f) m.put("blend_out", (double) l.blendOut());
            layers.put(l.name(), m);
        }
        tree.put("nodes", nodes);
        tree.putAll(names.sections);
        if (!layers.isEmpty()) tree.put("layers", layers);
        return tree;
    }
}
