package com.crystalgraphics.render.post;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialInstance;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgScreenTexture;
import com.crystalgraphics.render.scene.renderer.feature.translucency.CgGradeLayers;
import com.crystalgraphics.render.scene.renderer.feature.translucency.CgSeparateTranslucency;

/**
 * The chain's step after {@code TRANSLUCENT}: lays the firing's separate translucency ({@link CgSeparateTranslucency})
 * over the scene, and each graded look's share over that look's layer. Unreal's {@code FTranslucencyComposition}
 * ({@code ComposeSeparateTranslucency.usf}): the scene times what the translucency left of it plus the light it added.
 */
final class CgTranslucencyComposition {

    static final String SHADER = "crystalgraphics:shaders/post/translucency_compose.shader";

    private CgMaterialInstance material;

    /** Over {@code scene} unless the temporal upscaler lays it itself ({@code overScene} false), and over each graded layer. */
    void compose(CgPostContext ctx, CgScreenTexture scene, boolean overScene) {
        CgSeparateTranslucency apart = ctx.view().read(CgSeparateTranslucency.KEY);
        if (apart == null) return;
        if (material == null) material = CgMaterial.load(SHADER).instance();
        if (overScene && apart.target() != null) over(ctx, scene, apart.target());
        CgGradeLayers layers = ctx.view().read(CgGradeLayers.KEY);
        if (layers == null) return;
        for (int k = 0; k < CgGradeLayers.MAX; k++) {
            if (apart.layer(k) != null && layers.layer(k) != null) over(ctx, CgScreenTexture.of(layers.layer(k)), apart.layer(k));
        }
    }

    private void over(CgPostContext ctx, CgScreenTexture target, CgGraphTexture translucency) {
        material.sampler("_Translucency", 0, translucency);
        ctx.screen(material).name("translucencyComposition.into").from(target).reads(translucency).over(target);
    }
}
