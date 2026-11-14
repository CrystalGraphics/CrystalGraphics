package com.crystalgraphics.render.scene.renderer.feature.translucency;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialInstance;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshTopology;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.compute.CgCompute;
import com.crystalgraphics.compute.CgKernel;
import com.crystalgraphics.gpu.resource.CgTextureDesc;
import com.crystalgraphics.material.CgMaterialProxy;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.render.draw.CgDrawCommands;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.draw.CgPassConstants;
import com.crystalgraphics.render.draw.CgPipeline;
import com.crystalgraphics.render.draw.CgPipelineForm;
import com.crystalgraphics.render.draw.CgPrimitiveTable;
import com.crystalgraphics.render.graph.CgComputePass;
import com.crystalgraphics.render.graph.CgDepthAccess;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgLoad;
import com.crystalgraphics.render.graph.CgRasterPass;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.meshpass.engine.CgEnginePasses;
import com.crystalgraphics.render.scene.pass.CgCommandForm;
import com.crystalgraphics.render.scene.pass.CgViewPasses;
import com.crystalgraphics.render.scene.renderer.CgSceneRenderer;
import com.crystalgraphics.render.shader.CgBindingModel;
import com.crystalgraphics.render.stage.CgStageFiring;
import com.crystalgraphics.render.view.CgView;
import com.crystalgraphics.render.view.CgViewState;
import com.crystalgraphics.trace.CgGpuTrace;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;
import com.crystalgraphics.api.state.CgBlendState;
import com.crystalgraphics.api.state.CgDepthState;
import com.crystalgraphics.api.state.CgRenderState;
import org.joml.Matrix4f;

import javax.annotation.Nullable;

/**
 * Translucency at half the view's size, as Unreal's separate translucency at a lower screen percentage: what adds is
 * drawn into a half-size target and added over the scene depth-aware ({@link #recordHalf}); what covers premultiplied
 * (smoke, a volume) is marched into a half-size target, blended with its last frame, and laid first in the translucent
 * pass ({@link #recordVolumes}, {@link #addComposite}). The scene renderer's feature, on the {@code HALF} and
 * {@code VOLUME} pass types.
 */
public final class CgHalfResolution {

    /** The depth-aware upsample of the half-size target. */
    public static final String UPSAMPLE_SHADER = "crystalgraphics:shaders/world/half_upsample.shader";
    /** The volumes laid over the scene. */
    public static final String COMPOSITE_SHADER = "crystalgraphics:shaders/world/media/volume_composite.shader";
    private static final String RESOLVE_KERNELS = "crystalgraphics:shaders/world/media/volume_resolve.compute";

    private static final CgFormat HALF_FORMAT = CgFormat.RG11B10_UFLOAT;
    /** Half-size volumes: premultiplied light, and the eye depth each was marched to (0 for none). */
    private static final CgFormat VOLUME_FORMAT = CgFormat.RGBA16_FLOAT, VOLUME_DEPTH_FORMAT = CgFormat.R16_FLOAT,
            VOLUME_HISTORY_FORMAT = CgFormat.RGBA16_FLOAT;
    private static final CgMesh FULLSCREEN = CgMesh.vertices(3, CgMeshTopology.TRIANGLES);
    private static final CgRenderState TRANSPARENT_STATE = CgRenderState.builder().depth(CgDepthState.TEST_ONLY)
            .blend(CgBlendState.ALPHA).build();
    /** Each view's volumes, blended over its last frame's. */
    private static final CgViewState.HistoryKey HISTORY = new CgViewState.HistoryKey("cg_scene_volume_history");
    private static final int GPU_HALF = CgGpuTrace.name("scene.half"), GPU_HALF_ADD = CgGpuTrace.name("scene.halfAdd"),
            GPU_VOLUME = CgGpuTrace.name("scene.volume"), GPU_VOLUME_RESOLVE = CgGpuTrace.name("scene.volumeResolve");

    private static CgMaterialInstance upsample, composite;
    @Nullable
    private static CgKernel resolve;

    private final CgSceneRenderer renderer;
    private final CgPassConstants halfConstants = new CgPassConstants();
    private final float[] block = new float[CgPassConstants.FLOATS];
    private final Matrix4f identity = new Matrix4f();
    private CgTexture upsampleBound, compositeBound;
    private boolean folds;
    /** Its targets, kept while the view's size holds: a steady frame makes no handle. */
    @Nullable
    private CgGraphTexture halfTarget, volumeTarget, volumeDepth;

    /** A volume's marching form, its glow folded into its light where the scene takes glows. */
    private final CgCommandForm marching = (rows, b, p) -> {
        CgPipeline folded = folds && CgMaterialProxy.of(rows.material(b)).hasEmissivePass() ? p.as(CgPipelineForm.FOLD) : null;
        return (folded != null ? folded : p).as(CgPipelineForm.VOLUME);
    };

    public CgHalfResolution(CgSceneRenderer renderer) {
        this.renderer = renderer;
    }

    /** Starts the upsample, the composite and the resolve kernel; whether all are built. The precache's. */
    public static boolean precache() {
        if (upsample == null) upsample = CgMaterial.load(UPSAMPLE_SHADER).instance();
        if (composite == null) composite = CgMaterial.load(COMPOSITE_SHADER).instance();
        boolean ready = built(upsample) & resolveKernel().isReady();
        return built(composite) & ready;
    }

    private static boolean built(CgMaterialInstance material) {
        CgPipeline p = CgMaterialProxy.of(material).pipeline(CgInstanceKind.OBJECT);
        return p == null || p.precache();
    }

    private static CgKernel resolveKernel() {
        if (resolve == null) resolve = CgCompute.load(RESOLVE_KERNELS).kernel("Resolve").precache();
        return resolve;
    }

    /**
     * What adds, marked half resolution, into a target half the view's size, cleared, then added over the firing's
     * target by a depth-aware upsample: each pixel weighs the four half-size texels round it by how close the scene's
     * depth where each was drawn is to its own, so light does not bleed across a silhouette. Before the translucent
     * pass, so what draws after (a haze bending it, smoke over it) sees it.
     */
    public void recordHalf(CgStageFiring firing, CgViewPasses passes) {
        CgRecording rec = firing.recording();
        CgDrawCommands halves = passes.commands(CgEnginePasses.HALF, rec);
        CgTrace.counter(CgChannels.WORLD, "scene.halfDraws", halves.size());
        if (halves.size() == 0) return;
        CgView view = firing.view();
        int w = Math.max(1, view.width() / 2), h = Math.max(1, view.height() / 2);
        if (halfTarget == null || halfTarget.getWidth() != w || halfTarget.getHeight() != h) {
            halfTarget = CgGraphTexture.transientTexture("cg_scene_half", new CgTextureDesc(w, h, HALF_FORMAT));
        }
        CgGraphTexture target = halfTarget;
        CgRasterPass pass = rec.raster("scene.half").color(target, CgLoad.CLEAR).constants(halfConstants(firing, w, h))
                .state(TRANSPARENT_STATE).readsDepth(firing.depth())
                .texture(CgBindingModel.LIGHTMAP, view.hostTextures().lightmapTexture()).timed(GPU_HALF);
        renderer.mediated(pass);
        pass.add(halves).end();

        if (upsample == null) upsample = CgMaterial.load(UPSAMPLE_SHADER).instance();
        if (target != upsampleBound) {
            upsample.sampler("_Half", 0, target);
            upsampleBound = target;
        }
        CgRasterPass add = rec.raster("scene.halfAdd").color(firing.target(), CgLoad.load())
                .depth(firing.depth(), CgLoad.load(), CgDepthAccess.WRITE).constants(firing.constants())
                .readsDepth(firing.depth()).timed(GPU_HALF_ADD);
        add.draw(FULLSCREEN, upsample);
        add.end();
    }

    /**
     * The volumes into a half-size target, cleared: their light, premultiplied, and the eye depth each marched to
     * ({@code CG_VOLUME_DEPTH}), their glow folded into their light where {@code folds}. Then resolved into the view's
     * history by a kernel: last frame's, reprojected and held to this frame's neighbourhood, blended with this one.
     * Answers what the translucent pass lays over the scene: the history, this frame's alone until the kernel is
     * built, or null with no volume in view.
     */
    @Nullable
    public CgGraphTexture recordVolumes(CgStageFiring firing, CgViewPasses passes, boolean folds) {
        this.folds = folds;
        CgRecording rec = firing.recording();
        CgDrawCommands volumes = passes.commands(CgEnginePasses.VOLUME, rec, marching);
        CgTrace.counter(CgChannels.WORLD, "scene.volumeDraws", volumes.size());
        if (volumes.size() == 0) return null;
        CgView view = firing.view();
        int w = Math.max(1, view.width() / 2), h = Math.max(1, view.height() / 2);
        if (volumeTarget == null || volumeTarget.getWidth() != w || volumeTarget.getHeight() != h) {
            volumeTarget = CgGraphTexture.transientTexture("cg_scene_volume", CgTextureDesc.of(w, h, VOLUME_FORMAT));
            volumeDepth = CgGraphTexture.transientTexture("cg_scene_volume_depth", CgTextureDesc.of(w, h, VOLUME_DEPTH_FORMAT));
        }
        CgGraphTexture target = volumeTarget, depth = volumeDepth;
        CgPassConstants constants = halfConstants(firing, w, h);
        rec.raster("scene.volumes").color(target, CgLoad.CLEAR).color(depth, CgLoad.CLEAR).constants(constants)
                .state(TRANSPARENT_STATE).readsDepth(firing.depth())
                .texture(CgBindingModel.LIGHTMAP, view.hostTextures().lightmapTexture()).timed(GPU_VOLUME).add(volumes).end();

        CgViewState state = view.state();
        CgGraphTexture history = state.history(HISTORY, new CgTextureDesc(w, h, VOLUME_HISTORY_FORMAT), rec);
        if (!resolveKernel().isReady()) return target;
        CgComputePass resolving = rec.compute("scene.volumeResolve", constants).readsDepth(firing.depth()).timed(GPU_VOLUME_RESOLVE);
        resolving.dispatch(resolve, w, h, 1).image("RESOLVED", history).texture("_Now", target).texture("_NowDepth", depth)
                .texture("_Last", history.previous()).set("_Fresh", state.fresh(HISTORY) ? 1f : 0f);
        resolving.end();
        return history;
    }

    /**
     * The resolved volumes laid over the scene, first in the translucent pass: every surface after it is dimmed by the
     * smoke in front of it, so one behind the smoke must not have been covered by it too.
     */
    public void addComposite(CgDrawCommands translucent, CgRecording rec, CgGraphTexture volumes) {
        if (composite == null) composite = CgMaterial.load(COMPOSITE_SHADER).instance();
        if (compositeBound != volumes) {
            composite.sampler("_Volumes", 0, volumes);
            compositeBound = volumes;
        }
        CgPipeline pipeline = CgMaterialProxy.of(composite).pipeline(CgInstanceKind.OBJECT);
        if (pipeline == null) return;
        translucent.add(pipeline, CgMaterialProxy.of(composite).bindings(), FULLSCREEN).sortKey(Long.MIN_VALUE)
                .frameRecord(identityRecord(rec));
    }

    /** A record of the recording's table at the identity: what a full screen pass among a list's draws names. */
    private int identityRecord(CgRecording rec) {
        CgPrimitiveTable table = rec.primitives();
        int record = table.add();
        float[] data = table.data();
        identity.get(data, record);
        identity.get(data, record + 16);
        return CgPrimitiveTable.slot(record);
    }

    private CgPassConstants halfConstants(CgStageFiring firing, int w, int h) {
        firing.constants().write(block, 0);
        return halfConstants.read(block, 0).resolution(w, h);
    }

    /** Forgets its materials, which the material registry frees with the context. */
    public void release() {
        upsample = composite = null;
        resolve = null;
        upsampleBound = compositeBound = null;
        halfTarget = volumeTarget = volumeDepth = null;
    }
}
