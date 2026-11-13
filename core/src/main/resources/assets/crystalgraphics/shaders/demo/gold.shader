// Polished gold, mirroring the studio and the floor beneath it. CgVfxShowcase.
#domain surface
#include "crystalgraphics:shaders/demo/common.glsl"
Material { Faces Both }

struct v2f { vec3 worldPos; vec3 normalWs; vec3 objPos; };

void vertex(inout CgVertex v, out v2f o) {
    vec4 world = CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0);
    o.worldPos = world.xyz;
    o.normalWs = CG_NORMAL_MATRIX * cg_Normal;
    o.objPos = cg_Position;
    v.world = world.xyz;
}

void surface(in v2f i, inout CgSurface s) {
    vec3 n = normalize(i.normalWs);
    // From inside the sphere its inner wall shows, facing in.
    if (!gl_FrontFacing) n = -n;
    vec3 v = normalize(CG_CAMERA_POSITION - i.worldPos);
    float floorY = CG_OBJECT_TO_WORLD[3].y - CG_OBJECT_CUSTOM3.x;
    vec3 color = vfx_pbr_studio(i.worldPos, floorY, n, v, vec3(1.0, 0.72, 0.24), 1.0, 0.08);
    s.baseColor = fx_aces(0.8 * color);
}
