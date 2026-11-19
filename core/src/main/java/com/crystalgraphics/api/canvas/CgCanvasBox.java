package com.crystalgraphics.api.canvas;

import javax.annotation.Nullable;

import com.crystalgraphics.api.material.CgMaterialInterface;
import com.crystalgraphics.api.paint.CgPaint;
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
 * }</pre>
 *
 * <ul>
 *   <li>Unset, a box is transparent with square corners, no border and no shadow: {@code draw()} draws nothing visible.</li>
 *   <li>Radii larger than the box shrink to fit, as CSS's do.</li>
 *   <li>A border is drawn inside the box, over its fill.</li>
 * </ul>
 */
public final class CgCanvasBox {

    private final CgCanvas canvas;

    float x, y, w, h;
    int argb;
    @Nullable CgPaint paint;
    @Nullable CgTexture texture;
    boolean mask;
    @Nullable CgCornerRadii radii;
    float borderTop, borderRight, borderBottom, borderLeft;
    int borderTopColor, borderRightColor, borderBottomColor, borderLeftColor;
    @Nullable CgShadow shadow;
    @Nullable CgMaterialInterface material;

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
        radii = null;
        borderTop = borderRight = borderBottom = borderLeft = 0f;
        shadow = null;
        material = null;
        return this;
    }

    /** Filled in one colour. */
    public CgCanvasBox fill(int argb) {
        this.argb = argb;
        this.paint = null;
        this.texture = null;
        return this;
    }

    /** Filled by any paint: a colour, a gradient, a grid or a brush. */
    public CgCanvasBox fill(CgPaint paint) {
        this.paint = paint;
        this.texture = null;
        return this;
    }

    /** Filled by a texture, stretched, times the box's colour ({@link #tint}). */
    public CgCanvasBox fill(CgTexture texture) {
        this.texture = texture;
        this.paint = null;
        this.mask = false;
        return this;
    }

    /** Filled by the box's colour where {@code texture} is opaque: a silhouette in any colour. */
    public CgCanvasBox mask(CgTexture texture, int argb) {
        this.texture = texture;
        this.paint = null;
        this.mask = true;
        this.argb = argb;
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
