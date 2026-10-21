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
    _SoftDistance ("Fades into what is behind it over this many blocks; 0 for none", float) = 0.25
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
        CgMaterialVertex v = cg_material_vertex();
        CgMaterialParticle p = cg_material_particle();
        o.uv = vec4(v.texCoords[0], v.texCoords[1]);
        o.blend = p.subUVLerp;
        o.glow = vec2(CG_PARTICLE_HEAT(p.record), CG_PARTICLE_OPACITY(p.record));
        o.tint = p.color.rgb;
        gl_Position = cg_material_project(v);
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
