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
 * The HDR scene's way in and, around the opaque pass, its way out. At the top of
 * {@code WORLD_TRANSPARENT}, while {@link CgWorldRenderer#hdrScene()} is on, decodes the host's colour into an RGBA16F
 * scene beside the host's depth, publishes it as {@link CgFrameKeys#SCENE} and makes it the stage's target, so every
 * renderer after draws into it in linear light. The post stack's composite encodes it back into the host's target.
 *
 * <p>An opaque firing with draws of ours draws them into a scene of its own the same way, as Unreal's base pass writes
 * SceneColor: {@link #recordOpaqueIn} before the opaque pass, {@link #recordOpaqueOut} after it, which encodes it back
 * so the host's draws before the transparent stage blend over ours as before, and keeps it. The transparent firing's
 * way in then takes the kept value wherever the host's pixel is still what was encoded there, so an opaque colour past
 * white reaches bloom.</p>
 *
 * <ul>
 *   <li>A host that lends no depth (framebuffer 0, multisampled) gets a scene with a depth of its own, the host's
 *       copied in by the same pass; draws then write depth into the copy, which the opaque firing's way out writes
 *       back.</li>
 *   <li>Render thread: the depth probe is a {@code glGet}, once per framebuffer.</li>
 * </ul>
 */
final class CgSceneTarget {

    /** After the post stack blends its volumes (0), before anything draws the world. */
    static final int ORDER = 1;

    private static final String IN = "crystalgraphics:shaders/world_scene_in.shader";
    private static final String OUT = "crystalgraphics:shaders/world_scene_out.shader";
    private static final CgFrameBufferFormat SCENE = CgFrameBufferFormat.builder("cg_scene").color(0, CgTextureType.RGBA16F).build();
    private static final CgMesh FULLSCREEN = CgMesh.vertices(3, CgMeshTopology.TRIANGLES);
    private static final int GPU = CgGpuTrace.name("world.sceneIn"), GPU_OPAQUE = CgGpuTrace.name("world.opaqueSceneIn"),
            GPU_OUT = CgGpuTrace.name("world.opaqueSceneOut");

    /** One full-screen program, and its form writing depth where a scene has a depth of its own. */
    private static final class Pass {
        private final String shader;
        @Nullable
        private final String keyword;
        private CgMaterial material, withDepth;
        private CgPipeline depthPipeline, depthPipelineOf;

        Pass(String shader, @Nullable String keyword) {
            this.shader = shader;
            this.keyword = keyword;
        }

        CgMaterial material(boolean depth) {
            if (depth) {
                if (withDepth == null) {
                    withDepth = CgMaterial.newInstance(shader);
                    withDepth.enableKeyword("DEPTH");
                    if (keyword != null) withDepth.enableKeyword(keyword);
                }
                return withDepth;
            }
            if (material == null) {
                material = CgMaterial.newInstance(shader);
                if (keyword != null) material.enableKeyword(keyword);
            }
            return material;
        }

        /** Its pipeline, writing depth too where {@code depth}; null until its shader parses. */
        @Nullable
        CgPipeline pipeline(boolean depth) {
            CgPipeline base = material(depth).pipeline(CgInstanceKind.OBJECT);
            if (!depth || base == null) return base;
            // withState interns by the state's identity: derived once per base pipeline.
            if (base != depthPipelineOf) {
                depthPipeline = base.withState(base.state().withDepth(CgDepthState.TEST_WRITE_ALWAYS));
                depthPipelineOf = base;
            }
            return depthPipeline;
        }

        void release() {
            material = withDepth = null;
            depthPipeline = depthPipelineOf = null;
        }
    }

    private final Pass in = new Pass(IN, null), keep = new Pass(IN, "KEEP"), out = new Pass(OUT, null);

    /** The transparent scene's handle, kept while its size and form hold, so a steady frame allocates none. */
    @Nullable
    private CgGraphTexture scene;
    private int width, height;
    @Nullable
    private CgTextureType sceneDepth;
    private boolean sceneBeside;

    /** The opaque firing's scene, kept from its firing to the transparent one's, and its size and form. */
    @Nullable
    private CgGraphTexture opaque;
    private int opaqueWidth, opaqueHeight;
    @Nullable
    private CgTextureType opaqueDepth;
    private boolean opaqueBeside;
    /** Whether the opaque firing drew into {@link #opaque} since the last transparent firing's way in. */
    private boolean kept;

    /** The last framebuffer whose depth was probed, and its depth. */
    private int probed = -1;
    @Nullable
    private CgTextureType probedDepth;

    void record(CgStageFrame stage) {
        boolean keeping = kept;
        kept = false;
        if (!CgWorldRenderer.get().hdrSceneDrawn()) return;
        int fb = stage.host().mainFramebuffer(), w = Math.max(1, stage.host().width()), h = Math.max(1, stage.host().height());
        boolean beside = CgGraphTexture.takesCurrentDepth(fb);
        CgTextureType depth = beside ? null : depthOf(fb);
        // The kept scene of another size or form is a stale one: decoded as though none were kept
        keeping &= opaque != null && opaqueWidth == w && opaqueHeight == h && opaqueBeside == beside && opaqueDepth == depth;
        Pass pass = keeping ? keep : in;
        CgPipeline pipeline = pass.pipeline(depth != null);
        if (pipeline == null) return;
        CgGraphTexture target = scene(w, h, beside, depth);

        CgRecording recording = stage.recording();
        CgRasterPass raster = recording.raster(target, CgLoad.load(), stage.constants(), null, CgOrder.SORTED).timed(GPU)
                .sceneColor(CgBindingPoints.SCENE_COLOR_TEXTURE_UNIT, CgGraphTexture.current());
        if (depth != null) raster.sceneDepth(CgBindingPoints.DEPTH_TEXTURE_UNIT, CgGraphTexture.current());
        int bindings = pass.material(depth != null).captureBindings(recording.bindings());
        if (keeping) bindings = recording.bindings().withTexture(bindings, 0, opaque);
        fullscreen(recording, raster, pipeline, bindings);

        stage.resources().put(CgFrameKeys.SCENE, target);
        stage.retarget(target);
    }

    /**
     * Before the opaque pass of a firing drawing something of ours, while the HDR scene is drawn: decodes the host's
     * colour into the kept opaque scene and makes it the stage's target. False, changing nothing, until its programs
     * are built; then the firing draws onto the host's target as before.
     */
    boolean recordOpaqueIn(CgStageFrame stage) {
        int fb = stage.host().mainFramebuffer(), w = Math.max(1, stage.host().width()), h = Math.max(1, stage.host().height());
        boolean beside = CgGraphTexture.takesCurrentDepth(fb);
        CgTextureType depth = beside ? null : depthOf(fb);
        CgPipeline decode = in.pipeline(depth != null), encode = out.pipeline(depth != null);
        // Both or neither: a scene drawn into and never encoded back would lose the opaque draws
        if (decode == null || encode == null || keep.pipeline(depth != null) == null) return false;
        CgRecording recording = stage.recording();
        CgGraphTexture target = opaque(recording, w, h, beside, depth);
        CgRasterPass raster = recording.raster(target, CgLoad.load(), stage.constants(), null, CgOrder.SORTED).timed(GPU_OPAQUE)
                .sceneColor(CgBindingPoints.SCENE_COLOR_TEXTURE_UNIT, CgGraphTexture.current());
        if (depth != null) raster.sceneDepth(CgBindingPoints.DEPTH_TEXTURE_UNIT, CgGraphTexture.current());
        fullscreen(recording, raster, decode, in.material(depth != null).captureBindings(recording.bindings()));
        stage.retarget(target);
        return true;
    }

    /** After the opaque pass: the opaque scene encoded back into the host's target, kept for the transparent firing. */
    void recordOpaqueOut(CgStageFrame stage) {
        stage.retarget(null);
        boolean depth = opaqueDepth != null;
        CgPipeline encode = out.pipeline(depth);
        CgRecording recording = stage.recording();
        CgRasterPass raster = recording.raster(CgGraphTexture.current(), CgLoad.load(), stage.constants(), null,
                CgOrder.SORTED).timed(GPU_OUT);
        if (depth) raster.sceneDepth(CgBindingPoints.DEPTH_TEXTURE_UNIT, opaque);
        int bindings = recording.bindings().withTexture(out.material(depth).captureBindings(recording.bindings()), 0, opaque);
        fullscreen(recording, raster, encode, bindings);
        kept = true;
    }

    private static void fullscreen(CgRecording recording, CgRasterPass raster, CgPipeline pipeline, int bindings) {
        CgChunkBuilder chunks = recording.chunks().begin();
        chunks.draw(pipeline, bindings, FULLSCREEN);
        chunks.instance();
        raster.add(chunks.end());
        raster.end();
    }

    @Nullable
    private CgTextureType depthOf(int fb) {
        if (fb != probed) {
            probedDepth = CgFrameBuffer.depthTypeOf(fb);
            probed = fb;
        }
        return probedDepth;
    }

    private CgGraphTexture scene(int w, int h, boolean beside, @Nullable CgTextureType depth) {
        if (scene != null && width == w && height == h && sceneBeside == beside && sceneDepth == depth) return scene;
        if (beside) {
            scene = CgGraphTexture.besideCurrentDepth("cg_scene", new CgTextureDesc(w, h, SCENE));
        } else {
            scene = CgGraphTexture.transientTexture("cg_scene", new CgTextureDesc(w, h, ownDepth("cg_scene_depth", depth)));
        }
        width = w;
        height = h;
        sceneBeside = beside;
        sceneDepth = depth;
        return scene;
    }

    private CgGraphTexture opaque(CgRecording recording, int w, int h, boolean beside, @Nullable CgTextureType depth) {
        if (opaque != null && opaqueWidth == w && opaqueHeight == h && opaqueBeside == beside && opaqueDepth == depth) {
            return opaque;
        }
        if (opaque != null) recording.release(opaque);
        opaque = beside ? CgGraphTexture.requestedBesideCurrentDepth("cg_opaque_scene", new CgTextureDesc(w, h, SCENE))
                : CgGraphTexture.requested("cg_opaque_scene", new CgTextureDesc(w, h, ownDepth("cg_opaque_scene_depth", depth)));
        opaqueWidth = w;
        opaqueHeight = h;
        opaqueBeside = beside;
        opaqueDepth = depth;
        return opaque;
    }

    private static CgFrameBufferFormat ownDepth(String name, @Nullable CgTextureType depth) {
        CgFrameBufferFormat.Builder format = CgFrameBufferFormat.builder(name).color(0, CgTextureType.RGBA16F);
        if (depth != null) format.depth(depth);
        return format.build();
    }

    /** Forgets its materials, which the material registry frees with the context, and its textures. */
    void release() {
        in.release();
        keep.release();
        out.release();
        scene = null;
        opaque = null;
        kept = false;
        probed = -1;
    }
}
