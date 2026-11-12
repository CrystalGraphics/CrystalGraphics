package com.crystalgraphics.render.post;

import com.crystalgraphics.api.material.CgMaterialInstance;
import com.crystalgraphics.material.CgMaterialProxy;
import java.util.function.Consumer;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshTopology;
import com.crystalgraphics.api.material.CgMaterialValues;
import com.crystalgraphics.render.draw.CgChunkBuilder;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.draw.CgPipeline;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgLoad;
import com.crystalgraphics.render.graph.CgRasterPass;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.world.CgGradeLayers;
import com.crystalgraphics.render.world.CgSeparateTranslucency;

/**
 * Lays the firing's separate translucency ({@link CgSeparateTranslucency}) over the scene, and each graded look's share
 * over that look's layer: Unreal's {@code FTranslucencyComposition} ({@code ComposeSeparateTranslucency.usf}), the scene
 * times what the translucency left of it plus the light it added. The post stack runs it after its
 * {@code BEFORE_SEPARATE_TRANSLUCENCY} effects and before {@code AFTER_WORLD}'s; nothing else calls it.
 *
 * <pre>{@code
 * i = recordAt(CgPostPoint.BEFORE_SEPARATE_TRANSLUCENCY, effects, i);   // colour regions grade the scene alone
 * translucency.record(context);                                         // then the effects go over it
 * }</pre>
 */
final class CgTranslucencyComposition {

    static final String SHADER = "crystalgraphics:shaders/post/translucency_compose.shader";
    private static final CgMesh FULLSCREEN = CgMesh.vertices(3, CgMeshTopology.TRIANGLES);

    private CgMaterialInstance material;
    // What the held binder sets, so binding allocates nothing
    private CgGraphTexture bound;
    private final Consumer<CgMaterialValues> bind = b -> b.sampler("_Translucency", 0, bound);

    void record(CgPostContext post) {
        CgSeparateTranslucency apart = post.resources().get(CgSeparateTranslucency.KEY);
        if (apart == null) return;
        if (material == null) material = CgMaterial.load(SHADER).instance();
        CgPipeline pipeline = CgMaterialProxy.of(material).pipeline(CgInstanceKind.OBJECT);
        if (pipeline == null) return;
        if (apart.target() != null) over(post, pipeline, post.target(), apart.target());
        CgGradeLayers layers = post.resources().get(CgGradeLayers.KEY);
        if (layers == null) return;
        for (int k = 0; k < CgGradeLayers.MAX; k++) {
            if (apart.layer(k) != null && layers.layer(k) != null) over(post, pipeline, layers.layer(k), apart.layer(k));
        }
    }

    private void over(CgPostContext post, CgPipeline pipeline, CgGraphTexture into, CgGraphTexture translucency) {
        CgRecording recording = post.recording();
        bound = translucency;
        bind.accept(material);
        CgRasterPass pass = recording.raster("translucencyComposition.into").color(into, CgLoad.load()).constants(post.constants());
        CgChunkBuilder chunks = recording.chunks().begin();
        chunks.draw(pipeline, CgMaterialProxy.of(material).captureBindings(recording.bindings()), FULLSCREEN);
        CgPostMaterial.weighted(chunks, 1f);
        pass.add(chunks.end());
        pass.end();
    }
}
