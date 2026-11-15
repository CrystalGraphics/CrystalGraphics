package com.crystalgraphics.animation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import org.joml.Matrix4d;
import org.joml.Matrix4f;

import com.crystalgraphics.animation.graph.CgAnimGraph;
import com.crystalgraphics.animation.graph.CgAnimGround;
import com.crystalgraphics.animation.graph.CgAnimGraphInstance;
import com.crystalgraphics.animation.graph.CgAnimSlot;
import com.crystalgraphics.animation.graph.CgAnimVariable;
import com.crystalgraphics.animation.graph.CgSequencePlayerNode;
import com.crystalgraphics.animation.montage.CgMontages;
import com.crystalgraphics.animation.state.CgAnimStateMachineNode;
import com.crystalgraphics.api.animation.CgAnimPose;
import com.crystalgraphics.api.animation.CgAnimSequence;
import com.crystalgraphics.api.animation.CgSocket;
import com.crystalgraphics.api.mesh.CgSkeletalMesh;

/**
 * What a skeletal mesh plays: an animation graph ({@link CgAnimGraph}) run each frame from variables the game sets, or
 * one clip. Unreal's {@code UAnimInstance} with its proxy. Its component ({@code CgSkeletalMeshComponent}) makes one and
 * hands it out; where it stands and what draws it are the component's.
 *
 * <pre>{@code
 * CgAnimInstance anim = goku.anim();                    // goku: scene.skeletal(mesh)...spawn()
 * anim.play(mesh.sequence("idle"));                     // one clip, looping: Unreal's PlayAnimation
 * }</pre>
 *
 * <p>A graph, fed from the game each frame, with a montage's clip on its slot:</p>
 *
 * <pre>{@code
 * anim.graph(HERO_GRAPH)
 *     .onUpdate((a, dt) -> a.set(SPEED, speed).set(GROUNDED, onGround));   // the game thread, before the graph runs
 * anim.slot("DefaultSlot").play(roar, 0.4f, 1f);        // this frame: roar at 0.4 s over what the graph plays
 * }</pre>
 *
 * <p>Headless, on a dedicated server, with no component: evaluated each game tick for its bones and sockets.</p>
 *
 * <pre>{@code
 * CgAnimInstance body = CgAnimation.server().add(new CgAnimInstance(mesh).graph(HERO_GRAPH));
 * body.socket(body.socket("hand_r"), handInMesh);       // this tick's pose, in the mesh's space
 * }</pre>
 *
 * <ul>
 *   <li>Changed on the game thread: variables, slot entries, graph, clip. {@code CgAnimation} then runs Unreal's frame:
 *       {@link #preUpdate} on that thread, {@link #evaluate} on a worker, the material curves back on that thread.</li>
 *   <li>A graph keeps state between frames (clip times, blend weights), as Unreal's does. A sequence is still sought
 *       exactly: it sets slot entries at the times it is sought to.</li>
 *   <li>Allocates nothing per frame once warm.</li>
 * </ul>
 */
public final class CgAnimInstance {

    /** {@link #socket(String)}'s answer for a name it has neither as a socket nor a bone: the mesh's origin. */
    public static final int NO_SOCKET = -1;
    private static final int SOCKET = 1 << 30;

    private final CgSkeletalMesh mesh;
    private boolean posed;
    private CgAnimGraphInstance graph;
    private final CgMontages montages;
    private final CgAnimPose pose;
    private final Matrix4f[] skinning;
    private final Matrix4f socketScratch = new Matrix4f();
    @Nullable
    private Consumer<Matrix4d> placedBy;
    private final Matrix4d placement = new Matrix4d();
    private CgAnimGround ground = CgAnimGround.NONE;
    private final List<Update> updates = new ArrayList<>();
    private float rate = 1f, delta;
    private boolean playing = true;
    private final CgMaterialCurves curves = new CgMaterialCurves();
    @Nullable
    private CgAnimCurveTarget curveTarget;
    private long evaluations;

    /** An instance of {@code mesh} playing nothing: its rest pose, until a clip or a graph. */
    public CgAnimInstance(CgSkeletalMesh mesh) {
        this.mesh = Objects.requireNonNull(mesh, "mesh");
        this.pose = new CgAnimPose(mesh.skeleton());
        this.montages = new CgMontages(mesh.skeleton());
        this.skinning = new Matrix4f[mesh.skeleton().boneCount()];
        for (int b = 0; b < skinning.length; b++) skinning[b] = new Matrix4f();
        this.graph = new CgAnimGraphInstance(CgAnimGraph.single(mesh.skeleton()), mesh.skeleton());
        pose.resolve();
        pose.skinning(mesh, skinning);
    }

    public CgSkeletalMesh mesh() {
        return mesh;
    }

    // ── what plays ───────────────────────────────────────────────────────────────────────

    /**
     * Runs {@code graph} from its first frame: Unreal's {@code SetAnimInstanceClass}. Its variables start at their
     * defaults; {@link #onUpdate} callbacks stay.
     *
     * @throws IllegalArgumentException where the graph names a bone this mesh's skeleton lacks
     */
    public CgAnimInstance graph(CgAnimGraph graph) {
        this.graph = new CgAnimGraphInstance(Objects.requireNonNull(graph, "graph"), mesh.skeleton());
        return this;
    }

    /** The graph it runs, with its state: variables, slots, nodes. */
    public CgAnimGraphInstance graph() {
        return graph;
    }

    /** Plays {@code clip} alone, looping, from its start: Unreal's {@code PlayAnimation}. A graph it ran is dropped. */
    public CgAnimInstance play(CgAnimSequence clip) {
        return play(clip, true);
    }

    /** As {@link #play(CgAnimSequence)}, once or looping: once holds its last frame. */
    public CgAnimInstance play(CgAnimSequence clip, boolean loop) {
        single().play(Objects.requireNonNull(clip, "clip"), loop);
        return this;
    }

    /** Moves the single clip to {@code seconds}: the next pose is the clip's there. */
    public CgAnimInstance position(float seconds) {
        single().position(seconds);
        return this;
    }

    /** The single clip's time, seconds; 0 while it runs a graph. */
    public float position() {
        return graph.single() == null ? 0f : graph.single().time();
    }

    private CgSequencePlayerNode.PlayerRuntime single() {
        if (graph.single() == null) graph = new CgAnimGraphInstance(CgAnimGraph.single(mesh.skeleton()), mesh.skeleton());
        return graph.single();
    }

    /**
     * Its slot named {@code name}, where a montage or a sequence's animation section plays this frame; null where its
     * graph has none, and a montage on a slot the graph lacks plays nowhere, as in Unreal. One clip plays on
     * {@link CgAnimGraph#DEFAULT_SLOT}.
     */
    @Nullable
    public CgAnimSlot slot(String name) {
        return graph.slot(name);
    }

    /**
     * Plays each layer {@code layers} declares through it, wherever its graph plays that layer: Unreal's
     * {@code LinkAnimClassLayers}. A graph of sword arms over a hero's own; one group's layers share one run of it.
     *
     * <pre>{@code
     * anim.linkLayers(SWORD_ARMS);       // blended over each layer's blend times, under the graph's inertialization
     * anim.unlinkLayers(SWORD_ARMS);     // the graph's own layers again
     * }</pre>
     *
     * <ul>
     *   <li>On the game thread, between frames, as every change to what plays.</li>
     *   <li>Its graph's own graph is refused: it would play itself.</li>
     * </ul>
     */
    public CgAnimInstance linkLayers(CgAnimGraph layers) {
        graph.linkLayers(layers);
        return this;
    }

    /** Its graph's own layers again where {@code layers} played them: Unreal's {@code UnlinkAnimClassLayers}. */
    public CgAnimInstance unlinkLayers(CgAnimGraph layers) {
        graph.unlinkLayers(layers);
        return this;
    }

    /**
     * Runs {@code linked} in its graph's linked graph node tagged {@code tag}, null for none: Unreal's
     * {@code LinkAnimGraphByTag}.
     *
     * @throws IllegalArgumentException where no linked graph node has the tag
     */
    public CgAnimInstance linkGraph(String tag, @Nullable CgAnimGraph linked) {
        graph.linkGraph(tag, linked);
        return this;
    }

    /**
     * Its montages: those playing on its slots and blending out, its root motion, and their events. A sequence's
     * {@code animation} track plays each of its plays as one.
     *
     * <pre>{@code
     * anim.montages().rootMotionMode(CgRootMotionMode.FROM_MONTAGES_ONLY);
     * if (anim.montages().consumeRootMotion(1f, step)) mover.move(step);
     * }</pre>
     */
    public CgMontages montages() {
        return montages;
    }

    /**
     * Sets a variable of its graph, on the game thread; boxes nothing, so it is set every frame freely.
     *
     * @throws IllegalArgumentException where the graph does not declare it
     */
    public CgAnimInstance set(CgAnimVariable<Float> variable, float value) {
        graph.set(variable, value);
        return this;
    }

    /** As {@link #set(CgAnimVariable, float)}, a bool. */
    public CgAnimInstance set(CgAnimVariable<Boolean> variable, boolean value) {
        graph.set(variable, value);
        return this;
    }

    /** As {@link #set(CgAnimVariable, float)}, an int. */
    public CgAnimInstance set(CgAnimVariable<Integer> variable, int value) {
        graph.set(variable, value);
        return this;
    }

    /** The game thread's part of a frame: Unreal's {@code NativeUpdateAnimation}. */
    @FunctionalInterface
    public interface Update {

        /** Sets variables and slot entries from the game, {@code seconds} into the frame (scaled by the rate, 0 paused). */
        void update(CgAnimInstance anim, float seconds);
    }

    /**
     * Runs {@code update} on the game thread every frame before the graph: Unreal's {@code NativeUpdateAnimation},
     * where the game's state becomes variables.
     *
     * <pre>{@code
     * anim.onUpdate((a, dt) -> a.set(SPEED, entity.speed()).set(GROUNDED, entity.onGround()));
     * }</pre>
     */
    public CgAnimInstance onUpdate(Update update) {
        updates.add(Objects.requireNonNull(update, "update"));
        return this;
    }

    /**
     * Queues transition event {@code name} on every state machine of its graph for {@code timeToLive} seconds: Unreal's
     * {@code RequestTransitionEvent}. A transition whose rule is {@code event: name} takes it, and the first to take it
     * consumes it.
     *
     * <pre>{@code
     * anim.requestTransitionEvent("jump", 0.25);   // a jump pressed this frame, honoured within a quarter second
     * }</pre>
     */
    public CgAnimInstance requestTransitionEvent(String name, double timeToLive) {
        return requestTransitionEvent(name, timeToLive, false, CgAnimStateMachineNode.Replace.APPEND);
    }

    /**
     * As {@link #requestTransitionEvent(String, double)}: {@code unique}, each transition may take it once (Unreal's
     * Unique queue mode); {@code replace}, how it meets one of the name already queued.
     */
    public CgAnimInstance requestTransitionEvent(String name, double timeToLive, boolean unique, CgAnimStateMachineNode.Replace replace) {
        CgAnimStateMachineNode.requestTransitionEvent(graph, name, timeToLive, unique, replace);
        return this;
    }

    /** Drops every queued transition event named {@code name}. */
    public CgAnimInstance clearTransitionEvents(String name) {
        CgAnimStateMachineNode.clearTransitionEvents(graph, name);
        return this;
    }

    /** Graph seconds a second of play; 1 by default, 0 freezes it as {@code playing(false)} does. */
    public CgAnimInstance rate(float rate) {
        this.rate = rate;
        return this;
    }

    public float rate() {
        return rate;
    }

    /** Whether it moves each frame. A paused instance is still posed. */
    public CgAnimInstance playing(boolean playing) {
        this.playing = playing;
        return this;
    }

    public boolean playing() {
        return playing;
    }

    // ── the frame ────────────────────────────────────────────────────────────────────────

    /**
     * The game thread's half of a frame {@code seconds} long: {@link #onUpdate} callbacks, then the variables handed to
     * the worker. A paused instance moves 0 s.
     */
    public CgAnimInstance preUpdate(float seconds) {
        delta = playing ? seconds * rate : 0f;
        // Montages before the native update, so it sees where they are (AnimInstance.cpp:766-777)
        montages.preUpdate(delta, graph);
        for (int i = 0; i < updates.size(); i++) updates.get(i).update(this, delta);
        if (placedBy != null) {
            placedBy.accept(placement);
            graph.placement(placement);
        }
        graph.ground(ground);
        graph.preUpdate();
        return this;
    }

    /**
     * The worker's half: the graph updated and evaluated, the pose resolved and skinned, the frame's curves gathered.
     * After {@link #preUpdate}.
     */
    public CgAnimInstance evaluate() {
        graph.update(delta);
        gatherCurves();
        graph.evaluate(pose);
        pose.resolve();
        pose.skinning(mesh, skinning);
        evaluations++;
        return this;
    }

    /** {@link #preUpdate} then {@link #evaluate} on one thread: a tool's or a test's frame. */
    public CgAnimInstance tick(float seconds) {
        return preUpdate(seconds).evaluate();
    }

    /** The base's clips, then each curve layer's over them ({@code CurveBlendOption MaxWeight}). */
    private void gatherCurves() {
        curves.begin();
        int n = graph.samples(), layers = 0;
        for (int i = 0; i < n; i++) {
            int layer = graph.sampleLayer(i);
            if (layer == 0) curves.add(graph.sampleClip(i), graph.sampleTime(i), graph.sampleWeight(i));
            layers = Math.max(layers, layer);
        }
        for (int layer = 1; layer <= layers; layer++) {
            boolean begun = false;
            for (int i = 0; i < n; i++) {
                if (graph.sampleLayer(i) != layer) continue;
                if (!begun) {
                    curves.beginLayer();
                    begun = true;
                }
                curves.addLayer(graph.sampleClip(i), graph.sampleTime(i), graph.sampleWeight(i));
            }
        }
    }

    /**
     * Bone {@code name}'s index, ignoring case.
     *
     * @throws IllegalArgumentException where the skeleton has none
     */
    public int bone(String name) {
        int b = mesh.skeleton().indexOfIgnoringCase(name);
        if (b < 0) throw new IllegalArgumentException(this + ": the skeleton has no bone " + name);
        return b;
    }

    /** The pose {@link #evaluate()} last made: local and, resolved, in the mesh's space. */
    public CgAnimPose pose() {
        return pose;
    }

    /** Its skinning matrices as of the last {@link #evaluate()}, one a bone: the pose times the inverse bind. */
    public Matrix4f[] skinning() {
        return skinning;
    }

    /** How many times it has been evaluated: moves each time its pose and skinning do. */
    public long evaluations() {
        return evaluations;
    }

    /**
     * Where its component stands in the world, asked on the game thread each frame before the graph runs (Unreal's
     * proxy copies the component's transform there): what world-space targets and foot traces read. The skeletal mesh
     * component that makes it sets this; an instance with none stands at the origin.
     *
     * <pre>{@code
     * new CgAnimInstance(mesh).placedBy(out -> out.translation(x, y, z));
     * }</pre>
     *
     * <ul>
     *   <li>Called on the game thread every frame: it must not allocate.</li>
     * </ul>
     */
    public CgAnimInstance placedBy(@Nullable Consumer<Matrix4d> placedBy) {
        this.placedBy = placedBy;
        return this;
    }

    /**
     * The ground its graph's nodes ask on the game thread (a foot placement's sweeps, whether it stands on a floor):
     * Unreal's world and character movement through the proxy. {@link CgAnimGround#NONE} until set.
     *
     * <pre>{@code
     * anim.ground(CgCollisionGround.of(() -> CgCollisionWorld.client(partialTick), component::worldTransform));
     * }</pre>
     */
    public CgAnimInstance ground(CgAnimGround ground) {
        this.ground = Objects.requireNonNull(ground, "ground");
        return this;
    }

    /**
     * Where its clips' material curves are set each frame: its component's materials (a {@code CgSkeletalMeshComponent}
     * sets itself). Null, as on a server, evaluates them and sets nothing.
     */
    public CgAnimInstance curveTarget(@Nullable CgAnimCurveTarget target) {
        curveTarget = target;
        return this;
    }

    /** Applies this frame's material curves: after the evaluation, on the game thread ({@link CgAnimation}). */
    void applyCurves() {
        if (curveTarget != null) curves.apply(curveTarget);
    }

    // ── sockets, in the mesh's space ─────────────────────────────────────────────────────

    /**
     * The socket named {@code name}, as Unreal finds one (USkinnedMeshComponent::GetSocketTransform,
     * SkinnedMeshComponent.cpp:3525-3587): its mesh's socket of that name, else the bone of that name, ignoring case;
     * {@link #NO_SOCKET} for neither, the mesh's origin. Look it up once and keep it.
     *
     * <pre>{@code
     * int hand = anim.socket("hand_r");
     * anim.socket(hand, handInMesh);          // this frame's, once POSE has run
     * }</pre>
     */
    public int socket(String name) {
        List<CgSocket> sockets = mesh.sockets();
        for (int i = 0; i < sockets.size(); i++) if (sockets.get(i).name().equalsIgnoreCase(name)) return SOCKET | i;
        int bone = mesh.skeleton().indexOfIgnoringCase(name);
        return bone >= 0 ? bone : NO_SOCKET;
    }

    /**
     * Socket {@code socket} in the mesh's space into {@code out}, from the pose {@link #evaluate()} last made: its bone
     * times the socket's own transform; identity for {@link #NO_SOCKET}. Its component multiplies its placement in.
     */
    public Matrix4f socket(int socket, Matrix4f out) {
        if (socket == NO_SOCKET) return out.identity();
        if ((socket & SOCKET) != 0) {
            CgSocket s = mesh.sockets().get(socket & ~SOCKET);
            return out.set(pose.component(mesh.skeleton().indexOfIgnoringCase(s.bone()))).mul(s.relative(socketScratch));
        }
        return out.set(pose.component(socket));
    }

    /** Whether {@code CgAnimation} poses it: false once removed. */
    public boolean posed() {
        return posed;
    }

    void posed(boolean posed) {
        this.posed = posed;
    }

    @Override
    public String toString() {
        return "CgAnimInstance(" + mesh + ", " + graph.graph().id() + ")";
    }
}
