package com.crystalgraphics.render.look;

import javax.annotation.Nullable;

import org.joml.Matrix4f;

import com.crystalgraphics.compute.CgCompute;
import com.crystalgraphics.compute.CgKernel;
import com.crystalgraphics.gpu.resource.CgTextureDesc;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.render.graph.CgComputePass;
import com.crystalgraphics.render.graph.CgDispatch;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgScreenTexture;
import com.crystalgraphics.render.post.CgPostContext;
import com.crystalgraphics.render.scene.renderer.feature.translucency.CgSeparateTranslucency;
import com.crystalgraphics.render.view.CgView;
import com.crystalgraphics.render.view.CgViewKey;
import com.crystalgraphics.trace.CgGpuTrace;

/**
 * The look weights: how much of each pixel is each graded look's, at the size drawn, one channel a look of the firing's
 * {@link CgLookChannels}; what the four leave is the frame's look. A graded solid counts where its look id is in custom
 * depth's stencil and custom depth is the scene's depth; translucent draws by the larger of the cover they laid and
 * their share of the pixel's light. Published as {@link #KEY} for the temporal step, which resolves it beside the colour,
 * and the composite, which tones each channel by its look.
 *
 * <pre>{@code
 * CgGraphTexture solids = lookWeights.solids(ctx, picture);             // before anything moves the colour
 * solids = dof.carry(ctx, ctx.distorted(solids));                       // bent and blurred as the colour was
 * CgGraphTexture weights = lookWeights.record(ctx, picture, layApart, solids);   // after the translucency is composed
 * CgGraphTexture same = ctx.view().read(CgLookWeights.KEY);             // later in the chain; null with no graded look
 * }</pre>
 *
 * <ul>
 *   <li>Nothing is recorded, and nothing published, in a firing with no graded look: the frame's look is everywhere.</li>
 *   <li>The solids' shares are drawn before depth of field and the distortion's bend and carried through both, as
 *       Unreal propagates alpha: a defocused or bent character's soft edge keeps its look.</li>
 *   <li>{@code layApart}: whether the separate translucency is not yet in the picture (an upscaler composes it), so the
 *       pixel's light counts it.</li>
 * </ul>
 */
public final class CgLookWeights {

    /** Where the chain publishes the weights, at the size drawn, a firing with a graded look. */
    public static final CgViewKey<CgGraphTexture> KEY = new CgViewKey<>("crystalgraphics:look_weights");
    /** The weights the temporal step resolved beside the colour, at its output's size; absent without one. */
    public static final CgViewKey<CgGraphTexture> RESOLVED = new CgViewKey<>("crystalgraphics:look_weights_resolved");

    /** The weights the picture now in the chain is at: those the temporal step resolved, else those drawn; null for none. */
    @Nullable
    public static CgGraphTexture current(CgView view) {
        CgGraphTexture resolved = view.read(RESOLVED);
        return resolved != null ? resolved : view.read(KEY);
    }

    private static final String SHADER = "crystalgraphics:shaders/post/look/look_weights.compute";
    private static final int GPU = CgGpuTrace.name("post.look.weights");
    private static final String[] LAYERS = {"_Layer0", "_Layer1", "_Layer2", "_Layer3"},
            APART = {"_Apart0", "_Apart1", "_Apart2", "_Apart3"};

    private CgKernel kernel, solidsKernel;
    private CgGraphTexture weights, solids;
    private final float[] bound = new float[CgLookChannels.MAX];

    /** Starts its kernel compiling, as a loading screen does. Render thread. */
    public static void precache() {
        CgCompute.load(SHADER).kernel("Weights").precache();
        CgCompute.load(SHADER).kernel("Solids").precache();
    }

    /**
     * Each channel's graded solid where it is the visible surface, at {@code picture}'s size: its look id in custom
     * depth's stencil and custom depth at the scene's depth. Null with no graded look or no custom depth this firing.
     */
    @Nullable
    public CgGraphTexture solids(CgPostContext ctx, CgScreenTexture picture) {
        CgView view = ctx.view();
        CgLookChannels looks = view.read(CgLookChannels.KEY);
        CgGraphTexture custom = ctx.scene().customDepth();
        if (looks == null || !looks.any() || custom == null) return null;
        if (solidsKernel == null) solidsKernel = CgCompute.load(SHADER).kernel("Solids");
        int w = picture.width(), h = picture.height();
        if (solids == null || solids.getWidth() != w || solids.getHeight() != h) {
            solids = CgGraphTexture.transientTexture("cg_look_solids", new CgTextureDesc(w, h, CgFormat.RGBA8_UNORM));
        }
        Matrix4f projection = view.camera().projection();
        CgComputePass pass = ctx.recording().compute("look.solids").timed(GPU);
        pass.dispatch(solidsKernel, w, h, 1).image("SOLIDS", solids).texture("_SceneDepth", ctx.depth().sampledDepth())
                .texture("_CustomDepth", custom.sampledDepth()).texture("_CustomStencil", custom.stencil())
                .set("_Depth", projection.m32(), projection.m22(), 0f, 0f)
                .set("_Ids", looks.id(0), looks.id(1), looks.id(2), looks.id(3));
        pass.end();
        return solids;
    }

    /** The weights of {@code picture}'s pixels, published as {@link #KEY}; null with no graded look this firing. */
    @Nullable
    public CgGraphTexture record(CgPostContext ctx, CgScreenTexture picture, boolean layApart, @Nullable CgGraphTexture solids) {
        CgView view = ctx.view();
        CgLookChannels looks = view.read(CgLookChannels.KEY);
        if (looks == null || !looks.any()) return null;
        if (kernel == null) kernel = CgCompute.load(SHADER).kernel("Weights");
        int w = picture.width(), h = picture.height();
        if (weights == null || weights.getWidth() != w || weights.getHeight() != h) {
            weights = CgGraphTexture.transientTexture("cg_look_weights", new CgTextureDesc(w, h, CgFormat.RGBA8_UNORM));
        }
        CgSeparateTranslucency apart = view.read(CgSeparateTranslucency.KEY);
        boolean laid = layApart && apart != null && apart.target() != null;
        CgComputePass pass = ctx.recording().compute("look.weights").timed(GPU);
        CgDispatch d = pass.dispatch(kernel, w, h, 1).image("WEIGHTS", weights).texture("_Scene", picture.texture())
                .set("_Options", laid ? 1f : 0f, solids != null ? 1f : 0f, 0f, 0f);
        if (solids != null) d.texture("_Solids", solids);
        if (laid) d.texture("_Separate", apart.target());
        float[] bound = this.bound;
        for (int k = 0; k < CgLookChannels.MAX; k++) {
            bound[k] = 0f;
            if (looks.layer(k) != null) {
                d.texture(LAYERS[k], looks.layer(k));
                bound[k] += 1f;
            }
            if (apart != null && apart.layer(k) != null) {
                d.texture(APART[k], apart.layer(k));
                bound[k] += 2f;
            }
        }
        d.set("_Bound", bound[0], bound[1], bound[2], bound[3]);
        pass.end();
        view.publish(KEY, weights);
        return weights;
    }
}
