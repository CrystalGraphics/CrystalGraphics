package com.crystalgraphics.api.canvas;

import java.util.List;

import javax.annotation.Nullable;

import com.crystalgraphics.api.material.CgMaterialInterface;
import com.crystalgraphics.api.paint.CgPaint;
import com.crystalgraphics.api.paint.gradient.CgGradient;
import com.crystalgraphics.api.vector.CgFillRule;
import com.crystalgraphics.api.vector.CgStroke;
import com.crystalgraphics.api.vector.CgStrokeJoin;
import com.crystalgraphics.api.vector.CgVectorPath;
import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.gpu.resource.CgTextureDesc;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgLoad;
import com.crystalgraphics.render.ui.CgCanvasPrimitives;
import com.crystalgraphics.render.ui.CgCanvasTables;
import com.crystalgraphics.render.ui.CgUiRecorder;
import com.crystalgraphics.render.ui.element.CgUiElements;
import com.crystalgraphics.render.ui.path.CgPathCache;
import com.crystalgraphics.render.ui.path.CgPathCells;
import com.crystalgraphics.render.ui.path.CgPathFlattener;
import com.crystalgraphics.render.ui.path.CgPathStroker;

/**
 * A canvas's vector paths, filled or stroked: each one's exact-area coverage rasterised (into the shared path atlas
 * when it is small, axis-aligned and on the pixel grid, else into a texture for this frame), then one element painting
 * through it. A brush or grid paints through the path's mask in a layer. The coverage pass and the atlas's cells are
 * shared with SVG icons.
 */
final class CgCanvasPaths {

    private final CgCanvas canvas;
    /** Records a coverage raster: its own pass, before the element that paints it. */
    final CgUiRecorder coverage = new CgUiRecorder();
    private final CgPathCells cells = new CgPathCells();
    final CgCamera atlasCamera = new CgCamera().screenLens(CgPathCache.SIZE, CgPathCache.SIZE);
    final CgCamera rasterCamera = new CgCamera();

    CgCanvasPaths(CgCanvas canvas) {
        this.canvas = canvas;
    }

    /** How far a stroke's ink reaches past its path: its half-width, a miter or a square cap further. */
    static float reach(@Nullable CgStroke stroke) {
        return stroke == null ? 0f : stroke.width() * 0.5f
                * Math.max(1.4142135f, stroke.join() == CgStrokeJoin.MITER ? stroke.miterLimit() : 1f);
    }

    /** Drops what is recorded and not landed, and the atlas cells this canvas claimed: they will never be built. */
    void abandon() {
        coverage.abandon();
        CgPathCache.SHARED.abandon(canvas);
    }

    /** {@code path} filled ({@code stroke} null) or stroked in {@code paint}, the element drawn as {@code material}. */
    void draw(CgVectorPath path, @Nullable CgStroke stroke, CgPaint paint, CgMaterialInterface material) {
        CgCanvas cv = canvas;
        if (cv.recording == null) throw new IllegalStateException("drawing on a canvas that records nowhere: begin() first");
        if (!(paint instanceof CgPaint.Solid) && !(paint instanceof CgGradient)) {
            paintThrough(path, stroke, paint);
            return;
        }
        if (path.isEmpty() || cv.opacity <= 0f) return;
        float a = cv.a, b = cv.b, c = cv.c, d = cv.d, tx = cv.tx, ty = cv.ty;
        float scale = (float) Math.sqrt(Math.max(a * a + b * b, c * c + d * d));
        if (!(scale > 0f)) return;
        float fade = 1f;
        if (stroke != null && stroke.width() * scale < 1f) {
            // A hairline: a device pixel wide, faded by how much thinner it is
            fade = stroke.width() * scale;
            stroke = stroke.width(1f / scale);
        }
        float reach = reach(stroke);
        float lx0 = path.minX() - reach, ly0 = path.minY() - reach, lx1 = path.maxX() + reach, ly1 = path.maxY() + reach;
        boolean cached = b == 0f && c == 0f && a > 0f && d > 0f && cv.snap
                && (lx1 - lx0) * a <= CgPathCache.MAX_PX && (ly1 - ly0) * d <= CgPathCache.MAX_PX;
        // A cached path sits on the pixel grid: its translation snapped, its raster built without it
        float rtx = cached ? 0f : tx, rty = cached ? 0f : ty;
        float placeX = cached ? Math.round(tx) : 0f, placeY = cached ? Math.round(ty) : 0f;
        float[] bounds = cv.affineBounds(lx0, ly0, lx1, ly1, a, b, c, d, rtx, rty);
        int cornerX = (int) Math.floor(bounds[0]) - CgPathCache.PAD, cornerY = (int) Math.floor(bounds[1]) - CgPathCache.PAD;
        int w = (int) Math.ceil(bounds[2]) + CgPathCache.PAD - cornerX, h = (int) Math.ceil(bounds[3]) + CgPathCache.PAD - cornerY;
        float ex0 = placeX + cornerX, ey0 = placeY + cornerY, ex1 = ex0 + w, ey1 = ey0 + h;
        if (cv.clippedOut(ex0, ey0, ex1, ey1)) return;

        CgGraphTexture texture;
        float u0, v0, u1, v1;
        CgPathCache.Entry cell = cached ? cell(path, stroke, w, h) : null;
        if (cell != null) {
            texture = cell.texture();
            if (cell.takeBuild()) build(cell, path, stroke, cornerX, cornerY);
            u0 = cell.x() / (float) CgPathCache.SIZE;
            u1 = (cell.x() + w) / (float) CgPathCache.SIZE;
            // A render target's row 0 is its bottom
            v0 = 1f - cell.y() / (float) CgPathCache.SIZE;
            v1 = 1f - (cell.y() + h) / (float) CgPathCache.SIZE;
            cv.pathDraws++;
        } else {
            texture = CgGraphTexture.transientTexture("canvas_path", CgTextureDesc.of(w, h, CgPathCache.FORMAT));
            raster(texture, true, rasterCamera.screenLens(w, h), path, stroke, a, b, c, d, rtx - cornerX, rty - cornerY,
                    0f, 0f, w, h);
            u0 = 0f;
            u1 = 1f;
            v0 = 1f;
            v1 = 0f;
        }

        cv.pass();
        float[] record = cv.clear();
        int tint = (Math.round(255f * fade) << 24) | 0x00FFFFFF;
        float gain = 1f;
        if (paint instanceof CgPaint.Solid solid) {
            CgUiElements.fill(record, 0, CgUiElements.FILL_COLOR, CgCanvas.mul(solid.argb(), tint), 0f);
            gain = solid.intensity();
        } else {
            CgUiElements.fill(record, 0, CgUiElements.FILL_GRADIENT, tint,
                    CgCanvasTables.of(cv.recording).gradients().add((CgGradient) paint));
        }
        CgUiElements.uv(record, 0, u0, v0, u1, v1);
        paintFrame(record, path, ex0, ey0, cached);
        CgUiElements.rect(record, 0, ex0, ey0, ex1, ey1);
        cv.finish(cv.base, gain);
        cv.element(material, texture, null, cv.base, ex0, ey0, ex1, ey1);
    }

    /** A brush or grid on a path: the paint over the path's bounds, in a layer masked by its fill or its stroke. */
    private void paintThrough(CgVectorPath path, @Nullable CgStroke stroke, CgPaint paint) {
        float reach = reach(stroke);
        float x = path.minX() - reach, y = path.minY() - reach;
        float w = path.maxX() - path.minX() + 2f * reach, h = path.maxY() - path.minY() + 2f * reach;
        try (CgCanvasLayer l = canvas.layer(x, y, w, h).mask(path)) {
            l.maskStroke = stroke;
            canvas.box(x, y, w, h).fill(paint).draw();
        }
    }

    /** The cell for a cached path: claimed, built or pending; null when this canvas rasterises it for its frame. */
    @Nullable
    private CgPathCache.Entry cell(CgVectorPath path, @Nullable CgStroke stroke, int w, int h) {
        CgPathCache.Key key = new CgPathCache.Key(path, stroke, canvas.a, canvas.d);
        CgPathCache.Entry cell = CgPathCache.SHARED.find(key);
        if (cell == null) cell = CgPathCache.SHARED.claim(key, w, h, canvas);
        // Another canvas's cell, not built yet: drawing it would read a build that may not have executed
        if (cell == null || !cell.ready() && cell.owner() != canvas) return null;
        return cell;
    }

    /** A claimed cell's raster, recorded into this recording, opened to every canvas once it has executed. */
    private void build(CgPathCache.Entry cell, CgVectorPath path, @Nullable CgStroke stroke, int cornerX, int cornerY) {
        CgGraphTexture atlas = atlasFor(cell);
        cell.origin(-cornerX, -cornerY);
        raster(atlas, false, atlasCamera, path, stroke, canvas.a, 0f, 0f, canvas.d, cell.x() - cornerX, cell.y() - cornerY,
                cell.x(), cell.y(), cell.x() + cell.width(), cell.y() + cell.height());
        canvas.recording.callback("cg.path.ready", null, () -> CgPathCache.SHARED.ready(cell), atlas);
    }

    /**
     * A claimed cell's atlas, its generation's release and clear recorded first when this canvas owes them: the clear
     * opens the generation to every canvas once it has executed.
     */
    CgGraphTexture atlasFor(CgPathCache.Entry cell) {
        CgGraphTexture atlas = cell.texture();
        CgGraphTexture released = cell.takeRelease();
        if (released != null) canvas.recording.release(released);
        if (cell.takeClear()) {
            canvas.recorder.end();
            canvas.recording.raster("path-atlas-clear").color(atlas, CgLoad.CLEAR).camera(atlasCamera).end();
            CgPathCache.Generation generation = cell.generation();
            canvas.recording.callback("cg.path.atlas-ready", null, () -> CgPathCache.SHARED.ready(generation), atlas);
        }
        return atlas;
    }

    /**
     * Records {@code path}'s exact-area coverage into {@code texture} under the affine, landing the canvas's pass first:
     * the raster executes before the element that reads it, and the canvas's earlier items before both.
     */
    private void raster(CgGraphTexture texture, boolean clear, CgCamera lens, CgVectorPath path, @Nullable CgStroke stroke,
                        float ra, float rb, float rc, float rd, float rtx, float rty, float x0, float y0, float x1, float y1) {
        canvas.recorder.end();
        List<CgPathFlattener.Contour> local = CgPathFlattener.flatten(path, ra, rb, rc, rd, rtx, rty);
        boolean evenOdd = false;
        if (stroke != null) {
            float scale = (float) Math.sqrt(Math.max(ra * ra + rb * rb, rc * rc + rd * rd));
            local = CgPathStroker.outline(local, stroke, CgPathFlattener.TOLERANCE / scale);
        } else {
            evenOdd = path.fillRule() == CgFillRule.EVEN_ODD;
        }
        List<CgPathFlattener.Contour> device = CgPathFlattener.transform(local, ra, rb, rc, rd, rtx, rty);
        int n = cells.fill(device, evenOdd, 0f, 0f);
        coverage.begin(canvas.recording, texture, clear ? CgLoad.CLEAR : CgLoad.load(), lens);
        if (n > 0) {
            coverage.draw(CgInstanceKind.CURVE, CgCanvasPrimitives.coverage(), null, null, null, 0, cells.records(), 0, n,
                    x0, y0, x1, y1);
        } else if (clear) {
            coverage.openPass();
        }
        coverage.end();
    }

    /**
     * The element's paint frame: from a pixel's offset from its corner {@code (ex0, ey0)} to the path's own box, 0 to
     * 1, through the transform's inverse. A cached path's translation is its snapped place.
     */
    private void paintFrame(float[] record, CgVectorPath path, float ex0, float ey0, boolean cached) {
        CgCanvas cv = canvas;
        float a = cv.a, b = cv.b, c = cv.c, d = cv.d;
        float det = a * d - b * c;
        float ia = d / det, ib = -b / det, ic = -c / det, id = a / det;
        float ttx = cached ? Math.round(cv.tx) : cv.tx, tty = cached ? Math.round(cv.ty) : cv.ty;
        float bw = Math.max(path.maxX() - path.minX(), 1e-6f), bh = Math.max(path.maxY() - path.minY(), 1e-6f);
        // local = inverse * (corner + e - t); unit = (local - min) / size
        float ox = ex0 - ttx, oy = ey0 - tty;
        float lx = ia * ox + ic * oy, ly = ib * ox + id * oy;
        CgUiElements.paintFrame(record, 0, ia / bw, ib / bh, ic / bw, id / bh, (lx - path.minX()) / bw,
                (ly - path.minY()) / bh, bw, bh);
    }
}
