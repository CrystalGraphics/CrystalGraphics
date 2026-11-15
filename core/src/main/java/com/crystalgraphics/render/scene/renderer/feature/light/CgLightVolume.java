package com.crystalgraphics.render.scene.renderer.feature.light;

import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.compute.CgCompute;
import com.crystalgraphics.compute.CgKernel;
import com.crystalgraphics.gpu.resource.CgFallbackTextures;
import com.crystalgraphics.gpu.resource.CgTextureDesc;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.render.graph.CgComputePass;
import com.crystalgraphics.render.graph.CgDispatch;
import com.crystalgraphics.render.graph.CgGraphBuffer;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.scene.renderer.feature.field.CgEntityBoxes;
import com.crystalgraphics.render.scene.renderer.feature.field.CgVoxelWindow;
import com.crystalgraphics.render.scene.renderer.feature.media.CgSceneMedia;
import com.crystalgraphics.render.stage.CgStageFiring;
import com.crystalgraphics.render.view.CgViewState;
import com.crystalgraphics.trace.CgGpuTrace;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;
import org.joml.Matrix4fc;

import javax.annotation.Nullable;

/**
 * The lights in the volume round the eye, through world/lights/volume.compute: Unreal's translucency lighting volume
 * (TranslucentLighting.cpp), two cascades every lit transparent pass reads ({@code cg_LocalLights}), and the lit air,
 * each froxel of the media lit by its grid cell's lights (Unreal's LightScatteringCS).
 */
final class CgLightVolume {

    static final String KERNELS = "crystalgraphics:shaders/world/lights/volume.compute";
    private static final int GPU_VOLUME = CgGpuTrace.name("world.lightsVolume");
    private static final int ZONE_VOLUME = CgTrace.name("world.lights.volume");
    private static final CgFormat FORMAT = CgFormat.RGBA16_FLOAT;
    /** Unreal's ExponentialHeightFog VolumetricFogScatteringDistribution: a little forward scattering. */
    private static final float AIR_PHASE = 0.2f;

    /** Each view's lit air, this frame's and the last's, swapped each frame: each froxel's light scattered toward the eye. */
    static final class Air {
        final CgGraphTexture[] lit = new CgGraphTexture[2];
        int newest;
        /** The host frame that last lit it: the history holds that frame's alone. */
        long frame = Long.MIN_VALUE;
    }

    private static final CgViewState.Key<Air> AIR_KEPT = new CgViewState.Key<>("world.lights.air", Air::new);

    private final CgLightGrid grid;
    private final CgLightList list;
    private final CgShadowCasters casters;
    private final CgMegaLights shadows;
    private CgKernel fill, scatter;
    /** The volume, and the one twice as deep holding its direction too. */
    private CgGraphTexture volume, directional;
    private int cells;
    /** Each cascade's lowest corner from the eye and cell size. */
    private final float[] cascades = new float[8];
    /** This firing's volume, or null. */
    @Nullable
    CgGraphTexture localLights;

    CgLightVolume(CgLightGrid grid, CgLightList list, CgShadowCasters casters, CgMegaLights shadows) {
        this.grid = grid;
        this.list = list;
        this.casters = casters;
        this.shadows = shadows;
    }

    /** Both kernels, made and started once: the air's lit at the first light while anything is in it. */
    void kernels() {
        if (fill != null) return;
        fill = CgCompute.load(KERNELS).kernel("Fill");
        fill.precache();
        scatter = CgCompute.load(KERNELS).kernel("Scatter");
        scatter.precache();
    }

    static void precache() {
        CgCompute.load(KERNELS).kernel("Fill").precache();
        CgCompute.load(KERNELS).kernel("Scatter").precache();
    }

    boolean ready() {
        return fill != null && fill.isReady();
    }

    /**
     * This firing's volume, after the grid and the shadows' inputs are this firing's: each cascade the sphere round its
     * slice of a fixed 45-degree, square view (r.TranslucencyLightingVolume.AdaptToPerspective 0), its centre weighted
     * to the far end and snapped to whole cells in absolute space. With {@code withDirection}, each cell's direction
     * too, behind them.
     */
    void record(CgStageFiring stage, CgRecording recording, boolean withDirection) {
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.WORLD, ZONE_VOLUME)) {
            kernels();
            int n = CgLightSettings.VOLUME_CELLS.get();
            if (volume == null || cells != n) {
                volume = CgGraphTexture.transientTexture("cg_world_lights.volume", CgTextureDesc.volume(n + 1, n, 2 * n, FORMAT));
                directional = CgGraphTexture.transientTexture("cg_world_lights.volumeDirectional",
                        CgTextureDesc.volume(n + 1, n, 4 * n, FORMAT));
                cells = n;
            }
            CgGraphTexture into = withDirection ? directional : volume;
            int depth = (withDirection ? 4 : 2) * n;
            // The view's forward in eye-relative world: the view's third row turned round.
            Matrix4fc toView = grid.toView;
            float fx = -toView.m02(), fy = -toView.m12(), fz = -toView.m22();
            float norm = (float) Math.sqrt(fx * fx + fy * fy + fz * fz);
            fx /= norm;
            fy /= norm;
            fz /= norm;
            float inner = CgLightSettings.VOLUME_INNER.get(), outer = Math.max(CgLightSettings.VOLUME_OUTER.get(), inner + 1f);
            for (int c = 0; c < 2; c++) {
                float start = c == 0 ? 0f : inner, end = c == 0 ? inner : outer;
                // A 45-degree square view's corners are their distance out each way; the far four weigh 1 / 4.4 each and the
                // near 1 / 44 (FarVertexWeightScale 10).
                float along = start / 11f + end / 1.1f;
                float radius = (float) Math.max(Math.sqrt(2.0 * end * end + (end - along) * (end - along)),
                        Math.sqrt(2.0 * start * start + (along - start) * (along - start)));
                double cell = 2.0 * radius / n;
                cascades[c * 4] = (float) (Math.floor((grid.cameraX + fx * along) / cell) * cell - grid.cameraX) - radius;
                cascades[c * 4 + 1] = (float) (Math.floor((grid.cameraY + fy * along) / cell) * cell - grid.cameraY) - radius;
                cascades[c * 4 + 2] = (float) (Math.floor((grid.cameraZ + fz * along) / cell) * cell - grid.cameraZ) - radius;
                cascades[c * 4 + 3] = (float) cell;
            }
            CgTexture lightmap = stage.host().textures().lightmapTexture();
            CgComputePass pass = recording.compute("world.lightsVolume", stage.constants()).timed(GPU_VOLUME);
            traceInputs(pass.dispatch(fill, n + 1, n, depth).image("LOCAL_LIGHTS", into)
                    .set("_VolumeShadowRays", CgLightSettings.VOLUME_RAYS.get()).set("_Cells", n)
                    .set("_Cascade0", cascades[0], cascades[1], cascades[2], cascades[3])
                    .set("_Cascade1", cascades[4], cascades[5], cascades[6], cascades[7])
                    .set("_EyeOffset", grid.eye.x, grid.eye.y, grid.eye.z, 0f),
                    lightmap != null ? lightmap : CgFallbackTextures.WHITE_1x1, list.zeroes(recording));
            pass.end();
            localLights = into;
        }
    }

    /**
     * The air lit, after the media's injectors and before their integration: each froxel holding extinction lit by its
     * cell's lights, into a froxel volume of the light scattered toward the eye. Null with no light binned this firing.
     */
    @Nullable
    CgGraphTexture scatter(CgRecording recording, CgComputePass pass, CgSceneMedia media, CgViewState state,
                           CgGraphTexture extinction) {
        if (!grid.binned || !CgLightSettings.AIR.get()) return null;
        if (scatter == null) {
            scatter = CgCompute.load(KERNELS).kernel("Scatter");
            scatter.precache();
        }
        Air air = state.get(AIR_KEPT);
        if (air.lit[0] == null || air.lit[0].getWidth() != media.width() || air.lit[0].getHeight() != media.height()) {
            for (int k = 0; k < 2; k++) {
                if (air.lit[k] != null) recording.release(air.lit[k]);
                air.lit[k] = CgGraphTexture.requested("cg_world_lights.air" + k,
                        CgTextureDesc.volume(media.width(), media.height(), CgSceneMedia.SLICES, FORMAT));
            }
            air.frame = Long.MIN_VALUE;
        }
        // Unreal's VolumetricFogTemporalRandom (VolumetricFog.cpp:270-278): Halton 2, 3 and 5 of the frame, or the middle.
        boolean temporal = CgLightSettings.AIR_HISTORY.get();
        Matrix4fc reproject = media.reproject();
        boolean kept = temporal && reproject != null && state.continuous() && air.frame == state.frame() - 1;
        CgGraphTexture into = air.lit[1 - air.newest], last = air.lit[air.newest];
        CgDispatch d = media.rays(pass.dispatch(scatter, media.width(), media.height(), CgSceneMedia.SLICES))
                .image("MEDIA_SCATTER", into).texture("_Media", extinction).set("_PhaseG", AIR_PHASE)
                .set("_AirShadowRays", CgLightSettings.AIR_RAYS.get()).set("_Frame", state.frameIndex() & 1023)
                .set("_Jittered", temporal ? 1 : 0)
                .set("_HistoryWeight", kept ? CgLightSettings.AIR_HISTORY_WEIGHT.get() : 0f)
                .set("_Samples", CgLightSettings.AIR_SAMPLES.get())
                .texture("_History", kept ? last : CgFallbackTextures.BLACK_VOLUME);
        if (kept) {
            d.set("_Reproject0", reproject.m00(), reproject.m10(), reproject.m20(), reproject.m30())
                    .set("_Reproject1", reproject.m01(), reproject.m11(), reproject.m21(), reproject.m31())
                    .set("_Reproject2", reproject.m02(), reproject.m12(), reproject.m22(), reproject.m32())
                    .set("_Reproject3", reproject.m03(), reproject.m13(), reproject.m23(), reproject.m33());
        }
        traceInputs(d, CgFallbackTextures.WHITE_1x1, list.zeroes(recording));
        air.newest = 1 - air.newest;
        air.frame = state.frame();
        return into;
    }

    /** What both kernels read: the lights, their grid, and the shadows' inputs this firing. */
    private void traceInputs(CgDispatch d, CgTexture lightmap, CgGraphBuffer none) {
        CgGraphBuffer boxes = CgEntityBoxes.buffer();
        boolean traced = shadows.on, meshes = traced && casters.count > 0;
        CgLightGrid g = grid;
        g.values(d.bind("LIGHTS", list.list).bind("CELLS", g.cells).bind("INDICES", g.indices)
                .bind("ENTITIES", traced && boxes != null ? boxes : none))
                .set("_Shadows", traced ? 1f : 0f)
                .texture("_Lightmap", lightmap)
                .set("_Skip", CgMegaLights.skip()).set("_EyeX", g.eyeBlock[0]).set("_EyeY", g.eyeBlock[1]).set("_EyeZ", g.eyeBlock[2])
                .set("_EyeFrac", g.eyeFrac[0], g.eyeFrac[1], g.eyeFrac[2], 0f)
                .set("_Entities", traced && boxes != null ? shadows.entities : 0).set("_Casters", meshes ? casters.count : 0)
                .texture("_CasterRows", meshes ? casters.texture : CgFallbackTextures.BLACK_1x1)
                .texture("_MeshFields", meshes ? casters.fields.atlas() : CgFallbackTextures.BLACK_1x1);
        CgVoxelWindow.get().bind(d);
    }
}
