// A plasma bolt flying at the force field: a stretched sphere of light, white-hot along its middle and orange-red at
// its edges, added over everything. CgVfxShowcase.
#domain surface
#include "crystalgraphics:shaders/demo/vfx_common.glsl"
Material { BlendMode Additive  ShadingModel Unlit }

struct v2f { vec3 worldPos; vec3 normalWs; };

void vertex(inout CgVertex v, out v2f o) {
    vec4 world = CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0);
    o.worldPos = world.xyz;
    o.normalWs = CG_NORMAL_MATRIX * cg_Normal;
    v.world = world.xyz;
}

void surface(in v2f i, inout CgSurface s) {
    vec4 fragColor = vec4(0.0);
    float nv = abs(dot(normalize(i.normalWs), normalize(CG_CAMERA_POSITION - i.worldPos)));
    vec3 color = vec3(2.2, 0.55, 0.08) * pow(nv, 1.5) * 1.6 + vec3(1.6, 1.3, 0.9) * pow(nv, 6.0) * 3.5;
    fragColor = vec4(color, 1.0);
    s.baseColor = fragColor.rgb;
    s.opacity = fragColor.a;
}
