package com.crystalgraphics.api.text;

/**
 * A single line decoration applied to a run or style span. Multiple can be simultaneously
 * active — see {@link CgStyleSpan#decorations()}/{@link CgShapedRun#getDecorations()}, which
 * hold a {@code Set<CgTextDecoration>} rather than one value; "no decoration" is an empty set,
 * not a member of this enum.
 */
public enum CgTextDecoration {
    UNDERLINE,
    STRIKETHROUGH,
    OVERLINE
}
