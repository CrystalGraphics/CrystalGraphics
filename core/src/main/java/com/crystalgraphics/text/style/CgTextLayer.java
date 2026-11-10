package com.crystalgraphics.text.style;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * One layer of a {@link CgTextStyle}: the glyphs themselves ({@link Kind#FILL}), grown ({@link Kind#OUTLINE}), or cast
 * aside ({@link Kind#SHADOW}), each painted on its own, bottom to top. Slate draws its drop shadow, its outline and its
 * fill as three elements, each with its own material ({@code SlateTextRun.cpp:153-198},
 * {@code ElementBatcher.cpp:1499-1521}); a style stacks as many of each as it lists, as Godot's {@code LabelSettings}
 * stacks outlines and shadows.
 *
 * <pre>{@code
 * CgTextLayer fill = CgTextLayer.fill("fill").paint(CgTextPaint.colour(0xFFFFFFFF)).build();
 * CgTextLayer outline = CgTextLayer.outline("outline").width(3f).paint(CgTextPaint.colour(0xFF000000)).build();
 * CgTextLayer drop = CgTextLayer.shadow("shadow").offset(2f, 3f).outlined(true).paint(CgTextPaint.colour(0xC0000000)).build();
 * CgTextLayer glow = CgTextLayer.shadow("glow").spread(2f).blur(4f).blend(CgTextLayer.Blend.ADDITIVE)
 *         .paint(CgTextPaint.colour(0x80FFD040)).build();
 * }</pre>
 *
 * <ul>
 *   <li>Lengths are Slate units: pixels at the style's size, 96 DPI, scale 1, scaling with the text (Slate's
 *       {@code OutlineSize}).</li>
 *   <li>An outline under a fill is the glyph grown by its width, as Slate's: the fill covers its inside. Over a fill it
 *       is the ring alone, as CSS's stroke painted over its fill.</li>
 *   <li>A shadow takes a colour or a material: a gradient on a shadow fails at load.</li>
 * </ul>
 */
public final class CgTextLayer {

    /**
     * The widest outline every face's distance field describes cleanly, in em: half the narrow band's range, less a
     * texel the generator keeps back and one for the bilinear footprint, 4.5 of 80 texels. A face with no dense script
     * is banded wider and carries 0.131 em: ask {@code CgFontRegistry.maxStrokeWidthEm(family)} for the one that
     * applies.
     *
     * <ul>
     *   <li>An outline reads the stored field, which saturates past its range; the shader clamps a wider one to what
     *       the field holds, a pixel short of saturation, rather than filling the glyph's cell.</li>
     *   <li>Path stroking is not the way past it: FreeType's stroker self-intersects in tight concavities. Widening
     *       the stored range is. @see CgStrokeFieldRangeTest</li>
     *   <li>An outline pulls its draw onto the distance-field tier down to the size the field can antialias
     *       ({@code CgMsdfAtlasConfig.minAntialiasablePx()}); smaller, the glyph is a bitmap and the outline is not
     *       drawn.</li>
     *   <li>A mitre reads the median of the field and a round join the true distance (MTSDF's fourth channel); they
     *       differ at corners alone.</li>
     * </ul>
     */
    public static final float MAX_FIELD_WIDTH_EM = 4.5f / 80f;

    /** What the layer draws of the glyphs. */
    public enum Kind { FILL, OUTLINE, SHADOW }

    /** How the layer lands on what is beneath it. */
    public enum Blend {
        /** Over it, by the paint's alpha. */
        ALPHA,
        /** Added to it, weighted by the paint's alpha: a glow, a flash. */
        ADDITIVE
    }

    /** How an outline turns a corner: {@code bMiteredCorners}. */
    public enum Join { ROUND, MITRE }

    /** Where an outline sits on the glyph's edge. */
    public enum Align { OUTSIDE, CENTRE, INSIDE }

    private final String id;
    private final Kind kind;
    private final CgTextPaint paint;
    private final float opacity;
    private final Blend blend;
    private final float width;
    private final Join join;
    private final Align align;
    private final float offsetX, offsetY;
    private final boolean outlined;
    private final float spread;
    private final float blur;
    private final boolean inset;

    private CgTextLayer(Builder b) {
        this.id = b.id;
        this.kind = b.kind;
        this.paint = b.paint;
        this.opacity = b.opacity;
        this.blend = b.blend;
        this.width = b.width;
        this.join = b.join;
        this.align = b.align;
        this.offsetX = b.offsetX;
        this.offsetY = b.offsetY;
        this.outlined = b.outlined;
        this.spread = b.spread;
        this.blur = b.blur;
        this.inset = b.inset;
    }

    /** A fill layer: the glyphs. */
    public static Builder fill(String id) {
        return new Builder(id, Kind.FILL);
    }

    /** An outline layer: the glyphs grown by {@link Builder#width}. */
    public static Builder outline(String id) {
        return new Builder(id, Kind.OUTLINE);
    }

    /** A shadow layer: the glyphs offset, grown and blurred. */
    public static Builder shadow(String id) {
        return new Builder(id, Kind.SHADOW);
    }

    public String id() {
        return id;
    }

    public Kind kind() {
        return kind;
    }

    public CgTextPaint paint() {
        return paint;
    }

    /** 0 to 1, times the paint's alpha. */
    public float opacity() {
        return opacity;
    }

    public Blend blend() {
        return blend;
    }

    /** An outline's width, Slate units. */
    public float width() {
        return width;
    }

    public Join join() {
        return join;
    }

    public Align align() {
        return align;
    }

    /** A shadow's offset, Slate units, y down. */
    public float offsetX() {
        return offsetX;
    }

    public float offsetY() {
        return offsetY;
    }

    /** Whether a shadow casts the style's first outline's shape: {@code bApplyOutlineToDropShadows}. */
    public boolean outlined() {
        return outlined;
    }

    /** How far a shadow's shape grows, Slate units. */
    public float spread() {
        return spread;
    }

    /** A shadow's Gaussian sigma, Slate units; 0 is hard. */
    public float blur() {
        return blur;
    }

    /** Whether a shadow is cast inside the glyph, clipped to it: an inner glow or a sheen. */
    public boolean inset() {
        return inset;
    }

    /** As its file writes it: what differs from the defaults, in the declaration's order. */
    Map<String, Object> toTree() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("kind", kind.name().toLowerCase(Locale.ROOT));
        if (!paint.equals(CgTextPaint.WHITE)) m.put("paint", paint.toTree());
        if (opacity != 1f) m.put("opacity", (double) opacity);
        if (blend != Blend.ALPHA) m.put("blend", blend.name().toLowerCase(Locale.ROOT));
        if (kind == Kind.OUTLINE) {
            m.put("width", (double) width);
            if (join != Join.ROUND) m.put("join", join.name().toLowerCase(Locale.ROOT));
            if (align != Align.OUTSIDE) m.put("align", align.name().toLowerCase(Locale.ROOT));
        }
        if (kind == Kind.SHADOW) {
            if (offsetX != 0f || offsetY != 0f) m.put("offset", List.of((double) offsetX, (double) offsetY));
            if (outlined) m.put("outlined", true);
            if (spread != 0f) m.put("spread", (double) spread);
            if (blur != 0f) m.put("blur", (double) blur);
            if (inset) m.put("inset", true);
        }
        return m;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof CgTextLayer l && id.equals(l.id) && kind == l.kind && paint.equals(l.paint)
                && opacity == l.opacity && blend == l.blend && width == l.width && join == l.join && align == l.align
                && offsetX == l.offsetX && offsetY == l.offsetY && outlined == l.outlined && spread == l.spread
                && blur == l.blur && inset == l.inset;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, kind, paint, opacity, blend, width, join, align, offsetX, offsetY, outlined, spread,
                blur, inset);
    }

    @Override
    public String toString() {
        return kind.name().toLowerCase(Locale.ROOT) + " " + id + " " + paint;
    }

    /** Builds a layer; every field but its id and kind has Slate's default. */
    public static final class Builder {
        private final String id;
        private final Kind kind;
        private CgTextPaint paint = CgTextPaint.WHITE;
        private float opacity = 1f;
        private Blend blend = Blend.ALPHA;
        private float width;
        private Join join = Join.ROUND;
        private Align align = Align.OUTSIDE;
        private float offsetX, offsetY;
        private boolean outlined;
        private float spread;
        private float blur;
        private boolean inset;

        private Builder(String id, Kind kind) {
            this.id = Objects.requireNonNull(id, "id");
            this.kind = kind;
        }

        public Builder paint(CgTextPaint paint) {
            this.paint = Objects.requireNonNull(paint, "paint");
            return this;
        }

        /** A colour paint, {@code 0xAARRGGBB}. */
        public Builder paint(int argb) {
            return paint(CgTextPaint.colour(argb));
        }

        public Builder opacity(float opacity) {
            this.opacity = opacity;
            return this;
        }

        public Builder blend(Blend blend) {
            this.blend = Objects.requireNonNull(blend, "blend");
            return this;
        }

        /** An outline's width, Slate units. */
        public Builder width(float width) {
            this.width = width;
            return this;
        }

        public Builder join(Join join) {
            this.join = Objects.requireNonNull(join, "join");
            return this;
        }

        public Builder align(Align align) {
            this.align = Objects.requireNonNull(align, "align");
            return this;
        }

        /** A shadow's offset, Slate units, y down. */
        public Builder offset(float x, float y) {
            this.offsetX = x;
            this.offsetY = y;
            return this;
        }

        public Builder outlined(boolean outlined) {
            this.outlined = outlined;
            return this;
        }

        public Builder spread(float spread) {
            this.spread = spread;
            return this;
        }

        public Builder blur(float sigma) {
            this.blur = sigma;
            return this;
        }

        public Builder inset(boolean inset) {
            this.inset = inset;
            return this;
        }

        /**
         * The layer.
         *
         * @throws IllegalArgumentException for a field its kind does not take, an opacity outside 0 to 1, a negative
         *                                  width or blur, or a gradient on a shadow
         */
        public CgTextLayer build() {
            String at = "layer " + id + ": ";
            if (!(opacity >= 0f && opacity <= 1f)) throw new IllegalArgumentException(at + "opacity " + opacity + " is not 0 to 1");
            if (kind != Kind.OUTLINE && (width != 0f || join != Join.ROUND || align != Align.OUTSIDE)) {
                throw new IllegalArgumentException(at + "width, join and align are an outline's");
            }
            if (kind == Kind.OUTLINE && !(width >= 0f)) throw new IllegalArgumentException(at + "width " + width + " is negative");
            if (kind != Kind.SHADOW && (offsetX != 0f || offsetY != 0f || outlined || spread != 0f || blur != 0f || inset)) {
                throw new IllegalArgumentException(at + "offset, outlined, spread, blur and inset are a shadow's");
            }
            if (!(blur >= 0f)) throw new IllegalArgumentException(at + "blur " + blur + " is negative");
            if (kind == Kind.SHADOW && paint.kind() == CgTextPaint.Kind.GRADIENT) {
                throw new IllegalArgumentException(at + "a shadow is painted by a colour or a material, not a gradient");
            }
            return new CgTextLayer(this);
        }
    }
}
