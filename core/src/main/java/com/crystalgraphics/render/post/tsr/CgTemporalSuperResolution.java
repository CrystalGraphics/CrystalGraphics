package com.crystalgraphics.render.post.tsr;

import com.crystalgraphics.compute.CgCompute;
import com.crystalgraphics.compute.CgKernel;
import com.crystalgraphics.gpu.resource.CgTextureDesc;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.render.look.CgLookWeights;
import com.crystalgraphics.render.graph.CgComputePass;
import com.crystalgraphics.render.graph.CgDispatch;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.graph.CgScreenTexture;
import com.crystalgraphics.render.post.CgPostContext;
import com.crystalgraphics.render.post.aa.CgTemporalAA;
import com.crystalgraphics.render.post.aa.CgTemporalGlow;
import com.crystalgraphics.render.post.debug.CgPostDebug;
import com.crystalgraphics.render.post.upscale.CgUpscaler;
import com.crystalgraphics.render.scene.renderer.feature.translucency.CgSeparateTranslucency;
import com.crystalgraphics.render.view.CgView;
import com.crystalgraphics.render.view.CgViewKey;
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
 *       frame's guide, measure the rejection, anti-alias spatially (Medium and up), update the history, sharpen it (RCAS, with
 *       temporal AA's switch, {@code CgTemporalAA.sharpening}). Each is timed
 *       ({@code post.tsr.*}).</li>
 * </ul>
 */
public final class CgTemporalSuperResolution implements CgUpscaler {

    private static final String DILATE = "crystalgraphics:shaders/post/tsr/dilate_velocity.compute",
            DECIMATE = "crystalgraphics:shaders/post/tsr/decimate_history.compute",
            REJECT = "crystalgraphics:shaders/post/tsr/reject_shading.compute",
            SPATIAL = "crystalgraphics:shaders/post/tsr/spatial_anti_aliasing.compute",
            UPDATE = "crystalgraphics:shaders/post/tsr/update_history.compute",
            SHARPEN = "crystalgraphics:shaders/post/tsr/sharpen.compute",
            RESOLVE = "crystalgraphics:shaders/post/tsr/resolve_history.compute",
            SNAPSHOT = "crystalgraphics:shaders/post/tsr/snapshot.compute",
            FLICKER = "crystalgraphics:shaders/post/tsr/measure_flickering_luma.compute",
            VISUALIZE = "crystalgraphics:shaders/post/tsr/visualize.compute";
    /** This frame's debug view, at the output size, published while {@code crystalgraphics.post.debug} names one. */
    public static final CgViewKey<CgGraphTexture> DEBUG_VIEW = new CgViewKey<>("crystalgraphics:tsr_debug_view");
    /** {@code crystalgraphics.post.debug}'s TSR views, by the visualize kernel's mode. */
    private static final String[] DEBUG_VIEWS = {"", "tsr.rejection", "tsr.samples", "tsr.guide", "tsr.reprojection",
            "tsr.flicker", "tsr.resurrection"};
    private static final CgViewState.HistoryKey COLOR = new CgViewState.HistoryKey("crystalgraphics:tsr_color"),
            VALID = new CgViewState.HistoryKey("crystalgraphics:tsr_valid"),
            GUIDE = new CgViewState.HistoryKey("crystalgraphics:tsr_guide"),
            FLICKER_LUMA = new CgViewState.HistoryKey("crystalgraphics:tsr_flicker_luma"),
            MOIRE = new CgViewState.HistoryKey("crystalgraphics:tsr_moire"),
            SNAPSHOT_COLOR = new CgViewState.HistoryKey("crystalgraphics:tsr_snapshot_color"),
            SNAPSHOT_VALID = new CgViewState.HistoryKey("crystalgraphics:tsr_snapshot_valid"),
            SNAPSHOT_GUIDE = new CgViewState.HistoryKey("crystalgraphics:tsr_snapshot_guide"),
            LOOK = new CgViewState.HistoryKey("crystalgraphics:tsr_look");
    private static final CgViewState.Key<Snapshots> SNAPSHOTS = new CgViewState.Key<>("crystalgraphics:tsr_snapshots", Snapshots::new);
    private static final int GPU_DILATE = CgGpuTrace.name("post.tsr.dilate"), GPU_DECIMATE = CgGpuTrace.name("post.tsr.decimate"),
            GPU_REJECT = CgGpuTrace.name("post.tsr.reject"), GPU_SPATIAL = CgGpuTrace.name("post.tsr.spatial"),
            GPU_UPDATE = CgGpuTrace.name("post.tsr.update"), GPU_SHARPEN = CgGpuTrace.name("post.tsr.sharpen"),
            GPU_RESOLVE = CgGpuTrace.name("post.tsr.resolve"), GPU_SNAPSHOT = CgGpuTrace.name("post.tsr.snapshot"),
            GPU_FLICKER = CgGpuTrace.name("post.tsr.flicker");

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
    /**
     * The flicker heuristic (High and up, {@code r.TSR.ShadingRejection.Flickering.*}): a swing back and forth within this
     * many frames is flicker, at the frame rate cap and above; slower frames shorten it in proportion. A camera moving
     * across a surface by the parallax speed in 1080p pixels a frame has it count as moving.
     */
    static final float FLICKER_PERIOD = 2f, FLICKER_FRAME_RATE_CAP = 60f, FLICKER_MAX_PARALLAX = 10f;
    /**
     * The reprojection field (High and up): a depth edge is anti-aliased where the pixel's own and its dilated motion
     * differ by this many input pixels a frame at 100%, more as it upscales ({@code r.TSR.ReprojectionField.AntiAliasPixelSpeed}).
     */
    static final float FIELD_PIXEL_SPEED = 0.125f;
    /**
     * History resurrection (Medium and up): a snapshot of the history kept every this many frames, the older of two
     * brought back where what it saw shows again ({@code r.TSR.Resurrection.PersistentFrameInterval}).
     */
    static final int RESURRECTION_PERIOD = 31;
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
    private final Matrix4f clipToPrev = new Matrix4f(), clipToWorld = new Matrix4f(), prevClipToWorld = new Matrix4f(),
            turnToPrev = new Matrix4f(), clipToResurrection = new Matrix4f();
    private final Vector4f column = new Vector4f();
    private CgKernel clear, dilate, decimate, reject, rejectSpatial, rejectFlicker, spatial, update, updateSpatial, sharpen, resolve, snapshot,
            measure, visualize;
    /** The view state's frame index the flicker luma was last measured in, and when the last frame was resolved. */
    private int measuredFrame = -1;
    private long lastNanos;
    private int width, height, outWidth, outHeight;
    private CgGraphTexture atomics, closest, dilateMask, vector, reprojected, filled, mask, rejection, color, luma, aaMask,
            offsets, moving, reprojectedMoire, boundary, jacobian, resurrected, resolved, picture, debugView, noLook;

    /** Starts its kernels compiling, as a loading screen does. Render thread. */
    public static void precache() {
        CgCompute.load(DILATE).kernel("Clear").precache();
        CgCompute.load(DILATE).kernel("Dilate").precache();
        CgCompute.load(DECIMATE).kernel("Decimate").precache();
        CgCompute.load(REJECT).kernel("Reject").precache();
        CgCompute.load(REJECT).kernel("RejectSpatial").precache();
        CgCompute.load(REJECT).kernel("RejectFlicker").precache();
        CgCompute.load(FLICKER).kernel("Measure").precache();
        CgCompute.load(SPATIAL).kernel("Spatial").precache();
        CgCompute.load(UPDATE).kernel("Update").precache();
        CgCompute.load(UPDATE).kernel("UpdateSpatial").precache();
        CgCompute.load(SHARPEN).kernel("Sharpen").precache();
        CgCompute.load(RESOLVE).kernel("Resolve").precache();
        CgCompute.load(SNAPSHOT).kernel("Snapshot").precache();
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

    /** From High, the flicker heuristic's luma: measured here, before translucency is laid over the scene. */
    @Override
    public void beforeTranslucency(CgPostContext ctx, CgScreenTexture scene) {
        if (!CgGraphicsSettings.ANTI_ALIASING.get().atLeast(CgQuality.HIGH)) return;
        if (measure == null) measure = CgCompute.load(FLICKER).kernel("Measure");
        CgViewState state = ctx.view().state();
        int w = scene.width(), h = scene.height();
        CgGraphTexture luma = state.history(FLICKER_LUMA, new CgTextureDesc(w, h, CgFormat.R8_UNORM), ctx.recording());
        CgComputePass pass = ctx.recording().compute("tsr.flicker").timed(GPU_FLICKER);
        pass.dispatch(measure, w, h, 1).image("LUMA", luma).texture("_Scene", scene.texture());
        pass.end();
        measuredFrame = state.frameIndex();
    }

    @Override
    public CgScreenTexture apply(CgPostContext ctx, CgScreenTexture input) {
        if (clear == null) {
            clear = CgCompute.load(DILATE).kernel("Clear");
            dilate = CgCompute.load(DILATE).kernel("Dilate");
            decimate = CgCompute.load(DECIMATE).kernel("Decimate");
            reject = CgCompute.load(REJECT).kernel("Reject");
            rejectSpatial = CgCompute.load(REJECT).kernel("RejectSpatial");
            rejectFlicker = CgCompute.load(REJECT).kernel("RejectFlicker");
            spatial = CgCompute.load(SPATIAL).kernel("Spatial");
            update = CgCompute.load(UPDATE).kernel("Update");
            updateSpatial = CgCompute.load(UPDATE).kernel("UpdateSpatial");
            sharpen = CgCompute.load(SHARPEN).kernel("Sharpen");
            resolve = CgCompute.load(RESOLVE).kernel("Resolve");
            snapshot = CgCompute.load(SNAPSHOT).kernel("Snapshot");
        }
        CgQuality quality = CgGraphicsSettings.ANTI_ALIASING.get();
        boolean antiAliasSpatially = quality.atLeast(CgQuality.MEDIUM);
        CgView view = ctx.view();
        CgViewState state = view.state();
        boolean flicker = quality.atLeast(CgQuality.HIGH) && measuredFrame == state.frameIndex();
        boolean field = quality.atLeast(CgQuality.HIGH);
        // Below the frame rate cap a frame spans more of the period: the flicker period and parallax scale with it
        long now = System.nanoTime();
        float refresh = lastNanos == 0L ? 1f : (now - lastNanos) * 1e-9f * FLICKER_FRAME_RATE_CAP;
        lastNanos = now;
        CgRecording recording = ctx.recording();
        int w = input.width(), h = input.height(), ow = Math.max(w, view.outputWidth()), oh = Math.max(h, view.outputHeight());
        size(w, h, ow, oh);
        // Ultra keeps the history at twice the output size (r.TSR.History.ScreenPercentage 200), resolved down after
        int scale = quality.atLeast(CgQuality.ULTRA) ? 2 : 1, hw = ow * scale, hh = oh * scale;
        CgGraphTexture colorHistory = state.history(COLOR, new CgTextureDesc(hw, hh, CgFormat.RG11B10_UFLOAT), recording);
        CgGraphTexture validHistory = state.history(VALID, new CgTextureDesc(hw, hh, CgFormat.R8_UNORM), recording);
        CgGraphTexture guideHistory = state.history(GUIDE, new CgTextureDesc(w, h, CgFormat.RGB10A2_UNORM), recording);
        CgGraphTexture moireHistory = flicker ? state.history(MOIRE, new CgTextureDesc(w, h, CgFormat.RGBA8_UNORM), recording) : null;
        CgGraphTexture flickerLuma = flicker ? state.history(FLICKER_LUMA, new CgTextureDesc(w, h, CgFormat.R8_UNORM), recording) : null;
        boolean cut = !view.historyValid() || state.fresh(COLOR) || state.fresh(VALID) || state.fresh(GUIDE);
        float jx = view.jitterTexelsX(), jy = view.jitterTexelsY(), cutFlag = cut ? 1f : 0f;
        int frame = state.frameIndex();
        clipToPrevious(view);
        // History resurrection reprojects to the older snapshot, once two are kept
        boolean resurrect = quality.atLeast(CgQuality.MEDIUM) && state.keepsHistory();
        Snapshots snapshots = state.get(SNAPSHOTS);
        CgGraphTexture snapshotColor = null, snapshotValid = null, snapshotGuide = null;
        boolean canResurrect = false;
        if (resurrect) {
            snapshotColor = state.history(SNAPSHOT_COLOR, new CgTextureDesc(hw, hh, CgFormat.RG11B10_UFLOAT), recording);
            snapshotValid = state.history(SNAPSHOT_VALID, new CgTextureDesc(hw, hh, CgFormat.R8_UNORM), recording);
            snapshotGuide = state.history(SNAPSHOT_GUIDE, new CgTextureDesc(w, h, CgFormat.RGB10A2_UNORM), recording);
            snapshots.track(snapshotColor, snapshotGuide);
            canResurrect = !cut && snapshots.taken >= 2;
            if (canResurrect) snapshots.clipToOlder(view, clipToResurrection);
        }
        Matrix4f projection = view.camera().projection();
        float depthX = projection.m32(), depthY = projection.m22(), radius = 1f / (projection.m00() * w);
        CgGraphTexture velocity = ctx.scene().velocity(), responsive = ctx.scene().responsive();

        CgComputePass pass = recording.compute("tsr.dilate").timed(GPU_DILATE);
        pass.dispatch(clear, w, h, 1).image("ATOMICS", atomics);
        CgDispatch d = pass.dispatch(dilate, w, h, 1).image("ATOMICS", atomics).image("CLOSEST", closest)
                .image("DILATE", dilateMask).image("VECTOR", vector).image("MOVING", moving).image("BOUNDARY", boundary)
                .image("JACOBIAN", jacobian).texture("_SceneDepth", ctx.depth().sampledDepth());
        if (velocity != null) d.texture("_Velocity", velocity);
        frameValues(d, w, h, jx, jy, cutFlag, depthX, depthY, radius);
        if (flicker) flickerValues(d, view, w, refresh);
        if (field) {
            float speed = Math.max(FIELD_PIXEL_SPEED / ((float) w / ow), 1f / 64f);
            d.set("_Field", 1f, speed * speed, 0f, 0f);
        }
        pass.end();

        // The guide and moire histories are written by the reject pass, after this one: sampled now, their newest version
        // is last frame's (previous() would be the frame before)
        pass = recording.compute("tsr.decimate").timed(GPU_DECIMATE);
        d = pass.dispatch(decimate, w, h, 1).image("GUIDE", reprojected).image("FILLED", filled).image("MASK", mask)
                .texture("_PrevGuide", guideHistory).texture("_Closest", closest).texture("_Dilate", dilateMask)
                .texture("_Vector", vector).texture("_Atomics", atomics).image("MOIRE", reprojectedMoire)
                .set("_Frame", frame, 0f, 0f, 0f);
        if (responsive != null) d.texture("_Responsive", responsive);
        if (flicker) d.texture("_PrevMoire", moireHistory).set("_Flicker", 1f, state.fresh(MOIRE) ? 1f : 0f, 0f, 0f);
        d.image("RESURRECTED", resurrected);
        if (canResurrect) {
            d.texture("_ResurrectionGuide", snapshotGuide.previous()).set("_Resurrect", 1f, 0f, 0f, 0f);
            columns(d, "_ClipToResurrection", clipToResurrection);
        }
        frameValues(d, w, h, jx, jy, cutFlag, depthX, depthY, radius);
        pass.end();

        float fraction = (float) w / ow;
        float least = 1f / (1f + HISTORY_SAMPLES / (fraction * fraction));
        int stride = TILE - 2 * TILE_BORDER;
        CgSeparateTranslucency apart = view.read(CgSeparateTranslucency.KEY);
        CgGraphTexture translucency = apart != null ? apart.target() : null;
        pass = recording.compute("tsr.reject").timed(GPU_REJECT);
        d = pass.dispatchGroups(flicker ? rejectFlicker : antiAliasSpatially ? rejectSpatial : reject, (w + stride - 1) / stride,
                        (h + stride - 1) / stride, 1).image("GUIDE", guideHistory)
                .image("REJECTION", rejection).image("COLOR", color).texture("_Color", input.texture())
                .texture("_Guide", reprojected).texture("_Mask", mask).set("_Input", w, h, 1f / w, 1f / h)
                .set("_Jitter", jx, jy, cutFlag, 0f).set("_Frame", frame, 0f, 0f, 0f)
                .set("_Reject", least, TILE_BORDER, stride, translucency != null ? 1f : 0f);
        if (antiAliasSpatially) d.image("LUMA", luma).image("AA_MASK", aaMask);
        if (translucency != null) d.texture("_Translucency", translucency);
        if (canResurrect) d.texture("_Resurrected", resurrected).set("_Resurrect", 1f, 0f, 0f, 0f);
        if (flicker) {
            float period = FLICKER_PERIOD / Math.max(refresh, 1f);
            d.image("MOIRE", moireHistory).texture("_MoireLuma", flickerLuma).texture("_PrevMoire", reprojectedMoire)
                    .texture("_Moving", moving).set("_Flicker", 1f, 1f - (float) Math.pow(0.95, period), 0f, 0f);
        }
        pass.end();

        if (antiAliasSpatially) {
            int steps = quality.atLeast(CgQuality.ULTRA) ? SPATIAL_STEPS_ULTRA : SPATIAL_STEPS;
            pass = recording.compute("tsr.spatial").timed(GPU_SPATIAL);
            pass.dispatch(spatial, w, h, 1).image("OFFSET", offsets).texture("_Luma", luma).texture("_AAMask", aaMask)
                    .set("_Input", w, h, 1f / w, 1f / h).set("_Spatial", steps, 0f, 0f, 0f);
            pass.end();
        }

        // A history pixel 1/scale^2 the output's area holds that share of the samples: its count and the rejected and
        // moving counts scale down with it
        float samples = HISTORY_SAMPLES / (float) (scale * scale), toHistory = (float) hw / w, hysteresis = 1f / samples;
        pass = recording.compute("tsr.update").timed(GPU_UPDATE);
        d = pass.dispatch(antiAliasSpatially ? updateSpatial : update, hw, hh, 1).image("COLOR", colorHistory).image("VALID", validHistory).image("PICTURE", picture)
                .texture("_Color", color).texture("_Rejection", rejection).texture("_Vector", filled)
                .texture("_PrevColor", colorHistory.previous()).texture("_PrevValid", validHistory.previous())
                .set("_Input", w, h, 1f / w, 1f / h).set("_Output", hw, hh, 1f / hw, 1f / hh)
                .set("_Jitter", jx, jy, cutFlag, 0f).set("_Frame", frame, 0f, 0f, 0f)
                .set("_History", samples, hysteresis, 1f - REJECTED_SAMPLES / (float) (scale * scale) * hysteresis, toHistory)
                .set("_Motion", Math.max(0f, Math.min(1f, 1f - MOTION_SAMPLES * hysteresis)), 1f / (MOTION_PIXEL_SPEED * scale), 0f, 0f);
        if (antiAliasSpatially) d.texture("_Offset", offsets);
        if (field) d.texture("_Boundary", boundary).texture("_Jacobian", jacobian).set("_Field", 1f, 0f, 0f, 0f);
        // The look weights resolved beside the colour, as Unreal's TSR resolves scene alpha
        CgGraphTexture weights = view.read(CgLookWeights.KEY), lookHistory = null;
        if (weights != null) {
            lookHistory = state.history(LOOK, new CgTextureDesc(hw, hh, CgFormat.RGBA8_UNORM), recording);
            d.image("LOOK", lookHistory).texture("_LookWeights", weights).texture("_PrevLook", lookHistory.previous())
                    .set("_Look", 1f, cut || state.fresh(LOOK) ? 1f : 0f, 0f, 0f);
        } else {
            d.image("LOOK", noLook);
        }
        if (canResurrect) {
            d.texture("_Closest", closest).texture("_ResColor", snapshotColor.previous())
                    .texture("_ResValid", snapshotValid.previous()).set("_Resurrect", 1f, 0f, 0f, 0f);
            columns(d, "_ClipToResurrection", clipToResurrection);
        }
        float sharpness = CgTemporalAA.sharpness();
        d.set("_Options", sharpness > 0f || scale > 1 ? 0f : 1f, quality.atLeast(CgQuality.HIGH) ? 1f : 0f, 0f, 0f);
        pass.end();

        if (resurrect && !cut && frame % RESURRECTION_PERIOD == 0) {
            pass = recording.compute("tsr.snapshot").timed(GPU_SNAPSHOT);
            pass.dispatch(snapshot, hw, hh, 1).image("RES_COLOR", snapshotColor).image("RES_VALID", snapshotValid)
                    .image("RES_GUIDE", snapshotGuide).texture("_Color", colorHistory).texture("_Valid", validHistory)
                    .texture("_Guide", guideHistory);
            pass.end();
            snapshots.take(view);
        }

        CgGraphTexture output = colorHistory;
        if (scale > 1) {
            output = sharpness > 0f ? resolved : picture;
            pass = recording.compute("tsr.resolve").timed(GPU_RESOLVE);
            pass.dispatch(resolve, ow, oh, 1).image("PICTURE", output).texture("_History", colorHistory);
            pass.end();
        }
        if (lookHistory != null) view.publish(CgLookWeights.RESOLVED, lookHistory);
        if (sharpness > 0f) {
            pass = recording.compute("tsr.sharpen").timed(GPU_SHARPEN);
            pass.dispatch(sharpen, ow, oh, 1).image("PICTURE", picture).texture("_Source", output)
                    .set("_Sharpen", sharpness, 0f, 0f, 0f);
            pass.end();
        }
        int mode = debugMode(CgPostDebug.VIEW.get());
        if (mode > 0) {
            if (visualize == null) visualize = CgCompute.load(VISUALIZE).kernel("Visualize");
            pass = recording.compute("tsr.visualize");
            d = pass.dispatch(visualize, ow, oh, 1).image("VIEW", debugView).texture("_Rejection", rejection)
                    .texture("_Mask", mask).texture("_Valid", validHistory).texture("_Guide", reprojected)
                    .texture("_Filled", filled).set("_Output", ow, oh, 1f / ow, 1f / oh).set("_Mode", mode, 0f, 0f, 0f);
            if (moireHistory != null) d.texture("_Moire", moireHistory);
            pass.end();
            view.publish(DEBUG_VIEW, debugView);
        }
        return CgScreenTexture.of(picture);
    }

    /** The visualize kernel's mode for a {@code crystalgraphics.post.debug} value: 0 where it names no TSR view. */
    public static int debugMode(String view) {
        for (int i = 1; i < DEBUG_VIEWS.length; i++) if (DEBUG_VIEWS[i].equals(view)) return i;
        return 0;
    }

    /** The frame's geometry every pass of the first two reads. */
    private void frameValues(CgDispatch d, int w, int h, float jx, float jy, float cut, float depthX, float depthY, float radius) {
        d.set("_Input", w, h, 1f / w, 1f / h).set("_Jitter", jx, jy, cut, 0f).set("_Depth", depthX, depthY, radius, 0f);
        for (int i = 0; i < 4; i++) {
            clipToPrev.getColumn(i, column);
            d.set("_ClipToPrev" + i, column.x, column.y, column.z, column.w);
        }
    }

    /**
     * The dilate pass's is-moving values: this frame's clip to its camera-relative world, last frame's to the same, the clip
     * to last frame's had the camera only turned, and the parallax speed counting as moving, inverse.
     */
    private void flickerValues(CgDispatch d, CgView view, int w, float refresh) {
        clipToWorld.set(view.camera().projection()).mul(view.camera().view()).invert();
        view.previousViewProjection().invert(prevClipToWorld);
        turnToPrev.set(view.previousTurn()).mul(clipToWorld);
        float maxParallax = Math.max(refresh * FLICKER_MAX_PARALLAX * w / 1920f, 1e-3f);
        d.set("_Flicker", 1f, 1f / maxParallax, 0f, 0f);
        columns(d, "_ClipToWorld", clipToWorld);
        columns(d, "_PrevClipToWorld", prevClipToWorld);
        columns(d, "_TurnToPrev", turnToPrev);
    }

    private void columns(CgDispatch d, String name, Matrix4f m) {
        for (int i = 0; i < 4; i++) {
            m.getColumn(i, column);
            d.set(name + i, column.x, column.y, column.z, column.w);
        }
    }

    /** This frame's unjittered clip space to last frame's: its camera-relative world through last frame's projection. */
    private void clipToPrevious(CgView view) {
        clipToPrev.set(view.camera().projection()).mul(view.camera().view()).invert();
        clipToPrev.mulLocal(view.previousViewProjection());
    }

    /**
     * A view's two snapshots' cameras: unjittered view-projection, camera-relative, and where the camera stood. The older
     * one's is what resurrection reprojects to, its history being the snapshot histories' {@code previous()}.
     */
    private static final class Snapshots {
        final Matrix4f older = new Matrix4f(), newer = new Matrix4f(), scratch = new Matrix4f();
        double olderX, olderY, olderZ, newerX, newerY, newerZ;
        int taken;
        CgGraphTexture color, guide;

        /** Counts from none again where the snapshot histories were made again (a new size or quality). */
        void track(CgGraphTexture color, CgGraphTexture guide) {
            if (color == this.color && guide == this.guide) return;
            this.color = color;
            this.guide = guide;
            taken = 0;
        }

        void take(CgView view) {
            older.set(newer);
            olderX = newerX;
            olderY = newerY;
            olderZ = newerZ;
            newer.set(view.camera().projection()).mul(view.camera().view());
            newerX = view.camera().x();
            newerY = view.camera().y();
            newerZ = view.camera().z();
            taken++;
        }

        /** This frame's unjittered clip space to the older snapshot's, through this frame's camera-relative world. */
        void clipToOlder(CgView view, Matrix4f out) {
            out.set(view.camera().projection()).mul(view.camera().view()).invert();
            scratch.set(older).translate((float) (view.camera().x() - olderX), (float) (view.camera().y() - olderY),
                    (float) (view.camera().z() - olderZ));
            out.mulLocal(scratch);
        }
    }

    /** Its transient targets for an input of {@code w x h} and an output of {@code ow x oh}: made again when they move. */
    private void size(int w, int h, int ow, int oh) {
        if (atomics != null && width == w && height == h && outWidth == ow && outHeight == oh) return;
        width = w;
        height = h;
        outWidth = ow;
        outHeight = oh;
        atomics = transientTexture("cg_tsr_atomics", w, h, CgFormat.R32_UINT);
        closest = transientTexture("cg_tsr_closest", w, h, CgFormat.RG32_FLOAT);
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
        moving = transientTexture("cg_tsr_moving", w, h, CgFormat.R8_UNORM);
        reprojectedMoire = transientTexture("cg_tsr_reprojected_moire", w, h, CgFormat.RGBA8_UNORM);
        boundary = transientTexture("cg_tsr_boundary", w, h, CgFormat.R32_UINT);
        jacobian = transientTexture("cg_tsr_jacobian", w, h, CgFormat.R32_UINT);
        resurrected = transientTexture("cg_tsr_resurrected", w, h, CgFormat.RGB10A2_UNORM);
        noLook = transientTexture("cg_tsr_no_look", 1, 1, CgFormat.RGBA8_UNORM);
        picture = transientTexture("cg_tsr_picture", ow, oh, CgFormat.RGBA16_FLOAT);
        resolved = transientTexture("cg_tsr_resolved", ow, oh, CgFormat.RGBA16_FLOAT);
        debugView = transientTexture("cg_tsr_debug_view", ow, oh, CgFormat.RGBA16_FLOAT);
    }

    private static CgGraphTexture transientTexture(String name, int w, int h, CgFormat format) {
        return CgGraphTexture.transientTexture(name, new CgTextureDesc(w, h, format));
    }
}
