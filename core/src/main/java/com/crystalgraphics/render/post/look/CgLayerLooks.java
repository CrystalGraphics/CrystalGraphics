package com.crystalgraphics.render.post.look;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialInstance;
import com.crystalgraphics.material.CgMaterialProxy;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgScreenTexture;
import com.crystalgraphics.render.post.CgPostContext;
import com.crystalgraphics.render.post.CgPostMaterial;
import com.crystalgraphics.render.post.CgPostPoint;
import com.crystalgraphics.render.post.settings.CgPostSettings;
import com.crystalgraphics.render.scene.renderer.feature.translucency.CgGradeLayers;

import javax.annotation.Nullable;

/**
 * A graded look's own post materials, drawn on its draws alone: each {@code SURFACES} blendable of a graded layer's look
 * ({@code CgPrimitiveComponent.grade(look)}) grades the scene where that layer covers it, and the layer itself, before
 * anything translucent. A game's stage post (Kakarot's character colour) then reaches its characters and never the host's
 * world. The chain's, at its {@code SURFACES} point, after the layers' solids are drawn.
 *
 * <pre>{@code
 * CgPostSettings stage = CgPostBlock.settings(stagePost);           // its tonemapper, bloom and character colour
 * goku.grade(stage);                                                 // drawn in its look, the world in the frame's
 * }</pre>
 *
 * <ul>
 *   <li>The scene and the layer are graded alike, so the composite's and bloom's removal of the layer's light from the
 *       frame stays exact: what an outline drew over the layer's pixels is graded with them.</li>
 *   <li>Only a single-pass material draws here; one of several steps draws nothing.</li>
 *   <li>Its light is drawn before the layer's translucent rows, as Unreal draws a material before translucency.</li>
 * </ul>
 */
public final class CgLayerLooks {

    public static final String UNPREMULTIPLY = "crystalgraphics:shaders/post/layer_unpremultiply.shader";
    public static final String PREMULTIPLY = "crystalgraphics:shaders/post/layer_premultiply.shader";
    public static final String MASK = "crystalgraphics:shaders/post/layer_mask.shader";
    private static final String COPY = "crystalgraphics:shaders/post/hold.shader";
    private static final String[] COLOUR = {"cg_layer_look_colour0", "cg_layer_look_colour1"};
    private static final String[] GRADED = {"cg_layer_look_scene0", "cg_layer_look_scene1"};

    @Nullable
    private static CgMaterialInstance unpremultiply, premultiply, copy;
    private static final CgMaterialInstance[] MASKS = new CgMaterialInstance[CgGradeLayers.MAX];

    /** Whether {@code look} has a post material to draw on its draws. */
    public static boolean grades(@Nullable CgPostSettings look) {
        if (look == null) return false;
        for (int i = 0; i < look.blendableCount(); i++) if (look.blendable(i).point() == CgPostPoint.SURFACES) return true;
        return false;
    }

    /** Whether {@code look}'s post materials read the scene textures they are handed (its shading models, custom data). */
    public static boolean readsScene(@Nullable CgPostSettings look) {
        if (look == null) return false;
        for (int i = 0; i < look.blendableCount(); i++) {
            CgPostSettings.Blendable b = look.blendable(i);
            if (b.point() == CgPostPoint.SURFACES && CgMaterialProxy.of(b.material()).handedScene()) return true;
        }
        return false;
    }

    /**
     * Each graded layer's look's {@code SURFACES} materials over {@code scene} where the layer covers it, and over the
     * layer. Render thread; nothing where no layer's look has one.
     */
    public static void record(CgPostContext ctx, CgScreenTexture scene) {
        CgGradeLayers layers = ctx.view().read(CgGradeLayers.KEY);
        if (layers == null) return;
        for (int k = 0; k < CgGradeLayers.MAX; k++) {
            CgPostSettings look = layers.look(k);
            if (!grades(look)) continue;
            CgGraphTexture layer = layers.layer(k);
            CgScreenTexture cover = CgScreenTexture.of(layer);
            materials();

            // The scene: a copy graded whole, laid back where the layer covers
            CgScreenTexture graded = ctx.texture(GRADED[k], scene);
            ctx.screen(copy).from(scene).into(graded);
            drawLook(ctx, look, graded.texture());
            CgMaterialInstance mask = MASKS[k];
            mask.sampler("_Graded", CgMaterialProxy.of(mask).samplerUnit("_Graded"), graded.texture());
            mask.sampler("_Layer", CgMaterialProxy.of(mask).samplerUnit("_Layer"), layer);
            ctx.screen(mask).from(scene).over(scene);

            // The layer: its light as colour, graded, back as light under its own cover
            CgScreenTexture colour = ctx.texture(COLOUR[k], cover);
            ctx.screen(unpremultiply).from(cover).into(colour);
            drawLook(ctx, look, colour.texture());
            ctx.screen(premultiply).from(colour).over(cover);
        }
    }

    private static void drawLook(CgPostContext ctx, CgPostSettings look, CgGraphTexture over) {
        for (int i = 0; i < look.blendableCount(); i++) {
            CgPostSettings.Blendable b = look.blendable(i);
            if (b.point() != CgPostPoint.SURFACES) continue;
            CgPostMaterial drawer = b.drawer();
            drawer.weight(b.weight());
            drawer.drawOver(ctx, over);
        }
    }

    private static void materials() {
        if (unpremultiply != null) return;
        unpremultiply = CgMaterial.load(UNPREMULTIPLY).instance();
        premultiply = CgMaterial.load(PREMULTIPLY).instance();
        copy = CgMaterial.load(COPY).instance();
        for (int k = 0; k < MASKS.length; k++) MASKS[k] = CgMaterial.load(MASK).instance();
    }

    /** Forgets its materials: the registry freed them. At context teardown. */
    public static void releaseAll() {
        unpremultiply = premultiply = copy = null;
        java.util.Arrays.fill(MASKS, null);
    }

    private CgLayerLooks() {
    }
}
