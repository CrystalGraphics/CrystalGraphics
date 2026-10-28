package com.crystalgraphics.sequence.notify;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import com.crystalgraphics.object.CgType;

/**
 * A kind of notify a mod registers: Unreal's {@code UAnimNotify} and {@code UAnimNotifyState}, with its properties
 * declared as data and what it changes declared as slots. A {@code notify} track's sections name a kind; a trigger
 * section ({@code at}) fires it once as play crosses its tick, a span section ({@code range}) begins, ticks and ends.
 *
 * <pre>{@code
 * static final CgType GLOW = CgTypes.struct("mymod:notify.glow")
 *         .property("colour", CgValueType.COLOUR).required()
 *         .property("strength", CgValueType.FLOAT).defaultValue(1.0)
 *         .register();
 *
 * CgNotifyKind.builder("mymod:glow").properties(GLOW).writes("tint")
 *         .behaviour(new CgNotifyKind.Behaviour() {
 *             @Override public void begin(CgNotifyContext c) {
 *                 c.set(0, new float[] {1, 0.8f, 0.2f, 1}, 0b1111);   // slot 0, "tint": held while the span lasts
 *             }
 *         })
 *         .register();
 *
 * CgNotifyKind.builder("mymod:puff")                                   // a trigger: one call as play crosses it
 *         .behaviour(new CgNotifyKind.Behaviour() {
 *             @Override public void notify(CgNotifyContext c) { spawnPuffAt(c.target()); }
 *         })
 *         .register();
 * }</pre>
 *
 * <ul>
 *   <li>Register before any sequence naming it loads: a section naming a kind no one registered fails at load, naming
 *       it; its {@code properties} validate against the kind's type as an asset's values do.</li>
 *   <li>What a span {@linkplain CgNotifyContext#set sets} on its declared slots is restored when the span ends, as a
 *       {@code restore_state} section's is: by playing past it, stopping, or seeking back before it.</li>
 *   <li>Behaviours run on the client, in {@code SEQUENCES}, for presentation; a dedicated server never runs one.
 *       Gameplay's moments are {@code event} sections of side {@code rpg}.</li>
 *   <li>A queued notify (the default) is called after the frame's evaluation; a {@code branching_point} one inside it,
 *       on its exact tick.</li>
 * </ul>
 */
public final class CgNotifyKind {

    /** What a notify does. Every method defaults to nothing. */
    public interface Behaviour {
        /** A trigger section, as play crosses its tick: {@code UAnimNotify::Notify}. */
        default void notify(CgNotifyContext c) {
        }

        /** A span section, as time enters it: {@code UAnimNotifyState::NotifyBegin}. */
        default void begin(CgNotifyContext c) {
        }

        /** A span section, each frame inside it, {@code dt} the seconds of sequence time since the last. */
        default void tick(CgNotifyContext c, float dt) {
        }

        /** A span section, as time leaves it, forwards, backwards or by a seek. */
        default void end(CgNotifyContext c) {
        }
    }

    private static final Map<String, CgNotifyKind> KINDS = new ConcurrentHashMap<>();

    private final String id;
    @Nullable
    private final CgType properties;
    private final List<String> writes;
    private final Behaviour behaviour;

    private CgNotifyKind(String id, @Nullable CgType properties, List<String> writes, Behaviour behaviour) {
        this.id = id;
        this.properties = properties;
        this.writes = writes;
        this.behaviour = behaviour;
    }

    public static Builder builder(String id) {
        return new Builder(id);
    }

    /** The kind registered as {@code id}, or null. */
    @Nullable
    public static CgNotifyKind get(String id) {
        return KINDS.get(id);
    }

    public String id() {
        return id;
    }

    /** Its properties' type, or null for a kind with none. */
    @Nullable
    public CgType properties() {
        return properties;
    }

    /** The slots it changes on its section's bound target, by name: index {@code i} is {@link CgNotifyContext#set}'s slot {@code i}. */
    public List<String> writes() {
        return writes;
    }

    public Behaviour behaviour() {
        return behaviour;
    }

    /** Declares a kind: its id, its properties, the slots it writes, its behaviour. */
    public static final class Builder {
        private final String id;
        private CgType properties;
        private final List<String> writes = new ArrayList<>();
        private Behaviour behaviour = new Behaviour() {
        };

        private Builder(String id) {
            if (id.indexOf(':') < 0) throw new IllegalArgumentException("a notify kind is namespaced: " + id);
            this.id = id;
        }

        public Builder properties(CgType type) {
            this.properties = type;
            return this;
        }

        /** Slots of the bound target it changes, restored when its span ends. */
        public Builder writes(String... slots) {
            Collections.addAll(writes, slots);
            return this;
        }

        public Builder behaviour(Behaviour behaviour) {
            this.behaviour = behaviour;
            return this;
        }

        /** Registers it; an id already registered throws. */
        public CgNotifyKind register() {
            CgNotifyKind kind = new CgNotifyKind(id, properties, List.copyOf(writes), behaviour);
            if (KINDS.putIfAbsent(id, kind) != null) throw new IllegalStateException("notify kind " + id + " is already registered");
            return kind;
        }
    }
}
