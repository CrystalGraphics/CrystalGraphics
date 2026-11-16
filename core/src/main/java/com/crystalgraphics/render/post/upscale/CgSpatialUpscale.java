package com.crystalgraphics.render.post.upscale;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialInstance;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshTopology;
import com.crystalgraphics.api.state.CgDepthState;
import com.crystalgraphics.material.CgMaterialProxy;
import com.crystalgraphics.render.draw.CgChunkBuilder;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.draw.CgPassConstants;
import com.crystalgraphics.render.draw.CgPipeline;
import com.crystalgraphics.render.graph.CgDepthAccess;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgLoad;
import com.crystalgraphics.render.graph.CgRasterPass;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.precache.CgPipelineList;
import com.crystalgraphics.trace.CgGpuTrace;

import javax.annotation.Nullable;
import java.util.EnumMap;
import java.util.Map;

/**
 * Engine side: a view's finished picture, drawn below 100%, upscaled into its host's target by its
 * {@link CgUpscaleFilter} (Unreal's {@code PrimaryUpscale}, after the tonemapper). The picture is the current target, the
 * size the world drew at; the host's target is named in the graph.
 *
 * <pre>{@code
 * spatial.record(recording, constants, output, CgUpscaleFilter.CATMULL_ROM, 1280, 720);   // from 1280 x 720 into output
 * }</pre>
 *
 * <ul>
 *   <li>Where the host's target has a depth, the nearest texel's depth is written into it too: what draws after
 *       ({@code OVERLAY}) tests against the world as it would at full size.</li>
 * </ul>
 */
public final class CgSpatialUpscale {

    /** The upscale's shader, its forms precached by {@link #precache}. */
    public static final String SHADER = "crystalgraphics:shaders/post/upscale/spatial_upscale.shader";
    private static final CgMesh FULLSCREEN = CgMesh.vertices(3, CgMeshTopology.TRIANGLES);
    private static final int GPU = CgGpuTrace.name("post.upscale");

    /** A filter's material, with and without the depth write, and its depth-writing pipeline. */
    private static final class Form {
        final CgMaterialInstance colour, depth;
        @Nullable
        CgPipeline depthPipeline, depthPipelineOf;

        Form(CgUpscaleFilter filter) {
            CgMaterial base = CgMaterial.load(SHADER);
            if (filter.keyword() != null) base = base.keyword(filter.keyword(), true);
            colour = base.instance();
            depth = base.keyword("DEPTH", true).instance();
        }

        @Nullable
        CgPipeline pipeline(boolean writesDepth) {
            CgPipeline p = CgMaterialProxy.of(writesDepth ? depth : colour).pipeline(CgInstanceKind.OBJECT);
            if (!writesDepth || p == null) return p;
            if (p != depthPipelineOf) {
                depthPipeline = p.withState(p.state().withDepth(CgDepthState.TEST_WRITE_ALWAYS));
                depthPipelineOf = p;
            }
            return depthPipeline;
        }
    }

    private final Map<CgUpscaleFilter, Form> forms = new EnumMap<>(CgUpscaleFilter.class);

    /** Every filter's forms, with and without depth: what the chain's precache builds. */
    public static void precache(CgPipelineList pipelines) {
        for (CgUpscaleFilter filter : CgUpscaleFilter.values()) {
            String[] colour = filter.keyword() == null ? new String[0] : new String[]{filter.keyword()};
            String[] depth = filter.keyword() == null ? new String[]{"DEPTH"} : new String[]{filter.keyword(), "DEPTH"};
            pipelines.addPass(SHADER, colour);
            pipelines.addPass(SHADER, depth);
        }
    }

    /**
     * Records the upscale of the current target, {@code width} x {@code height}, into {@code output}, which is larger;
     * its depth too where {@code output} has one. Nothing until the filter's program is built.
     */
    public void record(CgRecording recording, CgPassConstants constants, CgGraphTexture output, CgUpscaleFilter filter,
                       int width, int height) {
        boolean writesDepth = output.target() != null && output.target().depth() != null;
        Form form = forms.computeIfAbsent(filter, Form::new);
        CgPipeline pipeline = form.pipeline(writesDepth);
        if (pipeline == null) return;
        CgMaterialInstance material = writesDepth ? form.depth : form.colour;
        float outW = output.getWidth(), outH = output.getHeight();
        material.vec4("_Upscale", width / outW, height / outH, width, height);
        CgRasterPass pass = recording.raster("post.upscale").color(output, CgLoad.load()).constants(constants).timed(GPU)
                .readsColor(CgGraphTexture.current());
        if (writesDepth) pass.depth(output, CgLoad.load(), CgDepthAccess.WRITE).readsDepth(CgGraphTexture.current());
        CgChunkBuilder chunks = recording.chunks().begin();
        chunks.draw(pipeline, CgMaterialProxy.of(material).bindings(), FULLSCREEN);
        chunks.instance();
        pass.add(chunks.end());
        pass.end();
    }
}
