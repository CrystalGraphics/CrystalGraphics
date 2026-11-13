package com.crystalgraphics.text.style;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import javax.annotation.Nullable;

import com.crystalgraphics.api.material.CgMaterialDefinition;
import com.crystalgraphics.api.material.CgMaterialDomain;
import com.crystalgraphics.asset.tree.CgAssetException;

/**
 * What a {@link CgTextLayer} is painted with: a colour, a gradient, or a text material (Unreal's font material). The
 * layer's coverage multiplies whichever it is, so a paint never decides a glyph's shape.
 *
 * <pre>{@code
 * CgTextPaint white = CgTextPaint.colour(0xFFFFFFFF);
 * CgTextPaint gold = CgTextPaint.gradient(CgTextPaint.Space.GLYPH, 90f,     // top to bottom of each glyph
 *         0f, 0xFFFFF0A0, 1f, 0xFFE09000);                                   // stops: t, then colour
 * CgTextPaint shimmer = CgTextPaint.material(shimmerDefinition);              // a UI material on Primitive Text
 * }</pre>
 *
 * <ul>
 *   <li>Colours are {@code 0xAARRGGBB}, straight alpha. A gradient interpolates its stops as written, in sRGB.</li>
 *   <li>An angle is in degrees: 0 left to right, 90 top to bottom (y down).</li>
 *   <li>A gradient's stops ascend in {@code t}, from 0 to 1; before the first and past the last they hold.</li>
 * </ul>
 */
public final class CgTextPaint {

    /** What a gradient's 0 to 1 spans. */
    public enum Space {
        /** Each glyph's own quad: every character graded alike, as TextMeshPro's per-character gradient. */
        GLYPH,
        /** Each line's box. */
        LINE,
        /** The whole text, as Slate's font-material UVs span the run. */
        RUN
    }

    /** What kind of paint it is. */
    public enum Kind { COLOUR, GRADIENT, MATERIAL }

    /** White, the default paint. */
    public static final CgTextPaint WHITE = colour(0xFFFFFFFF);

    private final Kind kind;
    private final int colour;
    private final Space space;
    private final float angle;
    private final float[] stops;
    private final int[] colours;
    @Nullable
    private final String material;
    @Nullable
    private final CgMaterialDefinition definition;

    private CgTextPaint(Kind kind, int colour, Space space, float angle, float[] stops, int[] colours,
                        @Nullable String material, @Nullable CgMaterialDefinition definition) {
        this.kind = kind;
        this.colour = colour;
        this.space = space;
        this.angle = angle;
        this.stops = stops;
        this.colours = colours;
        this.material = material;
        this.definition = definition;
    }

    /** A plain colour, {@code 0xAARRGGBB}. */
    public static CgTextPaint colour(int argb) {
        return new CgTextPaint(Kind.COLOUR, argb, Space.GLYPH, 0f, new float[0], new int[0], null, null);
    }

    /**
     * A linear gradient over {@code space} at {@code angle} degrees, its stops given as pairs: {@code t, argb, t, argb...}.
     *
     * @throws IllegalArgumentException with no stop, a {@code t} outside 0 to 1, or stops not ascending
     */
    public static CgTextPaint gradient(Space space, float angle, Number... stopsThenColours) {
        if (stopsThenColours.length == 0 || stopsThenColours.length % 2 != 0) {
            throw new IllegalArgumentException("a gradient's stops are pairs of t and colour, at least one");
        }
        int n = stopsThenColours.length / 2;
        float[] t = new float[n];
        int[] c = new int[n];
        for (int i = 0; i < n; i++) {
            t[i] = stopsThenColours[2 * i].floatValue();
            c[i] = (int) stopsThenColours[2 * i + 1].longValue();
        }
        return gradient(space, angle, t, c);
    }

    /** As {@link #gradient(Space, float, Number...)}, its stops' positions and colours apart. */
    public static CgTextPaint gradient(Space space, float angle, float[] stops, int[] colours) {
        if (stops.length == 0 || stops.length != colours.length) {
            throw new IllegalArgumentException("a gradient needs as many colours as stops, at least one");
        }
        for (int i = 0; i < stops.length; i++) {
            if (!(stops[i] >= 0f && stops[i] <= 1f)) throw new IllegalArgumentException("stop " + i + " is " + stops[i] + ", not 0 to 1");
            if (i > 0 && stops[i] < stops[i - 1]) throw new IllegalArgumentException("stop " + i + " comes before stop " + (i - 1));
        }
        return new CgTextPaint(Kind.GRADIENT, colours[0], Objects.requireNonNull(space, "space"), angle, stops.clone(),
                colours.clone(), null, null);
    }

    /** A UI material on Primitive Text by id, as a file names one: the asset loader compiles it with the style. */
    public static CgTextPaint material(String id) {
        return new CgTextPaint(Kind.MATERIAL, 0xFFFFFFFF, Space.RUN, 0f, new float[0], new int[0],
                Objects.requireNonNull(id, "id"), null);
    }

    /**
     * A UI material on Primitive Text already compiled: a style built in Java.
     *
     * @throws CgAssetException when it is not a UI material
     */
    public static CgTextPaint material(CgMaterialDefinition definition) {
        definition.requireDomain(CgMaterialDomain.UI, "a text paint");
        return new CgTextPaint(Kind.MATERIAL, 0xFFFFFFFF, Space.RUN, 0f, new float[0], new int[0], definition.id(),
                definition);
    }

    /** This material paint with its compiled definition: the loader's. */
    CgTextPaint resolved(CgMaterialDefinition definition) {
        return new CgTextPaint(kind, colour, space, angle, stops, colours, material, definition);
    }

    public Kind kind() {
        return kind;
    }

    /** A colour paint's colour; a gradient's first stop's; white for a material. */
    public int colour() {
        return colour;
    }

    public Space space() {
        return space;
    }

    /** In degrees: 0 left to right, 90 top to bottom. */
    public float angle() {
        return angle;
    }

    /** How many stops a gradient has; 0 otherwise. */
    public int stops() {
        return stops.length;
    }

    public float stop(int i) {
        return stops[i];
    }

    public int stopColour(int i) {
        return colours[i];
    }

    /** A gradient's colour at {@code t}, {@code 0xAARRGGBB}: interpolated between its stops, held past them. */
    public int at(float t) {
        if (kind != Kind.GRADIENT) return colour;
        if (t <= stops[0]) return colours[0];
        for (int i = 1; i < stops.length; i++) {
            if (t <= stops[i]) {
                float span = stops[i] - stops[i - 1];
                float u = span <= 0f ? 1f : (t - stops[i - 1]) / span;
                return lerp(colours[i - 1], colours[i], u);
            }
        }
        return colours[colours.length - 1];
    }

    /** A material paint's id; null otherwise. */
    @Nullable
    public String material() {
        return material;
    }

    /** A material paint's compiled definition, once the loader has resolved it. */
    @Nullable
    public CgMaterialDefinition definition() {
        return definition;
    }

    /** As its file writes it: a colour, {@code { gradient: ... }} or {@code { material: id }}. */
    Object toTree() {
        if (kind == Kind.COLOUR) return (long) colour & 0xFFFFFFFFL;
        Map<String, Object> m = new LinkedHashMap<>();
        if (kind == Kind.MATERIAL) {
            m.put("material", material);
            return m;
        }
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("space", space.name().toLowerCase(Locale.ROOT));
        g.put("angle", (double) angle);
        Object[] list = new Object[stops.length];
        for (int i = 0; i < stops.length; i++) list[i] = List.of((double) stops[i], (long) colours[i] & 0xFFFFFFFFL);
        g.put("stops", List.of(list));
        m.put("gradient", g);
        return m;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof CgTextPaint p && kind == p.kind && colour == p.colour && space == p.space
                && angle == p.angle && Arrays.equals(stops, p.stops) && Arrays.equals(colours, p.colours)
                && Objects.equals(material, p.material);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, colour, space, angle, Arrays.hashCode(stops), Arrays.hashCode(colours), material);
    }

    @Override
    public String toString() {
        return switch (kind) {
            case COLOUR -> String.format("#%08X", colour);
            case GRADIENT -> "gradient " + space + " " + angle + "° " + stops.length + " stops";
            case MATERIAL -> "material " + material;
        };
    }

    private static int lerp(int a, int b, float u) {
        int out = 0;
        for (int shift = 0; shift < 32; shift += 8) {
            int ca = a >>> shift & 0xFF, cb = b >>> shift & 0xFF;
            out |= Math.round(ca + (cb - ca) * u) << shift;
        }
        return out;
    }
}
