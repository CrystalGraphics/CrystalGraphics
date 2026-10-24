package com.crystalgraphics.vfx.scalability;

/**
 * A value by another between two points: {@code startY} up to {@code startX}, {@code endY} from {@code endX}, and
 * straight between. Mirrors Niagara's {@code FNiagaraLinearRamp}, which scales an effect type's limits by the budget's
 * usage; the spec writes one as {@code [start_x, start_y, end_x, end_y]}.
 *
 * <pre>{@code
 * CgVfxLinearRamp.DEFAULT.at(0.75f);                           // 0.75: halfway from 1 at 0.5 to 0.5 at 1
 * new CgVfxLinearRamp(1f, 1f, 2f, 0.25f).at(1.5f);             // 0.625: nothing until over budget
 * }</pre>
 *
 * <ul>
 *   <li>{@code endX} must be past {@code startX}.</li>
 * </ul>
 */
public record CgVfxLinearRamp(float startX, float startY, float endX, float endY) {

    /** Niagara's default for a budget scale: full at half the budget, half at all of it. */
    public static final CgVfxLinearRamp DEFAULT = new CgVfxLinearRamp(0.5f, 1f, 1f, 0.5f);

    public CgVfxLinearRamp {
        if (!(endX > startX)) throw new IllegalArgumentException("a ramp ending at " + endX + " starts at " + startX);
    }

    /** Its value at {@code x}. */
    public float at(float x) {
        if (x <= startX) return startY;
        if (x >= endX) return endY;
        return startY + (endY - startY) * (x - startX) / (endX - startX);
    }
}
