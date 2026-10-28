package com.crystalgraphics.sequence;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.crystalgraphics.frame.CgTickFunction;
import com.crystalgraphics.frame.CgTickGroup;
import com.crystalgraphics.frame.CgWorldSubsystem;
import com.crystalgraphics.util.trace.CgChannels;
import com.crystalgraphics.trace.CgTrace;

/**
 * A world's sequences: every playing {@link CgSequencePlayer} and one evaluator a track kind, run over all of them
 * at once in fixed phases each frame. Unreal's {@code UMovieSceneEntitySystemLinker} and its runner, one per world
 * rather than one per sequence.
 *
 * <pre>{@code
 * CgSequencer sequencer = world.add(new CgSequencer());        // its tick runs in CgTickGroup.SEQUENCES
 * CgSequencePlayer player = sequencer.play(definition, Map.of("caster", goku));
 *
 * sequencer.register(new MyKindEvaluator());                    // a kind of your own
 * sequencer.evaluate(dt);                                       // what its tick does; a test or a tool calls it
 * }</pre>
 *
 * <ul>
 *   <li>Each frame, in order: <b>advance</b> each player's range; <b>import</b> where it crossed a segment, its
 *       entries changing; <b>evaluate</b> each kind's entries across every player, in the order the evaluators were
 *       registered (the built-in state kinds first).</li>
 *   <li>Then <b>blend</b>: what every section of every player wrote into one target slot combines by blend type
 *       and weight (ease times {@code weight}), as Unreal's blenders do, and is written once. A slot's value before
 *       any sequence animated it is kept, and put back when its last animator leaves, if one of them restores
 *       state.</li>
 *   <li>Then <b>events</b>: the frame's {@code event} keys crossed and spans begun or ended, heard by the listeners of
 *       their side ({@link #listen}), as Unreal runs its event tokens after evaluation.</li>
 *   <li>A player that is {@linkplain CgSequencePlayer#stop() stopped} leaves at the next evaluation; one that
 *       finished holds its last frame.</li>
 *   <li>Evaluating allocates nothing; {@link #play} and the first entry of each section bind, once.</li>
 * </ul>
 */
public final class CgSequencer extends CgWorldSubsystem {

    /** Trace zones: the whole frame, its spawn and import phases, its tracks' evaluation, its spawned objects' updates. */
    public static final String EVALUATE_ZONE = "sequence.evaluate", SPAWN_ZONE = "sequence.spawn", IMPORT_ZONE = "sequence.import",
            TRACKS_ZONE = "sequence.tracks", SPAWNED_ZONE = "sequence.spawned";

    private final List<CgSequencePlayer> players = new ArrayList<>();
    private final List<CgSequenceEvaluator> evaluators = new ArrayList<>();
    private final CgSequenceBlender blender = new CgSequenceBlender();
    private final Map<String, CgSequenceSpawner> spawners = new HashMap<>();
    private final List<CgSequenceEventListener> rpg = new ArrayList<>(), ours = new ArrayList<>();
    private CgTickFunction tick;
    @Nullable
    private CgSequenceTarget environment;
    /** The viewer's significance tier, low (0) to ultra (3): what a notify's {@code filter} reads. */
    private int significance = 3;

    public CgSequencer() {
        for (String kind : CgSequenceStateEvaluator.KINDS) evaluators.add(new CgSequenceStateEvaluator(kind, blender));
        evaluators.add(new CgSequenceSystemEvaluator());
        evaluators.add(new CgSequenceAnimationEvaluator());
        evaluators.add(new CgNotifyEvaluator(this, blender));
        evaluators.add(new CgSequenceEventEvaluator(this));
    }

    @Override
    protected void initialize() {
        tick = world().schedule().add(CgTickGroup.SEQUENCES, "crystalgraphics:sequencer", this::evaluate);
    }

    @Override
    protected void deinitialize() {
        if (tick != null) tick.close();
    }

    /**
     * Plays {@code definition} from its start, its roles bound by name. Every section whose target is known binds
     * now: a slot a target lacks throws here, naming both.
     */
    public CgSequencePlayer play(CgSequenceDefinition definition, Map<String, ? extends CgSequenceTarget> roles) {
        CgSequencePlayer player = new CgSequencePlayer(this, definition, roles);
        if (!server()) {
            for (CgSequenceDefinition.Spawnable s : definition.spawnables()) {
                if (!spawners.containsKey(s.kind())) {
                    throw new IllegalArgumentException(definition.id() + ": spawnables." + s.id() + ": no spawner makes a " + s.kind() + " spawnable here");
                }
            }
            for (int i = 0; i < definition.spawnables().size(); i++) player.placement(i);
        }
        player.bindAll();
        players.add(player);
        return player;
    }

    /** What {@code environment} bindings write: the host world's fog, ambient light and grade. Null until a host sets it. */
    @Nullable
    public CgSequenceTarget environment() {
        return environment;
    }

    public CgSequencer environment(@Nullable CgSequenceTarget environment) {
        this.environment = environment;
        return this;
    }

    /** Adds {@code evaluator} for its kind; a kind already evaluated throws. */
    public void register(CgSequenceEvaluator evaluator) {
        if (evaluatorIndex(evaluator.kind()) >= 0) throw new IllegalStateException("the " + evaluator.kind() + " kind already has an evaluator");
        evaluators.add(evaluator);
    }

    /** Makes {@code kind}'s spawnables ({@code system}, {@code prefab}, {@code post}...); replaces one already set. */
    public CgSequencer spawner(String kind, CgSequenceSpawner spawner) {
        spawners.put(kind, spawner);
        return this;
    }

    /** {@code kind}'s spawner; throws, naming the kind, where none is set. */
    CgSequenceSpawner spawner(String kind) {
        CgSequenceSpawner s = spawners.get(kind);
        if (s == null) throw new IllegalStateException("no spawner makes a " + kind + " spawnable: CgSequencer.spawner(\"" + kind + "\", ...)");
        return s;
    }

    /**
     * Adds {@code listener} for every sequence's {@code event} tracks of {@code side}, from the next frame on.
     *
     * <pre>{@code
     * sequencer.listen(Side.OURS, (player, phase, event, args) -> captions.show(event));
     * }</pre>
     */
    public CgSequencer listen(CgSequenceEventListener.Side side, CgSequenceEventListener listener) {
        (side == CgSequenceEventListener.Side.RPG ? rpg : ours).add(listener);
        return this;
    }

    /** Removes {@code listener}, added for {@code side}. */
    public void unlisten(CgSequenceEventListener.Side side, CgSequenceEventListener listener) {
        (side == CgSequenceEventListener.Side.RPG ? rpg : ours).remove(listener);
    }

    /** Hands an event to {@code side}'s listeners. */
    void hear(CgSequenceEventListener.Side side, CgSequencePlayer player, CgSequenceEventListener.Phase phase, String event,
              @Nullable Object args) {
        List<CgSequenceEventListener> to = side == CgSequenceEventListener.Side.RPG ? rpg : ours;
        for (int i = 0; i < to.size(); i++) to.get(i).heard(player, phase, event, args);
    }

    /** The viewer's significance tier, 0 (low) to 3 (ultra): a notify filtered above it does not fire. */
    public int significance() {
        return significance;
    }

    public CgSequencer significance(int tier) {
        this.significance = tier;
        return this;
    }

    /** Whether its world is a dedicated server's, where presentation (a notify's behaviour) never runs. */
    boolean server() {
        return world() != null && world().server();
    }

    /** The players now playing, in the order they started. */
    public List<CgSequencePlayer> players() {
        return players;
    }

    /** One frame: every player advanced {@code dt} seconds, then each kind evaluated over all of them. */
    public void evaluate(float dt) {
        try (CgTrace.Zone frame = CgTrace.zone(CgChannels.WORLD, EVALUATE_ZONE)) {
            for (int i = players.size() - 1; i >= 0; i--) {
                if (!players.get(i).stopped()) continue;
                for (CgSequenceEvaluator e : evaluators) e.stopped(players.get(i));
                players.get(i).cleanUp();
                players.remove(i);
            }
            // Unreal's phases: spawn, then instantiation (import), then evaluation.
            for (int i = 0; i < players.size(); i++) {
                CgSequencePlayer p = players.get(i);
                p.advance(dt);
                try (CgTrace.Zone spawn = CgTrace.zone(CgChannels.WORLD, SPAWN_ZONE)) {
                    p.spawnPhase();
                }
                try (CgTrace.Zone imports = CgTrace.zone(CgChannels.WORLD, IMPORT_ZONE)) {
                    p.importEntries(this);
                }
            }
            try (CgTrace.Zone tracks = CgTrace.zone(CgChannels.WORLD, TRACKS_ZONE)) {
                for (int e = 0; e < evaluators.size(); e++) {
                    for (int i = 0; i < players.size(); i++) players.get(i).evaluate(this, e);
                }
                blender.apply();
                for (int e = 0; e < evaluators.size(); e++) evaluators.get(e).postEvaluation();
            }
            try (CgTrace.Zone spawned = CgTrace.zone(CgChannels.WORLD, SPAWNED_ZONE)) {
                for (int i = 0; i < players.size(); i++) players.get(i).updateSpawned();
            }
        }
    }

    int evaluatorIndex(String kind) {
        for (int i = 0; i < evaluators.size(); i++) if (evaluators.get(i).kind().equals(kind)) return i;
        return -1;
    }

    CgSequenceEvaluator evaluator(int index) {
        return evaluators.get(index);
    }
}
