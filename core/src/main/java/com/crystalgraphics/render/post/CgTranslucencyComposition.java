package com.crystalgraphics.render.post;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialInstance;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgScreenTexture;
import com.crystalgraphics.render.scene.renderer.feature.translucency.CgSeparateTranslucency;

/**
 * The chain's step after {@code TRANSLUCENT}: lays the firing's separate translucency ({@link CgSeparateTranslucency})
 * over the scene. Unreal's {@code FTranslucencyComposition} ({@code ComposeSeparateTranslucency.usf}): the scene times
 * what the translucency left of it plus the light it added. A graded look's separate translucency is in the scene's like
 * any other; the look weights read its own layer for its share.
 */
final class CgTranslucencyComposition {

    static final String SHADER = "crystalgraphics:shaders/post/translucency_compose.shader";

    private CgMaterialInstance material;

    /** Over {@code scene} unless the temporal upscaler lays it itself ({@code overScene} false). */
    void compose(CgPostContext ctx, CgScreenTexture scene, boolean overScene) {
        CgSeparateTranslucency apart = ctx.view().read(CgSeparateTranslucency.KEY);
        if (apart == null || !overScene || apart.target() == null) return;
        if (material == null) material = CgMaterial.load(SHADER).instance();
        over(ctx, scene, apart.target());
    }

    private void over(CgPostContext ctx, CgScreenTexture target, CgGraphTexture translucency) {
        material.sampler("_Translucency", 0, translucency);
        ctx.screen(material).name("translucencyComposition.into").from(target).reads(translucency).over(target);
    }
}
