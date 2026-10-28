package com.crystalgraphics.sequence.binding;

import java.util.Arrays;

import com.crystalgraphics.animation.CgAnimInstance;
import com.crystalgraphics.sequence.CgSequenceTarget;

/**
 * A role's rim: the material contract's {@code rim_strength}, {@code rim_power} and {@code rim} colour, written onto
 * the role's own materials by the parameter names its rig's {@code rim} gives. Kakarot's
 * {@code ATAnimNotifyState_FresnelColor}: its setter writes {@code FresnelInfluence}, {@code FresnelPower} and
 * {@code FresnelColor} on each of the character's dynamic materials.
 *
 * <pre>{@code
 * CgRoleRim rim = new CgRoleRim(goku, "FresnelInfluence", "FresnelPower", "FresnelColor");   // his own materials set
 *
 * // the role answers the contract through it
 * @Override public int slot(String name) { return rim.slot(name); }
 * @Override public void set(int slot, float[] values, int mask) { rim.set(slot, values, mask); }
 * @Override public void get(int slot, float[] out) { rim.get(slot, out); }
 * }</pre>
 *
 * <ul>
 *   <li>Unkeyed, each material keeps its own value (a hair instance overrides its master's). {@link #get} reads an
 *       unkeyed slot as NaN, and NaN written back puts every material to its own: so {@code restore_state} returns
 *       each to its own, never one value for all.</li>
 *   <li>An eased or additive section over an unkeyed slot blends with NaN, so its materials keep their own until a
 *       section keys it at full weight. Kakarot's fresnel sections key constants at full weight.</li>
 *   <li>A material that does not declare a name is left alone.</li>
 * </ul>
 */
public final class CgRoleRim implements CgSequenceTarget {

    /** The contract's slot names, in slot order. */
    public static final String STRENGTH = "rim_strength", POWER = "rim_power", COLOUR = "rim";

    private final CgAnimInstance instance;
    private final String strength, power, colour;
    /** Per slot, what was keyed, NaN while unkeyed. */
    private final float[][] keyed = new float[3][4];

    /**
     * @param instance the role's skinned instance, its own materials already set
     * @param strength the scalar for {@code rim_strength} (Kakarot's {@code FresnelInfluence})
     * @param power    the scalar for {@code rim_power} ({@code FresnelPower})
     * @param colour   the vector for {@code rim} ({@code FresnelColor})
     */
    public CgRoleRim(CgAnimInstance instance, String strength, String power, String colour) {
        this.instance = instance;
        this.strength = strength;
        this.power = power;
        this.colour = colour;
        for (float[] k : keyed) Arrays.fill(k, Float.NaN);
    }

    @Override
    public int slot(String name) {
        return switch (name) {
            case STRENGTH -> 0;
            case POWER -> 1;
            case COLOUR -> 2;
            default -> -1;
        };
    }

    @Override
    public void set(int slot, float[] values, int mask) {
        float[] k = keyed[slot];
        for (int i = 0; i < 4; i++) if ((mask & 1 << i) != 0) k[i] = values[i];
        if (slot == 2) {
            if (Float.isNaN(k[0])) instance.resetVector(colour);
            else instance.vector(colour, k[0], k[1], k[2], 1f);
        } else {
            String name = slot == 0 ? strength : power;
            if (Float.isNaN(k[0])) instance.resetScalar(name);
            else instance.scalar(name, k[0]);
        }
    }

    @Override
    public void get(int slot, float[] out) {
        System.arraycopy(keyed[slot], 0, out, 0, 4);
    }
}
