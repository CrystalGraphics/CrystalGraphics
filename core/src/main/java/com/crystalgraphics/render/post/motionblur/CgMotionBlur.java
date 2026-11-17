package com.crystalgraphics.render.post.motionblur;

import javax.annotation.Nullable;

import org.joml.Matrix4f;
import org.joml.Vector4f;

import com.crystalgraphics.compute.CgCompute;
import com.crystalgraphics.compute.CgKernel;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.render.CgFrameClock;
import com.crystalgraphics.render.graph.CgBufferDesc;
import com.crystalgraphics.render.graph.CgBufferUsage;
import com.crystalgraphics.render.graph.CgComputePass;
import com.crystalgraphics.render.graph.CgDispatch;
import com.crystalgraphics.render.graph.CgGraphBuffer;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgScreenTexture;
import com.crystalgraphics.render.look.CgLookWeights;
import com.crystalgraphics.render.post.CgPostContext;
import com.crystalgraphics.render.post.settings.CgPostSettings;
import com.crystalgraphics.render.scene.renderer.feature.translucency.CgSeparateTranslucency;
import com.crystalgraphics.render.view.CgView;
import com.crystalgraphics.render.view.CgViewState;
import com.crystalgraphics.settings.CgGraphicsSettings;
import com.crystalgraphics.settings.CgQuality;
import com.crystalgraphics.trace.CgGpuTrace;

/**
 * The post chain's motion blur: what moved this frame, and the camera's turn and travel, blurred along their motion
 * after the temporal step, at the output size, before {@code SCENE} (Unreal's order). Unreal 5's design: each render
 * pixel's motion flattened beside its depth, 16x16 tiles' slowest and fastest dilated to where their motion reaches,
 * output tiles classified into four filters dispatched indirectly, McGuire's reconstruction filter on the moving ones.
 * Engine side; a view turns it on and a consumer sets its look.
 *
 * <pre>{@code
 * view.showFlags().motionBlur(true);                                  // Unreal's defaults: half a frame, 5% at most, 30 fps
 * CgPost.client().camera().motionBlur(1f, 10f);                       // a whole frame's motion, up to a tenth of the width
 * scene.postVolume(new CgPostSettings().motionBlurAmount(0f)).spawn(); // none inside a volume
 * view.cameraMotionBlur(false);                                       // only what moves on its own blurs
 * }</pre>
 *
 * <ul>
 *   <li>The blur is the shutter of a {@code motionBlurTargetFps} camera (30 by default) whatever the frame rate: each
 *       frame's motion is scaled by the target's frame time over this frame's.</li>
 *   <li>Off on a camera cut, at {@code CgQuality.LOW}, and while the amount or the longest blur is under 0.001. Medium
 *       and High take 12 samples (Medium at half size where the blur is long), Ultra 16.</li>
 *   <li>A material drawn {@code AfterMotionBlur} is laid over by the filter itself, unsmeared; with motion blur off,
 *       by {@link #compose}.</li>
 *   <li>The resolved look weights are blurred with the colour, so a smeared graded silhouette keeps its look; the
 *       engine's bloom light along the same tiles ({@link #glow}), so a fast light's bloom streaks with it.</li>
 * </ul>
 */
public final class CgMotionBlur {

    public static final String SHADER = "crystalgraphics:shaders/post/motionblur/motion_blur.compute";
    private static final int TILE = 16;
    /** How far the tile dilation gathers, in tiles; past it the tiles scatter (Unreal's 3). */
    private static final float GATHER_TILES = 3f;
    private static final int LISTS = 4;
    private static final String[] FILTERS = {"GatherHalf", "GatherFull", "ScatterHalf", "ScatterFull"};
    private static final int GPU_TILES = CgGpuTrace.name("post.motionBlur.tiles"), GPU_FILTER = CgGpuTrace.name("post.motionBlur.filter");
    private static final CgViewState.Key<Shutter> SHUTTER = new CgViewState.Key<>("crystalgraphics:motion_blur.shutter", Shutter::new);

    private CgKernel flatten, dilate, scatterClear, scatter, scatterResolve, clearLists, classify, setup, halve, compose, glowKernel;
    private final CgKernel[] filters = new CgKernel[LISTS], carried = new CgKernel[LISTS];
    private final Matrix4f clipToPrev = new Matrix4f();
    private final Vector4f column = new Vector4f();
    /** This frame's dilated tiles and flattened motion, for {@link #glow}; null where it did not run. */
    @Nullable
    private CgGraphTexture dilated, flat;
    private float tilesX, tilesY, renderW, renderH, outW, outH;

    /** Starts its kernels compiling, as a loading screen does. Render thread. */
    public static void precache() {
        CgCompute c = CgCompute.load(SHADER);
        for (String k : new String[]{"Flatten", "Dilate", "ScatterClear", "Scatter", "ScatterResolve", "ClearLists",
                "Classify", "Setup", "Half", "Compose", "Glow"}) {
            c.kernel(k).precache();
        }
        for (String f : FILTERS) {
            c.kernel(f).precache();
            c.kernel(f).withKeywords("CARRY").precache();
        }
    }

    /**
     * Whether {@code view} may blur this frame under {@code settings}: what its prepare asks velocity for, before the
     * frame knows whether it draws a linear scene.
     */
    public static boolean wants(CgView view, CgPostSettings settings) {
        return view.showFlags().motionBlur() && settings.motionBlurOn() && quality() != CgQuality.LOW
                && !view.camera().isOrthographic();
    }

    /**
     * Whether it blurs this firing: it {@link #wants} to, over a linear scene, with last frame's view one to measure
     * motion from (not a camera cut) and the velocity drawn.
     */
    public boolean shows(CgPostContext ctx) {
        CgView view = ctx.view();
        return wants(view, ctx.settings()) && view.textures().linear() && view.historyValid() && ctx.scene().velocity() != null;
    }

    /**
     * Blurs {@code picture} (the temporal step's answer, or the scene) into a texture of its own and answers it, the
     * {@code AfterMotionBlur} translucency laid over, and the resolved look weights blurred beside it.
     */
    public CgScreenTexture apply(CgPostContext ctx, CgScreenTexture picture) {
        CgView view = ctx.view();
        CgPostSettings s = ctx.settings();
        load();
        int rw = Math.max(1, view.width()), rh = Math.max(1, view.height());
        int ow = picture.width(), oh = picture.height();
        int tx = (rw + TILE - 1) / TILE, ty = (rh + TILE - 1) / TILE;
        int fx = (ow + TILE - 1) / TILE, fy = (oh + TILE - 1) / TILE, listLength = fx * fy;
        renderW = rw;
        renderH = rh;
        outW = ow;
        outH = oh;
        tilesX = tx;
        tilesY = ty;
        CgQuality quality = quality();
        int most = quality.atLeast(CgQuality.ULTRA) ? 16 : 12;
        boolean halfGather = quality == CgQuality.MEDIUM;

        // Clip motion (this frame's position less last frame's, -2 to 2) to output pixels: half the size, the amount, half
        // again as the samples run both ways, and the shutter's time scale (Unreal's VelocityScale)
        float scale = 0.5f * s.motionBlurAmount() * 0.5f * view.state().get(SHUTTER).scale(s.motionBlurTargetFps());
        float longest = s.motionBlurMax() / 100f * ow * 0.5f;
        float pixelsToTiles = (float) rw / ow / TILE;
        float reach = longest * pixelsToTiles;

        clipToPrev.set(view.camera().projection()).mul(view.camera().view()).invert().mulLocal(view.previousViewProjection());
        flat = ctx.texture("motionBlur.flat", rw, rh, CgFormat.RGBA16_FLOAT).texture();
        CgGraphTexture ranges = ctx.texture("motionBlur.ranges", tx, ty, CgFormat.RGBA16_FLOAT).texture();
        dilated = ctx.texture("motionBlur.dilated", tx, ty, CgFormat.RGBA16_FLOAT).texture();

        CgComputePass pass = ctx.recording().compute("motionBlur.tiles").timed(GPU_TILES);
        CgDispatch d = pass.dispatch(flatten, rw, rh, 1).image("FLAT", flat).image("RANGES", ranges)
                .texture("_SceneDepth", ctx.scene().velocityDepth().sampledDepth()).texture("_Velocity", ctx.scene().velocity())
                .set("_Render", rw, rh, 1f / rw, 1f / rh).set("_Jitter", view.jitterTexelsX(), view.jitterTexelsY(), 0f, 0f)
                .set("_Depth", view.camera().projection().m32(), view.camera().projection().m22(), 0f, 0f)
                .set("_Scale", ow * scale, oh * scale, longest, view.cameraMotionBlur() ? 0f : 1f);
        for (int i = 0; i < 4; i++) {
            clipToPrev.getColumn(i, column);
            d.set("_ClipToPrev" + i, column.x, column.y, column.z, column.w);
        }
        if (reach > GATHER_TILES) {
            CgGraphTexture keyMax = ctx.texture("motionBlur.keyMax", tx, ty, CgFormat.R32_UINT).texture();
            CgGraphTexture keyMin = ctx.texture("motionBlur.keyMin", tx, ty, CgFormat.R32_UINT).texture();
            pass.dispatch(scatterClear, tx, ty, 1).image("KEY_MAX", keyMax).image("KEY_MIN", keyMin);
            pass.dispatch(scatter, tx, ty, 1).image("KEY_MAX", keyMax).image("KEY_MIN", keyMin).texture("_Ranges", ranges)
                    .set("_Tiles", tx, ty, pixelsToTiles, (float) Math.ceil(reach));
            pass.dispatch(scatterResolve, tx, ty, 1).image("KEY_MAX", keyMax).image("KEY_MIN", keyMin).image("DILATED", dilated);
        } else {
            pass.dispatch(dilate, tx, ty, 1).image("DILATED", dilated).texture("_Ranges", ranges)
                    .set("_Tiles", tx, ty, pixelsToTiles, 0f);
        }

        CgGraphBuffer lists = CgGraphBuffer.transientBuffer("motionBlur.lists", CgBufferDesc.elements(listLength * LISTS, 4, CgBufferUsage.STORAGE));
        CgGraphBuffer counts = CgGraphBuffer.transientBuffer("motionBlur.counts", CgBufferDesc.elements(LISTS, 4, CgBufferUsage.STORAGE));
        CgGraphBuffer args = CgGraphBuffer.transientBuffer("motionBlur.args",
                CgBufferDesc.elements(LISTS * 3, 4, CgBufferUsage.STORAGE, CgBufferUsage.INDIRECT));
        pass.dispatchGroups(clearLists, 1, 1, 1).bind("COUNTS", counts);
        pass.dispatch(classify, fx, fy, 1).bind("COUNTS", counts).bind("LISTS", lists).texture("_Dilated", dilated)
                .set("_Tiles", tx, ty, pixelsToTiles, 0f).set("_Filter", most, halfGather ? 1f : 0f, listLength, 0f)
                .set("_FilterTiles", fx, fy, (float) rw / ow, 0f);
        pass.dispatchGroups(setup, 1, 1, 1).bind("COUNTS", counts).bind("ARGS", args);
        pass.end();

        CgScreenTexture half = ctx.texture("motionBlur.half", (ow + 1) / 2, (oh + 1) / 2, CgFormat.RGBA16_FLOAT);
        CgScreenTexture out = ctx.texture("motionBlur.out", ow, oh, CgFormat.RGBA16_FLOAT);
        CgGraphTexture translucency = afterMotionBlur(view);
        CgGraphTexture weights = CgLookWeights.current(view);
        // Without look weights a texel stands in: the uncarried kernels never write it
        CgGraphTexture blurredWeights = weights != null ? ctx.texture("motionBlur.weights", ow, oh, CgFormat.RGBA8_UNORM).texture()
                : ctx.texture("motionBlur.noWeights", 1, 1, CgFormat.RGBA8_UNORM).texture();

        CgComputePass filter = ctx.recording().compute("motionBlur.filter").timed(GPU_FILTER);
        filter.dispatch(halve, half.width(), half.height(), 1).image("OUT", half.texture()).texture("_Color", picture.texture())
                .set("_Output", ow, oh, 1f / ow, 1f / oh);
        for (int list = 0; list < LISTS; list++) {
            CgDispatch f = filter.dispatchIndirect(weights != null ? carried[list] : filters[list], args, list * 12L)
                    .bind("LISTS", lists).image("OUT", out.texture()).texture("_Color", picture.texture())
                    .texture("_Half", half.texture()).texture("_Dilated", dilated).texture("_Flat", flat)
                    .set("_Render", rw, rh, 1f / rw, 1f / rh).set("_Output", ow, oh, 1f / ow, 1f / oh)
                    .set("_Tiles", tx, ty, pixelsToTiles, 0f)
                    .set("_Filter", most, halfGather ? 1f : 0f, listLength, translucency != null ? 1f : 0f);
            if (translucency != null) f.texture("_Translucency", translucency);
            f.image("CARRIED", blurredWeights);
            if (weights != null) f.texture("_Carried", weights);
        }
        filter.end();
        if (weights != null) view.publish(CgLookWeights.RESOLVED, blurredWeights);
        return out;
    }

    /**
     * Motion blur off: lays the {@code AfterMotionBlur} translucency over {@code picture} into a texture of its own
     * (Unreal's compose after the temporal step, never into its history); {@code picture} itself where none drew.
     */
    public CgScreenTexture compose(CgPostContext ctx, CgScreenTexture picture) {
        flat = dilated = null;
        CgGraphTexture translucency = afterMotionBlur(ctx.view());
        if (translucency == null) return picture;
        load();
        int ow = picture.width(), oh = picture.height();
        CgScreenTexture out = ctx.texture("motionBlur.composed", ow, oh, CgFormat.RGBA16_FLOAT);
        CgComputePass pass = ctx.recording().compute("motionBlur.compose").timed(GPU_FILTER);
        pass.dispatch(compose, ow, oh, 1).image("OUT", out.texture()).texture("_Color", picture.texture())
                .texture("_Translucency", translucency).set("_Output", ow, oh, 1f / ow, 1f / oh);
        pass.end();
        return out;
    }

    /**
     * The engine's bloom light ({@code CgTemporalGlow}) blurred along this frame's tiles, at its own size, after
     * {@link #apply}; {@code glow} itself where motion blur did not run.
     */
    @Nullable
    public CgGraphTexture glow(CgPostContext ctx, @Nullable CgGraphTexture glow) {
        if (glow == null || dilated == null || flat == null) return glow;
        int gw = glow.getWidth(), gh = glow.getHeight();
        CgGraphTexture out = ctx.texture("motionBlur.glow", gw, gh, CgFormat.RGBA16_FLOAT).texture();
        CgComputePass pass = ctx.recording().compute("motionBlur.glow").timed(GPU_FILTER);
        pass.dispatch(glowKernel, gw, gh, 1).image("OUT", out).texture("_GlowIn", glow).texture("_Dilated", dilated)
                .texture("_Flat", flat).set("_Glow", gw, gh, 1f / gw, 1f / gh)
                .set("_Render", renderW, renderH, 1f / renderW, 1f / renderH).set("_Output", outW, outH, 1f / outW, 1f / outH)
                .set("_Tiles", tilesX, tilesY, 0f, 0f);
        pass.end();
        return out;
    }

    @Nullable
    private static CgGraphTexture afterMotionBlur(CgView view) {
        CgSeparateTranslucency apart = view.read(CgSeparateTranslucency.KEY);
        return apart != null ? apart.afterMotionBlur() : null;
    }

    private static CgQuality quality() {
        return CgGraphicsSettings.QUALITY.get();
    }

    private void load() {
        if (flatten != null) return;
        CgCompute c = CgCompute.load(SHADER);
        flatten = c.kernel("Flatten");
        dilate = c.kernel("Dilate");
        scatterClear = c.kernel("ScatterClear");
        scatter = c.kernel("Scatter");
        scatterResolve = c.kernel("ScatterResolve");
        clearLists = c.kernel("ClearLists");
        classify = c.kernel("Classify");
        setup = c.kernel("Setup");
        halve = c.kernel("Half");
        compose = c.kernel("Compose");
        glowKernel = c.kernel("Glow");
        for (int i = 0; i < LISTS; i++) {
            filters[i] = c.kernel(FILTERS[i]);
            carried[i] = filters[i].withKeywords("CARRY");
        }
    }

    /**
     * A view's shutter (Unreal's {@code UpdateMotionBlurTimeScale}): the target frame time over this frame's on the
     * engine's clock ({@code CgFrameClock}: real time, or a driver's fixed step), the target a moving average of the
     * frame time where the settings follow the frame rate. A clock that has not moved keeps last frame's.
     */
    private static final class Shutter {
        private double last = -1.0;
        private float target = 1f / 60f, timeScale = 1f;

        float scale(int targetFps) {
            double now = CgFrameClock.seconds();
            float delta = last < 0.0 ? 1f / 60f : (float) (now - last);
            last = now;
            if (!(delta > 0f)) return timeScale;
            target = targetFps <= 0 ? target + (delta - target) * 0.1f : 1f / targetFps;
            timeScale = target / delta;
            return timeScale;
        }
    }
}
