package com.crystalgraphics.api.canvas;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.crystalgraphics.api.paint.effect.CgBackdropFilter;
import com.crystalgraphics.api.vector.CgCornerRadii;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.ui.CgCanvasTables;
import com.crystalgraphics.render.ui.backdrop.CgBackdrop;
import com.crystalgraphics.render.ui.element.CgUiElements;
import com.crystalgraphics.render.ui.property.CgSpatialTree;

/**
 * A canvas's glass panes: what is drawn under a pane captured (the target, then each open layer over it), blurred,
 * and drawn through the glass material as one element, its corners the box's. One capture serves every pane after it
 * while nothing is painted over what a pane samples and no layer opened, so a settled UI captures once a turn.
 */
final class CgCanvasBackdrop {

    private final CgCanvas canvas;
    /** The canvas's panes this turn, reused turn to turn: one each, since each holds its backdrop's values. */
    private final List<CgCanvasGlass> panes = new ArrayList<>();
    private int panesUsed;
    @Nullable private CgBackdrop backdrop;
    /**
     * What the target is composited over when it is shown, for glass to capture under it: the host's frame under a
     * recorder's picture. Null where the target is what is behind, as a stage's own target is.
     */
    @Nullable private CgGraphTexture behind;
    /**
     * The retake rule: whether a capture serves; what was painted since it, in the root's device pixels, top-down; the
     * layers opened when it was taken; the panes' union this turn and last, which seeds the next capture.
     */
    boolean captured;
    private float paintedX0, paintedY0, paintedX1, paintedY1;
    private int capturedOpens;
    private int wantX0, wantY0, wantX1, wantY1, lastX0, lastY0, lastX1, lastY1;

    CgCanvasBackdrop(CgCanvas canvas) {
        this.canvas = canvas;
    }

    /** A new turn: nothing captured, nothing behind; last turn's panes seed the first capture. */
    void beginTurn() {
        behind = null;
        captured = false;
        lastX0 = wantX0;
        lastY0 = wantY0;
        lastX1 = wantX1;
        lastY1 = wantY1;
        wantX0 = wantY0 = Integer.MAX_VALUE;
        wantX1 = wantY1 = Integer.MIN_VALUE;
        panesUsed = 0;
    }

    /** Sets what is behind the target for this turn: a recorder's picture over the host's frame. */
    void behind(@Nullable CgGraphTexture host) {
        behind = host;
    }

    /** {@code box}'s pane, its look the box's backdrop filter. */
    void pane(CgCanvasBox box) {
        CgCanvas cv = canvas;
        CgBackdropFilter f = box.backdrop;
        CgCanvasGlass glass = glass();
        CgCornerRadii fit = box.radii == null ? null : box.radii.fitted(box.w, box.h);
        glass.look(f).size(box.w, box.h);
        if (fit == null) {
            glass.radii(0f);
        } else {
            glass.radii(fit.topLeftX(), fit.topLeftY(), fit.topRightX(), fit.topRightY(), fit.bottomRightX(),
                    fit.bottomRightY(), fit.bottomLeftX(), fit.bottomLeftY());
        }
        // The quad in the root's device pixels, bottom-left as the capture counts them
        float a = cv.a, b = cv.b, c = cv.c, d = cv.d;
        float ox = a * box.x + c * box.y + cv.tx, oy = b * box.x + d * box.y + cv.ty;
        float rx = a * box.w, ry = b * box.w, ux = c * box.h, uy = d * box.h;
        // A layer's node is its outer base's, untransformed: the base's recorded place is the root's offset either way
        float shiftX = 0f, shiftY = 0f;
        if (cv.base != 0) {
            float[] o = cv.recordedOrigin(cv.base);
            shiftX = o[0];
            shiftY = o[1];
        }
        float px0 = ox + Math.min(0f, rx) + Math.min(0f, ux) + shiftX, px1 = ox + Math.max(0f, rx) + Math.max(0f, ux) + shiftX;
        float py0 = oy + Math.min(0f, ry) + Math.min(0f, uy) + shiftY, py1 = oy + Math.max(0f, ry) + Math.max(0f, uy) + shiftY;
        int w = Math.max(1, Math.round(cv.camera.width())), h = Math.max(1, Math.round(cv.camera.height()));
        float local = Math.max(1e-6f, Math.max(box.w, box.h));
        float pixels = Math.max(px1 - px0, py1 - py0);
        // Three sigma of blur reach in, and the lens's reach out
        float pad = 3f * f.blur() + glass.reach() * pixels / local + 4f;
        int cx0 = Math.max(0, (int) Math.floor(px0 - pad)), cx1 = Math.min(w, (int) Math.ceil(px1 + pad));
        int cy0 = Math.max(0, (int) Math.floor(h - py1 - pad)), cy1 = Math.min(h, (int) Math.ceil(h - py0 + pad));
        if (cx1 <= cx0 || cy1 <= cy0) return;
        cv.recorder.end();
        if (backdrop == null) backdrop = new CgBackdrop("canvas.backdrop");
        // Top-down, as what was painted is counted
        int ty0 = h - cy1, ty1 = h - cy0;
        CgCanvasLayers layers = cv.layers;
        boolean holds = captured && capturedOpens == layers.opens && cx0 >= backdrop.x0() && cy0 >= backdrop.y0()
                && cx1 <= backdrop.x1() && cy1 <= backdrop.y1()
                && !(paintedX1 > cx0 && paintedX0 < cx1 && paintedY1 > ty0 && paintedY0 < ty1);
        if (!holds) {
            // Taken over last turn's panes as well, so the panes after this one find it whole
            int kx0 = cx0, ky0 = ty0, kx1 = cx1, ky1 = ty1;
            if (!captured && lastX1 > lastX0) {
                kx0 = Math.max(0, Math.min(kx0, lastX0));
                ky0 = Math.max(0, Math.min(ky0, lastY0));
                kx1 = Math.min(w, Math.max(kx1, lastX1));
                ky1 = Math.min(h, Math.max(ky1, lastY1));
            }
            CgGraphTexture capture = backdrop.capture(cv.recording, behind != null ? behind : cv.target, w, h,
                    CgBackdrop.formatOf(cv.target), kx0, h - ky1, kx1, h - ky0);
            if (behind != null || layers.count > 0) layers.drawOver(capture, behind != null, w, h);
            captured = true;
            capturedOpens = layers.opens;
            paintedX0 = paintedY0 = Float.MAX_VALUE;
            paintedX1 = paintedY1 = -Float.MAX_VALUE;
        }
        wantX0 = Math.min(wantX0, cx0);
        wantY0 = Math.min(wantY0, ty0);
        wantX1 = Math.max(wantX1, cx1);
        wantY1 = Math.max(wantY1, ty1);
        backdrop.blur(cv.recording, f.blur());
        // Into the innermost layer: where its picture's (0, 0) is in the capture's bottom-left texels
        float offsetX = 0f, offsetY = 0f;
        if (layers.count > 0) {
            CgCanvasLayer top = layers.top();
            if (!top.opened) layers.place(top);
            offsetX = top.ownerX + top.x0;
            offsetY = h - (top.ownerY + top.y0 + top.h);
        }
        glass.backdrop(backdrop, offsetX, offsetY, backdrop.x0(), backdrop.y0(), backdrop.x1(), backdrop.y1()).apply();
        cv.pass();
        float[] record = cv.clear();
        CgUiElements.fill(record, 0, CgUiElements.FILL_COLOR, 0xFFFFFFFF, 0f);
        int at = cv.spatial();
        float[] r = cv.rect(at, box.x, box.y, box.w, box.h, false, 0f);
        if (r == null) return;
        cv.finish(at, 1f);
        cv.element(glass.instance(), null, null, at, r[0], r[1], r[2], r[3]);
    }

    /** A pane's glass for this turn. */
    private CgCanvasGlass glass() {
        if (panesUsed == panes.size()) panes.add(new CgCanvasGlass());
        return panes.get(panesUsed++);
    }

    /** Counts a draw in node {@code at}'s space as painted over the capture: carried to the root, or everything. */
    void painted(int at, float x0, float y0, float x1, float y1) {
        CgSpatialTree tree = CgCanvasTables.of(canvas.recording).spatial();
        float ox = 0f, oy = 0f;
        for (int n = at; n != 0; n = tree.parent(n)) {
            if (tree.local(n, 0) != 1f || tree.local(n, 1) != 0f || tree.local(n, 2) != 0f || tree.local(n, 3) != 1f) {
                paintedX0 = paintedY0 = -Float.MAX_VALUE;
                paintedX1 = paintedY1 = Float.MAX_VALUE;
                return;
            }
            ox += tree.local(n, 4);
            oy += tree.local(n, 5);
        }
        paintedX0 = Math.min(paintedX0, x0 + ox);
        paintedY0 = Math.min(paintedY0, y0 + oy);
        paintedX1 = Math.max(paintedX1, x1 + ox);
        paintedY1 = Math.max(paintedY1, y1 + oy);
    }
}
