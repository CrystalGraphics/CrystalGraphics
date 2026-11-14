// A decal's projection (#domain decal, DecalDomain): what is behind this pixel of the decal's box, in the box's space.
// Unreal's DecalCommon.ush: the scene's depth read back into the world, taken into the box (the engine's cube, -0.5 to
// 0.5, scaled by the decal's size), the pixel dropped outside it, the uv along the box's local X. The decal faces down
// its X axis, so its facing is -X in the world.
#pragma once
#ifdef CG_FRAGMENT_STAGE

// Projects this pixel: discards it outside the box, else fills the decal's uv, the point (camera-relative world) and
// its facing, and answers its fade: its lifetime's (CG_OBJECT_SPARE.x, 0 for whole, 1 for gone) times its screen
// size's (CG_OBJECT_SPARE.y, the screen share below which it fades out, 0 for none; Unreal's FadeScreenSize).
float cg_DecalProject(mat4 toDecal, out vec2 uv, out vec3 world, out vec3 facing) {
    vec2 screen = gl_FragCoord.xy / cg_Resolution;
    world = cg_DepthToWorld(screen, textureLod(cg_DepthBuffer, screen, 0.0).r);
    vec3 local = (toDecal * vec4(world, 1.0)).xyz * 2.0;
    if (any(greaterThan(abs(local), vec3(1.0)))) discard;
    uv = local.zy * vec2(0.5, -0.5) + 0.5;
    mat4 m = CG_OBJECT_TO_WORLD;
    facing = -normalize(m[0].xyz);
    float fade = 1.0 - clamp(CG_OBJECT_SPARE.x, 0.0, 1.0);
    float fadeSize = CG_OBJECT_SPARE.y;
    if (fadeSize > 0.0) {
        float radius = 0.5 * sqrt(dot(m[0].xyz, m[0].xyz) + dot(m[1].xyz, m[1].xyz) + dot(m[2].xyz, m[2].xyz));
        float distance = max(length(m[3].xyz - CG_CAMERA_POSITION), 1e-3);
        float size = radius * max(cg_ProjMatrix[0][0], cg_ProjMatrix[1][1]) / distance;
        fade *= smoothstep(fadeSize * 0.5, fadeSize, size);
    }
    return fade;
}
#endif
