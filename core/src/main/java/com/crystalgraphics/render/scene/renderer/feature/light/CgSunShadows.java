package com.crystalgraphics.render.scene.renderer.feature.light;

import com.crystalgraphics.api.material.CgMaterialInterface;
import com.crystalgraphics.api.material.CgMaterialValues;
import com.crystalgraphics.gpu.resource.CgFallbackTextures;
import com.crystalgraphics.gpu.resource.CgTextureDesc;
import com.crystalgraphics.material.CgMaterialProxy;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.render.draw.CgDrawCommands;
import com.crystalgraphics.render.draw.CgPassConstants;
import com.crystalgraphics.render.graph.CgDepthAccess;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgLoad;
import com.crystalgraphics.render.graph.CgComputePass;
import com.crystalgraphics.render.graph.CgDispatch;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.host.CgWorldAtmosphere;
import com.crystalgraphics.render.meshpass.engine.CgEnginePasses;
import com.crystalgraphics.render.scene.CgScene;
import com.crystalgraphics.render.scene.pass.CgCommandForm;
import com.crystalgraphics.render.scene.pass.CgViewPasses;
import com.crystalgraphics.render.view.CgView;
import com.crystalgraphics.render.view.CgViewState;
import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.compute.CgCompute;
import com.crystalgraphics.compute.CgKernel;
import com.crystalgraphics.render.stage.CgStageFiring;
import com.crystalgraphics.trace.CgGpuTrace;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.Arrays;

/**
 * The sun's cascaded shadow map, Unreal's for a movable directional light (DirectionalLightComponent.cpp:1043-1057): 4
 * cascades, each 3 times the depth of the last, overlapping by a tenth of each for the blend into the next. Each cascade
 * a shadow view of its own, Unreal's {@code FProjectedShadowInfo}: an orthographic light camera, the scene culled and
 * drawn through the shadow-depth mesh pass (every solid casting shadows, by its own vertex factory, so a skinned one
 * posed), resolved into one atlas of distances from the light. MegaLights' rays and a composing program read it.
 */
final class CgSunShadows {

    static final int CASCADES = 4;
    private static final float CASCADE_EXPONENT = 3f, CASCADE_TRANSITION = 0.1f, SUN_NEAR = 0.05f;
    /** Blocks the sun's ray through the blocks and entities runs: world/lights/sample.compute's sun_visibility. */
    static final float RAY = 96f;
    private static final String RESOLVE = "crystalgraphics:shaders/world/lights/sun_resolve.compute";
    private static final CgViewState.Key<Cascades> CASCADE_VIEWS = new CgViewState.Key<>("crystalgraphics:sun.cascades", Cascades::new);
    /** The rows whose draws the CPU makes: an instanced or GPU-fed row's need the main view's cull, half-size ones none. */
    private static final CgCommandForm CASTS = (rows, b, p) ->
            rows.instances(b) == null && rows.count(b) == null && rows.records(b) == null ? p : null;
    private static final int GPU_SUN = CgGpuTrace.name("world.sunShadow");
    private static final int ZONE_SUN = CgTrace.name("world.lights.sun");
    /** The cascades' atlas, a quadrant each: the casters' distance from the light in blocks, the resolve's. */
    private static final CgFormat MAP_FORMAT = CgFormat.R32_FLOAT, DEPTH_FORMAT = CgFormat.DEPTH32_FLOAT;

    private final CgLightGrid grid;
    private final CgShadowCasters casters;
    private CgKernel resolve;
    CgGraphTexture map;
    /**
     * Each cascade's rows, four texels after the casters' in the casters' texture: eye-relative world to its uv (two),
     * to its depth from the light, then its view depths and depth bias.
     */
    final float[] rows = new float[CASCADES * 16];
    private final Matrix4f ortho = new Matrix4f();
    private final float[] proj22 = new float[CASCADES], proj32 = new float[CASCADES], bias = new float[CASCADES],
            texels = new float[CASCADES];
    private final Matrix4f rotation = new Matrix4f(), viewToEye = new Matrix4f();
    private final Vector3f centre = new Vector3f();
    private final float[] direction = new float[3];
    /** Whether it runs this firing, and whether its map was drawn. */
    boolean on, mapped;
    private boolean handLive;
    private int handUnit;

    CgSunShadows(CgLightGrid grid, CgShadowCasters casters) {
        this.grid = grid;
        this.casters = casters;
    }

    /** Whether it runs this firing: something of ours drawn ({@code ours}), under a sun above the horizon. */
    boolean wanted(CgStageFiring stage, boolean ours) {
        mapped = false;
        on = false;
        if (!CgLightSettings.SHADOWS.get() || !CgLightSettings.SUN.get() || CgWorldAtmosphere.SUN_SHARE.get() <= 0f
                || !ours) return false;
        stage.host().environment().sunDirection(direction);
        return on = direction[1] > 0f;
    }

    /**
     * Its map, Unreal's for a directional light: each cascade a sphere round its slice of the view
     * (GetShadowSplitBoundsDepthRange, DirectionalLightComponent.cpp:818-925), grown into the next by the transition
     * (:947-969), its centre snapped to whole texels in absolute light space (ShadowSetup.cpp:1090-1100), the scene's
     * casters drawn under its orthographic light view, then resolved into the atlas. Whether drawn: not while the resolve
     * is unbuilt.
     */
    boolean record(CgStageFiring stage, CgRecording recording) {
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.WORLD, ZONE_SUN)) {
            if (resolve == null) resolve = CgCompute.load(RESOLVE).kernel("Resolve");
            if (!resolve.isReady()) return false;
            int res = CgLightSettings.SUN_RESOLUTION.get();
            casters.texture();
            if (map == null || map.getWidth() != 2 * res) {
                map = CgGraphTexture.transientTexture("cg_world_lights.sun", CgTextureDesc.of(2 * res, 2 * res, MAP_FORMAT));
            }
            CgView view = stage.view();
            CgScene scene = view.scene();
            Cascades cascades = view.state().get(CASCADE_VIEWS).of(scene, res);
            float length = (float) Math.sqrt(direction[0] * direction[0] + direction[1] * direction[1]
                    + direction[2] * direction[2]);
            float sx = direction[0] / length, sy = direction[1] / length, sz = direction[2] / length;
            // Any up not along the sun: Minecraft's sun crosses the x-y plane, so z.
            boolean alongZ = Math.abs(sz) > 0.9f;
            rotation.setLookAlong(-sx, -sy, -sz, alongZ ? 1f : 0f, 0f, alongZ ? 0f : 1f);
            grid.toView.invertAffine(viewToEye);
            float distance = CgLightSettings.SUN_DISTANCE.get(), back = CgLightSettings.SUN_TRACE.get();
            float[] rays = grid.rays;
            float tanX = (rays[2] - rays[0]) * 0.5f, tanY = (rays[3] - rays[1]) * 0.5f;
            int count = CgLightSettings.SUN_CASCADES.get();
            for (int k = 0; k < CASCADES; k++) {
                if (k >= count) {
                    // An empty range: no receiver's depth falls in it.
                    Arrays.fill(rows, k * 16, k * 16 + 16, 0f);
                    continue;
                }
                float splitNear = SUN_NEAR + accumulatedScale(k, count) * (distance - SUN_NEAR);
                float splitFar = SUN_NEAR + accumulatedScale(k + 1, count) * (distance - SUN_NEAR);
                float fade = (splitFar - splitNear) * CASCADE_TRANSITION, fadeStart, fadeEnd;
                if (k < count - 1) {
                    fadeStart = splitFar;
                    splitFar += fade;
                    fadeEnd = splitFar;
                } else {
                    fadeStart = splitFar - fade;
                    fadeEnd = splitFar;
                }
                float span = splitFar - splitNear;
                float diagonalFar = (tanX * splitFar) * (tanX * splitFar) + (tanY * splitFar) * (tanY * splitFar);
                float diagonalNear = (tanX * splitNear) * (tanX * splitNear) + (tanY * splitNear) * (tanY * splitNear);
                float centreZ = splitFar - ((diagonalNear - diagonalFar) / (2f * span) + span * 0.5f);
                centreZ = Math.max(splitNear, Math.min(splitFar, centreZ));
                float radius = 0f;
                for (int c = 0; c < 8; c++) {
                    float z = (c & 1) == 0 ? splitNear : splitFar;
                    float x = ((c & 2) == 0 ? rays[0] : rays[2]) * z, y = ((c & 4) == 0 ? rays[1] : rays[3]) * z;
                    radius = Math.max(radius, x * x + y * y + (z - centreZ) * (z - centreZ));
                }
                radius = Math.max((float) Math.sqrt(radius), 1f);
                viewToEye.transformPosition(centre.set(0f, 0f, -centreZ));
                // Snapped in absolute light space: a sub-texel move of the eye leaves the map's texels where they were.
                double ax = grid.cameraX + centre.x, ay = grid.cameraY + centre.y, az = grid.cameraZ + centre.z;
                Matrix4f r = rotation;
                double lx = r.m00() * ax + r.m10() * ay + r.m20() * az, ly = r.m01() * ax + r.m11() * ay + r.m21() * az;
                double texel = 2.0 * radius / res;
                float dlx = (float) (Math.floor(lx / texel) * texel - lx), dly = (float) (Math.floor(ly / texel) * texel - ly);
                centre.add(r.m00() * dlx + r.m01() * dly, r.m10() * dlx + r.m11() * dly, r.m20() * dlx + r.m21() * dly);
                // The light's eye: past the cascade toward the sun by the casters' reach.
                float lift = radius + back, far = 2f * radius + back;
                float ex = centre.x + sx * lift, ey = centre.y + sy * lift, ez = centre.z + sz * lift;
                // The receiver's rows: eye-relative world to the map's uv, then to depth from the light.
                float inv = 0.5f / radius;
                int o = k * 16;
                rows[o] = r.m00() * inv; rows[o + 1] = r.m10() * inv; rows[o + 2] = r.m20() * inv;
                rows[o + 3] = 0.5f - (r.m00() * ex + r.m10() * ey + r.m20() * ez) * inv;
                rows[o + 4] = r.m01() * inv; rows[o + 5] = r.m11() * inv; rows[o + 6] = r.m21() * inv;
                rows[o + 7] = 0.5f - (r.m01() * ex + r.m11() * ey + r.m21() * ez) * inv;
                rows[o + 8] = -r.m02(); rows[o + 9] = -r.m12(); rows[o + 10] = -r.m22();
                rows[o + 11] = r.m02() * ex + r.m12() * ey + r.m22() * ez;
                // Unreal's r.Shadow.CSMDepthBias of 10 times the cascade's world texel scale, its radius over its
                // resolution (ShadowRendering.cpp:1985-1995): the casters' bias, and the receivers' soft transition.
                rows[o + 12] = splitNear; rows[o + 13] = fadeStart; rows[o + 14] = fadeEnd; rows[o + 15] = 10f * radius / res;
                // Its shadow view: the light's eye, an orthographic box over the cascade's sphere, culled and drawn as any
                // view (Unreal's FProjectedShadowInfo, its CSMShadowDepth draws)
                CgCamera.orthographicMatrix(ortho, -radius, radius, -radius, radius, 0f, far);
                CgCamera camera = cascades.cameras[k].set(grid.cameraX + ex, grid.cameraY + ey, grid.cameraZ + ez, r, ortho);
                proj22[k] = ortho.m22();
                proj32[k] = ortho.m32();
                bias[k] = rows[o + 15];
                texels[k] = 2f * radius / res;
                CgDrawCommands draws = CgViewPasses.of(cascades.views[k], scene, camera)
                        .commands(CgEnginePasses.SHADOW_DEPTH, recording, CASTS);
                CgPassConstants constants = stage.defaults(cascades.constants[k]).seenThrough(camera, res, res)
                        .scene(scene.gpu());
                recording.raster("world.sunCascade").depth(cascades.depth[k], CgLoad.CLEAR_DEPTH, CgDepthAccess.WRITE)
                        .constants(constants).timed(GPU_SUN).add(draws).end();
            }
            CgComputePass pass = recording.compute("world.sunResolve", stage.constants()).timed(GPU_SUN);
            CgDispatch d = pass.dispatch(resolve, 2 * res, 2 * res, 1).image("MAP", map);
            for (int k = 0; k < CASCADES; k++) {
                d.texture("_Depth" + k, cascades.depth[k < count ? k : 0].sampledDepth());
            }
            d.set("_Size", res, count, 0f, 0f).set("_Proj22", proj22[0], proj22[1], proj22[2], proj22[3])
                    .set("_Proj32", proj32[0], proj32[1], proj32[2], proj32[3]).set("_Bias", bias[0], bias[1], bias[2], bias[3])
                    .set("_Texel", texels[0], texels[1], texels[2], texels[3]);
            pass.end();
            casters.uploadSun(recording, rows);
            return mapped = true;
        }
    }

    /** Its resolve, for the precache. */
    static void precache() {
        CgCompute.load(RESOLVE).kernel("Resolve").precache();
    }

    /** Unreal's ComputeAccumulatedScale: where split {@code index} of {@code count} falls, 0 to 1. */
    private static float accumulatedScale(int index, int count) {
        float scale = 1f, total = 0f, at = 0f;
        for (int i = 0; i < count; i++) {
            if (i < index) at += scale;
            total += scale;
            scale *= CASCADE_EXPONENT;
        }
        return at / total;
    }

    /**
     * Binds its map to {@code material}, a program composing its own ({@code env/handed/sun.glsl}): this firing's after
     * the apply in a transparent firing, none in an opaque one, whose pixels the deferred pass shadows.
     */
    void hand(CgMaterialInterface material, boolean transparent) {
        handLive = transparent && on && mapped;
        CgMaterialProxy proxy = CgMaterialProxy.of(material);
        handUnit = proxy.samplerUnit("_cg_SunMap");
        handValues(proxy.writer());
    }

    private void handValues(CgMaterialValues b) {
        b.sampler("_cg_SunMap", handUnit, handLive ? map : CgFallbackTextures.WHITE_1x1)
                .array("_cg_SunRows", rows).vec4("_cg_Sun", handLive ? 1f : 0f, 0f, 0f, 0f)
                .vec4("_cg_SunEye", grid.eye.x, grid.eye.y, grid.eye.z, 0f);
    }

    /** A view's cascades: a shadow view, its light camera, its constants and its depth target each. */
    private static final class Cascades {
        final CgView[] views = new CgView[CASCADES];
        final CgCamera[] cameras = new CgCamera[CASCADES];
        final CgPassConstants[] constants = new CgPassConstants[CASCADES];
        final CgGraphTexture[] depth = new CgGraphTexture[CASCADES];
        private CgScene scene;
        private int res;

        /** Made for {@code of}'s scene at {@code size} texels a side, remade when either changes. */
        Cascades of(CgScene of, int size) {
            if (of != scene) {
                scene = of;
                for (int k = 0; k < CASCADES; k++) {
                    views[k] = CgView.of(of);
                    cameras[k] = CgCamera.orthographic(-1f, 1f, -1f, 1f, 0f, 1f);
                    constants[k] = new CgPassConstants();
                }
            }
            if (size != res) {
                res = size;
                for (int k = 0; k < CASCADES; k++) {
                    depth[k] = CgGraphTexture.transientTexture("cg_world_lights.sun_cascade" + k, CgTextureDesc.of(size, size, DEPTH_FORMAT));
                }
            }
            return this;
        }
    }
}
