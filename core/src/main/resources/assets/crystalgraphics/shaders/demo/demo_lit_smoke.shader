// The lights demo's smoke (CgLightsDemo): a soft, boiling puff on a quad facing the camera, lit by the world where it
// is and blended over what is behind it, so the lights wheeling round it colour it as they pass (cg_Lit, the world
// lights' translucency volume). vfx/particle/sprite.shader with its sheet a noise puff. _Color tints it, its alpha a
// strength.
#type none
#pragma cg_use particle
#pragma cg_use rows
#include "crystalgraphics:shaders/lib/vfx/fx_common.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_ribbon.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_particle.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_draw.glsl"

Tags { "RenderType" = "Transparent" }
Queue = "Transparent"

Properties {
    _Color ("Tints the smoke; its alpha a strength", color) = (1, 1, 1, 1)
    _Noise ("Noise", sampler3D) = "cg_noise"
    _SoftDistance ("Fades into what is behind it over this many blocks; 0 for none", float) = 0.8
}

struct v2f { vec2 uv; float opacity; vec2 light; vec3 tint; float seed; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        Blend ONE ONE_MINUS_SRC_ALPHA
        DepthTest LEQUAL
        DepthWrite OFF
        Cull OFF
    }

    void vertex(out v2f o) {
        CgMaterialVertex v = cg_material_vertex();
        CgMaterialParticle p = cg_material_particle();
        o.uv = v.texCoords[0];
        o.opacity = CG_PARTICLE_OPACITY(p.record);
        o.light = CG_PARTICLE_LIGHT(p.record);
        o.tint = p.color.rgb;
        o.seed = p.random;
        gl_Position = cg_material_project(v);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        cg_Light = i.light;
        vec2 d = i.uv * 2.0 - 1.0;
        float boil = textureLod(_Noise, vec3(i.uv * 0.6 + i.seed * 7.0, i.seed + CG_TIME * 0.05), 0.0).r;
        float puff = 1.0 - smoothstep(0.25, 1.0, length(d) + (boil - 0.5) * 0.6);
        float soft = fx_particle_soft(CG_SCENE_EYE_DEPTH(gl_FragCoord.xy / CG_RESOLUTION),
                cg_LinearEyeDepth(gl_FragCoord.z), _SoftDistance);
        float alpha = puff * i.opacity * _Color.a * FX_PRESENCE * soft;
        fragColor = vec4(_Color.rgb * i.tint * alpha, alpha);
    }
}
