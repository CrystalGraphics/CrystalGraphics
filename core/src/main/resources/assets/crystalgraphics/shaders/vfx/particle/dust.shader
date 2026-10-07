// A puff of dust a landing kicks up: a soft, lumpy disc that swells and thins as it settles, lit by the world where it
// is. A CgVfxQuads quad placed from its particle record (CgVfxFrame.particles, QUADS). Colour A is the dust, A's alpha
// a strength.
#type none
#pragma cg_use particle
#include "crystalgraphics:shaders/lib/vfx/fx_common.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_ribbon.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_particle.glsl"

Tags { "RenderType" = "Transparent" }
Queue = "Transparent"

Properties {
    _ValueNoise ("Value noise", sampler3D) = "cg_value_noise"
    _SoftDistance ("Fades into what is behind it over this many blocks; 0 for none", float) = 0.3
}

struct v2f { vec3 world; vec4 puff; float opacity; vec2 light; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        Blend ONE ONE_MINUS_SRC_ALPHA
        DepthTest LEQUAL
        DepthWrite OFF
        Cull OFF
    }

    void vertex(out v2f o) {
        int n = fx_particle_index(FX_QUAD_INDEX, CG_OBJECT_CUSTOM0.x, CG_OBJECT_CUSTOM0.y);
        vec2 corner = FX_QUAD_CORNER;
        vec3 origin = CG_OBJECT_TO_WORLD[3].xyz - CG_OBJECT_CUSTOM1.xyz;
        vec3 right = vec3(cg_ViewMatrix[0][0], cg_ViewMatrix[1][0], cg_ViewMatrix[2][0]);
        vec3 up = vec3(cg_ViewMatrix[0][1], cg_ViewMatrix[1][1], cg_ViewMatrix[2][1]);
        int i = max(n, 0);
        float seed = CG_PARTICLE_SEED(i);
        float size = n < 0 ? 0.0 : CG_PARTICLE_SIZE(i) * CG_OBJECT_CUSTOM0.z;
        vec3 world = fx_particle_corner(origin + CG_PARTICLE_POSITION(i), corner, vec2(size), CG_PARTICLE_SPIN(i), right, up);
        o.world = world;
        // where on it, its shape seed, how far through its life
        o.puff = vec4(corner, seed * 40.0, CG_PARTICLE_PROGRESS(i));
        o.opacity = CG_PARTICLE_OPACITY(i);
        o.light = CG_PARTICLE_LIGHT(i);
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * vec4(world, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        cg_Light = i.light;
        vec2 q = i.puff.xy;
        float r = length(q);
        // Lumps round the edge and holes through the middle that open as it thins.
        vec2 around = q / max(r, 1.0e-4);
        float edge = 0.72 + 0.24 * fx_value_noise(vec3(around * 2.5, i.puff.z));
        float body = 1.0 - smoothstep(edge * 0.3, edge, r);
        float holes = smoothstep(i.puff.w * 0.8, i.puff.w * 0.8 + 0.35, fx_value_noise(vec3(q * 1.5, i.puff.z + 7.0)));
        float soft = fx_particle_soft(CG_SCENE_EYE_DEPTH(gl_FragCoord.xy / CG_RESOLUTION),
                cg_LinearEyeDepth(gl_FragCoord.z), _SoftDistance);
        float alpha = body * mix(1.0, holes, 0.6) * i.opacity * CG_OBJECT_CUSTOM2.a * soft;
        fragColor = vec4(CG_OBJECT_CUSTOM2.rgb * alpha, alpha);
    }
}
