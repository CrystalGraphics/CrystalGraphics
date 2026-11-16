package com.crystalgraphics.render.look;

import java.util.Arrays;

import javax.annotation.Nullable;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialInstance;
import com.crystalgraphics.material.CgMaterialProxy;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgScreenTexture;
import com.crystalgraphics.render.post.CgPostContext;
import com.crystalgraphics.render.post.CgPostMaterial;
import com.crystalgraphics.render.post.CgPostPoint;
import com.crystalgraphics.render.post.settings.CgPostSettings;

/**
 * A graded look's own post materials on its solids alone: each {@code SURFACES} blendable of a look a primitive is graded
 * by ({@code grade(look)}) grades a copy of the scene, laid back where that look's solid is the visible surface (its id
 * in custom stencil, custom depth at the scene's depth), as an Unreal post material masked by CustomStencil. A game's
 * stage post (Kakarot's character colour) then reaches its characters and never the host's world. The chain's, at its
 * {@code SURFACES} point.
 *
 * <pre>{@code
 * List<CgMaterialDefinition> stage = List.of(kit.material(chara).get());   // kk:Art/BG/Area02/PostProcess/Area02_CharaColor
 * goku.grade(CgVfxKitLook.grade("kk", stage));                              // Kakarot's tone, the arena's character colour
 * }</pre>
 *
 * <ul>
 *   <li>Before anything translucent: a look's translucent draws are not graded by its materials, as Unreal draws a
 *       material at BeforeTranslucency.</li>
 *   <li>A look's materials at any other point draw nowhere: they would grade the whole screen.</li>
 * </ul>
 */
public final class CgLookPost {

    public static final String MASK = "crystalgraphics:shaders/post/look/look_mask.shader";
    private static final String COPY = "crystalgraphics:shaders/post/hold.shader";
    private static final String[] GRADED = {"cg_look_post0", "cg_look_post1", "cg_look_post2", "cg_look_post3"};

    @Nullable
    private static CgMaterialInstance copy;
    private static final CgMaterialInstance[] MASKS = new CgMaterialInstance[CgLookChannels.MAX];

    /** Whether {@code look} has a post material to draw on its solids. */
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

    /** Each look channel's {@code SURFACES} materials over {@code scene} where its solids show. Render thread. */
    public static void record(CgPostContext ctx, CgScreenTexture scene) {
        CgLookChannels looks = ctx.view().read(CgLookChannels.KEY);
        CgGraphTexture custom = ctx.scene().customDepth();
        if (looks == null || custom == null) return;
        for (int k = 0; k < CgLookChannels.MAX; k++) {
            CgPostSettings look = looks.look(k);
            if (!grades(look)) continue;
            materials();
            CgScreenTexture graded = ctx.texture(GRADED[k], scene);
            ctx.screen(copy).from(scene).into(graded);
            for (int i = 0; i < look.blendableCount(); i++) {
                CgPostSettings.Blendable b = look.blendable(i);
                if (b.point() != CgPostPoint.SURFACES) continue;
                CgPostMaterial drawer = b.drawer();
                drawer.weight(b.weight());
                drawer.drawOver(ctx, graded.texture());
            }
            CgMaterialInstance mask = MASKS[k];
            CgMaterialProxy proxy = CgMaterialProxy.of(mask);
            mask.sampler("_Graded", proxy.samplerUnit("_Graded"), graded.texture())
                    .sampler("_CustomDepth", proxy.samplerUnit("_CustomDepth"), custom.sampledDepth())
                    .sampler("_CustomStencil", proxy.samplerUnit("_CustomStencil"), custom.stencil())
                    .set1f("_Look", looks.id(k));
            ctx.screen(mask).from(scene).reads(graded.texture()).reads(custom).over(scene);
        }
    }

    private static void materials() {
        if (copy != null) return;
        copy = CgMaterial.load(COPY).instance();
        for (int k = 0; k < MASKS.length; k++) MASKS[k] = CgMaterial.load(MASK).instance();
    }

    /** Forgets its materials: the registry freed them. At context teardown. */
    public static void releaseAll() {
        copy = null;
        Arrays.fill(MASKS, null);
    }

    private CgLookPost() {
    }
}
