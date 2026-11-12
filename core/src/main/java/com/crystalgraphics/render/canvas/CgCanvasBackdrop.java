package com.crystalgraphics.render.canvas;

import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.compute.ops.CgGpuOps;
import com.crystalgraphics.render.graph.CgComputePass;
import com.crystalgraphics.render.graph.CgExecutor;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.gpu.resource.CgTextureDesc;
import com.crystalgraphics.trace.CgGpuTrace;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import javax.annotation.Nullable;
import java.util.Arrays;
import com.crystalgraphics.platform.rhi.format.CgFormat;

/**
 * What is behind a pane of glass: a copy of the target over the pixels the pane covers, and a blur of it. A
 * {@link CgCanvasGlass} draws over it, on the screen or in the world. It owns its two textures, so keep one per drawer.
 *
 * <pre>{@code
 * CgCanvasBackdrop backdrop = new CgCanvasBackdrop("mymod.glass");                    // once, kept
 * CgCanvasGlass pane = new CgCanvasGlass(CgCanvasMaterials.WORLD);                    // once, kept
 * CgCanvasGlass.Look look = CgCanvasGlass.LIQUID.scaled(1 / 32f);                     // a block is 32 of its pixels
 *
 * // At a stage, after what the pane covers is drawn:
 * CgCanvas canvas = frame.canvas();
 * CgQuadBatch.Quad quad = canvas.quad(pane.material()).world(x, y, z).pose(facing).at(-1f, -0.5f).size(2f, 1f);
 * pane.look(look).size(2f, 1f).radii(0.25f).backdrop(backdrop.capture(canvas, quad, look.blur(), pane.reach()))
 *     .apply();
 * quad.submit();
 * }</pre>
 *
 * <p>On the screen it is the same with {@link CgCanvasMaterials#SCREEN} and a look in pixels.</p>
 *
 * <ul>
 *   <li>Capture after what should show through is drawn, and submit the pane straight after: each capture ends the
 *       canvas's pass and records a copy and a blur.</li>
 *   <li>Panes take turns: each captures again, so the second sees the first through it.</li>
 *   <li>The blur is in the target's pixels, whatever the look's other lengths are in.</li>
 *   <li>{@link #release} when done with it, or {@link #delete} at a context's teardown.</li>
 * </ul>
 *
 * <p>A caller that composes its own backdrop (CrystalGUI lays its layers over the scene) takes the parts:
 * {@link #capture(CgRecording, CgGraphTexture, int, int, CgFormat, int, int, int, int)}, its drawing into
 * {@link #sharp()}, then {@link #blur}.</p>
 */
public final class CgCanvasBackdrop {

    /** The largest working scale of the blur, which the grid is a multiple of. */
    private static final int MAX_SCALE = 4;
    /** A blur's reach is three sigma. */
    private static final float REACH_PER_SIGMA = 3f;
    private static final int GPU_BLUR = CgGpuTrace.name("canvas.backdrop.blur");

    private final String name;
    @Nullable
    private CgGraphTexture capture;
    /** The blur's target per working scale (1, 2 and 4), each the grid over its scale. */
    private final CgGraphTexture[] blurTargets = new CgGraphTexture[3];
    @Nullable
    private CgGraphTexture blurred;
    @Nullable
    private CgFormat type;
    private int gridWidth, gridHeight;
    /** What the last capture copied, in the capture's bottom-left texels. */
    private int x0, y0, x1, y1;

    private final float[] placed = new float[9];
    private final Matrix4f clip = new Matrix4f();
    private final Vector4f corner = new Vector4f();

    /** @param name what its textures are called in a trace */
    public CgCanvasBackdrop(String name) {
        this.name = name;
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

    /**
     * Copies {@code (x0, y0)-(x1, y1)} of {@code source}, a {@code width x height} target, in its bottom-left pixels,
     * into the capture at the same place, and answers the capture: what a caller composing its backdrop draws into
     * before {@link #blur}. RGBA16F keeps a float target's range; anything else is RGBA8.
     */
    public CgGraphTexture capture(CgRecording rec, CgGraphTexture source, int width, int height, CgFormat type,
                                  int x0, int y0, int x1, int y1) {
        size(rec, width, height, type == CgFormat.RGBA16_FLOAT ? CgFormat.RGBA16_FLOAT : CgFormat.RGBA8_UNORM);
        this.x0 = Math.max(0, x0);
        this.y0 = Math.max(0, y0);
        this.x1 = Math.min(width, Math.max(this.x0 + 1, x1));
        this.y1 = Math.min(height, Math.max(this.y0 + 1, y1));
        int w = this.x1 - this.x0, h = this.y1 - this.y0;
        rec.copy(source, this.x0, this.y0, w, h, capture, this.x0, this.y0, w, h, false);
        blurred = capture;
        return capture;
    }

    /**
     * Blurs what the last capture covers by {@code blurPx}, three sigma, in place on a texture of its own: Skia's
     * scale-then-blur ({@link CgGpuOps#blurScaled}), edges mirrored as Chromium extends a backdrop. Under half a
     * pixel, the capture is the blur.
     */
    public CgGraphTexture blur(CgRecording rec, float blurPx) {
        if (capture == null) throw new IllegalStateException("blur before a capture");
        if (blurPx < 0.5f) return blurred = capture;
        float sigma = blurPx / REACH_PER_SIGMA;
        int scale = CgGpuOps.blurScale(sigma);
        int at = Integer.numberOfTrailingZeros(scale);
        CgGraphTexture target = blurTargets[at];
        if (target == null) {
            target = CgGraphTexture.requested(name + ".blur" + scale,
                    new CgTextureDesc(gridWidth / scale, gridHeight / scale, type));
            blurTargets[at] = target;
        }
        CgComputePass pass = rec.compute(name + ".blur").timed(GPU_BLUR);
        CgGpuOps.blurScaled(pass, capture, x0, y0, x1 - x0, y1 - y0, target, sigma, CgGpuOps.Edge.MIRROR);
        pass.end();
        return blurred = target;
    }

    /** The capture: what the glass bends at its bezel. */
    public CgGraphTexture sharp() {
        if (capture == null) throw new IllegalStateException("no capture yet");
        return capture;
    }

    /** The last blur, or the capture when it blurred nothing: what the glass shows in its middle. */
    public CgGraphTexture blurred() {
        if (blurred == null) throw new IllegalStateException("no capture yet");
        return blurred;
    }

    /** Both textures' size in texels, a pixel of the target each: the target's, rounded up to a multiple of 4. */
    public int gridWidth() {
        return gridWidth;
    }

    public int gridHeight() {
        return gridHeight;
    }

    /** What the last capture covers, in its bottom-left texels. */
    public int x0() {
        return x0;
    }

    public int y0() {
        return y0;
    }

    public int x1() {
        return x1;
    }

    public int y1() {
        return y1;
    }

    /** Releases its textures into {@code rec}: they go once the frame executes. */
    public void release(CgRecording rec) {
        if (capture != null) rec.release(capture);
        for (CgGraphTexture t : blurTargets) if (t != null) rec.release(t);
        forget();
    }

    /** Frees its textures' storage now: a context's teardown, with no frame to release them in. */
    public void delete() {
        if (capture != null) CgExecutor.freeStorage(capture);
        for (CgGraphTexture t : blurTargets) if (t != null) CgExecutor.freeStorage(t);
        forget();
    }

    private void forget() {
        capture = null;
        blurred = null;
        Arrays.fill(blurTargets, null);
        type = null;
        gridWidth = gridHeight = 0;
    }

    /** Remakes the textures when the target's size or format changed. */
    private void size(CgRecording rec, int width, int height, CgFormat type) {
        int gw = (Math.max(1, width) + MAX_SCALE - 1) / MAX_SCALE * MAX_SCALE;
        int gh = (Math.max(1, height) + MAX_SCALE - 1) / MAX_SCALE * MAX_SCALE;
        if (capture != null && gw == gridWidth && gh == gridHeight && type == this.type) return;
        if (capture != null) release(rec);
        this.type = type;
        gridWidth = gw;
        gridHeight = gh;
        capture = CgGraphTexture.requested(name + ".capture", new CgTextureDesc(gw, gh, type));
    }

    /** A target's colour format: the current target's is RGBA8. */
    private static CgFormat formatOf(CgGraphTexture texture) {
        CgFormat format = texture.format();
        return format == null ? CgFormat.RGBA8_UNORM : format;
    }
}
