package com.crystalgraphics.render.post.dof;

import org.joml.Matrix4f;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialInstance;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgScreenTexture;
import com.crystalgraphics.render.post.CgPostContext;
import com.crystalgraphics.render.post.settings.CgPostSettings;
import com.crystalgraphics.render.view.CgView;

/**
 * The post chain's depth of field: a lens's blur by distance from its focus, from a view's blended settings
 * ({@code depthOfField...}), between the {@code TRANSLUCENT} point and the separate translucency laid over the scene,
 * so what is drawn {@code AfterDOF} stays sharp. Engine side; a consumer sets the settings.
 *
 * <pre>{@code
 * CgPost.client().camera().depthOfField(6f, 1.4f);         // focused 6 blocks out, at f/1.4
 * scene.postVolume(new CgPostSettings().depthOfFieldFocalDistance(2f)).spawn();   // a volume pulling focus
 * }</pre>
 *
 * <p>Its steps, at half size: the scene and each texel's signed circle of confusion; the largest radii of each 8 by 8
 * tile, dilated so a blur's reach crosses into its neighbours; the farther and nearer layers gathered on rings shaped
 * by the aperture's blades; then at full size, the sharp scene with the farther layer where it blurs and the nearer
 * over both by its coverage.</p>
 *
 * <ul>
 *   <li>The radius follows the thin lens: the focal length the view's horizontal field of view gives on the settings'
 *       sensor, the aperture that over the f-stop, the focus distance in the host's units (metres). Each layer's
 *       radius is held to 2.5% of the view's width.</li>
 *   <li>Off on an orthographic view, a view with no linear HDR scene, or with its show flag off; off while the focus
 *       is 0 or nearer than the lens.</li>
 * </ul>
 */
public final class CgDepthOfField {

    private static final String DIR = "crystalgraphics:shaders/post/dof/";
    /** Every pass's shader: precached with the chain's. */
    public static final String[] SHADERS = {DIR + "dof_setup.shader", DIR + "dof_tiles.shader", DIR + "dof_dilate.shader",
            DIR + "dof_gather.shader", DIR + "dof_recombine.shader"};
    /** A layer's largest radius, a share of the view's width. */
    private static final float MAX_RADIUS = 0.025f;
    /** The largest radius the dilation and the gather's rings reach, in half-size texels: four tiles of 8. */
    private static final float MAX_HALF_RADIUS = 32f;
    private static final int TILE = 8;

    private CgMaterialInstance setup, tiles, dilate, far, near, recombine;
    private CgMaterialInstance setupCarry, farCarry, nearCarry, recombineCarry;
    private final Matrix4f projection = new Matrix4f();
    /** The last apply's blur, which {@link #carry} repeats; {@code blurred} false where it blurred nothing. */
    private boolean blurred;
    private CgScreenTexture lastInput, lastHalf, lastDilated, lastFar, lastNear;
    private final float[] lastCoc = new float[4], lastShape = new float[2];

    /** Whether it blurs this firing: the settings focus, the view is a lens's, and it may. */
    public boolean shows(CgPostContext ctx) {
        CgPostSettings s = ctx.settings();
        CgView view = ctx.view();
        return s.depthOfFieldOn() && view.showFlags().depthOfField() && view.textures().linear()
                && !view.camera().isOrthographic();
    }

    /** Blurs {@code input} by depth into a texture of its own and answers it; {@code input} itself where nothing blurs. */
    public CgScreenTexture apply(CgPostContext ctx, CgScreenTexture input) {
        blurred = false;
        CgPostSettings s = ctx.settings();
        int width = input.width();
        projection.set(ctx.view().camera().projection());
        float sensor = s.depthOfFieldSensorWidth();
        // The lens whose horizontal view the projection has, on this sensor: millimetres, as the aperture
        float focal = 0.5f * sensor * projection.m00();
        float focus = s.depthOfFieldFocalDistance() * 1000f;
        if (focus <= focal) return input;
        float aperture = focal / s.depthOfFieldFstop();
        // The radius a point at infinity blurs to on the sensor, in half-size texels
        float infinity = 0.5f * aperture * focal / (focus - focal) / sensor * width * 0.5f;
        float most = Math.min(MAX_RADIUS * width * 0.5f, MAX_HALF_RADIUS);
        if (infinity < 0.05f) return input;
        load();
        float[] coc = {infinity, s.depthOfFieldFocalDistance(), most, most};
        float blades = s.depthOfFieldBladeCount();
        // Wide open at the lens's least f-stop, its blades drawn in as it closes
        float polygon = Math.max(0f, Math.min(1f, 1f - s.depthOfFieldMinFstop() / s.depthOfFieldFstop()));
        setup.vec4("_Coc", coc[0], coc[1], coc[2], coc[3]);
        recombine.vec4("_Coc", coc[0], coc[1], coc[2], coc[3]);
        far.vec4("_Shape", blades, polygon, 0f, 0f).vec4("_Layer", 0f, 0f, 0f, 0f);
        near.vec4("_Shape", blades, polygon, 0f, 0f).vec4("_Layer", 1f, 0f, 0f, 0f);

        int hw = (width + 1) / 2, hh = (input.height() + 1) / 2;
        CgScreenTexture half = ctx.texture("cg.dof.half", hw, hh, CgFormat.RGBA16_FLOAT);
        ctx.screen(setup).name("dof.setup").from(input).reads(ctx.depth()).into(half);
        int tw = (hw + TILE - 1) / TILE, th = (hh + TILE - 1) / TILE;
        CgScreenTexture tileMax = ctx.texture("cg.dof.tiles", tw, th, CgFormat.RG16_FLOAT);
        tiles.sampler("_Half", 0, half.texture());
        ctx.screen(tiles).name("dof.tiles").reads(half.texture()).into(tileMax);
        CgScreenTexture dilated = ctx.texture("cg.dof.dilated", tw, th, CgFormat.RG16_FLOAT);
        dilate.sampler("_Tiles", 0, tileMax.texture()).vec4("_Reach", (float) Math.ceil(most / TILE), 0f, 0f, 0f);
        ctx.screen(dilate).name("dof.dilate").reads(tileMax.texture()).into(dilated);
        CgScreenTexture farLayer = gather(ctx, far, "cg.dof.far", half, dilated, hw, hh);
        CgScreenTexture nearLayer = gather(ctx, near, "cg.dof.near", half, dilated, hw, hh);
        CgScreenTexture out = ctx.texture("cg.dof.out", input);
        recombine.sampler("_Far", 0, farLayer.texture()).sampler("_Near", 1, nearLayer.texture());
        blurred = true;
        lastInput = input;
        lastHalf = half;
        lastDilated = dilated;
        lastFar = farLayer;
        lastNear = nearLayer;
        System.arraycopy(coc, 0, lastCoc, 0, 4);
        lastShape[0] = blades;
        lastShape[1] = polygon;
        return ctx.screen(recombine).name("dof.recombine").from(input).reads(ctx.depth()).reads(farLayer.texture())
                .reads(nearLayer.texture()).into(out);
    }

    /**
     * {@code beside} blurred as the last {@link #apply} blurred the colour, this firing: the same texels chosen, the same
     * samples weighed the same, so a channel riding beside the colour (the look weights) stays where its colour went, as
     * Unreal's depth of field propagates alpha. {@code beside} itself where that apply blurred nothing.
     *
     * <pre>{@code
     * picture = dof.apply(ctx, picture);
     * solids = dof.carry(ctx, solids);   // after apply, before the next firing
     * }</pre>
     */
    public CgGraphTexture carry(CgPostContext ctx, CgGraphTexture beside) {
        if (!blurred || beside == null || beside.desc() == null) return beside;
        if (setupCarry == null) {
            setupCarry = CgMaterial.load(SHADERS[0]).instance().vec4("_Carry", 1f, 0f, 0f, 0f);
            farCarry = CgMaterial.load(SHADERS[3]).instance().vec4("_Carry", 1f, 0f, 0f, 0f);
            nearCarry = CgMaterial.load(SHADERS[3]).instance().vec4("_Carry", 1f, 0f, 0f, 0f);
            recombineCarry = CgMaterial.load(SHADERS[4]).instance().vec4("_Carry", 1f, 0f, 0f, 0f);
        }
        float[] coc = lastCoc;
        int hw = lastHalf.width(), hh = lastHalf.height();
        setupCarry.vec4("_Coc", coc[0], coc[1], coc[2], coc[3]).sampler("_Carried", 0, beside);
        CgScreenTexture half = ctx.texture("cg.dof.halfCarry", hw, hh, CgFormat.RGBA16_FLOAT);
        ctx.screen(setupCarry).name("dof.setupCarry").from(lastInput).reads(ctx.depth()).reads(beside).into(half);
        CgScreenTexture farLayer = gatherCarry(ctx, farCarry, "cg.dof.farCarry", 0f, half, hw, hh);
        CgScreenTexture nearLayer = gatherCarry(ctx, nearCarry, "cg.dof.nearCarry", 1f, half, hw, hh);
        CgFormat format = beside.desc().format();
        CgScreenTexture out = ctx.texture("cg.dof.carried", lastInput.width(), lastInput.height(),
                format != null ? format : CgFormat.RGBA8_UNORM);
        recombineCarry.vec4("_Coc", coc[0], coc[1], coc[2], coc[3]).sampler("_Far", 0, lastFar.texture())
                .sampler("_Near", 1, lastNear.texture()).sampler("_Carried", 2, beside)
                .sampler("_FarCarry", 3, farLayer.texture()).sampler("_NearCarry", 4, nearLayer.texture());
        return ctx.screen(recombineCarry).name("dof.recombineCarry").from(lastInput).reads(ctx.depth()).reads(beside)
                .reads(lastFar.texture()).reads(lastNear.texture()).reads(farLayer.texture()).reads(nearLayer.texture())
                .into(out).texture();
    }

    private CgScreenTexture gatherCarry(CgPostContext ctx, CgMaterialInstance layer, String name, float near,
                                        CgScreenTexture half, int hw, int hh) {
        CgScreenTexture out = ctx.texture(name, hw, hh, CgFormat.RGBA16_FLOAT);
        layer.vec4("_Shape", lastShape[0], lastShape[1], 0f, 0f).vec4("_Layer", near, 0f, 0f, 0f)
                .sampler("_Half", 0, lastHalf.texture()).sampler("_Tiles", 1, lastDilated.texture())
                .sampler("_HalfCarry", 2, half.texture());
        return ctx.screen(layer).name(name.substring(3)).reads(lastHalf.texture()).reads(lastDilated.texture())
                .reads(half.texture()).into(out);
    }

    private static CgScreenTexture gather(CgPostContext ctx, CgMaterialInstance layer, String name, CgScreenTexture half,
                                          CgScreenTexture tiles, int hw, int hh) {
        CgScreenTexture out = ctx.texture(name, hw, hh, CgFormat.RGBA16_FLOAT);
        layer.sampler("_Half", 0, half.texture()).sampler("_Tiles", 1, tiles.texture());
        return ctx.screen(layer).name(name.substring(3)).reads(half.texture()).reads(tiles.texture()).into(out);
    }

    private void load() {
        if (setup != null) return;
        setup = CgMaterial.load(SHADERS[0]).instance();
        tiles = CgMaterial.load(SHADERS[1]).instance();
        dilate = CgMaterial.load(SHADERS[2]).instance();
        far = CgMaterial.load(SHADERS[3]).instance();
        near = CgMaterial.load(SHADERS[3]).instance();
        recombine = CgMaterial.load(SHADERS[4]).instance();
    }
}
