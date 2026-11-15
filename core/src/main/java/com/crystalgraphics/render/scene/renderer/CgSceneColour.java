package com.crystalgraphics.render.scene.renderer;

import com.crystalgraphics.material.CgMaterialProxy;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshTopology;
import com.crystalgraphics.api.state.CgDepthState;
import com.crystalgraphics.render.draw.CgChunkBuilder;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.draw.CgPipeline;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgLoad;
import com.crystalgraphics.render.graph.CgRasterPass;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.gpu.resource.CgTextureDesc;
import com.crystalgraphics.render.stage.CgStageFiring;
import com.crystalgraphics.render.view.CgViewState;
import com.crystalgraphics.trace.CgGpuTrace;

import javax.annotation.Nullable;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.render.graph.CgDepthAccess;
import com.crystalgraphics.render.draw.CgMaterialBindings;

/**
 * The HDR scene's way in and, around the opaque pass, its way out: the one place the host's 8-bit colour is decoded
 * into linear light and encoded back. At the top of the translucent callback, once its programs are built, decodes the host's colour into an RGBA16F
 * scene beside the host's depth, makes it the view's scene colour ({@code textures().sceneColor()}) and the firing's
 * target, so every stage after draws into it in linear light. The post chain's composite encodes it back into the host's target.
 *
 * <p>An opaque firing with draws of ours draws them into a scene of its own the same way, as Unreal's base pass writes
 * SceneColor: {@link #opaqueIn} before the opaque pass, {@link #opaqueOut} after it, which encodes it back
 * so the host's draws before the transparent stage blend over ours as before, and keeps it. The transparent firing's
 * way in then takes the kept value wherever the host's pixel is still what was encoded there, so an opaque colour past
 * white reaches bloom.</p>
 *
 * <ul>
 *   <li>A host that lends no depth (the window's, a multisampled one) gets a scene with a depth of its own, the host's
 *       copied in by the same pass; draws then write depth into the copy, which the opaque firing's way out writes
 *       back.</li>
 * </ul>
 */
final class CgSceneColour {

    private static final String IN = "crystalgraphics:shaders/world/scene_in.shader";
    private static final String OUT = "crystalgraphics:shaders/world/scene_out.shader";
    private static final CgFormat SCENE = CgFormat.RGBA16_FLOAT;
    static final CgMesh FULLSCREEN = CgMesh.vertices(3, CgMeshTopology.TRIANGLES);
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
                    withDepth = CgMaterial.load(shader).keyword("DEPTH", true);
                    if (keyword != null) withDepth = withDepth.keyword(keyword, true);
                }
                return withDepth;
            }
            if (material == null) {
                material = CgMaterial.load(shader);
                if (keyword != null) material = material.keyword(keyword, true);
            }
            return material;
        }

        /** Its pipeline, writing depth too where {@code depth}; null until its shader parses. */
        @Nullable
        CgPipeline pipeline(boolean depth) {
            CgPipeline base = CgMaterialProxy.of(material(depth)).pipeline(CgInstanceKind.OBJECT);
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
    private CgFormat sceneDepth;
    private boolean sceneBeside;

    /** A view's opaque scene, kept from its opaque firing to its transparent one's, and its size and form. */
    private static final class Opaque {
        @Nullable
        CgGraphTexture scene;
        int width, height;
        @Nullable
        CgFormat depth;
        /** The opaque scene's depth where it has one of its own: released with it. */
        @Nullable
        CgGraphTexture depthTexture;
        boolean beside;
        /** Whether the view's opaque firing drew into {@link #scene} since its last transparent firing's way in. */
        boolean kept;

        /** The opaque scene's depth: the host's beside it, its own, or null for none. */
        @Nullable
        CgGraphTexture depthOf() {
            return beside ? CgGraphTexture.current() : depthTexture;
        }
    }

    private static final CgViewState.Key<Opaque> OPAQUE = new CgViewState.Key<>("scene.opaqueScene", Opaque::new);

    void sceneIn(CgStageFiring stage) {
        Opaque opaque = stage.view().state().get(OPAQUE);
        boolean keeping = opaque.kept;
        opaque.kept = false;
        int w = Math.max(1, stage.view().width()), h = Math.max(1, stage.view().height());
        boolean beside = stage.host().target().lendsAttachments();
        CgFormat depth = beside ? null : stage.host().depthFormat();
        // The kept scene of another size or form is a stale one: decoded as though none were kept
        keeping &= opaque.scene != null && opaque.width == w && opaque.height == h && opaque.beside == beside && opaque.depth == depth;
        Pass pass = keeping ? keep : in;
        CgPipeline pipeline = pass.pipeline(depth != null);
        if (pipeline == null) return;
        CgGraphTexture target = scene(w, h, beside, depth);

        CgRecording recording = stage.recording();
        CgRasterPass raster = recording.raster("world.sceneIn").color(target, CgLoad.load()).constants(stage.constants()).timed(GPU)
                .readsColor(CgGraphTexture.current());
        if (sceneDepthTexture != null) raster.depth(sceneDepthTexture, CgLoad.load(), CgDepthAccess.WRITE);
        if (depth != null) raster.readsDepth(CgGraphTexture.current());
        CgMaterialBindings bindings = CgMaterialProxy.of(pass.material(depth != null)).bindings();
        if (keeping) bindings = bindings.with(0, opaque.scene);
        fullscreen(recording, raster, pipeline, bindings);

        stage.view().textures().sceneColor(target, true);
        stage.retarget(target, sceneDepthTexture);
    }

    /**
     * Before the opaque pass of a firing drawing something of ours, while the HDR scene is drawn: decodes the host's
     * colour into the kept opaque scene and makes it the stage's target. False, changing nothing, until its programs
     * are built; then the firing draws onto the host's target as before.
     */
    boolean opaqueIn(CgStageFiring stage) {
        int w = Math.max(1, stage.view().width()), h = Math.max(1, stage.view().height());
        boolean beside = stage.host().target().lendsAttachments();
        CgFormat depth = beside ? null : stage.host().depthFormat();
        CgPipeline decode = in.pipeline(depth != null), encode = out.pipeline(depth != null);
        // Both or neither: a scene drawn into and never encoded back would lose the opaque draws
        if (decode == null || encode == null || keep.pipeline(depth != null) == null) return false;
        CgRecording recording = stage.recording();
        Opaque opaque = stage.view().state().get(OPAQUE);
        CgGraphTexture target = opaque(opaque, recording, w, h, beside, depth);
        CgRasterPass raster = recording.raster("world.opaqueIn").color(target, CgLoad.load()).constants(stage.constants()).timed(GPU_OPAQUE)
                .readsColor(CgGraphTexture.current());
        if (opaque.depthOf() != null) raster.depth(opaque.depthOf(), CgLoad.load(), CgDepthAccess.WRITE);
        if (depth != null) raster.readsDepth(CgGraphTexture.current());
        fullscreen(recording, raster, decode, CgMaterialProxy.of(in.material(depth != null)).bindings());
        stage.retarget(target, opaque.depthOf());
        stage.view().textures().sceneColor(target, true);
        return true;
    }

    /** After the opaque pass: the opaque scene encoded back into the host's target, kept for the view's transparent firing. */
    void opaqueOut(CgStageFiring stage) {
        stage.retarget(null, null);
        stage.view().textures().sceneColor(null, false);
        Opaque opaque = stage.view().state().get(OPAQUE);
        boolean depth = opaque.depth != null;
        CgPipeline encode = out.pipeline(depth);
        CgRecording recording = stage.recording();
        CgRasterPass raster = recording.raster("world.opaqueOut").color(CgGraphTexture.current(), CgLoad.load())
                .depth(CgGraphTexture.current(), CgLoad.load(), CgDepthAccess.WRITE).constants(stage.constants()).timed(GPU_OUT);
        if (depth) raster.readsDepth(opaque.depthTexture);
        CgMaterialBindings bindings = CgMaterialProxy.of(out.material(depth)).bindings().with(0, opaque.scene);
        fullscreen(recording, raster, encode, bindings);
        opaque.kept = true;
    }

    private static void fullscreen(CgRecording recording, CgRasterPass raster, CgPipeline pipeline, CgMaterialBindings bindings) {
        CgChunkBuilder chunks = recording.chunks().begin();
        chunks.draw(pipeline, bindings, FULLSCREEN);
        chunks.instance();
        raster.add(chunks.end());
        raster.end();
    }

    /** The HDR scene's depth: the host's beside it, one of its own, or null for none. */
    @Nullable
    private CgGraphTexture sceneDepthTexture;

    private CgGraphTexture scene(int w, int h, boolean beside, @Nullable CgFormat depth) {
        if (scene != null && width == w && height == h && sceneBeside == beside && sceneDepth == depth) return scene;
        CgTextureDesc colour = CgTextureDesc.of(w, h, SCENE);
        scene = CgGraphTexture.transientTexture("cg_scene", colour);
        sceneDepthTexture = beside ? CgGraphTexture.current()
                : depth != null ? CgGraphTexture.transientTexture("cg_scene_depth", CgTextureDesc.of(w, h, depth)) : null;
        width = w;
        height = h;
        sceneBeside = beside;
        sceneDepth = depth;
        return scene;
    }

    private static CgGraphTexture opaque(Opaque opaque, CgRecording recording, int w, int h, boolean beside, @Nullable CgFormat depth) {
        if (opaque.scene != null && opaque.width == w && opaque.height == h && opaque.beside == beside && opaque.depth == depth) {
            return opaque.scene;
        }
        if (opaque.scene != null) recording.release(opaque.scene);
        if (opaque.depthTexture != null) recording.release(opaque.depthTexture);
        CgTextureDesc colour = CgTextureDesc.of(w, h, SCENE);
        opaque.depthTexture = beside || depth == null ? null
                : CgGraphTexture.requested("cg_opaque_scene_depth", CgTextureDesc.of(w, h, depth));
        opaque.scene = CgGraphTexture.requested("cg_opaque_scene", colour);
        opaque.width = w;
        opaque.height = h;
        opaque.beside = beside;
        opaque.depth = depth;
        return opaque.scene;
    }

    /** Forgets its materials, which the material registry frees with the context, and its transient scene. */
    void release() {
        in.release();
        keep.release();
        out.release();
        scene = null;
    }
}
