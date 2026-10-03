// A shock front in the air: a sphere shell, invisible face-on, bending the scene behind it in a thin band just inside
// its silhouette, where the front is seen edge-on. Racing out from a blast ahead of its dust, it reads as the air
// itself rippling. Drawn on CgVfxFrame.mesh's sphere's far wall, the shell the eye sees found analytically: its near
// side from outside, its far side from inside, so a front sweeping past the camera ripples the whole view as it goes.
// After the soft layers and before the sharp ones (ORDER_DISTORTION), so it bends smoke and glow but never a bright
// body. CG_OBJECT_CUSTOM1.z is an intensity. Reads cg_SceneColor and depth.
#type spatial
#include "crystalgraphics:shaders/lib/vfx/fx_common.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_depth.glsl"

// The bend reaches _Strength times the intensity, at most about 0.05 of the height.
Tags { "RenderType" = "Transparent" "SceneColorMargin" = "0.05" }
Queue = "Transparent"

Properties {
    _Strength ("Bend at full intensity, share of the screen's height", float) = 0.04
    _Reference ("Within this many blocks the bend is _Strength; farther it shrinks as the front does on screen", float) = 6
    _Band     ("How far in from the silhouette the band reaches, as facing 0..1", float) = 0.45
}

struct v2f { vec3 world; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        Blend SRC_ALPHA ONE_MINUS_SRC_ALPHA
        DepthTest ALWAYS
        DepthWrite OFF
        Cull FRONT
    }

    void vertex(out v2f o) {
        vec4 world = CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0);
        o.world = world.xyz;
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * world;
    }

    void fragment(in v2f i, out vec4 fragColor) {
        vec3 eye = FX_CAMERA;
        vec3 ray = normalize(i.world - eye);
        vec3 centre = CG_OBJECT_TO_WORLD[3].xyz;
        float radius = length(CG_OBJECT_TO_WORLD[0].xyz);
        float along = dot(centre - eye, ray);
        vec3 line = eye + ray * along - centre;
        float chord = sqrt(max(radius * radius - dot(line, line), 0.0));
        // The shell the eye sees: the near side from outside, the far side from inside; hidden behind the scene.
        float hit = along - chord > 0.0 ? along - chord : along + chord;
        if (hit > FX_SCENE_DISTANCE(ray)) discard;
        vec3 n = normalize(eye + ray * hit - centre);
        float face = abs(dot(n, ray));
        float band = (1.0 - smoothstep(_Band * 0.4, _Band, face)) * smoothstep(0.0, 0.08, face);
        float strength = band * CG_OBJECT_CUSTOM1.z;
        if (strength < 0.002) discard;
        // Outward on screen, along the normal as the eye sees it.
        vec2 dir = normalize((mat3(cg_ViewMatrix) * n).xy + 1.0e-5);
        vec2 uv = gl_FragCoord.xy / CG_RESOLUTION;
        // Perspective: a far front moves the scene behind it as little as it covers.
        float far = min(1.0, _Reference / max(hit, 1.0e-3));
        vec2 bent = uv + dir * _Strength * far * strength * vec2(CG_RESOLUTION.y / CG_RESOLUTION.x, 1.0);
        fragColor = vec4(CG_SCENE_COLOR(bent).rgb, smoothstep(0.0, 0.15, strength));
    }
}
