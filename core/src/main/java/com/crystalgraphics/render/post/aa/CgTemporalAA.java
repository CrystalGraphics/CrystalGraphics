package com.crystalgraphics.render.post.aa;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialInstance;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgScreenTexture;
import com.crystalgraphics.render.post.CgPostContext;
import com.crystalgraphics.render.view.CgView;
import com.crystalgraphics.render.view.CgViewState;
import com.crystalgraphics.gpu.resource.CgTextureDesc;

/**
 * The post chain's temporal anti-aliasing: each frame's picture, drawn through a projection moved a fraction of a texel
 * ({@link CgView#jitter}), blended into the view's history, so edges, dithered masks and thin lines settle to their
 * coverage. After separate translucency is laid over and before {@code SCENE}, so bloom and the looks see the resolved
 * picture. Engine side; a view turns it on.
 *
 * <pre>{@code
 * view.showFlags().temporalAA(true);       // the resolve: settles a dithered mask, leaves an edge as drawn
 * view.jitter(true);                       // and the jitter, which resolves the edges: a view whose projection is ours
 * }</pre>
 *
 * <ul>
 *   <li>The history is fetched by velocity: what the velocity pass wrote, else the camera's motion over the depth.
 *       Asked for when it shows ({@code CgSceneTextures.requestVelocity}).</li>
 *   <li>A view whose last frame is not one of this camera (a cut, a jump, a new size) starts over from this frame.</li>
 *   <li>Off on a view with no linear HDR scene, and off by default on every view. On the client's view, Minecraft's
 *       own draws are not jittered and its moving entities and our particles write no velocity.</li>
 * </ul>
 */
public final class CgTemporalAA {

    /** The resolve's shader, precached with the chain's. */
    public static final String SHADER = "crystalgraphics:shaders/post/aa/taa_resolve.shader";
    /** The copy of the resolved picture back into the history. */
    private static final String COPY = "crystalgraphics:shaders/post/hold.shader";
    private static final CgViewState.HistoryKey HISTORY = new CgViewState.HistoryKey("crystalgraphics:taa");
    /** This frame's least share of the blend, and its share once a texel moves 8 texels a frame. */
    private static final float SHARE = 0.04f, SHARE_MOVING = 0.2f;

    private CgMaterialInstance resolve, copy;

    /** Whether it resolves this firing: the view asks and has a linear scene. */
    public static boolean shows(CgView view) {
        return view.showFlags().temporalAA() && view.textures().linear();
    }

    /** Resolves {@code input} into a texture of its own, which it answers, and keeps a copy as next frame's history. */
    public CgScreenTexture apply(CgPostContext ctx, CgScreenTexture input) {
        if (resolve == null) {
            resolve = CgMaterial.load(SHADER).instance();
            copy = CgMaterial.load(COPY).instance();
        }
        CgView view = ctx.view();
        CgViewState state = view.state();
        CgGraphTexture history = state.history(HISTORY, new CgTextureDesc(input.width(), input.height(), CgFormat.RGBA16_FLOAT),
                ctx.recording());
        boolean blend = !state.fresh(HISTORY) && view.historyValid();
        resolve.sampler("_History", 0, history.previous()).vec4("_Resolve", SHARE, SHARE_MOVING, blend ? 1f : 0f, 0f);
        CgGraphTexture velocity = ctx.scene().velocity();
        if (velocity != null) resolve.sampler("_Velocity", 1, velocity);
        CgScreenTexture out = ctx.texture("cg.taa.out", input.width(), input.height(), CgFormat.RGBA16_FLOAT);
        ctx.screen(resolve).name("taa.resolve").from(input).reads(history.previous()).reads(velocity).reads(ctx.depth()).into(out);
        // The chain may draw over its picture in place: the history keeps a copy of its own
        ctx.screen(copy).name("taa.history").from(out).into(CgScreenTexture.of(history));
        return out;
    }
}
