package com.crystalgraphics.vfx.scalability;

import javax.annotation.Nullable;
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
 * <p>Under the CPU's budget ({@link CgVfxBudget}) its limits scale by its ramps, each read at the budget's adjusted
 * usage, as Niagara's global budget scaling does; each is off unless given:
 *
 * <pre>{@code
 * public static final CgVfxScalability AMBIENT_FX = CgVfxScalability.builder("mymod:ambient")
 *         .maxDistance(64).maxInstances(32)
 *         .scaleDistanceByBudget(CgVfxLinearRamp.DEFAULT)              // shorter as the budget fills
 *         .scaleInstancesByBudget(new CgVfxLinearRamp(1f, 1f, 2f, 0.25f))
 *         .cullByBudget(1.5f)                                           // gone past half again over it
 *         .build();
 * }</pre>
 *
 * <ul>
 *   <li>{@link #maxInstances} counts every look sharing this object, {@link #maxPerLook} each look alone; past either the
 *       farthest are culled. Effects following the local player are kept whatever the count or the budget.</li>
 *   <li>A sleeping effect ({@link Reaction#SLEEP}) neither ticks nor draws, and its particles are freed. When it is
 *       wanted again it plays on from its true age, as {@code startAt} starts one late. Asleep past
 *       {@link #MAX_SLEEP_SECONDS} it is killed, so a one-off culled for good never lingers.</li>
 *   <li>Past {@link #lodDistance} an effect draws a quality tier lower and spawns half its particles; past twice that,
 *       two tiers and a quarter. What is detail is the look's: layers' {@code from(q)}, emitters' {@code optional()}.
 *       The budget never moves it: Niagara's budget scaling culls, it does not lower.</li>
 *   <li>A ramp scales a limit the scalability has: one on an unbounded distance or count does nothing.</li>
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

    /** The presets' budget ramps: nothing until over budget, a quarter at the most the adjusted usage reaches. */
    private static final CgVfxLinearRamp PRESET_RAMP = new CgVfxLinearRamp(1f, 1f, CgVfxBudget.MAX_USAGE, 0.25f);

    /** Most effects: gone past 160 blocks, a tier lower past 48. Asleep while culled. */
    public static final CgVfxScalability DEFAULT = preset("crystalgraphics:default").maxDistance(160f).lodDistance(48f).build();
    /** Background effects (embers, mist, a waterfall's spray): culled after two seconds out of view, near, few. */
    public static final CgVfxScalability AMBIENT = preset("crystalgraphics:ambient").maxDistance(64f).lodDistance(24f)
            .maxInstances(64).unseen(2f).build();
    /** Brief and many (hits, sparks, puffs): never more than 48, killed rather than kept asleep. */
    public static final CgVfxScalability IMPACT = preset("crystalgraphics:impact").maxDistance(96f).lodDistance(32f)
            .maxInstances(48).whenCulled(Reaction.KILL).build();
    /** Seen from afar (a blast on the horizon, a storm): kept to 512 blocks, a tier lower past 96. */
    public static final CgVfxScalability FAR = preset("crystalgraphics:far").maxDistance(512f).lodDistance(96f).build();
    /** Never culled and always full detail: the player's own abilities, a boss. */
    public static final CgVfxScalability HERO = builder("crystalgraphics:hero").build();

    private final String name;
    private final float maxDistance, lodDistance, unseen, cullUsage;
    private final int maxInstances, maxPerLook;
    private final Reaction reaction;
    @Nullable
    private final CgVfxLinearRamp distanceRamp, instanceRamp, perLookRamp;

    private CgVfxScalability(Builder b) {
        name = b.name;
        maxDistance = b.maxDistance;
        lodDistance = b.lodDistance;
        unseen = b.unseen;
        maxInstances = b.maxInstances;
        maxPerLook = b.maxPerLook;
        reaction = b.reaction;
        cullUsage = b.cullUsage;
        distanceRamp = b.distanceRamp;
        instanceRamp = b.instanceRamp;
        perLookRamp = b.perLookRamp;
    }

    /** Named for a log, a tool and a count ({@code vfx.culled/<name>}). */
    public static Builder builder(String name) {
        return new Builder(name);
    }

    private static Builder preset(String name) {
        return builder(name).scaleDistanceByBudget(PRESET_RAMP).scaleInstancesByBudget(PRESET_RAMP)
                .scalePerLookByBudget(PRESET_RAMP);
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

    /** The budget usage at which its effects are culled, or infinity where the budget culls none. */
    public float cullUsage() {
        return cullUsage;
    }

    /** What its distances are multiplied by at {@code usage}: 1 without a distance ramp. */
    public float distanceScale(float usage) {
        return distanceRamp != null ? distanceRamp.at(usage) : 1f;
    }

    /** What {@link #maxInstances} is multiplied by at {@code usage}: 1 without an instance ramp. */
    public float instanceScale(float usage) {
        return instanceRamp != null ? instanceRamp.at(usage) : 1f;
    }

    /** What {@link #maxPerLook} is multiplied by at {@code usage}: 1 without a ramp for it. */
    public float perLookScale(float usage) {
        return perLookRamp != null ? perLookRamp.at(usage) : 1f;
    }

    /** Whether anything about it ever culls or lowers an effect: {@link #HERO} answers no. */
    public boolean culls() {
        return maxDistance < Float.POSITIVE_INFINITY || unseen < Float.POSITIVE_INFINITY || maxInstances < Integer.MAX_VALUE
                || maxPerLook < Integer.MAX_VALUE || lodDistance < Float.POSITIVE_INFINITY
                || cullUsage < Float.POSITIVE_INFINITY;
    }

    @Override
    public String toString() {
        return name;
    }

    /** Unbounded but for what is set. */
    public static final class Builder {

        private final String name;
        private float maxDistance = Float.POSITIVE_INFINITY, lodDistance = Float.POSITIVE_INFINITY;
        private float unseen = Float.POSITIVE_INFINITY, cullUsage = Float.POSITIVE_INFINITY;
        private int maxInstances = Integer.MAX_VALUE, maxPerLook = Integer.MAX_VALUE;
        private Reaction reaction = Reaction.SLEEP;
        @Nullable
        private CgVfxLinearRamp distanceRamp, instanceRamp, perLookRamp;

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

        /** Culled while the budget's adjusted usage is at {@code usage} or past it: Niagara's {@code MaxGlobalBudgetUsage}. */
        public Builder cullByBudget(float usage) {
            cullUsage = positive(usage, "cullByBudget");
            return this;
        }

        /** {@link #maxDistance} times {@code ramp} at the budget's adjusted usage. */
        public Builder scaleDistanceByBudget(CgVfxLinearRamp ramp) {
            distanceRamp = Objects.requireNonNull(ramp, "ramp");
            return this;
        }

        /** {@link #maxInstances} times {@code ramp} at the budget's adjusted usage. */
        public Builder scaleInstancesByBudget(CgVfxLinearRamp ramp) {
            instanceRamp = Objects.requireNonNull(ramp, "ramp");
            return this;
        }

        /** {@link #maxPerLook} times {@code ramp} at the budget's adjusted usage: Niagara's per-system count. */
        public Builder scalePerLookByBudget(CgVfxLinearRamp ramp) {
            perLookRamp = Objects.requireNonNull(ramp, "ramp");
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
