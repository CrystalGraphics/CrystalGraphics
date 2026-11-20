package com.crystalgraphics.api.paint;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.crystalgraphics.serialization.CgCodec;
import com.crystalgraphics.serialization.CgCodecException;
import com.crystalgraphics.serialization.CgDynamicOps;

/**
 * A colour in linear light with straight alpha: what a colour is wherever one is held, blended or converted. Unreal's
 * {@code FLinearColor}, Skia's {@code SkColor4f}. Where a colour is only written down, write it as {@code 0xAARRGGBB}
 * sRGB, the way it is picked; this is the type for the arithmetic.
 *
 * <pre>{@code
 * CgColor gold = CgColor.argb(0xFFFFC040);                 // sRGB as picked, decoded to linear
 * CgColor glow = new CgColor(4f, 1.5f, 0.2f, 1f);          // linear, past white: HDR
 * CgColor muted = CgColor.css("oklch(70% 0.1 250)");        // any CSS colour: hex, rgb(), hsl(), oklab(), oklch(), names
 * int packed = gold.lerpOklab(muted, 0.5f).toArgb();         // back to sRGB ARGB for a canvas
 * }</pre>
 *
 * <pre>{@code
 * double ratio = CgColor.argb(text).contrast(CgColor.argb(background));   // WCAG 2: 4.5 for body text
 * float[] hsv = gold.toHsv();                                             // a picker's wheel
 * CgColor brighter = gold.intensity(2f);                                  // HDR: rgb doubled, alpha kept
 * }</pre>
 *
 * <ul>
 *   <li>Alpha is never encoded: 0.5 is half coverage in either space.</li>
 *   <li>{@link #argb(int)} decodes and {@link #toArgb()} encodes: a linear 0.5 is sRGB 0xBC, not 0x80. Encoding clamps
 *       each channel to 0..1, so an HDR colour does not survive {@code toArgb}; it does survive {@link #CODEC}.</li>
 *   <li>HSV and HSL are on the sRGB-encoded channels, as CSS's {@code hsl()} is; OKLab and OKLCH are Ottosson's, on
 *       the linear ones. Hues are in degrees.</li>
 *   <li>{@link #lerp} mixes light, {@link #lerpSrgb} mixes as most image editors do, {@link #lerpOklab} mixes as
 *       CSS does by default: the even-looking one.</li>
 * </ul>
 *
 * @param r red, linear; past 1 for HDR
 * @param g green, linear
 * @param b blue, linear
 * @param a alpha, straight, 0 to 1
 */
public record CgColor(float r, float g, float b, float a) {

    public static final CgColor WHITE = new CgColor(1f, 1f, 1f, 1f);
    public static final CgColor BLACK = new CgColor(0f, 0f, 0f, 1f);
    public static final CgColor TRANSPARENT = new CgColor(0f, 0f, 0f, 0f);

    /**
     * How a colour is written in a data file. Read from sRGB {@code 0xAARRGGBB}, a linear {@code [r, g, b, a]} or any
     * CSS colour string; written as the {@code 0xAARRGGBB} it is when one holds it exactly, else as the linear list.
     *
     * <pre>{@code
     * // grades.yaml: - { grade: Z, glow: 0xFFD80A16 }   or   glow: [4.0, 0.2, 0.1, 1.0]   or   glow: "#d80a16"
     * CgCodecs.field("glow", CgColor.CODEC, Grade::glow)
     * }</pre>
     */
    public static final CgCodec<CgColor> CODEC = new CgCodec<CgColor>() {
        @Override
        public <T> T encode(CgDynamicOps<T> ops, CgColor c) {
            int packed = c.toArgb();
            if (argb(packed).equals(c)) return ops.createNumber(packed & 0xFFFFFFFFL);
            List<T> list = new ArrayList<>(4);
            for (float v : new float[]{c.r, c.g, c.b, c.a}) list.add(ops.createNumber((double) v));
            return ops.createList(list);
        }

        @Override
        public <T> CgColor decode(CgDynamicOps<T> ops, T input) {
            Object v = ops.toPlain(input);
            if (v instanceof Number n) return argb((int) n.longValue());
            if (v instanceof String s) return css(s);
            if (v instanceof List<?> l && (l.size() == 3 || l.size() == 4)) {
                float[] f = new float[]{0f, 0f, 0f, 1f};
                for (int i = 0; i < l.size(); i++) {
                    if (!(l.get(i) instanceof Number n)) throw new CgCodecException("a colour's channel is a number: " + v);
                    f[i] = n.floatValue();
                }
                return new CgColor(f[0], f[1], f[2], f[3]);
            }
            throw new CgCodecException("a colour is 0xAARRGGBB, [r, g, b, a] linear, or a CSS colour: " + v);
        }
    };

    // ── In ──────────────────────────────────────────────────────────────────

    /** {@code argb}, sRGB {@code 0xAARRGGBB} as picked, decoded to linear. */
    public static CgColor argb(int argb) {
        return new CgColor(linear((argb >>> 16 & 0xFF) / 255f), linear((argb >>> 8 & 0xFF) / 255f),
                linear((argb & 0xFF) / 255f), (argb >>> 24) / 255f);
    }

    /** {@code rgba}, sRGB {@code 0xRRGGBBAA} (CSS's and most tools' byte order), decoded to linear. */
    public static CgColor rgba(int rgba) {
        return argb(rgba >>> 8 | rgba << 24);
    }

    /** sRGB channels, 0 to 1, decoded to linear. */
    public static CgColor srgb(float r, float g, float b, float a) {
        return new CgColor(linear(r), linear(g), linear(b), a);
    }

    /**
     * A CSS hex colour, {@code #rgb}, {@code #rgba}, {@code #rrggbb} or {@code #rrggbbaa} (alpha last, as CSS writes
     * it); the {@code #} optional.
     *
     * @throws IllegalArgumentException when it is not one
     */
    public static CgColor hex(String hex) {
        String h = hex.startsWith("#") ? hex : "#" + hex;
        Integer argb = CgCssColors.parse(h);
        if (argb == null) throw new IllegalArgumentException("not a hex colour: " + hex);
        return argb(argb);
    }

    /**
     * Any CSS {@code <color>}: hex, {@code rgb()}, {@code hsl()}, {@code oklab()}, {@code oklch()} (with their
     * {@code a} forms and either syntax), a named colour or {@code transparent}. {@code oklab} and {@code oklch} are
     * read at full precision, an HDR result kept.
     *
     * @throws IllegalArgumentException when it is not one
     */
    public static CgColor css(String value) {
        String v = value.trim().toLowerCase(Locale.ROOT);
        int open = v.indexOf('(');
        if (open > 0 && v.endsWith(")")) {
            String name = v.substring(0, open).trim();
            String[] t = v.substring(open + 1, v.length() - 1).trim().split("[\\s,/]+");
            switch (name) {
                case "hsl", "hsla" -> {
                    if (t.length < 3) throw new IllegalArgumentException("not a CSS colour: " + value);
                    return hsl(angle(t[0]), percent(t[1]), percent(t[2]), t.length > 3 ? alpha(t[3]) : 1f);
                }
                case "oklab" -> {
                    if (t.length < 3) throw new IllegalArgumentException("not a CSS colour: " + value);
                    return oklab(lightness(t[0]), chroma(t[1]), chroma(t[2]), t.length > 3 ? alpha(t[3]) : 1f);
                }
                case "oklch" -> {
                    if (t.length < 3) throw new IllegalArgumentException("not a CSS colour: " + value);
                    return oklch(lightness(t[0]), chroma(t[1]), angle(t[2]), t.length > 3 ? alpha(t[3]) : 1f);
                }
                default -> {
                }
            }
        }
        Integer argb = CgCssColors.parse(value);
        if (argb == null) throw new IllegalArgumentException("not a CSS colour: " + value);
        return argb(argb);
    }

    /** Hue (degrees), saturation and value (0 to 1) of the sRGB-encoded colour. */
    public static CgColor hsv(float h, float s, float v, float a) {
        float c = v * s;
        float[] rgb = hueRgb(h, c, v - c);
        return srgb(rgb[0], rgb[1], rgb[2], a);
    }

    /** Hue (degrees), saturation and lightness (0 to 1) of the sRGB-encoded colour: CSS's {@code hsl()}. */
    public static CgColor hsl(float h, float s, float l, float a) {
        float c = (1f - Math.abs(2f * l - 1f)) * s;
        float[] rgb = hueRgb(h, c, l - c / 2f);
        return srgb(rgb[0], rgb[1], rgb[2], a);
    }

    /** Ottosson's OKLab: lightness 0 to 1, a and b about -0.4 to 0.4. */
    public static CgColor oklab(float l, float aa, float bb, float alpha) {
        double l_ = l + 0.3963377774 * aa + 0.2158037573 * bb;
        double m_ = l - 0.1055613458 * aa - 0.0638541728 * bb;
        double s_ = l - 0.0894841775 * aa - 1.2914855480 * bb;
        double lc = l_ * l_ * l_, mc = m_ * m_ * m_, sc = s_ * s_ * s_;
        return new CgColor((float) (4.0767416621 * lc - 3.3077115913 * mc + 0.2309699292 * sc),
                (float) (-1.2684380046 * lc + 2.6097574011 * mc - 0.3413193965 * sc),
                (float) (-0.0041960863 * lc - 0.7034186147 * mc + 1.7076147010 * sc), alpha);
    }

    /** OKLab in polar form: lightness 0 to 1, chroma 0 to about 0.4, hue in degrees. */
    public static CgColor oklch(float l, float c, float h, float alpha) {
        double rad = Math.toRadians(h);
        return oklab(l, (float) (c * Math.cos(rad)), (float) (c * Math.sin(rad)), alpha);
    }

    // ── Out ─────────────────────────────────────────────────────────────────

    /** As sRGB {@code 0xAARRGGBB}, each channel clamped to 0 to 1. */
    public int toArgb() {
        return toArgb(r, g, b, a);
    }

    /** As sRGB {@code 0xRRGGBBAA}, each channel clamped to 0 to 1. */
    public int toRgba() {
        int argb = toArgb();
        return argb << 8 | argb >>> 24;
    }

    /** As CSS hex, lower case: {@code #rrggbb} when opaque, else {@code #rrggbbaa}. Clamped as {@link #toArgb()}. */
    public String toHex() {
        int argb = toArgb();
        return (argb >>> 24) == 0xFF ? String.format("#%06x", argb & 0xFFFFFF)
                : String.format("#%06x%02x", argb & 0xFFFFFF, argb >>> 24);
    }

    /** As CSS: its hex where 8 bits hold it exactly, else {@code color(srgb-linear r g b / a)}, HDR included. */
    public String toCss() {
        if (argb(toArgb()).equals(this)) return toHex();
        return String.format(Locale.ROOT, "color(srgb-linear %s %s %s / %s)", r, g, b, a);
    }

    /** {@code {h, s, v, a}}: hue in degrees, the rest 0 to 1, of the sRGB-encoded colour. */
    public float[] toHsv() {
        float sr = srgb(r), sg = srgb(g), sb = srgb(b);
        float max = Math.max(sr, Math.max(sg, sb)), min = Math.min(sr, Math.min(sg, sb));
        return new float[]{hue(sr, sg, sb, max, max - min), max == 0f ? 0f : (max - min) / max, max, a};
    }

    /** {@code {h, s, l, a}}: hue in degrees, the rest 0 to 1, of the sRGB-encoded colour. */
    public float[] toHsl() {
        float sr = srgb(r), sg = srgb(g), sb = srgb(b);
        float max = Math.max(sr, Math.max(sg, sb)), min = Math.min(sr, Math.min(sg, sb));
        float l = (max + min) / 2f, d = max - min;
        float s = d == 0f ? 0f : d / (1f - Math.abs(2f * l - 1f));
        return new float[]{hue(sr, sg, sb, max, d), s, l, a};
    }

    /** {@code {L, a, b, alpha}}: Ottosson's OKLab. */
    public float[] toOklab() {
        double l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b);
        double m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b);
        double s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b);
        return new float[]{(float) (0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s),
                (float) (1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s),
                (float) (0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s), a};
    }

    /** {@code {L, C, h, alpha}}: OKLab in polar form, hue in degrees (0 where there is no chroma). */
    public float[] toOklch() {
        float[] lab = toOklab();
        float c = (float) Math.hypot(lab[1], lab[2]);
        float h = c < 1e-6f ? 0f : (float) Math.toDegrees(Math.atan2(lab[2], lab[1]));
        return new float[]{lab[0], c, h < 0f ? h + 360f : h, a};
    }

    // ── Mixing ──────────────────────────────────────────────────────────────

    /** Toward {@code to} by {@code t}, in linear light: what light does. */
    public CgColor lerp(CgColor to, float t) {
        return new CgColor(r + (to.r - r) * t, g + (to.g - g) * t, b + (to.b - b) * t, a + (to.a - a) * t);
    }

    /** Toward {@code to} by {@code t} on the sRGB-encoded channels: what an image editor does. */
    public CgColor lerpSrgb(CgColor to, float t) {
        return srgb(srgb(r) + (srgb(to.r) - srgb(r)) * t, srgb(g) + (srgb(to.g) - srgb(g)) * t,
                srgb(b) + (srgb(to.b) - srgb(b)) * t, a + (to.a - a) * t);
    }

    /** Toward {@code to} by {@code t} in OKLab: even steps to the eye, CSS's default for gradients and mixes. */
    public CgColor lerpOklab(CgColor to, float t) {
        float[] x = toOklab(), y = to.toOklab();
        return oklab(x[0] + (y[0] - x[0]) * t, x[1] + (y[1] - x[1]) * t, x[2] + (y[2] - x[2]) * t,
                a + (to.a - a) * t);
    }

    /** Its rgb multiplied by its alpha: what a premultiplied target holds. */
    public CgColor premultiply() {
        return new CgColor(r * a, g * a, b * a, a);
    }

    /** Its rgb divided by its alpha, the inverse of {@link #premultiply}; transparent black stays so. */
    public CgColor unpremultiply() {
        return a <= 0f ? TRANSPARENT : new CgColor(r / a, g / a, b / a, a);
    }

    /** Its rgb times {@code k}, alpha kept: {@code k > 1} past white, for an HDR target or bloom. */
    public CgColor intensity(float k) {
        return new CgColor(r * k, g * k, b * k, a);
    }

    /** Each channel clamped to 0 to 1. */
    public CgColor clamp() {
        return new CgColor(clamp01(r), clamp01(g), clamp01(b), clamp01(a));
    }

    /** Whether a channel is past white. */
    public boolean isHdr() {
        return r > 1f || g > 1f || b > 1f;
    }

    // ── Measures ────────────────────────────────────────────────────────────

    /** Relative luminance, Rec. 709 / sRGB primaries, of the clamped colour, alpha ignored: WCAG's {@code L}. */
    public float luminance() {
        return 0.2126f * clamp01(r) + 0.7152f * clamp01(g) + 0.0722f * clamp01(b);
    }

    /** WCAG 2's contrast ratio with {@code other}, 1 to 21, either order: 4.5 is AA for body text, 3 for large. */
    public double contrast(CgColor other) {
        double x = luminance(), y = other.luminance();
        return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05);
    }

    // ── Copies ──────────────────────────────────────────────────────────────

    public CgColor withRed(float r) {
        return new CgColor(r, g, b, a);
    }

    public CgColor withGreen(float g) {
        return new CgColor(r, g, b, a);
    }

    public CgColor withBlue(float b) {
        return new CgColor(r, g, b, a);
    }

    public CgColor withAlpha(float a) {
        return new CgColor(r, g, b, a);
    }

    // ── Channels ────────────────────────────────────────────────────────────

    /** Linear channels to sRGB {@code 0xAARRGGBB}, each clamped to 0 to 1: a colour packed without a record. */
    public static int toArgb(float r, float g, float b, float a) {
        return byteOf(a) << 24 | byteOf(srgb(r)) << 16 | byteOf(srgb(g)) << 8 | byteOf(srgb(b));
    }

    /** An sRGB channel, 0 to 1, in linear light; clamped to 0 to 1. */
    public static float linear(float srgb) {
        if (srgb <= 0f) return 0f;
        if (srgb >= 1f) return 1f;
        return srgb <= 0.04045f ? srgb / 12.92f : (float) Math.pow((srgb + 0.055f) / 1.055f, 2.4);
    }

    /** A linear channel, 0 to 1, as sRGB; clamped to 0 to 1. */
    public static float srgb(float linear) {
        if (linear <= 0f) return 0f;
        if (linear >= 1f) return 1f;
        return linear <= 0.0031308f ? linear * 12.92f : 1.055f * (float) Math.pow(linear, 1 / 2.4) - 0.055f;
    }

    private static int byteOf(float value) {
        return Math.round(clamp01(value) * 255f);
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    /** The sRGB channels of a hue with chroma {@code c}, each raised by {@code m}. */
    private static float[] hueRgb(float h, float c, float m) {
        float hh = ((h % 360f) + 360f) % 360f / 60f;
        float x = c * (1f - Math.abs(hh % 2f - 1f));
        float r, g, b;
        if (hh < 1f) { r = c; g = x; b = 0f; }
        else if (hh < 2f) { r = x; g = c; b = 0f; }
        else if (hh < 3f) { r = 0f; g = c; b = x; }
        else if (hh < 4f) { r = 0f; g = x; b = c; }
        else if (hh < 5f) { r = x; g = 0f; b = c; }
        else { r = c; g = 0f; b = x; }
        return new float[]{r + m, g + m, b + m};
    }

    private static float hue(float r, float g, float b, float max, float d) {
        if (d == 0f) return 0f;
        float h;
        if (max == r) h = ((g - b) / d) % 6f;
        else if (max == g) h = (b - r) / d + 2f;
        else h = (r - g) / d + 4f;
        h *= 60f;
        return h < 0f ? h + 360f : h;
    }

    // CSS Color 4's numbers: a hue's units, a percentage's reference
    private static float angle(String t) {
        if (t.endsWith("deg")) return Float.parseFloat(t.substring(0, t.length() - 3));
        if (t.endsWith("grad")) return Float.parseFloat(t.substring(0, t.length() - 4)) * 0.9f;
        if (t.endsWith("rad")) return (float) Math.toDegrees(Float.parseFloat(t.substring(0, t.length() - 3)));
        if (t.endsWith("turn")) return Float.parseFloat(t.substring(0, t.length() - 4)) * 360f;
        return Float.parseFloat(t);
    }

    private static float percent(String t) {
        return t.endsWith("%") ? Float.parseFloat(t.substring(0, t.length() - 1)) / 100f : Float.parseFloat(t) / 100f;
    }

    private static float alpha(String t) {
        return t.endsWith("%") ? Float.parseFloat(t.substring(0, t.length() - 1)) / 100f : Float.parseFloat(t);
    }

    /** OKLab's lightness: a percentage of 1, or the number. */
    private static float lightness(String t) {
        return t.endsWith("%") ? Float.parseFloat(t.substring(0, t.length() - 1)) / 100f : Float.parseFloat(t);
    }

    /** OKLab's a, b and chroma: a percentage of 0.4, or the number. */
    private static float chroma(String t) {
        return t.endsWith("%") ? Float.parseFloat(t.substring(0, t.length() - 1)) * 0.004f : Float.parseFloat(t);
    }
}
