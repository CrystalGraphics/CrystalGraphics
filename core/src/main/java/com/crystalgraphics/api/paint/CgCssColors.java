package com.crystalgraphics.api.paint;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * CSS's {@code <color>} as straight ARGB: hex in all four lengths, {@code rgb()}, {@code hsl()}, {@code oklab()} and
 * {@code oklch()} (with their {@code a} forms) in either syntax, the 148 named colours of CSS Color 4 and
 * {@code transparent}. What an SVG file's paint and a style sheet's colour are read with; {@code CgColor.css} reads
 * the same at full precision.
 *
 * <pre>{@code
 * CgCssColors.parse("#f80");                  // 0xFFFF8800: three digits doubled, not shifted
 * CgCssColors.parse("#ff880080");             // 0x80FF8800: alpha last in CSS, first in ARGB
 * CgCssColors.parse("rgb(255 136 0 / 50%)");  // 0x80FF8800
 * CgCssColors.parse("RebeccaPurple");         // 0xFF663399: case-insensitive
 * CgCssColors.parse("currentColor");          // null: not a colour; the caller binds it
 * }</pre>
 *
 * <p>Ported from CrystalGUI's {@code ColorValue} and {@code NamedColors}.</p>
 */
public final class CgCssColors {

    // Threads: filled once by the static initialiser, read-only after.
    private static final Map<String, Integer> NAMED = new HashMap<>(256);

    static {
        NAMED.put("aliceblue", 0xFFF0F8FF);
        NAMED.put("antiquewhite", 0xFFFAEBD7);
        NAMED.put("aqua", 0xFF00FFFF);
        NAMED.put("aquamarine", 0xFF7FFFD4);
        NAMED.put("azure", 0xFFF0FFFF);
        NAMED.put("beige", 0xFFF5F5DC);
        NAMED.put("bisque", 0xFFFFE4C4);
        NAMED.put("black", 0xFF000000);
        NAMED.put("blanchedalmond", 0xFFFFEBCD);
        NAMED.put("blue", 0xFF0000FF);
        NAMED.put("blueviolet", 0xFF8A2BE2);
        NAMED.put("brown", 0xFFA52A2A);
        NAMED.put("burlywood", 0xFFDEB887);
        NAMED.put("cadetblue", 0xFF5F9EA0);
        NAMED.put("chartreuse", 0xFF7FFF00);
        NAMED.put("chocolate", 0xFFD2691E);
        NAMED.put("coral", 0xFFFF7F50);
        NAMED.put("cornflowerblue", 0xFF6495ED);
        NAMED.put("cornsilk", 0xFFFFF8DC);
        NAMED.put("crimson", 0xFFDC143C);
        NAMED.put("cyan", 0xFF00FFFF);
        NAMED.put("darkblue", 0xFF00008B);
        NAMED.put("darkcyan", 0xFF008B8B);
        NAMED.put("darkgoldenrod", 0xFFB8860B);
        NAMED.put("darkgray", 0xFFA9A9A9);
        NAMED.put("darkgreen", 0xFF006400);
        NAMED.put("darkgrey", 0xFFA9A9A9);
        NAMED.put("darkkhaki", 0xFFBDB76B);
        NAMED.put("darkmagenta", 0xFF8B008B);
        NAMED.put("darkolivegreen", 0xFF556B2F);
        NAMED.put("darkorange", 0xFFFF8C00);
        NAMED.put("darkorchid", 0xFF9932CC);
        NAMED.put("darkred", 0xFF8B0000);
        NAMED.put("darksalmon", 0xFFE9967A);
        NAMED.put("darkseagreen", 0xFF8FBC8F);
        NAMED.put("darkslateblue", 0xFF483D8B);
        NAMED.put("darkslategray", 0xFF2F4F4F);
        NAMED.put("darkslategrey", 0xFF2F4F4F);
        NAMED.put("darkturquoise", 0xFF00CED1);
        NAMED.put("darkviolet", 0xFF9400D3);
        NAMED.put("deeppink", 0xFFFF1493);
        NAMED.put("deepskyblue", 0xFF00BFFF);
        NAMED.put("dimgray", 0xFF696969);
        NAMED.put("dimgrey", 0xFF696969);
        NAMED.put("dodgerblue", 0xFF1E90FF);
        NAMED.put("firebrick", 0xFFB22222);
        NAMED.put("floralwhite", 0xFFFFFAF0);
        NAMED.put("forestgreen", 0xFF228B22);
        NAMED.put("fuchsia", 0xFFFF00FF);
        NAMED.put("gainsboro", 0xFFDCDCDC);
        NAMED.put("ghostwhite", 0xFFF8F8FF);
        NAMED.put("gold", 0xFFFFD700);
        NAMED.put("goldenrod", 0xFFDAA520);
        NAMED.put("gray", 0xFF808080);
        NAMED.put("green", 0xFF008000);
        NAMED.put("greenyellow", 0xFFADFF2F);
        NAMED.put("grey", 0xFF808080);
        NAMED.put("honeydew", 0xFFF0FFF0);
        NAMED.put("hotpink", 0xFFFF69B4);
        NAMED.put("indianred", 0xFFCD5C5C);
        NAMED.put("indigo", 0xFF4B0082);
        NAMED.put("ivory", 0xFFFFFFF0);
        NAMED.put("khaki", 0xFFF0E68C);
        NAMED.put("lavender", 0xFFE6E6FA);
        NAMED.put("lavenderblush", 0xFFFFF0F5);
        NAMED.put("lawngreen", 0xFF7CFC00);
        NAMED.put("lemonchiffon", 0xFFFFFACD);
        NAMED.put("lightblue", 0xFFADD8E6);
        NAMED.put("lightcoral", 0xFFF08080);
        NAMED.put("lightcyan", 0xFFE0FFFF);
        NAMED.put("lightgoldenrodyellow", 0xFFFAFAD2);
        NAMED.put("lightgray", 0xFFD3D3D3);
        NAMED.put("lightgreen", 0xFF90EE90);
        NAMED.put("lightgrey", 0xFFD3D3D3);
        NAMED.put("lightpink", 0xFFFFB6C1);
        NAMED.put("lightsalmon", 0xFFFFA07A);
        NAMED.put("lightseagreen", 0xFF20B2AA);
        NAMED.put("lightskyblue", 0xFF87CEFA);
        NAMED.put("lightslategray", 0xFF778899);
        NAMED.put("lightslategrey", 0xFF778899);
        NAMED.put("lightsteelblue", 0xFFB0C4DE);
        NAMED.put("lightyellow", 0xFFFFFFE0);
        NAMED.put("lime", 0xFF00FF00);
        NAMED.put("limegreen", 0xFF32CD32);
        NAMED.put("linen", 0xFFFAF0E6);
        NAMED.put("magenta", 0xFFFF00FF);
        NAMED.put("maroon", 0xFF800000);
        NAMED.put("mediumaquamarine", 0xFF66CDAA);
        NAMED.put("mediumblue", 0xFF0000CD);
        NAMED.put("mediumorchid", 0xFFBA55D3);
        NAMED.put("mediumpurple", 0xFF9370DB);
        NAMED.put("mediumseagreen", 0xFF3CB371);
        NAMED.put("mediumslateblue", 0xFF7B68EE);
        NAMED.put("mediumspringgreen", 0xFF00FA9A);
        NAMED.put("mediumturquoise", 0xFF48D1CC);
        NAMED.put("mediumvioletred", 0xFFC71585);
        NAMED.put("midnightblue", 0xFF191970);
        NAMED.put("mintcream", 0xFFF5FFFA);
        NAMED.put("mistyrose", 0xFFFFE4E1);
        NAMED.put("moccasin", 0xFFFFE4B5);
        NAMED.put("navajowhite", 0xFFFFDEAD);
        NAMED.put("navy", 0xFF000080);
        NAMED.put("oldlace", 0xFFFDF5E6);
        NAMED.put("olive", 0xFF808000);
        NAMED.put("olivedrab", 0xFF6B8E23);
        NAMED.put("orange", 0xFFFFA500);
        NAMED.put("orangered", 0xFFFF4500);
        NAMED.put("orchid", 0xFFDA70D6);
        NAMED.put("palegoldenrod", 0xFFEEE8AA);
        NAMED.put("palegreen", 0xFF98FB98);
        NAMED.put("paleturquoise", 0xFFAFEEEE);
        NAMED.put("palevioletred", 0xFFDB7093);
        NAMED.put("papayawhip", 0xFFFFEFD5);
        NAMED.put("peachpuff", 0xFFFFDAB9);
        NAMED.put("peru", 0xFFCD853F);
        NAMED.put("pink", 0xFFFFC0CB);
        NAMED.put("plum", 0xFFDDA0DD);
        NAMED.put("powderblue", 0xFFB0E0E6);
        NAMED.put("purple", 0xFF800080);
        NAMED.put("rebeccapurple", 0xFF663399);
        NAMED.put("red", 0xFFFF0000);
        NAMED.put("rosybrown", 0xFFBC8F8F);
        NAMED.put("royalblue", 0xFF4169E1);
        NAMED.put("saddlebrown", 0xFF8B4513);
        NAMED.put("salmon", 0xFFFA8072);
        NAMED.put("sandybrown", 0xFFF4A460);
        NAMED.put("seagreen", 0xFF2E8B57);
        NAMED.put("seashell", 0xFFFFF5EE);
        NAMED.put("sienna", 0xFFA0522D);
        NAMED.put("silver", 0xFFC0C0C0);
        NAMED.put("skyblue", 0xFF87CEEB);
        NAMED.put("slateblue", 0xFF6A5ACD);
        NAMED.put("slategray", 0xFF708090);
        NAMED.put("slategrey", 0xFF708090);
        NAMED.put("snow", 0xFFFFFAFA);
        NAMED.put("springgreen", 0xFF00FF7F);
        NAMED.put("steelblue", 0xFF4682B4);
        NAMED.put("tan", 0xFFD2B48C);
        NAMED.put("teal", 0xFF008080);
        NAMED.put("thistle", 0xFFD8BFD8);
        NAMED.put("tomato", 0xFFFF6347);
        NAMED.put("turquoise", 0xFF40E0D0);
        NAMED.put("violet", 0xFFEE82EE);
        NAMED.put("wheat", 0xFFF5DEB3);
        NAMED.put("white", 0xFFFFFFFF);
        NAMED.put("whitesmoke", 0xFFF5F5F5);
        NAMED.put("yellow", 0xFFFFFF00);
        NAMED.put("yellowgreen", 0xFF9ACD32);
    }

    private CgCssColors() {
    }

    /** A CSS {@code <color>}, straight ARGB, or null when {@code value} is not one. */
    public static Integer parse(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }

        value = value.trim().toLowerCase(Locale.ROOT);

        // CSS Color 4: transparent is rgba(0, 0, 0, 0), a colour, not the absence of one
        if (value.equals("transparent")) return 0x00000000;

        if (value.charAt(0) == '#') return hex(value.substring(1));
        if (value.startsWith("rgb(") || value.startsWith("rgba(")) return functional(value);
        if (value.startsWith("hsl") || value.startsWith("oklab(") || value.startsWith("oklch(")) {
            try {
                return CgColor.css(value).toArgb();
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

        return NAMED.get(value);
    }

    /**
     * {@code #rgb}, {@code #rgba}, {@code #rrggbb}, {@code #rrggbbaa}.
     *
     * <p>The three- and four-digit forms double each digit rather than shifting: {@code #f00} is
     * {@code #ff0000}, not {@code #f00000}. Shifting is the tempting one-liner and it darkens every short
     * hex by a few percent, which is invisible per-colour and wrong everywhere.</p>
     */
    private static Integer hex(String digits) {
        for (int i = 0; i < digits.length(); i++) {
            if (Character.digit(digits.charAt(i), 16) < 0) return null;
        }
        switch (digits.length()) {
            case 3:
            case 4: {
                int alpha = digits.length() == 4 ? nibble(digits.charAt(3)) : 0xF;
                return pack(alpha * 17, nibble(digits.charAt(0)) * 17,
                        nibble(digits.charAt(1)) * 17, nibble(digits.charAt(2)) * 17);
            }
            case 6:
                return 0xFF000000 | Integer.parseInt(digits, 16);
            case 8: {
                long full = Long.parseLong(digits, 16);
                // #rrggbbaa puts alpha LAST, unlike our ARGB int.
                return (int) (((full & 0xFF) << 24) | (full >>> 8));
            }
            default:
                return null;
        }
    }

    private static int nibble(char c) {
        return Character.digit(c, 16);
    }

    /**
     * {@code rgb()} and {@code rgba()}, either name with or without alpha, channels as numbers or
     * percentages, separated by commas or by spaces with a slash before the alpha. Out-of-range values
     * clamp, as CSS Color 4 says they do.
     */
    private static Integer functional(String value) {
        int open = value.indexOf('(');
        if (!value.endsWith(")")) return null;
        String[] parts = value.substring(open + 1, value.length() - 1).trim().split("[\\s,/]+");
        if (parts.length < 3 || parts.length > 4) return null;
        try {
            int r = channel(parts[0]);
            int g = channel(parts[1]);
            int b = channel(parts[2]);
            int a = parts.length > 3 ? Math.round(clamp01(alpha(parts[3])) * 255f) : 255;
            return pack(a, r, g, b);
        } catch (NumberFormatException malformed) {
            return null;
        }
    }

    private static int channel(String raw) {
        if (raw.endsWith("%")) {
            float percent = Float.parseFloat(raw.substring(0, raw.length() - 1));
            return Math.round(clamp01(percent / 100f) * 255f);
        }
        return Math.max(0, Math.min(255, Math.round(Float.parseFloat(raw))));
    }

    private static float alpha(String raw) {
        return raw.endsWith("%") ? Float.parseFloat(raw.substring(0, raw.length() - 1)) / 100f : Float.parseFloat(raw);
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static int pack(int a, int r, int g, int b) {
        return ((a & 0xFF) << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
    }
}
