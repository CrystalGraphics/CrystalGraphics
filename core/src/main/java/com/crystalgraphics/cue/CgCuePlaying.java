package com.crystalgraphics.cue;

import java.util.Arrays;

import javax.annotation.Nullable;

import com.crystalgraphics.cue.CgCueDefinition.Write;
import com.crystalgraphics.net.object.CgNetObject;
import com.crystalgraphics.net.object.CgPredictionKey;
import com.crystalgraphics.sequence.CgSequencePlayer;

/**
 * One looping cue playing, from {@link CgCueManager#add}: what gameplay holds to feed it, fire its events and end it.
 * Mirrors GAS's {@code WhileActive} to {@code Removed} on an instanced {@code AGameplayCueNotify_Looping}.
 *
 * <pre>{@code
 * CgCuePlaying aura = cues.add(MYMOD_CUE_AURA, params);
 * aura.writes("BeamLength", (time, out) -> out[0] = beam.lengthAt(time));   // pulled each frame, never pushed
 * aura.event("hit_world", hitParams);                                       // the cue's events burst
 * aura.remove();            // removal plays, then it lingers until its effects end
 * }</pre>
 *
 * <ul>
 *   <li>Every call is queued and played in {@code PLAYS}, in order, so a remove right after an add is never lost.</li>
 *   <li>The handle is pooled: once {@link #active()} is false it may already be another play's. Drop it then.</li>
 *   <li>A provider is called on the frame thread with the frame's time in seconds; it writes up to four floats.</li>
 *   <li>It is still called while removal lingers, for what plays on this machine, and no longer sent to viewers.</li>
 * </ul>
 */
public final class CgCuePlaying {

    /** A {@code writes} value, pulled each frame. */
    @FunctionalInterface
    public interface Provider {
        /** Writes the value at {@code time}, the frame's presented time in seconds, into {@code out}'s first floats. */
        void write(double time, float[] out);
    }

    enum State { PENDING, PLAYING, REMOVING, KILLED, ENDED }

    private static final int PROVIDERS = 8;

    private final CgCueManager manager;
    State state = State.ENDED;
    @Nullable
    CgCueDefinition definition;
    @Nullable
    CgCueEffects.Lasting lasting;
    final CgCueParameters params = new CgCueParameters();
    /** What it travels as: the server's object, or the one it was sent as on a client. */
    @Nullable
    CgNetObject object;
    /** Whether its {@code writes} go to viewers: an authoritative cue on the server. */
    boolean sends;
    @Nullable
    CgSequencePlayer sequence;
    /** The input's key it was predicted under, on its predicting client; null otherwise. */
    @Nullable
    CgPredictionKey key;
    private int[] slots = new int[8];
    private String[] names = new String[PROVIDERS];
    private Provider[] providers = new Provider[PROVIDERS];
    private Write[] bound = new Write[PROVIDERS];
    private int count;
    boolean discarded;

    CgCuePlaying(CgCueManager manager) {
        this.manager = manager;
    }

    /** Binds {@code name}, one of the cue's {@code writes}, to {@code provider}; a name the cue does not write is kept unused. */
    public CgCuePlaying writes(String name, Provider provider) {
        for (int i = 0; i < count; i++) {
            if (names[i].equals(name)) {
                providers[i] = provider;
                return this;
            }
        }
        if (count == names.length) {
            names = Arrays.copyOf(names, count * 2);
            providers = Arrays.copyOf(providers, count * 2);
            bound = Arrays.copyOf(bound, count * 2);
            slots = Arrays.copyOf(slots, count * 2);
        }
        names[count] = name;
        providers[count] = provider;
        bound[count] = definition == null ? null : definition.writes().get(name);
        slots[count] = definition == null ? -1 : index(definition, name);
        count++;
        return this;
    }

    /** Bursts the cue's event called {@code name} with {@code at}. */
    public CgCuePlaying event(String name, CgCueParameters at) {
        manager.queue(CgCueManager.Op.EVENT, null, this, name, at);
        return this;
    }

    /** Bursts its {@code recurring} block with {@code at}. */
    public CgCuePlaying recur(CgCueParameters at) {
        manager.queue(CgCueManager.Op.RECUR, null, this, null, at);
        return this;
    }

    /** Plays {@code removal} and stops {@code looping}; it lingers until what it played ends. */
    public void remove() {
        manager.queue(CgCueManager.Op.REMOVE, null, this, null, params);
    }

    /** Ends it now, playing no {@code removal}. */
    public void kill() {
        manager.queue(CgCueManager.Op.KILL, null, this, null, params);
    }

    /** A refused prediction's end: killed, and never counted as played. */
    public void discarded() {
        discarded = true;
        kill();
    }

    /**
     * Sets one of the cue's own {@code fields} for every viewer: a server's call, queued like the rest. A number, a
     * bool or a string, as the field's type.
     */
    public CgCuePlaying field(String name, Object value) {
        manager.queueField(this, name, value);
        return this;
    }

    /** The cue's own field {@code name} as this side last had it: a number field's value, a bool as 0 or 1. */
    public double number(String name) {
        if (object == null || definition == null) return 0;
        int values = 0;
        for (CgCueDefinition.Field f : definition.fields()) {
            if (f.type() == CgCueDefinition.Field.Type.STRING) continue;
            if (f.name().equals(name)) return object.getDouble(CgCueNet.V.get(values));
            values++;
        }
        throw new IllegalArgumentException(definition.id() + " has no number field " + name);
    }

    /** The sequence it plays as its timeline, once started; null for none. */
    @Nullable
    public CgSequencePlayer sequence() {
        return sequence;
    }

    /** Whether it is waiting to start, playing or lingering after its removal. */
    public boolean active() {
        return state == State.PENDING || state == State.PLAYING || state == State.REMOVING;
    }

    /** The cue handling it, once it started; null while it waits for its cue to load. */
    @Nullable
    public CgCueDefinition definition() {
        return definition;
    }

    void started(CgCueDefinition cue, CgCueEffects.Lasting effects) {
        definition = cue;
        lasting = effects;
        state = State.PLAYING;
        for (int i = 0; i < count; i++) {
            bound[i] = cue.writes().get(names[i]);
            slots[i] = index(cue, names[i]);
        }
    }

    private static int index(CgCueDefinition cue, String name) {
        int i = 0;
        for (String n : cue.writes().keySet()) {
            if (n.equals(name)) return i;
            i++;
        }
        return -1;
    }

    void stopSequence() {
        if (sequence != null) sequence.stop();
        sequence = null;
    }

    /** Each bound provider's value this frame, into its slots; to viewers too while {@code live}. */
    void pull(double time, float[] scratch, boolean live) {
        CgCueEffects.Lasting l = lasting;
        if (l == null) return;
        for (int i = 0; i < count; i++) {
            Write w = bound[i];
            if (w == null) continue;
            boolean draws = !w.slots().isEmpty(), send = live && sends && slots[i] >= 0;
            if (!draws && !send) continue;
            scratch[0] = scratch[1] = scratch[2] = scratch[3] = 0;
            providers[i].write(time, scratch);
            if (draws) l.write(w, scratch);
            if (send) CgCueNet.write(object, slots[i], scratch);
        }
    }

    /** Back to new, its providers unbound: a recycled handle inherits nothing. */
    void clear() {
        Arrays.fill(names, 0, count, null);
        Arrays.fill(providers, 0, count, null);
        Arrays.fill(bound, 0, count, null);
        count = 0;
        definition = null;
        lasting = null;
        object = null;
        key = null;
        sends = false;
        stopSequence();
        discarded = false;
        params.reset();
        state = State.ENDED;
    }
}
