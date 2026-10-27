package com.crystalgraphics.sequence;

import java.util.Locale;
import java.util.Map;

import javax.annotation.Nullable;

import com.crystalgraphics.asset.tree.CgAssetException;
import com.crystalgraphics.sequence.time.CgFrameTime;
import com.crystalgraphics.sequence.time.CgSequenceEvaluationRange;
import com.crystalgraphics.sequence.time.CgSequencePlaybackPosition;

/**
 * One playing sequence: its definition, its clock, its rate, what its roles and spawnables are bound to, and the
 * entries of its evaluation field active now. Unreal's {@code UMovieSceneSequencePlayer} with its sequence instance.
 * A {@link CgSequencer} makes and evaluates it.
 *
 * <pre>{@code
 * CgSequencePlayer player = sequencer.play(definition, Map.of("caster", goku, "target", vegeta));
 * player.rate(0.5f);                        // half speed
 * player.spawnable(0, chargeSystem);        // what spawnable 0 is, once made
 * float x = player.locator(0).translation()[0];
 * player.stop();                            // leaves the sequencer at its next evaluation
 * }</pre>
 *
 * <ul>
 *   <li>A role {@link #play} is not given fails it, unless the role is {@code absent: skip}; then its tracks are
 *       skipped.</li>
 *   <li>A spawnable's tracks wait for {@link #spawnable} to say what it is.</li>
 *   <li>At its range's end it holds its last frame ({@link #finished()}) until stopped.</li>
 *   <li>Its time is its own: the seconds the sequencer has advanced it, times its rate, never dilated by what it keys.</li>
 * </ul>
 */
public final class CgSequencePlayer {

    private final CgSequencer sequencer;
    private final CgSequenceDefinition definition;
    private final CgSequencePlaybackPosition position;
    private final CgSequenceTarget[] roles, spawnables;
    private final CgSequenceLocator[] locators;
    /** Per track, per section (index + 1; 0 for the whole track): what its evaluator bound. */
    private final Object[][] data;
    private final boolean[][] bound;
    /** The active entries of the current segment, and each one's evaluator's index in the sequencer. */
    private final int[] active, activeEvaluator;
    private int activeCount, segment = -1;
    private double seconds;
    private float rate = 1f;
    private boolean finished, stopped;

    CgSequencePlayer(CgSequencer sequencer, CgSequenceDefinition definition, Map<String, ? extends CgSequenceTarget> roles) {
        this.sequencer = sequencer;
        this.definition = definition;
        this.position = CgSequencePlaybackPosition.of(definition);
        this.roles = new CgSequenceTarget[definition.roles().size()];
        for (int i = 0; i < this.roles.length; i++) {
            CgSequenceDefinition.Role role = definition.roles().get(i);
            this.roles[i] = roles.get(role.name());
            if (this.roles[i] == null && !role.skip()) {
                throw new IllegalArgumentException(definition.id() + ": its role " + role.name() + " is not bound, and is not absent: skip");
            }
        }
        for (String name : roles.keySet()) {
            if (definition.role(name) < 0) throw new IllegalArgumentException(definition.id() + " has no role named " + name);
        }
        this.locators = new CgSequenceLocator[definition.locators().size()];
        for (int i = 0; i < locators.length; i++) locators[i] = new CgSequenceLocator(definition.locators().get(i).id());
        this.spawnables = new CgSequenceTarget[definition.spawnables().size()];
        this.data = new Object[definition.tracks().size()][];
        this.bound = new boolean[data.length][];
        for (int t = 0; t < data.length; t++) {
            data[t] = new Object[definition.tracks().get(t).sections().size() + 1];
            bound[t] = new boolean[data[t].length];
        }
        int most = 0;
        CgSequenceField field = definition.field();
        for (int s = 0; s < field.segments(); s++) most = Math.max(most, field.entryCount(s));
        this.active = new int[most];
        this.activeEvaluator = new int[most];
    }

    public CgSequenceDefinition definition() {
        return definition;
    }

    /** Seconds played, times its rate, since it started. */
    public double seconds() {
        return seconds;
    }

    /** Its play rate: 1 as authored, 0.5 half speed. */
    public float rate() {
        return rate;
    }

    public CgSequencePlayer rate(float rate) {
        this.rate = rate;
        return this;
    }

    /** Whether it has reached its range's end and holds there. */
    public boolean finished() {
        return finished;
    }

    /** Takes it out of its sequencer at the next evaluation. */
    public void stop() {
        stopped = true;
    }

    public boolean stopped() {
        return stopped;
    }

    /** The span the last evaluation covered, at the tick resolution. */
    public CgSequenceEvaluationRange range() {
        return position.lastRange();
    }

    /** Locator {@code index}'s transform. */
    public CgSequenceLocator locator(int index) {
        return locators[index];
    }

    /**
     * Says what spawnable {@code index} is, once made: its tracks bind to it now, and a slot it lacks throws here,
     * naming it.
     */
    public CgSequencePlayer spawnable(int index, @Nullable CgSequenceTarget target) {
        spawnables[index] = target;
        if (target != null) {
            bindAll();
            segment = -1;   // its entries join at the next import, inside the segment as well as past it
        }
        return this;
    }

    /** Binds every section whose target is known and that is not bound yet: at play, and as a spawnable arrives. */
    void bindAll() {
        for (CgSequenceTrack track : definition.tracks()) {
            if (track.muted()) continue;
            int evaluator = sequencer.evaluatorIndex(track.kind());
            if (evaluator < 0) continue;
            boolean waits = track.binding().subject() == CgSequenceBinding.Subject.SPAWNABLE && spawnables[track.binding().index()] == null
                    || track.binding().subject() == CgSequenceBinding.Subject.ROLE && roles[track.binding().index()] == null;
            if (waits) continue;
            for (int s = -1; s < track.sections().size(); s++) {
                if (bound[track.index()][s + 1]) continue;
                if (s < 0 && track.population() != CgSequenceTrack.Population.WHOLE_TRACK) continue;
                if (s >= 0 && track.population() == CgSequenceTrack.Population.WHOLE_TRACK) continue;
                bound[track.index()][s + 1] = true;
                sequencer.evaluator(evaluator).bind(this, track, s < 0 ? null : track.sections().get(s));
            }
        }
    }

    /** What an evaluator bound for {@code section} of {@code track} (null for the whole track). */
    @Nullable
    public Object data(CgSequenceTrack track, @Nullable CgSequenceSection section) {
        return data[track.index()][section == null ? 0 : section.index() + 1];
    }

    /** Keeps {@code value} for {@code section} of {@code track}: an evaluator's state, made once in its bind. */
    public void data(CgSequenceTrack track, @Nullable CgSequenceSection section, @Nullable Object value) {
        data[track.index()][section == null ? 0 : section.index() + 1] = value;
    }

    /**
     * The target {@code track}'s binding names, its part resolved; null where it waits on a spawnable not yet made or
     * names a skipped role. A binding naming a part its object does not have fails, naming both.
     */
    @Nullable
    public CgSequenceTarget target(CgSequenceTrack track) {
        CgSequenceBinding b = track.binding();
        CgSequenceTarget object = switch (b.subject()) {
            case ROLE -> roles[b.index()];
            case LOCATOR -> locators[b.index()];
            case SPAWNABLE -> spawnables[b.index()];
            case ENVIRONMENT -> sequencer.environment();
            case NONE -> null;
        };
        if (object == null || b.part() == CgSequenceBinding.Part.WHOLE || b.part() == CgSequenceBinding.Part.VELOCITY) return object;
        CgSequenceTarget part = object.part(b.part(), b.partName());
        if (part == null) throw new CgAssetException(definition.id() + ": tracks." + track.id() + ": " + object + " has no "
                + b.part().name().toLowerCase(Locale.ROOT) + " " + b.partName());
        return part;
    }

    // ── The sequencer's phases ───────────────────────────────────────────────────────────

    /** Advance: its range from its last time to {@code dt} seconds on. */
    CgSequenceEvaluationRange advance(float dt) {
        seconds += dt * rate;
        double length = definition.seconds(definition.end() - definition.start());
        if (seconds >= length) {
            seconds = length;
            finished = true;
        } else if (seconds < 0) {
            seconds = 0;
        }
        return position.playToSeconds(seconds);
    }

    /** The tick it evaluates at, a sub-frame allowed. */
    float tick() {
        long t = position.lastRange().current();
        return CgFrameTime.frame(t) + CgFrameTime.subFrame(t);
    }

    /** Import: where it crossed into another segment, its active entries change; each newly bound once. */
    void importEntries(CgSequencer sequencer) {
        int s = definition.field().segmentAt(CgFrameTime.frame(position.lastRange().current()));
        if (s == segment) return;
        segment = s;
        CgSequenceField field = definition.field();
        activeCount = 0;
        for (int k = 0; k < field.entryCount(s); k++) {
            int e = field.entry(s, k);
            CgSequenceTrack track = definition.tracks().get(field.track(e));
            int evaluator = sequencer.evaluatorIndex(track.kind());
            if (evaluator < 0) continue;
            // Not bound yet: a spawnable not made, or a skipped role.
            if (!bound[track.index()][field.section(e) + 1]) continue;
            active[activeCount] = e;
            activeEvaluator[activeCount++] = evaluator;
        }
    }

    /** Evaluate: each active entry of evaluator {@code evaluator}'s kind. */
    void evaluate(CgSequencer sequencer, int evaluator) {
        CgSequenceField field = definition.field();
        CgSequenceEvaluator e = sequencer.evaluator(evaluator);
        float tick = tick();
        for (int k = 0; k < activeCount; k++) {
            if (activeEvaluator[k] != evaluator) continue;
            CgSequenceTrack track = definition.tracks().get(field.track(active[k]));
            int section = field.section(active[k]);
            e.evaluate(this, track, section < 0 ? null : track.sections().get(section), tick);
        }
    }

    @Override
    public String toString() {
        return "player of " + definition.id() + " at " + seconds + " s";
    }
}
