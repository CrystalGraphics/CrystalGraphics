// A textured sprite: a frame of a flipbook sheet on a quad facing as the factory's _cg_Facing says (fx_particle.glsl's FX_FACE_*), lit by
// the world where it is and blended over what is behind: smoke, dust, leaves. A CgVfxQuads quad placed from its particle
// record (CgVfxFrame.particles, QUADS). _Color tints the sheet, its alpha a strength.
//
//     CgVfxLayer.of("crystalgraphics:shaders/vfx/particle/sprite.shader").on(PUFFS).bind("_Color", TINT)
//             .texture("_Sheet", SHEET).flipbook(CgVfxFlipbook.grid(8, 8)).set("_cg_Facing", 2f).build()
#type none
#pragma cg_use particle
#pragma cg_use rows
#include "crystalgraphics:shaders/lib/vfx/fx_common.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_ribbon.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_particle.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_draw.glsl"

Tags { "RenderType" = "Transparent" }
Queue = "Transparent"

Properties {
    _Color ("Tints the sheet; its alpha a strength", color) = (1, 1, 1, 1)
    _Sheet ("The flipbook sheet, straight alpha, frame 0 at the top left", sampler2D) = "white"
    _SoftDistance ("Fades into what is behind it over this many blocks; 0 for none", float) = 0.5
}

struct v2f { vec4 uv; float blend; float opacity; vec2 light; vec3 tint; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        Blend ONE ONE_MINUS_SRC_ALPHA
        DepthTest LEQUAL
        DepthWrite OFF
        Cull OFF
    }

    void vertex(out v2f o) {
        CgMaterialVertex v = cg_material_vertex();
        CgMaterialParticle p = cg_material_particle();
        o.uv = vec4(v.texCoords[0], v.texCoords[1]);
        o.blend = p.subUVLerp;
        o.opacity = CG_PARTICLE_OPACITY(p.record);
        o.light = CG_PARTICLE_LIGHT(p.record);
        o.tint = p.color.rgb;
        gl_Position = cg_material_project(v);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        cg_Light = i.light;
        vec4 texel = mix(texture(_Sheet, i.uv.xy), texture(_Sheet, i.uv.zw), i.blend);
        float soft = fx_particle_soft(CG_SCENE_EYE_DEPTH(gl_FragCoord.xy / CG_RESOLUTION),
                cg_LinearEyeDepth(gl_FragCoord.z), _SoftDistance);
        float alpha = texel.a * i.opacity * _Color.a * FX_PRESENCE * soft;
        fragColor = vec4(texel.rgb * _Color.rgb * i.tint * alpha, alpha);
    }
}
