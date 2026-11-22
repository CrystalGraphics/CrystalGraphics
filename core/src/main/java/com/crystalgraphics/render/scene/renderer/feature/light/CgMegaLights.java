package com.crystalgraphics.render.scene.renderer.feature.light;

import java.util.function.BooleanSupplier;
import com.crystalgraphics.api.material.CgMaterialValues;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.compute.CgCompute;
import com.crystalgraphics.compute.CgKernel;
import com.crystalgraphics.gpu.resource.CgFallbackTextures;
import com.crystalgraphics.gpu.resource.CgTextureDesc;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.render.graph.CgComputePass;
import com.crystalgraphics.render.graph.CgGraphBuffer;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.scene.renderer.feature.field.CgEntityBoxes;
import com.crystalgraphics.render.scene.renderer.feature.field.CgVoxelWindow;
import com.crystalgraphics.render.stage.CgStageFiring;
import com.crystalgraphics.render.view.CgViewState;
import com.crystalgraphics.trace.CgGpuTrace;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;

/**
 * The lights casting shadows, lit as Unreal's MegaLights lights them (MegaLights.cpp's pass order): samples a pixel, or
 * a downsampled one, picked by weighted reservoir from its cell's lights and traced across the screen then through the
 * occluders ({@code world/lights/sample.compute}); every pixel shaded from a nearby downsampled pixel's samples
 * ({@code shade.compute}); accumulated over frames into the view's history ({@code temporal.compute}); filtered across
 * the screen where still noisy ({@code denoise.compute}): each a compute pass of 8x8 groups, as MegaLights' are. Past
 * the screen the rays trace the world's blocks round the eye ({@link CgVoxelWindow}), its entities as capsules
 * ({@link CgEntityBoxes}) and our meshes' distance fields ({@link CgShadowCasters}).
 */
final class CgMegaLights {

    static final String SAMPLE_KERNELS = "crystalgraphics:shaders/world/lights/sample.compute",
            SHADE_KERNELS = "crystalgraphics:shaders/world/lights/shade.compute",
            TEMPORAL_KERNELS = "crystalgraphics:shaders/world/lights/temporal.compute",
            DENOISE_KERNELS = "crystalgraphics:shaders/world/lights/denoise.compute",
            TILES_KERNELS = "crystalgraphics:shaders/world/lights/tiles.compute",
            TILE_DEPTH_KERNELS = "crystalgraphics:shaders/world/lights/tile_depth.compute";
    private static final int GPU_SAMPLE = CgGpuTrace.name("world.lightsSample"), GPU_SHADE = CgGpuTrace.name("world.lightsShade"),
            GPU_TEMPORAL = CgGpuTrace.name("world.lightsTemporal"), GPU_DENOISE = CgGpuTrace.name("world.lightsDenoise"),
            GPU_TILES = CgGpuTrace.name("world.lightsTiles"), GPU_TILE_DEPTH = CgGpuTrace.name("world.lightsTileDepth");
    private static final int ZONE_SHADOWS = CgTrace.name("world.lights.shadows");
    /** Unreal's capsule shadow tiles: a tile's entities, a bit each, 16 a channel. */
    private static final int ENTITY_TILE = 16;
    private static final CgFormat TILES_FORMAT = CgFormat.RGBA32_FLOAT;
    /** Each 4x4 pixels' least and most window depth, the tiles' first step. */
    private static final CgFormat RANGES_FORMAT = CgFormat.RG32_FLOAT;
    /** A sample's light index, exact as a float to 2^24, its weight, and the sun's visibility. */
    private static final CgFormat SAMPLES_FORMAT = CgFormat.RGBA32_FLOAT;
    /**
     * Each output of the shade, temporal and denoise kernels, a texture each (an image is one texture): the lighting,
     * alpha where valid; the sun's visibility; the accumulated lighting and its frames; its luminance's moments, eye
     * depth and confidence; the sun's and block light's visibility and their squares.
     */
    private static final CgFormat LIGHTING_FORMAT = CgFormat.RGBA16_FLOAT;
    private static final String[] HISTORY_PARTS = {"lighting", "moments", "visibility"};
    private static final CgViewState.HistoryKey[] HISTORY = {new CgViewState.HistoryKey("cg_world_lights.history-lighting"),
            new CgViewState.HistoryKey("cg_world_lights.history-moments"),
            new CgViewState.HistoryKey("cg_world_lights.history-visibility")};

    private final CgLightGrid grid;
    private final CgLightList list;
    private final CgShadowCasters casters;
    private final CgLightGBuffer gbuffer;
    private final CgSunShadows sun;
    private CgKernel tilesKernel, tileDepthKernel, sampleKernel, shadeKernel, temporalKernel, denoiseKernel;
    private CgGraphTexture samples, shaded, shadedVis, entityTiles, tileRanges;
    /** What the apply adds: the filtered lighting and the sun's and block light's visibility. */
    CgGraphTexture filtered, filteredVis;
    /**
     * This firing's view's history of the lighting, moments and visibility; the scratch a view keeping no history
     * accumulates into; what this firing's temporal pass writes and its denoise reads, one or the other.
     */
    private final CgGraphTexture[] history = new CgGraphTexture[3], scratchHistory = new CgGraphTexture[3];
    private CgGraphTexture[] accumulated = history;
    /** The shading's description at its last size, which the history shares. */
    private CgTextureDesc lightingDesc;
    private boolean freshHistory;
    private CgTexture lightmap = CgFallbackTextures.WHITE_1x1;
    /** Whether the shadows ran this firing; when not, the apply lights every light unshadowed. */
    boolean on;
    /** This firing's entities, as capsules. */
    int entities;
    /** Target pixels the shading, history and filter's texels span each way this firing: 1, 2 or 4. */
    int lightScale = 1;
    /** The downsample factor the sample and shade passes read; the samples a downsampled pixel takes, as their block. */
    private int downX = 2, downY = 2, sampleX = 2, sampleY = 2;
    // Whether its scene is the host's world: Minecraft's blocks and entities shadow only that
    private final BooleanSupplier hostWorld;

    CgMegaLights(CgLightGrid grid, CgLightList list, CgShadowCasters casters, CgLightGBuffer gbuffer, CgSunShadows sun,
                 BooleanSupplier hostWorld) {
        this.hostWorld = hostWorld;
        this.grid = grid;
        this.list = list;
        this.casters = casters;
        this.gbuffer = gbuffer;
        this.sun = sun;
    }

    /** Every kernel it dispatches; inside {@link CgKernel#ahead}, their sources on a worker. */
    static void precache() {
        CgCompute.load(TILE_DEPTH_KERNELS).kernel("TileDepth").precache();
        CgCompute.load(TILES_KERNELS).kernel("EntityTiles").precache();
        CgCompute.load(SAMPLE_KERNELS).kernel("Sample").precache();
        CgCompute.load(SHADE_KERNELS).kernel("Shade").precache();
        CgCompute.load(TEMPORAL_KERNELS).kernel("Temporal").precache();
        CgCompute.load(DENOISE_KERNELS).kernel("Denoise").precache();
    }

    /** What the shadow rays leave out: {@code lightsSkip}, and our meshes without {@code lightsMeshShadows}. */
    static int skip() {
        return CgLightSettings.SKIP.get() | (CgLightSettings.MESH_SHADOWS.get() ? 0 : 8);
    }

    /**
     * This firing's shadowed lighting, when anything casts (a CPU light, a block, the sun): {@link #on} whether it ran,
     * not while a kernel is unbuilt. The sun's map is drawn inside it.
     */
    void record(CgStageFiring stage, CgRecording recording) {
        on = CgLightSettings.SHADOWS.get() && (list.shadowed > 0 || list.blockCount > 0 || sun.on) && run(stage, recording);
    }

    private boolean run(CgStageFiring stage, CgRecording recording) {
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.WORLD, ZONE_SHADOWS)) {
            if (sampleKernel == null) {
                sampleKernel = CgCompute.load(SAMPLE_KERNELS).kernel("Sample");
                shadeKernel = CgCompute.load(SHADE_KERNELS).kernel("Shade");
                temporalKernel = CgCompute.load(TEMPORAL_KERNELS).kernel("Temporal");
                denoiseKernel = CgCompute.load(DENOISE_KERNELS).kernel("Denoise");
                tilesKernel = CgCompute.load(TILES_KERNELS).kernel("EntityTiles");
                tileDepthKernel = CgCompute.load(TILE_DEPTH_KERNELS).kernel("TileDepth");
                for (CgKernel k : new CgKernel[] {sampleKernel, shadeKernel, temporalKernel, denoiseKernel, tilesKernel, tileDepthKernel}) {
                    k.precache();
                }
            }
            // Each started, whichever is not ready.
            boolean ready = sampleKernel.isReady() & shadeKernel.isReady() & temporalKernel.isReady() & denoiseKernel.isReady()
                    & tilesKernel.isReady() & tileDepthKernel.isReady();
            if (!ready) return false;
            int mode = Math.round(CgLightSettings.DOWNSAMPLE.get());
            downX = mode > 0 ? 2 : 1;
            downY = mode > 1 ? 2 : 1;
            // GetNumSamplesPerPixel2d: a downsampled pixel's samples as a block of the samples texture, 1x1, 2x1 or 2x2.
            int count = CgLightSettings.SAMPLES.get();
            sampleX = count >= 2 ? 2 : 1;
            sampleY = count >= 4 ? 2 : 1;
            int w = Math.max(1, (int) grid.targetW), h = Math.max(1, (int) grid.targetH);
            int sw = (w + downX - 1) / downX * sampleX, sh = (h + downY - 1) / downY * sampleY;
            if (samples == null || samples.getWidth() != sw || samples.getHeight() != sh) {
                samples = CgGraphTexture.transientTexture("cg_world_lights.samples", new CgTextureDesc(sw, sh, SAMPLES_FORMAT));
            }
            // Shading, history and filter at the target's size over the shade scale each way; the samples keep theirs.
            lightScale = Integer.highestOneBit(CgLightSettings.SHADE_SCALE.get());
            int lw = (w + lightScale - 1) / lightScale, lh = (h + lightScale - 1) / lightScale;
            if (shaded == null || shaded.getWidth() != lw || shaded.getHeight() != lh) {
                CgTextureDesc each = new CgTextureDesc(lw, lh, LIGHTING_FORMAT);
                lightingDesc = each;
                shaded = CgGraphTexture.transientTexture("cg_world_lights.shaded", each);
                shadedVis = CgGraphTexture.transientTexture("cg_world_lights.shaded-visibility", each);
                filtered = CgGraphTexture.transientTexture("cg_world_lights.filtered", each);
                filteredVis = CgGraphTexture.transientTexture("cg_world_lights.filtered-visibility", each);
                for (int i = 0; i < 3; i++) {
                    scratchHistory[i] = CgGraphTexture.transientTexture("cg_world_lights.accumulated-" + HISTORY_PARTS[i], each);
                }
            }
            // The view's own history; a view keeping none accumulates into transients, fresh each frame.
            CgViewState state = stage.view().state();
            if (state.keepsHistory()) {
                for (int i = 0; i < 3; i++) history[i] = state.history(HISTORY[i], lightingDesc, recording);
                accumulated = history;
                freshHistory = state.fresh(HISTORY[0]) || !stage.view().historyValid();
            } else {
                accumulated = scratchHistory;
                freshHistory = true;
            }
            CgGraphBuffer none = list.zeroes(recording);
            // Past the screen: the window's blocks and the entities' capsules round the eye.
            CgVoxelWindow.get().useDistance();
            // The client world's entities shadow only the client world: a scene of your own has none
            CgGraphBuffer boxes = null;
            if (hostWorld.getAsBoolean()) {
                CgEntityBoxes.around(grid.cameraX, grid.cameraY, grid.cameraZ, stage.host().partialTick());
                CgEntityBoxes.upload(recording);
                boxes = CgEntityBoxes.buffer();
            }
            entities = boxes != null ? CgEntityBoxes.count() : 0;
            // Our meshes: the fields made since the last firing, then this firing's casters.
            casters.upload(recording);
            if (sun.on) sun.record(stage, recording);
            if (entities > 0) {
                int tx = (w + ENTITY_TILE - 1) / ENTITY_TILE, ty = (h + ENTITY_TILE - 1) / ENTITY_TILE;
                if (entityTiles == null || entityTiles.getWidth() != tx || entityTiles.getHeight() != ty) {
                    entityTiles = CgGraphTexture.transientTexture("cg_world_lights.tiles", new CgTextureDesc(tx, ty, TILES_FORMAT));
                    tileRanges = CgGraphTexture.transientTexture("cg_world_lights.tile-ranges",
                            new CgTextureDesc(tx * (ENTITY_TILE / 4), ty * (ENTITY_TILE / 4), RANGES_FORMAT));
                }
                CgComputePass ranges = recording.compute("world.lightsTileDepth", stage.constants())
                        .readsDepth(stage.depth()).timed(GPU_TILE_DEPTH);
                ranges.dispatch(tileDepthKernel, tileRanges.getWidth(), tileRanges.getHeight(), 1)
                        .image("RANGES", tileRanges).values().vec4("_Screen", grid.targetW, grid.targetH, 0f, 0f);
                ranges.end();
                CgComputePass tiled = recording.compute("world.lightsTiles", stage.constants()).timed(GPU_TILES);
                tileValues(tiled.dispatch(tilesKernel, tx, ty, 1).image("TILES", entityTiles).bind("ENTITIES", boxes).values());
                tiled.end();
            }
            boolean binned = grid.binned;
            CgComputePass sampling = recording.compute("world.lightsSample", stage.constants()).readsDepth(stage.depth())
                    .timed(GPU_SAMPLE);
            CgMaterialValues sample = sampling.dispatch(sampleKernel, sw, sh, 1).image("SAMPLES", samples)
                    .bind("VIEWED", binned ? grid.viewed : none).bind("CELLS", binned ? grid.cells : none)
                    .bind("INDICES", binned ? grid.indices : none).bind("ENTITIES", boxes != null ? boxes : none).values();
            sampleValues(sample);
            if (hostWorld.getAsBoolean()) CgVoxelWindow.get().bind(sample, 0);
            else CgVoxelWindow.get().bindNone(sample, 0);
            sampling.end();
            lightmap = stage.host().textures().lightmapTexture();
            if (lightmap == null) lightmap = CgFallbackTextures.WHITE_1x1;
            CgComputePass shading = recording.compute("world.lightsShade", stage.constants()).readsDepth(stage.depth())
                    .timed(GPU_SHADE);
            shadeValues(shading.dispatch(shadeKernel, lw, lh, 1).image("SHADED", shaded).image("VISIBILITY", shadedVis)
                    .bind("LIGHTS", binned ? list.list : none).values());
            shading.end();
            CgComputePass accumulating = recording.compute("world.lightsTemporal", stage.constants())
                    .readsDepth(stage.depth()).timed(GPU_TEMPORAL);
            temporalValues(accumulating.dispatch(temporalKernel, lw, lh, 1).image("LIGHTING", accumulated[0])
                    .image("MOMENTS", accumulated[1]).image("VISIBILITY", accumulated[2]).values());
            accumulating.end();
            CgComputePass filtering = recording.compute("world.lightsDenoise", stage.constants()).readsDepth(stage.depth())
                    .timed(GPU_DENOISE);
            denoiseValues(filtering.dispatch(denoiseKernel, lw, lh, 1).image("FILTERED", filtered)
                    .image("VISIBILITY", filteredVis).values());
            filtering.end();
            return true;
        }
    }

    // Each pass's values, read from what this firing set: written into a kernel through its dispatch's values()

    private void tileValues(CgMaterialValues b) {
        CgLightGrid g = grid;
        b.vec4("_Screen", g.targetW, g.targetH, 0f, 0f)
                .sampler("_Ranges", 0, tileRanges)
                .vec4("_Rays", g.rays[0], g.rays[1], g.rays[2], g.rays[3])
                .vec4("_ToView0", g.toView.m00(), g.toView.m10(), g.toView.m20(), g.toView.m30())
                .vec4("_ToView1", g.toView.m01(), g.toView.m11(), g.toView.m21(), g.toView.m31())
                .vec4("_ToView2", g.toView.m02(), g.toView.m12(), g.toView.m22(), g.toView.m32())
                .vec4("_Depth", g.perspective.m22(), g.perspective.m32(), g.perspective.m23(), g.perspective.m33())
                .vec4("_EyeFrac", g.eyeFrac[0], g.eyeFrac[1], g.eyeFrac[2], 0f)
                .vec4("_Reach", CgLightSettings.ENTITY_TILES.get() ? list.rayReach : 1.0e9f, sun.on ? 1f : 0f, CgSunShadows.RAY, 0f)
                .set1i("_Entities", entities);
    }

    private void sampleValues(CgMaterialValues b) {
        CgLightGrid g = grid;
        boolean rows = casters.count > 0 || sun.mapped;
        grid.values(b.vec4("_Screen", g.targetW, g.targetH, downX, downY)
                .vec4("_Trace", CgLightSettings.SCREEN_TRACE.get(), CgLightSettings.SCREEN_STEPS.get(), 0.02f, 0.005f)
                .set1i("_EyeX", g.eyeBlock[0]).set1i("_EyeY", g.eyeBlock[1]).set1i("_EyeZ", g.eyeBlock[2])
                .vec4("_EyeFrac", g.eyeFrac[0], g.eyeFrac[1], g.eyeFrac[2], 0f)
                .set1i("_Entities", entities).set1i("_Casters", casters.count)
                .vec4("_Sun", sun.on ? 1f : 0f, sun.mapped ? 1f : 0f, 0f, 0f)
                .set1i("_Skip", skip())
                .vec4("_SampleGrid", sampleX, sampleY, sampleX * sampleY, 0f)
                .sampler("_CasterRows", 4, rows ? casters.texture : CgFallbackTextures.BLACK_1x1)
                .sampler("_MeshFields", 5, casters.count > 0 ? casters.fields.atlas() : CgFallbackTextures.BLACK_1x1)
                .sampler("_GBuffer", 6, gbuffer.texture)
                .sampler("_SunMap", 7, sun.mapped ? sun.map : CgFallbackTextures.WHITE_1x1)
                .sampler("_EntityTiles", 8, entities > 0 ? entityTiles : CgFallbackTextures.BLACK_1x1));
    }

    private void shadeValues(CgMaterialValues b) {
        grid.values(b.vec4("_Screen", grid.targetW, grid.targetH, downX, downY)
                .sampler("_Samples", 0, samples).set1f("_MaxWeight", 20f)
                .vec4("_SampleGrid", sampleX, sampleY, sampleX * sampleY, 0f)
                .sampler("_GBuffer", 1, gbuffer.texture).sampler("_Lightmap", 2, lightmap).set1i("_Scale", lightScale));
    }

    private void temporalValues(CgMaterialValues b) {
        float frames = CgLightSettings.MAX_FRAMES.get();
        grid.values(b.vec4("_Eye", grid.eye.x, grid.eye.y, grid.eye.z, 0f)
                .sampler("_Now", 0, shaded).sampler("_Last", 1, freshHistory ? shaded : accumulated[0].previous())
                .sampler("_LastMoments", 2, freshHistory ? shaded : accumulated[1].previous())
                .sampler("_NowVis", 3, shadedVis)
                .sampler("_LastVis", 4, freshHistory ? shadedVis : accumulated[2].previous())
                .set1f("_Fresh", freshHistory ? 1f : 0f)
                .vec4("_Frames", Math.min(4f, frames), frames, 1f, 0.03f).set1i("_Scale", lightScale));
    }

    private void denoiseValues(CgMaterialValues b) {
        grid.values(b.sampler("_Lighting", 0, accumulated[0])
                .sampler("_Moments", 1, accumulated[1]).sampler("_Vis", 2, accumulated[2])
                .sampler("_GBuffer", 3, gbuffer.texture)
                .vec4("_Spatial", CgLightSettings.SPATIAL_RADIUS.get() / lightScale, 4f, 10000f, 2f)
                .vec4("_Spread", 2f, 0.15f, CgLightSettings.MAX_FRAMES.get(), 0f).set1i("_Scale", lightScale));
    }
}
