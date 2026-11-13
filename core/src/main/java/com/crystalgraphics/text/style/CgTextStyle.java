package com.crystalgraphics.text.style;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import javax.annotation.Nullable;

import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.font.CgFontStyle;
import com.crystalgraphics.api.font.CgGenericFamily;
import com.crystalgraphics.api.font.CgSystemFontFace;
import com.crystalgraphics.api.font.CgSystemFonts;
import com.crystalgraphics.api.material.CgMaterialDefinition;
import com.crystalgraphics.api.material.CgMaterialDomain;
import com.crystalgraphics.asset.kind.CgLookKinds;
import com.crystalgraphics.asset.load.CgAssetCompiler;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.asset.tree.CgAssetException;
import com.crystalgraphics.object.CgType;
import com.crystalgraphics.util.io.CgIO;

/**
 * How text looks, apart from what it says: a face and a stack of {@link CgTextLayer}s, shared by every text draw that
 * names it. Unreal's {@code FTextBlockStyle} with its {@code FSlateFontInfo}, shared as Common UI's
 * {@code UCommonTextStyle}; a {@code text_styles/} asset compiles to one.
 *
 * <pre>{@code
 * // in Java: a title, white on a black outline, with a soft drop shadow
 * static final CgTextStyle TITLE = CgTextStyle.builder("mymod:text_styles/title")
 *         .font("mymod:fonts/title.ttf").size(32f)
 *         .layer(CgTextLayer.shadow("shadow").offset(0f, 2f).blur(2f).paint(0x80000000))
 *         .layer(CgTextLayer.outline("outline").width(2f).paint(0xFF000000))
 *         .layer(CgTextLayer.fill("fill").paint(0xFFFFFFFF))
 *         .build();
 * }</pre>
 *
 * <pre>{@code
 * # the same as data, assets/mymod/text_styles/title.yaml
 * _v: 1
 * font: mymod:fonts/title.ttf
 * size: 32
 * layers:
 *   - { id: shadow, kind: shadow, offset: [0, 2], blur: 2, paint: 0x80000000 }
 *   - { id: outline, kind: outline, width: 2, paint: 0xFF000000 }
 *   - { id: fill, kind: fill, paint: 0xFFFFFFFF }
 * }</pre>
 *
 * <pre>{@code
 * # a variant: assets/mymod/text_styles/title_gold.yaml changes the fill alone, by its id
 * _v: 1
 * parent: mymod:text_styles/title
 * layers:
 *   - { id: fill, paint: { gradient: { angle: 90, stops: [[0, 0xFFFFF0A0], [1, 0xFFE09000]] } } }
 * }</pre>
 *
 * <pre>{@code
 * # an installed family rather than a file: the platform's sans-serif (Arial on Windows), bold
 * font: system:sans-serif
 * bold: true
 * }</pre>
 *
 * <ul>
 *   <li>{@code size} is in points at Slate's 96 DPI: {@link #px()} pixels at scale 1. Every layer length is in those
 *       pixels and scales with the text.</li>
 *   <li>No layers draws one white fill, Slate's default.</li>
 *   <li>A draw's colour tints every layer; it never sets the fill.</li>
 *   <li>A material paint must be a {@code Text}-domain material: anything else fails at load, naming it.</li>
 * </ul>
 */
public final class CgTextStyle {

    /** A string's case as drawn: Slate's {@code ETextTransformPolicy}. */
    public enum Transform { NONE, UPPER, LOWER }

    /**
     * What a draw's glyphs are made of. {@code AUTO} takes a bitmap rastered at the draw's size below
     * {@link com.crystalgraphics.text.render.CgTextRaster#MSDF_ENTER} px and the distance field from there; {@code MSDF}
     * and {@code BITMAP} fix it. A distance field is one resolution-independent glyph, so it suits text whose size
     * animates; a bitmap is sharpest at its own size.
     *
     * <p>A bitmap is a request, not a promise: an outline, a skew or a rotated or sheared pose still take the distance
     * field, which is all that can draw them.</p>
     */
    public enum Raster { AUTO, MSDF, BITMAP }

    /** The text style kind's compiler. */
    public static final CgAssetCompiler<CgTextStyle> COMPILER = CgTextStyle::compile;

    private static final String SYSTEM = "system:";

    private static final List<CgTextLayer> DEFAULT_LAYERS = List.of(CgTextLayer.fill("fill").build());

    /** No font, one white fill: what a draw is in until it names a style. */
    public static final CgTextStyle PLAIN = new CgTextStyle(null, null, false, 24f, 0, 0f, Transform.NONE, Raster.AUTO, List.of());

    @Nullable
    private final String id;
    @Nullable
    private final String font;
    private final boolean bold;
    private final float size;
    private final int letterSpacing;
    private final float skew;
    private final Transform transform;
    private final Raster raster;
    private final List<CgTextLayer> layers;
    private final float reach;
    private final Map<Integer, CgFont> fonts = new HashMap<>();

    private CgTextStyle(@Nullable String id, @Nullable String font, boolean bold, float size, int letterSpacing,
                        float skew, Transform transform, Raster raster, List<CgTextLayer> layers) {
        this.id = id;
        this.font = font;
        this.bold = bold;
        this.size = size;
        this.letterSpacing = letterSpacing;
        this.skew = skew;
        this.transform = transform;
        this.raster = raster;
        this.layers = layers.isEmpty() ? DEFAULT_LAYERS : Collections.unmodifiableList(new ArrayList<>(layers));
        this.reach = reachOf(this.layers, size, skew);
    }

    private static float reachOf(List<CgTextLayer> layers, float size, float skew) {
        float outline = 0f;
        for (CgTextLayer l : layers) if (l.kind() == CgTextLayer.Kind.OUTLINE) outline = Math.max(outline, grows(l));
        float reach = 0f;
        for (CgTextLayer l : layers) {
            float r = switch (l.kind()) {
                case FILL -> 0f;
                case OUTLINE -> grows(l);
                // A Gaussian is drawn to three sigma; an inset shadow stays inside the glyph.
                case SHADOW -> l.inset() ? 0f : Math.max(Math.abs(l.offsetX()), Math.abs(l.offsetY())) + l.spread()
                        + (l.outlined() ? outline : 0f) + 3f * l.blur();
            };
            reach = Math.max(reach, r);
        }
        // A slant moves a glyph's top past its advance one way and its descent the other.
        return reach + Math.abs(skew) * size;
    }

    private static float grows(CgTextLayer outline) {
        return switch (outline.align()) {
            case OUTSIDE -> outline.width();
            case CENTRE -> outline.width() / 2f;
            case INSIDE -> 0f;
        };
    }

    /** Registers {@link #COMPILER} as the text style kind's. */
    public static void register() {
        CgAssetCompiler.register(CgLookKinds.TEXT_STYLE, COMPILER);
    }

    /** A style to build in Java; {@code id} names it in errors and editors, and may be null. */
    public static Builder builder(@Nullable String id) {
        return new Builder(id);
    }

    /**
     * {@code asset}'s style: its face and its layers, resolved over its parent, each material paint compiled.
     *
     * @throws CgAssetException a layer without a kind, a field its kind does not take, a material that is not a text
     *                          material, or no font, naming the asset and the path
     */
    public static CgTextStyle compile(CgAssetData asset, CgAssetCompiler.Dependencies deps) {
        Map<Object, Object> tree = asset.resolved();
        String id = asset.id();
        Builder b = new Builder(id);
        if (tree.get("font") instanceof String f) b.font(f);
        if (tree.get("bold") instanceof Boolean bold) b.bold(bold);
        if (tree.get("size") instanceof Number n) b.size(n.floatValue());
        if (tree.get("letter_spacing") instanceof Number n) b.letterSpacing(n.intValue());
        if (tree.get("skew") instanceof Number n) b.skew(n.floatValue());
        if (tree.get("transform") instanceof String t) b.transform(Transform.valueOf(t.toUpperCase(Locale.ROOT)));
        if (tree.get("raster") instanceof String r) b.raster(Raster.valueOf(r.toUpperCase(Locale.ROOT)));
        if (tree.get("layers") instanceof List<?> list) {
            for (Object o : list) {
                Map<?, ?> m = (Map<?, ?>) o;
                if (Boolean.FALSE.equals(m.get("enabled"))) continue;
                String layer = String.valueOf(m.get("id"));
                String at = id + ": layers." + layer;
                try {
                    b.layer(layer(m, layer, at, deps));
                } catch (IllegalArgumentException e) {
                    throw new CgAssetException(at + ": " + e.getMessage());
                }
            }
        }
        if (b.font == null) throw new CgAssetException(id + ": font: a text style needs a font, its own or its parent's");
        return b.build();
    }

    private static CgTextLayer layer(Map<?, ?> m, String id, String at, CgAssetCompiler.Dependencies deps) {
        if (!(m.get("kind") instanceof String kind)) throw new CgAssetException(at + ".kind: a layer needs a kind");
        CgTextLayer.Builder l = switch (kind) {
            case "fill" -> CgTextLayer.fill(id);
            case "outline" -> CgTextLayer.outline(id);
            default -> CgTextLayer.shadow(id);
        };
        if (m.containsKey("paint")) l.paint(paint(m.get("paint"), at + ".paint", deps));
        if (m.get("opacity") instanceof Number n) l.opacity(n.floatValue());
        if (m.get("blend") instanceof String s) l.blend(CgTextLayer.Blend.valueOf(s.toUpperCase(Locale.ROOT)));
        if (m.get("width") instanceof Number n) l.width(n.floatValue());
        if (m.get("join") instanceof String s) l.join(CgTextLayer.Join.valueOf(s.toUpperCase(Locale.ROOT)));
        if (m.get("align") instanceof String s) l.align(CgTextLayer.Align.valueOf(s.toUpperCase(Locale.ROOT)));
        if (m.get("offset") instanceof List<?> o) l.offset(((Number) o.get(0)).floatValue(), ((Number) o.get(1)).floatValue());
        if (m.get("outlined") instanceof Boolean v) l.outlined(v);
        if (m.get("spread") instanceof Number n) l.spread(n.floatValue());
        if (m.get("blur") instanceof Number n) l.blur(n.floatValue());
        if (m.get("inset") instanceof Boolean v) l.inset(v);
        return l.build();
    }

    private static CgTextPaint paint(Object p, String at, CgAssetCompiler.Dependencies deps) {
        if (p instanceof Number n) return CgTextPaint.colour((int) n.longValue());
        Map<?, ?> m = (Map<?, ?>) p;
        if (m.get("material") instanceof String material) {
            if (!(deps.definition(CgLookKinds.MATERIAL, material) instanceof CgMaterialDefinition d)) {
                throw new CgAssetException(at + ": material " + material + " did not compile");
            }
            d.requireDomain(CgMaterialDomain.UI, at);
            return CgTextPaint.material(material).resolved(d);
        }
        Map<?, ?> g = (Map<?, ?>) m.get("gradient");
        CgTextPaint.Space space = g.get("space") instanceof String s
                ? CgTextPaint.Space.valueOf(s.toUpperCase(Locale.ROOT)) : CgTextPaint.Space.GLYPH;
        float angle = g.get("angle") instanceof Number n ? n.floatValue() : 90f;
        List<?> stops = (List<?>) g.get("stops");
        float[] t = new float[stops.size()];
        int[] c = new int[stops.size()];
        for (int i = 0; i < t.length; i++) {
            List<?> stop = (List<?>) stops.get(i);
            if (stop.size() != 2) throw new CgAssetException(at + ".gradient.stops[" + i + "]: a stop is [t, colour]");
            t[i] = ((Number) stop.get(0)).floatValue();
            c[i] = (int) ((Number) stop.get(1)).longValue();
        }
        return CgTextPaint.gradient(space, angle, t, c);
    }

    /** Its id; null for a style built in Java without one. */
    @Nullable
    public String id() {
        return id;
    }

    /** Its font: a file, {@code <ns>:fonts/<path>}, or an installed family, {@code system:<family>}. */
    @Nullable
    public String font() {
        return font;
    }

    /** Whether it draws the font's bold face (Slate's typeface). */
    public boolean bold() {
        return bold;
    }

    /** In points at 96 DPI. */
    public float size() {
        return size;
    }

    /** Its size in pixels at scale 1: points at Slate's 96 DPI. */
    public float px() {
        return size * 96f / 72f;
    }

    /** Thousandths of an em added to each advance: Slate's {@code LetterSpacing}. */
    public int letterSpacing() {
        return letterSpacing;
    }

    /** Each point moves right by this times its height above the baseline: Slate's {@code SkewAmount}. */
    public float skew() {
        return skew;
    }

    public Transform transform() {
        return transform;
    }

    /** Whether its glyphs are a distance field, a bitmap at the draw's size, or whichever the size picks. */
    public Raster raster() {
        return raster;
    }

    /** Its layers, bottom to top; one white fill when it lists none. */
    public List<CgTextLayer> layers() {
        return layers;
    }

    /**
     * How far its ink can reach past the box its text lays out in, on any side, in units at its size: its widest
     * outline, its shadows' offset, spread and three of their blur, and its slant over its size. What a retainer box
     * pads its image by, as a browser grows a layer by its content's ink overflow, so nothing is cut.
     *
     * <pre>{@code
     * float pad = style.reach();   // a 10-unit outline under a 4.5-blur drop shadow offset 12: 25.5, plus the slant
     * }</pre>
     */
    public float reach() {
        return reach;
    }

    /** {@code text} in its case: the same instance when nothing changes. */
    public String transform(String text) {
        return switch (transform) {
            case NONE -> text;
            case UPPER -> text.toUpperCase(Locale.ROOT);
            case LOWER -> text.toLowerCase(Locale.ROOT);
        };
    }

    /**
     * Its font at {@code px} pixels, read once and kept per size. Client, the render thread or a loading task: the
     * first call per size reads the face, and the first {@code system:} font scans the installed fonts, so call it
     * from a load task before the first draw.
     *
     * @throws IllegalStateException when the file is missing or unreadable, or no installed family has the name
     */
    public synchronized CgFont font(int px) {
        CgFont f = fonts.get(px);
        if (f != null) return f;
        if (font == null) throw new IllegalStateException((id == null ? "a text style" : id) + " has no font");
        CgFontStyle style = bold ? CgFontStyle.BOLD : CgFontStyle.REGULAR;
        if (font.startsWith(SYSTEM)) {
            f = installed(font.substring(SYSTEM.length()), style, px);
            fonts.put(px, f);
            return f;
        }
        try (InputStream in = CgIO.openStream(font)) {
            if (in == null) throw new IllegalStateException("no font file " + font);
            f = CgFont.load(in.readAllBytes(), font, style, px);
        } catch (IOException e) {
            throw new IllegalStateException("font " + font + ": " + e.getMessage(), e);
        }
        fonts.put(px, f);
        return f;
    }

    /** {@code family}'s installed face: a CSS generic as the platform means it, else the family by name. */
    private CgFont installed(String family, CgFontStyle style, int px) {
        CgSystemFonts fonts = CgSystemFonts.get();
        CgGenericFamily generic = CgGenericFamily.fromCss(family);
        CgSystemFontFace face = generic != null ? fonts.generic(generic, style) : fonts.find(family, style);
        if (face == null) {
            throw new IllegalStateException((id == null ? "a text style" : id) + ": no installed font family " + family);
        }
        return fonts.load(face, style, px);
    }

    /** As its file writes it, with no parent: the tree {@link #compile} reads back to an equal style. */
    public Map<String, Object> toTree() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(CgType.VERSION, 1L);
        if (font != null) m.put("font", font);
        if (bold) m.put("bold", true);
        m.put("size", (double) size);
        if (letterSpacing != 0) m.put("letter_spacing", (long) letterSpacing);
        if (skew != 0f) m.put("skew", (double) skew);
        if (transform != Transform.NONE) m.put("transform", transform.name().toLowerCase(Locale.ROOT));
        if (raster != Raster.AUTO) m.put("raster", raster.name().toLowerCase(Locale.ROOT));
        List<Object> list = new ArrayList<>();
        for (CgTextLayer l : layers) list.add(l.toTree());
        m.put("layers", list);
        return m;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof CgTextStyle s && Objects.equals(id, s.id) && Objects.equals(font, s.font) && bold == s.bold
                && size == s.size && letterSpacing == s.letterSpacing && skew == s.skew && transform == s.transform
                && raster == s.raster && layers.equals(s.layers);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, font, bold, size, letterSpacing, skew, transform, raster, layers);
    }

    @Override
    public String toString() {
        return (id == null ? "text style" : id) + " " + layers;
    }

    /** Builds a style: 24 points, no spacing or skew, and the layers added, bottom to top. */
    public static final class Builder {
        @Nullable
        private final String id;
        private String font;
        private boolean bold;
        private float size = 24f;
        private int letterSpacing;
        private float skew;
        private Transform transform = Transform.NONE;
        private Raster raster = Raster.AUTO;
        private final List<CgTextLayer> layers = new ArrayList<>();

        private Builder(@Nullable String id) {
            this.id = id;
        }

        /**
         * Its font: a file, {@code <ns>:fonts/<path>.otf} or {@code .ttf}; or an installed family,
         * {@code system:<family>}, a CSS generic ({@code system:sans-serif}, {@code system:monospace}) or a family by
         * name ({@code system:Segoe UI}). A named family the machine lacks fails at {@link CgTextStyle#font(int)}.
         */
        public Builder font(String font) {
            this.font = Objects.requireNonNull(font, "font");
            return this;
        }

        public Builder bold(boolean bold) {
            this.bold = bold;
            return this;
        }

        /** In points at 96 DPI. */
        public Builder size(float points) {
            this.size = points;
            return this;
        }

        /** Thousandths of an em. */
        public Builder letterSpacing(int thousandths) {
            this.letterSpacing = thousandths;
            return this;
        }

        /** -5 to 5. */
        public Builder skew(float skew) {
            this.skew = skew;
            return this;
        }

        public Builder transform(Transform transform) {
            this.transform = Objects.requireNonNull(transform, "transform");
            return this;
        }

        /** {@link Raster}: {@code AUTO} unless set. {@code MSDF} for text whose size animates. */
        public Builder raster(Raster raster) {
            this.raster = Objects.requireNonNull(raster, "raster");
            return this;
        }

        /** Adds a layer on top of those added. */
        public Builder layer(CgTextLayer layer) {
            for (CgTextLayer l : layers) {
                if (l.id().equals(layer.id())) throw new IllegalArgumentException("a layer is already named " + layer.id());
            }
            layers.add(layer);
            return this;
        }

        /** Adds the layer {@code layer} builds. */
        public Builder layer(CgTextLayer.Builder layer) {
            return layer(layer.build());
        }

        /**
         * The style.
         *
         * @throws IllegalArgumentException for a size not above 0, a skew outside -5 to 5
         */
        public CgTextStyle build() {
            if (!(size > 0f)) throw new IllegalArgumentException("size " + size + " is not above 0");
            if (!(skew >= -5f && skew <= 5f)) throw new IllegalArgumentException("skew " + skew + " is not -5 to 5");
            return new CgTextStyle(id, font, bold, size, letterSpacing, skew, transform, raster, layers);
        }
    }
}
