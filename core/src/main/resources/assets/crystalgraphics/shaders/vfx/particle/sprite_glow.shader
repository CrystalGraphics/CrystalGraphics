// A glowing textured sprite: sprite.shader's flipbook frame and facing, added and unlit, as bright as the particle is
// hot: fire, magic, energy. A CgVfxQuads quad placed from its particle record (CgVfxFrame.particles, QUADS). _Color
// tints the cool sheet, its alpha a strength; _Hot the hot.
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
    _Color ("Tints the sheet where it is cool; its alpha a strength", color) = (1, 0.6, 0.2, 1)
    _Hot ("Tints the sheet where it is hot", color) = (1, 1, 0.9, 1)
    _Sheet ("The flipbook sheet, straight alpha, frame 0 at the top left", sampler2D) = "white"
    _SubImage ("CgVfxFlipbook: columns (a table's frames), rows (0: a table), blends, random start", vec4) = (1, 1, 1, 0)
    _SubUV ("CgVfxFlipbook: mode, first frame, last frame (-1: the last), rate", vec4) = (0, 0, -1, 0)
    _SubImages ("CgVfxFlipbook: a rect table, a rect a texel", sampler2D) = "white"
    _Facing ("FX_FACE_*: camera, camera position, upright, velocity, axis", float) = 0
    _Axis ("The AXIS mode's normal", vec4) = (0, 1, 0, 0)
    _Stretch ("VELOCITY mode: how much longer per block a second", float) = 0.1
    _MaxStretch ("VELOCITY mode: longest, in widths", float) = 4.0
    _SoftDistance ("Fades into what is behind it over this many blocks; 0 for none", float) = 0.25
    _CameraOffset ("Drawn this many of its sizes toward the eye: a solid piece resting on a surface", float) = 0
}

struct v2f { vec4 uv; float blend; vec2 glow; vec3 tint; };

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
        vec3 eyeRight = vec3(cg_ViewMatrix[0][0], cg_ViewMatrix[1][0], cg_ViewMatrix[2][0]);
        vec3 eyeUp = vec3(cg_ViewMatrix[0][1], cg_ViewMatrix[1][1], cg_ViewMatrix[2][1]);
        vec3 eye = -(transpose(mat3(cg_ViewMatrix)) * cg_ViewMatrix[3].xyz);
        int i = max(n, 0);
        vec3 velocity = CG_PARTICLE_VELOCITY(i);
        float size = n < 0 ? 0.0 : CG_PARTICLE_SIZE(i) * CG_OBJECT_CUSTOM0.z, keep;
        vec3 centre = fx_particle_toward_eye(origin + CG_PARTICLE_POSITION(i), eye, _CameraOffset * size, keep);
        size *= keep;
        int mode = int(_Facing + 0.5);
        vec3 right, up;
        fx_particle_facing(mode, centre, velocity, _Axis.xyz, eye, eyeRight, eyeUp, right, up);
        bool streak = mode == FX_FACE_VELOCITY;
        float length_ = streak ? min(1.0 + length(velocity) * _Stretch, _MaxStretch) : 1.0;
        vec2 extent = size * CG_PARTICLE_SCALE(i).xy * vec2(length_, 1.0);
        vec3 world = fx_particle_corner(centre, corner, extent, streak ? 0.0 : CG_PARTICLE_SPIN(i), right, up);
        float a, b;
        fx_subuv_frames(_SubUV, _SubImage, CG_PARTICLE_AGE(i), CG_PARTICLE_PROGRESS(i), CG_PARTICLE_SEED(i), a, b, o.blend);
        o.uv = vec4(fx_subimage_uv(_SubImages, _SubImage, a, corner), fx_subimage_uv(_SubImages, _SubImage, b, corner));
        o.glow = vec2(CG_PARTICLE_HEAT(i), CG_PARTICLE_OPACITY(i));
        o.tint = CG_PARTICLE_COLOR(i).rgb;
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * vec4(world, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        vec4 texel = mix(texture(_Sheet, i.uv.xy), texture(_Sheet, i.uv.zw), i.blend);
        vec3 tint = mix(_Color.rgb, _Hot.rgb, i.glow.x) * i.tint;
        float soft = fx_particle_soft(CG_SCENE_EYE_DEPTH(gl_FragCoord.xy / CG_RESOLUTION),
                cg_LinearEyeDepth(gl_FragCoord.z), _SoftDistance);
        float strength = texel.a * i.glow.y * _Color.a * FX_PRESENCE * (0.35 + 0.65 * i.glow.x) * soft;
        fragColor = vec4(texel.rgb * tint * strength, 1.0);
    }
}

Pass { Tags { "LightMode" = "Emissive" } }
