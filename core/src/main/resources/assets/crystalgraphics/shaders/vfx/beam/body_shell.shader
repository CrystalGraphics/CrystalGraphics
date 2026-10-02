// An energy wave's shell: a churning blue skin streaming forward, eroded by a noise threshold into crisp-edged
// streaks, sparse where it faces the eye so the core shows through and filled at the silhouette. Colour A is the skin,
// colour B its hottest streaks, A's alpha a strength. CgEnergyWave.
#type spatial
#include "crystalgraphics:shaders/lib/vfx/fx_common.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_tube.glsl"

Tags { "RenderType" = "Transparent" }
Queue = "Transparent"

Properties {
    _FxPath   ("Path rings", sampler2D) = "black"
    _Flow     ("Flow, blocks a second", float) = 18.0
    _Scale    ("Streak frequency", float) = 1.0
    _Erosion  ("Erosion threshold face-on, 0..1", float) = 0.6
    _Displace ("Surface churn, share of the radius", float) = 0.12
}

struct v2f { vec3 world; vec3 axis; vec3 tangent; vec4 surface; float pulse; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        Blend ONE ONE
        DepthTest LEQUAL
        DepthWrite OFF
        Cull OFF
    }

    void vertex(out v2f o) {
        FxTubeVertex v = fx_tube_vertex(_FxPath, int(CG_OBJECT_CUSTOM0.x + 0.5), int(CG_OBJECT_CUSTOM0.y + 0.5),
                                        cg_TexCoord0, CG_OBJECT_CUSTOM0.z, 0.0);
        float a = v.angle * 6.28318531;
        float age = v.header.w, seed = v.header.z;
        float churn = fx_noise(vec3((v.ring.arc - age * _Flow) * 0.5, cos(a) * 1.3, sin(a) * 1.3) + seed * 17.0);
        v.position += (v.position - v.ring.position) * (_Displace * churn);
        vec3 origin = CG_OBJECT_TO_WORLD[3].xyz - CG_OBJECT_CUSTOM1.xyz;
        o.world = origin + v.position;
        o.axis = origin + v.ring.position;
        o.tangent = v.ring.tangent;
        // arc, angle, the effect's age, its seed
        o.surface = vec4(v.ring.arc, v.angle, age, seed);
        o.pulse = v.ring.intensity;
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * vec4(o.world, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        vec3 eye = FX_CAMERA;
        vec3 ray = normalize(i.world - eye);
        vec3 t = normalize(i.tangent);
        vec3 rel = i.world - i.axis;
        vec3 n = normalize(rel - t * dot(rel, t) + 1.0e-6);
        float rim = 1.0 - abs(dot(n, ray));
        float a = i.surface.y * 6.28318531;
        vec2 around = vec2(cos(a), sin(a));
        float age = i.surface.z, seed = i.surface.w;
        // Long streaks along the beam, and a finer, faster set over them.
        vec3 p = vec3((i.surface.x - age * _Flow) * 0.3, around * 2.4) * _Scale + seed * 13.0;
        float e = 0.5 + 0.5 * fx_fbm(p, 4);
        vec3 fine = vec3((i.surface.x - age * _Flow * 1.6) * 0.9, around * 5.0) * _Scale + seed * 7.0;
        e = mix(e, 0.5 + 0.5 * fx_noise(fine), 0.3);
        float threshold = mix(_Erosion, _Erosion - 0.22, rim);
        float aa = fwidth(e) + 0.002;
        float alpha = smoothstep(threshold - aa, threshold + aa, e);
        float hot = smoothstep(threshold, threshold + 0.22, e);
        vec3 col = mix(CG_OBJECT_CUSTOM2.rgb, CG_OBJECT_CUSTOM3.rgb, hot) * (0.35 + 1.15 * rim);
        fragColor = vec4(col * alpha * i.pulse * CG_OBJECT_CUSTOM2.a * (gl_FrontFacing ? 1.0 : 0.55), 1.0);
    }
}
