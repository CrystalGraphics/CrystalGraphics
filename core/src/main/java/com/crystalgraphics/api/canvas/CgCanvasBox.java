package com.crystalgraphics.api.canvas;

import java.util.Objects;

import javax.annotation.Nullable;

import com.crystalgraphics.api.material.CgBlendMode;
import com.crystalgraphics.api.material.CgMaterialInterface;
import com.crystalgraphics.api.paint.CgPaint;
import com.crystalgraphics.api.paint.brush.CgBrush;
import com.crystalgraphics.api.paint.effect.CgBackdropFilter;
import com.crystalgraphics.api.paint.effect.CgShadow;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.api.vector.CgCornerRadii;

/**
 * A box being described on a {@link CgCanvas}: its fill, corners, border, shadow and material, drawn by {@link #draw}.
 * The canvas hands out one and reuses it, so describe and draw it in one statement.
 *
 * <pre>{@code
 * canvas.box(x, y, 160, 32).fill(0xFF202630).radius(6).border(1, 0x30FFFFFF).draw();
 * canvas.box(x, y, 160, 32).fill(CgGradient.linear(180).stop(0, top).stop(1, bottom)).radii(CgCornerRadii.of(8, 8, 0, 0)).draw();
 * canvas.box(x, y, 48, 48).fill(avatar).radius(24).shadow(CgShadow.of(0, 2, 6, 0x60000000)).draw();
 * canvas.box(x, y, w, h).material(myUiMaterial).fill(0xFFFFFFFF).draw();      // a #domain ui material, Primitive Element
 * canvas.box(x, y, 64, 64).fill(spark).blend(CgBlendMode.ADDITIVE).draw();     // added onto what is behind
 * canvas.box(x, y, 12, 16).mask(CgBrush.texture(digits).layer(7), 0xFFFFD040).draw();   // a 2D array's layer
 * }</pre>
 *
 * <ul>
 *   <li>Unset, a box is transparent with square corners, no border and no shadow: {@code draw()} draws nothing visible.</li>
 *   <li>Radii larger than the box shrink to fit, as CSS's do.</li>
 *   <li>A border is drawn inside the box, over its fill.</li>
 *   <li>{@link #blend} is the canvas's own element's; a box with its own {@link #material} blends as that material's
 *       state says, and naming both throws at {@code draw()}.</li>
 * </ul>
 */
public final class CgCanvasBox {

    private final CgCanvas canvas;

    float x, y, w, h;
    int argb;
    @Nullable CgPaint paint;
    @Nullable CgTexture texture;
    boolean mask, premultiplied;
    float u0, v0, u1 = 1f, v1 = 1f;
    @Nullable CgCornerRadii radii;
    float borderTop, borderRight, borderBottom, borderLeft;
    int borderTopColor, borderRightColor, borderBottomColor, borderLeftColor;
    @Nullable CgShadow shadow;
    @Nullable CgMaterialInterface material;
    @Nullable CgBlendMode blend;
    @Nullable CgBackdropFilter backdrop;
    /** Whether a fill was given: unset, a box is transparent. */
    boolean filled;

    CgCanvasBox(CgCanvas canvas) {
        this.canvas = canvas;
    }

    CgCanvasBox reset(float x, float y, float w, float h) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        argb = 0xFFFFFFFF;
        paint = null;
        texture = null;
        mask = false;
        premultiplied = false;
        u0 = v0 = 0f;
        u1 = v1 = 1f;
        radii = null;
        borderTop = borderRight = borderBottom = borderLeft = 0f;
        shadow = null;
        material = null;
        blend = null;
        backdrop = null;
        filled = false;
        return this;
    }

    /** Filled in one colour. */
    public CgCanvasBox fill(int argb) {
        this.filled = true;
        this.argb = argb;
        this.paint = null;
        this.texture = null;
        this.mask = false;
        return this;
    }

    /** Filled by any paint: a colour, a gradient, a grid or a brush. */
    public CgCanvasBox fill(CgPaint paint) {
        this.filled = true;
        this.paint = paint;
        this.texture = null;
        this.mask = false;
        return this;
    }

    /** Filled by a texture, stretched, times the box's colour ({@link #tint}). */
    public CgCanvasBox fill(CgTexture texture) {
        this.filled = true;
        this.texture = texture;
        this.paint = null;
        this.mask = false;
        return this;
    }

    /** Filled by the box's colour where {@code texture} is opaque: a silhouette in any colour. */
    public CgCanvasBox mask(CgTexture texture, int argb) {
        this.filled = true;
        this.texture = texture;
        this.paint = null;
        this.mask = true;
        this.argb = argb;
        return this;
    }

    /**
     * Filled by the box's colour where {@code brush}'s image is opaque: its region, slices, tiling and layer as a fill
     * of it would draw them.
     *
     * @throws IllegalArgumentException for an SVG brush, whose silhouette is {@code CgBrush.flat}
     */
    public CgCanvasBox mask(CgBrush brush, int argb) {
        if (brush.svg() != null) throw new IllegalArgumentException("an SVG brush's silhouette is its flat(): " + brush);
        this.filled = true;
        this.paint = brush;
        this.texture = null;
        this.mask = true;
        this.argb = argb;
        return this;
    }

    /**
     * Blended onto what is behind it by {@code blend}: {@code ADDITIVE} adds light, {@code MODULATE} multiplies,
     * {@code SUBTRACT} takes away, {@code MASK} keeps what is behind by its alpha. {@code PREMULTIPLIED}, over, unless
     * set. Inside a layer a {@code MODULATE} box is refused: a layer has no multiply form.
     */
    public CgCanvasBox blend(CgBlendMode blend) {
        this.blend = CgUiMaterials.require(blend);
        return this;
    }

    /**
     * The part of a {@link #fill(CgTexture) texture} drawn, in uv: past 0..1 it repeats under a repeating sampler; v1 < v0
     * flips it, as a render target whose row 0 is its bottom wants.
     */
    public CgCanvasBox region(float u0, float v0, float u1, float v1) {
        this.u0 = u0;
        this.v0 = v0;
        this.u1 = u1;
        this.v1 = v1;
        return this;
    }

    /** The {@link #fill(CgTexture) texture} is premultiplied already: a render target's picture. */
    public CgCanvasBox premultiplied() {
        this.premultiplied = true;
        return this;
    }

    /** Multiplies a gradient, texture or brush by a colour. */
    public CgCanvasBox tint(int argb) {
        this.argb = argb;
        return this;
    }

    /** Every corner rounded by {@code r}. */
    public CgCanvasBox radius(float r) {
        this.radii = r > 0f ? CgCornerRadii.of(r) : null;
        return this;
    }

    /** Each corner rounded by its own radii, circular or elliptical. */
    public CgCanvasBox radii(CgCornerRadii radii) {
        this.radii = radii;
        return this;
    }

    /** A border of one width and colour. */
    public CgCanvasBox border(float width, int argb) {
        return border(width, width, width, width, argb, argb, argb, argb);
    }

    /** A border per side, widths and colours: top, right, bottom, left. */
    public CgCanvasBox border(float top, float right, float bottom, float left,
                              int topArgb, int rightArgb, int bottomArgb, int leftArgb) {
        borderTop = Math.max(0f, top);
        borderRight = Math.max(0f, right);
        borderBottom = Math.max(0f, bottom);
        borderLeft = Math.max(0f, left);
        borderTopColor = topArgb;
        borderRightColor = rightArgb;
        borderBottomColor = bottomArgb;
        borderLeftColor = leftArgb;
        return this;
    }

    /** A shadow: outside the box, under it; or {@link CgShadow#inset() inset}, inside its border. */
    public CgCanvasBox shadow(CgShadow shadow) {
        this.shadow = shadow;
        return this;
    }

    /**
     * Glass behind the box: what is already drawn under it, captured, blurred, saturated, tinted and lit through
     * {@code filter} (CSS's {@code backdrop-filter}), its corners the box's. The box's own fill and border draw over it.
     *
     * <pre>{@code
     * canvas.box(x, y, 320, 200).radius(16).backdropFilter(CgBackdropFilter.ACRYLIC).draw();
     * canvas.box(x, y, 320, 200).radius(16).backdropFilter(CgBackdropFilter.LIQUID).fill(0x10FFFFFF).draw();
     * }</pre>
     *
     * <ul>
     *   <li>Each pane captures what is behind it when it draws, so a second pane over the first shows it.</li>
     *   <li>A pane breaks the canvas's pass: draw panes after what they cover, and few of them.</li>
     * </ul>
     */
    public CgCanvasBox backdropFilter(CgBackdropFilter filter) {
        this.backdrop = Objects.requireNonNull(filter, "filter") == CgBackdropFilter.NONE ? null : filter;
        return this;
    }

    /**
     * Drawn by a {@code #domain ui} material of {@code Primitive Element}: its {@code surface()} runs over the box's
     * paint, which it may keep or replace.
     */
    public CgCanvasBox material(CgMaterialInterface material) {
        this.material = material;
        return this;
    }

    /** Draws it, answering the canvas. */
    public CgCanvas draw() {
        canvas.draw(this);
        return canvas;
    }
}
