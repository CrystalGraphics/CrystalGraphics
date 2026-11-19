package com.crystalgraphics.render.ui.backdrop;

import com.crystalgraphics.compute.ops.CgGpuOps;
import com.crystalgraphics.render.graph.CgComputePass;
import com.crystalgraphics.render.graph.CgExecutor;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.gpu.resource.CgTextureDesc;
import com.crystalgraphics.trace.CgGpuTrace;

import javax.annotation.Nullable;
import java.util.Arrays;
import com.crystalgraphics.platform.rhi.format.CgFormat;

/**
 * What is behind a pane of glass: a copy of the target over the pixels the pane covers, and a blur of it on the 1/2/4
 * ladder. The canvas's {@code backdropFilter} and the world's glass draw over it; a caller composing its own backdrop
 * (CrystalGUI lays its layers over the scene) takes the parts. It owns its textures, so keep one per drawer.
 *
 * <pre>{@code
 * CgBackdrop backdrop = new CgBackdrop("mymod.glass");                                 // once, kept
 * CgGraphTexture capture = backdrop.capture(rec, target, w, h, format, x0, y0, x1, y1);  // bottom-left pixels
 * // ... draw anything more into capture: the layers above the target
 * CgGraphTexture blurred = backdrop.blur(rec, 24f);                                    // three sigma, in pixels
 * }</pre>
 *
 * <ul>
 *   <li>Capture after what should show through is drawn: a capture is a copy recorded in turn, so a second pane sees
 *       the first.</li>
 *   <li>The blur is in the target's pixels.</li>
 *   <li>{@link #release} when done with it, or {@link #delete} at a context's teardown.</li>
 * </ul>
 */
public class CgBackdrop {

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

    /** @param name what its textures are called in a trace */
    public CgBackdrop(String name) {
        this.name = name;
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

    /** A target's colour format, what {@link #capture} takes: the current target's is RGBA8. */
    public static CgFormat formatOf(CgGraphTexture texture) {
        CgFormat format = texture.format();
        return format == null ? CgFormat.RGBA8_UNORM : format;
    }
}
