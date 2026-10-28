package com.crystalgraphics.sequence;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nullable;

import org.joml.Quaternionf;

import com.crystalgraphics.asset.tree.CgAssetException;
import com.crystalgraphics.object.value.CgQuantity;
import com.crystalgraphics.object.value.CgUnits;
import com.crystalgraphics.sequence.time.CgFrameTime;
import com.crystalgraphics.sequence.time.CgSequenceEvaluationRange;
import com.crystalgraphics.sequence.time.CgSequencePlaybackPosition;
import com.crystalgraphics.world.CgAttachment;
import com.crystalgraphics.sequence.binding.CgSequenceLocator;
import com.crystalgraphics.sequence.binding.CgSequencePlacement;
import com.crystalgraphics.sequence.binding.CgSequenceRole;
import com.crystalgraphics.sequence.condition.CgConditionKinds;
import com.crystalgraphics.sequence.definition.CgSequenceBinding;
import com.crystalgraphics.sequence.definition.CgSequenceChannel;
import com.crystalgraphics.sequence.definition.CgSequenceCondition;
import com.crystalgraphics.sequence.definition.CgSequenceDefinition;
import com.crystalgraphics.sequence.definition.CgSequenceField;
import com.crystalgraphics.sequence.definition.CgSequenceSection;
import com.crystalgraphics.sequence.definition.CgSequenceTrack;
import com.crystalgraphics.sequence.event.CgEventTrigger;
import com.crystalgraphics.sequence.spawn.CgSequenceSpawned;

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
    /** Per spawnable, its {@code spawned} track's index, or -1 for one that lives for the whole play. */
    private final int[] spawnedTrack;
    private final CgSequencePlacement[] spawnablePlaces, locatorPlaces;
    /** Per track, per section (index + 1; 0 for the whole track): what its evaluator bound. */
    private final Object[][] data;
    private final boolean[][] bound;
    /** Per track, per section as {@link #data}: its condition's answer, tested at its first entry; 0 untested. */
    private final byte[][] conditions;
    private final CgSequenceCondition.Reader conditionReader;
    /** The active entries of the current segment, and each one's evaluator's index in the sequencer. */
    private final int[] active, activeEvaluator;
    private int activeCount, segment = -1;
    /** Its triggered sections, by place in the definition's lists: each one's clock and evaluator, and whether it runs. */
    private final CgSequenceDefinition.SectionRef[] triggered;
    private final CgEventTrigger[] triggers;
    private final int[] triggeredEvaluator;
    private final boolean[] running;
    /** Per composite section, the section it plays next, -1 for none: the definition's, re-linked by {@link #nextSection}. */
    private final int[] next;
    /** The composite section play is in, or -1. */
    private int composite = -1;
    /** Seconds a montage section's end handed on to its next, played in a pass of their own; and whether play jumps there. */
    private double carry;
    private boolean resumes, resumeJumps, inPass;
    /** The first tick a jump's one-shots fire from: past its point for a jump, the window before it for a seek. */
    private int oneShotFrom = Integer.MAX_VALUE;
    private double seconds;
    private float rate = 1f;
    private long seed;
    private boolean finished, stopped, jump, seek;

    CgSequencePlayer(CgSequencer sequencer, CgSequenceDefinition definition, Map<String, ? extends CgSequenceTarget> roles) {
        this.sequencer = sequencer;
        this.definition = definition;
        this.seed = definition.id().hashCode();
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
        CgUnits units = definition.units();
        for (int i = 0; i < locators.length; i++) {
            Map<?, ?> tree = definition.locators().get(i).tree();
            locators[i] = new CgSequenceLocator(definition.locators().get(i).id());
            if ("quaternion".equals(units.rotation())) {
                locators[i].restTurn(units.rotationToHost(tree.get("rotation"), new Quaternionf()));
            }
            locators[i].rest(vector(units, tree.get("location"), CgQuantity.POSITION, 0f), vector(units, tree.get("rotation"), CgQuantity.ROTATION, 0f),
                    vector(units, tree.get("scale"), CgQuantity.SCALE, 1f));
        }
        this.spawnables = new CgSequenceTarget[definition.spawnables().size()];
        this.spawnedTrack = new int[spawnables.length];
        Arrays.fill(spawnedTrack, -1);
        for (CgSequenceTrack t : definition.tracks()) {
            if (t.kind().equals("spawned") && !t.muted() && t.binding().subject() == CgSequenceBinding.Subject.SPAWNABLE) spawnedTrack[t.binding().index()] = t.index();
        }
        this.spawnablePlaces = new CgSequencePlacement[spawnables.length];
        this.locatorPlaces = new CgSequencePlacement[locators.length];
        this.data = new Object[definition.tracks().size()][];
        this.bound = new boolean[data.length][];
        this.conditions = new byte[data.length][];
        for (int t = 0; t < data.length; t++) {
            data[t] = new Object[definition.tracks().get(t).sections().size() + 1];
            bound[t] = new boolean[data[t].length];
            conditions[t] = new byte[data[t].length];
        }
        this.conditionReader = CgConditionKinds.reader(i -> this.roles[i]);
        int most = 0;
        CgSequenceField field = definition.field();
        for (int s = 0; s < field.segments(); s++) most = Math.max(most, field.entryCount(s));
        this.active = new int[most];
        this.activeEvaluator = new int[most];
        this.next = new int[definition.compositeSections().size()];
        for (int i = 0; i < next.length; i++) next[i] = definition.compositeSections().get(i).next();
        List<CgSequenceDefinition.SectionRef> refs = new ArrayList<>();
        for (List<CgSequenceDefinition.SectionRef> l : definition.triggered().values()) refs.addAll(l);
        this.triggered = refs.toArray(new CgSequenceDefinition.SectionRef[0]);
        this.triggers = new CgEventTrigger[triggered.length];
        this.triggeredEvaluator = new int[triggered.length];
        this.running = new boolean[triggered.length];
        for (int i = 0; i < triggered.length; i++) {
            CgSequenceSection s = definition.tracks().get(triggered[i].track()).sections().get(triggered[i].section());
            triggers[i] = new CgEventTrigger(s.trigger(), s.to() == CgSequenceSection.OPEN_END ? Integer.MAX_VALUE : s.to());
            triggeredEvaluator[i] = -1;
        }
    }

    /**
     * The gameplay's {@code event} into this play: every section with {@code start: { event: name }} starts now, its
     * range from this tick, and one already running starts over. Ignored on a dedicated server, and by a section of a
     * muted track.
     *
     * <pre>{@code
     * player.event("dragonforge:energy_charge");     // its triggered sections play from this frame's tick
     * }</pre>
     *
     * @return whether any section started
     */
    public boolean event(String name) {
        if (sequencer.server()) return false;
        int now = CgFrameTime.frame(position.lastRange().current());
        boolean any = false;
        for (int i = 0; i < triggered.length; i++) {
            if (definition.tracks().get(triggered[i].track()).muted()) continue;
            if (triggers[i].fire(name, now, seed)) {
                running[i] = true;
                any = true;
            }
        }
        return any;
    }

    /** {@code section}'s clock, a triggered section of {@code track}: its run count and seed; null for another. */
    @Nullable
    public CgEventTrigger trigger(CgSequenceTrack track, CgSequenceSection section) {
        for (int i = 0; i < triggered.length; i++) {
            if (triggered[i].track() == track.index() && triggered[i].section() == section.index()) return triggers[i];
        }
        return null;
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

    /** Its seed: what a notify's chance draws from, the same for every viewer of one play. */
    public long seed() {
        return seed;
    }

    public CgSequencePlayer seed(long seed) {
        this.seed = seed;
        return this;
    }

    /** Whether it has reached its range's end and holds there. */
    public boolean finished() {
        return finished;
    }

    /**
     * Jumps to {@code seconds} at the next evaluation: Unreal's {@code JumpToFrame}. What it lands on is evaluated, what
     * it skips over fires nothing, and sections it left end, restoring what they set.
     */
    public CgSequencePlayer jumpTo(double seconds) {
        this.seconds = seconds;
        this.jump = true;
        this.seek = false;
        this.finished = false;
        this.resumes = false;
        return this;
    }

    /** How long before a seek's point a one-shot still fires: a hit spark later than this is wrong, and is dropped. */
    public static final double ONE_SHOT_WINDOW = 0.2;

    /**
     * Seeks to {@code seconds} at the next evaluation, as a late joiner or a scrub lands: state is evaluated there,
     * what lasts (a system, a span) starts at its age in its section, and a one-shot fires only within
     * {@link #ONE_SHOT_WINDOW} seconds before it. {@link #jumpTo} fires no one-shot at all.
     *
     * <pre>{@code
     * CgSequencePlayer late = sequencer.play(definition, roles).seek(serverSeconds);   // a late joiner
     * }</pre>
     */
    public CgSequencePlayer seek(double seconds) {
        jumpTo(seconds);
        this.seek = true;
        return this;
    }

    /**
     * Jumps to the start of the montage section named {@code name}, as {@code JumpToSectionName} does: a jump, firing
     * nothing it skips. A name it has not throws, naming it.
     */
    public CgSequencePlayer jumpToSection(String name) {
        CgSequenceDefinition.CompositeSection c = definition.compositeSection(name);
        if (c == null) throw new IllegalArgumentException(definition.id() + " has no section " + name);
        return jumpTo(definition.seconds(c.from() - definition.start()));
    }

    /**
     * Makes the montage section {@code from} hand play to {@code to} at its end, or stop there for null: Unreal's
     * {@code SetNextSectionName}, for this play alone. A loop ends when gameplay re-links its section elsewhere.
     *
     * <pre>{@code
     * player.nextSection("loop", "release");   // the charge's loop runs out into its release
     * }</pre>
     */
    public CgSequencePlayer nextSection(String from, @Nullable String to) {
        int f = compositeIndex(from), t = to == null ? -1 : compositeIndex(to);
        if (f < 0 || to != null && t < 0) throw new IllegalArgumentException(definition.id() + " has no section " + (f < 0 ? from : to));
        next[f] = t;
        return this;
    }

    /** The name of the montage section play is in, or null where the sequence has none there. */
    @Nullable
    public String section() {
        return composite < 0 ? null : definition.compositeSections().get(composite).name();
    }

    /** The first tick a jump's one-shots fire from: past its point for a jump, {@link #ONE_SHOT_WINDOW} before it for a seek. */
    public int oneShotFrom() {
        return oneShotFrom;
    }

    private int compositeIndex(String name) {
        List<CgSequenceDefinition.CompositeSection> cs = definition.compositeSections();
        for (int i = 0; i < cs.size(); i++) if (cs.get(i).name().equals(name)) return i;
        return -1;
    }

    private int compositeAt(int tick) {
        List<CgSequenceDefinition.CompositeSection> cs = definition.compositeSections();
        for (int i = 0; i < cs.size(); i++) if (tick >= cs.get(i).from() && tick < cs.get(i).to()) return i;
        return -1;
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
     * Where spawnable {@code index} stands each frame: its {@code at} under its parent, as an attachment. What a
     * spawner's object follows.
     */
    public CgAttachment placement(int index) {
        CgSequencePlacement p = spawnablePlaces[index];
        if (p != null) return p;
        CgSequenceDefinition.Spawnable s = definition.spawnables().get(index);
        Map<?, ?> at = s.tree().get("at") instanceof Map<?, ?> m ? m : Map.of();
        CgUnits units = definition.units();
        Quaternionf turn = units.rotationToHost(at.get("rotation"), new Quaternionf());
        p = new CgSequencePlacement(parent(s.at(), "spawnables." + s.id() + ".at"), definition,
                vector(units, at.get("offset"), CgQuantity.OFFSET, 0f), turn);
        return spawnablePlaces[index] = p;
    }

    /** Locator {@code index}'s place each frame, under its parent. */
    public CgAttachment locatorPlacement(int index) {
        CgSequencePlacement p = locatorPlaces[index];
        if (p != null) return p;
        CgSequenceDefinition.Locator l = definition.locators().get(index);
        return locatorPlaces[index] = new CgSequencePlacement(parent(l.parent(), "locators." + l.id() + ".parent"), definition, locators[index]);
    }

    /** What a binding places under, resolved each frame; a role socket it cannot place fails here, naming it. */
    private CgSequencePlacement.Parent parent(CgSequenceBinding b, String path) {
        switch (b.subject()) {
            case ROLE -> {
                CgSequenceTarget target = roles[b.index()];
                if (target == null) return () -> null;
                if (!(target instanceof CgSequenceRole role)) {
                    throw new CgAssetException(definition.id() + ": " + path + ": the role " + definition.roles().get(b.index()).name()
                            + " places nothing: its target is not a CgSequenceRole");
                }
                CgAttachment a = role.attachment(b.part() == CgSequenceBinding.Part.SOCKET ? b.partName() : null);
                if (a == null) throw new CgAssetException(definition.id() + ": " + path + ": " + role + " has no socket " + b.partName());
                return () -> a;
            }
            case LOCATOR -> {
                CgAttachment a = locatorPlacement(b.index());
                return () -> a;
            }
            case SPAWNABLE -> {
                int index = b.index();
                String node = b.partName();
                return () -> spawnables[index] instanceof CgSequenceSpawned s ? (node == null ? placement(index) : s.attachment(node)) : null;
            }
            default -> {
                return () -> null;
            }
        }
    }

    private static float[] vector(CgUnits units, @Nullable Object v, CgQuantity q, float fill) {
        float[] out = {fill, fill, fill};
        if (!(v instanceof List<?> l) || l.size() != 3) return out;
        Object host = units.toHost(l, q);
        List<?> h = host instanceof List<?> hl ? hl : l;
        for (int i = 0; i < 3; i++) out[i] = ((Number) h.get(i)).floatValue();
        return out;
    }

    /**
     * Spawn: each spawnable made or ended as its {@code spawned} track says at this tick; one with none lives for the
     * whole play. Unreal's spawn phase, after import and before evaluation.
     */
    void spawnPhase() {
        if (sequencer.server()) return;
        int now = CgFrameTime.frame(position.lastRange().current());
        for (int i = 0; i < spawnables.length; i++) {
            boolean wanted = spawnedTrack[i] < 0 || spawnedAt(definition.tracks().get(spawnedTrack[i]), now);
            if (wanted && spawnables[i] == null) {
                CgSequenceDefinition.Spawnable s = definition.spawnables().get(i);
                spawnable(i, sequencer.spawner(s.kind()).spawn(this, s, placement(i)));
            } else if (!wanted && spawnables[i] instanceof CgSequenceSpawned obj) {
                despawn(i, obj, true);
            }
        }
    }

    private static boolean spawnedAt(CgSequenceTrack track, int tick) {
        for (CgSequenceSection s : track.sections()) {
            if (!s.active() || !s.contains(tick)) continue;
            CgSequenceChannel c = s.channel("spawned");
            return c != null && c.bool(tick);
        }
        return false;
    }

    /** Ends spawnable {@code i}: its tracks unbind, to bind again to what is made next. */
    private void despawn(int i, CgSequenceSpawned obj, boolean graceful) {
        obj.destroy(graceful);
        spawnables[i] = null;
        for (CgSequenceTrack t : definition.tracks()) {
            if (t.binding().subject() != CgSequenceBinding.Subject.SPAWNABLE || t.binding().index() != i) continue;
            Arrays.fill(bound[t.index()], false);
            Arrays.fill(data[t.index()], null);
        }
        segment = -1;
    }

    /** Ends every spawned object, as Unreal's spawn register does at a play's end ({@code CleanUp}). */
    void cleanUp() {
        for (int i = 0; i < spawnables.length; i++) {
            if (spawnables[i] instanceof CgSequenceSpawned obj) despawn(i, obj, true);
        }
    }

    /** Each spawned object's {@link CgSequenceSpawned#update}, after the frame's tracks are applied. */
    void updateSpawned() {
        for (CgSequenceTarget s : spawnables) {
            if (s instanceof CgSequenceSpawned obj) obj.update();
        }
    }

    /** The object spawnable {@code index} is now, or null. */
    @Nullable
    public CgSequenceTarget spawned(int index) {
        return spawnables[index];
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
                CgSequenceSection section = s < 0 ? null : track.sections().get(s);
                if (section != null && section.trigger() != null && !sequencer.evaluator(evaluator).playsTriggered()) {
                    throw new CgAssetException(definition.id() + ": tracks." + track.id() + ".sections." + section.id()
                            + ": a " + track.kind() + " section started by an event is not played yet");
                }
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

    /**
     * Advance: its range from its last time to {@code dt} seconds on. Where a montage section ends inside the frame,
     * play stops at its end and the rest is {@link #resumes() played on} from its next section's start in another
     * pass, as Unreal's montage sub-steps: a section with none holds play at its end.
     */
    CgSequenceEvaluationRange advance(float dt) {
        inPass = true;
        if (jump) {
            jump = false;
            CgSequenceEvaluationRange r = position.jumpToSeconds(seconds);
            int now = CgFrameTime.frame(r.current());
            oneShotFrom = seek ? now - (int) Math.round(ONE_SHOT_WINDOW / definition.seconds(1)) + 1 : now + 1;
            seek = false;
            composite = compositeAt(now);
            return r;
        }
        return play(seconds + dt * rate);
    }

    /** Whether a montage section handed play on this frame: the sequencer runs a pass for the rest. */
    boolean resumes() {
        return resumes;
    }

    /** The pass after a section's end: play from its next section's start for the seconds it handed on. */
    CgSequenceEvaluationRange resume() {
        inPass = true;
        resumes = false;
        if (resumeJumps) position.jumpToSeconds(seconds);
        return play(seconds + carry);
    }

    /** Whether it plays in the sequencer's current pass. */
    boolean inPass() {
        return inPass;
    }

    void endPass() {
        inPass = false;
    }

    private CgSequenceEvaluationRange play(double target) {
        double length = definition.seconds(definition.end() - definition.start());
        int at = CgFrameTime.frame(position.lastRange().isSet() ? position.lastRange().current() : position.currentTicks());
        // Held at a section's end, play is still in that section.
        int in = compositeAt(at);
        if (in >= 0 || !finished) composite = in;
        if (composite >= 0 && rate > 0 && !finished) {
            CgSequenceDefinition.CompositeSection c = definition.compositeSections().get(composite);
            double end = definition.seconds(c.to() - definition.start());
            if (target >= end) {
                int n = next[composite];
                if (n < 0) {
                    // FAnimMontageInstance::Advance: no next section, so play stops at this one's end.
                    seconds = end;
                    finished = true;
                    return position.playToSeconds(end);
                }
                CgSequenceDefinition.CompositeSection to = definition.compositeSections().get(n);
                carry = target - end;
                resumes = true;
                resumeJumps = to.from() != c.to();
                CgSequenceEvaluationRange r = position.playToSeconds(end);
                seconds = definition.seconds(to.from() - definition.start());
                return r;
            }
        }
        seconds = target;
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
            if (field.section(e) >= 0 && !conditionHolds(track, track.sections().get(field.section(e)))) continue;
            active[activeCount] = e;
            activeEvaluator[activeCount++] = evaluator;
        }
    }

    /**
     * Whether {@code section}'s condition lets it play: tested at its first entry and kept for the play, as Unreal's
     * {@code Once} conditions at import (FEntityLedger::CanImportEntity). A server plays no conditional section.
     */
    private boolean conditionHolds(CgSequenceTrack track, CgSequenceSection section) {
        if (section.condition() == null) return true;
        if (sequencer.server()) return false;
        byte[] answers = conditions[track.index()];
        int at = section.index() + 1;
        if (answers[at] == 0) answers[at] = (byte) (section.condition().test(conditionReader) ? 1 : 2);
        return answers[at] == 1;
    }

    /** Evaluate: each active entry of evaluator {@code evaluator}'s kind, then its triggered sections running. */
    void evaluate(CgSequencer sequencer, int evaluator) {
        if (!inPass) return;
        CgSequenceField field = definition.field();
        CgSequenceEvaluator e = sequencer.evaluator(evaluator);
        float tick = tick();
        for (int k = 0; k < activeCount; k++) {
            if (activeEvaluator[k] != evaluator) continue;
            CgSequenceTrack track = definition.tracks().get(field.track(active[k]));
            int section = field.section(active[k]);
            e.evaluate(this, track, section < 0 ? null : track.sections().get(section), tick);
        }
        int now = (int) Math.floor(tick);
        for (int i = 0; i < triggered.length; i++) {
            if (!running[i]) continue;
            CgSequenceTrack track = definition.tracks().get(triggered[i].track());
            if (triggeredEvaluator[i] < 0) triggeredEvaluator[i] = sequencer.evaluatorIndex(track.kind());
            if (triggeredEvaluator[i] != evaluator) continue;
            CgSequenceSection s = track.sections().get(triggered[i].section());
            // Bound with its track: a spawnable not made yet, or a skipped role, plays nothing.
            if (!bound[track.index()][track.population() == CgSequenceTrack.Population.WHOLE_TRACK ? 0 : s.index() + 1]) continue;
            int local = triggers[i].local(now);
            if (s.to() != CgSequenceSection.OPEN_END && local >= s.to()) {
                running[i] = false;
                e.ended(this, track, s);
            } else if (local >= s.from() || s.from() == CgSequenceSection.OPEN_START) {
                e.evaluate(this, track, s, tick - (now - local));
            }
        }
    }

    @Override
    public String toString() {
        return "player of " + definition.id() + " at " + seconds + " s";
    }
}
