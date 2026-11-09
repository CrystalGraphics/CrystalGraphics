package com.crystalgraphics.render.world;

import com.crystalgraphics.render.post.debug.CgPostDebug;
import com.crystalgraphics.render.post.volume.CgPostSettings;
import com.crystalgraphics.shape.CgShape;
import com.crystalgraphics.shape.bounds.CgAABB;
import com.crystalgraphics.shape.bounds.CgAABBc;
import com.crystalgraphics.variable.CgVar;
import com.crystalgraphics.api.CgBindingPoints;
import com.crystalgraphics.api.framebuffer.CgFrameBufferFormat;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgParameterCollection;
import com.crystalgraphics.api.material.CgRenderPassVariant;
import com.crystalgraphics.api.material.CgRenderQueue;
import com.crystalgraphics.api.state.CgBlendState;
import com.crystalgraphics.api.state.CgDepthState;
import com.crystalgraphics.api.state.CgRenderState;
import com.crystalgraphics.api.text.CgShapedParagraph;
import com.crystalgraphics.api.text.CgTextLayout;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.compute.CgKernel;
import com.crystalgraphics.compute.ops.CgCull;
import com.crystalgraphics.compute.ops.CgCullSets;
import com.crystalgraphics.compute.ops.CgGpuCount;
import com.crystalgraphics.compute.ops.CgGpuOps;
import com.crystalgraphics.frame.CgWorld;
import com.crystalgraphics.lifecycle.CgGraphicsLifecycle;
import com.crystalgraphics.gl.buffer.CgFrameRing;
import com.crystalgraphics.animation.CgAnimInstance;
import com.crystalgraphics.gl.buffer.shader.CgBonePalettes;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshLods;
import com.crystalgraphics.api.mesh.CgMeshTopology;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.platform.world.CgWorldBegin;
import com.crystalgraphics.platform.service.CgShaderPacks;
import javax.annotation.Nullable;
import com.crystalgraphics.render.CgViewFrustum;
import com.crystalgraphics.render.canvas.CgCanvasMaterials;
import com.crystalgraphics.render.draw.CgBufferHandle;
import com.crystalgraphics.render.draw.CgChunkBuilder;
import com.crystalgraphics.render.draw.CgDrawChunk;
import com.crystalgraphics.render.draw.CgIndirect;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.draw.CgOrder;
import com.crystalgraphics.text.render.CgTextMaterials;
import com.crystalgraphics.render.draw.CgPassConstants;
import com.crystalgraphics.render.draw.CgPipeline;
import com.crystalgraphics.render.draw.CgPipelineForm;
import com.crystalgraphics.render.graph.CgBufferDesc;
import com.crystalgraphics.render.graph.CgBufferUsage;
import com.crystalgraphics.render.graph.CgComputePass;
import com.crystalgraphics.render.graph.CgGraphBuffer;
import com.crystalgraphics.render.graph.CgExecutor;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgLoad;
import com.crystalgraphics.render.graph.CgRasterPass;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.graph.CgTextureDesc;
import com.crystalgraphics.render.stage.CgHostFrame;
import com.crystalgraphics.render.stage.CgHostView;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.render.stage.CgFrameKeys;
import com.crystalgraphics.render.stage.CgStageFrame;
import com.crystalgraphics.render.stage.CgStageHook;
import com.crystalgraphics.trace.CgGpuTrace;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.trace.CgTraceRun;
import com.crystalgraphics.util.trace.CgChannels;
import com.crystalgraphics.render.graph.CgRehearsal;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

import java.util.Arrays;
import java.util.Objects;

/**
 * Draws meshes into a host's world at its two world stages, under the host's own camera: submitted at absolute
 * positions in doubles, culled against the view, sorted, and instanced where neighbours share a mesh and a material.
 *
 * <pre>{@code
 * CgWorldRenderer world = CgWorldRenderer.get();
 * CgWorld.client().schedule().add(CgTickGroup.SUBMIT, "mymod:rune", dt -> {   // once a frame, before the world records
 *     world.draw(mesh, material)                         // scratch: build and submit in one expression
 *          .at(x, y, z)                                  // absolute, in doubles
 *          .transform(rotationScale)                     // optional, about that position
 *          .custom(0, r, g, b, a)                        // CG_OBJECT_CUSTOM0
 *          .submit();
 * });
 *
 * // Lit by the world at its position unless told otherwise: a lamp's own glass, or a thing that glows
 * world.draw(bulb, glass).at(x, y, z).light(15, skyLight).submit();
 * world.draw(rune, sigil).at(x, y, z).fullBright().submit();
 *
 * // A material's authored queue decides opaque or transparent; a draw may override it.
 * world.draw(pane, glass).at(x, y, z).queue(CgRenderQueue.TRANSPARENT).submit();
 *
 * // How much of the mesh draws comes from a count a kernel wrote this frame
 * world.draw(CgMesh.quads(capacity), sparks).indirect(live, 0, CgIndirect.INDICES, 6).at(x, y, z).bounds(box).submit();
 *
 * // Thousands of one mesh from a GPU buffer of object records, culled and given levels on the GPU
 * world.draw(rockLods, stone).instances(rocks, CgGpuCount.of(n)).at(x, y, z).bounds(field).submit();
 * }</pre>
 *
 * <ul>
 *   <li>Render thread. A draw lives for the frame it was submitted in; every world stage that frame fires draws it,
 *       under that stage's view, so a host drawing the world twice (1.7.10's anaglyph) needs nothing more.</li>
 *   <li>Shaders see camera-relative world space: {@code CG_CAMERA_WORLD_POS} is the origin, and
 *       {@code CG_ABSOLUTE_WORLD_POS(p)} adds the camera back for an effect that must not move with it.</li>
 *   <li>A mesh with no bounds (a format whose positions are not floats) is never culled.</li>
 *   <li>A material is lit by the host's lightmap at {@code CG_OBJECT_LIGHT} and fogged unless its shader is tagged
 *       {@code "Lighting" = "Unlit"}. The light is the world's at {@link Draw#at}, read at {@link Draw#submit}.</li>
 *   <li>A material with a {@code "LightMode" = "Emissive"} pass glows: its glow is light added into the HDR scene
 *       ({@code CgFrameKeys.SCENE}), where what draws in front covers it, and bloom takes what passes white.</li>
 *   <li>A transparent draw marked {@link Draw#halfResolution()} draws at half the target's size before the transparent
 *       pass ({@link #halfResolution(boolean)} turns it off): light that adds is added over the target, depth-aware; a
 *       volume that covers (premultiplied) is blended with its last frame and laid over the target in the transparent
 *       pass, at its farthest draw's place.</li>
 * </ul>
 */
public final class CgWorldRenderer {

    /** Where the world renderer records in each world stage: after renderers at the default order, which may submit. */
    public static final int ORDER = 1000;
    /** A pass into a target it fills from nothing: one load, not one a pass. */
    static final CgLoad CLEAR = CgLoad.clear(0f, 0f, 0f, 0f);
    /** Where a firing starts the world renderer's frame and runs the client world's schedule: after camera shake only. */
    private static final int FRAME_ORDER = Integer.MIN_VALUE + 1;

    static final Logger LOGGER = LogManager.getLogger("CgWorldRenderer");
    // Above INSTANCE: its constructor reads them.
    private static final CgVar.Bool MERGE_EMISSION = CgVar.bool("crystalgraphics.world.mergeEmission", true, CgVar.DEV,
            "What a new world renderer starts with: a transparent draw's codeless Emissive pass folded into its Forward"
                    + " draw; false draws every Emissive pass on its own (mergeEmission(boolean) live).");
    private static final CgVar.Bool HALF_RESOLUTION = CgVar.bool("crystalgraphics.world.halfResolution", true, CgVar.DEV,
            "What a new world renderer starts with: draws marked halfResolution() at half size; false at full size, in the"
                    + " transparent pass (H in the harness, halfResolution(boolean) live).");
    static final CgVar.Bool SKIP_UNREADY = CgVar.bool("crystalgraphics.world.skipUnready", true, CgVar.DEV,
            "Holds a draw whose programs are not built yet, starting them and counting it as world.unready-draws; false"
                    + " compiles at the draw (skipUnready(boolean) live).");
    /** A firing's record, step by step. */
    private static final int REC_PREPARE = CgTrace.name("world.record.prepare"), REC_CLASSIFY = CgTrace.name("world.record.classify"),
            REC_TOP = CgTrace.name("world.record.top"), REC_MEDIA = CgTrace.name("world.record.media"),
            TOP_NORMALS = CgTrace.name("world.top.normals"), TOP_OPAQUE_SCENE = CgTrace.name("world.top.opaqueScene"),
            TOP_DISTORTION = CgTrace.name("world.top.distortion"), TOP_LABELS = CgTrace.name("world.top.labels"),
            TOP_CUSTOM = CgTrace.name("world.top.custom"),
            REC_CULL = CgTrace.name("world.record.cull"), REC_PASSES = CgTrace.name("world.record.passes"),
            REC_AFTER = CgTrace.name("world.record.after");
    private static final CgWorldRenderer INSTANCE = new CgWorldRenderer();

    static final CgRenderState OPAQUE_STATE = CgRenderState.builder()
            .depth(CgDepthState.TEST_WRITE).blend(CgBlendState.DISABLED).build();
    static final CgRenderState TRANSPARENT_STATE = CgRenderState.builder()
            .depth(CgDepthState.TEST_ONLY).blend(CgBlendState.ALPHA).build();
    /** The host's depth copied into the custom data's own, where the host lends none. */
    static final String DEPTH_IN_SHADER = "crystalgraphics:shaders/world_depth_in.shader";

    static final String UPSAMPLE_SHADER = "crystalgraphics:shaders/world_half_upsample.shader";
    static final CgMesh FULLSCREEN = CgMesh.vertices(3, CgMeshTopology.TRIANGLES);

    private final Draw scratch = new Draw();

    // The frame's draws, flat: what a pooled command object per draw used to hold.
    long frame = -1;
    private long notified = -1;
    /** The frame's draws: what {@link Draw#submit} wrote, read by every pass. */
    final CgWorldDraws draws = new CgWorldDraws();
    /** What every pass builds its draws with, over {@link #draws}. */
    final CgMeshDrawCommands commands = new CgMeshDrawCommands(draws);
    final CgBasePass basePass = new CgBasePass(this);
    final CgGradeLayerPass gradeLayerPass = new CgGradeLayerPass(this);
    /** Separate translucency's draws this firing. */
    private int apartDrawn;
    final CgVolumePass volumePass = new CgVolumePass(this);
    final CgHalfPass halfPass = new CgHalfPass(this);
    final CgEmissivePass emissivePass = new CgEmissivePass(this);
    final CgCustomDataPass customDataPass = new CgCustomDataPass(this);
    final CgCustomDepthPass customDepthPass = new CgCustomDepthPass(this);
    final CgOverdrawPass overdrawPass = new CgOverdrawPass(this);
    private final float[] meshBounds = new float[6];
    private final CgAABB meshAabb = new CgAABB();

    private final Matrix4f viewProjection = new Matrix4f();
    private final Matrix4f toWorld = new Matrix4f();
    private final Vector3f min = new Vector3f();
    private final Vector3f max = new Vector3f();
    /** A box's corners in clip space: x, y, w. */
    private final float[] clipX = new float[8], clipY = new float[8], clipW = new float[8];
    private final CgViewFrustum frustum = new CgViewFrustum();

    // Sets culled on the GPU: one cull, its values copied into each dispatch; per draw, the records and level counts its
    // cull wrote this stage, and where its own start in them. Every set one cull pass culls shares one records buffer
    // and one counts buffer, so their draws join; the k-th pass of a stage writes the k-th pair.
    private final CgCull cull = new CgCull();
    private CgGraphBuffer[] cullOut = new CgGraphBuffer[4], cullLevels = new CgGraphBuffer[4];
    /** Per cull pass of the frame: its sets, culled together. */
    private CgCullSets[] cullBatches = new CgCullSets[4];
    private int cullPasses;

    /**
     * Our opaque surfaces' normals, Unreal's G-buffer normal, for the world lights and a post material asking
     * ({@link CgFrameKeys#WORLD_NORMALS_READ}); this firing's, or null; and the opaque firing's, for the transparent one.
     */
    CgGraphTexture normalTarget, normalsInto, normalsWritten;
    private static final CgFrameBufferFormat NORMAL_FORMAT = CgFrameBufferFormat.builder("cg_world.normals")
            .color(0, CgTextureType.RGBA16F).build();

    /** Whether a foldable glow is drawn in its colour's draw: {@link #mergeEmission(boolean)}, once warm. */
    boolean mergeEmission = MERGE_EMISSION.get();
    /** What {@link #mergeEmission(boolean)} asked for; applied once the program list is warm under it. */
    private boolean mergeRequested = mergeEmission;
    private volatile boolean gradeDraws = true;
    @Nullable
    private CgPipelineList.Settings applied;
    private final CgPipelineList pipelines = new CgPipelineList(this::precache, this::requestedSettings);
    private boolean normalsRefusedNoted;
    final float[] constantsBlock = new float[CgPassConstants.FLOATS];

    // Half resolution: the target half-size draws go into, its constants, and what adds it over the stage's target.
    private boolean halfResolution = HALF_RESOLUTION.get();
    final CgPassConstants halfConstants = new CgPassConstants();

    @Nullable
    CgTexture volumeOut;
    /** Whether a volume's glow is folded into its light this firing: the HDR scene, merging glows. */
    boolean volumeFolds;

    // Distortion: what plans and records it, and the distorting draws this firing.
    final CgWorldDistortion distortion = new CgWorldDistortion();
    private final CgWorldMedia media = new CgWorldMedia();
    private final CgWorldLights pointLights = new CgWorldLights();
    /** Whether this transparent firing's light list holds any light. */
    private boolean lit;
    /** This transparent firing's froxel transmittance, bound at {@code MEDIA_TEXTURE_UNIT}; null with nothing in the air. */
    @Nullable
    private CgGraphTexture mediaOut;
    private final CgWorldText text = new CgWorldText();
    private final Hazes hazes = new Hazes();
    private boolean[] distorts = new boolean[64];

    // The overdraw view: the transparent draws counted again into this target.
    private boolean overdraw = "overdraw".equals(CgPostDebug.VIEW.get());

    /** A stop down: strengths were set for a glow blurred into bloom, which spreads a core's energy wide. */
    public static final float SCENE_EMISSION = 0.5f;
    volatile float sceneEmission = SCENE_EMISSION;

    // The HDR scene: what WORLD_TRANSPARENT draws into in place of the host's colour.
    private final CgSceneTarget sceneTarget = new CgSceneTarget();

    private boolean installed;
    private boolean irisWarned;

    private CgWorldRenderer() {
    }

    public static CgWorldRenderer get() {
        return INSTANCE;
    }

    /** Registers on the world stages, once. The engine calls it when it starts. */
    public void install() {
        if (installed) return;
        installed = true;
        // Before anything else records (camera shake apart), so what the schedule submits reaches the renderers below ORDER
        // that read it in the same firing: VFX pools and Range lay out slots from it.
        CgWorldBegin.listen(this::worldBegin);
        CgRenderStage.WORLD_OPAQUE.register(FRAME_ORDER, stage -> beginFrame(stage.host().view()));
        CgRenderStage.WORLD_TRANSPARENT.register(FRAME_ORDER, stage -> beginFrame(stage.host().view()));
        CgRenderStage.WORLD_TRANSPARENT.register(CgSceneTarget.ORDER, sceneTarget::record);
        CgRenderStage.WORLD_OPAQUE.register(ORDER, this::recordOpaque);
        CgRenderStage.WORLD_TRANSPARENT.register(ORDER, this::recordTransparent);
        parsePassesAhead();
        // The renderer's own programs no material brings in: text in every form (canvas quads and world labels, bitmap
        // and MSDF) and the canvas's default materials; the loading screen's lines and a HUD's missed it at their first
        // draw.
        CgPipelinePrecache.register("crystalgraphics:world", list -> {
            CgTextMaterials.precache(list);
            CgCanvasMaterials.precache(list);
            list.addPass(CgWorldMedia.LIGHT_SHADER);
        });
        // What the first light, smoke or haze dispatches, compiled at a scene's first frame otherwise; their files parsed
        // on a worker first: in the collectors' frame they held the loading screen 170 ms
        CgPipelinePrecache.steps("crystalgraphics:world-kernels").ahead(() -> CgKernel.ahead(CgWorldRenderer::precacheKernels))
                .step(list -> list.kernels(CgWorldRenderer::precacheKernels)).start();
    }

    private static void precacheKernels() {
        CgGpuOps.precacheDepthPyramid();   // the GPU cull's
        CgWorldLights.precacheKernels();
        CgWorldMedia.precacheKernels();
        CgWorldHaze.precacheKernels();
    }

    private static final int BOOT_ZONE = CgTrace.name("world.programs.boot");
    private static final long BOOT_TIMEOUT_NANOS = 60_000_000_000L;

    /**
     * Builds the program list (the engine's own passes, and anything added before) under the settings asked for, behind
     * the loading screen: from now until {@link #bootStep} answers true, no world stage should fire, or a pass draws
     * before it is built. Logs what is left after a minute and goes on. The lifecycle's.
     */
    public void beginBoot() {
        booting = true;
        bootStarted = System.nanoTime();
    }

    /**
     * One frame of the boot: what every collector named collected, and each program not built started or polled, in
     * parallel, never waited for. True once all of it is built. Render thread, outside a stage firing.
     */
    public boolean bootStep() {
        if (!booting) return true;
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, BOOT_ZONE)) {
            settle();
        }
        if (pipelines.pending() > 0 && System.nanoTime() - bootStarted < BOOT_TIMEOUT_NANOS) return false;
        booting = false;
        LOGGER.info("{} engine programs built in {} ms{}", pipelines.size(), (System.nanoTime() - bootStarted) / 1_000_000,
                pipelines.pending() > 0 ? ", " + pipelines.pending() + " still compiling" : "");
        return true;
    }

    /** Whether the engine's programs are still building behind the loading screen: a world stage must not fire yet. */
    public boolean booting() {
        return booting;
    }

    private boolean booting;
    private long bootStarted;

    /**
     * The renderer's own passes a material brings in (half size, volumes, the distortion apply), parsed on a worker now:
     * the warm-up that first needs one finds it parsed rather than parsing on its frame.
     */
    private static void parsePassesAhead() {
        // text.shader with them: its collector above parsed it on the render thread (109 ms on 1.7.10)
        CgPipelinePrecache.parseAhead(UPSAMPLE_SHADER, CgVolumePass.COMPOSITE_SHADER, CgWorldDistortion.APPLY_SHADER,
                CgWorldLights.APPLY_SHADER, CgTextMaterials.TEXT_SHADER);
    }

    /**
     * {@code WORLD_BEGIN}, from a host's camera hook: this frame's partial tick into the opaque stage's host frame, whose
     * camera is still last frame's, and the client world up to its shakes, once a ring frame.
     */
    private void worldBegin(float partialTick) {
        // Minecraft runs its camera hook for a frame or two after the engine has shut down.
        if (CgGraphicsLifecycle.isContextDestroyed() || CgGraphicsLifecycle.isStoodDown()) return;
        CgHostFrame host = CgRenderStage.WORLD_OPAQUE.host();
        host.set(partialTick, host.width(), host.height(), host.mainFramebuffer());
        CgWorld.client().begin(host.view(), CgFrameRing.frame());
    }

    /** Drops the previous ring frame's draws and runs the client world's schedule, once a ring frame. */
    private void beginFrame(CgHostView view) {
        long now = CgFrameRing.frame();
        if (now != frame) {
            clear();
            frame = now;
        }
        if (now != notified) {
            notified = now;
            CgTraceRun.worldFrame();
            CgWorld.client().frame(view, now);
            CgParameterCollection.upload();   // after the schedule, where effects and sequences set them
            settle();
        }
    }

    /** Warms the program list under the settings asked for, and applies them once it is warm. */
    private void settle() {
        CgPipelineList.Settings requested = requestedSettings();
        if (applied == null) applied = requested;
        if (pipelines.frame(requested, applied)) {
            applied = requested;
            mergeEmission = mergeRequested;
        }
        commands.precacheHeld();
    }

    /** The settings asked for now: what the next frames draw under once the program list is warm. */
    public CgPipelineList.Settings requestedSettings() {
        boolean merge = mergeRequested;
        CgPipelineList.Settings r = requested;
        if (r == null || r.mergeEmission() != merge || r.halfResolution() != halfResolution) {
            requested = r = new CgPipelineList.Settings(merge, halfResolution);
        }
        return r;
    }

    @Nullable
    private CgPipelineList.Settings requested;

    /** The precache's list: every pipeline kept built under the settings in force. {@link CgPipelinePrecache}'s. */
    CgPipelineList pipelines() {
        return pipelines;
    }

    /**
     * Whether a draw whose programs are not built is held and counted ({@link #unreadyDraws()}, trace counter
     * {@code world.unready-draws}) rather than compiled at the draw: on by default, as Unreal delays a primitive's
     * proxy until its PSOs are built ({@code r.PSOPrecache.ProxyCreationStrategy} 1, PSOPrecache.cpp:36). A held draw
     * starts its compiles, in the frame's warm budget, and draws in the frame they finish.
     *
     * <pre>{@code
     * CgWorldRenderer.get().skipUnready(false);   // compile at the draw: the control a precache gate measures against
     * }</pre>
     */
    public void skipUnready(boolean on) {
        commands.skipUnready = on;
    }

    public boolean skipUnready() {
        return commands.skipUnready;
    }

    /** How many draws were skipped for an unbuilt program since start. */
    public long unreadyDraws() {
        return commands.unreadyDraws;
    }

    /** Drops every draw. At context teardown. */
    public void release() {
        clear();
        frame = -1;
        commands.held.clear();
        commands.heldSet.clear();
        commands.depthOnly.clear();
        customDepthPass.customDepthStates.clear();
        customDataPass.customDataStates.clear();
        customDataPass.depthIn = null;
        customDataPass.customDataTarget = null;
        customDataPass.customDataProbed = -1;
        halfPass.upsample = null;
        halfPass.upsampleBound = null;
        volumePass.resolve = null;
        volumePass.composite = null;
        volumePass.compositeBound = null;
        volumePass.volumeTarget = null;
        volumeOut = null;
        Arrays.fill(volumePass.volumeHistories, null);
        distortion.release();
        text.release();
        sceneTarget.release();
    }

    /**
     * The distortion target's size as a share of the world's, 0.5 by default: a bend is smooth, so the hazes draw a quarter
     * of the pixels and the apply reads it bilinearly. 1 draws it at full size, a pixel per texel.
     *
     * <pre>{@code
     * CgWorldRenderer.get().distortionScale(1f);   // full size: what --mode=distortion checks pixel by pixel
     * }</pre>
     */
    public void distortionScale(float scale) {
        distortion.scale(scale);
    }

    public float distortionScale() {
        return distortion.scale();
    }

    /**
     * Whether draws that ask for it ({@link Draw#halfResolution()}) draw at half the target's size, on by default. Off,
     * they draw in the transparent pass at full size: the comparison.
     *
     * <pre>{@code
     * CgWorldRenderer.get().halfResolution(false);   // every glow at full size again
     * }</pre>
     */
    public void halfResolution(boolean on) {
        halfResolution = on;
    }

    public boolean halfResolution() {
        return halfResolution;
    }

    /**
     * The smoke and gas in the air, which every transparent world draw is dimmed by at its depth: where whatever
     * holds some adds an injector.
     *
     * <pre>{@code
     * CgWorldRenderer.get().media().add((pass, media) -> clouds.inject(pass, media));
     * }</pre>
     */
    public CgWorldMedia media() {
        return media;
    }

    /**
     * The point lights in the world this frame, which light the scene behind each pixel: an effect's or a mod's from
     * the CPU, and a kernel's appended.
     *
     * <pre>{@code
     * CgWorldRenderer.get().lights().add(x, y, z, 6f, 0xFFFF9030, 4f, 0f);
     * }</pre>
     */
    public CgWorldLights lights() {
        return pointLights;
    }

    /**
     * What a glow drawn into the HDR scene is multiplied by ({@code CG_SCENE_GLOW}), {@value #SCENE_EMISSION} by default:
     * below 1, cores flood less to white, and fewer glows pass white to bloom.
     */
    public CgWorldRenderer sceneEmission(float gain) {
        sceneEmission = Math.max(0f, gain);
        return this;
    }

    public float sceneEmission() {
        return sceneEmission;
    }

    /**
     * Whether a transparent draw whose Emissive pass folds into its Forward pass draws both at once, its glow added into
     * the scene with its colour ({@code CgPipeline.sceneFold}), on by default; off, the glow is drawn after it at the
     * same key.
     *
     * <pre>{@code
     * CgWorldRenderer.get().mergeEmission(false);   // every glow its own draw again
     * }</pre>
     */
    public void mergeEmission(boolean on) {
        mergeRequested = on;
    }

    /** What {@link #mergeEmission(boolean)} asked for; drawn so once the program list is warm under it. */
    public boolean mergeEmission() {
        return mergeRequested;
    }

    /**
     * Whether a draw given a look of its own ({@link Draw#grade}) is graded by it, on by default. Off, every draw takes
     * the frame's look, from the next draw submitted: a before and after of a kit's grade.
     *
     * <pre>{@code
     * CgWorldRenderer.get().gradeDraws(false);   // every kit's effects under the frame's look
     * }</pre>
     *
     * Its exposure is the draw's own and stays.
     */
    public CgWorldRenderer gradeDraws(boolean on) {
        gradeDraws = on;
        return this;
    }

    public boolean gradeDraws() {
        return gradeDraws;
    }

    /**
     * Whether the transparent pass is counted again, each draw's overdraw variant adding 1 per fragment into an R16F
     * target published as {@link CgFrameKeys#OVERDRAW}: its own vertex stage, depth test and discard. Off by default,
     * on under {@code -Dcrystalgraphics.post.debug=overdraw}, which also shows it.
     *
     * <pre>{@code
     * CgWorldRenderer.get().overdraw(true);   // then read CgFrameKeys.OVERDRAW from a later renderer of the firing
     * }</pre>
     */
    public void overdraw(boolean on) {
        overdraw = on;
    }

    public boolean overdraw() {
        return overdraw;
    }

    /** Starts a draw of {@code mesh} under {@code material}: the shared scratch, so build and submit in one expression. */
    public Draw draw(CgMesh mesh, CgMaterial material) {
        return scratch.start(mesh, material);
    }

    /**
     * Starts a draw of a skinned mesh posed this frame: its mesh, skinned by the palette {@code POSE} wrote, where the
     * instance stands. Submit from {@code SUBMIT}, or another group after {@code POSE}.
     *
     * <pre>{@code
     * world.draw(kurama, skin).pad(4f).submit();       // padded: a pose reaches past the bind pose's bounds
     * }</pre>
     *
     * @throws IllegalStateException where {@code CgAnimation} has not posed it this frame
     */
    public Draw draw(CgAnimInstance instance, CgMaterial material) {
        int palette = instance.palette();
        if (palette < 0) throw new IllegalStateException(instance + " is not posed this frame: CgAnimation.add it, and draw after POSE");
        Draw d = scratch.start(instance.mesh().mesh(), material).bones(palette)
                .at(instance.x(), instance.y(), instance.z()).transform(instance.transform()).pad(instance.boundsPad());
        if (instance.customDepthStencil() >= 0) d.customDepth(instance.customDepthStencil(), instance.customDepthWriteMask());
        return d;
    }

    /**
     * Draws a skinned mesh posed this frame, each submesh in its slot's material ({@link CgAnimInstance#material}):
     * Unreal's skinned mesh component drawing its sections, each with {@code GetMaterial(slot)}
     * (SkinnedMeshComponentHelper.h:108-125), into CustomDepth too where it is marked, and each again in its slot's
     * overlay within its overlay distance (Unreal's {@code OverlayMaterial}). Submit from {@code SUBMIT}, or another
     * group after {@code POSE}.
     *
     * <pre>{@code
     * goku.material(0, hair).material(1, head)...;   // or every slot from a prefab's materials
     * goku.overlay(outline).customDepth(1, 0xFF);
     * world.draw(goku);
     * }</pre>
     *
     * @throws IllegalStateException where it is not posed this frame, or a slot has no material: Unreal draws the
     *         default material there, and this engine has none to draw, so it names the slot
     */
    public void draw(CgAnimInstance instance) {
        int palette = instance.palette();
        if (palette < 0) throw new IllegalStateException(instance + " is not posed this frame: CgAnimation.add it, and draw after POSE");
        boolean overlays = withinOverlayDistance(instance);
        for (int slot = 0; slot < instance.materialSlots(); slot++) {
            CgMaterial material = instance.material(slot);
            if (material == null) {
                throw new IllegalStateException(instance + "'s slot " + slot + " (" + instance.mesh().material(slot)
                        + ") has no material: set it with material(slot, material)");
            }
            Draw d = scratch.start(instance.mesh().mesh(), material).bones(palette).submesh(slot)
                    .at(instance.x(), instance.y(), instance.z()).transform(instance.transform()).pad(instance.boundsPad());
            if (instance.customDepthStencil() >= 0) d.customDepth(instance.customDepthStencil(), instance.customDepthWriteMask());
            d.submit();
            CgMaterial overlay = overlays ? instance.overlay(slot) : null;
            if (overlay != null) {
                scratch.start(instance.mesh().mesh(), overlay).bones(palette).submesh(slot)
                        .at(instance.x(), instance.y(), instance.z()).transform(instance.transform()).pad(instance.boundsPad())
                        .submit();
            }
        }
    }

    /** Whether {@code instance}'s overlay draws this frame: within its overlay distance of the world's camera, or no limit. */
    private static boolean withinOverlayDistance(CgAnimInstance instance) {
        float most = instance.overlayMaxDistance();
        if (most <= 0f) return true;
        CgHostView view = CgRenderStage.WORLD_OPAQUE.host().view();
        double dx = instance.x() - view.x(), dy = instance.y() - view.y(), dz = instance.z() - view.z();
        return dx * dx + dy * dy + dz * dz <= (double) most * most;
    }

    /**
     * Starts a label: {@code text} at a point in the world, facing the camera unless turned, depth-tested against the
     * scene and drawn after the transparent draws, as a name tag. Place it on the {@link CgWorldText.Label}, then
     * {@code font(...)} or {@code family(...)} gives its {@code CgTextDraw}, with all a draw has (a style,
     * constraints); {@code submit()} that. It lives for the frame. Render thread.
     *
     * <pre>{@code
     * world.text("Attract + Orbit").at(x, y + 6, z).height(0.5f).font(font).submit();          // centred on its point
     * world.text("12").at(x, y, z).anchor(0.5f, 0f).font(font).color(0xFFFF5040)
     *         .style(DAMAGE).submit();                                                          // a CgTextStyle
     * world.text(sign).at(x, y, z).rotation(facingSouth).family(family).targetPx(48).constraints(400f, 0f).submit();
     * }</pre>
     *
     * <ul>
     *   <li>{@code height} is a line's height in blocks: the draw's {@code basePx()} maps to it. Its raster tier (bitmap
     *       or distance field) follows how tall it stands on screen, as any world text does.</li>
     *   <li>The draw's {@code pose} is the world's, set at each firing; its {@code at} offsets it in its own pixels.</li>
     *   <li>A label is unlit and unfogged, and writes no depth: labels behind a nearer one show through it.</li>
     *   <li>A glyph not yet in the atlas draws a frame or more late, as all text does.</li>
     * </ul>
     */
    public CgWorldText.Label text(String text) {
        CgWorldText.Label label = label();
        label.draw().text(text);
        return label;
    }

    /** As {@link #text(String)}, of a shaped paragraph, wrapped by the draw's {@code constraints}. */
    public CgWorldText.Label text(CgShapedParagraph paragraph) {
        CgWorldText.Label label = label();
        label.draw().paragraph(paragraph);
        return label;
    }

    /** The next label, dropping the last frame's draws first as {@link #add} does: a frame of labels alone keeps them. */
    private CgWorldText.Label label() {
        long now = CgFrameRing.frame();
        if (now != frame) {
            clear();
            frame = now;
        }
        return text.next();
    }

    /** As {@link #text(String)}, of a finished layout: its fonts are its own, so {@code draw()} may submit as it is. */
    public CgWorldText.Label text(CgTextLayout layout) {
        CgWorldText.Label label = label();
        label.draw().layout(layout);
        return label;
    }

    /** As {@link #draw(CgMesh, CgMaterial)}, of the level of {@code lods} for how tall the draw stands on screen. */
    public Draw draw(CgMeshLods lods, CgMaterial material) {
        Draw draw = scratch.start(lods.finest(), material);
        draw.lods = lods;
        return draw;
    }

    /**
     * Starts every program this renderer draws {@code material} with under {@code settings}, without waiting, and says
     * whether all are built: each link of its chain's Forward pass, its depth pass, its Emissive and Distortion passes,
     * each in the multi-draw form a joined run binds, the half-size passes where {@code halfResolution}, the skinned
     * vertex factory's forms where {@code skinned} (the precache key's vertex factory, as Unreal's), with the keywords
     * enabled now. Only the variants those settings draw: a glow folded into its colour ({@code sceneFold}) where
     * glows merge, and a transparent draw's graded layer written beside its colour where a device draws. {@link CgPipelineList}'s
     * precacher: a caller requests its materials through {@link CgPipelinePrecache}.
     */
    boolean precache(CgMaterial material, boolean halfResolution, boolean skinned, CgPipelineList.Settings settings) {
        boolean ready = true;
        boolean layersOnDevice = CgExecutor.drawsOnDevice();
        for (CgMaterial link = material; link != null; link = link.getNextPass()) {
            ready &= builtAs(skinned, link.pipeline(CgInstanceKind.OBJECT));
            if (link.hasDepthPass()) ready &= builtAs(skinned, link.pipeline(CgRenderPassVariant.DEPTH, CgInstanceKind.OBJECT));
            CgPipeline forward = link.pipeline(CgInstanceKind.OBJECT);
            if (forward != null) ready &= builtAs(skinned, forward.as(CgPipelineForm.DEPTH));
            // The normals and custom data an opaque draw writes whenever the lights or a post material ask: warm
            // whether or not they ask now, so the frame that starts asking compiles nothing.
            if (forward != null && link.getRenderQueue() < CgRenderQueue.TRANSPARENT_THRESHOLD) {
                ready &= builtAs(skinned, forward.as(CgPipelineForm.NORMALS));
                if (link.writesCustomData()) ready &= builtAs(skinned, forward.as(CgPipelineForm.CUSTOM_DATA));
            }
            if (link.hasEmissivePass()) {
                ready &= builtAs(skinned, link.pipeline(CgRenderPassVariant.EMISSIVE, CgInstanceKind.OBJECT));
                if (forward != null && settings.mergeEmission()) ready &= builtAs(skinned, forward.as(CgPipelineForm.FOLD));
            }
            if (forward != null && link.getRenderQueue() < CgRenderQueue.TRANSPARENT_THRESHOLD
                    && link.readsExposure()) {
                // An effect's opaque or masked draw graded: its Forward pass covering its layer, and its glow
                ready &= builtAs(skinned, gradeLayerPass.layered(forward.as(CgPipelineForm.SOLID)));
                if (link.hasEmissivePass()) {
                    ready &= builtAs(skinned, gradeLayerPass.layered(link.pipeline(CgRenderPassVariant.EMISSIVE, CgInstanceKind.OBJECT)));
                }
            }
            if (forward != null && link.getRenderQueue() >= CgRenderQueue.TRANSPARENT_THRESHOLD) {
                // A graded draw's layer: its Forward pass, folded or with its glow, under the layer's blend
                ready &= builtAs(skinned, gradeLayerPass.layered(forward));
                // And the transparent pass writing that layer itself, where a device draws it (T12 L3)
                if (layersOnDevice) ready &= builtAs(skinned, forward.as(CgPipelineForm.LAYER));
                if (link.hasEmissivePass()) {
                    if (settings.mergeEmission()) ready &= builtAs(skinned, gradeLayerPass.layered(forward.as(CgPipelineForm.FOLD)));
                    if (settings.mergeEmission() && layersOnDevice) {
                        CgPipeline folded = forward.as(CgPipelineForm.FOLD);
                        ready &= builtAs(skinned, folded == null ? null : folded.as(CgPipelineForm.LAYER));
                    }
                    ready &= builtAs(skinned, gradeLayerPass.layered(link.pipeline(CgRenderPassVariant.EMISSIVE, CgInstanceKind.OBJECT)));
                }
            }
            if (link.hasDistortionPass()) {
                ready &= builtAs(skinned, link.pipeline(CgRenderPassVariant.DISTORTION, CgInstanceKind.OBJECT));
                ready &= distortion.precache();
            }
        }
        if (!halfResolution || !settings.halfResolution()) return ready;
        if (halfPass.upsample == null) halfPass.upsample = CgMaterial.newInstance(UPSAMPLE_SHADER);
        if (volumePass.composite == null) volumePass.composite = CgMaterial.newInstance(CgVolumePass.COMPOSITE_SHADER);
        ready &= built(halfPass.upsample.pipeline(CgInstanceKind.OBJECT));
        ready &= volumePass.resolveKernel().isReady();
        return built(volumePass.composite.pipeline(CgInstanceKind.OBJECT)) & ready;
    }

    /** {@link #built} of {@code pipeline}'s skinned form where {@code skinned}. */
    private static boolean builtAs(boolean skinned, @Nullable CgPipeline pipeline) {
        return built(pipeline == null || !skinned ? pipeline : pipeline.as(CgPipelineForm.SKINNED));
    }

    /** {@code pipeline} started; whether it is built. */
    static boolean built(@Nullable CgPipeline pipeline) {
        return pipeline == null || pipeline.precache();
    }

    /** One draw being built. Never hold it: the next {@link #draw} reuses it. */
    public final class Draw {

        private CgMesh mesh;
        private CgMeshLods lods;
        private CgMaterial material;
        private double x, y, z;
        private final Matrix4f transform = new Matrix4f();
        private final float[] custom = new float[16];
        private int queue;
        private int order, batchKey;
        private CgSortLayer layer;
        private boolean inGroup;
        private double groupX, groupY, groupZ;
        private int submesh, first, count;
        private final float[] bounds = new float[6];
        private boolean boundsSet;
        private float pad;
        private CgBufferHandle indirect;
        private boolean each;
        /** Its {@link #each} records: position (3), customs (16), count offset. */
        private int records;
        private double[] recordAt = new double[16 * 3];
        private float[] recordCustom = new float[16 * 16];
        private long[] recordCount = new long[16];
        private long indirectOffset;
        private CgIndirect indirectMode;
        private int indirectFactor;
        private CgGraphBuffer set;
        private int setFirst;
        private int stated;
        private float setScale;
        private CgGpuCount setCount;
        private final CgBufferHandle[] buffer = new CgBufferHandle[CgDrawChunk.BUFFERS];
        private final CgBindingPoints.Binding[] bufferPoint = new CgBindingPoints.Binding[CgDrawChunk.BUFFERS];
        private final CgAABB meshBox = new CgAABB();
        /** Block and sky light; NaN block for the world's at its position. */
        private float blockLight, skyLight;
        private float emission;
        private boolean half, onGpu;
        private String gpuGroup;
        private int subject;
        @Nullable
        private CgPostSettings grade;
        private int customDepth;
        /** Where its palette starts in the frame's bones; -1 for none. */
        private int bones;

        private Draw start(CgMesh mesh, CgMaterial material) {
            this.mesh = mesh;
            this.lods = null;
            this.material = material;
            x = y = z = 0;
            transform.identity();
            Arrays.fill(custom, 0f);
            queue = material.getRenderQueue();
            order = batchKey = 0;
            layer = CgSortLayer.DEFAULT;
            inGroup = false;
            submesh = -1;
            first = 0;
            count = -1;
            boundsSet = false;
            pad = 0f;
            indirect = null;
            each = false;
            records = 0;
            set = null;
            setFirst = 0;
            stated = 0;
            setScale = 1f;
            setCount = null;
            Arrays.fill(buffer, null);
            Arrays.fill(bufferPoint, null);
            blockLight = Float.NaN;
            emission = 1f;
            half = false;
            onGpu = false;
            gpuGroup = null;
            subject = 0;
            grade = null;
            customDepth = -1;
            bones = -1;
            return this;
        }

        /**
         * Draws it into CustomDepth as well, writing {@code stencil} into the bits {@code writeMask} names: Unreal's
         * {@code bRenderCustomDepth}, {@code CustomDepthStencilValue} and {@code CustomDepthStencilWriteMask}. The pass
         * runs after the transparent pass and is published as {@link CgFrameKeys#CUSTOM_DEPTH}, where outline and stencil
         * post materials read it.
         *
         * <pre>{@code
         * world.draw(goku.mesh(), skin).bones(base).at(x, y, z).customDepth(1, 0xFF).submit();
         * world.draw(rock, stone).at(x, y, z).customDepth(0x10, 0x10).submit();   // bit 4 alone, other bits kept
         * }</pre>
         *
         * <ul>
         *   <li>Its depth is tested against CustomDepth alone, never the scene's: a mesh behind a wall is written, and a
         *       post material compares it with the scene's depth.</li>
         *   <li>A transparent material is drawn there only where it writes depth; a set of instances
         *       ({@link #instances}) is refused.</li>
         * </ul>
         */
        public Draw customDepth(int stencil, int writeMask) {
            if (stencil < 0 || stencil > 255 || writeMask < 1 || writeMask > 255) {
                throw new IllegalArgumentException("stencil " + stencil + " under write mask " + writeMask + ": both 0 to 255, the mask at least 1");
            }
            customDepth = stencil | writeMask << 8;
            return this;
        }

        /**
         * Skins it by the palette starting at {@code base}: what {@link CgBonePalettes#write} answered this frame for
         * its bones, in its skeleton's order. Required of a mesh in a skinned format, and only of one.
         *
         * <pre>{@code
         * int base = CgBonePalettes.write(skinning, skeleton.boneCount());   // pose(bone) * inverseBind(bone)
         * world.draw(goku.mesh(), skin).bones(base).at(x, y, z).submit();
         * }</pre>
         *
         * <ul>
         *   <li>Its bounds are the mesh's bind pose unless stated: a pose reaching past them needs {@link #bounds} or
         *       {@link #pad}, or it is culled while still on screen.</li>
         * </ul>
         */
        public Draw bones(int base) {
            if (base < 0) throw new IllegalArgumentException("bone base " + base);
            bones = base;
            return this;
        }

        /**
         * Puts its glow in the subject of an impact frame whose settings name {@code key}
         * ({@code CgPostSettings.subject}): only such draws are filled in ink, drawn before the opaque stage's own draws
         * so no other effect's smoke hides them. 0, the default, is no key.
         *
         * <pre>{@code
         * world.draw(dome, glow).at(x, y, z).subject(effectKey).submit();
         * new CgPostSettings().impact(CgImpact.SUBJECT, 1f).subject(effectKey);
         * }</pre>
         */
        public Draw subject(int key) {
            subject = key;
            return this;
        }

        /**
         * Grades its light as {@code look} grades it, apart from the rest of the frame: under the HDR scene the light it
         * adds is drawn again into a layer of its look's, and the post stack tones and blooms that layer by {@code look}
         * alone ({@link CgGradeLayers}), adding only what it contributes over the frame's own look. Draws of one look
         * object share a layer. Null, the default, is the frame's look.
         *
         * <pre>{@code
         * world.draw(sphere, glow).at(x, y, z).grade(kitLook.settings()).submit();
         * }</pre>
         *
         * <ul>
         *   <li>Only its tonemapper, grade and bloom are its own: a look's vignette, flash or impact frame grades the
         *       whole screen and is left out.</li>
         *   <li>A draw that multiplies what is behind it, or draws at half size, keeps the frame's look.</li>
         *   <li>At most {@link CgGradeLayers#MAX} looks a frame; without the HDR scene, or where the host lends no depth,
         *       every draw keeps the frame's look.</li>
         * </ul>
         */
        public Draw grade(@Nullable CgPostSettings look) {
            grade = look;
            return this;
        }

        /**
         * Charges its GPU time to {@code label} under {@code crystalgraphics.gpu.groups}, in place of its material's
         * path: for a consumer whose draws share materials but not purpose.
         *
         * <pre>{@code
         * world.draw(tube, beam).at(x, y, z).gpuGroup("vfx.beam.core").submit();
         * }</pre>
         */
        public Draw gpuGroup(String label) {
            gpuGroup = label;
            return this;
        }

        /**
         * Draws it at half the target's size, at a quarter of the pixels, before the transparent pass. Its material's
         * blend picks the route: light that adds (Blend ONE ONE: a glow) is added over the target where the scene's
         * depth agrees; a volume that covers (Blend ONE ONE_MINUS_SRC_ALPHA: smoke, a cloud) is blended with its last
         * frame, reprojected, then laid over the target in the transparent pass at its place in the sort. With
         * {@link CgWorldRenderer#halfResolution(boolean)} off it draws in that pass as any other. A transparent draw only.
         *
         * <pre>{@code
         * world.draw(sphere, glow).at(x, y, z).transform(scale).halfResolution().submit();       // added
         * world.draw(cube, smoke).at(x, y, z).transform(box).halfResolution().submit();          // a volume
         * }</pre>
         *
         * <pre>{@code
         * // a volume's fragment: the eye depth it marched to, which its last frame is reprojected by
         * CG_VOLUME_DEPTH(FX_EYE_DEPTH(ray, weightedDistance));
         * if (CG_HISTORY_VALID) jitter = fract(jitter + 0.618034 * float(CG_FRAME_INDEX % 64));   // a new sample each frame
         * }</pre>
         *
         * <ul>
         *   <li>The half-size target has no depth: a material that hides itself behind the scene does it from
         *       {@code cg_DepthBuffer} ({@code CG_SCENE_EYE_DEPTH} at {@code gl_FragCoord.xy / CG_RESOLUTION}), with
         *       {@code DepthTest ALWAYS}.</li>
         *   <li>A volume naming no depth is reprojected at the scene's, which smears it as the camera moves past it.</li>
         *   <li>Every volume of a firing is laid over the target at once, at the farthest one's key: a transparent draw
         *       sorted between two volumes draws over both.</li>
         *   <li>Under the HDR scene a volume's codeless Emissive pass is folded into its light, so it marches once.</li>
         * </ul>
         */
        public Draw halfResolution() {
            half = true;
            return this;
        }

        /** Draws only submesh {@code i} of the mesh, whole. */
        public Draw submesh(int i) {
            submesh = i;
            return this;
        }

        /**
         * Draws {@code count} of the submesh's indices from {@code first} (submesh 0 unless {@link #submesh} named
         * one); for a mesh without indices, its vertices. {@code CG_VERTEX_ID} is not moved by it.
         *
         * <pre>{@code
         * world.draw(CgMesh.quads(capacity), sparks).indices(0, live * 6).at(x, y, z).bounds(box).submit();
         * }</pre>
         */
        public Draw indices(int first, int count) {
            if (submesh < 0) submesh = 0;
            this.first = first;
            this.count = count;
            return this;
        }

        /**
         * The box this draw covers, in its own space (transformed with it, as a mesh's bounds are): what it is culled
         * by, in place of its mesh's. A mesh the shader places, {@code CgMesh.quads(n)}, has none of its own.
         *
         * <pre>{@code
         * world.draw(CgMesh.quads(capacity), sparks).indices(0, live * 6).at(x, y, z).bounds(CgAABBc.UNIT).submit();
         *
         * private final CgAABB box = new CgAABB();                      // a box that changes: one, reused
         * world.draw(ribbon, bolt).at(x, y, z).bounds(box.set(0, 0, 0, tx, ty, tz).grow(0.5)).submit();
         * }</pre>
         */
        public Draw bounds(CgAABBc box) {
            bounds[0] = (float) box.minX();
            bounds[1] = (float) box.minY();
            bounds[2] = (float) box.minZ();
            bounds[3] = (float) box.maxX();
            bounds[4] = (float) box.maxY();
            bounds[5] = (float) box.maxZ();
            boundsSet = true;
            return this;
        }

        /**
         * As {@link #bounds(CgAABBc)}, by a shape's own-space box: an orb drawn round its centre states the ball it
         * fills.
         *
         * <pre>{@code
         * world.draw(orb, plasma).at(x, y, z).bounds(CgSphere.of(2)).submit();
         * }</pre>
         */
        public Draw bounds(CgShape shape) {
            return bounds(shape.bounds(meshBox));
        }

        /**
         * Skips the CPU's bounds test: for an {@link #indirect} draw whose count a GPU cull wrote, holding only what
         * that cull found visible, as a particle pool's draw list does. It still sorts by its position and
         * {@link #group}, and states no bounds.
         *
         * <pre>{@code
         * world.draw(quads, spark).indirect(visible, slot * 4L, CgIndirect.INDICES, 6).at(x, y, z).gpuCulled()
         *      .group(x, y, z).submit();
         * }</pre>
         *
         * <ul>
         *   <li>A reader of the scene ({@code cg_SceneColor}) takes a copy of the whole target for it, having no
         *       screen rect.</li>
         * </ul>
         */
        public Draw gpuCulled() {
            onGpu = true;
            return this;
        }

        /**
         * Has its shader read {@code records} where it reads the engine buffer at {@code at}, through the same macros:
         * records in that buffer's layout which a kernel wrote this frame, as a GPU particle pool's draw list stands in
         * for the frame's particle records.
         *
         * <pre>{@code
         * world.draw(quads, spark).buffer(CgBindingPoints.PARTICLES, range.drawn())
         *      .indirect(range.visible(), range.visibleWord(slot) * 4L, CgIndirect.INDICES, 6)
         *      .custom(0, range.base(slot), capacity, radius, parameter).at(x, y, z).gpuCulled().submit();
         * }</pre>
         *
         * <ul>
         *   <li>A graph buffer is read after the pass writing it, so the draw waits for that kernel.</li>
         *   <li>Three a draw at most ({@code CgDrawChunk.BUFFERS}), at different bindings: a particle draw reads its pool's
         *       records and its order, a strip's draw its run besides. Object records take {@link #instances} instead.</li>
         * </ul>
         */
        public Draw buffer(CgBindingPoints.Binding at, CgBufferHandle records) {
            Objects.requireNonNull(at, "at");
            Objects.requireNonNull(records, "records");
            int s = 0;
            while (s < bufferPoint.length && bufferPoint[s] != null) {
                if (bufferPoint[s].equals(at)) throw new IllegalArgumentException("a second buffer() at " + at);
                s++;
            }
            if (s == bufferPoint.length) throw new IllegalStateException("a draw reads at most " + bufferPoint.length + " buffers");
            bufferPoint[s] = at;
            buffer[s] = records;
            return this;
        }

        /**
         * Draws as much of the mesh as a count written on the GPU says: the {@code uint} at byte {@code offset} in
         * {@code count}, times {@code factor}, read as {@code mode}, within the range {@link #indices} or
         * {@link #submesh} chose. A graph buffer's count is read after the pass that writes it in the stage's frame.
         *
         * <pre>{@code
         * world.draw(CgMesh.quads(capacity), sparks).indirect(live, 0, CgIndirect.INDICES, 6).at(x, y, z).bounds(box).submit();
         * world.draw(billow, smoke).indirect(live, 0, CgIndirect.INSTANCES, 1).at(x, y, z).bounds(box).submit();
         * }</pre>
         *
         * <p>Under {@code INSTANCES} every instance reads this draw's record, and the shader places each from
         * {@code CG_DRAW_INSTANCE}. The count is unknown on the CPU, so cull by {@link #bounds} covering every element.</p>
         */
        public Draw indirect(CgBufferHandle count, long offset, CgIndirect mode, int factor) {
            this.indirect = count;
            this.indirectOffset = offset;
            this.indirectMode = mode;
            this.indirectFactor = factor;
            return this;
        }

        /**
         * One draw of many records, each its own indirect command: record k, added by {@link #each}, draws the
         * {@code uint} at its own offset in {@code count}, times {@code factor}, of the mesh, at its own position and
         * with its own customs. Where draws join it is one multi-draw call, and it costs the CPU one draw: many particle
         * slots of one material. Its records draw far to near.
         *
         * <pre>{@code
         * CgWorldRenderer.Draw draw = world.draw(quads, spark).buffer(CgBindingPoints.PARTICLES, range.drawn())
         *         .indirectEach(range.visible(), CgIndirect.INDICES, 6).gpuCulled().at(gx, gy, gz).group(gx, gy, gz);
         * for (int slot : slots) {
         *     draw.each(ox, oy, oz, range.visibleWord(slot) * 4L).custom(0, range.base(slot), capacity, radius, 0f);
         * }
         * draw.submit();
         * }</pre>
         *
         * <ul>
         *   <li>{@link #custom} after {@code each} sets that record's; each record starts from the draw's own.</li>
         *   <li>Its position ({@link #at}) sorts and lights it; its records' positions place them.</li>
         *   <li>{@code INDICES} or {@code VERTICES}; not with {@link #instances}. A draw of no records draws nothing.</li>
         * </ul>
         */
        public Draw indirectEach(CgBufferHandle count, CgIndirect mode, int factor) {
            if (mode == CgIndirect.INSTANCES) throw new IllegalArgumentException("indirectEach() counts indices or vertices");
            indirect(count, 0, mode, factor);
            each = true;
            return this;
        }

        /** Adds a record to a draw of {@link #indirectEach}: at absolute {@code (x, y, z)}, its count at byte {@code countOffset}. */
        public Draw each(double x, double y, double z, long countOffset) {
            if (!each) throw new IllegalStateException("each() on a draw that is not indirectEach()");
            if (records == recordCount.length) {
                int n = records * 2;
                recordAt = Arrays.copyOf(recordAt, n * 3);
                recordCustom = Arrays.copyOf(recordCustom, n * 16);
                recordCount = Arrays.copyOf(recordCount, n);
            }
            recordAt[records * 3] = x;
            recordAt[records * 3 + 1] = y;
            recordAt[records * 3 + 2] = z;
            System.arraycopy(custom, 0, recordCustom, records * 16, 16);
            recordCount[records] = countOffset;
            records++;
            return this;
        }

        /**
         * Draws the mesh once per object record of {@code records} ({@code CgInstanceKind.OBJECT}'s layout, each in this
         * draw's own space), as many as {@code count} says, culled on the GPU in every stage that draws it: against the
         * view, by screen height for a {@link CgMeshLods}, and against the depth drawn before the world renderer (in
         * Minecraft, the terrain). Each level kept draws as one indirect draw, the levels one call where draws join.
         *
         * <pre>{@code
         * CgGraphBuffer rocks = CgGraphBuffer.persistent("rocks", CgBufferDesc.elements(n, CgGpuOps.cullRecordBytes(),
         *         CgBufferUsage.STORAGE, CgBufferUsage.COPY));                       // filled once, by update or a kernel
         * world.draw(rockLods, stone).instances(rocks, CgGpuCount.of(n)).at(x, y, z).bounds(field).submit();
         *
         * // A count a kernel wrote: the live ones of a buffer of capacity records
         * world.draw(shard, crystal).instances(shards, CgGpuCount.at(alive, 0, capacity)).at(x, y, z).submit();
         * }</pre>
         *
         * <ul>
         *   <li>{@link #bounds} is the whole set's box, culled on the CPU; without it the set is culled per instance
         *       only. Each instance is culled by its mesh's box, grown by {@link #pad}.</li>
         *   <li>A record's model matrix places it in this draw's space, and its customs are its own but those this draw
         *       states with {@link #custom}, which every kept record takes; every kept record is lit by this draw's
         *       light ({@link #light}, else the world's at its position).</li>
         *   <li>A transparent set draws its instances in no order among themselves.</li>
         *   <li>Not with {@link #indirect}: the cull writes the count. The mesh needs bounds, and a {@link CgMeshLods} at
         *       most {@link CgCull#MAX_LEVELS} levels.</li>
         * </ul>
         */
        public Draw instances(CgGraphBuffer records, CgGpuCount count) {
            return instances(records, 0, count);
        }

        /**
         * {@link #instances(CgGraphBuffer, CgGpuCount)} of the records from {@code first}: many sets in one buffer, each
         * a draw of its own range, as a pool's mesh slots are.
         *
         * <pre>{@code
         * for (int s = 0; s < slots; s++) {
         *     world.draw(meshOf[s], materialOf[s]).instances(pool, slotFirst[s], CgGpuCount.at(slotCounts, s, slotSize[s]))
         *          .at(x, y, z).bounds(box).submit();
         * }
         * }</pre>
         */
        public Draw instances(CgGraphBuffer records, int first, CgGpuCount count) {
            if (first < 0) throw new IllegalArgumentException("a set's first record is " + first);
            set = Objects.requireNonNull(records, "records");
            setFirst = first;
            setCount = Objects.requireNonNull(count, "count");
            return this;
        }

        /**
         * Scales each of its {@link #instances} by {@code scale} about the instance's own origin: one set of records
         * drawn at several sizes, as a particle mesh is by each layer that draws it. 1 by default.
         *
         * <pre>{@code
         * world.draw(billow, smoke)
         *      .instances(range.objects(), range.base(slot), CgGpuCount.at(range.visible(), range.visibleWord(slot), n))
         *      .instanceScale(layer.radius()).custom(0, radius, parameter, age, seed).at(x, y, z).gpuCulled().submit();
         * }</pre>
         */
        public Draw instanceScale(float scale) {
            if (!(scale > 0f)) throw new IllegalArgumentException("instance scale " + scale);
            setScale = scale;
            return this;
        }

        /** Grows the bounds it is culled by on every side, for a vertex shader that displaces. */
        public Draw pad(float radius) {
            pad = radius;
            return this;
        }

        /** Its absolute world position. */
        public Draw at(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
            return this;
        }

        /** A rotation and scale (or any affine transform) about its position. */
        public Draw transform(Matrix4fc transform) {
            this.transform.set(transform);
            return this;
        }

        /** {@code CG_OBJECT_CUSTOM<slot>}, slot 0 to 3: of every kept record, for a draw of {@link #instances}. */
        public Draw custom(int slot, float x, float y, float z, float w) {
            float[] into = records > 0 ? recordCustom : custom;
            int at = (records > 0 ? (records - 1) * 16 : 0) + slot * 4;
            stated |= 1 << slot;
            into[at] = x;
            into[at + 1] = y;
            into[at + 2] = z;
            into[at + 3] = w;
            return this;
        }

        /** Lit by block and sky light {@code block} and {@code sky}, 0 to 15, in place of the world's at its position. */
        public Draw light(float block, float sky) {
            blockLight = block;
            skyLight = sky;
            return this;
        }

        /** Lit as if nothing shaded it: what a thing glowing on its own takes. */
        public Draw fullBright() {
            return light(15f, 15f);
        }

        /**
         * Scales its glow: what its material's Emissive pass draws, through {@code CG_EMISSION}. 1 by default; 0 takes
         * it out of the bloom, its Emissive pass not drawn.
         *
         * <pre>{@code
         * world.draw(orb, plasma).at(x, y, z).emission(1f - age / life).submit();   // a glow fading with its effect
         * }</pre>
         */
        public Draw emission(float scale) {
            emission = Math.max(0f, scale);
            return this;
        }

        /**
         * Keeps it from being bent by the hazes sorted before it, its own effect's included, while nearer hazes still bend
         * it: {@link CgRenderQueue#AFTER_DISTORTION}, for a draw whose material also serves layers that bend. A
         * transparent draw only.
         *
         * <pre>{@code
         * world.draw(tube, beamCore).at(x, y, z).afterDistortion().submit();
         * }</pre>
         *
         * <ul>
         *   <li>Those hazes are applied just before it in the transparent pass. Where four such applies already overlap
         *       it on screen, it draws after every haze instead, over nearer transparent draws.</li>
         * </ul>
         */
        public Draw afterDistortion() {
            queue = CgRenderQueue.AFTER_DISTORTION;
            return this;
        }

        /** Overrides the material's authored queue: {@link CgRenderQueue} values. */
        public Draw queue(int queue) {
            this.queue = queue;
            return this;
        }

        /** The {@link CgSortLayer} it sorts in; {@link CgSortLayer#DEFAULT} unless named. */
        public Draw layer(CgSortLayer layer) {
            this.layer = Objects.requireNonNull(layer, "layer");
            return this;
        }

        /** 0 to 255; higher draws later within its layer, or within its {@link #group}. */
        public Draw order(int order) {
            if (order < 0 || order > 255) throw new IllegalArgumentException("order " + order + " is outside 0..255");
            this.order = order;
            return this;
        }

        /**
         * 0 to 4,095, 0 unless given: within its group and {@link #order}, transparent draws sort by it before distance,
         * back to front only among draws of one key. Draws of one material given one key in one group run together, so
         * the executor joins them into one multi-draw, at the price of blending each material's run over the others'.
         *
         * <pre>{@code
         * world.draw(quads, dust).at(x, y, z).group(gx, gy, gz).batchKey(dustRank).indirect(...).submit();
         * }</pre>
         */
        public Draw batchKey(int key) {
            if (key < 0 || key > 0xFFF) throw new IllegalArgumentException("batch key " + key + " is outside 0..4095");
            this.batchKey = key;
            return this;
        }

        /**
         * Sorts it with the group at absolute {@code (x, y, z)} as one, as Niagara sorts a system's emitters: a
         * transparent group draws whole, back to front by its position among the other groups and draws of its
         * {@link #layer}, and its draws by their own {@link #order}, then distance, within it: back to front only among
         * the draws of one order. Every draw of a group gives
         * the same position and layer. Opaque draws ignore it.
         *
         * <pre>{@code
         * world.draw(mesh, haze).at(x, y, z).layer(CgSortLayer.EFFECTS).group(ox, oy, oz).order(4).submit();
         * }</pre>
         */
        public Draw group(double x, double y, double z) {
            inGroup = true;
            groupX = x;
            groupY = y;
            groupZ = z;
            return this;
        }

        public void submit() {
            if (each && records == 0) return;   // nothing to draw
            boolean skinned = (lods != null ? lods.finest() : mesh).format().isSkinned();
            if (skinned != (bones >= 0)) {
                throw new IllegalStateException(skinned ? mesh + " is skinned: bones(base) names its palette"
                        : "bones() on " + mesh + ", which is not in a skinned format");
            }
            if (skinned && set != null) throw new IllegalStateException("a skinned mesh draws once a palette: not instances()");
            if (set != null) {
                if (customDepth >= 0) throw new IllegalStateException("a set of instances is not drawn into CustomDepth: not customDepth() too");
                if (indirect != null) throw new IllegalStateException("instances() takes its count from its cull: not indirect() too");
                if ((lods != null ? lods.finest() : mesh).bounds(meshBox) == null) {
                    throw new IllegalArgumentException(mesh + " has no bounds to cull its instances by");
                }
                if (lods != null && lods.levelCount() > CgCull.MAX_LEVELS) {
                    throw new IllegalArgumentException(lods.levelCount() + " levels; a set's cull takes " + CgCull.MAX_LEVELS);
                }
            }
            add(this);
        }
    }

    private void add(Draw d) {
        long now = CgFrameRing.frame();
        if (now != frame) {
            clear();
            frame = now;
        }
        draws.reserve();
        draws.meshes[draws.count] = d.mesh;
        draws.lods[draws.count] = d.lods;
        draws.materials[draws.count] = d.material;
        draws.positions[draws.count * 3] = d.x;
        draws.positions[draws.count * 3 + 1] = d.y;
        draws.positions[draws.count * 3 + 2] = d.z;
        d.transform.get(draws.transforms, draws.count * 16);
        System.arraycopy(d.custom, 0, draws.customs, draws.count * 16, 16);
        if (Float.isNaN(d.blockLight)) {
            int light = CgWorldLight.at(d.x, d.y, d.z);
            draws.lights[draws.count * 2] = CgWorldLight.block(light);
            draws.lights[draws.count * 2 + 1] = CgWorldLight.sky(light);
        } else {
            draws.lights[draws.count * 2] = d.blockLight;
            draws.lights[draws.count * 2 + 1] = d.skyLight;
        }
        draws.emissions[draws.count] = d.emission;
        draws.boneBases[draws.count] = d.bones;
        draws.halves[draws.count] = d.half;
        draws.culledOnGpu[draws.count] = d.onGpu;
        draws.gpuGroups[draws.count] = d.gpuGroup;
        draws.subjects[draws.count] = d.subject;
        draws.grades[draws.count] = gradeDraws ? d.grade : null;
        draws.customDepths[draws.count] = d.customDepth;
        draws.queues[draws.count] = d.queue;
        draws.orders[draws.count] = d.order;
        draws.batchKeys[draws.count] = d.batchKey;
        draws.layers[draws.count] = d.layer;
        if (d.material.state().separateTranslucency()
                && d.queue >= CgRenderQueue.TRANSPARENT_THRESHOLD && d.queue < CgRenderQueue.OVERLAY_THRESHOLD) {
            // Unreal's separate translucency: after every other translucent draw, unbent by their distortion
            draws.queues[draws.count] = Math.max(d.queue, CgRenderQueue.AFTER_DISTORTION);
            if (d.layer.rank() < CgSortLayer.SEPARATE_TRANSLUCENCY.rank()) draws.layers[draws.count] = CgSortLayer.SEPARATE_TRANSLUCENCY;
        }
        draws.grouped[draws.count] = d.inGroup;
        if (d.inGroup) {
            draws.groupPositions[draws.count * 3] = d.groupX;
            draws.groupPositions[draws.count * 3 + 1] = d.groupY;
            draws.groupPositions[draws.count * 3 + 2] = d.groupZ;
        }
        draws.ranges[draws.count * 3] = d.submesh;
        draws.ranges[draws.count * 3 + 1] = d.first;
        draws.ranges[draws.count * 3 + 2] = d.count;
        draws.boundsStated[draws.count] = d.boundsSet;
        if (d.boundsSet) System.arraycopy(d.bounds, 0, draws.drawBounds, draws.count * 6, 6);
        draws.pads[draws.count] = d.pad;
        draws.counts[draws.count] = d.indirect;
        draws.countOffsets[draws.count] = d.indirectOffset;
        draws.countModes[draws.count] = d.indirectMode;
        draws.countFactors[draws.count] = d.indirectFactor;
        draws.sets[draws.count] = d.set;
        draws.setFirsts[draws.count] = d.setFirst;
        draws.customsStated[draws.count] = d.stated;
        draws.setScales[draws.count] = d.setScale;
        draws.setCounts[draws.count] = d.setCount;
        System.arraycopy(d.buffer, 0, draws.buffers, draws.count * CgDrawChunk.BUFFERS, CgDrawChunk.BUFFERS);
        System.arraycopy(d.bufferPoint, 0, draws.bufferAt, draws.count * CgDrawChunk.BUFFERS, CgDrawChunk.BUFFERS);
        draws.recordCounts[draws.count] = d.each ? d.records : 0;
        if (d.each) {
            draws.recordFirsts[draws.count] = draws.records;
            draws.reserveRecords(d.records);
            int n = draws.records + d.records;
            System.arraycopy(d.recordAt, 0, draws.recordPositions, draws.records * 3, d.records * 3);
            System.arraycopy(d.recordCustom, 0, draws.recordCustoms, draws.records * 16, d.records * 16);
            System.arraycopy(d.recordCount, 0, draws.recordOffsets, draws.records, d.records);
            draws.records = n;
        }
        draws.count++;
    }

    private void clear() {
        text.clear();
        draws.clear();
    }

    // ── Recording ────────────────────────────────────────────────────────────────────────────────

    static final int OPAQUE = 0, TRANSPARENT = 1;

    /**
     * This frame's opaque draws as the lights' shadow casters, each traced through its mesh's distance field (Unreal's
     * bCastShadow is on for every opaque primitive). Draws whose geometry the CPU cannot see cast none: instance sets,
     * indirect counts, half-size draws and meshes the shader places.
     */
    private void casters() {
        for (int i = 0; i < draws.count; i++) {
            CgMesh mesh = draws.lods[i] != null ? draws.lods[i].finest() : draws.meshes[i];
            // A transparent draw that is a solid drawn late (a program lighting itself) casts as opaque ones do; one that
            // blends never does, though it write depth (a beam's closed core), or it shadows its own lights.
            if (mesh == null || draws.sets[i] != null || draws.counts[i] != null || draws.halves[i] || draws.culledOnGpu[i]
                    || draws.queues[i] >= CgRenderQueue.TRANSPARENT_THRESHOLD && !draws.materials[i].solid()) {
                continue;
            }
            pointLights.caster(mesh, draws.ranges[i * 3], draws.ranges[i * 3 + 1], draws.ranges[i * 3 + 2], draws.positions[i * 3],
                    draws.positions[i * 3 + 1], draws.positions[i * 3 + 2], draws.transforms, i * 16);
        }
    }
    /** The clip-space w a draw's screen rect is cut at: nearer than any host's near plane (Minecraft's is 0.05). */
    private static final float NEAR_W = 0.01f;

    float targetWidth, targetHeight;

    private void recordOpaque(CgStageFrame stage) {
        record(stage, OPAQUE);
    }

    private void recordTransparent(CgStageFrame stage) {
        record(stage, TRANSPARENT);
    }

    /**
     * Onto the lit opaque scene, before any transparent draw: what the firing's blackboard hands to record there (the
     * post stack's before-translucency materials, Unreal's {@code BL_BeforeTranslucency}). The renderer records nothing
     * of the post stack's itself.
     */
    private static void beforeTranslucency(CgStageFrame stage) {
        CgStageHook hook = stage.resources().get(CgFrameKeys.BEFORE_TRANSLUCENCY);
        if (hook != null) hook.record(stage);
    }

    private void record(CgStageFrame stage, int which) {
        if (!CgRehearsal.active()) {
            recordFiring(stage, which);
            return;
        }
        // A rehearsal's firing records no labels, whose uploads are the real frame's; its draws were its own.
        try {
            recordFiring(stage, which);
        } finally {
            if (which == TRANSPARENT) {
                clear();
                pointLights.dropRehearsed();
            }
        }
    }

    private void recordFiring(CgStageFrame stage, int which) {
        commands.firing++;
        CgHostView view = stage.host().view();
        beginFrame(view);
        if (draws.count == 0) {
            if (which == TRANSPARENT) {
                // Lights fall on the opaque scene, so a frame drawing nothing of ours still lights it.
                targetWidth = stage.host().width();
                targetHeight = stage.host().height();
                prepare(view);
                pointLights.record(stage, view, toWorld, targetWidth, targetHeight);
                pointLights.apply(stage, stage.recording(), false, false);
                beforeTranslucency(stage);
                if (!CgRehearsal.active()) text.record(stage, view);
            }
            return;
        }
        if (!irisWarned && CgShaderPacks.packInUse()) {
            irisWarned = true;
            LOGGER.warn("A shader pack is active in " + CgShaderPacks.mod() + ": the world renderer draws into the main framebuffer, "
                    + "outside its deferred G-buffer, so its geometry is unlit under a deferred pack. cg_DepthBuffer "
                    + "remains valid.");
        }
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.WORLD, which == OPAQUE ? "world.recordOpaque" : "world.recordTransparent")) {
            targetWidth = stage.host().width();
            targetHeight = stage.host().height();
            try (CgTrace.Zone z = CgTrace.zone(CgChannels.WORLD, REC_PREPARE)) {
                prepare(view);
                if (commands.palettesFrame != frame && CgBonePalettes.written() > 0) {
                    // Once a frame, in its first stage: a persistent buffer, so the later stage reads the same upload.
                    commands.palettesFrame = frame;
                    commands.palettes = CgBonePalettes.record(stage.recording());
                }
            }
            CgTrace.Zone classifying = CgTrace.zone(CgChannels.WORLD, REC_CLASSIFY);
            Arrays.fill(draws.culled, 0, draws.count, null);
            Arrays.fill(draws.culledCounts, 0, draws.count, null);
            cullPasses = 0;
            boolean prepass = false, surfaceLit = false, handed = false;
            int drawn = 0;
            for (int i = 0; i < draws.count; i++) {
                byte p = classify(i, which, view);
                draws.phase[i] = p;
                if (p == CgWorldDraws.SKIP) continue;
                drawn++;
                prepass |= p == CgWorldDraws.FORWARD_AND_PREPASS;
                for (CgMaterial link = draws.materials[i]; link != null; link = link.getNextPass()) {
                    surfaceLit |= which == TRANSPARENT && link.surfaceLit();
                    handed |= link.handedLights() || link.handedSun();
                }
            }
            CgTrace.counter(CgChannels.WORLD, which == OPAQUE ? "world.opaqueDraws" : "world.transparentDraws", drawn);
            int halfDrawn = 0, volumeDrawn = 0;
            if (which == TRANSPARENT && halfResolution) {
                for (int i = 0; i < draws.count; i++) {
                    if (draws.phase[i] == CgWorldDraws.SKIP || !draws.halves[i]) continue;
                    if (covers(draws.materials[i])) {
                        draws.phase[i] = CgWorldDraws.VOLUME;
                        volumeDrawn++;
                    } else {
                        draws.phase[i] = CgWorldDraws.HALF;
                        halfDrawn++;
                    }
                }
                CgTrace.counter(CgChannels.WORLD, "world.halfDraws", halfDrawn);
                CgTrace.counter(CgChannels.WORLD, "world.volumeDraws", volumeDrawn);
            }
            int afterDrawn = 0;
            apartDrawn = 0;
            separate.reset();
            if (which == TRANSPARENT) {
                for (int i = 0; i < draws.count; i++) {
                    if (draws.phase[i] != CgWorldDraws.FORWARD || draws.queues[i] < CgRenderQueue.AFTER_DISTORTION_THRESHOLD) continue;
                    draws.phase[i] = CgWorldDraws.AFTER;
                    afterDrawn++;
                }
                apartDrawn = gradeLayerPass.separateDraws(stage);
                for (int i = 0; i < draws.count; i++) prepass |= prepassed(i);
            }
            classifying.close();
            CgTrace.Zone top = CgTrace.zone(CgChannels.WORLD, REC_TOP);

            CgRecording recording = stage.recording();
            CgTrace.Zone step = CgTrace.zone(CgChannels.GL_DETAIL, TOP_NORMALS);
            normalsInto = which == OPAQUE && drawn > 0 ? normalsFor(stage, recording) : null;
            if (which == OPAQUE) normalsWritten = normalsInto;
            CgPassConstants constants = stage.constants().sceneGlow(sceneEmission);
            commands.bindings.clear();
            // Before the opaque pass: its depth then holds the host's world and none of the draws below.
            Integer subjectKey = which == OPAQUE ? stage.resources().get(CgFrameKeys.SUBJECT_READ) : null;
            if (subjectKey != null && subjectKey != 0) emissivePass.recordOwnedSubject(stage, recording, view, subjectKey);
            step.close();
            step = CgTrace.zone(CgChannels.GL_DETAIL, TOP_OPAQUE_SCENE);
            // Our opaque draws into a linear scene of their own, as Unreal's base pass into SceneColor: past white kept
            boolean opaqueScene = which == OPAQUE && drawn > 0 && sceneTarget.recordOpaqueIn(stage);
            if (opaqueScene) constants = stage.constants().sceneGlow(sceneEmission);
            step.close();
            step = CgTrace.zone(CgChannels.GL_DETAIL, TOP_DISTORTION);
            int distorting = 0;
            if (drawn > 0 && which == TRANSPARENT) {
                if (distorts.length < draws.meshes.length) distorts = new boolean[draws.meshes.length];
                // Separate translucency is composited after the distortion, unbent, as Unreal's
                for (int i = 0; i < draws.count; i++) {
                    distorts[i] = draws.phase[i] != CgWorldDraws.SKIP && draws.phase[i] != CgWorldDraws.APART && !halfSize(i) && distortsScene(draws.materials[i]);
                }
                // The apply is a pass of the renderer's own: skipped while unbuilt, its hazes with it, each counted.
                if (commands.skipUnready && !distortion.precache()) {
                    for (int i = 0; i < draws.count; i++) {
                        if (!distorts[i]) continue;
                        distorts[i] = false;
                        commands.unreadyDraws++;
                        CgTrace.add(CgChannels.GL, CgMeshDrawCommands.UNREADY_DRAWS, 1);
                    }
                }
                hazes.view = view;
                distorting = distortion.plan(hazes, targetWidth, targetHeight);
                afterDrawn = 0;
                for (int i = 0; i < draws.count; i++) if (draws.phase[i] == CgWorldDraws.AFTER) afterDrawn++;
            }
            step.close();
            // Under the HDR scene a glow is light added into the scene itself, where what draws in front covers it.
            boolean scene = which == TRANSPARENT && stage.resources().has(CgFrameKeys.SCENE);
            volumeFolds = scene && mergeEmission;
            // Before any glow: a label's text then hides the glows behind it, as it hides their colour.
            step = CgTrace.zone(CgChannels.GL_DETAIL, TOP_LABELS);
            if (which == TRANSPARENT && !CgRehearsal.active()) text.recordDepth(stage, view);
            step.close();
            step = CgTrace.zone(CgChannels.GL_DETAIL, TOP_CUSTOM);
            if (which == TRANSPARENT && stage.resources().has(CgFrameKeys.CUSTOM_DATA_READ)) customDataPass.recordCustomData(stage, recording, view);
            // Custom depth and normals before the lights, as Unreal's after its base pass: what reads them before
            // translucency needs them published
            if (which == TRANSPARENT) customDepthPass.recordCustomDepth(stage, recording, view);
            if (which == TRANSPARENT && normalsWritten != null) {
                stage.resources().put(CgFrameKeys.WORLD_NORMALS, normalsWritten);
                normalsWritten = null;
            }
            step.close();
            top.close();
            // Lights fall on the opaque scene, so a firing with nothing transparent still lists them.
            lit = which == TRANSPARENT && pointLights.record(stage, view, toWorld, targetWidth, targetHeight);
            if (which == TRANSPARENT) casters();
            // Onto the opaque scene, before any transparent draw blends over it; the sun's shadows run with no light.
            if (which == TRANSPARENT) pointLights.apply(stage, recording, drawn > 0, surfaceLit);
            // After the grid is binned in a transparent firing; an opaque one reads the last frame's.
            if (handed) handLights(recording, which == TRANSPARENT);
            // Something transparent reads the air, or a light may glow in it.
            try (CgTrace.Zone z = CgTrace.zone(CgChannels.WORLD, REC_MEDIA)) {
                mediaOut = which == TRANSPARENT && (drawn > 0 || media.holdsAny())
                        ? media.record(recording, stage.constants(), view, toWorld, targetWidth, targetHeight, pointLights) : null;
                if (mediaOut != null) media.composite(stage, recording, mediaOut);
                if (scene) emissivePass.recordSceneGlows(stage, recording, view);
            }
            if (which == TRANSPARENT) beforeTranslucency(stage);
            // The graded layers made, and the one the transparent passes write beside the scene cleared (T12 L3)
            if (scene) gradeLayerPass.prepareLayers(stage, recording, view, constants);
            if (drawn > 0) {
                try (CgTrace.Zone z = CgTrace.zone(CgChannels.WORLD, REC_CULL)) {
                    cullSets(stage, recording, view, CULL_PASSES);
                }
                CgTrace.Zone passes = CgTrace.zone(CgChannels.WORLD, REC_PASSES);
                if (prepass) basePass.recordPass(stage, recording, constants, OPAQUE_STATE, true, false, view, false);
                if (halfDrawn > 0) halfPass.recordHalf(stage, recording, view);
                volumeOut = volumeDrawn > 0 ? volumePass.recordVolumes(stage, recording, view) : null;
                if (distorting > 0) distortion.recordBends(stage, recording, hazes);
                if (drawn > halfDrawn + volumeDrawn + afterDrawn + apartDrawn || volumeOut != null) {
                    basePass.recordPass(stage, recording, constants, which == OPAQUE ? OPAQUE_STATE : TRANSPARENT_STATE, false, false,
                            view, scene);
                }
                if (distorting > 0) distortion.recordFinal(stage, recording, hazes);
                if (afterDrawn > 0) basePass.recordPass(stage, recording, constants, TRANSPARENT_STATE, false, true, view, scene);
                if (apartDrawn > 0) gradeLayerPass.recordSeparate(stage, recording, view, constants);
                passes.close();
            }
            // With nothing transparent too: a graded opaque or masked draw has its layer all the same
            if (scene) gradeLayerPass.recordGradeLayers(stage, recording, view, constants);
            if (opaqueScene) sceneTarget.recordOpaqueOut(stage);
            CgTrace.Zone after = CgTrace.zone(CgChannels.WORLD, REC_AFTER);
            if (which == TRANSPARENT && stage.resources().has(CgFrameKeys.SUBJECT_READ)) {
                emissivePass.recordSubject(stage, recording, view, stage.resources().get(CgFrameKeys.SUBJECT_READ));
            }
            if (which == TRANSPARENT && overdraw && drawn > 0) overdrawPass.recordOverdraw(stage, recording, view);
            if (which == TRANSPARENT && !CgRehearsal.active()) text.record(stage, view);
            after.close();
        }
    }

    /** The light grid and the sun's map bound to every drawn material reading what it is handed of them. */
    private void handLights(CgRecording recording, boolean transparent) {
        for (int i = 0; i < draws.count; i++) {
            if (draws.phase[i] == CgWorldDraws.SKIP) continue;
            for (CgMaterial link = draws.materials[i]; link != null; link = link.getNextPass()) {
                if (link.handedLights()) pointLights.hand(link, recording);
                if (link.handedSun()) pointLights.handSun(link, transparent);
            }
        }
    }

    /** The draws as {@link CgWorldDistortion} sees them, under the view of the firing that planned it. */
    private final class Hazes implements CgWorldDistortion.Draws {

        CgHostView view;

        @Override
        public int count() {
            return draws.count;
        }

        @Override
        public boolean distorts(int i) {
            return distorts[i];
        }

        @Override
        public boolean inPass(int i) {
            return draws.phase[i] == CgWorldDraws.FORWARD || draws.phase[i] == CgWorldDraws.AFTER;
        }

        @Override
        public boolean sharp(int i) {
            return draws.phase[i] == CgWorldDraws.AFTER;
        }

        @Override
        public void inPlace(int i) {
            draws.phase[i] = CgWorldDraws.FORWARD;
        }

        @Override
        public long key(int i) {
            return draws.keys[i];
        }

        @Override
        public void rect(int i, float[] out) {
            if (Float.isNaN(draws.screens[i * 4])) {
                out[0] = 0f;
                out[1] = 0f;
                out[2] = targetWidth;
                out[3] = targetHeight;
            } else {
                System.arraycopy(draws.screens, i * 4, out, 0, 4);
            }
        }

        @Override
        public void drawDistortion(CgChunkBuilder chunks, CgRecording recording, int i) {
            for (CgMaterial link = draws.materials[i]; link != null; link = link.getNextPass()) {
                if (!link.hasDistortionPass()) continue;
                CgPipeline pipeline = link.pipeline(CgRenderPassVariant.DISTORTION, CgInstanceKind.OBJECT);
                if (pipeline == null || commands.unready(pipeline, i)) continue;
                if (draws.sets[i] != null) {
                    commands.drawSet(chunks, pipeline, commands.bindingOf(link, recording), i);
                    continue;
                }
                chunks.draw(pipeline, commands.bindingOf(link, recording), draws.meshes[i]).sortKey(draws.keys[i]);
                commands.group(chunks, i);
                commands.writeInstance(chunks, i);
            }
        }
    }

    /** Whether any link of a material chain has a Distortion pass. */
    private static boolean distortsScene(CgMaterial material) {
        for (CgMaterial link = material; link != null; link = link.getNextPass()) {
            if (link.hasDistortionPass()) return true;
        }
        return false;
    }

    /** A half float holding a whole count, as an int; below 1 is 0. */
    static int halfCount(short half) {
        int exponent = (half >>> 10) & 0x1f;
        return exponent == 0 ? 0 : Math.round(Math.scalb((float) (1024 + (half & 0x3ff)), exponent - 25));
    }

    /**
     * The normal target the opaque pass writes beside the host's target this firing, cleared, and handed to the world
     * lights; null where neither they (casting shadows) nor a reader ({@link CgFrameKeys#WORLD_NORMALS_READ}) asks, or
     * the host's framebuffer takes no second attachment, which is warned once: the lights then rebuild normals from depth.
     */
    @Nullable
    private CgGraphTexture normalsFor(CgStageFrame stage, CgRecording recording) {
        int fb = stage.host().mainFramebuffer();
        boolean asked = pointLights.wantsNormals() || stage.resources().has(CgFrameKeys.WORLD_NORMALS_READ);
        if (!asked) return null;
        if (fb <= 0 || CgRasterPass.refusesAttachment(fb)) {
            if (!normalsRefusedNoted) {
                normalsRefusedNoted = true;
                LOGGER.warn("Framebuffer {} takes no normal target: the world lights rebuild our surfaces' normals from depth,"
                        + " snapped to world axes as blocks', so a curved mesh shades in facets. Draw the world into a"
                        + " framebuffer of the host's own", fb);
            }
            return null;
        }
        int w = Math.max(1, (int) targetWidth), h = Math.max(1, (int) targetHeight);
        if (normalTarget == null || normalTarget.getWidth() != w || normalTarget.getHeight() != h) {
            if (normalTarget != null) recording.release(normalTarget);
            normalTarget = CgGraphTexture.requested("cg_world.normals", new CgTextureDesc(w, h, NORMAL_FORMAT));
        }
        recording.raster(normalTarget, CLEAR, stage.constants(), null, CgOrder.SORTED).end();
        pointLights.normals(normalTarget);
        return normalTarget;
    }

    /** Whether draw {@code i} draws at half size this firing, added or as a volume. */
    boolean halfSize(int i) {
        return draws.phase[i] == CgWorldDraws.HALF || draws.phase[i] == CgWorldDraws.VOLUME;
    }

    /** Whether a material's Forward pass covers what is behind it, premultiplied: a volume's blend. */
    private static boolean covers(CgMaterial material) {
        CgPipeline forward = material.pipeline(CgInstanceKind.OBJECT);
        CgBlendState blend = forward == null ? null : forward.state().getBlend();
        return blend != null && blend.enabled() && blend.srcRgb() == CgGL.GL_ONE
                && blend.dstRgb() == CgGL.GL_ONE_MINUS_SRC_ALPHA;
    }

    /** Whether every Emissive pass of draw {@code i}'s chain folds into its Forward draw under the scene. */
    boolean allFold(int i) {
        for (CgMaterial link = draws.materials[i]; link != null; link = link.getNextPass()) {
            if (!link.hasEmissivePass()) continue;
            CgPipeline forward = link.pipeline(CgInstanceKind.OBJECT);
            if (forward == null || forward.as(CgPipelineForm.FOLD) == null) return false;
        }
        return true;
    }

    /** Whether any link of a material chain has an Emissive pass. */
    static boolean emitsLight(CgMaterial material) {
        for (CgMaterial link = material; link != null; link = link.getNextPass()) {
            if (link.hasEmissivePass()) return true;
        }
        return false;
    }

    /** The frustum, the eye and its forward, in the view's camera-relative space. */
    private void prepare(CgHostView view) {
        commands.recordView = view;
        viewProjection.set(view.projection()).mul(view.view());
        frustum.set(viewProjection);
        toWorld.set(view.view()).invert();
        toWorld.transformPosition(commands.eye.set(0f, 0f, 0f));
        toWorld.transformDirection(commands.forward.set(0f, 0f, -1f)).normalize();
    }

    /** Whether draw {@code i} takes part in this stage, and its sort key. */
    byte classify(int i, int which, CgHostView view) {
        int queue = draws.queues[i];
        boolean transparent = queue >= CgRenderQueue.TRANSPARENT_THRESHOLD;
        if (queue >= CgRenderQueue.OVERLAY_THRESHOLD || transparent != (which == TRANSPARENT)) return CgWorldDraws.SKIP;
        if (draws.sets[i] != null && draws.setCounts[i].capacity() == 0) return CgWorldDraws.SKIP;
        commands.modelOf(i, view);
        float cx, cy, cz;
        float[] bounds;
        if (draws.culledOnGpu[i]) {
            bounds = null;
        } else if (draws.boundsStated[i]) {
            System.arraycopy(draws.drawBounds, i * 6, meshBounds, 0, 6);
            bounds = meshBounds;
        } else if (draws.sets[i] != null) {
            bounds = null;   // a set's instances are draws.culled one by one, on the GPU
        } else if ((draws.lods[i] != null ? draws.lods[i].finest() : draws.meshes[i]).bounds(meshAabb) != null) {
            meshBounds[0] = (float) meshAabb.minX;
            meshBounds[1] = (float) meshAabb.minY;
            meshBounds[2] = (float) meshAabb.minZ;
            meshBounds[3] = (float) meshAabb.maxX;
            meshBounds[4] = (float) meshAabb.maxY;
            meshBounds[5] = (float) meshAabb.maxZ;
            bounds = meshBounds;
        } else {
            bounds = null;
        }
        if (bounds != null) {
            float p = draws.pads[i];
            commands.model.transformAab(bounds[0] - p, bounds[1] - p, bounds[2] - p, bounds[3] + p, bounds[4] + p, bounds[5] + p,
                    min, max);
            if (!frustum.testAabb(min.x, min.y, min.z, max.x, max.y, max.z)) return CgWorldDraws.SKIP;
            screenOf(i);
            cx = (min.x + max.x) * 0.5f;
            cy = (min.y + max.y) * 0.5f;
            cz = (min.z + max.z) * 0.5f;
            if (draws.lods[i] != null && draws.sets[i] == null) {
                CgMesh level = draws.lods[i].pick(screenHeight(cx, cy, cz, view));
                if (level == null) return CgWorldDraws.SKIP;
                draws.meshes[i] = level;
            }
        } else {
            draws.screens[i * 4] = Float.NaN;
            if (draws.lods[i] != null) draws.meshes[i] = draws.lods[i].finest();
            cx = commands.model.m30();
            cy = commands.model.m31();
            cz = commands.model.m32();
        }
        float distance = Math.max(0f, (cx - commands.eye.x) * commands.forward.x + (cy - commands.eye.y) * commands.forward.y + (cz - commands.eye.z) * commands.forward.z);
        CgMaterial material = draws.materials[i];
        draws.keys[i] = transparent
                ? transparentKey(i, queue, distance, view)
                : CgSortKey.opaque(queue, draws.layers[i].rank(), draws.orders[i], material.getMaterialId(), System.identityHashCode(draws.meshes[i]), distance);
        boolean prepass = !transparent && (material.hasDepthPass() || queue >= CgRenderQueue.ALPHA_TEST_THRESHOLD);
        return prepass ? CgWorldDraws.FORWARD_AND_PREPASS : CgWorldDraws.FORWARD;
    }

    /** Draw {@code i}'s transparent key: its layer, its group's distance, then its own order, batch key and distance. */
    private long transparentKey(int i, int queue, float distance, CgHostView view) {
        int layer = draws.layers[i].rank();
        boolean solid = draws.materials[i].writesDepth();
        if (!draws.grouped[i]) return CgSortKey.transparent(queue, layer, distance, solid, draws.orders[i], draws.batchKeys[i], distance);
        float gx = (float) (draws.groupPositions[i * 3] - view.x()) - commands.eye.x;
        float gy = (float) (draws.groupPositions[i * 3 + 1] - view.y()) - commands.eye.y;
        float gz = (float) (draws.groupPositions[i * 3 + 2] - view.z()) - commands.eye.z;
        float groupDistance = Math.max(0f, gx * commands.forward.x + gy * commands.forward.y + gz * commands.forward.z);
        return CgSortKey.transparent(queue, layer, groupDistance, solid, draws.orders[i], draws.batchKeys[i], distance);
    }

    /**
     * The fraction of the screen's height the sphere around {@code min}..{@code max}, centred on {@code (cx, cy, cz)},
     * covers: its diameter projected at its centre's depth. Above 1 with the eye inside it.
     */
    private float screenHeight(float cx, float cy, float cz, CgHostView view) {
        float r = 0.5f * (float) Math.sqrt((max.x - min.x) * (max.x - min.x) + (max.y - min.y) * (max.y - min.y)
                + (max.z - min.z) * (max.z - min.z));
        float w = viewProjection.m03() * cx + viewProjection.m13() * cy + viewProjection.m23() * cz + viewProjection.m33();
        if (w <= r) return Float.MAX_VALUE;
        return r * Math.abs(view.projection().m11()) / w;
    }

    /**
     * Draw {@code i}'s box {@code min}..{@code max} on screen, into {@link CgWorldDraws#screens}: what the frame graph cuts a copy
     * of the target to. A box crossing the near plane is cut by it, its edges' crossings projected with the corners in
     * front; NaN for a box wholly behind it.
     */
    private void screenOf(int i) {
        Matrix4f m = viewProjection;
        for (int c = 0; c < 8; c++) {
            float x = (c & 1) == 0 ? min.x : max.x, y = (c & 2) == 0 ? min.y : max.y, z = (c & 4) == 0 ? min.z : max.z;
            clipX[c] = m.m00() * x + m.m10() * y + m.m20() * z + m.m30();
            clipY[c] = m.m01() * x + m.m11() * y + m.m21() * z + m.m31();
            clipW[c] = m.m03() * x + m.m13() * y + m.m23() * z + m.m33();
        }
        float x0 = Float.POSITIVE_INFINITY, y0 = Float.POSITIVE_INFINITY, x1 = Float.NEGATIVE_INFINITY, y1 = Float.NEGATIVE_INFINITY;
        for (int c = 0; c < 8; c++) {
            if (clipW[c] >= NEAR_W) {
                float nx = clipX[c] / clipW[c], ny = clipY[c] / clipW[c];
                x0 = Math.min(x0, nx);
                x1 = Math.max(x1, nx);
                y0 = Math.min(y0, ny);
                y1 = Math.max(y1, ny);
            }
            for (int bit = 1; bit < 8; bit <<= 1) {
                if ((c & bit) != 0) continue;
                int d = c | bit;
                if ((clipW[c] >= NEAR_W) == (clipW[d] >= NEAR_W)) continue;
                // An edge through the near plane: where it crosses, in front of the eye.
                float t = (NEAR_W - clipW[c]) / (clipW[d] - clipW[c]);
                float nx = (clipX[c] + (clipX[d] - clipX[c]) * t) / NEAR_W;
                float ny = (clipY[c] + (clipY[d] - clipY[c]) * t) / NEAR_W;
                x0 = Math.min(x0, nx);
                x1 = Math.max(x1, nx);
                y0 = Math.min(y0, ny);
                y1 = Math.max(y1, ny);
            }
        }
        if (x0 > x1) {
            draws.screens[i * 4] = Float.NaN;
            return;
        }
        draws.screens[i * 4] = (x0 * 0.5f + 0.5f) * targetWidth;
        draws.screens[i * 4 + 1] = (0.5f - y1 * 0.5f) * targetHeight;
        draws.screens[i * 4 + 2] = (x1 * 0.5f + 0.5f) * targetWidth;
        draws.screens[i * 4 + 3] = (0.5f - y0 * 0.5f) * targetHeight;
    }

    static final CgLoad CLEAR_LAYER = CgLoad.clear(0f, 0f, 0f, 0f);
    static final int GPU_GRADE = CgGpuTrace.name("world.gradeLayers");

    static final int GPU_SEPARATE = CgGpuTrace.name("world.separateTranslucency");
    final CgSeparateTranslucency separate = new CgSeparateTranslucency();

    /** {@code pass} reading this firing's media, where anything is in the air. */
    void mediated(CgRasterPass pass) {
        if (mediaOut != null) pass.texture(CgBindingPoints.MEDIA_TEXTURE_UNIT, mediaOut);
        CgGraphTexture lit = pointLights.localLights();
        if (lit != null) pass.texture(CgBindingPoints.LOCAL_LIGHTS_TEXTURE_UNIT, lit);
    }

    /** What {@link #cullSets} culls: the sets the stage's passes draw, those its bloom draws, those its graded layers draw. */
    static final int CULL_PASSES = 0, CULL_EMITTING = 1, CULL_LAYERS = 2;

    /**
     * Culls on the GPU every set this stage draws that it has not culled yet, all at once: those {@code which} names.
     * The first asks for the stage's depth pyramid.
     */
    void cullSets(CgStageFrame stage, CgRecording recording, CgHostView view, int which) {
        int k = -1;
        CgCullSets batch = null;
        for (int i = 0; i < draws.count; i++) {
            draws.cullHandles[i] = -1;
            if (!culls(i, which)) continue;
            if (batch == null) {
                k = cullPasses++;
                if (k == cullOut.length) {
                    cullOut = Arrays.copyOf(cullOut, k * 2);
                    cullLevels = Arrays.copyOf(cullLevels, k * 2);
                    cullBatches = Arrays.copyOf(cullBatches, k * 2);
                }
                if (cullBatches[k] == null) cullBatches[k] = new CgCullSets();
                batch = cullBatches[k].clear();
            }
            draws.cullHandles[i] = batch.add(placeCull(i, view), draws.sets[i], draws.setFirsts[i], draws.setCounts[i]);
        }
        if (batch == null) return;
        long bytes = (long) batch.records() * CgGpuOps.cullRecordBytes(), words = batch.words() * 4L;
        if (cullOut[k] == null || cullOut[k].size() < bytes) {
            cullOut[k] = CgGraphBuffer.transientBuffer("cg_world.culled", CgBufferDesc.of(bytes, CgBufferUsage.STORAGE));
        }
        if (cullLevels[k] == null || cullLevels[k].size() < words) {
            cullLevels[k] = CgGraphBuffer.transientBuffer("cg_world.kept",
                    CgBufferDesc.of(words, CgBufferUsage.STORAGE, CgBufferUsage.INDIRECT));
        }
        cull.view(view.view(), view.projection()).pyramid(stage.depthPyramid());
        CgComputePass pass = recording.compute("world.cull");
        CgGpuOps.cull(pass, cull, batch, cullOut[k], cullLevels[k]);
        pass.end();
        for (int i = 0; i < draws.count; i++) {
            int h = draws.cullHandles[i];
            if (h < 0) continue;
            draws.culled[i] = cullOut[k];
            draws.culledCounts[i] = cullLevels[k];
            draws.culledFirst[i] = batch.first(h);
            draws.culledWord[i] = batch.word(h);
        }
    }

    /** Whether this {@link #cullSets} culls draw {@code i}: a set not culled yet that the stage, or its bloom, draws. */
    private boolean culls(int i, int which) {
        if (draws.sets[i] == null || draws.culled[i] != null) return false;
        return which == CULL_EMITTING ? emissivePass.emits[i] : which == CULL_LAYERS ? gradeLayerPass.inLayer[i] : draws.phase[i] != CgWorldDraws.SKIP;
    }

    /** The cull set to draw {@code i}'s set: its mesh, placed by its model, its pad, light, scale and customs. */
    private CgCull placeCull(int i, CgHostView view) {
        if (draws.lods[i] != null) cull.mesh(draws.lods[i]);
        else cull.mesh(draws.meshes[i]);
        commands.modelOf(i, view);
        cull.place(commands.model).pad(draws.pads[i]).light(draws.lights[i * 2], draws.lights[i * 2 + 1]).scale(draws.setScales[i]).ownCustoms();
        for (int k = 0; k < 4; k++) {
            int c = i * 16 + k * 4;
            if ((draws.customsStated[i] & 1 << k) != 0) cull.custom(k, draws.customs[c], draws.customs[c + 1], draws.customs[c + 2], draws.customs[c + 3]);
        }
        return cull;
    }

    /** Whether transparent draw {@code i} draws into the prepass: its material's {@code "Depth" = "Prepass"}. */
    boolean prepassed(int i) {
        return (draws.phase[i] == CgWorldDraws.FORWARD || draws.phase[i] == CgWorldDraws.AFTER || draws.phase[i] == CgWorldDraws.APART) && draws.queues[i] >= CgRenderQueue.TRANSPARENT_THRESHOLD
                && draws.materials[i].hasDepthPrepass();
    }

}
