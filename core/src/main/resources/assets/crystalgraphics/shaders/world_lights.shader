// The world's lights (CgWorldLights) lighting the scene behind each pixel, as Unreal lights a surface with a simple light
// (DeferredLightingCommon.ush's GetSimpleDynamicLighting, its diffuse half): colour * attenuation * N.L * albedo / pi.
// Minecraft keeps neither albedo nor normals, so both are rebuilt. The surface and its normal come from depth, the
// normal from whichever neighbour each way is the nearer step, so a block's edge does not bend it (Wicked Engine's
// improved normal reconstruction, MIT), held to a world axis near one, as Minecraft's blocks are. The albedo is the scene's colour over the lightmap Minecraft lit it by, read
// at the light's own block (CgWorldLights' ambient): what the light adds is scene * light / lightmap, which the blend
// does. Minecraft has no exposure, so its noon is as bright as its moonlight is dim; a light is adapted to the sun as
// an eye would be, for the lights alone: full at night and wherever no sky reaches it, fading to _DayFade under the
// full daylight of an open sky (CG_DAYLIGHT times the sky light at it). Specular is left out: Minecraft has no
// roughness. A light that casts shadows is not lit here while the shadow passes run (_Shadows): their denoised lighting,
// in the same units, is added in its place (_Shadowed, CgWorldLights' MegaLights port).
//
// Where the sun is withheld (_Visibility), the pixel loses the sun's direct share of what its sky light gives it, in
// brightness: the lightmap with and without its sky light, read from the voxel window at the block the pixel's face
// looks into, as Unreal's shadow takes a directional light's part and leaves the sky light. Minecraft's curve is steep,
// so a share of the light level would leave far less than that share of the light. Where Minecraft's light-giving
// blocks are withheld (_Visibility's y), its block light level is cut by the share withheld, as its flood would be. The pass reads the scene and writes the whole pixel, so it can darken as well as add.
#type none
#include "crystalgraphics:shaders/lib/math.glsl"
#include "crystalgraphics:shaders/lib/color.glsl"
#include "crystalgraphics:shaders/lib/lights.glsl"
#include "crystalgraphics:shaders/lib/light_shadow.glsl"

Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" "Fog" = "Off" "ColorSpace" = "Linear" "Media" = "Off" "SceneColorMargin" = "0.01" }
Queue = "Transparent"

Properties {
    _Grid    ("Cells across, up and deep",                         vec4) = (1, 1, 32, 0)
    _Tile    ("A tile's share of the target across and up",        vec4) = (1, 1, 0, 0)
    _ZParams ("The slices' B, O and S",                            vec4) = (1, 1, 1, 0)
    _Rays    ("Unit-depth view rays at the target's edges: left, bottom, right, top", vec4) = (-1, -1, 1, 1)
    _ToView0 ("Camera-relative world to the bobbed view, the first row, its shift in w", vec4) = (1, 0, 0, 0)
    _ToView1 ("The second row",                                    vec4) = (0, 1, 0, 0)
    _ToView2 ("The third row",                                     vec4) = (0, 0, 1, 0)
    _Depth   ("The perspective's m22, m32, m23 and m33: window depth to eye depth", vec4) = (1, 0, -1, 0)
    _DayFade ("A light's strength under a full day's open sky; 1 for never fading", float) = 0.1
    _Shadows ("1 while the shadow passes light the lights that cast shadows", float) = 0.0
    _Shadowed ("Their lighting, denoised",                         sampler2D) = "black"
    _Visibility ("The sun's and the block light's visibility, denoised", sampler2D) = "white"
    _World         ("The voxel window's blocks",                   sampler3D) = "black"
    _WorldSections ("Which of its sections are filled",            sampler3D) = "black"
    _WorldDistance ("Its distance to solid over FX_WORLD_FAR",      sampler3D) = "white"
    _GBuffer       ("Each pixel's normal in the view (world_lights_gbuffer)", sampler2D) = "black"
    _WorldBaseX    ("Its lowest block, absolute: x",               int) = 0
    _WorldBaseY    ("y",                                           int) = 0
    _WorldBaseZ    ("z",                                           int) = 0
    _WorldWrapX    ("That block's texel: x",                       int) = 0
    _WorldWrapY    ("y",                                           int) = 0
    _WorldWrapZ    ("z",                                           int) = 0
    _WorldLive     ("1 with a level, else 0: read nothing",        int) = 0
    _Sun     ("1 while the sun's visibility is traced, and its direct share of the sky light", vec4) = (0, 0.6, 0, 0)
    _EyeX    ("The eye's block: x",                                int) = 0
    _EyeY    ("y",                                                 int) = 0
    _EyeZ    ("z",                                                 int) = 0
    _EyeFrac ("The eye within its block",                          vec4) = (0, 0, 0, 0)
}

// light_grid.compute's: each light in the view with what it is shaded by, worked out once a light.
struct CgViewLight { vec4 positionRadius; vec4 spot; vec4 kind; vec4 segment; vec4 radiance; };

Buffers {
    CELLS   ("Each cell's light count and start", uint, readonly)
    INDICES ("The cells' light indices",       uint,    readonly)
    VIEWED  ("Each light in the grid's view, as the grid binned it", CgViewLight, readonly)
}

struct v2f { vec2 uv; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        Blend OFF
        DepthTest ALWAYS
        DepthWrite OFF
        Cull OFF
    }

    // The surface at pixel px, in the grid's view: cg_LinearEyeDepth under the bob-free perspective
    // (CgWorldLights.splitPerspective), along the pixel's ray.
    vec3 to_world_dir(vec3 v) {
        return vec3(dot(vec3(_ToView0.x, _ToView1.x, _ToView2.x), v), dot(vec3(_ToView0.y, _ToView1.y, _ToView2.y), v),
                dot(vec3(_ToView0.z, _ToView1.z, _ToView2.z), v));
    }

    vec3 lightmap(vec2 light) {
        vec3 c = CG_LIGHTMAP(light);
        return CG_LINEAR_SCENE ? srgb_to_linear(c) : c;
    }

    vec3 surface(ivec2 px) {
        px = clamp(px, ivec2(0), ivec2(CG_RESOLUTION) - 1);
        float window = texelFetch(cg_DepthBuffer, px, 0).r, ndc = cg_DepthParams.y > 0.5 ? window : window * 2.0 - 1.0;
        float depth = _Depth.z == 0.0 ? (_Depth.y - ndc) / _Depth.x
                : (_Depth.y - _Depth.x / _Depth.z * _Depth.w) / (ndc - _Depth.x / _Depth.z);
        vec2 uv = (vec2(px) + 0.5) / CG_RESOLUTION;
        return vec3(mix(_Rays.xy, _Rays.zw, uv) * depth, -depth);
    }

    void vertex(out v2f o) {
        vec2 p = vec2(float((CG_VERTEX_ID & 1) << 2) - 1.0, float((CG_VERTEX_ID & 2) << 1) - 1.0);
        o.uv = p * 0.5 + 0.5;
        gl_Position = vec4(p, 0.0, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        ivec2 px = ivec2(gl_FragCoord.xy);
        vec3 p = surface(px);
        // Its normal, written once a firing for every lighting pass (world_lights_gbuffer).
        vec3 n = texelFetch(_GBuffer, px, 0).xyz;
        ivec3 cell = cg_light_cell(p, _ZParams.xyz, _Rays.xy, _Rays.zw, _Tile.xy, ivec2(_Grid.xy));
        int e = (cell.z * int(_Grid.y) + cell.y) * int(_Grid.x) + cell.x;
        int from = CG_LIGHT_CELL_START(e);
        vec3 sum = vec3(0.0);
        bool shadows = _Shadows > 0.5;
        // With the shadow passes running the lights they sample are theirs (_Shadowed); the rest, and without them
        // every lit light, are shaded here exactly: one run of the cell's list, each light read from the grid's view,
        // which holds everything it is shaded with. A cell lists many small lights that reach few of its pixels: each
        // is rejected by its reach first.
        ivec2 span = shadows ? CG_LIGHTS_EXACT(e) : CG_LIGHTS_LIT(e);
        for (int k = span.x; k < span.y; k++) {
            CgViewLight light = VIEWED(int(INDICES(from + k)));
            vec4 reach = light.positionRadius;
            if (dot(reach.xyz - p, reach.xyz - p) >= reach.w * reach.w) continue;
            float lit = cg_light_surface(reach.xyz, reach.w, light.kind.y, light.spot, light.segment, light.kind.z, p, n);
            if (lit > 0.0) sum += light.radiance.rgb * lit;
        }
        if (shadows) sum += texelFetch(_Shadowed, px, 0).rgb;

        // The sun withheld: the sky light at the block this face looks into, less the sun's direct share; the block
        // light less the share of it withheld.
        vec3 ratio = vec3(1.0);
        float window = texelFetch(cg_DepthBuffer, px, 0).r;
        bool sky = cg_DepthParams.x > 0.5 ? window <= 0.0 : window >= 1.0;
        vec2 seen = shadows ? texelFetch(_Visibility, px, 0).xy : vec2(1.0);
        float vis = _Sun.x > 0.5 ? seen.x : 1.0;
        if (!sky && min(vis, seen.y) < 0.999 && _WorldLive != 0) {
            vec3 w = to_world_dir(p + n * 0.5 - vec3(_ToView0.w, _ToView1.w, _ToView2.w)) + _EyeFrac.xyz;
            ivec3 block = ivec3(_EyeX, _EyeY, _EyeZ) + ivec3(floor(w));
            vec2 light = fx_world_light(_World, _WorldSections, ivec3(_WorldBaseX, _WorldBaseY, _WorldBaseZ),
                    ivec3(_WorldWrapX, _WorldWrapY, _WorldWrapZ), block, vec2(-1.0));
            if (light.x >= 0.0) {
                float block = light.x * seen.y, withheld = _Sun.y * clamp(CG_DAYLIGHT, 0.0, 1.0) * (1.0 - vis);
                vec3 lit = lightmap(vec2(block, light.y)), sky = lit - lightmap(vec2(block, 0.0));
                ratio = max(lit - sky * withheld, vec3(0.0)) / max(lightmap(light), vec3(1.0e-3));
            }
        }
        vec4 scene = texelFetch(cg_SceneColor, px, 0);
        fragColor = vec4(scene.rgb * (ratio + sum), scene.a);
    }
}
