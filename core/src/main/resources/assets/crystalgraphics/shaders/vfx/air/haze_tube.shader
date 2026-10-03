// Heat haze along a beam: haze.shader's shimmer on a tube round a path, strongest face-on and gone at the silhouette and
// where the tube meets the scene. A tube layer: fx_tube.glsl's contract (_FxPath, CG_OBJECT_CUSTOM0..1 as CgVfxTube
// writes them), its radius the layer's. Drawn first in the transparent pass (PRIORITY_DISTORTION). Reads cg_SceneColor
// and depth.
#type spatial
#include "crystalgraphics:shaders/lib/vfx/fx_common.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_tube.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_depth.glsl"

Tags { "RenderType" = "Transparent" }
Queue = "Transparent"

Properties {
    _FxPath   ("Path rings", sampler2D) = "black"
    _Strength ("Shimmer at full intensity, share of the screen's height", float) = 0.01
    _Scale    ("Shimmer frequency, a block", float) = 1.6
    _Rise     ("Rising speed, blocks a second", float) = 0.7
}

struct v2f { vec3 world; vec3 axis; vec3 tangent; vec2 time; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        Blend SRC_ALPHA ONE_MINUS_SRC_ALPHA
        DepthTest LEQUAL
        DepthWrite OFF
        Cull BACK
    }

    void vertex(out v2f o) {
        FxTubeVertex v = fx_tube_vertex(_FxPath, int(CG_OBJECT_CUSTOM0.x + 0.5), int(CG_OBJECT_CUSTOM0.y + 0.5),
                                        cg_TexCoord0, CG_OBJECT_CUSTOM0.z, 0.0);
        vec3 origin = CG_OBJECT_TO_WORLD[3].xyz - CG_OBJECT_CUSTOM1.xyz;
        o.world = origin + v.position;
        o.axis = origin + v.ring.position;
        o.tangent = v.ring.tangent;
        // the effect's age and seed
        o.time = vec2(v.header.w, v.header.z);
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * vec4(o.world, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        vec3 eye = FX_CAMERA;
        vec3 ray = normalize(i.world - eye);
        vec3 t = normalize(i.tangent);
        vec3 rel = i.world - i.axis;
        vec3 n = normalize(rel - t * dot(rel, t) + 1.0e-6);
        float body = smoothstep(0.0, 0.7, abs(dot(n, ray)));
        float soft = smoothstep(0.0, 1.5, FX_SCENE_DISTANCE(ray) - distance(eye, i.world));
        float strength = body * soft;
        if (strength < 0.002) discard;
        float age = i.time.x, seed = i.time.y;
        vec3 p = i.world * _Scale + vec3(0.0, -age * _Rise * _Scale, 0.0) + seed * 11.0;
        vec2 wobble = vec2(fx_noise(p), fx_noise(p + vec3(19.1, 7.3, 3.7)));
        vec2 uv = gl_FragCoord.xy / CG_RESOLUTION;
        vec2 bent = uv + wobble * _Strength * strength * vec2(CG_RESOLUTION.y / CG_RESOLUTION.x, 1.0);
        fragColor = vec4(CG_SCENE_COLOR(bent).rgb, smoothstep(0.0, 0.1, strength));
    }
}
