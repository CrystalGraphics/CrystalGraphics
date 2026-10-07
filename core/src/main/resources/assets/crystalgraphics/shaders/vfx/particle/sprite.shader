// A textured sprite: a frame of a flipbook sheet on a quad facing as _Facing says (fx_particle.glsl's FX_FACE_*), lit by
// the world where it is and blended over what is behind: smoke, dust, leaves. A CgVfxQuads quad placed from its particle
// record (CgVfxFrame.particles, QUADS). Colour A tints the sheet, A's alpha a strength.
//
//     CgVfxLayer.builder("crystalgraphics:shaders/vfx/particle/sprite.shader").slot(puffs.layer()).colors(TINT, TINT)
//             .properties(b -> b.sampler("_Sheet", 0, sheet).vec4("_Frames", 8f, 8f, 64f, 1f).set1f("_Facing", 2f))
#type none
#pragma cg_use particle
#include "crystalgraphics:shaders/lib/vfx/fx_common.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_ribbon.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_particle.glsl"

Tags { "RenderType" = "Transparent" }
Queue = "Transparent"

Properties {
    _Sheet ("The flipbook sheet, straight alpha, frame 0 at the top left", sampler2D) = "white"
    _Frames ("Columns, rows, frames, cycles over a life", vec4) = (1, 1, 1, 1)
    _Flip ("Start-frame spread by seed (0..1), loops, blends frames", vec4) = (0, 1, 1, 0)
    _Facing ("FX_FACE_*: camera, camera position, upright, velocity, axis", float) = 0
    _Axis ("The AXIS mode's normal", vec4) = (0, 1, 0, 0)
    _Stretch ("VELOCITY mode: how much longer per block a second", float) = 0.1
    _MaxStretch ("VELOCITY mode: longest, in widths", float) = 4.0
}

struct v2f { vec4 uv; float blend; float opacity; vec2 light; };

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
        vec3 eyeRight = vec3(cg_ViewMatrix[0][0], cg_ViewMatrix[1][0], cg_ViewMatrix[2][0]);
        vec3 eyeUp = vec3(cg_ViewMatrix[0][1], cg_ViewMatrix[1][1], cg_ViewMatrix[2][1]);
        vec3 eye = -(transpose(mat3(cg_ViewMatrix)) * cg_ViewMatrix[3].xyz);
        int i = max(n, 0);
        vec3 centre = origin + CG_PARTICLE_POSITION(i);
        vec3 velocity = CG_PARTICLE_VELOCITY(i);
        float size = n < 0 ? 0.0 : CG_PARTICLE_SIZE(i) * CG_OBJECT_CUSTOM0.z;
        int mode = int(_Facing + 0.5);
        vec3 right, up;
        fx_particle_facing(mode, centre, velocity, _Axis.xyz, eye, eyeRight, eyeUp, right, up);
        bool streak = mode == FX_FACE_VELOCITY;
        float length_ = streak ? min(1.0 + length(velocity) * _Stretch, _MaxStretch) : 1.0;
        vec3 world = fx_particle_corner(centre, corner, vec2(size * length_, size), streak ? 0.0 : CG_PARTICLE_SPIN(i),
                right, up);
        float blend;
        o.uv = fx_particle_flipbook(corner * 0.5 + 0.5, CG_PARTICLE_PROGRESS(i), CG_PARTICLE_SEED(i) * _Flip.x, _Frames,
                _Flip.y > 0.5, blend);
        o.blend = _Flip.z > 0.5 ? blend : 0.0;
        o.opacity = CG_PARTICLE_OPACITY(i);
        o.light = CG_PARTICLE_LIGHT(i);
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * vec4(world, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        cg_Light = i.light;
        vec4 texel = mix(texture(_Sheet, i.uv.xy), texture(_Sheet, i.uv.zw), i.blend);
        float alpha = texel.a * i.opacity * CG_OBJECT_CUSTOM2.a;
        fragColor = vec4(texel.rgb * CG_OBJECT_CUSTOM2.rgb * alpha, alpha);
    }
}
