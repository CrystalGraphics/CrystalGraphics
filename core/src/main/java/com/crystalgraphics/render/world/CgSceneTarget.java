package com.crystalgraphics.render.world;

import com.crystalgraphics.api.CgBindingPoints;
import com.crystalgraphics.api.framebuffer.CgFrameBufferFormat;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshTopology;
import com.crystalgraphics.api.state.CgDepthState;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.gl.framebuffer.CgFrameBuffer;
import com.crystalgraphics.render.draw.CgChunkBuilder;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.draw.CgOrder;
import com.crystalgraphics.render.draw.CgPipeline;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgLoad;
import com.crystalgraphics.render.graph.CgRasterPass;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.graph.CgTextureDesc;
import com.crystalgraphics.render.stage.CgFrameKeys;
import com.crystalgraphics.render.stage.CgStageFrame;
import com.crystalgraphics.trace.CgGpuTrace;

import javax.annotation.Nullable;

/**
 * The HDR scene's first pass (render-hdr-scene H1): at the top of {@code WORLD_TRANSPARENT}, while
 * {@link CgWorldRenderer#hdrScene()} is on, decodes the host's colour into an RGBA16F scene beside the host's depth,
 * publishes it as {@link CgFrameKeys#SCENE} and makes it the stage's target, so every renderer after draws into it in
 * linear light. The post stack's composite encodes it back into the host's target.
 *
 * <ul>
 *   <li>A host that lends no depth (framebuffer 0, multisampled) gets a scene with a depth of its own, the host's
 *       copied in by the same pass; draws then write depth into the copy, which the host never sees.</li>
 *   <li>Render thread: the depth probe is a {@code glGet}, once per framebuffer.</li>
 * </ul>
 */
final class CgSceneTarget {

    /** After the post stack blends its volumes (0), before anything draws the world. */
    static final int ORDER = 1;

    private static final String SHADER = "crystalgraphics:shaders/world_scene_in.shader";
    private static final CgFrameBufferFormat SCENE = CgFrameBufferFormat.builder("cg_scene").color(0, CgTextureType.RGBA16F).build();
    private static final CgMesh FULLSCREEN = CgMesh.vertices(3, CgMeshTopology.TRIANGLES);
    private static final int GPU = CgGpuTrace.name("world.sceneIn");

    private CgMaterial material, withDepth;
    private CgPipeline depthPipeline, depthPipelineOf;

    /** The scene's handle, kept while its size and form hold, so a steady frame allocates none. */
    @Nullable
    private CgGraphTexture scene;
    private int width, height;
    @Nullable
    private CgTextureType sceneDepth;
    private boolean sceneBeside;

    /** The last framebuffer whose depth was probed, and its depth. */
    private int probed = -1;
    @Nullable
    private CgTextureType probedDepth;

    void record(CgStageFrame stage) {
        if (!CgWorldRenderer.get().hdrScene()) return;
        int fb = stage.host().mainFramebuffer(), w = Math.max(1, stage.host().width()), h = Math.max(1, stage.host().height());
        boolean beside = CgGraphTexture.takesCurrentDepth(fb);
        CgTextureType depth = null;
        if (!beside) {
            if (fb != probed) {
                probedDepth = CgFrameBuffer.depthTypeOf(fb);
                probed = fb;
            }
            depth = probedDepth;
        }
        CgPipeline pipeline = pipeline(depth != null);
        if (pipeline == null) return;
        CgGraphTexture target = scene(w, h, beside, depth);

        CgRecording recording = stage.recording();
        CgRasterPass pass = recording.raster(target, CgLoad.load(), stage.constants(), null, CgOrder.SORTED).timed(GPU)
                .sceneColor(CgBindingPoints.SCENE_COLOR_TEXTURE_UNIT, CgGraphTexture.current());
        if (depth != null) pass.sceneDepth(CgBindingPoints.DEPTH_TEXTURE_UNIT, CgGraphTexture.current());
        CgMaterial drawn = depth != null ? withDepth : material;
        CgChunkBuilder chunks = recording.chunks().begin();
        chunks.draw(pipeline, drawn.captureBindings(recording.bindings()), FULLSCREEN);
        chunks.instance();
        pass.add(chunks.end());
        pass.end();

        stage.resources().put(CgFrameKeys.SCENE, target);
        stage.retarget(target);
    }

    /** Scene in's pipeline, writing the host's depth too where {@code depth}; null until its shader parses. */
    @Nullable
    private CgPipeline pipeline(boolean depth) {
        if (!depth) {
            if (material == null) material = CgMaterial.newInstance(SHADER);
            return material.pipeline(CgInstanceKind.OBJECT);
        }
        if (withDepth == null) {
            withDepth = CgMaterial.newInstance(SHADER);
            withDepth.enableKeyword("DEPTH");
        }
        CgPipeline base = withDepth.pipeline(CgInstanceKind.OBJECT);
        if (base == null) return null;
        // withState interns by the state's identity: derived once per base pipeline.
        if (base != depthPipelineOf) {
            depthPipeline = base.withState(base.state().withDepth(CgDepthState.TEST_WRITE_ALWAYS));
            depthPipelineOf = base;
        }
        return depthPipeline;
    }

    private CgGraphTexture scene(int w, int h, boolean beside, @Nullable CgTextureType depth) {
        if (scene != null && width == w && height == h && sceneBeside == beside && sceneDepth == depth) return scene;
        if (beside) {
            scene = CgGraphTexture.besideCurrentDepth("cg_scene", new CgTextureDesc(w, h, SCENE));
        } else {
            CgFrameBufferFormat.Builder format = CgFrameBufferFormat.builder("cg_scene_depth").color(0, CgTextureType.RGBA16F);
            if (depth != null) format.depth(depth);
            scene = CgGraphTexture.transientTexture("cg_scene", new CgTextureDesc(w, h, format.build()));
        }
        width = w;
        height = h;
        sceneBeside = beside;
        sceneDepth = depth;
        return scene;
    }

    /** Forgets its materials, which the material registry frees with the context. */
    void release() {
        material = withDepth = null;
        depthPipeline = depthPipelineOf = null;
        scene = null;
        probed = -1;
    }
}
