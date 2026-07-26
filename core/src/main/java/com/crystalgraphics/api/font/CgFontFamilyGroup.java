package com.crystalgraphics.api.font;

import java.util.Map;

/**
 * A set of {@link CgFontFamily} instances keyed by {@link CgFontStyle} — the family a
 * bold or italic {@code CgStyleSpan} resolves against, instead of always shaping through
 * whichever single family a plain {@code layout(...)} call was given.
 *
 * <p>v1: no synthetic bold/oblique synthesis. A requested style with no exact match falls
 * back to {@link CgFontStyle#REGULAR} rather than faking bold/italic by skewing or
 * double-stroking the regular face.</p>
 *
 * @param byStyle families by style; must contain at least {@link CgFontStyle#REGULAR}
 */
public record CgFontFamilyGroup(Map<CgFontStyle, CgFontFamily> byStyle) {

    public CgFontFamilyGroup {
        if (byStyle == null || !byStyle.containsKey(CgFontStyle.REGULAR)) {
            throw new IllegalArgumentException("byStyle must contain at least CgFontStyle.REGULAR");
        }
        byStyle = Map.copyOf(byStyle);
    }

    /** A group with only a regular face — every style resolves to it. */
    public static CgFontFamilyGroup ofRegular(CgFontFamily regular) {
        return new CgFontFamilyGroup(Map.of(CgFontStyle.REGULAR, regular));
    }

    /**
     * Resolves the family for {@code requested}, falling back to
     * {@link CgFontStyle#REGULAR} if no exact match exists.
     */
    public CgFontFamily resolve(CgFontStyle requested) {
        CgFontFamily exact = byStyle.get(requested);
        return exact != null ? exact : byStyle.get(CgFontStyle.REGULAR);
    }
}
