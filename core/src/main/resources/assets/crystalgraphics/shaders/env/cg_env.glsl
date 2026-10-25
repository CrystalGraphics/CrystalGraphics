#pragma once
// Naming: cg_PascalCase = GLSL identifiers; CG_UPPER_SNAKE = macros

// Per-pass uniforms (view + proj + time + resolution + camera), one block per pass. Block index wired post-link
// via glUniformBlockBinding. FIELD ORDER MUST MATCH CgPassConstants.FORMAT -- std140 offsets are positional, so a
// field added to one and not the other silently reads its neighbour.
layout(std140) uniform CgFrameBlock {
    mat4 cg_ViewMatrix;
    mat4 cg_ProjMatrix;
    vec4 cg_Time;        // (t/20, t, t*2, t*3) - seconds, scaled like Unity _Time
    vec2 cg_Resolution;  // viewport size in pixels
    vec4 cg_CameraPos;   // world-space camera position in .xyz; .w unused, see CG_CAMERA_WORLD_POS
    vec4 cg_DepthParams; // x: 1 when depth is reversed (nearer is greater); y: 1 when clip depth runs 0..1;
                         // z: 1 when the target is the linear HDR scene (CG_LINEAR_SCENE); w: its glow gain
    vec4 cg_WorldOrigin; // where world space's origin is in absolute coordinates: the camera, in a world pass
    vec4 cg_SunDirection; // xyz toward the sun, or the moon while the sun is down; w the daylight, 0 to 1
    vec4 cg_FogColor;     // the host's fog colour; a 1 while there is fog, 0 for none
    vec4 cg_FogParams;    // x where the fog starts, y where it is whole, in blocks from the camera; z CG_SUN_SHARE
    mat4 cg_PrevViewProj; // this pass's camera-relative points to last frame's clip space: cg_PreviousClip
    vec4 cg_FrameParams;  // x the host frame's index, 0 to 65535; y 1 while last frame is one of this view
};

// -- Per-Instance Object Data (SSBO path: GL 4.3+/ARB) ----------------------
struct CgObjectData {
    mat4 modelMatrix;
    mat4 normalMatrix;
    vec4 custom0;
    vec4 custom1;
    vec4 custom2;
    vec4 custom3;
};

#ifdef CG_USE_SSBO
layout(std430) readonly buffer CgObjectDataBuffer {
    CgObjectData cg_Objects[];
};

// -- Per-Instance Object Data (TBO fallback path: material baseline GL 3.3) --
#else
uniform samplerBuffer CgObjectDataBuffer;

CgObjectData cg_FetchObjectData(int instanceId) {
    int base = instanceId * 12;
    CgObjectData data;
    data.modelMatrix = mat4(
        texelFetch(CgObjectDataBuffer, base),
        texelFetch(CgObjectDataBuffer, base + 1),
        texelFetch(CgObjectDataBuffer, base + 2),
        texelFetch(CgObjectDataBuffer, base + 3)
    );
    data.normalMatrix = mat4(
        texelFetch(CgObjectDataBuffer, base + 4),
        texelFetch(CgObjectDataBuffer, base + 5),
        texelFetch(CgObjectDataBuffer, base + 6),
        texelFetch(CgObjectDataBuffer, base + 7)
    );
    data.custom0 = texelFetch(CgObjectDataBuffer, base + 8);
    data.custom1 = texelFetch(CgObjectDataBuffer, base + 9);
    data.custom2 = texelFetch(CgObjectDataBuffer, base + 10);
    data.custom3 = texelFetch(CgObjectDataBuffer, base + 11);
    return data;
}
#endif

// -- Instance ID bridge -----------------------------------------------------
// The vertex stage adds cg_InstanceBase: where a batch's instances start in its kind's upload for the pass, 0 for
// a draw that uploaded its own. Below 0 it names the one record every instance reads, -1 - cg_InstanceBase: an
// indirect INSTANCES draw's. The fragment stage reads the result as a flat varying the compiler wires. A kernel has
// neither: its invocations are env/compute/kernel.glsl's.
#ifdef CG_VERTEX_STAGE
#ifdef CG_MULTI_DRAW
// A multi-draw's variant: no uniform changes between its commands, so each command's first instance is its batch's
// base and its base vertex the mesh's. Every command is drawn by indices, a mesh without them by 0, 1, 2 ...: GL gives
// an array draw's base vertex as 0. Core in 4.6, GL_ARB_shader_draw_parameters below, which the compiler enables.
// Vulkan's gl_InstanceIndex counts from the first instance; GL's gl_InstanceID does not.
#if __VERSION__ >= 460
#define CG_BASE_INSTANCE gl_BaseInstance
#define CG_BASE_VERTEX gl_BaseVertex
#else
#define CG_BASE_INSTANCE gl_BaseInstanceARB
#define CG_BASE_VERTEX gl_BaseVertexARB
#endif
#ifdef VULKAN
#define CG_DRAW_INSTANCE (gl_InstanceIndex - CG_BASE_INSTANCE)
#else
#define CG_DRAW_INSTANCE gl_InstanceID
#endif
#define CG_INSTANCE_ID (CG_DRAW_INSTANCE + CG_BASE_INSTANCE)
#define CG_VERTEX_ID (gl_VertexID - CG_BASE_VERTEX)
#else
uniform int cg_InstanceBase;
#define CG_INSTANCE_ID (cg_InstanceBase < 0 ? -1 - cg_InstanceBase : gl_InstanceID + cg_InstanceBase)
// The instance's index in its own draw: in an indirect INSTANCES draw, the element it draws.
#define CG_DRAW_INSTANCE gl_InstanceID
// The vertex's index in its own mesh: cg_VertexBase is where the mesh starts in the buffer it is drawn from.
uniform int cg_VertexBase;
#define CG_VERTEX_ID (gl_VertexID - cg_VertexBase)
#endif
// The corner of a CgMesh.quads(n) vertex: (0,0), (1,0), (1,1), (0,1) around each quad.
#define CG_VERTEX_CORNER vec2(float(((CG_VERTEX_ID + 1) >> 1) & 1), float((CG_VERTEX_ID >> 1) & 1))
#elif !defined(CG_COMPUTE_STAGE)
flat in int cg_InstanceId;
#define CG_INSTANCE_ID cg_InstanceId
#endif

// -- Object Data Definition-----------------------------------------------------
#ifdef CG_USE_SSBO
#define CG_OBJECT_DATA cg_Objects[CG_INSTANCE_ID]
#else
#define CG_OBJECT_DATA cg_FetchObjectData(CG_INSTANCE_ID)
#endif

// -- Data Macros ------------------------------------------------------
#define CG_OBJECT_TO_WORLD (CG_OBJECT_DATA.modelMatrix)
#define CG_NORMAL_MATRIX mat3(CG_OBJECT_DATA.normalMatrix)
#define CG_OBJECT_CUSTOM0 (CG_OBJECT_DATA.custom0)
#define CG_OBJECT_CUSTOM1 (CG_OBJECT_DATA.custom1)
#define CG_OBJECT_CUSTOM2 (CG_OBJECT_DATA.custom2)
#define CG_OBJECT_CUSTOM3 (CG_OBJECT_DATA.custom3)
// The world's light at the draw: block and sky light, 0 to 15 each. Rides in the normal matrix's unused column.
#define CG_OBJECT_LIGHT (CG_OBJECT_DATA.normalMatrix[3].xy)
// The draw's emission scale (CgWorldRenderer's .emission), stored less 1 in the same column so a writer leaving the
// slot 0 means 1. Read through CG_EMISSION.
#define CG_OBJECT_EMISSION (1.0 + CG_OBJECT_DATA.normalMatrix[3].z)
// Four lanes the engine never reads, the normal matrix's w row, for a record's writer to hand its material:
// (0, 0, 0, 1) from CgWorldRenderer. A VFX mesh particle's is what it rests against (CgVfxRange).
#define CG_OBJECT_SPARE vec4(CG_OBJECT_DATA.normalMatrix[0].w, CG_OBJECT_DATA.normalMatrix[1].w, CG_OBJECT_DATA.normalMatrix[2].w, CG_OBJECT_DATA.normalMatrix[3].w)

// -- Scene samplers (auto-bound by the engine; do not redeclare or bind manually) -----------
// cg_DepthBuffer: scene depth snapshot, in the host's depth format, taken at the start of the world stage that
// reads it: in an opaque pass the host's world, in a transparent pass with the world renderer's opaque draws too.
// Bound to CgBindingPoints.DEPTH_TEXTURE_UNIT in both vertex and fragment stages of every pass.
// Do NOT use that texture unit in material Properties.
uniform sampler2D cg_DepthBuffer;

// cg_SceneColor: the scene's colour, taken the same way and at the same moments as cg_DepthBuffer, for a material that
// bends what is behind it (heat haze, a shockwave). RGBA8, linearly filtered. Bound to
// CgBindingPoints.SCENE_COLOR_TEXTURE_UNIT; do NOT use that unit in material Properties either.
uniform sampler2D cg_SceneColor;
#define CG_SCENE_COLOR(uv) texture(cg_SceneColor, (uv))

// cg_Lightmap: the host's lightmap, block light across and sky light down, in a world pass; 1x1 white in any other.
// Bound to CgBindingPoints.LIGHTMAP_TEXTURE_UNIT; do NOT use that unit in material Properties either.
uniform sampler2D cg_Lightmap;
// The colour the world lights a thing with at block and sky light (0 to 15 each), as Minecraft lights its blocks.
#define CG_LIGHTMAP(light) texture(cg_Lightmap, (clamp((light), 0.0, 15.0) + 0.5) / 16.0).rgb

// Raw depth is the host's convention -- Minecraft 26.2 is reversed-Z with a 0..1 clip range, earlier
// versions are not -- so compare depths as eye distances, never as raw values:
//
//     float scene = CG_SCENE_EYE_DEPTH(screenUv);
//     float self  = cg_LinearEyeDepth(gl_FragCoord.z);
//     float fade  = saturate((scene - self) / _FadeDistance);   // soft particles, water edges
//
// Exact for an orthographic projection, and for a perspective one in the pass's convention, reversed or not, times
// any affine transform of eye space: Minecraft multiplies its view bobbing and hurt shake into the projection, which
// scales [2][2] and tilts the w row. The perspective's own terms are recovered from both rows, since read off [2][2]
// alone a half-degree bob moved a point at render distance by half its distance or more.
float cg_LinearEyeDepth(float windowDepth) {
    float ndc = cg_DepthParams.y > 0.5 ? windowDepth : windowDepth * 2.0 - 1.0;
    if (cg_ProjMatrix[2][3] == 0.0) return (cg_ProjMatrix[3][2] - ndc) / cg_ProjMatrix[2][2];
    float p22 = -cg_ProjMatrix[2][2] / cg_ProjMatrix[2][3];
    float p32 = cg_ProjMatrix[3][2] + p22 * cg_ProjMatrix[3][3];
    return p32 / (ndc + p22);
}
#define CG_SCENE_EYE_DEPTH(uv) cg_LinearEyeDepth(texture(cg_DepthBuffer, (uv)).r)

// Unreal's CustomStencil, CustomDepth (as an eye distance, as CG_SCENE_EYE_DEPTH) and the shading-model id at a screen
// position: no pass writes them yet, so a material reading one fails to link, naming the read, rather than drawing as if
// nothing were stencilled.
#define CG_CUSTOM_STENCIL(uv) cg_custom_stencil_has_no_pass_writing_it_yet(uv)
#define CG_CUSTOM_EYE_DEPTH(uv) cg_custom_depth_has_no_pass_writing_it_yet(uv)
#define CG_SHADING_MODEL_ID(uv) cg_shading_model_id_has_no_pass_writing_it_yet(uv)
// The rest of what Unreal's post materials sample, none written yet either: SceneTexture:WorldNormal, its ambient
// occlusion, PostProcessInput1 (separate translucency), PostProcessInput2, and the velocity (PostProcessInput4).
#define CG_SCENE_WORLD_NORMAL(uv) cg_scene_world_normal_has_no_pass_writing_it_yet(uv)
#define CG_SCENE_AMBIENT_OCCLUSION(uv) cg_scene_ambient_occlusion_has_no_pass_writing_it_yet(uv)
#define CG_SEPARATE_TRANSLUCENCY(uv) cg_separate_translucency_has_no_pass_writing_it_yet(uv)
#define CG_POST_PROCESS_INPUT_2(uv) cg_post_process_input_2_has_no_pass_writing_it_yet(uv)
#define CG_SCENE_VELOCITY(uv) cg_scene_velocity_has_no_pass_writing_it_yet(uv)
// The lights a draw is handed whole, for a program carrying its own lighting: the directional light's direction (toward
// it) and colour, the sky light's colour, and the four point lights nearest the draw, i 0 to 3 (camera-relative
// position, colour, the light's four attenuation parameters; intensity 0 where there is none). Either stage. The
// colours split the host's light at the draw's (CG_OBJECT_LIGHT): the sun takes CG_SUN_SHARE of what sky light adds by
// day, and the sky the rest, block light included. The point lights are env/handed_lights.glsl's, included for a
// program naming them; these stand where it is not, failing to link by name.
#define CG_DIRECTIONAL_LIGHT_DIRECTION CG_SUN_DIRECTION
#define CG_DIRECTIONAL_LIGHT_COLOR cg_DirectionalLightColor(CG_OBJECT_LIGHT)
#define CG_SKY_LIGHT_COLOR (CG_LIGHTMAP(CG_OBJECT_LIGHT) - cg_DirectionalLightColor(CG_OBJECT_LIGHT))
#define CG_DRAW_POINT_LIGHT_POSITION(i) cg_draw_point_light_has_no_pass_writing_it_yet(i)
#define CG_DRAW_POINT_LIGHT_COLOR(i) cg_draw_point_light_has_no_pass_writing_it_yet(i)
#define CG_DRAW_POINT_LIGHT_PARAMS(i) cg_draw_point_light_has_no_pass_writing_it_yet(i)
// The casting directional light's shadow for a program composing its own (Unreal's modulated shadow): its visibility at
// camera-relative p, 1 lit and faded out by distance; the colour a shadow tints toward; the program's wrap of N.L.
// Fragment stage. env/handed_sun.glsl's, included for a program naming them; these stand where it is not.
#define CG_DIRECTIONAL_SHADOW(p) cg_directional_shadow_has_no_pass_writing_it_yet(p)
#define CG_DIRECTIONAL_SHADOW_COLOR cg_directional_shadow_has_no_pass_writing_it_yet
#define CG_DIRECTIONAL_SHADOW_WRAP cg_directional_shadow_has_no_pass_writing_it_yet

// cg_Media: the world's froxel transmittance (CgWorldMedia), a volume over the screen, each texel to its slice's far edge
// the light the air scattered toward the eye (rgb) and what gets through (a); white in a pass that binds none. Bound to
// CgBindingPoints.MEDIA_TEXTURE_UNIT; do NOT use that unit in Properties.
uniform sampler3D cg_Media;
// Its slices in eye depth as Unreal's volumetric fog spaces them (CalculateGridZParams, r.VolumetricFog.
// DepthDistributionScale 32): slice s's near edge at (2^(s / SPREAD) - O) / B, from CG_MEDIA_NEAR (Unreal's 9.5 cm) to
// CG_MEDIA_FAR at the last. Twice Unreal's 64 slices, for four times its 60 m: each about as deep as Unreal's at 40 blocks.
#define CG_MEDIA_NEAR 0.095
#define CG_MEDIA_FAR 256.0
#define CG_MEDIA_SLICES 128.0
#define CG_MEDIA_SPREAD 32.0
#define CG_MEDIA_O ((CG_MEDIA_FAR - CG_MEDIA_NEAR * exp2((CG_MEDIA_SLICES - 1.0) / CG_MEDIA_SPREAD)) / (CG_MEDIA_FAR - CG_MEDIA_NEAR))
#define CG_MEDIA_B ((1.0 - CG_MEDIA_O) / CG_MEDIA_NEAR)

// The slice coordinate at eye depth `eye`: slice s spans s to s + 1.
float cg_MediaSlice(float eye) {
    return log2(max(eye, 0.0) * CG_MEDIA_B + CG_MEDIA_O) * CG_MEDIA_SPREAD;
}

// The eye depth at slice coordinate `s`.
float cg_MediaEye(float s) {
    return (exp2(s / CG_MEDIA_SPREAD) - CG_MEDIA_O) / CG_MEDIA_B;
}

// cg_Media at `p`, cubic B-spline across the screen and linear in depth: four trilinear reads (Sigg and Hadwiger, GPU
// Gems 2 ch. 20). A froxel is 16 pixels wide, and read linearly a light's edge shows the grid's cells.
vec4 cg_MediaSample(vec3 p) {
    vec2 size = vec2(textureSize(cg_Media, 0).xy);
    vec2 x = p.xy * size - 0.5, i = floor(x), f = x - i;
    vec2 w0 = (1.0 - f) * (1.0 - f) * (1.0 - f) / 6.0, w1 = (4.0 - 6.0 * f * f + 3.0 * f * f * f) / 6.0;
    vec2 w3 = f * f * f / 6.0, w2 = 1.0 - w0 - w1 - w3;
    vec2 g0 = w0 + w1, g1 = w2 + w3;
    vec2 h0 = (i - 0.5 + w1 / g0) / size, h1 = (i + 1.5 + w3 / g1) / size;
    return g0.y * (g0.x * textureLod(cg_Media, vec3(h0.x, h0.y, p.z), 0.0) + g1.x * textureLod(cg_Media, vec3(h1.x, h0.y, p.z), 0.0))
         + g1.y * (g0.x * textureLod(cg_Media, vec3(h0.x, h1.y, p.z), 0.0) + g1.x * textureLod(cg_Media, vec3(h1.x, h1.y, p.z), 0.0));
}

// What of the light from eye depth `eye` at screen `uv` reaches the eye through smoke and gas, 0 to 1.
float cg_MediaTransmit(vec2 uv, float eye) {
    float k = cg_MediaSlice(eye);
    float t = cg_MediaSample(vec3(uv, (k - 0.5) / CG_MEDIA_SLICES)).a;
    return mix(1.0, t, clamp(k, 0.0, 1.0));
}

// The light the smoke and gas between the eye and eye depth `eye` at screen `uv` scattered toward the eye: the world's
// lights in the air. Only where a pass binds the world's media: the white fallback reads 1.
vec3 cg_MediaLight(vec2 uv, float eye) {
    float k = cg_MediaSlice(eye);
    return cg_MediaSample(vec3(uv, (k - 0.5) / CG_MEDIA_SLICES)).rgb * clamp(k, 0.0, 1.0);
}
#if !defined(CG_COMPUTE_STAGE)
// cg_LocalLights: the world's lights on transparent surfaces (CgWorldLights' translucency lighting volume, Unreal's),
// N + 1 x N x 2N for N cells a side (the tier's, 64 at Ultra): two cascades of N cells round the view, cascade 1 behind
// cascade 0 in z, and a column at x N whose texel (N, 0, N * cascade) holds the cascade's lowest corner from the camera
// and its size. 4N deep in a firing drawing a
// "Surface"-lit material: both cascades' direction behind them (cg_LocalLightDirectional). Black, and 1 texel, in a
// pass that binds none. Bound to CgBindingPoints.LOCAL_LIGHTS_TEXTURE_UNIT; do NOT use that unit in Properties.
uniform sampler3D cg_LocalLights;

// Layer 0 and 1 the cascades' light, 2 and 3 their direction; n the cells a side.
vec3 cg_local_light_cascade(vec3 u, float layer, float n, float depth) {
    vec3 t = clamp(u * n, 0.5, n - 0.5);
    return textureLod(cg_LocalLights, vec3(t.x / (n + 1.0), t.y / n, (t.z + n * layer) / depth), 0.0).rgb;
}

// The cascades blended at camera-relative p, layer 0 the light and 2 its direction: the inner into the outer over its
// edge (TranslucencyVolumeCommon.ush's ComputeVolumeUVs, TransitionScale 6); past the outer, nothing.
vec3 cg_local_light_blend(vec3 p, float layer) {
    ivec3 size = textureSize(cg_LocalLights, 0);
    int cells = size.x - 1;
    if (cells < 2 || size.z < cells * (int(layer) + 2)) return vec3(0.0);
    vec4 c0 = texelFetch(cg_LocalLights, ivec3(cells, 0, 0), 0), c1 = texelFetch(cg_LocalLights, ivec3(cells, 0, cells), 0);
    vec3 u0 = (p - c0.xyz) / c0.w, u1 = (p - c1.xyz) / c1.w;
    if (any(lessThan(u1, vec3(0.0))) || any(greaterThan(u1, vec3(1.0)))) return vec3(0.0);
    vec3 edge = clamp((0.5 - abs(u0 - 0.5)) * 6.0, 0.0, 1.0);
    float inner = edge.x * edge.y * edge.z, depth = float(size.z), n = float(cells);
    vec3 outer = cg_local_light_cascade(u1, layer + 1.0, n, depth);
    return inner > 0.0 ? mix(outer, cg_local_light_cascade(u0, layer, n, depth), inner) : outer;
}

// What the world's lights add at camera-relative point p, non-directional: the light a particle or a pane takes, as
// Unreal's Volumetric NonDirectional translucency does.
vec3 cg_LocalLightAt(vec3 p) {
    return cg_local_light_blend(p, 0.0);
}

// The world's lights' direction at camera-relative p (Unreal's TranslucencyLightingVolumeDirectional): band 1 of their
// SH in world axes, luminance only, toward the lights. Read by cg_LitSurface; 0 in a firing drawing nothing
// "Surface"-lit, where the volume holds no direction.
vec3 cg_LocalLightDirectional(vec3 p) {
    return cg_local_light_blend(p, 2.0);
}
#endif

// A Distortion pass's output: the offset in UV units, the chromatic split, and the eye depth the haze starts at. The
// apply pulls nothing nearer than that depth into the bend. Alpha is the closeness, kept by a MAX blend.
#define CG_DISTORTION(offset, split, eyeDepth) vec4((offset), (split), 1.0 / max((eyeDepth), 0.01))
#define CG_DEPTH_REVERSED      (cg_DepthParams.x > 0.5)
// Whether this pass draws into the linear HDR scene. A Forward pass's colour is authored as sRGB, so the generated
// main decodes it there (cg_SceneDecode), unless the shader is tagged "ColorSpace" = "Linear".
#define CG_LINEAR_SCENE        (cg_DepthParams.z > 0.5)
// What a glow drawn into the scene is multiplied by (CgWorldRenderer.sceneEmission): 1 anywhere else.
#define CG_SCENE_GLOW          (CG_LINEAR_SCENE ? cg_DepthParams.w : 1.0)
// A material's value for each path, e.g. CG_HDR(0.4, 1.0): one program, picked at runtime.
#define CG_HDR(off, on)        (CG_LINEAR_SCENE ? (on) : (off))
// sRGB to linear up to 1; above it the excess passes unchanged, so an HDR colour keeps its brightness past white.
vec3 cg_SceneDecode(vec3 c) {
    vec3 s = clamp(c, 0.0, 1.0);
    vec3 lin = mix(s / 12.92, pow((s + 0.055) / 1.055, vec3(2.4)), step(vec3(0.04045), s));
    return lin + max(c - 1.0, 0.0);
}
// A blend's coverage as it reads in the scene: what lets through (1 - a) decoded, so a dark blend darkens linear
// light as much as it darkened the encoded picture, and smoke keeps the density it was tuned at.
float cg_SceneCoverage(float a) {
    return 1.0 - cg_SceneDecode(vec3(1.0 - clamp(a, 0.0, 1.0))).x;
}
// How far behind the scene an Emissive pass's fragment may be and still bloom: an opaque emissive surface is at the
// depth it wrote, give or take the depth buffer's precision. Eye units.
#define CG_EMISSIVE_DEPTH_SLACK 1.002
#define CG_EMISSIVE_DEPTH_BIAS  0.02

// -- Sun and fog --------------------------------------------------------------
#define CG_SUN_DIRECTION (cg_SunDirection.xyz)
#define CG_DAYLIGHT      (cg_SunDirection.w)
// The sun's direct share of a full day's sky light (crystalgraphics.world.sunShare): what its shadow takes away.
#define CG_SUN_SHARE     (cg_FogParams.z)

// The sun's part of the host's light at block and sky light (0 to 15 each): CG_DIRECTIONAL_LIGHT_COLOR's.
vec3 cg_DirectionalLightColor(vec2 light) {
    vec3 skyPart = max(CG_LIGHTMAP(light) - CG_LIGHTMAP(vec2(light.x, 0.0)), vec3(0.0));
    return skyPart * CG_SUN_SHARE * clamp(CG_DAYLIGHT, 0.0, 1.0);
}

// -- The last frame -----------------------------------------------------------
// What a temporal effect reprojects by (CgPassConstants.history): the host frame's index, whether last frame was one
// of this view, and where a camera-relative point was in last frame's clip space.
#define CG_FRAME_INDEX   int(cg_FrameParams.x)
#define CG_HISTORY_VALID (cg_FrameParams.y > 0.5)
vec4 cg_PreviousClip(vec3 worldPos) {
    return cg_PrevViewProj * vec4(worldPos, 1.0);
}

// How much of the host's fog lies between the camera and a point this far from it: 0 to 1, 0 with no fog.
float cg_FogAmount(float distance) {
    if (cg_FogColor.a < 0.5) return 0.0;
    return distance < cg_FogParams.y ? smoothstep(cg_FogParams.x, cg_FogParams.y, distance) : 1.0;
}
#define CG_FOG_AMOUNT(worldPos) cg_FogAmount(length((worldPos) - cg_CameraPos.xyz))

#if !defined(CG_VERTEX_STAGE) && !defined(CG_COMPUTE_STAGE)
// The light a lit fragment takes: its draw's (CG_OBJECT_LIGHT) unless its fragment function sets another, as a
// particle setting its own does.
vec2 cg_Light;

// This fragment's distance from the camera, from its window position: what its fog is measured by.
float cg_FragmentDistance() {
    vec2 ndc = gl_FragCoord.xy / cg_Resolution * 2.0 - 1.0;
    float eye = cg_ProjMatrix[2][3] == 0.0 ? cg_LinearEyeDepth(gl_FragCoord.z) : 1.0 / gl_FragCoord.w;
    return eye * length(vec3(ndc.x / cg_ProjMatrix[0][0], ndc.y / cg_ProjMatrix[1][1], 1.0));
}

// This fragment's camera-relative position, from its window position and the view: what the world's lights reach it at.
vec3 cg_FragmentPosition() {
    vec2 ndc = gl_FragCoord.xy / cg_Resolution * 2.0 - 1.0;
    float eye = cg_ProjMatrix[2][3] == 0.0 ? cg_LinearEyeDepth(gl_FragCoord.z) : 1.0 / gl_FragCoord.w;
    vec3 v = vec3((ndc.x + cg_ProjMatrix[2][0]) * eye / cg_ProjMatrix[0][0],
            (ndc.y + cg_ProjMatrix[2][1]) * eye / cg_ProjMatrix[1][1], -eye);
    return transpose(mat3(cg_ViewMatrix)) * (v - cg_ViewMatrix[3].xyz);
}

// The window depth of eye distance eye in the pass's convention: cg_LinearEyeDepth's inverse.
float cg_WindowDepth(float eye) {
    float ndc;
    if (cg_ProjMatrix[2][3] == 0.0) {
        ndc = cg_ProjMatrix[3][2] - cg_ProjMatrix[2][2] * eye;
    } else {
        float p22 = -cg_ProjMatrix[2][2] / cg_ProjMatrix[2][3];
        ndc = (cg_ProjMatrix[3][2] + p22 * cg_ProjMatrix[3][3]) / eye - p22;
    }
    return cg_DepthParams.y > 0.5 ? ndc : ndc * 0.5 + 0.5;
}

// Unreal's Pixel Depth Offset (ApplyPixelDepthOffsetToMaterialParameters): this fragment's depth pushed blocks farther
// in view depth, never nearer, written to gl_FragDepth. Call it once on every path; what shades it still reads the
// unpushed fragment.
//
//     CG_PIXEL_DEPTH_OFFSET(_DepthOffset * 0.01);   // a port's centimetres
void cg_PixelDepthOffset(float blocks) {
    float pushed = cg_WindowDepth(cg_LinearEyeDepth(gl_FragCoord.z) + max(blocks, 0.0));
    gl_FragDepth = CG_DEPTH_REVERSED ? min(pushed, gl_FragCoord.z) : max(pushed, gl_FragCoord.z);
}
#define CG_PIXEL_DEPTH_OFFSET(blocks) cg_PixelDepthOffset(blocks)

// What a lit material's colour becomes: times the lightmap at cg_Light, and lit by the world's lights as the opaque
// scene is (world_lights.shader adds scene x light): in a transparent firing, at this fragment. In the linear scene the
// colour is decoded after, so the gain is taken to the decode's inverse power.
vec4 cg_Lit(vec4 color) {
    vec3 local = cg_LocalLightAt(cg_FragmentPosition());
    vec3 gain = CG_LINEAR_SCENE ? pow(1.0 + local, vec3(1.0 / 2.4)) : 1.0 + local;
    return vec4(color.rgb * CG_LIGHTMAP(cg_Light) * gain, color.a);
}

#ifdef CG_SURFACE_LIT
// cg_Lit for a "Surface"-lit material, lit by normal n: Unreal's GetVolumeLightingDirectional. The ambient is band 0
// times its transfer, the direction band 1, coloured by the ambient's hue; 1.0233267 is the transfer's 2pi/3 times the
// basis's 0.488603, and the luminance Unreal 4's.
vec4 cg_LitSurface(vec4 color, vec3 n) {
    vec3 p = cg_FragmentPosition();
    vec3 ambient = cg_LocalLightAt(p);
    vec3 hue = ambient / (dot(ambient, vec3(0.3, 0.59, 0.11)) + 1.0e-5);
    vec3 local = max(ambient + 1.0233267 * dot(cg_LocalLightDirectional(p), n) * hue, vec3(0.0));
    vec3 gain = CG_LINEAR_SCENE ? pow(1.0 + local, vec3(1.0 / 2.4)) : 1.0 + local;
    return vec4(color.rgb * CG_LIGHTMAP(cg_Light) * gain, color.a);
}
#endif

// What a colour becomes behind this fragment's fog, for the pass's blend (CG_FOG_MODE, set by the compiler): mixed
// toward the fog colour (0), toward it times alpha for premultiplied output (1), or faded to nothing when added (2).
#ifndef CG_FOG_MODE
#define CG_FOG_MODE 0
#endif
vec4 cg_Fog(vec4 color) {
    float fog = cg_FogAmount(cg_FragmentDistance());
#if CG_FOG_MODE == 2
    return vec4(color.rgb * (1.0 - fog), color.a);
#elif CG_FOG_MODE == 1
    return vec4(mix(color.rgb, cg_FogColor.rgb * color.a, fog), color.a);
#elif CG_FOG_MODE == 3
    // A multiplier fades to 1, by the square of what gets through (Unreal's modulate)
    return vec4(mix(vec3(1.0), color.rgb, (1.0 - fog) * (1.0 - fog)), color.a);
#else
    return vec4(mix(color.rgb, cg_FogColor.rgb, fog), color.a);
#endif
}
#endif

// -- Time and Resolution Macros ---------------------------------------------
#define CG_TIME           (cg_Time.y)   // most useful: raw seconds
#define CG_TIME_VEC4      (cg_Time)     // all four: t/20, t, t*2, t*3
#define CG_RESOLUTION     (cg_Resolution)

// -- Convenience Macros ------------------------------------------------------
#define CG_MATRIX_MVP (cg_ProjMatrix * cg_ViewMatrix * CG_OBJECT_TO_WORLD)

// Where the camera is, in world space: a uniform, since the CPU has it and a fragment would otherwise invert the
// view to get it. A world pass is camera-relative, as Minecraft draws, so there it is the origin.
#define CG_CAMERA_WORLD_POS (cg_CameraPos.xyz)

// A world-space point in absolute coordinates: for an effect that must not move with the camera. A float: at a
// million blocks it resolves to 1/16.
#define CG_ABSOLUTE_WORLD_POS(p) ((p) + cg_WorldOrigin.xyz)

// -- Per-renderer environments ----------------------------------------------
// Everything above is universal. A renderer's own macros are NOT: they live beside the buffer
// they read, in env/buffer/<token>.glsl, and arrive only when a shader declares
// `#pragma cg_use <token>`. Three quarters of this file used to be CgQuadRenderer's and
// CgVectorRenderer's, paid for by every shader including the ones that draw neither.
