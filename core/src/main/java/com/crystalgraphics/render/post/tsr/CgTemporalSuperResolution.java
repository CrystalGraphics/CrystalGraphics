package com.crystalgraphics.render.post.tsr;

import com.crystalgraphics.compute.CgCompute;
import com.crystalgraphics.compute.CgKernel;
import com.crystalgraphics.gpu.resource.CgTextureDesc;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.render.graph.CgComputePass;
import com.crystalgraphics.render.graph.CgDispatch;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.graph.CgScreenTexture;
import com.crystalgraphics.render.post.CgPostContext;
import com.crystalgraphics.render.post.aa.CgTemporalGlow;
import com.crystalgraphics.render.post.upscale.CgUpscaler;
import com.crystalgraphics.render.scene.renderer.feature.translucency.CgSeparateTranslucency;
import com.crystalgraphics.render.view.CgView;
import com.crystalgraphics.render.view.CgViewState;
import com.crystalgraphics.settings.CgGraphicsSettings;
import com.crystalgraphics.settings.CgQuality;
import com.crystalgraphics.trace.CgGpuTrace;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * The engine's temporal super resolution: a view's world drawn at a fraction of its output size through a jittered
 * projection, rebuilt to the full output size from its frames. Each output pixel keeps a history of up to 16 samples,
 * gathered over the frames from wherever the jitter put each frame's pixels, and drops it where this frame shows the
 * history is no longer what is there. Runs after depth of field, laying the separate translucency in itself, before {@code SCENE}; what
 * records after it (bloom, the looks, the composite, {@code OVERLAY}) runs at the output size.
 *
 * <pre>{@code
 * view.showFlags().temporalAA(true);
 * view.jitter(true);
 * view.antiAliasing(CgAntiAliasing.TSR);
 * view.screenPercentage(67f);                // drawn at 67%, rebuilt to the full size
 * }</pre>
 *
 * <p>At 100% it resolves at the size drawn, as temporal AA does, with its own rejection.</p>
 *
 * <p>The player's {@link CgGraphicsSettings#ANTI_ALIASING} sets how much it does where it drops a pixel's history:
 * from Medium a spatial anti-aliasing smooths that pixel's edge the frame it is seen, and Ultra follows each edge 8 pixels
 * each way rather than 3.</p>
 *
 * <ul>
 *   <li>Engine views only: a host drawing its world itself (Minecraft) stays at 100%, and its own draws are not
 *       jittered.</li>
 *   <li>The history lives on the view's state ({@code CgViewState}); a camera cut, a resize, or a view that did not draw
 *       last frame starts it over.</li>
 *   <li>Its passes, in order: dilate the velocity and scatter each surface to where it was (disocclusion), reproject last
 *       frame's guide, measure the rejection, anti-alias spatially (Medium and up), update the history. Each is timed
 *       ({@code post.tsr.*}).</li>
 * </ul>
 */
public final class CgTemporalSuperResolution implements CgUpscaler {

    private static final String DILATE = "crystalgraphics:shaders/post/tsr/dilate_velocity.compute",
            DECIMATE = "crystalgraphics:shaders/post/tsr/decimate_history.compute",
            REJECT = "crystalgraphics:shaders/post/tsr/reject_shading.compute",
            SPATIAL = "crystalgraphics:shaders/post/tsr/spatial_anti_aliasing.compute",
            UPDATE = "crystalgraphics:shaders/post/tsr/update_history.compute";
    private static final CgViewState.HistoryKey COLOR = new CgViewState.HistoryKey("crystalgraphics:tsr_color"),
            VALID = new CgViewState.HistoryKey("crystalgraphics:tsr_valid"),
            GUIDE = new CgViewState.HistoryKey("crystalgraphics:tsr_guide");
    private static final int GPU_DILATE = CgGpuTrace.name("post.tsr.dilate"), GPU_DECIMATE = CgGpuTrace.name("post.tsr.decimate"),
            GPU_REJECT = CgGpuTrace.name("post.tsr.reject"), GPU_SPATIAL = CgGpuTrace.name("post.tsr.spatial"),
            GPU_UPDATE = CgGpuTrace.name("post.tsr.update");

    /** The most samples a history pixel holds ({@code r.TSR.History.SampleCount}). */
    static final float HISTORY_SAMPLES = 16f;
    /** The samples a rejected pixel's history keeps ({@code r.TSR.ShadingRejection.SampleCount}). */
    static final float REJECTED_SAMPLES = 2f;
    /** The samples motion of {@link #MOTION_PIXEL_SPEED} output pixels a frame leaves ({@code r.TSR.Velocity.WeightClamping*}). */
    static final float MOTION_SAMPLES = 4f, MOTION_PIXEL_SPEED = 1f;
    /**
     * The decision pass's tile and the border of it that only feeds the inside ({@code r.TSR.ShadingRejection.TileOverscan}).
     * Its filters chain seven deep, so the outer rings of what a tile writes see its replicated edge; measured on a still
     * frame at 50%, a border of 7 rejects no differently, at twice the cost.
     */
    static final int TILE = 32, TILE_BORDER = 3;
    /** How far the spatial anti-aliasing follows an edge each way, below Ultra and at it ({@code r.TSR.RejectionAntiAliasingQuality}). */
    static final int SPATIAL_STEPS = 3, SPATIAL_STEPS_ULTRA = 8;
    /** The least fraction it rebuilds from. */
    static final float MIN_FRACTION = 0.25f;
    /** The jitter's base sequence, and the primes it is rounded up to. */
    private static final int JITTER_SAMPLES = 8;
    private static final int[] PRIMES = {2, 3, 5, 7, 11, 13, 17, 19, 23, 29, 31, 37, 41, 43, 47, 53, 59, 61, 67, 71, 73, 79,
            83, 89, 97, 101, 103, 107, 109, 113, 127, 131, 137, 139, 149, 151, 157, 163, 167, 173, 179, 181, 191, 193, 197,
            199, 211, 223, 227, 229, 233, 239, 241, 251};
    /** The material mip bias's offset and floor ({@code r.ViewTextureMipBias.Offset} and {@code .Min}). */
    private static final float MIP_BIAS_OFFSET = -0.3f, MIP_BIAS_MIN = -2f;

    private final CgTemporalGlow glow = new CgTemporalGlow(new CgViewState.HistoryKey("crystalgraphics:tsr_glow"));
    private final Matrix4f clipToPrev = new Matrix4f();
    private final Vector4f column = new Vector4f();
    private CgKernel clear, dilate, decimate, reject, rejectSpatial, spatial, update, updateSpatial;
    private int width, height, outWidth, outHeight;
    private CgGraphTexture atomics, closest, dilateMask, vector, reprojected, filled, mask, rejection, color, luma, aaMask,
            offsets, picture;

    /** Starts its kernels compiling, as a loading screen does. Render thread. */
    public static void precache() {
        CgCompute.load(DILATE).kernel("Clear").precache();
        CgCompute.load(DILATE).kernel("Dilate").precache();
        CgCompute.load(DECIMATE).kernel("Decimate").precache();
        CgCompute.load(REJECT).kernel("Reject").precache();
        CgCompute.load(REJECT).kernel("RejectSpatial").precache();
        CgCompute.load(SPATIAL).kernel("Spatial").precache();
        CgCompute.load(UPDATE).kernel("Update").precache();
        CgCompute.load(UPDATE).kernel("UpdateSpatial").precache();
    }

    @Override
    public float minFraction() {
        return MIN_FRACTION;
    }

    /**
     * 8 frames, times the input pixels an output pixel spans where it upscales, rounded up to a prime: so every output
     * pixel takes samples from across it, and the sequence shares no period with the frame index's powers of two.
     */
    @Override
    public int jitterSamples(float fraction) {
        int n = Math.round(JITTER_SAMPLES * Math.max(1f, 1f / (fraction * fraction)));
        for (int i = Math.max(4, (n - 1) / 5); i < PRIMES.length; i++) {
            if (PRIMES[i] >= n) return PRIMES[i];
        }
        return n;
    }

    /** The textures' mip bias: sampled as sharp as the output, less a little, never below -2. */
    @Override
    public float materialMipBias(float fraction) {
        if (fraction >= 1f) return 0f;
        float bias = (float) (Math.log(fraction) / Math.log(2.0)) + MIP_BIAS_OFFSET;
        return Math.max(-4f, Math.min(4f, Math.max(bias, MIP_BIAS_MIN)));
    }

    @Override
    public boolean presents(float fraction) {
        return true;
    }

    /** It lays the separate translucency in its reject pass: measured blurred, kept sharp. */
    @Override
    public boolean composesTranslucency() {
        return true;
    }

    /** Bloom's light averaged over the jitter's frames at the size drawn ({@link CgTemporalGlow}). */
    @Override
    public CgGraphTexture bloomLight(CgPostContext ctx, CgScreenTexture input, CgScreenTexture resolved, float threshold) {
        return glow.record(ctx, input, resolved, threshold);
    }

    @Override
    public CgScreenTexture apply(CgPostContext ctx, CgScreenTexture input) {
        if (clear == null) {
            clear = CgCompute.load(DILATE).kernel("Clear");
            dilate = CgCompute.load(DILATE).kernel("Dilate");
            decimate = CgCompute.load(DECIMATE).kernel("Decimate");
            reject = CgCompute.load(REJECT).kernel("Reject");
            rejectSpatial = CgCompute.load(REJECT).kernel("RejectSpatial");
            spatial = CgCompute.load(SPATIAL).kernel("Spatial");
            update = CgCompute.load(UPDATE).kernel("Update");
            updateSpatial = CgCompute.load(UPDATE).kernel("UpdateSpatial");
        }
        CgQuality quality = CgGraphicsSettings.ANTI_ALIASING.get();
        boolean antiAliasSpatially = quality.atLeast(CgQuality.MEDIUM);
        CgView view = ctx.view();
        CgViewState state = view.state();
        CgRecording recording = ctx.recording();
        int w = input.width(), h = input.height(), ow = Math.max(w, view.outputWidth()), oh = Math.max(h, view.outputHeight());
        size(w, h, ow, oh);
        CgGraphTexture colorHistory = state.history(COLOR, new CgTextureDesc(ow, oh, CgFormat.RG11B10_UFLOAT), recording);
        CgGraphTexture validHistory = state.history(VALID, new CgTextureDesc(ow, oh, CgFormat.R8_UNORM), recording);
        CgGraphTexture guideHistory = state.history(GUIDE, new CgTextureDesc(w, h, CgFormat.RGB10A2_UNORM), recording);
        boolean cut = !view.historyValid() || state.fresh(COLOR) || state.fresh(VALID) || state.fresh(GUIDE);
        float jx = view.jitterTexelsX(), jy = view.jitterTexelsY(), cutFlag = cut ? 1f : 0f;
        int frame = state.frameIndex();
        clipToPrevious(view);
        Matrix4f projection = view.camera().projection();
        float depthX = projection.m32(), depthY = projection.m22(), radius = 1f / (projection.m00() * w);
        CgGraphTexture velocity = ctx.scene().velocity(), responsive = ctx.scene().responsive();

        CgComputePass pass = recording.compute("tsr.dilate").timed(GPU_DILATE);
        pass.dispatch(clear, w, h, 1).image("ATOMICS", atomics);
        CgDispatch d = pass.dispatch(dilate, w, h, 1).image("ATOMICS", atomics).image("CLOSEST", closest)
                .image("DILATE", dilateMask).image("VECTOR", vector).texture("_SceneDepth", ctx.depth());
        if (velocity != null) d.texture("_Velocity", velocity);
        frameValues(d, w, h, jx, jy, cutFlag, depthX, depthY, radius);
        pass.end();

        pass = recording.compute("tsr.decimate").timed(GPU_DECIMATE);
        d = pass.dispatch(decimate, w, h, 1).image("GUIDE", reprojected).image("FILLED", filled).image("MASK", mask)
                .texture("_PrevGuide", guideHistory.previous()).texture("_Closest", closest).texture("_Dilate", dilateMask)
                .texture("_Vector", vector).texture("_Atomics", atomics).set("_Frame", frame, 0f, 0f, 0f);
        if (responsive != null) d.texture("_Responsive", responsive);
        frameValues(d, w, h, jx, jy, cutFlag, depthX, depthY, radius);
        pass.end();

        float fraction = (float) w / ow;
        float least = 1f / (1f + HISTORY_SAMPLES / (fraction * fraction));
        int stride = TILE - 2 * TILE_BORDER;
        CgSeparateTranslucency apart = view.read(CgSeparateTranslucency.KEY);
        CgGraphTexture translucency = apart != null ? apart.target() : null;
        pass = recording.compute("tsr.reject").timed(GPU_REJECT);
        d = pass.dispatchGroups(antiAliasSpatially ? rejectSpatial : reject, (w + stride - 1) / stride,
                        (h + stride - 1) / stride, 1).image("GUIDE", guideHistory)
                .image("REJECTION", rejection).image("COLOR", color).texture("_Color", input.texture())
                .texture("_Guide", reprojected).texture("_Mask", mask).set("_Input", w, h, 1f / w, 1f / h)
                .set("_Jitter", jx, jy, cutFlag, 0f).set("_Frame", frame, 0f, 0f, 0f)
                .set("_Reject", least, TILE_BORDER, stride, translucency != null ? 1f : 0f);
        if (antiAliasSpatially) d.image("LUMA", luma).image("AA_MASK", aaMask);
        if (translucency != null) d.texture("_Translucency", translucency);
        pass.end();

        if (antiAliasSpatially) {
            int steps = quality.atLeast(CgQuality.ULTRA) ? SPATIAL_STEPS_ULTRA : SPATIAL_STEPS;
            pass = recording.compute("tsr.spatial").timed(GPU_SPATIAL);
            pass.dispatch(spatial, w, h, 1).image("OFFSET", offsets).texture("_Luma", luma).texture("_AAMask", aaMask)
                    .set("_Input", w, h, 1f / w, 1f / h).set("_Spatial", steps, 0f, 0f, 0f);
            pass.end();
        }

        float toHistory = (float) ow / w, hysteresis = 1f / HISTORY_SAMPLES;
        pass = recording.compute("tsr.update").timed(GPU_UPDATE);
        d = pass.dispatch(antiAliasSpatially ? updateSpatial : update, ow, oh, 1).image("COLOR", colorHistory).image("VALID", validHistory).image("PICTURE", picture)
                .texture("_Color", color).texture("_Rejection", rejection).texture("_Vector", filled)
                .texture("_PrevColor", colorHistory.previous()).texture("_PrevValid", validHistory.previous())
                .set("_Input", w, h, 1f / w, 1f / h).set("_Output", ow, oh, 1f / ow, 1f / oh)
                .set("_Jitter", jx, jy, cutFlag, 0f).set("_Frame", frame, 0f, 0f, 0f)
                .set("_History", HISTORY_SAMPLES, hysteresis, 1f - REJECTED_SAMPLES * hysteresis, toHistory)
                .set("_Motion", Math.max(0f, Math.min(1f, 1f - MOTION_SAMPLES * hysteresis)), 1f / MOTION_PIXEL_SPEED, 0f, 0f);
        if (antiAliasSpatially) d.texture("_Offset", offsets);
        pass.end();
        return CgScreenTexture.of(picture);
    }

    /** The frame's geometry every pass of the first two reads. */
    private void frameValues(CgDispatch d, int w, int h, float jx, float jy, float cut, float depthX, float depthY, float radius) {
        d.set("_Input", w, h, 1f / w, 1f / h).set("_Jitter", jx, jy, cut, 0f).set("_Depth", depthX, depthY, radius, 0f);
        for (int i = 0; i < 4; i++) {
            clipToPrev.getColumn(i, column);
            d.set("_ClipToPrev" + i, column.x, column.y, column.z, column.w);
        }
    }

    /** This frame's unjittered clip space to last frame's: its camera-relative world through last frame's projection. */
    private void clipToPrevious(CgView view) {
        clipToPrev.set(view.camera().projection()).mul(view.camera().view()).invert();
        clipToPrev.mulLocal(view.previousViewProjection());
    }

    /** Its transient targets for an input of {@code w x h} and an output of {@code ow x oh}: made again when they move. */
    private void size(int w, int h, int ow, int oh) {
        if (atomics != null && width == w && height == h && outWidth == ow && outHeight == oh) return;
        width = w;
        height = h;
        outWidth = ow;
        outHeight = oh;
        atomics = transientTexture("cg_tsr_atomics", w, h, CgFormat.R32_UINT);
        closest = transientTexture("cg_tsr_closest", w, h, CgFormat.R32_FLOAT);
        dilateMask = transientTexture("cg_tsr_dilate", w, h, CgFormat.RG8_UINT);
        vector = transientTexture("cg_tsr_vector", w, h, CgFormat.RG16_UNORM);
        reprojected = transientTexture("cg_tsr_reprojected", w, h, CgFormat.RGB10A2_UNORM);
        filled = transientTexture("cg_tsr_filled", w, h, CgFormat.RG16_UNORM);
        mask = transientTexture("cg_tsr_mask", w, h, CgFormat.RG8_UNORM);
        rejection = transientTexture("cg_tsr_rejection", w, h, CgFormat.RGBA8_UNORM);
        color = transientTexture("cg_tsr_color", w, h, CgFormat.RG11B10_UFLOAT);
        luma = transientTexture("cg_tsr_luma", w, h, CgFormat.R16_FLOAT);
        aaMask = transientTexture("cg_tsr_aa_mask", w, h, CgFormat.R8_UNORM);
        offsets = transientTexture("cg_tsr_offsets", w, h, CgFormat.RG8_UINT);
        picture = transientTexture("cg_tsr_picture", ow, oh, CgFormat.RGBA16_FLOAT);
    }

    private static CgGraphTexture transientTexture(String name, int w, int h, CgFormat format) {
        return CgGraphTexture.transientTexture(name, new CgTextureDesc(w, h, format));
    }
}
