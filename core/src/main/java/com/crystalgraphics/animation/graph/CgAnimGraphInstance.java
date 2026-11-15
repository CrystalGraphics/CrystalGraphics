package com.crystalgraphics.animation.graph;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import org.joml.Matrix4d;
import org.joml.Matrix4dc;

import com.crystalgraphics.animation.sync.CgAnimAssetPlayer;
import com.crystalgraphics.animation.sync.CgAnimSync;
import com.crystalgraphics.animation.sync.CgSyncGroup;
import com.crystalgraphics.api.animation.CgAnimPose;
import com.crystalgraphics.api.animation.CgAnimSequence;
import com.crystalgraphics.api.animation.CgSkeleton;

/**
 * One character running a {@link CgAnimGraph}: its variables, each node's runtime, its slots, and a frame's work.
 * Unreal's anim instance and its proxy together. A {@code CgAnimInstance} holds one and runs it each frame; a node reads
 * it while it updates and evaluates.
 *
 * <pre>{@code
 * CgAnimGraphInstance g = new CgAnimGraphInstance(graph, skeleton);
 * g.set(SPEED, 3.5f);                       // the game thread
 * g.preUpdate();                            // the game thread: variables handed to the worker's copy
 * g.update(dt);                             // a worker: weights, transitions, every player's tick
 * g.evaluate(pose);                         // the same worker: the pose
 * }</pre>
 *
 * <ul>
 *   <li>The game thread sets variables and slot entries; {@link #preUpdate} copies the variables for the worker, which
 *       reads only its copy, as Unreal's proxy does.</li>
 *   <li>{@link #update} then {@link #evaluate}, once a frame each, on one thread. Slot entries are this frame's: the
 *       evaluate clears them.</li>
 *   <li>Allocates nothing per frame once warm: its pose pool grows to the graph's depth in the first frames and stays.</li>
 * </ul>
 */
public final class CgAnimGraphInstance {

    private final CgAnimGraph graph;
    private final CgSkeleton skeleton;
    private final float[] values, proxy;
    private final Map<CgAnimNode, CgAnimNode.Runtime> runtimes = new IdentityHashMap<>();
    private final Map<String, CgAnimSlot> slots = new LinkedHashMap<>();
    private final CgAnimNode.Runtime root;
    private final CgAnimSlot[] slotArray;
    private final CgCachedPoseNode.SaveRuntime[] saveArray, flushOrder;
    private final Map<String, CgCachedPoseNode.SaveRuntime> saves = new LinkedHashMap<>();
    @Nullable
    private final CgSequencePlayerNode.PlayerRuntime single;
    private float delta;
    private double seconds;
    private long frame;
    private int curveLayer, curveLayers, playerScope, inertializersAbove;
    private Inertializer[] inertializers = new Inertializer[2];
    private int inertializing;

    // This frame's players, ticked by the sync after the walk, and the clips sampled, for the curves
    private final CgAnimSync sync = new CgAnimSync();
    private CgAnimAssetPlayer[] recordPlayers = new CgAnimAssetPlayer[8];
    private float[] recordWeights = new float[8];
    private int[] recordLayers = new int[8], recordScopes = new int[8];
    private int records;
    /** Montages leading a sync group this frame, queued on the game thread. */
    private CgAnimAssetPlayer[] montagePlayers = new CgAnimAssetPlayer[0];
    private float[] montageWeights = new float[0];
    private String[] montageGroups = new String[0];
    private int montages;
    @Nullable
    private CgAnimAssetPlayer[] samplePlayers = new CgAnimAssetPlayer[8];
    private CgAnimSequence[] sampleClips = new CgAnimSequence[8];
    private float[] sampleTimes = new float[8], sampleWeights = new float[8];
    private int[] sampleLayers = new int[8], sampleScopes = new int[8];
    private int samples;
    private final Sink sink = new Sink();

    private CgAnimPose[] pool = new CgAnimPose[4];
    private int pooled, borrowed;

    /** The instance whose frame this runs in: itself, or a linked instance's owner's own main. */
    private final CgAnimGraphInstance main;
    /** Per variable, its main instance's of the same name and type, or -1. */
    private final int[] fromMain;
    /** Each layer's (or, under "", the graph's) input poses, by name: what its linked input pose nodes read. */
    private final Map<String, Input> inputs = new LinkedHashMap<>();
    /** The layer whose nodes are instantiating now, "" for the graph's own. */
    private String scope = "";
    /** A main instance's linked instances; a linked instance's last frame run. */
    private final List<CgAnimGraphInstance> linked = new ArrayList<>();
    private long linkedFrame = -1;
    /** A main instance's slots its linked graphs brought. */
    private final List<CgAnimSlot> extraSlots = new ArrayList<>();
    /** A linked instance's layer and graph nodes playing it: at 0 it leaves its main. */
    int users;
    /** Runtimes asking for the game thread's {@code preUpdate}: Unreal's {@code bHasPreUpdate}. */
    private final List<CgAnimNode.Runtime> preUpdates = new ArrayList<>();
    /** The component's placement in the world, copied on the game thread (Unreal's proxy {@code ComponentTransform}). */
    private final Matrix4d placement = new Matrix4d();
    private CgAnimGround ground = CgAnimGround.NONE;

    public CgAnimGraphInstance(CgAnimGraph graph, CgSkeleton skeleton) {
        this(graph, skeleton, null);
    }

    /**
     * A run of {@code graph} inside {@code owner}'s: a linked graph's or a layer graph's. It keeps its own variables,
     * nodes and cached poses; its players tick in its owner's sync, its clips reach its owner's curves, a slot its owner
     * has is its owner's (Unreal's linked instances play the main instance's montages), and its variables are its
     * owner's of the same name and type each frame.
     */
    CgAnimGraphInstance(CgAnimGraph graph, CgSkeleton skeleton, @Nullable CgAnimGraphInstance owner) {
        this.graph = Objects.requireNonNull(graph, "graph");
        this.skeleton = Objects.requireNonNull(skeleton, "skeleton");
        this.main = owner == null ? this : owner.main;
        values = new float[graph.variables().size()];
        for (int i = 0; i < values.length; i++) values[i] = graph.variables().get(i).defaultValue();
        proxy = values.clone();
        fromMain = new int[values.length];
        for (int i = 0; i < values.length; i++) {
            CgAnimVariable<?> v = graph.variables().get(i);
            int at = main.graph.variable(v.name());
            fromMain[i] = main != this && at >= 0 && main.graph.variables().get(at).type() == v.type() ? at : -1;
        }
        for (String name : graph.slots()) {
            CgAnimSlot slot = main != this ? main.slots.get(name) : null;
            if (slot == null) {
                slot = new CgAnimSlot(name);
                // A linked graph's slot is its main's, as Unreal's linked instances play the main instance's montages
                if (main != this) {
                    main.slots.put(name, slot);
                    main.extraSlots.add(slot);
                }
            }
            slots.put(name, slot);
        }
        for (String name : graph.saves().keySet()) save(name);
        root = runtime(graph.output());
        single = graph.singlePlayer() == null ? null : (CgSequencePlayerNode.PlayerRuntime) runtime(graph.singlePlayer());
        // Arrays for the frame's loops, which allocate nothing
        slotArray = slots.values().toArray(new CgAnimSlot[0]);
        saveArray = saves.values().toArray(new CgCachedPoseNode.SaveRuntime[0]);
        flushOrder = new CgCachedPoseNode.SaveRuntime[saveArray.length];
        for (int i = 0; i < flushOrder.length; i++) flushOrder[i] = saves.get(graph.saveOrder().get(i));
        initialize();
        if (main != this) main.linked.add(this);
    }

    public CgAnimGraph graph() {
        return graph;
    }

    public CgSkeleton skeleton() {
        return skeleton;
    }

    /** {@code node}'s runtime here, made the first time it is asked for: what a node's {@code instantiate} reads its children with. */
    public CgAnimNode.Runtime runtime(CgAnimNode node) {
        CgAnimNode.Runtime r = runtimes.get(node);
        if (r == null) {
            r = node.instantiate(this);
            runtimes.put(node, r);
            if (r.hasPreUpdate()) preUpdates.add(r);
        }
        return r;
    }

    /** The cached pose saved as {@code name}: made the first time a node asks, its source's runtime with it. */
    CgCachedPoseNode.SaveRuntime save(String name) {
        CgCachedPoseNode.SaveRuntime s = saves.get(name);
        if (s == null) {
            CgAnimNode source = graph.saves().get(name);
            if (source == null) throw new IllegalArgumentException(graph + " saves no pose " + name);
            s = new CgCachedPoseNode.SaveRuntime(name);
            saves.put(name, s);
            s.source(runtime(source));
        }
        return s;
    }

    /** A new curve layer's number, for a layered blend's layer at instantiate. */
    int newCurveLayer() {
        return main != this ? main.newCurveLayer() : ++curveLayers;
    }

    // ── linked graphs and layers ─────────────────────────────────────────────────────────

    /** An input pose handed into a layer or a linked graph: the node passing it, and the instance that node runs in. */
    static final class Input {
        @Nullable
        CgAnimNode.Runtime source;
        @Nullable
        CgAnimGraphInstance instance;

        void set(@Nullable CgAnimNode.Runtime source, @Nullable CgAnimGraphInstance instance) {
            this.source = source;
            this.instance = instance;
        }
    }

    /** Input pose {@code name} of the layer instantiating now (or of the graph's own inputs). */
    Input input(String name) {
        return input(scope, name);
    }

    Input input(String layer, String name) {
        return inputs.computeIfAbsent(layer + '\u0000' + name, k -> new Input());
    }

    /** Layer {@code layer}'s output here, its nodes instantiated in the layer's scope. */
    CgAnimNode.Runtime layerRuntime(String layer) {
        CgAnimGraph.Layer l = graph.layers().get(layer);
        if (l == null) throw new IllegalArgumentException(graph + " declares no layer " + layer);
        String was = scope;
        scope = layer;
        try {
            return runtime(l.output());
        } finally {
            scope = was;
        }
    }

    /** The main instance this one runs in: itself unless it is a linked instance. */
    CgAnimGraphInstance main() {
        return main;
    }

    /**
     * A linked instance's part of its main's update: {@code root} (its output or a layer's) at {@code weight}. Its first
     * in a frame takes the main's variables and begins its cached poses; the main flushes them after its walk.
     */
    void linkedUpdate(CgAnimNode.Runtime root, float weight) {
        if (linkedFrame != main.frame) {
            linkedFrame = main.frame;
            for (int i = 0; i < fromMain.length; i++) {
                if (fromMain[i] >= 0) proxy[i] = main.proxy[fromMain[i]];
                else proxy[i] = values[i];
            }
            for (CgCachedPoseNode.SaveRuntime s : saveArray) s.begin();
        }
        root.update(this, weight);
    }

    void linkedEvaluate(CgAnimNode.Runtime root, CgAnimPose out) {
        root.evaluate(this, out);
    }

    /** Leaves its main: it no longer runs. */
    void unlinkFromMain() {
        main.linked.remove(this);
    }

    /** How many linked instances run in this one. */
    int linkedInstances() {
        return linked.size();
    }

    /**
     * Plays each layer {@code layers} declares through it, wherever this graph plays that layer: Unreal's
     * {@code LinkAnimClassLayers}. Layers of one group share one run of it; an ungrouped layer has its own. A layer
     * already played by {@code layers} is left as it is.
     *
     * @throws IllegalArgumentException for this instance's own graph, which would play itself
     */
    public void linkLayers(CgAnimGraph layers) {
        if (layers == graph) throw new IllegalArgumentException(graph + " cannot link its own layers");
        Map<String, CgAnimGraphInstance> groups = new LinkedHashMap<>();
        for (CgAnimNode.Runtime r : runtimes.values().toArray(new CgAnimNode.Runtime[0])) {
            if (!(r instanceof CgLinkedAnimLayerNode.LayerRuntime l)) continue;
            CgAnimGraph.Layer layer = layers.layers().get(l.layer());
            if (layer == null || l.linkedGraph() == layers) continue;
            CgAnimGraphInstance target = layer.group() == null ? null : groups.get(layer.group());
            if (target == null) {
                target = linkedInstance(layers, l.inertialized());
                if (layer.group() != null) groups.put(layer.group(), target);
            }
            l.link(this, target, layer);
        }
    }

    /**
     * A run of {@code linked} in this instance, built as though inside the node linking it: under an inertialization
     * node where that node is, so the linked graph's own transitions find one.
     */
    CgAnimGraphInstance linkedInstance(CgAnimGraph linked, boolean inertialized) {
        if (inertialized) main.inertializersAbove++;
        try {
            return new CgAnimGraphInstance(linked, skeleton, this);
        } finally {
            if (inertialized) main.inertializersAbove--;
        }
    }

    /** Plays each layer {@code layers} plays here as this graph's own again: Unreal's {@code UnlinkAnimClassLayers}. */
    public void unlinkLayers(CgAnimGraph layers) {
        for (CgAnimNode.Runtime r : runtimes.values().toArray(new CgAnimNode.Runtime[0])) {
            if (r instanceof CgLinkedAnimLayerNode.LayerRuntime l && l.linkedGraph() == layers) l.link(this, null, null);
        }
    }

    /**
     * Runs {@code graph} in the linked graph node tagged {@code tag}, or nothing for null: Unreal's
     * {@code LinkAnimGraphByTag}.
     *
     * @throws IllegalArgumentException where no linked graph node has the tag
     */
    public void linkGraph(String tag, @Nullable CgAnimGraph graph) {
        for (CgAnimNode.Runtime r : runtimes.values()) {
            if (r instanceof CgLinkedAnimGraphNode.GraphRuntime gr && tag.equals(gr.tag())) {
                gr.link(this, graph);
                return;
            }
        }
        throw new IllegalArgumentException(this.graph + " has no linked graph tagged " + tag);
    }

    /** Each node's runtime, in no order: what a game-thread request to every node of a kind walks (a transition event). */
    public void forEachRuntime(Consumer<CgAnimNode.Runtime> each) {
        runtimes.values().forEach(each);
    }

    /** Every node back to its first frame: clips to their start, blends to their first choice. */
    public void initialize() {
        for (CgCachedPoseNode.SaveRuntime s : saves.values()) s.initialize(this);
        root.initialize(this);
    }

    // ── variables ────────────────────────────────────────────────────────────────────────

    /**
     * Sets {@code variable}, on the game thread: the graph reads it from the next {@link #preUpdate}. Typed, so a value
     * set every frame boxes nothing.
     *
     * @throws IllegalArgumentException where the graph does not declare it
     */
    public CgAnimGraphInstance set(CgAnimVariable<Float> variable, float value) {
        values[index(variable)] = value;
        return this;
    }

    /** As {@link #set(CgAnimVariable, float)}, a bool. */
    public CgAnimGraphInstance set(CgAnimVariable<Boolean> variable, boolean value) {
        values[index(variable)] = value ? 1f : 0f;
        return this;
    }

    /** As {@link #set(CgAnimVariable, float)}, an int. */
    public CgAnimGraphInstance set(CgAnimVariable<Integer> variable, int value) {
        values[index(variable)] = value;
        return this;
    }

    /** Its game-thread value, as a float. */
    public float value(CgAnimVariable<?> variable) {
        return values[index(variable)];
    }

    /** What a node reads, on the worker: the copy taken at {@link #preUpdate}. */
    public float get(CgAnimVariable<?> variable) {
        return proxy[index(variable)];
    }

    /** A bool's worker value. */
    public boolean is(CgAnimVariable<Boolean> variable) {
        return proxy[index(variable)] != 0f;
    }

    private int index(CgAnimVariable<?> variable) {
        int at = variable.indexIn(graph);
        if (at < 0) throw new IllegalArgumentException(graph + " declares no variable " + variable.name());
        return at;
    }

    // ── slots ────────────────────────────────────────────────────────────────────────────

    /** Its slot named {@code name}, or null where the graph has none: what a sequence's animation section plays into. */
    @Nullable
    public CgAnimSlot slot(String name) {
        return slots.get(name);
    }

    /** The single node graph's player, which {@code CgAnimInstance.play} sets; null in any other graph. */
    @Nullable
    public CgSequencePlayerNode.PlayerRuntime single() {
        return single;
    }

    // ── the frame ────────────────────────────────────────────────────────────────────────

    /** The game thread, before the worker's update: the variables handed over. */
    public void preUpdate() {
        System.arraycopy(values, 0, proxy, 0, values.length);
        for (int i = 0; i < preUpdates.size(); i++) preUpdates.get(i).preUpdate(this);
        for (int i = 0; i < linked.size(); i++) {
            CgAnimGraphInstance l = linked.get(i);
            for (int j = 0; j < l.preUpdates.size(); j++) l.preUpdates.get(j).preUpdate(l);
        }
    }

    /**
     * Where the component stands in the world, set on the game thread before {@link #preUpdate}: what a node reads a
     * world-space target or a trace by. Identity until set.
     */
    public CgAnimGraphInstance placement(Matrix4dc placement) {
        this.placement.set(placement);
        return this;
    }

    /** The component's placement in the world, as of this frame's {@link #preUpdate}: its main instance's. */
    public Matrix4dc placement() {
        return main.placement;
    }

    /** The ground a node asks on the game thread, in its {@code preUpdate}: {@link CgAnimGround#NONE} until set. */
    public CgAnimGraphInstance ground(CgAnimGround ground) {
        this.ground = Objects.requireNonNull(ground, "ground");
        return this;
    }

    /** Its ground: its main instance's. */
    public CgAnimGround ground() {
        return main.ground;
    }

    /**
     * This frame's update, {@code delta} seconds on: the walk from the output (weights, relevance), each cached pose's
     * source at its heaviest use (Unreal's {@code PostGraphUpdate}), then every recorded player ticked by sync group
     * ({@code TickAssetPlayerInstances}), and their clips gathered for the curves.
     */
    public void update(float delta) {
        this.delta = delta;
        seconds += delta;
        frame++;
        samples = 0;
        records = 0;
        curveLayer = 0;
        sync.begin();
        for (int i = 0; i < montages; i++) {
            sync.addMontage(montagePlayers[i], montageWeights[i], montageGroups[i]);
            montagePlayers[i] = null;
        }
        montages = 0;
        for (CgCachedPoseNode.SaveRuntime s : saveArray) s.begin();
        root.update(this, 1f);
        // Each cached pose's source once, at its heaviest use; a save before those it reads, so their requests are in
        for (CgCachedPoseNode.SaveRuntime s : flushOrder) s.flush(this);
        for (int i = 0; i < linked.size(); i++) {
            CgAnimGraphInstance l = linked.get(i);
            if (l.linkedFrame == frame) for (CgCachedPoseNode.SaveRuntime s : l.flushOrder) s.flush(l);
        }
        sync.tick(delta);
        for (int i = 0; i < records; i++) {
            sink.player = recordPlayers[i];
            sink.layer = recordLayers[i];
            sink.scope = recordScopes[i];
            recordPlayers[i].forEachSample(recordWeights[i], sink);
        }
    }

    /** Where a player's clips land after its tick, each with its record's curve layer and scope. */
    private final class Sink implements CgAnimAssetPlayer.SampleSink {
        CgAnimAssetPlayer player;
        int layer, scope;

        @Override
        public void sample(CgAnimSequence clip, float time, float weight) {
            int i = reserve();
            samplePlayers[i] = player;
            sampleClips[i] = clip;
            sampleTimes[i] = time;
            sampleWeights[i] = weight;
            sampleLayers[i] = layer;
            sampleScopes[i] = scope;
        }
    }

    /** Its pose into {@code out}, after {@link #update}; this frame's slot entries are cleared. */
    public void evaluate(CgAnimPose out) {
        root.evaluate(this, out);
        for (CgAnimSlot s : slotArray) s.clear();
        for (int i = 0; i < extraSlots.size(); i++) extraSlots.get(i).clear();
        if (borrowed != 0) throw new IllegalStateException(graph + ": " + borrowed + " scratch poses not given back");
    }

    /** This frame's delta, seconds. */
    public float delta() {
        if (main != this) return main.delta;
        return delta;
    }

    /** Its clock, seconds: the sum of its deltas. What a state's re-entry and an event's time to live count by. */
    public double seconds() {
        if (main != this) return main.seconds;
        return seconds;
    }

    /** How many frames it has updated: a cached pose evaluates once per frame by it. */
    public long frame() {
        if (main != this) return main.frame;
        return frame;
    }

    // ── what nodes call ──────────────────────────────────────────────────────────────────

    /** The curve layer a node's players contribute to: 0 the base, more a layer's own, -1 none. */
    public int curveLayer() {
        if (main != this) return main.curveLayer;
        return curveLayer;
    }

    /** Sets the curve layer for the nodes a layered blend updates under it; answers the one it replaced. */
    public int curveLayer(int layer) {
        if (main != this) return main.curveLayer(layer);
        int was = curveLayer;
        curveLayer = layer;
        return was;
    }

    /**
     * An asset player relevant this frame at {@code weight}, from its update: ticked by its sync group after the walk
     * (Unreal's {@code AddTickRecord}), its clips then added to the curves. A transition role joins its group only once
     * {@code fullWeightOnce}.
     */
    public void record(CgAnimAssetPlayer player, float weight, @Nullable CgSyncGroup group, boolean fullWeightOnce) {
        if (main != this) {
            main.record(player, weight, group, fullWeightOnce);
            return;
        }
        if (records == recordPlayers.length) {
            int n = records * 2;
            recordPlayers = Arrays.copyOf(recordPlayers, n);
            recordWeights = Arrays.copyOf(recordWeights, n);
            recordLayers = Arrays.copyOf(recordLayers, n);
            recordScopes = Arrays.copyOf(recordScopes, n);
        }
        recordPlayers[records] = player;
        recordWeights[records] = weight;
        recordLayers[records] = curveLayer;
        recordScopes[records++] = playerScope;
        sync.add(player, weight, group, fullWeightOnce);
    }

    /**
     * A montage leading {@code group} this frame at {@code weight}, from the game thread before the update: Unreal's
     * {@code UpdateMontageSyncGroup}, its record added to the group as the frame's sync begins. It outranks every
     * player of the graph.
     */
    public void syncMontage(CgAnimAssetPlayer montage, float weight, String group) {
        if (montages == montagePlayers.length) {
            int n = Math.max(2, montages * 2);
            montagePlayers = Arrays.copyOf(montagePlayers, n);
            montageWeights = Arrays.copyOf(montageWeights, n);
            montageGroups = Arrays.copyOf(montageGroups, n);
        }
        montagePlayers[montages] = montage;
        montageWeights[montages] = weight;
        montageGroups[montages++] = group;
    }

    /** This frame's players recorded so far: a state machine finds its states' relevant player among them. */
    public int records() {
        if (main != this) return main.records;
        return records;
    }

    public CgAnimAssetPlayer recordPlayer(int i) {
        if (main != this) return main.recordPlayers[i];
        return recordPlayers[i];
    }

    public float recordWeight(int i) {
        if (main != this) return main.recordWeights[i];
        return recordWeights[i];
    }

    /** The player scope record {@code i} was made in: which state machine's states it belongs to, 0 none. */
    public int recordScope(int i) {
        if (main != this) return main.recordScopes[i];
        return recordScopes[i];
    }

    /** Its sync groups: where they stood after the last tick. */
    public CgAnimSync sync() {
        if (main != this) return main.sync;
        return sync;
    }

    /** A clip sampled at {@code time} this frame (a slot's entry): its curves added at {@code weight}. */
    void sampled(CgAnimSequence clip, float time, float weight) {
        if (main != this) {
            main.sampled(clip, time, weight);
            return;
        }
        int i = reserve();
        sampleScopes[i] = playerScope;
        samplePlayers[i] = null;
        sampleClips[i] = clip;
        sampleTimes[i] = time;
        sampleWeights[i] = weight;
        sampleLayers[i] = curveLayer;
    }

    private int reserve() {
        if (samples == sampleClips.length) {
            int n = samples * 2;
            samplePlayers = Arrays.copyOf(samplePlayers, n);
            sampleClips = Arrays.copyOf(sampleClips, n);
            sampleTimes = Arrays.copyOf(sampleTimes, n);
            sampleWeights = Arrays.copyOf(sampleWeights, n);
            sampleLayers = Arrays.copyOf(sampleLayers, n);
            sampleScopes = Arrays.copyOf(sampleScopes, n);
        }
        return samples++;
    }

    /** This frame's clips sampled, for its curves: count, then each one's clip, time, weight and curve layer. */
    public int samples() {
        return samples;
    }

    public CgAnimSequence sampleClip(int i) {
        return sampleClips[i];
    }

    public float sampleTime(int i) {
        return sampleTimes[i];
    }

    public float sampleWeight(int i) {
        return sampleWeights[i];
    }

    public int sampleLayer(int i) {
        return sampleLayers[i];
    }

    /** Sample {@code i}'s player, or null for a slot's entry. */
    @Nullable
    public CgAnimAssetPlayer samplePlayer(int i) {
        return samplePlayers[i];
    }

    /**
     * Opens a player scope one deeper, for a state machine updating its states: the players ticked until
     * {@link #endPlayerScope} are its states' own (Unreal's {@code PlayerNodeIndices}), not an outer machine's.
     * Answers the scope's depth.
     */
    public int beginPlayerScope() {
        if (main != this) return main.beginPlayerScope();
        return ++playerScope;
    }

    public void endPlayerScope() {
        if (main != this) {
            main.endPlayerScope();
            return;
        }
        playerScope--;
    }

    // ── inertialization ──────────────────────────────────────────────────────────────────

    /** Where a transition's inertialization goes: the nearest inertialization node above it (Unreal's graph message). */
    public interface Inertializer {

        /** Inertializes the pose over {@code duration} seconds, from this frame's evaluation. */
        void requestInertialization(float duration);
    }

    /** While an inertialization node updates its source: requests below go to it. */
    public void pushInertializer(Inertializer inertializer) {
        if (main != this) {
            main.pushInertializer(inertializer);
            return;
        }
        if (inertializing == inertializers.length) inertializers = Arrays.copyOf(inertializers, inertializing * 2);
        inertializers[inertializing++] = inertializer;
    }

    public void popInertializer() {
        if (main != this) {
            main.popInertializer();
            return;
        }
        inertializers[--inertializing] = null;
    }

    /** The nearest inertialization node above the node updating now, or null. */
    @Nullable
    public Inertializer inertializer() {
        if (main != this) return main.inertializer();
        return inertializing == 0 ? null : inertializers[inertializing - 1];
    }

    /**
     * While an inertialization node instantiates its source: a node made below it can request inertialization. A node
     * that needs one checks {@link #inertializerAbove} as it instantiates.
     */
    public void enterInertializer() {
        if (main != this) {
            main.enterInertializer();
            return;
        }
        inertializersAbove++;
    }

    public void exitInertializer() {
        if (main != this) {
            main.exitInertializer();
            return;
        }
        inertializersAbove--;
    }

    /** Whether the node instantiating now has an inertialization node above it. */
    public boolean inertializerAbove() {
        if (main != this) return main.inertializerAbove();
        return inertializersAbove > 0;
    }

    /** A scratch pose for a node's evaluate; give it back before returning. */
    public CgAnimPose borrow() {
        if (borrowed == pooled) {
            if (pooled == pool.length) pool = Arrays.copyOf(pool, pooled * 2);
            pool[pooled++] = new CgAnimPose(skeleton);
        }
        return pool[borrowed++];
    }

    /** Gives back the last pose borrowed. */
    public void giveBack(CgAnimPose pose) {
        if (borrowed == 0 || pool[borrowed - 1] != pose) throw new IllegalStateException("scratch poses are given back last first");
        borrowed--;
    }

    @Override
    public String toString() {
        return "CgAnimGraphInstance(" + graph.id() + ")";
    }
}
