package com.crystalgraphics.animation.graph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.annotation.Nullable;

import com.crystalgraphics.api.animation.CgSkeleton;
import com.crystalgraphics.tag.CgGameplayTag;
import com.crystalgraphics.net.object.CgNetField;
import com.crystalgraphics.net.object.CgNetType;
import com.crystalgraphics.object.CgValueType;
import com.crystalgraphics.world.CgEntityBinding;
import com.crystalgraphics.world.CgEntityNetField;

/**
 * An animation graph: which clips play and how they mix, from variables the game sets. Unreal's anim blueprint, compiled
 * (its generated class): immutable and shared by every character it animates, each running it in a
 * {@link CgAnimGraphInstance} of its own.
 *
 * <pre>{@code
 * static final CgAnimVariable<Float> RUN = CgAnimVariable.floating("run");         // 0 standing, 1 running
 *
 * static final CgAnimGraph HERO = CgAnimGraph.builder("mymod:hero", mesh.skeleton())
 *         .variables(RUN)
 *         .output(CgSlotNode.of(CgAnimGraph.DEFAULT_SLOT,
 *                 CgTwoWayBlendNode.of(CgSequencePlayerNode.of(IDLE).loop(), CgSequencePlayerNode.of(JOG).loop(), RUN)))
 *         .build();
 *
 * body.anim().graph(HERO);                                   // a skeletal mesh component's instance runs it
 * body.anim().onUpdate((a, dt) -> a.set(RUN, speed / 4f));
 * }</pre>
 *
 * <p>A face over the body, as Kakarot's graph has it: the body's pose cached once, a slot on the face's bones over it.</p>
 *
 * <pre>{@code
 * CgAnimGraph.builder("mymod:hero", skeleton)
 *         .save("body", CgSlotNode.of("DefaultSlot", locomotion))
 *         .output(CgLayeredBlendNode.of(CgCachedPoseNode.use("body"))
 *                 .layer(CgSlotNode.of("FacialSlot", CgCachedPoseNode.use("body")), CgBlendMask.branch("facialbase", 1)))
 *         .build();
 * }</pre>
 *
 * <ul>
 *   <li>The graph is a tree: a node is read by one parent. A pose read twice is a cached pose ({@link #save} once,
 *       {@link CgCachedPoseNode#use} wherever), evaluated once a frame.</li>
 *   <li>Building checks it: a cycle, a node read twice, a cached pose used and never saved, or a variable a node reads
 *       and the graph does not declare, each fails, naming it.</li>
 *   <li>{@link Builder#entity} binds a variable to a value of the entity: a field the engine derives
 *       ({@code CgEntityFields}) or one of its networked data ({@code CgEntityNetField}). An entity actor then sets it
 *       every frame ({@link CgEntityFeed}), and no code does.</li>
 *   <li>{@link Builder#tag} binds a variable to a gameplay tag the entity owns ({@code CgEntityTags}): set every frame
 *       from its count, as Unreal's gameplay tag property map sets a property.</li>
 *   <li>{@link #single} is a graph of one clip over the default slot: what an instance runs until it is given one.</li>
 * </ul>
 */
public final class CgAnimGraph {

    /** The slot a sequence's animation section plays on when it names none: Unreal's {@code DefaultSlot}. */
    public static final String DEFAULT_SLOT = "DefaultSlot";

    private final String id;
    private final CgSkeleton skeleton;
    private final List<CgAnimVariable<?>> variables;
    private final Map<String, Integer> variableIndex = new LinkedHashMap<>();
    private final CgAnimNode output;
    private final Map<String, CgAnimNode> saves;
    private final List<String> slots;
    private final List<String> saveOrder;
    private final CgSequencePlayerNode single;

    private final List<String> inputs;
    private final Map<String, Layer> layers;
    private final Map<String, CgEntityBinding> entity;
    private final Map<String, TagBinding> tags;

    /**
     * How a tag's count becomes a variable's value: Unreal's choice by the property's type, named. {@code BOOL} whether
     * it is owned, {@code COUNT} its count, {@code FLOAT} its count as a number.
     */
    public enum TagAs {
        BOOL, COUNT, FLOAT;

        /** What a variable of {@code type} takes when a binding says nothing. */
        public static TagAs of(CgAnimVariable.Type type) {
            return switch (type) {
                case BOOL -> BOOL;
                case INT -> COUNT;
                case FLOAT -> FLOAT;
            };
        }

        /** Whether a variable of {@code type} can take it: its own type, or a float, which takes any. */
        public boolean fits(CgAnimVariable.Type type) {
            return type == CgAnimVariable.Type.FLOAT || of(type) == this;
        }

        /** {@code count} as the value a variable holds. */
        public float value(int count) {
            return this == BOOL ? (count > 0 ? 1f : 0f) : count;
        }
    }

    /** A variable's gameplay tag and how its count becomes the value. */
    public record TagBinding(CgGameplayTag tag, TagAs as) {
        public TagBinding {
            Objects.requireNonNull(tag, "tag");
            Objects.requireNonNull(as, "as");
        }
    }

    /**
     * A layer: a named part of the graph another graph may play instead, Unreal's anim layer function. Its output reads
     * its input poses through {@link CgLinkedInputPoseNode}s; a {@link CgLinkedAnimLayerNode} plays it.
     *
     * <pre>{@code
     * CgAnimGraph.Layer legs = CgAnimGraph.Layer.of("legs", CgLinkedInputPoseNode.of("body"), "body");   // passes through
     * CgAnimGraph.Layer arms = CgAnimGraph.Layer.of("arms", aiming, "body").group("upper").blend(0.2f, 0.2f);
     * }</pre>
     *
     * @param name      what a layer node calls it by
     * @param inputs    its input poses' names
     * @param output    its pose
     * @param group     layers of one group linked from one graph share one run of it; null, each its own
     * @param blendIn   seconds a swap to it is inertialized over
     * @param blendOut  seconds a swap away from it is inertialized over
     */
    public record Layer(String name, List<String> inputs, CgAnimNode output, @Nullable String group, float blendIn, float blendOut) {

        public Layer {
            Objects.requireNonNull(name, "name");
            inputs = List.copyOf(inputs);
            Objects.requireNonNull(output, "output");
        }

        /** Layer {@code name} of {@code output}, reading {@code inputs}; ungrouped, swapped with no blend. */
        public static Layer of(String name, CgAnimNode output, String... inputs) {
            return new Layer(name, List.of(inputs), output, null, 0f, 0f);
        }

        public Layer group(@Nullable String group) {
            return new Layer(name, inputs, output, group, blendIn, blendOut);
        }

        public Layer blend(float in, float out) {
            return new Layer(name, inputs, output, group, in, out);
        }
    }

    private CgAnimGraph(String id, CgSkeleton skeleton, List<CgAnimVariable<?>> variables, Map<String, CgAnimNode> saves,
                        CgAnimNode output, CgSequencePlayerNode single) {
        this(id, skeleton, variables, saves, output, single, List.of(), Map.of(), Map.of(), Map.of());
    }

    private CgAnimGraph(String id, CgSkeleton skeleton, List<CgAnimVariable<?>> variables, Map<String, CgAnimNode> saves,
                        CgAnimNode output, CgSequencePlayerNode single, List<String> inputs, Map<String, Layer> layers,
                        Map<String, CgEntityBinding> entity, Map<String, TagBinding> tags) {
        this.id = id;
        this.skeleton = skeleton;
        this.inputs = List.copyOf(inputs);
        this.layers = Collections.unmodifiableMap(new LinkedHashMap<>(layers));
        this.variables = Collections.unmodifiableList(new ArrayList<>(variables));
        for (int i = 0; i < this.variables.size(); i++) {
            if (variableIndex.put(this.variables.get(i).name(), i) != null) {
                throw new IllegalArgumentException(id + " declares variable " + this.variables.get(i).name() + " twice");
            }
        }
        // Unreal's property map checks the same at validation: a declared variable, of a type the value fits
        // (FGameplayTagBlueprintPropertyMap::IsDataValid)
        for (Map.Entry<String, CgEntityBinding> e : entity.entrySet()) {
            Integer at = variableIndex.get(e.getKey());
            if (at == null) throw new IllegalArgumentException(id + ": entity." + e.getKey() + " binds a variable the graph does not declare");
            if (!takes(this.variables.get(at).type(), e.getValue().type())) {
                throw new IllegalArgumentException(id + ": entity." + e.getKey() + ": " + this.variables.get(at) + " cannot take "
                        + e.getValue().name() + ", a " + e.getValue().type());
            }
        }
        this.entity = Collections.unmodifiableMap(new LinkedHashMap<>(entity));
        for (Map.Entry<String, TagBinding> e : tags.entrySet()) {
            Integer at = variableIndex.get(e.getKey());
            if (at == null) throw new IllegalArgumentException(id + ": tags." + e.getKey() + " binds a variable the graph does not declare");
            if (entity.containsKey(e.getKey())) {
                throw new IllegalArgumentException(id + ": " + e.getKey() + " is bound both to an entity variable and to a tag");
            }
            if (!e.getValue().as().fits(this.variables.get(at).type())) {
                throw new IllegalArgumentException(id + ": tags." + e.getKey() + ": " + this.variables.get(at) + " cannot take "
                        + e.getValue().tag() + " as " + e.getValue().as().name().toLowerCase(Locale.ROOT));
            }
        }
        this.tags = Collections.unmodifiableMap(new LinkedHashMap<>(tags));
        this.saves = Collections.unmodifiableMap(new LinkedHashMap<>(saves));
        this.output = Objects.requireNonNull(output, "output");
        this.single = single;
        List<String> found = new ArrayList<>();
        Set<CgAnimNode> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<String> called = new HashSet<>();
        walk(output, seen, Collections.newSetFromMap(new IdentityHashMap<>()), found, this.inputs, called);
        for (CgAnimNode saved : saves.values()) walk(saved, seen, Collections.newSetFromMap(new IdentityHashMap<>()), found, this.inputs, called);
        for (Layer layer : this.layers.values()) {
            walk(layer.output(), seen, Collections.newSetFromMap(new IdentityHashMap<>()), found, layer.inputs(), called);
        }
        this.slots = Collections.unmodifiableList(found);
        for (String save : saves.keySet()) saveCycle(save, new ArrayList<>());
        List<String> order = new ArrayList<>();
        for (String save : saves.keySet()) postOrder(save, order);
        Collections.reverse(order);
        this.saveOrder = Collections.unmodifiableList(order);
    }

    /** {@code save} after every save it reads: reversed, a save flushes before the saves it requests. */
    private void postOrder(String save, List<String> out) {
        if (out.contains(save)) return;
        List<String> used = new ArrayList<>();
        uses(saves.get(save), used);
        for (String u : used) postOrder(u, out);
        out.add(save);
    }

    /** Its cached poses in flush order: each before the saves it reads, so their heaviest use is known when they flush. */
    List<String> saveOrder() {
        return saveOrder;
    }

    /** A save reading itself through other saves: the tree walk stops at each use, so cycles across saves are found here. */
    private void saveCycle(String save, List<String> path) {
        if (path.contains(save)) throw new IllegalArgumentException(id + ": cached poses read each other: " + path + " -> " + save);
        path.add(save);
        List<String> used = new ArrayList<>();
        uses(saves.get(save), used);
        for (String u : used) saveCycle(u, path);
        path.remove(path.size() - 1);
    }

    private static void uses(CgAnimNode node, List<String> out) {
        if (node instanceof CgCachedPoseNode.Use u) out.add(u.name());
        node.children(child -> uses(child, out));
    }

    /**
     * Checks the tree and gathers its slots, cached poses and variables; {@code scope} is the input poses a linked
     * input pose may name there (the layer's, or the graph's own), {@code called} the layers played so far.
     */
    private void walk(CgAnimNode node, Set<CgAnimNode> seen, Set<CgAnimNode> path, List<String> slots, List<String> scope,
                      Set<String> called) {
        if (!path.add(node)) throw new IllegalArgumentException(id + ": a cycle through " + node);
        if (!seen.add(node)) throw new IllegalArgumentException(id + ": " + node + " is read twice; cache its pose instead");
        if (node instanceof CgSlotNode s) {
            if (slots.contains(s.name())) throw new IllegalArgumentException(id + ": slot " + s.name() + " twice");
            slots.add(s.name());
        }
        if (node instanceof CgLinkedInputPoseNode p && !scope.contains(p.name())) {
            throw new IllegalArgumentException(id + ": input pose " + p.name() + " is none of " + scope);
        }
        if (node instanceof CgLinkedAnimLayerNode l) {
            Layer layer = layers.get(l.layer());
            if (layer == null) throw new IllegalArgumentException(id + ": " + node + " plays layer " + l.layer() + ", which the graph does not declare");
            if (!called.add(l.layer())) throw new IllegalArgumentException(id + ": layer " + l.layer() + " is played by two nodes");
            for (String in : l.inputs().keySet()) {
                if (!layer.inputs().contains(in)) throw new IllegalArgumentException(id + ": " + node + " passes " + in + ", none of " + layer.inputs());
            }
        }
        if (node instanceof CgLinkedAnimGraphNode g && g.graph() != null) {
            for (String in : g.inputs().keySet()) {
                if (!g.graph().inputs().contains(in)) {
                    throw new IllegalArgumentException(id + ": " + node + " passes " + in + ", none of " + g.graph().inputs());
                }
            }
        }
        if (node instanceof CgCachedPoseNode.Use u && !saves.containsKey(u.name())) {
            throw new IllegalArgumentException(id + ": cached pose " + u.name() + " is used and never saved");
        }
        node.reads(v -> {
            Integer at = variableIndex.get(v.name());
            if (at == null) throw new IllegalArgumentException(id + ": " + node + " reads " + v.name() + ", which the graph does not declare");
            if (variables.get(at).type() != v.type()) {
                throw new IllegalArgumentException(id + ": " + node + " reads " + v + ", declared " + variables.get(at));
            }
        });
        node.children(child -> walk(child, seen, path, slots, scope, called));
        path.remove(node);
    }

    /** A graph built node by node. */
    public static Builder builder(String id, CgSkeleton skeleton) {
        return new Builder(id, skeleton);
    }

    /**
     * One clip over the default slot, Unreal's single node instance ({@code PlayAnimation}): what an instance runs
     * before it is given a graph. {@code CgAnimInstance.play(clip)} sets its clip.
     */
    public static CgAnimGraph single(CgSkeleton skeleton) {
        CgSequencePlayerNode player = CgSequencePlayerNode.single();
        return new CgAnimGraph("crystalgraphics:single", skeleton, List.of(), Map.of(), CgSlotNode.of(DEFAULT_SLOT, player), player);
    }

    public String id() {
        return id;
    }

    /** The skeleton its masks name bones of. */
    public CgSkeleton skeleton() {
        return skeleton;
    }

    /** Its variables, in declaration order. */
    public List<CgAnimVariable<?>> variables() {
        return variables;
    }

    /** Variable {@code name}'s index, or -1 where the graph declares none. */
    public int variable(String name) {
        Integer at = variableIndex.get(name);
        return at == null ? -1 : at;
    }

    /** Its slots' names, as the tree reads them. */
    public List<String> slots() {
        return slots;
    }

    public CgAnimNode output() {
        return output;
    }

    /** Its cached poses by name. */
    public Map<String, CgAnimNode> saves() {
        return saves;
    }

    /** The input poses it reads when another graph plays it ({@link CgLinkedAnimGraphNode}). */
    public List<String> inputs() {
        return inputs;
    }

    /** Its variables an entity actor sets each frame, by variable name: what feeds each. */
    public Map<String, CgEntityBinding> entity() {
        return entity;
    }

    /** Whether a variable of {@code variable}'s type takes a value of {@code value}'s: its own, or any as a float. */
    static boolean takes(CgAnimVariable.Type variable, CgValueType value) {
        return switch (variable) {
            case FLOAT -> true;
            case INT -> CgValueType.INT.equals(value);
            case BOOL -> CgValueType.BOOL.equals(value);
        };
    }

    /** Its variables set each frame from a gameplay tag the entity owns, by variable name. */
    public Map<String, TagBinding> tags() {
        return tags;
    }

    /** Its layers by name: what it plays itself, and what it offers another graph's layer nodes. */
    public Map<String, Layer> layers() {
        return layers;
    }

    /** The single node graph's player, else null. */
    CgSequencePlayerNode singlePlayer() {
        return single;
    }

    @Override
    public String toString() {
        return "CgAnimGraph(" + id + ")";
    }

    /** Builds a {@link CgAnimGraph}. */
    public static final class Builder {

        private final String id;
        private final CgSkeleton skeleton;
        private final List<CgAnimVariable<?>> variables = new ArrayList<>();
        private final Map<String, CgAnimNode> saves = new LinkedHashMap<>();
        private final List<String> inputs = new ArrayList<>();
        private final Map<String, Layer> layers = new LinkedHashMap<>();
        private final Map<String, CgEntityBinding> entity = new LinkedHashMap<>();
        private final Map<String, TagBinding> tags = new LinkedHashMap<>();
        private CgAnimNode output;

        private Builder(String id, CgSkeleton skeleton) {
            this.id = Objects.requireNonNull(id, "id");
            this.skeleton = Objects.requireNonNull(skeleton, "skeleton");
        }

        /** Declares {@code variables}: the game sets them, nodes read them. */
        public Builder variables(CgAnimVariable<?>... variables) {
            Collections.addAll(this.variables, variables);
            return this;
        }

        /**
         * {@code source}'s pose cached under {@code name}, evaluated once a frame however many
         * {@link CgCachedPoseNode#use} read it: Unreal's Save Cached Pose.
         */
        public Builder save(String name, CgAnimNode source) {
            if (saves.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(source, "source")) != null) {
                throw new IllegalArgumentException(id + " saves " + name + " twice");
            }
            return this;
        }

        /** The node whose pose is the graph's: Unreal's Output Pose. */
        public Builder output(CgAnimNode output) {
            this.output = output;
            return this;
        }

        /** The input poses its output reads when another graph plays it: Unreal's Input Pose nodes of the AnimGraph. */
        public Builder inputs(String... inputs) {
            Collections.addAll(this.inputs, inputs);
            return this;
        }

        /**
         * Has {@code variable} set every frame from {@code source}, a value of the entity, by whatever actor animates
         * an entity with this graph: Unreal's gameplay tag property map, over the entity's values.
         *
         * <pre>{@code
         * builder.variables(SPEED, ON_GROUND, KI)
         *        .entity(SPEED, CgEntityFields.SPEED)                       // derived each frame, on each side
         *        .entity(ON_GROUND, CgEntityFields.GROUNDED)
         *        .entity(KI, Mana.TYPE, Mana.CURRENT);                      // a field of the entity's networked data
         * }</pre>
         *
         * The variable must be declared, and of {@code source}'s type, or a float.
         */
        public Builder entity(CgAnimVariable<?> variable, CgEntityBinding source) {
            if (entity.put(variable.name(), Objects.requireNonNull(source, "source")) != null) {
                throw new IllegalArgumentException(id + " binds " + variable.name() + " twice");
            }
            return this;
        }

        /** As {@link #entity(CgAnimVariable, CgEntityBinding)}, from {@code field} of the entity's object of {@code type}. */
        public Builder entity(CgAnimVariable<?> variable, CgNetType type, CgNetField<?> field) {
            return entity(variable, CgEntityNetField.of(type, field));
        }

        /**
         * Has {@code variable} set every frame from {@code tag}'s count on the entity an actor animates with this graph
         * ({@code CgEntityTags}), as its type takes it: a bool whether the tag is owned, an int its count, a float the
         * count. Unreal's gameplay tag property map.
         *
         * <pre>{@code
         * builder.variables(FLYING).tag(FLYING, CgTagRegistry.global().require("mymod:state.flying"));
         * }</pre>
         *
         * A tag counts the tags under it: {@code mymod:state.flying} is owned while {@code mymod:state.flying.fast} is.
         */
        public Builder tag(CgAnimVariable<?> variable, CgGameplayTag tag) {
            return tag(variable, tag, TagAs.of(variable.type()));
        }

        /** As {@link #tag(CgAnimVariable, CgGameplayTag)}, its count taken {@code as} said: a float can take a bool. */
        public Builder tag(CgAnimVariable<?> variable, CgGameplayTag tag, TagAs as) {
            if (tags.put(variable.name(), new TagBinding(tag, as)) != null) {
                throw new IllegalArgumentException(id + " binds " + variable.name() + " to a tag twice");
            }
            return this;
        }

        /** Declares {@code layer}: its own, and one a graph linking this one's layers may take. */
        public Builder layer(Layer layer) {
            if (layers.put(layer.name(), layer) != null) throw new IllegalArgumentException(id + " declares layer " + layer.name() + " twice");
            return this;
        }

        /**
         * @throws IllegalArgumentException for no output, a cycle, a node read twice, an unsaved cached pose, an
         *                                  undeclared variable, or a layer or input pose the graph does not declare,
         *                                  or an entity or tag binding to an undeclared variable or of a type it
         *                                  cannot take
         */
        public CgAnimGraph build() {
            if (output == null) throw new IllegalArgumentException(id + " has no output");
            return new CgAnimGraph(id, skeleton, variables, saves, output, null, inputs, layers, entity, tags);
        }
    }
}
