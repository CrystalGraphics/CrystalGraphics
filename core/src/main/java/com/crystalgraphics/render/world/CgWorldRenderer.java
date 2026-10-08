package com.crystalgraphics.render.world;

import com.crystalgraphics.api.CgBindingPoints;
import com.crystalgraphics.api.framebuffer.CgFrameBufferFormat;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgRenderPassVariant;
import com.crystalgraphics.api.material.CgRenderQueue;
import com.crystalgraphics.api.state.CgBlendState;
import com.crystalgraphics.api.state.CgColorMask;
import com.crystalgraphics.api.state.CgDepthState;
import com.crystalgraphics.api.state.CgRenderState;
import com.crystalgraphics.api.text.CgShapedParagraph;
import com.crystalgraphics.api.text.CgTextLayout;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.compute.ops.CgCull;
import com.crystalgraphics.compute.ops.CgCullSets;
import com.crystalgraphics.compute.ops.CgGpuCount;
import com.crystalgraphics.compute.ops.CgGpuOps;
import com.crystalgraphics.gl.buffer.CgFrameRing;
import com.crystalgraphics.gl.buffer.CgReadback;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshLods;
import com.crystalgraphics.api.mesh.CgMeshTopology;
import com.crystalgraphics.mc.compat.CgIrisCompat;
import com.crystalgraphics.platform.gl.CgGL;
import javax.annotation.Nullable;
import com.crystalgraphics.render.mesh.CgMeshStore;
import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.render.CgViewFrustum;
import com.crystalgraphics.render.draw.CgBufferHandle;
import com.crystalgraphics.render.draw.CgChunkBuilder;
import com.crystalgraphics.render.draw.CgIndirect;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.draw.CgOrder;
import com.crystalgraphics.render.draw.CgPassConstants;
import com.crystalgraphics.render.draw.CgPipeline;
import com.crystalgraphics.render.graph.CgBufferDesc;
import com.crystalgraphics.render.graph.CgBufferUsage;
import com.crystalgraphics.render.graph.CgComputePass;
import com.crystalgraphics.render.graph.CgGraphBuffer;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgLoad;
import com.crystalgraphics.render.graph.CgRasterPass;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.graph.CgTextureDesc;
import com.crystalgraphics.render.stage.CgHostView;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.render.stage.CgFrameKeys;
import com.crystalgraphics.render.stage.CgStageFrame;
import com.crystalgraphics.settings.CgGraphicsSettings;
import com.crystalgraphics.settings.CgQuality;
import com.crystalgraphics.trace.CgGpuTrace;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Draws meshes into a host's world at its two world stages, under the host's own camera: submitted at absolute
 * positions in doubles, culled against the view, sorted, and instanced where neighbours share a mesh and a material.
 *
 * <pre>{@code
 * CgWorldRenderer world = CgWorldRenderer.get();
 * world.onFrame(view -> {                                // once a frame, before the world records
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
 *   <li>A material with a {@code "LightMode" = "Emissive"} pass glows: after the transparent pass that pass is drawn
 *       into the emission target ({@link #emissionScale} of the world's size), where the scene's depth hides it, and
 *       published as {@code CgFrameKeys.EMISSION} for the post stack to bloom.</li>
 *   <li>A transparent draw marked {@link Draw#halfResolution()} draws at half the target's size before the transparent
 *       pass and is added over the target, depth-aware ({@link #halfResolution(boolean)} turns it off).</li>
 * </ul>
 */
public final class CgWorldRenderer {

    /** Where the world renderer records in each world stage: after renderers at the default order, which may submit. */
    public static final int ORDER = 1000;
    /** Where a firing starts the world renderer's frame and runs its {@code onFrame} listeners: after camera shake only. */
    private static final int FRAME_ORDER = Integer.MIN_VALUE + 1;

    private static final Logger LOGGER = LogManager.getLogger("CgWorldRenderer");
    private static final CgWorldRenderer INSTANCE = new CgWorldRenderer();

    private static final CgRenderState OPAQUE_STATE = CgRenderState.builder()
            .depth(CgDepthState.TEST_WRITE).blend(CgBlendState.DISABLED).build();
    private static final CgRenderState TRANSPARENT_STATE = CgRenderState.builder()
            .depth(CgDepthState.TEST_ONLY).blend(CgBlendState.ALPHA).build();
    /**
     * Emissive passes add into the emission target, which has no depth: the scene hides them by its copied depth. ONE
     * ONE, since emitted light is written with an alpha of 0.
     */
    private static final CgRenderState EMISSIVE_STATE = CgRenderState.builder()
            .depth(CgDepthState.NONE).blend(new CgBlendState(true, CgGL.GL_ONE, CgGL.GL_ONE, CgGL.GL_ONE, CgGL.GL_ONE,
                    CgGL.GL_FUNC_ADD, CgGL.GL_FUNC_ADD)).build();
    private static final CgFrameBufferFormat EMISSION_FORMAT = CgFrameBufferFormat.builder("cg_emission")
            .color(0, CgTextureType.R11F_G11F_B10F).build();
    private static final int GPU_EMISSION = CgGpuTrace.name("world.emission");
    private static final CgFrameBufferFormat HALF_FORMAT = CgFrameBufferFormat.builder("cg_world_half")
            .color(0, CgTextureType.R11F_G11F_B10F).build();
    private static final String UPSAMPLE_SHADER = "crystalgraphics:shaders/world_half_upsample.shader";
    private static final CgMesh FULLSCREEN = CgMesh.vertices(3, CgMeshTopology.TRIANGLES);
    private static final int GPU_HALF = CgGpuTrace.name("world.half"), GPU_HALF_ADD = CgGpuTrace.name("world.halfAdd");
    private static final CgFrameBufferFormat OVERDRAW_FORMAT = CgFrameBufferFormat.builder("cg_world_overdraw")
            .color(0, CgTextureType.R16F).build();

    /** Called once a frame, before the first world stage records, with the host's camera. */
    @FunctionalInterface
    public interface FrameListener {
        void frame(CgHostView view);
    }

    private final List<FrameListener> listeners = new CopyOnWriteArrayList<>();
    private final Draw scratch = new Draw();

    // The frame's draws, flat: what a pooled command object per draw used to hold.
    private long frame = -1;
    private long notified = -1;
    private int count;
    private CgMesh[] meshes = new CgMesh[64];
    private CgMeshLods[] lods = new CgMeshLods[64];
    private final float[] meshBounds = new float[6];
    /** Per draw: submesh, first, count of its mesh; its stated bounds (6) and whether set; its padding. */
    private int[] ranges = new int[64 * 3];
    private float[] drawBounds = new float[64 * 6];
    private boolean[] boundsStated = new boolean[64];
    private float[] pads = new float[64];
    /** Per draw: the buffer an indirect draw's count is in, else null; the count's byte offset, mode and factor. */
    private CgBufferHandle[] counts = new CgBufferHandle[64];
    private long[] countOffsets = new long[64];
    private CgIndirect[] countModes = new CgIndirect[64];
    private int[] countFactors = new int[64];
    /** Per draw: a buffer it reads in place of an engine buffer, and where; else null. */
    private CgBufferHandle[] buffers = new CgBufferHandle[64];
    private CgBindingPoints.Binding[] bufferAt = new CgBindingPoints.Binding[64];
    /** Per draw: the object records a set of instances draws, else null, and how many of them. */
    private CgGraphBuffer[] sets = new CgGraphBuffer[64];
    private int[] setFirsts = new int[64];
    /** Per draw: the customs it stated, a bit each, which a set's cull stamps; and a set's instance scale. */
    private int[] customsStated = new int[64];
    private float[] setScales = new float[64];
    private CgGpuCount[] setCounts = new CgGpuCount[64];
    private CgMaterial[] materials = new CgMaterial[64];
    private double[] positions = new double[64 * 3];
    private float[] transforms = new float[64 * 16];
    private float[] customs = new float[64 * 16];
    /** Per draw: block and sky light, 0 to 15. */
    private float[] lights = new float[64 * 2];
    /** Per draw: its emission scale, CG_OBJECT_EMISSION. */
    private float[] emissions = new float[64];
    /** Per draw: whether it asked to be drawn at half the target's size. */
    private boolean[] halves = new boolean[64];
    /** Per draw: whether its count holds only what a GPU cull kept, so no CPU bounds test runs. */
    private boolean[] culledOnGpu = new boolean[64];
    /** Per draw: the GPU group it is charged to, null for its material's. */
    private String[] gpuGroups = new String[64];
    private int[] queues = new int[64];
    private int[] orders = new int[64];
    private CgSortLayer[] layers = new CgSortLayer[64];
    /** Per draw: whether it is in a group, and the group's absolute position (3). */
    private boolean[] grouped = new boolean[64];
    private double[] groupPositions = new double[64 * 3];

    // Per recording, reused: nothing here allocates per frame once warm.
    private final Matrix4f model = new Matrix4f();
    private final Matrix4f normal = new Matrix4f();
    private final Matrix4f viewProjection = new Matrix4f();
    private final Matrix4f toWorld = new Matrix4f();
    private final Vector3f eye = new Vector3f();
    private final Vector3f forward = new Vector3f();
    private final Vector3f min = new Vector3f();
    private final Vector3f max = new Vector3f();
    /** A box's corners in clip space: x, y, w. */
    private final float[] clipX = new float[8], clipY = new float[8], clipW = new float[8];
    private final CgViewFrustum frustum = new CgViewFrustum();
    private final IdentityHashMap<CgMaterial, Integer> bindings = new IdentityHashMap<>();
    private final IdentityHashMap<CgRenderState, CgRenderState> depthOnly = new IdentityHashMap<>();

    // Sets culled on the GPU: one cull, its values copied into each dispatch; per draw, the records and level counts its
    // cull wrote this stage, and where its own start in them. Every set one cull pass culls shares one records buffer
    // and one counts buffer, so their draws join; the k-th pass of a stage writes the k-th pair.
    private final CgCull cull = new CgCull();
    private CgGraphBuffer[] culled = new CgGraphBuffer[64], culledCounts = new CgGraphBuffer[64];
    private int[] culledFirst = new int[64], culledWord = new int[64], cullHandles = new int[64];
    private CgGraphBuffer[] cullOut = new CgGraphBuffer[4], cullLevels = new CgGraphBuffer[4];
    /** Per cull pass of the frame: its sets, culled together. */
    private CgCullSets[] cullBatches = new CgCullSets[4];
    private int cullPasses;

    // Emission: the target Emissive passes draw into, and its constants.
    private boolean[] emits = new boolean[64];
    /** 0 until set: the tier's share then. */
    private float emissionScale;
    private CgGraphTexture emissionTarget;
    private final CgPassConstants emissionConstants = new CgPassConstants();
    /** Whether the transparent passes write glows beside the target ({@link #mergeEmission(boolean)}). */
    private boolean mergeEmission = !"false".equals(System.getProperty("crystalgraphics.world.mergeEmission"));
    /** The target-sized emission those passes write into, and per draw whether its glows were drawn there. */
    private CgGraphTexture emissionFull;
    private boolean[] merged = new boolean[64];
    private int mergedDraws;
    private boolean mergeNoted;
    private final float[] constantsBlock = new float[CgPassConstants.FLOATS];

    // Half resolution: the target half-size draws go into, its constants, and what adds it over the stage's target.
    private boolean halfResolution = !"false".equals(System.getProperty("crystalgraphics.world.halfResolution"));
    private CgGraphTexture halfTarget;
    private final CgPassConstants halfConstants = new CgPassConstants();
    private CgMaterial upsample;
    private CgTexture upsampleBound;

    // Distortion: what plans and records it, and the distorting draws this firing.
    private final CgWorldDistortion distortion = new CgWorldDistortion();
    private final CgWorldText text = new CgWorldText();
    private final Hazes hazes = new Hazes();
    private boolean[] distorts = new boolean[64];

    // The overdraw view: the transparent draws counted again into this target.
    private boolean overdraw = "overdraw".equals(System.getProperty("crystalgraphics.post.debug"));
    private CgGraphTexture overdrawTarget;
    /** Pixels per overdraw count, the last bin everything from it up: {@link #summariseOverdraw}'s. */
    private final int[] overdrawCounts = new int[256];
    private final CgReadback.Sink overdrawSink = this::summariseOverdraw;

    // The HDR scene: what WORLD_TRANSPARENT draws into in place of the host's colour, while on.
    private boolean hdrScene = "true".equals(System.getProperty("crystalgraphics.world.hdrScene"));
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
        // Before anything else records (camera shake apart), so what onFrame submits reaches the renderers below ORDER
        // that read it in the same firing: VFX pools and Range lay out slots from it.
        CgRenderStage.WORLD_OPAQUE.register(FRAME_ORDER, stage -> beginFrame(stage.host().view()));
        CgRenderStage.WORLD_TRANSPARENT.register(FRAME_ORDER, stage -> beginFrame(stage.host().view()));
        CgRenderStage.WORLD_TRANSPARENT.register(CgSceneTarget.ORDER, sceneTarget::record);
        CgRenderStage.WORLD_OPAQUE.register(ORDER, this::recordOpaque);
        CgRenderStage.WORLD_TRANSPARENT.register(ORDER, this::recordTransparent);
    }

    /** Drops the previous ring frame's draws and runs the {@link #onFrame} listeners, once a ring frame. */
    private void beginFrame(CgHostView view) {
        long now = CgFrameRing.frame();
        if (now != frame) {
            clear();
            frame = now;
        }
        if (now != notified) {
            notified = now;
            for (FrameListener listener : listeners) listener.frame(view);
        }
    }

    /** Drops every draw. At context teardown. */
    public void release() {
        clear();
        frame = -1;
        depthOnly.clear();
        upsample = null;
        upsampleBound = null;
        distortion.release();
        text.release();
        sceneTarget.release();
        mergeNoted = false;
    }

    /**
     * The emission target's size as a share of the world's, in place of the tier's: Low 0.25, Medium and High 0.5,
     * Ultra 1. Larger is a tighter glow, at the square of the cost. How strongly it blooms is the post stack's
     * ({@code CgPostStack.get().bloom()}).
     *
     * <pre>{@code
     * CgWorldRenderer.get().emissionScale(1f);   // full size whatever the tier
     * CgWorldRenderer.get().emissionScale(0f);   // the tier's again
     * }</pre>
     */
    public void emissionScale(float scale) {
        if (!(scale >= 0f && scale <= 1f)) throw new IllegalArgumentException("an emission scale of " + scale + ": 0 to 1");
        emissionScale = scale;
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
     * Whether the transparent stage draws into a linear HDR scene (RGBA16F beside the host's depth) that the post
     * stack's composite encodes back into the host's target, off by default; {@code -Dcrystalgraphics.world.hdrScene=true}
     * starts it on. Takes effect at the next firing, so it can be flipped live to compare.
     *
     * <pre>{@code
     * CgWorldRenderer.get().hdrScene(true);   // blends, glows and edges in linear light
     * }</pre>
     *
     * <ul>
     *   <li>On, every renderer on {@code WORLD_TRANSPARENT} between the scene's first pass and the composite draws into
     *       the scene through {@code CgStageFrame.target()}; raw GL into the host's framebuffer there is overwritten.</li>
     *   <li>{@code cg_SceneColor} there reads linear HDR, not the host's encoded 8 bits.</li>
     *   <li>Merged emission stands down while it is on.</li>
     * </ul>
     */
    public void hdrScene(boolean on) {
        hdrScene = on;
    }

    public boolean hdrScene() {
        return hdrScene;
    }

    /**
     * Whether a transparent draw whose Emissive pass folds into its Forward pass draws both at once, its glow written
     * into a second attachment beside the target ({@code CgPipeline.emissionTarget}), on by default. The emission is
     * then the target's size; what does not fold (opaque, half-size and authored Emissive passes) is drawn into it after.
     * Off, every Emissive pass draws on its own into an emission target of {@link #emissionScale}: the comparison.
     *
     * <pre>{@code
     * CgWorldRenderer.get().mergeEmission(false);   // every glow its own draw again
     * }</pre>
     */
    public void mergeEmission(boolean on) {
        mergeEmission = on;
    }

    public boolean mergeEmission() {
        return mergeEmission;
    }

    /** How many draws the last transparent firing recorded with their glow in their own draw: for gates and readouts. */
    public int mergedDraws() {
        return mergedDraws;
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

    private float emissionScale() {
        if (emissionScale > 0f) return emissionScale;
        CgQuality tier = CgGraphicsSettings.QUALITY.get();
        return tier == CgQuality.LOW ? 0.25f : tier == CgQuality.ULTRA ? 1f : 0.5f;
    }

    /** Calls {@code listener} once a frame, before the first world stage records. Closing the registration stops it. */
    public CgRenderStage.Registration onFrame(FrameListener listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    /** Starts a draw of {@code mesh} under {@code material}: the shared scratch, so build and submit in one expression. */
    public Draw draw(CgMesh mesh, CgMaterial material) {
        return scratch.start(mesh, material);
    }

    /**
     * Starts a label: {@code text} at a point in the world, facing the camera unless turned, depth-tested against the
     * scene and drawn after the transparent draws, as a name tag. Place it on the {@link CgWorldText.Label}, then
     * {@code font(...)} or {@code family(...)} gives its {@code CgTextRenderer.Draw}, with all a draw has (strokes,
     * shadows, constraints); {@code submit()} that. It lives for the frame. Render thread.
     *
     * <pre>{@code
     * world.text("Attract + Orbit").at(x, y + 6, z).height(0.5f).font(font).submit();          // centred on its point
     * world.text("12").at(x, y, z).anchor(0.5f, 0f).font(font).color(0xFFFF5040)
     *         .stroke(0.12f, 0xFF000000).shadowCount(1).shadow(0, 2f, 2f, 1.5f, 0f, 0x80000000, false).submit();
     * world.text(sign).at(x, y, z).rotation(facingSouth).family(family).targetPx(48).constraints(400f, 0f).submit();
     * }</pre>
     *
     * <ul>
     *   <li>{@code height} is a line's height in blocks: the draw's {@code basePx()} maps to it. Its raster tier (bitmap
     *       or distance field) follows how tall it stands on screen, as {@code CgTextRenderer}'s world text does.</li>
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
     * Starts compiling every program this renderer draws {@code material} with, without waiting, and says whether all
     * are built: each link of its chain's Forward pass, its depth pass, its Emissive and Distortion passes, each in
     * the multi-draw form a joined run binds too, with the keywords enabled now. Poll it from a warm-up list, so no
     * frame compiles a program the frame draws. Render thread.
     *
     * <pre>{@code
     * warming.add(material);              // when a look starts playing, before its first draw
     * warming.removeIf(world::prepare);   // each frame: dropped once built
     * }</pre>
     *
     * <ul>
     *   <li>The mesh and the draw's form ({@code instances}, {@code indirect}, a {@code buffer}) choose no program, so
     *       the material is all it takes. A GPU-culled set's kernels are {@code CgGpuOps.prepareCull}'s.</li>
     *   <li>A keyword enabled after it answered true is a program it never built.</li>
     * </ul>
     */
    public boolean prepare(CgMaterial material) {
        boolean joins = CgCapabilities.detect().multiDraw() && CgMeshStore.get().multiDraw();
        boolean ready = true;
        for (CgMaterial link = material; link != null; link = link.getNextPass()) {
            ready &= prepared(link.pipeline(CgInstanceKind.OBJECT), joins);
            if (link.hasDepthPass()) ready &= prepared(link.pipeline(CgRenderPassVariant.DEPTH, CgInstanceKind.OBJECT), joins);
            CgPipeline forward = link.pipeline(CgInstanceKind.OBJECT);
            if (link.hasEmissivePass()) {
                ready &= prepared(link.pipeline(CgRenderPassVariant.EMISSIVE, CgInstanceKind.OBJECT), joins);
                if (forward != null) ready &= prepared(forward.emissionTarget(), joins);
            }
            // What a transparent draw covers the glows behind it with: merged, and in an emission drawn on its own.
            if (forward != null && link.getRenderQueue() >= CgRenderQueue.TRANSPARENT_THRESHOLD) {
                ready &= prepared(forward.emissionCover(), joins);
                ready &= prepared(forward.emissionOccluder(), joins);
            }
            if (link.hasDistortionPass()) ready &= prepared(link.pipeline(CgRenderPassVariant.DISTORTION, CgInstanceKind.OBJECT), joins);
        }
        return ready;
    }

    /** {@code pipeline} and, where draws join, its multi-draw form, both started; whether both are built. */
    private static boolean prepared(@Nullable CgPipeline pipeline, boolean joins) {
        if (pipeline == null) return true;
        boolean ready = pipeline.prepare();
        return joins ? pipeline.multiDraw().prepare() & ready : ready;
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
        private int order;
        private CgSortLayer layer;
        private boolean inGroup;
        private double groupX, groupY, groupZ;
        private int submesh, first, count;
        private final float[] bounds = new float[6];
        private boolean boundsSet;
        private float pad;
        private CgBufferHandle indirect;
        private long indirectOffset;
        private CgIndirect indirectMode;
        private int indirectFactor;
        private CgGraphBuffer set;
        private int setFirst;
        private int stated;
        private float setScale;
        private CgGpuCount setCount;
        private CgBufferHandle buffer;
        private CgBindingPoints.Binding bufferPoint;
        private final float[] meshBox = new float[6];
        /** Block and sky light; NaN block for the world's at its position. */
        private float blockLight, skyLight;
        private float emission;
        private boolean half, onGpu;
        private String gpuGroup;

        private Draw start(CgMesh mesh, CgMaterial material) {
            this.mesh = mesh;
            this.lods = null;
            this.material = material;
            x = y = z = 0;
            transform.identity();
            Arrays.fill(custom, 0f);
            queue = material.getRenderQueue();
            order = 0;
            layer = CgSortLayer.DEFAULT;
            inGroup = false;
            submesh = -1;
            first = 0;
            count = -1;
            boundsSet = false;
            pad = 0f;
            indirect = null;
            set = null;
            setFirst = 0;
            stated = 0;
            setScale = 1f;
            setCount = null;
            buffer = null;
            bufferPoint = null;
            blockLight = Float.NaN;
            emission = 1f;
            half = false;
            onGpu = false;
            gpuGroup = null;
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
         * Draws it at half the target's size, then adds it over the target where the scene's depth agrees: for soft
         * light that adds (a glow, a volume), at a quarter of the pixels. A transparent draw only, before the
         * transparent pass; with {@link CgWorldRenderer#halfResolution(boolean)} off it draws in that pass as any other.
         *
         * <pre>{@code
         * world.draw(sphere, glow).at(x, y, z).transform(scale).halfResolution().submit();
         * }</pre>
         *
         * <ul>
         *   <li>Its material adds (Blend ONE ONE): what it draws is added over the target, never blended.</li>
         *   <li>The half-size target has no depth: a material that hides itself behind the scene does it from
         *       {@code cg_DepthBuffer} ({@code CG_SCENE_EYE_DEPTH} at {@code gl_FragCoord.xy / CG_RESOLUTION}), with
         *       {@code DepthTest ALWAYS}.</li>
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
         */
        public Draw bounds(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
            bounds[0] = minX;
            bounds[1] = minY;
            bounds[2] = minZ;
            bounds[3] = maxX;
            bounds[4] = maxY;
            bounds[5] = maxZ;
            boundsSet = true;
            return this;
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
         *   <li>One a draw; object records take {@link #instances} instead.</li>
         * </ul>
         */
        public Draw buffer(CgBindingPoints.Binding at, CgBufferHandle records) {
            bufferPoint = Objects.requireNonNull(at, "at");
            buffer = Objects.requireNonNull(records, "records");
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
            int at = slot * 4;
            stated |= 1 << slot;
            custom[at] = x;
            custom[at + 1] = y;
            custom[at + 2] = z;
            custom[at + 3] = w;
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

        /** 0 to 15; higher draws later within its layer, or within its {@link #group}. */
        public Draw order(int order) {
            if (order < 0 || order > 15) throw new IllegalArgumentException("order " + order + " is outside 0..15");
            this.order = order;
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
            if (set != null) {
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
        if (count == meshes.length) grow();
        meshes[count] = d.mesh;
        lods[count] = d.lods;
        materials[count] = d.material;
        positions[count * 3] = d.x;
        positions[count * 3 + 1] = d.y;
        positions[count * 3 + 2] = d.z;
        d.transform.get(transforms, count * 16);
        System.arraycopy(d.custom, 0, customs, count * 16, 16);
        if (Float.isNaN(d.blockLight)) {
            int light = CgWorldLight.at(d.x, d.y, d.z);
            lights[count * 2] = CgWorldLight.block(light);
            lights[count * 2 + 1] = CgWorldLight.sky(light);
        } else {
            lights[count * 2] = d.blockLight;
            lights[count * 2 + 1] = d.skyLight;
        }
        emissions[count] = d.emission;
        halves[count] = d.half;
        culledOnGpu[count] = d.onGpu;
        gpuGroups[count] = d.gpuGroup;
        queues[count] = d.queue;
        orders[count] = d.order;
        layers[count] = d.layer;
        grouped[count] = d.inGroup;
        if (d.inGroup) {
            groupPositions[count * 3] = d.groupX;
            groupPositions[count * 3 + 1] = d.groupY;
            groupPositions[count * 3 + 2] = d.groupZ;
        }
        ranges[count * 3] = d.submesh;
        ranges[count * 3 + 1] = d.first;
        ranges[count * 3 + 2] = d.count;
        boundsStated[count] = d.boundsSet;
        if (d.boundsSet) System.arraycopy(d.bounds, 0, drawBounds, count * 6, 6);
        pads[count] = d.pad;
        counts[count] = d.indirect;
        countOffsets[count] = d.indirectOffset;
        countModes[count] = d.indirectMode;
        countFactors[count] = d.indirectFactor;
        sets[count] = d.set;
        setFirsts[count] = d.setFirst;
        customsStated[count] = d.stated;
        setScales[count] = d.setScale;
        setCounts[count] = d.setCount;
        buffers[count] = d.buffer;
        bufferAt[count] = d.bufferPoint;
        count++;
    }

    private void clear() {
        text.clear();
        Arrays.fill(meshes, 0, count, null);
        Arrays.fill(lods, 0, count, null);
        Arrays.fill(materials, 0, count, null);
        Arrays.fill(counts, 0, count, null);
        Arrays.fill(layers, 0, count, null);
        Arrays.fill(sets, 0, count, null);
        Arrays.fill(setCounts, 0, count, null);
        Arrays.fill(buffers, 0, count, null);
        Arrays.fill(bufferAt, 0, count, null);
        Arrays.fill(culled, 0, count, null);
        Arrays.fill(culledCounts, 0, count, null);
        count = 0;
    }

    private void grow() {
        int n = meshes.length * 2;
        meshes = Arrays.copyOf(meshes, n);
        lods = Arrays.copyOf(lods, n);
        materials = Arrays.copyOf(materials, n);
        positions = Arrays.copyOf(positions, n * 3);
        transforms = Arrays.copyOf(transforms, n * 16);
        customs = Arrays.copyOf(customs, n * 16);
        lights = Arrays.copyOf(lights, n * 2);
        emissions = Arrays.copyOf(emissions, n);
        halves = Arrays.copyOf(halves, n);
        culledOnGpu = Arrays.copyOf(culledOnGpu, n);
        gpuGroups = Arrays.copyOf(gpuGroups, n);
        queues = Arrays.copyOf(queues, n);
        orders = Arrays.copyOf(orders, n);
        layers = Arrays.copyOf(layers, n);
        grouped = Arrays.copyOf(grouped, n);
        groupPositions = Arrays.copyOf(groupPositions, n * 3);
        ranges = Arrays.copyOf(ranges, n * 3);
        drawBounds = Arrays.copyOf(drawBounds, n * 6);
        boundsStated = Arrays.copyOf(boundsStated, n);
        pads = Arrays.copyOf(pads, n);
        counts = Arrays.copyOf(counts, n);
        countOffsets = Arrays.copyOf(countOffsets, n);
        countModes = Arrays.copyOf(countModes, n);
        countFactors = Arrays.copyOf(countFactors, n);
        sets = Arrays.copyOf(sets, n);
        setFirsts = Arrays.copyOf(setFirsts, n);
        customsStated = Arrays.copyOf(customsStated, n);
        setScales = Arrays.copyOf(setScales, n);
        setCounts = Arrays.copyOf(setCounts, n);
        buffers = Arrays.copyOf(buffers, n);
        bufferAt = Arrays.copyOf(bufferAt, n);
        culled = Arrays.copyOf(culled, n);
        culledCounts = Arrays.copyOf(culledCounts, n);
        culledFirst = Arrays.copyOf(culledFirst, n);
        culledWord = Arrays.copyOf(culledWord, n);
        cullHandles = Arrays.copyOf(cullHandles, n);
    }
    // ── Recording ────────────────────────────────────────────────────────────────────────────────

    private static final int OPAQUE = 0, TRANSPARENT = 1;
    private static final byte SKIP = -1, FORWARD = 0, FORWARD_AND_PREPASS = 1, HALF = 2, AFTER = 3;
    /** The clip-space w a draw's screen rect is cut at: nearer than any host's near plane (Minecraft's is 0.05). */
    private static final float NEAR_W = 0.01f;

    private long[] keys = new long[64];
    private byte[] phase = new byte[64];
    /** Per draw this stage: its box on screen, in target pixels from the top left; NaN first for none. */
    private float[] screens = new float[64 * 4];
    private float targetWidth, targetHeight;

    private void recordOpaque(CgStageFrame stage) {
        record(stage, OPAQUE);
    }

    private void recordTransparent(CgStageFrame stage) {
        record(stage, TRANSPARENT);
    }

    private void record(CgStageFrame stage, int which) {
        CgHostView view = stage.host().view();
        beginFrame(view);
        if (count == 0) {
            if (which == TRANSPARENT) text.record(stage, view);
            return;
        }
        if (!irisWarned && CgIrisCompat.isShaderPackActive()) {
            irisWarned = true;
            LOGGER.warn("An Iris/Oculus shader pack is active: the world renderer draws into the main framebuffer, "
                    + "outside its deferred G-buffer, so its geometry is unlit under a deferred pack. cg_DepthBuffer "
                    + "remains valid.");
        }
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.WORLD, which == OPAQUE ? "world.recordOpaque" : "world.recordTransparent")) {
            targetWidth = stage.host().width();
            targetHeight = stage.host().height();
            prepare(view);
            Arrays.fill(culled, 0, count, null);
            Arrays.fill(culledCounts, 0, count, null);
            cullPasses = 0;
            boolean prepass = false;
            int drawn = 0;
            for (int i = 0; i < count; i++) {
                byte p = classify(i, which, view);
                phase[i] = p;
                if (p == SKIP) continue;
                drawn++;
                prepass |= p == FORWARD_AND_PREPASS;
            }
            CgTrace.counter(CgChannels.WORLD, which == OPAQUE ? "world.opaqueDraws" : "world.transparentDraws", drawn);
            int halfDrawn = 0;
            if (which == TRANSPARENT && halfResolution) {
                for (int i = 0; i < count; i++) {
                    if (phase[i] == SKIP || !halves[i]) continue;
                    phase[i] = HALF;
                    halfDrawn++;
                }
                CgTrace.counter(CgChannels.WORLD, "world.halfDraws", halfDrawn);
            }
            int afterDrawn = 0;
            if (which == TRANSPARENT) {
                for (int i = 0; i < count; i++) {
                    if (phase[i] != FORWARD || queues[i] < CgRenderQueue.AFTER_DISTORTION_THRESHOLD) continue;
                    phase[i] = AFTER;
                    afterDrawn++;
                }
            }

            CgRecording recording = stage.recording();
            CgPassConstants constants = stage.constants();
            bindings.clear();
            int distorting = 0;
            if (drawn > 0 && which == TRANSPARENT) {
                if (distorts.length < meshes.length) distorts = new boolean[meshes.length];
                for (int i = 0; i < count; i++) distorts[i] = phase[i] != SKIP && phase[i] != HALF && distortsScene(materials[i]);
                hazes.view = view;
                distorting = distortion.plan(hazes, targetWidth, targetHeight);
                afterDrawn = 0;
                for (int i = 0; i < count; i++) if (phase[i] == AFTER) afterDrawn++;
            }
            // Under the HDR scene a glow is light added into the scene itself, where what draws in front covers it.
            boolean scene = which == TRANSPARENT && stage.resources().has(CgFrameKeys.SCENE);
            CgGraphTexture emission = which == TRANSPARENT && drawn > 0 ? mergedEmission(stage, recording) : null;
            if (scene) recordSceneGlows(stage, recording, view);
            if (drawn > 0) {
                cullSets(stage, recording, view, false);
                if (prepass) recordPass(stage, recording, constants, OPAQUE_STATE, true, false, view, null, false);
                if (halfDrawn > 0) recordHalf(stage, recording, view);
                if (emission != null) recordEmission(stage, recording, view, emission, EMIT_BEFORE);
                if (distorting > 0) distortion.recordBends(stage, recording, hazes);
                if (drawn > halfDrawn + afterDrawn) {
                    recordPass(stage, recording, constants, which == OPAQUE ? OPAQUE_STATE : TRANSPARENT_STATE, false, false,
                            view, emission, scene);
                }
                if (distorting > 0) distortion.recordFinal(stage, recording, hazes);
                if (afterDrawn > 0) recordPass(stage, recording, constants, TRANSPARENT_STATE, false, true, view, emission, scene);
            }
            if (which == TRANSPARENT && !scene) recordEmission(stage, recording, view, emission, emission != null ? EMIT_AFTER : EMIT_ALL);
            if (which == TRANSPARENT && overdraw && drawn > 0) recordOverdraw(stage, recording, view);
            if (which == TRANSPARENT) text.record(stage, view);
        }
    }

    /** The draws as {@link CgWorldDistortion} sees them, under the view of the firing that planned it. */
    private final class Hazes implements CgWorldDistortion.Draws {

        CgHostView view;

        @Override
        public int count() {
            return count;
        }

        @Override
        public boolean distorts(int i) {
            return distorts[i];
        }

        @Override
        public boolean inPass(int i) {
            return phase[i] == FORWARD || phase[i] == AFTER;
        }

        @Override
        public boolean sharp(int i) {
            return phase[i] == AFTER;
        }

        @Override
        public void inPlace(int i) {
            phase[i] = FORWARD;
        }

        @Override
        public long key(int i) {
            return keys[i];
        }

        @Override
        public void rect(int i, float[] out) {
            if (Float.isNaN(screens[i * 4])) {
                out[0] = 0f;
                out[1] = 0f;
                out[2] = targetWidth;
                out[3] = targetHeight;
            } else {
                System.arraycopy(screens, i * 4, out, 0, 4);
            }
        }

        @Override
        public void drawDistortion(CgChunkBuilder chunks, CgRecording recording, int i) {
            modelOf(i, view);
            model.normal(normal);
            for (CgMaterial link = materials[i]; link != null; link = link.getNextPass()) {
                if (!link.hasDistortionPass()) continue;
                CgPipeline pipeline = link.pipeline(CgRenderPassVariant.DISTORTION, CgInstanceKind.OBJECT);
                if (pipeline == null) continue;
                if (sets[i] != null) {
                    drawSet(chunks, pipeline, bindingOf(link, recording), i);
                    continue;
                }
                chunks.draw(pipeline, bindingOf(link, recording), meshes[i]).sortKey(keys[i]);
                group(chunks, i);
                writeInstance(chunks, i);
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

    /**
     * Every transparent draw this stage drew, half-size ones too, again through its overdraw variant into a target of
     * the stage's size: 1 a fragment, hidden by the stage's depth as its own depth test would hide it.
     */
    private void recordOverdraw(CgStageFrame stage, CgRecording recording, CgHostView view) {
        int w = Math.max(1, (int) targetWidth), h = Math.max(1, (int) targetHeight);
        if (overdrawTarget == null || overdrawTarget.getWidth() != w || overdrawTarget.getHeight() != h) {
            overdrawTarget = CgGraphTexture.transientTexture("cg_world_overdraw", new CgTextureDesc(w, h, OVERDRAW_FORMAT));
        }
        CgRasterPass pass = recording.raster(overdrawTarget, CgLoad.clear(0f, 0f, 0f, 0f), stage.constants(), null,
                        CgOrder.SORTED).sceneDepth(CgBindingPoints.DEPTH_TEXTURE_UNIT, stage.target())
                .texture(CgBindingPoints.LIGHTMAP_TEXTURE_UNIT, stage.host().textures().lightmapTexture());
        CgChunkBuilder chunks = recording.chunks().begin();
        for (int i = 0; i < count; i++) {
            if (phase[i] == SKIP) continue;
            modelOf(i, view);
            model.normal(normal);
            for (CgMaterial link = materials[i]; link != null; link = link.getNextPass()) {
                CgPipeline pipeline = link.pipeline(CgInstanceKind.OBJECT);
                // A pass writing nothing is skipped; a pure haze's cost is its Distortion pass.
                if (pipeline != null && pipeline.state().writesNothing()) {
                    pipeline = link.hasDistortionPass() ? link.pipeline(CgRenderPassVariant.DISTORTION, CgInstanceKind.OBJECT) : null;
                }
                if (pipeline == null) continue;
                if (sets[i] != null) {
                    drawSet(chunks, pipeline.overdraw(), bindingOf(link, recording), i);
                    continue;
                }
                chunks.draw(pipeline.overdraw(), bindingOf(link, recording), meshes[i]).sortKey(keys[i]);
                writeInstance(chunks, i);
            }
        }
        pass.add(chunks.end());
        pass.end();
        stage.resources().put(CgFrameKeys.OVERDRAW, overdrawTarget);
        if (CgTrace.isEnabled(CgChannels.WORLD)) recording.readback(overdrawTarget, 0, 0, 0, w, h, overdrawSink);
    }

    /**
     * The overdraw count read back, as counters a frame or two later: {@code world.overdraw-covered} the share of pixels
     * any transparent fragment reached, and over those the mean, 95th percentile and most.
     */
    private void summariseOverdraw(ByteBuffer data) {
        Arrays.fill(overdrawCounts, 0);
        int pixels = data.remaining() / 2, base = data.position();
        long sum = 0;
        for (int i = 0; i < pixels; i++) {
            int n = halfCount(data.getShort(base + i * 2));
            sum += n;
            overdrawCounts[Math.min(n, overdrawCounts.length - 1)]++;
        }
        int covered = pixels - overdrawCounts[0];
        CgTrace.counter(CgChannels.WORLD, "world.overdraw-covered", pixels == 0 ? 0.0 : (double) covered / pixels);
        if (covered == 0) return;
        CgTrace.counter(CgChannels.WORLD, "world.overdraw-mean", (double) sum / covered);
        int p95 = 0, max = 0;
        for (int c = 1, seen = 0; c < overdrawCounts.length; c++) {
            if (overdrawCounts[c] == 0) continue;
            max = c;
            seen += overdrawCounts[c];
            if (p95 == 0 && seen >= 0.95 * covered) p95 = c;
        }
        CgTrace.counter(CgChannels.WORLD, "world.overdraw-p95", p95);
        CgTrace.counter(CgChannels.WORLD, "world.overdraw-max", max);
    }

    /** A half float holding a whole count, as an int; below 1 is 0. */
    private static int halfCount(short half) {
        int exponent = (half >>> 10) & 0x1f;
        return exponent == 0 ? 0 : Math.round(Math.scalb((float) (1024 + (half & 0x3ff)), exponent - 25));
    }

    /** {@link #recordEmission}'s parts: every glow, or those whose colour draws before the transparent pass, or after. */
    private static final int EMIT_ALL = 0, EMIT_BEFORE = 1, EMIT_AFTER = 2;

    /**
     * Every visible draw with an Emissive pass, opaque or transparent, into the emission target hidden by the stage's
     * depth, published as {@link CgFrameKeys#EMISSION}. After the transparent pass, so it glows over everything; into a
     * merged emission, the glows whose colour draws before that pass (opaque and half-size draws) go before it too, so
     * the surfaces it draws over them cover them there as well ({@code CgPipeline.emissionCover}). What nothing reads
     * (bloom off, or below its quality) the graph culls, with its depth copy.
     */
    private void recordEmission(CgStageFrame stage, CgRecording recording, CgHostView view, @Nullable CgGraphTexture merged,
                                int part) {
        int recorded = markGlows(view, merged, part);
        if (merged != null) stage.resources().put(CgFrameKeys.EMISSION, merged);
        if (recorded == 0) return;
        cullSets(stage, recording, view, true);

        CgGraphTexture into = merged;
        if (into == null) {
            float scale = emissionScale();
            int w = Math.max(1, (int) (targetWidth * scale)), h = Math.max(1, (int) (targetHeight * scale));
            if (emissionTarget == null || emissionTarget.getWidth() != w || emissionTarget.getHeight() != h) {
                emissionTarget = CgGraphTexture.transientTexture("cg_emission", new CgTextureDesc(w, h, EMISSION_FORMAT));
            }
            into = emissionTarget;
        }
        stage.constants().write(constantsBlock, 0);
        emissionConstants.read(constantsBlock, 0).resolution(into.getWidth(), into.getHeight());

        CgRasterPass glow = recording.raster(into, merged != null ? CgLoad.load() : CgLoad.clear(0f, 0f, 0f, 0f),
                emissionConstants, EMISSIVE_STATE, CgOrder.SORTED)
                .sceneDepth(CgBindingPoints.DEPTH_TEXTURE_UNIT, stage.target()).timed(GPU_EMISSION);
        CgChunkBuilder chunks = recording.chunks().begin();
        for (int i = 0; i < count; i++) {
            // Drawn on its own, the emission takes each transparent surface's cover too, in the glows' order: what
            // the merged emission's surfaces write beside their colour.
            boolean covers = merged == null && (phase[i] == FORWARD || phase[i] == AFTER)
                    && queues[i] >= CgRenderQueue.TRANSPARENT_THRESHOLD && queues[i] < CgRenderQueue.OVERLAY_THRESHOLD;
            if (!emits[i] && !covers) continue;
            modelOf(i, view);
            model.normal(normal);
            for (CgMaterial link = materials[i]; link != null; link = link.getNextPass()) {
                CgPipeline forward = covers ? link.pipeline(CgInstanceKind.OBJECT) : null;
                if (forward != null && !forward.state().writesNothing()) {
                    CgPipeline occluder = forward.emissionOccluder();
                    if (occluder != null) emissionDraw(chunks, occluder, link, recording, i);
                }
                if (!emits[i] || !link.hasEmissivePass() || merged != null && this.merged[i] && foldsEmission(link)) continue;
                CgPipeline pipeline = link.pipeline(CgRenderPassVariant.EMISSIVE, CgInstanceKind.OBJECT);
                if (pipeline != null) emissionDraw(chunks, pipeline, link, recording, i);
            }
        }
        glow.add(chunks.end());
        glow.end();
        stage.resources().put(CgFrameKeys.EMISSION, into);
    }

    /**
     * Marks {@link #emits}: the visible draws with a glow in {@code part}, less those {@code merged} drew whole; answers
     * how many. Counts every visible glow, but for a merged emission's {@link #EMIT_BEFORE}, which its second part follows.
     */
    private int markGlows(CgHostView view, @Nullable CgGraphTexture merged, int part) {
        if (emits.length < meshes.length) emits = new boolean[meshes.length];
        int emitting = 0, recorded = 0;
        for (int i = 0; i < count; i++) {
            int queue = queues[i];
            // A transparent draw was classified for this stage; an opaque one is classified again, its pass recorded.
            boolean e = queue < CgRenderQueue.OVERLAY_THRESHOLD && emissions[i] > 0f && emitsLight(materials[i])
                    && (queue >= CgRenderQueue.TRANSPARENT_THRESHOLD ? phase[i] != SKIP : classify(i, OPAQUE, view) != SKIP)
                    && (merged == null || !allMerged(i));
            if (e) emitting++;
            boolean before = queue < CgRenderQueue.TRANSPARENT_THRESHOLD || phase[i] == HALF;
            e &= part == EMIT_ALL || (part == EMIT_BEFORE) == before;
            emits[i] = e;
            if (e) recorded++;
        }
        if (merged == null || part != EMIT_BEFORE) CgTrace.counter(CgChannels.WORLD, "world.emissiveDraws", emitting);
        return recorded;
    }

    /**
     * Under the HDR scene, the Emissive passes of the draws whose colour draws before the transparent pass (opaque and
     * half-size draws) added into the scene, hidden by its depth, so the transparent pass covers them as it covers what
     * they glow on. A transparent draw's glow follows its own draw in that pass ({@link #recordPass}).
     */
    private void recordSceneGlows(CgStageFrame stage, CgRecording recording, CgHostView view) {
        if (markGlows(view, null, EMIT_BEFORE) == 0) return;
        cullSets(stage, recording, view, true);
        CgRasterPass glow = recording.raster(stage.target(), CgLoad.load(), stage.constants(), EMISSIVE_STATE, CgOrder.SORTED)
                .sceneDepth(CgBindingPoints.DEPTH_TEXTURE_UNIT).timed(GPU_EMISSION);
        CgChunkBuilder chunks = recording.chunks().begin();
        for (int i = 0; i < count; i++) {
            if (!emits[i]) continue;
            modelOf(i, view);
            model.normal(normal);
            for (CgMaterial link = materials[i]; link != null; link = link.getNextPass()) {
                CgPipeline pipeline = link.hasEmissivePass() ? link.pipeline(CgRenderPassVariant.EMISSIVE, CgInstanceKind.OBJECT) : null;
                if (pipeline != null) emissionDraw(chunks, pipeline, link, recording, i);
            }
        }
        glow.add(chunks.end());
        glow.end();
    }

    /** Draw {@code i} through {@code pipeline} into the emission pass, sorted by its key. */
    private void emissionDraw(CgChunkBuilder chunks, CgPipeline pipeline, CgMaterial link, CgRecording recording, int i) {
        if (sets[i] != null) {
            drawSet(chunks, pipeline, bindingOf(link, recording), i);
            return;
        }
        chunks.draw(pipeline, bindingOf(link, recording), meshes[i]).sortKey(keys[i]);
        group(chunks, i);
        writeInstance(chunks, i);
    }

    /**
     * The target-sized emission the transparent passes write glows into beside the target, after a pass clearing it,
     * with {@link #merged} saying which draws do; null where none does, or the target is the default framebuffer
     * (1.7.10 without framebuffers), which takes no second attachment, or the device cannot mask the other draws'
     * second slot alone.
     */
    @Nullable
    private CgGraphTexture mergedEmission(CgStageFrame stage, CgRecording recording) {
        if (merged.length < meshes.length) merged = new boolean[meshes.length];
        Arrays.fill(merged, 0, count, false);
        mergedDraws = 0;
        if (!mergeEmission || !CgCapabilities.detect().independentBlend() || stage.resources().has(CgFrameKeys.SCENE)
                || stage.host().mainFramebuffer() <= 0 || !stage.resources().has(CgFrameKeys.EMISSION_READ)
                || CgRasterPass.refusesAttachment(stage.host().mainFramebuffer())) return null;
        for (int i = 0; i < count; i++) {
            if (phase[i] != FORWARD && phase[i] != AFTER || queues[i] >= CgRenderQueue.OVERLAY_THRESHOLD || emissions[i] <= 0f) continue;
            merged[i] = true;
            for (CgMaterial link = materials[i]; link != null; link = link.getNextPass()) {
                if (foldsEmission(link)) mergedDraws++;
            }
        }
        if (mergedDraws == 0) return null;
        int w = Math.max(1, (int) targetWidth), h = Math.max(1, (int) targetHeight);
        if (emissionFull == null || emissionFull.getWidth() != w || emissionFull.getHeight() != h) {
            emissionFull = CgGraphTexture.transientTexture("cg_emission_full", new CgTextureDesc(w, h, EMISSION_FORMAT));
        }
        recording.raster(emissionFull, CgLoad.clear(0f, 0f, 0f, 0f), stage.constants(), null, CgOrder.SORTED).end();
        if (!mergeNoted) {
            mergeNoted = true;
            LOGGER.info("Glows merge into their surfaces' draws: an emission of {}x{} beside framebuffer {}", w, h, stage.host().mainFramebuffer());
        }
        return emissionFull;
    }

    /** Whether a link's Emissive pass is drawn by its Forward draw ({@code CgPipeline.emissionTarget}). */
    private static boolean foldsEmission(CgMaterial link) {
        if (!link.hasEmissivePass()) return false;
        CgPipeline forward = link.pipeline(CgInstanceKind.OBJECT);
        return forward != null && forward.emissionTarget() != null;
    }

    /** Whether every Emissive pass of draw {@code i}'s chain was drawn by its Forward draws. */
    private boolean allMerged(int i) {
        if (!merged[i]) return false;
        for (CgMaterial link = materials[i]; link != null; link = link.getNextPass()) {
            if (link.hasEmissivePass() && !foldsEmission(link)) return false;
        }
        return true;
    }

    /**
     * The draws asking for half resolution ({@link #HALF}) into a target half the stage's size, cleared, then added over
     * the stage's target with a depth-aware upsample: each pixel weighs the four half-size texels round it by how close
     * the scene's depth where each was drawn is to its own, so light does not bleed across a silhouette. Before the
     * transparent pass, so what draws after (a haze bending it, smoke over it) sees it in the target.
     */
    private void recordHalf(CgStageFrame stage, CgRecording recording, CgHostView view) {
        int w = Math.max(1, (int) (targetWidth * 0.5f)), h = Math.max(1, (int) (targetHeight * 0.5f));
        if (halfTarget == null || halfTarget.getWidth() != w || halfTarget.getHeight() != h) {
            halfTarget = CgGraphTexture.transientTexture("cg_world_half", new CgTextureDesc(w, h, HALF_FORMAT));
        }
        stage.constants().write(constantsBlock, 0);
        halfConstants.read(constantsBlock, 0).resolution(w, h);
        CgRasterPass pass = recording.raster(halfTarget, CgLoad.clear(0f, 0f, 0f, 0f), halfConstants, TRANSPARENT_STATE,
                        CgOrder.SORTED).sceneDepth(CgBindingPoints.DEPTH_TEXTURE_UNIT, stage.target())
                .texture(CgBindingPoints.LIGHTMAP_TEXTURE_UNIT, stage.host().textures().lightmapTexture()).timed(GPU_HALF);
        CgChunkBuilder chunks = recording.chunks().begin();
        for (int i = 0; i < count; i++) {
            if (phase[i] != HALF) continue;
            modelOf(i, view);
            model.normal(normal);
            for (CgMaterial link = materials[i]; link != null; link = link.getNextPass()) {
                CgPipeline pipeline = link.pipeline(CgInstanceKind.OBJECT);
                if (pipeline == null) continue;
                if (sets[i] != null) {
                    drawSet(chunks, pipeline, bindingOf(link, recording), i);
                    continue;
                }
                chunks.draw(pipeline, bindingOf(link, recording), meshes[i]).sortKey(keys[i]);
                group(chunks, i);
                writeInstance(chunks, i);
            }
        }
        pass.add(chunks.end());
        pass.end();

        if (upsample == null) upsample = CgMaterial.newInstance(UPSAMPLE_SHADER);
        if (halfTarget != upsampleBound) {
            CgTexture half = halfTarget;
            upsample.applyProperties(b -> b.sampler("_Half", 0, half));
            upsampleBound = half;
        }
        CgPipeline pipeline = upsample.pipeline(CgInstanceKind.OBJECT);
        if (pipeline == null) return;
        CgRasterPass add = recording.raster(stage.target(), CgLoad.load(), stage.constants(), null, CgOrder.SORTED)
                .sceneDepth(CgBindingPoints.DEPTH_TEXTURE_UNIT).timed(GPU_HALF_ADD);
        CgChunkBuilder upsampled = recording.chunks().begin();
        upsampled.draw(pipeline, upsample.captureBindings(recording.bindings()), FULLSCREEN);
        upsampled.instance();
        add.add(upsampled.end());
        add.end();
    }

    /** Whether any link of a material chain has an Emissive pass. */
    private static boolean emitsLight(CgMaterial material) {
        for (CgMaterial link = material; link != null; link = link.getNextPass()) {
            if (link.hasEmissivePass()) return true;
        }
        return false;
    }

    /** The frustum, the eye and its forward, in the view's camera-relative space. */
    private void prepare(CgHostView view) {
        viewProjection.set(view.projection()).mul(view.view());
        frustum.set(viewProjection);
        toWorld.set(view.view()).invert();
        toWorld.transformPosition(eye.set(0f, 0f, 0f));
        toWorld.transformDirection(forward.set(0f, 0f, -1f)).normalize();
        if (keys.length < count) {
            keys = new long[meshes.length];
            phase = new byte[meshes.length];
            screens = new float[meshes.length * 4];
        }
    }

    /** Whether draw {@code i} takes part in this stage, and its sort key. */
    private byte classify(int i, int which, CgHostView view) {
        int queue = queues[i];
        boolean transparent = queue >= CgRenderQueue.TRANSPARENT_THRESHOLD;
        if (queue >= CgRenderQueue.OVERLAY_THRESHOLD || transparent != (which == TRANSPARENT)) return SKIP;
        if (sets[i] != null && setCounts[i].capacity() == 0) return SKIP;
        modelOf(i, view);
        float cx, cy, cz;
        float[] bounds;
        if (culledOnGpu[i]) {
            bounds = null;
        } else if (boundsStated[i]) {
            System.arraycopy(drawBounds, i * 6, meshBounds, 0, 6);
            bounds = meshBounds;
        } else if (sets[i] != null) {
            bounds = null;   // a set's instances are culled one by one, on the GPU
        } else {
            bounds = (lods[i] != null ? lods[i].finest() : meshes[i]).bounds(meshBounds);
        }
        if (bounds != null) {
            float p = pads[i];
            model.transformAab(bounds[0] - p, bounds[1] - p, bounds[2] - p, bounds[3] + p, bounds[4] + p, bounds[5] + p,
                    min, max);
            if (!frustum.testAabb(min.x, min.y, min.z, max.x, max.y, max.z)) return SKIP;
            screenOf(i);
            cx = (min.x + max.x) * 0.5f;
            cy = (min.y + max.y) * 0.5f;
            cz = (min.z + max.z) * 0.5f;
            if (lods[i] != null && sets[i] == null) {
                CgMesh level = lods[i].pick(screenHeight(cx, cy, cz, view));
                if (level == null) return SKIP;
                meshes[i] = level;
            }
        } else {
            screens[i * 4] = Float.NaN;
            if (lods[i] != null) meshes[i] = lods[i].finest();
            cx = model.m30();
            cy = model.m31();
            cz = model.m32();
        }
        float distance = Math.max(0f, (cx - eye.x) * forward.x + (cy - eye.y) * forward.y + (cz - eye.z) * forward.z);
        CgMaterial material = materials[i];
        keys[i] = transparent
                ? transparentKey(i, queue, distance, view)
                : CgSortKey.opaque(queue, layers[i].rank(), orders[i], material.getMaterialId(), System.identityHashCode(meshes[i]), distance);
        boolean prepass = !transparent && (material.hasDepthPass() || queue >= CgRenderQueue.ALPHA_TEST_THRESHOLD);
        return prepass ? FORWARD_AND_PREPASS : FORWARD;
    }

    /** Draw {@code i}'s transparent key: its layer, its group's distance, then its own order and distance. */
    private long transparentKey(int i, int queue, float distance, CgHostView view) {
        int layer = layers[i].rank();
        if (!grouped[i]) return CgSortKey.transparent(queue, layer, distance, orders[i], distance);
        float gx = (float) (groupPositions[i * 3] - view.x()) - eye.x;
        float gy = (float) (groupPositions[i * 3 + 1] - view.y()) - eye.y;
        float gz = (float) (groupPositions[i * 3 + 2] - view.z()) - eye.z;
        float groupDistance = Math.max(0f, gx * forward.x + gy * forward.y + gz * forward.z);
        return CgSortKey.transparent(queue, layer, groupDistance, orders[i], distance);
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
     * Draw {@code i}'s box {@code min}..{@code max} on screen, into {@link #screens}: what the frame graph cuts a copy
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
            screens[i * 4] = Float.NaN;
            return;
        }
        screens[i * 4] = (x0 * 0.5f + 0.5f) * targetWidth;
        screens[i * 4 + 1] = (0.5f - y1 * 0.5f) * targetHeight;
        screens[i * 4 + 2] = (x1 * 0.5f + 0.5f) * targetWidth;
        screens[i * 4 + 3] = (0.5f - y0 * 0.5f) * targetHeight;
    }

    /** Draw {@code i}'s model matrix, camera-relative: its position minus the view's, in doubles, then its transform. */
    private void modelOf(int i, CgHostView view) {
        model.set(transforms, i * 16);
        model.m30(model.m30() + (float) (positions[i * 3] - view.x()))
                .m31(model.m31() + (float) (positions[i * 3 + 1] - view.y()))
                .m32(model.m32() + (float) (positions[i * 3 + 2] - view.z()));
    }

    /**
     * The opaque, prepass or transparent pass; with {@code after}, the transparent draws drawn after distortion. With
     * {@code sceneGlows}, each draw's Emissive pass follows it, at its key, adding its glow into the HDR scene.
     */
    private void recordPass(CgStageFrame stage, CgRecording recording, CgPassConstants constants, CgRenderState state,
                            boolean depthOnlyPass, boolean after, CgHostView view, @Nullable CgGraphTexture emission,
                            boolean sceneGlows) {
        CgRasterPass pass = recording.raster(stage.target(), CgLoad.load(), constants, state, CgOrder.SORTED)
                .sceneDepth(CgBindingPoints.DEPTH_TEXTURE_UNIT)
                .sceneColor(CgBindingPoints.SCENE_COLOR_TEXTURE_UNIT)
                .texture(CgBindingPoints.LIGHTMAP_TEXTURE_UNIT, stage.host().textures().lightmapTexture());
        if (emission != null) pass.attachmentIfTaken(emission);
        CgChunkBuilder chunks = recording.chunks().begin();
        for (int i = 0; i < count; i++) {
            if (phase[i] == SKIP || phase[i] == HALF || (depthOnlyPass && phase[i] != FORWARD_AND_PREPASS)
                    || (phase[i] == AFTER) != after) continue;
            modelOf(i, view);
            model.normal(normal);
            boolean glows = sceneGlows && emissions[i] > 0f && queues[i] < CgRenderQueue.OVERLAY_THRESHOLD;
            for (CgMaterial link = materials[i]; link != null; link = depthOnlyPass ? null : link.getNextPass()) {
                CgPipeline pipeline = depthOnlyPass ? depthPipeline(link) : link.pipeline(CgInstanceKind.OBJECT);
                if (glows && link.hasEmissivePass()) {
                    // Its glow follows its colour: SORTED is stable, so a draw of the same key after it covers both.
                    if (pipeline != null && !pipeline.state().writesNothing()) passDraw(chunks, pipeline, link, recording, i);
                    CgPipeline glow = link.pipeline(CgRenderPassVariant.EMISSIVE, CgInstanceKind.OBJECT);
                    if (glow != null) passDraw(chunks, glow, link, recording, i);
                    continue;
                }
                if (pipeline == null || (!depthOnlyPass && pipeline.state().writesNothing())) continue;
                if (emission != null && !depthOnlyPass) {
                    // A surface that does not glow covers the glows behind it as it covers their colour, so bloom
                    // does not lay them back over it.
                    CgPipeline glowing = merged[i] && link.hasEmissivePass() ? pipeline.emissionTarget() : null;
                    if (glowing == null) glowing = pipeline.emissionCover();
                    if (glowing != null) pipeline = glowing;
                }
                passDraw(chunks, pipeline, link, recording, i);
            }
        }
        if (state == TRANSPARENT_STATE && !depthOnlyPass && !after) distortion.addApplies(chunks, recording);
        pass.add(chunks.end());
        pass.end();
    }

    /** Draw {@code i} through {@code pipeline} into a world pass, at its key and within its screen rect. */
    private void passDraw(CgChunkBuilder chunks, CgPipeline pipeline, CgMaterial link, CgRecording recording, int i) {
        if (sets[i] != null) {
            drawSet(chunks, pipeline, bindingOf(link, recording), i);
            return;
        }
        chunks.draw(pipeline, bindingOf(link, recording), meshes[i]).sortKey(keys[i]);
        group(chunks, i);
        if (!Float.isNaN(screens[i * 4])) chunks.bounds(screens[i * 4], screens[i * 4 + 1], screens[i * 4 + 2], screens[i * 4 + 3]);
        writeInstance(chunks, i);
    }

    /**
     * Culls on the GPU every set this stage draws that it has not culled yet, all at once: those the stage's passes
     * draw, or with {@code emitting}, those its bloom draws. The first asks for the stage's depth pyramid.
     */
    private void cullSets(CgStageFrame stage, CgRecording recording, CgHostView view, boolean emitting) {
        int k = -1;
        CgCullSets batch = null;
        for (int i = 0; i < count; i++) {
            cullHandles[i] = -1;
            if (!culls(i, emitting)) continue;
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
            cullHandles[i] = batch.add(placeCull(i, view), sets[i], setFirsts[i], setCounts[i]);
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
        for (int i = 0; i < count; i++) {
            int h = cullHandles[i];
            if (h < 0) continue;
            culled[i] = cullOut[k];
            culledCounts[i] = cullLevels[k];
            culledFirst[i] = batch.first(h);
            culledWord[i] = batch.word(h);
        }
    }

    /** Whether this {@link #cullSets} culls draw {@code i}: a set not culled yet that the stage, or its bloom, draws. */
    private boolean culls(int i, boolean emitting) {
        return sets[i] != null && culled[i] == null && (emitting ? emits[i] : phase[i] != SKIP);
    }

    /** The cull set to draw {@code i}'s set: its mesh, placed by its model, its pad, light, scale and customs. */
    private CgCull placeCull(int i, CgHostView view) {
        if (lods[i] != null) cull.mesh(lods[i]);
        else cull.mesh(meshes[i]);
        modelOf(i, view);
        cull.place(model).pad(pads[i]).light(lights[i * 2], lights[i * 2 + 1]).scale(setScales[i]).ownCustoms();
        for (int k = 0; k < 4; k++) {
            int c = i * 16 + k * 4;
            if ((customsStated[i] & 1 << k) != 0) cull.custom(k, customs[c], customs[c + 1], customs[c + 2], customs[c + 3]);
        }
        return cull;
    }

    private void group(CgChunkBuilder chunks, int i) {
        if (gpuGroups[i] != null && CgTrace.isEnabled(CgChannels.GPU_GROUPS)) chunks.gpuGroup(gpuGroups[i]);
    }

    /** Set {@code i}'s levels, each an indirect draw of the records its cull kept at that level this stage. */
    private void drawSet(CgChunkBuilder chunks, CgPipeline pipeline, int binding, int i) {
        int capacity = setCounts[i].capacity(), levels = lods[i] != null ? lods[i].levelCount() : 1;
        for (int l = 0; l < levels; l++) {
            chunks.draw(pipeline, binding, lods[i] != null ? lods[i].level(l) : meshes[i]).sortKey(keys[i]);
            group(chunks, i);
            if (!Float.isNaN(screens[i * 4])) chunks.bounds(screens[i * 4], screens[i * 4 + 1], screens[i * 4 + 2], screens[i * 4 + 3]);
            if (ranges[i * 3] >= 0) chunks.range(ranges[i * 3], ranges[i * 3 + 1], ranges[i * 3 + 2]);
            chunks.objects(culled[i], culledFirst[i] + CgGpuOps.cullFirst(l, capacity), capacity)
                    .indirect(culledCounts[i], (culledWord[i] + l) * 4L, CgIndirect.INSTANCES, 1);
        }
    }

    /** Draw {@code i}'s range, count and object record into the draw just begun, under {@link #model} and {@link #normal}. */
    private void writeInstance(CgChunkBuilder chunks, int i) {
        if (ranges[i * 3] >= 0) chunks.range(ranges[i * 3], ranges[i * 3 + 1], ranges[i * 3 + 2]);
        if (counts[i] != null) chunks.indirect(counts[i], countOffsets[i], countModes[i], countFactors[i]);
        if (buffers[i] != null) chunks.buffer(bufferAt[i], buffers[i]);
        int at = chunks.instance();
        float[] data = chunks.data();
        model.get(data, at);
        normal.get(data, at + 16);
        data[at + 28] = lights[i * 2];   // CG_OBJECT_LIGHT: the normal matrix's unused column
        data[at + 29] = lights[i * 2 + 1];
        data[at + 30] = emissions[i] - 1f;   // CG_OBJECT_EMISSION, less 1 so a record left 0 means 1
        System.arraycopy(customs, i * 16, data, at + 32, 16);
    }

    /** A material's depth pass, or its forward pass writing depth alone. */
    private CgPipeline depthPipeline(CgMaterial material) {
        if (material.hasDepthPass()) return material.pipeline(CgRenderPassVariant.DEPTH, CgInstanceKind.OBJECT);
        CgPipeline forward = material.pipeline(CgInstanceKind.OBJECT);
        if (forward == null) return null;
        CgRenderState own = material.getPassRenderState(CgRenderPassVariant.FORWARD);
        CgRenderState colourless = depthOnly.get(own);
        if (colourless == null) depthOnly.put(own, colourless = own.withColorMask(CgColorMask.NONE));
        return forward.withState(colourless);
    }

    /** A material's snapshot in this recording, taken once per stage. */
    private int bindingOf(CgMaterial material, CgRecording recording) {
        Integer id = bindings.get(material);
        if (id == null) bindings.put(material, id = material.captureBindings(recording.bindings()));
        return id;
    }
}
