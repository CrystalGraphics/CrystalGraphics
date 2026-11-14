package com.crystalgraphics.animation.graph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.crystalgraphics.api.animation.CgSkeleton;

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

    private CgAnimGraph(String id, CgSkeleton skeleton, List<CgAnimVariable<?>> variables, Map<String, CgAnimNode> saves,
                        CgAnimNode output, CgSequencePlayerNode single) {
        this.id = id;
        this.skeleton = skeleton;
        this.variables = Collections.unmodifiableList(new ArrayList<>(variables));
        for (int i = 0; i < this.variables.size(); i++) {
            if (variableIndex.put(this.variables.get(i).name(), i) != null) {
                throw new IllegalArgumentException(id + " declares variable " + this.variables.get(i).name() + " twice");
            }
        }
        this.saves = Collections.unmodifiableMap(new LinkedHashMap<>(saves));
        this.output = Objects.requireNonNull(output, "output");
        this.single = single;
        List<String> found = new ArrayList<>();
        Set<CgAnimNode> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        walk(output, seen, Collections.newSetFromMap(new IdentityHashMap<>()), found);
        for (CgAnimNode saved : saves.values()) walk(saved, seen, Collections.newSetFromMap(new IdentityHashMap<>()), found);
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

    /** Checks the tree and gathers its slots, cached poses and variables. */
    private void walk(CgAnimNode node, Set<CgAnimNode> seen, Set<CgAnimNode> path, List<String> slots) {
        if (!path.add(node)) throw new IllegalArgumentException(id + ": a cycle through " + node);
        if (!seen.add(node)) throw new IllegalArgumentException(id + ": " + node + " is read twice; cache its pose instead");
        if (node instanceof CgSlotNode s) {
            if (slots.contains(s.name())) throw new IllegalArgumentException(id + ": slot " + s.name() + " twice");
            slots.add(s.name());
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
        node.children(child -> walk(child, seen, path, slots));
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

        /**
         * @throws IllegalArgumentException for no output, a cycle, a node read twice, an unsaved cached pose, or an
         *                                  undeclared variable
         */
        public CgAnimGraph build() {
            if (output == null) throw new IllegalArgumentException(id + " has no output");
            return new CgAnimGraph(id, skeleton, variables, saves, output, null);
        }
    }
}
