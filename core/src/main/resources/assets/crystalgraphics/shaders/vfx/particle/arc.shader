// An ink streak, as anime draws an explosion's shock: a dark tapered stroke, an arc of the circle round the emitter's
// source through its particle, in the eye's plane, so it flies out as the particle does and thins as its size curve
// falls. A CgVfxRibbons stroke placed from its particle record (CgVfxFrame.particles, ARCS); the draw is centred on the
// source, so CG_OBJECT_TO_WORLD[3] is the source. Colour A is the ink, A's alpha a strength.
#type none
#pragma cg_use particle
#include "crystalgraphics:shaders/lib/vfx/fx_common.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_ribbon.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_particle.glsl"

Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" }
Queue = "Transparent"

Properties {
    _Span ("Arc a stroke spans, radians, before its seed varies it", float) = 0.9
}

struct v2f { vec3 world; vec2 stroke; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        Blend ONE ONE_MINUS_SRC_ALPHA
        DepthTest LEQUAL
        DepthWrite OFF
        Cull OFF
    }

    void vertex(out v2f o) {
        int n = fx_particle_index(FX_RIBBON_INDEX, CG_OBJECT_CUSTOM0.x, CG_OBJECT_CUSTOM0.y);
        float along = FX_RIBBON_ALONG, side = FX_RIBBON_SIDE;
        vec3 source = CG_OBJECT_TO_WORLD[3].xyz;
        vec3 origin = source - CG_OBJECT_CUSTOM1.xyz;
        vec3 right = vec3(cg_ViewMatrix[0][0], cg_ViewMatrix[1][0], cg_ViewMatrix[2][0]);
        vec3 up = vec3(cg_ViewMatrix[0][1], cg_ViewMatrix[1][1], cg_ViewMatrix[2][1]);
        int i = max(n, 0);
        vec3 offset = origin + CG_PARTICLE_POSITION(i) - source;
        // The circle round the source through the particle, in the eye's plane.
        float radius = length(vec2(dot(offset, right), dot(offset, up)));
        float centreAngle = atan(dot(offset, up), dot(offset, right) + 1.0e-6);
        float span = _Span * (0.6 + 0.8 * CG_PARTICLE_SEED(i));
        float a = centreAngle + span * (along - 0.5);
        vec3 p = source + (right * cos(a) + up * sin(a)) * radius;
        vec3 tangent = right * -sin(a) + up * cos(a);
        float taper = pow(sin(3.14159265 * along), 0.8);
        float width = n < 0 ? 0.0 : CG_PARTICLE_SIZE(i) * CG_OBJECT_CUSTOM0.z * taper;
        vec3 world = fx_ribbon_vertex(p, tangent, FX_CAMERA, width, side);
        o.world = world;
        o.stroke = vec2(side, CG_PARTICLE_OPACITY(i));
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * vec4(world, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        float across = abs(i.stroke.x);
        float aa = fwidth(across) + 1.0e-3;
        float alpha = (1.0 - smoothstep(1.0 - 2.0 * aa, 1.0, across)) * i.stroke.y * CG_OBJECT_CUSTOM2.a;
        fragColor = vec4(CG_OBJECT_CUSTOM2.rgb * alpha, alpha);
    }
}
