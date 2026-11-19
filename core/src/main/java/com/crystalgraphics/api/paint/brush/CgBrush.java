package com.crystalgraphics.api.paint.brush;

import java.util.Objects;

import javax.annotation.Nullable;

import com.crystalgraphics.api.paint.CgPaint;
import com.crystalgraphics.api.texture.CgSampler;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.api.vector.svg.CgSvg;

/**
 * An image as paint: a texture or a region of one, stretched, tiled or nine-sliced over the shape it fills, tinted; or
 * an SVG document, drawn sharp at any size. Slate's {@code FSlateBrush}, CSS's {@code background-image}. Anything
 * that takes a {@link CgPaint} takes one.
 *
 * <pre>{@code
 * static final CgBrush ICON = CgBrush.texture(CgTextures.of("mymod:textures/gui/save.png"));
 * static final CgBrush DIGIT = CgBrush.texture(digits).region(16, 0, 8, 12);               // pixels of the image
 * static final CgBrush PANEL = CgBrush.texture(frame).nineSlice(CgNineSlice.uniform(4));   // corners stay put
 * static final CgBrush BUTTON = CgBrush.sprite("crystalgui:ore", "button");                // a sprite sheet's entry
 * static final CgBrush FLOOR = CgBrush.texture(tile).tiling(CgTiling.REPEAT);
 * static final CgBrush SAVE = CgBrush.svg(CgSvg.of("mymod:ui/icons/save.svg"));               // fitted, centred
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
 *   <li>A brush is a value: every setter answers a new one. Build it once.</li>
 * </ul>
 */
public final class CgBrush implements CgPaint {

    // Set once, on a copy no one else holds: a brush is a value
    @Nullable
    private CgTexture texture;
    @Nullable
    private CgSvg svg;
    private int x, y, width = -1, height = -1;
    @Nullable
    private CgNineSlice nineSlice;
    private CgTiling tiling = CgTiling.STRETCH;
    private CgTiling edges = CgTiling.STRETCH;
    private CgSampler sampler = CgSampler.BILINEAR_CLAMP;
    private int tint = 0xFFFFFFFF;
    private boolean premultiplied;
    private int color = 0xFF000000;
    private boolean flat;
    private float strokeWidth;
    private CgFit fit = CgFit.CONTAIN;

    private CgBrush() {
    }

    private CgBrush copy() {
        CgBrush b = new CgBrush();
        b.texture = texture;
        b.svg = svg;
        b.x = x;
        b.y = y;
        b.width = width;
        b.height = height;
        b.nineSlice = nineSlice;
        b.tiling = tiling;
        b.edges = edges;
        b.sampler = sampler;
        b.tint = tint;
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
        b.tint = argb;
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
        if (svg != null) throw new IllegalStateException(what + " is an image brush's; an SVG brush is drawn whole: " + this);
    }

    private void vector(String what) {
        if (svg == null) throw new IllegalStateException(what + " is an SVG brush's: " + this);
    }

    /** The image; null for an SVG brush. */
    @Nullable
    public CgTexture texture() {
        return texture;
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
        return width > 0 ? width : texture == null ? 0 : texture.getWidth();
    }

    public int regionHeight() {
        return height > 0 ? height : texture == null ? 0 : texture.getHeight();
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

    public CgSampler sampler() {
        return sampler;
    }

    public int tint() {
        return tint;
    }

    public boolean premultiplied() {
        return premultiplied;
    }

    /** The document an SVG brush draws; null for an image. */
    @Nullable
    public CgSvg svg() {
        return svg;
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
        return texture == b.texture && svg == b.svg && color == b.color && flat == b.flat
                && Float.compare(strokeWidth, b.strokeWidth) == 0 && fit == b.fit && x == b.x && y == b.y && width == b.width && height == b.height
                && Objects.equals(nineSlice, b.nineSlice) && tiling == b.tiling && edges == b.edges
                && sampler.equals(b.sampler) && tint == b.tint && premultiplied == b.premultiplied;
    }

    @Override
    public int hashCode() {
        int h = System.identityHashCode(texture);
        h = 31 * h + System.identityHashCode(svg);
        h = 31 * h + color;
        h = 31 * h + Boolean.hashCode(flat);
        h = 31 * h + Float.hashCode(strokeWidth);
        h = 31 * h + fit.hashCode();
        h = 31 * h + x;
        h = 31 * h + y;
        h = 31 * h + width;
        h = 31 * h + height;
        h = 31 * h + Objects.hashCode(nineSlice);
        h = 31 * h + tiling.hashCode();
        h = 31 * h + edges.hashCode();
        h = 31 * h + sampler.hashCode();
        h = 31 * h + tint;
        return 31 * h + Boolean.hashCode(premultiplied);
    }

    @Override
    public String toString() {
        if (svg != null) {
            return "CgBrush[" + svg + String.format(" color #%08X tint #%08X", color, tint) + (flat ? " flat " + strokeWidth : "")
                    + " " + fit + ']';
        }
        return "CgBrush[" + texture + (hasRegion() ? " " + x + "," + y + " " + width + "x" + height : "")
                + (nineSlice != null ? " " + nineSlice : "") + " " + tiling + String.format(" #%08X", tint)
                + (premultiplied ? " premultiplied" : "") + ']';
    }
}
