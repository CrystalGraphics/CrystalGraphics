// An energy orb's skin (a wave's head, its charge ball): churning, its folds rolling back along -z, eroded into crisp
// streaks and brightest where it leads. Drawn on CgVfxFrame.mesh's sphere, +z forward. The layer's parameter is how much
// its back fades out, 1 for a head blending into the body; CG_OBJECT_CUSTOM1.z an intensity. Colour A is the skin,
// colour B its hottest streaks, A's alpha a strength. CgEnergyWave.
#type spatial
#include "crystalgraphics:shaders/lib/vfx/fx_common.glsl"

Tags { "RenderType" = "Transparent" }
Queue = "Transparent"

Properties {
    _Flow     ("How fast the folds roll back, blocks a second", float) = 14.0
    _Erosion  ("Erosion threshold face-on, 0..1", float) = 0.56
    _Displace ("Churn, share of the size", float) = 0.18
}

struct v2f { vec3 world; vec3 local; vec3 centre; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        Blend ONE ONE
        DepthTest LEQUAL
        DepthWrite OFF
        Cull OFF
    }

    void vertex(out v2f o) {
        vec3 p = cg_Position;
        float age = CG_OBJECT_CUSTOM0.z, seed = CG_OBJECT_CUSTOM0.w;
        float churn = fx_fbm(vec3(p.xy * 1.7, p.z * 1.7 + age * _Flow * 0.25) + seed * 17.0, 3);
        p *= 1.0 + _Displace * (0.6 + 0.4 * p.z) * churn;
        vec4 world = CG_OBJECT_TO_WORLD * vec4(p, 1.0);
        o.world = world.xyz;
        o.local = cg_Position;
        o.centre = CG_OBJECT_TO_WORLD[3].xyz;
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * world;
    }

    void fragment(in v2f i, out vec4 fragColor) {
        vec3 eye = FX_CAMERA;
        vec3 ray = normalize(i.world - eye);
        vec3 n = normalize(transpose(inverse(mat3(CG_OBJECT_TO_WORLD))) * i.local);
        float rim = 1.0 - abs(dot(n, ray));
        float age = CG_OBJECT_CUSTOM0.z, seed = CG_OBJECT_CUSTOM0.w;
        vec3 lp = i.local;
        float e = 0.5 + 0.5 * fx_fbm(vec3(lp.xy * 2.6, lp.z * 1.4 + age * _Flow * 0.3) + seed * 11.0, 4);
        float threshold = mix(_Erosion, _Erosion - 0.25, rim);
        float aa = fwidth(e) + 0.002;
        float alpha = smoothstep(threshold - aa, threshold + aa, e);
        float hot = smoothstep(threshold, threshold + 0.2, e);
        float lead = 0.55 + 0.45 * smoothstep(-0.2, 0.9, lp.z);
        float fade = mix(1.0, smoothstep(-0.95, -0.25, lp.z), CG_OBJECT_CUSTOM0.y);
        vec3 col = mix(CG_OBJECT_CUSTOM2.rgb, CG_OBJECT_CUSTOM3.rgb, hot) * (0.4 + 1.2 * rim) * lead;
        fragColor = vec4(col * alpha * fade * CG_OBJECT_CUSTOM2.a * CG_OBJECT_CUSTOM1.z * (gl_FrontFacing ? 1.0 : 0.55), 1.0);
    }
}
