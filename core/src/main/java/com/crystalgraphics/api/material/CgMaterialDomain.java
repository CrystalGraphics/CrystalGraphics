package com.crystalgraphics.api.material;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where a material is drawn: Unreal's material domain. An archetype names its own with a top-level tag, and a material
 * of the wrong domain for where it is used fails at load ({@link CgMaterialDefinition#requireDomain}).
 *
 * <pre>{@code
 * Tags { "Domain" = "Decal" }        // in the archetype: projected through the froxel bins
 * Tags { "Domain" = "Post" }         // drawn by the post stack at a point
 * // no tag: SURFACE, drawn through the world renderer
 * }</pre>
 */
public enum CgMaterialDomain {
    /** Drawn through the world renderer: the default. */
    SURFACE,
    /** Drawn by the post stack at a point ({@code CgPostMaterial}); a material with a {@code post} block. */
    POST,
    /** Projected onto what is behind it. */
    DECAL;

    private static final Pattern TAG = Pattern.compile("\"Domain\"\\s*=\\s*\"(\\w+)\"");

    /**
     * The domain a {@code .shader} source names in its tags, {@link #SURFACE} when it names none.
     *
     * @throws IllegalArgumentException for a domain that does not exist
     */
    public static CgMaterialDomain ofSource(String source) {
        Matcher m = TAG.matcher(source);
        if (!m.find()) return SURFACE;
        try {
            return valueOf(m.group(1).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("\"Domain\" = \"" + m.group(1) + "\" is no domain: Surface, Post or Decal");
        }
    }
}
