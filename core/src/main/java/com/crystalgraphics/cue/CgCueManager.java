package com.crystalgraphics.cue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.crystalgraphics.asset.kind.CgGameplayKinds;
import com.crystalgraphics.asset.kind.CgSequenceKinds;
import com.crystalgraphics.asset.load.CgAssetRef;
import com.crystalgraphics.asset.load.CgAssetState;
import com.crystalgraphics.asset.load.CgLoadPriority;
import com.crystalgraphics.asset.load.CgVfxAssets;
import com.crystalgraphics.asset.registry.CgAssetEntry;
import com.crystalgraphics.asset.registry.CgAssetRegistry;
import com.crystalgraphics.cue.CgCueDefinition.Event;
import com.crystalgraphics.cue.CgCueDefinition.SpawnCondition;
import com.crystalgraphics.frame.CgTickFunction;
import com.crystalgraphics.frame.CgTickGroup;
import com.crystalgraphics.frame.CgWorldSubsystem;
import com.crystalgraphics.net.object.CgNetObject;
import com.crystalgraphics.net.object.CgPredictionKey;
import com.crystalgraphics.sequence.CgSequencePlayer;
import com.crystalgraphics.sequence.net.CgSequenceNet;
import com.crystalgraphics.sequence.definition.CgSequenceDefinition;
import com.crystalgraphics.tag.CgGameplayTag;
import com.crystalgraphics.tag.CgTagRegistry;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;
import com.crystalgraphics.world.CgAttachment;

/**
 * Plays cues by tag: a world's router from gameplay's "this happened" to the effects that show it. Mirrors GAS's
 * {@code UGameplayCueManager} and {@code UGameplayCueSet}: a tag with no cue of its own plays its nearest parent's,
 * what gameplay sends is queued and played in {@code PLAYS} ({@code FlushPendingCues}), a burst has no instance, and
 * a looping cue's effects are pooled per cue.
 *
 * <pre>{@code
 * CgCueManager cues = world.add(new CgCueManager(CgVfxAssets.instance()));
 * cues.effects(new CgVfxCueEffects(vfx, cues));                    // a client's; a server keeps none
 * cues.localControl(a -> a.entity() == localPlayerId);             // for local_only and not_local conditions
 * cues.viewer(out -> player.position(out));                        // for in-world lenses: the pawn, else the camera
 *
 * cues.execute(MYMOD_CUE_HIT, new CgCueParameters().at(x, y, z));  // a burst: Unreal's Execute
 * CgCuePlaying aura = cues.add(MYMOD_CUE_AURA, params);            // a looping cue: Add
 * aura.remove();                                                   // Remove
 * }</pre>
 *
 * <p>Cues are found by the {@code tag} their files declare, which the asset registry gathers without loading them.
 * A cue built in Java is routed by hand:</p>
 *
 * <pre>{@code
 * cues.route(tags.require("mymod:cue.hit"), CgVfxAssets.ref(CgGameplayKinds.CUE, "mymod:hit"));
 * }</pre>
 *
 * <ul>
 *   <li>The frame thread owns it: call from the frame thread, or from a gameplay tick on it.</li>
 *   <li>A cue not yet {@code READY} waits in the queue, and its request is the load signal. Once ready, a looping
 *       cue starts at its age; a burst plays only within {@link #ONE_SHOT_WINDOW} of its arrival, else it is dropped
 *       and counted ({@code cue.late-dropped}).</li>
 *   <li>A cue that does not handle the call (a burst sent {@code add}) passes it to its parent tag's cue, as Unreal's
 *       notifies do; a tag no cue answers is dropped and counted ({@code cue.unrouted}).</li>
 *   <li>On a server every play is sent to the players who see it ({@link CgCueNet}), and an {@code authoritative}
 *       cue's {@code writes} with it; a client plays what it is sent through its own manager, a late joiner at the
 *       play's age. Gameplay binds a non-authoritative cue's {@code writes} on each client, from
 *       {@link #onNetworked}.</li>
 *   <li>A looping cue's {@code sequence} starts with it, through {@link #sequences}, sought to its age.</li>
 *   <li>Once warm (its pools grown, {@link #prewarm}), it allocates nothing per call or per frame.</li>
 * </ul>
 */
public final class CgCueManager extends CgWorldSubsystem {

    /** How late a burst may still play, in seconds: the sequencer's one-shot window. */
    public static final double ONE_SHOT_WINDOW = CgSequencePlayer.ONE_SHOT_WINDOW;
    public static final String UPDATE_ZONE = "cue.update";

    private static final Logger LOGGER = LogManager.getLogger("CgCue");
    private static final int LATE_DROPPED = CgTrace.name("cue.late-dropped");
    private static final int UNROUTED = CgTrace.name("cue.unrouted");

    enum Op { EXECUTE, ADD, EVENT, RECUR, REMOVE, KILL, FIELD }

    /**
     * Starts a looping cue's {@code sequence}: the host binds its roles from the parameters. Null plays none.
     *
     * <pre>{@code
     * cues.sequences((cue, sequence, at) -> sequencer.play(sequence, Map.of("caster", roleOf(at.instigator()))));
     * }</pre>
     */
    @FunctionalInterface
    public interface SequenceStarter {
        @Nullable
        CgSequencePlayer start(CgCueDefinition cue, CgSequenceDefinition sequence, CgCueParameters at);
    }

    /** A tag's own cue; a cue a server sent by id has no tag. */
    private record Route(@Nullable CgGameplayTag tag, CgAssetRef<CgCueDefinition> ref) {
    }

    /** One queued call: copied in, so the caller's parameters are free at once. */
    private static final class Command {
        Op op;
        @Nullable
        CgGameplayTag tag;
        @Nullable
        CgCuePlaying playing;
        @Nullable
        String event;
        @Nullable
        Object value;
        /** The object a server sent it as, on a client. */
        @Nullable
        CgNetObject net;
        final CgCueParameters params = new CgCueParameters();
        double arrival;
        @Nullable
        Route route;
        boolean routed, requested, sequenceRequested;

        void clear() {
            op = null;
            tag = null;
            playing = null;
            event = null;
            value = null;
            net = null;
            params.reset();
            route = null;
            routed = requested = sequenceRequested = false;
        }
    }

    private final CgVfxAssets assets;
    private final Map<CgGameplayTag, Route> fromFiles = new HashMap<>(), byHand = new HashMap<>();
    /** Each tag asked for and the route that answers it, its own or its nearest ancestor's; null for none. */
    private final Map<CgGameplayTag, Route> resolved = new HashMap<>();
    /** Cues a server sent, by id. */
    private final Map<String, Route> byId = new HashMap<>();
    private final List<Consumer<CgCuePlaying>> networked = new ArrayList<>();
    private final Map<CgCueDefinition, ArrayDeque<CgCueEffects.Lasting>> pools = new HashMap<>();
    private final ArrayDeque<CgCuePlaying> handles = new ArrayDeque<>();
    private final ArrayDeque<Command> spare = new ArrayDeque<>();
    private final List<CgCuePlaying> playing = new ArrayList<>();
    private final float[] scratch = new float[4];
    private Command[] queue = new Command[64];
    private int queued;
    private CgCueEffects effects = CgCueEffects.NONE;
    private Predicate<CgAttachment> localControl = a -> false;
    private Viewer viewer = out -> false;
    private IntFunction<CgAttachment> entities = id -> null;
    @Nullable
    private SequenceStarter sequences;
    private double now;
    private long lateDropped, unrouted;
    private volatile boolean routesDirty = true;
    @Nullable
    private CgTickFunction tick;
    @Nullable
    private Runnable unlisten;

    /** A router over {@code assets}' registry: every {@code cue} asset declaring a tag answers it. */
    public CgCueManager(CgVfxAssets assets) {
        this.assets = assets;
    }

    @Override
    protected void initialize() {
        // On a server, gameplay's calls of the tick played in AUTHORITY, before NET_OUT sends; on a client, in PLAYS.
        tick = world().server() ? world().schedule().add(CgTickGroup.AUTHORITY, "crystalgraphics:cues", this::update).onServer(true)
                : world().schedule().add(CgTickGroup.PLAYS, "crystalgraphics:cues", this::update);
        unlisten = assets.registry().onEvent(e -> {
            if (e.entry() == null || e.entry().kind() == CgGameplayKinds.CUE) routesDirty = true;
        });
    }

    @Override
    protected void deinitialize() {
        if (tick != null) tick.close();
        if (unlisten != null) unlisten.run();
    }

    /** What plays the effects here; {@link CgCueEffects#NONE}, the default, plays none. */
    public CgCueManager effects(CgCueEffects effects) {
        this.effects = effects;
        return this;
    }

    /** Whether an attachment is controlled on this machine: what {@code local_only} and {@code not_local} test. */
    public CgCueManager localControl(Predicate<CgAttachment> localControl) {
        this.localControl = localControl;
        return this;
    }

    /** Where this machine's player stands, for the lenses that play in the world. */
    @FunctionalInterface
    public interface Viewer {
        /** Writes the absolute position into {@code out}; false where there is no one to stand anywhere. */
        boolean position(double[] out);
    }

    /**
     * Where this machine's viewer stands: its pawn, or its camera where the host has none. An in-world lens plays
     * only for a viewer within its reach as it starts; without a viewer, none plays.
     */
    public CgCueManager viewer(Viewer viewer) {
        this.viewer = viewer;
        return this;
    }

    /** This machine's viewer's position into {@code out}, by {@link #viewer(Viewer)}; false where there is none. */
    public boolean viewerAt(double[] out) {
        return viewer.position(out);
    }

    /** What an entity's network id is here, for the instigator and target a server sends; -1 is never asked. */
    public CgCueManager entities(IntFunction<CgAttachment> entities) {
        this.entities = entities;
        return this;
    }

    /** What starts a looping cue's {@code sequence}; none, the default, plays it nowhere. */
    public CgCueManager sequences(@Nullable SequenceStarter sequences) {
        this.sequences = sequences;
        return this;
    }

    /**
     * Hears each looping play a server sent as it starts here: where gameplay binds a non-authoritative cue's
     * {@code writes}, from its own copy of the state.
     */
    public CgCueManager onNetworked(Consumer<CgCuePlaying> listener) {
        networked.add(listener);
        return this;
    }

    /** Routes {@code tag} to {@code cue}, over any file declaring it: a cue built in Java. */
    public CgCueManager route(CgGameplayTag tag, CgAssetRef<CgCueDefinition> cue) {
        byHand.put(tag, new Route(tag, cue));
        resolved.clear();
        return this;
    }

    /** Plays {@code tag}'s burst cue once: GAS's {@code Execute}. */
    public void execute(CgGameplayTag tag, CgCueParameters at) {
        queue(Op.EXECUTE, tag, null, null, at);
    }

    /** Starts {@code tag}'s looping cue: GAS's {@code Add}. The handle is live at once; the cue starts when ready. */
    public CgCuePlaying add(CgGameplayTag tag, CgCueParameters at) {
        CgCuePlaying p = handle();
        p.state = CgCuePlaying.State.PENDING;
        p.params.set(at);
        queue(Op.ADD, tag, p, null, at);
        return p;
    }

    /**
     * Starts {@code tag}'s looping cue on this client now, for its own input, and asks the server for it under
     * {@code key}: GAS's predicted {@code Add}. Accepted, the server's play takes this one over and it is never sent
     * here again; refused, or unanswered within the cue type's timeout, it is {@link CgCuePlaying#discarded()} with
     * everything else under the key.
     *
     * <pre>{@code
     * CgPredictionKey key = CgPredictionKey.open();
     * CgCuePlaying beam = cues.predict(MYMOD_CUE_BEAM, new CgCueParameters().anchor(CgAnchor.onEntity(me)), key);
     * montage.jumpToSection("fire", key);
     * }</pre>
     *
     * <ul>
     *   <li>{@code at} names its anchor; without one nothing is asked, and the key waits for another object.</li>
     *   <li>The server says which predictions it takes: {@code CgCueNet.TYPE.acceptPredictionsFrom(...)}.</li>
     * </ul>
     */
    public CgCuePlaying predict(CgGameplayTag tag, CgCueParameters at, CgPredictionKey key) {
        CgCuePlaying p = add(tag, at);
        p.key = key;
        key.onRefused(() -> {
            if (p.key == key && p.active() && !p.discarded) p.discarded();
        });
        return p;
    }

    /** Grows {@code cue}'s pool to {@code count} looping plays, so the first that many allocate nothing. */
    public void prewarm(CgCueDefinition cue, int count) {
        ArrayDeque<CgCueEffects.Lasting> pool = pools.computeIfAbsent(cue, c -> new ArrayDeque<>());
        while (pool.size() < count) pool.push(effects.lasting(cue));
        while (handles.size() + playing.size() < count) handles.push(new CgCuePlaying(this));
        while (spare.size() < count) spare.push(new Command());
    }

    /** Whether {@code a} is controlled on this machine, by {@link #localControl}; false for null. */
    public boolean locallyControlled(@Nullable CgAttachment a) {
        return a != null && localControl.test(a);
    }

    /**
     * Whether an entry under {@code condition} plays for this viewer, in {@code ShouldSpawn}'s order: local control,
     * then chance, then surfaces. The chance roll is the play's seed with the entry's index, the same on every viewer.
     */
    public boolean spawns(SpawnCondition condition, CgCueParameters at, int entry) {
        if (condition.policy() != SpawnCondition.Policy.ALWAYS) {
            CgAttachment source = condition.source() == SpawnCondition.Source.INSTIGATOR ? at.instigator() : at.target();
            boolean local = source != null && localControl.test(source);
            if (condition.policy() == SpawnCondition.Policy.LOCAL_ONLY ? !local : local) return false;
        }
        if (condition.chance() < 1f && condition.chance() < roll(at.seed(), entry)) return false;
        String surface = at.surface();
        if (!condition.allowed().isEmpty() && (surface == null || !condition.allowed().contains(surface))) return false;
        return surface == null || !condition.rejected().contains(surface);
    }

    /** Bursts dropped for arriving later than {@link #ONE_SHOT_WINDOW} after their cue was ready. */
    public long lateDropped() {
        return lateDropped;
    }

    /** Calls no cue answered. */
    public long unrouted() {
        return unrouted;
    }

    /** Looping plays still alive, waiting or lingering. */
    public int playing() {
        return playing.size();
    }

    /** The frame's work, in {@code PLAYS}: the queue played in order, then each looping play's {@code writes}. */
    public void update(float dt) {
        now += dt;
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.WORLD, UPDATE_ZONE)) {
            if (routesDirty) rebuildRoutes();
            int kept = 0;
            for (int i = 0; i < queued; i++) {
                Command c = queue[i];
                if (run(c)) {
                    c.clear();
                    spare.push(c);
                } else {
                    queue[kept++] = c;
                }
            }
            Arrays.fill(queue, kept, queued, null);
            queued = kept;
            for (int i = playing.size() - 1; i >= 0; i--) {
                CgCuePlaying p = playing.get(i);
                if (p.lasting != null && (p.state == CgCuePlaying.State.PLAYING || p.state == CgCuePlaying.State.REMOVING)) {
                    p.lasting.update(dt);
                }
                if (p.state == CgCuePlaying.State.PLAYING) p.pull(now, scratch);
                else if (p.state == CgCuePlaying.State.REMOVING && p.lasting != null && p.lasting.finished()) end(i, p);
                else if (p.state == CgCuePlaying.State.KILLED) end(i, p);
            }
        }
    }

    Command queue(Op op, @Nullable CgGameplayTag tag, @Nullable CgCuePlaying p, @Nullable String event, CgCueParameters at) {
        return enqueue(op, tag, p, event, at);
    }

    void queueField(CgCuePlaying p, String name, Object value) {
        enqueue(Op.FIELD, null, p, name, p.params).value = value;
    }

    /** A play a server sent: by its cue's id, {@code age} seconds old. Its view's; null for a burst. */
    @Nullable
    CgCuePlaying received(CgNetObject obj, CgCueParameters at, float age) {
        Route r = byId.computeIfAbsent(obj.get(CgCueNet.CUE), id -> new Route(null, assets.declare(CgGameplayKinds.CUE, id)));
        CgCuePlaying p = null;
        if (!obj.isFired()) {
            p = handle();
            p.state = CgCuePlaying.State.PENDING;
            p.params.set(at);
        }
        Command c = enqueue(obj.isFired() ? Op.EXECUTE : Op.ADD, null, p, null, at);
        c.route = r;
        c.routed = true;
        c.net = obj;
        c.arrival = now - age;
        return p;
    }

    /** The sequence the play of the cue object {@code id} runs as its timeline here; null until it starts. */
    @Nullable
    CgSequencePlayer sequenceOfCue(int id) {
        for (int i = 0; i < playing.size(); i++) {
            CgCuePlaying p = playing.get(i);
            if (p.object != null && p.object.id() == id) return p.sequence;
        }
        return null;
    }

    @Nullable
    CgAttachment entity(int id) {
        return id < 0 ? null : entities.apply(id);
    }

    private CgCuePlaying handle() {
        return handles.isEmpty() ? new CgCuePlaying(this) : handles.pop();
    }

    private Command enqueue(Op op, @Nullable CgGameplayTag tag, @Nullable CgCuePlaying p, @Nullable String event, CgCueParameters at) {
        Command c = spare.isEmpty() ? new Command() : spare.pop();
        c.op = op;
        c.tag = tag;
        c.playing = p;
        c.event = event;
        c.params.set(at);
        c.arrival = now;
        if (queued == queue.length) queue = Arrays.copyOf(queue, queued * 2);
        queue[queued++] = c;
        return c;
    }

    /** Plays {@code c}; false while it must wait. */
    private boolean run(Command c) {
        return switch (c.op) {
            case EXECUTE, ADD -> start(c);
            case EVENT, RECUR -> burstOn(c);
            case REMOVE -> {
                CgCuePlaying p = c.playing;
                if (p.state == CgCuePlaying.State.PENDING) yield false;
                if (p.state == CgCuePlaying.State.PLAYING) {
                    p.lasting.remove(c.params);
                    p.state = CgCuePlaying.State.REMOVING;
                    p.stopSequence();
                    if (p.object != null && world().server()) p.object.despawn();
                }
                yield true;
            }
            case KILL -> {
                CgCuePlaying p = c.playing;
                if (p.state == CgCuePlaying.State.PLAYING || p.state == CgCuePlaying.State.REMOVING) {
                    p.lasting.kill();
                    p.stopSequence();
                    if (p.object != null && world().server()) p.object.kill();
                }
                if (p.active()) p.state = CgCuePlaying.State.KILLED;
                yield true;
            }
            case FIELD -> {
                CgCuePlaying p = c.playing;
                if (p.state == CgCuePlaying.State.PENDING) yield false;
                if (p.object != null && world().server() && p.active()) CgCueNet.field(p.definition, p.object, c.event, c.value);
                yield true;
            }
        };
    }

    private boolean start(Command c) {
        CgCuePlaying p = c.playing;
        if (p != null && p.state != CgCuePlaying.State.PENDING) {
            // Killed while it waited: it never starts.
            if (p.state == CgCuePlaying.State.KILLED) {
                p.clear();
                handles.push(p);
            }
            return true;
        }
        if (!c.routed) {
            c.route = resolve(c.tag);
            c.routed = true;
        }
        boolean burst = c.op == Op.EXECUTE;
        while (true) {
            Route r = c.route;
            if (r == null) {
                unrouted++;
                CgTrace.counter(CgChannels.MISC, UNROUTED, unrouted);
                if (p != null) {
                    p.clear();
                    handles.push(p);
                }
                return true;
            }
            CgAssetRef<CgCueDefinition> ref = r.ref();
            if (ref.state() == CgAssetState.FAILED) {
                c.route = next(r);
                continue;
            }
            if (!ready(ref)) {
                if (!c.requested) {
                    ref.request(CgLoadPriority.MOVE);
                    c.requested = true;
                }
                return false;
            }
            CgCueDefinition cue = ref.loaded();
            if ((cue.kind() == CgCueDefinition.Kind.BURST) != burst) {
                c.route = next(r);
                continue;
            }
            CgAssetRef<CgSequenceDefinition> sequence = null;
            if (!burst && cue.sequence() != null && sequences != null) {
                sequence = assets.declare(CgSequenceKinds.SEQUENCE, cue.sequence());
                if (sequence.state() == CgAssetState.FAILED) {
                    LOGGER.warn("{}: its sequence {} failed: {}", cue.id(), cue.sequence(), sequence.failure());
                    sequence = null;
                } else if (!ready(sequence)) {
                    if (!c.sequenceRequested) {
                        sequence.request(CgLoadPriority.MOVE);
                        c.sequenceRequested = true;
                    }
                    return false;
                }
            }
            boolean server = world().server();
            double age = now - c.arrival;
            if (burst) {
                if (server) {
                    CgNetObject sent = CgCueNet.spawn(cue, c.params);
                    if (sent != null) sent.fire();
                }
                if (age > ONE_SHOT_WINDOW) {
                    lateDropped++;
                    CgTrace.counter(CgChannels.MISC, LATE_DROPPED, lateDropped);
                } else {
                    effects.burst(cue, cue.burst(), c.params, (float) age);
                }
                return true;
            }
            ArrayDeque<CgCueEffects.Lasting> pool = pools.get(cue);
            if (pool == null) pools.put(cue, pool = new ArrayDeque<>());
            CgCueEffects.Lasting lasting = pool.isEmpty() ? effects.lasting(cue) : pool.pop();
            p.started(cue, lasting);
            if (server) {
                p.object = CgCueNet.spawn(cue, p.params);
                if (p.object != null) p.object.start();
                p.sends = p.object != null && cue.authoritative();
            } else if (p.key != null && c.net == null) {
                p.object = CgCueNet.predict(cue, p.params, this, p, p.key);
                if (p.object != null) p.object.start();
            } else if (c.net != null) {
                p.object = c.net;
                if (cue.authoritative()) {
                    int i = 0;
                    for (CgCueDefinition.Write w : cue.writes().values()) p.writes(w.name(), new CgCueNet.Replica(c.net, i++, w));
                } else {
                    for (int i = 0; i < networked.size(); i++) networked.get(i).accept(p);
                }
            }
            lasting.start(p.params, (float) age);
            if (sequence != null) {
                CgSequencePlayer player = sequences.start(cue, sequence.loaded(), p.params);
                if (player != null && age > 0) player.seek(age);
                p.sequence = player;
                // Its jumps and section links reach every viewer; a viewer's copy is the play started just above.
                if (player != null && server && p.object != null && p.params.anchor() != null) {
                    CgSequenceNet.serveWith(p.object, player, p.params.anchor())
                            .range(Float.isNaN(cue.range()) ? CgCueNet.DEFAULT_RANGE : cue.range()).start();
                }
            }
            playing.add(p);
            return true;
        }
    }

    private boolean burstOn(Command c) {
        CgCuePlaying p = c.playing;
        if (p.state == CgCuePlaying.State.PENDING) return false;
        if (p.state != CgCuePlaying.State.PLAYING) return true;
        CgCueDefinition cue = p.definition;
        if (p.object != null && world().server()) {
            p.object.emit(c.op == Op.RECUR ? CgCueNet.RECUR : CgCueNet.EVENT, CgCueNet.burst(c.op == Op.RECUR ? "" : c.event, c.params));
        }
        double age = now - c.arrival;
        if (age > ONE_SHOT_WINDOW) {
            lateDropped++;
            CgTrace.counter(CgChannels.MISC, LATE_DROPPED, lateDropped);
            return true;
        }
        if (c.op == Op.RECUR) {
            effects.burst(cue, cue.recurring(), c.params, (float) age);
            return true;
        }
        List<Event> events = cue.events();
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i).event().equals(c.event)) effects.burst(cue, events.get(i).effects(), c.params, (float) age);
        }
        return true;
    }

    private void end(int index, CgCuePlaying p) {
        int last = playing.size() - 1;
        playing.set(index, playing.get(last));
        playing.remove(last);
        CgCueEffects.Lasting l = p.lasting;
        CgCueDefinition cue = p.definition;
        if (l != null && cue != null) {
            l.reset();
            ArrayDeque<CgCueEffects.Lasting> pool = pools.get(cue);
            if (pool != null) pool.push(l);
        }
        p.clear();
        handles.push(p);
    }

    private boolean ready(CgAssetRef<?> ref) {
        // A dedicated server stops at COMPILED: nothing of a cue is resident there.
        return world().server() ? ref.loaded() != null : ref.ready();
    }

    /** The route after {@code r}: its tag's parent's; none for a cue sent by id. */
    @Nullable
    private Route next(Route r) {
        CgGameplayTag parent = r.tag() == null ? null : r.tag().parent();
        return parent == null ? null : resolve(parent);
    }

    /** {@code tag}'s own route, else its nearest ancestor's; null for none. */
    @Nullable
    private Route resolve(CgGameplayTag tag) {
        Route r = resolved.get(tag);
        if (r != null || resolved.containsKey(tag)) return r;
        for (CgGameplayTag t = tag; t != null && r == null; t = t.parent()) {
            r = byHand.get(t);
            if (r == null) r = fromFiles.get(t);
        }
        resolved.put(tag, r);
        return r;
    }

    private void rebuildRoutes() {
        routesDirty = false;
        fromFiles.clear();
        resolved.clear();
        CgAssetRegistry registry = assets.registry();
        CgTagRegistry tags = CgTagRegistry.global();
        for (String ns : registry.namespaces()) {
            for (CgAssetEntry e : registry.entries(ns)) {
                if (e.kind() != CgGameplayKinds.CUE || e.redirect() != null) continue;
                String name = e.searchable().get("tag");
                if (name == null) continue;
                CgGameplayTag tag = tags.find(name);
                // An undeclared tag fails the cue at load, by name; routing it would only defer that.
                if (tag == null) continue;
                Route was = fromFiles.put(tag, new Route(tag, assets.declare(CgGameplayKinds.CUE, e.id())));
                if (was != null) LOGGER.warn("cue tag {} is declared by both {} and {}: {} plays", name, was.ref().id(), e.id(), e.id());
            }
        }
    }

    /** A float in [0, 1) from a play's seed and an entry's index: SplitMix64. */
    private static float roll(long seed, int entry) {
        long z = seed + (entry + 1) * 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z ^= z >>> 31;
        return (z >>> 40) * 0x1.0p-24f;
    }
}
