package com.crystalgraphics.api.canvas;

import javax.annotation.Nullable;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import com.crystalgraphics.api.material.CgMaterialInterface;
import com.crystalgraphics.api.state.CgRenderState;
import com.crystalgraphics.api.text.CgText;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.ui.element.CgQuadRecord;
import com.crystalgraphics.text.render.CgTextBatch;
import com.crystalgraphics.text.render.CgTextDraw;
import com.crystalgraphics.text.render.CgTextGamma;

/**
 * A canvas's text: its text batch, made at the first text drawn, whose glyph quads land in the canvas's pass as its own
 * records do, under the transform (rotation included), clip and effect node now. Keeps the gamma later text is drawn
 * with and the count of text items a paint chunk reads.
 */
final class CgCanvasText {

    private final CgCanvas canvas;
    /** The transform as text's pose: its glyph quads bake it, rotation included. */
    private final Matrix4f pose = new Matrix4f();
    private final Target target = new Target();
    @Nullable private CgTextBatch batch;
    CgTextGamma gamma = CgTextGamma.initial();
    /** Text items drawn so far: a chunk holding one keeps the glyph epoch it was taken in. */
    long draws;

    CgCanvasText(CgCanvas canvas) {
        this.canvas = canvas;
    }

    void draw(CgText text, float x, float y, int argb) {
        CgCanvas cv = canvas;
        cv.pass();
        int tint = cv.opacity >= 1f ? argb : (argb & 0x00FFFFFF) | Math.round((argb >>> 24) * cv.opacity) << 24;
        batch().draw(text, x, y, tint, pose(), cv.clip, cv.base, cv.effect, gamma);
        draws++;
    }

    CgTextDraw draw() {
        CgCanvas cv = canvas;
        cv.pass();
        Matrix4f at = pose();
        draws++;
        return batch().draw(at, cv.clip, cv.base, cv.effect).gamma(gamma);
    }

    /** Text items that drew below the tier they asked for, still generating. */
    long degraded() {
        return batch == null ? 0 : batch.degradedDraws();
    }

    private Matrix4f pose() {
        CgCanvas cv = canvas;
        return pose.set(cv.a, cv.b, 0f, 0f, cv.c, cv.d, 0f, 0f, 0f, 0f, 1f, 0f, cv.tx, cv.ty, 0f, 1f);
    }

    private CgTextBatch batch() {
        CgTextBatch t = batch;
        if (t == null) batch = t = new CgTextBatch(target);
        return t;
    }

    /** The canvas as its text batch sees it; glyph quads land as the canvas's own records do. */
    private final class Target implements CgTextBatch.Target {

        @Override
        public CgCamera camera() {
            return canvas.camera;
        }

        @Override
        public boolean visible(@Nullable Matrix4fc at, float x0, float y0, float x1, float y1) {
            CgCanvas cv = canvas;
            // Asked at the base node alone: the rect's device bounds against the clip and the target
            if (at == null || cv.base != 0) return true;
            float ax = at.m00(), bx = at.m01(), cx = at.m10(), dx = at.m11(), ox = at.m30(), oy = at.m31();
            float minX = Math.min(Math.min(ax * x0 + cx * y0, ax * x1 + cx * y0), Math.min(ax * x0 + cx * y1, ax * x1 + cx * y1)) + ox;
            float maxX = Math.max(Math.max(ax * x0 + cx * y0, ax * x1 + cx * y0), Math.max(ax * x0 + cx * y1, ax * x1 + cx * y1)) + ox;
            float minY = Math.min(Math.min(bx * x0 + dx * y0, bx * x1 + dx * y0), Math.min(bx * x0 + dx * y1, bx * x1 + dx * y1)) + oy;
            float maxY = Math.max(Math.max(bx * x0 + dx * y0, bx * x1 + dx * y0), Math.max(bx * x0 + dx * y1, bx * x1 + dx * y1)) + oy;
            boolean clipped = cv.clipNode == cv.base;
            float left = clipped ? Math.max(0f, cv.clipX0) : 0f, top = clipped ? Math.max(0f, cv.clipY0) : 0f;
            float right = Math.min(cv.camera.width(), clipped ? cv.clipX1 : Float.MAX_VALUE);
            float bottom = Math.min(cv.camera.height(), clipped ? cv.clipY1 : Float.MAX_VALUE);
            return maxX > left && maxY > top && minX < right && minY < bottom;
        }

        @Nullable
        @Override
        public CgGraphTexture target() {
            return canvas.target;
        }

        @Override
        public void textQuads(CgMaterialInterface material, @Nullable CgRenderState state, @Nullable CgTexture texture,
                              float[] records, int from, int count, int spatial) {
            if (count <= 0) return;
            int stride = CgQuadRecord.FLOATS;
            float x0 = Float.POSITIVE_INFINITY, y0 = Float.POSITIVE_INFINITY;
            float x1 = Float.NEGATIVE_INFINITY, y1 = Float.NEGATIVE_INFINITY;
            for (int o = from * stride, end = (from + count) * stride; o < end; o += stride) {
                float ox = records[o + CgQuadRecord.ORIGIN], oy = records[o + CgQuadRecord.ORIGIN + 1];
                float rx = records[o + CgQuadRecord.RIGHT], ry = records[o + CgQuadRecord.RIGHT + 1];
                float ux = records[o + CgQuadRecord.UP], uy = records[o + CgQuadRecord.UP + 1];
                // A pixel past the corners, as the quad primitive's coverage reaches
                x0 = Math.min(x0, ox + Math.min(0f, rx) + Math.min(0f, ux) - 1f);
                y0 = Math.min(y0, oy + Math.min(0f, ry) + Math.min(0f, uy) - 1f);
                x1 = Math.max(x1, ox + Math.max(0f, rx) + Math.max(0f, ux) + 1f);
                y1 = Math.max(y1, oy + Math.max(0f, ry) + Math.max(0f, uy) + 1f);
            }
            canvas.recorder.draw(CgInstanceKind.QUAD, material, state, texture, null, spatial, records, from * stride,
                    count, x0, y0, x1, y1);
            canvas.grow(spatial, x0, y0, x1, y1);
        }
    }
}
