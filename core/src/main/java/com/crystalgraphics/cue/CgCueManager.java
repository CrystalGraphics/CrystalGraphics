package com.crystalgraphics.cue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.crystalgraphics.asset.kind.CgGameplayKinds;
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
import com.crystalgraphics.sequence.CgSequencePlayer;
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
 *   <li>On a dedicated server only {@code authoritative} cues play, with no effects.</li>
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

    enum Op { EXECUTE, ADD, EVENT, RECUR, REMOVE, KILL }

    /** A tag's own cue. */
    private record Route(CgGameplayTag tag, CgAssetRef<CgCueDefinition> ref) {
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
        final CgCueParameters params = new CgCueParameters();
        double arrival;
        @Nullable
        Route route;
        boolean routed, requested;

        void clear() {
            op = null;
            tag = null;
            playing = null;
            event = null;
            params.reset();
            route = null;
            routed = requested = false;
        }
    }

    private final CgVfxAssets assets;
    private final Map<CgGameplayTag, Route> fromFiles = new HashMap<>(), byHand = new HashMap<>();
    /** Each tag asked for and the route that answers it, its own or its nearest ancestor's; null for none. */
    private final Map<CgGameplayTag, Route> resolved = new HashMap<>();
    private final Map<CgCueDefinition, ArrayDeque<CgCueEffects.Lasting>> pools = new HashMap<>();
    private final ArrayDeque<CgCuePlaying> handles = new ArrayDeque<>();
    private final ArrayDeque<Command> spare = new ArrayDeque<>();
    private final List<CgCuePlaying> playing = new ArrayList<>();
    private final float[] scratch = new float[4];
    private Command[] queue = new Command[64];
    private int queued;
    private CgCueEffects effects = CgCueEffects.NONE;
    private Predicate<CgAttachment> localControl = a -> false;
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
        tick = world().schedule().add(CgTickGroup.PLAYS, "crystalgraphics:cues", this::update);
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
        CgCuePlaying p = handles.isEmpty() ? new CgCuePlaying(this) : handles.pop();
        p.state = CgCuePlaying.State.PENDING;
        p.params.set(at);
        queue(Op.ADD, tag, p, null, at);
        return p;
    }

    /** Grows {@code cue}'s pool to {@code count} looping plays, so the first that many allocate nothing. */
    public void prewarm(CgCueDefinition cue, int count) {
        ArrayDeque<CgCueEffects.Lasting> pool = pools.computeIfAbsent(cue, c -> new ArrayDeque<>());
        while (pool.size() < count) pool.push(effects.lasting(cue));
        while (handles.size() + playing.size() < count) handles.push(new CgCuePlaying(this));
        while (spare.size() < count) spare.push(new Command());
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
                if (p.state == CgCuePlaying.State.PLAYING) p.pull(now, scratch);
                else if (p.state == CgCuePlaying.State.REMOVING && p.lasting != null && p.lasting.finished()) end(i, p);
                else if (p.state == CgCuePlaying.State.KILLED) end(i, p);
            }
        }
    }

    void queue(Op op, @Nullable CgGameplayTag tag, @Nullable CgCuePlaying p, @Nullable String event, CgCueParameters at) {
        Command c = spare.isEmpty() ? new Command() : spare.pop();
        c.op = op;
        c.tag = tag;
        c.playing = p;
        c.event = event;
        c.params.set(at);
        c.arrival = now;
        if (queued == queue.length) queue = Arrays.copyOf(queue, queued * 2);
        queue[queued++] = c;
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
                }
                yield true;
            }
            case KILL -> {
                CgCuePlaying p = c.playing;
                if (p.state == CgCuePlaying.State.PLAYING || p.state == CgCuePlaying.State.REMOVING) p.lasting.kill();
                if (p.active()) p.state = CgCuePlaying.State.KILLED;
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
                c.route = r.tag().parent() == null ? null : resolve(r.tag().parent());
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
                c.route = r.tag().parent() == null ? null : resolve(r.tag().parent());
                continue;
            }
            if (world().server() && !cue.authoritative()) {
                if (p != null) {
                    p.clear();
                    handles.push(p);
                }
                return true;
            }
            double age = now - c.arrival;
            if (burst) {
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
            lasting.start(p.params, (float) age);
            playing.add(p);
            return true;
        }
    }

    private boolean burstOn(Command c) {
        CgCuePlaying p = c.playing;
        if (p.state == CgCuePlaying.State.PENDING) return false;
        if (p.state != CgCuePlaying.State.PLAYING) return true;
        CgCueDefinition cue = p.definition;
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

    private boolean ready(CgAssetRef<CgCueDefinition> ref) {
        // A dedicated server stops at COMPILED: nothing of a cue is resident there.
        return world().server() ? ref.loaded() != null : ref.ready();
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
