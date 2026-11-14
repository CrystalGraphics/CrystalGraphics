package com.crystalgraphics.render.scene.renderer;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialInterface;
import com.crystalgraphics.api.material.CgRenderPassVariant;
import com.crystalgraphics.api.material.CgRenderQueue;
import com.crystalgraphics.compute.CgKernel;
import com.crystalgraphics.compute.ops.CgGpuOps;
import com.crystalgraphics.material.CgMaterialProxy;
import com.crystalgraphics.render.canvas.CgCanvasMaterials;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.draw.CgPipeline;
import com.crystalgraphics.render.draw.CgPipelineForm;
import com.crystalgraphics.render.graph.CgExecutor;
import com.crystalgraphics.render.precache.CgPipelineList;
import com.crystalgraphics.render.precache.CgPipelinePrecache;
import com.crystalgraphics.render.scene.renderer.feature.distortion.CgDistortion;
import com.crystalgraphics.render.scene.renderer.feature.light.CgSceneLights;
import com.crystalgraphics.render.scene.renderer.feature.media.CgSceneHaze;
import com.crystalgraphics.render.scene.renderer.feature.media.CgSceneMedia;
import com.crystalgraphics.render.scene.renderer.feature.translucency.CgHalfResolution;
import com.crystalgraphics.render.scene.renderer.feature.translucency.CgLayerPasses;
import com.crystalgraphics.render.scene.visibility.CgInstanceCulling;
import com.crystalgraphics.text.render.CgTextMaterials;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;

/**
 * Every pipeline the scene renderer draws, kept built for the session (Unreal's PSO precache): the program list each
 * material's precache request fills, in every form the renderer draws it with under the settings in force, built behind
 * the loading screen at boot and over frames after. Engine side: {@code CgPipelinePrecache} is the consumer's way in.
 *
 * <pre>{@code
 * CgScenePipelines.install();                  // the engine's start
 * CgScenePipelines.beginBoot();                // behind the loading screen
 * while (!CgScenePipelines.bootStep()) ...     // a frame each, outside any stage
 * }</pre>
 *
 * <ul>
 *   <li>Render thread.</li>
 *   <li>Its settings are global, as the program list is: the {@code world.mergeEmission} and
 *       {@code world.halfResolution} flags. A view overriding one draws its other forms as they build.</li>
 * </ul>
 */
public final class CgScenePipelines {

    static final Logger LOGGER = LogManager.getLogger("CgScenePipelines");
    private static final int BOOT_ZONE = CgTrace.name("world.programs.boot");
    private static final long BOOT_TIMEOUT_NANOS = 60_000_000_000L;

    private static final CgPipelineList PIPELINES = new CgPipelineList(CgScenePipelines::precache, CgScenePipelines::requestedSettings);
    @Nullable
    private static CgPipelineList.Settings applied, requested;
    private static boolean installed, booting;
    private static long bootStarted;

    private CgScenePipelines() {
    }

    /** The program list: what every precache collector named. {@code CgPipelinePrecache}'s. */
    public static CgPipelineList list() {
        return PIPELINES;
    }

    /** Registers the renderer's own programs and kernels for the precache, once. The engine calls it when it starts. */
    public static void install() {
        if (installed) return;
        installed = true;
        // text.shader with them: its collector parsed it on the render thread otherwise (109 ms on 1.7.10)
        CgPipelinePrecache.parseAhead(CgHalfResolution.UPSAMPLE_SHADER, CgHalfResolution.COMPOSITE_SHADER,
                CgDistortion.APPLY_SHADER, CgSceneLights.APPLY_SHADER, CgTextMaterials.TEXT_SHADER);
        // The renderer's own programs no material brings in: text in every form and the canvas's default materials
        CgPipelinePrecache.register("crystalgraphics:world", list -> {
            CgTextMaterials.precache(list);
            CgCanvasMaterials.precache(list);
            list.addPass(CgSceneMedia.LIGHT_SHADER);
        });
        // What the first light, smoke or haze dispatches; their files parsed on a worker first
        CgPipelinePrecache.steps("crystalgraphics:world-kernels").ahead(() -> CgKernel.ahead(CgScenePipelines::precacheKernels))
                .step(list -> list.kernels(CgScenePipelines::precacheKernels)).start();
    }

    private static void precacheKernels() {
        CgGpuOps.precacheDepthPyramid();
        CgInstanceCulling.precache();
        CgSceneLights.precacheKernels();
        CgSceneMedia.precacheKernels();
        CgSceneHaze.precacheKernels();
    }

    /**
     * Builds the program list under the settings asked for, behind the loading screen: until {@link #bootStep} answers
     * true, no world callback should fire. Logs what is left after a minute and goes on.
     */
    public static void beginBoot() {
        booting = true;
        bootStarted = System.nanoTime();
    }

    /** One frame of the boot: every program not built started or polled, never waited for. True once all are built. */
    public static boolean bootStep() {
        if (!booting) return true;
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, BOOT_ZONE)) {
            settle();
        }
        if (PIPELINES.pending() > 0 && System.nanoTime() - bootStarted < BOOT_TIMEOUT_NANOS) return false;
        booting = false;
        LOGGER.info("{} engine programs built in {} ms{}", PIPELINES.size(), (System.nanoTime() - bootStarted) / 1_000_000,
                PIPELINES.pending() > 0 ? ", " + PIPELINES.pending() + " still compiling" : "");
        return true;
    }

    /** Whether the engine's programs are still building behind the loading screen. */
    public static boolean booting() {
        return booting;
    }

    /** Warms the list under the settings asked for, and applies them once it is warm. Once a frame. */
    public static void settle() {
        CgPipelineList.Settings r = requestedSettings();
        if (applied == null) applied = r;
        if (PIPELINES.frame(r, applied)) applied = r;
    }

    /** The settings asked for now: what the list keeps built. */
    public static CgPipelineList.Settings requestedSettings() {
        boolean merge = CgSceneRenderer.MERGE_EMISSION.get(), half = CgSceneRenderer.HALF_RESOLUTION.get();
        CgPipelineList.Settings r = requested;
        if (r == null || r.mergeEmission() != merge || r.halfResolution() != half) requested = r = new CgPipelineList.Settings(merge, half);
        return r;
    }

    /** Whether glows fold into their colour's draw: the applied settings', once warm under them. */
    static boolean mergeApplied() {
        return applied == null ? CgSceneRenderer.MERGE_EMISSION.get() : applied.mergeEmission();
    }

    /**
     * Starts every program the renderer draws {@code material} with under {@code settings}, without waiting, and says
     * whether all are built: its Forward pass and depth form, the normals and custom data an opaque draw writes when
     * asked, its Emissive and Distortion passes and the fold, a graded draw's layer forms, the half-size passes where
     * {@code halfResolution}, each in its skinned form where {@code skinned}.
     */
    static boolean precache(CgMaterialInterface material, boolean halfResolution, boolean skinned, CgPipelineList.Settings settings) {
        boolean ready = true;
        boolean layersOnDevice = CgExecutor.drawsOnDevice();
        CgMaterialProxy proxy = CgMaterialProxy.of(material);
        CgPipeline forward = proxy.pipeline(CgInstanceKind.OBJECT);
        ready &= builtAs(skinned, forward);
        if (forward != null) ready &= builtAs(skinned, forward.as(CgPipelineForm.DEPTH));
        if (forward != null && proxy.getRenderQueue() < CgRenderQueue.TRANSPARENT_THRESHOLD) {
            ready &= builtAs(skinned, forward.as(CgPipelineForm.NORMALS));
            if (proxy.writesCustomData()) ready &= builtAs(skinned, forward.as(CgPipelineForm.CUSTOM_DATA));
        }
        CgPipeline glow = proxy.hasEmissivePass() ? proxy.pipeline(CgRenderPassVariant.EMISSIVE, CgInstanceKind.OBJECT) : null;
        if (glow != null) {
            ready &= builtAs(skinned, glow);
            if (forward != null && settings.mergeEmission()) ready &= builtAs(skinned, forward.as(CgPipelineForm.FOLD));
        }
        if (forward != null && proxy.getRenderQueue() < CgRenderQueue.TRANSPARENT_THRESHOLD && proxy.readsExposure()) {
            ready &= builtAs(skinned, CgLayerPasses.layered(forward.as(CgPipelineForm.SOLID)));
            if (glow != null) ready &= builtAs(skinned, CgLayerPasses.layered(glow));
        }
        if (forward != null && proxy.getRenderQueue() >= CgRenderQueue.TRANSPARENT_THRESHOLD) {
            ready &= builtAs(skinned, CgLayerPasses.layered(forward));
            if (layersOnDevice) ready &= builtAs(skinned, forward.as(CgPipelineForm.LAYER));
            if (glow != null) {
                CgPipeline folded = forward.as(CgPipelineForm.FOLD);
                if (settings.mergeEmission()) ready &= builtAs(skinned, CgLayerPasses.layered(folded));
                if (settings.mergeEmission() && layersOnDevice) ready &= builtAs(skinned, folded == null ? null : folded.as(CgPipelineForm.LAYER));
                ready &= builtAs(skinned, CgLayerPasses.layered(glow));
            }
        }
        if (proxy.hasDistortionPass()) {
            ready &= builtAs(skinned, proxy.pipeline(CgRenderPassVariant.DISTORTION, CgInstanceKind.OBJECT));
            ready &= CgDistortion.precacheApply();
        }
        if (!halfResolution || !settings.halfResolution()) return ready;
        if (forward != null) ready &= builtAs(skinned, forward.as(CgPipelineForm.VOLUME));
        return ready & CgHalfResolution.precache();
    }

    private static boolean builtAs(boolean skinned, @Nullable CgPipeline pipeline) {
        return built(pipeline == null || !skinned ? pipeline : pipeline.as(CgPipelineForm.SKINNED));
    }

    /** {@code pipeline} started; whether it is built. */
    static boolean built(@Nullable CgPipeline pipeline) {
        return pipeline == null || pipeline.precache();
    }

    /** Whether a material has a program the renderer's own passes cannot build yet: a full screen pass of the engine's. */
    static boolean ready(CgMaterial material) {
        return built(CgMaterialProxy.of(material).pipeline(CgInstanceKind.OBJECT));
    }
}
