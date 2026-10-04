// The final blast's dome: a fireball of billowing plasma bursting outward, its outline torn ragged by the billows, its
// surface white-hot billow tops over darker lanes and veined with white heat, flickering. As it cools to blue it erodes
// into dimming shreds until nothing is left. Brightest where it is thickest, face-on: no rim, so no outline. Drawn on
// CgVfxFrame.mesh's sphere, both faces. CG_OBJECT_CUSTOM1.z is an intensity, .w the blast's progress 0..1. Colour A is
// the hot burst, colour B the cool shell, A's alpha a strength. CgEnergyWave.
#type spatial
#include "crystalgraphics:shaders/lib/vfx/fx_common.glsl"

Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" }
Queue = "Transparent"

Properties {
    _Erode   ("Progress the dome starts breaking up at", float) = 0.3
    _Scale   ("Break-up frequency", float) = 3.0
    _Billow  ("Billows out of the sphere, share of its radius", float) = 0.16
    _Noise ("Noise", sampler3D) = "cg_noise"
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
        vec3 p = cg_Position;
        float age = CG_OBJECT_CUSTOM0.z, seed = CG_OBJECT_CUSTOM0.w;
        float billow = fx_fbm(p * 1.8 + vec3(0.0, -age * 0.8, age * 0.5) + seed * 5.0, 3);
        p *= 1.0 + _Billow * billow;
        vec4 world = CG_OBJECT_TO_WORLD * vec4(p, 1.0);
        o.world = world.xyz;
        o.local = cg_Position;
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * world;
    }

    void fragment(in v2f i, out vec4 fragColor) {
        vec3 eye = FX_CAMERA;
        vec3 ray = normalize(i.world - eye);
        vec3 n = normalize(i.world - CG_OBJECT_TO_WORLD[3].xyz);
        float face = abs(dot(n, ray));
        float progress = CG_OBJECT_CUSTOM1.w, seed = CG_OBJECT_CUSTOM0.w, age = CG_OBJECT_CUSTOM0.z;
        float heat = 1.0 - progress;
        // Billows racing over the surface: bright tops, darker lanes between.
        float flow = 0.5 + 0.5 * fx_warped(i.local * 2.0 + vec3(age * 1.4, -age * 2.0, age * 0.9) + seed * 7.0, 1.6);
        float tops = flow * flow * flow;
        // Breaking up: sooner at the silhouette, so the outline tears first.
        float e = 0.5 + 0.5 * fx_fbm(i.local * _Scale + vec3(0.0, 0.0, -progress * 2.0) + seed * 19.0, 4);
        float threshold = mix(-0.1, 1.05, smoothstep(_Erode, 1.0, progress)) + 0.35 * pow(1.0 - face, 3.0);
        float aa = fwidth(e) + 0.002;
        float alpha = smoothstep(threshold - aa, threshold + aa, e);
        float edge = (1.0 - smoothstep(threshold, threshold + 0.05, e)) * step(0.0, threshold);
        // Veins of white heat, sparse and irregular, burning only while it is young.
        float vein = fx_ridged(i.local * 3.2 + vec3(age * 0.9, 0.0, -age * 0.6) + seed * 3.0, 3);
        float crack = smoothstep(0.8, 0.93, vein) * heat;
        vec3 cool = CG_OBJECT_CUSTOM3.rgb, hot = CG_OBJECT_CUSTOM2.rgb;
        vec3 col = mix(cool, hot, clamp(heat * heat + tops * heat, 0.0, 1.0));
        float fill = (0.2 + 2.0 * tops) * (0.45 + 0.55 * face) * (0.25 + 1.25 * heat);
        col = col * (fill + edge * 0.8 * heat) + hot * crack * 2.2;
        col *= fx_flicker(age, seed);
        fragColor = vec4(col * alpha * CG_OBJECT_CUSTOM2.a * CG_OBJECT_CUSTOM1.z * (gl_FrontFacing ? 1.0 : 0.5), 1.0);
    }
}

// Its light again, into the world's bloom: the Forward pass's code and state, blurred over the scene.
Pass { Tags { "LightMode" = "Emissive" } }
