// A scorch torn along the ground: what a dash skimming the earth or a beam raking it leaves. A flat trail
// (CgVfxTrail.flat, facing up) of burnt earth, darkest down its middle and ragged at its edges, with embers glowing in
// its cracks that are hottest just behind the thing and cool to nothing as it ages, the scorch itself lasting. Blended
// over the ground, lifted a touch toward the eye so it never fights it. The layer's size is its half-width in blocks.
#type none
#pragma cg_use rows
#include "crystalgraphics:shaders/lib/vfx/fx_common.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_trail.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_draw.glsl"

Tags { "RenderType" = "Transparent" }
Queue = "Transparent"

Properties {
    _Color    ("The burnt ground; its alpha how dark", color) = (0.05, 0.04, 0.035, 0.85)
    _Ember    ("Its embers", color) = (2.2, 0.75, 0.18, 1)
    _Hot      ("Its embers' hottest", color) = (2.4, 1.8, 0.9, 1)
    _Cool     ("How fast its embers cool, a second", float) = 1.6
    _Scale    ("Cracks a block", float) = 1.4
    _Lift     ("How far it is drawn toward the eye, blocks", float) = 0.03
    _Noise  ("Noise", sampler3D) = "cg_noise"
    _Voronoi ("Voronoi", sampler3D) = "cg_voronoi"
    _FxTrail ("Trail points", sampler2D) = "black"
}

struct v2f { vec4 trail; vec2 shape; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        Blend ONE ONE_MINUS_SRC_ALPHA
        DepthTest LEQUAL
        DepthWrite OFF
        Cull OFF
    }

    void vertex(out v2f o) {
        FxTrailVertex v = fx_trail_vertex(_FxTrail, CG_VERTEX_ID, CG_OBJECT_CUSTOM0, CG_OBJECT_CUSTOM2,
                CG_OBJECT_TO_WORLD[3].xyz - CG_OBJECT_CUSTOM1.xyz, FX_CAMERA);
        vec3 world = v.world;
        world += normalize(FX_CAMERA - world) * _Lift;
        o.trail = vec4(v.uv.x, v.side, v.along, v.age);
        o.shape = vec2(v.extra.y, v.alive);
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * vec4(world, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        if (i.shape.y < 0.5) discard;
        float u = i.trail.x, across = abs(i.trail.y), age = i.trail.w;
        vec3 p = vec3(i.trail.z * _Scale, i.trail.y * _Scale * 0.8, FX_SEED * 3.0);
        float ragged = 0.18 * fx_fbm(p * 1.6, 3);
        float edge = 1.0 - smoothstep(0.55 + ragged, 1.0 + ragged, across);
        float aa = fwidth(edge) + 1.0e-3;
        float burnt = smoothstep(0.0, aa * 2.0 + 0.15, edge) * (0.7 + 0.3 * (1.0 - across));
        // Its cracks, where embers lie: between voronoi cells.
        vec3 cells = fx_voronoi(p * 1.5);
        float crack = 1.0 - smoothstep(0.0, 0.08, cells.y - cells.x);
        float heat = exp(-age * _Cool) * edge;
        float ember = crack * heat * (0.6 + 0.4 * fx_noise(p * 3.0 + vec3(0.0, 0.0, FX_AGE * 2.0)));
        float alpha = burnt * _Color.a * pow(1.0 - u, 0.6) * i.shape.x * FX_PRESENCE;
        vec3 glow = mix(_Ember.rgb, _Hot.rgb, heat * heat) * ember * i.shape.x * FX_PRESENCE;
        fragColor = vec4(_Color.rgb * alpha + glow, alpha);
        CG_GLOW(glow);
    }
}

Pass {
    Tags { "LightMode" = "Emissive" }
    RenderState {
        Blend ONE ONE
        DepthTest LEQUAL
        DepthWrite OFF
        Cull OFF
    }
}
