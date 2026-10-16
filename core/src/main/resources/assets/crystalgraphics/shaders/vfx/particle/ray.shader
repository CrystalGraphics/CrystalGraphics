// A hot streak shooting out of an explosion: a straight tapered stroke of light along its particle's velocity, white-hot
// down its middle in a soft glow of its colour, brightest at its head and dying out down its tail. Stretched by its speed,
// so it shortens as the air brakes it down to _MinLength, and never reaching back past the emitter's source. A
// velocity-aligned streak, as Niagara's sprite renderer draws one, on a CgVfxRibbons stroke placed from its particle
// record (CgVfxFrame.particles, ARCS); the draw is centred on the source, so CG_OBJECT_TO_WORLD[3] is the source. _Color
// is the glow, its alpha a strength; _Hot the middle.
#type none
#pragma cg_use particle
#pragma cg_use rows
#include "crystalgraphics:shaders/lib/vfx/fx_common.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_ribbon.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_particle.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_draw.glsl"

Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" }
Queue = "Transparent"

Properties {
    _Color ("The glow; its alpha a strength", color) = (1, 0.55, 0.2, 1)
    _Hot ("The white-hot middle", color) = (1, 1, 1, 1)
    _Stretch ("Stroke length per block a second of speed", float) = 0.2
    _MinLength ("Shortest it draws however slow, blocks", float) = 1.2
    _Longest ("How many times its speed's length the longest few draw, over the shortest's 0.4", float) = 2.4
    _Bright ("Its light", float) = 2.5
}

struct v2f { vec3 world; vec3 stroke; vec3 tint; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        Blend ONE ONE
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
        // Most short, a few long: its length by its seed cubed.
        float seed = CG_PARTICLE_SEED(i), long3 = seed * seed * seed;
        float span = min(max(speed * _Stretch, _MinLength) * (0.4 + _Longest * long3), reach);
        vec3 p = head - dir * span * (1.0 - along);
        // Thickest just behind the head, a hair at the tail; the long ones a little wider.
        float taper = pow(sin(3.14159265 * pow(along, 1.6)), 0.7);
        float width = n < 0 ? 0.0 : CG_PARTICLE_SIZE(i) * CG_PARTICLE_SCALE(i).x * CG_OBJECT_CUSTOM0.z * taper * (0.7 + 0.6 * long3);
        vec3 world = fx_ribbon_vertex(p, dir, FX_CAMERA, width, side);
        o.world = world;
        o.stroke = vec3(side, along, CG_PARTICLE_OPACITY(i));
        o.tint = CG_PARTICLE_COLOR(i).rgb;
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * vec4(world, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        float across = abs(i.stroke.x), along = i.stroke.y;
        float glow = exp(-across * across * 3.0) * (1.0 - smoothstep(0.75, 1.0, across));
        float core = exp(-across * across * 40.0);
        // Dying out down its tail.
        float fade = along * along;
        vec3 col = (_Color.rgb * glow + _Hot.rgb * core) * fade * _Bright;
        fragColor = vec4(col * i.tint * i.stroke.z * _Color.a * FX_PRESENCE, 1.0);
    }
}

// Its light again, into the world's bloom: the Forward pass's code and state, blurred over the scene.
Pass { Tags { "LightMode" = "Emissive" } }
