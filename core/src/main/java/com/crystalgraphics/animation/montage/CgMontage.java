package com.crystalgraphics.animation.montage;

import java.util.Objects;

import javax.annotation.Nullable;

import com.crystalgraphics.easing.CgEasing;
import com.crystalgraphics.easing.CgEasings;

/**
 * How a montage plays on a character: its blend in and out, when it blends out by itself, its slot group, whether it
 * moves the character and the sync group it leads. Unreal's {@code UAnimMontage} properties that its instance reads. A
 * sequence's {@code animation} track makes one from its sections; code may build one directly.
 *
 * <pre>{@code
 * CgMontage punch = CgMontage.DEFAULT.blendIn(0.1f, CgEasings.LINEAR).blendOut(0.2f, CgEasings.LINEAR);
 * CgMontage upper = CgMontage.DEFAULT.group("UpperBody");         // stops only montages of its own group
 * CgMontage hold = CgMontage.DEFAULT.autoBlendOut(false);         // holds its last pose until stopped
 * }</pre>
 *
 * <ul>
 *   <li>A trigger time below 0 is Unreal's -1: blend out over the time left, starting the blend-out time before the
 *       end.</li>
 *   <li>Unreal's montage blends are linear unless they name a curve; so are this record's defaults.</li>
 * </ul>
 *
 * @param group               its slot group: a montage played stops every other of its group ({@code DefaultGroup})
 * @param blendIn             seconds it blends in over
 * @param blendInCurve        its blend in's curve
 * @param blendOut            seconds it blends out over, stopped or ending
 * @param blendOutCurve       its blend out's curve
 * @param blendOutTriggerTime seconds before its end it starts blending out; below 0, its blend-out time
 * @param autoBlendOut        whether it blends out by itself at its end ({@code bEnableAutoBlendOut})
 * @param rootMotion          whether its clips' root motion moves the character
 * @param syncGroup           the sync group it leads while blending out, or null
 */
public record CgMontage(String group, float blendIn, CgEasing blendInCurve, float blendOut, CgEasing blendOutCurve,
                        float blendOutTriggerTime, boolean autoBlendOut, boolean rootMotion, @Nullable String syncGroup) {

    /** Unreal's default slot group. */
    public static final String DEFAULT_GROUP = "DefaultGroup";

    /** Unreal's montage defaults, its blend times 0: in the default group, blending out by itself, no root motion. */
    public static final CgMontage DEFAULT = new CgMontage(DEFAULT_GROUP, 0f, CgEasings.LINEAR, 0f, CgEasings.LINEAR, -1f, true, false, null);

    public CgMontage {
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(blendInCurve, "blendInCurve");
        Objects.requireNonNull(blendOutCurve, "blendOutCurve");
        blendIn = Math.max(0f, blendIn);
        blendOut = Math.max(0f, blendOut);
    }

    public CgMontage group(String group) {
        return new CgMontage(group, blendIn, blendInCurve, blendOut, blendOutCurve, blendOutTriggerTime, autoBlendOut, rootMotion, syncGroup);
    }

    public CgMontage blendIn(float seconds, CgEasing curve) {
        return new CgMontage(group, seconds, curve, blendOut, blendOutCurve, blendOutTriggerTime, autoBlendOut, rootMotion, syncGroup);
    }

    public CgMontage blendOut(float seconds, CgEasing curve) {
        return new CgMontage(group, blendIn, blendInCurve, seconds, curve, blendOutTriggerTime, autoBlendOut, rootMotion, syncGroup);
    }

    public CgMontage blendOutTriggerTime(float seconds) {
        return new CgMontage(group, blendIn, blendInCurve, blendOut, blendOutCurve, seconds, autoBlendOut, rootMotion, syncGroup);
    }

    public CgMontage autoBlendOut(boolean autoBlendOut) {
        return new CgMontage(group, blendIn, blendInCurve, blendOut, blendOutCurve, blendOutTriggerTime, autoBlendOut, rootMotion, syncGroup);
    }

    public CgMontage rootMotion(boolean rootMotion) {
        return new CgMontage(group, blendIn, blendInCurve, blendOut, blendOutCurve, blendOutTriggerTime, autoBlendOut, rootMotion, syncGroup);
    }

    public CgMontage syncGroup(@Nullable String syncGroup) {
        return new CgMontage(group, blendIn, blendInCurve, blendOut, blendOutCurve, blendOutTriggerTime, autoBlendOut, rootMotion, syncGroup);
    }
}
