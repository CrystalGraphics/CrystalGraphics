package com.crystalgraphics.object.layout;

import com.crystalgraphics.object.CgProperty;
import com.crystalgraphics.object.CgPropertyFlag;
import com.crystalgraphics.object.value.CgValueForm;

/**
 * Where a kernel reads an input from, decided by the form its value takes. Part of a pool's shape key: a kernel built
 * for a constant reads a word, one built for a curve samples texels.
 *
 * <pre>{@code
 * CgFormClass.of(property, CgValueForm.CURVE);     // CURVE_BLOCK
 * CgFormClass.of(property, CgValueForm.RANGE);     // ROW: rolled per particle from two words
 * }</pre>
 */
public enum CgFormClass {
    /** A constant, or a range rolled per particle: a word in the definition's row. */
    ROW,
    /** A curve, gradient or array: texels in the definition's curve block. */
    CURVE_BLOCK,
    /** {@code PER_INSTANCE}, a binding, a per-instance roll: a lane in the instance row. */
    INSTANCE_LANE,
    /** A binding to a particle's own attribute ({@code particles.*}): read in the kernel, no word. */
    ATTRIBUTE,
    /** An engine or world value, a texture, a mesh: outside the layout's words. */
    WORLD;

    /** The class of {@code property} holding a value of {@code form}. */
    public static CgFormClass of(CgProperty property, CgValueForm form) {
        if (property.has(CgPropertyFlag.PER_INSTANCE) || form == CgValueForm.BINDING) return INSTANCE_LANE;
        switch (form) {
            case CURVE: case GRADIENT: case ARRAY: return CURVE_BLOCK;
            case DATA: return WORLD;
            default: return ROW;
        }
    }
}
