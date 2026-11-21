package com.crystalgraphics.api.canvas;

import javax.annotation.Nullable;

import com.crystalgraphics.api.material.CgBlendMode;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialInterface;
import com.crystalgraphics.api.paint.CgPaint;
import com.crystalgraphics.api.paint.brush.CgBrush;
import com.crystalgraphics.api.paint.brush.CgTiling;
import com.crystalgraphics.api.paint.effect.CgShadow;
import com.crystalgraphics.api.paint.gradient.CgGradient;
import com.crystalgraphics.api.paint.pattern.CgGrid;
import com.crystalgraphics.api.texture.CgSampler;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.api.vector.CgCornerRadii;
import com.crystalgraphics.render.ui.CgCanvasTables;
import com.crystalgraphics.render.ui.element.CgUiElements;

/**
 * A canvas's boxes, each as elements: its shadow under it, its fill (a colour, a gradient, a grid, an image, a brush's
 * nine-slice or tiles, an SVG brush), its corners and border, an inset shadow over it; glass first when it has a
 * backdrop filter.
 */
final class CgCanvasBoxes {

    /** Past this many pieces a brush's tiling stops. */
    private static final int MAX_TILES = 4096;

    private final CgCanvas canvas;
    /** Glass drawn flat inside a kept walk. */
    private final CgCanvasBox flatBox;
    private final Tiles tiles = new Tiles();

    CgCanvasBoxes(CgCanvas canvas) {
        this.canvas = canvas;
        this.flatBox = new CgCanvasBox(canvas);
    }

    void draw(CgCanvasBox box) {
        CgCanvas cv = canvas;
        if (cv.recording == null) throw new IllegalStateException("drawing on a canvas that records nowhere: begin() first");
        float w = box.w, h = box.h;
        if (!(w > 0f && h > 0f) || cv.opacity <= 0f) return;
        if (box.backdrop != null) {
            if (cv.layers.insideKept()) {
                // A kept picture of glass would be stale: flat, its fallback under the box's own paint
                flatBox.reset(box.x, box.y, box.w, box.h).fill(box.backdrop.fallback());
                flatBox.radii = box.radii;
                draw(flatBox);
            } else {
                cv.backdrop.pane(box);
            }
            // Over the glass, the box's own paint only where it was given one
            if (!box.filled && box.shadow == null
                    && box.borderTop + box.borderRight + box.borderBottom + box.borderLeft <= 0f) {
                return;
            }
        }
        cv.pass();
        CgPaint paint = box.paint;
        // A material brush's material is the box's, as one given by material(...) is
        CgMaterialInterface own = box.material != null ? box.material
                : paint instanceof CgBrush mb && mb.material() != null ? CgMaterial.of(mb.material()) : null;
        if (own != null && box.blend != null) {
            throw new IllegalStateException("a box's own material blends as its state says; blend(" + box.blend
                    + ") is the canvas's element's");
        }
        CgMaterialInterface material = own != null ? own
                : CgUiMaterials.element(box.blend != null ? box.blend : CgBlendMode.PREMULTIPLIED);
        if (box.shadow != null && !box.shadow.inside()) shadow(box, material);
        if (paint instanceof CgBrush brush && brush.isSvg()) {
            cv.svg.draw(brush, box.x, box.y, w, h, CgCanvas.mul(brush.tint(), box.argb));
            borderOver(box, material);
        } else if (paint instanceof CgBrush brush && (brush.nineSlice() != null || brush.tiling() != CgTiling.STRETCH)) {
            brushTiles(box, brush, material);
        } else {
            fill(box, material, box.x, box.y, w, h);
        }
        if (box.shadow != null && box.shadow.inside()) shadow(box, material);
    }

    /** One element of {@code box}'s paint over a rect of it, its corners and border the box's where they touch. */
    private void fill(CgCanvasBox box, CgMaterialInterface material, float x, float y, float w, float h) {
        float[] record = canvas.clear();
        CgTexture texture = null;
        CgSampler sampler = null;
        int argb = box.argb;
        CgPaint paint = box.paint;
        if (paint instanceof CgPaint.Solid solid) {
            CgUiElements.fill(record, 0, CgUiElements.FILL_COLOR, solid.argb(), 0f);
            emit(box, material, null, null, x, y, w, h, solid.intensity(), true);
            return;
        }
        if (paint instanceof CgGradient gradient) {
            CgUiElements.fill(record, 0, CgUiElements.FILL_GRADIENT, argb,
                    CgCanvasTables.of(canvas.recording).gradients().add(gradient));
        } else if (paint instanceof CgGrid grid) {
            CgUiElements.fill(record, 0, CgUiElements.FILL_GRID, grid.lineColor(), Float.intBitsToFloat(grid.secondColor()));
            CgUiElements.uv(record, 0, grid.cellWidth(), grid.cellHeight(), grid.lineWidth(), grid.lineHeight());
            CgUiElements.flag(record, 0, CgUiElements.FLAG_CHECKER, grid.checker());
        } else if (paint instanceof CgBrush brush && (!brush.draws() || brush.texture() == null)) {
            // NONE draws nothing; a plain or material brush is its tint over the box, the material's surface on it
            CgUiElements.fill(record, 0, CgUiElements.FILL_COLOR, brush.draws() ? CgCanvas.mul(brush.tint(), argb) : 0, 0f);
        } else if (paint instanceof CgBrush brush) {
            texture = brush.texture();
            sampler = brush.sampler();
            CgUiElements.fill(record, 0, box.mask ? CgUiElements.FILL_MASK : CgUiElements.FILL_TEXTURE,
                    CgCanvas.mul(brush.tint(), argb), 0f);
            CgUiElements.flag(record, 0, CgUiElements.FLAG_PREMULTIPLIED, brush.premultiplied());
            if (texture.isArray()) CgUiElements.layer(record, 0, brush.layer());
            float tw = texture.getWidth(), th = texture.getHeight();
            if (brush.hasRegion() && tw > 0 && th > 0) {
                CgUiElements.uv(record, 0, brush.regionX() / tw, brush.regionY() / th,
                        (brush.regionX() + brush.regionWidth()) / tw, (brush.regionY() + brush.regionHeight()) / th);
            }
        } else if (box.texture != null) {
            texture = box.texture;
            CgUiElements.fill(record, 0, box.mask ? CgUiElements.FILL_MASK : CgUiElements.FILL_TEXTURE, argb, 0f);
            CgUiElements.flag(record, 0, CgUiElements.FLAG_PREMULTIPLIED, box.premultiplied);
            CgUiElements.uv(record, 0, box.u0, box.v0, box.u1, box.v1);
        } else {
            CgUiElements.fill(record, 0, CgUiElements.FILL_COLOR, box.filled ? argb : 0, 0f);
        }
        emit(box, material, texture, sampler, x, y, w, h, 1f, !(paint instanceof CgGrid));
    }

    /**
     * A brush's nine-slice or tiles, as one element a piece, laid over the whole box and shown inside its border and
     * corners, as an element's own fill is: a transparent border shows what is behind the box.
     */
    private void brushTiles(CgCanvasBox box, CgBrush brush, CgMaterialInterface material) {
        CgCanvas cv = canvas;
        float bt = box.borderTop, br = box.borderRight, bb = box.borderBottom, bl = box.borderLeft;
        boolean rounded = box.radii != null && !box.radii.isNone();
        boolean clipped = rounded || bt + br + bb + bl > 0f;
        if (clipped) {
            cv.save();
            cv.clip(box.x + bl, box.y + bt, box.w - bl - br, box.h - bt - bb, rounded ? box.radii.inset(bt, br, bb, bl) : null);
        }
        tiles.brush = brush;
        tiles.argb = CgCanvas.mul(brush.tint(), box.argb);
        tiles.mask = box.mask;
        tiles.material = material;
        tiles.x0 = box.x;
        tiles.y0 = box.y;
        tiles.x1 = box.x + box.w;
        tiles.y1 = box.y + box.h;
        CgCanvasTiles.of(brush, box.x, box.y, box.w, box.h, MAX_TILES, tiles);
        tiles.brush = null;
        tiles.material = null;
        if (clipped) cv.restore();
        borderOver(box, material);
    }

    /** A box's border over what its brush drew: one more element, transparent but its border. */
    private void borderOver(CgCanvasBox box, CgMaterialInterface material) {
        if (box.borderTop + box.borderRight + box.borderBottom + box.borderLeft > 0f) {
            float[] record = canvas.clear();
            CgUiElements.fill(record, 0, CgUiElements.FILL_COLOR, 0, 0f);
            emit(box, material, null, null, box.x, box.y, box.w, box.h, 1f, true);
        }
    }

    /**
     * Where a brush's pieces go: one element each, hard on every side inside the box, so pieces meeting inside a pixel
     * cover it once. Reused, so laying tiles allocates nothing.
     */
    private final class Tiles implements CgCanvasTiles.Sink {
        @Nullable CgBrush brush;
        @Nullable CgMaterialInterface material;
        int argb;
        boolean mask;
        /** The box the pieces fill: a piece's side on it fades, every other side is hard. */
        float x0, y0, x1, y1;

        @Override
        public void piece(float x, float y, float w, float h, float u0, float v0, float u1, float v1) {
            CgCanvas cv = canvas;
            float[] record = cv.clear();
            CgUiElements.fill(record, 0, mask ? CgUiElements.FILL_MASK : CgUiElements.FILL_TEXTURE, argb, 0f);
            CgUiElements.flag(record, 0, CgUiElements.FLAG_PREMULTIPLIED, brush.premultiplied());
            if (brush.texture().isArray()) CgUiElements.layer(record, 0, brush.layer());
            CgUiElements.uv(record, 0, u0, v0, u1, v1);
            float e = 1e-3f;
            CgUiElements.flag(record, 0, CgUiElements.FLAG_HARD_LEFT, x > x0 + e);
            CgUiElements.flag(record, 0, CgUiElements.FLAG_HARD_TOP, y > y0 + e);
            CgUiElements.flag(record, 0, CgUiElements.FLAG_HARD_RIGHT, x + w < x1 - e);
            CgUiElements.flag(record, 0, CgUiElements.FLAG_HARD_BOTTOM, y + h < y1 - e);
            int at = cv.spatial();
            float[] r = cv.rect(at, x, y, w, h, true, 0f);
            if (r == null) return;
            cv.finish(at, 1f);
            cv.element(material, brush.texture(), brush.sampler(), at, r[0], r[1], r[2], r[3]);
        }
    }

    private void shadow(CgCanvasBox box, CgMaterialInterface material) {
        CgCanvas cv = canvas;
        CgShadow s = box.shadow;
        float[] record = cv.clear();
        boolean inside = s.inside();
        int at = cv.spatial();
        // A blur is in the record's units: device pixels where the transform is baked
        float sigma = s.sigma() * (at == cv.base ? cv.linearScale() : 1f);
        float x = box.x, y = box.y, w = box.w, h = box.h;
        if (inside) {
            // The hole is the box less its border and spread, offset; in the box's units from its corner
            float hx0 = box.borderLeft + s.spread() + s.dx(), hy0 = box.borderTop + s.spread() + s.dy();
            float hx1 = w - box.borderRight - s.spread() + s.dx(), hy1 = h - box.borderBottom - s.spread() + s.dy();
            float kx = cv.kx(at), ky = cv.ky(at);
            CgUiElements.fill(record, 0, CgUiElements.FILL_INSET_SHADOW, s.argb(), sigma);
            CgUiElements.uv(record, 0, hx0 * kx, hy0 * ky, hx1 * kx, hy1 * ky);
            emitShadow(box, material, at, x, y, w, h, 0f, 0f);
        } else {
            CgUiElements.fill(record, 0, CgUiElements.FILL_SHADOW, s.argb(), sigma);
            emitShadow(box, material, at, x + s.dx() - s.spread(), y + s.dy() - s.spread(), w + 2 * s.spread(),
                    h + 2 * s.spread(), s.spread(), 3f * sigma);
        }
    }

    /** A shadow's element; its ink reaches {@code reach} past its rect, in the record's units. */
    private void emitShadow(CgCanvasBox box, CgMaterialInterface material, int at, float x, float y, float w,
                            float h, float grow, float reach) {
        CgCanvas cv = canvas;
        float[] r = cv.rect(at, x, y, w, h, false, reach);
        if (r == null) return;
        radii(box, at, grow);
        cv.finish(at, 1f);
        cv.element(material, null, null, at, r[0] - reach, r[1] - reach, r[2] + reach, r[3] + reach);
    }

    private void emit(CgCanvasBox box, CgMaterialInterface material, @Nullable CgTexture texture,
                      @Nullable CgSampler sampler, float x, float y, float w, float h, float gain, boolean snapIt) {
        CgCanvas cv = canvas;
        int at = cv.spatial();
        float[] r = cv.rect(at, x, y, w, h, snapIt, 0f);
        if (r == null) return;
        radii(box, at, 0f);
        if (box.borderTop + box.borderRight + box.borderBottom + box.borderLeft > 0f) {
            float kx = cv.kx(at), ky = cv.ky(at);
            CgUiElements.border(cv.record, 0, box.borderTop * ky, box.borderRight * kx, box.borderBottom * ky,
                    box.borderLeft * kx, box.borderTopColor, box.borderRightColor, box.borderBottomColor,
                    box.borderLeftColor);
        }
        cv.finish(at, gain);
        cv.element(material, texture, sampler, at, r[0], r[1], r[2], r[3]);
    }

    /** The box's corners into the record, grown by a shadow's spread. */
    private void radii(CgCanvasBox box, int at, float grow) {
        CgCornerRadii radii = box.radii;
        if (radii == null || radii.isNone()) return;
        float kx = canvas.kx(at), ky = canvas.ky(at);
        CgCornerRadii fit = radii.fitted(box.w, box.h);
        CgUiElements.radii(canvas.record, 0,
                grown(fit.topLeftX(), grow) * kx, grown(fit.topLeftY(), grow) * ky,
                grown(fit.topRightX(), grow) * kx, grown(fit.topRightY(), grow) * ky,
                grown(fit.bottomRightX(), grow) * kx, grown(fit.bottomRightY(), grow) * ky,
                grown(fit.bottomLeftX(), grow) * kx, grown(fit.bottomLeftY(), grow) * ky);
    }

    /** A radius grown by a shadow's spread, as CSS grows it: a square corner stays square. */
    private static float grown(float r, float spread) {
        return r > 0f ? Math.max(0f, r + spread) : 0f;
    }
}
