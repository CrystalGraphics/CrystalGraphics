package com.crystalgraphics.sequence.binding;

import java.util.Arrays;

import javax.annotation.Nullable;

import com.crystalgraphics.animation.CgAnimInstance;
import com.crystalgraphics.sequence.CgSequenceTarget;

/**
 * A role's tint and flash: the material contract's {@code tint} and {@code flash} colours and their
 * {@code tint_strength} and {@code flash_strength}, written onto the role's own materials by the parameter names it is
 * given. The flash is what Minecraft's overlay draws (the hurt flash, red at 0.3); the tint multiplies.
 *
 * <pre>{@code
 * // the entity look (crystalgraphics:shaders/role/entity.shader): each strength rides its colour's alpha
 * CgRoleFlash flash = new CgRoleFlash(role, "_Tint", null, "_Flash", null);
 *
 * // a material with scalars of its own for the strengths
 * CgRoleFlash flash = new CgRoleFlash(role, "TintColor", "TintAmount", "FlashColor", "FlashAmount");
 *
 * // the role answers the contract through it, beside its rim
 * @Override public int slot(String name) {
 *     int s = flash.slot(name);
 *     return s >= 0 ? s : rim.slot(name) < 0 ? -1 : 8 + rim.slot(name);
 * }
 * }</pre>
 *
 * <ul>
 *   <li>Unkeyed, each material keeps its own value. {@link #get} reads an unkeyed slot as NaN, and NaN written back puts
 *       every material to its own, so {@code restore_state} returns each to its own.</li>
 *   <li>A strength riding its colour's alpha is written with the colour: keying the strength alone, the colour's
 *       unkeyed rgb is white.</li>
 *   <li>A material that does not declare a name is left alone.</li>
 * </ul>
 */
public final class CgRoleFlash implements CgSequenceTarget {

    /** The contract's slot names, in slot order. */
    public static final String TINT = "tint", TINT_STRENGTH = "tint_strength", FLASH = "flash",
            FLASH_STRENGTH = "flash_strength";

    private final CgAnimInstance instance;
    private final String tint, flash;
    @Nullable
    private final String tintStrength, flashStrength;
    /** Per slot, what was keyed, NaN while unkeyed. */
    private final float[][] keyed = new float[4][4];

    /**
     * @param instance      the role's skinned instance, its own materials already set
     * @param tint          the vector for {@code tint}
     * @param tintStrength  the scalar for {@code tint_strength}; null to write it as {@code tint}'s alpha
     * @param flash         the vector for {@code flash}
     * @param flashStrength the scalar for {@code flash_strength}; null to write it as {@code flash}'s alpha
     */
    public CgRoleFlash(CgAnimInstance instance, String tint, @Nullable String tintStrength, String flash,
                       @Nullable String flashStrength) {
        this.instance = instance;
        this.tint = tint;
        this.tintStrength = tintStrength;
        this.flash = flash;
        this.flashStrength = flashStrength;
        for (float[] k : keyed) Arrays.fill(k, Float.NaN);
    }

    @Override
    public int slot(String name) {
        return switch (name) {
            case TINT -> 0;
            case TINT_STRENGTH -> 1;
            case FLASH -> 2;
            case FLASH_STRENGTH -> 3;
            default -> -1;
        };
    }

    @Override
    public void set(int slot, float[] values, int mask) {
        float[] k = keyed[slot];
        for (int i = 0; i < 4; i++) if ((mask & 1 << i) != 0) k[i] = values[i];
        if (slot < 2) write(tint, tintStrength, keyed[0], keyed[1]);
        else write(flash, flashStrength, keyed[2], keyed[3]);
    }

    private void write(String colour, @Nullable String strength, float[] c, float[] s) {
        if (strength != null) {
            if (Float.isNaN(c[0])) instance.resetVector(colour);
            else instance.vector(colour, c[0], c[1], c[2], 1f);
            if (Float.isNaN(s[0])) instance.resetScalar(strength);
            else instance.scalar(strength, s[0]);
        } else if (Float.isNaN(c[0]) && Float.isNaN(s[0])) {
            instance.resetVector(colour);
        } else {
            boolean rgb = !Float.isNaN(c[0]);
            instance.vector(colour, rgb ? c[0] : 1f, rgb ? c[1] : 1f, rgb ? c[2] : 1f, Float.isNaN(s[0]) ? 1f : s[0]);
        }
    }

    @Override
    public void get(int slot, float[] out) {
        System.arraycopy(keyed[slot], 0, out, 0, 4);
    }
}
