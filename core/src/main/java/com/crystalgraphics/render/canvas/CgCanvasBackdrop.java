package com.crystalgraphics.render.canvas;

import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.ui.backdrop.CgBackdrop;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * A {@link CgBackdrop} captured over a quad of the old canvas: what is behind it in the canvas's camera, padded by the
 * blur and the lens's reach. Goes with the old canvas (UI14); the new canvas's {@code box(...).backdropFilter(f)} needs
 * none of this.
 *
 * <pre>{@code
 * CgCanvasBackdrop backdrop = new CgCanvasBackdrop("mymod.glass");                    // once, kept
 * CgCanvasGlass pane = new CgCanvasGlass(CgPlacement.WORLD);                          // once, kept
 * CgQuadBatch.Quad quad = canvas.quad(pane.material()).world(x, y, z).pose(facing).at(-1f, -0.5f).size(2f, 1f);
 * pane.look(look).size(2f, 1f).radii(0.25f).backdrop(backdrop.capture(canvas, quad, look.blur(), pane.reach()))
 *     .apply(quad);
 * quad.submit();
 * }</pre>
 */
public final class CgCanvasBackdrop extends CgBackdrop {

    private final float[] placed = new float[9];
    private final Matrix4f clip = new Matrix4f();
    private final Vector4f corner = new Vector4f();

    /** @param name what its textures are called in a trace */
    public CgCanvasBackdrop(String name) {
        super(name);
    }

    /**
     * Captures what is behind {@code quad}, built and not yet submitted, and blurs it by {@code blurPx}: the copy covers
     * the pixels the quad covers in {@code canvas}'s camera, padded by the blur and by {@code reach}, how far a lens
     * samples past the quad in its own units ({@link CgCanvasGlass#reach}).
     *
     * @throws IllegalStateException when the canvas records nowhere: a backdrop is recorded into a frame
     */
    public CgCanvasBackdrop capture(CgCanvas canvas, CgQuadBatch.Quad quad, float blurPx, float reach) {
        CgRecording rec = canvas.breakPass();
        CgGraphTexture source = canvas.target();
        if (rec == null || source == null) throw new IllegalStateException("a backdrop is captured from a recording canvas");
        CgCamera camera = canvas.camera();
        int w = Math.max(1, Math.round(camera.width())), h = Math.max(1, Math.round(camera.height()));

        quad.place(placed);
        clip.set(camera.projection()).mul(camera.view());
        float px0 = Float.MAX_VALUE, py0 = Float.MAX_VALUE, px1 = -Float.MAX_VALUE, py1 = -Float.MAX_VALUE;
        boolean behind = false;
        for (int c = 0; c < 4; c++) {
            float a = (c & 1) != 0 ? 1f : 0f, b = (c & 2) != 0 ? 1f : 0f;
            corner.set(placed[0] + a * placed[3] + b * placed[6], placed[1] + a * placed[4] + b * placed[7],
                    placed[2] + a * placed[5] + b * placed[8], 1f);
            clip.transform(corner);
            if (corner.w <= 1e-5f) {
                behind = true;
                break;
            }
            float sx = (corner.x / corner.w * 0.5f + 0.5f) * w, sy = (corner.y / corner.w * 0.5f + 0.5f) * h;
            px0 = Math.min(px0, sx); py0 = Math.min(py0, sy);
            px1 = Math.max(px1, sx); py1 = Math.max(py1, sy);
        }
        int cx0 = 0, cy0 = 0, cx1 = w, cy1 = h;
        if (!behind) {
            // A local unit's size on screen, taken from the quad's extent there: the lens's reach in pixels.
            float local = Math.max(1e-6f, Math.max(quad.width(), quad.height()));
            float pixels = Math.max(px1 - px0, py1 - py0);
            float pad = blurPx + reach * pixels / local + 4f;
            cx0 = Math.max(0, (int) Math.floor(px0 - pad));
            cy0 = Math.max(0, (int) Math.floor(py0 - pad));
            cx1 = Math.min(w, (int) Math.ceil(px1 + pad));
            cy1 = Math.min(h, (int) Math.ceil(py1 + pad));
        }
        if (cx1 <= cx0 || cy1 <= cy0) {
            cx0 = cy0 = 0;
            cx1 = cy1 = 1;
        }
        capture(rec, source, w, h, formatOf(source), cx0, cy0, cx1, cy1);
        blur(rec, blurPx);
        return this;
    }

}
