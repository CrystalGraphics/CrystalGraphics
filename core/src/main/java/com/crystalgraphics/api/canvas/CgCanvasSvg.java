package com.crystalgraphics.api.canvas;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import javax.annotation.Nullable;

import com.crystalgraphics.api.paint.brush.CgBrush;
import com.crystalgraphics.api.paint.brush.CgFit;
import com.crystalgraphics.api.paint.effect.CgFilter;
import com.crystalgraphics.api.vector.svg.CgSvg;
import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.gpu.resource.CgTextureDesc;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgLoad;
import com.crystalgraphics.render.ui.CgCanvasPrimitives;
import com.crystalgraphics.render.ui.CgUiRecorder;
import com.crystalgraphics.render.ui.element.CgUiElements;
import com.crystalgraphics.render.ui.path.CgPathCache;
import com.crystalgraphics.render.ui.svg.CgSvgCells;
import com.crystalgraphics.render.ui.svg.CgSvgOps;
import com.crystalgraphics.render.ui.svg.CgSvgScene;

/**
 * A canvas's SVG brushes: each op's exact-area coverage, from the path cache's atlas when the icon is at most
 * {@link CgPathCache#MAX_PX} device pixels and axis-aligned, else rasterised for this frame; then an element per op in
 * document order, tinted. Snapped, an icon is drawn 1:1 on the pixel grid; unsnapped (animated) between pixels, from
 * its exact raster or the raster ladder's next rung, filtered. Drop-shadowed groups are filtered in a layer, or once
 * into a cell on the pixel grid.
 */
final class CgCanvasSvg {

    private static final Logger LOGGER = Logger.getLogger(CgCanvasSvg.class.getName());
    /** Below this many raster pixels across, an icon's raster is built at this and drawn minified: two texels are no icon. */
    private static final float MIN_RASTER_PX = 8f;
    /** Raster scales per doubling, off the pixel grid: a raster is drawn at most a sixteenth of a doubling smaller. */
    private static final float LADDER_STEPS = 16f;
    /** A shadowed group's cell past this many device pixels a side is not cached: it is filtered each frame. */
    private static final int SHADOW_CELL_MAX = 256;
    /** Documents whose drop shadow was skipped, logged once each. Threads: any (concurrent). */
    private static final Set<CgSvg> SHADOWS_LOGGED = ConcurrentHashMap.newKeySet();

    /** An SVG op's raster identity in the path cache: the tier's op, coloured or white, a stroke's raster half-width. */
    private record SvgOp(CgSvg tier, int op, boolean coloured, float halfWidth) {
    }

    /** A shadowed group's cell: the group's ops from {@code first} with their shadow, at a tint and a stroke override. */
    private record SvgShadowGroup(CgSvg tier, int first, int tint, boolean flat, float override) {
    }

    private final CgCanvas canvas;
    private final CgSvgCells cells = new CgSvgCells();
    /** The last shadow's filter, kept: an icon with one draws it every frame. */
    @Nullable private CgSvgScene.DropShadow lastShadow;
    private float lastShadowSx, lastShadowSy;
    @Nullable private CgFilter lastShadowFilter;

    CgCanvasSvg(CgCanvas canvas) {
        this.canvas = canvas;
    }

    /** An SVG brush over a rect, fitted as the brush says, tinted by {@code tint}. */
    void draw(CgBrush brush, float x, float y, float w, float h, int tint) {
        CgCanvas cv = canvas;
        CgSvg doc = brush.svg();
        // A path not readable draws nothing, as a texture not yet loaded does
        if (doc == null) return;
        float dw = doc.width(), dh = doc.height();
        if (doc.isEmpty() || !(dw > 0f && dh > 0f && w > 0f && h > 0f) || cv.opacity <= 0f) return;
        float sx = w / dw, sy = h / dh, ox = x, oy = y;
        if (brush.fit() == CgFit.CONTAIN) {
            sx = sy = Math.min(sx, sy);
            ox = x + (w - dw * sx) * 0.5f;
            oy = y + (h - dh * sy) * 0.5f;
        }
        // The document's units to device pixels
        float a = cv.a, b = cv.b, c = cv.c, d = cv.d;
        float da = a * sx, db = b * sx, dc = c * sy, dd = d * sy;
        float dtx = a * ox + c * oy + cv.tx, dty = b * ox + d * oy + cv.ty;
        float devicePx = Math.max(dw * (float) Math.hypot(da, db), dh * (float) Math.hypot(dc, dd));
        boolean cached = db == 0f && dc == 0f && da > 0f && dd > 0f && devicePx <= CgPathCache.MAX_PX;
        if (cached && cv.snap) {
            dtx = Math.round(dtx);
            dty = Math.round(dty);
        }
        // A flat brush's stroke width is the canvas's units: device pixels by the transform's scale
        float override = brush.flat() && brush.strokeWidth() > 0f ? brush.strokeWidth() * 0.5f * cv.linearScale() : 0f;
        float reach = override * CgSvgOps.Op.CAP_REACH;
        float[] bounds = cv.affineBounds(doc.minX(), doc.minY(), doc.maxX(), doc.maxY(), da, db, dc, dd, dtx, dty);
        if (cv.clippedOut(bounds[0] - reach, bounds[1] - reach, bounds[2] + reach, bounds[3] + reach)) return;
        CgSvg tier = doc.tier(devicePx);
        boolean shadows = !brush.flat() && tier.hasShadow();
        boolean layered = shadows && cv.layers.layerable();
        if (shadows && !layered && SHADOWS_LOGGED.add(doc)) {
            LOGGER.info(doc + " has a drop shadow, not drawn inside a turned or scaled movable node (a layer's picture"
                    + " cannot turn with it)");
        }
        List<CgSvgOps.Op> ops = tier.ops();
        for (int i = 0; i < ops.size(); ) {
            // A filtered element's ops share one shadow instance: the shadow is of their union, under them
            CgSvgScene.DropShadow shadow = layered ? ops.get(i).shadow() : null;
            int end = i + 1;
            while (end < ops.size() && (layered ? ops.get(end).shadow() : null) == shadow) end++;
            if (shadow == null) {
                for (; i < end; i++) op(tier, i, ops.get(i), brush, tint, cached, override, da, db, dc, dd, dtx, dty);
                continue;
            }
            CgFilter filter = shadow(shadow, sx, sy);
            float gx = ox + doc.minX() * sx, gy = oy + doc.minY() * sy;
            float gw = (doc.maxX() - doc.minX()) * sx, gh = (doc.maxY() - doc.minY()) * sy;
            if (!(cached && cv.snap)) {
                try (CgCanvasLayer l = cv.layer(gx, gy, gw, gh).filter(filter)) {
                    for (; i < end; i++) op(tier, i, ops.get(i), brush, tint, cached, override, da, db, dc, dd, dtx, dty);
                }
                continue;
            }
            // On the pixel grid the group's filtered picture is the same at every whole-pixel position: filtered once
            // into a cell of the path cache, then drawn from the atlas every icon shares
            float r = filter.reach() * cv.linearScale();
            int px0 = (int) Math.floor(dtx + doc.minX() * da - r), py0 = (int) Math.floor(dty + doc.minY() * dd - r);
            int pw = ((int) Math.ceil(dtx + doc.maxX() * da + r) - px0 + 3) & ~3;
            int ph = ((int) Math.ceil(dty + doc.maxY() * dd + r) - py0 + 3) & ~3;
            CgPathCache.Entry cell = null;
            if (pw <= SHADOW_CELL_MAX && ph <= SHADOW_CELL_MAX) {
                CgPathCache.Key key = new CgPathCache.Key(new SvgShadowGroup(tier, i, tint, brush.flat(), override), null,
                        da, dd);
                cell = CgPathCache.SHARED.find(key);
                if (cell == null) cell = CgPathCache.SHARED.claim(key, pw, ph, cv);
                if (cell != null && !cell.ready() && cell.owner() != cv) cell = null;
            }
            if (cell == null) {
                try (CgCanvasLayer l = cv.layer(gx, gy, gw, gh).filter(filter)) {
                    for (; i < end; i++) op(tier, i, ops.get(i), brush, tint, cached, override, da, db, dc, dd, dtx, dty);
                }
                continue;
            }
            if (cell.takeBuild()) {
                cv.paths.atlasFor(cell);
                try (CgCanvasLayer l = cv.layer(gx, gy, gw, gh).filter(filter)) {
                    l.cell = cell;
                    l.x0 = px0;
                    l.y0 = py0;
                    l.w = pw;
                    l.h = ph;
                    // Unclipped: the cell serves the icon wherever it is drawn
                    cv.clipX0 = cv.clipY0 = -Float.MAX_VALUE;
                    cv.clipX1 = cv.clipY1 = Float.MAX_VALUE;
                    for (int op = i; op < end; op++) {
                        op(tier, op, ops.get(op), brush, tint, cached, override, da, db, dc, dd, dtx, dty);
                    }
                }
            }
            shadowCell(cell, px0, py0);
            i = end;
        }
    }

    /** A shadowed group's filtered picture, from its cell, its corner at {@code (x0, y0)} in the base's units. */
    private void shadowCell(CgPathCache.Entry cell, int x0, int y0) {
        CgCanvas cv = canvas;
        int w = cell.width(), h = cell.height();
        float ex0 = x0, ey0 = y0, ex1 = x0 + w, ey1 = y0 + h;
        if (cv.clippedOut(ex0, ey0, ex1, ey1)) return;
        cv.pass();
        float[] record = cv.clear();
        CgUiElements.fill(record, 0, CgUiElements.FILL_TEXTURE, 0xFFFFFFFF, 0f);
        CgUiElements.flag(record, 0, CgUiElements.FLAG_PREMULTIPLIED, true);
        // A render target's row 0 is its bottom
        CgUiElements.uv(record, 0, cell.x() / (float) CgPathCache.SIZE, 1f - cell.y() / (float) CgPathCache.SIZE,
                (cell.x() + w) / (float) CgPathCache.SIZE, 1f - (cell.y() + h) / (float) CgPathCache.SIZE);
        CgUiElements.rect(record, 0, ex0, ey0, ex1, ey1);
        cv.finish(cv.base, 1f);
        cv.element(CgUiMaterials.element(), cell.texture(), null, cv.base, ex0, ey0, ex1, ey1);
        cv.pathDraws++;
    }

    /** {@code shadow}, in document units, as a filter in the canvas's: the document's scale applied. */
    private CgFilter shadow(CgSvgScene.DropShadow shadow, float sx, float sy) {
        if (shadow != lastShadow || sx != lastShadowSx || sy != lastShadowSy) {
            lastShadow = shadow;
            lastShadowSx = sx;
            lastShadowSy = sy;
            lastShadowFilter = CgFilter.dropShadow(shadow.dx() * sx, shadow.dy() * sy, shadow.sigmaX() * sx,
                    shadow.sigmaY() * sy, shadow.argb());
        }
        return lastShadowFilter;
    }

    /** One op: its coverage, cached or this frame's, then the element painting through it. */
    private void op(CgSvg tier, int index, CgSvgOps.Op op, CgBrush brush, int tint, boolean cached, float override,
                    float da, float db, float dc, float dd, float dtx, float dty) {
        CgCanvas cv = canvas;
        CgCanvasPaths paths = cv.paths;
        boolean coloured = op.coloured() && !brush.flat();
        float halfWidth = op.fill() ? 0f : override;
        float[] ob = op.bounds();
        CgGraphTexture texture = null;
        float u0 = 0f, v0 = 1f, u1 = 1f, v1 = 0f, ex0 = 0f, ey0 = 0f, ex1 = 0f, ey1 = 0f;
        if (cached) {
            // The raster's scale: the draw's, floored to MIN_RASTER_PX across, off the grid the ladder's rung at or above
            float rx = da, ry = dd;
            float span = Math.max(tier.width() * rx, tier.height() * ry);
            if (span < MIN_RASTER_PX) {
                rx *= MIN_RASTER_PX / span;
                ry *= MIN_RASTER_PX / span;
            }
            boolean onPixel = dtx == Math.round(dtx) && dty == Math.round(dty) && rx == da && ry == dd;
            CgPathCache.Entry cell = null;
            int cornerX = 0, cornerY = 0, w = 0, h = 0;
            for (int attempt = 0; attempt < 2 && cell == null; attempt++) {
                if (attempt == 1) {
                    if (onPixel) break;
                    float k = rung(Math.max(rx, ry)) / Math.max(rx, ry);
                    rx *= k;
                    ry *= k;
                }
                float rasterHalf = halfWidth * rx / da;
                int grow = (int) Math.ceil(rasterHalf * CgSvgOps.Op.CAP_REACH);
                cornerX = (int) Math.floor(ob[0] * rx) - CgPathCache.PAD - grow;
                cornerY = (int) Math.floor(ob[1] * ry) - CgPathCache.PAD - grow;
                w = (int) Math.ceil(ob[2] * rx) + CgPathCache.PAD + grow - cornerX;
                h = (int) Math.ceil(ob[3] * ry) + CgPathCache.PAD + grow - cornerY;
                CgPathCache.Key key = new CgPathCache.Key(new SvgOp(tier, index, coloured, rasterHalf), null, rx, ry);
                cell = CgPathCache.SHARED.find(key);
                // The exact raster is built only on the grid; between pixels a missing one is the rung's
                if (cell == null && (onPixel || attempt == 1)) cell = CgPathCache.SHARED.claim(key, w, h, cv);
                if (cell != null && !cell.ready() && cell.owner() != cv) cell = null;
                if (cell != null && cell.takeBuild()) {
                    CgGraphTexture atlas = paths.atlasFor(cell);
                    cell.origin(-cornerX, -cornerY);
                    raster(atlas, false, paths.atlasCamera, op, coloured, rasterHalf, rx, 0f, 0f, ry,
                            cell.x() - cornerX, cell.y() - cornerY, cell.x(), cell.y(), cell.x() + cell.width(),
                            cell.y() + cell.height());
                    CgPathCache.Entry built = cell;
                    cv.recording.callback("cg.path.ready", null, () -> CgPathCache.SHARED.ready(built), atlas);
                }
            }
            if (cell != null) {
                texture = cell.texture();
                float kx = da / rx, ky = dd / ry;
                ex0 = dtx + cornerX * kx;
                ey0 = dty + cornerY * ky;
                ex1 = ex0 + w * kx;
                ey1 = ey0 + h * ky;
                u0 = cell.x() / (float) CgPathCache.SIZE;
                u1 = (cell.x() + w) / (float) CgPathCache.SIZE;
                // A render target's row 0 is its bottom
                v0 = 1f - cell.y() / (float) CgPathCache.SIZE;
                v1 = 1f - (cell.y() + h) / (float) CgPathCache.SIZE;
                cv.pathDraws++;
            }
        }
        if (texture == null) {
            // This frame's raster, in device pixels: larger, turned, or another canvas's cell not built yet
            float[] bounds = cv.affineBounds(ob[0], ob[1], ob[2], ob[3], da, db, dc, dd, dtx, dty);
            int grow = (int) Math.ceil(halfWidth * CgSvgOps.Op.CAP_REACH);
            int cornerX = (int) Math.floor(bounds[0]) - CgPathCache.PAD - grow;
            int cornerY = (int) Math.floor(bounds[1]) - CgPathCache.PAD - grow;
            int w = (int) Math.ceil(bounds[2]) + CgPathCache.PAD + grow - cornerX;
            int h = (int) Math.ceil(bounds[3]) + CgPathCache.PAD + grow - cornerY;
            ex0 = cornerX;
            ey0 = cornerY;
            ex1 = cornerX + w;
            ey1 = cornerY + h;
            if (cv.clippedOut(ex0, ey0, ex1, ey1)) return;
            texture = CgGraphTexture.transientTexture("canvas_svg", CgTextureDesc.of(w, h, CgPathCache.FORMAT));
            raster(texture, true, paths.rasterCamera.screenLens(w, h), op, coloured, halfWidth, da, db, dc, dd,
                    dtx - cornerX, dty - cornerY, 0f, 0f, w, h);
            u0 = 0f;
            u1 = 1f;
            v0 = 1f;
            v1 = 0f;
        } else if (cv.clippedOut(ex0, ey0, ex1, ey1)) {
            return;
        }

        cv.pass();
        float[] record = cv.clear();
        if (coloured) {
            // Its own colours, premultiplied in the raster: the tint multiplies them
            CgUiElements.fill(record, 0, CgUiElements.FILL_TEXTURE, tint, 0f);
            CgUiElements.flag(record, 0, CgUiElements.FLAG_PREMULTIPLIED, true);
        } else {
            int argb = brush.flat() || op.currentColor() ? brush.color() : op.argb();
            CgUiElements.fill(record, 0, CgUiElements.FILL_COLOR, CgCanvas.mul(argb, tint), 0f);
            CgUiElements.flag(record, 0, CgUiElements.FLAG_COVERAGE, true);
        }
        CgUiElements.uv(record, 0, u0, v0, u1, v1);
        CgUiElements.rect(record, 0, ex0, ey0, ex1, ey1);
        cv.finish(cv.base, 1f);
        cv.element(CgUiMaterials.element(), texture, null, cv.base, ex0, ey0, ex1, ey1);
    }

    /** Records op {@code op}'s coverage into {@code texture} under the affine, landing the canvas's pass first. */
    private void raster(CgGraphTexture texture, boolean clear, CgCamera lens, CgSvgOps.Op op, boolean coloured,
                        float halfWidth, float ra, float rb, float rc, float rd, float rtx, float rty,
                        float x0, float y0, float x1, float y1) {
        canvas.recorder.end();
        int n = cells.op(op, coloured, halfWidth, ra, rb, rc, rd, rtx, rty);
        CgUiRecorder coverage = canvas.paths.coverage;
        coverage.begin(canvas.recording, texture, clear ? CgLoad.CLEAR : CgLoad.load(), lens);
        if (n > 0) {
            coverage.draw(CgInstanceKind.CURVE, coloured ? CgCanvasPrimitives.colourCoverage() : CgCanvasPrimitives.coverage(),
                    null, null, null, 0, cells.records(), 0, n, x0, y0, x1, y1);
        } else if (clear) {
            coverage.openPass();
        }
        coverage.end();
    }

    /** The rung of the raster ladder at or above {@code exact}: an icon growing through a hundred scales builds a dozen rasters. */
    private static float rung(float exact) {
        return (float) Math.pow(2.0, Math.ceil(Math.log(exact) / Math.log(2.0) * LADDER_STEPS) / LADDER_STEPS);
    }
}
