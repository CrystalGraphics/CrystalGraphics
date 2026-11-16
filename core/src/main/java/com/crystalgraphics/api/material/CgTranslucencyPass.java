package com.crystalgraphics.api.material;

import javax.annotation.Nullable;

/**
 * Where a translucent material's draws are composited among the post chain's steps: Unreal 5.1's
 * {@code TranslucencyPass}. A material before depth of field is blurred with the scene behind it; one after it stays
 * sharp; one after motion blur is not smeared by it either.
 *
 * <pre>{@code
 * // A shader's Material { }
 * TranslucencyPass BeforeDOF         // the default: drawn into the scene, blurred with what is behind it
 * TranslucencyPass AfterDOF          // a spark or a hit flash: stays sharp while the scene behind it blurs
 *
 * // An asset's state, or a material made in code: later than its shader's, never earlier
 * state: { TranslucencyPass: AfterMotionBlur }
 * CgMaterialState.builder().translucencyPass(CgTranslucencyPass.AFTER_DOF).build();
 * }</pre>
 *
 * <ul>
 *   <li>{@link #BEFORE_DOF} draws into the scene with the opaque surfaces; the others into a target of their own, laid
 *       over the scene after their step, unbent by the scene's distortion and ungraded by its colour regions.</li>
 *   <li>With neither depth of field nor motion blur on, the after passes are laid over at one point.</li>
 *   <li>A draw whose blend multiplies has no form in a target of its own: it stays in the scene, warned once.</li>
 *   <li>The default is {@code BeforeDOF}, where Unreal's is {@code AfterDOF}: an after pass costs a target and its
 *       composite a frame and sorts over every draw in the scene whatever its depth, so it is asked for, not given.
 *       What a kit imports says its pass.</li>
 * </ul>
 */
public enum CgTranslucencyPass {

    BEFORE_DOF("BeforeDOF"),
    AFTER_DOF("AfterDOF"),
    AFTER_MOTION_BLUR("AfterMotionBlur");

    /** A shader's and an asset's spelling. */
    private final String name;

    CgTranslucencyPass(String name) {
        this.name = name;
    }

    /** Its spelling in a shader's {@code Material { }} and an asset's {@code state}. */
    public String formatName() {
        return name;
    }

    /** The pass spelled {@code name}; null for none. */
    @Nullable
    public static CgTranslucencyPass of(String name) {
        for (CgTranslucencyPass p : values()) if (p.name.equals(name)) return p;
        return null;
    }

    /** Whether it is laid over the scene from a target of its own, after depth of field at the earliest. */
    public boolean apart() {
        return this != BEFORE_DOF;
    }

    /** The later of the two: an asset moves its shader's pass later, never earlier. */
    public static CgTranslucencyPass later(CgTranslucencyPass a, CgTranslucencyPass b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
