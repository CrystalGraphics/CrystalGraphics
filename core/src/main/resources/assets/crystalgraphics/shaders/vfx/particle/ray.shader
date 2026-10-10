// An ink spike shooting out of an explosion: a straight tapered stroke along its particle's velocity, stretched by its
// speed, so it shortens as the air brakes it down to _MinLength, and never reaching back past the emitter's source. A
// velocity-aligned streak, as Niagara's sprite renderer draws one, on a CgVfxRibbons stroke placed from its particle
// record (CgVfxFrame.particles, ARCS); the draw is centred on the source, so CG_OBJECT_TO_WORLD[3] is the source. Colour
// A is the ink, A's alpha a strength.
#type none
#pragma cg_use particle
#include "crystalgraphics:shaders/lib/vfx/fx_common.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_ribbon.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_particle.glsl"

Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" "Depth" = "Clip" }
Queue = "Transparent"

Properties {
    _Stretch ("Stroke length per block a second of speed", float) = 0.5
    _MinLength ("Shortest it draws however slow, blocks", float) = 3.0
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
        int i = max(n, 0);
        vec3 head = origin + CG_PARTICLE_POSITION(i);
        vec3 velocity = CG_PARTICLE_VELOCITY(i), outward = head - source;
        float speed = length(velocity), reach = length(outward);
        vec3 dir = speed > 1.0e-4 ? velocity / speed : outward / max(reach, 1.0e-4);
        float span = min(max(speed * _Stretch, _MinLength) * (0.7 + 0.6 * CG_PARTICLE_SEED(i)), reach);
        vec3 p = head - dir * span * (1.0 - along);
        // Thickest just behind the head, a hair at the tail.
        float taper = pow(sin(3.14159265 * pow(along, 1.6)), 0.7);
        float width = n < 0 ? 0.0 : CG_PARTICLE_SIZE(i) * CG_OBJECT_CUSTOM0.z * taper;
        vec3 world = fx_ribbon_vertex(p, dir, FX_CAMERA, width, side);
        o.world = world;
        o.stroke = vec2(side, CG_PARTICLE_OPACITY(i));
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * vec4(world, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        float across = abs(i.stroke.x);
        float aa = fwidth(across) + 1.0e-3;
        // Solid ink: it writes depth wherever it covers half a pixel, so what is behind it is hidden.
        float cover = 1.0 - smoothstep(1.0 - 2.0 * aa, 1.0, across);
        cg_Clip(cover);
        float alpha = cover * i.stroke.y * CG_OBJECT_CUSTOM2.a;
        fragColor = vec4(CG_OBJECT_CUSTOM2.rgb * alpha, alpha);
    }
}
