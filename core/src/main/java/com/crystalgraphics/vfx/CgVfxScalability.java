package com.crystalgraphics.vfx;

import java.util.Objects;

/**
 * When an effect is too far, too many or out of view to be worth playing, and what then happens to it: Niagara's
 * effect types, a look's to share. Distance is from the camera to the effect's bounds, not its origin, so a beam a
 * hundred blocks long stays while any of it is near. Every look has one: {@link #DEFAULT} unless it names another.
 *
 * <pre>{@code
 * public static final CgVfxScalability IMPACTS = CgVfxScalability.builder("mymod:impacts")
 *         .maxDistance(64).maxInstances(24).whenCulled(CgVfxScalability.Reaction.KILL).build();
 *
 * CgVfxLook.builder(SCHEMA).with(KIT).scalability(IMPACTS).build();        // every effect playing the look
 * VFX.play(new Aura(LOOK, x, y, z)).scalability(CgVfxScalability.HERO);   // one effect: the player's own
 * }</pre>
 *
 * <ul>
 *   <li>{@link #maxInstances} counts every look sharing this object, {@link #maxPerLook} each look alone; past either the
 *       farthest are culled. Effects following the local player are kept whatever the count.</li>
 *   <li>A sleeping effect ({@link Reaction#SLEEP}) neither ticks nor draws, and its particles are freed. When it is
 *       wanted again it plays on from its true age, as {@code startAt} starts one late. Asleep past
 *       {@link #MAX_SLEEP_SECONDS} it is killed, so a one-off culled for good never lingers.</li>
 *   <li>Past {@link #lodDistance} an effect draws a quality tier lower and spawns half its particles; past twice that,
 *       two tiers and a quarter. What is detail is the look's: layers' {@code from(q)}, emitters' {@code optional()}.</li>
 *   <li>Under load every distance and cap shrinks together (the system's pressure), down to a quarter.</li>
 *   <li>A server's copy of an authoritative effect is never culled.</li>
 * </ul>
 */
public final class CgVfxScalability {

    /** What a culled effect does. */
    public enum Reaction {
        /** Held where it is, drawing and ticking nothing, its particles freed; it plays on from its true age when wanted. */
        SLEEP,
        /** Ends its own way ({@code stop()}): what is in flight plays out, unseen. */
        STOP,
        /** Ends at once ({@code kill()}). */
        KILL
    }

    /** How long an effect may sleep before it is killed. */
    public static final float MAX_SLEEP_SECONDS = 120f;

    /** Most effects: gone past 160 blocks, a tier lower past 48. Asleep while culled. */
    public static final CgVfxScalability DEFAULT = builder("crystalgraphics:default").maxDistance(160f).lodDistance(48f).build();
    /** Background effects (embers, mist, a waterfall's spray): culled after two seconds out of view, near, few. */
    public static final CgVfxScalability AMBIENT = builder("crystalgraphics:ambient").maxDistance(64f).lodDistance(24f)
            .maxInstances(64).unseen(2f).build();
    /** Brief and many (hits, sparks, puffs): never more than 48, killed rather than kept asleep. */
    public static final CgVfxScalability IMPACT = builder("crystalgraphics:impact").maxDistance(96f).lodDistance(32f)
            .maxInstances(48).whenCulled(Reaction.KILL).build();
    /** Seen from afar (a blast on the horizon, a storm): kept to 512 blocks, a tier lower past 96. */
    public static final CgVfxScalability FAR = builder("crystalgraphics:far").maxDistance(512f).lodDistance(96f).build();
    /** Never culled and always full detail: the player's own abilities, a boss. */
    public static final CgVfxScalability HERO = builder("crystalgraphics:hero").build();

    private final String name;
    final float maxDistance, lodDistance, unseen;
    final int maxInstances, maxPerLook;
    final Reaction reaction;

    private CgVfxScalability(Builder b) {
        name = b.name;
        maxDistance = b.maxDistance;
        lodDistance = b.lodDistance;
        unseen = b.unseen;
        maxInstances = b.maxInstances;
        maxPerLook = b.maxPerLook;
        reaction = b.reaction;
    }

    /** Named for a log, a tool and a count ({@code vfx.culled/<name>}). */
    public static Builder builder(String name) {
        return new Builder(name);
    }

    public String name() {
        return name;
    }

    public float maxDistance() {
        return maxDistance;
    }

    public float lodDistance() {
        return lodDistance;
    }

    public float unseen() {
        return unseen;
    }

    public int maxInstances() {
        return maxInstances;
    }

    public int maxPerLook() {
        return maxPerLook;
    }

    public Reaction reaction() {
        return reaction;
    }

    /** Whether anything about it ever culls or lowers an effect: {@link #HERO} answers no. */
    boolean culls() {
        return maxDistance < Float.POSITIVE_INFINITY || unseen < Float.POSITIVE_INFINITY || maxInstances < Integer.MAX_VALUE
                || maxPerLook < Integer.MAX_VALUE || lodDistance < Float.POSITIVE_INFINITY;
    }

    @Override
    public String toString() {
        return name;
    }

    /** Unbounded but for what is set. */
    public static final class Builder {

        private final String name;
        private float maxDistance = Float.POSITIVE_INFINITY, lodDistance = Float.POSITIVE_INFINITY;
        private float unseen = Float.POSITIVE_INFINITY;
        private int maxInstances = Integer.MAX_VALUE, maxPerLook = Integer.MAX_VALUE;
        private Reaction reaction = Reaction.SLEEP;

        private Builder(String name) {
            this.name = Objects.requireNonNull(name, "name");
        }

        /** Culled once the camera is farther than {@code blocks} from every part of it. */
        public Builder maxDistance(float blocks) {
            maxDistance = positive(blocks, "maxDistance");
            return this;
        }

        /** A quality tier lower past {@code blocks}, and two past twice that. */
        public Builder lodDistance(float blocks) {
            lodDistance = positive(blocks, "lodDistance");
            return this;
        }

        /** Culled once nothing of it has been in view for {@code seconds}. */
        public Builder unseen(float seconds) {
            unseen = positive(seconds, "unseen");
            return this;
        }

        /** At most {@code n} playing of every look sharing this, the nearest kept. */
        public Builder maxInstances(int n) {
            if (n < 1) throw new IllegalArgumentException(name + ": maxInstances(" + n + ")");
            maxInstances = n;
            return this;
        }

        /** At most {@code n} playing of any one look, the nearest kept. */
        public Builder maxPerLook(int n) {
            if (n < 1) throw new IllegalArgumentException(name + ": maxPerLook(" + n + ")");
            maxPerLook = n;
            return this;
        }

        /** What a culled effect does: {@link Reaction#SLEEP} unless given. */
        public Builder whenCulled(Reaction reaction) {
            this.reaction = Objects.requireNonNull(reaction, "reaction");
            return this;
        }

        public CgVfxScalability build() {
            return new CgVfxScalability(this);
        }

        private float positive(float v, String what) {
            if (!(v > 0f)) throw new IllegalArgumentException(name + ": " + what + "(" + v + ")");
            return v;
        }
    }
}
