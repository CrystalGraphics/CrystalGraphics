package com.crystalgraphics.world.actor;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import javax.annotation.Nullable;

import com.crystalgraphics.animation.graph.CgAnimGraph;
import com.crystalgraphics.animation.graph.CgAnimNode;
import com.crystalgraphics.animation.graph.CgAnimNodeKind;
import com.crystalgraphics.animation.graph.CgAnimVariable;
import com.crystalgraphics.animation.state.CgAnimRule;
import com.crystalgraphics.animation.sync.CgSyncGroup;
import com.crystalgraphics.api.animation.CgAnimSequence;
import com.crystalgraphics.asset.tree.CgAssetException;
import com.crystalgraphics.net.object.CgNetField;
import com.crystalgraphics.net.object.CgNetObject;
import com.crystalgraphics.object.CgType;
import com.crystalgraphics.world.CgEntityProperties;

/**
 * An entity event playing a sequence on its actor's body: a hit plays the flinch, a landing the landing. Unreal's
 * {@code PlaySlotAnimationAsDynamicMontage} from a gameplay event, as data. Each of the sequence's roles is the actor's
 * body, and every animation section plays on {@code slot}, whatever slot it names.
 *
 * <pre>{@code
 * CgEntityActors.register("mymod:golem", GolemActor::new).prefab("mymod:golem")
 *         .react(CgReaction.of(CgEntityProperties.HURT, "mymod:golem_flinch"))
 *         .react(CgReaction.of(CgEntityProperties.LANDED, "mymod:golem_land", "FullBody",
 *                 CgReaction.when(Map.of("variable", "entity.land_height", "op", ">", "value", 2.0))));
 * }</pre>
 *
 * <p>In an {@code actor} file:</p>
 * <pre>{@code
 * reactions:
 *   - { event: hurt, sequence: mymod:golem_flinch }
 *   - { event: landed, sequence: mymod:golem_land, slot: FullBody, when: { variable: entity.land_height, op: ">", value: 2 } }
 * }</pre>
 *
 * <ul>
 *   <li>It plays on each side that has the actor, from that side's own events: the server's play moves the entity
 *       where the sequence carries root motion, a client's draws it.</li>
 *   <li>A reaction played while the last one still plays stops it first.</li>
 *   <li>{@code when} is a graph rule ({@link CgAnimRule}) whose variables are entity properties by their graph names
 *       ({@code entity.grounded}, {@code mymod:ki#current}); clocks and transition events read as none.</li>
 * </ul>
 *
 * @param event    the event: one of {@link CgEntityProperties#MOTION}'s, a bool true for its frame
 * @param sequence the sequence's asset id
 * @param slot     the montage slot its animation plays on
 * @param when     what must hold as the event fires, or null for always
 */
public record CgReaction(CgNetField<Boolean> event, String sequence, String slot, @Nullable When when) {

    /** The engine's events a reaction may name, by their names. */
    private static final List<CgNetField<Boolean>> EVENTS = List.of(CgEntityProperties.JUMPED, CgEntityProperties.LANDED,
            CgEntityProperties.HURT, CgEntityProperties.SWING_STARTED, CgEntityProperties.USE_STARTED,
            CgEntityProperties.USE_STOPPED, CgEntityProperties.MOUNTED, CgEntityProperties.DISMOUNTED, CgEntityProperties.DIED);

    public CgReaction {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(sequence, "sequence");
        Objects.requireNonNull(slot, "slot");
        if (!EVENTS.contains(event)) throw new IllegalArgumentException(event.name() + " is no entity event");
    }

    /** {@code event} plays {@code sequence} on the default slot, always. */
    public static CgReaction of(CgNetField<Boolean> event, String sequence) {
        return new CgReaction(event, sequence, CgAnimGraph.DEFAULT_SLOT, null);
    }

    public static CgReaction of(CgNetField<Boolean> event, String sequence, String slot, @Nullable When when) {
        return new CgReaction(event, sequence, slot, when);
    }

    /**
     * The event named {@code name} ({@code hurt}, {@code landed}, ...).
     *
     * @throws IllegalArgumentException for any other
     */
    public static CgNetField<Boolean> event(String name) {
        for (CgNetField<Boolean> e : EVENTS) if (e.name().equals(name)) return e;
        throw new IllegalArgumentException(name + " is no entity event: jumped, landed, hurt, swing_started, use_started, "
                + "use_stopped, mounted, dismounted or died");
    }

    /**
     * A rule's data form over entity properties, as a reaction's {@code when}.
     *
     * @throws CgAssetException naming the rule's part that is not one, or a property no entity has
     */
    public static When when(Object tree) {
        return When.read("when", tree);
    }

    /** Whether it plays for {@code motion}'s entity this frame: its event fired, and its rule holds, read by {@code probe}. */
    boolean fires(CgNetObject motion, Probe probe) {
        return motion.getBool(event) && (when == null || probe.test(when));
    }

    /**
     * What a rule reads an entity through: one per actor, so a rule shared by the server's and a client's actors is
     * read on each side's thread without either touching the other's state. Allocates nothing.
     */
    static final class Probe implements CgAnimRule.Context {
        private final CgEntity entity;
        private When when;

        Probe(CgEntity entity) {
            this.entity = entity;
        }

        boolean test(When rule) {
            when = rule;
            return rule.rule.test(this);
        }

        @Override
        public float get(CgAnimVariable<?> variable) {
            CgAnimGraph.EntityBinding b = when.bindings.get(variable);
            CgNetObject o = entity.properties(b.type());
            return o == null ? b.field().fallbackNumber() : o.getNumber(b.field());
        }

        @Override
        public float timeRemaining() {
            return Float.MAX_VALUE;
        }

        @Override
        public float timeRemainingFraction() {
            return 1f;
        }

        @Override
        public float stateTime() {
            return 0f;
        }

        @Override
        public boolean event(String name) {
            return false;
        }
    }

    /** A rule over an entity's properties: each variable an {@link CgAnimGraph.EntityBinding} read off the entity. */
    public static final class When {

        private final CgAnimRule rule;
        private final Map<CgAnimVariable<?>, CgAnimGraph.EntityBinding> bindings;

        private When(CgAnimRule rule, Map<CgAnimVariable<?>, CgAnimGraph.EntityBinding> bindings) {
            this.rule = rule;
            this.bindings = bindings;
        }

        static When read(String at, Object tree) {
            Map<CgAnimVariable<?>, CgAnimGraph.EntityBinding> bindings = new IdentityHashMap<>();
            Map<String, CgAnimVariable<?>> byName = new HashMap<>();
            CgAnimRule rule = CgAnimRule.read(new Reader(bindings, byName), at, tree);
            return new When(rule, bindings);
        }

        /** The rule. */
        public CgAnimRule rule() {
            return rule;
        }
    }

    /** What reads a rule's variables as entity properties; nothing else of a graph is there to read. */
    private record Reader(Map<CgAnimVariable<?>, CgAnimGraph.EntityBinding> bindings, Map<String, CgAnimVariable<?>> byName)
            implements CgAnimNodeKind.Context {

        @Override
        public CgAnimVariable<?> variable(String at, String name) {
            CgAnimVariable<?> known = byName.get(name);
            if (known != null) return known;
            CgAnimGraph.EntityBinding b;
            try {
                b = CgAnimGraph.EntityBinding.named(name);
            } catch (IllegalArgumentException e) {
                throw new CgAssetException(at + ": " + e.getMessage());
            }
            CgAnimVariable<?> v = switch (b.field().kind()) {
                case BOOL -> CgAnimVariable.bool(name);
                case INT, LONG -> CgAnimVariable.integer(name);
                default -> CgAnimVariable.floating(name);
            };
            byName.put(name, v);
            bindings.put(v, b);
            return v;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> CgAnimVariable<T> variable(String at, String name, CgAnimVariable.Type type) {
            CgAnimVariable<?> v = variable(at, name);
            if (v.type() != type) throw new CgAssetException(at + ": " + name + " is not a " + type.name().toLowerCase(Locale.ROOT));
            return (CgAnimVariable<T>) v;
        }

        @Override
        public CgAnimNode pose(String name) {
            throw new CgAssetException("a reaction's rule reads no node");
        }

        @Override
        @Nullable
        public Map<?, ?> section(String key, String name) {
            return null;
        }

        @Override
        public String string(String at, Map<?, ?> m, String key) {
            if (!(m.get(key) instanceof String s)) throw new CgAssetException(at + "." + key + ": none");
            return s;
        }

        @Override
        public float number(String at, Object o) {
            if (!(o instanceof Number n)) throw new CgAssetException(at + ": " + o + " is not a number");
            return n.floatValue();
        }

        @Override
        public CgAnimSequence clip(String at, String id) {
            throw new CgAssetException(at + ": a reaction's rule reads no clip");
        }

        @Override
        public Object definition(String at, CgType kind, String id) {
            throw new CgAssetException(at + ": a reaction's rule reads no asset");
        }

        @Override
        @Nullable
        public CgSyncGroup syncGroup(String at, Map<?, ?> m) {
            return null;
        }
    }
}
