package com.crystalgraphics.api.font;

import java.util.AbstractMap;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * A set of {@link CgFontFamily} instances keyed by {@link CgFontStyle} — the family a
 * bold or italic {@code CgStyleSpan} resolves against, instead of always shaping through
 * whichever single family a plain {@code layout(...)} call was given.
 *
 * <pre>{@code
 * CgFontFamilyGroup fixed = new CgFontFamilyGroup(Map.of(
 *         CgFontStyle.REGULAR, regular,
 *         CgFontStyle.BOLD, bold));
 *
 * // bold and italic built the first time a span asks; null means "this family has no such face"
 * CgFontFamilyGroup lazy = CgFontFamilyGroup.lazy(regular, style -> installedFamily(style));
 * }</pre>
 *
 * <p>A style with no family of its own resolves to {@link CgFontStyle#REGULAR}, and the layout
 * engine synthesises the missing bold or italic (see {@code CgShapedRun#syntheticBold()}).</p>
 *
 * @param byStyle families by style; must contain at least {@link CgFontStyle#REGULAR}. For a
 *                {@link #lazy} group, the styles built so far
 */
public record CgFontFamilyGroup(Map<CgFontStyle, CgFontFamily> byStyle) {

    public CgFontFamilyGroup {
        if (byStyle == null || !byStyle.containsKey(CgFontStyle.REGULAR)) {
            throw new IllegalArgumentException("byStyle must contain at least CgFontStyle.REGULAR");
        }
        byStyle = byStyle instanceof LazyStyles ? byStyle : Map.copyOf(byStyle);
    }

    /** A group with only a regular face — every style resolves to it. */
    public static CgFontFamilyGroup ofRegular(CgFontFamily regular) {
        return new CgFontFamilyGroup(Map.of(CgFontStyle.REGULAR, regular));
    }

    /**
     * {@code regular} now, and each other style from {@code styled} the first time it is resolved,
     * so a bold face is loaded only once something is bold. {@code styled} returns {@code null} for
     * a style the family has no face for; either answer is remembered.
     */
    public static CgFontFamilyGroup lazy(CgFontFamily regular, Function<CgFontStyle, CgFontFamily> styled) {
        if (regular == null || styled == null) {
            throw new IllegalArgumentException("regular and styled must not be null");
        }
        return new CgFontFamilyGroup(new LazyStyles(regular, styled));
    }

    /**
     * Resolves the family for {@code requested}, falling back to
     * {@link CgFontStyle#REGULAR} if no exact match exists.
     */
    public CgFontFamily resolve(CgFontStyle requested) {
        CgFontFamily exact = byStyle.get(requested);
        return exact != null ? exact : byStyle.get(CgFontStyle.REGULAR);
    }

    /** Regular up front; every other style built on its first {@code get}, from any thread. */
    private static final class LazyStyles extends AbstractMap<CgFontStyle, CgFontFamily> {

        private final Map<CgFontStyle, CgFontFamily> built = new ConcurrentHashMap<>();
        private final Set<CgFontStyle> absent = ConcurrentHashMap.newKeySet();
        private final Function<CgFontStyle, CgFontFamily> styled;

        LazyStyles(CgFontFamily regular, Function<CgFontStyle, CgFontFamily> styled) {
            this.styled = styled;
            built.put(CgFontStyle.REGULAR, regular);
        }

        @Override
        public CgFontFamily get(Object key) {
            if (!(key instanceof CgFontStyle)) {
                return null;
            }
            CgFontStyle style = (CgFontStyle) key;
            CgFontFamily known = built.get(style);
            if (known != null || absent.contains(style)) {
                return known;
            }
            CgFontFamily family = styled.apply(style);
            if (family == null) {
                absent.add(style);
                return null;
            }
            CgFontFamily raced = built.putIfAbsent(style, family);
            return raced != null ? raced : family;
        }

        @Override
        public boolean containsKey(Object key) {
            return get(key) != null;
        }

        @Override
        public Set<Entry<CgFontStyle, CgFontFamily>> entrySet() {
            return Collections.unmodifiableMap(built).entrySet();
        }
    }
}
