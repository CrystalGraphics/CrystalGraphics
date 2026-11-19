package com.crystalgraphics.api.canvas;

import com.crystalgraphics.api.paint.brush.CgBrush;
import com.crystalgraphics.api.paint.brush.CgNineSlice;
import com.crystalgraphics.api.paint.brush.CgTiling;
import com.crystalgraphics.api.texture.CgTexture;

/**
 * A brush laid over a rect as pieces: its nine slices (corners at their size, edges and centre by the brush's tiling)
 * or its tiles. CSS's {@code border-image} and {@code background-repeat}: stretch, repeat, round and space.
 */
final class CgCanvasTiles {

    /** Takes each piece: where it lands, and the texture region it shows. */
    interface Sink {
        void piece(float x, float y, float w, float h, float u0, float v0, float u1, float v1);
    }

    private CgCanvasTiles() {
    }

    /** Lays {@code brush} over the rect into {@code sink}, at most {@code most} pieces. */
    static void of(CgBrush brush, float x, float y, float w, float h, int most, Sink sink) {
        CgTexture t = brush.texture();
        float tw = Math.max(1f, t.getWidth()), th = Math.max(1f, t.getHeight());
        float rx = brush.hasRegion() ? brush.regionX() : 0f, ry = brush.hasRegion() ? brush.regionY() : 0f;
        float rw = brush.hasRegion() ? brush.regionWidth() : tw, rh = brush.hasRegion() ? brush.regionHeight() : th;
        CgNineSlice nine = brush.nineSlice();
        if (nine == null) {
            area(sink, brush.tiling(), x, y, w, h, rx, ry, rw, rh, rw, rh, tw, th, most);
            return;
        }
        float k = nine.scale();
        // Corners at their size, shrunk together where the rect is smaller than two of them (CSS's border-image scale)
        float l = nine.left() * k, r = nine.right() * k, tp = nine.top() * k, b = nine.bottom() * k;
        float fx = l + r > w ? w / (l + r) : 1f, fy = tp + b > h ? h / (tp + b) : 1f;
        float f = Math.min(fx, fy);
        l *= f;
        r *= f;
        tp *= f;
        b *= f;
        float sl = nine.left(), sr = nine.right(), st = nine.top(), sb = nine.bottom();
        float mw = w - l - r, mh = h - tp - b, smw = rw - sl - sr, smh = rh - st - sb;
        CgTiling edges = brush.edges();
        // Corners
        piece(sink, x, y, l, tp, rx, ry, sl, st, tw, th);
        piece(sink, x + w - r, y, r, tp, rx + rw - sr, ry, sr, st, tw, th);
        piece(sink, x + w - r, y + h - b, r, b, rx + rw - sr, ry + rh - sb, sr, sb, tw, th);
        piece(sink, x, y + h - b, l, b, rx, ry + rh - sb, sl, sb, tw, th);
        // Edges: tiled along their length at the corners' scale
        area(sink, along(edges, true), x + l, y, mw, tp, rx + sl, ry, smw, st, smw * k * f, tp, tw, th, most);
        area(sink, along(edges, true), x + l, y + h - b, mw, b, rx + sl, ry + rh - sb, smw, sb, smw * k * f, b, tw, th, most);
        area(sink, along(edges, false), x, y + tp, l, mh, rx, ry + st, sl, smh, l, smh * k * f, tw, th, most);
        area(sink, along(edges, false), x + w - r, y + tp, r, mh, rx + rw - sr, ry + st, sr, smh, r, smh * k * f, tw, th, most);
        if (nine.fill()) {
            area(sink, brush.tiling(), x + l, y + tp, mw, mh, rx + sl, ry + st, smw, smh, smw * k * f, smh * k * f, tw, th,
                    most);
        }
    }

    /** An edge tiles along its length only. */
    private static CgTiling along(CgTiling t, boolean horizontal) {
        if (t == CgTiling.REPEAT || t == CgTiling.REPEAT_X || t == CgTiling.REPEAT_Y) {
            return horizontal ? CgTiling.REPEAT_X : CgTiling.REPEAT_Y;
        }
        return t;
    }

    /**
     * A rect filled by one region of the texture: stretched, or tiled at {@code tileW x tileH} from its top-left
     * (repeat), scaled to a whole count (round) or a whole count spread with gaps (space).
     */
    private static void area(Sink sink, CgTiling tiling, float x, float y, float w, float h, float rx, float ry, float rw,
                             float rh, float tileW, float tileH, float tw, float th, int most) {
        if (!(w > 0f && h > 0f && rw > 0f && rh > 0f)) return;
        boolean tx = tiling == CgTiling.REPEAT || tiling == CgTiling.REPEAT_X || tiling == CgTiling.ROUND
                || tiling == CgTiling.SPACE;
        boolean ty = tiling == CgTiling.REPEAT || tiling == CgTiling.REPEAT_Y || tiling == CgTiling.ROUND
                || tiling == CgTiling.SPACE;
        float cw = tx ? tileW : w, ch = ty ? tileH : h;
        float gapX = 0f, gapY = 0f;
        if (tiling == CgTiling.ROUND) {
            cw = w / Math.max(1, Math.round(w / tileW));
            ch = h / Math.max(1, Math.round(h / tileH));
        } else if (tiling == CgTiling.SPACE) {
            int nx = (int) (w / tileW), ny = (int) (h / tileH);
            if (nx < 1 || ny < 1) return;
            gapX = nx > 1 ? (w - nx * tileW) / (nx - 1) : 0f;
            gapY = ny > 1 ? (h - ny * tileH) / (ny - 1) : 0f;
        }
        if (cw <= 0f || ch <= 0f) return;
        int count = 0;
        for (float py = y; py < y + h - 1e-3f; py += ch + gapY) {
            float ph = Math.min(ch, y + h - py);
            for (float px = x; px < x + w - 1e-3f; px += cw + gapX) {
                if (++count > most) return;
                float pw = Math.min(cw, x + w - px);
                // A cut tile shows the matching part of its region
                piece(sink, px, py, pw, ph, rx, ry, rw * pw / cw, rh * ph / ch, tw, th);
            }
        }
    }

    private static void piece(Sink sink, float x, float y, float w, float h, float rx, float ry, float rw, float rh,
                              float tw, float th) {
        if (w <= 0f || h <= 0f) return;
        sink.piece(x, y, w, h, rx / tw, ry / th, (rx + rw) / tw, (ry + rh) / th);
    }
}
