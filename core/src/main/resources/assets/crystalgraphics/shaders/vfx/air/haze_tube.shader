// Heat haze along a beam: haze.shader's shimmer on a tube round a path, strongest face-on and gone at the silhouette and
// where the tube meets the scene. A tube layer: fx_tube.glsl's contract (_FxPath, CG_OBJECT_CUSTOM0..1 as CgVfxTube
// writes them), its radius the layer's times the ring's, capped at the layer's parameter in blocks (0 for no cap). Drawn
// after the soft layers and before the sharp ones (ORDER_DISTORTION). A ray through the beam itself, within _Core
// times its ring's radius of the path, is left unbent, so the haze shimmers round the beam and never warps it. Reads
// cg_SceneColor and depth.
#type spatial
#include "crystalgraphics:shaders/lib/vfx/fx_common.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_tube.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_depth.glsl"

// The bend reaches _Strength times the intensity times the noise, at most about 0.03 of the height.
Tags { "RenderType" = "Transparent" "SceneColorMargin" = "0.03" }
Queue = "Transparent"

Properties {
    _FxPath   ("Path rings", sampler2D) = "black"
    _Strength ("Shimmer at full intensity, share of the screen's height", float) = 0.02
    _Reference ("Within this many blocks the bend is _Strength; farther it shrinks as the haze does on screen", float) = 6
    _Scale    ("Shimmer frequency, a block", float) = 1.6
    _Rise     ("Rising speed, blocks a second", float) = 0.7
    _Core     ("The beam's visible reach, in ring radii: left unbent", float) = 1
}

struct v2f { vec3 world; vec3 axis; vec3 tangent; vec2 time; float core; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        Blend SRC_ALPHA ONE_MINUS_SRC_ALPHA
        DepthTest LEQUAL
        DepthWrite OFF
        Cull BACK
    }

    void vertex(out v2f o) {
        float scale = CG_OBJECT_CUSTOM0.z, cap = CG_OBJECT_CUSTOM0.w;
        FxTubeVertex v = fx_tube_vertex(_FxPath, int(CG_OBJECT_CUSTOM0.x + 0.5), int(CG_OBJECT_CUSTOM0.y + 0.5),
                                        cg_TexCoord0, scale, 0.0);
        // Capped below the body's throb, so the haze holds still while the beam pulses; the taper at its ends survives.
        float full = max(v.ring.radius * scale, 1.0e-4);
        if (cap > 0.0) v.position = v.ring.position + (v.position - v.ring.position) * (min(full, cap) / full);
        vec3 origin = CG_OBJECT_TO_WORLD[3].xyz - CG_OBJECT_CUSTOM1.xyz;
        o.world = origin + v.position;
        o.axis = origin + v.ring.position;
        o.tangent = v.ring.tangent;
        o.core = v.ring.radius * _Core;
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
        // How far the ray passes from the path, across it, against the beam's own radius.
        vec3 across = ray - t * dot(ray, t);
        float c = abs(dot(n, across)) / max(length(across), 1.0e-4);
        float passes = length(rel - t * dot(rel, t)) * sqrt(max(0.0, 1.0 - c * c));
        body *= smoothstep(i.core * 0.9, i.core * 1.25, passes);
        float soft = smoothstep(0.0, 1.5, FX_SCENE_DISTANCE(ray) - distance(eye, i.world));
        float strength = body * soft;
        if (strength < 0.002) discard;
        float age = i.time.x, seed = i.time.y;
        vec2 wobble = fx_heat(i.world * _Scale, age, _Rise * _Scale, seed);
        vec2 uv = gl_FragCoord.xy / CG_RESOLUTION;
        // Perspective: a far haze moves the scene behind it as little as it covers.
        float far = min(1.0, _Reference / max(distance(eye, i.world), 1.0e-3));
        vec2 bent = uv + wobble * _Strength * far * strength * vec2(CG_RESOLUTION.y / CG_RESOLUTION.x, 1.0);
        fragColor = vec4(CG_SCENE_COLOR(bent).rgb, smoothstep(0.0, 0.1, strength));
    }
}
