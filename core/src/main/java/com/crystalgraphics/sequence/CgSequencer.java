package com.crystalgraphics.sequence;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.crystalgraphics.audio.CgAudioEngine;
import com.crystalgraphics.frame.CgTickFunction;
import com.crystalgraphics.frame.CgTickGroup;
import com.crystalgraphics.frame.CgWorldSubsystem;
import com.crystalgraphics.util.trace.CgChannels;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.sequence.animation.CgSequenceAnimationEvaluator;
import com.crystalgraphics.sequence.definition.CgSequenceDefinition;
import com.crystalgraphics.sequence.event.CgSequenceEventEvaluator;
import com.crystalgraphics.sequence.event.CgSequenceEventListener;
import com.crystalgraphics.sequence.notify.CgNotifyEvaluator;
import com.crystalgraphics.sequence.spawn.CgSequenceLight;
import com.crystalgraphics.sequence.spawn.CgSequencePuppet;
import com.crystalgraphics.sequence.spawn.CgSequenceSpawner;
import com.crystalgraphics.sequence.sound.CgSequenceSoundEvaluator;
import com.crystalgraphics.sequence.sound.CgSequenceSubtitleEvaluator;
import com.crystalgraphics.sequence.spawn.CgSequenceSystemEvaluator;
import com.crystalgraphics.sequence.state.CgSequenceBlender;
import com.crystalgraphics.sequence.state.CgSequenceStateEvaluator;

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
 *       finished holds its last frame. One that throws is logged and killed; the others go on.</li>
 *   <li>Evaluating allocates nothing; {@link #play} and the first entry of each section bind, once.</li>
 *   <li>What a play shows on the viewer's own screen (its camera, its unbound post, its {@code global} and
 *       {@code environment} tracks) applies only where the viewer is {@linkplain CgSequencePlayer#involved()
 *       involved}: tell it who the viewer is with {@link #viewer}.</li>
 * </ul>
 */
public final class CgSequencer extends CgWorldSubsystem {

    /** Trace zones: the whole frame, its spawn and import phases, its tracks' evaluation, its spawned objects' updates. */
    /** The most passes a frame runs: montage sections shorter than a frame looping are cut there. */
    static final int MAX_PASSES = 8;
    private static final Logger LOGGER = LogManager.getLogger("CgSequencer");

    public static final String EVALUATE_ZONE = "sequence.evaluate", SPAWN_ZONE = "sequence.spawn", IMPORT_ZONE = "sequence.import",
            TRACKS_ZONE = "sequence.tracks", SPAWNED_ZONE = "sequence.spawned";

    private final List<CgSequencePlayer> players = new ArrayList<>();
    private final List<CgSequenceEvaluator> evaluators = new ArrayList<>();
    private final CgSequenceBlender blender = new CgSequenceBlender();
    private final Map<String, CgSequenceSpawner> spawners = new HashMap<>();
    private final List<CgSequenceEventListener> rpg = new ArrayList<>(), ours = new ArrayList<>();
    private double heardAgo;
    private CgTickFunction tick;
    @Nullable
    private CgSequenceTarget environment;
    /** The viewer's significance tier, low (0) to ultra (3): what a notify's {@code filter} reads. */
    private int significance = 3;
    /** Whether a role's target is the viewer's own character; null while every play involves the viewer. */
    @Nullable
    private Predicate<? super CgSequenceTarget> viewer;

    public CgSequencer() {
        for (String kind : CgSequenceStateEvaluator.KINDS) evaluators.add(new CgSequenceStateEvaluator(kind, blender));
        evaluators.add(new CgSequenceSystemEvaluator());
        evaluators.add(new CgSequenceSoundEvaluator(this));
        evaluators.add(new CgSequenceSubtitleEvaluator(this));
        evaluators.add(new CgSequenceAnimationEvaluator());
        evaluators.add(new CgNotifyEvaluator(this, blender));
        evaluators.add(new CgSequenceEventEvaluator(this));
        spawners.put("light", CgSequenceLight.SPAWNER);
        spawners.put("puppet", CgSequencePuppet.DEFAULT);
    }

    @Override
    protected void initialize() {
        // On a server, headless evaluation each game tick (AUTHORITY); on a client, each frame.
        tick = server() ? world().schedule().add(CgTickGroup.AUTHORITY, "crystalgraphics:sequencer", this::evaluate).onServer(true)
                : world().schedule().add(CgTickGroup.SEQUENCES, "crystalgraphics:sequencer", this::evaluate);
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
        return start(new CgSequencePlayer(this, definition, roles));
    }

    /**
     * A clip's notify sequence, played on the clock its parent's animation section drives, skipping its own animation
     * tracks of {@code clip}: the section already plays that clip. Its parent's.
     */
    CgSequencePlayer playNotifies(CgSequenceDefinition definition, Map<String, ? extends CgSequenceTarget> roles, String clip) {
        return start(new CgSequencePlayer(this, definition, roles).drivenFor(clip));
    }

    private CgSequencePlayer start(CgSequencePlayer player) {
        CgSequenceDefinition definition = player.definition();
        if (!server()) {
            // A driven player's spawnables were checked as its parent started.
            if (!player.driven()) check(definition);
            for (int i = 0; i < definition.spawnables().size(); i++) player.placement(i);
        }
        player.bindAll();
        players.add(player);
        return player;
    }

    /** Throws, naming it, for a spawnable of {@code definition} or a sequence it plays that no spawner here can make. */
    private void check(CgSequenceDefinition definition) {
        for (CgSequenceDefinition.Spawnable s : definition.spawnables()) {
            CgSequenceSpawner spawner = spawners.get(s.kind());
            if (spawner == null) {
                throw new IllegalArgumentException(definition.id() + ": spawnables." + s.id() + ": no spawner makes a " + s.kind() + " spawnable here");
            }
            spawner.check(definition, s);
        }
        for (CgSequenceDefinition.Child c : definition.children()) check(c.definition());
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

    /**
     * Who watches: {@code mine} tells whether a role's target is this viewer's own character. A play then involves the
     * viewer only when its caster or its target is, so a remote player's move never takes this viewer's camera, post
     * or shaders.
     *
     * <pre>{@code
     * sequencer.viewer(target -> target instanceof Fighter f && f.entityId() == localPlayerId);
     * sequencer.viewer(null);   // every play involves the viewer: one player, a preview, the harness
     * }</pre>
     *
     * <ul>
     *   <li>Unset, every play involves the viewer: right with one player, wrong for a client of many.</li>
     *   <li>A role of kind {@code character} never involves the viewer: a bystander the move is not about.</li>
     * </ul>
     */
    public CgSequencer viewer(@Nullable Predicate<? super CgSequenceTarget> mine) {
        this.viewer = mine;
        return this;
    }

    /** True if {@code target} is the viewer's own character, or no viewer is set. */
    boolean isViewer(@Nullable CgSequenceTarget target) {
        return viewer == null || target != null && viewer.test(target);
    }

    /** True with no viewer set: every play involves the viewer. */
    boolean everyoneViews() {
        return viewer == null;
    }

    /** Adds {@code evaluator} for its kind; a kind already evaluated throws. */
    public void register(CgSequenceEvaluator evaluator) {
        if (registered(evaluator.kind()) >= 0) throw new IllegalStateException("the " + evaluator.kind() + " kind already has an evaluator");
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

    /**
     * Hands an event to {@code side}'s listeners; {@code secondsAgo} is how long before the frame's end it
     * fell, which they read through {@link #heardSecondsAgo()}.
     */
    public void hear(CgSequenceEventListener.Side side, CgSequencePlayer player, CgSequenceEventListener.Phase phase, String event,
              @Nullable Object args, double secondsAgo) {
        List<CgSequenceEventListener> to = side == CgSequenceEventListener.Side.RPG ? rpg : ours;
        heardAgo = secondsAgo;
        for (int i = 0; i < to.size(); i++) to.get(i).heard(player, phase, event, args);
        heardAgo = 0;
    }

    /**
     * While a listener hears an event: how long before the frame's end the event fell, in the viewer's
     * seconds. A frame hears every event it crossed at once, at its end; a sound starts on its own time by this.
     *
     * <pre>{@code
     * sequencer.listen(Side.OURS, (player, phase, event, args) ->
     *         audio.play(click, CgSoundClass.SFX, 1f, 1f, false, audio.clock().now() - sequencer.heardSecondsAgo()));
     * }</pre>
     *
     * <ul>
     *   <li>0 for a span's begin or end, and for a key of a section an event started: they have no time on the play's
     *       timeline.</li>
     *   <li>0 outside {@code heard}.</li>
     * </ul>
     */
    public double heardSecondsAgo() {
        return heardAgo;
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
    /** Its world's audio, what its sound tracks play through; null on a server or where the host has no output. */
    @Nullable
    public CgAudioEngine audio() {
        if (world() == null) return null;
        CgAudioEngine audio = world().subsystem(CgAudioEngine.class);
        return audio != null && audio.active() ? audio : null;
    }

    public boolean server() {
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
            // A pass a montage section's end hands play on in: its rest from the next section's start, as Unreal's
            // montage sub-steps, at most MAX_PASSES a frame.
            boolean more = true;
            for (int pass = 0; more && pass < MAX_PASSES; pass++) {
                more = false;
                // Unreal's phases: spawn, then instantiation (import), then evaluation.
                for (int i = 0; i < players.size(); i++) {
                    CgSequencePlayer p = players.get(i);
                    if (p.stopped()) continue;
                    try {
                        if (pass == 0) p.advance(dt);
                        else if (p.resumes()) p.resume();
                        else continue;
                        try (CgTrace.Zone spawn = CgTrace.zone(CgChannels.WORLD, SPAWN_ZONE)) {
                            p.spawnPhase();
                        }
                        try (CgTrace.Zone imports = CgTrace.zone(CgChannels.WORLD, IMPORT_ZONE)) {
                            p.importEntries(this);
                        }
                    } catch (RuntimeException ex) {
                        fail(p, ex);
                    }
                }
                try (CgTrace.Zone tracks = CgTrace.zone(CgChannels.WORLD, TRACKS_ZONE)) {
                    for (int e = 0; e < evaluators.size(); e++) {
                        for (int i = 0; i < players.size(); i++) {
                            CgSequencePlayer p = players.get(i);
                            if (p.stopped()) continue;
                            try {
                                p.evaluate(this, e);
                            } catch (RuntimeException ex) {
                                fail(p, ex);
                            }
                        }
                    }
                    blender.apply();
                    for (int e = 0; e < evaluators.size(); e++) evaluators.get(e).postEvaluation();
                }
                for (int i = 0; i < players.size(); i++) {
                    players.get(i).endPass();
                    more |= players.get(i).resumes();
                }
            }
            try (CgTrace.Zone spawned = CgTrace.zone(CgChannels.WORLD, SPAWNED_ZONE)) {
                for (int i = 0; i < players.size(); i++) players.get(i).updateSpawned();
            }
        }
    }

    /**
     * Ends a play whose evaluation threw, every other play going on: one sequence a kind cannot play (a binding it lacks)
     * never takes the world's sequences with it. Logged once, as the play leaves; {@link CgSequencePlayer#failure()} keeps it.
     */
    private static void fail(CgSequencePlayer p, RuntimeException e) {
        LOGGER.error("{} failed and is stopped: {}", p.definition().id(), e.toString(), e);
        p.fail(e);
    }

    /** {@code kind}'s evaluator, or -1 for none: on a server, for any kind that is not {@link CgSequenceEvaluator#headless()}. */
    int evaluatorIndex(String kind) {
        int i = registered(kind);
        return i >= 0 && server() && !evaluators.get(i).headless() ? -1 : i;
    }

    private int registered(String kind) {
        for (int i = 0; i < evaluators.size(); i++) if (evaluators.get(i).kind().equals(kind)) return i;
        return -1;
    }

    CgSequenceEvaluator evaluator(int index) {
        return evaluators.get(index);
    }
}
