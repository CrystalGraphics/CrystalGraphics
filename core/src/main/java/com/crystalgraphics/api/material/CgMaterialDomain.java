package com.crystalgraphics.api.material;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where a material is drawn: Unreal's material domain. A {@code .shader} names its own on its {@code #domain} line, and a
 * material of the wrong domain for where it is used fails at load ({@link CgMaterialDefinition#requireDomain}).
 *
 * <pre>{@code
 * #domain surface    // drawn through the world renderer
 * #domain post       // drawn by the post stack at a point
 * #domain ui         // a widget's brush, a retainer box's effect, or (Material { Primitive Text }) a text style's paint
 * #domain global     // one of the renderer's own passes: a raw vertex and fragment pair
 * }</pre>
 */
public enum CgMaterialDomain {
    /** Drawn through the world renderer. */
    SURFACE,
    /** Drawn by the post stack at a point ({@code CgPostMaterial}); a material with a {@code post} block. */
    POST,
    /** Projected onto what is behind it; no renderer draws one yet. */
    DECAL,
    /**
     * Drawn on a canvas (Unreal's {@code MD_UI}): a widget's brush or a retainer box's effect, a quad its UVs 0 to 1, its
     * tint the vertex colour in linear, blended by the material's own state; or, on {@code Primitive Text}, a text
     * style's paint, a colour the glyph's coverage multiplies afterwards. A retainer's effect samples the retained image
     * at its texture parameter.
     */
    UI,
    /**
     * One of the renderer's own passes, or a consumer's own mesh pass: a raw vertex and fragment pair under a
     * {@code RenderState}, never a mesh's material.
     */
    GLOBAL;

    /** The first line of a file in the material format: {@code #domain surface}. */
    private static final Pattern DIRECTIVE = Pattern.compile("(?m)^\\s*#domain\\s+(\\w+)");

    /**
     * The domain a {@code .shader} source names on its {@code #domain} line.
     *
     * @throws IllegalArgumentException when it has none, or names one that does not exist
     */
    public static CgMaterialDomain ofSource(String source) {
        Matcher directive = DIRECTIVE.matcher(source);
        if (!directive.find()) throw new IllegalArgumentException("a .shader opens with its #domain line: #domain surface");
        return ofDirective(directive.group(1));
    }

    /**
     * The domain a {@code #domain} line names: {@code surface}, {@code post}, {@code ui}, {@code decal} or
     * {@code global}, lower case as written.
     *
     * @throws IllegalArgumentException for any other word
     */
    public static CgMaterialDomain ofDirective(String word) {
        switch (word) {
            case "surface": return SURFACE;
            case "post": return POST;
            case "ui": return UI;
            case "decal": return DECAL;
            case "global": return GLOBAL;
            default:
                throw new IllegalArgumentException("#domain " + word + " is no domain: surface, post, ui, decal or global");
        }
    }
}
