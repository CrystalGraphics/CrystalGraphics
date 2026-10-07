// A glowing spark or ember: a white-hot disc in a soft bloom of its colour, drawn out along its motion the faster it
// goes, as bright as it is hot, so an ember dims as it cools. A CgVfxQuads quad placed from its particle record
// (CgVfxFrame.particles, QUADS). Colour A is the bloom, colour B the hot centre, A's alpha a strength.
#type none
#pragma cg_use particle
#include "crystalgraphics:shaders/lib/vfx/fx_common.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_ribbon.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_particle.glsl"

Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" }
Queue = "Transparent"

Properties {
    _Stretch ("How much longer a spark draws per block a second it moves", float) = 0.12
    _MaxStretch ("Longest a streak gets, in its widths", float) = 4.0
    _CameraOffset ("Drawn this many of its sizes toward the eye, so one resting on a surface is not cut by it", float) = 1
}

struct v2f { vec3 world; vec3 spark; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        Blend ONE ONE
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
        vec3 velocity = CG_PARTICLE_VELOCITY(i);
        vec3 eye = -(transpose(mat3(cg_ViewMatrix)) * cg_ViewMatrix[3].xyz);
        float size = n < 0 ? 0.0 : CG_PARTICLE_SIZE(i) * CG_OBJECT_CUSTOM0.z, keep;
        vec3 centre = fx_particle_toward_eye(origin + CG_PARTICLE_POSITION(i), eye, _CameraOffset * size, keep);
        size *= keep;
        // Drawn out along its motion in the eye's plane, the faster the longer.
        float onScreen = length(vec2(dot(velocity, right), dot(velocity, up)));
        float streak = min(1.0 + onScreen * _Stretch, _MaxStretch);
        vec2 extent = vec2(size * streak, size);
        float angle = fx_particle_heading(velocity, right, up);
        vec3 world = fx_particle_corner(centre, corner, extent, angle, right, up);
        float glow = (0.35 + 0.65 * CG_PARTICLE_HEAT(i)) * CG_PARTICLE_OPACITY(i);
        o.world = world;
        o.spark = vec3(corner, glow);
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * vec4(world, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        vec2 q = i.spark.xy;
        float r = length(q);
        float aa = fwidth(r) + 1.0e-3;
        // A round disc, white-hot in the middle, in a wide soft bloom.
        float disc = 1.0 - smoothstep(0.26 - aa, 0.26 + aa, r);
        float bloom = exp(-r * r * 9.0) * (1.0 - smoothstep(0.7, 1.0, r));
        vec3 col = mix(CG_OBJECT_CUSTOM2.rgb, CG_OBJECT_CUSTOM3.rgb, exp(-r * r * 60.0)) * disc * 1.8
                 + CG_OBJECT_CUSTOM2.rgb * bloom * 0.9;
        fragColor = vec4(col * i.spark.z * CG_OBJECT_CUSTOM2.a, 1.0);
    }
}

// Its light again, into the world's bloom: the Forward pass's code and state, blurred over the scene.
Pass { Tags { "LightMode" = "Emissive" } }
