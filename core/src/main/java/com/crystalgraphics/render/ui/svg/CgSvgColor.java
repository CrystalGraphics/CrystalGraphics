package com.crystalgraphics.render.ui.svg;

import com.crystalgraphics.api.paint.CgCssColors;

/**
 * Parses an SVG paint value into an ARGB int.
 *
 * <h3>The four answers a paint value can give</h3>
 *
 * <p>A {@code fill} or {@code stroke} attribute is not simply a colour. It is one of: a colour, the
 * keyword {@code none} (draw nothing), the keyword {@code currentColor} (whatever the consumer is
 * tinting with), or a {@code url(#id)} reference to a paint server. All four have to be distinguishable
 * by the caller, which is why this returns a small record rather than an int — an {@code int} would have
 * to encode "absent" as a colour, and every such encoding is a colour somebody eventually wants.</p>
 *
 * <p>The colour itself is CSS's {@code <color>}, read by {@link CgCssColors#parse}: hex, {@code rgb()}
 * and {@code rgba()}, and every named colour.</p>
 */
public final class CgSvgColor {

    /** What a {@code fill}/{@code stroke} value turned out to be. */
    public record Paint(int argb, boolean present, boolean currentColor, String reference) {

        public static final Paint NONE = new Paint(0, false, false, null);
        public static final Paint CURRENT = new Paint(0xFF000000, true, true, null);

        public static Paint of(int argb) {
            return new Paint(argb, true, false, null);
        }

        public static Paint url(String id) {
            return new Paint(0xFF000000, true, false, id);
        }
    }

    private CgSvgColor() {
    }

    /** Parses a paint value. An empty or unrecognised value is reported as absent, not as black. */
    public static Paint parse(String raw) {
        if (raw == null) return Paint.NONE;
        String value = raw.trim();
        if (value.isEmpty()) return Paint.NONE;
        if (value.equalsIgnoreCase("none")) return Paint.NONE;
        if (value.equalsIgnoreCase("currentColor")) return Paint.CURRENT;

        if (value.regionMatches(true, 0, "url(", 0, 4)) {
            int close = value.indexOf(')');
            String id = value.substring(4, close < 0 ? value.length() : close).trim();
            if (id.startsWith("#")) id = id.substring(1);
            // A quoted reference -- url("#a") -- is legal CSS and appears in exported artwork.
            id = id.replace("\"", "").replace("'", "");
            return Paint.url(id);
        }

        Integer argb = parseColor(value);
        return argb == null ? Paint.NONE : Paint.of(argb);
    }

    /**
     * A plain colour, or null when it is not one. SVG presentation attributes are CSS, so this is the style
     * layer's {@code <color>} parser.
     */
    public static Integer parseColor(String raw) {
        return CgCssColors.parse(raw);
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static int pack(int a, int r, int g, int b) {
        return ((a & 0xFF) << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
    }

    /** Multiplies a colour's alpha, for {@code opacity} / {@code fill-opacity} / {@code stroke-opacity}. */
    public static int withOpacity(int argb, float opacity) {
        if (opacity >= 1f) return argb;
        int alpha = Math.round(((argb >>> 24) & 0xFF) * clamp01(opacity));
        return (alpha << 24) | (argb & 0x00FFFFFF);
    }

    /** Linear blend, for collapsing a gradient's stops to one representative colour. */
    public static int mix(int from, int to, float t) {
        float u = 1f - t;
        int a = Math.round(((from >>> 24) & 0xFF) * u + ((to >>> 24) & 0xFF) * t);
        int r = Math.round(((from >>> 16) & 0xFF) * u + ((to >>> 16) & 0xFF) * t);
        int g = Math.round(((from >>> 8) & 0xFF) * u + ((to >>> 8) & 0xFF) * t);
        int b = Math.round((from & 0xFF) * u + (to & 0xFF) * t);
        return pack(a, r, g, b);
    }
}
