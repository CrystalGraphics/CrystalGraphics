// A shock front in the air: a sphere shell, invisible face-on, bending the scene behind it in a thin band just inside
// its silhouette, where the front is seen edge-on. Racing out from a blast ahead of its dust, it reads as the air
// itself rippling. Drawn on CgVfxFrame.mesh's sphere, front faces, after the soft layers and before the sharp ones
// (ORDER_DISTORTION), so it bends smoke and glow but never a bright body. CG_OBJECT_CUSTOM1.z is an intensity. Reads
// cg_SceneColor.
#type spatial
#include "crystalgraphics:shaders/lib/vfx/fx_common.glsl"

Tags { "RenderType" = "Transparent" }
Queue = "Transparent"

Properties {
    _Strength ("Bend at full intensity, share of the screen's height", float) = 0.04
    _Band     ("How far in from the silhouette the band reaches, as facing 0..1", float) = 0.45
}

struct v2f { vec3 world; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        Blend SRC_ALPHA ONE_MINUS_SRC_ALPHA
        DepthTest LEQUAL
        DepthWrite OFF
        Cull BACK
    }

    void vertex(out v2f o) {
        vec4 world = CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0);
        o.world = world.xyz;
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * world;
    }

    void fragment(in v2f i, out vec4 fragColor) {
        vec3 ray = normalize(i.world - FX_CAMERA);
        vec3 n = normalize(i.world - CG_OBJECT_TO_WORLD[3].xyz);
        float face = abs(dot(n, ray));
        float band = (1.0 - smoothstep(_Band * 0.4, _Band, face)) * smoothstep(0.0, 0.08, face);
        float strength = band * CG_OBJECT_CUSTOM1.z;
        if (strength < 0.002) discard;
        // Outward on screen, along the normal as the eye sees it.
        vec2 dir = normalize((mat3(cg_ViewMatrix) * n).xy + 1.0e-5);
        vec2 uv = gl_FragCoord.xy / CG_RESOLUTION;
        vec2 bent = uv + dir * _Strength * strength * vec2(CG_RESOLUTION.y / CG_RESOLUTION.x, 1.0);
        fragColor = vec4(CG_SCENE_COLOR(bent).rgb, smoothstep(0.0, 0.15, strength));
    }
}
