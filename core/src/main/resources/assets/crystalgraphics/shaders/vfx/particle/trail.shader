// A trail: the ribbon CgVfxTrail writes, turned about its spine to face the eye, widest at the head and tapering to a
// point at the tail, white-hot down its middle. A vertex is its point, its tangent scaled to the half-width there, and
// (how far from the tail, side). CG_OBJECT_CUSTOM0: rgb its colour, a a strength.
#type spatial
#include "crystalgraphics:shaders/lib/vfx/fx_common.glsl"

Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" }
Queue = "Transparent"

struct v2f { vec2 trail; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        Blend ONE ONE
        DepthTest LEQUAL
        DepthWrite OFF
        Cull OFF
    }

    void vertex(out v2f o) {
        vec3 spine = (CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0)).xyz;
        vec3 tangent = mat3(CG_OBJECT_TO_WORLD) * cg_Normal;
        float halfWidth = length(tangent);
        vec3 across = cross(tangent, spine - FX_CAMERA);
        float reach = length(across);
        across = reach > 1.0e-6 ? across / reach : vec3(0.0, 1.0, 0.0);
        float along = cg_TexCoord0.x, side = cg_TexCoord0.y * 2.0 - 1.0;
        vec3 world = spine + across * side * halfWidth * sqrt(along);
        o.trail = vec2(along, side);
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * vec4(world, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        float edge = 1.0 - i.trail.y * i.trail.y;
        float core = pow(edge, 6.0);
        float fade = i.trail.x * i.trail.x;
        vec3 col = CG_OBJECT_CUSTOM0.rgb * edge + vec3(core);
        fragColor = vec4(col * fade * CG_OBJECT_CUSTOM0.a, 1.0);
    }
}

// Its light again, into the world's bloom: the Forward pass's code and state, blurred over the scene.
Pass { Tags { "LightMode" = "Emissive" } }
