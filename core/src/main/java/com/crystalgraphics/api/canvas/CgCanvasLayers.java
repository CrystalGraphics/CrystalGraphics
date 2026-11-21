package com.crystalgraphics.api.canvas;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import javax.annotation.Nullable;

import com.crystalgraphics.api.material.CgBlendMode;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialInstance;
import com.crystalgraphics.api.material.CgMaterialInterface;
import com.crystalgraphics.api.paint.CgPaint;
import com.crystalgraphics.api.paint.effect.CgFilter;
import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.compute.ops.CgGpuOps;
import com.crystalgraphics.gpu.resource.CgTextureDesc;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.graph.CgComputePass;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgLoad;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.ui.CgCanvasTables;
import com.crystalgraphics.render.ui.CgUiRecorder;
import com.crystalgraphics.render.ui.element.CgUiElements;
import com.crystalgraphics.render.ui.path.CgPathCache;
import com.crystalgraphics.render.ui.property.CgPalette;
import com.crystalgraphics.render.ui.property.CgSpatialTree;

/**
 * A canvas's layers, Skia's {@code saveLayer}: the open ones innermost last, each a picture placed at its first draw,
 * then masked, filtered and composited as one element when it closes. It also draws the open layers over a backdrop's
 * capture: what a pane inside them sees.
 */
final class CgCanvasLayers {

    private final CgCanvas canvas;
    /** The open layers, innermost last, kept for reuse. */
    private CgCanvasLayer[] stack = new CgCanvasLayer[4];
    int count;
    /** Layers opened this turn: a capture taken before one opened no longer serves. */
    int opens;
    /** A layer's own restore is running: it must not close the layer again. */
    boolean closing;
    // The filter passes' recorder, lens and record
    private final CgUiRecorder filters = new CgUiRecorder();
    private final CgCamera filterLens = new CgCamera();
    private final float[] filterRecord = new float[CgUiElements.FLOATS];
    /** Where a mask path's composite stops, in the outer base's units. */
    private float maskX0, maskY0, maskX1, maskY1;
    /** Material filters' instances this turn, reused turn to turn: one a layer, since each holds its picture. */
    private final List<CgMaterialInstance> filterInstances = new ArrayList<>();
    private int filterInstancesUsed;

    CgCanvasLayers(CgCanvas canvas) {
        this.canvas = canvas;
    }

    /** The innermost open layer. */
    CgCanvasLayer top() {
        return stack[count - 1];
    }

    /** Open layer {@code i}, outermost first. */
    CgCanvasLayer at(int i) {
        return stack[i];
    }

    /** A new turn: none open, material filters' instances free again. */
    void beginTurn() {
        drop();
        filterInstancesUsed = 0;
    }

    /** Drops what is drawn and not landed: its recording is being discarded. */
    void abandon() {
        drop();
        filters.abandon();
    }

    CgCanvasLayer open(float x, float y, float w, float h) {
        CgCanvas cv = canvas;
        if (cv.recording == null) throw new IllegalStateException("a layer on a canvas that records nowhere: begin() first");
        cv.recorder.end();
        opens++;
        if (count == stack.length) stack = Arrays.copyOf(stack, count * 2);
        CgCanvasLayer l = stack[count];
        if (l == null) stack[count] = l = new CgCanvasLayer(cv);
        l.reset();
        if (!Float.isNaN(x)) {
            float[] bounds = cv.affineBounds(x, y, x + w, y + h, cv.a, cv.b, cv.c, cv.d, cv.tx, cv.ty);
            l.boundX0 = bounds[0];
            l.boundY0 = bounds[1];
            l.boundX1 = bounds[2];
            l.boundY1 = bounds[3];
        }
        l.clipX0 = cv.clipX0;
        l.clipY0 = cv.clipY0;
        l.clipX1 = cv.clipX1;
        l.clipY1 = cv.clipY1;
        l.scale = cv.linearScale();
        l.outerBase = cv.base;
        owner(l);
        cv.save();
        l.saveDepth = cv.depth;
        if (!Float.isNaN(x)) cv.cull(l.boundX0, l.boundY0, l.boundX1, l.boundY1);
        // Its items under a node of their own: clips made inside evaluate through the palette, which the pass's view
        // origin moves to the picture's corner. The clip outside applies when it composites.
        l.node = CgCanvasTables.of(cv.recording).spatial().add(cv.base, 1f, 0f, 0f, 1f, 0f, 0f, false);
        cv.enterLayer(l.node);
        count++;
        return l;
    }

    /** Where the outer base sits in the root as recorded: a layer's picture is placed there. */
    private void owner(CgCanvasLayer l) {
        CgSpatialTree tree = CgCanvasTables.of(canvas.recording).spatial();
        float ox = 0f, oy = 0f;
        for (int n = l.outerBase; n != 0; n = tree.parent(n)) {
            if (tree.local(n, 0) != 1f || tree.local(n, 1) != 0f || tree.local(n, 2) != 0f || tree.local(n, 3) != 1f) {
                throw new IllegalStateException("a layer inside a turned or scaled movable node: open the layer outside it,"
                        + " or make the node by translation (movable() under an axis-aligned transform)");
            }
            ox += tree.local(n, 4);
            oy += tree.local(n, 5);
        }
        l.ownerX = ox;
        l.ownerY = oy;
    }

    /** Whether a layer may open here: no turned or scaled movable node above the current one ({@link #owner}). */
    boolean layerable() {
        CgSpatialTree tree = CgCanvasTables.of(canvas.recording).spatial();
        for (int n = canvas.base; n != 0; n = tree.parent(n)) {
            if (tree.local(n, 0) != 1f || tree.local(n, 1) != 0f || tree.local(n, 2) != 0f || tree.local(n, 3) != 1f) {
                return false;
            }
        }
        return true;
    }

    /** A layer's picture placed at its first draw: its bounds or the clip, grown by the filter's reach, cut to the target. */
    void place(CgCanvasLayer l) {
        if (l.cell != null) {
            l.texture = layerTexture(l.w, l.h);
            l.lens.screenLens(l.w, l.h);
            l.load = CgLoad.CLEAR;
            l.opened = true;
            return;
        }
        float reach = l.filter.reach() * l.scale;
        float x0, y0, x1, y1;
        if (Float.isNaN(l.boundX0)) {
            x0 = l.clipX0;
            y0 = l.clipY0;
            x1 = l.clipX1;
            y1 = l.clipY1;
        } else {
            x0 = Math.max(l.boundX0 - reach, l.clipX0 - reach);
            y0 = Math.max(l.boundY0 - reach, l.clipY0 - reach);
            x1 = Math.min(l.boundX1 + reach, l.clipX1 + reach);
            y1 = Math.min(l.boundY1 + reach, l.clipY1 + reach);
        }
        // The target, in the outer base's space
        CgCamera camera = canvas.camera;
        x0 = Math.max(x0, -l.ownerX);
        y0 = Math.max(y0, -l.ownerY);
        x1 = Math.min(x1, camera.width() - l.ownerX);
        y1 = Math.min(y1, camera.height() - l.ownerY);
        int ix0 = (int) Math.floor(x0), iy0 = (int) Math.floor(y0);
        int w = Math.max(1, (int) Math.ceil(x1) - ix0), h = Math.max(1, (int) Math.ceil(y1) - iy0);
        if (blurs(l.filter)) {
            // A blur works at a half or a quarter: the picture a whole number of its texels
            w = (w + 3) & ~3;
            h = (h + 3) & ~3;
        }
        l.x0 = ix0;
        l.y0 = iy0;
        l.w = w;
        l.h = h;
        l.texture = layerTexture(w, h);
        l.lens.screenLens(w, h);
        l.load = CgLoad.CLEAR;
        l.opened = true;
    }

    private static boolean blurs(CgFilter filter) {
        for (CgFilter.Op op : filter.ops()) {
            if (op.kind() == CgFilter.Kind.BLUR || op.kind() == CgFilter.Kind.DROP_SHADOW) return true;
        }
        return false;
    }

    /** Masks, filters and composites {@code l}, restoring what its opening saved. */
    void close(CgCanvasLayer l) {
        if (!l.live) return;
        if (count == 0 || stack[count - 1] != l) {
            throw new IllegalStateException("layers close in the reverse of their opening");
        }
        CgCanvas cv = canvas;
        while (cv.depth > l.saveDepth) cv.restore();
        if (l.opened && (l.maskPath != null || l.maskPaint != null)) mask(l);
        cv.recorder.end();
        count--;
        l.live = false;
        closing = true;
        try {
            cv.restore();
        } finally {
            closing = false;
        }
        if (l.kept != null) {
            cv.keptLayers.finish(l);
            return;
        }
        CgRecording recording = cv.recording;
        if (l.cell != null) {
            CgPathCache.Entry cell = l.cell;
            if (l.opened) {
                recording.copy(filtered(l), 0, 0, l.w, l.h, cell.texture(), cell.x(), CgPathCache.SIZE - cell.y() - l.h,
                        l.w, l.h, false);
            }
            recording.callback("cg.path.ready", null, () -> CgPathCache.SHARED.ready(cell), cell.texture());
            return;
        }
        if (!l.opened || l.opacity <= 0f || cv.opacity <= 0f) return;
        composite(l, filtered(l));
    }

    /** The mask drawn into the layer, keeping what it covers: a path's coverage, or a paint over the picture. */
    private void mask(CgCanvasLayer l) {
        CgCanvas cv = canvas;
        CgMaterialInterface masking = CgUiMaterials.element(CgBlendMode.MASK);
        if (l.maskPath != null) {
            cv.paths.draw(l.maskPath, l.maskStroke, CgPaint.color(0xFFFFFFFF), masking);
            // Outside the path's bounds nothing was masked: the composite stops there
            float r = CgCanvasPaths.reach(l.maskStroke);
            float[] bounds = cv.affineBounds(l.maskPath.minX() - r, l.maskPath.minY() - r, l.maskPath.maxX() + r,
                    l.maskPath.maxY() + r, cv.a, cv.b, cv.c, cv.d, cv.tx, cv.ty);
            maskX0 = bounds[0] - 1f;
            maskY0 = bounds[1] - 1f;
            maskX1 = bounds[2] + 1f;
            maskY1 = bounds[3] + 1f;
        } else {
            cv.save().setTransform(1f, 0f, 0f, 1f, 0f, 0f);
            cv.box(l.x0, l.y0, l.w, l.h).fill(l.maskPaint).material(masking).draw();
            cv.restore();
            maskX0 = -Float.MAX_VALUE;
            maskY0 = -Float.MAX_VALUE;
            maskX1 = Float.MAX_VALUE;
            maskY1 = Float.MAX_VALUE;
        }
    }

    /** The picture through the layer's filter chain, each step on the last one's result. */
    private CgGraphTexture filtered(CgCanvasLayer l) {
        CgGraphTexture picture = l.texture;
        for (CgFilter.Op op : l.filter.ops()) {
            // Lengths are the canvas's units: device pixels by the transform, then the picture's texels
            float k = l.scale * picture.getWidth() / (float) l.w;
            float[] v = op.values();
            switch (op.kind()) {
                case BLUR -> picture = blurred(picture, v[0] * k, v[1] * k);
                case COLOR_MATRIX -> {
                    int pw = picture.getWidth(), ph = picture.getHeight();
                    CgGraphTexture out = layerTexture(pw, ph);
                    CgComputePass pass = canvas.recording.compute("canvas.layer.matrix");
                    CgGpuOps.colorMatrix(pass, picture, 0, 0, pw, ph, out, v);
                    pass.end();
                    picture = out;
                }
                case DROP_SHADOW -> {
                    CgGraphTexture shadow = blurred(picture, v[2] * k, v[3] * k);
                    picture = composed(picture, shadow, v[0] * k, v[1] * k, Float.floatToRawIntBits(v[4]), null);
                }
                case MATERIAL -> picture = composed(picture, null, 0f, 0f, 0, op);
                case LUMINOSITY -> throw new IllegalArgumentException(
                        "luminosity is a backdrop filter's step; a layer's filter cannot take it");
            }
        }
        return picture;
    }

    /** {@code picture} blurred by sigmas in its texels, at a half or a quarter where the blur is wide. */
    private CgGraphTexture blurred(CgGraphTexture picture, float sigmaX, float sigmaY) {
        if (Math.max(sigmaX, sigmaY) < 0.25f) return picture;
        int pw = picture.getWidth(), ph = picture.getHeight();
        int scale = CgGpuOps.blurScale(Math.max(sigmaX, sigmaY));
        CgGraphTexture out = layerTexture((pw + scale - 1) / scale, (ph + scale - 1) / scale);
        CgComputePass pass = canvas.recording.compute("canvas.layer.blur");
        CgGpuOps.blurScaled(pass, picture, 0, 0, pw, ph, out, Math.max(0f, sigmaX), Math.max(0f, sigmaY), CgGpuOps.Edge.CLAMP);
        pass.end();
        return out;
    }

    /**
     * A new picture the size of {@code picture}: a shadow's alpha under it, offset and tinted; or the picture through a
     * material filter.
     */
    private CgGraphTexture composed(CgGraphTexture picture, @Nullable CgGraphTexture shadow, float dx, float dy,
                                    int shadowArgb, @Nullable CgFilter.Op through) {
        int pw = picture.getWidth(), ph = picture.getHeight();
        CgGraphTexture out = layerTexture(pw, ph);
        filters.begin(canvas.recording, out, CgLoad.CLEAR, filterLens.screenLens(pw, ph));
        if (shadow != null) {
            filterElement(CgUiElements.FILL_MASK, shadowArgb, false, dx, dy, pw, ph);
            filters.draw(CgInstanceKind.ELEMENT, CgUiMaterials.element(), null, shadow, null, 0, filterRecord, 0, 1,
                    dx, dy, dx + pw, dy + ph);
        }
        filterElement(CgUiElements.FILL_TEXTURE, 0xFFFFFFFF, true, 0f, 0f, pw, ph);
        CgMaterialInterface material = CgUiMaterials.element();
        if (through != null) {
            CgMaterialInstance instance = filterInstance(through.material());
            instance.sampler(through.textureParameter(), 0, picture);
            material = instance;
        }
        filters.draw(CgInstanceKind.ELEMENT, material, null, picture, null, 0, filterRecord, 0, 1, 0f, 0f, pw, ph);
        filters.end();
        return out;
    }

    /**
     * What is drawn so far over {@code capture}: the canvas's target where the capture is of what lies behind it, then
     * each open layer's picture, outermost first. What a pane in the innermost sees behind it.
     */
    void drawOver(CgGraphTexture capture, boolean behind, int w, int h) {
        filters.begin(canvas.recording, capture, CgLoad.load(), filterLens.screenLens(capture.getWidth(), capture.getHeight()));
        if (behind) {
            float y = capture.getHeight() - h;
            filterElement(CgUiElements.FILL_TEXTURE, 0xFFFFFFFF, true, 0f, y, w, h);
            filters.draw(CgInstanceKind.ELEMENT, CgUiMaterials.element(), null, canvas.target, null, 0, filterRecord, 0, 1,
                    0f, y, w, y + h);
        }
        for (int i = 0; i < count; i++) {
            CgCanvasLayer l = stack[i];
            if (!l.opened) continue;
            float x = l.ownerX + l.x0, y = capture.getHeight() - h + l.ownerY + l.y0;
            filterElement(CgUiElements.FILL_TEXTURE, 0xFFFFFFFF, true, x, y, l.w, l.h);
            filters.draw(CgInstanceKind.ELEMENT, CgUiMaterials.element(), null, l.texture, null, 0, filterRecord, 0, 1,
                    x, y, x + l.w, y + l.h);
        }
        filters.end();
    }

    private void filterElement(int fill, int argb, boolean premultiplied, float x, float y, int w, int h) {
        Arrays.fill(filterRecord, 0f);
        CgUiElements.fill(filterRecord, 0, fill, argb, 0f);
        CgUiElements.flag(filterRecord, 0, CgUiElements.FLAG_PREMULTIPLIED, premultiplied);
        // A render target's row 0 is its bottom
        CgUiElements.uv(filterRecord, 0, 0f, 1f, 1f, 0f);
        CgUiElements.rect(filterRecord, 0, x, y, x + w, y + h);
        CgUiElements.nodes(filterRecord, 0, CgPalette.pack(0, 0), 0);
        CgUiElements.opacity(filterRecord, 0, 1f, 1f);
    }

    /** A material filter's instance for this turn: the caller's own, or one of the canvas's over a material. */
    private CgMaterialInstance filterInstance(CgMaterialInterface material) {
        if (material instanceof CgMaterialInstance own) return own;
        CgMaterial asset = material.material();
        for (int i = filterInstancesUsed; i < filterInstances.size(); i++) {
            if (filterInstances.get(i).material() == asset) {
                Collections.swap(filterInstances, i, filterInstancesUsed);
                return filterInstances.get(filterInstancesUsed++);
            }
        }
        CgMaterialInstance made = asset.instance();
        filterInstances.add(filterInstancesUsed, made);
        filterInstancesUsed++;
        return made;
    }

    private static CgGraphTexture layerTexture(int w, int h) {
        return CgGraphTexture.transientTexture("canvas_layer", CgTextureDesc.of(w, h, CgFormat.RGBA16_FLOAT));
    }

    /** The picture drawn as one element at the layer's region, under the clip and opacity outside it. */
    private void composite(CgCanvasLayer l, CgGraphTexture picture) {
        CgCanvas cv = canvas;
        float ex0 = l.x0, ey0 = l.y0, ex1 = l.x0 + l.w, ey1 = l.y0 + l.h;
        if (l.maskPath != null) {
            ex0 = Math.max(ex0, maskX0);
            ey0 = Math.max(ey0, maskY0);
            ex1 = Math.min(ex1, maskX1);
            ey1 = Math.min(ey1, maskY1);
        }
        if (ex1 <= ex0 || ey1 <= ey0 || cv.clippedOut(ex0, ey0, ex1, ey1)) return;
        cv.pass();
        float[] record = cv.clear();
        CgUiElements.fill(record, 0, CgUiElements.FILL_TEXTURE, (Math.round(255f * l.opacity) << 24) | 0x00FFFFFF, 0f);
        CgUiElements.flag(record, 0, CgUiElements.FLAG_PREMULTIPLIED, true);
        // A render target's row 0 is its bottom
        CgUiElements.uv(record, 0, (ex0 - l.x0) / l.w, 1f - (ey0 - l.y0) / l.h, (ex1 - l.x0) / l.w, 1f - (ey1 - l.y0) / l.h);
        CgUiElements.rect(record, 0, ex0, ey0, ex1, ey1);
        cv.finish(cv.base, 1f);
        cv.element(CgUiMaterials.element(l.blend == CgBlendMode.ADDITIVE ? CgBlendMode.ADDITIVE : CgBlendMode.PREMULTIPLIED),
                picture, null, cv.base, ex0, ey0, ex1, ey1);
    }

    /** Whether a kept layer is being walked here: glass inside draws flat. */
    boolean insideKept() {
        for (int i = 0; i < count; i++) if (stack[i].kept != null) return true;
        return false;
    }

    /** Forgets open layers: a canvas begun again or abandoned with them open. */
    void drop() {
        for (int i = 0; i < count; i++) {
            stack[i].live = false;
            if (stack[i].kept != null) stack[i].kept.layer().invalidate();
        }
        count = 0;
        canvas.keptLayers.dropWalks();
    }
}
