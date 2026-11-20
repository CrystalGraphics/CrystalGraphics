package com.crystalgraphics.api.paint.brush;

import java.util.Objects;

import javax.annotation.Nullable;

import com.crystalgraphics.api.material.CgMaterialDefinition;
import com.crystalgraphics.api.material.CgMaterialDomain;
import com.crystalgraphics.api.paint.CgColor;
import com.crystalgraphics.api.paint.CgPaint;
import com.crystalgraphics.api.texture.CgSampler;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.api.texture.CgTextures;
import com.crystalgraphics.api.vector.svg.CgSvg;

/**
 * An image as paint: a texture or a region of one, stretched, tiled or nine-sliced over the shape it fills, tinted; an
 * SVG document, drawn sharp at any size; or a UI material. Slate's {@code FSlateBrush}, CSS's {@code background-image}.
 * Anything that takes a {@link CgPaint} takes one, and a widget's image and border draw one.
 *
 * <pre>{@code
 * static final CgBrush ICON = CgBrush.texture(CgTextures.of("mymod:textures/gui/save.png"));
 * static final CgBrush DIGIT = CgBrush.texture(digits).region(16, 0, 8, 12);               // pixels of the image
 * static final CgBrush PANEL = CgBrush.texture(frame).nineSlice(CgNineSlice.uniform(4));   // corners stay put
 * static final CgBrush BUTTON = CgBrush.sprite("crystalgui:ore", "button");                // a sprite sheet's entry
 * static final CgBrush FLOOR = CgBrush.texture(tile).tiling(CgTiling.REPEAT);
 * static final CgBrush SEVEN = CgBrush.texture(digitArray).layer(7);                    // a layer of a 2D array
 * static final CgBrush SAVE = CgBrush.svg(CgSvg.of("mymod:ui/icons/save.svg"));               // fitted, centred
 * static final CgBrush BANNER = CgBrush.texture("kk:textures/hud/banner.png").size(224, 56);   // by path, loaded at first draw
 * static final CgBrush SHADED = CgBrush.material(bannerMaterial).size(224, 56);              // a UI-domain material
 *
 * canvas.image(ICON).at(x, y).size(16, 16).submit();
 * canvas.rect(x, y, w, h).fill(PANEL.tint(0xFFC0C0FF)).submit();
 * }</pre>
 *
 * <ul>
 *   <li>A region is in the image's pixels, so it holds when a resource pack swaps in a larger image of the same layout
 *       only if the layout scales; a sprite sheet names its own image size.</li>
 *   <li>{@link #premultiplied()} says what the texture's pixels hold; drawing a straight-alpha image as premultiplied
 *       darkens its edges, the other way round brightens them.</li>
 *   <li>An SVG brush binds the file's {@code currentColor} with {@link #color}, black by default, and multiplies
 *       everything by {@link #tint}: one document, many colours in a frame. Region, nine-slice, tiling, sampler and
 *       premultiplied are an image's and do not apply.</li>
 *   <li>A brush over a 2D array ({@code CgTexture2DArray}) draws its layer 0 unless {@link #layer} names another;
 *       region, nine-slice and tiling are in that layer's pixels.</li>
 *   <li>{@link #size} is the image size a widget desires (Slate's {@code ImageSize}); unset, a texture's region, or
 *       Slate's 32 x 32 where none is known yet.</li>
 *   <li>{@link #WHITE} has no image and draws a plain box in its tint; {@link #NONE} draws nothing, its size still
 *       desired.</li>
 *   <li>A brush is a value: every setter answers a new one. Build it once.</li>
 * </ul>
 */
public final class CgBrush implements CgPaint {

    /** No image: a plain box in its tint, as Slate's default brush. 32 x 32. */
    public static final CgBrush WHITE = new CgBrush();
    /** Draws nothing; a widget still desires its size, 32 x 32. Slate's {@code NoDrawType}. */
    public static final CgBrush NONE = none();

    // Set once, on a copy no one else holds: a brush is a value
    @Nullable
    private CgTexture texture;
    @Nullable
    private String texturePath;
    @Nullable
    private CgSvg svg;
    @Nullable
    private String svgPath;
    @Nullable
    private CgMaterialDefinition material;
    private float imageWidth = -1f, imageHeight = -1f;
    private boolean draws = true;
    private int x, y, width = -1, height = -1;
    private int layer;
    @Nullable
    private CgNineSlice nineSlice;
    private CgTiling tiling = CgTiling.STRETCH;
    private CgTiling edges = CgTiling.STRETCH;
    /** Null: the texture's own, as a path brush loads it with its settings file. */
    @Nullable
    private CgSampler sampler = CgSampler.BILINEAR_CLAMP;
    private CgColor tint = CgColor.WHITE;
    private int tintArgb = 0xFFFFFFFF;
    private boolean premultiplied;
    private int color = 0xFF000000;
    private boolean flat;
    private float strokeWidth;
    private CgFit fit = CgFit.CONTAIN;

    private CgBrush() {
    }

    private static CgBrush none() {
        CgBrush b = new CgBrush();
        b.draws = false;
        return b;
    }

    private CgBrush copy() {
        CgBrush b = new CgBrush();
        b.texture = texture;
        b.texturePath = texturePath;
        b.svg = svg;
        b.svgPath = svgPath;
        b.material = material;
        b.imageWidth = imageWidth;
        b.imageHeight = imageHeight;
        b.draws = draws;
        b.x = x;
        b.y = y;
        b.width = width;
        b.height = height;
        b.layer = layer;
        b.nineSlice = nineSlice;
        b.tiling = tiling;
        b.edges = edges;
        b.sampler = sampler;
        b.tint = tint;
        b.tintArgb = tintArgb;
        b.premultiplied = premultiplied;
        b.color = color;
        b.flat = flat;
        b.strokeWidth = strokeWidth;
        b.fit = fit;
        return b;
    }

    /** All of {@code texture}, stretched, bilinear, untinted, straight alpha. */
    public static CgBrush texture(CgTexture texture) {
        CgBrush b = new CgBrush();
        b.texture = Objects.requireNonNull(texture, "texture");
        return b;
    }

    /** The texture at {@code path} ({@code CgTextures.of}'s), loaded at its first draw and shared: what a data file names. */
    public static CgBrush texture(String path) {
        CgBrush b = new CgBrush();
        b.texturePath = Objects.requireNonNull(path, "path");
        b.sampler = null;
        return b;
    }

    /** The SVG document at {@code path} ({@code "ns:path.svg"} or {@code "...svg#id"}), read at its first draw. */
    public static CgBrush svg(String path) {
        CgBrush b = new CgBrush();
        b.svgPath = Objects.requireNonNull(path, "path");
        return b;
    }

    /**
     * A UI-domain material ({@code #domain ui}, {@code Primitive Element}) over what it fills, the brush's tint its
     * colour.
     *
     * @throws IllegalArgumentException naming it, for a material of another domain
     */
    public static CgBrush material(CgMaterialDefinition material) {
        Objects.requireNonNull(material, "material").requireDomain(CgMaterialDomain.UI, "a brush");
        CgBrush b = new CgBrush();
        b.material = material;
        return b;
    }

    /**
     * {@code svg} fitted into the rect it fills and centred, in its own colours with {@code currentColor} black.
     * Rasterised at the size shown up to 128 device pixels, drawn as vectors above.
     *
     * <pre>{@code
     * CgBrush icon = CgBrush.svg(CgSvg.of("mymod:ui/icons/save.svg")).color(theme.text());
     * }</pre>
     */
    public static CgBrush svg(CgSvg svg) {
        CgBrush b = new CgBrush();
        b.svg = Objects.requireNonNull(svg, "svg");
        return b;
    }

    /**
     * Entry {@code element} of the sprite sheet {@code sheet} ({@code "namespace:path"}, the file
     * {@code assets/<namespace>/ui/sprites/<path>.json}): its image, region and slices. See {@link CgSpriteSheet}.
     *
     * @throws IllegalArgumentException naming the sheet and entry, when either is missing
     */
    public static CgBrush sprite(String sheet, String element) {
        return CgSpriteSheet.of(sheet).brush(element);
    }

    /** This brush showing only the region of its image at ({@code x}, {@code y}), {@code width} by {@code height} pixels. */
    public CgBrush region(int x, int y, int width, int height) {
        if (x < 0 || y < 0 || width <= 0 || height <= 0) {
            throw new IllegalArgumentException("region " + x + ", " + y + ", " + width + " x " + height);
        }
        image("a region");
        CgBrush b = copy();
        b.x = x;
        b.y = y;
        b.width = width;
        b.height = height;
        return b;
    }

    /**
     * This brush drawing layer {@code layer} of its 2D array.
     *
     * @throws IllegalStateException when its texture is not an array
     */
    public CgBrush layer(int layer) {
        image("a layer");
        CgTexture t = texture();
        if (t == null || !t.isArray()) throw new IllegalStateException("a layer is a 2D array's; " + t + " is not one");
        if (layer < 0) throw new IllegalArgumentException("layer " + layer);
        CgBrush b = copy();
        b.layer = layer;
        return b;
    }

    /** This brush nine-sliced: its corners keep their size, its edges and middle stretch or tile. */
    public CgBrush nineSlice(CgNineSlice slice) {
        image("a nine-slice");
        CgBrush b = copy();
        b.nineSlice = Objects.requireNonNull(slice, "slice");
        return b;
    }

    /** This brush covering larger shapes by {@code tiling}; with a nine-slice, how its middle fills. */
    public CgBrush tiling(CgTiling tiling) {
        image("tiling");
        CgBrush b = copy();
        b.tiling = Objects.requireNonNull(tiling, "tiling");
        return b;
    }

    /** With a nine-slice, how its edges cover their length: CSS's {@code border-image-repeat}. */
    public CgBrush edges(CgTiling edges) {
        image("edge tiling");
        CgBrush b = copy();
        b.edges = Objects.requireNonNull(edges, "edges");
        return b;
    }

    /** This brush sampled by {@code sampler}: {@link CgSampler#NEAREST_CLAMP} for pixel art. */
    public CgBrush sampler(CgSampler sampler) {
        image("a sampler");
        CgBrush b = copy();
        b.sampler = Objects.requireNonNull(sampler, "sampler");
        return b;
    }

    /** This brush multiplied by {@code argb}, straight {@code 0xAARRGGBB}. */
    public CgBrush tint(int argb) {
        CgBrush b = copy();
        b.tint = CgColor.argb(argb);
        b.tintArgb = argb;
        return b;
    }

    /** This brush multiplied by {@code tint} in linear light, kept exactly: a widget multiplies it down its tree. */
    public CgBrush tint(CgColor tint) {
        CgBrush b = copy();
        b.tint = Objects.requireNonNull(tint, "tint");
        b.tintArgb = tint.toArgb();
        return b;
    }

    /** This brush desiring {@code width} x {@code height} units: an image widget's size (Slate's {@code ImageSize}). */
    public CgBrush size(float width, float height) {
        CgBrush b = copy();
        b.imageWidth = width;
        b.imageHeight = height;
        return b;
    }

    /** This brush's texture read as premultiplied: an image another pass rendered, or one baked that way. */
    public CgBrush premultiplied(boolean premultiplied) {
        image("premultiplied");
        CgBrush b = copy();
        b.premultiplied = premultiplied;
        return b;
    }

    /**
     * An SVG brush's {@code currentColor}, straight {@code 0xAARRGGBB}; with {@link #flat}, every paint's.
     *
     * @throws IllegalStateException on an image brush, which has no currentColor
     */
    public CgBrush color(int argb) {
        vector("color");
        CgBrush b = copy();
        b.color = argb;
        return b;
    }

    /**
     * An SVG brush drawn as a silhouette: every fill and stroke in {@link #color}, its strokes {@code strokeWidth}
     * canvas units wide whatever its size ({@code <= 0} keeps the file's own, scaled). What a themed icon set of
     * outlines wants, whose weight is the theme's.
     */
    public CgBrush flat(float strokeWidth) {
        vector("flat");
        CgBrush b = copy();
        b.flat = true;
        b.strokeWidth = strokeWidth;
        return b;
    }

    /** How an SVG brush fills its rect: {@link CgFit#CONTAIN} by default. */
    public CgBrush fit(CgFit fit) {
        vector("fit");
        CgBrush b = copy();
        b.fit = Objects.requireNonNull(fit, "fit");
        return b;
    }

    private void image(String what) {
        if (material != null) throw new IllegalStateException(what + " is an image brush's; this one is a material: " + this);
        if (isSvg()) throw new IllegalStateException(what + " is an image brush's; an SVG brush is drawn whole: " + this);
    }

    private void vector(String what) {
        if (!isSvg()) throw new IllegalStateException(what + " is an SVG brush's: " + this);
    }

    /** The image, a path's loaded at the first ask and shared; null for an SVG, material or plain brush. */
    @Nullable
    public CgTexture texture() {
        return texture != null ? texture : texturePath != null ? CgTextures.of(texturePath) : null;
    }

    /** The path it loads its texture from; null when it was given the texture itself. */
    @Nullable
    public String texturePath() {
        return texturePath;
    }

    /** Whether it draws an SVG document. */
    public boolean isSvg() {
        return svg != null || svgPath != null;
    }

    /** The path it reads its SVG document from; null when it was given the document itself. */
    @Nullable
    public String svgPath() {
        return svgPath;
    }

    /** Its UI material; null for any other brush. */
    @Nullable
    public CgMaterialDefinition material() {
        return material;
    }

    /** False for {@link #NONE}: nothing is drawn. */
    public boolean draws() {
        return draws;
    }

    /** Whether {@link #size} set its image size. */
    public boolean hasSize() {
        return imageWidth >= 0f;
    }

    /** The width an image of it desires: {@link #size}'s, else its texture's region, else 32. */
    public float imageWidth() {
        return imageWidth >= 0f ? imageWidth : texture != null ? regionWidth() : 32f;
    }

    /** The height an image of it desires, as {@link #imageWidth}. */
    public float imageHeight() {
        return imageHeight >= 0f ? imageHeight : texture != null ? regionHeight() : 32f;
    }

    /** Whether it shows only a region of its image. */
    public boolean hasRegion() {
        return width > 0;
    }

    /** The region's left edge in the image's pixels, 0 for the whole image. */
    public int regionX() {
        return x;
    }

    public int regionY() {
        return y;
    }

    /** The region's width in pixels; the image's own width when there is no region (it reads the texture). */
    public int regionWidth() {
        if (width > 0) return width;
        CgTexture t = texture();
        return t == null ? 0 : t.getWidth();
    }

    public int regionHeight() {
        if (height > 0) return height;
        CgTexture t = texture();
        return t == null ? 0 : t.getHeight();
    }

    /** The layer of its 2D array it draws; 0 for any other texture. */
    public int layer() {
        return layer;
    }

    @Nullable
    public CgNineSlice nineSlice() {
        return nineSlice;
    }

    public CgTiling tiling() {
        return tiling;
    }

    public CgTiling edges() {
        return edges;
    }

    /** How its texture is sampled; null for the texture's own (a brush by path, unless {@link #sampler} set one). */
    @Nullable
    public CgSampler sampler() {
        return sampler;
    }

    /** Its tint as straight sRGB {@code 0xAARRGGBB}: what the canvas multiplies by. */
    public int tint() {
        return tintArgb;
    }

    /** Its tint in linear light, as set: a widget multiplies it down its tree. */
    public CgColor linearTint() {
        return tint;
    }

    public boolean premultiplied() {
        return premultiplied;
    }

    /** The document an SVG brush draws, a path's read at the first ask; null for an image or a path not readable. */
    @Nullable
    public CgSvg svg() {
        return svg != null ? svg : svgPath != null ? CgSvg.of(svgPath) : null;
    }

    /** What an SVG brush binds {@code currentColor} to. */
    public int color() {
        return color;
    }

    /** Whether an SVG brush draws as a silhouette in {@link #color}. */
    public boolean flat() {
        return flat;
    }

    /** A flat SVG brush's stroke width in canvas units; {@code <= 0} for the file's own. */
    public float strokeWidth() {
        return strokeWidth;
    }

    public CgFit fit() {
        return fit;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CgBrush b)) return false;
        return texture == b.texture && Objects.equals(texturePath, b.texturePath) && svg == b.svg
                && Objects.equals(svgPath, b.svgPath) && Objects.equals(material, b.material) && draws == b.draws
                && Float.compare(imageWidth, b.imageWidth) == 0 && Float.compare(imageHeight, b.imageHeight) == 0
                && color == b.color && flat == b.flat
                && Float.compare(strokeWidth, b.strokeWidth) == 0 && fit == b.fit && x == b.x && y == b.y && width == b.width && height == b.height && layer == b.layer
                && Objects.equals(nineSlice, b.nineSlice) && tiling == b.tiling && edges == b.edges
                && Objects.equals(sampler, b.sampler) && tint.equals(b.tint) && premultiplied == b.premultiplied;
    }

    @Override
    public int hashCode() {
        int h = System.identityHashCode(texture);
        h = 31 * h + Objects.hashCode(texturePath);
        h = 31 * h + System.identityHashCode(svg);
        h = 31 * h + Objects.hashCode(svgPath);
        h = 31 * h + Objects.hashCode(material);
        h = 31 * h + Boolean.hashCode(draws);
        h = 31 * h + Float.hashCode(imageWidth);
        h = 31 * h + Float.hashCode(imageHeight);
        h = 31 * h + color;
        h = 31 * h + Boolean.hashCode(flat);
        h = 31 * h + Float.hashCode(strokeWidth);
        h = 31 * h + fit.hashCode();
        h = 31 * h + x;
        h = 31 * h + y;
        h = 31 * h + width;
        h = 31 * h + height;
        h = 31 * h + layer;
        h = 31 * h + Objects.hashCode(nineSlice);
        h = 31 * h + tiling.hashCode();
        h = 31 * h + edges.hashCode();
        h = 31 * h + Objects.hashCode(sampler);
        h = 31 * h + tint.hashCode();
        return 31 * h + Boolean.hashCode(premultiplied);
    }

    @Override
    public String toString() {
        if (!draws) return "CgBrush[none]";
        String size = hasSize() ? " size " + imageWidth + "x" + imageHeight : "";
        if (material != null) return "CgBrush[" + material + String.format(" tint #%08X", tintArgb) + size + ']';
        if (texture == null && texturePath == null && !isSvg()) {
            return "CgBrush[" + String.format("#%08X", tintArgb) + size + ']';
        }
        if (isSvg()) {
            return "CgBrush[" + (svg != null ? svg : svgPath) + String.format(" color #%08X tint #%08X", color, tintArgb)
                    + (flat ? " flat " + strokeWidth : "") + " " + fit + size + ']';
        }
        return "CgBrush[" + (texture != null ? texture : texturePath)
                + (texture != null && texture.isArray() ? " layer " + layer : "")
                + (hasRegion() ? " " + x + "," + y + " " + width + "x" + height : "")
                + (nineSlice != null ? " " + nineSlice : "") + " " + tiling + String.format(" #%08X", tintArgb)
                + (premultiplied ? " premultiplied" : "") + size + ']';
    }
}
