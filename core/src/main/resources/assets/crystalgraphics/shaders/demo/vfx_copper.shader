// Polished copper with faint brush lines around the pole, mirroring the studio and the floor beneath it. CgVfxShowcase.
#type spatial
#include "crystalgraphics:shaders/demo/vfx_common.glsl"

Tags { "RenderType" = "Opaque" }
Queue = "Geometry"

Properties {
    _ValueNoise ("Value noise", sampler3D) = "cg_value_noise"
}

struct v2f { vec3 worldPos; vec3 normalWs; vec3 objPos; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        DepthTest LEQUAL
        DepthWrite ON
        Cull OFF
    }

    void vertex(out v2f o) {
        vec4 world = CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0);
        o.worldPos = world.xyz;
        o.normalWs = CG_NORMAL_MATRIX * cg_Normal;
        o.objPos = cg_Position;
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * world;
    }

    void fragment(in v2f i, out vec4 fragColor) {
        vec3 p = i.objPos;
        // Faint brush lines along each latitude, in the colour only: varying the roughness per line saws every
        // reflected edge.
        float brush = fx_value_noise(vec3(p.y * 60.0, atan(p.z, p.x) * 2.0, 0.0));
        vec3 n = normalize(i.normalWs);
        // From inside the sphere its inner wall shows, facing in.
        if (!gl_FrontFacing) n = -n;
        vec3 v = normalize(FX_CAMERA - i.worldPos);
        float floorY = CG_OBJECT_TO_WORLD[3].y - CG_OBJECT_CUSTOM3.x;
        vec3 copper = vec3(0.98, 0.56, 0.4) * (0.96 + 0.05 * brush);
        vec3 color = vfx_pbr_studio(i.worldPos, floorY, n, v, copper, 1.0, 0.14);
        fragColor = vec4(fx_aces(0.8 * color), 1.0);
    }
}
