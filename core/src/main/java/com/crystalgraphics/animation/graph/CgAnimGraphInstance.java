package com.crystalgraphics.animation.graph;

import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import javax.annotation.Nullable;

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

    // This frame's ticks and samples: players to advance after the walk, and what each contributes to the curves
    private CgSequencePlayerNode.PlayerRuntime[] tickPlayers = new CgSequencePlayerNode.PlayerRuntime[8];
    private CgAnimSequence[] sampleClips = new CgAnimSequence[8];
    private float[] sampleTimes = new float[8], sampleWeights = new float[8];
    private int[] sampleLayers = new int[8], sampleScopes = new int[8];
    private int samples;

    private CgAnimPose[] pool = new CgAnimPose[4];
    private int pooled, borrowed;

    public CgAnimGraphInstance(CgAnimGraph graph, CgSkeleton skeleton) {
        this.graph = Objects.requireNonNull(graph, "graph");
        this.skeleton = Objects.requireNonNull(skeleton, "skeleton");
        values = new float[graph.variables().size()];
        for (int i = 0; i < values.length; i++) values[i] = graph.variables().get(i).defaultValue();
        proxy = values.clone();
        for (String name : graph.slots()) slots.put(name, new CgAnimSlot(name));
        for (String name : graph.saves().keySet()) save(name);
        root = runtime(graph.output());
        single = graph.singlePlayer() == null ? null : (CgSequencePlayerNode.PlayerRuntime) runtime(graph.singlePlayer());
        // Arrays for the frame's loops, which allocate nothing
        slotArray = slots.values().toArray(new CgAnimSlot[0]);
        saveArray = saves.values().toArray(new CgCachedPoseNode.SaveRuntime[0]);
        flushOrder = new CgCachedPoseNode.SaveRuntime[saveArray.length];
        for (int i = 0; i < flushOrder.length; i++) flushOrder[i] = saves.get(graph.saveOrder().get(i));
        initialize();
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
        return ++curveLayers;
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
    }

    /**
     * This frame's update, {@code delta} seconds on: the walk from the output (weights, relevance), each cached pose's
     * source at its heaviest use (Unreal's {@code PostGraphUpdate}), then every recorded player's tick
     * ({@code TickAssetPlayerInstances}).
     */
    public void update(float delta) {
        this.delta = delta;
        seconds += delta;
        frame++;
        samples = 0;
        curveLayer = 0;
        for (CgCachedPoseNode.SaveRuntime s : saveArray) s.begin();
        root.update(this, 1f);
        // Each cached pose's source once, at its heaviest use; a save before those it reads, so their requests are in
        for (CgCachedPoseNode.SaveRuntime s : flushOrder) s.flush(this);
        for (int i = 0; i < samples; i++) {
            CgSequencePlayerNode.PlayerRuntime p = tickPlayers[i];
            if (p != null) {
                p.tick(delta);
                sampleTimes[i] = p.time();
            }
        }
    }

    /** Its pose into {@code out}, after {@link #update}; this frame's slot entries are cleared. */
    public void evaluate(CgAnimPose out) {
        root.evaluate(this, out);
        for (CgAnimSlot s : slotArray) s.clear();
        if (borrowed != 0) throw new IllegalStateException(graph + ": " + borrowed + " scratch poses not given back");
    }

    /** This frame's delta, seconds. */
    public float delta() {
        return delta;
    }

    /** Its clock, seconds: the sum of its deltas. What a state's re-entry and an event's time to live count by. */
    public double seconds() {
        return seconds;
    }

    /** How many frames it has updated: a cached pose evaluates once per frame by it. */
    public long frame() {
        return frame;
    }

    // ── what nodes call ──────────────────────────────────────────────────────────────────

    /** The curve layer a node's players contribute to: 0 the base, more a layer's own, -1 none. */
    public int curveLayer() {
        return curveLayer;
    }

    /** Sets the curve layer for the nodes a layered blend updates under it; answers the one it replaced. */
    public int curveLayer(int layer) {
        int was = curveLayer;
        curveLayer = layer;
        return was;
    }

    /** A player's tick this frame at {@code weight}: advanced after the walk, its curves added at its new time. */
    void tick(CgSequencePlayerNode.PlayerRuntime player, CgAnimSequence clip, float weight) {
        int i = reserve();
        sampleScopes[i] = playerScope;
        tickPlayers[i] = player;
        sampleClips[i] = clip;
        sampleWeights[i] = weight;
        sampleLayers[i] = curveLayer;
    }

    /** A clip sampled at {@code time} this frame (a slot's entry): its curves added at {@code weight}. */
    void sampled(CgAnimSequence clip, float time, float weight) {
        int i = reserve();
        sampleScopes[i] = playerScope;
        tickPlayers[i] = null;
        sampleClips[i] = clip;
        sampleTimes[i] = time;
        sampleWeights[i] = weight;
        sampleLayers[i] = curveLayer;
    }

    private int reserve() {
        if (samples == sampleClips.length) {
            int n = samples * 2;
            tickPlayers = Arrays.copyOf(tickPlayers, n);
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
    public CgSequencePlayerNode.PlayerRuntime samplePlayer(int i) {
        return tickPlayers[i];
    }

    /** The player scope sample {@code i} was ticked in: which state machine's states it belongs to, 0 none. */
    public int sampleScope(int i) {
        return sampleScopes[i];
    }

    /**
     * Opens a player scope one deeper, for a state machine updating its states: the players ticked until
     * {@link #endPlayerScope} are its states' own (Unreal's {@code PlayerNodeIndices}), not an outer machine's.
     * Answers the scope's depth.
     */
    public int beginPlayerScope() {
        return ++playerScope;
    }

    public void endPlayerScope() {
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
        if (inertializing == inertializers.length) inertializers = Arrays.copyOf(inertializers, inertializing * 2);
        inertializers[inertializing++] = inertializer;
    }

    public void popInertializer() {
        inertializers[--inertializing] = null;
    }

    /** The nearest inertialization node above the node updating now, or null. */
    @Nullable
    public Inertializer inertializer() {
        return inertializing == 0 ? null : inertializers[inertializing - 1];
    }

    /**
     * While an inertialization node instantiates its source: a node made below it can request inertialization. A node
     * that needs one checks {@link #inertializerAbove} as it instantiates.
     */
    public void enterInertializer() {
        inertializersAbove++;
    }

    public void exitInertializer() {
        inertializersAbove--;
    }

    /** Whether the node instantiating now has an inertialization node above it. */
    public boolean inertializerAbove() {
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
