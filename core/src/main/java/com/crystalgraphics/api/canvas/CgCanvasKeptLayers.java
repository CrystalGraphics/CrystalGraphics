package com.crystalgraphics.api.canvas;

import java.util.ArrayList;
import java.util.List;

import com.crystalgraphics.compute.ops.CgGpuOps;
import com.crystalgraphics.gpu.resource.CgFrameRing;
import com.crystalgraphics.gpu.resource.CgTextureDesc;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.render.graph.CgComputePass;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgLoad;
import com.crystalgraphics.render.graph.CgRasterPass;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.ui.element.CgUiElements;

/**
 * A canvas's kept layers: each walk drawn into the layer's held picture when its revision or size changed, else
 * nothing recorded for it; the picture composited where the walk is, its damage cut into the passes that drew it.
 * Also a picture's previews ({@code picture}, {@code thumbnail}) and the sweep that frees the idle ones.
 */
final class CgCanvasKeptLayers {

    /** Past this many device pixels a side a kept layer is refused. */
    private static final int MAX_SIDE = 4096;
    /** Frames a kept layer may go unasked for before its canvas frees it. */
    private static final long IDLE_FRAMES = 300L;
    /** How often a thumbnail is filtered again while its picture changes: a preview needs no more. */
    private static final long THUMBNAIL_REFRESH_NANOS = 100L * 1_000_000L;

    private final CgCanvas canvas;
    /** The kept layers this canvas walked, which it frees when idle. */
    private final List<CgKeptLayer> walked = new ArrayList<>();
    private final float[] scratch = new float[4];

    CgCanvasKeptLayers(CgCanvas canvas) {
        this.canvas = canvas;
    }

    CgKeptLayer.Walk walk(CgKeptLayer kept, float x, float y, float w, float h, long revision) {
        CgCanvas cv = canvas;
        if (cv.recording == null) throw new IllegalStateException("a kept layer on a canvas that records nowhere: begin() first");
        if (!(w > 0f && h > 0f)) throw new IllegalArgumentException("a kept layer of " + w + " x " + h);
        CgKeptLayer.Walk walk = kept.walk;
        if (walk.live) throw new IllegalStateException("a kept layer walked inside its own walk");
        float[] bounds = cv.affineBounds(x, y, x + w, y + h, cv.a, cv.b, cv.c, cv.d, cv.tx, cv.ty);
        // Whole device pixels covering the rect, a hair inside it (a transform taken out and put back is not exact): a
        // move by translation composites the same picture, and a half-covered edge pixel stays in it
        int x0 = (int) Math.floor(bounds[0] + 1e-3f), y0 = (int) Math.floor(bounds[1] + 1e-3f);
        int pw = Math.max(1, (int) Math.ceil(bounds[2] - 1e-3f) - x0);
        int ph = Math.max(1, (int) Math.ceil(bounds[3] - 1e-3f) - y0);
        if (pw > MAX_SIDE || ph > MAX_SIDE) {
            throw new IllegalArgumentException("a kept layer " + pw + " x " + ph + " device pixels: at most "
                    + MAX_SIDE + " a side");
        }
        long now = CgFrameRing.frame();
        kept.lastFrame = now;
        if (kept.sweeper != cv) {
            if (kept.sweeper != null) kept.sweeper.keptLayers.walked.remove(kept);
            walked.add(kept);
            kept.sweeper = cv;
        }
        walk.canvas = cv;
        walk.live = true;
        walk.x0 = x0;
        walk.y0 = y0;
        walk.opacity = 1f;
        walk.revision = revision;
        // Held, unless a picture it drew moved since: then it walks again, damaged only where that picture lies
        boolean held = kept.holds(revision, pw, ph);
        boolean moved = held && kept.picturesMoved();
        walk.draws = !held || moved;
        if (!walk.draws) return walk;

        CgRecording recording = cv.recording;
        CgKeptLayer.Shown shown = kept.shown;
        boolean full = kept.revision == CgKeptLayer.NONE || shown == null || shown.width() != pw || shown.height() != ph;
        if (kept.texture != null) {
            // Remade when outgrown, or holding more than twice what it needs
            int tw = kept.texture.getWidth(), th = kept.texture.getHeight();
            int rw = reserved(kept.reserveWidth), rh = reserved(kept.reserveHeight);
            if (tw < pw || th < ph || tw < rw || th < rh || tw > Math.max(Math.max(64, pw * 2), rw)
                    || th > Math.max(Math.max(64, ph * 2), rh)) {
                // Released when this recording ends: nothing may composite it from here
                kept.shown = null;
                recording.release(kept.texture);
                kept.texture = null;
            }
        }
        if (kept.texture == null) {
            kept.texture = CgGraphTexture.requested("canvas_kept", CgTextureDesc.of(
                    Math.max(bucket(pw), reserved(kept.reserveWidth)), Math.max(bucket(ph), reserved(kept.reserveHeight)),
                    CgFormat.RGBA16_FLOAT));
            full = true;
        }
        kept.invalidate();
        kept.beginWalk(now, full);
        if (moved && !full) kept.damageMoved();
        CgCanvasLayer l = cv.layers.open(Float.NaN, 0f, 0f, 0f);
        l.kept = walk;
        l.x0 = x0;
        l.y0 = y0;
        l.w = pw;
        l.h = ph;
        l.texture = kept.texture;
        l.lens.screenLens(kept.texture.getWidth(), kept.texture.getHeight());
        l.load = CgLoad.CLEAR;
        l.opened = true;
        l.passMark = recording.passCount();
        cv.cullX0 = x0;
        cv.cullY0 = y0;
        cv.cullX1 = x0 + pw;
        cv.cullY1 = y0 + ph;
        // Its clear lands even when nothing is drawn: what vacated is cleared
        cv.pass();
        cv.recorder.openPass();
        return walk;
    }

    /**
     * Records {@code picture}, drawn over a rect in the units now at its version, in the innermost walk drawing on this
     * canvas, if any: that layer follows it from then on.
     */
    void pictured(CgPicture picture, float x, float y, float w, float h, int deviceWidth, int deviceHeight) {
        CgCanvasLayers layers = canvas.layers;
        for (int i = layers.count - 1; i >= 0; i--) {
            CgKeptLayer.Walk walk = layers.at(i).kept;
            if (walk == null || !walk.live) continue;
            float[] r = rect(walk, x, y, w, h);
            walk.layer().pictured(picture, picture.version(), r[0], r[1], r[2], r[3], deviceWidth, deviceHeight,
                    canvas.deviceScale());
            return;
        }
    }

    /** A rect in the units now as {@code walk}'s texels, top-down, into a scratch array. */
    float[] rect(CgKeptLayer.Walk walk, float x, float y, float w, float h) {
        CgCanvas cv = canvas;
        float[] bounds = cv.affineBounds(x, y, x + w, y + h, cv.a, cv.b, cv.c, cv.d, cv.tx, cv.ty);
        // Drawn under a node inside the walk (a scroll), that node's offset from the walk's: translations, as recorded
        float ox = 0f, oy = 0f;
        for (int i = cv.layers.count - 1; i >= 0; i--) {
            CgCanvasLayer l = cv.layers.at(i);
            if (l.kept != walk) continue;
            if (cv.base != l.node) {
                float[] o = cv.recordedOrigin(cv.base);
                ox = o[0];
                oy = o[1];
                o = cv.recordedOrigin(l.node);
                ox -= o[0];
                oy -= o[1];
            }
            break;
        }
        scratch[0] = bounds[0] + ox - walk.x0;
        scratch[1] = bounds[1] + oy - walk.y0;
        scratch[2] = bounds[2] + ox - walk.x0;
        scratch[3] = bounds[3] + oy - walk.y0;
        return scratch;
    }

    /** Ends {@code walk}: a drawn one's damage cut into its passes; then its picture composited. */
    void close(CgKeptLayer.Walk walk) {
        CgCanvasLayers layers = canvas.layers;
        if (walk.draws) {
            if (layers.count == 0 || layers.top().kept != walk) {
                throw new IllegalStateException("layers close in the reverse of their opening");
            }
            layers.close(layers.top());
        } else {
            walk.live = false;
            composite(walk);
        }
    }

    /** A drawn walk's end, its layer closed: tenants vacate, the damage is cut into its passes, the picture is held. */
    void finish(CgCanvasLayer l) {
        CgKeptLayer.Walk walk = l.kept;
        CgKeptLayer kept = walk.layer();
        walk.live = false;
        kept.endWalk();
        // Nothing damaged on a drawn walk is the whole picture: the safe answer for a caller that damages nothing
        if (!kept.full && kept.damaged()) {
            CgGraphTexture t = kept.texture;
            int th = t.getHeight();
            // A pixel either side for edge coverage, then the bottom-left rect a pass takes
            int x0 = Math.max(0, (int) Math.floor(kept.damageX0) - 1), y0 = Math.max(0, (int) Math.floor(kept.damageY0) - 1);
            int x1 = Math.min(l.w, (int) Math.ceil(kept.damageX1) + 1);
            int y1 = Math.min(l.h, (int) Math.ceil(kept.damageY1) + 1);
            int dx = 0, dy = 0, dw = 0, dh = 0;
            if (x1 > x0 && y1 > y0) {
                dx = x0;
                dy = th - y1;
                dw = x1 - x0;
                dh = y1 - y0;
            }
            CgRecording recording = canvas.recording;
            for (int i = l.passMark, n = recording.passCount(); i < n; i++) {
                if (recording.pass(i) instanceof CgRasterPass raster && raster.target() == t) raster.damage(dx, dy, dw, dh);
            }
        }
        kept.revision = walk.revision;
        CgKeptLayer.Shown shown = kept.shown;
        if (shown == null || shown.texture() != kept.texture || shown.width() != l.w || shown.height() != l.h) {
            kept.shown = CgKeptLayer.Shown.of(kept.texture, l.w, l.h);
        }
        kept.drawCount++;
        composite(walk);
    }

    /** {@code walk}'s picture as one element at its place, under the clip and opacity outside it. */
    private void composite(CgKeptLayer.Walk walk) {
        CgCanvas cv = canvas;
        CgKeptLayer kept = walk.layer();
        CgKeptLayer.Shown shown = kept.shown;
        if (shown == null) return;
        int x0 = walk.x0, y0 = walk.y0, w = shown.width(), h = shown.height();
        // Composited into a kept layer being walked: it damages where it lands there, and where it was
        for (int i = cv.layers.count - 1; i >= 0; i--) {
            CgCanvasLayer outer = cv.layers.at(i);
            if (outer.kept == null) continue;
            kept.placement.place(outer.kept.layer(), x0 - outer.x0, y0 - outer.y0, x0 - outer.x0 + w, y0 - outer.y0 + h,
                    outer.kept.layer().walkFrame);
            break;
        }
        if (walk.opacity <= 0f || cv.opacity <= 0f || cv.clippedOut(x0, y0, x0 + w, y0 + h)) return;
        float tw = shown.texture().getWidth(), th = shown.texture().getHeight();
        cv.pass();
        float[] record = cv.clear();
        CgUiElements.fill(record, 0, CgUiElements.FILL_TEXTURE, (Math.round(255f * walk.opacity) << 24) | 0x00FFFFFF, 0f);
        CgUiElements.flag(record, 0, CgUiElements.FLAG_PREMULTIPLIED, true);
        // A render target's row 0 is its bottom; the picture is the texture's top-left
        CgUiElements.uv(record, 0, 0f, 1f, w / tw, 1f - h / th);
        CgUiElements.rect(record, 0, x0, y0, x0 + w, y0 + h);
        cv.finish(cv.base, 1f);
        cv.element(CgUiMaterials.element(), shown.texture(), null, cv.base, x0, y0, x0 + w, y0 + h);
    }

    /** Part of {@code kept}'s picture over a rect in the units now, as last drawn; nothing when it holds none. */
    void picture(CgKeptLayer kept, float sx, float sy, float sw, float sh, float x, float y, float w, float h) {
        CgKeptLayer.Shown shown = kept.shown;
        if (shown == null || !(sw > 0f && sh > 0f)) return;
        float tw = shown.texture().getWidth(), th = shown.texture().getHeight();
        canvas.box(x, y, w, h).fill(shown.texture()).premultiplied()
                .region(sx / tw, 1f - sy / th, (sx + sw) / tw, 1f - (sy + sh) / th).draw();
    }

    /** As {@link #picture}, filtered down to the rect's device size first and refreshed at most every 100 ms. */
    void thumbnail(CgKeptLayer kept, float sx, float sy, float sw, float sh, float x, float y, float w, float h) {
        CgKeptLayer.Shown shown = kept.shown;
        if (shown == null || !(sw > 0f && sh > 0f)) return;
        CgCanvas cv = canvas;
        int dw = Math.round(w * (float) Math.hypot(cv.a, cv.b)), dh = Math.round(h * (float) Math.hypot(cv.c, cv.d));
        if (dw < 1 || dh < 1 || sw < dw || sh < dh || sw < dw * 1.5f && sh < dh * 1.5f) {
            picture(kept, sx, sy, sw, sh, x, y, w, h);
            return;
        }
        CgRecording recording = cv.recording;
        if (kept.thumbnail == null || kept.thumbnailWidth != dw || kept.thumbnailHeight != dh) {
            if (kept.thumbnail != null) recording.release(kept.thumbnail);
            kept.thumbnail = CgGraphTexture.requested("canvas_kept_thumbnail", CgTextureDesc.of(dw, dh, CgFormat.RGBA16_FLOAT));
            kept.thumbnailWidth = dw;
            kept.thumbnailHeight = dh;
            kept.thumbnailDrawn = -1L;
        }
        kept.thumbnailStale = kept.thumbnailDrawn != kept.drawCount || kept.thumbnailX != sx || kept.thumbnailY != sy
                || kept.thumbnailW != sw || kept.thumbnailH != sh;
        long now = System.nanoTime();
        if (kept.thumbnailStale && (kept.thumbnailDrawn == -1L || now - kept.thumbnailAt >= THUMBNAIL_REFRESH_NANOS)) {
            cv.recorder.end();
            CgComputePass pass = recording.compute("canvas.kept.thumbnail");
            CgGpuOps.shrink(pass, shown.texture(), sx, shown.texture().getHeight() - sy - sh, sw, sh, kept.thumbnail, 0, 0,
                    dw, dh);
            pass.end();
            kept.thumbnailDrawn = kept.drawCount;
            kept.thumbnailX = sx;
            kept.thumbnailY = sy;
            kept.thumbnailW = sw;
            kept.thumbnailH = sh;
            kept.thumbnailAt = now;
            kept.thumbnailStale = false;
        }
        cv.box(x, y, w, h).fill(kept.thumbnail).premultiplied().region(0f, 1f, 1f, 0f).draw();
    }

    /** Frees the kept layers nobody asked for in {@link #IDLE_FRAMES} frames; every 16th frame. */
    void sweep() {
        long now = CgFrameRing.frame();
        if (walked.isEmpty() || (now & 15L) != 0L) return;
        CgRecording recording = canvas.recording;
        for (int i = walked.size() - 1; i >= 0; i--) {
            CgKeptLayer kept = walked.get(i);
            if (now - kept.lastFrame < IDLE_FRAMES || kept.keep) continue;
            if (kept.texture != null) recording.release(kept.texture);
            if (kept.thumbnail != null) recording.release(kept.thumbnail);
            kept.texture = kept.thumbnail = null;
            kept.shown = null;
            kept.thumbnailDrawn = -1L;
            kept.invalidate();
            kept.sweeper = null;
            walked.remove(i);
        }
    }

    /** A held walk left open has nothing to land. */
    void dropWalks() {
        for (int i = 0; i < walked.size(); i++) walked.get(i).walk.live = false;
    }

    /** What the owner promised, rounded as a texture is; 0 for no promise. */
    private static int reserved(int size) {
        return size <= 0 ? 0 : bucket(Math.min(MAX_SIDE, size));
    }

    /** Rounded up to 64, so a picture resizing by a pixel a frame does not remake its texture every frame. */
    private static int bucket(int size) {
        return Math.max(64, (Math.max(1, size) + 63) & ~63);
    }
}
