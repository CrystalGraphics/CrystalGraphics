package com.crystalgraphics.render.scene.renderer.feature.light;

import java.util.function.BooleanSupplier;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialInstance;
import com.crystalgraphics.api.material.CgMaterialInterface;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshTopology;
import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.compute.CgKernel;
import com.crystalgraphics.gpu.resource.CgFallbackTextures;
import com.crystalgraphics.job.CgJob;
import com.crystalgraphics.job.CgJobLane;
import com.crystalgraphics.material.CgMaterialProxy;
import com.crystalgraphics.render.draw.CgChunkBuilder;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.draw.CgPipeline;
import com.crystalgraphics.render.graph.CgComputePass;
import com.crystalgraphics.render.graph.CgDepthAccess;
import com.crystalgraphics.render.graph.CgDispatch;
import com.crystalgraphics.render.graph.CgGraphBuffer;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgLoad;
import com.crystalgraphics.render.graph.CgRasterPass;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.host.CgWorldAtmosphere;
import com.crystalgraphics.render.scene.component.CgDynamicLights;
import com.crystalgraphics.render.scene.renderer.feature.field.CgEntityBoxes;
import com.crystalgraphics.render.scene.renderer.feature.field.CgVoxelWindow;
import com.crystalgraphics.render.scene.renderer.feature.media.CgSceneMedia;
import com.crystalgraphics.render.shader.CgBindingModel;
import com.crystalgraphics.render.stage.CgStageFiring;
import com.crystalgraphics.render.view.CgViewState;
import com.crystalgraphics.trace.CgGpuTrace;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;
import org.joml.Matrix4fc;

import javax.annotation.Nullable;

/**
 * The lights in a scene this frame, Unreal's local lights: points, spots and tubes, each a position, a radius, a
 * colour and a falloff, which the scene renderer bins into its light grid and adds onto the scene behind each pixel.
 * A consumer makes lights as components ({@code scene.pointLight()}, {@code spotLight()}, {@code tubeLight()}), adds
 * one-frame ones ({@code scene.onceLights()}), or collects many short-lived ones each frame
 * ({@code scene.dynamicLights(source)}); a kernel appends its own into the same list ({@code scene.gpuLights(appender)},
 * a light per particle). The grid, MegaLights and their settings ({@link CgLightSettings}) are the renderer's.
 *
 * <pre>{@code
 * CgPointLight torch = scene.pointLight().at(x, y + 1.5, z).radius(6f).color(0xFFFF9030).intensity(40f).spawn();
 * scene.dynamicLights((view, out) -> out.point(x, y, z, 3f, 0xFF40A0FF, 8f).exponent(8f));
 * }</pre>
 *
 * <pre>{@code
 * // from a kernel: an appender, run at the top of each transparent firing
 * scene.gpuLights(new CgSceneLights.Appender() {
 *     public boolean append(CgComputePass pass, CgSceneLights lights) {
 *         if (embers == 0) return false;                        // nothing to add this firing
 *         lights.into(pass.dispatch(gather, embers)).bind("EMBERS", records);
 *         return true;
 *     }
 * });
 *
 * // its kernel: CgLight declared in its own file (STRUCT), LIGHTS an append buffer of it
 * #pragma kernel Gather append
 * #include "crystalgraphics:shaders/world/lights/lights.glsl"
 * struct CgLight { vec4 positionRadius; vec4 colorExponent; vec4 spotCos; vec4 segmentCone; vec4 ambient; vec4 air; };
 * Properties { _LightEye ("The firing's eye, absolute", vec4) = (0, 0, 0, 0) }
 * Buffers { EMBERS ("Embers", vec4, readonly)  LIGHTS ("Lights", CgLight, append) }
 * void Gather() {
 *     vec4 e = EMBERS(CG_ELEMENT);
 *     LIGHTS_APPEND(cg_light(e.xyz - _LightEye.xyz, 2.0, vec3(4.0, 1.6, 0.3), 0.0));
 * }
 * }</pre>
 *
 * <ul>
 *   <li>A component's colour is sRGB, as authored colours are; a kernel appends linear colour, premultiplied by its
 *       intensity.</li>
 *   <li>A light lights a surface as Unreal's simple light does: colour × intensity × falloff × N·L × albedo / π, the
 *       albedo the scene's colour over the lightmap at the light's block (read when it is added; a kernel's light sets
 *       its {@code ambient}, or the scene is taken as it is).</li>
 *   <li>Author a light for the night. Under an open sky by day it fades, as an eye adapted to the sun would see it:
 *       full at night and wherever no sky reaches, a tenth under a full day's open sky
 *       ({@code -Dcrystalgraphics.world.lightsDayFade}, 1 for never).</li>
 *   <li>Exponent 0 is inverse-squared falloff in blocks, faded to nothing at the radius: a quarter as strong at two
 *       blocks as at one. Any other exponent is Unreal's {@code (1 - d²/r²)^exponent}.</li>
 *   <li>A point in a surface's plane only grazes it: give a light that touches surfaces (an ember on the ground) a
 *       source radius. A particle's light takes its drawn radius.</li>
 *   <li>A kernel's positions are from the firing's eye ({@link #cameraX()}, {@code _LightEye}), as the froxels are.</li>
 *   <li>Past {@link #CAPACITY} lights a frame the rest are dropped and counted ({@code world.lights-dropped}).</li>
 *   <li>A light from the CPU casts shadows unless {@code castsShadow(false)}: blocks and entities between it and a
 *       surface withhold it, traced as Unreal's MegaLights traces them (docs/LIGHTS.md). A kernel's light casts none
 *       unless its {@code ambient.w} is 1. Past the screen the rays trace the world's blocks round the camera
 *       ({@link CgVoxelWindow}) and the entities round it ({@link CgEntityBoxes}), each a capsule; meshes drawn
 *       through the world renderer shadow only where they are on screen.</li>
 *   <li>Minecraft's own light is shadowed by our objects too: the sun's, and the block light of every light-giving block
 *       within {@code -Dcrystalgraphics.world.blockShadowReach} blocks (torches, glowstone, lava) the voxel window has
 *       found, while something of ours is drawn opaque.</li>
 * </ul>
 */
public final class CgSceneLights {

    /** The most lights a frame, the CPU's and the kernels' together: {@code -Dcrystalgraphics.world.lights}. */
    public static final int CAPACITY = Integer.getInteger("crystalgraphics.world.lights", 65536);
    /** The append buffer {@link #into} binds, of {@code lights.glsl}'s {@code CgLight}. */
    public static final String BUFFER = "LIGHTS";
    /**
     * The light as a kernel declares it, in its own file before its {@code Buffers}: a buffer's element is laid out
     * from the kernel's file alone, not its includes.
     */
    public static final String STRUCT =
            "struct CgLight { vec4 positionRadius; vec4 colorExponent; vec4 spotCos; vec4 segmentCone; vec4 ambient; vec4 air; };";
    /**
     * Floats a light: position and radius, colour and exponent, a spot's direction and the cosine of its outer angle (-2
     * for none), a tube's length along its axis and a spot's 1 / (cos inner - cos outer), the block and sky light at
     * the light (-1 unknown) and its source radius.
     */
    public static final int FLOATS = 24;
    /**
     * The light grid, Unreal's (LightGridInjection.cpp:34-50): as {@code lights.glsl}'s {@code CG_LIGHT_*}. Its cells'
     * lists share {@code PER_CELL} indices a cell, as Unreal's MaxCulledLightsPerCell does under its linked lists.
     */
    public static final int TILE = 64, SLICES = 32, PER_CELL = 32;
    /** Cells a parent cell spans each way: Unreal's r.LightCulling.TwoLevel.Exponent of 2. */
    public static final int PARENT = 4;
    /** What adds the lights onto the scene: a full-screen pass reading the grid. */
    public static final String APPLY_SHADER = "crystalgraphics:shaders/world/lights/lights.shader";

    /** Adds lights from a kernel, each transparent firing. */
    public interface Appender {
        /** Before the firing's pass opens, its eye known: uploads what {@link #append} binds. */
        default void upload(CgRecording recording, CgSceneLights lights) {
        }

        /** Appends its lights: {@link #into} each dispatch that does; false when it appends none this firing. */
        boolean append(CgComputePass pass, CgSceneLights lights);

        /**
         * Whether every light it appends is a point, never a spot or a tube. While every light shaded exactly is, the
         * apply runs a program without the spots' and tubes' path, a third cheaper on a floor of particle lights.
         */
        default boolean pointsOnly() {
            return false;
        }
    }

    private static final CgMesh FULLSCREEN = CgMesh.vertices(3, CgMeshTopology.TRIANGLES);
    private static final int GPU_ZONE = CgGpuTrace.name("world.lights"), GPU_APPLY = CgGpuTrace.name("world.lightsApply");
    /** The CPU's recording of each part, inside world.recordTransparent. */
    private static final int ZONE_RECORD = CgTrace.name("world.lights.record"), ZONE_APPLY = CgTrace.name("world.lights.apply");

    private final CgLightGrid grid = new CgLightGrid();
    private final CgLightList list;
    private final CgShadowCasters casters = new CgShadowCasters(grid);
    private final CgLightGBuffer gbuffer = new CgLightGBuffer(grid);
    private final CgSunShadows sun = new CgSunShadows(grid, casters);
    private final CgMegaLights shadows;
    private final CgLightVolume volume;
    /** The apply, without and with the spots' and tubes' path ({@code CG_LIGHTS_SHAPED}). */
    private CgMaterialInstance applyPoints, applyShaped;
    /** Whether {@link #warm} has built what the first light records. */
    private boolean warmed;
    /** Its kernels parsed, emitted and stripped on a worker, which {@link #warm} waits for: 85-115 ms on the render thread. */
    @Nullable
    private CgJob<Void> parse;

    /**
     * The lights of a scene whose light components {@code components} writes into each frame's collector: the scene
     * renderer's.
     */
    public CgSceneLights(CgDynamicLights components) {
        list = new CgLightList(components);
        shadows = new CgMegaLights(grid, list, casters, gbuffer, sun, () -> hostWorld.getAsBoolean());
        volume = new CgLightVolume(grid, list, casters, shadows, () -> hostWorld.getAsBoolean());
    }

    /**
     * Engine side, {@code CgScene.onceLights}': this frame's lights from the CPU. Render thread, before the frame's
     * first world firing.
     */
    public CgLightCollector frame() {
        return list.frame;
    }

    /** Engine side, {@code CgScene.dynamicLights}'s: collected each frame from now. Any thread. */
    public void add(CgDynamicLights source) {
        list.add(source);
    }

    /** Engine side: collected no more. */
    public void remove(CgDynamicLights source) {
        list.remove(source);
    }

    /** Adds {@code appender}, from any thread; a frame recording now may miss it. */
    public void add(Appender appender) {
        list.add(appender);
    }

    public void remove(Appender appender) {
        list.remove(appender);
    }

    /** An sRGB channel decoded to linear, 0 to 1; past 1 (a colour times an intensity) it carries on linearly. */
    public static float linear(float srgb) {
        if (srgb >= 1f) return srgb;
        if (srgb <= 0f) return 0f;
        return srgb <= 0.04045f ? srgb / 12.92f : (float) Math.pow((srgb + 0.055f) / 1.055f, 2.4);
    }

    /** {@code dispatch} appending into this firing's list: {@link #BUFFER}, its count, and {@code _LightEye}. */
    public CgDispatch into(CgDispatch dispatch) {
        return dispatch.bind(BUFFER, list.list).counter(BUFFER, list.count, 0)
                .set("_LightEye", (float) grid.cameraX, (float) grid.cameraY, (float) grid.cameraZ, 0f);
    }

    /** Where the firing's eye is, absolute: what a kernel's positions are from. */
    public double cameraX() {
        return grid.cameraX;
    }

    public double cameraY() {
        return grid.cameraY;
    }

    public double cameraZ() {
        return grid.cameraZ;
    }

    /** The CPU's lights the last firing listed: 0 where it listed none, or its kernels were not yet ready. */
    public int listed() {
        return list.listed;
    }

    /** Whether the last firing applied lights: its kernels ready and something listed or appended. */
    public boolean applied() {
        return lastLit;
    }

    /** Lights past {@link #CAPACITY} in a recent firing, read back a few frames late; the CPU's at once. */
    public long dropped() {
        return list.dropped;
    }

    /** {@code -Dcrystalgraphics.world.lightsMinScreenRadius}: Unreal's r.MinScreenRadiusForLights, 0.03 unless set. */
    public static float minScreenRadius() {
        return CgLightSettings.MIN_SCREEN.get();
    }

    /** The cells' shared budget this firing, in lights a cell on average. */
    public int budgetPerCell() {
        return grid.budgetPerCell();
    }

    /** Lights a recent firing's cells dropped past their shared budget, read back a few frames late. */
    public long cellsDropped() {
        return grid.cellsDropped;
    }

    /**
     * Each cell's lights as the last firing binned them: two {@code uint}s a cell, its count and where its list starts
     * in {@link #indices}, x fastest, then y, then the slice.
     */
    public CgGraphBuffer cells() {
        return grid.cells;
    }

    /** The cells' lists of light indices, each in index order, {@link #PER_CELL} a cell on average. */
    public CgGraphBuffer indices() {
        return grid.indices;
    }

    /** Cells across and up this firing; {@link #SLICES} deep. */
    public int gridX() {
        return grid.gridX;
    }

    public int gridY() {
        return grid.gridY;
    }

    /**
     * How far past its cone the light grid lists a spot at eye depth z, {@code z * [0] + [1]} blocks: the lit air's
     * widening of its cone ({@link CgSceneMedia}). Read only.
     */
    public float[] airSpread() {
        return grid.airSpread;
    }

    /** The unit-depth rays at the target's edges, as the last firing placed them. Read only. */
    public float[] rays() {
        return grid.rays;
    }

    /** A tile's share of the target across, as the last firing cut its grid. */
    public float tileU() {
        return grid.tileU;
    }

    /** A tile's share of the target up. */
    public float tileV() {
        return grid.tileV;
    }

    /** The last firing's camera-relative world to view: what a light's position is turned by to meet its cell. */
    public Matrix4fc toView() {
        return grid.toView;
    }

    /** The slices' B, O and S: slice = log2(depth * B + O) * S. */
    public float[] zParams() {
        return grid.zParams;
    }

    /** This firing's list: {@link #FLOATS} floats a light, camera-relative. */
    public CgGraphBuffer list() {
        return list.list;
    }

    /** This firing's count, a {@code uint} at 0, possibly past {@link #CAPACITY}: read it clamped. */
    public CgGraphBuffer count() {
        return list.count;
    }

    /**
     * One of our opaque draws as a shadow caster this firing, after {@link #record}: {@code submesh}, {@code first} and
     * {@code count} its range (submesh -1 the whole mesh), at an absolute position, its local transform the 16 floats
     * from {@code at}. Traced through its mesh's distance field once that is made; too many or too far, not at all.
     */
    public void caster(CgMesh mesh, int submesh, int first, int count, double x, double y, double z, float[] transforms, int at) {
        casters.add(mesh, submesh, first, count, x, y, z, transforms, at);
    }

    /** Whether the world renderer should write our surfaces' normals: the shadow passes read them. */
    public boolean wantsNormals() {
        return CgLightSettings.SHADOWS.get();
    }

    /** Before {@link #record}: the normals the firing's view wrote in its opaque firing this frame, or null. */
    public void normals(@Nullable CgGraphTexture target) {
        gbuffer.normals(target);
    }

    /** Every kernel a lit firing dispatches, for the world's collector: the grid's, the volume's, MegaLights'. */
    public static void precacheKernels() {
        CgLightGrid.precache();
        CgLightVolume.precache();
        CgLightGBuffer.precache();
        CgSunShadows.precache();
        CgMegaLights.precache();
    }

    private boolean lastLit;
    private BooleanSupplier hostWorld = () -> true;

    /**
     * Engine side: whether its scene is the host's world, read as it records. Only then do Minecraft's blocks and
     * entities round the eye shadow its lights and its light-giving blocks join them; a scene of your own has neither.
     */
    public void hostWorld(BooleanSupplier ofHost) {
        this.hostWorld = ofHost;
    }

    /**
     * The firing's list under {@code view}: the CPU's lights placed from its eye, then each appender's, binned into the
     * grid. False when it holds none, and nothing is recorded.
     */
    public boolean record(CgStageFiring frame, CgCamera view, Matrix4fc toWorld, float targetWidth, float targetHeight) {
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.WORLD, ZONE_RECORD)) {
            CgLightSettings.follow();
            CgRecording recording = frame.recording();
            list.collect(frame.view());
            // The view, every firing: the sun's shadows need it with no light listed.
            grid.place(view, toWorld, targetWidth, targetHeight);
            casters.reset();
            // Lights wait for their kernels as the apply waits for its programs: a dispatch taking one still linking
            // held the loading screen 100 ms
            if (!list.begin(grid, gbuffer.hasNormals(), hostWorld.getAsBoolean()) || !grid.ready() || !volume.ready()) {
                lastLit = false;
                warm();
                return false;
            }
            list.allocate();
            grid.allocate();
            list.upload(recording);
            grid.clearAlloc(recording);
            Appender[] all = list.appenders();
            for (Appender a : all) a.upload(recording, this);
            CgComputePass pass = recording.compute("world.lights", frame.constants()).timed(GPU_ZONE);
            boolean appended = false;
            for (Appender a : all) {
                if (!a.append(pass, this)) continue;
                appended = true;
                if (!a.pointsOnly()) list.shapedExact = list.shapedAny = true;
            }
            boolean lit = list.listed > 0 || appended;
            lastLit = lit;
            if (lit) grid.bin(recording, frame.constants(), pass, list, frame.host().textures().lightmapTexture());
            else pass.end();
            list.readDropped(recording, appended);
            CgTrace.counter(CgChannels.WORLD, "world.lights", list.listed);
            CgTrace.counter(CgChannels.WORLD, "world.lights-dropped", list.dropped);
            CgTrace.counter(CgChannels.WORLD, "world.lights-cell-dropped", grid.cellsDropped);
            CgTrace.counter(CgChannels.WORLD, "world.lights-shadowed", list.shadowed);
            CgTrace.counter(CgChannels.WORLD, "world.lights-blocks", list.blockCount);
            return lit;
        }
    }

    /**
     * Builds what the first light records, while there is none: Unreal compiles its light grid's shaders with the
     * engine's globals, not on the first light. Polled each empty firing until done; the warm budget spreads it.
     */
    private void warm() {
        if (warmed) return;
        if (parse == null) {
            // Parsed, emitted and stripped there, so the render thread's precache below only submits
            parse = CgJob.lane(CgJobLane.BACKGROUND).named("light kernels").run(() -> CgKernel.ahead(CgSceneLights::precacheKernels));
        }
        if (!parse.isDone()) return;
        list.allocate();
        grid.allocate();
        grid.kernels();
        volume.kernels();
        warmed = applyBuilt();
    }

    /** The apply's and the G-buffer's programs, made once; whether all three are built, polling those still compiling. */
    private boolean applyBuilt() {
        if (applyPoints == null) {
            applyPoints = CgMaterial.load(APPLY_SHADER).instance();
            applyShaped = CgMaterial.load(APPLY_SHADER).keyword("CG_LIGHTS_SHAPED", true).instance();
        }
        // The pipelines the draws bind: a material's precache answered for a program its pipeline still waited on.
        CgPipeline points = CgMaterialProxy.of(applyPoints).pipeline(CgInstanceKind.OBJECT);
        CgPipeline shaped = CgMaterialProxy.of(applyShaped).pipeline(CgInstanceKind.OBJECT);
        if (points == null || shaped == null) return false;
        return points.precache() & shaped.precache() & gbuffer.ready();
    }

    /**
     * Adds this firing's lights onto the scene behind each pixel, after {@link #record} answered true: one full-screen
     * pass over the stage's target, Unreal's simple lights through the grid, MegaLights' shadowed ones added in. Skipped
     * while its program is unbuilt. {@code transparents} fills the translucency volume, and {@code directional} its
     * direction too.
     */
    public void apply(CgStageFiring stage, CgRecording recording, boolean transparents, boolean directional) {
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.WORLD, ZONE_APPLY)) {
            volume.localLights = null;
            // Both warmed, so a spot turning up never compiles in a frame.
            if (!applyBuilt()) return;
            gbuffer.record(stage, recording);
            sun.wanted(stage, gbuffer.hasNormals());
            shadows.record(stage, recording);
            if (transparents && grid.binned && CgLightSettings.VOLUME.get()) volume.record(stage, recording, directional);
            if (!grid.binned && !shadows.on) return;
            CgGraphBuffer none = list.zeroes(recording);
            CgMaterialInstance apply = (shadows.on ? list.shapedExact : list.shapedAny) ? applyShaped : applyPoints;
            boolean binned = grid.binned, shadowed = shadows.on;
            apply.buffer("CELLS", binned ? grid.cells : none)
                    .buffer("INDICES", binned ? grid.indices : none).buffer("VIEWED", binned ? grid.viewed : none);
            grid.values(apply.set1f("_Shadows", shadowed ? 1f : 0f)
                    .sampler("_Shadowed", 0, shadowed ? shadows.filtered : CgFallbackTextures.BLACK_1x1)
                    .sampler("_Visibility", 1, shadowed ? shadows.filteredVis : CgFallbackTextures.WHITE_1x1)
                    .vec4("_Sun", shadowed && sun.on ? 1f : 0f, CgWorldAtmosphere.SUN_SHARE.get(), 0f, 0f)
                    .set1i("_EyeX", grid.eyeBlock[0]).set1i("_EyeY", grid.eyeBlock[1]).set1i("_EyeZ", grid.eyeBlock[2])
                    .vec4("_EyeFrac", grid.eyeFrac[0], grid.eyeFrac[1], grid.eyeFrac[2], 0f).set1i("_Scale", shadows.lightScale)
                    .sampler("_GBuffer", 5, gbuffer.texture));
            if (hostWorld.getAsBoolean()) CgVoxelWindow.get().bind(apply, 2);
            else CgVoxelWindow.get().bindNone(apply, 2);
            CgPipeline pipeline = CgMaterialProxy.of(apply).pipeline(CgInstanceKind.OBJECT);
            if (pipeline == null) return;
            CgRasterPass add = recording.raster("worldLights.target").color(stage.target(), CgLoad.load())
                    .depth(stage.depth(), CgLoad.load(), CgDepthAccess.WRITE).constants(stage.constants())
                    .readsDepth(stage.depth()).readsColor(stage.target())
                    // What the scene was lit by, divided out to find its albedo: unbound, it reads white and divides by 1.
                    .texture(CgBindingModel.LIGHTMAP, stage.host().textures().lightmapTexture()).timed(GPU_APPLY);
            CgChunkBuilder chunks = recording.chunks().begin();
            chunks.draw(pipeline, CgMaterialProxy.of(apply).bindings(), FULLSCREEN);
            chunks.instance();
            add.add(chunks.end());
            add.end();
        }
    }

    /**
     * Binds the light grid to {@code material}, a program handed the world's lights ({@code env/handed/lights.glsl}): the
     * list, the cells and their indices as the last firing to bin lights left them, and where the grid lay. After
     * {@link #record} in a transparent firing that is this firing's; in an opaque one, the last frame's.
     */
    public void hand(CgMaterialInterface material, CgRecording recording) {
        boolean live = grid.binned && list.list != null && grid.cells != null;
        CgGraphBuffer none = list.zeroes(recording);
        // The engine's values on whatever the draw names, shared or its own: a view's binding in a material's place.
        CgMaterialProxy proxy = CgMaterialProxy.of(material);
        proxy.buffer("WORLD_LIGHTS", live ? list.list : none).buffer("WORLD_LIGHT_CELLS", live ? grid.cells : none)
                .buffer("WORLD_LIGHT_INDICES", live ? grid.indices : none);
        grid.handValues(proxy.writer(), live);
    }

    /**
     * Binds no lights to {@code material}, a program composing its own, where nothing was ever bound: what a draw of it
     * before this frame's {@link #hand} binds, as its depth in the all-opaque prepass does (Unreal binds an empty
     * buffer where a parameter is unset). Once per material; {@link #hand} replaces it.
     */
    public void handNone(CgMaterialInterface material, CgRecording recording) {
        CgMaterialProxy proxy = CgMaterialProxy.of(material);
        if (proxy.hasBuffer("WORLD_LIGHTS")) return;
        CgGraphBuffer none = list.zeroes(recording);
        proxy.buffer("WORLD_LIGHTS", none).buffer("WORLD_LIGHT_CELLS", none).buffer("WORLD_LIGHT_INDICES", none);
    }

    /**
     * Binds the sun's shadow map to {@code material}, a program composing its own ({@code env/handed/sun.glsl}): this
     * firing's after {@link #apply} in a transparent firing, none in an opaque one, whose pixels the deferred pass
     * shadows.
     */
    public void handSun(CgMaterialInterface material, boolean transparent) {
        sun.hand(material, transparent);
    }

    /** The world's lights on transparent surfaces this firing, for {@link CgBindingModel#LOCAL_LIGHTS}; null for none. */
    @Nullable
    public CgGraphTexture localLights() {
        return volume.localLights;
    }

    /**
     * The air lit, after the media's injectors and before their integration: each froxel holding extinction lit by its
     * light-grid cell's lights through world/lights/volume.compute's {@code Scatter} (Unreal's LightScatteringCS), into a
     * froxel volume of the light scattered toward the eye. Null with no light binned this firing.
     */
    @Nullable
    public CgGraphTexture scatter(CgRecording recording, CgComputePass pass, CgSceneMedia media, CgViewState state,
                                  CgGraphTexture extinction) {
        return volume.scatter(recording, pass, media, state, extinction);
    }
}
