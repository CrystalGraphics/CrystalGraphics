package com.crystalgraphics.api.paint.brush;

import java.util.Objects;

import javax.annotation.Nullable;

import com.crystalgraphics.api.paint.CgPaint;
import com.crystalgraphics.api.texture.CgSampler;
import com.crystalgraphics.api.texture.CgTexture;

/**
 * An image as paint: a texture or a region of one, stretched, tiled or nine-sliced over the shape it fills, tinted.
 * Slate's {@code FSlateBrush}, CSS's {@code background-image}. Anything that takes a {@link CgPaint} takes one.
 *
 * <pre>{@code
 * static final CgBrush ICON = CgBrush.texture(CgTextures.of("mymod:textures/gui/save.png"));
 * static final CgBrush DIGIT = CgBrush.texture(digits).region(16, 0, 8, 12);               // pixels of the image
 * static final CgBrush PANEL = CgBrush.texture(frame).nineSlice(CgNineSlice.uniform(4));   // corners stay put
 * static final CgBrush BUTTON = CgBrush.sprite("crystalgui:ore", "button");                // a sprite sheet's entry
 * static final CgBrush FLOOR = CgBrush.texture(tile).tiling(CgTiling.REPEAT);
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
 *   <li>A brush is a value: every setter answers a new one. Build it once.</li>
 * </ul>
 */
public final class CgBrush implements CgPaint {

    private final CgTexture texture;
    private final int x, y, width, height;
    @Nullable
    private final CgNineSlice nineSlice;
    private final CgTiling tiling;
    private final CgTiling edges;
    private final CgSampler sampler;
    private final int tint;
    private final boolean premultiplied;

    private CgBrush(CgTexture texture, int x, int y, int width, int height, @Nullable CgNineSlice nineSlice,
                    CgTiling tiling, CgTiling edges, CgSampler sampler, int tint, boolean premultiplied) {
        this.texture = texture;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.nineSlice = nineSlice;
        this.tiling = tiling;
        this.edges = edges;
        this.sampler = sampler;
        this.tint = tint;
        this.premultiplied = premultiplied;
    }

    /** All of {@code texture}, stretched, bilinear, untinted, straight alpha. */
    public static CgBrush texture(CgTexture texture) {
        return new CgBrush(Objects.requireNonNull(texture, "texture"), 0, 0, -1, -1, null, CgTiling.STRETCH,
                CgTiling.STRETCH, CgSampler.BILINEAR_CLAMP, 0xFFFFFFFF, false);
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
        return new CgBrush(texture, x, y, width, height, nineSlice, tiling, edges, sampler, tint, premultiplied);
    }

    /** This brush nine-sliced: its corners keep their size, its edges and middle stretch or tile. */
    public CgBrush nineSlice(CgNineSlice slice) {
        return new CgBrush(texture, x, y, width, height, Objects.requireNonNull(slice, "slice"), tiling, edges,
                sampler, tint, premultiplied);
    }

    /** This brush covering larger shapes by {@code tiling}; with a nine-slice, how its middle fills. */
    public CgBrush tiling(CgTiling tiling) {
        return new CgBrush(texture, x, y, width, height, nineSlice, Objects.requireNonNull(tiling, "tiling"), edges,
                sampler, tint, premultiplied);
    }

    /** With a nine-slice, how its edges cover their length: CSS's {@code border-image-repeat}. */
    public CgBrush edges(CgTiling edges) {
        return new CgBrush(texture, x, y, width, height, nineSlice, tiling, Objects.requireNonNull(edges, "edges"),
                sampler, tint, premultiplied);
    }

    /** This brush sampled by {@code sampler}: {@link CgSampler#NEAREST_CLAMP} for pixel art. */
    public CgBrush sampler(CgSampler sampler) {
        return new CgBrush(texture, x, y, width, height, nineSlice, tiling, edges,
                Objects.requireNonNull(sampler, "sampler"), tint, premultiplied);
    }

    /** This brush multiplied by {@code argb}, straight {@code 0xAARRGGBB}. */
    public CgBrush tint(int argb) {
        return new CgBrush(texture, x, y, width, height, nineSlice, tiling, edges, sampler, argb, premultiplied);
    }

    /** This brush's texture read as premultiplied: an image another pass rendered, or one baked that way. */
    public CgBrush premultiplied(boolean premultiplied) {
        return new CgBrush(texture, x, y, width, height, nineSlice, tiling, edges, sampler, tint, premultiplied);
    }

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
        return width > 0 ? width : texture.getWidth();
    }

    public int regionHeight() {
        return height > 0 ? height : texture.getHeight();
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CgBrush b)) return false;
        return texture == b.texture && x == b.x && y == b.y && width == b.width && height == b.height
                && Objects.equals(nineSlice, b.nineSlice) && tiling == b.tiling && edges == b.edges
                && sampler.equals(b.sampler) && tint == b.tint && premultiplied == b.premultiplied;
    }

    @Override
    public int hashCode() {
        int h = System.identityHashCode(texture);
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
        return "CgBrush[" + texture + (hasRegion() ? " " + x + "," + y + " " + width + "x" + height : "")
                + (nineSlice != null ? " " + nineSlice : "") + " " + tiling + String.format(" #%08X", tint)
                + (premultiplied ? " premultiplied" : "") + ']';
    }
}
