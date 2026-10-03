// Heat haze round something hot: the scene behind a sphere shimmering in rising noise, strongest face-on (where the
// most hot air lies along the ray) and gone at the silhouette and where the sphere meets the scene, so it has no edge.
// Drawn on CgVfxFrame.mesh's sphere, front faces, after the soft layers and before the sharp ones (ORDER_DISTORTION).
// CG_OBJECT_CUSTOM0.zw are the effect's age and seed, CG_OBJECT_CUSTOM1.z an intensity. Reads cg_SceneColor and depth.
#type spatial
#include "crystalgraphics:shaders/lib/vfx/fx_common.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_depth.glsl"

Tags { "RenderType" = "Transparent" }
Queue = "Transparent"

Properties {
    _Strength ("Shimmer at full intensity, share of the screen's height", float) = 0.012
    _Scale    ("Shimmer frequency, a block", float) = 1.6
    _Rise     ("Rising speed, blocks a second", float) = 0.7
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
        vec3 eye = FX_CAMERA;
        vec3 ray = normalize(i.world - eye);
        vec3 n = normalize(i.world - CG_OBJECT_TO_WORLD[3].xyz);
        float body = smoothstep(0.0, 0.7, abs(dot(n, ray)));
        float soft = smoothstep(0.0, 1.5, FX_SCENE_DISTANCE(ray) - distance(eye, i.world));
        float strength = body * soft * CG_OBJECT_CUSTOM1.z;
        if (strength < 0.002) discard;
        float age = CG_OBJECT_CUSTOM0.z, seed = CG_OBJECT_CUSTOM0.w;
        vec3 p = i.world * _Scale + vec3(0.0, -age * _Rise * _Scale, 0.0) + seed * 11.0;
        vec2 wobble = vec2(fx_noise(p), fx_noise(p + vec3(19.1, 7.3, 3.7)));
        vec2 uv = gl_FragCoord.xy / CG_RESOLUTION;
        vec2 bent = uv + wobble * _Strength * strength * vec2(CG_RESOLUTION.y / CG_RESOLUTION.x, 1.0);
        fragColor = vec4(CG_SCENE_COLOR(bent).rgb, smoothstep(0.0, 0.1, strength));
    }
}
