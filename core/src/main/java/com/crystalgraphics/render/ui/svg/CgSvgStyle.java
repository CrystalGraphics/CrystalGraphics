package com.crystalgraphics.render.ui.svg;


import java.util.Map;

/**
 * The presentation state in force at one point in an SVG tree.
 *
 * <h3>Immutable, because inheritance is a stack</h3>
 *
 * <p>SVG presentation attributes inherit: a {@code stroke} on a {@code <g>} applies to every descendant
 * that does not override it. Modelling that with a mutable object means saving and restoring fields around
 * every element, which is the shape of bug that only shows up on the second sibling. A value type makes
 * descent a parameter and makes "restore" mean nothing at all — {@link #inherit} builds the child and the
 * parent is simply still there when it returns.</p>
 *
 * <h3>Opacity multiplies down, which is an approximation</h3>
 *
 * <p>Per spec, {@code opacity} on a {@code <g>} composites the <em>rendered group</em> — the group is
 * drawn to its own surface and that surface is faded, so two overlapping children at 50% do not show
 * through each other. Multiplying the value into each child instead makes them do exactly that. It is the
 * same trade every renderer without an isolation buffer makes, it is invisible for artwork that does not
 * self-overlap, and getting it right means an FBO per group.</p>
 */
public record CgSvgStyle(CgSvgColor.Paint fill, CgSvgColor.Paint stroke,
                       float strokeWidth, int cap, boolean evenOdd,
                       float fillOpacity, float strokeOpacity, float opacity) {

    /** Stroke caps, as the curve records pack them: butt, round, square. */
    public static final int CAP_BUTT = 0, CAP_ROUND = 1, CAP_SQUARE = 2;

    /**
     * SVG's own initial values: black fill, no stroke, width 1, {@code nonzero}.
     *
     * <p>The default being a <b>fill</b> is what makes a bare {@code <path d="…"/>} with no presentation
     * attributes at all draw as a solid black shape — which is how most logos and every Material icon are
     * authored. A renderer that defaults to stroking instead produces a wireframe of the artwork, and that
     * looks like it lost the fill rather than like it never had one.</p>
     */
    public static final CgSvgStyle ROOT = new CgSvgStyle(
            CgSvgColor.Paint.of(0xFF000000), CgSvgColor.Paint.NONE,
            1f, CAP_BUTT, false, 1f, 1f, 1f);

    /**
     * This style with one element's attributes applied over it.
     *
     * <p>An absent attribute inherits; a present one overrides. {@code opacity} is the exception and
     * <b>multiplies</b>, because nested groups compound.</p>
     *
     * <p>{@code style="…"} is read <b>after</b> the presentation attributes and therefore beats them,
     * which is CSS's own precedence: a presentation attribute has the specificity of an author rule at
     * zero, and an inline declaration outranks it. Exported artwork routinely carries both with different
     * values, so reading them in the other order paints a whole file in the wrong colours.</p>
     *
     * @param gradients paint-server id to a single representative colour; see {@code CgSvg}
     */
    public CgSvgStyle inherit(Map<String, String> attributes, Map<String, Integer> gradients) {
        CgSvgStyle result = this;
        result = result.applyOne(attributes);
        String inline = attributes.get("style");
        if (inline != null && !inline.isBlank()) result = result.applyOne(declarations(inline));
        return result.resolve(gradients);
    }

    private CgSvgStyle applyOne(Map<String, String> declarations) {
        CgSvgColor.Paint newFill = fill;
        CgSvgColor.Paint newStroke = stroke;
        float newWidth = strokeWidth;
        int newCap = cap;
        boolean newEvenOdd = evenOdd;
        float newFillOpacity = fillOpacity;
        float newStrokeOpacity = strokeOpacity;
        float newOpacity = opacity;

        for (Map.Entry<String, String> entry : declarations.entrySet()) {
            String value = entry.getValue();
            switch (entry.getKey()) {
                case "fill" -> newFill = CgSvgColor.parse(value);
                case "stroke" -> newStroke = CgSvgColor.parse(value);
                case "stroke-width" -> newWidth = length(value, newWidth);
                case "stroke-linecap" -> newCap = parseCap(value);
                case "fill-rule", "clip-rule" -> newEvenOdd = value.trim().equalsIgnoreCase("evenodd");
                case "fill-opacity" -> newFillOpacity = clamp(length(value, 1f));
                case "stroke-opacity" -> newStrokeOpacity = clamp(length(value, 1f));
                case "opacity" -> newOpacity = newOpacity * clamp(length(value, 1f));
                default -> { }
            }
        }
        return new CgSvgStyle(newFill, newStroke, newWidth, newCap, newEvenOdd,
                newFillOpacity, newStrokeOpacity, newOpacity);
    }

    /** Swaps any {@code url(#id)} paint for the flat colour that paint server reduces to. */
    private CgSvgStyle resolve(Map<String, Integer> gradients) {
        CgSvgColor.Paint newFill = resolveOne(fill, gradients);
        CgSvgColor.Paint newStroke = resolveOne(stroke, gradients);
        if (newFill == fill && newStroke == stroke) return this;
        return new CgSvgStyle(newFill, newStroke, strokeWidth, cap, evenOdd,
                fillOpacity, strokeOpacity, opacity);
    }

    private static CgSvgColor.Paint resolveOne(CgSvgColor.Paint paint, Map<String, Integer> gradients) {
        if (paint.reference() == null) return paint;
        Integer colour = gradients.get(paint.reference());
        // The reference SURVIVES resolution, and that is the point. A fill can honour the real ramp by
        // subdividing, so it needs the paint server itself; a stroke, and any server we cannot draw, needs
        // the one colour. Flattening here would take the first option away from the fill path.
        //
        // An unresolved reference draws grey rather than nothing: a url() pointing at a pattern or a filter
        // is still a statement that this shape is painted, and dropping it removes a region of the artwork
        // with no visible cause.
        return new CgSvgColor.Paint(colour != null ? colour : 0xFF808080, true, false, paint.reference());
    }

    /** {@code a:b; c:d} — the inline {@code style} attribute, which is a CSS declaration list. */
    private static Map<String, String> declarations(String raw) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (String part : raw.split(";")) {
            int colon = part.indexOf(':');
            if (colon <= 0) continue;
            out.put(part.substring(0, colon).trim().toLowerCase(), part.substring(colon + 1).trim());
        }
        return out;
    }

    public boolean fills() {
        return fill.present() && fillOpacity * opacity > 0.001f;
    }

    public boolean strokes() {
        return stroke.present() && strokeWidth > 0f && strokeOpacity * opacity > 0.001f;
    }

    /** The fill colour with both opacities folded in. {@code currentColor} is left for the caller. */
    public int fillArgb() {
        return CgSvgColor.withOpacity(fill.argb(), fillOpacity * opacity);
    }

    public int strokeArgb() {
        return CgSvgColor.withOpacity(stroke.argb(), strokeOpacity * opacity);
    }

    /** Half-width, which is what a curve record takes. */
    public float strokeHalfWidth() {
        return strokeWidth / 2f;
    }

    /** {@code butt} / {@code round} / {@code square}, SVG's own three keywords. */
    public static int parseCap(String raw) {
        return switch (raw.trim()) {
            case "round" -> CAP_ROUND;
            case "square" -> CAP_SQUARE;
            default -> CAP_BUTT;
        };
    }

    /**
     * A length or a number, with any CSS unit suffix dropped.
     *
     * <p>{@code px} is the only unit that means anything inside a viewBox and it is a no-op, but exported
     * files carry {@code pt} and {@code %} anyway. Parsing the number and ignoring the suffix is wrong for
     * {@code %} and right for everything else; refusing the value entirely is wrong for all of them.</p>
     */
    private static float length(String raw, float fallback) {
        String value = raw.trim();
        int end = 0;
        while (end < value.length()
                && (Character.isDigit(value.charAt(end)) || value.charAt(end) == '.'
                || value.charAt(end) == '-' || value.charAt(end) == '+'
                || value.charAt(end) == 'e' || value.charAt(end) == 'E')) {
            end++;
        }
        if (end == 0) return fallback;
        try {
            return Float.parseFloat(value.substring(0, end));
        } catch (NumberFormatException malformed) {
            return fallback;
        }
    }

    private static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
