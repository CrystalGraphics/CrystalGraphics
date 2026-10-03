// A debris speck: a small dark chunk, sliver or shard, each its own stretch and raggedness from its seed, turned by its
// spin, lit by the world where it is. A CgVfxQuads quad placed from its particle record (CgVfxFrame.particles, QUADS).
// Colour A is the specks, A's alpha a strength.
#type none
#pragma cg_use particle
#include "crystalgraphics:shaders/lib/vfx/fx_common.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_ribbon.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_particle.glsl"

Tags { "RenderType" = "Transparent" }
Queue = "Transparent"

struct v2f { vec3 world; vec4 speck; float opacity; vec2 light; };

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
        vec4 m = fx_hash41(seed * 97.0);
        // From chunks to slivers.
        float size = n < 0 ? 0.0 : CG_PARTICLE_SIZE(i) * CG_OBJECT_CUSTOM0.z;
        vec2 extent = vec2(size, size * mix(0.25, 1.0, m.x * m.x));
        vec3 world = fx_particle_corner(origin + CG_PARTICLE_POSITION(i), corner, extent, CG_PARTICLE_SPIN(i), right, up);
        o.world = world;
        // where on it, its shape seed, how spiky its edge is
        o.speck = vec4(corner, seed * 40.0, mix(1.2, 4.0, m.y));
        o.opacity = CG_PARTICLE_OPACITY(i);
        o.light = CG_PARTICLE_LIGHT(i);
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * vec4(world, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        cg_Light = i.light;
        vec2 q = i.speck.xy;
        float r = length(q);
        // A ragged chunk: its edge wanders with the angle, lumpy at low frequency and spiky at high.
        vec2 around = q / max(r, 1.0e-4);
        float edge = 0.5 + 0.48 * fx_value_noise(vec3(around * i.speck.w, i.speck.z));
        float aa = fwidth(r) + 1.0e-3;
        float alpha = (1.0 - smoothstep(edge - aa, edge + aa, r)) * i.opacity * CG_OBJECT_CUSTOM2.a;
        fragColor = vec4(CG_OBJECT_CUSTOM2.rgb * alpha, alpha);
    }
}
