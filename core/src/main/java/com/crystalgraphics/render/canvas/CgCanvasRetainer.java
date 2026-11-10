package com.crystalgraphics.render.canvas;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.compute.ops.CgGpuOps;
import com.crystalgraphics.render.draw.CgPassConstants;
import com.crystalgraphics.render.graph.CgComputePass;
import com.crystalgraphics.render.graph.CgExecutor;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgLoad;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.graph.CgTextureDesc;
import com.crystalgraphics.trace.CgGpuTrace;

import javax.annotation.Nullable;

/**
 * Canvas items drawn into a texture of their own, then drawn as images: what fades, tints or glows as one picture.
 * Mirrors UMG's Retainer Box ({@code SRetainerWidget}): its children painted into a render target cleared transparent,
 * then that target drawn as one premultiplied box under the widget's colour and opacity.
 *
 * <p>Styled text is the reason: a {@code CgTextDraw}'s colour fades each of its style's layers on its own, so half
 * faded, an outline shows through the fill above it. Retained, the text fades as a flat image would.</p>
 *
 * <pre>{@code
 * CgCanvasRetainer retained = new CgCanvasRetainer("mymod.hud");   // once, kept
 * // Its two looks, asked for in a load task so they are built before the first frame
 * CgMaterial image = CgCanvasMaterials.SCREEN.image(CgBlendMode.PREMULTIPLIED);   // what it holds
 * CgMaterial silhouette = CgCanvasMaterials.SCREEN.mask(CgBlendMode.ADDITIVE);    // its shape, added in a colour
 *
 * // Each frame, at a stage, before anything on frame.canvas() reads it: paint, in units, at texels a unit
 * CgCanvas paint = retained.paint(frame.recording(), 400, 100, scale);
 * paint.text().style(TITLE).text("Level up!").at(10, 10).submit();
 * retained.end();
 *
 * // Then draw a region of it as many times as you like: faded as one
 * retained.quad(frame.canvas(), image, 0, 0, 400, 100).at(x, y).color(0x80FFFFFF).submit();
 * // a glow or a flash in the shape of what it holds
 * retained.quad(frame.canvas(), silhouette, 0, 0, 400, 100).at(x, y).color(0xFFFFE080).submit();
 * }</pre>
 *
 * <pre>{@code
 * // blurred, as CSS's filter: blur(): after end(), before the canvas reads it; its region padded by three sigma
 * int reduce = retained.blur(frame.recording(), 0, 0, 400, 100, 2f);
 * retained.quad(frame.canvas(), image, 0, 0, 0, 400, 100, reduce).at(x, y).submit();
 * }</pre>
 *
 * <p>Several things share one: paint each in its own region, and draw each region on its own. One pass paints them
 * all.</p>
 *
 * <ul>
 *   <li>Paint it every frame it is drawn: a texture's first draw in a frame that also samples it has come out empty on
 *       NVIDIA, and painting each frame also follows a window's resize.</li>
 *   <li>Paint before the first item of the turn on {@code frame.canvas()}: its pass is recorded first, so the graph
 *       orders the canvas's reading after it.</li>
 *   <li>{@code scale} is texels a unit: give it the largest a region is drawn at, so it is never magnified.</li>
 *   <li>What it holds is premultiplied: draw it under the {@code PREMULTIPLIED} image, never the {@code ALPHA} one,
 *       which darkens its edges. The quad's colour is straight; the material premultiplies it, as Slate premultiplies
 *       the retainer's.</li>
 *   <li>It paints through a canvas of its own, the one exception to drawing into {@code frame.canvas()}: that canvas
 *       draws into its texture alone.</li>
 *   <li>{@link #release} when done with it, or {@link #delete} at a context's teardown. It only grows.</li>
 * </ul>
 */
public final class CgCanvasRetainer {

    /** Texture sizes are multiples of this, so a slowly growing need remakes it rarely. */
    private static final int GRAIN = 64;

    private final String name;
    private final CgCanvas canvas = new CgCanvas();
    private final CgPassConstants constants = new CgPassConstants();
    @Nullable
    private CgGraphTexture texture;
    private int texelsWide, texelsHigh;
    /** Texels a unit, as last painted. */
    private float scale = 1f;
    private boolean painting;
    /** Each blur's target, by its reduction (1, 2, 4): {@link CgGpuOps#blurScaled} writes where the region lies. */
    private final CgGraphTexture[] blurred = new CgGraphTexture[3];
    private static final int GPU_BLUR = CgGpuTrace.name("canvas.retainer.blur");

    /** @param name what its texture is called in a trace */
    public CgCanvasRetainer(String name) {
        this.name = name;
    }

    /**
     * A canvas painting into it, cleared transparent: {@code width} by {@code height} units from its top-left,
     * {@code scale} texels a unit. Submit items to it, then {@link #end}.
     */
    public CgCanvas paint(CgRecording recording, float width, float height, float scale) {
        if (painting) throw new IllegalStateException(name + " is already painting: end() first");
        if (width <= 0f || height <= 0f || scale <= 0f) {
            throw new IllegalArgumentException(name + ": size and scale must be positive, were " + width + " x " + height
                    + " at " + scale);
        }
        size(recording, (int) Math.ceil(width * scale), (int) Math.ceil(height * scale));
        this.scale = scale;
        constants.view.identity();
        constants.projection.identity().ortho(0f, texelsWide / scale, texelsHigh / scale, 0f, -1f, 1f);
        constants.resolution(texelsWide, texelsHigh).cameraFromView();
        canvas.recordInto(recording, texture, CgLoad.clear(0f, 0f, 0f, 0f), constants);
        painting = true;
        return canvas;
    }

    /** Lands what was painted. */
    public void end() {
        if (!painting) throw new IllegalStateException(name + " is not painting");
        canvas.stop();
        painting = false;
    }

    /**
     * A quad on {@code target} drawing its region at ({@code x}, {@code y}), {@code width} by {@code height} units as
     * painted, under {@code material}: its texture, UVs and size set. Place, pose, colour and submit it.
     */
    public CgQuadBatch.Quad quad(CgCanvas target, CgMaterial material, float x, float y, float width, float height) {
        return quad(target, material, 0, x, y, width, height);
    }

    /**
     * As {@link #quad(CgCanvas, CgMaterial, float, float, float, float)}, its texture at {@code unit}: a material's
     * sampler other than its first, as a retainer box's effect names its texture parameter
     * ({@code material.samplerUnit(name)}).
     */
    public CgQuadBatch.Quad quad(CgCanvas target, CgMaterial material, int unit, float x, float y, float width,
                                 float height) {
        if (texture == null) throw new IllegalStateException(name + " was never painted");
        float u0 = x * scale / texelsWide, u1 = (x + width) * scale / texelsWide;
        // A target's first row is its bottom: the painted top is v = 1.
        float v0 = 1f - y * scale / texelsHigh, v1 = 1f - (y + height) * scale / texelsHigh;
        return target.quad(material).texture(unit, texture).uv(u0, v0, u1, v1).size(width, height);
    }

    /**
     * Blurs the region ({@code x}, {@code y}) {@code width} by {@code height} units, as painted, by a Gaussian of
     * {@code sigma} units: Skia's scale-then-blur ({@link CgGpuOps#blurScaled}) into a texture of its own, nothing past
     * the region read. Returns the reduction to draw it with, the last argument of
     * {@link #quad(CgCanvas, CgMaterial, int, float, float, float, float, int)}.
     *
     * <ul>
     *   <li>After {@link #end}, before anything on the canvas samples it.</li>
     *   <li>What lies within three sigma of the region's edge spreads past it and is cut: pad the region by that.</li>
     *   <li>A compute pass: GL 4.3 and up, or a device.</li>
     * </ul>
     */
    public int blur(CgRecording recording, float x, float y, float width, float height, float sigma) {
        if (painting) throw new IllegalStateException(name + ": end() before blur");
        if (texture == null) throw new IllegalStateException(name + " was never painted");
        float texels = sigma * scale;
        int reduce = CgGpuOps.blurScale(texels);
        int at = Integer.numberOfTrailingZeros(reduce);
        CgGraphTexture target = blurred[at];
        if (target == null || target.getWidth() != texelsWide / reduce || target.getHeight() != texelsHigh / reduce) {
            if (target != null) recording.release(target);
            target = CgGraphTexture.requested(name + ".blur" + reduce,
                    new CgTextureDesc(texelsWide / reduce, texelsHigh / reduce, CgTextureDesc.RGBA8));
            blurred[at] = target;
        }
        // Its rows run up from the bottom, as quad's uv: the painted top is the last row.
        int x0 = Math.max(0, (int) Math.floor(x * scale));
        int x1 = Math.min(texelsWide, (int) Math.ceil((x + width) * scale));
        int y0 = Math.max(0, texelsHigh - (int) Math.ceil((y + height) * scale));
        int y1 = Math.min(texelsHigh, texelsHigh - (int) Math.floor(y * scale));
        CgComputePass pass = recording.compute(name + ".blur").timed(GPU_BLUR);
        CgGpuOps.blurScaled(pass, texture, x0, y0, x1 - x0, y1 - y0, target, texels, CgGpuOps.Edge.CLAMP);
        pass.end();
        return reduce;
    }

    /**
     * As {@link #quad(CgCanvas, CgMaterial, int, float, float, float, float)}, sampling the {@link #blur} that returned
     * {@code reduce}; 0 for the sharp image.
     */
    public CgQuadBatch.Quad quad(CgCanvas target, CgMaterial material, int unit, float x, float y, float width,
                                 float height, int reduce) {
        CgQuadBatch.Quad quad = quad(target, material, unit, x, y, width, height);
        if (reduce == 0) return quad;
        CgGraphTexture blur = blurred[Integer.numberOfTrailingZeros(reduce)];
        if (blur == null) throw new IllegalStateException(name + ": no blur at a reduction of " + reduce);
        return quad.texture(unit, blur);
    }

    /** Releases its texture into {@code recording}: it goes once the frame executes. */
    public void release(CgRecording recording) {
        if (texture != null) recording.release(texture);
        for (int i = 0; i < blurred.length; i++) {
            if (blurred[i] != null) recording.release(blurred[i]);
            blurred[i] = null;
        }
        texture = null;
        texelsWide = texelsHigh = 0;
    }

    /** Frees its texture's storage now: a context's teardown, with no frame to release it in. */
    public void delete() {
        if (texture != null) CgExecutor.freeStorage(texture);
        for (int i = 0; i < blurred.length; i++) {
            if (blurred[i] != null) CgExecutor.freeStorage(blurred[i]);
            blurred[i] = null;
        }
        texture = null;
        texelsWide = texelsHigh = 0;
    }

    /** Remakes the texture when it is smaller than {@code wide} by {@code high} texels. */
    private void size(CgRecording recording, int wide, int high) {
        if (texture != null && wide <= texelsWide && high <= texelsHigh) return;
        int w = Math.max(texelsWide, grain(wide)), h = Math.max(texelsHigh, grain(high));
        if (texture != null) recording.release(texture);
        texelsWide = w;
        texelsHigh = h;
        texture = CgGraphTexture.requested(name, new CgTextureDesc(w, h, CgTextureDesc.RGBA8));
    }

    private static int grain(int texels) {
        return (Math.max(1, texels) + GRAIN - 1) / GRAIN * GRAIN;
    }
}
