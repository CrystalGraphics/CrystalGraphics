// The final blast's dome: a shell of churning plasma, a blazing rim over a filled interior, white-hot as it bursts out
// and cooling to blue, eroding into crisp-edged patches as it cools until nothing is left. Drawn on CgVfxFrame.mesh's sphere, both
// faces. CG_OBJECT_CUSTOM1.z is an intensity, .w the blast's progress 0..1. Colour A is the hot burst, colour B the
// cool shell, A's alpha a strength. CgEnergyWave.
#type spatial
#include "crystalgraphics:shaders/lib/vfx/fx_common.glsl"

Tags { "RenderType" = "Transparent" }
Queue = "Transparent"

Properties {
    _Erode ("Progress the dome starts breaking up at", float) = 0.3
    _Scale ("Break-up frequency", float) = 3.0
}

struct v2f { vec3 world; vec3 local; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        Blend ONE ONE
        DepthTest LEQUAL
        DepthWrite OFF
        Cull OFF
    }

    void vertex(out v2f o) {
        vec4 world = CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0);
        o.world = world.xyz;
        o.local = cg_Position;
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * world;
    }

    void fragment(in v2f i, out vec4 fragColor) {
        vec3 eye = FX_CAMERA;
        vec3 ray = normalize(i.world - eye);
        vec3 n = normalize(i.world - CG_OBJECT_TO_WORLD[3].xyz);
        float rim = 1.0 - abs(dot(n, ray));
        float progress = CG_OBJECT_CUSTOM1.w, seed = CG_OBJECT_CUSTOM0.w;
        float e = 0.5 + 0.5 * fx_fbm(i.local * _Scale + vec3(0.0, 0.0, -progress * 2.0) + seed * 19.0, 4);
        float threshold = mix(-0.1, 1.05, smoothstep(_Erode, 1.0, progress));
        float aa = fwidth(e) + 0.002;
        float alpha = smoothstep(threshold - aa, threshold + aa, e);
        float heat = 1.0 - progress;
        vec3 col = mix(CG_OBJECT_CUSTOM3.rgb, CG_OBJECT_CUSTOM2.rgb, heat * heat);
        // Plasma flowing over the surface, brightest where it folds.
        float age = CG_OBJECT_CUSTOM0.z;
        float flow = 0.5 + 0.5 * fx_fbm(i.local * 2.2 + vec3(age * 0.6, -age * 0.9, age * 0.4) + seed * 7.0, 4);
        // A blazing fill while it is young, the rim taking over as it cools.
        float shell = (pow(rim, 2.5) * 1.2 + 0.25 + 1.1 * heat * heat) * (0.4 + 1.2 * flow * flow);
        float edge = 1.0 - smoothstep(threshold, threshold + 0.08, e);
        col *= shell + edge * 1.2 * step(0.0, threshold);
        fragColor = vec4(col * alpha * CG_OBJECT_CUSTOM2.a * CG_OBJECT_CUSTOM1.z * (gl_FrontFacing ? 1.0 : 0.5), 1.0);
    }
}
